/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.lwjgl.opengl.GL;

/**
 * 实例化渲染的全局状态与绘制出口。
 *
 * <p>开关由 {@link CarbonModule} 的配置驱动；这里只关心「这一帧要不要接管」。
 * 任何一项前提不满足（模块关闭、驱动不到 OpenGL 3.3、绘制线程不对）都必须静默退回原版渲染 ——
 * 本模块是可选增强，坏掉的代价必须是「没效果」而不是「画面花掉」或「启动崩溃」。
 *
 * <p>注入点自身带 {@code require = 0}：锚点在某个版本上消失时 mixin 只跳过注入，
 * 由 {@link #statistics()} 的「未接管」状态暴露出来，而不是让游戏起不来。
 */
public final class CarbonRenderer {

    private static volatile boolean enabled;
    private static volatile boolean available = true;
    private static boolean checked;

    private static CameraRenderState camera;
    private static int frame;
    private static int framesSeen;

    /** 诊断计数：注入点被调用多少次、真正接管多少次。开关是否生效、锚点是否命中，全看这两条。 */
    private static long submissions;
    private static long taken;
    private static long lastReport;

    private CarbonRenderer() {}

    public static void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        invalidate();
    }

    public static boolean enabled() {
        return enabled;
    }

    /** 驱动能力只在渲染线程第一次询问时探测一次；结果缓存在 {@link #available}。 */
    static boolean supported() {
        if (!checked) {
            // 非渲染线程问 GL 会直接抛，且一旦记为不可用就不会再探测，所以只在渲染线程上做一次性探测
            if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
                return available;
            }
            checked = true;
            try {
                var capabilities = GL.getCapabilities();
                available = capabilities.OpenGL33;
            } catch (Throwable exception) {
                available = false;
            }
        }
        return available;
    }

    public static boolean capturing() {
        return enabled && supported();
    }

    public static CameraRenderState camera() {
        return camera;
    }

    public static int frame() {
        return frame;
    }

    /**
     * 帧首快照。
     *
     * <p>绘制出口（{@code GameRenderer#renderLevel} 收尾）拿不到相机状态，只能在提交阶段顺带存下来；
     * 两个版本的提交入口不同，由 {@code CarbonFrameMixin} 分版本注入。
     */
    public static void beginFrame(CameraRenderState state) {
        camera = state;
        framesSeen++;
        if (capturing()) {
            frame++;
        }
    }

    /**
     * 物品提交的接管点，返回 {@code true} 表示已接管、调用方要丢弃这次原版提交。
     *
     * <p>当前阶段恒返回 {@code false}：批量表的装载还没接上，接管了就会画不出来。
     */
    public static boolean captureItem(
            com.mojang.blaze3d.vertex.PoseStack pose,
            net.minecraft.world.item.ItemDisplayContext context,
            int light,
            int overlay,
            int outline,
            int[] tints,
            Object quads,
            net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foil) {
        submissions++;
        return false;
    }

    /** 一帧的绘制出口，位于原版世界渲染全部结束之后、渲染线程上。 */
    public static void flush() {
        if (!capturing()) {
            return;
        }
        report();
    }

    /**
     * 每 5 秒往日志里打一行统计。
     *
     * <p>批量表还没接上，所以这里只报「注入点看到了多少提交」：模块开着时数字应当持续增长、
     * 关掉后停止 —— 这就是开关与锚点都生效的凭据。
     */
    private static void report() {
        long now = System.currentTimeMillis();
        if (now - lastReport < 5000L) {
            return;
        }
        lastReport = now;
        me.matl114.utils.Debug.info("[CarbonRender] submissions=" + submissions
                + " taken=" + taken + " frames=" + framesSeen + " frame=" + frame);
    }

    public static void invalidate() {
        lastReport = 0L;
        camera = null;
        frame = 0;
    }

    public static String statistics() {
        return "carbon: enabled=" + enabled + " supported=" + supported() + " frames=" + framesSeen
                + " patched=" + (capturing() ? "on" : "off");
    }
}
