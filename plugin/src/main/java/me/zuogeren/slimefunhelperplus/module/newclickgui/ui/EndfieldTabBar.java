package me.zuogeren.slimefunhelperplus.module.newclickgui.ui;

import java.util.List;
import me.matl114.versioned.api.VDrawContext;

/**
 * 顶部分类条：主屏与各分类页共用的一条命令栏。
 *
 * <p>分类就在同一屏里切，所以这条栏由每个页面自己画、自己命中：当前分类用强调色块，
 * 悬停的分类在底边擦一条线；右侧是深浅切换与 Esc 提示。窄窗口下分类换成两行，
 * 命令栏跟着变高，不会把页签画到右侧提示上。
 */
public final class EndfieldTabBar {
    /** 显示顺序；第二列是切页用的键，与 {@code pages.EndfieldPages} 的注册键一一对应。 */
    public static final String[][] TABS = {
        {"模块", "Module"},
        {"好友", "Friends"},
        {"宏", "CmdMacros"},
        {"快捷键", "Hotkeys"},
        {"基础", "BaseSettings"},
        {"GUI", "GuiSettings"},
        {"字体", "Font"},
        {"全部", "Config"},
        {"Baritone", "Baritone"},
    };

    /** 点的不是分类而是"切深浅"。 */
    public static final String THEME = "\u0000theme";

    private static final int TAB_H = 15;
    private static final int TAB_STEP = 17;
    private static final int BAR_MIN = 22;
    private static final float WIDE = 1.2F;

    private final long openStart;
    private int barH = BAR_MIN;
    private String hoverKey;
    private long hoverStart;
    /** 每个分类的左边与宽度，索引与 {@link #TABS} 对齐；-1 表示这一行没排它。 */
    private int[] slotX = new int[TABS.length];
    private int[] slotY = new int[TABS.length];
    private int[] slotW = new int[TABS.length];

    public EndfieldTabBar() {
        this(System.currentTimeMillis());
    }

    public EndfieldTabBar(long openStart) {
        this.openStart = openStart;
    }

    /** 命令栏实际高度：分类换行时变高。 */
    public int height() {
        return barH;
    }

    /** 只量高度：控件要按命令栏高度布局，所以得在绘制之前算一次。 */
    public int measure(int winW, boolean light) {
        layout(winW, light);
        return barH;
    }

    private static int brandWidth(int winW) {
        return winW >= 520 ? Ef.trackedWidth("CLICKGUI", 1) + 36 : 18;
    }

    private static int themeX(int winW, boolean light) {
        String esc = "Esc 关闭";
        String theme = light ? "深色" : "浅色";
        return winW - 8 - Ef.textWidth(esc) - 10 - Ef.textWidth(theme);
    }

    private void layout(int winW, boolean light) {
        int x = 6 + brandWidth(winW);
        int y = 4;
        int limit = winW >= 320 ? themeX(winW, light) - 6 : winW - 6;
        for (int i = 0; i < TABS.length; i++) {
            int w = Ef.textWidth(TABS[i][0]) + 10;
            if (x + w > limit) {
                x = 6;
                y += TAB_STEP;
                limit = winW - 6;
                w = Math.min(w, Math.max(20, limit - x));
            }
            slotX[i] = x;
            slotY[i] = y;
            slotW[i] = w;
            x += w + 4;
        }
        barH = Math.max(BAR_MIN, y + 19);
    }

    /** 画整条命令栏；{@code activeKey} 是这一屏自己的分类。 */
    public void paint(
            VDrawContext ctx, int x, int y, int winW, String activeKey, boolean light, int mouseX, int mouseY) {
        layout(winW, light);
        int h = barH;
        Ef.fill(ctx, x, y, winW, h, Ef.BAR_BG);
        paintTriBar(ctx, x, y, winW);

        Ef.cutFill(ctx, x + 6, y + 5, 12, 12, Ef.SIGNAL, Ef.CUT);
        Ef.text(ctx, "S", x + 9, y + 7, Ef.ON_SIGNAL);
        if (winW >= 520) {
            Ef.trackedWide(ctx, "CLICKGUI", x + 24, y + 7, Ef.BAR_INK, 1, WIDE);
            Ef.fill(ctx, x + 24 + Ef.trackedWideWidth("CLICKGUI", 1, WIDE) + 8, y + 5, 1, 12, Ef.BAR_RULE);
        }

        String hot = null;
        for (int i = 0; i < TABS.length; i++) {
            if (hitTab(i, x, y, mouseX, mouseY)) {
                hot = TABS[i][1];
            }
        }
        if (hot != null && !hot.equals(hoverKey)) {
            hoverKey = hot;
            hoverStart = System.currentTimeMillis();
        } else if (hot == null) {
            hoverKey = null;
        }

        for (int i = 0; i < TABS.length; i++) {
            String key = TABS[i][1];
            boolean active = key.equals(activeKey);
            int tx = x + slotX[i];
            int ty = y + slotY[i];
            if (active) {
                Ef.cutFill(ctx, tx, ty, slotW[i], TAB_H, Ef.SIGNAL, Ef.CUT);
                Ef.text(ctx, TABS[i][0], tx + 5, ty + 3, Ef.ON_SIGNAL);
                continue;
            }
            boolean over = key.equals(hoverKey);
            Ef.text(ctx, TABS[i][0], tx + 5, ty + 3, over ? Ef.BAR_INK : Ef.BAR_INK2);
            if (over) {
                float t = Ef.mech(Ef.progress(hoverStart, 280.0F));
                Ef.fill(ctx, tx, ty + TAB_H, Math.round(slotW[i] * t), 2, Ef.SIGNAL);
            }
        }

        if (winW < 360) {
            return;
        }
        String esc = "Esc 关闭";
        Ef.text(ctx, esc, x + winW - 8 - Ef.textWidth(esc), y + 7, Ef.BAR_INK2);
        int themeX = x + themeX(winW, light);
        Ef.fill(ctx, themeX - 6, y + 6, 1, 10, Ef.BAR_RULE);
        Ef.text(ctx, light ? "深色" : "浅色", themeX, y + 7, Ef.BAR_INK);
    }

    /** 命中分类返回它的键；命中右侧深浅切换返回 {@link #THEME}；都不是返回 null。 */
    public String click(int x, int y, int winW, String activeKey, boolean light, int mouseX, int mouseY) {
        layout(winW, light);
        for (int i = 0; i < TABS.length; i++) {
            if (hitTab(i, x, y, mouseX, mouseY)) {
                return TABS[i][1];
            }
        }
        if (winW >= 360) {
            int themeX = x + themeX(winW, light);
            if (mouseX >= themeX - 8 && mouseX <= themeX + Ef.textWidth("浅色") + 2 && mouseY <= y + BAR_MIN) {
                return THEME;
            }
        }
        return null;
    }

    private boolean hitTab(int index, int x, int y, int mouseX, int mouseY) {
        return mouseX >= x + slotX[index]
                && mouseX <= x + slotX[index] + slotW[index]
                && mouseY >= y + slotY[index]
                && mouseY <= y + slotY[index] + TAB_H;
    }

    /** 三色条扫过：切到这一屏时从左往右画出来。 */
    private void paintTriBar(VDrawContext ctx, int x, int y, int winW) {
        int drawn = Math.round(winW * Ef.ease(Ef.progress(openStart, 600.0F)));
        int third = Math.max(1, winW / 3);
        int a = Math.min(third, drawn);
        if (a > 0) {
            Ef.fill(ctx, x, y, a, 3, Ef.TRI_A);
        }
        int b = Math.min(third, drawn - third);
        if (b > 0) {
            Ef.fill(ctx, x + third, y, b, 3, Ef.SIGNAL);
        }
        int c = drawn - third * 2;
        if (c > 0) {
            Ef.fill(ctx, x + third * 2, y, c, 3, Ef.TRI_C);
        }
    }
}
