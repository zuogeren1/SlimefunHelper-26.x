package me.zuogeren.slimefunhelperplus.module.newclickgui.ui;

import java.util.ArrayList;
import java.util.List;
import me.matl114.renderer.TextRenderers;
import me.matl114.renderer.text.TextDrawer;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * 界面绘制原语：颜色常量、切角矩形、细线、字距、裁剪。
 *
 * <p>调色板集中在 {@link Palette}，几何常量（切角、行高、内边距）也都在这层。
 * 要接另一套外观时，替换 {@code palette(...)} 的返回值与这几个常量即可，
 * 上层界面只认这两者的语义（paper/panel/ink/rule/chip/trace…），不认具体色值。
 *
 * <p>全部走 {@link VDrawContext}，宿主已经替我们抹平了 26.1.2 / 26.2 / 26.3 的绘制入口差异，
 * 所以本包一行版本指令都不需要。切角走一张 alpha 遮罩贴图，斜边是连续的；贴图缺失时退回矩形阶梯。
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

    public static void fill(VDrawContext ctx, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        ctx.fill(x, y, x + w, y + h, color);
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
     * 右下切一个 45° 角：可点件的形状标记。
     *
     * <p>斜边用一张带 alpha 的遮罩贴图贴上去，GPU 采样出来是连续斜边；
     * 纯矩形 fill 拼阶梯在 GUI scale 2/3 下会看到明显的台阶。
     * 贴图缺失时退回阶梯，功能不受影响。
     */
    public static void cutFill(VDrawContext ctx, int x, int y, int w, int h, int color, int cut) {
        int c = Math.max(0, Math.min(cut, Math.min(w, h)));
        if (c == 0) {
            fill(ctx, x, y, w, h, color);
            return;
        }
        fill(ctx, x, y, w, h - c, color);
        fill(ctx, x, y + h - c, w - c, c, color);
        if (!drawCornerMask(ctx, x + w - c, y + h - c, c, color)) {
            for (int i = 0; i < c; i++) {
                fill(ctx, x, y + h - c + i, w - 1 - i, 1, color);
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

    private static final java.util.Map<Identifier, Boolean> RING_CACHE = new java.util.HashMap<>();

    private static boolean drawRingMask(VDrawContext ctx, int x, int y, int size, int color, int cut) {
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
        float a = ((color >>> 24) & 0xFF) / 255.0F;
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        ctx.setShaderColor(r, g, b, a);
        try {
            ctx.drawTexture(sprite, x, y, size, size, 0.0F, 0.0F, 16, 16, 16, 16);
        } finally {
            ctx.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return true;
    }

    private static boolean drawCornerMask(VDrawContext ctx, int x, int y, int size, int color) {
        if (!cutTextureAvailable()) {
            return false;
        }
        float a = ((color >>> 24) & 0xFF) / 255.0F;
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        ctx.setShaderColor(r, g, b, a);
        try {
            ctx.drawTexture(CUT_TEXTURE, x, y, size, size, 0.0F, 0.0F, 16, 16, 16, 16);
        } finally {
            ctx.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return true;
    }

    /** 右下切角的空心框。 */
    public static void cutOutline(VDrawContext ctx, int x, int y, int w, int h, int color, int cut) {
        int c = Math.max(0, Math.min(cut, Math.min(w, h)));
        if (c == 0) {
            fill(ctx, x, y, w, 1, color);
            fill(ctx, x, y + h - 1, w, 1, color);
            fill(ctx, x, y, 1, h, color);
            fill(ctx, x + w - 1, y, 1, h, color);
            return;
        }
        fill(ctx, x, y, w, 1, color);
        fill(ctx, x, y, 1, h, color);
        fill(ctx, x + w - 1, y, 1, h - c, color);
        fill(ctx, x, y + h - 1, w - c, 1, color);
        if (!drawRingMask(ctx, x + w - c, y + h - c, c, color, c)) {
            // 没有遮罩时退回逐格，形状仍然是对的
            int alpha = (color >>> 24) & 0xFF;
            int faded = ((int) (alpha * 0.72F) << 24) | (color & 0x00FFFFFF);
            for (int i = 0; i < c; i++) {
                fill(ctx, x + w - c + i, y + h - 1 - i, 1, 1, faded);
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
