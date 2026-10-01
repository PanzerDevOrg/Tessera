package com.panzer.mods.tessera.atlas;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the RGBA8 mip chain for an atlas with the box-filter downsampling
 * in {@link SpriteFamilyAnalyzer#buildMipChain}. Level 0 is the input buffer
 * itself; deeper levels are new heap buffers.
 */
public final class MipChainBuilder {

    private MipChainBuilder() {
    }

    public static List<MipLevel> build(ByteBuffer baseRgba8, int baseWidth, int baseHeight, int maxLevel) {
        if (maxLevel <= 0) {
            return List.of(new MipLevel(baseWidth, baseHeight, baseRgba8));
        }

        byte[] basePixels = toByteArray(baseRgba8);
        List<SpriteFamilyAnalyzer.DownsampleResult> chain =
                SpriteFamilyAnalyzer.buildMipChain(basePixels, baseWidth, baseHeight, maxLevel);

        List<MipLevel> levels = new ArrayList<>(chain.size());
        for (SpriteFamilyAnalyzer.DownsampleResult level : chain) {
            levels.add(new MipLevel(level.width(), level.height(), ByteBuffer.wrap(level.pixels())));
        }
        return levels;
    }

    private static byte[] toByteArray(ByteBuffer buffer) {
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.rewind();
        byte[] array = new byte[duplicate.remaining()];
        duplicate.get(array);
        return array;
    }

    public record MipLevel(int width, int height, ByteBuffer rgba8) {
    }
}
