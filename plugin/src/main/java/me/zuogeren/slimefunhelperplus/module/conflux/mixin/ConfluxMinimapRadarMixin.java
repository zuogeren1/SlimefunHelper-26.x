package me.zuogeren.slimefunhelperplus.module.conflux.mixin;

import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import me.zuogeren.slimefunhelperplus.module.conflux.ConfluxMapHooks;
import me.zuogeren.slimefunhelperplus.module.conflux.hooks.ConfluxMapOverlayRenderer;
import me.zuogeren.slimefunhelperplus.module.conflux.hooks.ConfluxStateAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把全屏地图上那两层（已加载区块边界 + 旅行目标标记）同样画到 conflux 的 <b>HUD 小地图</b>上。
 *
 * <h2>为什么注入 {@code MinimapRadarPainter#drawRadar} 的 HEAD</h2>
 * conflux 0.1.16 把小地图的绘制拆成了几个 painter，{@code MinimapHudRenderer#render} 每帧按
 * 「瓦片 → 传送门区块高亮 → 玩家轨迹 → 标注 → 边框 → <b>drawRadar</b> → 方位字母 → 路径点 / 自定义标记
 * → 相机标记 → 本地玩家箭头 → 信息文字」的顺序调用它们。HEAD 上正好是
 * 「地图内容已画完、他们的所有标记还没画」：我们的线落在地图上，雷达点 / 方位字母 / 路径点 /
 * 玩家箭头一定盖在我们上面。
 *
 * <p>{@code drawRadar} 每帧只由 {@code render} 调一次，而 {@code render} 是注册给 Fabric
 * {@code HudElementRegistry} 的回调 —— 能进到这里就说明小地图真的在显示
 * （{@code MinimapHudVisibility.shouldRender} 已通过：开关打开、会话激活、没开全屏地图、
 * 没开容器界面、没按 F3），不需要我们再判断一遍。
 *
 * <h2>参数就是几何</h2>
 * {@code centerX / centerY} 是内容区中心的屏幕坐标，{@code size} 是内容区边长，
 * {@code mapAngle} 是旋转角（正北朝上时为 0），{@code blocksPerPixel} 是<b>本帧生效</b>的缩放
 * （conflux 自己的档位，或 {@link ConfluxMinimapHudMixin} 覆盖之后的值 —— 两边必须用同一个，
 * 否则我们的线会与他们缩放后的瓦片错位），{@code player} 是视口中心的世界坐标。
 *
 * <p><b>为什么用 {@code targets} 字符串 + {@code @Pseudo}</b>：这个 painter 是包级私有的，
 * 源码里写不出类型名；{@code @Pseudo} 保证它不存在时静默跳过。{@code require = 0} 让结构变化
 * 只表现为「这一层不画」，由 {@code ConfluxMapHooks} 的自检在启动日志里点出原因。
 */
@Pseudo
@Environment(EnvType.CLIENT)
@Mixin(targets = "cn.net.rms.confluxmap.mc.ui.hud.MinimapRadarPainter")
public abstract class ConfluxMinimapRadarMixin {
    @Inject(method = "drawRadar", at = @At("HEAD"), require = 0)
    private void slimefunhelper$drawMinimapOverlay(
            GuiDraw draw,
            float centerX,
            float centerY,
            int size,
            float mapAngle,
            PlayerView player,
            float tickDelta,
            float blocksPerPixel,
            float fadeAlpha,
            CallbackInfo ci) {
        try {
            if (!ConfluxMapHooks.getInstance().isEnabled()) {
                return;
            }
            ConfluxMapOverlayRenderer.drawMinimapOverlay(
                    draw,
                    player,
                    centerX,
                    centerY,
                    size,
                    mapAngle,
                    blocksPerPixel,
                    ConfluxStateAccess.circularMinimap(this));
        } catch (Throwable ignored) {
            // 覆盖层自己已经 try/catch 过
        }
    }
}
