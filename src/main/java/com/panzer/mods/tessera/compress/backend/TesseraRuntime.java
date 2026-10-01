package com.panzer.mods.tessera.compress.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides once, for the whole JVM lifetime, which {@link CompressionBackend}
 * Tessera runs on. Mirrors
 * {@code com.panzer.mods.celeris.core.memory.backend.CelerisRuntime}
 * exactly -- Tessera depends on Celeris specifically so this kind of
 * plumbing doesn't have to be reinvented per mod, but the backend being
 * selected here (native BC7/BC1 encode vs a pure-Java block encoder) is
 * Tessera's own concern, not something Celeris's memory-backend selection
 * has any reason to know about.
 *
 * <ol>
 *   <li>If {@code -Dtessera.compatMode=true} is set, always use
 *       {@link SoftwareCompressionBackend} -- no native library extraction
 *       or FFM downcall setup is even attempted.</li>
 *   <li>Otherwise, probe whether the bundled native library actually loads
 *       and links on this platform by performing one real
 *       {@code tessera_bc7_is_available()} call through it. A missing
 *       native binary for this OS/arch, a JVM without
 *       {@code --enable-preview}, or a corrupt/incompatible library throws
 *       a variety of errors -- {@code UnsatisfiedLinkError},
 *       {@code NoClassDefFoundError}, {@code ExceptionInInitializerError} --
 *       so the probe catches {@code Throwable} deliberately.</li>
 *   <li>If the probe throws anything, fall back to
 *       {@link SoftwareCompressionBackend} and log the concrete reason
 *       once.</li>
 * </ol>
 *
 * <p>This class holds no reference to any FFM type in its own fields or
 * method signatures, so it loads cleanly even on a JVM where FFM classes
 * don't exist at all -- only {@link #probeNative()} ever touches them,
 * behind the try/catch, exactly like {@code CelerisRuntime#probeFfm()}.
 *
 * <p>External consumers should not call this class directly -- use
 * {@code com.panzer.mods.tessera.api.TesseraFeatures} instead.
 */
public final class TesseraRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/Runtime");
    private static final String COMPAT_MODE_PROPERTY = "tessera.compatMode";
    /**
     * Native backends in preference order. Only JNI today: it loads on every
     * JDK with no launch flags and is zero-copy (FFM was removed because it is
     * preview-only on Java 21). Loaded reflectively so a failing static
     * initializer is caught by the probe instead of breaking this class.
     */
    private static final String[] NATIVE_BACKEND_CLASSES = {
            "com.panzer.mods.tessera.compress.backend.JniCompressionBackend",
    };

    private static volatile CompressionBackend backend;
    private static volatile boolean forcedCompatMode;

    private TesseraRuntime() {
    }

    /** Same shape as {@code CelerisRuntime#forceCompatMode()}; must be called before {@link #backend()}. */
    public static void forceCompatMode() {
        forcedCompatMode = true;
    }

    public static boolean isCompatModeForced() {
        return forcedCompatMode || Boolean.getBoolean(COMPAT_MODE_PROPERTY);
    }

    /**
     * Whether the active backend is the real native (FFM) one, as opposed
     * to the pure-Java software fallback. Triggers backend selection on
     * first call.
     */
    public static boolean isNativeActive() {
        return !(backend() instanceof SoftwareCompressionBackend);
    }

    /** Stateless pure-Java encoder; used as a per-job retry when the native backend fails. */
    public static CompressionBackend softwareBackend() {
        return SoftwareHolder.INSTANCE;
    }

    private static final class SoftwareHolder {
        static final CompressionBackend INSTANCE = new SoftwareCompressionBackend();
    }

    public static CompressionBackend backend() {
        CompressionBackend existing = backend;
        if (existing != null) {
            return existing;
        }
        synchronized (TesseraRuntime.class) {
            if (backend == null) {
                backend = selectBackend();
            }
            return backend;
        }
    }

    private static CompressionBackend selectBackend() {
        if (isCompatModeForced()) {
            LOGGER.info("Tessera compat mode forced -- using software (pure Java) compression backend");
            return new SoftwareCompressionBackend();
        }

        Object probeResult = probeNative();
        if (probeResult instanceof CompressionBackend native_) {
            LOGGER.info("Tessera native compression bridge available -- running at full performance ({})",
                    native_.name());
            return native_;
        }

        Throwable probeFailure = (Throwable) probeResult;
        LOGGER.warn(
                "Tessera native compression bridge unavailable ({}: {}) -- falling back to the pure-Java "
                        + "software encoder. Atlases will still compress, just at lower fidelity/throughput.",
                probeFailure.getClass().getSimpleName(), probeFailure.getMessage());
        return new SoftwareCompressionBackend();
    }

    /**
     * Instantiates the native backend and performs one real availability
     * call through it, entirely inside this method so no other code in
     * this file references {@code java.lang.foreign} at the bytecode level
     * outside a try/catch. On success the <em>same</em> instance is
     * returned and becomes the JVM-wide backend; on failure the caught
     * {@link Throwable} is returned instead.
     */
    private static Object probeNative() {
        Throwable firstFailure = null;
        for (String className : NATIVE_BACKEND_CLASSES) {
            try {
                // Class init loads and links the native library, so failures
                // surface here rather than on the first real compress() call.
                CompressionBackend candidate = (CompressionBackend) Class.forName(className)
                        .getDeclaredConstructor().newInstance();
                candidate.supportsBc7();
                return candidate;
            } catch (Throwable t) {
                // Report the preferred backend's failure; later ones are fallbacks.
                if (firstFailure == null) {
                    firstFailure = t;
                }
            }
        }
        return firstFailure;
    }
}
