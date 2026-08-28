package com.nerdsoft.mods.tessera.render;

import com.nerdsoft.mods.tessera.Tessera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Purges {@link SectionGeometryStore}'s entries (all Y slices) for a chunk
 * column as soon as it unloads client-side, so the compiled geometry and
 * its GL buffer don't outlive the chunk -- see
 * {@link SectionGeometryStore#removeChunk}. Actual {@code glDeleteBuffers}
 * calls still only happen later, on the render thread, via
 * {@link SectionGeometryStore#drainPendingGpuBufferDeletions}; this handler
 * only queues them.
 *
 * <p>{@code ChunkEvent.Unload} fires for both integrated/dedicated server
 * levels and client levels sharing the same event type -- {@link #getLevel}
 * is checked against {@link ClientLevel} so this is a no-op on a dedicated
 * server (where {@link SectionGeometryStore} is never populated to begin
 * with, since {@link SectionGeometryHandler}/{@link LevelRenderHandler} are
 * both {@code Dist.CLIENT}-only) and on the logical-server side of an
 * integrated server.
 */
@SuppressWarnings({"removal", "JavadocReference"})
@EventBusSubscriber(modid = Tessera.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class SectionUnloadHandler {

    private SectionUnloadHandler() {
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        LevelAccessor level = event.getLevel();
        if (!(level instanceof ClientLevel)) {
            return;
        }

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        SectionGeometryStore.removeChunk(pos.x, pos.z);
    }
}
