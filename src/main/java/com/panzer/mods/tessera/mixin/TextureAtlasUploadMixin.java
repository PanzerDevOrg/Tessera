package com.panzer.mods.tessera.mixin;

import com.panzer.mods.tessera.api.AtlasCompressEvent;
import com.panzer.mods.tessera.atlas.AtlasCompressionDriver;
import com.panzer.mods.tessera.cache.AtlasCache;
import com.panzer.mods.tessera.compat.TesseraCompat;
import com.panzer.mods.tessera.compress.Bc1TextureFormatSupport;
import com.panzer.mods.tessera.compress.Bc7GpuSupport;
import com.panzer.mods.tessera.compress.CompressedAnimationUploader;
import com.panzer.mods.tessera.compress.CompressionPipeline;
import com.panzer.mods.tessera.config.Config;
import com.panzer.mods.tessera.gui.DebugOverlay;
import com.panzer.mods.tessera.vram.VramBudgetEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Replaces the previous split-atlas architecture entirely: no second
 * {@code TextureAtlas}, no custom chunk geometry, no manual VAO/VBO draw
 * path. Vanilla stitches and uploads {@code TextureAtlas} exactly as it
 * always does -- every {@code BakedQuad}, every UV, every vertex layout
 * byte stays 100% vanilla. The only thing Tessera does here is, right
 * after vanilla's own {@code upload()} finishes (same texture, same atlas
 * object), re-assemble that atlas's already-placed pixels and re-upload them
 * compressed via {@code glCompressedTexImage2D} into the SAME texture --
 * respecifying its GL storage format in place.
 *
 * <p>This trades away the second BC1 format entirely (everything goes
 * through BC7, which handles both opaque and alpha content) in exchange
 * for eliminating every custom-geometry failure mode the split-atlas
 * design had: UV remapping, vertex stride/layout, sampler binding,
 * face culling, buffer lifecycle -- none of that code exists in this
 * design, because there is no second atlas and no custom draw call.
 *
 * <p>Animations keep running through {@link CompressedAnimationUploader}:
 * frames up to 1.21.10 arrive through {@code SpriteContents.upload} (see
 * {@link SpriteContentsUploadMixin}); from 1.21.11 vanilla draws them with
 * GPU passes into the atlas, which Tessera replaces for compressed atlases.
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasUploadMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/InPlaceAtlasCompressor");

    /** The texture is about to be recreated as RGBA: until a new chain is uploaded, it is not compressed. */
    @Inject(method = "upload", at = @At("HEAD"))
    private void tessera$beforeUpload(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        TextureAtlas atlas = (TextureAtlas) (Object) this;
        CompressedAnimationUploader.unregister(atlas);
        DebugOverlay.resetAtlas(atlas.location().toString());
        VramBudgetEngine.recordResident(atlas.location().toString(), -1);
        // A chain still being encoded for the previous texture must not land on this one.
        ++tessera$generation;
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void tessera$compressInPlace(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        TextureAtlas atlas = (TextureAtlas) (Object) this;
        LOGGER.info("Atlas {} stitched with {} sprites.", atlas.location(), preparations.regions().size());
        // Vanilla has already uploaded the RGBA atlas at this point; any failure
        // below must leave that upload intact instead of failing the reload.
        try {
            tessera$compressInPlaceUnchecked(preparations);
        } catch (Throwable t) {
            LOGGER.error("Tessera in-place compression failed for {} -- keeping vanilla RGBA atlas",
                    atlas.location(), t);
        }
    }

    //? >=1.21.11 {
    /*// Frames of a compressed atlas: drawn by Tessera instead of vanilla's GPU passes into it.
    @Inject(method = "uploadAnimationFrames", at = @At("HEAD"), cancellable = true)
    private void tessera$drawCompressedFrames(CallbackInfo ci) {
        if (CompressedAnimationUploader.drawFrames((TextureAtlas) (Object) this, false)) {
            ci.cancel();
        }
    }
    *///?} else {
    /** cycleAnimationFrames ticks every animated sprite; see SpriteContentsUploadMixin. */
    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"))
    private void tessera$beginAnimationCycle(CallbackInfo ci) {
        CompressedAnimationUploader.beginAnimationCycle((TextureAtlas) (Object) this);
    }

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void tessera$endAnimationCycle(CallbackInfo ci) {
        CompressedAnimationUploader.endAnimationCycle();
    }
    //?}

    @Unique
    private void tessera$compressInPlaceUnchecked(SpriteLoader.Preparations preparations) {
        if (Config.get(Config.DISABLE_NATIVE_COMPRESSION)) {
            return;
        }

        TextureAtlas self = (TextureAtlas) (Object) this;
        var atlasLocation = self.location();
        String atlasName = atlasLocation.toString();

        Collection<TextureAtlasSprite> sprites = preparations.regions().values();
        if (sprites.isEmpty()) {
            return;
        }

        // Animated sprites keep animating on the compressed atlas: frames are written
        // as BC blocks by CompressedAnimationUploader, which needs every animated
        // region (with its padding, 1.21.11+) 4x4-block aligned at each compressed
        // mip level, so the chain is capped where that stops holding (e.g. a 16px
        // sprite at level >= 3).
        int[] animatedRects = sprites.stream()
                .filter(sprite -> TesseraCompat.isAnimated(sprite.contents()))
                .flatMapToInt(sprite -> {
                    int padding = TesseraCompat.padding(sprite);
                    return java.util.stream.IntStream.of(sprite.getX(), sprite.getY(),
                            sprite.contents().width() + 2 * padding, sprite.contents().height() + 2 * padding);
                })
                .toArray();
        if (!CompressedAnimationUploader.canAnimate(animatedRects.length > 0)) {
            return;
        }
        IdentityHashMap<Object, TextureAtlasSprite> animatedSprites = TesseraCompat.animatedSprites(self);
        if (animatedSprites == null) {
            LOGGER.warn("Atlas {}: animation states do not match its animated sprites; keeping it uncompressed.",
                    atlasName);
            return;
        }

        // Default format: BC7 (keeps alpha). Listeners may cancel or pick BC1.
        AtlasCompressEvent.Pre pre = NeoForge.EVENT_BUS.post(
                new AtlasCompressEvent.Pre(atlasLocation, AtlasCache.CompressedFormat.BC7));
        if (pre.isCanceled()) {
            return;
        }
        AtlasCache.CompressedFormat format = pre.getTargetFormat();
        CompressionPipeline.Target target = format == AtlasCache.CompressedFormat.BC1
                ? CompressionPipeline.Target.BC1
                : CompressionPipeline.Target.BC7;
        boolean formatSupported = target == CompressionPipeline.Target.BC1
                ? Bc1TextureFormatSupport.isSupported()
                : Bc7GpuSupport.isSupported();
        if (!formatSupported) {
            return;
        }

        // Pixel assembly stays synchronous: it reads sprite images that vanilla may
        // close after this reload completes.
        ByteBuffer assembled = AtlasCompressionDriver.assembleAtlasBuffer(
                atlasName, sprites, preparations.width(), preparations.height());
        if (assembled == null) {
            return;
        }

        // Encoding (and mip generation) runs off the render thread; only the GL
        // upload hops back. The generation stamp drops results from a reload that
        // was superseded while compressing, so a stale chain never overwrites a newer atlas.
        int generation = tessera$generation;
        int width = preparations.width();
        int height = preparations.height();
        // Full vanilla mip chain. Levels past alignedLevel (where animated sprites
        // shrink below a 4x4 block) keep a small RGBA copy so frames can be
        // patched and re-encoded per block (~1.3 MB for a 4096^2 atlas).
        int mipLevel = preparations.mipLevel();
        int alignedLevel = CompressedAnimationUploader.alignedMipLevels(mipLevel, animatedRects);
        int retainFrom = alignedLevel < mipLevel ? alignedLevel + 1 : Integer.MAX_VALUE;
        if (alignedLevel < mipLevel) {
            LOGGER.info("Atlas {}: animated sprites patched per block at mip levels {}..{}.",
                    atlasName, alignedLevel + 1, mipLevel);
        }

        CompletableFuture
                .supplyAsync(() -> AtlasCompressionDriver.compress(
                        atlasName, target, assembled, width, height, mipLevel,
                        retainFrom),
                        TesseraCompat.backgroundExecutor())
                .whenComplete((compressed, failure) -> {
                    if (failure != null) {
                        LOGGER.error("Background compression failed for {} -- keeping vanilla RGBA atlas",
                                atlasName, failure);
                        return;
                    }
                    if (compressed == null || compressed.levels().isEmpty()) {
                        return;
                    }
                    Minecraft.getInstance().execute(() -> {
                        if (generation != tessera$generation) {
                            return; // superseded by a newer reload
                        }
                        try {
                            int glId = TesseraCompat.glId(self);
                            long resident = AtlasCompressionDriver.upload(glId, compressed);
                            if (resident >= 0) {
                                // From now on animation frames go through the BC path.
                                int uploadedLevels = Math.min(mipLevel, compressed.levels().size() - 1);
                                CompressedAnimationUploader.register(self, glId, compressed.target(), uploadedLevels,
                                        alignedLevel, compressed.retainedRgba(), animatedSprites);
                                long uncompressed = 0L;
                                for (AtlasCompressionDriver.CompressedLevel level : compressed.levels()) {
                                    uncompressed += (long) level.width() * level.height() * 4;
                                }
                                NeoForge.EVENT_BUS.post(new AtlasCompressEvent.Post(
                                        atlasLocation, format, uncompressed - resident, resident));
                            }
                        } catch (Throwable t) {
                            LOGGER.error("Compressed upload failed for {} -- keeping vanilla RGBA atlas",
                                    atlasName, t);
                        }
                    });
                });
    }

    /** Bumped on every upload; written and compared on the render thread only. */
    @Unique
    private int tessera$generation;
}
