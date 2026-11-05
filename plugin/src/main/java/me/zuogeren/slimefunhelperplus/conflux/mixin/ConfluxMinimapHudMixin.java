package me.zuogeren.slimefunhelperplus.conflux.mixin;

import cn.net.rms.confluxmap.mc.ui.hud.MinimapHudRenderer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.lang.reflect.Field;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.conflux.ConfluxMapHooks;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMinimapZoom;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * conflux <b>HUD 小地图</b>的缩放覆盖（{@code conflux-map-extra.conflux-map-helper.minimap-blocks-per-pixel}）。
 *
 * <h2>为什么换「表」而不是逐处改「值」</h2>
 * conflux 的小地图缩放只有 4 个离散档位（{@code BLOCKS_PER_PIXEL = {0.5F, 1.0F, 2.0F, 4.0F}}，由
 * {@code ConfluxConfig#minimapZoomIndex} 选），用户要的是 0.1 ~ 8 的任意值。0.1.16 里这张表只在
 * {@code MinimapHudRenderer#render} 读一次（{@code BLOCKS_PER_PIXEL[config.minimapZoomIndex]}），
 * 结果作为局部变量传给各个 painter（{@code drawTiles / drawRadar / drawWaypointMarkers …}），
 * 所以只要在这一处 {@code getstatic} 上把<b>表本身</b>换掉，他们这一帧的所有画法就都落在我们的数值上 ——
 * 瓦片、传送门区块高亮、玩家轨迹、方位字母、路径点、自定义标记、雷达点、玩家箭头一起缩放，不会出现
 * 「瓦片缩了、标记没缩」的错位。
 *
 * <p>配置为 0（默认，跟随 conflux）时 {@link ConfluxMinimapZoom#substitute} <b>直接返回他们那张表的原引用</b>，
 * 连一次拷贝都不做 —— 他们的行为逐字节不变。
 *
 * <h2>为什么反射 + require = 0</h2>
 * {@code config} 与 {@code BLOCKS_PER_PIXEL} 都是 conflux 的<b>私有</b>成员，将来任何一次改名都会让
 * {@code @Shadow} / {@code require = 1} 变成 <b>mixin apply 失败</b>（直接崩游戏，不是少画一层）。
 * 我们的硬约束是「绝不能崩游戏」，所以这里不写任何 {@code @Shadow}、注入只用 {@code require = 0}：
 * 探测不到就整段不生效，由 {@code ConfluxMapHooks} 的启动自检把原因写进日志。
 *
 * <p>本类只负责缩放；小地图上的两层覆盖（已加载区块边界 / 旅行目标标记）在
 * {@link ConfluxMinimapRadarMixin} 里，绘制本身在 {@code ConfluxMapOverlayRenderer}。
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
                            + ", the minimap zoom override is disabled until restart", e);
        }
    }

    /**
     * 一次性拿到 {@code MinimapHudRenderer#config}、{@code ConfluxConfig#minimapZoomIndex}
     * 与 {@code MinimapHudRenderer#BLOCKS_PER_PIXEL} 的反射句柄。
     *
     * <p>{@code minimapZoomIndex} 在真类里是 <b>public</b> 字段，{@code config} 与
     * {@code BLOCKS_PER_PIXEL} 是 private。探测失败不影响别的功能，只是不覆盖缩放。
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
            slimefunhelper$zoomIndexField = config.getType().getField("minimapZoomIndex");
            Field table = MinimapHudRenderer.class.getDeclaredField("BLOCKS_PER_PIXEL");
            table.setAccessible(true);
            slimefunhelper$blocksPerPixelField = table;
        } catch (Throwable e) {
            slimefunhelper$warnOnce("probe the conflux minimap renderer", e);
        }
    }

    /** 这一帧的 {@code MinimapHudRenderer#config}；拿不到返回 null（调用方直接跳过） */
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
     * conflux 每一帧读的缩放表（{@code BLOCKS_PER_PIXEL}）→ 换成我们的表，见 {@link ConfluxMinimapZoom}。
     *
     * <p>带描述符是为了避免将来他们加重载时「名字唯一」这个前提失效（那种情况 mixin 会在 apply 阶段
     * 硬失败 = 崩游戏），而描述符不匹配的后果仅仅是这一处不生效 —— 日志里有
     * {@link ConfluxMinimapZoom} 的状态行可对照。{@code remap = false}：目标在 conflux 自己的 jar 里，
     * 不经过任何 MC 映射。
     */
    @ModifyExpressionValue(
            method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(
                    value = "FIELD",
                    target = "Lcn/net/rms/confluxmap/mc/ui/hud/MinimapHudRenderer;BLOCKS_PER_PIXEL:[F"),
            require = 0,
            remap = false)
    private float[] slimefunhelper$overrideBlocksPerPixel(float[] original) {
        try {
            if (!ConfluxMapHooks.getInstance().isEnabled()) {
                return original;
            }
            slimefunhelper$probeOnce();
            return ConfluxMinimapZoom.substitute(original, slimefunhelper$confluxBlocksPerPixel(original));
        } catch (Throwable e) {
            slimefunhelper$warnOnce("override the conflux minimap zoom", e);
            return original;
        }
    }

    /**
     * conflux 这一帧会读到的那一格（{@code BLOCKS_PER_PIXEL[config.minimapZoomIndex]}）。
     *
     * <p><b>只用来打日志</b>：{@link ConfluxMinimapZoom#substitute} 把整张表填成我们的值，
     * 压根不需要知道档位。拿不到就返回 {@code NaN}（日志那头会跳过这一行），
     * 绝不允许为了记一行日志影响他们的渲染。
     */
    @Unique
    private float slimefunhelper$confluxBlocksPerPixel(float[] table) {
        try {
            Field zoom = slimefunhelper$zoomIndexField;
            Object config = slimefunhelper$minimapConfig();
            if (table == null || zoom == null || config == null) {
                return Float.NaN;
            }
            int index = zoom.getInt(config);
            return index >= 0 && index < table.length ? table[index] : Float.NaN;
        } catch (Throwable e) {
            return Float.NaN;
        }
    }
}
