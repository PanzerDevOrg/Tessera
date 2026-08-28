package com.nerdsoft.mods.tessera.compress;

import org.lwjgl.opengl.GL;

/**
 * Capability check for whether the driver exposes BPTC (BC7) texture
 * compression. Mirrors {@link Bc1TextureFormatSupport}/{@link Bc1ComputeSupport}'s
 * pattern: volatile cache, {@code GL.getCapabilities()} check, and a
 * render-thread {@link #warmUp()} that must run before any background
 * caller can reach {@link #isSupported()} first -- {@code GL.getCapabilities()}
 * is thread-local to whichever thread has a GL context current, so a
 * background-thread call would throw and, if cached as a false negative,
 * would permanently misreport this driver's real BC7 support for the rest
 * of the process.
 */
public final class Bc7GpuSupport {

    private static volatile Boolean supported;

    private Bc7GpuSupport() {
    }

    /**
     * Forces capability detection now, on whatever thread calls this --
     * callers MUST invoke this from the render thread before any
     * background caller (e.g. {@code CompressionPipeline#compress} running
     * on {@code SplitAtlasManager}'s background stitch executor) can reach
     * {@link #isSupported()} first. A no-op if already cached.
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
            if (caps.OpenGL42 || caps.GL_ARB_texture_compression_bptc) {
                found = true;
            }
        } catch (Throwable ignored) {
            // Reachable when no GL context is current on the calling
            // thread (e.g. this is hit from a background executor before
            // warmUp() has run). Deliberately does NOT cache `found` in
            // this branch -- caching false here would permanently poison
            // this class's result for the rest of the process even once a
            // render-thread context exists.
            return false;
        }

        supported = found;
        return found;
    }
}