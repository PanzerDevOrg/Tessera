package com.panzer.mods.tessera.compress.software;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure-Java BC7 block encoder, mode 6 only (single subset, no partitions,
 * no rotation, 7.7.7.7 RGBA endpoints with one shared p-bit per endpoint,
 * 4-bit indices). This is the "lowest level possible" fallback for the
 * alpha atlas bucket when {@code TesseraNativeBridge} (native bc7enc_rdo
 * via FFM) isn't available -- see {@code TesseraRuntime}.
 *
 * <p>Mode 6 is the simplest full-alpha-precision BC7 mode, which is exactly
 * why it's the one implemented here: every other mode adds partitioning
 * (2-3 subsets with a 64-entry shape table), per-subset rotation, or index
 * selection, none of which changes format correctness, only fidelity on
 * blocks with sharp internal color boundaries. Reproducing bc7enc_rdo's
 * full uber-quality mode search and rate-distortion optimization in Java
 * is out of scope for a compatibility fallback -- that's the whole reason
 * BC7 stays a native call in the first place; this class only has to
 * produce a spec-correct stream, not match its fidelity.
 */
public final class SoftwareBc7Encoder {

    private static final int BYTES_PER_BLOCK = 16;
    private static final int ENDPOINT_BITS = 7;

    // BC7 spec's official 4-bit index interpolation weights (out of 64).
    // Reconstructed component = round(((64-w)*e0 + w*e1) / 64) -- every
    // BC7-capable GPU decodes indices against exactly this table, so the
    // nearest-palette search below must score candidates against it too,
    // not a naive linear 0..15/15 ramp.
    private static final int[] WEIGHTS_4BIT = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    private SoftwareBc7Encoder() {
    }

    public static ByteBuffer encode(ByteBuffer rgba8, int width, int height) {
        int blocksX = BlockMath.blocksAcross(width);
        int blocksY = BlockMath.blocksAcross(height);
        // Direct + little-endian: goes straight to glCompressedTexImage2D with no extra copy,
        // and lets each 128-bit block be emitted as two putLong calls.
        ByteBuffer out = ByteBuffer.allocateDirect(blocksX * blocksY * BYTES_PER_BLOCK).order(ByteOrder.LITTLE_ENDIAN);

        // All per-block state lives here and is reused: zero allocation inside the loop.
        int[] block = new int[BlockMath.BLOCK_TEXELS * 4];
        int[] indices = new int[BlockMath.BLOCK_TEXELS];
        int[] ep0 = new int[ENDPOINT_FIELDS];
        int[] ep1 = new int[ENDPOINT_FIELDS];
        int[] palette = new int[16 * 4];
        long[] bits = new long[2];

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                BlockMath.readBlock(rgba8, width, height, bx, by, block);
                encodeBlockMode6(block, indices, ep0, ep1, palette, bits);
                out.putLong(bits[0]);
                out.putLong(bits[1]);
            }
        }
        out.flip();
        return out;
    }

    // Endpoint layout: 7-bit stored r,g,b,a, p-bit, then reconstructed 8-bit r,g,b,a.
    private static final int ENDPOINT_FIELDS = 9;
    private static final int E_P = 4;
    private static final int E_REC = 5;

    /** Encodes one block into {@code bits} (lo, hi) as a little-endian 128-bit BC7 mode-6 block. */
    static void encodeBlockMode6(int[] block, int[] indices, int[] ep0, int[] ep1, int[] palette, long[] bits) {
        int minR = 255, minG = 255, minB = 255, minA = 255;
        int maxR = 0, maxG = 0, maxB = 0, maxA = 0;
        for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
            int r = block[i * 4], g = block[i * 4 + 1], b = block[i * 4 + 2], a = block[i * 4 + 3];
            minR = Math.min(minR, r);
            minG = Math.min(minG, g);
            minB = Math.min(minB, b);
            minA = Math.min(minA, a);
            maxR = Math.max(maxR, r);
            maxG = Math.max(maxG, g);
            maxB = Math.max(maxB, b);
            maxA = Math.max(maxA, a);
        }

        bestFit(maxR, maxG, maxB, maxA, ep0);
        bestFit(minR, minG, minB, minA, ep1);
        assignIndices(block, ep0, ep1, palette, indices);

        // Anchor canonicalization: index[0] must fit in 3 bits (< 8). The weight table is
        // symmetric (w[i] + w[15-i] == 64), so swapping endpoints and flipping every index
        // reconstructs identical colors.
        int[] e0 = ep0, e1 = ep1;
        if (indices[0] >= 8) {
            e0 = ep1;
            e1 = ep0;
            for (int i = 0; i < indices.length; i++) {
                indices[i] = 15 - indices[i];
            }
        }

        bits[0] = 0L;
        bits[1] = 0L;
        int pos = put(bits, 0, 1 << 6, 7); // mode 6: six zero bits then a one bit
        for (int c = 0; c < 4; c++) {
            pos = put(bits, pos, e0[c], ENDPOINT_BITS);
            pos = put(bits, pos, e1[c], ENDPOINT_BITS);
        }
        pos = put(bits, pos, e0[E_P], 1);
        pos = put(bits, pos, e1[E_P], 1);
        pos = put(bits, pos, indices[0], 3);
        for (int i = 1; i < BlockMath.BLOCK_TEXELS; i++) {
            pos = put(bits, pos, indices[i], 4);
        }
    }

    /** Appends {@code numBits} of {@code value} at bit {@code pos}; fields may straddle the 64-bit boundary. */
    private static int put(long[] bits, int pos, int value, int numBits) {
        long v = value & ((1L << numBits) - 1);
        if (pos < 64) {
            bits[0] |= v << pos;
            if (pos + numBits > 64) {
                bits[1] |= v >>> (64 - pos);
            }
        } else {
            bits[1] |= v << (pos - 64);
        }
        return pos + numBits;
    }

    private static void assignIndices(int[] block, int[] ep0, int[] ep1, int[] palette, int[] outIndices) {
        for (int w = 0; w < 16; w++) {
            int weight = WEIGHTS_4BIT[w];
            for (int c = 0; c < 4; c++) {
                palette[w * 4 + c] = lerp(ep0[E_REC + c], ep1[E_REC + c], weight);
            }
        }

        for (int i = 0; i < BlockMath.BLOCK_TEXELS; i++) {
            int r = block[i * 4], g = block[i * 4 + 1], b = block[i * 4 + 2], a = block[i * 4 + 3];
            int best = 0;
            int bestDist = Integer.MAX_VALUE;
            for (int w = 0; w < 16; w++) {
                int p = w * 4;
                int dist = BlockMath.rgbaDistanceSquared(r, g, b, a, palette[p], palette[p + 1], palette[p + 2], palette[p + 3]);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = w;
                }
            }
            outIndices[i] = best;
        }
    }

    /** Integer form of round(((64 - w) * e0 + w * e1) / 64): no float conversion in the inner loop. */
    private static int lerp(int e0, int e1, int weight64) {
        return ((64 - weight64) * e0 + weight64 * e1 + 32) >> 6;
    }

    /**
     * Picks whichever shared p-bit (0 or 1) minimizes total squared reconstruction error
     * across all four channels and writes the endpoint into {@code out}.
     */
    private static void bestFit(int r8, int g8, int b8, int a8, int[] out) {
        long bestError = Long.MAX_VALUE;
        for (int p = 0; p <= 1; p++) {
            int rv = solveComponent(r8, p);
            int gv = solveComponent(g8, p);
            int bv = solveComponent(b8, p);
            int av = solveComponent(a8, p);
            int rr = (rv << 1) | p, rg = (gv << 1) | p, rb = (bv << 1) | p, ra = (av << 1) | p;
            long error = sqErr(rr, r8) + sqErr(rg, g8) + sqErr(rb, b8) + sqErr(ra, a8);
            if (error < bestError) {
                bestError = error;
                out[0] = rv;
                out[1] = gv;
                out[2] = bv;
                out[3] = av;
                out[E_P] = p;
                out[E_REC] = rr;
                out[E_REC + 1] = rg;
                out[E_REC + 2] = rb;
                out[E_REC + 3] = ra;
            }
        }
    }

    /** Equivalent to Math.round((value8 - pBit) / 2.0f) clamped to [0, 127], in integer math. */
    private static int solveComponent(int value8, int pBit) {
        int v7 = (value8 - pBit + 1) >> 1;
        return Math.max(0, Math.min(127, v7));
    }

    private static long sqErr(int a, int b) {
        long d = a - b;
        return d * d;
    }
}
