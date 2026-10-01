package com.panzer.mods.tessera.compress;

import com.panzer.mods.tessera.compress.backend.CompressionBackend;
import com.panzer.mods.tessera.compress.backend.TesseraRuntime;
import com.panzer.mods.tessera.compress.software.TransparentTexelBleed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * BC7/BC1 encoding entry point. {@link #compressBlocking} encodes on the
 * calling thread (callers run it off the render thread): it applies alpha
 * bleeding, guarantees direct buffers in and out, and retries once on the
 * pure-Java encoder if the native one fails.
 */
public final class CompressionPipeline {

    /** Preserved from the pre-port API: {@code AtlasCompressionDriver} branches on this directly. */
    public enum Target {
        BC7(16), BC1(8);

        private final int bytesPerBlock;

        Target(int bytesPerBlock) {
            this.bytesPerBlock = bytesPerBlock;
        }

        public int bytesPerBlock() {
            return bytesPerBlock;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/CompressionPipeline");

    private CompressionPipeline() {
    }

    /**
     * Synchronous compression on the calling thread. Intended for callers that are
     * already off the render thread (e.g. the split-atlas background stitch), where
     * hopping through a worker pool and result queue would only add latency.
     *
     * <p>Guarantees: the input is handed to the backend as a direct buffer, the result
     * is a direct little-endian buffer suitable for {@code glCompressedTexImage2D},
     * and a native failure falls back to the pure-Java encoder once before giving up.
     *
     * @return direct buffer of BC blocks, or {@code null} if every backend failed
     */
    public static ByteBuffer compressBlocking(String atlasKey, ByteBuffer rgba8, int width, int height,
                                              boolean bc7, int qualityPreset) {
        long requiredBytes = (long) width * height * 4L;
        if (width <= 0 || height <= 0 || rgba8.remaining() < requiredBytes) {
            LOGGER.error("Invalid RGBA input for atlas '{}': {}x{} needs {} bytes, buffer has {}",
                    atlasKey, width, height, requiredBytes, rgba8.remaining());
            return null;
        }
        ByteBuffer input = toDirect(rgba8);
        // Invisible texels must not steer block endpoints (white/grey fringes).
        TransparentTexelBleed.apply(input, width, height);
        CompressionBackend backend = TesseraRuntime.backend();
        ByteBuffer compressed;
        try {
            compressed = bc7
                    ? backend.compressBc7(input, width, height, qualityPreset)
                    : backend.compressBc1(input, width, height, qualityPreset);
        } catch (Throwable t) {
            LOGGER.warn("Backend {} threw while compressing atlas '{}' -- retrying with software encoder",
                    backend.name(), atlasKey, t);
            compressed = null;
        }
        if (compressed == null && TesseraRuntime.isNativeActive()) {
            CompressionBackend software = TesseraRuntime.softwareBackend();
            compressed = bc7
                    ? software.compressBc7(input, width, height, qualityPreset)
                    : software.compressBc1(input, width, height, qualityPreset);
        }
        if (compressed == null) {
            LOGGER.warn("Compression failed for atlas '{}' ({} path) on every backend", atlasKey, bc7 ? "BC7" : "BC1");
            return null;
        }
        return toDirect(compressed);
    }

    private static ByteBuffer toDirect(ByteBuffer buffer) {
        if (buffer.isDirect()) {
            return buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
        }
        ByteBuffer direct = ByteBuffer.allocateDirect(buffer.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        direct.put(buffer.duplicate()).flip();
        return direct;
    }
}
