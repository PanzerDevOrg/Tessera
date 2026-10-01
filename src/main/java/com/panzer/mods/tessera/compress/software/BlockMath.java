package com.panzer.mods.tessera.compress.software;

import java.nio.ByteBuffer;

/**
 * Shared 4x4-block helpers for {@link SoftwareBc1Encoder} and
 * {@link SoftwareBc7Encoder}. This is the pure-Java, lowest-level fallback
 * path -- no native call, no SIMD (a single 4x4=16-texel block is too
 * small for {@code jdk.incubator.vector} to pay for itself over scalar
 * code; see the package-level note in {@code SoftwareCompressionBackend}
 * for where this fallback path *does* use Celeris's SIMD facade).
 */
final class BlockMath {

    static final int BLOCK_DIM = 4;
    static final int BLOCK_TEXELS = BLOCK_DIM * BLOCK_DIM;

    private BlockMath() {
    }

    /**
     * Reads one 4x4 RGBA8 block starting at pixel {@code (blockX*4, blockY*4)}
     * out of {@code rgba8}, clamping to the image edge for partial blocks
     * (the last block of a non-multiple-of-4 atlas repeats its edge texel,
     * matching what every block-compression format's encoders do).
     *
     * @param out must be {@code BLOCK_TEXELS * 4} ints long; filled as
     *            {@code out[i*4 + c]} = channel {@code c} (0=R,1=G,2=B,3=A)
     *            of texel {@code i}, row-major within the block.
     */
    static void readBlock(ByteBuffer rgba8, int width, int height, int blockX, int blockY, int[] out) {
        int baseX = blockX * BLOCK_DIM;
        int baseY = blockY * BLOCK_DIM;
        for (int y = 0; y < BLOCK_DIM; y++) {
            int srcY = Math.min(baseY + y, height - 1);
            for (int x = 0; x < BLOCK_DIM; x++) {
                int srcX = Math.min(baseX + x, width - 1);
                int srcOffset = (srcY * width + srcX) * 4;
                int outOffset = (y * BLOCK_DIM + x) * 4;
                out[outOffset] = rgba8.get(srcOffset) & 0xFF;
                out[outOffset + 1] = rgba8.get(srcOffset + 1) & 0xFF;
                out[outOffset + 2] = rgba8.get(srcOffset + 2) & 0xFF;
                out[outOffset + 3] = rgba8.get(srcOffset + 3) & 0xFF;
            }
        }
    }

    static int blocksAcross(int dimension) {
        return (dimension + BLOCK_DIM - 1) / BLOCK_DIM;
    }

    /** Squared Euclidean distance between two RGB triples (no alpha). */
    static int rgbDistanceSquared(int r0, int g0, int b0, int r1, int g1, int b1) {
        int dr = r0 - r1;
        int dg = g0 - g1;
        int db = b0 - b1;
        return dr * dr + dg * dg + db * db;
    }

    /** Squared Euclidean distance between two RGBA quads. */
    static int rgbaDistanceSquared(int r0, int g0, int b0, int a0, int r1, int g1, int b1, int a1) {
        int dr = r0 - r1;
        int dg = g0 - g1;
        int db = b0 - b1;
        int da = a0 - a1;
        return dr * dr + dg * dg + db * db + da * da;
    }

    static int quantizeTo(int value8, int bits) {
        int maxOut = (1 << bits) - 1;
        int maxIn = 255;
        return Math.round(value8 * (float) maxOut / maxIn);
    }

    /**
     * Bit-replication expand of a {@code bits}-wide component (5, 6, or 7
     * bits here) up to 8 bits -- the standard, bias-free way block
     * compression formats round-trip endpoints, matching what every
     * BC1/BC7 hardware decoder does. Only valid for {@code bits} in
     * {@code [4, 8)}; this fallback only ever calls it with 5, 6, or 7.
     */
    static int expandFrom(int valueBits, int bits) {
        return (valueBits << (8 - bits)) | (valueBits >>> (2 * bits - 8));
    }
}
