package com.nerdsoft.mods.tessera.mixin;

import com.nerdsoft.mods.tessera.TesseraClient;
import com.nerdsoft.mods.tessera.atlas.AtlasSplitTarget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * GUI-side counterpart to {@code ModelWrapper}/{@code SectionGeometryHandler}
 * for blocks: rebinds a GUI sprite blit onto Tessera's {@code HUD_OPAQUE}/
 * {@code HUD_ALPHA} atlas whenever the sprite being blitted was routed
 * there, instead of vanilla's own {@code minecraft:textures/atlas/gui}
 * texture.
 *
 * <h2>Why this is safe as a parameter substitution</h2>
 * Every {@code GuiGraphics.blitSprite(ResourceLocation, ...)} overload
 * eventually funnels into one of the two private
 * {@code blitSprite(TextureAtlasSprite, ...)} overloads targeted here, both
 * of which compute their blit geometry entirely from the
 * {@code TextureAtlasSprite} parameter itself -- {@code atlasLocation()}
 * (which texture to bind), and {@code getU0()}/{@code getU1()}/
 * {@code getV0()}/{@code getV1()} or {@code getU(float)}/{@code getV(float)}
 * (which region of that texture). Substituting the parameter for the
 * equivalent {@code TextureAtlasSprite} Tessera's own atlas produced for
 * the same sprite name makes that existing math correct for the new atlas
 * with no duplicated UV/geometry logic: both sprites share the same
 * underlying {@code SpriteContents} (Tessera restitches the same pixel
 * data, just at a different atlas position), so any caller-supplied
 * sub-region fractions (e.g. the 9-int overload's {@code getU(x / width)})
 * remain valid against the substitute.
 *
 * <p>Only fires for sprites Tessera actually routed to {@link AtlasSplitTarget#HUD_OPAQUE}/
 * {@link AtlasSplitTarget#HUD_ALPHA} this reload (see
 * {@link com.nerdsoft.mods.tessera.atlas.SplitAtlasManager#routingFor}) --
 * every other GUI sprite (dynamic/animated sprites, or any reload where the
 * split failed/was skipped) passes through with the original vanilla
 * sprite untouched.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiBlitSpriteMixin {

    @ModifyVariable(
            method = "blitSprite(Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;IIIIIIIII)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private TextureAtlasSprite tessera$rebind9Arg(TextureAtlasSprite sprite) {
        return tessera$rebind(sprite);
    }

    @ModifyVariable(
            method = "blitSprite(Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;IIIII)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private TextureAtlasSprite tessera$rebind5Arg(TextureAtlasSprite sprite) {
        return tessera$rebind(sprite);
    }

    private static TextureAtlasSprite tessera$rebind(TextureAtlasSprite original) {
        ResourceLocation spriteName = original.contents().name();
        AtlasSplitTarget target = TesseraClient.SPLIT_ATLAS_MANAGER.routingFor(spriteName);
        if (target != AtlasSplitTarget.HUD_OPAQUE && target != AtlasSplitTarget.HUD_ALPHA) {
            return original;
        }
        if (!TesseraClient.SPLIT_ATLAS_MANAGER.hasContent(target)) {
            // Atlas exists this reload but never had upload() called on it
            // (zero sprites routed here) -- see SplitAtlasManager's own
            // hasContent doc. Falling back to the original vanilla sprite
            // is always safe; it just forgoes the VRAM saving for this
            // blit rather than sampling an atlas with no defined storage.
            return original;
        }

        TextureAtlas atlas = TesseraClient.SPLIT_ATLAS_MANAGER.atlasFor(target);
        TextureAtlasSprite rebound = atlas.getSprite(spriteName);
        return rebound != null ? rebound : original;
    }
}
