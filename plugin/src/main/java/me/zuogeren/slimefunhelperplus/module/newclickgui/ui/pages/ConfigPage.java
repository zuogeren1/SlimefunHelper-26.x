package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.GenericScreen;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.ConfigEnum;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.FloatRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.LongRef;
import me.matl114.managers.config.MapRef;
import me.matl114.managers.config.Ref;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.newclickgui.NewClickGui;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.Ef;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EfWidget;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldClickGuiScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldTabBar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 「全配置」页：把宿主每一份配置文件的可见设置摊成一张总表。
 *
 * <p>数据不是 {@code WrapperConfigRef}（那是模块的视角），而是 {@link Config#getVisiblePaths()}：
 * 一条可见路径就一定能通过 {@link Config#get(String...)} 解析回 {@link Ref}，
 * 和宿主 {@code ConfigMultiLevelList} 走的是同一条解析路。
 * 解析不出来的路径仍要列出来，只是画成只读行——总表的价值一半在"这里有什么"，不是"这里能改什么"。
 *
 * <p>控件只做四件事：开关翻转、数值增减、枚举轮换、右键恢复默认。
 * 都走 {@link Ref} 自己的写入口，所以校验器、更新监听、脏标记全部沿用宿主那一套，
 * 不在这里复制一份配置语义。
 */
public class ConfigPage extends GenericScreen {
    private static final int BAR_H = 22;

    /** 顶部分类条：切分类在同一屏里完成。 */
    private final EndfieldTabBar tabBar = new EndfieldTabBar();

    private int barH = BAR_H;
    private static final int SEARCH_H = 22;
    private static final int FOOT_H = 14;
    private static final int PAD = 12;
    private static final int HEAD_H = 30;
    private static final int SECTION_H = 22;
    private static final int ROW_H = 18;
    private static final int RAIL_CHIP_H = 15;
    private static final int RAIL_STEP = 19;
    /** 数值行两侧加减键的边长。 */
    private static final int BTN = 13;
    private static final float WIDE = 1.2F;
    private static final String TITLE = "全配置";
    private static final String LATIN = "CONFIG";

    private final EndfieldSettings settings;

    /** 配置根清单：每份配置文件一项，顺序沿用注册表。 */
    private final List<Config> roots = new ArrayList<>();

    /** 每份配置解析出来的条目：Ref 是活的，值每帧现读，只有路径与 Ref 关系是缓存。 */
    private final Map<Config, List<Item>> itemCache = new IdentityHashMap<>();

    private Config selected;
    private String query = "";
    private int railScroll;
    private int itemScroll;

    /** 原版输入框，只负责收字与画字；框体由 paintSearch 画。 */
    private EditBox searchBox;

    private int winX;
    private int winY;
    private int winW;
    private int winH;

    /** 左侧配置根那一列的宽度。 */
    private int railW;

    /** 这一帧布局出来的行，绘制与命中共用；坐标为屏幕绝对坐标。 */
    private final List<Line> lines = new ArrayList<>();

    /** 内容总高，用来算滚动范围。 */
    private int listHeight;

    /** 这一帧鼠标停在哪一条设置上，悬停说明框用。 */
    private Item hovered;

    /** 一条可见路径：{@code ref} 可能为 null（路径解析不出 Ref，只能只读展示）。 */
    private record Item(String path, String section, Ref<?> ref) {}

    /** 分节标题行（item 为 null）或一条设置行。 */
    private record Line(int y, int height, String section, Item item) {}

    public ConfigPage(EndfieldSettings settings) {
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

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void init0() {
        super.init0();
        this.x = 0;
        this.y = 0;
        this.railScroll = 0;
        this.itemScroll = 0;
        this.roots.clear();
        this.itemCache.clear();
        for (Config config : Config.REGISTRY.stream().toList()) {
            if (config != null) {
                roots.add(config);
            }
        }
        // 配置可能被插件晚一步注册，选中的根失效时要重新落一个
        if (selected == null || !roots.contains(selected)) {
            selected = roots.isEmpty() ? null : roots.get(0);
        }
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        winW = Math.min(this.width - 8, Math.max(320, Math.round(this.width * 0.78F)));
        winH = Math.min(this.height - 8, Math.max(220, Math.round(this.height * 0.80F)));
        winX = (this.width - winW) / 2;
        winY = (this.height - winH) / 2;
        barH = tabBar.measure(winW, light());
        railW = Math.max(96, Math.min(160, winW / 4));

        EfWidget.of(0, 0, this.width, this.height, this::paint)
                .click(this::click)
                .scroll(this::wheel)
                .addTo(this);

        // 搜索框交给原版 EditBox：输入法、退格、粘贴都由 MC 自己处理
        int boxX = winX + 8;
        int prefixW = Ef.trackedWidth("//", 1) + 4;
        EditBox box = new EditBox(
                Minecraft.getInstance().font,
                boxX + 4 + prefixW,
                searchTop() + 6,
                Math.max(24, searchWidth() - 8 - prefixW),
                9,
                Component.literal("搜索配置项"));
        box.setBordered(false);
        box.setMaxLength(48);
        box.setHint(Component.literal("搜索配置项"));
        box.setValue(query);
        box.setResponder(text -> {
            query = text;
            itemScroll = 0;
        });
        this.searchBox = box;
        this.addRenderableWidget(box);
    }

    // ------------------------------------------------------------------ 几何

    private int searchTop() {
        return winY + barH;
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

    private int railRowsTop() {
        return bodyTop() + 34;
    }

    private int itemsX() {
        return winX + railW + PAD;
    }

    private int itemsRight() {
        return winX + winW - PAD;
    }

    private int itemsWidth() {
        return Math.max(40, itemsRight() - itemsX());
    }

    /** 设置列表的起点：标题块在它上面。 */
    private int listTop() {
        return bodyTop() + 6 + HEAD_H;
    }

    private int listViewH() {
        return Math.max(1, bodyBottom() - listTop());
    }

    private int searchWidth() {
        return Math.min(240, Math.max(120, winW / 3));
    }

    // ------------------------------------------------------------------ 数据

    /** 一份配置的可见设置项：按 {@code getVisiblePaths()} 的顺序，路径与 Ref 解析一次就缓存。 */
    private List<Item> itemsOf(Config config) {
        if (config == null) {
            return List.of();
        }
        List<Item> cached = itemCache.get(config);
        if (cached != null) {
            return cached;
        }
        List<Item> out = new ArrayList<>();
        for (String path : config.getVisiblePaths()) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String[] cut = Config.cutToPath(path);
            if (cut.length == 0 || cut[cut.length - 1].isBlank()) {
                continue;
            }
            Ref<?> ref = config.get(cut);
            // 分组节点（MapRef）自身不是一条可编辑的设置，它的子项各自在可见路径里
            if (ref instanceof MapRef) {
                continue;
            }
            String section = cut.length > 1 ? String.join(".", Arrays.copyOf(cut, cut.length - 1)) : "";
            out.add(new Item(path, section, ref));
        }
        itemCache.put(config, out);
        return out;
    }

    private List<Item> all() {
        return itemsOf(selected);
    }

    /** 搜索后的清单：路径与中文名都参与匹配。 */
    private List<Item> shown() {
        List<Item> items = all();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return items;
        }
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            if (item.path().toLowerCase(Locale.ROOT).contains(q)
                    || labelOf(item).toLowerCase(Locale.ROOT).contains(q)) {
                out.add(item);
            }
        }
        return out;
    }

    private static int sectionsOf(List<Item> items) {
        Set<String> seen = new LinkedHashSet<>();
        for (Item item : items) {
            seen.add(item.section());
        }
        return seen.size();
    }

    private static String labelOf(Item item) {
        if (item.ref() == null) {
            // 解析不出 Ref 的路径连翻译都没有意义，直接报路径
            return item.path();
        }
        return Component.translatableWithFallback(item.path(), leaf(item.path())).getString();
    }

    private static String sectionTitle(String section) {
        if (section.isEmpty()) {
            return "主设置";
        }
        return Component.translatableWithFallback("config.index." + section, leaf(section)).getString();
    }

    private static String rootName(Config config) {
        if (config == null) {
            return "—";
        }
        String name = config.getConfigName();
        String key = config.getTranslationKey();
        return Component.translatableWithFallback(key == null ? name : key, name).getString();
    }

    /** 配置根的拉丁标识：注册表里的路径，没有注册就退回配置名。 */
    private static String codeOf(Config config) {
        if (config == null) {
            return "";
        }
        if (config.getRegistryKey() != null) {
            return config
                    .getRegistryKey()
                    .identifier()
                    .getPath()
                    .replace('-', ' ')
                    .replace('_', ' ')
                    .toUpperCase(Locale.ROOT);
        }
        return config.getConfigName().toUpperCase(Locale.ROOT);
    }

    private static String leaf(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    // ------------------------------------------------------------------ 值

    private static boolean isNumeric(Ref<?> ref) {
        return ref instanceof IntRef
                || ref instanceof LongRef
                || ref instanceof DoubleRef
                || ref instanceof FloatRef;
    }

    /** 这一页真正给了控件的类型；其余只显示当前值。 */
    private static boolean writable(Ref<?> ref) {
        return ref instanceof FlagRef || isNumeric(ref) || ref instanceof EnumRef<?>;
    }

    private static double numericOf(Ref<?> ref) {
        Object value = ref.getValue();
        return value instanceof Number number ? number.doubleValue() : 0.0D;
    }

    private static double stepOf(Ref<?> ref) {
        if (ref instanceof IntRef || ref instanceof LongRef) {
            return 1.0D;
        }
        double magnitude = Math.abs(numericOf(ref));
        return magnitude >= 10.0D ? 1.0D : (magnitude >= 1.0D ? 0.5D : 0.1D);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void nudge(Ref<?> ref, double delta) {
        double next = numericOf(ref) + delta;
        if (ref instanceof IntRef intRef) {
            intRef.set((int) Math.round(next));
        } else if (ref instanceof LongRef longRef) {
            longRef.set(Math.round(next));
        } else if (ref instanceof DoubleRef doubleRef) {
            doubleRef.set(Math.round(next * 100.0D) / 100.0D);
        } else if (ref instanceof FloatRef floatRef) {
            floatRef.set((float) (Math.round(next * 100.0D) / 100.0D));
        }
    }

    private static String enumLabel(Object value) {
        if (value instanceof ConfigEnum configEnum) {
            String name = configEnum.cast().name();
            String key = "configenum." + configEnum.getConfigEnumType().replace('_', '-') + "." + name.toLowerCase(Locale.ROOT);
            return Component.translatableWithFallback(key, name).getString();
        }
        return String.valueOf(value);
    }

    /** 枚举取值可能没注册完成，读不到就画一个占位，别让整页因为一格坏掉。 */
    private static String enumText(Ref<?> ref) {
        try {
            Object value = ref.getValue();
            return value == null ? "—" : enumLabel(value);
        } catch (Throwable e) {
            return "—";
        }
    }

    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0e-6D) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String valueText(Ref<?> ref) {
        if (ref == null) {
            return "—";
        }
        try {
            Object value = ref.getValue();
            if (value == null) {
                return "—";
            }
            if (value instanceof Float || value instanceof Double) {
                return formatNumber(((Number) value).doubleValue());
            }
            if (value instanceof Boolean flag) {
                return flag ? "开" : "关";
            }
            if (value instanceof ConfigEnum) {
                return enumLabel(value);
            }
            if (value instanceof MultiKeyBind bind) {
                String keys = bind.isEmpty() ? "" : bind.getKeyStr();
                return keys.isEmpty() ? "未绑定" : keys;
            }
            String text = String.valueOf(value);
            return text.length() > 40 ? text.substring(0, 40) + "..." : text;
        } catch (Throwable e) {
            return "—";
        }
    }

    // ------------------------------------------------------------------ 布局

    private void buildLines() {
        lines.clear();
        List<Item> shown = shown();
        int y = listTop() - itemScroll;
        String current = null;
        for (Item item : shown) {
            if (!item.section().equals(current)) {
                current = item.section();
                lines.add(new Line(y, SECTION_H, current, null));
                y += SECTION_H;
            }
            lines.add(new Line(y, ROW_H, current, item));
            y += ROW_H;
        }
        listHeight = (y + itemScroll) - listTop();
    }

    private int maxItemScroll() {
        return Math.max(0, listHeight + 8 - listViewH());
    }

    private int maxRailScroll() {
        int view = Math.max(1, bodyBottom() - railRowsTop());
        return Math.max(0, roots.size() * RAIL_STEP + 4 - view);
    }

    /** 控件区的左边界：绘制标签宽度和点击判定共用，两边不会错开。 */
    private int controlLeft(Item item) {
        int right = itemsRight();
        Ref<?> ref = item.ref();
        if (ref instanceof FlagRef) {
            return right - 20;
        }
        if (isNumeric(ref)) {
            return right - BTN * 2 - 4 - 56;
        }
        if (ref instanceof EnumRef<?>) {
            return right - enumWidth(ref);
        }
        return right - readOnlyWidth();
    }

    private int enumWidth(Ref<?> ref) {
        return Ef.textWidth(enumText(ref) + " ▾") + 10;
    }

    private int readOnlyWidth() {
        return Math.min(180, Math.max(40, itemsWidth() / 3));
    }

    // ------------------------------------------------------------------ 绘制

    private void paint(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        Ef.Palette p = pal();
        buildLines();
        hovered = null;

        paintBar(ctx, p, mouseX, mouseY);
        paintSearch(ctx, p);
        paintBody(ctx, p, mouseX, mouseY);
        paintFooter(ctx, p);
        paintFrame(ctx, p);
        paintTip(ctx, p, mouseX, mouseY);
    }

    private void paintBar(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        // 命令栏就是整条分类条：当前分类高亮，点别的分类当场换页
        tabBar.paint(ctx, winX, winY, winW, "Config", light(), mouseX, mouseY);
    }

    private void paintSearch(VDrawContext ctx, Ef.Palette p) {
        int y = searchTop();
        Ef.fill(ctx, winX, y, winW, SEARCH_H, p.panel());
        Ef.rule(ctx, winX, y + SEARCH_H - 1, winW, p.rule());

        int boxX = winX + 8;
        int boxW = searchWidth();
        boolean focused = searchBox != null && searchBox.isFocused();
        Ef.fill(ctx, boxX, y + 4, boxW, 13, p.paper());
        Ef.cutOutline(ctx, boxX, y + 4, boxW, 13, focused ? p.ink() : p.rule(), Ef.CUT);
        if (focused) {
            // 聚焦时下沿换 2px 的当前位置线，颜色跟主题走
            Ef.fill(ctx, boxX + 1, y + 15, boxW - 2, 2, p.trace());
        }
        Ef.tracked(ctx, "//", boxX + 4, y + 6, p.ink2(), 1);
        if (searchBox != null) {
            searchBox.setTextColor(p.ink());
        }

        int avail = winX + winW - 8 - (boxX + boxW + 12);
        if (avail > 48) {
            String right = Ef.ellipsis("当前配置  │  " + rootName(selected), avail);
            Ef.text(ctx, right, winX + winW - 8 - Ef.textWidth(right), y + 7, p.ink2());
        }
    }

    private void paintBody(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        int top = bodyTop();
        int height = bodyHeight();
        Ef.fill(ctx, winX, top, winW, height, p.paper());
        Ef.fill(ctx, winX + railW - 1, top, 1, height, p.rule());

        ctx.enableScissor(winX, top, winX + winW, top + height);
        try {
            paintRail(ctx, p, mouseX, mouseY);
            paintItems(ctx, p, mouseX, mouseY);
        } finally {
            ctx.disableScissor();
        }

        Ef.scrollbar(
                ctx, winX + winW - 4, top + 2, height - 6, itemScroll, listHeight, listViewH(), p.rule(), p.ink2());
    }

    private void paintRail(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        int x = winX;
        int chipX = x + 8;
        int chipW = Math.max(40, railW - 16);
        int y = railRowsTop() - railScroll;
        for (Config config : roots) {
            if (y + RAIL_CHIP_H >= bodyTop() && y <= bodyBottom()) {
                boolean current = config == selected;
                boolean hover = mouseX >= chipX && mouseX <= chipX + chipW && mouseY >= y && mouseY < y + RAIL_CHIP_H;
                int bg = current ? Ef.SIGNAL : (hover ? Ef.mix(p.chip(), p.ink(), 0.12F) : p.chip());
                int fg = current ? Ef.ON_SIGNAL : p.ink();
                Ef.cutFill(ctx, chipX, y, chipW, RAIL_CHIP_H, bg, Ef.CUT_WIDE);

                String number = String.valueOf(itemsOf(config).size());
                int numberW = Ef.textWidth(number);
                // 切角会啃掉右下角，右侧数字必须让开切角宽度
                int nameW = Math.max(20, chipW - 10 - numberW - Ef.CUT_WIDE);
                Ef.text(ctx, Ef.ellipsis(rootName(config), nameW), chipX + 5, y + 3, fg);
                Ef.text(ctx, number, chipX + chipW - Ef.CUT_WIDE - 4 - numberW, y + 3, current ? Ef.ON_SIGNAL : p.ink2());
            }
            y += RAIL_STEP;
        }

        // 表头最后画：左列滚动时从它底下穿过去，不用再套一层裁剪
        Ef.fill(ctx, x, bodyTop(), Math.max(1, railW - 1), railRowsTop() - bodyTop(), p.paper());
        Ef.trackedWide(ctx, "// CONFIGS", x + 8, bodyTop() + 6, p.ink2(), 1, WIDE);
        int titleY = bodyTop() + 6 + Ef.lineH() + 2;
        Ef.text(ctx, "配置文件", x + 8, titleY, p.ink());
        String count = roots.size() + " 个";
        Ef.text(ctx, count, x + railW - 8 - Ef.textWidth(count), titleY, p.ink2());
        Ef.rule(ctx, x + 8, bodyTop() + 32, Math.max(1, railW - 16), p.rule());
    }

    private void paintItems(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        int ix = itemsX();
        int right = itemsRight();

        if (selected == null) {
            Ef.text(ctx, "宿主还没有注册配置文件，这里暂时没有可编辑的设置。", ix, listTop() + 4, p.ink2());
            return;
        }

        for (Line line : lines) {
            if (line.y() + line.height() < bodyTop() || line.y() > bodyBottom()) {
                continue;
            }
            if (line.item() == null) {
                paintSection(ctx, p, line);
            } else {
                paintRow(ctx, p, line, mouseX, mouseY);
            }
        }

        if (lines.isEmpty()) {
            String empty = query.trim().isEmpty()
                    ? "这份配置里还没有可显示的设置。"
                    : "没有匹配「" + query.trim() + "」的配置项，换个词试试。";
            Ef.text(ctx, empty, ix, listTop() + 4, p.ink2());
        }

        // 标题块最后画：设置项滚上来时从它底下穿过去，不用再套一层裁剪
        int top = bodyTop() + 6;
        Ef.fill(ctx, winX + railW, bodyTop(), winW - railW, listTop() - bodyTop(), p.paper());
        int codeW = Math.round(itemsWidth() / WIDE);
        Ef.trackedWide(ctx, Ef.ellipsis("// " + codeOf(selected), codeW), ix, top, p.ink2(), 1, WIDE);
        int titleY = top + Ef.lineH() + 2;
        List<Item> items = all();
        String meta = items.size() + " 项  │  " + sectionsOf(items) + " 组";
        int metaX = right - Ef.textWidth(meta);
        int titleW = Math.max(40, metaX - ix - 10);
        Ef.text(ctx, Ef.ellipsis(rootName(selected), titleW), ix, titleY, p.ink());
        Ef.text(ctx, meta, metaX, titleY, p.ink2());
        Ef.rule(ctx, ix, top + HEAD_H - 8, itemsWidth(), p.rule());
    }

    private void paintSection(VDrawContext ctx, Ef.Palette p, Line line) {
        int ix = itemsX();
        String section = line.section();
        String mark = section.isEmpty() ? "MAIN" : leaf(section).replace('-', ' ').toUpperCase(Locale.ROOT);
        String eyebrow = "// " + mark;
        Ef.trackedWide(ctx, eyebrow, ix, line.y() + 7, p.ink2(), 1, WIDE);
        int titleX = ix + Math.max(72, Ef.trackedWideWidth(eyebrow, 1, WIDE) + 10);
        int titleW = Math.max(20, itemsWidth() - (titleX - ix));
        Ef.text(ctx, Ef.ellipsis(sectionTitle(section), titleW), titleX, line.y() + 7, p.ink());
        Ef.rule(ctx, ix, line.y() + SECTION_H - 5, itemsWidth(), p.rule());
    }

    private void paintRow(VDrawContext ctx, Ef.Palette p, Line line, int mouseX, int mouseY) {
        int ix = itemsX();
        int right = itemsRight();
        int y = line.y();
        Item item = line.item();
        Ref<?> ref = item.ref();

        boolean hover = mouseY >= Math.max(y, listTop())
                && mouseY < y + ROW_H
                && mouseX >= ix - 4
                && mouseX <= right + 4;
        if (hover) {
            hovered = item;
            Ef.fill(ctx, ix - 4, y, itemsWidth() + 8, ROW_H, Ef.mix(p.paper(), p.ink(), 0.05F));
            Ef.fill(ctx, ix - 4, y, 3, ROW_H, p.trace());
        }

        int labelW = Math.max(24, controlLeft(item) - 8 - ix);
        Ef.text(ctx, Ef.ellipsis(labelOf(item), labelW), ix, y + 5, p.ink());
        Ef.rule(ctx, ix - 4, y + ROW_H - 1, itemsWidth() + 8, Ef.alpha(p.rule(), 0.55F));

        if (ref instanceof FlagRef flag) {
            int sw = 20;
            int sh = 9;
            int sx = right - sw;
            int sy = y + (ROW_H - sh) / 2;
            if (flag.get()) {
                Ef.fill(ctx, sx, sy, sw, sh, p.ink());
                Ef.fill(ctx, sx + sw - 7, sy + 2, 5, 5, p.paper());
            } else {
                Ef.dashedOutline(ctx, sx, sy, sw, sh, p.ink2());
                Ef.fill(ctx, sx + 2, sy + 2, 5, 5, p.ink2());
            }
        } else if (isNumeric(ref)) {
            int minusX = right - BTN * 2 - 4;
            int plusX = right - BTN;
            int valueRight = minusX - 6;
            String text = valueText(ref);
            Ef.text(ctx, text, valueRight - Ef.textWidth(text), y + 5, p.ink());
            paintStep(ctx, p, minusX, y, "-", mouseX, mouseY);
            paintStep(ctx, p, plusX, y, "+", mouseX, mouseY);
        } else if (ref instanceof EnumRef<?>) {
            String text = enumText(ref) + " ▾";
            int width = Ef.textWidth(text) + 10;
            int chipX = right - width;
            boolean hot = mouseX >= chipX && mouseX <= right && mouseY >= y + 3 && mouseY < y + 15;
            Ef.cutFill(ctx, chipX, y + 3, width, 12, hot ? Ef.mix(p.chip(), p.ink(), 0.14F) : p.chip(), Ef.CUT);
            Ef.text(ctx, text, chipX + 5, y + 5, p.ink());
        } else {
            String text = Ef.ellipsis(valueText(ref), readOnlyWidth());
            Ef.text(ctx, text, right - Ef.textWidth(text), y + 5, p.ink2());
        }
    }

    /** 数值行两端的加减键：切角是"能按"的形状标记，悬停时补底色。 */
    private void paintStep(VDrawContext ctx, Ef.Palette p, int x, int y, String glyph, int mouseX, int mouseY) {
        boolean hot = mouseX >= x && mouseX < x + BTN && mouseY >= y + 2 && mouseY < y + 2 + BTN;
        if (hot) {
            Ef.cutFill(ctx, x, y + 2, BTN, BTN, p.chip(), Ef.CUT);
        }
        Ef.cutOutline(ctx, x, y + 2, BTN, BTN, hot ? p.ink() : p.rule(), Ef.CUT);
        Ef.text(ctx, glyph, x + 4, y + 4, hot ? p.ink() : p.ink2());
    }

    private void paintFooter(VDrawContext ctx, Ef.Palette p) {
        int y = winY + winH - FOOT_H;
        Ef.fill(ctx, winX, y, winW, FOOT_H, p.panel());
        Ef.rule(ctx, winX, y, winW, p.rule());

        String left = roots.size() + " 个配置  │  当前 " + all().size() + " 项设置";
        if (!query.trim().isEmpty()) {
            left = left + "  │  命中 " + shown().size() + " 项";
        }
        Ef.text(ctx, left, winX + 8, y + 3, p.ink2());

        String hint = "滚轮滚动  │  右键恢复默认";
        Ef.text(ctx, hint, winX + winW - 8 - Ef.textWidth(hint), y + 3, p.ink2());
    }

    private void paintFrame(VDrawContext ctx, Ef.Palette p) {
        int edge = p.ink();
        Ef.fill(ctx, winX, winY, winW, 1, edge);
        Ef.fill(ctx, winX, winY + winH - 1, winW, 1, edge);
        Ef.fill(ctx, winX, winY, 1, winH, edge);
        Ef.fill(ctx, winX + winW - 1, winY, 1, winH, edge);
    }

    /** 悬停说明：显示完整路径——总表里同名项可能很多，路径才是它的身份。 */
    private void paintTip(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        if (hovered == null) {
            return;
        }
        List<String> tip = new ArrayList<>();
        tip.addAll(Ef.wrap(hovered.path(), 240));
        Ref<?> ref = hovered.ref();
        if (ref == null) {
            tip.add("这条路径解析不出配置项，只能看。");
        } else {
            tip.add(writable(ref) ? "可以在这里改。" : "这一类项在这里只能看。");
            if (ref.hasDefaultValue()) {
                tip.add("右键恢复默认值");
            }
        }
        Ef.tooltip(ctx, p, tip, mouseX, mouseY, this.width, this.height);
    }

    // ------------------------------------------------------------------ 交互

    private boolean click(EfWidget self, double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (my >= winY && my < winY + barH) {
            return clickTabBar(mx, my);
        }
        // 搜索框那一行留给原版输入框，否则点不进焦点
        if (my >= searchTop() && my < bodyTop()) {
            return false;
        }
        if (my < bodyTop() || my >= bodyBottom()) {
            return false;
        }
        buildLines();
        if (mx >= winX && mx < winX + railW) {
            return button == 0 && clickRail(mx, my);
        }
        return clickItems(mx, my, button);
    }

    /** 分类条：点当前项什么都不做，点别的项当场换页，点右侧切深浅。 */
    private boolean clickTabBar(int mx, int my) {
        String hit = tabBar.click(winX, winY, winW, "Config", light(), mx, my);
        if (EndfieldTabBar.THEME.equals(hit)) {
            if (settings != null) {
                settings.light(!settings.light());
            }
            return true;
        }
        if (hit == null || "Config".equals(hit)) {
            return false;
        }
        Screen next = "Module".equals(hit) ? new EndfieldClickGuiScreen(settings) : EndfieldPages.create(hit, settings);
        if (next != null) {
            ScreenAccess.of(this).switchToScreen(next);
        }
        return true;
    }

    private boolean clickRail(int mx, int my) {
        if (my < railRowsTop()) {
            return false;
        }
        int offset = my - railRowsTop() + railScroll;
        int index = offset / RAIL_STEP;
        if (index < 0 || index >= roots.size() || offset % RAIL_STEP >= RAIL_CHIP_H) {
            return false;
        }
        int chipX = winX + 8;
        int chipW = Math.max(40, railW - 16);
        if (mx < chipX || mx > chipX + chipW) {
            return false;
        }
        Config target = roots.get(index);
        if (target != selected) {
            selected = target;
            itemScroll = 0;
        }
        return true;
    }

    private boolean clickItems(int mx, int my, int button) {
        if (my < listTop()) {
            return false;
        }
        for (Line line : lines) {
            if (line.item() == null || my < line.y() || my >= line.y() + line.height()) {
                continue;
            }
            return apply(line.item(), line.y(), mx, my, button);
        }
        return false;
    }

    private boolean apply(Item item, int rowY, int mx, int my, int button) {
        Ref<?> ref = item.ref();
        if (ref == null) {
            return false;
        }
        if (button == 1) {
            if (ref.hasDefaultValue()) {
                ref.resetValue();
                return true;
            }
            return false;
        }
        int right = itemsRight();
        if (ref instanceof FlagRef flag) {
            int sx = right - 20;
            if (mx >= sx - 2 && mx <= sx + 22) {
                flag.toggle();
                return true;
            }
            return false;
        }
        if (isNumeric(ref)) {
            if (my < rowY + 2 || my >= rowY + 2 + BTN) {
                return false;
            }
            int minusX = right - BTN * 2 - 4;
            int plusX = right - BTN;
            if (mx >= minusX && mx < minusX + BTN) {
                nudge(ref, -stepOf(ref));
                return true;
            }
            if (mx >= plusX && mx < plusX + BTN) {
                nudge(ref, stepOf(ref));
                return true;
            }
            return false;
        }
        if (ref instanceof EnumRef<?> enumRef) {
            int chipX = right - enumWidth(ref);
            if (mx >= chipX && mx <= right && my >= rowY + 3 && my < rowY + 15) {
                try {
                    enumRef.next();
                } catch (Throwable e) {
                    // 枚举类型还没注册完成时取值会抛，点一下不该把界面打断
                }
                return true;
            }
            return false;
        }
        return false;
    }

    private boolean wheel(EfWidget self, double mouseX, double mouseY, double amount) {
        int my = (int) mouseY;
        if (my < bodyTop() || my >= bodyBottom()) {
            return false;
        }
        buildLines();
        int step = (int) (Math.signum(amount) * 18.0D);
        if (mouseX >= winX && mouseX < winX + railW) {
            railScroll = Math.max(0, Math.min(maxRailScroll(), railScroll - step));
        } else {
            itemScroll = Math.max(0, Math.min(maxItemScroll(), itemScroll - step));
        }
        return true;
    }
}
