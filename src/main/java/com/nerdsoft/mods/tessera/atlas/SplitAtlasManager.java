package com.nerdsoft.mods.tessera.atlas;

import com.nerdsoft.mods.tessera.compress.CompressionPipeline;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Owns Tessera's independent static atlases -- one opaque/alpha pair per
 * {@link SourceAtlasFamily} (see {@link AtlasSplitTarget}) -- as real,
 * standalone {@link TextureAtlas} instances, each with its own GL texture
 * ID and its own {@code SpriteLoader.stitch()} pass, so every
 * {@code TextureAtlasSprite} they produce has UVs correctly relative to
 * <em>that</em> atlas alone.
 *
 * <p>Nothing downstream needs a routing table to find the right UV space --
 * the UVs are already correct because each atlas was stitched on its own.
 * What downstream code (block/chunk render routing, GUI sprite blit
 * routing) still needs is to know <em>which atlas</em> a given sprite ended
 * up on, which is what {@link #routingFor} answers.
 *
 * <h2>Compression</h2>
 * BC1/BC7 compression itself runs on the background executor, immediately
 * after each atlas's own stitch completes (see {@link #tessera$stitchFamily}/
 * {@link #tessera$compressInBackground}) -- not on the render thread. Only
 * the final GL upload of the compressed result happens on the render
 * thread (see {@link #tessera$applyOnRenderThread}), via
 * {@link AtlasCompressionDriver#upload}, immediately after vanilla's own
 * uncompressed upload for that atlas. Both BC1 and BC7 always go through
 * the same CPU-side {@link AtlasCompressionDriver#compress} path; there is
 * no GPU-compute BC1 branch. Compression failure of any kind (unsupported
 * GPU, VRAM budget, native bridge unavailable) leaves an atlas on
 * vanilla's already-completed uncompressed upload rather than leaving it
 * without texture data.
 *
 * <h2>Per-family requested mip level</h2>
 * Each {@link SourceAtlasFamily} stitches its owned atlases at
 * {@link SourceAtlasFamily#maxMipLevel()}, NOT unconditionally at the
 * block-mipmap option's value. {@code BLOCKS} uses
 * {@code Minecraft.getInstance().options.mipmapLevels().get()}, matching
 * vanilla's own {@code minecraft:textures/atlas/blocks} stitch depth.
 * {@code GUI} always uses 0, matching vanilla's own
 * {@code minecraft:textures/atlas/gui} stitch depth. Both source atlases'
 * sprites share the same underlying {@code SpriteContents} objects Tessera
 * routes here (see {@code SpriteRoutingMixin}), and a {@code SpriteContents}'
 * per-sprite {@code byMipLevel} array is populated -- once, shared, not
 * atlas-specific -- to whatever the deepest level any stitch call against
 * it requested. Requesting a deeper level here than vanilla's own atlas
 * will request means vanilla's later {@code TextureAtlas.upload()} still
 * finds and uploads that deeper data via {@code TextureAtlasSprite.uploadFirstFrame},
 * into level indices vanilla's own atlas never allocated storage for --
 * {@code GL_INVALID_VALUE} ("Invalid texture format") once per excess
 * level per sprite, and the affected texture is left without valid data
 * (renders black/invisible). Requesting 0 for GUI keeps the shared array
 * no deeper than vanilla's own gui atlas will ever ask of it.
 *
 * <h2>Registration and reload sequencing</h2>
 * Registered as a {@link PreparableReloadListener} via
 * {@code RegisterClientReloadListenersEvent} purely so its owned
 * {@link TextureAtlas} instances participate in the client resource
 * manager's listener lifecycle (see {@link #reload} for why that method
 * itself is a no-op). The actual stitch/upload work does not run on an
 * independent reload cycle -- it rides on vanilla's own
 * {@code SpriteLoader.stitch()} calls for each split-eligible source atlas
 * ({@code minecraft:textures/atlas/blocks}, {@code minecraft:textures/atlas/gui}),
 * driven by {@code SpriteRoutingMixin} calling {@link #accumulateStaticSprites}
 * / {@link #applyPendingSplitStitch} at the matching points in each
 * source atlas's own prepare/apply phases. This is deliberate: vanilla's
 * {@code Stitcher} is the only place the full static sprite list for a
 * source atlas is available, so Tessera reuses that exact list rather than
 * re-discovering it via a second, independent reload pass that would need
 * its own model-material enumeration.
 */
public final class SplitAtlasManager implements PreparableReloadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/SplitAtlasManager");
    private static final ThreadLocal<Boolean> IS_DISPATCHING = ThreadLocal.withInitial(() -> false);

    private final Map<AtlasSplitTarget, TextureAtlas> tessera$atlases;

    /**
     * Per-reload routing: which {@link AtlasSplitTarget} a given sprite
     * {@link ResourceLocation} ended up on. Published once per successful
     * reload (see {@link #tessera$applyOnRenderThread}), consumed by the
     * block/chunk render-type routing mixin and the GUI sprite blit mixin
     * that read which atlas each sprite's geometry should bind against.
     * Volatile rather than synchronized -- published via a single
     * reference swap after the reload's apply phase completes on the
     * render thread, read from the render thread thereafter; no
     * concurrent-mutation window exists once published.
     */
    private volatile Map<ResourceLocation, AtlasSplitTarget> tessera$spriteRouting = Map.of();
    private volatile Map<SourceAtlasFamily, StitchResult> tessera$pendingResults = Map.of();

    /**
     * One accumulator per {@link SourceAtlasFamily}: every source atlas
     * that family covers (see {@code SpriteRoutingMixin#tessera$familyFor})
     * contributes its static sprites here during the prepare phase: a
     * family's own combined split-stitch runs once, over that family's
     * full contribution, from the apply phase (see
     * {@link #tessera$triggerMergedStitchIfNeeded}). Synchronized rather
     * than a lock-free structure: contributions arrive concurrently from
     * multiple {@code SpriteLoader.stitch()} background-executor
     * invocations, but this only runs once per reload per family (not a
     * hot path), so a simple synchronized block is the correct,
     * simplest-safe choice here.
     */
    private final Map<SourceAtlasFamily, List<SpriteContents>> tessera$accumulated = new EnumMap<>(SourceAtlasFamily.class);
    private final Map<SourceAtlasFamily, Executor> tessera$accumulatedExecutor = new EnumMap<>(SourceAtlasFamily.class);

    /**
     * Reload-generation counter, one per family. Bumped once per
     * fully-applied reload (see {@link #applyPendingSplitStitch}) --
     * {@link #tessera$triggerMergedStitchIfNeeded} is keyed off this so it
     * only ever runs a family's merged stitch once per reload cycle,
     * regardless of how many of that family's own source atlases' apply-
     * phase callbacks call it. Minecraft's own {@code ReloadableResourceManager}
     * serializes reload cycles (a new reload's prepare phase cannot begin
     * until the previous one's apply phase has fully resolved), so there
     * is no window where two reloads' accumulator contributions could
     * interleave.
     */
    private final Map<SourceAtlasFamily, Long> tessera$reloadGeneration = new EnumMap<>(SourceAtlasFamily.class);
    private final Map<SourceAtlasFamily, Long> tessera$mergedStitchTriggeredGeneration = new EnumMap<>(SourceAtlasFamily.class);
    private final Map<SourceAtlasFamily, CompletableFuture<Void>> tessera$mergedStitchFuture = new EnumMap<>(SourceAtlasFamily.class);

    /**
     * Published atomically alongside {@link #tessera$spriteRouting} once
     * per reload (see {@link #tessera$applyOnRenderThread}) -- tracks
     * which atlases actually got a real
     * {@code glTexImage2D}/{@code glCompressedTexImage2D} call this reload
     * via {@code TextureAtlas.upload()}. An atlas with zero routed sprites
     * never has {@code upload()} called on it, so its GL texture name --
     * if one exists at all -- has no defined storage. Binding + sampling
     * such a texture produces {@code GL_INVALID_OPERATION}; this set is
     * the single source of truth render code must check before ever
     * calling {@code atlasFor(target).getId()}.
     */
    private volatile Set<AtlasSplitTarget> tessera$uploadedTargets = Set.of();

    public SplitAtlasManager() {
        Map<AtlasSplitTarget, TextureAtlas> atlases = new EnumMap<>(AtlasSplitTarget.class);
        for (AtlasSplitTarget target : AtlasSplitTarget.values()) {
            if (target.eligible()) {
                atlases.put(target, new TextureAtlas(target.atlasLocation()));
            }
        }
        this.tessera$atlases = Collections.unmodifiableMap(atlases);

        for (SourceAtlasFamily family : SourceAtlasFamily.values()) {
            tessera$accumulated.put(family, new ArrayList<>());
            tessera$reloadGeneration.put(family, 0L);
            tessera$mergedStitchTriggeredGeneration.put(family, -1L);
        }
    }

    public TextureAtlas atlasFor(AtlasSplitTarget target) {
        return tessera$atlases.get(target);
    }

    /**
     * Whether {@code target}'s atlas has valid, sampleable GL storage for
     * the current reload. Must be checked before any render code binds
     * {@link #atlasFor} for this target -- an empty bucket this reload
     * (zero static sprites routed here) means upload() was never called
     * and the underlying texture name has no defined storage.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean hasContent(AtlasSplitTarget target) {
        return tessera$uploadedTargets.contains(target);
    }

    /**
     * Returns which atlas a sprite was routed to, or {@code null} if this
     * sprite was not part of the static split for the current reload (e.g.
     * it is a dynamic/ticked sprite left on vanilla's own atlas, or it
     * belongs to a source atlas Tessera does not split).
     */
    public AtlasSplitTarget routingFor(ResourceLocation spriteName) {
        return tessera$spriteRouting.get(spriteName);
    }

    @SuppressWarnings("DataFlowIssue")
    @Override
    public @NotNull CompletableFuture<Void> reload(
            PreparationBarrier barrier, @NotNull ResourceManager resourceManager,
            @NotNull ProfilerFiller prepareProfiler, @NotNull ProfilerFiller applyProfiler,
            @NotNull Executor backgroundExecutor, @NotNull Executor gameExecutor
    ) {
        // Intentionally a no-op reload of its own. Tessera's split atlases
        // do not run an independent reload cycle -- they ride on vanilla's
        // own SpriteLoader.stitch() reload for each split-eligible source
        // atlas, via SpriteRoutingMixin calling accumulateStaticSprites /
        // applyPendingSplitStitch at the right points in that cycle. This
        // class is still registered as a PreparableReloadListener (see
        // Tessera#onRegisterReloadListeners) purely so its TextureAtlas
        // instances participate in the client resource manager's listener
        // list for lifecycle purposes -- the actual stitch/upload work
        // happens off this method entirely.
        return barrier.wait(null);
    }

    /**
     * Accumulates one source atlas's worth of static sprites (pulled out
     * of vanilla's own {@code SpriteLoader.stitch()} call by
     * {@code SpriteRoutingMixin}) into {@code family}'s combined pool for
     * this reload, instead of immediately stitching them in isolation --
     * a source atlas family's owned targets ({@link SourceAtlasFamily#opaqueTarget()}/
     * {@link SourceAtlasFamily#alphaTarget()}) are one physical pair, not
     * one per source atlas, so only a single combined stitch pass over
     * that family's accumulated contributions can populate them correctly.
     *
     * <p>Detects the start of a new reload by checking whether the
     * previous reload's merged stitch for this family has already been
     * triggered (see {@link #tessera$triggerMergedStitchIfNeeded}) -- if
     * so, this contribution cannot belong to that already-triggered reload
     * (its prepare phase is long over by construction; a reload's
     * prepare-phase calls cannot outlive that same reload's own apply
     * phase, and triggering only ever happens from the apply phase), so it
     * must be the first contribution of the next one, and this family's
     * generation counter advances to match.
     *
     * @param family           which split target pair this source atlas's
     *                         sprites belong to
     * @param allStaticSprites every non-dynamic sprite vanilla would have
     *                         stitched into this one source atlas this
     *                         reload
     * @param executor         the same background executor vanilla's own
     *                         stitch call was given for this source atlas
     */
    public synchronized void accumulateStaticSprites(SourceAtlasFamily family, List<SpriteContents> allStaticSprites, Executor executor) {
        if (Objects.equals(tessera$mergedStitchTriggeredGeneration.get(family), tessera$reloadGeneration.get(family))) {
            // This family's current generation has already had its merged
            // stitch triggered (from a previous reload's apply phase) --
            // this contribution belongs to a new reload starting up.
            // Advance the generation so this reload gets its own fresh
            // trigger window; the accumulator is already guaranteed empty
            // at this point (tessera$triggerMergedStitchIfNeeded drained it
            // when it triggered), so there is nothing to clear here.
            tessera$reloadGeneration.merge(family, 1L, Long::sum);
        }
        tessera$accumulated.get(family).addAll(allStaticSprites);
        tessera$accumulatedExecutor.put(family, executor);
        LOGGER.debug("[Tessera-Debug] {}: accumulated {} static sprites this reload (running total {}).",
                family, allStaticSprites.size(), tessera$accumulated.get(family).size());
    }

    public static void setDispatching(boolean value) {
        IS_DISPATCHING.set(value);
    }

    public static boolean isDispatching() {
        return IS_DISPATCHING.get();
    }

    /**
     * Triggers the single combined split-stitch for {@code family}'s
     * accumulated static-sprite pool this reload, exactly once. Safe to
     * call from every one of that family's source atlases' apply-phase
     * callbacks (see {@code TextureAtlasUploadMixin}) -- by the time ANY
     * atlas reaches its apply phase, Minecraft's own
     * {@code PreparationBarrier} guarantees every reload listener's
     * prepare phase (including every {@link #accumulateStaticSprites}
     * call for this reload) has already completed, so it is always safe
     * to snapshot and consume the accumulator the first time this fires
     * for a given family. Subsequent calls this same reload (from that
     * family's other source atlases' own apply phases) are no-ops.
     */
    public synchronized CompletableFuture<Void> tessera$triggerMergedStitchIfNeeded(SourceAtlasFamily family) {
        if (Objects.equals(tessera$mergedStitchTriggeredGeneration.get(family), tessera$reloadGeneration.get(family))) {
            return tessera$mergedStitchFuture.get(family);
        }
        tessera$mergedStitchTriggeredGeneration.put(family, tessera$reloadGeneration.get(family));

        List<SpriteContents> merged = List.copyOf(tessera$accumulated.get(family));
        Executor executor = tessera$accumulatedExecutor.get(family);
        tessera$accumulated.get(family).clear();

        if (merged.isEmpty() || executor == null) {
            CompletableFuture<Void> empty = CompletableFuture.completedFuture(null);
            tessera$mergedStitchFuture.put(family, empty);
            return empty;
        }

        // Guard against re-entrancy: this family's owned TextureAtlas
        // instances (see tessera$stitchFamily below) are themselves
        // TextureAtlas instances and therefore carry SpriteRoutingMixin
        // too -- without this guard, their own internal
        // SpriteLoader.create(...).stitch(...) calls (fired synchronously
        // inside tessera$stitchFamily, on whichever thread calls this
        // method) would recurse straight back into the mixin's capture
        // point and re-accumulate Tessera's own already-split sprites into
        // the pool for the *next* reload, which would then grow
        // unboundedly reload over reload.
        setDispatching(true);
        CompletableFuture<Void> future;
        try {
            future = tessera$stitchFamily(family, merged, executor)
                    .thenAccept(result -> {
                        synchronized (this) {
                            Map<SourceAtlasFamily, StitchResult> updated = new EnumMap<>(SourceAtlasFamily.class);
                            updated.putAll(tessera$pendingResults);
                            updated.put(family, result);
                            tessera$pendingResults = updated;
                        }
                    });
        } finally {
            setDispatching(false);
        }
        tessera$mergedStitchFuture.put(family, future);
        return future;
    }

    /**
     * Applies whatever {@link #tessera$triggerMergedStitchIfNeeded}
     * produced for {@code family}'s combined static-sprite pool this
     * reload. Must run on the render thread, after that family's merged
     * stitch future has completed -- {@code TextureAtlasUploadMixin} is
     * responsible for sequencing this the same way vanilla sequences its
     * own reload's apply phase, since Tessera's extra atlases are riding
     * along on the same reload cycle rather than running their own
     * independent {@link #reload}.
     */
    public void applyPendingSplitStitch(SourceAtlasFamily family, ProfilerFiller profiler) {
        StitchResult result;
        synchronized (this) {
            result = tessera$pendingResults.get(family);
            if (result != null) {
                Map<SourceAtlasFamily, StitchResult> updated = new EnumMap<>(tessera$pendingResults);
                updated.remove(family);
                tessera$pendingResults = updated;
            }
            // Bumped unconditionally, even when result is null (this
            // family's source atlas(es) had zero static sprites this
            // reload -- see tessera$triggerMergedStitchIfNeeded's
            // merged.isEmpty() branch, which deliberately never populates
            // tessera$pendingResults in that case since there is nothing
            // to apply). Without this, a zero-static-sprite reload would
            // leave this family's generation counters equal to each other
            // AND unchanged from before this reload started, so the very
            // next reload's first tessera$triggerMergedStitchIfNeeded call
            // for this family would incorrectly read as "already triggered
            // this generation" and return the stale completed-with-nothing
            // future instead of running a fresh stitch over whatever that
            // next reload actually accumulated.
            tessera$reloadGeneration.merge(family, 1L, Long::sum);
        }
        if (result == null) {
            return;
        }
        tessera$applyOnRenderThread(family, result, profiler);
    }

    /**
     * Classifies {@code family}'s full static sprite set via
     * {@link SpriteClassifier} (its opaque/punch-through/blended
     * alpha-shape detection is orthogonal to which family a sprite came
     * from), then runs two fully independent {@code SpriteLoader.stitch()}
     * calls, one per target atlas owned by this family. Each call only
     * ever sees sprites destined for that one atlas, so the
     * {@code Stitcher}'s bin-packing and UV assignment for each atlas is
     * self-contained by construction -- there is no merge step that could
     * reintroduce cross-atlas coordinate confusion, and no cross-family
     * mixing either, since a different family's sprites were never in this
     * classification call to begin with.
     *
     * <p>CPU-side BC1/BC7 compression ({@link AtlasCompressionDriver#compress})
     * happens here, on {@code executor}, immediately after each atlas's
     * own stitch completes -- both the opaque and the alpha target always
     * take this same background CPU path; there is no render-thread GPU
     * compute branch.
     */
    @SuppressWarnings("LoggingSimilarMessage")
    private CompletableFuture<StitchResult> tessera$stitchFamily(SourceAtlasFamily family, List<SpriteContents> allStaticSprites, Executor executor) {
        AtlasSplitTarget opaqueTarget = family.opaqueTarget();
        AtlasSplitTarget alphaTarget = family.alphaTarget();

        SpriteClassifier.ClassificationResult classification = SpriteClassifier.classify(allStaticSprites);

        List<SpriteContents> opaqueSprites = tessera$mergeOpaqueBuckets(classification);
        List<SpriteContents> alphaSprites = classification.bucket(SpriteBucket.ALPHA_BC7);

        // Each split atlas gets its own independent SpriteLoader.stitch()
        // call, so each independently needs a sprite satisfying
        // SpriteLoader's own missing() detection (match against
        // MissingTextureAtlasSprite.getLocation()) -- but SpriteClassifier
        // routes every sprite, including the missing-texture one, into
        // exactly ONE bucket by alpha shape. Whichever atlas didn't
        // receive it stitches with missing() == null, and vanilla's
        // TextureAtlas.upload() throws IllegalStateException ("has no
        // missing texture sprite") the moment that atlas has at least one
        // region. Force it into both lists here rather than relying on
        // classification, since both atlases need it regardless of its
        // own alpha shape. A plain contains() check is fine: allStaticSprites
        // is at most a few thousand SpriteContents per reload, this runs
        // once per reload per family, and SpriteContents doesn't override
        // equals()/hashCode() so this is an identity check either way --
        // not worth a Set for one lookup per list.
        SpriteContents missingSprite = null;
        for (SpriteContents contents : allStaticSprites) {
            if (contents.name().equals(MissingTextureAtlasSprite.getLocation())) {
                missingSprite = contents;
                break;
            }
        }
        if (missingSprite != null) {
            if (!opaqueSprites.contains(missingSprite)) {
                opaqueSprites.add(missingSprite);
            }
            if (!alphaSprites.contains(missingSprite)) {
                alphaSprites.add(missingSprite);
            }
        }

        SpriteLoader opaqueLoader = SpriteLoader.create(tessera$atlases.get(opaqueTarget));
        SpriteLoader alphaLoader = SpriteLoader.create(tessera$atlases.get(alphaTarget));

        // family.maxMipLevel() -- NOT unconditionally
        // Minecraft.getInstance().options.mipmapLevels().get() -- MUST
        // match the maxMipLevel vanilla's own source atlas stitch for
        // this family requests. See SourceAtlasFamily#maxMipLevel and
        // this class's own doc comment: BLOCKS matches the block-mipmap
        // option (vanilla's minecraft:textures/atlas/blocks stitches at
        // that same depth); GUI is hardcoded to 0 (vanilla's
        // minecraft:textures/atlas/gui never requests mips). Sprites in
        // both families share their SpriteContents objects with vanilla's
        // own atlas; a SpriteContents' byMipLevel array is populated once,
        // shared, to whatever the deepest level ANY stitch call against it
        // requested. Requesting a level here deeper than vanilla's own
        // atlas will request means vanilla's later TextureAtlas.upload()
        // still uploads that now-present deeper data via
        // TextureAtlasSprite.uploadFirstFrame, into level indices vanilla's
        // own atlas never allocated storage for: GL_INVALID_VALUE
        // ("Invalid texture format") once per excess level per sprite, and
        // the affected atlas is left without valid data for those texels
        // (renders black/invisible). This was previously hardcoded to the
        // block-mipmap option unconditionally for every family, which is
        // correct for BLOCKS but wrong for GUI.
        int maxMipLevel = family.maxMipLevel();
        LOGGER.debug("[Tessera-Debug] {}: stitching at maxMipLevel={} ({} opaque, {} alpha sprites, missing-texture present={}).",
                family, maxMipLevel, opaqueSprites.size(), alphaSprites.size(), missingSprite != null);

        CompletableFuture<SpriteLoader.Preparations> opaqueFuture = opaqueSprites.isEmpty()
                ? CompletableFuture.completedFuture(tessera$emptyPreparations())
                : opaqueLoader.stitch(opaqueSprites, maxMipLevel, executor).waitForUpload();

        CompletableFuture<SpriteLoader.Preparations> alphaFuture = alphaSprites.isEmpty()
                ? CompletableFuture.completedFuture(tessera$emptyPreparations())
                : alphaLoader.stitch(alphaSprites, maxMipLevel, executor).waitForUpload();

        // thenApplyAsync(..., executor) rather than thenApply(...) pins
        // this work to the background executor explicitly -- without it,
        // it would run on whichever thread completes opaqueFuture/
        // alphaFuture, which for a stitch() future is normally the
        // background executor but isn't contractually guaranteed to stay
        // that way, so pinning avoids ever risking this sneaking onto the
        // render thread.
        CompletableFuture<AtlasCompressionDriver.CompressedAtlas> opaqueCompressedFuture =
                opaqueFuture.thenApplyAsync(prep -> tessera$compressInBackground(opaqueTarget, prep), executor);
        CompletableFuture<AtlasCompressionDriver.CompressedAtlas> alphaCompressedFuture =
                alphaFuture.thenApplyAsync(prep -> tessera$compressInBackground(alphaTarget, prep), executor);

        CompletableFuture<StitchResult> preparationsAndRouting = opaqueFuture.thenCombine(alphaFuture,
                (opaquePrep, alphaPrep) -> new StitchResult(
                        opaqueTarget, alphaTarget, opaquePrep, alphaPrep,
                        tessera$buildRouting(opaqueTarget, alphaTarget, opaquePrep, alphaPrep), null, null));

        return preparationsAndRouting.thenCombine(opaqueCompressedFuture, (partial, opaqueCompressed) ->
                        new StitchResult(partial.opaqueTarget(), partial.alphaTarget(), partial.opaquePreparations(), partial.alphaPreparations(), partial.routing(),
                                opaqueCompressed, null))
                .thenCombine(alphaCompressedFuture, (partial, alphaCompressed) ->
                        new StitchResult(partial.opaqueTarget(), partial.alphaTarget(), partial.opaquePreparations(), partial.alphaPreparations(), partial.routing(),
                                partial.opaqueCompressed(), alphaCompressed));
    }

    /**
     * Runs {@link AtlasCompressionDriver#compress} for one atlas. Called
     * from a {@code thenApplyAsync(..., executor)} continuation (see
     * {@link #tessera$stitchFamily}), so this always executes on the
     * background executor, never the render thread. Handles both BC1 and
     * BC7 targets identically -- {@link AtlasCompressionDriver#compress}
     * branches on {@code target} internally via {@link CompressionPipeline}'s
     * CPU-only native call. Returns an empty {@code CompressedAtlas} (zero
     * levels) for an empty atlas, which {@link #tessera$applyOnRenderThread}
     * treats as "nothing to upload, leave vanilla's own upload as-is".
     */
    private AtlasCompressionDriver.CompressedAtlas tessera$compressInBackground(
            AtlasSplitTarget target, SpriteLoader.Preparations preparations
    ) {
        if (preparations.regions().isEmpty()) {
            return new AtlasCompressionDriver.CompressedAtlas(target.atlasLocation(), target.compressionTarget(), List.of(), 0);
        }

        ByteBuffer baseRgba8 = AtlasCompressionDriver.assembleAtlasBuffer(
                target.name(), preparations.regions().values(), preparations.width(), preparations.height());
        if (baseRgba8 == null) {
            LOGGER.info("[Tessera coverage] Atlas {} SKIPPED (native buffer assembly unavailable); remains uncompressed RGBA8.",
                    target.atlasLocation());
            return new AtlasCompressionDriver.CompressedAtlas(target.atlasLocation(), target.compressionTarget(), List.of(), preparations.mipLevel());
        }

        LOGGER.debug("[Tessera-Debug] {}: compressing {}x{} via CPU {} (requestedMaxLevel={}).",
                target, preparations.width(), preparations.height(), target.compressionTarget(), preparations.mipLevel());

        return AtlasCompressionDriver.compress(
                target.atlasLocation(), target.compressionTarget(),
                baseRgba8, preparations.width(), preparations.height(), preparations.mipLevel());
    }

    /**
     * A family's opaque target covers both {@code SpriteBucket.OPAQUE_BC1}
     * and {@code SpriteBucket.PUNCHTHROUGH_BC1} -- both encode to BC1 at
     * the same 4bpp rate and share one physical atlas.
     */
    private List<SpriteContents> tessera$mergeOpaqueBuckets(SpriteClassifier.ClassificationResult classification) {
        List<SpriteContents> merged = new ArrayList<>(
                classification.bucket(SpriteBucket.OPAQUE_BC1).size()
                        + classification.bucket(SpriteBucket.PUNCHTHROUGH_BC1).size());
        merged.addAll(classification.bucket(SpriteBucket.OPAQUE_BC1));
        merged.addAll(classification.bucket(SpriteBucket.PUNCHTHROUGH_BC1));
        return merged;
    }

    private Map<ResourceLocation, AtlasSplitTarget> tessera$buildRouting(
            AtlasSplitTarget opaqueTarget, AtlasSplitTarget alphaTarget,
            SpriteLoader.Preparations opaque, SpriteLoader.Preparations alpha
    ) {
        Map<ResourceLocation, AtlasSplitTarget> map = new HashMap<>();
        opaque.regions().keySet().forEach(loc -> map.put(loc, opaqueTarget));
        alpha.regions().keySet().forEach(loc -> map.put(loc, alphaTarget));
        return map;
    }

    @SuppressWarnings("DataFlowIssue")
    private SpriteLoader.Preparations tessera$emptyPreparations() {
        return new SpriteLoader.Preparations(0, 0, 0, null, Map.of(), CompletableFuture.completedFuture(null));
    }

    /**
     * Render-thread apply phase for one family. Vanilla's own
     * {@code TextureAtlas.upload(Preparations)} runs first for each of
     * this family's two atlases -- this is what creates each atlas's GL
     * texture ID and performs the baseline RGBA8 upload, exactly as it
     * would for any vanilla atlas. Both targets' compression already ran
     * on the background executor (see {@link #tessera$stitchFamily}/
     * {@link #tessera$compressInBackground}), so this method's own work is
     * GL upload only, via {@link AtlasCompressionDriver#upload}.
     */
    private void tessera$applyOnRenderThread(SourceAtlasFamily family, StitchResult result, ProfilerFiller profiler) {
        profiler.push("tessera_split_atlas_upload_" + family.name().toLowerCase(Locale.ROOT));
        try {
            AtlasSplitTarget opaqueTarget = result.opaqueTarget();
            AtlasSplitTarget alphaTarget = result.alphaTarget();
            TextureAtlas opaqueAtlas = tessera$atlases.get(opaqueTarget);
            TextureAtlas alphaAtlas = tessera$atlases.get(alphaTarget);

            // Guard: vanilla's TextureAtlas.upload(Preparations) requires
            // a non-null Preparations#missing() and throws
            // IllegalStateException otherwise. tessera$emptyPreparations()
            // deliberately passes missing=null for a 0-sprite target. An
            // empty opaque or alpha target is a legitimate, reachable
            // per-reload outcome (e.g. every static sprite this reload
            // classifying into the other target), not a corrupted state,
            // so it must not throw. Skipping upload() for an empty target
            // leaves that atlas without a GL texture ID this reload, which
            // is safe: with zero sprites routed to it, nothing will ever
            // sample it (see routingFor/tessera$buildRouting).
            boolean opaqueHasSprites = !result.opaquePreparations().regions().isEmpty();
            boolean alphaHasSprites = !result.alphaPreparations().regions().isEmpty();

            if (opaqueHasSprites) {
                GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0,
                        "tessera:vanilla-upload:" + opaqueTarget.atlasLocation());
                try {
                    opaqueAtlas.upload(result.opaquePreparations());
                } finally {
                    GL43.glPopDebugGroup();
                }
            }
            if (alphaHasSprites) {
                GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, 0,
                        "tessera:vanilla-upload:" + alphaTarget.atlasLocation());
                try {
                    alphaAtlas.upload(result.alphaPreparations());
                } finally {
                    GL43.glPopDebugGroup();
                }
            }

            synchronized (this) {
                Map<ResourceLocation, AtlasSplitTarget> mergedRouting = new HashMap<>(tessera$spriteRouting);
                mergedRouting.putAll(result.routing());
                tessera$spriteRouting = mergedRouting;
            }
            LOGGER.info("Tessera split atlases stitched ({}): {} opaque sprites, {} alpha sprites.",
                    family, result.opaquePreparations().regions().size(),
                    result.alphaPreparations().regions().size());

            long opaqueResident = (opaqueHasSprites && result.opaqueCompressed() != null)
                    ? AtlasCompressionDriver.upload(opaqueAtlas.getId(), result.opaqueCompressed())
                    : -1;
            long alphaResident = (alphaHasSprites && result.alphaCompressed() != null)
                    ? AtlasCompressionDriver.upload(alphaAtlas.getId(), result.alphaCompressed())
                    : -1;

            if (opaqueHasSprites && opaqueResident < 0) {
                LOGGER.info("Atlas {} remains on vanilla's uncompressed RGBA8 upload (compression skipped or failed).",
                        opaqueTarget.atlasLocation());
            }
            if (alphaHasSprites && alphaResident < 0) {
                LOGGER.info("Atlas {} remains on vanilla's uncompressed RGBA8 upload (compression skipped or failed).",
                        alphaTarget.atlasLocation());
            }

            // Published together with tessera$spriteRouting above (same
            // reload, same publish point) so a render-thread read of one
            // is never stale relative to the other. Gated on *HasSprites
            // alone, not on *Resident -- an atlas that fell back to
            // vanilla's uncompressed RGBA8 path (opaqueResident < 0 above,
            // compression skipped/failed) still had TextureAtlas.upload()
            // called on it a few lines up and therefore has valid,
            // sampleable GL storage; only the "zero sprites routed here"
            // case leaves the texture name without defined storage.
            synchronized (this) {
                Set<AtlasSplitTarget> uploaded = EnumSet.noneOf(AtlasSplitTarget.class);
                uploaded.addAll(tessera$uploadedTargets);
                uploaded.remove(opaqueTarget);
                uploaded.remove(alphaTarget);
                if (opaqueHasSprites) {
                    uploaded.add(opaqueTarget);
                }
                if (alphaHasSprites) {
                    uploaded.add(alphaTarget);
                }
                this.tessera$uploadedTargets = uploaded;
            }
        } finally {
            profiler.pop();
        }
    }

    private record StitchResult(AtlasSplitTarget opaqueTarget, AtlasSplitTarget alphaTarget,
                                SpriteLoader.Preparations opaquePreparations,
                                SpriteLoader.Preparations alphaPreparations,
                                Map<ResourceLocation, AtlasSplitTarget> routing,
                                AtlasCompressionDriver.CompressedAtlas opaqueCompressed,
                                AtlasCompressionDriver.CompressedAtlas alphaCompressed) {
    }
}