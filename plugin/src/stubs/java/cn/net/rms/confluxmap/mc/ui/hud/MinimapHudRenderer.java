package cn.net.rms.confluxmap.mc.ui.hud;

/**
 * conflux-map 的 HUD 小地图渲染器（编译期桩，不参与打包）。
 *
 * <p>真实 jar 中为 {@code public final class}，在 {@code ConfluxMapClient} 里 new 出来之后由
 * {@code HudElementRegistry.addLast(Ids.of("confluxmap", "minimap"), this::render)} 注册成 HUD 元素，
 * 所以它的 {@code render} 只在真实 HUD 渲染时被调用（没有预览 / mock 路径）。
 *
 * <p>0.1.16 起它只做「算几何 + 按顺序调 painter」，自己不再画瓦片 / 雷达 / 标记：
 * <pre>
 *   private void render(net.minecraft.client.gui.GuiGraphicsExtractor, net.minecraft.client.DeltaTracker)
 *   private static final float[] BLOCKS_PER_PIXEL;   // {0.5F, 1.0F, 2.0F, 4.0F}
 *   private final cn.net.rms.confluxmap.core.config.ConfluxConfig config;
 * </pre>
 * {@code BLOCKS_PER_PIXEL} 在 {@code render} 里只读一次（{@code BLOCKS_PER_PIXEL[config.minimapZoomIndex]}），
 * 结果作为局部变量传给各个 painter —— 所以缩放覆盖（{@code ConfluxMinimapHudMixin} 的
 * {@code @ModifyExpressionValue}）只需要改这一处 {@code getstatic}。语法选择器里点名
 * {@code BLOCKS_PER_PIXEL} 的字符串不需要编译期符号，这里保留字段声明只是为了给那段注释一个准确的出处。
 */
public abstract class MinimapHudRenderer {
    private static float[] BLOCKS_PER_PIXEL;
}
