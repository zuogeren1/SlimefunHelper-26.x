package me.zuogeren.slimefunhelperplus.module.newclickgui.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.matl114.renderer.TextRenderers;
import me.matl114.renderer.text.TextDrawer;
import me.zuogeren.slimefunhelperplus.module.newclickgui.NewClickGui;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * 界面绘制原语：颜色常量、切角矩形、细线、字距、裁剪、动效曲线。
 *
 * <p>调色板集中在 {@link Palette}，几何常量（切角、行高、内边距）也都在这层。
 * 要接另一套外观时，替换 {@code palette(...)} 的返回值与这几个常量即可，
 * 上层界面只认这两者的语义（paper/panel/ink/rule/chip/trace…），不认具体色值。
 *
 * <p>全部走 {@link VDrawContext}，宿主已经替我们抹平了 26.1.2 / 26.2 / 26.3 的绘制入口差异，
 * 所以本包一行版本指令都不需要。切角走一张 alpha 遮罩贴图，斜边是连续的；贴图缺失时退回矩形阶梯。
 *
 * <p>外观约定（换皮时照这三行改）：变体 = 夜班 / 强调色 lime {@code #B8FF3D} / 中性纸 / 标准切角 / 无底纹 / 紧凑 / 动效克制；
 * 页面类型 = 控制台（窗口化，深色命令栏 + 记录行 + 右侧详情看板）；
 * 主视觉 = 模块记录行，编号与启用计数都是真实数据。
 */
public final class Ef {
    private Ef() {}

    /** 强调色：只表示当前位置、可点、需要看一眼，只做填充。 */
    public static final int SIGNAL = 0xFFB8FF3D;

    public static final int ON_SIGNAL = 0xFF161616;

    /** 命令栏两套主题下都是深色，所以这几个色值不跟主题走。 */
    public static final int BAR_BG = 0xFF161616;
    public static final int BAR_INK = 0xFFEDEDED;
    public static final int BAR_INK2 = 0xFF9A9A9A;
    public static final int BAR_RULE = 0xFF3A3A3A;

    /** 屏幕起始的三色条：品红 / 强调色 / 薄荷绿；只做色块，不做线。 */
    public static final int TRI_A = 0xFFFF00F0;
    public static final int TRI_C = 0xFF00FFA2;

    /** 可点件统一的切角尺寸：换外观时只改这里。 */
    public static final int CUT = 3;

    /** 分组块与主要按钮的切角，比普通可点件略大。 */
    public static final int CUT_WIDE = 4;

    /** 看板用的切角比可点件大得多，才看得出「朝中间切」的形状。 */
    public static final int CUT_PANEL = 12;

    /** 切角方向，指的是「被切掉的那一个角」。 */
    public enum Corner {
        BR,
        BL,
        TR,
        TL
    }

    /** 一套主题的纸面/墨色。深色不是浅色的反相，是另一组独立取值。 */
    public record Palette(
            int paper, int panel, int ink, int ink2, int rule, int chip, int trace, int tagBg, int tagFg) {
        /** 只按比例淡掉背景类色值；文字、trace 保持不透明，窗口半透明时字还得看得清。 */
        public Palette withAlpha(double factor) {
            if (factor >= 0.999D) {
                return this;
            }
            int alpha = (int) Math.round(255.0D * Math.max(0.05D, Math.min(1.0D, factor)));
            return new Palette(
                    fade(paper, alpha),
                    fade(panel, alpha),
                    ink,
                    ink2,
                    fade(rule, alpha),
                    fade(chip, alpha),
                    trace,
                    fade(tagBg, alpha),
                    tagFg);
        }

        /**
         * 铺在 trace 填充上的前景色：深色主题的 trace 是强调色，用墨色反白；
         * 浅色主题的 trace 退成墨色（纸上黄线看不见），此时用纸色反白。
         */
        public int onTrace() {
            return trace == ink ? paper : ON_SIGNAL;
        }

        private static int fade(int color, int alpha) {
            return (alpha << 24) | (color & 0x00FFFFFF);
        }
    }

    public static final Palette DARK =
            new Palette(0xFF191919, 0xFF222222, 0xFFEDEDED, 0xFFA3A3A3, 0xFF353535, 0xFF2E2E2E, SIGNAL, 0xFF3A3A3A, 0xFFEDEDED);

    public static final Palette LIGHT =
            new Palette(0xFFF4F4F2, 0xFFFFFFFF, 0xFF161616, 0xFF5C5C5C, 0xFFD9D9D6, 0xFFE4E4E0, 0xFF161616, 0xFF161616, 0xFFFFFFFF);

    public static Palette palette(boolean light) {
        return light ? LIGHT : DARK;
    }

    // ------------------------------------------------------------------ 颜色

    /** 颜色加减明度，用来做 hover 这类同色系变化。 */
    public static int mix(int a, int b, float t) {
        int aa = (int) (((a >>> 24) & 0xFF) * (1 - t) + ((b >>> 24) & 0xFF) * t);
        int rr = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int gg = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bb = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }

    /** 按比例缩放一个颜色的 alpha，入场淡入用。 */
    public static int alpha(int color, float factor) {
        float f = Math.max(0.0F, Math.min(1.0F, factor));
        int a = (int) (((color >>> 24) & 0xFF) * f);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    // ------------------------------------------------------------------ 动效

    /** 出现、到位后稳住。 */
    public static float snap(float t) {
        return bezierY(0.16D, 1.0D, 0.3D, 1.0D, t);
    }

    /** 慢起硬停：擦入、快门、页签伸开，以及所有退出。 */
    public static float mech(float t) {
        return bezierY(0.7D, 0.0D, 0.2D, 1.0D, t);
    }

    /** 普通过渡：颜色变化这类不该被注意到的位移。 */
    public static float ease(float t) {
        return bezierY(0.2D, 0.7D, 0.1D, 1.0D, t);
    }

    /**
     * 从某个时刻起的线性进度 0~1，曲线由调用方挑。
     * 界面是每帧重绘的，所以动效只靠时间戳算，不额外维持状态机。
     */
    public static float progress(long startMillis, float durationMillis) {
        if (durationMillis <= 0.0F || !NewClickGui.animations()) {
            // 关掉动效时，所有过渡直接落到终态
            return 1.0F;
        }
        float t = (System.currentTimeMillis() - startMillis) / durationMillis;
        return t <= 0.0F ? 0.0F : (t >= 1.0F ? 1.0F : t);
    }

    /** CSS 的 cubic-bezier(x1,y1,x2,y2)：先解出横轴参数，再取纵轴。 */
    private static float bezierY(double x1, double y1, double x2, double y2, float t) {
        double x = Math.max(0.0D, Math.min(1.0D, t));
        double u = x;
        for (int i = 0; i < 4; i++) {
            double slope = bezierSlope(x1, x2, u);
            if (Math.abs(slope) < 1.0E-6D) {
                break;
            }
            u = Math.max(0.0D, Math.min(1.0D, u - (bezierValue(x1, x2, u) - x) / slope));
        }
        return (float) bezierValue(y1, y2, u);
    }

    private static double bezierValue(double a, double b, double u) {
        double v = 1.0D - u;
        return 3.0D * v * v * u * a + 3.0D * v * u * u * b + u * u * u;
    }

    private static double bezierSlope(double a, double b, double u) {
        double v = 1.0D - u;
        return 3.0D * v * v * a + 6.0D * v * u * (b - a) + 3.0D * u * u * (1.0D - b);
    }

    // ------------------------------------------------------------------ 图元

    public static void fill(VDrawContext ctx, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        ctx.fill(x, y, x + w, y + h, color);
    }

    /** 悬停擦入：底色上从左侧铺进强调色，前沿一条亮边。 */
    public static void wipe(VDrawContext ctx, int x, int y, int w, int h, int base, int accent, float progress) {
        fill(ctx, x, y, w, h, base);
        wipeFront(ctx, x, y, w, h, accent, progress);
    }

    /** 带切角的擦入：形状本身是切角件，擦到满时才补上切角。 */
    public static void wipeCut(
            VDrawContext ctx, int x, int y, int w, int h, int base, int accent, float progress, int cut, Corner corner) {
        cutFill(ctx, x, y, w, h, base, cut, corner);
        wipeFront(ctx, x, y, w, h, accent, progress);
    }

    private static void wipeFront(VDrawContext ctx, int x, int y, int w, int h, int accent, float progress) {
        float t = Math.max(0.0F, Math.min(1.0F, progress));
        if (t <= 0.0F) {
            return;
        }
        int front = Math.max(1, Math.round(w * t));
        fill(ctx, x, y, front, h, accent);
        if (front < w) {
            fill(ctx, x + front - 1, y, 1, h, mix(accent, 0xFFFFFFFF, 0.5F));
        }
    }

    /** 切角遮罩：16×16，左上三角不透明、右下透明，斜边带 8×8 超采样的 alpha。 */
    private static final Identifier CUT_TEXTURE =
            Identifier.fromNamespaceAndPath("slimefunhelperplus", "textures/gui/cut_corner.png");

    private static Boolean cutTextureReady;

    private static boolean cutTextureAvailable() {
        if (cutTextureReady == null) {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                cutTextureReady = minecraft != null
                        && minecraft.getResourceManager() != null
                        && minecraft.getResourceManager().getResource(CUT_TEXTURE).isPresent();
            } catch (Throwable e) {
                cutTextureReady = false;
            }
        }
        return cutTextureReady;
    }

    /**
     * 切一个 45° 角：可点件的形状标记。
     *
     * <p>斜边用一张带 alpha 的遮罩贴图贴上去，GPU 采样出来是连续斜边；
     * 纯矩形 fill 拼阶梯在 GUI scale 2/3 下会看到明显的台阶。
     * 另外三个角靠矩阵镜像同一张图，不额外占资源；贴图缺失时退回阶梯，功能不受影响。
     */
    public static void cutFill(VDrawContext ctx, int x, int y, int w, int h, int color, int cut) {
        cutFill(ctx, x, y, w, h, color, cut, Corner.BR);
    }

    public static void cutFill(VDrawContext ctx, int x, int y, int w, int h, int color, int cut, Corner corner) {
        int c = Math.max(0, Math.min(cut, Math.min(w, h)));
        if (c == 0) {
            fill(ctx, x, y, w, h, color);
            return;
        }
        switch (corner) {
            case BR -> {
                fill(ctx, x, y, w, h - c, color);
                fill(ctx, x, y + h - c, w - c, c, color);
                if (!drawCornerMask(ctx, x + w - c, y + h - c, c, color, corner)) {
                    for (int i = 0; i < c; i++) {
                        fill(ctx, x, y + h - c + i, w - 1 - i, 1, color);
                    }
                }
            }
            case BL -> {
                fill(ctx, x, y, w, h - c, color);
                fill(ctx, x + c, y + h - c, w - c, c, color);
                if (!drawCornerMask(ctx, x, y + h - c, c, color, corner)) {
                    for (int i = 0; i < c; i++) {
                        fill(ctx, x + 1 + i, y + h - c + i, w - 1 - i, 1, color);
                    }
                }
            }
            case TR -> {
                fill(ctx, x, y + c, w, h - c, color);
                fill(ctx, x, y, w - c, c, color);
                if (!drawCornerMask(ctx, x + w - c, y, c, color, corner)) {
                    for (int i = 0; i < c; i++) {
                        fill(ctx, x, y + i, w - 1 - i, 1, color);
                    }
                }
            }
            case TL -> {
                fill(ctx, x, y + c, w, h - c, color);
                fill(ctx, x + c, y, w - c, c, color);
                if (!drawCornerMask(ctx, x, y, c, color, corner)) {
                    for (int i = 0; i < c; i++) {
                        fill(ctx, x + 1 + i, y + i, w - 1 - i, 1, color);
                    }
                }
            }
        }
    }

    /** 画一个 GUI 图标：按精灵图集取图，白图靠着色上色，画完立刻复位。 */
    public static void icon(VDrawContext ctx, Identifier sprite, int x, int y, int size, int color) {
        float a = ((color >>> 24) & 0xFF) / 255.0F;
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        ctx.setShaderColor(r, g, b, a);
        try {
            ctx.drawGuiTexture(sprite, x, y, size, size);
        } finally {
            ctx.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * 斜边描边遮罩：每种切角尺寸都有自己的带宽（{@code 16/c}），
     * 缩放后才是 1px——共用一张 1px 带的话，切角越小越糊。
     */
    private static Identifier ringFor(int cut) {
        if (cut >= 12) {
            return Identifier.fromNamespaceAndPath("slimefunhelperplus", "textures/gui/cut_ring_12.png");
        }
        if (cut >= 4) {
            return Identifier.fromNamespaceAndPath("slimefunhelperplus", "textures/gui/cut_ring_4.png");
        }
        return Identifier.fromNamespaceAndPath("slimefunhelperplus", "textures/gui/cut_ring_3.png");
    }

    private static final Map<Identifier, Boolean> RING_CACHE = new HashMap<>();

    private static boolean drawRingMask(VDrawContext ctx, int x, int y, int size, int color, int cut, Corner corner) {
        Identifier sprite = ringFor(cut);
        Boolean available = RING_CACHE.get(sprite);
        if (available == null) {
            try {
                available = Minecraft.getInstance()
                        .getResourceManager()
                        .getResource(sprite)
                        .isPresent();
            } catch (Throwable e) {
                available = false;
            }
            RING_CACHE.put(sprite, available);
        }
        if (!available) {
            return false;
        }
        shade(ctx, color, () -> drawMirrored(ctx, sprite, x, y, size, corner));
        return true;
    }

    private static boolean drawCornerMask(VDrawContext ctx, int x, int y, int size, int color, Corner corner) {
        if (!cutTextureAvailable()) {
            return false;
        }
        shade(ctx, color, () -> drawMirrored(ctx, CUT_TEXTURE, x, y, size, corner));
        return true;
    }

    /**
     * 贴图是按「右下切角」画的，其余三个角翻 UV 采样，省掉三份资源。
     *
     * <p>不能靠负缩放镜像：那会把四边形的绕序翻过来，带剔除的 GUI 管线会直接把它丢掉
     * （表现就是切角处留一个方块缺口）。只改纹理坐标，几何始终是轴对齐的正向四边形。
     */
    private static void drawMirrored(VDrawContext ctx, Identifier sprite, int x, int y, int size, Corner corner) {
        boolean flipX = corner == Corner.BL || corner == Corner.TL;
        boolean flipY = corner == Corner.TR || corner == Corner.TL;
        float u1 = flipX ? 1.0F : 0.0F;
        float u2 = flipX ? 0.0F : 1.0F;
        float v1 = flipY ? 1.0F : 0.0F;
        float v2 = flipY ? 0.0F : 1.0F;
        ctx.drawTexturedQuad(sprite, x, x + size, y, y + size, 0, u1, u2, v1, v2);
    }

    private static void shade(VDrawContext ctx, int color, Runnable draw) {
        float a = ((color >>> 24) & 0xFF) / 255.0F;
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        ctx.setShaderColor(r, g, b, a);
        try {
            draw.run();
        } finally {
            ctx.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** 切角的空心框。 */
    public static void cutOutline(VDrawContext ctx, int x, int y, int w, int h, int color, int cut) {
        cutOutline(ctx, x, y, w, h, color, cut, Corner.BR);
    }

    public static void cutOutline(VDrawContext ctx, int x, int y, int w, int h, int color, int cut, Corner corner) {
        int c = Math.max(0, Math.min(cut, Math.min(w, h)));
        if (c == 0) {
            fill(ctx, x, y, w, 1, color);
            fill(ctx, x, y + h - 1, w, 1, color);
            fill(ctx, x, y, 1, h, color);
            fill(ctx, x + w - 1, y, 1, h, color);
            return;
        }
        switch (corner) {
            case BR -> {
                fill(ctx, x, y, w, 1, color);
                fill(ctx, x, y, 1, h, color);
                fill(ctx, x + w - 1, y, 1, h - c, color);
                fill(ctx, x, y + h - 1, w - c, 1, color);
                if (!drawRingMask(ctx, x + w - c, y + h - c, c, color, c, corner)) {
                    staircase(ctx, x + w - c, y + h - c, c, color, corner);
                }
            }
            case BL -> {
                fill(ctx, x, y, w, 1, color);
                fill(ctx, x + w - 1, y, 1, h, color);
                fill(ctx, x, y, 1, h - c, color);
                fill(ctx, x + c, y + h - 1, w - c, 1, color);
                if (!drawRingMask(ctx, x, y + h - c, c, color, c, corner)) {
                    staircase(ctx, x, y + h - c, c, color, corner);
                }
            }
            case TR -> {
                fill(ctx, x, y + c, 1, h - c, color);
                fill(ctx, x + w - 1, y, 1, h, color);
                fill(ctx, x, y + h - 1, w, 1, color);
                fill(ctx, x, y, w - c, 1, color);
                if (!drawRingMask(ctx, x + w - c, y, c, color, c, corner)) {
                    staircase(ctx, x + w - c, y, c, color, corner);
                }
            }
            case TL -> {
                fill(ctx, x + w - 1, y, 1, h, color);
                fill(ctx, x, y + h - 1, w, 1, color);
                fill(ctx, x, y + c, 1, h - c, color);
                fill(ctx, x + c, y, w - c, 1, color);
                if (!drawRingMask(ctx, x, y, c, color, c, corner)) {
                    staircase(ctx, x, y, c, color, corner);
                }
            }
        }
    }

    /** 没有遮罩时退化的斜边：逐格 1px，形状仍然对。 */
    private static void staircase(VDrawContext ctx, int x, int y, int size, int color, Corner corner) {
        int faded = (((int) (((color >>> 24) & 0xFF) * 0.72F)) << 24) | (color & 0x00FFFFFF);
        for (int i = 0; i < size; i++) {
            switch (corner) {
                case BR -> fill(ctx, x + i, y + size - 1 - i, 1, 1, faded);
                case BL -> fill(ctx, x + size - 1 - i, y + size - 1 - i, 1, 1, faded);
                case TR -> fill(ctx, x + i, y + i, 1, 1, faded);
                case TL -> fill(ctx, x + size - 1 - i, y + i, 1, 1, faded);
            }
        }
    }

    /** 虚线框：表示"还活着但没生效"这类后台状态。 */
    public static void dashedOutline(VDrawContext ctx, int x, int y, int w, int h, int color) {
        for (int i = 0; i < w; i += 3) {
            int seg = Math.min(2, w - i);
            fill(ctx, x + i, y, seg, 1, color);
            fill(ctx, x + i, y + h - 1, seg, 1, color);
        }
        for (int i = 0; i < h; i += 3) {
            int seg = Math.min(2, h - i);
            fill(ctx, x, y + i, 1, seg, color);
            fill(ctx, x + w - 1, y + i, 1, seg, color);
        }
    }

    /** 1px 细线分区。 */
    public static void rule(VDrawContext ctx, int x, int y, int w, int color) {
        fill(ctx, x, y, w, 1, color);
    }

    /**
     * 细滚动条：1px 轨道 + 3px 滑柄。
     * 内容装不下时才出现，长度按可见比例算，短于一屏时也不至于细成一条线。
     */
    public static void scrollbar(
            VDrawContext ctx, int x, int y, int h, int offset, int total, int visible, int track, int thumb) {
        if (total <= visible || h <= 8) {
            return;
        }
        fill(ctx, x, y, 1, h, track);
        int length = Math.max(12, (int) ((long) h * visible / total));
        int span = h - length;
        int maxOffset = Math.max(1, total - visible);
        int py = y + (int) ((long) span * Math.max(0, Math.min(maxOffset, offset)) / maxOffset);
        fill(ctx, x - 1, py, 3, length, thumb);
    }

    /**
     * 说明框：白底细框 + 左上黄旗。
     *
     * <p>不可点，所以是直角；框体固定接近不透明，不跟界面透明度一起淡，否则字会糊在背景里。
     */
    public static void tooltip(
            VDrawContext ctx, Palette palette, List<String> lines, int mouseX, int mouseY, int screenW, int screenH) {
        if (lines.isEmpty()) {
            return;
        }
        int rowH = lineH() + 2;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, textWidth(line));
        }
        width += 16;
        int height = lines.size() * rowH + 10;
        int x = Math.max(8, Math.min(screenW - width - 8, mouseX + 12));
        int y = Math.max(8, Math.min(screenH - height - 8, mouseY + 12));

        int panel = 0xF0000000 | (palette.panel() & 0x00FFFFFF);
        fill(ctx, x, y, width, height, panel);
        fill(ctx, x, y, 3, height, SIGNAL);
        fill(ctx, x, y, width, 1, palette.ink());
        fill(ctx, x, y + height - 1, width, 1, palette.ink());
        fill(ctx, x + width - 1, y, 1, height, palette.ink());
        int ty = y + 5;
        for (String line : lines) {
            text(ctx, line, x + 9, ty, palette.ink());
            ty += rowH;
        }
    }

    // ------------------------------------------------------------------ 文字

    public static TextDrawer font() {
        return TextRenderers.get();
    }

    /** 当前生效字体的行高。用户可能换了自定义字体，写死 9 会让两行字贴在一起。 */
    public static int lineH() {
        return Math.max(9, font().fontHeight());
    }

    public static int textWidth(String text) {
        return font().getWidth(text);
    }

    public static void text(VDrawContext ctx, String text, int x, int y, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ctx.drawText(font(), text, x, y, color, false);
    }

    /**
     * 拉开字距的拉丁小字：标签与模块名之间的层次感主要靠它，换成别的外观时也是通用手段。
     * 中文字距固定为 0，只有全大写的西文才加。
     */
    public static int trackedWidth(String text, int tracking) {
        TextDrawer d = font();
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            w += d.getWidth(String.valueOf(text.charAt(i))) + tracking;
        }
        return Math.max(0, w - tracking);
    }

    public static void tracked(VDrawContext ctx, String text, int x, int y, int color, int tracking) {
        TextDrawer d = font();
        int cx = x;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            ctx.drawText(d, ch, cx, y, color, false);
            cx += d.getWidth(ch) + tracking;
        }
    }

    /**
     * 宽体拉丁：把字形横向拉开再画，宽度按放大后的实际像素算。
     * 界面字体本身没有宽度轴，靠这一步做出"宽、几何、大写"的观感。
     */
    public static int wideWidth(String text, float stretch) {
        return Math.round(textWidth(text) * stretch);
    }

    public static int trackedWideWidth(String text, int tracking, float stretch) {
        return Math.round(trackedWidth(text, tracking) * stretch);
    }

    /** 眉标与字标：宽体 + 字距一起上，拉宽的英文才是这套界面的辨识度。 */
    public static void trackedWide(
            VDrawContext ctx, String text, int x, int y, int color, int tracking, float stretch) {
        ctx.getMatrices().pushMatrix();
        try {
            ctx.getMatrices().translate((float) x, 0.0F);
            ctx.getMatrices().scale(stretch, 1.0F);
            tracked(ctx, text, 0, y, color, tracking);
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    public static void wide(VDrawContext ctx, String text, int x, int y, int color, float stretch) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ctx.getMatrices().pushMatrix();
        try {
            ctx.getMatrices().translate((float) x, 0.0F);
            ctx.getMatrices().scale(stretch, 1.0F);
            ctx.drawText(font(), text, 0, y, color, false);
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    /** 把一段中文按像素宽折行；中英混排时按字符宽度逐个累加。 */
    public static List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return out;
        }
        StringBuilder line = new StringBuilder();
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.add(line.toString());
                line.setLength(0);
                w = 0;
                continue;
            }
            int cw = textWidth(String.valueOf(c));
            if (w + cw > maxWidth && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                w = 0;
            }
            line.append(c);
            w += cw;
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    public static String ellipsis(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (textWidth(text) <= maxWidth) {
            return text;
        }
        String dots = "...";
        int limit = maxWidth - textWidth(dots);
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            int cw = textWidth(String.valueOf(text.charAt(i)));
            if (w + cw > limit) {
                break;
            }
            sb.append(text.charAt(i));
            w += cw;
        }
        return sb + dots;
    }
}
