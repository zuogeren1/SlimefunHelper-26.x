package me.matl114.hooks.impl.confluxmap;

import me.matl114.utils.LoadedChunkEdgeCache;

/**
 * ConfluxMapHelper 在 conflux-map 全屏地图上那两层的<b>几何与绘制</b>：
 * 已加载区块的边界线（{@link #renderChunkEdges}）与旅行目标标记（{@link #renderTravelMarker}）。
 *
 * <p>这个类里<b>不出现任何 conflux-map 的类型</b>：它只吃几何（中心 / 缩放 / 视口）与两个回调接口
 * （{@link RectFiller} 对齐 {@code GuiDraw#fill(int,int,int,int,int)}，
 * {@link TextDrawer} 对齐 {@code GuiDraw#drawTextWithShadow(Font,String,float,float,int)}），
 * 所以它可以留在普通包里，不需要跟 mixin 一起被合并进目标类。
 *
 * <h2>投影（来自 conflux 0.1.9 反编译源码，0.1.7 逐条一致）</h2>
 * 全屏地图是<b>正北朝上</b>的正交投影，地图区域就是整个屏幕，投影式在他们自己的代码里到处都是：
 * <pre>
 *   FullscreenMapScreen#drawExportSelection      : screenX = width  / 2.0 + (worldX - centerX) / scale
 *   FullscreenMapScreen#drawRegionSelection      : screenY = height / 2.0 + (worldZ - centerZ) / scale
 *   FullscreenMapScreen#drawChunkGrid            : pxPerBlock = 1.0 / scale（16.0 / scale = 一个区块的像素宽）
 *   FullscreenMapScreen#captureLocationMenu      : 逆变换 worldX = centerX + (mouseX - width / 2.0) * scale
 * </pre>
 * 全屏这条路径上<b>没有</b> {@code SplitMapLayout}（那是嵌入 / 分屏地图用的）：
 * {@code openLocationMenu} 传给 {@code captureLocationMenu} 的就是 {@code width / 2.0, height / 2.0}。
 * 地图背景 / 网格 / 瓦片都由他们从 {@code (0, 0)} 铺到 {@code (width, height)}，所以我们的视口就是整屏。
 *
 * <p>唯一的“别画到他们界面上”的处理是上下两条安全带（见 {@link #viewport}）：
 * 我们注入在 {@code drawLocationMenu} 的 HEAD（地图内容之后、他们的面板与所有原版控件之前），
 * 所以他们自己的按钮 / 面板一定盖在我们上面；需要主动避开的只有<b>先于我们绘制</b>的那几行常驻文字
 * （左上角维度 / 层 / 预测，右上角缩放，底部光标坐标与更新徽标）。
 */
public final class ConfluxMapOverlay {
    /** 画一块矩形，参数与 {@code cn.net.rms.confluxmap.mc.ui.GuiDraw#fill(int,int,int,int,int)} 对齐 */
    public interface RectFiller {
        void fill(int x1, int y1, int x2, int y2, int color);
    }

    /** 画一段文字，参数与 {@code GuiDraw#drawTextWithShadow(Font,String,float,float,int)} 对齐 */
    public interface TextDrawer {
        void draw(String text, float x, float y, int color);
    }

    /** conflux 自己的外边距常量（{@code FullscreenMapScreen.MARGIN}） */
    public static final int MARGIN = 6;
    /** 左上角最多叠几行常驻标签（维度 / 层 / 预测） */
    public static final int CHROME_ROWS = 3;
    /** 底部文字的基线偏移（他们写死的 {@code height - MARGIN - 10}） */
    public static final int CHROME_BOTTOM_GAP = 10;
    /** 安全带之外至少还要留这么多像素，否则整个视口判为不可用 */
    public static final int MIN_MAP_HEIGHT = 16;
    /** 缺字体时的兜底行高（原版 Font 是 9） */
    public static final int FALLBACK_LINE_HEIGHT = 9;

    /**
     * 一个区块在屏幕上的宽度下限（px）：{@code 16.0 / scale} 小于它就整层不画。
     *
     * <p>这就是 conflux 自己 {@code drawChunkGrid} 的护栏（他们用 {@code MIN_CHUNK_GRID_SPACING_PX = 6.0}
     * 挡掉“缩小到世界级”时的摩尔纹）；我们画的是实色线，取 2px 已经很密了。
     * 缩到最远（{@code scale = 16}）时一个区块只有 1px，此时画的不是边界而是一片红。
     */
    public static final double MIN_CHUNK_PIXELS = 2.0;

    /** 缓存里线段数的硬上限：超过就整层不画（防御病态数据，正常边界边只有几百条） */
    public static final int MAX_CACHED_SEGMENTS = 20000;

    /** 旅行目标标记的颜色与文本（对齐 XaeroHelper 的 {@code [SFH] Travel} + 绿色） */
    public static final int TRAVEL_MARKER_COLOR = 0xFF55FF55;
    public static final String TRAVEL_MARKER_LABEL = "[SFH] Travel";
    /** 标记方框的半边长（px）：整体 7x7 */
    public static final int TRAVEL_MARKER_HALF = 3;

    /**
     * 一帧的投影 + 可画区域。
     *
     * @param centerX    视口中心的世界 X（conflux 的 {@code centerX} 字段）
     * @param centerZ    视口中心的世界 Z（conflux 的 {@code centerZ} 字段）
     * @param scale      每像素多少格（conflux 的 {@code scale} 字段，越大越“缩得远”）
     * @param width      屏幕宽（GUI 缩放后的像素）
     * @param height     屏幕高
     * @param top        可画区的上边界（含）
     * @param bottom     可画区的下边界（不含）
     * @param fontHeight 字体行高（用来算顶部安全带与标记文字的位置）
     */
    public record Viewport(
            double centerX,
            double centerZ,
            double scale,
            int width,
            int height,
            int top,
            int bottom,
            int fontHeight) {
        /** 世界 X -> 屏幕 X */
        public double screenX(double blockX) {
            return width / 2.0 + (blockX - centerX) / scale;
        }

        /** 世界 Z -> 屏幕 Y */
        public double screenZ(double blockZ) {
            return height / 2.0 + (blockZ - centerZ) / scale;
        }

        /** 一个区块的像素宽度 = {@code 16.0 / scale} */
        public double chunkPixels() {
            return 16.0 / scale;
        }

        /** 区块边界线这一层现在值不值得画 */
        public boolean chunkEdgesVisible() {
            return chunkPixels() >= MIN_CHUNK_PIXELS;
        }
    }

    private ConfluxMapOverlay() {}

    /**
     * 按 conflux 的字段算出一帧的视口；几何不合法时返回 {@code null}（调用方直接不画）。
     *
     * <p>上下两条安全带（都取他们自己的常量口径）：
     * <pre>
     *   top    = MARGIN + 3 * 行高 + 4     // 左上角维度 / 层 / 预测三行标签（y = 6 / 17 / 28）
     *   bottom = height - (MARGIN + 10)    // 底部光标坐标 / 更新徽标的文字上沿（y = height - 16）
     * </pre>
     * 他们的顶栏控件与位置菜单面板都画在我们<b>之后</b>，所以不需要为它们留位置。
     */
    public static Viewport viewport(
            double centerX, double centerZ, double scale, int width, int height, int fontHeight) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ) || !(scale > 0.0) || !Double.isFinite(scale)) {
            return null;
        }
        if (width <= 0 || height <= 0) {
            return null;
        }
        int lineHeight = fontHeight > 0 ? fontHeight : FALLBACK_LINE_HEIGHT;
        int top = MARGIN + CHROME_ROWS * lineHeight + 4;
        int bottom = height - (MARGIN + CHROME_BOTTOM_GAP);
        if (bottom - top < MIN_MAP_HEIGHT) {
            return null;
        }
        return new Viewport(centerX, centerZ, scale, width, height, top, bottom, lineHeight);
    }

    /**
     * 画已加载区块的边界线（每条都是轴对齐的 16 格线段，进到屏幕上就是 1px 的横线 / 竖线）。
     *
     * <p>裁剪规则：竖线按 {@code [top, bottom)} 夹 y、横线按 {@code [0, width)} 夹 x；
     * 完全落在视口外的线段直接跳过，所以“画了多少条 fill”与缓存里有多少条无关。
     *
     * @return 这一帧真的画出去的线段数（0 表示没画，调用方可以据此打日志）
     */
    public static int renderChunkEdges(
            Viewport view, LoadedChunkEdgeCache cache, int color, RectFiller filler) {
        if (view == null || cache == null || filler == null || !view.chunkEdgesVisible()) {
            return 0;
        }
        int count = cache.segmentCount();
        if (count <= 0 || count > MAX_CACHED_SEGMENTS) {
            return 0;
        }
        int drawn = 0;
        for (int i = 0; i < count; ++i) {
            int x1 = cache.x1(i);
            int z1 = cache.z1(i);
            int x2 = cache.x2(i);
            int z2 = cache.z2(i);
            if (x1 == x2) {
                int screenX = (int) Math.floor(view.screenX(x1));
                if (screenX < 0 || screenX >= view.width()) {
                    continue;
                }
                int from = (int) Math.floor(view.screenZ(Math.min(z1, z2)));
                int to = (int) Math.floor(view.screenZ(Math.max(z1, z2)));
                int clipTop = Math.max(from, view.top());
                int clipBottom = Math.min(to, view.bottom());
                if (clipBottom <= clipTop) {
                    continue;
                }
                filler.fill(screenX, clipTop, screenX + 1, clipBottom, color);
                ++drawn;
            } else {
                int screenZ = (int) Math.floor(view.screenZ(z1));
                if (screenZ < view.top() || screenZ >= view.bottom()) {
                    continue;
                }
                int from = (int) Math.floor(view.screenX(Math.min(x1, x2)));
                int to = (int) Math.floor(view.screenX(Math.max(x1, x2)));
                int clipLeft = Math.max(from, 0);
                int clipRight = Math.min(to, view.width());
                if (clipRight <= clipLeft) {
                    continue;
                }
                filler.fill(clipLeft, screenZ, clipRight, screenZ + 1, color);
                ++drawn;
            }
        }
        return drawn;
    }

    /**
     * 画旅行目标的临时标记：一个 7x7 的方框 + 中心点 + 右边的绿色 {@code [SFH] Travel}。
     *
     * <p>只画这一帧，<b>不写进 conflux 的路径点存储</b>（这是与 XaeroHelper 版的有意差异：
     * 那边是真的往路径点集合里塞了一个临时点，用多了会污染用户的路径点列表）。
     *
     * @return true 表示标记落在可画区里、画出去了
     */
    public static boolean renderTravelMarker(
            Viewport view,
            double blockX,
            double blockZ,
            int markerColor,
            String label,
            int labelColor,
            RectFiller filler,
            TextDrawer drawer) {
        if (view == null || filler == null || !Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
            return false;
        }
        double screenX = view.screenX(blockX);
        double screenZ = view.screenZ(blockZ);
        if (screenX < 0 || screenX >= view.width() || screenZ < view.top() || screenZ >= view.bottom()) {
            return false;
        }
        int x = (int) Math.floor(screenX);
        int y = (int) Math.floor(screenZ);
        // 4 条 1px 边 + 中心点，画法与 conflux 的 drawChunkLoadOutline 一致
        filler.fill(x - TRAVEL_MARKER_HALF, y - TRAVEL_MARKER_HALF, x + TRAVEL_MARKER_HALF + 1, y - TRAVEL_MARKER_HALF + 1, markerColor);
        filler.fill(x - TRAVEL_MARKER_HALF, y + TRAVEL_MARKER_HALF, x + TRAVEL_MARKER_HALF + 1, y + TRAVEL_MARKER_HALF + 1, markerColor);
        filler.fill(x - TRAVEL_MARKER_HALF, y - TRAVEL_MARKER_HALF + 1, x - TRAVEL_MARKER_HALF + 1, y + TRAVEL_MARKER_HALF, markerColor);
        filler.fill(x + TRAVEL_MARKER_HALF, y - TRAVEL_MARKER_HALF + 1, x + TRAVEL_MARKER_HALF + 1, y + TRAVEL_MARKER_HALF, markerColor);
        filler.fill(x, y, x + 1, y + 1, markerColor);
        if (drawer != null && label != null && !label.isEmpty()) {
            drawer.draw(label, x + TRAVEL_MARKER_HALF + 3, y - view.fontHeight() / 2f, labelColor);
        }
        return true;
    }
}
