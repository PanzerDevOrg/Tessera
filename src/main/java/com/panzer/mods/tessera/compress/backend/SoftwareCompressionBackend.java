package com.panzer.mods.tessera.compress.backend;

import com.panzer.mods.tessera.compress.software.SoftwareBc1Encoder;
import com.panzer.mods.tessera.compress.software.SoftwareBc7Encoder;

import java.nio.ByteBuffer;

/**
 * Pure-Java fallback: no native call, no JNI, no FFM, nothing outside the
 * JDK. Always constructible and always correct on every platform this mod
 * runs on -- {@code TesseraRuntime} falls back to this whenever the native
 * bridge can't be loaded (missing binary for this OS/arch, JVM launched
 * without {@code --enable-preview}, or {@code -Dtessera.compatMode=true}).
 *
 * <p>{@code qualityPreset} is intentionally ignored: {@link SoftwareBc1Encoder}
 * and {@link SoftwareBc7Encoder} only implement a single, fixed-fidelity
 * encoding strategy (bounding-box endpoints, nearest-color indices) with no
 * uber-quality search to scale -- there's nothing for a preset to select
 * between yet.
 */
final class SoftwareCompressionBackend implements CompressionBackend {

    @Override
    public ByteBuffer compressBc7(ByteBuffer rgba8Direct, int width, int height, int qualityPreset) {
        return SoftwareBc7Encoder.encode(rgba8Direct, width, height);
    }

    @Override
    public ByteBuffer compressBc1(ByteBuffer rgba8Direct, int width, int height, int qualityPreset) {
        return SoftwareBc1Encoder.encode(rgba8Direct, width, height);
    }

    @Override
    public boolean supportsBc7() {
        return true;
    }

    @Override
    public boolean supportsBc1() {
        return true;
    }

    @Override
    public String name() {
        return "software (pure Java)";
    }
}
