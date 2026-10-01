package com.panzer.mods.tessera.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.panzer.mods.tessera.compress.CompressedAnimationUploader;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code SpriteContents.upload} is the single path every animation frame takes
 * (keyframes from {@code AnimatedTexture.uploadFrame}, blended frames from
 * {@code InterpolationData.uploadInterpolatedFrame}). On a compressed atlas the
 * frame is written as BC blocks instead of vanilla's RGBA sub-image upload,
 * which is invalid on a compressed texture.
 */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsUploadMixin {

    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void tessera$uploadCompressed(int x, int y, int frameX, int frameY, NativeImage[] atlasData,
                                          CallbackInfo ci) {
        if (CompressedAnimationUploader.upload((SpriteContents) (Object) this, x, y, frameX, frameY, atlasData)) {
            ci.cancel();
        }
    }
}
