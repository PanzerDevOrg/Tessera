package com.panzer.mods.tessera.compress;

import com.mojang.blaze3d.platform.NativeImage;
import com.panzer.mods.tessera.atlas.MipChainBuilder;
import com.panzer.mods.tessera.compress.software.SoftwareBc1Encoder;
import com.panzer.mods.tessera.compress.software.SoftwareBc7Encoder;
import com.panzer.mods.tessera.compress.software.TransparentTexelBleed;
import net.minecraft.client.renderer.texture.SpriteContents;
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
 * <p>Vanilla animates by {@code glTexSubImage2D}-ing RGBA frames into the atlas,
 * which is invalid on a BC texture. Instead, while a compressed atlas cycles its
 * animations, each frame region is encoded to BC blocks and written with
 * {@code glCompressedTexSubImage2D}. That is legal for 4x4-block-aligned regions,
 * which {@link #alignedMipLevels} guarantees by capping the atlas's compressed
 * mip chain.
 *
 * <ul>
 *   <li>Keyframes (frames read straight from the sprite's own mip images) are
 *       encoded once and cached: steady-state cost is one small GPU upload per
 *       sprite per frame change, no CPU encoding.</li>
 *   <li>Interpolated frames (blended per tick into temporary images) are encoded
 *       on the fly. Pure-Java mode-6 BC7 on purpose: a 16x16 sprite is 16 blocks,
 *       where the native encoder's per-call thread-pool spin-up would dominate.</li>
 * </ul>
 *
 * <p>Render thread only: every entry point runs inside
 * {@code TextureAtlas.cycleAnimationFrames} or the atlas upload path.
 */
public final class CompressedAnimationUploader {

    /**
     * @param alignedLevel deepest level at which every animated region is 4x4-block
     *                     aligned (direct per-sprite encode, keyframes cached)
     * @param deepLevels   RGBA copy of each level beyond alignedLevel, patched per
     *                     frame and re-encoded only over the touched blocks
     */
    private record AtlasState(CompressionPipeline.Target target, int maxLevel, int alignedLevel,
                              Map<Integer, MipChainBuilder.MipLevel> deepLevels) {
    }

    /** Compressed atlases by GL texture id. */
    private static final Map<Integer, AtlasState> COMPRESSED_ATLASES = new HashMap<>();
    /** Encoded keyframes per sprite: key = frameX << 32 | frameY, value = blocks per mip level. */
    private static final Map<SpriteContents, Map<Long, ByteBuffer[]>> KEYFRAME_CACHE = new IdentityHashMap<>();

    /** Atlas currently inside cycleAnimationFrames, or null when it is not compressed. */
    private static AtlasState active;
    private static ByteBuffer rgbaScratch = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.LITTLE_ENDIAN);

    private CompressedAnimationUploader() {
    }

    /** Called after a successful compressed upload of {@code textureId}. */
    public static void register(int textureId, CompressionPipeline.Target target, int maxUploadedLevel,
                                int alignedLevel, Map<Integer, MipChainBuilder.MipLevel> deepLevels) {
        // Deep levels without a CPU copy cannot be updated; cap there so a
        // missing copy degrades to fewer levels, never to stale/garbled ones.
        int maxLevel = alignedLevel;
        while (maxLevel < maxUploadedLevel && deepLevels.containsKey(maxLevel + 1)) {
            maxLevel++;
        }
        COMPRESSED_ATLASES.put(textureId, new AtlasState(target, maxLevel, alignedLevel, deepLevels));
    }

    /**
     * Called when vanilla re-uploads the atlas as RGBA (reload). Drops the whole
     * keyframe cache: sprites from the previous reload are dead, and the cache is
     * small enough that rebuilding it lazily is cheaper than tracking ownership.
     */
    public static void unregister(int textureId) {
        if (COMPRESSED_ATLASES.remove(textureId) != null) {
            KEYFRAME_CACHE.clear();
        }
    }

    public static void beginAnimationCycle(int textureId) {
        active = COMPRESSED_ATLASES.get(textureId);
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
     * Replacement for {@code SpriteContents.upload} on a compressed atlas.
     *
     * @return false when the active atlas is not compressed (caller runs vanilla's upload)
     */
    public static boolean upload(SpriteContents contents, int x, int y, int frameX, int frameY, NativeImage[] images) {
        AtlasState state = active;
        if (state == null) {
            return false;
        }
        boolean keyframe = images == contents.byMipLevel;
        ByteBuffer[] cached = null;
        Map<Long, ByteBuffer[]> spriteCache = null;
        long key = ((long) frameX << 32) | (frameY & 0xFFFFFFFFL);
        if (keyframe) {
            spriteCache = KEYFRAME_CACHE.computeIfAbsent(contents, c -> new HashMap<>());
            cached = spriteCache.get(key);
        }
        int levels = Math.min(state.maxLevel(), images.length - 1);
        ByteBuffer[] encoded = cached != null ? cached : new ByteBuffer[levels + 1];
        int glFormat = state.target() == CompressionPipeline.Target.BC7
                ? GL42.GL_COMPRESSED_RGBA_BPTC_UNORM
                : EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT;

        for (int level = 0; level <= levels; level++) {
            int w = contents.width() >> level;
            int h = contents.height() >> level;
            if (w < 1 || h < 1) {
                break; // same guard as vanilla's upload
            }
            if (level > state.alignedLevel()) {
                patchDeepLevel(state, level, images[level], x >> level, y >> level,
                        frameX >> level, frameY >> level, w, h, glFormat);
                continue;
            }
            if (!isBlockAligned(x >> level, y >> level, w, h)) {
                break; // unreachable: alignedLevel guarantees alignment up to here
            }
            ByteBuffer blocks = encoded[level];
            if (blocks == null) {
                blocks = encode(images[level], frameX >> level, frameY >> level, w, h, state.target());
                encoded[level] = blocks;
            }
            GL13.glCompressedTexSubImage2D(GL11.GL_TEXTURE_2D, level, x >> level, y >> level, w, h, glFormat,
                    blocks.duplicate());
        }
        if (keyframe && cached == null) {
            spriteCache.put(key, encoded);
        }
        return true;
    }

    /**
     * Writes the frame's pixels into the retained RGBA copy of {@code level}, then
     * re-encodes and uploads the smallest block-aligned rectangle covering them
     * (neighbouring static pixels come from the copy, so they are preserved).
     */
    private static void patchDeepLevel(AtlasState state, int level, NativeImage image, int dstX, int dstY,
                                       int srcX, int srcY, int w, int h, int glFormat) {
        MipChainBuilder.MipLevel copy = state.deepLevels().get(level);
        int levelWidth = copy.width();
        int levelHeight = copy.height();
        ByteBuffer pixels = copy.rgba8().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                pixels.putInt(((dstY + py) * levelWidth + dstX + px) * 4, image.getPixelRGBA(srcX + px, srcY + py));
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
        TransparentTexelBleed.apply(rect, rw, rh);
        ByteBuffer blocks = state.target() == CompressionPipeline.Target.BC7
                ? SoftwareBc7Encoder.encode(rect, rw, rh)
                : SoftwareBc1Encoder.encode(rect, rw, rh);
        GL13.glCompressedTexSubImage2D(GL11.GL_TEXTURE_2D, level, x0, y0, rw, rh, glFormat, blocks);
    }

    private static ByteBuffer encode(NativeImage image, int srcX, int srcY, int w, int h,
                                     CompressionPipeline.Target target) {
        int bytes = w * h * 4;
        if (rgbaScratch.capacity() < bytes) {
            rgbaScratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        ByteBuffer rgba = rgbaScratch.clear();
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                // getPixelRGBA returns ABGR; written little-endian that is R,G,B,A bytes.
                rgba.putInt(image.getPixelRGBA(srcX + px, srcY + py));
            }
        }
        rgba.flip();
        TransparentTexelBleed.apply(rgba, w, h);
        return target == CompressionPipeline.Target.BC7
                ? SoftwareBc7Encoder.encode(rgba, w, h)
                : SoftwareBc1Encoder.encode(rgba, w, h);
    }
}
