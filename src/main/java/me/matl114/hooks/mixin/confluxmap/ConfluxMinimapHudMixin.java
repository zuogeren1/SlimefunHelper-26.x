package me.matl114.hooks.mixin.confluxmap;

import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.hud.MinimapHudRenderer;
import java.lang.reflect.Field;
import me.matl114.hacks.modules.survival.ConfluxMapHelper;
import me.matl114.hooks.impl.confluxmap.ConfluxMapOverlay;
import me.matl114.utils.Debug;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把全屏地图上那两层（已加载区块边界 + 旅行目标标记）同样画到 conflux 的 <b>HUD 小地图</b>上。
 *
 * <h2>为什么注入 {@code drawRadar} 的 HEAD</h2>
 * {@code MinimapHudRenderer#render(GuiGraphicsExtractor, DeltaTracker)} 是注册给 Fabric
 * {@code HudElementRegistry.addLast(confluxmap:minimap, this::render)} 的回调，每帧的绘制顺序是
 * （0.1.9-26.2 反编译源码行号，0.1.7-26.2 与 0.1.9-26.1.2 逐条一致）：
 * <pre>
 *   advance 238-276   背景填充 → scissor → 瓦片(drawTiles) → 传送门区块高亮 → 玩家轨迹 → 标注 → drawFrame 边框
 *   advance 278       drawRadar(draw, centerX, centerY, contentSize, mapAngle, player, tickDelta)   ← 我们在这里
 *   advance 279-286   drawCardinals →（0.1.9 的 drawPortalMarkers）→ drawWaypointMarkers
 *                     → drawCustomMarkers → 相机标记 → 本地玩家箭头 → drawInfoText
 * </pre>
 * 于是 HEAD 上正好是「地图内容（瓦片 / 高亮 / 边框）已画完、他们的所有标记还没画」：
 * 我们的线落在地图上，雷达点 / 方位字母 / 路径点 / 玩家箭头一定盖在我们上面。
 * {@code drawRadar} 在 {@code render} 里是<b>唯一</b>的调用点（javap + 反编译源码 278 行），
 * 而 {@code render} 只由 {@code HudElementRegistry} 调用 —— 所以能进到这里就说明
 * 「小地图真的在显示」（{@code MinimapHudVisibility.shouldRender} 已通过：开关打开、会话激活、
 * 没开全屏地图、没开容器界面、没按 F3），不需要我们再判断一遍。
 *
 * <p>参数里已经带全了我们需要的几何（见 {@link ConfluxMapOverlay#minimapViewport}）：
 * {@code centerX / centerY} 是内容区中心的屏幕坐标，{@code size} 是内容区边长，
 * {@code mapAngle} 是旋转角（正北朝上时为 0），{@code player} 是视口中心的世界坐标。
 *
 * <h2>为什么全部走反射、@Inject 用 require = 0</h2>
 * 三份 jar（0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2）javap 实测都有
 * {@code private void drawRadar(GuiDraw, float, float, int, float, PlayerView, float)}、
 * {@code private final ConfluxConfig config} 与 {@code private static final float[] BLOCKS_PER_PIXEL}，
 * 但这些都是 conflux 的<b>私有</b>成员，将来任何一次改名都会让 @Shadow / require = 1 变成
 * <b>mixin apply 失败</b>（那是直接崩游戏，不是少画一层）。我们的硬约束是「绝不能崩游戏」，
 * 所以这里刻意不写任何 @Shadow、注入也只用 require = 0：探测不到就整层不画，
 * 由 {@code ConfluxMapHooks} 的构造期自检在日志里给出明确原因。
 *
 * <p>整段 {@code try/catch(Throwable)} + 只 warn 一次：我们出问题最多这两层不显示，
 * 绝不允许影响到他们的小地图。
 */
@Pseudo
@Environment(EnvType.CLIENT)
@Mixin(MinimapHudRenderer.class)
public abstract class ConfluxMinimapHudMixin {
    /** 反射句柄是否已经探测过（失败也只探测一次，避免每帧都抛 NoSuchFieldException） */
    @Unique
    private static volatile boolean slimefunhelper$minimapProbed;

    @Unique
    private static Field slimefunhelper$configField;

    @Unique
    private static Field slimefunhelper$zoomIndexField;

    @Unique
    private static Field slimefunhelper$shapeField;

    @Unique
    private static Field slimefunhelper$blocksPerPixelField;

    /** 异常只记一次日志，避免每帧刷屏 */
    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean slimefunhelper$minimapWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Unique
    private static void slimefunhelper$warnOnce(String stage, Throwable e) {
        if (slimefunhelper$minimapWarned.compareAndSet(false, true)) {
            Debug.getLogger()
                    .warn("ConfluxMapHelper failed to " + stage
                            + ", the minimap overlay is disabled until restart", e);
        }
    }

    /**
     * 一次性拿到 {@code MinimapHudRenderer#config}、{@code ConfluxConfig#minimapZoomIndex / #minimapShape}
     * 与 {@code MinimapHudRenderer#BLOCKS_PER_PIXEL} 的反射句柄。
     *
     * <p>{@code minimapZoomIndex} 与 {@code minimapShape} 在真类里是 <b>public</b> 字段
     * （javap 实测三份 jar 一致），{@code config} 与 {@code BLOCKS_PER_PIXEL} 是 private。
     * 探测失败不影响别的功能，只是这两层不画。
     */
    @Unique
    private static void slimefunhelper$probeOnce() {
        if (slimefunhelper$minimapProbed) {
            return;
        }
        slimefunhelper$minimapProbed = true;
        try {
            Field config = MinimapHudRenderer.class.getDeclaredField("config");
            config.setAccessible(true);
            slimefunhelper$configField = config;
            Class<?> configClass = config.getType();
            slimefunhelper$zoomIndexField = configClass.getField("minimapZoomIndex");
            slimefunhelper$shapeField = configClass.getField("minimapShape");
            Field table = MinimapHudRenderer.class.getDeclaredField("BLOCKS_PER_PIXEL");
            table.setAccessible(true);
            slimefunhelper$blocksPerPixelField = table;
        } catch (Throwable e) {
            slimefunhelper$warnOnce("probe the conflux minimap renderer", e);
        }
    }

    /** 这一帧的 {@code MinimapHudRenderer#config}；拿不到返回 null（调用方直接不画） */
    @Unique
    private Object slimefunhelper$minimapConfig() {
        try {
            Field field = slimefunhelper$configField;
            return field == null ? null : field.get(this);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 小地图的缩放：{@code BLOCKS_PER_PIXEL[config.minimapZoomIndex]}（<b>每像素多少格</b>，
     * 与全屏地图的 {@code scale} 同一语义，见 {@code MinimapHudRenderer#drawTiles} 的
     * {@code screenX = (blockX - player.x()) / blocksPerPixel}）。
     *
     * <p>数组是从他们的类里读出来的（而不是我们写死 {0.5, 1, 2, 4}）：他们的表变了我们自动跟上，
     * 越界 / 读不到就返回 0（= 不画），绝不会用错的缩放出错位的线。
     */
    @Unique
    private float slimefunhelper$blocksPerPixel() {
        try {
            Field table = slimefunhelper$blocksPerPixelField;
            Field zoom = slimefunhelper$zoomIndexField;
            Object config = slimefunhelper$minimapConfig();
            if (table == null || zoom == null || config == null) {
                return 0.0F;
            }
            if (!(table.get(null) instanceof float[] values)) {
                return 0.0F;
            }
            int index = zoom.getInt(config);
            return index >= 0 && index < values.length ? values[index] : 0.0F;
        } catch (Throwable e) {
            return 0.0F;
        }
    }

    /** 小地图是不是圆形（{@code config.minimapShape == CIRCLE}）：决定圆盘裁剪还是矩形裁剪 */
    @Unique
    private boolean slimefunhelper$circular() {
        try {
            Field shape = slimefunhelper$shapeField;
            Object config = slimefunhelper$minimapConfig();
            if (shape == null || config == null) {
                return false;
            }
            Object value = shape.get(config);
            return value instanceof Enum<?> constant && "CIRCLE".equals(constant.name());
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 小地图上的两层覆盖：<b>客户端已加载区块的边界线</b>与<b>当前旅行目标的临时标记</b>。
     *
     * <p>画什么与全屏完全一致（同一份 {@code LoadedChunkEdgeCache}、同一个颜色配置、
     * 同一个 {@code [SFH] Travel} 绿标），只有几何不同：
     * 中心是玩家位置、缩放是 {@code BLOCKS_PER_PIXEL}、可旋转、圆形时按圆盘裁剪。
     *
     * <p>{@code require = 0}：0.1.7 / 0.1.9 三份 jar 里 {@code drawRadar} 的签名逐字一致，
     * 但将来一旦改名，我们宁可这一层静默不画，也不要让整个 mixin apply 失败把游戏带崩
     * （{@code ConfluxMapHooks} 的自检会在启动日志里点出原因）。
     */
    @Inject(method = "drawRadar", at = @At("HEAD"), require = 0)
    private void slimefunhelper$drawMinimapOverlay(
            GuiDraw draw,
            float centerX,
            float centerY,
            int size,
            float mapAngle,
            PlayerView player,
            float tickDelta,
            CallbackInfo ci) {
        try {
            ConfluxMapHelper module = ConfluxMapHelper.INSTANCE;
            if (module == null || draw == null || player == null) {
                return;
            }
            boolean chunkEdges = module.loadedChunkRender.get();
            boolean travelGoal = module.travelGoalSync.get();
            if (!chunkEdges && !travelGoal) {
                slimefunhelper$logMinimapState(0, "off (loaded-chunk-render=false, travel-goal-sync=false)");
                return;
            }
            slimefunhelper$probeOnce();
            float blocksPerPixel = slimefunhelper$blocksPerPixel();
            if (!(blocksPerPixel > 0.0F)) {
                slimefunhelper$logMinimapState(
                        1, "skipped: cannot resolve BLOCKS_PER_PIXEL[config.minimapZoomIndex]");
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.font == null) {
                return;
            }
            boolean circular = slimefunhelper$circular();
            ConfluxMapOverlay.Viewport view = ConfluxMapOverlay.minimapViewport(
                    player.x(),
                    player.z(),
                    blocksPerPixel,
                    centerX,
                    centerY,
                    size,
                    mapAngle,
                    circular,
                    mc.font.lineHeight);
            if (view == null) {
                slimefunhelper$logMinimapState(2, "skipped: the minimap viewport geometry is unusable");
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
            if (travelGoal) {
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
            }
            int state = 4 | (drawnEdges > 0 ? 1 : 0) | (drawnMarker ? 2 : 0);
            slimefunhelper$logMinimapState(
                    state,
                    "drawn: chunkSegments=" + module.loadedChunkEdges().segmentCount()
                            + " chunkLines=" + drawnEdges
                            + " blocksPerPixel=" + blocksPerPixel
                            + " chunkPixelsPerChunk=" + view.chunkPixels()
                            + " mapAngle=" + mapAngle
                            + " circular=" + circular
                            + " travelMarker=" + drawnMarker);
        } catch (Throwable e) {
            slimefunhelper$warnOnce("draw the conflux minimap overlay", e);
        }
    }

    /** 覆盖层日志的去重签名（-1 = 还没打过） */
    @Unique
    private static int slimefunhelper$lastMinimapLogState = -1;

    /**
     * 小地图覆盖层的状态日志：<b>只在状态真的变了</b>的时候打一行。
     *
     * <p>与全屏那条（{@code [ConfluxMapHelper] map overlay: ...}）刻意分开，
     * 实机验收按前缀就能看出是哪一层在画。
     */
    @Unique
    private static void slimefunhelper$logMinimapState(int state, String message) {
        if (state == slimefunhelper$lastMinimapLogState) {
            return;
        }
        slimefunhelper$lastMinimapLogState = state;
        Debug.getLogger().info("[ConfluxMapHelper] minimap overlay: {}", message);
    }
}
