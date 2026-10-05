package com.panzer.mods.tessera.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;

//? <1.21.10 {
import org.spongepowered.asm.mixin.gen.Accessor;
//?}

/** 1.21.1: F3+4 marks the debug key as handled (later versions toggle F3 entries in the debug options). */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {

    //? <1.21.10 {
    @Accessor("handledDebugKey")
    void setHandledDebugKey(boolean handled);
    //?}
}
