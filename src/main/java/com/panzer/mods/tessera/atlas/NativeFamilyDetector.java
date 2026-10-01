package com.panzer.mods.tessera.atlas;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles an atlas's sprites into one RGBA8 buffer and, along the way,
 * fingerprints each sprite (perceptual hash), classifies its alpha shape and
 * groups near-duplicate sprites into families. All work is done in Java by
 * {@link SpriteFamilyAnalyzer}.
 *
 * <p>The assembled buffer is exactly {@code atlasWidth * atlasHeight * 4}
 * bytes ({@code pad_to_4 = false}); 4x4 block alignment is handled by the
 * encoders, not here.
 */
public final class NativeFamilyDetector {

    private NativeFamilyDetector() {
    }

    public static DetectionResult detect(
            ByteBuffer pixels,
            List<SpriteInput> sprites,
            int atlasWidth,
            int atlasHeight,
            int maxHammingDistance
    ) {
        byte[] pixelArray = toByteArray(pixels);
        int count = sprites.size();

        long[] fingerprints = new long[count];
        boolean[] tinted = new boolean[count];
        boolean[] alphaFlags = new boolean[count];
        AlphaShape[] alphaShapes = new AlphaShape[count];
        List<SpriteFamilyAnalyzer.SpriteMeta> metas = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            SpriteInput sprite = sprites.get(i);
            fingerprints[i] = SpriteFamilyAnalyzer.fingerprint(pixelArray, sprite.srcOffset(), sprite.width(), sprite.height());
            tinted[i] = sprite.tinted();

            SpriteFamilyAnalyzer.AlphaShape rawShape = SpriteFamilyAnalyzer.classifyAlphaShape(
                    pixelArray, sprite.srcOffset(), sprite.width(), sprite.height());
            alphaShapes[i] = toLocalAlphaShape(rawShape);
            alphaFlags[i] = rawShape != SpriteFamilyAnalyzer.AlphaShape.FULLY_OPAQUE;

            metas.add(new SpriteFamilyAnalyzer.SpriteMeta(
                    sprite.srcOffset(), sprite.width(), sprite.height(), sprite.destX(), sprite.destY(), sprite.tinted()));
        }

        List<SpriteFamilyAnalyzer.Family> rawFamilies = SpriteFamilyAnalyzer.groupBySimilarity(fingerprints, tinted, maxHammingDistance);
        List<Family> families = new ArrayList<>(rawFamilies.size());
        for (SpriteFamilyAnalyzer.Family f : rawFamilies) {
            families.add(new Family(f.representativeIndex(), f.memberIndices()));
        }

        byte[] atlasBytes = SpriteFamilyAnalyzer.assembleAtlasFast(pixelArray, metas, atlasWidth, atlasHeight, false);
        ByteBuffer atlasBuffer = ByteBuffer.wrap(atlasBytes);

        return new DetectionResult(fingerprints, families, alphaFlags, alphaShapes, atlasBuffer);
    }

    private static AlphaShape toLocalAlphaShape(SpriteFamilyAnalyzer.AlphaShape shape) {
        return switch (shape) {
            case FULLY_OPAQUE -> AlphaShape.FULLY_OPAQUE;
            case PUNCH_THROUGH -> AlphaShape.PUNCH_THROUGH;
            case BLENDED -> AlphaShape.BLENDED;
        };
    }

    /**
     * Copies the buffer's full content (from index 0, ignoring position and
     * limit) into a heap array. One copy per atlas rebuild, not per frame.
     */
    private static byte[] toByteArray(ByteBuffer buffer) {
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.rewind();
        byte[] array = new byte[duplicate.remaining()];
        duplicate.get(array);
        return array;
    }

    public enum AlphaShape {
        FULLY_OPAQUE,
        PUNCH_THROUGH,
        BLENDED
    }

    public record SpriteInput(int srcOffset, int width, int height, int destX, int destY, boolean tinted) {
    }

    public record Family(int representativeIndex, List<Integer> memberIndices) {
    }

    public record DetectionResult(
            long[] fingerprints, List<Family> families, boolean[] alphaFlags, AlphaShape[] alphaShapes,
            ByteBuffer atlasBuffer
    ) {
    }
}
