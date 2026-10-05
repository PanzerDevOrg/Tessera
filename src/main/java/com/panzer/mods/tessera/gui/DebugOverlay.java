package com.panzer.mods.tessera.gui;

import com.panzer.mods.tessera.config.Config;
import com.panzer.mods.tessera.vram.VramBudgetEngine;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tessera's lines in the F3 screen: a summary (compression on or off, atlas
 * VRAM and savings, budget, GPU memory) and a per-atlas breakdown. Up to
 * 1.21.1 they are appended to F3's right column, the breakdown toggled with
 * F3+4. From 1.21.10 they are two debug screen entries,
 * {@code tessera:atlas_compression} (shown with F3 by default) and
 * {@code tessera:atlas_compression_breakdown}, toggled in vanilla's debug options.
 */
public final class DebugOverlay {

    private static final int GL_GPU_MEM_INFO_TOTAL_AVAILABLE_NVX = 0x9048;
    private static final int GL_GPU_MEM_INFO_CURRENT_AVAILABLE_NVX = 0x9049;
    private static final int GL_VBO_FREE_MEMORY_ATI = 0x87FC;

    private static final long HARDWARE_VRAM_REFRESH_INTERVAL_MS = 1000L;
    private static final Map<String, AtlasStats> perAtlasStats = new ConcurrentHashMap<>();
    private static final Map<String, AtlasStats> perBucketStats = new ConcurrentHashMap<>();
    public static boolean isCompressedAtlasActive = false;
    public static long bytesSavedByBC7 = 0;
    public static long totalCompressedAtlasBytes = 0;
    private static volatile String cachedHardwareVramUsage = "§7N/A (Driver Limited)§r";
    private static volatile long lastHardwareVramQueryMs = -1L;

    private DebugOverlay() {
    }

    @SuppressWarnings("unused")
    public static synchronized void recordCompression(String atlasLocation, long savedBytes, long compressedBytes) {
        // Replace, not accumulate: an atlas holds exactly one compressed chain at a
        // time, so a reload or quality change must not add it twice. Totals move
        // by the delta only.
        AtlasStats previous = perAtlasStats.put(atlasLocation, new AtlasStats(savedBytes, compressedBytes));
        if (previous != null) {
            bytesSavedByBC7 -= previous.bytesSaved();
            totalCompressedAtlasBytes -= previous.compressedBytes();
        }
        isCompressedAtlasActive = true;
        bytesSavedByBC7 += savedBytes;
        totalCompressedAtlasBytes += compressedBytes;
    }

    /**
     * Same accumulation as {@link #recordCompression}, scoped to a single
     * bucket within an atlas. Called once per non-empty bucket (up to 3x
     * per atlas) in addition to the atlas-wide call, so the two maps stay
     * consistent: summing a given atlas's entries in {@link #perBucketStats}
     * always equals its single entry in {@link #perAtlasStats}.
     */
    @SuppressWarnings("unused")
    public static void recordBucketCompression(String atlasLocation, String bucketName, long savedBytes, long compressedBytes) {
        String key = atlasLocation + "/" + bucketName;
        // Replace for the same reason as recordCompression.
        perBucketStats.put(key, new AtlasStats(savedBytes, compressedBytes));
    }


    /** Called when vanilla re-uploads an atlas as RGBA (reload), until it is compressed again. */
    public static synchronized void resetAtlas(String atlasLocation) {
        AtlasStats removed = perAtlasStats.remove(atlasLocation);
        if (removed != null) {
            bytesSavedByBC7 -= removed.bytesSaved();
            totalCompressedAtlasBytes -= removed.compressedBytes();
        }
        isCompressedAtlasActive = !perAtlasStats.isEmpty();
        String prefix = atlasLocation + "/";
        perBucketStats.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @SuppressWarnings("unused")
    public static Map<String, AtlasStats> getPerAtlasStats() {
        return Map.copyOf(perAtlasStats);
    }

    /** Compression status, atlas VRAM, budget and GPU memory. */
    public static List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add("§d[Tessera]");

        int budgetTargetMB = VramBudgetEngine.getEffectiveBudgetMb();
        boolean isMac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

        if (isMac) {
            lines.add("Compression: §cUNSUPPORTED (macOS)§r");
        } else if (!isCompressedAtlasActive || bytesSavedByBC7 <= 0) {
            lines.add("Compression: §cDISABLED§r");
            lines.add(String.format("VRAM Budget: %d MB", budgetTargetMB));
            lines.add("VRAM: " + getHardwareVramUsage());
        } else {
            lines.add("Compression: §aENABLED§r");

            double savedMB = bytesSavedByBC7 / (1024.0 * 1024.0);
            double compressedMB = totalCompressedAtlasBytes / (1024.0 * 1024.0);
            double originalMB = compressedMB + savedMB;
            double percentageSaved = originalMB > 0 ? (savedMB / originalMB) * 100.0 : 0;

            lines.add(String.format("Atlas VRAM: §b%.2f MB§r / §7%.2f MB§r (§a-%.1f%%§r)",
                    compressedMB, originalMB, percentageSaved));
            lines.add(String.format("Saved: §a%.2f MB§r", savedMB));
            lines.add(String.format("VRAM Budget: %d MB", budgetTargetMB));

            lines.add("GPU VRAM: " + getHardwareVramUsage());
        }
        return lines;
    }

    /** Savings per atlas, and per bucket within an atlas. */
    public static List<String> breakdownLines() {
        List<String> lines = new ArrayList<>();
        appendPerAtlasBreakdown(lines);
        return lines;
    }

    //? >=1.21.10 {
    /*public static void registerDebugEntries(net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent event) {
        var summary = com.panzer.mods.tessera.compat.TesseraCompat.id("atlas_compression");
        var breakdown = com.panzer.mods.tessera.compat.TesseraCompat.id("atlas_compression_breakdown");
        event.register(summary, (displayer, level, clientChunk, serverChunk) -> displayer.addToGroup(summary, summaryLines()));
        event.register(breakdown, (displayer, level, clientChunk, serverChunk) -> displayer.addToGroup(breakdown, breakdownLines()));
        event.includeInProfile(summary, net.minecraft.client.gui.components.debug.DebugScreenProfile.DEFAULT,
                net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.IN_F3);
    }
    *///?} else {
    @net.neoforged.fml.common.EventBusSubscriber(modid = com.panzer.mods.tessera.Tessera.MOD_ID,
            value = net.neoforged.api.distmarker.Dist.CLIENT)
    public static final class F3Text {

        private F3Text() {
        }

        @net.neoforged.bus.api.SubscribeEvent
        public static void onRenderDebugText(net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent.DebugText event) {
            if (!net.minecraft.client.Minecraft.getInstance().gui.getDebugOverlay().showDebugScreen()) {
                return;
            }
            List<String> rightList = event.getRight();
            List<String> tesseraLines = new ArrayList<>();
            tesseraLines.add("");
            tesseraLines.addAll(summaryLines());
            if (isCompressedAtlasActive && bytesSavedByBC7 > 0 && Config.SHOW_EXTENDED_DEBUG_BREAKDOWN.get()) {
                tesseraLines.addAll(breakdownLines());
            }
            int insertIndex = getIndex(rightList);
            if (insertIndex != -1 && insertIndex <= rightList.size()) {
                rightList.addAll(insertIndex, tesseraLines);
            } else {
                rightList.addAll(tesseraLines);
            }
        }
    }
    //?}

    private static int getIndex(List<String> rightList) {
        for (int i = 0; i < rightList.size(); i++) {
            String line = rightList.get(i);
            // example "4.6.0 - Build 31.0.101.4502"
            if (line.contains(" - Build")) {
                return i + 1;
            }
        }
        return -1;
    }

    private static void appendPerAtlasBreakdown(List<String> rightList) {
        if (perAtlasStats.isEmpty()) {
            return;
        }

        rightList.add("");
        rightList.add("§7Per-atlas:§r");
        perAtlasStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().bytesSaved(), a.getValue().bytesSaved()))
                .forEach(entry -> {
                    double atlasSavedMB = entry.getValue().bytesSaved() / (1024.0 * 1024.0);
                    rightList.add(String.format("§7%s: §a%.2f MB§r", entry.getKey(), atlasSavedMB));
                    appendBucketBreakdownFor(rightList, entry.getKey());
                });
    }

    /**
     * Appends the per-bucket lines (OPAQUE_BC1 / ALPHA_BC7 / DYNAMIC_RGBA8)
     * nested under a single atlas's line in the breakdown, when that atlas
     * has more than one bucket recorded -- a single-bucket atlas (e.g. one
     * with no alpha sprites at all) skips this since the atlas-level line
     * already says everything the bucket line would.
     */
    private static void appendBucketBreakdownFor(List<String> rightList, String atlasLocation) {
        String prefix = atlasLocation + "/";
        List<Map.Entry<String, AtlasStats>> bucketEntries = perBucketStats.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .sorted((a, b) -> Long.compare(b.getValue().bytesSaved(), a.getValue().bytesSaved()))
                .toList();

        if (bucketEntries.size() <= 1) {
            return;
        }

        for (Map.Entry<String, AtlasStats> bucketEntry : bucketEntries) {
            String bucketName = bucketEntry.getKey().substring(prefix.length());
            double bucketSavedMB = bucketEntry.getValue().bytesSaved() / (1024.0 * 1024.0);
            double bucketResidentMB = bucketEntry.getValue().compressedBytes() / (1024.0 * 1024.0);
            rightList.add(String.format("   §8%s: §a%.2f MB§r saved, §7%.2f MB§r resident",
                    bucketName, bucketSavedMB, bucketResidentMB));
        }
    }

    private static String getHardwareVramUsage() {
        long now = System.currentTimeMillis();
        if (now - lastHardwareVramQueryMs < HARDWARE_VRAM_REFRESH_INTERVAL_MS) {
            return cachedHardwareVramUsage;
        }
        lastHardwareVramQueryMs = now;
        cachedHardwareVramUsage = queryHardwareVramUsage();
        return cachedHardwareVramUsage;
    }

    private static String queryHardwareVramUsage() {
        try {
            var caps = GL.getCapabilities();

            if (caps.GL_NVX_gpu_memory_info) {
                int totalKb = GL11.glGetInteger(GL_GPU_MEM_INFO_TOTAL_AVAILABLE_NVX);
                int freeKb = GL11.glGetInteger(GL_GPU_MEM_INFO_CURRENT_AVAILABLE_NVX);
                int usedKb = totalKb - freeKb;
                return String.format("%dMB / %dMB", usedKb / 1024, totalKb / 1024);
            }

            if (caps.GL_ATI_meminfo) {
                int[] info = new int[4];
                GL11.glGetIntegerv(GL_VBO_FREE_MEMORY_ATI, info);
                return String.format("Free: %dMB", info[0] / 1024);
            }
        } catch (Throwable ignored) {

        }

        return "§7N/A (Driver Limited)§r";
    }

    public record AtlasStats(long bytesSaved, long compressedBytes) {
    }
}
