package me.zuogeren.slimefunhelperplus.conflux.mixin;

import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapLocationMenu;
import java.util.List;
import java.util.UUID;
import me.zuogeren.slimefunhelperplus.conflux.ConfluxMapHooks;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMapOverlayRenderer;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMenuRowHost;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxStateAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * conflux 0.1.16 起，右键位置菜单的绘制、状态与按钮都在 {@code MapLocationMenuController}
 * （包级私有）里，屏幕 {@code FullscreenMapScreen} 只保留转发。
 *
 * <p>本 mixin 挂两处：
 * <ul>
 *   <li>{@code drawLocationMenu} 的 HEAD —— 画我们的全屏覆盖层（已加载区块边界 + 旅行目标标记）。
 *       那一刻地图内容已经画完、他们的面板 / 按钮 / 热键提示还没画，我们的线落在地图上、
 *       一定被他们的面板与控件盖住；</li>
 *   <li>{@code locationMenuButtonSpecs} 的 RETURN —— 把我们的条目追加进<b>他们随后逐条造按钮的那个列表</b>。
 *       0.1.16 的 {@code addLocationMenuButtons()} 读的是控制器自己的这个方法，屏幕上的同名方法只是转发；
 *       挂在屏幕上会「列表里有、按钮没有」，表现就是点得到、看不见。追加逻辑本身在屏幕的 mixin 里
 *       （要读包级私有的 {@code Bounds} 算标签宽度），这里通过 {@link ConfluxMenuRowHost} 回调过去。</li>
 * </ul>
 *
 * <p><b>为什么用 {@code targets} 字符串 + {@code @Pseudo}</b>：目标类是包级私有的，
 * 源码里连类型名都写不出来；{@code @Pseudo} 保证「这个类不存在时」静默跳过而不是让 mixin 配置加载失败。
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

    @SuppressWarnings("rawtypes")
    @Inject(method = "locationMenuButtonSpecs", at = @At("RETURN"), require = 0)
    private void slimefunhelper$appendMenuRows(
            FullscreenMapLocationMenu.Target target,
            WaypointRenderEntry waypoint,
            UUID playerId,
            boolean deletePending,
            CallbackInfoReturnable<List> cir) {
        try {
            if (!ConfluxMapHooks.getInstance().isEnabled() || cir == null) {
                return;
            }
            List specs = cir.getReturnValue();
            if (specs == null || specs.isEmpty()) {
                return;
            }
            if (ConfluxStateAccess.screenOf(this) instanceof ConfluxMenuRowHost host) {
                // 条目数与追加都在屏幕那一侧完成，这里只做转发
                host.slimefunhelper$appendMenuRows(specs, specs.size());
            }
        } catch (Throwable ignored) {
            // 追加失败最多少几行，不影响他们的菜单
        }
    }
}
