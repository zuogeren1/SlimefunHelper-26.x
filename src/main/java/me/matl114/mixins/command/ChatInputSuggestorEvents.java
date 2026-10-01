package me.matl114.mixins.command;

import com.mojang.brigadier.suggestion.Suggestions;
import java.util.concurrent.CompletableFuture;
import me.matl114.managers.command.MainCommand;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(CommandSuggestions.class)
public abstract class ChatInputSuggestorEvents {
    @Shadow
    @Final
    EditBox input;

    @Shadow
    private CompletableFuture<Suggestions> pendingSuggestions;

    @Shadow
    public abstract void showSuggestions(boolean a);

    @Shadow
    private boolean keepSuggestions;

    @Inject(
            method = "updateCommandInfo",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/components/EditBox;getCursorPosition()I",
                            shift = At.Shift.BEFORE),
            cancellable = true)
    private void parseClientCommandsTabComplete(CallbackInfo ci) {
        if (MainCommand.isClientCommand(input.getValue())) {
            if (!this.keepSuggestions) {
                CompletableFuture<Suggestions> suggestionCompletableFuture =
                        MainCommand.tabCompleteClientCommand(input.getValue(), input.getCursorPosition());
                if (suggestionCompletableFuture != null) {
                    this.pendingSuggestions = suggestionCompletableFuture;
                    this.pendingSuggestions.thenRun(() -> {
                        if (this.pendingSuggestions.isDone()) {
                            showSuggestions(true);
                        }
                    });
                }
            }
            ci.cancel();
        }
    }
}
