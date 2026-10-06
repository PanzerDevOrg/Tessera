package com.panzer.mods.tessera.compress;

import com.mojang.blaze3d.platform.NativeImage;
import com.panzer.mods.tessera.atlas.MipChainBuilder;
import com.panzer.mods.tessera.compat.TesseraCompat;
import com.panzer.mods.tessera.compress.backend.TesseraRuntime;
import com.panzer.mods.tessera.compress.software.SoftwareBc1Encoder;
import com.panzer.mods.tessera.compress.software.SoftwareBc7Encoder;
import com.panzer.mods.tessera.compress.software.TransparentTexelBleed;
import com.panzer.mods.tessera.config.Config;
import com.panzer.mods.tessera.selftest.TesseraSelfTest;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL42;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps animated sprites working on a block-compressed atlas.
 *
 * <p>Vanilla animates by writing RGBA frames into the atlas: sub-image
 * uploads up to 1.21.10, GPU draws into the atlas from 1.21.11. Both are
 * invalid on a BC texture. Instead, each frame region is encoded to BC blocks
 * and written with {@code glCompressedTexSubImage2D}. That is legal for
 * 4x4-block-aligned regions, which {@link #alignedMipLevels} guarantees by
 * capping the atlas's compressed mip chain.
 *
 * <p>Everything encoded on the render thread uses the plain software encoder:
 * the native one costs about 5 ms per call whatever the size (its setup and
 * thread pool), and a tick can need over a hundred small encodes, which the
 * self-test measured at 100-800 ms per tick against under 1 ms in software.
 * <ul>
 *   <li>Keyframes (frames read straight from the sprite's own mip images) are
 *       encoded once and cached: steady-state cost is one small GPU upload per
 *       sprite per frame change, no CPU encoding. The first time a keyframe is
 *       shown it is encoded in software; when the native encoder is loaded, a
 *       background thread re-encodes it with the atlas's own encoder and quality,
 *       and the cache takes that version (the software encoder loses a lot on
 *       blocks mixing transparent and opaque texels: fire, lanterns, campfires).</li>
 *   <li>Interpolated frames (blended per tick) and deep mip levels (patched per
 *       frame change, mixed with neighbouring sprites) are looked up by content:
 *       animations repeat, so each distinct image is encoded once (in software,
 *       and in the background with the native encoder for interpolated frames),
 *       then only hashed.</li>
 * </ul>
 *
 * <p>Up to 1.21.10 frames arrive through {@code SpriteContents.upload}
 * ({@link #upload}); from 1.21.11 Tessera draws them itself, replacing
 * {@code TextureAtlas.uploadAnimationFrames} for compressed atlases
 * ({@link #drawFrames}), with the same padding and blending as vanilla's
 * {@code animate_sprite} shaders.
 *
 * <p>Render thread only.
 */
public final class CompressedAnimationUploader {

    /**
     * @param alignedLevel   deepest level at which every animated region is 4x4-block
     *                       aligned (direct per-sprite encode, keyframes cached)
     * @param deepLevels     RGBA copy of each level beyond alignedLevel, patched per
     *                       frame and re-encoded only over the touched blocks
     * @param spritesByState 1.21.11+: the atlas's animation states and their sprites
     */
    private record AtlasState(int glId, CompressionPipeline.Target target, int maxLevel, int alignedLevel,
                              Map<Integer, MipChainBuilder.MipLevel> deepLevels,
                              Map<Object, TextureAtlasSprite> spritesByState) {
    }

    /** One frame's pixels, as ABGR, at {@code (x, y)} of its region at a mip level. */
    @FunctionalInterface
    private interface FramePixels {
        int abgr(int level, int x, int y);
    }

    /** Compressed atlases. */
    private static final Map<TextureAtlas, AtlasState> COMPRESSED_ATLASES = new IdentityHashMap<>();
    /** Encoded keyframes per sprite: key = frame column << 32 | frame row (or pixel offsets), value = blocks per mip level. */
    private static final Map<SpriteContents, Map<Long, ByteBuffer[]>> KEYFRAME_CACHE = new IdentityHashMap<>();

    /** Atlas currently inside cycleAnimationFrames (up to 1.21.10), or null when it is not compressed. */
    private static AtlasState active;
    private static ByteBuffer rgbaScratch = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.LITTLE_ENDIAN);

    /** Best-quality keyframe encodes, one at a time, never on the render thread. */
    private static final ExecutorService BEST_QUALITY = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Tessera animation encoder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    /** Finished best-quality keyframes, put in the cache on the render thread. */
    private static final ConcurrentLinkedQueue<Runnable> UPGRADES = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PENDING_UPGRADES = new AtomicInteger();

    /** Encoded blocks by image content (see {@link #encodeByContent}); render thread only, least recently used out first. */
    private static final int MAX_BY_CONTENT = 8192;
    private static final Map<Long, ByteBuffer> BY_CONTENT = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ByteBuffer> eldest) {
            return size() > MAX_BY_CONTENT;
        }
    };

    /** One aligned mip level of a new keyframe, kept for its best-quality re-encode. */
    private record LevelInput(ByteBuffer rgba, int width, int height) {
    }

    private CompressedAnimationUploader() {
    }

    /** Keyframes still waiting for their best-quality re-encode (self-test). */
    public static int pendingUpgrades() {
        return PENDING_UPGRADES.get();
    }

    /** Animations of an atlas can be kept running once it is compressed. */
    public static boolean canAnimate(boolean hasAnimatedSprites) {
        return !hasAnimatedSprites || !TesseraCompat.GPU_ANIMATION || AnimationReader.AVAILABLE;
    }

    /**
     * Called after a successful compressed upload of {@code atlas}.
     *
     * @param spritesByState 1.21.11+: {@link TesseraCompat#animatedSprites}
     */
    public static void register(TextureAtlas atlas, int glId, CompressionPipeline.Target target, int maxUploadedLevel,
                                int alignedLevel, Map<Integer, MipChainBuilder.MipLevel> deepLevels,
                                Map<Object, TextureAtlasSprite> spritesByState) {
        // Deep levels without a CPU copy cannot be updated; cap there so a
        // missing copy degrades to fewer levels, never to stale/garbled ones.
        int maxLevel = alignedLevel;
        while (maxLevel < maxUploadedLevel && deepLevels.containsKey(maxLevel + 1)) {
            maxLevel++;
        }
        COMPRESSED_ATLASES.put(atlas, new AtlasState(glId, target, maxLevel, alignedLevel, deepLevels, spritesByState));
        if (TesseraCompat.GPU_ANIMATION) {
            // The compressed chain holds frame 0 of every animated sprite; show
            // the frame each animation is on now.
            drawFrames(atlas, true);
        }
    }

    /**
     * Called when vanilla re-uploads the atlas as RGBA (reload). Drops the whole
     * keyframe cache: sprites from the previous reload are dead, and the cache is
     * small enough that rebuilding it lazily is cheaper than tracking ownership.
     */
    public static void unregister(TextureAtlas atlas) {
        if (COMPRESSED_ATLASES.remove(atlas) != null) {
            KEYFRAME_CACHE.clear();
            BY_CONTENT.clear();
        }
    }

    public static void beginAnimationCycle(TextureAtlas atlas) {
        active = COMPRESSED_ATLASES.get(atlas);
    }

    public static void endAnimationCycle() {
        active = null;
    }

    /**
     * Highest mip level at which every animated sprite region stays aligned to
     * 4x4 blocks (position and size), so compressed sub-image updates are legal.
     */
    public static int alignedMipLevels(int requestedMaxLevel, int[] animatedRects) {
        int level = requestedMaxLevel;
        for (int i = 0; i < animatedRects.length; i += 4) {
            int x = animatedRects[i], y = animatedRects[i + 1], w = animatedRects[i + 2], h = animatedRects[i + 3];
            while (level > 0 && !isBlockAligned(x >> level, y >> level, w >> level, h >> level)) {
                level--;
            }
        }
        return level;
    }

    private static boolean isBlockAligned(int x, int y, int w, int h) {
        return w > 0 && h > 0 && ((x | y | w | h) & 3) == 0;
    }

    /**
     * Up to 1.21.10: replacement for {@code SpriteContents.upload} on a compressed atlas.
     *
     * @return false when the active atlas is not compressed (caller runs vanilla's upload)
     */
    public static boolean upload(SpriteContents contents, int x, int y, int frameX, int frameY, NativeImage[] images) {
        AtlasState state = active;
        if (state == null) {
            return false;
        }
        boolean keyframe = images == contents.byMipLevel;
        long key = ((long) frameX << 32) | (frameY & 0xFFFFFFFFL);
        FramePixels pixels = (level, px, py) -> TesseraCompat.abgr(images[level], (frameX >> level) + px, (frameY >> level) + py);
        writeFrame(state, contents, keyframe, key, x, y, contents.width(), contents.height(), images.length - 1, pixels);
        return true;
    }

    /**
     * 1.21.11+: replacement for {@code TextureAtlas.uploadAnimationFrames} on a
     * compressed atlas. Draws what {@code SpriteContents.AnimationState.drawToAtlas}
     * would: the current frame (blended with the next one for interpolated
     * sprites) over the sprite's padded region, edge pixels repeated into the
     * padding, at every mip level.
     *
     * @param all every animation, not only those vanilla would redraw this tick
     * @return false when the atlas is not compressed (caller runs vanilla's passes)
     */
    public static boolean drawFrames(TextureAtlas atlas, boolean all) {
        AtlasState state = COMPRESSED_ATLASES.get(atlas);
        if (state == null) {
            return false;
        }
        for (Map.Entry<Object, TextureAtlasSprite> entry : state.spritesByState().entrySet()) {
            Object animation = entry.getKey();
            if (!all && !AnimationReader.needsToDraw(animation)) {
                continue;
            }
            TextureAtlasSprite sprite = entry.getValue();
            AnimationReader.Frame frame = AnimationReader.read(animation);
            SpriteContents contents = sprite.contents();
            NativeImage[] images = contents.byMipLevel;
            int w = contents.width();
            int h = contents.height();
            int padding = TesseraCompat.padding(sprite);
            float padU = (float) padding / w;
            float padV = (float) padding / h;
            FramePixels pixels = (level, px, py) -> {
                // Frame textures are w x h with the sprite's mip chain; animate_sprite.vsh
                // stretches them over the padded region, the clamp-to-edge sampler fills the ring.
                int tx = texel(px, (w + 2 * padding) >> level, padU, Math.max(1, w >> level));
                int ty = texel(py, (h + 2 * padding) >> level, padV, Math.max(1, h >> level));
                NativeImage image = images[level];
                int current = TesseraCompat.abgr(image, ((frame.column() * w) >> level) + tx, ((frame.row() * h) >> level) + ty);
                if (!frame.interpolated()) {
                    return current;
                }
                int next = TesseraCompat.abgr(image, ((frame.nextColumn() * w) >> level) + tx,
                        ((frame.nextRow() * h) >> level) + ty);
                return mix(current, next, frame.progress());
            };
            long key = ((long) frame.column() << 32) | (frame.row() & 0xFFFFFFFFL);
            writeFrame(state, contents, !frame.interpolated(), key, sprite.getX(), sprite.getY(),
                    w + 2 * padding, h + 2 * padding, images.length - 1, pixels);
        }
        return true;
    }

    /** animate_sprite.vsh plus a NEAREST clamp-to-edge sampler: pixel i of a padded region to a frame texel. */
    private static int texel(int i, int region, float padding, int size) {
        float uv = (i + 0.5F) / region;
        float tex = uv + padding * (uv * 2.0F - 1.0F);
        int t = (int) Math.floor(tex * size);
        return Math.max(0, Math.min(size - 1, t));
    }

    /** animate_sprite_interpolate.fsh: mix(current, next, progress) per channel, stored as 8-bit. */
    private static int mix(int current, int next, float progress) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            float a = ((current >>> shift) & 0xFF) / 255.0F;
            float b = ((next >>> shift) & 0xFF) / 255.0F;
            int c = Math.round((a * (1.0F - progress) + b * progress) * 255.0F);
            out |= Math.max(0, Math.min(255, c)) << shift;
        }
        return out;
    }

    /**
     * Writes one frame over the region at {@code (x, y)} of size {@code width x height}
     * (level 0; halved per level) at every compressed level the frame has.
     */
    private static void writeFrame(AtlasState state, SpriteContents owner, boolean cacheable, long cacheKey,
                                   int x, int y, int width, int height, int frameLevels, FramePixels pixels) {
        for (Runnable upgrade; (upgrade = UPGRADES.poll()) != null; ) {
            upgrade.run();
        }
        TesseraCompat.bindTexture(state.glId());
        ByteBuffer[] cached = null;
        Map<Long, ByteBuffer[]> spriteCache = null;
        if (cacheable) {
            spriteCache = KEYFRAME_CACHE.computeIfAbsent(owner, c -> new HashMap<>());
            cached = spriteCache.get(cacheKey);
        }
        int levels = Math.min(state.maxLevel(), frameLevels);
        ByteBuffer[] encoded = cached != null ? cached : new ByteBuffer[levels + 1];
        int glFormat = state.target() == CompressionPipeline.Target.BC7
                ? GL42.GL_COMPRESSED_RGBA_BPTC_UNORM
                : EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT;
        // A new keyframe: its pixels per level, for the best-quality re-encode.
        LevelInput[] inputs = cacheable && cached == null && TesseraRuntime.isNativeActive()
                ? new LevelInput[levels + 1] : null;

        for (int level = 0; level <= levels; level++) {
            int w = width >> level;
            int h = height >> level;
            if (w < 1 || h < 1) {
                break; // same guard as vanilla's upload
            }
            if (level > state.alignedLevel()) {
                patchDeepLevel(state, level, pixels, x >> level, y >> level, w, h, glFormat);
                continue;
            }
            if (!isBlockAligned(x >> level, y >> level, w, h)) {
                break; // unreachable: alignedLevel guarantees alignment up to here
            }
            ByteBuffer blocks = encoded[level];
            if (blocks == null) {
                ByteBuffer rgba = frameRgba(pixels, level, w, h);
                if (!cacheable) {
                    // Interpolated: not a keyframe, but the same blend comes back every cycle.
                    blocks = encodeByContent(rgba, w, h, state.target(), true);
                } else {
                    if (inputs != null) {
                        inputs[level] = new LevelInput(copyOf(rgba), w, h);
                    }
                    blocks = encodeFast(rgba, w, h, state.target());
                    encoded[level] = blocks;
                }
            }
            GL13.glCompressedTexSubImage2D(GL11.GL_TEXTURE_2D, level, x >> level, y >> level, w, h, glFormat,
                    blocks.duplicate());
        }
        if (cacheable && cached == null) {
            spriteCache.put(cacheKey, encoded);
            if (inputs != null) {
                encodeBestLater(spriteCache, cacheKey, encoded, inputs, state.target());
            }
        }
    }

    /**
     * Re-encodes a new keyframe with the atlas's own encoder on the background
     * thread; the next frame drawn on the render thread puts it in the cache,
     * unless the entry changed meanwhile (a reload dropped the cache).
     */
    private static void encodeBestLater(Map<Long, ByteBuffer[]> spriteCache, long key, ByteBuffer[] fast,
                                        LevelInput[] inputs, CompressionPipeline.Target target) {
        encodeBestLater(target, inputs, fast, best -> {
            if (spriteCache.get(key) == fast) {
                spriteCache.put(key, best);
            }
        });
    }

    /**
     * Encodes {@code inputs} with the atlas's own encoder on the background thread,
     * then hands {@code apply} the result (levels that failed keep {@code fast}) on
     * the render thread, before the next frame is drawn.
     */
    private static void encodeBestLater(CompressionPipeline.Target target, LevelInput[] inputs, ByteBuffer[] fast,
                                        java.util.function.Consumer<ByteBuffer[]> apply) {
        PENDING_UPGRADES.incrementAndGet();
        try {
            BEST_QUALITY.execute(() -> {
                try {
                    ByteBuffer[] best = fast.clone();
                    boolean bc7 = target == CompressionPipeline.Target.BC7;
                    int quality = Config.get(Config.COMPRESSION_QUALITY);
                    for (int level = 0; level < inputs.length; level++) {
                        LevelInput in = inputs[level];
                        if (in == null) {
                            continue;
                        }
                        long start = System.nanoTime();
                        ByteBuffer blocks = CompressionPipeline.compressBlocking("animation", in.rgba(), in.width(),
                                in.height(), bc7, quality);
                        if (TesseraSelfTest.ENABLED) {
                            EncodeStats.addBest(System.nanoTime() - start);
                        }
                        if (blocks != null) {
                            best[level] = blocks;
                        }
                    }
                    UPGRADES.add(() -> apply.accept(best));
                } finally {
                    PENDING_UPGRADES.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            PENDING_UPGRADES.decrementAndGet();
        }
    }

    /**
     * Blocks for an image that may have been encoded before: by a 64-bit hash of its
     * pixels, size and format. A new image is encoded in software now; with
     * {@code best}, the native encoder redoes it in the background and the cache
     * takes that version.
     */
    private static ByteBuffer encodeByContent(ByteBuffer rgba, int w, int h, CompressionPipeline.Target target,
                                             boolean best) {
        long key = contentHash(rgba, w, h, target);
        ByteBuffer known = BY_CONTENT.get(key);
        if (known != null) {
            if (TesseraSelfTest.ENABLED) {
                EncodeStats.found++;
            }
            return known.duplicate();
        }
        ByteBuffer input = best && TesseraRuntime.isNativeActive() && (w & 3) == 0 && (h & 3) == 0
                ? copyOf(rgba) : null;
        ByteBuffer blocks = encodeFast(rgba, w, h, target);
        BY_CONTENT.put(key, blocks);
        if (input != null) {
            encodeBestLater(target, new LevelInput[]{new LevelInput(input, w, h)}, new ByteBuffer[]{blocks},
                    better -> {
                        if (BY_CONTENT.get(key) == blocks) {
                            BY_CONTENT.put(key, better[0]);
                        }
                    });
        }
        return blocks.duplicate();
    }

    private static long contentHash(ByteBuffer rgba, int w, int h, CompressionPipeline.Target target) {
        long hash = 0x9E3779B97F4A7C15L ^ ((long) w << 32 | (long) h << 1 | target.ordinal());
        int base = rgba.position();
        int bytes = w * h * 4;
        int i = 0;
        for (; i + 8 <= bytes; i += 8) {
            hash = mix(hash ^ rgba.getLong(base + i));
        }
        for (; i < bytes; i++) {
            hash = mix(hash ^ (rgba.get(base + i) & 0xFFL));
        }
        return hash;
    }

    /** SplitMix64's finalizer: every input bit reaches every output bit. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static ByteBuffer copyOf(ByteBuffer rgba) {
        ByteBuffer copy = ByteBuffer.allocateDirect(rgba.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        copy.put(rgba.duplicate()).flip();
        return copy;
    }

    /**
     * Writes the frame's pixels into the retained RGBA copy of {@code level}, then
     * re-encodes and uploads the smallest block-aligned rectangle covering them
     * (neighbouring static pixels come from the copy, so they are preserved).
     */
    private static void patchDeepLevel(AtlasState state, int level, FramePixels frame, int dstX, int dstY,
                                       int w, int h, int glFormat) {
        MipChainBuilder.MipLevel copy = state.deepLevels().get(level);
        int levelWidth = copy.width();
        int levelHeight = copy.height();
        ByteBuffer pixels = copy.rgba8().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                pixels.putInt(((dstY + py) * levelWidth + dstX + px) * 4, frame.abgr(level, px, py));
            }
        }

        // Offsets snap down to the block grid; extents snap up, clamped to the
        // level edge (partial blocks are only legal where they touch the edge).
        int x0 = dstX & ~3;
        int y0 = dstY & ~3;
        int x1 = Math.min(levelWidth, (dstX + w + 3) & ~3);
        int y1 = Math.min(levelHeight, (dstY + h + 3) & ~3);
        int rw = x1 - x0;
        int rh = y1 - y0;

        int bytes = rw * rh * 4;
        if (rgbaScratch.capacity() < bytes) {
            rgbaScratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        ByteBuffer rect = rgbaScratch.clear();
        for (int row = 0; row < rh; row++) {
            int rowStart = ((y0 + row) * levelWidth + x0) * 4;
            rect.put(rect.position(), pixels, rowStart, rw * 4);
            rect.position(rect.position() + rw * 4);
        }
        rect.flip();
        ByteBuffer blocks = encodeByContent(rect, rw, rh, state.target(), false);
        GL13.glCompressedTexSubImage2D(GL11.GL_TEXTURE_2D, level, x0, y0, rw, rh, glFormat, blocks);
    }

    /** One frame's pixels at a mip level as RGBA8, in the shared scratch buffer. */
    private static ByteBuffer frameRgba(FramePixels pixels, int level, int w, int h) {
        int bytes = w * h * 4;
        if (rgbaScratch.capacity() < bytes) {
            rgbaScratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        ByteBuffer rgba = rgbaScratch.clear();
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                // ABGR written little-endian is R,G,B,A bytes.
                rgba.putInt(pixels.abgr(level, px, py));
            }
        }
        rgba.flip();
        return rgba;
    }

    /** The plain software encoder, for everything encoded on the render thread (bleeds {@code rgba} in place). */
    private static ByteBuffer encodeFast(ByteBuffer rgba, int w, int h, CompressionPipeline.Target target) {
        long start = TesseraSelfTest.ENABLED ? System.nanoTime() : 0L;
        TransparentTexelBleed.apply(rgba, w, h);
        ByteBuffer blocks = target == CompressionPipeline.Target.BC7
                ? SoftwareBc7Encoder.encode(rgba, w, h)
                : SoftwareBc1Encoder.encode(rgba, w, h);
        if (TesseraSelfTest.ENABLED) {
            EncodeStats.addFast(System.nanoTime() - start);
        }
        return blocks;
    }

    /**
     * Self-test only: animation encoding time since the last {@link #reset}, on the
     * render thread (what a tick pays) and on the background thread (best-quality keyframes).
     */
    public static final class EncodeStats {
        public static long calls, nanos, maxNanos, found;
        private static long bestCalls, bestNanos;

        private EncodeStats() {
        }

        static void addFast(long used) {
            calls++;
            nanos += used;
            maxNanos = Math.max(maxNanos, used);
        }

        static synchronized void addBest(long used) {
            bestCalls++;
            bestNanos += used;
        }

        public static synchronized long bestCalls() {
            return bestCalls;
        }

        public static synchronized long bestNanos() {
            return bestNanos;
        }

        public static synchronized void reset() {
            calls = nanos = maxNanos = found = 0;
            bestCalls = bestNanos = 0;
        }
    }
}
