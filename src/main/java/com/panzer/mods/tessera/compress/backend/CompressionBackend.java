package com.panzer.mods.tessera.compress.backend;

import java.nio.ByteBuffer;

/**
 * Compression backend abstraction, so {@code CompressionPipeline} never
 * calls {@code TesseraNativeBridge} directly. Mirrors
 * {@code com.panzer.mods.celeris.core.memory.CompressionCodec} for exactly
 * the same reason: {@code TesseraNativeBridge}'s static initializer
 * performs FFM downcall setup (and native library extraction) the moment
 * the class is loaded, which throws on a JVM without usable FFM or without
 * the bundled native library for this platform. Routing everything through
 * this interface means that class is only ever loaded after
 * {@code TesseraRuntime} has already confirmed it works.
 */
public interface CompressionBackend {

    /**
     * Compresses {@code width x height} RGBA8 pixels from {@code rgba8Direct}
     * (a direct buffer, positioned at 0) to BC7 blocks.
     *
     * @return the compressed blocks, or {@code null} if compression failed
     * or BC7 isn't supported by this backend.
     */
    ByteBuffer compressBc7(ByteBuffer rgba8Direct, int width, int height, int qualityPreset);

    /** BC1 counterpart of {@link #compressBc7}. Same contract. */
    ByteBuffer compressBc1(ByteBuffer rgba8Direct, int width, int height, int qualityPreset);

    boolean supportsBc7();

    boolean supportsBc1();

    /** Short name for logging (e.g. "native (FFM)", "software (pure Java)"). */
    String name();
}
