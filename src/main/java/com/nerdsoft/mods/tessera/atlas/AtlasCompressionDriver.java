package com.nerdsoft.mods.tessera.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nerdsoft.mods.tessera.api.AtlasCompressEvent;
import com.nerdsoft.mods.tessera.cache.AtlasCache;
import com.nerdsoft.mods.tessera.compress.Bc1TextureFormatSupport;
import com.nerdsoft.mods.tessera.compress.Bc7GpuSupport;
import com.nerdsoft.mods.tessera.compress.CompressionPipeline;
import com.nerdsoft.mods.tessera.config.Config;
import com.nerdsoft.mods.tessera.gui.DebugOverlay;
import com.nerdsoft.mods.tessera.jni.NativeLibraryLoader;
import com.nerdsoft.mods.tessera.vram.VramBudgetEngine;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * BC1/BC7 compression + upload driver, extracted from the old
 * {@code SpriteLoaderMixin}'s per-bucket compression path (now disabled --
 * see {@code tessera.mixins.json}) so it can be reused against
 * {@link SplitAtlasManager}'s independently-owned
 * {@code TextureAtlas} instances without depending on the retired
 * {@code SpriteBucket}/{@code SpriteAtlasRouting} bucket-hack types.
 *
 * <p>Everything here is atlas-identity-agnostic: callers pass a GL texture
 * ID, a target {@link CompressionPipeline.Target}, and the sprite/pixel
 * data to compress. Nothing in this class assumes there is only one atlas,
 * or that atlases share GL texture storage -- both assumptions the old
 * bucket hack made and which caused the original UV-corruption bug.
 *
 * <p>Both BC1 and BC7 always go through the same CPU-side native encoder
 * (via {@link CompressionPipeline}, backed by {@code bc7enc_rdo} for BC7
 * and the native BC1 encoder for BC1) -- there is no GPU compute-shader
 * path. This is deliberately simpler than an earlier revision that
 * dispatched BC1 through a render-thread compute shader: that path
 * required its own capability detection, its own render-thread/background-
 * thread branching in {@link SplitAtlasManager}, and its own GLSL resource,
 * for a format that the CPU encoder already produces at acceptable speed.
 *
 * <h2>Two-phase compress/upload split</h2>
 * {@link #compress} (CPU-only, no GL calls, safe on a background executor)
 * and {@link #upload} (GL calls only, render-thread only) are deliberately
 * separate methods. An earlier version of this class fused both into one
 * {@code compressAndUpload} call that ran entirely on the render thread --
 * that meant every resource reload stalled a frame for however long native
 * BC1/BC7 encoding of a full atlas took, since the CPU-bound encoding work
 * had no reason to be on the render thread at all. Callers should run
 * {@link #compress} on the same background executor
 * {@code SpriteLoader.stitch()} was already given, and only call
 * {@link #upload} once back on the render thread (see
 * {@link SplitAtlasManager#tessera$triggerMergedStitchIfNeeded}/
 * {@link SplitAtlasManager#applyPendingSplitStitch}).
 */
public final class AtlasCompressionDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/AtlasCompressionDriver");

    private static volatile AtlasCache cacheInstance;

    private AtlasCompressionDriver() {
    }

    /**
     * Assembles one contiguous RGBA8 buffer for an entire atlas from its
     * stitched sprites' own pixel data, positioned per each sprite's
     * stitch-time placement ({@code TextureAtlasSprite.getX()/getY()}
     * within the atlas), then runs perceptual near-duplicate detection
     * over the assembled buffer via {@code NativeFamilyDetector} -- lifted
     * unmodified from the old bucket-buffer assembly, since dedup detection
     * is orthogonal to which physical atlas the sprites end up on.
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
        if (Config.DISABLE_NATIVE_COMPRESSION.get() || !NativeLibraryLoader.isAvailable()) {
            LOGGER.debug("[Tessera-Debug] {}: buffer assembly skipped (native compression disabled or bridge unavailable).", label);
            return null;
        }

        long totalPixelBytes = 0L;
        int[] widths = new int[regions.size()];
        int[] heights = new int[regions.size()];
        int[] srcOffsets = new int[regions.size()];
        int[] destX = new int[regions.size()];
        int[] destY = new int[regions.size()];

        int index = 0;
        for (TextureAtlasSprite sprite : regions) {
            int spriteWidth = sprite.contents().width();
            int spriteHeight = sprite.contents().height();
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

            pixels.position(srcOffsets[index]);
            for (int y = 0; y < spriteHeight; y++) {
                for (int x = 0; x < spriteWidth; x++) {
                    pixels.putInt(sprite.getPixelRGBA(0, x, y));
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
            LOGGER.debug("[Tessera-Debug] {}: native family detection returned no result.", label);
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
     * Upper-bound byte estimate for an atlas's <em>full</em> mip chain
     * (base level + every level down to 1x1) -- see the original
     * derivation in the retired {@code SpriteLoaderMixin}: a full chain
     * never exceeds 4/3 of the base level's size, since the geometric
     * series {@code sum(1/4^i)} converges to {@code 4/3}.
     */
    public static long estimateFullMipChainBytes(int width, int height, CompressionPipeline.Target target) {
        int alignedWidth = (width + 3) & ~3;
        int alignedHeight = (height + 3) & ~3;
        long baseLevelBytes = (long) (alignedWidth / 4) * (alignedHeight / 4) * target.bytesPerBlock();
        return (baseLevelBytes * 4) / 3;
    }

    /**
     * CPU-only compression phase: builds the mip chain and compresses each
     * level via the native BC1/BC7 encoder. Contains no GL calls and is
     * safe to run on any thread, including a background executor -- this
     * is the phase that previously ran fused into
     * {@code compressAndUpload} directly on the render thread, stalling a
     * frame on every resource reload for however long native encoding of
     * a full atlas took. Callers should run this on a background executor
     * (e.g. the same one {@code SpriteLoader.stitch()} was already given)
     * and only hand the result to {@link #upload} once back on the render
     * thread. Handles BC1 and BC7 identically; both are CPU-only.
     *
     * @return a {@link CompressedAtlas} with zero or more levels (zero
     * levels means compression was entirely unavailable/skipped;
     * the caller should fall back to leaving the atlas on its
     * existing uncompressed upload)
     */
    @SuppressWarnings("LoggingSimilarMessage")
    public static CompressedAtlas compress(
            ResourceLocation atlasLocation, CompressionPipeline.Target target,
            ByteBuffer baseRgba8, int baseWidth, int baseHeight, int requestedMaxLevel
    ) {
        if (target == CompressionPipeline.Target.BC7 && !Bc7GpuSupport.isSupported()) {
            LOGGER.info("[Tessera coverage] Atlas {} SKIPPED (BC7 unsupported on this GPU).", atlasLocation);
            return new CompressedAtlas(atlasLocation, target, List.of(), requestedMaxLevel);
        }
        if (target == CompressionPipeline.Target.BC1 && !Bc1TextureFormatSupport.isSupported()) {
            LOGGER.info("[Tessera coverage] Atlas {} SKIPPED (BC1/S3TC unsupported on this GPU).", atlasLocation);
            return new CompressedAtlas(atlasLocation, target, List.of(), requestedMaxLevel);
        }

        long fullChainEstimateBytes = estimateFullMipChainBytes(baseWidth, baseHeight, target);
        if (!VramBudgetEngine.isWithinBudget(fullChainEstimateBytes, 0)) {
            LOGGER.warn("Atlas {} (full mip chain estimate) exceeds VRAM budget target. Falling back to uncompressed RGBA for this atlas.",
                    atlasLocation);
            LOGGER.info("[Tessera coverage] Atlas {} SKIPPED (exceeds VRAM budget).", atlasLocation);
            return new CompressedAtlas(atlasLocation, target, List.of(), requestedMaxLevel);
        }

        List<MipChainBuilder.MipLevel> mipLevels = MipChainBuilder.build(baseRgba8, baseWidth, baseHeight, requestedMaxLevel);
        if (mipLevels == null || mipLevels.isEmpty()) {
            // Native mip-chain generation unavailable/failed entirely --
            // fall back to compressing level 0 only.
            LOGGER.debug("[Tessera-Debug] Atlas {}: native mip-chain build unavailable, compressing level 0 only.", atlasLocation);
            mipLevels = List.of(new MipChainBuilder.MipLevel(baseWidth, baseHeight, baseRgba8));
        }

        CompressionPipeline pipeline = new CompressionPipeline(cache());
        List<CompressedLevel> compressedLevels = new ArrayList<>(mipLevels.size());

        for (int level = 0; level < mipLevels.size(); level++) {
            MipChainBuilder.MipLevel mipLevel = mipLevels.get(level);
            Optional<CompressionPipeline.CompressionResult> result =
                    pipeline.compress(mipLevel.rgba8(), mipLevel.width(), mipLevel.height(), target);
            if (result.isEmpty()) {
                LOGGER.info("[Tessera coverage] Atlas {} mip level {} SKIPPED (compression unavailable); stopping chain here.",
                        atlasLocation, level);
                break;
            }
            LOGGER.debug("[Tessera-Debug] Atlas {} mip level {} ({}x{}) compressed to {} via CPU (fromCache={}).",
                    atlasLocation, level, mipLevel.width(), mipLevel.height(), target, result.get().fromCache());
            compressedLevels.add(new CompressedLevel(level, mipLevel.width(), mipLevel.height(), result.get().compressedBlocks()));
        }

        return new CompressedAtlas(atlasLocation, target, compressedLevels, requestedMaxLevel);
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
            LOGGER.debug("[Tessera-Debug] Atlas {}: upload() called with zero compressed levels; nothing to do.",
                    compressed.atlasLocation());
            return -1;
        }

        // Per-atlas GL debug group (added because debug.log kept showing
        // GL_INVALID_OPERATION as "in (null)" with no attributable call
        // site, making BC1's opaque-atlas errors indistinguishable from
        // BC7's alpha-atlas errors in the log). Every GL call between
        // push/pop below -- including any async debug messages the driver
        // emits for them -- is now tagged with this atlas's own location
        // string.
        String debugGroupLabel = "tessera:upload:" + compressed.atlasLocation();
        GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0, debugGroupLabel);
        try {
            return tessera$uploadInner(textureId, compressed);
        } finally {
            GL43.glPopDebugGroup();

            // One-shot, non-looping poll -- deliberately NOT a flood-
            // amplifying spin loop. A single call here produces at most
            // one log line per upload() invocation (there are only ever a
            // handful of these per reload, one per atlas), so this cannot
            // recreate a multi-thousand-line flood the way polling inside
            // a per-sprite or per-block loop would. This does NOT replace
            // the async GLDebugMessageCallback as the primary error-
            // reporting mechanism -- that is untouched and is what
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
        RenderSystem.bindTexture(textureId);

        // requestedMaxLevel is the same maxMipLevel this family's own
        // stitch call requested (see SourceAtlasFamily#maxMipLevel /
        // SplitAtlasManager#tessera$stitchFamily) -- clamped here
        // defensively so compress()'s caller can never walk this driver
        // past a level index deeper than what was actually requested,
        // regardless of how many levels MipChainBuilder produced.
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
                LOGGER.warn("Atlas {} compressed chain produced level {} beyond the {} level(s) this atlas's own stitch requested storage for; stopping chain here.",
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

        long savedBytes = totalUncompressedBytes - totalResidentBytes;
        DebugOverlay.recordCompression(compressed.atlasLocation().toString(), savedBytes, totalResidentBytes);
        DebugOverlay.recordBucketCompression(
                compressed.atlasLocation().toString(), compressed.target().name(), savedBytes, totalResidentBytes);

        NeoForge.EVENT_BUS.post(new AtlasCompressEvent.Post(
                compressed.atlasLocation(), compressed.target().cacheFormat(), savedBytes, totalResidentBytes));

        return totalResidentBytes;
    }

    private static void applyTextureFilteringState(int maxMipLevel) {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                maxMipLevel > 0 ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    private static boolean uploadCompressedLevel(
            ResourceLocation atlasLocation, CompressionPipeline.Target target,
            int level, int width, int height, ByteBuffer compressedBlocks
    ) {
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

        // Last-line defense: never issue glCompressedTexImage2D with an
        // S3TC enum the driver hasn't advertised. compress() already
        // checks Bc1TextureFormatSupport before running the CPU encoder
        // at all, but this call site is kept as a second, independent
        // guard since it is the actual point a bad enum would reach the
        // driver -- cheap insurance against a future caller bypassing
        // compress()'s own check.
        if (target == CompressionPipeline.Target.BC1 && !Bc1TextureFormatSupport.isSupported()) {
            LOGGER.warn("Atlas {} BC1 upload skipped: driver does not advertise GL_EXT_texture_compression_s3tc.",
                    atlasLocation);
            return false;
        }

        int glInternalFormat = target == CompressionPipeline.Target.BC1
                ? EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT
                : GL42.GL_COMPRESSED_RGBA_BPTC_UNORM;

        // Vanilla's TextureAtlas.upload(Preparations) allocates this
        // texture's storage via glTexImage2D per level -- MUTABLE storage,
        // format GL_RGBA8 -- not glTexStorage2D (TextureAtlas has never
        // opted into ARB_texture_storage). glCompressedTexImage2D
        // respecifies the level's format+storage in one call, which is
        // legal (and required) against mutable storage; glCompressedTexSubImage2D
        // would require the level's EXISTING internal format to already
        // be compressed, which it never is coming from vanilla's own
        // upload.
        GL13.glCompressedTexImage2D(
                GL11.GL_TEXTURE_2D, level, glInternalFormat, alignedWidth, alignedHeight, 0, compressedBlocks
        );

        long uncompressedSize = (long) width * height * 4;
        long compressedSize = compressedBlocks.remaining();
        double savedMB = (uncompressedSize - compressedSize) / (1024.0 * 1024.0);
        LOGGER.info("Successfully compressed atlas {} to {} mip level {}: {}x{}. VRAM saved: {} MB",
                atlasLocation, target, level, width, height, String.format("%.2f", savedMB));
        return true;
    }

    private static synchronized AtlasCache cache() {
        if (cacheInstance == null) {
            cacheInstance = new AtlasCache(FMLPaths.GAMEDIR.get().resolve(Config.CACHE_DIRECTORY.get()));
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
            ResourceLocation atlasLocation,
            CompressionPipeline.Target target,
            List<CompressedLevel> levels,
            int requestedMaxLevel
    ) {
    }

    public record CompressedLevel(int level, int width, int height, ByteBuffer compressedBlocks) {
    }
}