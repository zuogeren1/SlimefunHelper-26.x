package me.zuogeren.slimefunhelperplus.mixin;

import me.matl114.hacks.modules.task.ClickGui;
import me.matl114.utils.ClientUtils;
import me.zuogeren.slimefunhelperplus.module.newclickgui.NewClickGui;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 多人游戏界面右上角那个 SlimefunHelper 按钮走的是宿主 {@code ClickGui#openClickGui}。
 *
 * <p>本界面的「接管多人游戏界面的 ClickGui」打开时，把这一次打开换成新界面；
 * 判据只用"当前屏是多人游戏界面"，所以游戏内的热键、别的入口都还是宿主的界面。
 */
@Mixin(ClickGui.class)
public class ClickGuiTakeoverMixin {

    @Inject(method = "openClickGui", at = @At("HEAD"), cancellable = true, require = 0)
    private void sfhplus$takeoverMultiplayer(CallbackInfo callbackInfo) {
        if (!NewClickGui.takeoverMultiplayer()) {
            return;
        }
        if (ClientUtils.getScreen() instanceof JoinMultiplayerScreen) {
            callbackInfo.cancel();
            NewClickGui.openScreen();
        }
    }
}
