package com.nerdsoft.mods.tessera.mixin;

import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.atlas.SourceAtlasFamily;
import com.nerdsoft.mods.tessera.config.RulesManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies whichever split-stitch results {@link SpriteRoutingMixin} queued
 * up for the current reload, at the same point vanilla uploads its own
 * atlas -- {@code TextureAtlas.upload(Preparations)}, always called from
 * the render thread as part of {@code ReloadableResourceManager}'s apply
 * phase. This is the correct/only safe place to call GL upload functions
 * for Tessera's split atlases: {@code SpriteLoader.stitch()} itself (where
 * {@code SpriteRoutingMixin} does its work) runs on a background executor
 * and must never touch GL directly.
 *
 * <p>Fires for every non-blacklisted {@code TextureAtlas} instance's
 * upload -- not just the specific source atlas(es) Tessera splits -- and
 * triggers/applies every {@link SourceAtlasFamily}'s merged stitch
 * unconditionally each time. Each family's own trigger/apply pair is
 * idempotent per reload generation (see {@code SplitAtlasManager}), so
 * calling both for every atlas's upload is cheap after the first: a family
 * with no accumulated sprites this reload (e.g. no GUI-eligible sprites
 * classified) short-circuits to a completed no-op future.
 */
@Mixin(TextureAtlas.class)
@SuppressWarnings("JavadocReference")
public abstract class TextureAtlasUploadMixin {

    // Render-thread-only re-entrancy guard (see class doc). Not volatile:
    // every read and write happens on the render thread only, by the same
    // invariant that makes upload() itself render-thread-only, so there is
    // no cross-thread visibility requirement to pay for here.
    @Unique
    private static boolean tessera$applying = false;

    @Inject(method = "upload", at = @At("RETURN"))
    private void tessera$applySplitAtlases(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if (tessera$applying) {
            // Re-entered from Tessera's own owned-atlas upload() calls
            // inside applyPendingSplitStitch() below -- those are
            // TextureAtlas instances too and carry this same mixin.
            // Returning here is correct and sufficient: the outer
            // (non-re-entrant) call already fully drains every family's
            // pending result, so there is nothing left to apply on the
            // re-entrant one even if we let it through.
            return;
        }

        TextureAtlas self = (TextureAtlas) (Object) this;
        ResourceLocation atlasLocation = self.location();

        if (RulesManager.BLACKLISTED_ATLASES.contains(atlasLocation.toString())) {
            // Tessera's own split atlases (tessera:atlas/blocks_opaque,
            // tessera:atlas/blocks_alpha, tessera:atlas/hud_opaque,
            // tessera:atlas/hud_alpha) -- these ARE the re-entrant calls
            // tessera$applying guards against above; nothing to trigger or
            // apply from their own upload(). Every OTHER atlas (blocks,
            // gui, particles, and every other vanilla atlas Tessera isn't
            // blacklisted against) falls through below and is a valid
            // trigger point for every family's merged stitch.
            return;
        }

        tessera$applying = true;
        try {
            // Triggers (idempotently -- see SplitAtlasManager's own doc
            // comment) each family's combined split-stitch for this
            // reload's accumulated static-sprite pool, then blocks until
            // it completes. join() is safe here despite running on the
            // render thread: tessera$triggerMergedStitchIfNeeded's own
            // CPU-side work (classification, buffer assembly, CPU
            // compression) all runs on the background executor it was
            // handed, and by this point in the reload every prepare-phase
            // contribution has already landed (PreparationBarrier
            // guarantee) -- this join() is waiting on already-in-flight or
            // already-complete background work, not kicking off new
            // render-thread-blocking work itself.
            for (SourceAtlasFamily family : SourceAtlasFamily.values()) {
                TesseraClient.SPLIT_ATLAS_MANAGER.tessera$triggerMergedStitchIfNeeded(family).join();
                TesseraClient.SPLIT_ATLAS_MANAGER.applyPendingSplitStitch(
                        family, Minecraft.getInstance().getProfiler());
            }
        } finally {
            tessera$applying = false;
        }
    }
}