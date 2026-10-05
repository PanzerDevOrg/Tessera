package com.panzer.mods.tessera.vram;

import com.panzer.mods.tessera.config.Config;
import com.panzer.mods.tessera.config.RulesManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public final class VramBudgetEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/VramBudgetEngine");

    private static final int GL_NVX_GPU_MEMORY_INFO_DEDICATED_VIDMEM = 0x9047;
    private static final int GL_ATI_MEMINFO_VBO_FREE_MEMORY = 0x87FC;

    private static volatile Integer cachedHardwareVramMb;

    private VramBudgetEngine() {
    }

    public static int getEffectiveBudgetMb() {
        if (RulesManager.forcedVramBudgetMb != null) {
            return RulesManager.forcedVramBudgetMb;
        }

        int hardwareVram = queryHardwareVramMb();
        if (hardwareVram > 0) {
            return (int) (hardwareVram * 0.75);
        }

        return Config.get(Config.VRAM_BUDGET_TARGET_MB);
    }

    private static int queryHardwareVramMb() {
        Integer cached = cachedHardwareVramMb;
        if (cached != null) {
            return cached;
        }
        return detectAndCacheHardwareVramMb();
    }

    /** Call once from the render thread (client setup) so background callers hit the cache. */
    public static void warmUp() {
        queryHardwareVramMb();
    }

    private static synchronized int detectAndCacheHardwareVramMb() {
        if (cachedHardwareVramMb != null) {
            return cachedHardwareVramMb;
        }
        if (!RenderSystem.isOnRenderThread()) {
            // No GL context here: querying would fail and caching -1 would
            // disable hardware detection for the whole session.
            return -1;
        }

        int result = queryHardwareVramMbUncached();
        cachedHardwareVramMb = result;
        return result;
    }

    private static int queryHardwareVramMbUncached() {
        GLCapabilities caps;
        try {
            caps = GL.getCapabilities();
        } catch (Throwable t) {
            LOGGER.debug("Failed to read OpenGL capabilities for VRAM query.", t);
            return -1;
        }

        if (caps.GL_NVX_gpu_memory_info) {
            try {
                int[] query = new int[4];
                GL11.glGetIntegerv(GL_NVX_GPU_MEMORY_INFO_DEDICATED_VIDMEM, query);
                if (query[0] > 0) {
                    return query[0] / 1024;
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to query VRAM via GL_NVX_gpu_memory_info.", e);
            }
        }

        if (caps.GL_ATI_meminfo) {
            try {
                int[] query = new int[4];
                GL11.glGetIntegerv(GL_ATI_MEMINFO_VBO_FREE_MEMORY, query);
                if (query[0] > 0) {
                    return query[0] / 1024;
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to query VRAM via GL_ATI_meminfo.", e);
            }
        }

        return -1;
    }

    /** Bytes currently resident per atlas, as reported by successful uploads. */
    private static final ConcurrentHashMap<String, Long> RESIDENT_BYTES = new ConcurrentHashMap<>();

    /** Records (or replaces, on reload) the resident size of an atlas; {@code bytes <= 0} clears it. */
    public static void recordResident(String atlasKey, long bytes) {
        if (bytes <= 0) {
            RESIDENT_BYTES.remove(atlasKey);
        } else {
            RESIDENT_BYTES.put(atlasKey, bytes);
        }
    }

    public static long totalResidentBytes() {
        long total = 0L;
        for (long v : RESIDENT_BYTES.values()) {
            total += v;
        }
        return total;
    }

    /**
     * Whether adding {@code estimatedBytes} for {@code atlasKey} keeps Tessera's total
     * resident footprint within budget. The atlas's own previous size is excluded, so a
     * reload replacing an existing atlas is not double-counted.
     */
    public static boolean isWithinBudget(String atlasKey, long estimatedBytes) {
        long others = totalResidentBytes() - RESIDENT_BYTES.getOrDefault(atlasKey, 0L);
        long projected = others + estimatedBytes;
        long budgetBytes = (long) getEffectiveBudgetMb() * 1024L * 1024L;
        if (projected <= budgetBytes) {
            return true;
        }
        LOGGER.warn("Atlas {} would raise Tessera VRAM use to {} MB, above the {} MB budget.",
                atlasKey, String.format("%.2f", projected / (1024.0 * 1024.0)), budgetBytes / (1024 * 1024));
        return false;
    }

}
