package com.panzer.mods.tessera.keybinds;

// 1.21.1 only: from 1.21.10 the breakdown is its own debug screen entry,
// toggled in vanilla's debug options like any other.
//? <1.21.10 {
import com.mojang.blaze3d.platform.InputConstants;
import com.panzer.mods.tessera.Tessera;
import com.panzer.mods.tessera.config.Config;
import com.panzer.mods.tessera.mixin.KeyboardHandlerAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

// F3+4 toggles the per-atlas breakdown in the F3 text.
@EventBusSubscriber(modid = Tessera.MOD_ID, value = Dist.CLIENT)
public final class TesseraKeyBinds {

    private TesseraKeyBinds() {
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        long windowHandle = mc.getWindow().getWindow();

        if (event.getKey() == GLFW.GLFW_KEY_4 && InputConstants.isKeyDown(windowHandle, GLFW.GLFW_KEY_F3)) {

            ((KeyboardHandlerAccessor) mc.keyboardHandler).setHandledDebugKey(true);

            boolean newState = !Config.SHOW_EXTENDED_DEBUG_BREAKDOWN.get();
            Config.SHOW_EXTENDED_DEBUG_BREAKDOWN.set(newState);
            Config.SHOW_EXTENDED_DEBUG_BREAKDOWN.save();

            Component statusComponent = newState
                    ? Component.translatable("debug.state.shown")
                    : Component.translatable("debug.state.hidden");

            Component debugMessage = Component.empty()
                    .append(Component.literal("[Tessera Debug]: ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.translatable("tessera.debug.advanced_atlas_info", statusComponent).withStyle(ChatFormatting.WHITE));

            mc.gui.getChat().addMessage(debugMessage);
        }
    }
}
//?}
