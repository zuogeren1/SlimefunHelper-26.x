package me.matl114.mixins.hack;

import me.matl114.hacks.utils.ClientUtils;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Objects;
import me.matl114.accessors.access.ChatScreenAccess;
import me.matl114.accessors.gui.CustomFocusBehaviourScreenAccess;
import me.matl114.hacks.ChatTasks;
import me.matl114.hacks.utils.chat.ChatScreenTextFieldWidget;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;

@Environment(EnvType.CLIENT)
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen implements CustomFocusBehaviourScreenAccess, ChatScreenAccess {
    @Shadow
    public abstract void handleChatInput(String chatText, boolean addToHistory);

    @Shadow
    protected EditBox input;

    @Shadow
    private int historyPos;

    @Shadow
    protected String initial;

    @Unique
    public void resetMessageHistoryIndex() {
        historyPos = ClientUtils.getChat().getRecentChat().size();
    }

    @Accessor("commandSuggestions")
    public abstract CommandSuggestions getSuggestor();

    public EditBox getInputWidget() {
        return input;
    }

    protected ChatScreenMixin(Component title) {
        super(title);
    }

    // 关于选择Element这件事
    // 在mouseClick中选择

    // 防止选中原输出框时候不进行setFocus
    // already fixed by ojng

    //    private boolean fixMouseClickedOnChatFocusLost(ChatInputSuggestor instance, Click click, Operation<Boolean>
    // original) {
    //        boolean returnValue = original.call(instance, click);
    //        if(returnValue){
    //            this.setFocused(instance);
    //        }
    //        return returnValue;
    //    }
    // interface
    @WrapOperation(
            method = "onEdited",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/components/CommandSuggestions;setAllowSuggestions(Z)V"))
    private void fixChatInputSuggestor(
            CommandSuggestions instance,
            boolean windowActive,
            Operation<Void> original,
            @Local(argsOnly = true) String chatText) {
        if (ChatTasks.getChatExtra().tabFix.get()) {
            original.call(instance, true);
        } else {
            original.call(instance, !Objects.equals(chatText, this.initial));
        }
    }

    @Unique
    public GuiEventListener getDefaultElement() {
        return this.input;
    }

    @Unique
    public boolean canFocusButtonWhenClicked() {
        return false;
    }
    // resize

    // warn: do not cancel normalize, conflict with other mods
    @WrapOperation(
            method = "normalizeChatMessage",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;trim()Ljava/lang/String;"))
    private String cancelTrim(String instance, Operation<String> original) {
        if (!ChatTasks.getChatExtra().escapeChatTrim.get()) {
            return original.call(instance);
        }
        return instance;
    }

    @WrapOperation(
            method = "normalizeChatMessage",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lorg/apache/commons/lang3/StringUtils;normalizeSpace(Ljava/lang/String;)Ljava/lang/String;",
                            remap = false))
    private String cancelNormalize(String actualChar, Operation<String> original) {
        if (!ChatTasks.getChatExtra().escapeNormalize.get()) {
            return original.call(actualChar);
        }
        return actualChar;
    }

    @WrapOperation(
            method = "normalizeChatMessage",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/util/StringUtil;trimChatMessage(Ljava/lang/String;)Ljava/lang/String;"))
    private String cancelTruncate(String text, Operation<String> original) {
        if (!ChatTasks.getChatExtra().noChathudInputLimit.get()) {
            return original.call(text);
        }
        return text;
    }

    @WrapOperation(
            method = "init",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/client/gui/screens/ChatScreen;input:Lnet/minecraft/client/gui/components/EditBox;",
                            ordinal = 0))
    private void modifyTextFieldWidget(ChatScreen instance, EditBox value, Operation<Void> original) {
        original.call(instance, new ChatScreenTextFieldWidget((ChatScreen) (Screen) this));
    }
}
