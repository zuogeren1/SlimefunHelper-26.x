package me.matl114.mixins.events;

import me.matl114.events.Listener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 的「丢弃所选物品」在 MultiPlayerGameMode#dropItem 上，LocalPlayer 不再有 drop；
 * 这里只在 26.3 挂等价锚点，26.2 / 26.1.2 仍由 ClientPlayerEntityEvents 的 LocalPlayer#drop 负责。
 */
@Environment(EnvType.CLIENT)
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeEvents {
    //#if MC >= 260300
    //$$ @Inject(method = "dropItem(Lnet/minecraft/client/player/LocalPlayer;Z)V", at = @At("HEAD"), cancellable = true)
    //$$ private void onDropSelected(LocalPlayer player, boolean entireStack, CallbackInfo ci) {
    //$$     if (Minecraft.getInstance().player == player && !Listener.getPlayerDropSelectedItem().fireEvent(entireStack)) {
    //$$         ci.cancel();
    //$$     }
    //$$ }
    //#endif
}
