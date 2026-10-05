package com.panzer.mods.tessera.compress;

import com.mojang.blaze3d.platform.NativeImage;
import com.panzer.mods.tessera.atlas.MipChainBuilder;
import com.panzer.mods.tessera.compat.TesseraCompat;
import com.panzer.mods.tessera.compress.software.SoftwareBc1Encoder;
import com.panzer.mods.tessera.compress.software.SoftwareBc7Encoder;
import com.panzer.mods.tessera.compress.software.TransparentTexelBleed;
import com.panzer.mods.tessera.config.Config;
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
import java.util.Map;

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
 * <ul>
 *   <li>Keyframes (frames read straight from the sprite's own mip images) are
 *       encoded once and cached: steady-state cost is one small GPU upload per
 *       sprite per frame change, no CPU encoding.</li>
 *   <li>Interpolated frames (blended per tick) are encoded on the fly. Pure-Java
 *       mode-6 BC7 on purpose: a 16x16 sprite is 16 blocks, where the native
 *       encoder's per-call thread-pool spin-up would dominate.</li>
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

    private CompressedAnimationUploader() {
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
                blocks = encode(pixels, level, w, h, state.target());
                encoded[level] = blocks;
            }
            GL13.glCompressedTexSubImage2D(GL11.GL_TEXTURE_2D, level, x >> level, y >> level, w, h, glFormat,
                    blocks.duplicate());
        }
        if (cacheable && cached == null) {
            spriteCache.put(cacheKey, encoded);
        }
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
        ByteBuffer blocks = encodeBlocks(rect, rw, rh, state.target());
        GL13.glCompressedTexSubImage2D(GL11.GL_TEXTURE_2D, level, x0, y0, rw, rh, glFormat, blocks);
    }

    private static ByteBuffer encode(FramePixels pixels, int level, int w, int h, CompressionPipeline.Target target) {
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
        return encodeBlocks(rgba, w, h, target);
    }

    /**
     * The encoder the rest of the atlas got (native when available, same quality
     * preset), so animated sprites look like static ones. The plain software
     * encoder used before lost a lot on blocks mixing transparent and opaque
     * texels (fire, lanterns, campfires: under 20 dB, found by the self-test).
     */
    private static ByteBuffer encodeBlocks(ByteBuffer rgba, int w, int h, CompressionPipeline.Target target) {
        boolean bc7 = target == CompressionPipeline.Target.BC7;
        ByteBuffer blocks = (w & 3) == 0 && (h & 3) == 0
                ? CompressionPipeline.compressBlocking("animation", rgba, w, h, bc7, Config.get(Config.COMPRESSION_QUALITY))
                : null;
        if (blocks == null) {
            ByteBuffer copy = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
            copy.put(rgba.duplicate()).flip();
            TransparentTexelBleed.apply(copy, w, h);
            blocks = bc7 ? SoftwareBc7Encoder.encode(copy, w, h) : SoftwareBc1Encoder.encode(copy, w, h);
        }
        return blocks;
    }
}
