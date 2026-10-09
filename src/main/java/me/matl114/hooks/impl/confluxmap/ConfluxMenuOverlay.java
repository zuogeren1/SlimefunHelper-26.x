package me.matl114.hooks.impl.confluxmap;

import java.awt.Rectangle;
import java.util.List;
import net.minecraft.client.gui.Font;

/**
 * ConfluxMapHelper 在地图右键菜单正下方追加的那块面板。
 *
 * <p>这个类只接收 {@code GuiDraw} 的“绘制能力”（一个 drawText 回调）与基本类型，
 * 因此不需要任何 conflux-map 的类型，也就不受它内部结构变化影响；
 * conflux-map 侧的读写全部由 mixin 在自己的注入体里完成。
 */
public class ConfluxMenuOverlay {
    /** 与 conflux-map 的位置菜单保持同宽，视觉上连成一体 */
    public static final int PANEL_WIDTH = 140;
    public static final int PANEL_PADDING = 3;
    /** 单行高度，比正文字体略大，保证两次点击不会误触 */
    public static final int LINE_HEIGHT = 12;
    /** 面板与 conflux 面板之间的缝隙 */
    public static final int PANEL_GAP = 2;
    /** 距离屏幕边缘的最小留白 */
    public static final int SCREEN_MARGIN = 4;
    /** 最多条目数，对应数字快捷键 6~9 */
    public static final int MAX_ENTRIES = 4;

    /** 与他们面板同款配色：背景 / 边框 / 快捷键提示 */
    public static final int PANEL_BACKGROUND = 0xF0181822;
    public static final int PANEL_BORDER = 0xFF9A9AA8;
    public static final int HOTKEY_COLOR = 0xFF55FF55;
    public static final int TEXT_COLOR = 0xFFFFFFFF;

    /** 我们的数字快捷键：6 7 8 9，与他们的 1~5 错开 */
    public static final int[] HOTKEY_KEYS = {54, 55, 56, 57};
    public static final String[] HOTKEY_LABELS = {"6", "7", "8", "9"};

    public static int hotkeyIndex(int key) {
        for (int i = 0; i < HOTKEY_KEYS.length; ++i) {
            if (HOTKEY_KEYS[i] == key) {
                return i;
            }
        }
        return -1;
    }

    /** 绘制一段文字，参数与 {@code GuiDraw#drawTextWithShadow(Font, String, float, float, int)} 对齐 */
    public interface TextDrawer {
        void draw(String text, float x, float y, int color);
    }

    /**
     * 计算面板矩形。
     *
     * @param screenWidth  当前屏幕宽
     * @param screenHeight 当前屏幕高
     * @param boundsX      conflux 位置菜单的 x
     * @param boundsY      conflux 位置菜单的 y
     * @param boundsHeight conflux 位置菜单的高
     * @param entryCount   条目数
     * @return 面板矩形；条目为空时返回 null（调用方据此隐藏面板）
     */
    public static Rectangle computePanel(
            int screenWidth, int screenHeight, int boundsX, int boundsY, int boundsHeight, int entryCount) {
        if (entryCount <= 0) {
            return null;
        }
        int lines = Math.min(entryCount, MAX_ENTRIES);
        int height = PANEL_PADDING * 2 + lines * LINE_HEIGHT;

        int x = boundsX;
        // 优先贴在他们的面板下方，放不下才改放到上方
        int y = boundsY + boundsHeight + PANEL_GAP;
        if (y + height > screenHeight - SCREEN_MARGIN) {
            int above = boundsY - PANEL_GAP - height;
            if (above >= SCREEN_MARGIN) {
                y = above;
            }
        }
        // 无论走哪条分支，最后都把矩形夹回屏幕内
        x = clamp(x, SCREEN_MARGIN, screenWidth - PANEL_WIDTH - SCREEN_MARGIN);
        y = clamp(y, SCREEN_MARGIN, screenHeight - height - SCREEN_MARGIN);
        return new Rectangle(x, y, PANEL_WIDTH, height);
    }

    public static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static boolean contains(Rectangle rect, double mouseX, double mouseY) {
        return rect != null
                && mouseX >= rect.x
                && mouseX < rect.x + rect.width
                && mouseY >= rect.y
                && mouseY < rect.y + rect.height;
    }

    /** 画背景 + 边框，配色与 conflux 的位置菜单完全一致 */
    public static void drawPanel(Rectangle rect, PanelFiller filler) {
        if (rect == null) {
            return;
        }
        int left = rect.x;
        int top = rect.y;
        int right = rect.x + rect.width;
        int bottom = rect.y + rect.height;
        filler.fill(left, top, right, bottom, PANEL_BACKGROUND);
        filler.fill(left, top, right, top + 1, PANEL_BORDER);
        filler.fill(left, bottom - 1, right, bottom, PANEL_BORDER);
        filler.fill(left, top + 1, left + 1, bottom - 1, PANEL_BORDER);
        filler.fill(right - 1, top + 1, right, bottom - 1, PANEL_BORDER);
    }

    /** 画一块矩形，参数与 {@code GuiDraw#fill(int, int, int, int, int)} 对齐 */
    public interface PanelFiller {
        void fill(int x1, int y1, int x2, int y2, int color);
    }

    /** 画条目：左侧文字（过长按像素宽度截断），右侧数字快捷键提示 */
    public static void drawEntries(
            Rectangle rect, List<ConfluxMenuContext> entries, Font font, TextDrawer drawer) {
        if (rect == null || entries == null || entries.isEmpty()) {
            return;
        }
        int lines = Math.min(entries.size(), MAX_ENTRIES);
        int textLeft = rect.x + PANEL_PADDING;
        int textRight = rect.x + rect.width - PANEL_PADDING;
        for (int i = 0; i < lines; ++i) {
            ConfluxMenuContext entry = entries.get(i);
            int lineTop = rect.y + PANEL_PADDING + i * LINE_HEIGHT;
            float textY = lineTop + Math.max(0, (LINE_HEIGHT - font.lineHeight) / 2.0F);

            String hotkey = i < HOTKEY_LABELS.length ? HOTKEY_LABELS[i] : "";
            int hotkeyWidth = hotkey.isEmpty() ? 0 : font.width(hotkey) + 2;
            String label = entry.label() == null ? "" : entry.label();
            int available = Math.max(0, textRight - textLeft - hotkeyWidth);
            String shown = label;
            if (available > 0 && font.width(shown) > available) {
                // 优先用原版按像素宽度的截断（26.2 / 26.1.2 都有），失败时退回按字符数截断
                try {
                    shown = font.plainSubstrByWidth(label, available);
                } catch (Throwable ignored) {
                    shown = label.substring(0, Math.min(label.length(), Math.max(0, available / 6)));
                }
            }
            drawer.draw(shown, textLeft, textY, TEXT_COLOR);
            if (!hotkey.isEmpty()) {
                drawer.draw(hotkey, textRight - font.width(hotkey), textY, HOTKEY_COLOR);
            }
        }
    }
}
