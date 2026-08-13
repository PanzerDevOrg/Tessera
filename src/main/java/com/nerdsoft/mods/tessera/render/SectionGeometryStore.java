package com.nerdsoft.mods.tessera.render;

import com.nerdsoft.mods.tessera.atlas.AtlasSplitTarget;
import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 *       fires for that section, mirroring vanilla's own recompile cadence.</li>
 *   <li>{@link LevelRenderHandler} reads (never writes) during
 *       {@code RenderLevelStageEvent}, once per frame per stage.</li>
 *   <li>{@link #removeSection} exists for chunk unload but is not wired to
 *       any event yet -- unloaded sections' entries are retained, a known
 *       memory-growth gap for long sessions with heavy chunk churn.</li>
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
     */
    public static void putSection(BlockPos sectionOrigin, AtlasSplitTarget target, CompiledSectionGeometry geometry) {
        SECTIONS.computeIfAbsent(SectionKey.of(sectionOrigin), k -> new ConcurrentHashMap<>()).put(target, geometry);
    }

    /**
     * Removes a target's geometry for a section, e.g. when a recompile
     * finds no remaining quads for that target.
     */
    public static void removeSectionTarget(BlockPos sectionOrigin, AtlasSplitTarget target) {
        Map<AtlasSplitTarget, CompiledSectionGeometry> perTarget = SECTIONS.get(SectionKey.of(sectionOrigin));
        if (perTarget != null) {
            perTarget.remove(target);
        }
    }

    /**
     * Not currently called by anything -- see this class's lifecycle doc.
     */
    @SuppressWarnings("unused")
    public static void removeSection(BlockPos sectionOrigin) {
        SECTIONS.remove(SectionKey.of(sectionOrigin));
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
     * <p>Entries are never evicted, including for removed sections -- an
     * unbounded GPU-buffer leak, not just the Java-heap gap noted on
     * {@link #removeSection}. Does not cause incorrect rendering, since
     * lookups only ever target geometry about to be drawn.
     */
    public static final class GpuBufferCache {
        private static final Map<CompiledSectionGeometry, Integer> BUFFERS = new java.util.IdentityHashMap<>();

        private GpuBufferCache() {
        }

        /**
         * Returns the cached GL buffer id for this exact geometry
         * instance, or empty if nothing is cached yet (caller should
         * create, upload, and register one via {@link #put}).
         */
        public static java.util.OptionalInt get(CompiledSectionGeometry geometry) {
            Integer id = BUFFERS.get(geometry);
            return id == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(id);
        }

        public static void put(CompiledSectionGeometry geometry, int glBufferId) {
            BUFFERS.put(geometry, glBufferId);
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