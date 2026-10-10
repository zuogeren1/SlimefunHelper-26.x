package me.matl114.hooks.impl.confluxmap;

import net.minecraft.client.gui.Font;

/**
 * ConfluxMapHelper 在 conflux-map 位置菜单里那几行的<b>几何与绘制</b>。
 *
 * <p>和旧版最大的区别：不再自己另起一块面板。条目已经并进 conflux 自己的按钮列表
 * （见 {@code ConfluxMapScreenMixin} 对 {@code locationMenuButtonSpecs} 的注入），
 * 所以这里的活只剩三件：
 * <ol>
 *   <li>按 conflux <b>自己的</b>几何把面板矩形往下补齐，让多出来的行落在同一块面板里
 *       （不留缝、不另开框、同宽同左边界）；</li>
 *   <li>提供命中判定 —— 我们用同一个按钮矩形公式算行位置，点击与数字键都在原版处理之前拦下；</li>
 *   <li>把 label 按像素截断（{@link #labelMaxWidth} / {@link #truncateLabel}），以及补画
 *       conflux <b>没画到</b>的快捷键数字（{@link #shouldDrawHotkeyDigit} / {@link #drawHotkeyDigits}）。
 *       行内文字<b>不在这里画</b>：我们把截断后的文本当作 {@code ButtonSpec.labelKey} 交给他们，
 *       他们会用 {@code Texts.translatable(labelKey)} 让原生按钮把文字居中渲染出来；
 *       在这里再画一遍就是用户看到的重影。</li>
 * </ol>
 *
 * <p>几何常量直接来自 conflux 的字节码（0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2 三份 jar 完全一致）：
 * <pre>
 *   FullscreenMapLocationMenu.Bounds.buttonX()     = x + 3
 *   FullscreenMapLocationMenu.Bounds.buttonY(i)    = y + 3 + 22 * i      // BUTTON_HEIGHT 20 + BUTTON_GAP 2
 *   FullscreenMapLocationMenu.Bounds.buttonWidth() = width - 6
 *   FullscreenMapLocationMenu.place(...)           = height 6 + max(0, n-1)*2 + n*20
 *   FullscreenMapLocationMenu.drawPanel(...)       = fill(bg) + 上下左右各 1px 边框
 * </pre>
 * 面板宽恒为 140（受屏幕宽度夹取），所以补齐出来的延伸块与他们的面板天然同宽。
 */
public class ConfluxMenuOverlay {
    /** conflux 面板内边距（Bounds.buttonX 的 +3、place 的 6 = 3*2） */
    public static final int PANEL_PADDING = 3;
    /** conflux 按钮高度（addLocationMenuButtons 里写死的 20） */
    public static final int BUTTON_HEIGHT = 20;
    /** conflux 按钮行距 = BUTTON_HEIGHT + BUTTON_GAP(2) */
    public static final int BUTTON_PITCH = 22;
    /** 边框宽度，与 drawPanel 一致 */
    public static final int BORDER = 1;
    /** 数字提示与面板右边界的间距（drawHotkeyHints 里写死的 3 + 4） */
    public static final int HOTKEY_HINT_GAP = 7;
    /** 原版按钮里文字的左右内边距（{@code AbstractButton.TEXT_MARGIN = 2}，26.2 / 26.1.2 相同） */
    public static final int BUTTON_TEXT_MARGIN = 2;
    /** 居中文字右缘与数字提示左缘之间至少留出的间隔 */
    public static final int HOTKEY_TEXT_GAP = 2;
    /** 最多条目数，对应我们可能占到的行数 */
    public static final int MAX_ENTRIES = 4;

    /** 与他们面板同款配色：背景 / 边框 / 快捷键提示 */
    public static final int PANEL_BACKGROUND = 0xF0181822;
    public static final int PANEL_BORDER = 0xFF9A9AA8;
    public static final int HOTKEY_COLOR = 0xFF55FF55;

    /** GLFW 里数字 1 的键码（'1'），第 n 个数字键 = DIGIT_KEY_1 + n - 1 */
    public static final int DIGIT_KEY_1 = 49;

    /**
     * 我们的第 index 行该显示 / 该用哪个数字键。
     *
     * <p>编号接在 conflux 自己的条目之后：0.1.9 的 {@code FullscreenMapLocationMenu.drawHotkeyHints}
     * 是「第 i 个按钮画 {@code Integer.toString(i + 1)}」，我们的行是列表的第
     * {@code nativeCount + index} 个，所以这个槽位本来就该显示 {@code nativeCount + index + 1}。
     * 这样他们的槽位、我们显示的数字、以及实际按键三者永远一致；
     * 0.1.7 没有这套提示，这组数字只由我们自己画。
     *
     * <p>注意：0.1.9 的 {@code HOTKEY_KEYS} 只有 5 个键（1~5），所以只有列表下标 < 5 的槽位他们会画，
     * 其余由我们补 —— 判据是 {@link #shouldDrawHotkeyDigit}，同色同位置，但绝不重画他们已经画过的。
     */
    public static int hotkeyDigit(int nativeCount, int index) {
        return nativeSlotCount(nativeCount) + index + 1;
    }

    /** 原生条目占掉的数字槽位数（至少 1，避免退化到 0 号槽） */
    public static int nativeSlotCount(int nativeCount) {
        return Math.max(1, nativeCount);
    }

    /** 第 index 行显示的快捷键数字文本 */
    public static String hotkeyLabel(int nativeCount, int index) {
        if (index < 0 || index >= MAX_ENTRIES) {
            return "";
        }
        return Integer.toString(hotkeyDigit(nativeCount, index));
    }

    /** 按键 -> 我们的行号；不是我们的键返回 -1 */
    public static int hotkeyIndex(int nativeCount, int key) {
        int digit = key - DIGIT_KEY_1 + 1;
        if (digit < 1 || digit > 9) {
            return -1;
        }
        int index = digit - nativeSlotCount(nativeCount) - 1;
        return index >= 0 && index < MAX_ENTRIES ? index : -1;
    }

    /** 绘制一段文字，参数与 {@code GuiDraw#drawTextWithShadow(Font, String, float, float, int)} 对齐 */
    public interface TextDrawer {
        void draw(String text, float x, float y, int color);
    }

    /** 画一块矩形，参数与 {@code GuiDraw#fill(int, int, int, int, int)} 对齐 */
    public interface PanelFiller {
        void fill(int x1, int y1, int x2, int y2, int color);
    }

    /** 画面板背景 + 1px 边框，与 conflux 的 {@code drawPanel} 同一个画法 */
    private static void drawBox(int left, int top, int right, int bottom, PanelFiller filler) {
        filler.fill(left, top, right, bottom, PANEL_BACKGROUND);
        filler.fill(left, top, right, top + BORDER, PANEL_BORDER);
        filler.fill(left, bottom - BORDER, right, bottom, PANEL_BORDER);
        filler.fill(left, top + BORDER, left + BORDER, bottom - BORDER, PANEL_BORDER);
        filler.fill(right - BORDER, top + BORDER, right, bottom - BORDER, PANEL_BORDER);
    }

    /**
     * 第 index 行在屏幕上的 Y（和 conflux 的 {@code Bounds.buttonY(index)} 完全同式）。
     *
     * @param boundsY conflux 面板的 y（{@code Bounds.y()}）
     * @param index   行号，接在他们原生条目之后
     */
    public static int rowY(int boundsY, int index) {
        return boundsY + PANEL_PADDING + BUTTON_PITCH * index;
    }

    /** 第 index 行的左边界（= {@code Bounds.buttonX()}） */
    public static int rowX(int boundsX) {
        return boundsX + PANEL_PADDING;
    }

    /** 行宽（= {@code Bounds.buttonWidth()}） */
    public static int rowWidth(int panelWidth) {
        return panelWidth - PANEL_PADDING * 2;
    }

    /** 面板要装下 totalRows 行时，内容真正的底边（含下边框那 1px 之外的内边距） */
    public static int contentBottom(int boundsY, int totalRows) {
        if (totalRows <= 0) {
            return boundsY;
        }
        return rowY(boundsY, totalRows - 1) + BUTTON_HEIGHT + PANEL_PADDING;
    }

    /**
     * 面板补齐后真正的底边。
     *
     * <p>两个下界取大值：
     * <ul>
     *   <li>{@code boundsY + nativeHeight} —— 他们自己算出来的面板底边；</li>
     *   <li>{@code contentBottom(boundsY, nativeCount)} —— 他们**最后一个原生按钮**的下沿 + 内边距。
     *       0.1.9 的 {@code place(...)} 用的是「已启用 action 数」而按钮列表是「全部 action」，
     *       两者会差 2px 左右（他们的既有现象）；不把这个缝一起盖掉，我们的行和他们的行之间就会
     *       露出一条背景色 —— 正是用户报的「有缝隙」。</li>
     * </ul>
     */
    public static int extendedPanelBottom(int boundsY, int nativeHeight, int nativeCount, int ourRows) {
        int current = boundsY + nativeHeight;
        if (nativeCount > 0) {
            current = Math.max(current, contentBottom(boundsY, nativeCount));
        }
        if (ourRows <= 0) {
            return current;
        }
        return Math.max(current, contentBottom(boundsY, nativeCount + ourRows));
    }

    /**
     * 把 conflux 面板补齐到能装下我们那几行。
     *
     * <p>整块面板其实只是「一个背景 + 一圈 1px 边框」，所以补法很简单：
     * 从他们<b>最后一个原生按钮的上沿</b>开始，一直重画到新的底边（含下边框）。
     * 这一段里只有原生按钮的下半截和我们的行，而按钮本身是在这之后由原版控件画上去的，
     * 所以盖掉的只是背景与边框，不会糊掉任何按钮 —— 视觉上仍然是同一块面板，且不会留缝。
     *
     * @return true 表示画了延伸块；false 表示本来就够高，无需处理
     */
    public static boolean drawPanelExtension(
            int boundsX,
            int boundsY,
            int panelWidth,
            int nativeHeight,
            int nativeCount,
            int ourRows,
            PanelFiller filler) {
        if (filler == null || panelWidth <= 0 || nativeHeight <= 0 || ourRows <= 0) {
            return false;
        }
        int neededBottom = extendedPanelBottom(boundsY, nativeHeight, nativeCount, ourRows);
        if (neededBottom <= boundsY + nativeHeight) {
            return false;
        }
        int start = boundsY + BORDER;
        if (nativeCount > 0) {
            start = Math.max(start, rowY(boundsY, nativeCount - 1));
        }
        drawBox(boundsX, start, boundsX + panelWidth, neededBottom, filler);
        return true;
    }

    /** 我们的第 index 行是否被鼠标点中 */
    public static boolean isOverRow(
            int boundsX, int boundsY, int panelWidth, int nativeCount, int index, double mouseX, double mouseY) {
        int rowIndex = nativeCount + index;
        int left = rowX(boundsX);
        int right = left + rowWidth(panelWidth);
        int top = rowY(boundsY, rowIndex);
        int bottom = top + BUTTON_HEIGHT;
        return mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
    }

    /**
     * 鼠标是否落在「我们补出来的那块面板区域」里（含行间缝隙）。
     *
     * <p>这一块在他们 {@code Bounds.contains(...)} 之外，所以必须在我们的 HEAD 注入里先拦下来，
     * 否则他们的 {@code mouseClicked} 会走到「点在面板外 → dismissLocationMenu」。
     */
    public static boolean isOverExtraRows(
            int boundsX, int boundsY, int panelWidth, int nativeHeight, int nativeCount, int rows,
            double mouseX, double mouseY) {
        if (rows <= 0 || panelWidth <= 0 || nativeCount <= 0) {
            return false;
        }
        // 顶部从“他们面板底边”起算：0.1.9 的 place() 按原生条目数算高度，可能会比最后一个
        // 原生按钮的下沿高 2px（他们自己的既有现象），所以实际可点的起点取两者较小值。
        int top = Math.min(boundsY + nativeHeight, rowY(boundsY, nativeCount));
        int bottom = extendedPanelBottom(boundsY, nativeHeight, nativeCount, rows);
        return mouseX >= boundsX && mouseX < boundsX + panelWidth && mouseY >= top && mouseY < bottom;
    }

    /**
     * 数字提示的左边界 x。
     *
     * <p>与他们的 {@code drawHotkeyHints} 完全同式：
     * {@code bounds.x() + bounds.width() - 3 - 4 - font.width(label)}（3 = 面板内边距，4 是他们写死的间隔）。
     */
    public static int hotkeyHintLeft(int panelRight, int digitWidth) {
        return panelRight - PANEL_PADDING - HOTKEY_HINT_GAP - digitWidth;
    }

    /**
     * 我们第 index 行的数字，该不该由<b>我们</b>画。
     *
     * <p>0.1.9 的 {@code drawHotkeyHints} 遍历他们的 spec 列表：对下标
     * {@code index < HOTKEY_KEYS.length(=5)} 的槽位画一个绿色数字，遇到第一个越界下标就直接
     * {@code return}。我们的行是列表的第 {@code nativeCount + index} 个，所以只有落在这个槽位数
     * <b>之外</b>的行才需要补画 —— 落在里面的那些他们已经画了，我们再画一遍就是同一个数字叠两次。
     *
     * <p>0.1.7 完全没有这套提示（{@code hotkeyLabel} / {@code drawHotkeyHints} 都不存在，
     * 探测出的 {@code nativeHotkeySlots} = 0），于是每一行都由我们画。
     *
     * @param nativeHotkeySlots conflux 自己会画数字的槽位数（0 表示他们没有这套提示）
     */
    public static boolean shouldDrawHotkeyDigit(int nativeCount, int index, int nativeHotkeySlots) {
        if (index < 0) {
            return false;
        }
        return nativeCount + index >= nativeHotkeySlots;
    }

    /** 我们要补画的数字行数（日志与实机验收用） */
    public static int ownDigitRowCount(int nativeCount, int rows, int nativeHotkeySlots) {
        int count = 0;
        for (int i = 0; i < rows; ++i) {
            if (shouldDrawHotkeyDigit(nativeCount, i, nativeHotkeySlots)) {
                ++count;
            }
        }
        return count;
    }

    /**
     * 我们第 index 行的 label 交给原生按钮居中渲染时，最多能占多少像素。
     *
     * <p>两个上界取较小值：
     * <ol>
     *   <li>{@code rowWidth - 2 * BUTTON_TEXT_MARGIN} —— 不越出按钮自己的文字内边距
     *       （{@code AbstractButton.extractDefaultLabel} 给了 2px，超宽时它会滚动/裁切）；</li>
     *   <li>{@code 2 * (数字提示左边界 - 按钮中心) - HOTKEY_TEXT_GAP} —— 文字是<b>居中</b>画的，
     *       而数字提示右对齐画在面板右边界附近，居中之后文字右缘不能压到那个数字上。</li>
     * </ol>
     *
     * <p>拿不到字体或面板宽非法时返回 0，调用方按“不截断”处理。
     */
    public static int labelMaxWidth(int boundsX, int panelWidth, int nativeCount, int index, Font font) {
        if (font == null || panelWidth <= 0 || index < 0) {
            return 0;
        }
        int rowW = rowWidth(panelWidth);
        int byPadding = rowW - BUTTON_TEXT_MARGIN * 2;
        String hotkey = hotkeyLabel(nativeCount, index);
        if (hotkey.isEmpty()) {
            return Math.max(0, byPadding);
        }
        int center = rowX(boundsX) + rowW / 2;
        int byDigit = 2 * (hotkeyHintLeft(boundsX + panelWidth, font.width(hotkey)) - center) - HOTKEY_TEXT_GAP;
        return Math.max(0, Math.min(byPadding, byDigit));
    }

    /**
     * 把 label 截到 maxWidth 像素以内（原版 {@code Font#plainSubstrByWidth(String,int)}，26.2 / 26.1.2 都有）。
     *
     * <p>为什么必须在<b>追加 ButtonSpec 时</b>就截断：文字是原生按钮自己居中画的，
     * 那一刻的渲染我们既看不到也改不了，只能保证交出去的文本本身就装得下。
     */
    public static String truncateLabel(String label, int maxWidth, Font font) {
        if (label == null || label.isEmpty() || font == null) {
            return label == null ? "" : label;
        }
        if (maxWidth <= 0) {
            // 面板窄到一个像素都不剩（面板宽被夹到极小时才会发生）：宁可不显示文字，也不能压到数字上
            return "";
        }
        if (font.width(label) <= maxWidth) {
            return label;
        }
        try {
            return font.plainSubstrByWidth(label, maxWidth);
        } catch (Throwable ignored) {
            // 兜底：按最宽的字形（全角/中文 9px）粗估字符数 —— 宁可短一点也不能溢出
            return label.substring(0, Math.min(label.length(), Math.max(0, maxWidth / 9)));
        }
    }

    /**
     * 补画我们那几行里 conflux <b>没画</b>的快捷键数字。
     *
     * <p>行内文字<b>不在这里画</b>：原生按钮已经按我们传进去的 {@code labelKey} 把它居中渲染出来了，
     * 这里再画一遍就是重影。数字的位置/颜色与他们完全同式：
     * {@code x = 面板右边界 - 3 - 4 - 数字宽，y = buttonY(index) + (20 - 9) / 2，颜色 = 0xFF55FF55}。
     *
     * @param nativeHotkeySlots conflux 自己会画数字的槽位数（0.1.9 = 5，0.1.7 = 0）
     */
    public static void drawHotkeyDigits(
            int boundsX,
            int boundsY,
            int panelWidth,
            int nativeCount,
            int rows,
            int nativeHotkeySlots,
            Font font,
            TextDrawer drawer) {
        if (rows <= 0 || panelWidth <= 0 || font == null || drawer == null) {
            return;
        }
        int lines = Math.min(rows, MAX_ENTRIES);
        int panelRight = boundsX + panelWidth;
        for (int i = 0; i < lines; ++i) {
            if (!shouldDrawHotkeyDigit(nativeCount, i, nativeHotkeySlots)) {
                continue;
            }
            String hotkey = hotkeyLabel(nativeCount, i);
            if (hotkey.isEmpty()) {
                continue;
            }
            int lineTop = rowY(boundsY, nativeCount + i);
            int textY = lineTop + Math.max(0, (BUTTON_HEIGHT - font.lineHeight) / 2);
            drawer.draw(hotkey, hotkeyHintLeft(panelRight, font.width(hotkey)), textY, HOTKEY_COLOR);
        }
    }
}
