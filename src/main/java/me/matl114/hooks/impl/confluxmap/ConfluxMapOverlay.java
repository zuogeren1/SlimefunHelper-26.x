package me.matl114.hooks.impl.confluxmap;

import me.matl114.utils.LoadedChunkEdgeCache;

/**
 * ConfluxMapHelper 在 conflux-map 地图上那两层的<b>几何与绘制</b>：
 * 已加载区块的边界线（{@link #renderChunkEdges}）与旅行目标标记（{@link #renderTravelMarker}）。
 *
 * <p>这个类里<b>不出现任何 conflux-map 的类型</b>：它只吃几何（中心 / 缩放 / 可画矩形 / 旋转角）
 * 与两个回调接口（{@link RectFiller} 对齐 {@code GuiDraw#fill(int,int,int,int,int)}，
 * {@link TextDrawer} 对齐 {@code GuiDraw#drawTextWithShadow(Font,String,float,float,int)}），
 * 所以它可以留在普通包里，不需要跟 mixin 一起被合并进目标类。
 *
 * <p>同一套几何被两处使用：
 * <ul>
 *   <li><b>全屏地图</b>：注入 {@code FullscreenMapScreen#drawLocationMenu} 的 HEAD，
 *       视口由 {@link #viewport} 造（地图区域 = 整个屏幕，正北朝上，无圆盘裁剪）；</li>
 *   <li><b>HUD 小地图</b>：注入 {@code MinimapHudRenderer#drawRadar} 的 HEAD，
 *       视口由 {@link #minimapViewport} 造（内容矩形 = {@code MinimapContentViewport}，
 *       中心 = 玩家位置，缩放 = {@code BLOCKS_PER_PIXEL[config.minimapZoomIndex]}，
 *       可旋转、圆形时按圆盘裁剪）。</li>
 * </ul>
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
 *
 * <h2>小地图的投影（来自 {@code MinimapHudRenderer} 0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2 三份反编译源码）</h2>
 * <pre>
 *   MinimapContentViewport.resolve(x0, y0, size, inset)  : 内容矩形 (x, y, size)，centerX = x + size/2.0F
 *   BLOCKS_PER_PIXEL = {0.5F, 1.0F, 2.0F, 4.0F}          : 每像素多少格（与全屏的 scale 同一语义）
 *   MinimapHudRenderer#drawTiles                         : 屏幕 = 玩家位置 + (世界 - 玩家) / blocksPerPixel
 *   MinimapHudRenderer#waypointMarkerOffset              : screenOffX = rawX*cos - rawY*sin（mapAngle 旋转）
 *   mapAngle = config.minimapRotate ? 180 - player.yawDegrees() : 0
 * </pre>
 * 所以小地图的视口中心恒为<b>玩家位置</b>（{@code PlayerView#x()/#z()}，就是 {@code drawTiles} 用的那个），
 * 而 {@code drawRadar(draw, centerX, centerY, size, mapAngle, player, tickDelta)} 的参数
 * 恰好把 centerX / centerY / size / mapAngle / player 一次给全 —— 不需要再去读 {@code MinimapPlacement}
 * 或 {@code contentInset}（内容矩形可以从 centerX / centerY / size 无损反推）。
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
     * 挡掉“缩小到世界级”时的摩尔纹），也是他们小地图上 {@code drawPortalChunkHighlights} 的
     * {@code if (!(sizePx < 2.0F))} 门槛；我们画的是实色线，取 2px 已经很密了。
     * 缩到最远（{@code scale = 16}）时一个区块只有 1px，此时画的不是边界而是一片红。
     *
     * <p>小地图的 {@code BLOCKS_PER_PIXEL} 只有 {0.5, 1, 2, 4}，所以小地图上这个门槛恒为真
     * （区块恒有 4~32px），它真正的用武之地是全屏那条会缩到很远的路径。
     */
    public static final double MIN_CHUNK_PIXELS = 2.0;

    /** 缓存里线段数的硬上限：超过就整层不画（防御病态数据，正常边界边只有几百条） */
    public static final int MAX_CACHED_SEGMENTS = 20000;

    /**
     * 一帧最多提交多少次 {@code fill}：超出就停止本帧剩下的线段。
     *
     * <p>正北朝上时每条边界边只花 1 次 fill，这道闸基本碰不到；但小地图可以旋转
     * （{@code config.minimapRotate}），旋转后世界轴对齐的线段在屏幕上是斜的，只能逐像素画，
     * 每条边的 fill 次数变成它的像素长度（16 / scale，最多 32 次）。护栏保证最坏情况只是一帧画得少，
     * 而不是卡住渲染线程。
     */
    public static final int MAX_FILLS_PER_FRAME = 8000;

    /** 旅行目标标记的颜色与文本（对齐 XaeroHelper 的 {@code [SFH] Travel} + 绿色） */
    public static final int TRAVEL_MARKER_COLOR = 0xFF55FF55;
    public static final String TRAVEL_MARKER_LABEL = "[SFH] Travel";
    /** 标记方框的半边长（px）：整体 7x7 */
    public static final int TRAVEL_MARKER_HALF = 3;

    /**
     * 小地图内容矩形再往里缩多少像素：他们的 {@code drawFrame} 在内容边缘画 1px 边框
     * （方形走 {@code drawBorder}，圆形走 {@code drawRing(..., size / 2.0F, 1.0F, ...)}），
     * 我们的线是从 {@code drawRadar} 的 HEAD 画的（在边框<b>之后</b>），不缩就会压在他们的边框上。
     */
    public static final int MINIMAP_EDGE_INSET = 1;

    /** 边长小于这个值的小地图整层不画（几何已经没有意义） */
    public static final int MIN_MINIMAP_SIZE = 8;

    /** 把线段判成“屏幕上的竖线 / 横线”的阈值（px）：屏幕坐标差小于它就按轴对齐处理 */
    private static final double AXIS_EPSILON = 0.5;

    /**
     * 一帧的投影 + 可画区域。
     *
     * <p>屏幕位置 = 投影基准点 {@code (screenCenterX, screenCenterY)} 再叠加投影出来的偏移。
     * 全屏那条路径给的是 {@code (width / 2.0, height / 2.0)} 与 {@code left = 0, right = width}，
     * 与改造前逐像素一致（<b>不能</b>把基准点写成可画矩形的中心：上下安全带的中心不是屏幕中心）。
     *
     * @param centerX          视口中心的世界 X
     * @param centerZ          视口中心的世界 Z
     * @param scale            每像素多少格（全屏的 {@code scale}；小地图的 {@code BLOCKS_PER_PIXEL[zoomIndex]}，
     *                         越大越“缩得远”）
     * @param screenCenterX    投影基准点的屏幕 X：世界中心 {@code (centerX, centerZ)} 就落在这一点上。
     *                         <b>它不一定等于可画矩形的中心</b> —— 全屏地图的裁剪安全带把上下各切掉一块，
     *                         但他们的投影基准始终是<b>整屏中心</b>（{@code width / 2.0, height / 2.0}）；
     *                         HUD 小地图上则是内容区中心（{@code MinimapContentViewport#centerX()}）
     * @param screenCenterY    投影基准点的屏幕 Y（同上）
     * @param left             可画矩形左边界（含，屏幕绝对坐标）
     * @param top              可画矩形上边界（含）
     * @param right            可画矩形右边界（不含）
     * @param bottom           可画矩形下边界（不含）
     * @param fontHeight       字体行高（用来算顶部安全带与标记文字的位置）
     * @param mapAngleDegrees  地图旋转角（度）：0 = 正北朝上（全屏恒为 0，小地图看 {@code config.minimapRotate}）
     * @param clipRadius       圆盘裁剪半径（px）：{@code <= 0} 表示按 {@code left/top/right/bottom}
     *                         的矩形裁剪；圆形小地图给的是内容半径（圆心即投影基准点）
     */
    public record Viewport(
            double centerX,
            double centerZ,
            double scale,
            double screenCenterX,
            double screenCenterY,
            int left,
            int top,
            int right,
            int bottom,
            int fontHeight,
            double mapAngleDegrees,
            double clipRadius) {
        /** 世界 {@code (blockX, blockZ)} -> 屏幕 X */
        public double screenX(double blockX, double blockZ) {
            double dx = (blockX - centerX) / scale;
            if (mapAngleDegrees == 0.0) {
                return screenCenterX() + dx;
            }
            double radians = Math.toRadians(mapAngleDegrees);
            return screenCenterX() + dx * Math.cos(radians) - ((blockZ - centerZ) / scale) * Math.sin(radians);
        }

        /** 世界 {@code (blockX, blockZ)} -> 屏幕 Y */
        public double screenZ(double blockX, double blockZ) {
            double dz = (blockZ - centerZ) / scale;
            if (mapAngleDegrees == 0.0) {
                return screenCenterY() + dz;
            }
            double radians = Math.toRadians(mapAngleDegrees);
            return screenCenterY() + ((blockX - centerX) / scale) * Math.sin(radians) + dz * Math.cos(radians);
        }

        /** 屏幕点是不是落在可画区里（矩形 + 可选的圆盘） */
        public boolean contains(double screenX, double screenY) {
            if (screenX < left || screenX >= right || screenY < top || screenY >= bottom) {
                return false;
            }
            if (clipRadius <= 0.0) {
                return true;
            }
            double dx = screenX - screenCenterX();
            double dy = screenY - screenCenterY();
            return dx * dx + dy * dy <= clipRadius * clipRadius;
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
     * 按 conflux 全屏地图的字段算出一帧的视口；几何不合法时返回 {@code null}（调用方直接不画）。
     *
     * <p>上下两条安全带（都取他们自己的常量口径）：
     * <pre>
     *   top    = MARGIN + 3 * 行高 + 4     // 左上角维度 / 层 / 预测三行标签（y = 6 / 17 / 28）
     *   bottom = height - (MARGIN + 10)    // 底部光标坐标 / 更新徽标的文字上沿（y = height - 16）
     * </pre>
     * 他们的顶栏控件与位置菜单面板都画在我们<b>之后</b>，所以不需要为它们留位置。
     *
     * <p>{@code left = 0, right = width}：地图区域就是整屏，屏幕中心即 {@code width / 2.0, height / 2.0}。
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
        return new Viewport(
                centerX, centerZ, scale, width / 2.0, height / 2.0, 0, top, width, bottom, lineHeight, 0.0, 0.0);
    }

    /**
     * 按 conflux <b>HUD 小地图</b>那一帧的参数算出视口；几何不合法时返回 {@code null}。
     *
     * <p>参数就是 {@code MinimapHudRenderer#drawRadar(GuiDraw, float, float, int, float, PlayerView, float)}
     * 的入参（{@code size} 是内容区边长，{@code centerX / centerY} 是内容区中心的屏幕坐标）。
     * 内容矩形由中心与边长无损反推：
     * <pre>
     *   rectLeft = round(screenCenterX - size / 2.0F)   // == MinimapContentViewport#x()
     *   left     = rectLeft + MINIMAP_EDGE_INSET        // 让开他们的 1px 边框
     *   right    = rectLeft + size - MINIMAP_EDGE_INSET
     * </pre>
     * 世界中心恒为玩家位置（{@code player.x() / player.z()}，与 {@code drawTiles} 用的中心同一个），
     * 缩放是 {@code BLOCKS_PER_PIXEL[config.minimapZoomIndex]}。
     *
     * @param mapAngleDegrees 他们的 {@code mapAngle}：{@code config.minimapRotate} 为真时是
     *                        {@code 180 - player.yawDegrees()}，否则 0（正北朝上）
     * @param circular        {@code config.minimapShape == Shape.CIRCLE}：真则按内容圆盘裁剪，
     *                        否则按内容矩形裁剪
     */
    public static Viewport minimapViewport(
            double centerX,
            double centerZ,
            double scale,
            float screenCenterX,
            float screenCenterY,
            int size,
            double mapAngleDegrees,
            boolean circular,
            int fontHeight) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ) || !(scale > 0.0) || !Double.isFinite(scale)) {
            return null;
        }
        if (!Double.isFinite(mapAngleDegrees)) {
            return null;
        }
        if (size < MIN_MINIMAP_SIZE) {
            return null;
        }
        int rectLeft = Math.round(screenCenterX - size / 2.0F);
        int rectTop = Math.round(screenCenterY - size / 2.0F);
        int left = rectLeft + MINIMAP_EDGE_INSET;
        int top = rectTop + MINIMAP_EDGE_INSET;
        int right = rectLeft + size - MINIMAP_EDGE_INSET;
        int bottom = rectTop + size - MINIMAP_EDGE_INSET;
        if (right - left <= 0 || bottom - top <= 0) {
            return null;
        }
        double clipRadius = circular ? size / 2.0 - MINIMAP_EDGE_INSET : 0.0;
        if (circular && clipRadius <= 0.0) {
            return null;
        }
        int lineHeight = fontHeight > 0 ? fontHeight : FALLBACK_LINE_HEIGHT;
        return new Viewport(
                centerX, centerZ, scale, screenCenterX, screenCenterY, left, top, right, bottom, lineHeight, mapAngleDegrees,
                clipRadius);
    }

    /**
     * 画已加载区块的边界线（每条都是轴对齐的 16 格线段；正北朝上时进到屏幕上就是 1px 的横线 / 竖线）。
     *
     * <p>裁剪规则：竖线按 {@code [top, bottom)} 夹 y、横线按 {@code [left, right)} 夹 x；
     * 完全落在视口外的线段直接跳过，所以“画了多少条 fill”与缓存里有多少条无关。
     * {@code clipRadius > 0}（圆形小地图）时每条线再按圆盘夹一次：
     * 竖线按屏幕 x 求出圆内的 y 区间，横线按屏幕 y 求出圆内的 x 区间 —— 都是解析解，不做逐像素试探。
     *
     * <p>{@code mapAngleDegrees != 0}（小地图开了旋转）时世界轴对齐的线段在屏幕上是斜的，
     * 走 Bresenham 阶梯逐像素画；像素数由 {@link #MAX_FILLS_PER_FRAME} 兜底。
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
        int budget = MAX_FILLS_PER_FRAME;
        for (int i = 0; i < count; ++i) {
            int x1 = cache.x1(i);
            int z1 = cache.z1(i);
            int x2 = cache.x2(i);
            int z2 = cache.z2(i);
            double sx1 = view.screenX(x1, z1);
            double sy1 = view.screenZ(x1, z1);
            double sx2 = view.screenX(x2, z2);
            double sy2 = view.screenZ(x2, z2);
            int used;
            if (Math.abs(sx1 - sx2) <= AXIS_EPSILON) {
                used = drawVertical(view, sx1, sy1, sy2, color, filler, budget);
            } else if (Math.abs(sy1 - sy2) <= AXIS_EPSILON) {
                used = drawHorizontal(view, sx1, sx2, sy1, color, filler, budget);
            } else {
                used = drawSlanted(view, sx1, sy1, sx2, sy2, color, filler, budget);
            }
            if (used < 0) {
                // 预算耗尽：本帧剩下的线段不再画，下一帧重来（护栏，见 MAX_FILLS_PER_FRAME）
                break;
            }
            if (used > 0) {
                ++drawn;
                budget -= used;
            }
        }
        return drawn;
    }

    /**
     * 画一条屏幕竖线，做矩形 + 圆盘裁剪。
     *
     * @return 消耗的 fill 次数；0 表示整条在可画区外；-1 表示预算不够（调用方停止本帧）
     */
    private static int drawVertical(
            Viewport view, double screenX, double screenY1, double screenY2, int color, RectFiller filler, int budget) {
        int x = (int) Math.floor(screenX);
        if (x < view.left() || x >= view.right()) {
            return 0;
        }
        int clipTop = Math.max(
                (int) Math.floor(Math.min(screenY1, screenY2)), view.top());
        int clipBottom = Math.min(
                (int) Math.floor(Math.max(screenY1, screenY2)), view.bottom());
        double radius = view.clipRadius();
        if (radius > 0.0) {
            double dx = (x + 0.5) - view.screenCenterX();
            double squared = radius * radius - dx * dx;
            if (squared <= 0.0) {
                return 0;
            }
            // 像素 y 代表区间 [y, y + 1)，中心是 y + 0.5：要求 |y + 0.5 - 圆心| <= half，
            // 于是 y ∈ [ceil(圆心 - half - 0.5), floor(圆心 + half - 0.5)]（fill 的上界不含，故 +1）。
            // 用像素<b>上边</b>去夹（ceil(c - half)）会在圆周上少画 1px，实测能复现。
            double half = Math.sqrt(squared);
            clipTop = Math.max(clipTop, (int) Math.ceil(view.screenCenterY() - half - 0.5));
            clipBottom = Math.min(clipBottom, (int) Math.floor(view.screenCenterY() + half - 0.5) + 1);
        }
        if (clipBottom <= clipTop) {
            return 0;
        }
        if (budget <= 0) {
            return -1;
        }
        filler.fill(x, clipTop, x + 1, clipBottom, color);
        return 1;
    }

    /**
     * 画一条屏幕横线，做矩形 + 圆盘裁剪。
     *
     * @return 同 {@link #drawVertical}
     */
    private static int drawHorizontal(
            Viewport view, double screenX1, double screenX2, double screenY, int color, RectFiller filler, int budget) {
        int y = (int) Math.floor(screenY);
        if (y < view.top() || y >= view.bottom()) {
            return 0;
        }
        int clipLeft = Math.max(
                (int) Math.floor(Math.min(screenX1, screenX2)), view.left());
        int clipRight = Math.min(
                (int) Math.floor(Math.max(screenX1, screenX2)), view.right());
        double radius = view.clipRadius();
        if (radius > 0.0) {
            double dy = (y + 0.5) - view.screenCenterY();
            double squared = radius * radius - dy * dy;
            if (squared <= 0.0) {
                return 0;
            }
            // 同 drawVertical：按像素中心（x + 0.5）夹，避免在圆周上少画 1px
            double half = Math.sqrt(squared);
            clipLeft = Math.max(clipLeft, (int) Math.ceil(view.screenCenterX() - half - 0.5));
            clipRight = Math.min(clipRight, (int) Math.floor(view.screenCenterX() + half - 0.5) + 1);
        }
        if (clipRight <= clipLeft) {
            return 0;
        }
        if (budget <= 0) {
            return -1;
        }
        filler.fill(clipLeft, y, clipRight, y + 1, color);
        return 1;
    }

    /**
     * 画一条屏幕斜线（只在小地图旋转时出现）：Bresenham 逐像素，每个像素一个 1x1 的 fill。
     *
     * <p>线段在屏幕上的长度恒为 {@code 16 / scale} 像素，所以每条边最多 32 次 fill。
     *
     * @return 同 {@link #drawVertical}
     */
    private static int drawSlanted(
            Viewport view, double sx1, double sy1, double sx2, double sy2, int color, RectFiller filler, int budget) {
        int px1 = (int) Math.floor(sx1);
        int py1 = (int) Math.floor(sy1);
        int dx = (int) Math.floor(sx2) - px1;
        int dy = (int) Math.floor(sy2) - py1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        int used = 0;
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        for (int k = 0; k <= steps; ++k) {
            int px = steps == 0 ? px1 : px1 + Math.round((float) dx * k / steps);
            int py = steps == 0 ? py1 : py1 + Math.round((float) dy * k / steps);
            if (px == lastX && py == lastY) {
                continue;
            }
            lastX = px;
            lastY = py;
            int painted = drawPoint(view, px, py, color, filler, budget - used);
            if (painted < 0) {
                return used > 0 ? used : -1;
            }
            used += painted;
        }
        return used;
    }

    /**
     * 画一个屏幕像素（1x1），做矩形 + 圆盘裁剪。
     *
     * @return 同 {@link #drawVertical}
     */
    private static int drawPoint(Viewport view, int x, int y, int color, RectFiller filler, int budget) {
        if (x < view.left() || x >= view.right() || y < view.top() || y >= view.bottom()) {
            return 0;
        }
        double radius = view.clipRadius();
        if (radius > 0.0) {
            double dx = (x + 0.5) - view.screenCenterX();
            double dy = (y + 0.5) - view.screenCenterY();
            if (dx * dx + dy * dy > radius * radius) {
                return 0;
            }
        }
        if (budget <= 0) {
            return -1;
        }
        filler.fill(x, y, x + 1, y + 1, color);
        return 1;
    }

    /**
     * 画旅行目标的临时标记：一个 7x7 的方框 + 中心点 + 右边的绿色 {@code [SFH] Travel}。
     *
     * <p>只画这一帧，<b>不写进 conflux 的路径点存储</b>（这是与 XaeroHelper 版的有意差异：
     * 那边是真的往路径点集合里塞了一个临时点，用多了会污染用户的路径点列表）。
     *
     * <p>标记落在可画区之外时整块不画（小地图上就是“超出这一圈就不显示”，不做边缘指示器 ——
     * conflux 自己给路径点做的边缘指示是另一套图标，我们不去碰）。
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
        double screenX = view.screenX(blockX, blockZ);
        double screenZ = view.screenZ(blockX, blockZ);
        if (!view.contains(screenX, screenZ)) {
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
