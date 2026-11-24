package me.matl114.mixins.hack;

import me.matl114.hacks.utils.ClientUtils;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.List;
import me.matl114.hacks.ChatTasks;
import me.matl114.hacks.modules.chat.ChatExtra;
import me.matl114.hacks.modules.render.SleepMode;
import me.matl114.hacks.modules.survival.XaeroHelper;
import me.matl114.hooks.XaeroHooks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
    @Shadow
    @Final
    private List<GuiMessage.Line> trimmedMessages;

    @Shadow
    @Final
    private Minecraft minecraft;

    // mixin for chatHistoryLength override
    @Inject(
            method = "addMessageToDisplayQueue",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/util/List;removeLast()Ljava/lang/Object;",
                            shift = At.Shift.BEFORE),
            cancellable = true)
    private void resizeChatHistoryMaxLength(GuiMessage message, CallbackInfo ci) {
        if (ChatExtra.INSTANCE.overrideChatHistoryLength.get()) {
            int chat = ChatTasks.getChatExtra().chatHistoryLength.get();
            if (chat > 0) {
                // 提前结束
                if (this.trimmedMessages.size() <= chat) {
                    ci.cancel();
                }
            }
        }
    }

    // mixin for chatHud usage

    @Inject(method = "isChatFocused", at = @At("HEAD"), cancellable = true)
    private void onSleepingChatScreenUseChatHud(CallbackInfoReturnable<Boolean> cir) {
        if (SleepMode.INSTANCE.isScreenSleeping()
                && SleepMode.INSTANCE.getCurrentRenderingSleeping() instanceof ChatScreen) {
            cir.setReturnValue(true);
            return;
        }
    }

    // 26.2: 第四个参数由 boolean expanded 换成了 ChatComponent$DisplayMode。
    // 原来"强制展开"的语义这里按"强制前台显示"映射为 FOREGROUND —— 若与原意不符需要再调。
    @Inject(
            method =
                    "captureClickableText(Lnet/minecraft/client/gui/ActiveTextCollector;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
            at = @At("HEAD"))
    private void onRenderChatScreen(
            ActiveTextCollector textConsumer,
            int windowHeight,
            int currentTick,
            ChatComponent.DisplayMode displayMode,
            CallbackInfo ci,
            @Local(argsOnly = true) LocalRef<ChatComponent.DisplayMode> displayModeRef) {
        if (XaeroHelper.INSTANCE.transparentGuiMapFix.get()
                && XaeroHooks.getInstance().isXaeroWorldMapEnable()
                && XaeroHooks.getInstance().isGuiMap(ClientUtils.getScreen(minecraft))) {
            // 聊天受限时原版传 FOREGROUND_RESTRICTED，无条件覆盖会让它退化成 FOREGROUND，
            // 导致"聊天受限"提示不再渲染（旧版无此概念），故受限时保持原样。
            if (displayModeRef.get() != ChatComponent.DisplayMode.FOREGROUND_RESTRICTED) {
                displayModeRef.set(ChatComponent.DisplayMode.FOREGROUND);
            }
        }
    }
}
