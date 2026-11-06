package me.zuogeren.slimefunhelperplus.module.conflux.hooks;

import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.module.conflux.ConfluxMapHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * conflux 地图上那两层覆盖（<b>已加载区块边界</b> 与 <b>旅行目标临时标记</b>）的绘制入口。
 *
 * <p>全屏与 HUD 小地图共用同一份数据（{@link ConfluxMapHelper#loadedChunkEdges()}）与同一个颜色配置，
 * 只有视口几何不同：全屏用屏幕中心的 {@code centerX / centerZ / scale}，小地图用玩家位置 +
 * {@code BLOCKS_PER_PIXEL}、可旋转、圆形时按圆盘裁剪。几何与裁剪全部在
 * {@link ConfluxMapOverlay#viewport} / {@link ConfluxMapOverlay#minimapViewport} 里算，本类只负责
 * 「取运行态 → 调绘制 → 打一行状态日志」。
 *
 * <p>本类在 conflux 的包之外，所以只碰 conflux 的 <b>public</b> 类型（{@code GuiDraw} /
 * {@code PlayerView}）；包级私有的菜单类型一律不出现在这里（它们只在对应 mixin 里经手）。
 *
 * <p>每个入口整段 {@code try/catch(Throwable)} 并只 warn 一次：我们出问题最多这两层不显示，
 * 绝不允许影响 conflux 自己的渲染。
 */
public final class ConfluxMapOverlayRenderer {
    private ConfluxMapOverlayRenderer() {}

    /**
     * 全屏地图上的两层覆盖。
     *
     * <p>调用点在 conflux 的 {@code drawLocationMenu} 的 HEAD（0.1.16 起这个方法在
     * {@code MapLocationMenuController} 上）：那一刻地图内容已经画完、他们的面板 / 按钮 / 热键提示
     * 都还没画，我们的线落在地图上、一定被他们的面板与控件盖住。
     *
     * @param screen 全屏地图界面本身（由调用方的 mixin 提供，见 {@code ConfluxStateAccess#screenOf}）——
     *               视口几何与「是否实时会话」都从它身上反射读，避免依赖各版本不同的
     *               {@code Minecraft} 取当前界面的入口
     */
    public static void drawFullscreenOverlay(Object screen, GuiDraw draw) {
        try {
            ConfluxMapHelper module = ConfluxMapHelper.INSTANCE;
            if (module == null || draw == null || screen == null) {
                return;
            }
            boolean chunkEdges = module.loadedChunkRender.get();
            boolean travelGoal = module.travelGoalSync.get();
            if (!chunkEdges && !travelGoal) {
                logOverlayState(0, "off (loaded-chunk-render=false, travel-goal-sync=false)");
                return;
            }
            if (!ConfluxStateAccess.viewingLiveSession(screen)) {
                logOverlayState(1, "skipped: the map is browsing another world or dimension");
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.font == null) {
                return;
            }
            ConfluxMapOverlay.Viewport view = ConfluxMapOverlay.viewport(
                    ConfluxStateAccess.centerX(screen),
                    ConfluxStateAccess.centerZ(screen),
                    ConfluxStateAccess.scale(screen),
                    mc.getWindow().getGuiScaledWidth(),
                    mc.getWindow().getGuiScaledHeight(),
                    mc.font.lineHeight);
            if (view == null) {
                logOverlayState(2, "skipped: the viewport geometry is unusable");
                return;
            }
            int drawnEdges = 0;
            if (chunkEdges) {
                drawnEdges = ConfluxMapOverlay.renderChunkEdges(
                        view,
                        module.loadedChunkEdges(),
                        module.loadedChunkColor.get().withAlpha(255),
                        draw::fill);
            }
            boolean drawnMarker = false;
            boolean markerSuppressed = false;
            // API 可用且路径点已建好时不再画我们自己那份（同一个目标只能出现一份）
            if (travelGoal && module.shouldDrawTravelMarker()) {
                Vec3 target = module.currentTravelTarget();
                if (target != null) {
                    drawnMarker = ConfluxMapOverlay.renderTravelMarker(
                            view,
                            target.x,
                            target.z,
                            ConfluxMapOverlay.TRAVEL_MARKER_COLOR,
                            ConfluxMapOverlay.TRAVEL_MARKER_LABEL,
                            ConfluxMapOverlay.TRAVEL_MARKER_COLOR,
                            draw::fill,
                            (text, x, y, color) -> draw.drawTextWithShadow(mc.font, text, x, y, color));
                }
            } else {
                markerSuppressed = travelGoal;
            }
            int state = 4 | (drawnEdges > 0 ? 1 : 0) | (drawnMarker ? 2 : 0);
            logOverlayState(
                    state,
                    "drawn: chunkSegments=" + module.loadedChunkEdges().segmentCount() + " chunkLines=" + drawnEdges
                            + " chunkPixelsPerChunk=" + view.chunkPixels() + " travelMarker=" + drawnMarker
                            + (markerSuppressed ? " (suppressed: the travel goal is drawn as a conflux waypoint)" : ""));
        } catch (Throwable e) {
            warnOnce("draw the conflux map overlay", e);
        }
    }

    /**
     * HUD 小地图上的同两层覆盖。
     *
     * <p>调用点在 conflux 的 {@code MinimapRadarPainter#drawRadar} 的 HEAD：小地图的地图内容与边框
     * 已经画完、雷达点 / 方位字母 / 路径点 / 玩家箭头都还没画。
     *
     * @param centerX       内容区中心的屏幕 x
     * @param centerY       内容区中心的屏幕 y
     * @param size          内容区边长
     * @param mapAngle      conflux 的旋转角（正北朝上时为 0）
     * @param blocksPerPixel 本帧生效的缩放（conflux 自己的档位，或我们覆盖之后的值）
     * @param circular      {@code config.minimapShape == CIRCLE}
     */
    public static void drawMinimapOverlay(
            GuiDraw draw,
            PlayerView player,
            float centerX,
            float centerY,
            int size,
            float mapAngle,
            float blocksPerPixel,
            boolean circular) {
        try {
            ConfluxMapHelper module = ConfluxMapHelper.INSTANCE;
            if (module == null || draw == null || player == null) {
                return;
            }
            boolean chunkEdges = module.loadedChunkRender.get();
            boolean travelGoal = module.travelGoalSync.get();
            if (!chunkEdges && !travelGoal) {
                logMinimapState(0, "off (loaded-chunk-render=false, travel-goal-sync=false)");
                return;
            }
            if (!(blocksPerPixel > 0.0F)) {
                logMinimapState(1, "skipped: the minimap blocks-per-pixel is not usable");
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.font == null) {
                return;
            }
            ConfluxMapOverlay.Viewport view = ConfluxMapOverlay.minimapViewport(
                    player.x(), player.z(), blocksPerPixel, centerX, centerY, size, mapAngle, circular, mc.font.lineHeight);
            if (view == null) {
                logMinimapState(2, "skipped: the minimap viewport geometry is unusable");
                return;
            }
            int drawnEdges = 0;
            if (chunkEdges) {
                drawnEdges = ConfluxMapOverlay.renderChunkEdges(
                        view,
                        module.loadedChunkEdges(),
                        module.loadedChunkColor.get().withAlpha(255),
                        draw::fill);
            }
            boolean drawnMarker = false;
            boolean markerSuppressed = false;
            if (travelGoal && module.shouldDrawTravelMarker()) {
                Vec3 target = module.currentTravelTarget();
                if (target != null) {
                    drawnMarker = ConfluxMapOverlay.renderTravelMarker(
                            view,
                            target.x,
                            target.z,
                            ConfluxMapOverlay.TRAVEL_MARKER_COLOR,
                            ConfluxMapOverlay.TRAVEL_MARKER_LABEL,
                            ConfluxMapOverlay.TRAVEL_MARKER_COLOR,
                            draw::fill,
                            (text, x, y, color) -> draw.drawTextWithShadow(mc.font, text, x, y, color));
                }
            } else {
                markerSuppressed = travelGoal;
            }
            int state = 4 | (drawnEdges > 0 ? 1 : 0) | (drawnMarker ? 2 : 0);
            logMinimapState(
                    state,
                    "drawn: chunkSegments=" + module.loadedChunkEdges().segmentCount()
                            + " chunkLines=" + drawnEdges
                            + " blocksPerPixel=" + blocksPerPixel
                            + " chunkPixelsPerChunk=" + view.chunkPixels()
                            + " mapAngle=" + mapAngle
                            + " circular=" + circular
                            + " travelMarker=" + drawnMarker
                            + (markerSuppressed ? " (suppressed: the travel goal is drawn as a conflux waypoint)" : ""));
        } catch (Throwable e) {
            warnOnce("draw the conflux minimap overlay", e);
        }
    }

    /** 异常只记一次日志，避免每帧刷屏 */
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED = new java.util.concurrent.atomic.AtomicBoolean();

    private static void warnOnce(String stage, Throwable e) {
        if (WARNED.compareAndSet(false, true)) {
            Debug.getLogger().warn("ConfluxMapHelper failed to " + stage + ", the map overlays are disabled until restart", e);
        }
    }

    /** 全屏覆盖层日志的去重签名（-1 = 还没打过） */
    private static int lastFullscreenState = -1;

    private static int lastMinimapState = -1;

    /**
     * 全屏覆盖层的状态日志：<b>只在状态真的变了</b>的时候打一行，实机验收按这一行判断两层各自的死活。
     *
     * <p>状态位（{@code 0/1/2} 是「没画」的三种原因，{@code 4|1} 是区块线、{@code 4|2} 是旅行标记、
     * {@code 4|3} 是两层都画了）。
     */
    private static void logOverlayState(int state, String message) {
        if (state == lastFullscreenState) {
            return;
        }
        lastFullscreenState = state;
        Debug.getLogger().info("[ConfluxMapHelper] map overlay: {}", message);
    }

    /** 小地图覆盖层的状态日志；与全屏那条刻意分开，实机验收按前缀就能看出是哪一层在画 */
    private static void logMinimapState(int state, String message) {
        if (state == lastMinimapState) {
            return;
        }
        lastMinimapState = state;
        Debug.getLogger().info("[ConfluxMapHelper] minimap overlay: {}", message);
    }
}
