package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.GenericScreen;
import me.matl114.hooks.BaritoneHooks;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.config.ValueAccessor;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.newclickgui.NewClickGui;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.Ef;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EfWidget;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldClickGuiScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldTabBar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 「Baritone」页：{@link BaritoneHooks} 暴露的整张设置表。
 *
 * <p>这些设置是 Baritone 自己的 Setting，只有 {@code getValue} / {@code setValue}，
 * 没有配置路径、默认值和校验器，所以不复用模块配置页那套按路径分节的表单：
 * 这里一页平铺，控件按值的运行时类型挑。
 *
 * <p>值必须按运行时对象判类型、写回也要写回同一个类型：Baritone 的 Setting 是按泛型读出来的，
 * 把 Double 塞进 Integer 设置，它下一次取值就会抛转型异常。
 */
public class BaritonePage extends GenericScreen {
    private static final int BAR_H = 22;

    /** 顶部分类条：切分类在同一屏里完成。 */
    private final EndfieldTabBar tabBar = new EndfieldTabBar();

    private int barH = BAR_H;
    private static final int FOOT_H = 14;
    private static final int ROW_H = 18;
    private static final int PAD = 14;
    /** 数值槽位的固定宽度：宽度稳定，滑轨才不会随数值跳动。 */
    private static final int TEXT_W = 44;
    /** 行首序号栏的宽度。 */
    private static final int INDEX_W = 22;
    /** 设置项翻译键前缀，与宿主旧界面同一个约定。 */
    private static final String KEY_PREFIX = "widget.click-gui.baritone.";

    private final EndfieldSettings settings;

    /** Baritone 是否可用：不可用时整页只写一句说明，不画空白表单。 */
    private final boolean available;

    private final List<Entry> entries;

    private int winX;
    private int winY;
    private int winW;
    private int winH;

    private int contentLeft;
    private int contentWidth;
    private int scroll;
    private int totalHeight;

    private final List<Row> rows = new ArrayList<>();

    /** 每项滑杆的取值区间：只在当前值跑出区间时重取。 */
    private final Map<String, double[]> ranges = new HashMap<>();

    /** 设置项说明的折行结果，按设置名缓存，不每帧查语言表。 */
    private final Map<String, List<String>> tips = new HashMap<>();

    /** 打开中的枚举下拉。 */
    private Entry dropdown;
    private int dropX;
    private int dropY;
    private int dropW;

    private Entry hovered;

    /** 正在拖动的滑杆：轨道与区间都按按下那一刻记下的算，拖动中数值变化不会让轨道左右跳。 */
    private Entry dragEntry;
    private int dragTrackX;
    private int dragTrackW;
    private double dragMin;
    private double dragMax;

    /** 一个设置项：Baritone 的设置名与它唯一的读写入口。 */
    private record Entry(String name, ValueAccessor<?> accessor) {}

    /** 一帧内布局出来的一行：序号是它在设置表里的位置，y 已经是屏幕绝对坐标。 */
    private record Row(int index, int y, Entry entry) {}

    public BaritonePage(EndfieldSettings settings) {
        super(Component.empty(), 0, 0);
        this.settings = settings;
        this.available = isApiSupported();
        this.entries = this.available ? readSettings() : List.of();
    }

    // ------------------------------------------------------------------ 数据

    private static boolean isApiSupported() {
        try {
            return BaritoneHooks.getInstance().isBaritoneAPISupported();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 设置表的顺序就是 Baritone 自己的声明顺序，照原样排，不按名字重排。 */
    private static List<Entry> readSettings() {
        List<Entry> out = new ArrayList<>();
        try {
            Map<String, ValueAccessor<?>> all = BaritoneHooks.getInstance().getAllSettings();
            if (all == null) {
                return out;
            }
            for (Map.Entry<String, ValueAccessor<?>> pair : all.entrySet()) {
                if (pair.getKey() != null && pair.getValue() != null) {
                    out.add(new Entry(pair.getKey(), pair.getValue()));
                }
            }
        } catch (Throwable ignored) {
            // 读不出设置表就按空表处理：页面照样能开，只是没有行
        }
        return out;
    }

    private Object valueOf(Entry entry) {
        try {
            return entry.accessor().getValue();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 访问器是按擦除后的裸类型存的，写回也只能走裸类型，再靠 coerce 还原成原来的数值类型。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void write(Entry entry, Object value) {
        try {
            ((ValueAccessor) entry.accessor()).setValue(value);
        } catch (Throwable ignored) {
            // 写不进去就保持原值，下一帧照旧显示真实状态
        }
    }

    private static boolean integral(Object value) {
        return value instanceof Integer
                || value instanceof Long
                || value instanceof Short
                || value instanceof Byte;
    }

    /**
     * 滑杆的取值区间：Baritone 的设置不带上界信息，按当前值的数量级取一段，
     * 只有当前值跑出这段区间时才按新的数量级重取——每帧重推会让轨道随拖动跳档。
     */
    private double[] range(Entry entry, double current, boolean whole) {
        double[] cached = ranges.get(entry.name());
        if (cached == null || current < cached[0] || current > cached[1]) {
            cached = deriveRange(current, whole);
            ranges.put(entry.name(), cached);
        }
        return cached;
    }

    private static double[] deriveRange(double current, boolean whole) {
        double abs = Math.abs(current);
        double top;
        if (abs < 1.0D) {
            // 0 和纯小数：整数型给一段能拉到 100 的区间，浮点型按 0~1 的比例给
            top = whole ? 100.0D : 1.0D;
        } else {
            top = Math.pow(10.0D, Math.ceil(Math.log10(abs)));
        }
        return current < 0.0D ? new double[] {-top, top} : new double[] {0.0D, top};
    }

    /** 把滑杆算出的 double 还原成原来的数值类型；认不出的类型不写。 */
    private static Object coerce(Object current, double next) {
        if (current instanceof Integer) {
            return (int) Math.round(next);
        }
        if (current instanceof Long) {
            return Math.round(next);
        }
        if (current instanceof Short) {
            return (short) Math.round(next);
        }
        if (current instanceof Byte) {
            return (byte) Math.round(next);
        }
        if (current instanceof Float) {
            return (float) next;
        }
        if (current instanceof Double) {
            return next;
        }
        return null;
    }

    private static String label(Entry entry) {
        return Component.translatableWithFallback(KEY_PREFIX + entry.name(), entry.name())
                .getString();
    }

    /** 设置名之外再没有别的地方能说明它是什么，说明文字沿用宿主 {@code <键名>.tooltips} 的约定。 */
    private List<String> tipLines(Entry entry) {
        return tips.computeIfAbsent(entry.name(), name -> {
            List<String> out = new ArrayList<>();
            for (Component component : ChatUtils.parseTooltipsTranslation(KEY_PREFIX + name + ".tooltips", "")) {
                String text = component == null ? "" : component.getString();
                if (!text.isEmpty()) {
                    out.addAll(Ef.wrap(text, 240));
                }
            }
            return out;
        });
    }

    /** 只读值的展示文本：容器只报类别，别把几千条目的列表摊成一行字。 */
    private static String preview(Object value) {
        if (value == null) {
            return "无值";
        }
        if (value instanceof CharSequence text) {
            String raw = text.toString();
            return raw.length() > 64 ? raw.substring(0, 64) + "..." : raw;
        }
        if (value instanceof Collection<?> || value.getClass().isArray()) {
            return "列表";
        }
        if (value instanceof Map<?, ?>) {
            return "映射";
        }
        if (value instanceof Enum<?> option) {
            return option.name();
        }
        return "不可编辑";
    }

    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-6D) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    // ------------------------------------------------------------------ 骨架

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

    @Override
    protected void init0() {
        super.init0();
        this.x = 0;
        this.y = 0;
        this.scroll = 0;
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        winW = Math.min(this.width - 8, Math.max(280, Math.round(this.width * 0.78F)));
        winH = Math.min(this.height - 8, Math.max(200, Math.round(this.height * 0.78F)));
        winX = (this.width - winW) / 2;
        winY = (this.height - winH) / 2;
        barH = tabBar.measure(winW, light());

        EfWidget.of(0, 0, this.width, this.height, this::paint)
                .click(this::click)
                .drag(this::drag, () -> dragEntry != null)
                .scroll(this::wheel)
                .addTo(this);
    }

    // ------------------------------------------------------------------ 布局

    private int bodyTop() {
        return winY + barH;
    }

    /** 正文一直铺到页脚上沿：中间留空的那几像素没有任何绘制，会透出背后的世界。 */
    private int bodyHeight() {
        return winH - barH - FOOT_H;
    }

    private int textX() {
        return contentLeft + INDEX_W;
    }

    private int contentRight() {
        return contentLeft + contentWidth;
    }

    private int sliderWidth() {
        return Math.min(56, Math.max(28, contentWidth / 5));
    }

    /** 标签可用宽度：别让长名字压到滑轨上。 */
    private int labelWidth() {
        return Math.max(40, contentRight() - TEXT_W - 8 - sliderWidth() - 10 - textX());
    }

    private void buildRows() {
        rows.clear();
        contentLeft = winX + PAD;
        contentWidth = Math.min(winW - PAD * 2, 560);

        int y = bodyTop() - scroll;
        for (int i = 0; i < entries.size(); i++) {
            rows.add(new Row(i + 1, y, entries.get(i)));
            y += ROW_H;
        }
        totalHeight = (y + scroll) - bodyTop();
    }

    private int maxScroll() {
        return Math.max(0, totalHeight - bodyHeight() + 8);
    }

    // ------------------------------------------------------------------ 绘制

    private void paint(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        Ef.Palette p = pal();
        buildRows();
        hovered = null;

        paintBar(ctx, mouseX, mouseY);
        paintBody(ctx, p, mouseX, mouseY);
        paintDropdown(ctx, p, mouseX, mouseY);
        paintFooter(ctx, p);
        paintFrame(ctx, p);
        paintTooltip(ctx, p, mouseX, mouseY);
    }

    private void paintBar(VDrawContext ctx, int mouseX, int mouseY) {
        // 命令栏就是整条分类条；Baritone 可不可用由正文那一句说明，不再占命令栏
        tabBar.paint(ctx, winX, winY, winW, "Baritone", light(), mouseX, mouseY);
    }

    private void paintBody(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        int top = bodyTop();
        int height = bodyHeight();
        Ef.fill(ctx, winX, top, winW, height, p.paper());

        ctx.enableScissor(winX, top, winX + winW, top + height);
        try {
            if (!available) {
                paintNote(ctx, p, "这台机器上没有可用的 Baritone API，这一页的设置项读不出来。");
                return;
            }
            if (rows.isEmpty()) {
                paintNote(ctx, p, "Baritone 没有给出任何可以显示的设置项。");
                return;
            }
            for (Row row : rows) {
                if (row.y() + ROW_H <= top || row.y() >= top + height) {
                    continue;
                }
                paintRow(ctx, p, row, mouseX, mouseY);
            }
        } finally {
            ctx.disableScissor();
        }
        Ef.scrollbar(ctx, winX + winW - 4, top + 2, height - 6, scroll, totalHeight, height, p.rule(), p.ink2());
    }

    /** 空页与不可用都只写一句完整的话。 */
    private void paintNote(VDrawContext ctx, Ef.Palette p, String text) {
        int y = bodyTop() + 8;
        for (String line : Ef.wrap(text, contentWidth)) {
            Ef.text(ctx, line, contentLeft, y, p.ink2());
            y += Ef.lineH() + 3;
        }
    }

    private void paintRow(VDrawContext ctx, Ef.Palette p, Row row, int mouseX, int mouseY) {
        int y = row.y();
        boolean hover = mouseY >= y && mouseY < y + ROW_H && mouseX >= contentLeft - 4 && mouseX <= contentRight() + 4;
        if (hover && dropdown == null) {
            hovered = row.entry();
        }
        if (hover) {
            Ef.fill(ctx, contentLeft - 4, y, contentWidth + 8, ROW_H, Ef.mix(p.paper(), p.ink(), 0.04F));
            Ef.fill(ctx, contentLeft - 4, y, 3, ROW_H, p.trace());
        }

        // 行首序号是这一项在设置表里的真实位置
        String index = String.format(Locale.ROOT, "%03d", row.index());
        Ef.tracked(ctx, index, contentLeft, y + 5, p.ink2(), 1);
        Ef.text(ctx, Ef.ellipsis(label(row.entry()), labelWidth()), textX(), y + 5, p.ink());
        Ef.rule(ctx, contentLeft - 4, y + ROW_H - 1, contentWidth + 8, Ef.alpha(p.rule(), 0.55F));

        Object value = valueOf(row.entry());
        if (value instanceof Boolean flag) {
            paintSwitch(ctx, p, y, flag);
        } else if (value instanceof Number number) {
            double current = number.doubleValue();
            double[] bounds = range(row.entry(), current, integral(value));
            paintSlider(ctx, p, y, formatNumber(current), current, bounds[0], bounds[1]);
        } else if (value instanceof Enum<?> option) {
            paintEnum(ctx, p, y, dropdown == row.entry(), option.name());
        } else {
            String shown = Ef.ellipsis(preview(value), Math.min(200, Math.max(80, contentWidth / 2)));
            Ef.text(ctx, shown, contentRight() - Ef.textWidth(shown), y + 5, p.ink2());
        }
    }

    /** 开关块：实心墨块 = 开，虚线框 = 关，点一下切一次。 */
    private void paintSwitch(VDrawContext ctx, Ef.Palette p, int y, boolean on) {
        int right = contentRight();
        int width = 20;
        int height = 9;
        int top = y + (ROW_H - height) / 2;
        if (on) {
            Ef.fill(ctx, right - width, top, width, height, p.ink());
            Ef.fill(ctx, right - 9, top + 2, 5, 5, p.paper());
        } else {
            Ef.dashedOutline(ctx, right - width, top, width, height, p.ink2());
            Ef.fill(ctx, right - width + 2, top + 2, 5, 5, p.ink2());
        }
    }

    /** 数值滑杆：细线轨道 + 2px 手柄 + 右端数值。 */
    private void paintSlider(
            VDrawContext ctx, Ef.Palette p, int y, String text, double value, double min, double max) {
        int right = contentRight();
        int width = sliderWidth();
        int left = right - TEXT_W - 8 - width;
        int lineY = y + ROW_H / 2;

        Ef.fill(ctx, left, lineY, width, 1, p.rule());
        double ratio = max > min ? (value - min) / (max - min) : 0.0D;
        int knob = (int) (width * Math.max(0.0D, Math.min(1.0D, ratio)));
        Ef.fill(ctx, left, lineY, knob, 1, p.ink());
        Ef.fill(ctx, left + knob - 1, lineY - 3, 2, 7, p.ink());
        Ef.text(ctx, text, right - Ef.textWidth(text), y + 5, p.ink());
    }

    /** 枚举是一个切角小块，打开时用墨底反白。 */
    private void paintEnum(VDrawContext ctx, Ef.Palette p, int y, boolean opened, String text) {
        String chip = Ef.ellipsis(text, Math.max(60, contentWidth / 3)) + "   ▾";
        int width = Ef.textWidth(chip);
        int left = contentRight() - width - 10;
        Ef.cutFill(ctx, left, y + 3, width + 10, 11, opened ? p.ink() : p.chip(), Ef.CUT);
        Ef.text(ctx, chip, left + 5, y + 5, opened ? p.paper() : p.ink());
    }

    /** 下拉面板的上沿：放不下就翻到行上方，绘制与命中共用同一个判断。 */
    private int dropTop(int height) {
        int top = dropY;
        if (top + height > winY + winH - FOOT_H) {
            top = Math.max(winY + BAR_H, dropY + ROW_H - height);
        }
        return top;
    }

    private void paintDropdown(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        if (dropdown == null) {
            return;
        }
        Object[] options = enumOptions(dropdown);
        if (options.length == 0) {
            dropdown = null;
            return;
        }
        int rowH = 13;
        int height = options.length * rowH + 4;
        int top = dropTop(height);
        Ef.fill(ctx, dropX, top, dropW, height, p.panel());
        Ef.cutOutline(ctx, dropX, top, dropW, height, p.ink(), Ef.CUT);

        Object current = valueOf(dropdown);
        for (int i = 0; i < options.length; i++) {
            int oy = top + 2 + i * rowH;
            boolean hover = mouseX >= dropX && mouseX <= dropX + dropW && mouseY >= oy && mouseY < oy + rowH;
            boolean active = options[i] == current;
            if (hover) {
                Ef.fill(ctx, dropX + 1, oy, dropW - 2, rowH, Ef.mix(p.panel(), p.ink(), 0.08F));
            }
            if (active) {
                Ef.fill(ctx, dropX + 1, oy, 2, rowH, p.trace());
            }
            Ef.text(ctx, Ef.ellipsis(enumLabel(options[i]), dropW - 14), dropX + 8, oy + 3, active ? p.ink() : p.ink2());
        }
    }

    private Object[] enumOptions(Entry entry) {
        Object value = valueOf(entry);
        return value instanceof Enum<?> option ? option.getDeclaringClass().getEnumConstants() : new Object[0];
    }

    private static String enumLabel(Object option) {
        return option instanceof Enum<?> value ? value.name() : String.valueOf(option);
    }

    private void paintFooter(VDrawContext ctx, Ef.Palette p) {
        int y = winY + winH - FOOT_H;
        Ef.fill(ctx, winX, y, winW, FOOT_H, p.panel());
        Ef.rule(ctx, winX, y, winW, p.rule());

        String left = available ? entries.size() + " 项设置  │  已开启 " + enabledCount() + " 项" : "Baritone 不可用";
        Ef.text(ctx, left, winX + 8, y + 3, p.ink2());
        if (available) {
            String hint = "点开关切换 │ 拖滑杆改数值";
            Ef.text(ctx, hint, winX + winW - 8 - Ef.textWidth(hint), y + 3, p.ink2());
        }
    }

    /** 页脚统计：真的去读，不按「应该开着」估算。 */
    private int enabledCount() {
        int count = 0;
        for (Entry entry : entries) {
            if (Boolean.TRUE.equals(valueOf(entry))) {
                count++;
            }
        }
        return count;
    }

    private void paintFrame(VDrawContext ctx, Ef.Palette p) {
        int edge = p.ink();
        Ef.fill(ctx, winX, winY, winW, 1, edge);
        Ef.fill(ctx, winX, winY + winH - 1, winW, 1, edge);
        Ef.fill(ctx, winX, winY, 1, winH, edge);
        Ef.fill(ctx, winX + winW - 1, winY, 1, winH, edge);
    }

    private void paintTooltip(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        if (hovered == null) {
            return;
        }
        List<String> lines = tipLines(hovered);
        if (lines.isEmpty()) {
            return;
        }
        Ef.tooltip(ctx, p, lines, mouseX, mouseY, this.width, this.height);
    }

    // ------------------------------------------------------------------ 交互

    private boolean click(EfWidget self, double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        // 上一次拖动在这里收尾：重新点击就重新判定
        dragEntry = null;
        buildRows();

        if (dropdown != null) {
            Object[] options = enumOptions(dropdown);
            int rowH = 13;
            int top = dropTop(options.length * rowH + 4);
            if (mx >= dropX && mx <= dropX + dropW && my >= top && my <= top + options.length * rowH + 4) {
                int index = (my - top - 2) / rowH;
                if (index >= 0 && index < options.length) {
                    write(dropdown, options[index]);
                }
            }
            dropdown = null;
            return true;
        }

        if (my >= winY && my < winY + barH) {
            String hit = tabBar.click(winX, winY, winW, "Baritone", light(), mx, my);
            if (EndfieldTabBar.THEME.equals(hit)) {
                if (settings != null) {
                    settings.light(!settings.light());
                }
                return true;
            }
            if (hit != null && !"Baritone".equals(hit)) {
                Screen next = "Module".equals(hit)
                        ? new EndfieldClickGuiScreen(settings)
                        : EndfieldPages.create(hit, settings);
                if (next != null) {
                    ScreenAccess.of(this).switchToScreen(next);
                }
                return true;
            }
            return false;
        }

        if (my < bodyTop() || my > bodyTop() + bodyHeight()) {
            return false;
        }
        for (Row row : rows) {
            if (my < row.y() || my >= row.y() + ROW_H) {
                continue;
            }
            return hit(row, mx, button);
        }
        return false;
    }

    private boolean hit(Row row, int mouseX, int button) {
        Entry entry = row.entry();
        Object value = valueOf(entry);
        if (value instanceof Boolean flag) {
            if (mouseX >= contentRight() - 24) {
                write(entry, !flag);
                return true;
            }
            return false;
        }
        if (value instanceof Number number) {
            double current = number.doubleValue();
            double[] bounds = range(entry, current, integral(value));
            int width = sliderWidth();
            int left = contentRight() - TEXT_W - 8 - width;
            if (button == 0 && mouseX >= left - 3 && mouseX <= left + width + 3) {
                dragEntry = entry;
                dragTrackX = left;
                dragTrackW = width;
                dragMin = bounds[0];
                dragMax = bounds[1];
                applyRatio(entry, value, (mouseX - left) / (double) width);
                return true;
            }
            return false;
        }
        if (value instanceof Enum<?>) {
            if (button == 0) {
                dropdown = entry;
                dropW = Math.min(180, Math.max(120, contentWidth / 3));
                dropX = contentRight() - dropW;
                dropY = row.y() + ROW_H;
                return true;
            }
            return false;
        }
        return false;
    }

    private void applyRatio(Entry entry, Object current, double ratio) {
        double clamped = Math.max(0.0D, Math.min(1.0D, ratio));
        double next = dragMin + clamped * (dragMax - dragMin);
        Object value = coerce(current, next);
        if (value != null) {
            write(entry, value);
        }
    }

    /** 拖动中：轨道矩形与区间按按下那一刻记下的算。 */
    private boolean drag(EfWidget self, double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragEntry == null) {
            return false;
        }
        applyRatio(dragEntry, valueOf(dragEntry), (mouseX - dragTrackX) / (double) dragTrackW);
        return true;
    }

    private boolean wheel(EfWidget self, double mouseX, double mouseY, double amount) {
        if (mouseY < bodyTop() || mouseY > bodyTop() + bodyHeight()) {
            return false;
        }
        // 滚动会挪动行，下拉跟着旧坐标走就会错位，直接收起来
        dropdown = null;
        buildRows();
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (Math.signum(amount) * 18)));
        return true;
    }
}
