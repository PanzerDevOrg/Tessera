package com.panzer.mods.tessera.compress.software;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Alpha bleeding before BC encoding.
 *
 * <p>Fully transparent texels (A = 0) in Minecraft textures carry arbitrary RGB,
 * often white or grey. BC encoders fit block endpoints to all 16 texels, so that
 * invisible colour drags the palette of the visible ones, and wherever alpha is
 * not reconstructed as exactly 0 (blended GUI sprites, interpolated animation
 * frames) it shows up as white or grey fringes.
 *
 * <p>Each A = 0 texel takes the average colour of the visible texels in its own
 * 4x4 block (the encoder's unit), so it no longer influences the endpoints, and
 * any residual alpha error reveals a neighbouring colour instead of white.
 * Fully transparent blocks become black, which encodes exactly. Alpha is never
 * changed, so the visible result is identical.
 */
public final class TransparentTexelBleed {

    private TransparentTexelBleed() {
    }

    /** Rewrites, in place, the RGB of A = 0 texels in a tightly packed RGBA8 image starting at index 0. */
    public static void apply(ByteBuffer rgba, int width, int height) {
        ByteBuffer px = rgba.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = rgba.position();
        for (int by = 0; by < height; by += 4) {
            for (int bx = 0; bx < width; bx += 4) {
                int yEnd = Math.min(by + 4, height);
                int xEnd = Math.min(bx + 4, width);
                int sumR = 0, sumG = 0, sumB = 0, visible = 0;
                boolean anyTransparent = false;
                for (int y = by; y < yEnd; y++) {
                    for (int x = bx; x < xEnd; x++) {
                        int v = px.getInt(base + (y * width + x) * 4); // bytes R,G,B,A
                        if ((v >>> 24) == 0) {
                            anyTransparent = true;
                        } else {
                            sumR += v & 0xFF;
                            sumG += (v >>> 8) & 0xFF;
                            sumB += (v >>> 16) & 0xFF;
                            visible++;
                        }
                    }
                }
                if (!anyTransparent) {
                    continue;
                }
                int fill = visible == 0 ? 0
                        : (sumR / visible) | (sumG / visible) << 8 | (sumB / visible) << 16;
                for (int y = by; y < yEnd; y++) {
                    for (int x = bx; x < xEnd; x++) {
                        int index = base + (y * width + x) * 4;
                        if ((px.getInt(index) >>> 24) == 0) {
                            px.putInt(index, fill); // A stays 0
                        }
                    }
                }
            }
        }
    }
}
