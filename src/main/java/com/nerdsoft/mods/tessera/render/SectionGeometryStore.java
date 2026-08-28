package com.nerdsoft.mods.tessera.render;

import com.nerdsoft.mods.tessera.atlas.AtlasSplitTarget;
import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Owns Tessera's own compiled per-section geometry -- the counterpart to
 * vanilla's {@code RenderChunk}-held {@code VertexBuffer}s, for the two
 * render types that don't participate in vanilla's own
 * {@code chunkBufferLayers()} compile/draw cycle (see {@link ModelWrapper}).
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link SectionGeometryHandler} populates one entry per section per
 *       {@link AtlasSplitTarget} whenever {@code AddSectionGeometryEvent}
 *       fires for that section, mirroring vanilla's own recompile cadence.
 *       {@link #putSection}/{@link #removeSectionTarget} run on the compile
 *       thread, not the render thread.</li>
 *   <li>{@link LevelRenderHandler} reads (never writes) during
 *       {@code RenderLevelStageEvent}, once per frame per stage, and calls
 *       {@link #drainPendingGpuBufferDeletions} once per frame to actually
 *       free superseded GL buffers -- see {@link GpuBufferCache}.</li>
 *   <li>{@link #removeChunk} is wired to {@code ChunkEvent.Unload}
 *       client-side (see {@code SectionUnloadHandler}), purging every
 *       section in the unloaded column across all Y.</li>
 * </ul>
 */
public final class SectionGeometryStore {

    private static final Map<SectionKey, Map<AtlasSplitTarget, CompiledSectionGeometry>> SECTIONS = new ConcurrentHashMap<>();

    private SectionGeometryStore() {
    }

    /**
     * Called from the compile-thread callback registered in
     * {@link SectionGeometryHandler} -- {@link ConcurrentHashMap} is
     * required since different sections compile concurrently on
     * different worker threads.
     *
     * <p>If this section+target already held geometry (a recompile, not a
     * first compile), the geometry instance being replaced is queued for
     * GPU-buffer cleanup via {@link GpuBufferCache#queueForDeletion} --
     * compile-thread code must never touch GL directly, so the actual
     * {@code glDeleteBuffers} call happens later, on the render thread,
     * via {@link #drainPendingGpuBufferDeletions}.
     */
    public static void putSection(BlockPos sectionOrigin, AtlasSplitTarget target, CompiledSectionGeometry geometry) {
        Map<AtlasSplitTarget, CompiledSectionGeometry> perTarget =
                SECTIONS.computeIfAbsent(SectionKey.of(sectionOrigin), k -> new ConcurrentHashMap<>());
        CompiledSectionGeometry previous = perTarget.put(target, geometry);
        if (previous != null && previous != geometry) {
            GpuBufferCache.queueForDeletion(previous);
        }
    }

    /**
     * Removes a target's geometry for a section, e.g. when a recompile
     * finds no remaining quads for that target. Queues the removed
     * geometry's GL buffer (if any) for deletion, same as {@link #putSection}.
     */
    public static void removeSectionTarget(BlockPos sectionOrigin, AtlasSplitTarget target) {
        Map<AtlasSplitTarget, CompiledSectionGeometry> perTarget = SECTIONS.get(SectionKey.of(sectionOrigin));
        if (perTarget != null) {
            CompiledSectionGeometry removed = perTarget.remove(target);
            if (removed != null) {
                GpuBufferCache.queueForDeletion(removed);
            }
        }
    }

    /**
     * Removes every stored target's geometry for one section (all render
     * layers), queuing each one's GL buffer for deletion. Used by
     * {@link #removeChunk}; also safe to call directly for a single
     * section (e.g. a future per-section unload signal, should one become
     * available).
     */
    public static void removeSection(BlockPos sectionOrigin) {
        Map<AtlasSplitTarget, CompiledSectionGeometry> perTarget = SECTIONS.remove(SectionKey.of(sectionOrigin));
        if (perTarget != null) {
            for (CompiledSectionGeometry geometry : perTarget.values()) {
                GpuBufferCache.queueForDeletion(geometry);
            }
        }
    }

    /**
     * Removes every section in the given chunk column (every Y slice),
     * queuing their GL buffers for deletion. Called from
     * {@code SectionUnloadHandler} on {@code ChunkEvent.Unload}.
     *
     * <p>{@code chunkX}/{@code chunkZ} are chunk coordinates (i.e.
     * {@code ChunkPos.x}/{@code .z}, block coordinate divided by 16), not
     * block coordinates -- matches how {@link SectionKey}'s own stored
     * block-origin coordinates are compared here via {@code >> 4}.
     */
    public static void removeChunk(int chunkX, int chunkZ) {
        Iterator<Map.Entry<SectionKey, Map<AtlasSplitTarget, CompiledSectionGeometry>>> iterator = SECTIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SectionKey, Map<AtlasSplitTarget, CompiledSectionGeometry>> entry = iterator.next();
            SectionKey key = entry.getKey();
            if ((key.x() >> 4) == chunkX && (key.z() >> 4) == chunkZ) {
                for (CompiledSectionGeometry geometry : entry.getValue().values()) {
                    GpuBufferCache.queueForDeletion(geometry);
                }
                iterator.remove();
            }
        }
    }

    /**
     * Render-thread iteration for {@link LevelRenderHandler}. Allocates one
     * {@link BlockPos} per section per draw call -- bounded by loaded-section
     * count, not scene complexity, so not a hot-path allocation.
     */
    public static void forEachSection(AtlasSplitTarget target, java.util.function.BiConsumer<BlockPos, CompiledSectionGeometry> action) {
        for (Map.Entry<SectionKey, Map<AtlasSplitTarget, CompiledSectionGeometry>> entry : SECTIONS.entrySet()) {
            CompiledSectionGeometry geometry = entry.getValue().get(target);
            if (geometry != null && geometry.quadCount() > 0) {
                SectionKey key = entry.getKey();
                action.accept(new BlockPos(key.x(), key.y(), key.z()), geometry);
            }
        }
    }

    /**
     * Render-thread-only: actually frees every GL buffer queued by
     * {@link GpuBufferCache#queueForDeletion} since the last call. Must be
     * called once per frame from {@link LevelRenderHandler} (the only
     * class in this pipeline already guaranteed to run on the render
     * thread every frame) -- calling this from any other thread is a GL
     * context violation.
     */
    public static void drainPendingGpuBufferDeletions() {
        GpuBufferCache.drainPendingDeletions();
    }

    /**
     * One compiled buffer's worth of geometry for one section, one atlas
     * target. {@code vertexData} is already-baked {@code DefaultVertexFormat.BLOCK}
     * vertex bytes, ready to upload as-is. {@code quadCount} is tracked
     * separately since it determines the index buffer draw count.
     *
     * <p>Each call to {@link #putSection} creates a new instance -- object
     * identity is therefore a valid, cheap "did this section's geometry
     * change since last frame" signal, which {@link GpuBufferCache} relies
     * on to skip re-uploading unchanged sections.
     */
    public record CompiledSectionGeometry(ByteBuffer vertexData, int quadCount) {
    }

    /**
     * Render-thread-only cache mapping a specific
     * {@link CompiledSectionGeometry} instance to the persistent GL buffer
     * already holding its uploaded contents, so {@link LevelRenderHandler}
     * doesn't recreate a VBO every section every frame.
     *
     * <p>{@link IdentityHashMap} is deliberate: keying on object identity
     * (not the record's generated {@code equals()}) avoids comparing
     * potentially large {@code ByteBuffer} contents every frame. A new
     * instance only exists because {@link #putSection} ran again, which
     * only happens on an actual recompile.
     *
     * <p>Superseded/removed geometry's GL buffer is queued (from whichever
     * compile thread replaced or removed it -- see {@link #queueForDeletion})
     * and actually freed on the next {@link #drainPendingDeletions} call
     * from the render thread, closing what was previously an unbounded
     * GPU-buffer leak on every recompile and every chunk unload.
     */
    public static final class GpuBufferCache {
        private static final Map<CompiledSectionGeometry, Integer> BUFFERS = new IdentityHashMap<>();
        private static final ConcurrentLinkedQueue<Integer> PENDING_DELETIONS = new ConcurrentLinkedQueue<>();

        private GpuBufferCache() {
        }

        /**
         * Returns the cached GL buffer id for this exact geometry
         * instance, or empty if nothing is cached yet (caller should
         * create, upload, and register one via {@link #put}).
         */
        public static java.util.OptionalInt get(CompiledSectionGeometry geometry) {
            Integer id;
            synchronized (BUFFERS) {
                id = BUFFERS.get(geometry);
            }
            return id == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(id);
        }

        public static void put(CompiledSectionGeometry geometry, int glBufferId) {
            synchronized (BUFFERS) {
                BUFFERS.put(geometry, glBufferId);
            }
        }

        /**
         * Called from a compile thread (via {@link SectionGeometryStore}'s
         * put/remove methods) when a {@link CompiledSectionGeometry}
         * instance is no longer reachable from {@code SECTIONS}. If it has
         * an uploaded GL buffer, removes it from the identity cache
         * (freeing the strong reference so the geometry/vertex bytes can
         * be collected) and queues its id for render-thread deletion.
         * Never touches GL directly -- this may run on any compile thread.
         */
        static void queueForDeletion(CompiledSectionGeometry geometry) {
            Integer id;
            synchronized (BUFFERS) {
                id = BUFFERS.remove(geometry);
            }
            if (id != null) {
                PENDING_DELETIONS.add(id);
            }
        }

        /**
         * Render-thread-only. Drains and deletes every GL buffer queued by
         * {@link #queueForDeletion} since the last call. Safe to call every
         * frame even when nothing is queued (empty poll loop, no GL calls
         * issued).
         */
        static void drainPendingDeletions() {
            Integer id;
            while ((id = PENDING_DELETIONS.poll()) != null) {
                org.lwjgl.opengl.GL15.glDeleteBuffers(id);
            }
        }
    }

    private record SectionKey(int x, int y, int z) {
        static SectionKey of(BlockPos sectionOrigin) {
            return new SectionKey(sectionOrigin.getX(), sectionOrigin.getY(), sectionOrigin.getZ());
        }
    }

    public static int getSectionCount(AtlasSplitTarget target) {
        int count = 0;
        for (Map.Entry<SectionKey, Map<AtlasSplitTarget, CompiledSectionGeometry>> entry : SECTIONS.entrySet()) {
            CompiledSectionGeometry geometry = entry.getValue().get(target);
            if (geometry != null && geometry.quadCount() > 0) {
                count++;
            }
        }
        return count;
    }
}