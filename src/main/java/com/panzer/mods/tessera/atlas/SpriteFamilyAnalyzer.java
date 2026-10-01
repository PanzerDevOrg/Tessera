package com.panzer.mods.tessera.atlas;

import com.panzer.mods.celeris.api.simd.VectorOperations;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Sprite-family grouping, mip generation and alpha classification for atlas
 * assembly. Plain per-pixel arithmetic (box-filter averaging, an 8x8 DCT,
 * alpha scanning) with no OS or GPU dependency, so it stays in Java.
 *
 * <p>SIMD (through Celeris's {@link VectorOperations}, which falls back to
 * scalar code when {@code jdk.incubator.vector} is unavailable) is applied
 * only to {@link #downsampleBoxFilter}, which processes whole atlas rows. The
 * DCT fingerprint and per-sprite alpha scan touch at most 64 elements per
 * call, too little for vectorization to pay off, so they stay scalar.
 */
public final class SpriteFamilyAnalyzer {

    private static final int CHANNELS = 4;
    private static final int DCT_SIZE = 8;
    private static final double[][] COSINE_TABLE = buildCosineTable();

    private SpriteFamilyAnalyzer() {
    }

    // ----------------------------------------------------------------
    // Box-filter downsampling (SIMD-accelerated: whole rows at a time)
    // ----------------------------------------------------------------

    /** 2x box-filter downsample, edge-clamped. Returns {pixels, width, height} packed as a record. */
    public record DownsampleResult(byte[] pixels, int width, int height) {
    }

    public static DownsampleResult downsampleBoxFilter(byte[] pixels, int width, int height) {
        int w = Math.max(width, 1);
        int h = Math.max(height, 1);
        int outW = Math.max(w / 2, 1);
        int outH = Math.max(h / 2, 1);
        byte[] output = new byte[outW * outH * CHANNELS];

        // Reused per output row: four interleaved-channel float rows (the
        // two source rows' even/odd-column contributions), summed and
        // scaled via VectorOperations across the whole row width at once
        // rather than per-texel scalar arithmetic.
        float[] topSum = new float[outW * CHANNELS];
        float[] bottomSum = new float[outW * CHANNELS];

        for (int outY = 0; outY < outH; outY++) {
            int y0 = Math.min(outY * 2, h - 1);
            int y1 = Math.min(outY * 2 + 1, h - 1);

            fillRowPairSum(pixels, w, h, y0, outW, topSum);
            fillRowPairSum(pixels, w, h, y1, outW, bottomSum);

            VectorOperations.addInPlace(topSum, bottomSum, outW * CHANNELS);
            // Box filter of 4 texels: (sum + 2) / 4, matching the Rust
            // implementation's integer rounding exactly (add half the
            // divisor before truncating) rather than plain float rounding,
            // so results agree at the boundary cases too.
            VectorOperations.addScalarInPlace(topSum, 2.0f, outW * CHANNELS);
            VectorOperations.multiplyScalarInPlace(topSum, 0.25f, outW * CHANNELS);

            int rowOffset = outY * outW * CHANNELS;
            for (int i = 0; i < outW * CHANNELS; i++) {
                output[rowOffset + i] = (byte) clampByte((int) topSum[i]);
            }
        }

        return new DownsampleResult(output, outW, outH);
    }

    /** Sums the two horizontally-paired source texels (x0,x1) for every output column of one source row into {@code out}. */
    private static void fillRowPairSum(byte[] pixels, int width, int height, int srcY, int outW, float[] out) {
        for (int outX = 0; outX < outW; outX++) {
            int x0 = Math.min(outX * 2, width - 1);
            int x1 = Math.min(outX * 2 + 1, width - 1);
            int base0 = (srcY * width + x0) * CHANNELS;
            int base1 = (srcY * width + x1) * CHANNELS;
            int outBase = outX * CHANNELS;
            for (int c = 0; c < CHANNELS; c++) {
                int v0 = base0 + c < pixels.length ? pixels[base0 + c] & 0xFF : 0;
                int v1 = base1 + c < pixels.length ? pixels[base1 + c] & 0xFF : 0;
                out[outBase + c] = v0 + v1;
            }
        }
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** Repeated {@link #downsampleBoxFilter} down to a 1x1 level or {@code maxLevel} steps, whichever comes first. */
    public static List<DownsampleResult> buildMipChain(byte[] pixels, int width, int height, int maxLevel) {
        List<DownsampleResult> chain = new ArrayList<>(maxLevel + 1);
        chain.add(new DownsampleResult(pixels, width, height));
        for (int i = 0; i < maxLevel; i++) {
            DownsampleResult prev = chain.get(chain.size() - 1);
            if (prev.width() <= 1 && prev.height() <= 1) {
                break;
            }
            chain.add(downsampleBoxFilter(prev.pixels(), prev.width(), prev.height()));
        }
        return chain;
    }

    // ----------------------------------------------------------------
    // Alpha shape classification
    // ----------------------------------------------------------------

    public enum AlphaShape {FULLY_OPAQUE, PUNCH_THROUGH, BLENDED}

    public static AlphaShape classifyAlphaShape(byte[] rgba8, int srcOffset, int width, int height) {
        boolean sawTransparent = false;
        boolean sawOpaque = false;

        for (int y = 0; y < height; y++) {
            int rowStart = srcOffset + y * width * CHANNELS;
            int rowEnd = rowStart + width * CHANNELS;
            if (rowEnd > rgba8.length) {
                break;
            }
            for (int x = 0; x < width; x++) {
                int alpha = rgba8[rowStart + x * CHANNELS + 3] & 0xFF;
                if (alpha == 0) {
                    sawTransparent = true;
                } else if (alpha == 255) {
                    sawOpaque = true;
                } else {
                    return AlphaShape.BLENDED;
                }
            }
        }

        if (sawTransparent) {
            return AlphaShape.PUNCH_THROUGH;
        }
        return AlphaShape.FULLY_OPAQUE; // includes the empty-sprite case, same as the Rust original
    }

    public static boolean hasAlpha(byte[] rgba8, int srcOffset, int width, int height) {
        return classifyAlphaShape(rgba8, srcOffset, width, height) != AlphaShape.FULLY_OPAQUE;
    }

    // ----------------------------------------------------------------
    // Perceptual fingerprint (8x8 DCT, pHash-style) + family grouping
    // ----------------------------------------------------------------

    private static double[][] buildCosineTable() {
        double[][] table = new double[DCT_SIZE][DCT_SIZE];
        for (int x = 0; x < DCT_SIZE; x++) {
            for (int u = 0; u < DCT_SIZE; u++) {
                table[x][u] = Math.cos(((2.0 * x + 1.0) * u * Math.PI) / (2.0 * DCT_SIZE));
            }
        }
        return table;
    }

    private static double[][] downsampleToLuminance(byte[] rgba8, int srcOffset, int width, int height) {
        double[][] luminance = new double[DCT_SIZE][DCT_SIZE];
        for (int by = 0; by < DCT_SIZE; by++) {
            int startY = (by * height) / DCT_SIZE;
            int endY = Math.max(((by + 1) * height) / DCT_SIZE, startY + 1);
            for (int bx = 0; bx < DCT_SIZE; bx++) {
                int startX = (bx * width) / DCT_SIZE;
                int endX = Math.max(((bx + 1) * width) / DCT_SIZE, startX + 1);

                double sum = 0.0;
                int samples = 0;
                for (int y = startY; y < Math.min(endY, height); y++) {
                    for (int x = startX; x < Math.min(endX, width); x++) {
                        int offset = srcOffset + (y * width + x) * CHANNELS;
                        if (offset + 3 >= rgba8.length) {
                            continue;
                        }
                        int r = rgba8[offset] & 0xFF;
                        int g = rgba8[offset + 1] & 0xFF;
                        int b = rgba8[offset + 2] & 0xFF;
                        sum += 0.299 * r + 0.587 * g + 0.114 * b;
                        samples++;
                    }
                }
                luminance[by][bx] = samples == 0 ? 0.0 : sum / samples;
            }
        }
        return luminance;
    }

    private static double[][] forwardDct(double[][] block) {
        double[][] result = new double[DCT_SIZE][DCT_SIZE];
        for (int u = 0; u < DCT_SIZE; u++) {
            for (int v = 0; v < DCT_SIZE; v++) {
                double sum = 0.0;
                for (int x = 0; x < DCT_SIZE; x++) {
                    for (int y = 0; y < DCT_SIZE; y++) {
                        sum += block[y][x] * COSINE_TABLE[x][u] * COSINE_TABLE[y][v];
                    }
                }
                double cu = u == 0 ? 1.0 / Math.sqrt(2) : 1.0;
                double cv = v == 0 ? 1.0 / Math.sqrt(2) : 1.0;
                result[v][u] = 0.25 * cu * cv * sum;
            }
        }
        return result;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2.0 : sorted[mid];
    }

    /** 64-bit perceptual fingerprint of one sprite region, for {@link #groupBySimilarity}. */
    public static long fingerprint(byte[] rgba8, int srcOffset, int width, int height) {
        double[][] luminance = downsampleToLuminance(rgba8, srcOffset, width, height);
        double[][] dct = forwardDct(luminance);

        double[] lowFrequency = new double[DCT_SIZE * DCT_SIZE - 1];
        int idx = 0;
        for (int y = 0; y < DCT_SIZE; y++) {
            for (int x = 0; x < DCT_SIZE; x++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                lowFrequency[idx++] = dct[y][x];
            }
        }

        double med = median(lowFrequency);
        long fp = 0L;
        int bit = 0;
        for (int y = 0; y < DCT_SIZE; y++) {
            for (int x = 0; x < DCT_SIZE; x++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                if (dct[y][x] > med) {
                    fp |= 1L << bit;
                }
                bit++;
            }
        }
        return fp;
    }

    public record Family(int representativeIndex, List<Integer> memberIndices) {
    }

    /**
     * Groups sprites into families by Hamming distance between fingerprints,
     * restricted to same-tint pairs -- a tinted and an untinted sprite never
     * share a family regardless of visual similarity, matching the original
     * Rust behavior exactly (tint changes a sprite's actual rendered color
     * per-instance, so grouping across the tint boundary would defeat the
     * whole point of family-based atlas deduplication).
     */
    public static List<Family> groupBySimilarity(long[] fingerprints, boolean[] tinted, int maxHammingDistance) {
        int count = fingerprints.length;
        boolean[] visited = new boolean[count];
        List<Family> families = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            if (visited[i]) {
                continue;
            }
            visited[i] = true;
            List<Integer> members = new ArrayList<>();
            Deque<Integer> frontier = new ArrayDeque<>();
            frontier.push(i);

            while (!frontier.isEmpty()) {
                int current = frontier.pop();
                for (int j = 0; j < count; j++) {
                    if (visited[j] || tinted[j] != tinted[current]) {
                        continue;
                    }
                    int hamming = Long.bitCount(fingerprints[current] ^ fingerprints[j]);
                    if (hamming <= maxHammingDistance) {
                        visited[j] = true;
                        members.add(j);
                        frontier.push(j);
                    }
                }
            }
            families.add(new Family(i, members));
        }
        return families;
    }

    // ----------------------------------------------------------------
    // Atlas assembly
    // ----------------------------------------------------------------

    public record SpriteMeta(int srcOffset, int width, int height, int destX, int destY, boolean tinted) {
    }

    /**
     * Blits sprites into a destination atlas buffer, row by row. When
     * {@code padTo4} is set, the atlas is rounded up to the next multiple
     * of 4 in each dimension (BC1/BC7 block alignment) -- padding stays
     * zero-filled rather than edge-clamped, matching the Rust original
     * (which only clamped sample coordinates during downsampling, not
     * during this blit).
     */
    public static byte[] assembleAtlasFast(byte[] src, List<SpriteMeta> sprites, int atlasW, int atlasH, boolean padTo4) {
        int aw = padTo4 ? Math.max((atlasW + 3) & ~3, 4) : atlasW;
        int ah = padTo4 ? Math.max((atlasH + 3) & ~3, 4) : atlasH;
        byte[] atlas = new byte[aw * ah * CHANNELS];

        for (SpriteMeta sprite : sprites) {
            if (sprite.destX() + sprite.width() > aw || sprite.destY() + sprite.height() > ah) {
                continue;
            }
            for (int row = 0; row < sprite.height(); row++) {
                int srcOff = sprite.srcOffset() + row * sprite.width() * CHANNELS;
                int dstOff = ((sprite.destY() + row) * aw + sprite.destX()) * CHANNELS;
                int rowBytes = sprite.width() * CHANNELS;
                if (srcOff + rowBytes > src.length || dstOff + rowBytes > atlas.length) {
                    continue;
                }
                System.arraycopy(src, srcOff, atlas, dstOff, rowBytes);
            }
        }
        return atlas;
    }
}
