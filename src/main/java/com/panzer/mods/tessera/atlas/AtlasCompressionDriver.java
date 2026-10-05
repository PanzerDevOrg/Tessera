package com.panzer.mods.tessera.atlas;

import com.panzer.mods.tessera.compat.TesseraCompat;
import com.panzer.mods.tessera.cache.AtlasCache;
import com.panzer.mods.tessera.compress.Bc1TextureFormatSupport;
import com.panzer.mods.tessera.compress.Bc7GpuSupport;
import com.panzer.mods.tessera.compress.CompressionPipeline;
import com.panzer.mods.tessera.compress.backend.TesseraRuntime;
import com.panzer.mods.tessera.config.Config;
import com.panzer.mods.tessera.gui.DebugOverlay;
import com.panzer.mods.tessera.vram.VramBudgetEngine;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.opengl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BC1/BC7 compression and upload for one atlas texture.
 *
 * <p>Two phases, deliberately separate: {@link #compress} does CPU work only
 * (mip chain, disk cache, encoding) and is safe on a background thread;
 * {@link #upload} issues the GL calls and must run on the render thread.
 * Encoding a full atlas takes long enough that doing it on the render thread
 * would stall frames during every resource reload.
 */
@SuppressWarnings("JavadocReference")
public final class AtlasCompressionDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/AtlasCompressionDriver");

    private static volatile AtlasCache cacheInstance;

    private AtlasCompressionDriver() {
    }

    /**
     * Assembles one contiguous RGBA8 buffer for an entire atlas from its
     * stitched sprites' pixel data, placed at each sprite's atlas position
     * ({@code TextureAtlasSprite.getX()/getY()}), and logs how many sprites are
     * perceptual near-duplicates of another (see {@code NativeFamilyDetector}).
     *
     * @param label       for logging only (e.g. {@code "OPAQUE"}/{@code "ALPHA"})
     * @param regions     every sprite placed in this atlas by its own stitch pass
     * @param atlasWidth  the atlas's own packed width (from {@code Preparations.width()})
     * @param atlasHeight the atlas's own packed height (from {@code Preparations.height()})
     * @return the assembled RGBA8 buffer, or {@code null} if native dedup
     * detection is unavailable and the caller should fall back to
     * an uncompressed upload for this atlas
     */
    @SuppressWarnings("resource")
    public static ByteBuffer assembleAtlasBuffer(
            String label, Collection<TextureAtlasSprite> regions, int atlasWidth, int atlasHeight
    ) {

        // Guard mirrors compress(): don't cross the JNI boundary, and don't pay for the
        // pixel-buffer allocation/copy below, when the bridge never loaded (or is disabled).
        // Without this check, NativeFamilyDetector.detect() calls straight into
        // NativeBridge.detectFamiliesAndAssemble and throws UnsatisfiedLinkError instead
        // of the clean vanilla-atlas fallback this method's own docstring promises.
        // No native-availability gate here: TesseraRuntime already falls back to the
        // software encoder, and gating on native would disable that fallback entirely.
        if (Config.DISABLE_NATIVE_COMPRESSION.get()) {
            return null;
        }

        long totalPixelBytes = 0L;
        int[] widths = new int[regions.size()];
        int[] heights = new int[regions.size()];
        int[] srcOffsets = new int[regions.size()];
        int[] destX = new int[regions.size()];
        int[] destY = new int[regions.size()];

        // Each sprite's region in the atlas: its pixels, plus (1.21.11+) a padding
        // ring that repeats its edge pixels, as vanilla's animate_sprite blit draws it.
        int[] paddings = new int[regions.size()];
        int index = 0;
        for (TextureAtlasSprite sprite : regions) {
            int padding = TesseraCompat.padding(sprite);
            int spriteWidth = sprite.contents().width() + 2 * padding;
            int spriteHeight = sprite.contents().height() + 2 * padding;
            paddings[index] = padding;
            widths[index] = spriteWidth;
            heights[index] = spriteHeight;
            srcOffsets[index] = (int) totalPixelBytes;
            destX[index] = sprite.getX();
            destY[index] = sprite.getY();
            totalPixelBytes += (long) spriteWidth * spriteHeight * 4;
            index++;
        }

        ByteBuffer pixels = ByteBuffer.allocateDirect((int) totalPixelBytes).order(ByteOrder.LITTLE_ENDIAN);
        List<NativeFamilyDetector.SpriteInput> spriteInputs = new ArrayList<>(regions.size());

        index = 0;
        for (TextureAtlasSprite sprite : regions) {
            int spriteWidth = widths[index];
            int spriteHeight = heights[index];

            int padding = paddings[index];
            int maxX = sprite.contents().width() - 1;
            int maxY = sprite.contents().height() - 1;
            pixels.position(srcOffsets[index]);
            for (int y = 0; y < spriteHeight; y++) {
                for (int x = 0; x < spriteWidth; x++) {
                    int sx = Math.max(0, Math.min(maxX, x - padding));
                    int sy = Math.max(0, Math.min(maxY, y - padding));
                    pixels.putInt(TesseraCompat.spriteAbgr(sprite, 0, sx, sy));
                }
            }

            spriteInputs.add(new NativeFamilyDetector.SpriteInput(
                    srcOffsets[index], spriteWidth, spriteHeight, destX[index], destY[index], false));
            index++;
        }
        pixels.rewind();

        NativeFamilyDetector.DetectionResult result = NativeFamilyDetector.detect(
                pixels, spriteInputs, atlasWidth, atlasHeight, Config.DEDUP_SIMILARITY_THRESHOLD.get());
        if (result == null) {
            return null;
        }

        int duplicateCount = result.families().size() < spriteInputs.size()
                ? spriteInputs.size() - result.families().size()
                : 0;
        if (duplicateCount > 0) {
            LOGGER.info("{} of {} sprites in the {} atlas are perceptual near-duplicates of another sprite in this atlas.",
                    duplicateCount, spriteInputs.size(), label);
        }

        return result.atlasBuffer();
    }

    /**
     * Upper-bound byte estimate for an atlas's <em>full</em> mip chain (base
     * level down to 1x1). A full chain never exceeds 4/3 of the base level,
     * since {@code sum(1/4^i)} converges to {@code 4/3}.
     */
    public static long estimateFullMipChainBytes(int width, int height, CompressionPipeline.Target target, int maxLevel) {
        long total = 0L;
        int levelWidth = width;
        int levelHeight = height;
        // Mirrors MipChainBuilder/the native build_mip_chain's actual level
        // progression (halve-and-floor-to-1, stop once both dims hit 1) up
        // to maxLevel, rather than assuming the full geometric series down
        // to 1x1 regardless of how many levels will actually be requested
        // -- the previous (baseLevelBytes * 4) / 3 shortcut always assumed
        // an unbounded chain, which could overestimate VRAM cost enough to
        // reject an atlas that would have comfortably fit at its actual
        // requested level count.
        for (int level = 0; level <= maxLevel; level++) {
            int alignedWidth = (levelWidth + 3) & ~3;
            int alignedHeight = (levelHeight + 3) & ~3;
            total += (long) (alignedWidth / 4) * (alignedHeight / 4) * target.bytesPerBlock();
            if (levelWidth <= 1 && levelHeight <= 1) {
                break;
            }
            levelWidth = Math.max(levelWidth / 2, 1);
            levelHeight = Math.max(levelHeight / 2, 1);
        }
        return total;
    }

    /**
     * CPU-only compression phase: builds the mip chain, then for each level
     * either loads it from the disk cache or encodes it (native encoder when
     * available, pure-Java otherwise). No GL calls; safe on any thread.
     * Hand the result to {@link #upload} on the render thread.
     *
     * @return a {@link CompressedAtlas} with zero or more levels (zero levels
     * means compression was skipped; the caller keeps the uncompressed atlas)
     */
    public static CompressedAtlas compress(
            String atlasLocation, CompressionPipeline.Target target,
            ByteBuffer baseRgba8, int baseWidth, int baseHeight, int requestedMaxLevel
    ) {
        return compress(atlasLocation, target, baseRgba8, baseWidth, baseHeight, requestedMaxLevel, Integer.MAX_VALUE);
    }

    /**
     * @param retainRgbaFromLevel mip levels at or above this index also keep their
     *                            RGBA pixels in the result (see
     *                            {@link CompressedAtlas#retainedRgba}); pass
     *                            {@link Integer#MAX_VALUE} to retain nothing
     */
    @SuppressWarnings("LoggingSimilarMessage")
    public static CompressedAtlas compress(
            String atlasLocation, CompressionPipeline.Target target,
            ByteBuffer baseRgba8, int baseWidth, int baseHeight, int requestedMaxLevel, int retainRgbaFromLevel
    ) {
        if (target == CompressionPipeline.Target.BC7 && !Bc7GpuSupport.isSupported()) {
            LOGGER.info("[Tessera coverage] Atlas {} SKIPPED (BC7 unsupported on this GPU).", atlasLocation);
            return new CompressedAtlas(atlasLocation, target, List.of(), requestedMaxLevel);
        }

        long fullChainEstimateBytes = estimateFullMipChainBytes(baseWidth, baseHeight, target, requestedMaxLevel);
        if (!VramBudgetEngine.isWithinBudget(atlasLocation.toString(), fullChainEstimateBytes)) {
            LOGGER.warn("Atlas {} (full mip chain estimate) exceeds VRAM budget target. Falling back to uncompressed RGBA for this atlas.",
                    atlasLocation);
            LOGGER.info("[Tessera coverage] Atlas {} SKIPPED (exceeds VRAM budget).", atlasLocation);
            return new CompressedAtlas(atlasLocation, target, List.of(), requestedMaxLevel);
        }

        List<MipChainBuilder.MipLevel> mipLevels = MipChainBuilder.build(baseRgba8, baseWidth, baseHeight, requestedMaxLevel);
        if (mipLevels == null || mipLevels.isEmpty()) {
            // Native mip-chain generation unavailable/failed entirely --
            // fall back to compressing level 0 only.
            mipLevels = List.of(new MipChainBuilder.MipLevel(baseWidth, baseHeight, baseRgba8));
        }

        // Synchronous on the caller's thread: the split-atlas path already runs this
        // off the render thread, and the native encoder parallelises each level
        // internally, so a per-call pool plus queue would only add overhead.
        List<CompressedLevel> compressedLevels = new ArrayList<>(mipLevels.size());
        int quality = Config.COMPRESSION_QUALITY.get();
        boolean bc7 = target == CompressionPipeline.Target.BC7;
        String key = atlasLocation.toString();
        AtlasCache cache = cache();
        AtlasCache.CompressedFormat format = bc7 ? AtlasCache.CompressedFormat.BC7 : AtlasCache.CompressedFormat.BC1;
        // Backend tag: software output must not be served forever once native becomes available.
        String backendTag = TesseraRuntime.isNativeActive() ? "n" : "s";
        int cacheHits = 0;

        for (int level = 0; level < mipLevels.size(); level++) {
            MipChainBuilder.MipLevel mipLevel = mipLevels.get(level);
            int width = mipLevel.width();
            int height = mipLevel.height();
            // Clamp so a short buffer reaches compressBlocking's validation instead of throwing here.
            int rgbaBytes = Math.min(width * height * 4, mipLevel.rgba8().remaining());
            // Key on the input pixels per level, so any mip-filter change also invalidates.
            String cacheKey = cache.hashHex(mipLevel.rgba8(), rgbaBytes) + "-q" + quality + backendTag + CACHE_VERSION;

            ByteBuffer blocks = readCached(cache, cacheKey, format, width, height, quality);
            if (blocks != null) {
                cacheHits++;
            } else {
                blocks = CompressionPipeline.compressBlocking(key, mipLevel.rgba8(), width, height, bc7, quality);
                if (blocks == null) {
                    // Keep the levels already produced; upload() caps MAX_LEVEL accordingly.
                    break;
                }
                try {
                    cache.write(cacheKey, format, width, height, quality, blocks);
                } catch (IOException | RuntimeException e) {
                    LOGGER.debug("Failed writing cache entry {} for atlas {}", cacheKey, atlasLocation, e);
                }
            }
            compressedLevels.add(new CompressedLevel(level, width, height, blocks));
        }
        if (cacheHits > 0) {
            LOGGER.info("Atlas {}: {} of {} mip level(s) served from disk cache.", atlasLocation, cacheHits, compressedLevels.size());
        }

        Map<Integer, MipChainBuilder.MipLevel> retained = new HashMap<>();
        for (int level = Math.max(0, retainRgbaFromLevel); level < mipLevels.size(); level++) {
            retained.put(level, mipLevels.get(level));
        }
        return new CompressedAtlas(atlasLocation, target, compressedLevels, requestedMaxLevel, Map.copyOf(retained));
    }

    /**
     * Render-thread-only upload phase: takes a {@link CompressedAtlas}
     * already built by {@link #compress} (on a background thread) and
     * issues the actual {@code glCompressedTexImage2D} calls. This is the
     * only part of the pipeline that touches GL and therefore the only
     * part that must run on the render thread.
     *
     * @return total resident bytes across all uploaded levels, or
     * {@code -1} if not even the base level could be uploaded (or
     * {@code compressed} had zero levels to begin with) -- the
     * caller should leave the atlas on its existing uncompressed
     * upload in that case
     */
    public static long upload(int textureId, CompressedAtlas compressed) {
        if (compressed.levels().isEmpty()) {
            return -1;
        }

        // Per-atlas GL debug group: every GL call until the matching pop, and
        // any async debug message the driver emits for it, is tagged with this
        // atlas's location, so upload errors are attributable in the log.
        // Harmless on drivers that ignore debug groups.
        String debugGroupLabel = "tessera:upload:" + compressed.atlasLocation();
        GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0, debugGroupLabel);
        try {
            long resident = tessera$uploadInner(textureId, compressed);
            // -1 (nothing uploaded) clears the entry: the atlas stays vanilla RGBA.
            VramBudgetEngine.recordResident(compressed.atlasLocation(), resident);
            return resident;
        } finally {
            GL43.glPopDebugGroup();

            // One-shot, non-looping poll -- deliberately NOT the flood-
            // amplifying spin loop the removed tessera$flushGlErrors() was
            // (see uploadCompressedLevel's own doc comment on why that
            // was wrong). A single call here produces at most one log
            // line per upload() invocation (there are only ever a
            // handful of these per reload, one per atlas), so this cannot
            // recreate the multi-thousand-line flood the way polling
            // inside a per-sprite or per-block loop would. This does NOT
            // replace the async GLDebugMessageCallback as the primary
            // error-reporting mechanism -- that is untouched and is what
            // actually identifies *which* GL call failed; this is purely
            // a "did upload() as a whole leave any error flagged" signal,
            // logged at DEBUG so it stays out of the way by default.
            int trailingError = GL11.glGetError();
            if (trailingError != GL11.GL_NO_ERROR) {
                LOGGER.debug("GL error {} flagged at some point during {} (see the async GlDebug messages logged around this atlas's upload for the specific failing call).",
                        trailingError, debugGroupLabel);
            }
        }
    }

    private static long tessera$uploadInner(int textureId, CompressedAtlas compressed) {
        if (TesseraCompat.FULL_CHAIN_REQUIRED && !isFullChain(compressed)) {
            // Texture views reset GL_TEXTURE_MAX_LEVEL to the full chain: a level
            // left in RGBA next to compressed ones would make the atlas incomplete.
            LOGGER.warn("Atlas {}: compressed mip chain incomplete ({} of {} levels); keeping it uncompressed.",
                    compressed.atlasLocation(), compressed.levels().size(), compressed.requestedMaxLevel() + 1);
            return -1;
        }
        TesseraCompat.bindTexture(textureId);

        // requestedMaxLevel is the atlas's own mip level count. It only bounds
        // how deep this upload goes: on vanilla's mutable storage,
        // glCompressedTexImage2D respecifies each level, so there is no
        // pre-allocated depth to overflow.
        int maxAllocatedLevel = compressed.requestedMaxLevel();

        long totalResidentBytes = 0L;
        // Sum of each uploaded level's own uncompressed RGBA8 footprint
        // (width*height*4), NOT just the base level -- a full mip chain's
        // uncompressed baseline is the same geometric-series sum used by
        // estimateFullMipChainBytes, so tallying per-level here rather
        // than assuming level 0 alone keeps the DebugOverlay's "saved MB"
        // figure consistent with what vanilla's own uncompressed upload
        // would actually have resident for the same level count.
        long totalUncompressedBytes = 0L;
        int lastUploadedLevel = -1;

        for (CompressedLevel level : compressed.levels()) {
            if (level.level() > maxAllocatedLevel) {
                LOGGER.warn("Atlas {} compressed chain produced level {} beyond the {} level(s) vanilla's own upload allocated storage for; stopping chain here.",
                        compressed.atlasLocation(), level.level(), maxAllocatedLevel + 1);
                break;
            }
            boolean uploaded = uploadCompressedLevel(
                    compressed.atlasLocation(), compressed.target(), level.level(), level.width(), level.height(), level.compressedBlocks());
            if (!uploaded) {
                break;
            }
            totalResidentBytes += level.compressedBlocks().remaining();
            totalUncompressedBytes += (long) level.width() * level.height() * 4;
            lastUploadedLevel = level.level();
        }

        if (lastUploadedLevel < 0) {
            return -1;
        }

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, lastUploadedLevel);
        applyTextureFilteringState(lastUploadedLevel);

        if (lastUploadedLevel < compressed.requestedMaxLevel()) {
            LOGGER.info("Atlas {} mip chain stopped early at level {} of {} requested.",
                    compressed.atlasLocation(), lastUploadedLevel, compressed.requestedMaxLevel());
        }

        // Feeds the F3 debug overlay (see DebugOverlay#recordCompression).
        long savedBytes = totalUncompressedBytes - totalResidentBytes;
        DebugOverlay.recordCompression(compressed.atlasLocation().toString(), savedBytes, totalResidentBytes);
        DebugOverlay.recordBucketCompression(
                compressed.atlasLocation().toString(), compressed.target().name(), savedBytes, totalResidentBytes);

        return totalResidentBytes;
    }

    /** Levels 0..requestedMaxLevel, in order, each with exactly the block bytes its size needs. */
    private static boolean isFullChain(CompressedAtlas compressed) {
        if (compressed.levels().size() != compressed.requestedMaxLevel() + 1) {
            return false;
        }
        for (int i = 0; i < compressed.levels().size(); i++) {
            CompressedLevel level = compressed.levels().get(i);
            int blocksWide = ((level.width() + 3) & ~3) / 4;
            int blocksHigh = ((level.height() + 3) & ~3) / 4;
            if (level.level() != i || level.compressedBlocks() == null
                    || level.compressedBlocks().remaining() != blocksWide * blocksHigh * compressed.target().bytesPerBlock()) {
                return false;
            }
        }
        return true;
    }

    private static void applyTextureFilteringState(int maxMipLevel) {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                maxMipLevel > 0 ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    private static boolean uploadCompressedLevel(
            String atlasLocation, CompressionPipeline.Target target,
            int level, int width, int height, ByteBuffer compressedBlocks
    ) {
        if (compressedBlocks == null) {
            return false;
        }

        int alignedWidth = (width + 3) & ~3;
        int alignedHeight = (height + 3) & ~3;
        int expectedBytes = (alignedWidth / 4) * (alignedHeight / 4) * target.bytesPerBlock();

        if (compressedBlocks.remaining() != expectedBytes) {
            LOGGER.error(
                    "{} size mismatch for atlas {} mip level {} ({}x{}): received {} bytes, expected {}.",
                    target, atlasLocation, level, width, height, compressedBlocks.remaining(), expectedBytes
            );
            return false;
        }

        // Never issue glCompressedTexImage2D with an S3TC format the driver has
        // not advertised: the level's storage would be left undefined.
        if (target == CompressionPipeline.Target.BC1 && !Bc1TextureFormatSupport.isSupported()) {
            LOGGER.warn("Atlas {} BC1 upload skipped: driver does not advertise GL_EXT_texture_compression_s3tc.",
                    atlasLocation);
            return false;
        }

        int glInternalFormat = target == CompressionPipeline.Target.BC1
                ? EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT
                : GL42.GL_COMPRESSED_RGBA_BPTC_UNORM;

        // glCompressedTexImage2D (not glCompressedTexSubImage2D): vanilla allocates
        // atlas storage per level with glTexImage2D as mutable GL_RGBA8, and a
        // compressed *sub*-image upload into an RGBA8 level is GL_INVALID_OPERATION.
        // Respecifying the level's format and storage in one call is legal on
        // mutable storage. Later partial updates (animations) can then use
        // glCompressedTexSubImage2D, because the level is BC by that point.
        GL13.glCompressedTexImage2D(
                GL11.GL_TEXTURE_2D, level, glInternalFormat, width, height, 0, compressedBlocks
        );

        // No glGetError() here: polling error state on the render thread is a
        // synchronous pipeline stall. GL errors are reported asynchronously by
        // vanilla's GlDebug callback, which logs any failure of this call.

        long uncompressedSize = (long) width * height * 4;
        long compressedSize = compressedBlocks.remaining();
        double savedMB = (uncompressedSize - compressedSize) / (1024.0 * 1024.0);
        LOGGER.info("Successfully compressed atlas {} to {} mip level {}: {}x{}. VRAM saved: {} MB",
                atlasLocation, target, level, width, height, String.format("%.2f", savedMB));
        return true;
    }

    /** Bump whenever encoder output for identical input changes (new presets, encoder upgrade). */
    private static final String CACHE_VERSION = "v2"; // v2: alpha bleeding before encode

    private static ByteBuffer readCached(AtlasCache cache, String cacheKey, AtlasCache.CompressedFormat format,
                                         int width, int height, int quality) {
        try {
            return cache.read(cacheKey, format)
                    .filter(hit -> hit.width() == width && hit.height() == height && hit.qualityPreset() == quality)
                    .map(AtlasCache.CachedAtlas::compressedBlocks)
                    .orElse(null);
        } catch (IOException | RuntimeException e) {
            // Corrupt or unreadable entry: treat as a miss and recompress.
            LOGGER.debug("Ignoring unreadable cache entry {}", cacheKey, e);
            return null;
        }
    }

    private static synchronized AtlasCache cache() {
        if (cacheInstance == null) {
            cacheInstance = new AtlasCache(FMLPaths.GAMEDIR.get().resolve(Config.CACHE_DIRECTORY.get()));
            // Once per (re)creation, i.e. per resource reload, on whichever thread
            // calls compress(); a directory listing, negligible next to encoding.
            cacheInstance.prune(AtlasCache.configuredMaxBytes());
        }
        return cacheInstance;
    }

    /**
     * Drops the memoized {@link AtlasCache}, forcing the next {@link #cache()}
     * call to re-read {@link Config#CACHE_DIRECTORY} and rebuild it against
     * the current value. Without this, {@code cacheInstance} stays pinned to
     * whatever directory was configured the first time any reload needed the
     * cache, for the rest of the game session -- changing
     * {@code cacheDirectory} in the settings screen would silently do
     * nothing until restart. Called from {@code Tessera}'s config-reload
     * listener.
     */
    public static synchronized void invalidateCache() {
        cacheInstance = null;
    }

    /**
     * Result of the CPU-side compression phase: every successfully
     * compressed mip level, ready to be uploaded. Carries no GL state and
     * touches no GL calls to produce -- safe to build entirely on a
     * background executor thread. {@link #upload} is the only part of
     * this pipeline that must run on the render thread.
     */
    public record CompressedAtlas(
            String atlasLocation,
            CompressionPipeline.Target target,
            List<CompressedLevel> levels,
            int requestedMaxLevel,
            // RGBA pixels of selected mip levels, kept so that animated sprites
            // whose regions are smaller than a 4x4 block at those levels can be
            // patched and re-encoded block by block. Empty for most atlases.
            Map<Integer, MipChainBuilder.MipLevel> retainedRgba
    ) {
        public CompressedAtlas(String atlasLocation, CompressionPipeline.Target target,
                               List<CompressedLevel> levels, int requestedMaxLevel) {
            this(atlasLocation, target, levels, requestedMaxLevel, Map.of());
        }
    }

    public record CompressedLevel(int level, int width, int height, ByteBuffer compressedBlocks) {
    }
}
