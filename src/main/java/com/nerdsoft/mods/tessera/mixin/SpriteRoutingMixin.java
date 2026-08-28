package com.nerdsoft.mods.tessera.mixin;

import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.atlas.SourceAtlasFamily;
import com.nerdsoft.mods.tessera.atlas.SplitAtlasManager;
import com.nerdsoft.mods.tessera.config.Config;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(SpriteLoader.class)
public abstract class SpriteRoutingMixin {

    @Unique
    private static final Logger tessera$LOGGER = LoggerFactory.getLogger("Tessera/SpriteRoutingMixin");

    // No TextureAtlas.LOCATION_GUI constant exists (unlike LOCATION_BLOCKS/
    // LOCATION_PARTICLES) -- GuiSpriteManager builds this ResourceLocation
    // inline itself rather than exposing a shared constant.
    @Unique
    private static final ResourceLocation tessera$LOCATION_GUI = ResourceLocation.withDefaultNamespace("textures/atlas/gui.png");

    @Shadow
    @Final
    private ResourceLocation location;

    @Inject(method = "stitch", at = @At("HEAD"))
    private void tessera$captureStitchArgs(List<SpriteContents> allSprites, int maxMipLevel, Executor executor, CallbackInfoReturnable<CompletableFuture<SpriteLoader.Preparations>> cir) {
        if (SplitAtlasManager.isDispatching()) {
            return;
        }
        SourceAtlasFamily family = tessera$familyFor(this.location);
        if (family == null) {
            return;
        }

        boolean freezeAnimations = Config.DISABLE_ANIMATIONS_ATLASES.get().contains(this.location.toString());
        List<SpriteContents> staticSprites = allSprites.stream()
                .filter(contents -> contents.createTicker() == null || freezeAnimations)
                .toList();

        if (staticSprites.isEmpty()) {
            return;
        }

        TesseraClient.SPLIT_ATLAS_MANAGER.accumulateStaticSprites(family, staticSprites, executor);

        tessera$LOGGER.info("Atlas {}: {} static sprites queued for Tessera's combined split-atlas stitch; vanilla's own atlas is untouched.",
                this.location, staticSprites.size());
    }

    /**
     * Which {@link SourceAtlasFamily} pool {@code atlasLocation}'s static
     * sprites should accumulate into, or {@code null} if this source atlas
     * isn't split at all. Each family gets its own independent opaque/alpha
     * pair (see {@link SourceAtlasFamily}) rather than sharing one, since a
     * block-atlas sprite and a GUI-atlas sprite must never end up bin-packed
     * into the same physical texture their respective render-side consumers
     * (chunk section geometry vs. {@code GuiGraphics} sprite blits) bind
     * independently of each other.
     */
    @Unique
    private SourceAtlasFamily tessera$familyFor(ResourceLocation atlasLocation) {
        if (Config.DISABLE_NATIVE_COMPRESSION.get()) {
            return null;
        }
        if (atlasLocation.equals(TextureAtlas.LOCATION_BLOCKS)) {
            return SourceAtlasFamily.BLOCKS;
        }
        if (atlasLocation.equals(tessera$LOCATION_GUI)) {
            return SourceAtlasFamily.GUI;
        }
        return null;
    }
}