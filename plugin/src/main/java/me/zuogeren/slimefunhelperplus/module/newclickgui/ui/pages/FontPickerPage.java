package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.matl114.gui.GenericScreen;
import me.matl114.hacks.MainTasks;
import me.matl114.hacks.modules.task.TextFont;
import me.matl114.renderer.text.LocalFontTextDrawer;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.newclickgui.NewClickGui;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.Ef;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EfWidget;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 「系统字体」选择页：把本机字体目录里的 ttf 排成一张表，点一行就写进文字模块的字体设置。
 *
 * <p>字体清单来自 {@link LocalFontTextDrawer#scanFonts()}，那个方法要遍历字体目录并逐个读 ttf 头，
 * 所以结果按静态字段缓存一次：重开这一页、改窗口大小都不再扫。
 *
 * <p>行上写「家族 + 字重」，右侧写文件名——同一个家族往往有好几个字重，
 * 只有文件名能把它们区分到最后一份文件。
 */
public class FontPickerPage extends GenericScreen {
    private static final int BAR_H = 22;
    private static final int SEARCH_H = 22;
    private static final int FOOT_H = 14;
    private static final int ROW_H = 18;
    private static final int PAD = 14;
    private static final float WIDE = 1.2F;
    private static final String TITLE = "系统字体";
    private static final String LATIN = "SYSTEM FONTS";

    /** 扫出来的字体：扫一次就够，字体目录在游戏运行期间不会变。 */
    private static List<LocalFontTextDrawer.FontFile> scanned;

    private final EndfieldSettings settings;

    private String query = "";
    private int scroll;
    private EditBox searchBox;

    private int winX;
    private int winY;
    private int winW;
    private int winH;
    private int contentLeft;
    private int contentWidth;

    /** 这一帧排出来的行；绘制与命中共用。 */
    private final List<Row> rows = new ArrayList<>();
    private int totalHeight;

    /** 悬停行，只用来出说明框。 */
    private LocalFontTextDrawer.FontFile hovered;

    private record Row(int y, LocalFontTextDrawer.FontFile font) {}

    public FontPickerPage(EndfieldSettings settings) {
        super(Component.empty(), 0, 0);
        this.settings = settings;
    }

    private boolean light() {
        return settings != null && settings.light();
    }

    private Ef.Palette pal() {
        return Ef.palette(light()).withAlpha(NewClickGui.opacity());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ 数据

    private static List<LocalFontTextDrawer.FontFile> fonts() {
        if (scanned == null) {
            try {
                scanned = LocalFontTextDrawer.scanFonts();
            } catch (Throwable e) {
                // 读不了字体目录就当作没有字体，这一页仍然打得开
                scanned = List.of();
            }
        }
        return scanned;
    }

    /** 当前选择的字体路径；不是路径（用户手输的名字）就返回空串，行上不给「当前」标记。 */
    private static String selectedPath() {
        TextFont font = MainTasks.getTextFont();
        if (font == null) {
            return "";
        }
        String value = font.textFontType.get();
        return value == null ? "" : value.trim();
    }

    private List<LocalFontTextDrawer.FontFile> shown() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return fonts();
        }
        List<LocalFontTextDrawer.FontFile> out = new ArrayList<>();
        for (LocalFontTextDrawer.FontFile font : fonts()) {
            if (font.displayName().toLowerCase(Locale.ROOT).contains(q)
                    || font.path().toString().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(font);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void init0() {
        super.init0();
        this.x = 0;
        this.y = 0;
        this.scroll = 0;
        // 第一次打开就把字体扫掉：绘制时每帧扫会把界面卡住
        fonts();
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        winW = Math.min(this.width - 8, Math.max(280, Math.round(this.width * 0.78F)));
        winH = Math.min(this.height - 8, Math.max(200, Math.round(this.height * 0.78F)));
        winX = (this.width - winW) / 2;
        winY = (this.height - winH) / 2;
        contentLeft = winX + PAD;
        contentWidth = Math.min(winW - PAD * 2, 560);

        EfWidget.of(0, 0, this.width, this.height, this::paint)
                .click(this::click)
                .scroll(this::wheel)
                .addTo(this);

        EditBox box = new EditBox(
                Minecraft.getInstance().font,
                contentLeft + 4 + prefixWidth(),
                searchTop() + 7,
                Math.max(24, searchWidth() - 8 - prefixWidth()),
                9,
                Component.literal("搜索字体"));
        box.setBordered(false);
        box.setMaxLength(48);
        box.setHint(Component.literal("搜索字体名"));
        box.setValue(query);
        box.setResponder(text -> {
            query = text;
            scroll = 0;
        });
        this.searchBox = box;
        this.addRenderableWidget(box);
    }

    // ------------------------------------------------------------------ 几何

    private int searchTop() {
        return winY + BAR_H;
    }

    private int bodyTop() {
        return searchTop() + SEARCH_H;
    }

    private int bodyBottom() {
        return winY + winH - FOOT_H;
    }

    private int bodyHeight() {
        return Math.max(1, bodyBottom() - bodyTop());
    }

    private int searchWidth() {
        return Math.min(240, Math.max(120, contentWidth / 2));
    }

    private static int prefixWidth() {
        return Ef.trackedWidth("//", 1) + 4;
    }

    private int maxScroll() {
        return Math.max(0, totalHeight + 6 - bodyHeight());
    }

    private void buildRows() {
        rows.clear();
        int y = bodyTop() - scroll;
        for (LocalFontTextDrawer.FontFile font : shown()) {
            rows.add(new Row(y, font));
            y += ROW_H;
        }
        totalHeight = (y + scroll) - bodyTop();
    }

    // ------------------------------------------------------------------ 绘制

    private void paint(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        Ef.Palette p = pal();
        buildRows();
        hovered = null;

        paintBar(ctx, mouseX, mouseY);
        paintSearch(ctx, p);
        paintBody(ctx, p, mouseX, mouseY);
        paintFooter(ctx, p);
        paintFrame(ctx, p);
        paintTip(ctx, p, mouseX, mouseY);
    }

    private void paintBar(VDrawContext ctx, int mouseX, int mouseY) {
        Ef.fill(ctx, winX, winY, winW, BAR_H, Ef.BAR_BG);
        Ef.fill(ctx, winX, winY, winW, 3, Ef.SIGNAL);

        boolean backHot = mouseX >= winX + 6 && mouseX <= winX + 40 && mouseY >= winY + 5 && mouseY <= winY + 17;
        if (backHot) {
            Ef.cutFill(ctx, winX + 6, winY + 5, 34, 12, Ef.BAR_RULE, Ef.CUT);
        } else {
            Ef.cutOutline(ctx, winX + 6, winY + 5, 34, 12, Ef.BAR_INK2, Ef.CUT);
        }
        Ef.text(ctx, "< 返回", winX + 9, winY + 6, Ef.BAR_INK);

        int titleX = winX + 48;
        Ef.text(ctx, TITLE, titleX, winY + 6, Ef.BAR_INK);
        Ef.trackedWide(ctx, LATIN, titleX + Ef.textWidth(TITLE) + 8, winY + 7, Ef.BAR_INK2, 1, WIDE);

        String count = shown().size() + " / " + fonts().size();
        if (winW >= 360) {
            Ef.text(ctx, count, winX + winW - 8 - Ef.textWidth(count), winY + 6, Ef.BAR_INK2);
        }
    }

    private void paintSearch(VDrawContext ctx, Ef.Palette p) {
        int y = searchTop();
        Ef.fill(ctx, winX, y, winW, SEARCH_H, p.panel());
        Ef.rule(ctx, winX, y + SEARCH_H - 1, winW, p.rule());

        int boxW = searchWidth();
        boolean focused = searchBox != null && searchBox.isFocused();
        Ef.fill(ctx, contentLeft, y + 4, boxW, 13, p.paper());
        Ef.cutOutline(ctx, contentLeft, y + 4, boxW, 13, focused ? p.ink() : p.rule(), Ef.CUT);
        if (focused) {
            Ef.fill(ctx, contentLeft + 1, y + 15, boxW - 2, 2, p.trace());
        }
        Ef.tracked(ctx, "//", contentLeft + 4, y + 6, p.ink2(), 1);
        if (searchBox != null) {
            searchBox.setTextColor(p.ink());
        }

        int avail = winX + winW - 8 - (contentLeft + boxW + 12);
        if (avail > 40) {
            String right = selectedPath().isEmpty() ? "未选择字体文件" : Ef.ellipsis(fileNameOf(selectedPath()), avail);
            Ef.text(ctx, right, winX + winW - 8 - Ef.textWidth(right), y + 7, p.ink2());
        }
    }

    private void paintBody(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        int top = bodyTop();
        int height = bodyHeight();
        Ef.fill(ctx, winX, top, winW, height, p.paper());

        ctx.enableScissor(winX, top, winX + winW, top + height);
        try {
            if (rows.isEmpty()) {
                String empty = fonts().isEmpty()
                        ? "本机字体目录里没有找到 ttf 文件，换一个字体目录或手动填写字体名。"
                        : "没有匹配「" + query.trim() + "」的字体，换个词试试。";
                Ef.text(ctx, empty, contentLeft, top + 6, p.ink2());
            } else {
                String current = selectedPath();
                for (Row row : rows) {
                    if (row.y + ROW_H < top || row.y > top + height) {
                        continue;
                    }
                    paintRow(ctx, p, row, current, mouseX, mouseY);
                }
            }
        } finally {
            ctx.disableScissor();
        }
        Ef.scrollbar(ctx, winX + winW - 4, top + 2, height - 6, scroll, totalHeight, height, p.rule(), p.ink2());
    }

    private void paintRow(
            VDrawContext ctx, Ef.Palette p, Row row, String currentPath, int mouseX, int mouseY) {
        LocalFontTextDrawer.FontFile font = row.font;
        String path = font.path().toAbsolutePath().normalize().toString();
        boolean current = !currentPath.isEmpty() && path.equals(currentPath);
        boolean hover = mouseY >= row.y
                && mouseY < row.y + ROW_H
                && mouseX >= contentLeft - 4
                && mouseX <= contentLeft + contentWidth + 4;
        if (hover) {
            hovered = font;
            Ef.fill(ctx, contentLeft - 4, row.y, contentWidth + 8, ROW_H, Ef.mix(p.paper(), p.ink(), 0.04f));
            Ef.fill(ctx, contentLeft - 4, row.y, 3, ROW_H, p.trace());
        } else if (current) {
            // 当前生效的字体：不靠悬停也看得出来
            Ef.fill(ctx, contentLeft - 4, row.y, 3, ROW_H, Ef.SIGNAL);
        }

        int chipW = current ? Ef.textWidth("当前") + 10 : 0;
        int fileW = Ef.textWidth(fileNameOf(path));
        int metaX = contentLeft + contentWidth - chipW - 8 - fileW;
        int nameW = Math.max(40, metaX - contentLeft - 10);
        Ef.text(ctx, Ef.ellipsis(font.displayName(), nameW), contentLeft, row.y + 5, p.ink());
        if (metaX > contentLeft + 40) {
            Ef.text(ctx, fileNameOf(path), metaX, row.y + 5, p.ink2());
        }
        if (current) {
            Ef.cutFill(ctx, contentLeft + contentWidth - chipW, row.y + 3, chipW, 11, Ef.SIGNAL, Ef.CUT);
            Ef.text(ctx, "当前", contentLeft + contentWidth - chipW + 5, row.y + 5, Ef.ON_SIGNAL);
        }
        Ef.rule(ctx, contentLeft - 4, row.y + ROW_H - 1, contentWidth + 8, Ef.alpha(p.rule(), 0.55F));
    }

    private void paintFooter(VDrawContext ctx, Ef.Palette p) {
        int y = winY + winH - FOOT_H;
        Ef.fill(ctx, winX, y, winW, FOOT_H, p.panel());
        Ef.rule(ctx, winX, y, winW, p.rule());

        String current = selectedPath().isEmpty() ? "未选择" : fileNameOf(selectedPath());
        String left = fonts().size() + " 个字体 │ 当前 " + current;
        Ef.text(ctx, Ef.ellipsis(left, winW - 16 - Ef.textWidth("单击选用 │ Esc 返回") - 12), winX + 8, y + 3, p.ink2());

        String hint = "单击选用 │ Esc 返回";
        Ef.text(ctx, hint, winX + winW - 8 - Ef.textWidth(hint), y + 3, p.ink2());
    }

    private void paintFrame(VDrawContext ctx, Ef.Palette p) {
        int edge = p.ink();
        Ef.fill(ctx, winX, winY, winW, 1, edge);
        Ef.fill(ctx, winX, winY + winH - 1, winW, 1, edge);
        Ef.fill(ctx, winX, winY, 1, winH, edge);
        Ef.fill(ctx, winX + winW - 1, winY, 1, winH, edge);
    }

    private void paintTip(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        if (hovered == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.addAll(Ef.wrap(hovered.displayName(), 240));
        lines.addAll(Ef.wrap(hovered.path().toString(), 240));
        TextFont font = MainTasks.getTextFont();
        if (font != null && font.textStyle.get() != TextFont.TextStyle.SYSTEM) {
            // 选了也不会立刻生效，这一条必须说清楚，否则会被当成坏了
            lines.add("文字样式不是「系统字体」，选用后要先切到系统字体才会生效。");
        }
        Ef.tooltip(ctx, p, lines, mouseX, mouseY, this.width, this.height);
    }

    // ------------------------------------------------------------------ 交互

    private boolean click(EfWidget self, double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (my >= winY && my < winY + BAR_H) {
            if (mx >= winX + 6 && mx <= winX + 42) {
                onClose();
                return true;
            }
            return false;
        }
        // 搜索行留给原版输入框，否则点不进焦点
        if (my >= searchTop() && my < bodyTop()) {
            return false;
        }
        if (my < bodyTop() || my >= bodyBottom() || button != 0) {
            return false;
        }
        buildRows();
        for (Row row : rows) {
            if (my < row.y || my >= row.y + ROW_H) {
                continue;
            }
            if (mx < contentLeft - 4 || mx > contentLeft + contentWidth + 4) {
                return false;
            }
            select(row.font);
            return true;
        }
        return false;
    }

    /** 写进字体设置就退回上一屏：这一步之后用户要看到的是正文，不是这张表。 */
    private void select(LocalFontTextDrawer.FontFile font) {
        TextFont target = MainTasks.getTextFont();
        if (target != null) {
            target.textFontType.set(font.path().toAbsolutePath().normalize().toString());
        }
        onClose();
    }

    private boolean wheel(EfWidget self, double mouseX, double mouseY, double amount) {
        if (mouseY < bodyTop() || mouseY >= bodyBottom()) {
            return false;
        }
        buildRows();
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (Math.signum(amount) * 18)));
        return true;
    }

    /** 只取文件名：完整路径太长，行里放不下，悬停说明框里有全文。 */
    private static String fileNameOf(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String value = path.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash >= 0 && slash < value.length() - 1 ? value.substring(slash + 1) : value;
    }
}
