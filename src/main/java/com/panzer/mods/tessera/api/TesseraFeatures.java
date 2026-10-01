package com.panzer.mods.tessera.api;

import com.panzer.mods.celeris.api.CelerisFeatures;
import com.panzer.mods.tessera.compress.backend.TesseraRuntime;

/**
 * Public, stable-ish facade over Tessera's runtime state -- other mods (or
 * this mod's own {@code DebugOverlay}) should read backend status through
 * here, not by reaching into {@code compress.backend} directly.
 *
 * <p>Generic "does FFM/SIMD work on this JVM at all" questions delegate to
 * {@link CelerisFeatures}, since Celeris already answers those for its own
 * memory/vector backends and there is exactly one JVM-wide answer to give;
 * only the BC7/BC1 compression backend choice is Tessera's own concern.
 */
public final class TesseraFeatures {

    private TesseraFeatures() {
    }

    /** Whether Tessera is compressing through the native FFM bridge (bc7enc_rdo) right now. */
    public static boolean isNativeCompressionActive() {
        return TesseraRuntime.isNativeActive();
    }

    public static String compressionBackendName() {
        return TesseraRuntime.backend().name();
    }

    /** Whether this JVM's Foreign Function & Memory support is usable at all (Celeris-wide answer). */
    public static boolean isFfmActive() {
        return CelerisFeatures.isFfmActive();
    }

    /** Whether Celeris's {@code jdk.incubator.vector} SIMD backend is active (used by the sprite-family analyzer). */
    public static boolean isSimdActive() {
        return CelerisFeatures.isSimdActive();
    }
}
