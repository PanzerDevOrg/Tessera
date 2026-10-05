package com.panzer.mods.tessera.config;

import net.neoforged.neoforge.common.ModConfigSpec;


public final class Config {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue COMPRESSION_QUALITY;
    public static final ModConfigSpec.IntValue DEDUP_SIMILARITY_THRESHOLD;
    public static final ModConfigSpec.IntValue VRAM_BUDGET_TARGET_MB;
    public static final ModConfigSpec.ConfigValue<String> CACHE_DIRECTORY;
    public static final ModConfigSpec.BooleanValue DISABLE_NATIVE_COMPRESSION;
    public static final ModConfigSpec.BooleanValue SHOW_EXTENDED_DEBUG_BREAKDOWN;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("compression");

        COMPRESSION_QUALITY = builder
                .comment(
                        "BC7 compression quality preset, 0 (fastest, lowest fidelity) to 7 (slowest, highest fidelity).",
                        "Maps to a fixed preset table in the native encoder; the pure-Java fallback ignores it."
                )
                .translation("tessera.configuration.compressionQuality")
                .defineInRange("compressionQuality", 4, 0, 7);

        DISABLE_NATIVE_COMPRESSION = builder
                .comment("Disables all atlas compression (native and pure-Java): atlases stay vanilla RGBA8.")
                .translation("tessera.configuration.disableNativeCompression")
                .define("disableNativeCompression", false);

        builder.pop();

        builder.push("deduplication");

        DEDUP_SIMILARITY_THRESHOLD = builder
                .comment(
                        "Maximum Hamming distance, 0 to 64, between two 64-bit pHash fingerprints for two sprites",
                        "to be treated as duplicates. Lower is stricter."
                )
                .translation("tessera.configuration.dedupSimilarityThreshold")
                .defineInRange("dedupSimilarityThreshold", 6, 0, 64);

        builder.pop();

        builder.push("vramBudget");

        VRAM_BUDGET_TARGET_MB = builder
                .comment(
                        "Advisory VRAM target in megabytes for the compressed atlas. Evaluated once per",
                        "resource-pack or mod-list reload, not polled continuously at runtime.",
                        "BC1/BC7 are both fixed bits-per-pixel formats, so this cannot be enforced by",
                        "lowering compressionQuality -- an atlas over this budget is logged as a warning",
                        "only; lower dedupSimilarityThreshold or accept the overage."
                )
                .translation("tessera.configuration.vramBudgetTargetMb")
                .defineInRange("vramBudgetTargetMb", 2048, 256, 16384);

        builder.pop();

        builder.push("cache");

        CACHE_DIRECTORY = builder
                .comment("Cache directory for compressed atlas data, relative to the game directory.")
                .translation("tessera.configuration.cacheDirectory")
                .define("cacheDirectory", "tessera-cache");

        builder.pop();

        builder.push("debug");

        SHOW_EXTENDED_DEBUG_BREAKDOWN = builder
                .comment("Displays the expanded breakdown by atlas and by bucket on the F3 Debug Screen.")
                .translation("tessera.configuration.showExtendedDebugBreakdown")
                .define("showExtendedDebugBreakdown", false);

        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }

    public record ReloadSensitiveSnapshot(String cacheDirectory, int compressionQuality) {
        public static ReloadSensitiveSnapshot capture() {
            return new ReloadSensitiveSnapshot(get(CACHE_DIRECTORY), get(COMPRESSION_QUALITY));
        }
    }

    /**
     * The value, or its default while the client config is not loaded yet: some
     * versions (1.21.7) stitch atlases before NeoForge loads client configs.
     */
    public static <T> T get(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
