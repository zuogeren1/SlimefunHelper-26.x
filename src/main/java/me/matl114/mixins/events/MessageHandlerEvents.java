package me.matl114.mixins.events;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.authlib.GameProfile;
import java.time.Instant;
import java.util.Optional;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.ChatRecv;
import me.matl114.versioned.api.VRecord;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(ChatListener.class)
public abstract class MessageHandlerEvents {
    // 26.2 的 ChatListener 用 previousMessageTime 充当上游 MessageHandler.lastProcessTime 的节流计时
    @Shadow
    private long previousMessageTime;

    // 上游 MessageHandler.processChatMessageInternal 的等价注入点：
    // showMessageToPlayer 的参数里带的就是最终要显示的 decorated 组件（可取消、可改写）
    @Inject(method = "showMessageToPlayer", at = @At("HEAD"), cancellable = true)
    private void onPlayerChatMessageReceive(
            ChatType.Bound boundChatType,
            PlayerChatMessage message,
            Component decoratedMessage,
            GameProfile sender,
            boolean onlyShowSecure,
            Instant received,
            CallbackInfoReturnable<Boolean> cir,
            @Local(argsOnly = true) LocalRef<Component> decoratedRef) {
        Event<ChatRecv> recv = new Event<>(
                new ChatRecv(
                        decoratedMessage,
                        Optional.ofNullable(sender),
                        Optional.ofNullable(sender).map(VRecord::getName),
                        false,
                        Optional.of(boundChatType.chatType())),
                true,
                false);
        Listener.getChatMessageReceive().handleValue(recv);
        if (recv.isCancelled()) {
            // 与上游一致：被取消的消息按已处理刷新延迟计时，避免后续消息被卡在节流窗口里
            this.previousMessageTime = Util.getMillis();
            cir.setReturnValue(false);
            return;
        }
        decoratedRef.set(recv.context.text());
    }

    // 上游 MessageHandler.onProfilelessMessage（无 sender 的 disguised chat）
    @Inject(method = "handleDisguisedChatMessage", at = @At("HEAD"), cancellable = true)
    private void onDisguisedChatMessageReceive(
            Component message,
            ChatType.Bound boundChatType,
            CallbackInfo ci,
            @Local(argsOnly = true) LocalRef<Component> messageRef) {
        Event<ChatRecv> recv = new Event<>(
                new ChatRecv(
                        message, Optional.empty(), Optional.empty(), false, Optional.of(boundChatType.chatType())),
                true,
                false);
        Listener.getChatMessageReceive().handleValue(recv);
        if (recv.isCancelled()) {
            ci.cancel();
            return;
        }
        messageRef.set(recv.context.text());
    }
}
