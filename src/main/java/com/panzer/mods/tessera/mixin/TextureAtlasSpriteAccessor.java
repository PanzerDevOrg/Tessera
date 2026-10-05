package com.panzer.mods.tessera.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;

//? >=1.21.11 {
/*import org.spongepowered.asm.mixin.gen.Accessor;
*///?}

/** 1.21.11+: the padding ring around a sprite in the atlas (empty before). */
@Mixin(TextureAtlasSprite.class)
public interface TextureAtlasSpriteAccessor {

    //? >=1.21.11 {
    /*@Accessor("padding")
    int tessera$padding();
    *///?}
}
