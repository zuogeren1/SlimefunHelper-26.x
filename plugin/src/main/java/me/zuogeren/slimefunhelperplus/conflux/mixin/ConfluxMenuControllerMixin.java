package me.zuogeren.slimefunhelperplus.conflux.mixin;

import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import me.zuogeren.slimefunhelperplus.conflux.ConfluxMapHooks;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMapOverlayRenderer;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxStateAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * conflux 0.1.16 起，右键位置菜单的绘制与状态搬进了 {@code MapLocationMenuController}
 * （包级私有的 {@code drawLocationMenu(GuiDraw)}，由屏幕的 {@code renderContents} 最后一句调用）。
 *
 * <p>我们的全屏覆盖层（已加载区块边界 + 旅行目标标记）就挂在这个方法的 HEAD：
 * 那一刻地图内容已经画完、他们的面板 / 按钮 / 热键提示还没画，我们的线落在地图上、
 * 一定被他们的面板与控件盖住。
 *
 * <p><b>为什么用 {@code targets} 字符串 + {@code @Pseudo}</b>：目标类是包级私有的，
 * 源码里连类型名都写不出来；{@code @Pseudo} 保证「这个类不存在时」静默跳过而不是让 mixin 配置加载失败
 * （更早的 conflux 版本把同一个方法放在屏幕上，见 {@code ConfluxMapScreenMixin} 的注释）。
 *
 * <p>{@code require = 0}：结构变了就只是这条注入不生效，绝不让 apply 阶段失败把游戏带崩。
 */
@Pseudo
@Environment(EnvType.CLIENT)
@Mixin(targets = "cn.net.rms.confluxmap.mc.ui.screen.MapLocationMenuController")
public abstract class ConfluxMenuControllerMixin {
    @Inject(method = "drawLocationMenu", at = @At("HEAD"), require = 0)
    private void slimefunhelper$drawMapOverlay(GuiDraw draw, CallbackInfo ci) {
        try {
            if (!ConfluxMapHooks.getInstance().isEnabled()) {
                return;
            }
            ConfluxMapOverlayRenderer.drawFullscreenOverlay(ConfluxStateAccess.screenOf(this), draw);
        } catch (Throwable ignored) {
            // 覆盖层自己已经 try/catch 过；这里只防「拿不到模块实例」这类极端情况
        }
    }
}
