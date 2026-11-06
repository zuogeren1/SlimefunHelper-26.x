package me.matl114.mixins.events;

import me.matl114.managers.input.SimpleInputManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(value = KeyboardHandler.class, priority = 1)
public abstract class KeyBoardEvents {
    @Inject(
            method = "keyPress",
            cancellable = true,
            at =
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/KeyboardHandler;debugCrashKeyTime:J",
                            ordinal = 0))
    private void onKeyboardInput(long window, int action, KeyEvent input, CallbackInfo ci) {
        //#if MC >= 260300
        //$$ if (SimpleInputManager.getInstance().onKeyInput(input.key(), input.keycode(), input.modifiers(), action)) {
        //#else
        if (SimpleInputManager.getInstance().onKeyInput(input.key(), input.scancode(), input.modifiers(), action)) {
        //#endif
            ci.cancel();
        }
    }

    @Inject(
            method = "charTyped",
            cancellable = true,
            at =
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/KeyboardHandler;minecraft:Lnet/minecraft/client/Minecraft;",
                            ordinal = 0))
    private void onChar(long window, CharacterEvent input, CallbackInfo ci) {
        if (SimpleInputManager.getInstance().onCharTyped(input.codepoint(), 0)) {
            ci.cancel();
        }
    }
}
