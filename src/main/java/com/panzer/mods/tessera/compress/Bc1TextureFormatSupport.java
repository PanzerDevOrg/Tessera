package com.panzer.mods.tessera.compress;

import org.lwjgl.opengl.GL;

/**
 * Capability check for whether the driver can actually accept
 * {@code GL_COMPRESSED_RGB_S3TC_DXT1_EXT} / {@code GL_COMPRESSED_RGBA_S3TC_DXT1_EXT}
 * in {@code glCompressedTexImage2D}. Mirrors {@link Bc7GpuSupport}'s pattern
 * (volatile cache, {@code GL.getCapabilities()} check).
 *
 * <p>Neither the CPU compression path ({@code CompressionPipeline#compress})
 * ever checked whether the driver actually exposes the S3TC
 * texture-compression format before {@code uploadCompressedLevel} fed the
 * DXT1 enum straight into {@code glCompressedTexImage2D} -- native BC1
 * encoder availability alone never guaranteed the resulting blocks could
 * legally be uploaded as S3TC.
 *
 * <h2>Render-thread warmup requirement</h2>
 * {@code GL.getCapabilities()} returns the {@code GLCapabilities} bound to
 * whichever thread currently has a GL context current -- it is thread-local
 * state inside LWJGL, not a global snapshot. {@code AtlasCompressionDriver}'s
 * background-executor path can reach {@link #isSupported()} before any
 * render-thread caller ever has, which would throw inside
 * {@code GL.getCapabilities()} and, if caught and cached incorrectly,
 * permanently memoize a false negative regardless of the driver's actual
 * support. {@link #warmUp()} must be invoked from a render-thread context
 * (e.g. {@code FMLClientSetupEvent#enqueueWork}) before
 * {@link #isSupported()} can ever be reached from a background thread --
 * see {@code Tessera#onClientSetup}, which already does this.
 */
public final class Bc1TextureFormatSupport {

    private static volatile Boolean supported;

    private Bc1TextureFormatSupport() {
    }

    /**
     * Forces capability detection now, on whatever thread calls this --
     * callers MUST invoke this from the render thread (a thread with a
     * current GL context) before {@code AtlasCompressionDriver}'s background
     * stitch path can reach {@link #isSupported()} first. A no-op if
     * already cached, so safe to call defensively on every client-setup
     * pass without re-querying the driver each time.
     */
    public static void warmUp() {
        isSupported();
    }

    public static boolean isSupported() {
        Boolean cached = supported;
        if (cached != null) {
            return cached;
        }
        return detectAndCache();
    }

    private static synchronized boolean detectAndCache() {
        if (supported != null) {
            return supported;
        }

        boolean found = false;
        try {
            var caps = GL.getCapabilities();
            // GL_EXT_texture_compression_s3tc is the extension that actually
            // gates GL_COMPRESSED_RGB_S3TC_DXT1_EXT / ..._RGBA_..._DXT1_EXT.
            // There is no core-version fallback the way there is for BPTC
            // (GL 4.2) or compute shaders (GL 4.3) -- S3TC never became core
            // GL due to patent history, so the extension flag is the only
            // signal, on every version, on every platform.
            if (caps.GL_EXT_texture_compression_s3tc) {
                found = true;
            }
        } catch (Throwable ignored) {
            // Reachable when no GL context is current on the calling
            // thread (e.g. this is hit from a background executor before
            // warmUp() has run). Deliberately does NOT cache `found` in
            // this branch -- see below.
            return false;
        }

        supported = found;
        return found;
    }
}
