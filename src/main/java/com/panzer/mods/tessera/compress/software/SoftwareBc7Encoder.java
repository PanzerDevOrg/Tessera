package com.panzer.mods.tessera.compress.software;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure-Java BC7 block encoder: the fallback when the native encoder is not
 * loaded, and what animated frames use on the render thread (where the native
 * encoder's per-call cost is too high).
 *
 * <p>Single-subset modes only, no partitions and no rotation:
 * <ul>
 *   <li>mode 6 (RGBA endpoints, 4-bit indices shared by colour and alpha) for
 *       every block;</li>
 *   <li>modes 5 and 4 as well when the block's alpha varies: colour and alpha get
 *       their own indices, so a block mixing transparent and opaque texels (fire,
 *       lanterns, sculk veins) keeps both its colours and its exact cut-out
 *       instead of spending every index on the alpha step.</li>
 * </ul>
 * Each candidate's endpoints come from the principal axis of the block's colours,
 * refined by least squares against the chosen indices; the candidate with the
 * smallest error wins. Colour error counts much less on fully transparent texels.
 * The native encoder (bc7enc_rdo) still does better on blocks with sharp internal
 * edges (partitions), which is why it stays the default for whole atlases.
 */
public final class SoftwareBc7Encoder {

    private static final int BYTES_PER_BLOCK = 16;
    private static final int TEXELS = BlockMath.BLOCK_TEXELS;

    // BC7 interpolation weights (out of 64) for 2-, 3- and 4-bit indices: every
    // decoder reconstructs round(((64 - w) * e0 + w * e1) / 64) against these.
    private static final int[] W2 = {0, 21, 43, 64};
    private static final int[] W3 = {0, 9, 18, 27, 37, 46, 55, 64};
    private static final int[] W4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    /** Error weight of a texel's colour when visible, and when fully transparent; alpha always counts as visible. */
    private static final int VISIBLE = 16, HIDDEN = 1;
    private static final int REFINE_PASSES = 1;

    /** Per weight table: for t in 0..64 (a texel's position along the line, in 64ths), the index whose weight is nearest. */
    private static final int[] NEAREST2 = nearest(W2), NEAREST3 = nearest(W3), NEAREST4 = nearest(W4);

    private static int[] nearest(int[] weights) {
        int[] table = new int[65];
        for (int t = 0; t <= 64; t++) {
            int best = 0;
            for (int k = 1; k < weights.length; k++) {
                if (Math.abs(weights[k] - t) < Math.abs(weights[best] - t)) {
                    best = k;
                }
            }
            table[t] = best;
        }
        return table;
    }

    private SoftwareBc7Encoder() {
    }

    public static ByteBuffer encode(ByteBuffer rgba8, int width, int height) {
        int blocksX = BlockMath.blocksAcross(width);
        int blocksY = BlockMath.blocksAcross(height);
        // Direct + little-endian: goes straight to glCompressedTex(Sub)Image2D, each
        // 128-bit block written as two putLong calls.
        ByteBuffer out = ByteBuffer.allocateDirect(blocksX * blocksY * BYTES_PER_BLOCK).order(ByteOrder.LITTLE_ENDIAN);
        Encoder encoder = new Encoder();
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                BlockMath.readBlock(rgba8, width, height, bx, by, encoder.block);
                encoder.encodeBlock();
                out.putLong(encoder.bits[0]);
                out.putLong(encoder.bits[1]);
            }
        }
        out.flip();
        return out;
    }

    /** Scratch for one {@link #encode} call (callers run on several threads), reused for every block. */
    static final class Encoder {
        final int[] block = new int[TEXELS * 4];
        final long[] bits = new long[2];

        private final int[] colourWeight = new int[TEXELS];

        // Line fit of channels [first, first + count) of the block, against a weight table.
        private final int[] idx = new int[TEXELS];
        private final int[] bestIdx = new int[TEXELS];
        private final float[] e0f = new float[4], e1f = new float[4];
        private final int[] q0 = new int[5], q1 = new int[5];          // stored values, [4] = mode 6 p-bit
        private final int[] r0 = new int[4], r1 = new int[4];          // reconstructed 8-bit endpoints
        private final int[] best0 = new int[5], best1 = new int[5];
        private final int[] bestR0 = new int[4], bestR1 = new int[4];

        // Mode 4/5 colour and alpha parts, kept while the other part is fitted.
        private final int[] colourIdx = new int[TEXELS], alphaIdx = new int[TEXELS];
        private final int[] colour0 = new int[3], colour1 = new int[3];
        private int alpha0, alpha1;

        private final long[] candidate = new long[2];

        // principalAxisEndpoints scratch
        private final float[] mean = new float[4], cov = new float[16], axis = new float[4], next = new float[4];
        private final float[] lo = new float[4], hi = new float[4];
        private final int[] pal = new int[16 * 4];

        void encodeBlock() {
            int minA = 255, maxA = 0;
            for (int i = 0; i < TEXELS; i++) {
                int a = block[i * 4 + 3];
                minA = Math.min(minA, a);
                maxA = Math.max(maxA, a);
                colourWeight[i] = a == 0 ? HIDDEN : VISIBLE;
            }
            long bestError = encodeMode6(bits);
            if (minA != maxA) {
                long error = encodeMode5(candidate);
                if (error < bestError) {
                    bestError = error;
                    bits[0] = candidate[0];
                    bits[1] = candidate[1];
                }
                error = encodeMode4(candidate);
                if (error < bestError) {
                    bits[0] = candidate[0];
                    bits[1] = candidate[1];
                }
            }
        }

        // ---------------------------------------------------------------- mode 6

        private long encodeMode6(long[] out) {
            long error = fit(0, 4, W4, 7, true);
            int[] e0 = best0, e1 = best1;
            int[] indices = bestIdx;
            if (indices[0] >= 8) { // anchor index stores 3 bits: swap ends, flip indices
                e0 = best1;
                e1 = best0;
                flip(indices, 15);
            }
            out[0] = 0L;
            out[1] = 0L;
            int pos = put(out, 0, 1 << 6, 7);
            for (int c = 0; c < 4; c++) {
                pos = put(out, pos, e0[c], 7);
                pos = put(out, pos, e1[c], 7);
            }
            pos = put(out, pos, e0[4], 1);
            pos = put(out, pos, e1[4], 1);
            pos = putIndices(out, pos, indices, 4);
            return error;
        }

        // ---------------------------------------------------------------- mode 5: 7-bit colour, 8-bit alpha, 2-bit indices each

        private long encodeMode5(long[] out) {
            long error = fitColourAndAlpha(W2, 7, W2, 8);
            out[0] = 0L;
            out[1] = 0L;
            int pos = put(out, 0, 1 << 5, 6);
            pos = put(out, pos, 0, 2); // rotation
            for (int c = 0; c < 3; c++) {
                pos = put(out, pos, colour0[c], 7);
                pos = put(out, pos, colour1[c], 7);
            }
            pos = put(out, pos, alpha0, 8);
            pos = put(out, pos, alpha1, 8);
            pos = putIndices(out, pos, colourIdx, 2);
            putIndices(out, pos, alphaIdx, 2);
            return error;
        }

        // ---------------------------------------------------------------- mode 4: 5-bit colour with 3-bit indices, 6-bit alpha with 2-bit indices

        private long encodeMode4(long[] out) {
            long error = fitColourAndAlpha(W3, 5, W2, 6);
            out[0] = 0L;
            out[1] = 0L;
            int pos = put(out, 0, 1 << 4, 5);
            pos = put(out, pos, 0, 2); // rotation
            pos = put(out, pos, 1, 1); // index selection: colour takes the 3-bit indices
            for (int c = 0; c < 3; c++) {
                pos = put(out, pos, colour0[c], 5);
                pos = put(out, pos, colour1[c], 5);
            }
            pos = put(out, pos, alpha0, 6);
            pos = put(out, pos, alpha1, 6);
            pos = putIndices(out, pos, alphaIdx, 2);  // first index section: 2-bit
            putIndices(out, pos, colourIdx, 3);       // second index section: 3-bit
            return error;
        }

        /** Colour and alpha fitted separately (modes 4 and 5); results in colour0/1, alpha0/1 and their indices. */
        private long fitColourAndAlpha(int[] colourWeights, int colourBits, int[] alphaWeights, int alphaBits) {
            long error = fit(0, 3, colourWeights, colourBits, false);
            int top = colourWeights.length - 1;
            if (bestIdx[0] > top / 2) {
                swapBest(3);
                flip(bestIdx, top);
            }
            System.arraycopy(best0, 0, colour0, 0, 3);
            System.arraycopy(best1, 0, colour1, 0, 3);
            System.arraycopy(bestIdx, 0, colourIdx, 0, TEXELS);

            error += fit(3, 1, alphaWeights, alphaBits, false);
            top = alphaWeights.length - 1;
            if (bestIdx[0] > top / 2) {
                swapBest(1);
                flip(bestIdx, top);
            }
            alpha0 = best0[0];
            alpha1 = best1[0];
            System.arraycopy(bestIdx, 0, alphaIdx, 0, TEXELS);
            return error;
        }

        private void swapBest(int channels) {
            for (int c = 0; c < channels; c++) {
                int t = best0[c];
                best0[c] = best1[c];
                best1[c] = t;
            }
        }

        // ---------------------------------------------------------------- line fit

        /**
         * Fits a line through channels {@code [first, first + count)} of the block:
         * principal axis, then least-squares refinement against the chosen indices.
         * Best stored endpoints go to best0/best1 (channel {@code c} at {@code [c]},
         * mode 6's p-bit at {@code [4]}), indices to bestIdx.
         *
         * @param bits  stored bits per channel
         * @param mode6 7 bits plus mode 6's p-bit shared by the endpoint's channels
         * @return weighted squared error of the best fit
         */
        private long fit(int first, int count, int[] weights, int bits, boolean mode6) {
            principalAxisEndpoints(first, count);
            long bestError = Long.MAX_VALUE;
            for (int pass = 0; ; pass++) {
                quantize(e0f, q0, r0, count, bits, mode6);
                quantize(e1f, q1, r1, count, bits, mode6);
                long error = assign(first, count, weights);
                if (error < bestError) {
                    bestError = error;
                    System.arraycopy(q0, 0, best0, 0, 5);
                    System.arraycopy(q1, 0, best1, 0, 5);
                    System.arraycopy(r0, 0, bestR0, 0, 4);
                    System.arraycopy(r1, 0, bestR1, 0, 4);
                    System.arraycopy(idx, 0, bestIdx, 0, TEXELS);
                }
                if (pass == REFINE_PASSES || error == 0 || !leastSquares(first, count, weights)) {
                    return bestError;
                }
            }
        }

        private int channelWeight(int texel, int channel) {
            return channel == 3 ? VISIBLE : colourWeight[texel];
        }

        /** e0f/e1f: the extremes of the block's texels along the weighted principal axis. */
        private void principalAxisEndpoints(int first, int count) {
            java.util.Arrays.fill(cov, 0f);
            java.util.Arrays.fill(lo, 255f);
            java.util.Arrays.fill(hi, 0f);
            for (int c = 0; c < count; c++) {
                float sum = 0, total = 0;
                for (int i = 0; i < TEXELS; i++) {
                    int w = channelWeight(i, first + c);
                    sum += w * block[i * 4 + first + c];
                    total += w;
                }
                mean[c] = sum / total;
            }
            // Without alpha in the fit, hidden texels neither steer nor stretch the line.
            boolean colourOnly = first + count <= 3;
            // Weighted covariance, then a few power iterations from the bounding-box diagonal.
            for (int i = 0; i < TEXELS; i++) {
                float w = colourOnly ? colourWeight[i] : VISIBLE;
                for (int c = 0; c < count; c++) {
                    float dc = block[i * 4 + first + c] - mean[c];
                    for (int d = c; d < count; d++) {
                        cov[c * 4 + d] += w * dc * (block[i * 4 + first + d] - mean[d]);
                    }
                    if (w == VISIBLE) {
                        lo[c] = Math.min(lo[c], block[i * 4 + first + c]);
                        hi[c] = Math.max(hi[c], block[i * 4 + first + c]);
                    }
                }
            }
            for (int c = 0; c < count; c++) {
                for (int d = 0; d < c; d++) {
                    cov[c * 4 + d] = cov[d * 4 + c];
                }
                axis[c] = hi[c] >= lo[c] ? hi[c] - lo[c] + 1 : 1;
            }
            for (int iter = 0; iter < 4; iter++) {
                float norm = 0;
                for (int c = 0; c < count; c++) {
                    next[c] = 0;
                    for (int d = 0; d < count; d++) {
                        next[c] += cov[c * 4 + d] * axis[d];
                    }
                    norm = Math.max(norm, Math.abs(next[c]));
                }
                if (norm < 1e-6f) {
                    break; // every texel the same colour: the axis does not matter
                }
                for (int c = 0; c < count; c++) {
                    axis[c] = next[c] / norm;
                }
            }
            float tMin = Float.MAX_VALUE, tMax = -Float.MAX_VALUE;
            float axisLength2 = 0;
            for (int c = 0; c < count; c++) {
                axisLength2 += axis[c] * axis[c];
            }
            boolean anyVisible = false;
            for (int i = 0; i < TEXELS && !anyVisible; i++) {
                anyVisible = colourWeight[i] == VISIBLE;
            }
            for (int i = 0; i < TEXELS; i++) {
                if (colourOnly && anyVisible && colourWeight[i] != VISIBLE) {
                    continue;
                }
                float t = 0;
                for (int c = 0; c < count; c++) {
                    t += (block[i * 4 + first + c] - mean[c]) * axis[c];
                }
                tMin = Math.min(tMin, t);
                tMax = Math.max(tMax, t);
            }
            if (axisLength2 < 1e-12f || tMin > tMax) {
                tMin = tMax = 0;
                axisLength2 = 1;
            }
            for (int c = 0; c < count; c++) {
                e0f[c] = clamp255(mean[c] + axis[c] * tMin / axisLength2);
                e1f[c] = clamp255(mean[c] + axis[c] * tMax / axisLength2);
            }
        }

        /**
         * Indices minimizing weighted error against the reconstructed endpoints r0/r1;
         * returns the error. Each texel is projected on the line, then the nearest
         * index and its two neighbours are scored exactly.
         */
        private long assign(int first, int count, int[] weights) {
            int levels = weights.length;
            int[] nearest = levels == 4 ? NEAREST2 : levels == 8 ? NEAREST3 : NEAREST4;
            for (int k = 0; k < levels; k++) {
                int w = weights[k];
                for (int c = 0; c < count; c++) {
                    pal[k * 4 + c] = ((64 - w) * r0[c] + w * r1[c] + 32) >> 6;
                }
            }
            long total = 0;
            for (int i = 0; i < TEXELS; i++) {
                int o = i * 4 + first;
                long num = 0, den = 0;
                for (int c = 0; c < count; c++) {
                    int w = channelWeight(i, first + c);
                    int d = r1[c] - r0[c];
                    num += (long) w * (block[o + c] - r0[c]) * d;
                    den += (long) w * d * d;
                }
                int t = den == 0 ? 0 : (int) Math.max(0, Math.min(64, (num * 64 + den / 2) / den));
                int k0 = nearest[t];
                long bestError = Long.MAX_VALUE;
                int best = k0;
                for (int k = Math.max(0, k0 - 1); k <= Math.min(levels - 1, k0 + 1); k++) {
                    long error = 0;
                    for (int c = 0; c < count; c++) {
                        int d = pal[k * 4 + c] - block[o + c];
                        error += (long) channelWeight(i, first + c) * d * d;
                    }
                    if (error < bestError) {
                        bestError = error;
                        best = k;
                    }
                }
                idx[i] = best;
                total += bestError;
            }
            return total;
        }

        /** New e0f/e1f: per-channel least squares with the indices in idx fixed. False when degenerate. */
        private boolean leastSquares(int first, int count, int[] weights) {
            boolean any = false;
            for (int c = 0; c < count; c++) {
                double a = 0, b = 0, cc = 0, d0 = 0, d1 = 0;
                for (int i = 0; i < TEXELS; i++) {
                    double w = channelWeight(i, first + c);
                    double t = weights[idx[i]] / 64.0;
                    double x = block[i * 4 + first + c];
                    a += w * (1 - t) * (1 - t);
                    b += w * (1 - t) * t;
                    cc += w * t * t;
                    d0 += w * (1 - t) * x;
                    d1 += w * t * x;
                }
                double det = a * cc - b * b;
                if (Math.abs(det) < 1e-9) {
                    continue;
                }
                e0f[c] = clamp255((float) ((cc * d0 - b * d1) / det));
                e1f[c] = clamp255((float) ((a * d1 - b * d0) / det));
                any = true;
            }
            return any;
        }

        /**
         * Stored value per channel ({@code stored[c]}) and its 8-bit reconstruction
         * ({@code rec[c]}). Mode 6: 7 bits plus one p-bit shared by the endpoint's
         * channels, picked for the smaller error.
         */
        private static void quantize(float[] e, int[] stored, int[] rec, int count, int bits, boolean mode6) {
            if (mode6) {
                float bestError = Float.MAX_VALUE;
                for (int p = 0; p <= 1; p++) {
                    float error = 0;
                    for (int c = 0; c < count; c++) {
                        int v = Math.max(0, Math.min(127, Math.round((e[c] - p) / 2f)));
                        float d = ((v << 1) | p) - e[c];
                        error += d * d;
                    }
                    if (error < bestError) {
                        bestError = error;
                        for (int c = 0; c < count; c++) {
                            int v = Math.max(0, Math.min(127, Math.round((e[c] - p) / 2f)));
                            stored[c] = v;
                            rec[c] = (v << 1) | p;
                        }
                        stored[4] = p;
                    }
                }
                return;
            }
            int max = (1 << bits) - 1;
            for (int c = 0; c < count; c++) {
                int base = (int) Math.floor(e[c] * max / 255f);
                int bestV = 0;
                float bestError = Float.MAX_VALUE;
                for (int v = Math.max(0, base - 1); v <= Math.min(max, base + 2); v++) {
                    float d = expand(v, bits) - e[c];
                    if (d * d < bestError) {
                        bestError = d * d;
                        bestV = v;
                    }
                }
                stored[c] = bestV;
                rec[c] = expand(bestV, bits);
            }
        }

        private static int expand(int v, int bits) {
            return bits == 8 ? v : (v << (8 - bits)) | (v >>> (2 * bits - 8));
        }

        private static float clamp255(float v) {
            return Math.max(0f, Math.min(255f, v));
        }

        private static void flip(int[] indices, int top) {
            for (int i = 0; i < TEXELS; i++) {
                indices[i] = top - indices[i];
            }
        }

        /** The 16 indices, the first (anchor) one bit shorter: its top bit is implied 0. */
        private static int putIndices(long[] out, int pos, int[] indices, int bits) {
            pos = put(out, pos, indices[0], bits - 1);
            for (int i = 1; i < TEXELS; i++) {
                pos = put(out, pos, indices[i], bits);
            }
            return pos;
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
}
