package com.panzer.mods.tessera.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.panzer.mods.tessera.compress.CompressedAnimationUploader;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? >=1.21.10 && <1.21.11 {
/*import com.mojang.blaze3d.textures.GpuTexture;
*///?}

/**
 * Up to 1.21.10, {@code SpriteContents.upload} is the single path every
 * animation frame takes (keyframes from {@code AnimatedTexture.uploadFrame},
 * blended frames from {@code InterpolationData.uploadInterpolatedFrame}). On a
 * compressed atlas the frame is written as BC blocks instead of vanilla's RGBA
 * sub-image upload, which is invalid on a compressed texture.
 *
 * <p>From 1.21.11 frames are drawn by GPU passes instead; see
 * {@code TextureAtlasUploadMixin}. This mixin then has nothing to do.
 */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsUploadMixin {

    //? <1.21.10 {
    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void tessera$uploadCompressed(int x, int y, int frameX, int frameY, NativeImage[] atlasData,
                                          CallbackInfo ci) {
        if (CompressedAnimationUploader.upload((SpriteContents) (Object) this, x, y, frameX, frameY, atlasData)) {
            ci.cancel();
        }
    }
    //?} else if <1.21.11 {
    /*@Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void tessera$uploadCompressed(int x, int y, int frameX, int frameY, NativeImage[] atlasData,
                                          GpuTexture texture, CallbackInfo ci) {
        if (CompressedAnimationUploader.upload((SpriteContents) (Object) this, x, y, frameX, frameY, atlasData)) {
            ci.cancel();
        }
    }
    *///?}
}
