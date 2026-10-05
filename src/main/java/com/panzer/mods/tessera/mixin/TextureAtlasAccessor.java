package com.panzer.mods.tessera.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;

//? >=1.21.11 {
/*import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
*///?}

/** 1.21.11+: the atlas's sprites and their animation states (empty before). */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {

    //? >=1.21.11 {
    /*@Accessor("sprites")
    List<TextureAtlasSprite> tessera$sprites();

    @Accessor("animatedTexturesStates")
    List<SpriteContents.AnimationState> tessera$animationStates();
    *///?}
}
