package com.panzer.mods.tessera.compress.software;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure-Java BC1 (DXT1) block encoder. This is the "lowest level possible"
 * fallback for the opaque atlas bucket when {@code TesseraNativeBridge}
 * (native bc7enc_rdo, called via FFM) isn't available on this platform --
 * see {@code TesseraRuntime}.
 *
 * <p>Deliberately simple: a bounding-box endpoint fit (min/max per RGB
 * channel) plus nearest-color index assignment, always in 4-color mode.
 * This is meaningfully lower fidelity than bc7enc_rdo's rate-distortion
 * optimized encoder -- there is no principal-axis fit, no cluster
 * refinement, no multithreading -- but it produces a spec-correct BC1
 * stream with no native dependency at all, which is the actual point of
 * this class existing.
 */
public final class SoftwareBc1Encoder {

    private static final int BYTES_PER_BLOCK = 8;

    private SoftwareBc1Encoder() {
    }

    public static ByteBuffer encode(ByteBuffer rgba8, int width, int height) {
        int blocksX = BlockMath.blocksAcross(width);
        int blocksY = BlockMath.blocksAcross(height);
        // Direct: uploaded via glCompressedTexImage2D without an extra copy.
        ByteBuffer out = ByteBuffer.allocateDirect(blocksX * blocksY * BYTES_PER_BLOCK).order(ByteOrder.LITTLE_ENDIAN);

        int[] block = new int[BlockMath.BLOCK_TEXELS * 4];
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                BlockMath.readBlock(rgba8, width, height, bx, by, block);
                encodeBlock(block, out);
            }
        }
        out.flip();
        return out;
    }

    private static void encodeBlock(int[] block, ByteBuffer out) {
        int minR = 255, minG = 255, minB = 255;
        int maxR = 0, maxG = 0, maxB = 0;
        for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
            int r = block[i * 4], g = block[i * 4 + 1], b = block[i * 4 + 2];
            minR = Math.min(minR, r);
            minG = Math.min(minG, g);
            minB = Math.min(minB, b);
            maxR = Math.max(maxR, r);
            maxG = Math.max(maxG, g);
            maxB = Math.max(maxB, b);
        }

        int c0_565 = pack565(maxR, maxG, maxB);
        int c1_565 = pack565(minR, minG, minB);
        // BC1 4-color mode requires c0 > c1 as packed uint16 -- our bucket
        // is the opaque one, so we always want 4-color mode, never the
        // 3-color + punch-through-alpha mode a tie here would otherwise
        // select on some decoders.
        if (c0_565 <= c1_565) {
            if (c0_565 == c1_565) {
                // Flat block: make c1 strictly smaller so c0 > c1 holds. With
                // c0 <= c1 the block would decode in 3-color mode, where index 3
                // is transparent black.
                if (c0_565 == 0) {
                    c0_565 = 1;
                } else {
                    c1_565 = c0_565 - 1;
                }
            } else {
                int tmp = c0_565;
                c0_565 = c1_565;
                c1_565 = tmp;
            }
        }

        // Unpacked into locals: no per-block array allocation.
        int c0r = expandR(c0_565), c0g = expandG(c0_565), c0b = expandB(c0_565);
        int c1r = expandR(c1_565), c1g = expandG(c1_565), c1b = expandB(c1_565);
        // Palette colors 2/3 are computed in the *expanded* 8-bit domain,
        // matching what every BC1 hardware decoder does -- an encoder that
        // instead lerps in 5/6-bit space before expanding would pick
        // indices optimal for a palette the decoder never actually
        // produces.
        int r2 = (2 * c0r + c1r) / 3, g2 = (2 * c0g + c1g) / 3, b2 = (2 * c0b + c1b) / 3;
        int r3 = (c0r + 2 * c1r) / 3, g3 = (c0g + 2 * c1g) / 3, b3 = (c0b + 2 * c1b) / 3;

        int indices = 0;
        for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
            int r = block[i * 4], g = block[i * 4 + 1], b = block[i * 4 + 2];
            int d0 = BlockMath.rgbDistanceSquared(r, g, b, c0r, c0g, c0b);
            int d1 = BlockMath.rgbDistanceSquared(r, g, b, c1r, c1g, c1b);
            int d2 = BlockMath.rgbDistanceSquared(r, g, b, r2, g2, b2);
            int d3 = BlockMath.rgbDistanceSquared(r, g, b, r3, g3, b3);
            int best = 0;
            int bestDist = d0;
            if (d1 < bestDist) {
                best = 1;
                bestDist = d1;
            }
            if (d2 < bestDist) {
                best = 2;
                bestDist = d2;
            }
            if (d3 < bestDist) {
                best = 3;
            }
            indices |= best << (i * 2);
        }

        out.putShort((short) c0_565);
        out.putShort((short) c1_565);
        out.putInt(indices);
    }

    private static int pack565(int r, int g, int b) {
        int r5 = BlockMath.quantizeTo(r, 5);
        int g6 = BlockMath.quantizeTo(g, 6);
        int b5 = BlockMath.quantizeTo(b, 5);
        return (r5 << 11) | (g6 << 5) | b5;
    }

    private static int expandR(int packed) {
        return BlockMath.expandFrom((packed >>> 11) & 0x1F, 5);
    }

    private static int expandG(int packed) {
        return BlockMath.expandFrom((packed >>> 5) & 0x3F, 6);
    }

    private static int expandB(int packed) {
        return BlockMath.expandFrom(packed & 0x1F, 5);
    }
}
