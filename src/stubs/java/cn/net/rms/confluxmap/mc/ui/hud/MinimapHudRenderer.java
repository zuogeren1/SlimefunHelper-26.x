package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;

/**
 * conflux-map 的 HUD 小地图渲染器（编译期桩，不参与打包）。
 *
 * <p>真实 jar 中为 {@code public final class}，在 {@code ConfluxMapClient} 里 new 出来之后由
 * {@code HudElementRegistry.addLast(Ids.of("confluxmap", "minimap"), this::render)} 注册成 HUD 元素，
 * 所以它的 {@code render} 只在真实 HUD 渲染时被调用（没有预览 / mock 路径）。
 *
 * <p>本桩只声明我们真正伸手去够的那两个成员，签名以 javap 实测为准
 * （0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2 三份 jar 逐字一致）：
 * <pre>
 *   private void drawRadar(cn.net.rms.confluxmap.mc.ui.GuiDraw, float, float, int, float,
 *                          cn.net.rms.confluxmap.bridge.PlayerView, float);
 *   private static final float[] BLOCKS_PER_PIXEL;   // {0.5F, 1.0F, 2.0F, 4.0F}
 *   private final cn.net.rms.confluxmap.core.config.ConfluxConfig config;
 * </pre>
 *
 * <p>{@code drawRadar} 是 {@code render} 里<b>唯一</b>的调用点（反编译源码 278 行），
 * 参数就是内容区中心的屏幕坐标 / 边长 / 旋转角 / 玩家视图；{@code config} 与
 * {@code BLOCKS_PER_PIXEL} 在我们的 mixin 里是<b>反射</b>读的（不写 @Shadow，见该 mixin 的注释），
 * 所以这里只把 {@code BLOCKS_PER_PIXEL} 声明出来当作签名记录，{@code config} 的类型
 * （{@code core.config.ConfluxConfig}）在桩里不必再拉一个文件进来。
 *
 * <p><b>注意</b>：小地图缩放自定义（{@code ConfluxMinimapHudMixin#slimefunhelper$overrideBlocksPerPixel}）
 * 还会按<b>名字 + 描述符</b>去挂 {@code @ModifyExpressionValue}，涉及 {@code render} /
 * {@code localPlayerMarker} / {@code drawPlayerTrail} / {@code annotationProjection} /
 * {@code drawWaypointMarkers} / {@code drawPortalChunkHighlights} / {@code drawPortalMarkers} /
 * {@code drawCustomMarkers} / {@code drawTiles} / {@code drawRadar} 这 10 个方法
 * （每个方法体里都有一处 {@code BLOCKS_PER_PIXEL[config.minimapZoomIndex]}）。
 * 那些名字只出现在混入的<b>字符串选择器</b>里、不需要编译期符号，所以这里<b>不</b>声明它们：
 * 一旦在这里写出宽松/近似的签名，反而会诱导出描述符对不上的选择器 —— 完整的 10 条描述符
 * 记在该 mixin 的 {@code method} 列表里（逐条 javap -s 核过）。
 */
public abstract class MinimapHudRenderer {
    private static float[] BLOCKS_PER_PIXEL;

    private void drawRadar(
            GuiDraw draw, float centerX, float centerY, int size, float mapAngle, PlayerView player, float tickDelta) {}
}
