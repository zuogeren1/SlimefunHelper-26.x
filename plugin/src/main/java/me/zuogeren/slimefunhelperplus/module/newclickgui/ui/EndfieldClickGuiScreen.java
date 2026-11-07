package me.zuogeren.slimefunhelperplus.module.newclickgui.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.GenericScreen;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.hacks.api.ViewModuleGroup;
import me.matl114.hacks.modules.HackModules;
import me.matl114.hacks.modules.task.ClickGui;
import me.matl114.hacks.utils.gui.ClassicClickGuiScreen;
import me.matl114.managers.config.FlagRef;
import me.matl114.utils.ChatUtils;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.newclickgui.NewClickGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 自绘 ClickGui 主屏。
 *
 * <p>只依赖 {@link Ef} 给出的调色板与几何常量，不写死任何具体色值；
 * 换外观时改 {@code Ef} 那层即可。
 *
 * <p>版式：顶部深色命令栏（两套主题都是深色）、左侧分组列、中间模块记录行、右侧模块详情、底部状态条。
 * 全部自绘，复用宿主只到 {@link GenericScreen} 为止。
 *
 * <p>不碰宿主的 {@code ClickGuiMetaData}：面板位置不复用旧界面那套，也就不存在两套布局互相覆盖。
 * 每个分组各记一个滚动位置，切分组不会跳回顶部。
 *
 * <p>坐标约定：宿主先把矩阵平移到元素原点再回调，所以画的时候一律用局部坐标；
 * 鼠标给的是屏幕坐标，判定前必须先减去元素自身的 {@code x()/y()}。
 */
public class EndfieldClickGuiScreen extends GenericScreen {
    private static final int BAR_H = 22;
    /** 命令栏装不下时换行，行距就是这个数。 */
    private static final int TAB_STEP = 17;
    private static final int FOOT_H = 14;
    private static final int HEAD_H = 34;
    private static final int CHIP_H = 15;
    private static final int CHIP_GAP = 4;
    /** 记录行悬停时整行右移的距离。 */
    private static final int ROW_SHIFT = 6;
    /** 眉标与字标的横向拉伸：界面字体没有宽度轴，靠这一步做宽体拉丁。 */
    private static final float WIDE = 1.2F;

    private final EndfieldSettings settings;
    private List<String> groupNames;
    private final Map<String, Integer> scrollTop = new HashMap<>();
    private final Map<String, List<BaseModule>> moduleCache = new HashMap<>();
    private final Map<BaseModule, List<String>> tipCache = new HashMap<>();
    private String group;
    private BaseModule selected;
    /** 搜索词；空串表示不过滤。 */
    private String query = "";
    /** 入场动画的起点：打开界面、切分组、改搜索词都会重来一次。 */
    private long animStart = System.currentTimeMillis();
    /** 三色条扫过的起点，只在打开界面时重置。 */
    private long openStart = System.currentTimeMillis();
    /** 原版输入框，只负责收字和画字；框体由 paintMain 画。 */
    private EditBox searchBox;
    /** 详情面板里"打开配置"按钮的命中框，由绘制时写、点击时读。 */
    private int btnX;
    private int btnY;
    private int btnW;
    private int btnH;
    /** 窗口矩形：界面不再铺满屏幕，只占中间一块。 */
    private int winX;
    private int winY;
    private int winW;
    private int winH;
    /** 命令栏的实际高度：装不下时换成两行。 */
    private int barH = BAR_H;
    /** 每帧布局出来的分类页签，绘制与命中共用。 */
    private List<Tab> tabLayout = List.of();
    private String hoverTabKey;
    private long hoverTabStart;
    private boolean hoverDetail;
    private long hoverDetailStart;
    private boolean hoverButton;
    private long hoverButtonStart;
    private long buttonPressAt;
    /** 这一帧鼠标停在哪个记录行上，用来出说明框。 */
    private BaseModule hoveredModule;

    /** 一行的分类页签：坐标是命令栏的局部坐标。 */
    private record Tab(int x, int y, int w, String label, String key, boolean active) {}

    public EndfieldClickGuiScreen(EndfieldSettings settings) {
        super(Component.empty(), 0, 0);
        this.settings = settings;
        List<String> groups = groupNames();
        this.group = groups.isEmpty() ? "" : groups.get(0);
        List<BaseModule> first = modulesOf(this.group);
        this.selected = first.isEmpty() ? null : first.get(0);
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

    private ModuleGroup groupOf(String name) {
        ModuleGroup group = HackModules.getModuleGroup(name);
        if (group != null) {
            return group;
        }
        for (ViewModuleGroup view : HackModules.getViewGroups()) {
            if (view.getName().equals(name)) {
                return view;
            }
        }
        return null;
    }

    /** 模块清单按分组缓存：每帧重建 160 多个元素的 Stream 会白烧 CPU。 */
    private List<BaseModule> modulesOf(String name) {
        List<BaseModule> cached = moduleCache.get(name);
        if (cached != null) {
            return cached;
        }
        ModuleGroup moduleGroup = groupOf(name);
        List<BaseModule> list = moduleGroup == null
                ? List.of()
                : moduleGroup.getModules().stream().filter(BaseModule::shouldShowInGui).toList();
        moduleCache.put(name, list);
        return list;
    }

    /** 分组清单来自宿主模块表，但只读一遍：每帧重建会白白分配。 */
    private List<String> groupNames() {
        if (groupNames == null) {
            List<String> names = new ArrayList<>(HackModules.main.getModuleGroups().keySet());
            for (ViewModuleGroup view : HackModules.getViewGroups()) {
                names.add(view.getName());
            }
            groupNames = names;
        }
        return groupNames;
    }

    private static String plain(Component component) {
        return component == null ? "" : ChatUtils.textToPlainString(component);
    }

    /** 模块显示名：沿用宿主的翻译键约定，但不经过 ClickGui 实例，换界面也能用。 */
    public static String moduleLabel(BaseModule module) {
        String key = translationKey(module, "");
        return Component.translatableWithFallback(key, module.getName()).getString();
    }

    /** 模块说明：没有翻译时给一句自己的兜底文案。 */
    public static List<Component> descriptionOf(BaseModule module) {
        return ChatUtils.parseTooltipsTranslation(translationKey(module, ".tooltips"), "暂无介绍");
    }

    /** 悬停说明：没有翻译就不出框，别拿兜底文案冒充说明。 */
    private List<String> tipLines(BaseModule module) {
        List<String> cached = tipCache.get(module);
        if (cached != null) {
            return cached;
        }
        List<String> lines = new ArrayList<>();
        for (Component tip : ChatUtils.parseTooltipsTranslation(translationKey(module, ".tooltips"), "")) {
            String text = tip == null ? "" : tip.getString();
            if (text.isEmpty()) {
                continue;
            }
            lines.addAll(Ef.wrap(text, 240));
        }
        tipCache.put(module, lines);
        return lines;
    }

    private static String translationKey(BaseModule module, String suffix) {
        String manager = module.getModuleManager() == null
                ? ""
                : module.getModuleManager().getName();
        String prefix = manager.isEmpty()
                ? "widget.click-gui.module-name."
                : "widget.click-gui.module-name." + manager + ".";
        return prefix + module.getName() + suffix;
    }

    /** 顶部分类：模块页是本界面，其余交给宿主原有的页面。 */
    private static final String[][] TABS = {
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

    private Map<String, Function<Screen, DrawableWidget>> extras;

    private Map<String, Function<Screen, DrawableWidget>> extraSelections() {
        if (extras == null) {
            ClickGui clickGui = ClickGui.INSTANCE;
            extras = clickGui == null
                    ? new LinkedHashMap<>()
                    : clickGui.getExtraSelections(clickGui.getClickGuiMetadata());
        }
        return extras;
    }

    private void openHostPage(String key) {
        Function<Screen, DrawableWidget> page = extraSelections().get(key);
        if (page == null) {
            return;
        }
        Map<String, Function<Screen, DrawableWidget>> one = new LinkedHashMap<>();
        one.put(key, page);
        Screen screen = new ClassicClickGuiScreen(one, NewClickGui.elementFactory());
        ScreenAccess.of(screen).openFromCurrent();
    }

    private static boolean isOn(BaseModule module) {
        FlagRef flag = module.getBindFlag();
        return flag != null && flag.get();
    }

    /** 行尾那块写什么：有总开关写启停，没有就写这一页有多少项设置。 */
    private String chipText(BaseModule module) {
        FlagRef flag = module.getBindFlag();
        return flag != null ? (flag.get() ? "启用" : "停用") : settingsLabel(module);
    }

    /** 没有总开关的模块，用「N 项设置」代替启停；行尾块与详情面板共用这一句。 */
    private static String settingsLabel(BaseModule module) {
        int count = configCount(module);
        return count == 0 ? "无设置" : count + " 项设置";
    }

    /** 这一页实际能看到几项设置：和配置页用同一个可见性判据。 */
    private static int configCount(BaseModule module) {
        int count = 0;
        for (WrapperConfigRef<?> ref : module.getEditableConfig()) {
            if (ref.shouldShow()) {
                count++;
            }
        }
        return count;
    }

    /** 行尾块的左边界，绘制与命中共用。 */
    private int chipX(int width, BaseModule module) {
        return width - 12 - (Ef.textWidth(chipText(module)) + 8);
    }

    /** 行尾块的动作：有总开关就切开关，没有就打开它的设置页。 */
    private void activate(BaseModule module) {
        FlagRef flag = module.getBindFlag();
        if (flag != null) {
            flag.toggle();
        } else {
            openConfig(module);
        }
    }

    private int enabledOf(List<BaseModule> modules) {
        int count = 0;
        for (BaseModule module : modules) {
            if (isOn(module)) {
                count++;
            }
        }
        return count;
    }

    private int totalEnabled() {
        int count = 0;
        for (String name : groupNames()) {
            count += enabledOf(modulesOf(name));
        }
        return count;
    }

    private int totalModules() {
        int count = 0;
        for (String name : groupNames()) {
            count += modulesOf(name).size();
        }
        return count;
    }

    private int scrollOf(String name) {
        return scrollTop.getOrDefault(name, 0);
    }

    private int firstRow(List<BaseModule> modules) {
        int max = Math.max(0, modules.size() - visibleRows());
        return Math.max(0, Math.min(scrollOf(group), max));
    }

    // ------------------------------------------------------------------ 布局

    private int railWidth() {
        return Math.max(84, Math.min(150, winW / 6));
    }

    private int detailWidth() {
        return winW >= 420 ? Math.max(120, Math.min(190, winW / 5)) : 0;
    }

    private int bodyHeight() {
        return winH - barH - FOOT_H;
    }

    /** 一行 = 中文名 + 英文 id，按当前字体行高算，换字体也不会挤在一起。 */
    private static int rowH() {
        return Ef.lineH() * 2 + 7;
    }

    private int visibleRows() {
        return Math.max(1, (bodyHeight() - HEAD_H) / rowH());
    }

    /** 窄窗口里省掉字标，只留徽记，把宽度让给分类页签。 */
    private boolean showBrand() {
        return winW >= 520;
    }

    /** 徽记到第一个页签之间的距离；绘制与布局共用，改这里就够了。 */
    private int brandWidth() {
        return showBrand() ? Ef.trackedWidth("CLICKGUI", 1) + 36 : 18;
    }

    private int themeToggleX(int width) {
        String esc = "Esc 关闭";
        String theme = light() ? "深色" : "浅色";
        return width - 8 - Ef.textWidth(esc) - 10 - Ef.textWidth(theme);
    }

    /**
     * 分类页签按可用宽度排；装不下就换行，命令栏跟着变高。
     * 这样窄窗口不会把页签画到主题开关和 Esc 提示上，也不会藏起某个页面。
     */
    private void layoutTabs() {
        List<Tab> tabs = new ArrayList<>();
        int x = 6 + brandWidth();
        int y = 4;
        int limit = winW >= 320 ? themeToggleX(winW) - 6 : winW - 6;
        for (String[] tab : TABS) {
            boolean module = "Module".equals(tab[1]);
            if (!module && !extraSelections().containsKey(tab[1])) {
                continue;
            }
            int w = Ef.textWidth(tab[0]) + 10;
            if (x + w > limit) {
                x = 6;
                y += TAB_STEP;
                limit = winW - 6;
                w = Math.min(w, Math.max(20, limit - x));
            }
            tabs.add(new Tab(x, y, w, tab[0], tab[1], module));
            x += w + 4;
        }
        this.tabLayout = tabs;
        this.barH = Math.max(BAR_H, y + 19);
    }

    // ------------------------------------------------------------------ 控件

    @Override
    protected void init0() {
        super.init0();
        this.x = 0;
        this.y = 0;
        this.animStart = System.currentTimeMillis();
        this.openStart = System.currentTimeMillis();
        this.extras = null;
        this.moduleCache.clear();
        this.tipCache.clear();
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        winW = Math.min(this.width - 8, Math.max(280, Math.round(this.width * 0.78F)));
        winH = Math.min(this.height - 8, Math.max(200, Math.round(this.height * 0.78F)));
        winX = (this.width - winW) / 2;
        winY = (this.height - winH) / 2;
        layoutTabs();

        int railW = railWidth();
        int detailW = detailWidth();
        int mainW = winW - railW - detailW;

        EfWidget.of(winX, winY, winW, barH, this::paintBar)
                .click(this::clickBar)
                .addTo(this);

        EfWidget.of(winX, winY + barH, railW, bodyHeight(), this::paintRail)
                .click(this::clickRail)
                .addTo(this);

        EfWidget.of(winX + railW, winY + barH, mainW, bodyHeight(), this::paintMain)
                .click(this::clickMain)
                .scroll(this::scrollMain)
                .addTo(this);

        if (detailW > 0) {
            EfWidget.of(winX + railW + mainW, winY + barH, detailW, bodyHeight(), this::paintDetail)
                    .click(this::clickDetail)
                    .addTo(this);
        }

        EfWidget.of(winX, winY + winH - FOOT_H, winW, FOOT_H, this::paintFooter)
                .addTo(this);

        // 搜索框交给原版 EditBox：输入法、退格、粘贴都由 MC 自己处理
        int searchWidth = searchWidth(mainW);
        // 前缀 `//` 也占位：输入框起点按实测宽度让开，字不会压到前缀上
        int prefixW = Ef.trackedWidth("//", 1) + 4;
        EditBox box = new EditBox(
                Minecraft.getInstance().font,
                winX + railW + searchX(mainW) + 4 + prefixW,
                winY + barH + 4,
                Math.max(24, searchWidth - 8 - prefixW),
                9,
                Component.literal("搜索"));
        box.setBordered(false);
        box.setMaxLength(32);
        box.setHint(Component.literal("搜索模块"));
        box.setValue(query);
        box.setResponder(text -> {
            query = text;
            scrollTop.put(group, 0);
            animStart = System.currentTimeMillis();
        });
        this.searchBox = box;
        this.addRenderableWidget(box);

        // 最后加的是最上层：只画 1px 窗框，点击直接放行（没有 clicker）
        EfWidget.of(winX, winY, winW, winH, this::paintFrame).addTo(this);
    }

    private void paintFrame(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        Ef.Palette p = pal();
        int w = self.getTextureWidth();
        int h = self.getTextureHeight();
        int edge = p.ink();
        Ef.fill(ctx, 0, 0, w, 1, edge);
        Ef.fill(ctx, 0, h - 1, w, 1, edge);
        Ef.fill(ctx, 0, 0, 1, h, edge);
        Ef.fill(ctx, w - 1, 0, 1, h, edge);
    }

    // ------------------------------------------------------------------ 命令栏

    /** 三色条扫过：打开界面时从左往右画出来，之后一直静止。 */
    private void paintTriBar(VDrawContext ctx, int w) {
        int drawn = Math.round(w * Ef.ease(Ef.progress(openStart, 600.0F)));
        int third = Math.max(1, w / 3);
        int a = Math.min(third, drawn);
        if (a > 0) {
            Ef.fill(ctx, 0, 0, a, 3, Ef.TRI_A);
        }
        int b = Math.min(third, drawn - third);
        if (b > 0) {
            Ef.fill(ctx, third, 0, b, 3, Ef.SIGNAL);
        }
        int c = drawn - third * 2;
        if (c > 0) {
            Ef.fill(ctx, third * 2, 0, c, 3, Ef.TRI_C);
        }
    }

    private void paintBar(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        int w = self.getTextureWidth();
        int h = self.getTextureHeight();
        int mx = (int) (mouseX - self.x());
        int my = (int) (mouseY - self.y());
        Ef.fill(ctx, 0, 0, w, h, Ef.BAR_BG);
        paintTriBar(ctx, w);

        Ef.cutFill(ctx, 6, 5, 12, 12, Ef.SIGNAL, Ef.CUT);
        Ef.text(ctx, "S", 9, 7, Ef.ON_SIGNAL);
        if (showBrand()) {
            Ef.trackedWide(ctx, "CLICKGUI", 24, 7, Ef.BAR_INK, 1, WIDE);
            Ef.fill(ctx, 24 + Ef.trackedWideWidth("CLICKGUI", 1, WIDE) + 8, 5, 1, 12, Ef.BAR_RULE);
        }

        // 悬停的分区在底边擦一条强调色线：命令栏是深色的，这里用线不违反"只做填充"的用意
        String hot = null;
        for (Tab tab : tabLayout) {
            if (mx >= tab.x && mx <= tab.x + tab.w && my >= tab.y && my <= tab.y + CHIP_H) {
                hot = tab.key;
            }
        }
        if (hot != null && !hot.equals(hoverTabKey)) {
            hoverTabKey = hot;
            hoverTabStart = System.currentTimeMillis();
        } else if (hot == null) {
            hoverTabKey = null;
        }

        for (Tab tab : tabLayout) {
            if (tab.active) {
                Ef.cutFill(ctx, tab.x, tab.y, tab.w, CHIP_H, Ef.SIGNAL, Ef.CUT);
                Ef.text(ctx, tab.label, tab.x + 5, tab.y + 3, Ef.ON_SIGNAL);
                continue;
            }
            boolean over = tab.key.equals(hoverTabKey);
            Ef.text(ctx, tab.label, tab.x + 5, tab.y + 3, over ? Ef.BAR_INK : Ef.BAR_INK2);
            if (over) {
                float t = Ef.mech(Ef.progress(hoverTabStart, 280.0F));
                Ef.fill(ctx, tab.x, tab.y + CHIP_H, Math.round(tab.w * t), 2, Ef.SIGNAL);
            }
        }

        if (winW < 360) {
            // 极窄时右侧提示让位给分类
            return;
        }
        String esc = "Esc 关闭";
        Ef.text(ctx, esc, w - 8 - Ef.textWidth(esc), 7, Ef.BAR_INK2);
        int themeX = themeToggleX(w);
        Ef.fill(ctx, themeX - 6, 6, 1, 10, Ef.BAR_RULE);
        Ef.text(ctx, light() ? "深色" : "浅色", themeX, 7, Ef.BAR_INK);
    }

    private boolean clickBar(EfWidget self, double mouseX, double mouseY, int button) {
        int mx = (int) (mouseX - self.x());
        int my = (int) (mouseY - self.y());
        for (Tab tab : tabLayout) {
            if (mx >= tab.x && mx <= tab.x + tab.w && my >= tab.y && my <= tab.y + CHIP_H) {
                if (!tab.active) {
                    openHostPage(tab.key);
                }
                return true;
            }
        }
        if (winW < 360) {
            return false;
        }
        int themeX = themeToggleX(self.getTextureWidth());
        if (mx >= themeX - 8 && mx <= themeX + Ef.textWidth("浅色") + 2 && my <= BAR_H) {
            if (settings != null) {
                settings.light(!settings.light());
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 分组列

    private void paintRail(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        Ef.Palette p = pal();
        int w = self.getTextureWidth();
        int h = self.getTextureHeight();
        int mx = (int) (mouseX - self.x());
        int my = (int) (mouseY - self.y());
        Ef.fill(ctx, 0, 0, w, h, p.paper());
        Ef.fill(ctx, w - 1, 0, 1, h, p.rule());

        Ef.trackedWide(ctx, "// GROUPS", 8, 8, p.ink2(), 1, WIDE);
        Ef.text(ctx, "分组", 8, 17, p.ink());
        String total = groupNames().size() + " 组";
        Ef.text(ctx, total, 8, 29, p.ink2());

        int y = 42;
        for (String name : groupNames()) {
            if (y + CHIP_H > h - 4) {
                break;
            }
            boolean current = name.equals(group);
            boolean hover = mx >= 6 && mx <= w - 8 && my >= y && my < y + CHIP_H;
            int bg = current ? Ef.SIGNAL : (hover ? Ef.mix(p.chip(), p.ink(), 0.12f) : p.chip());
            int fg = current ? Ef.ON_SIGNAL : p.ink();
            int cut = Ef.CUT_WIDE;
            int chipW = w - 14;
            Ef.cutFill(ctx, 6, y, chipW, CHIP_H, bg, cut);

            List<BaseModule> modules = modulesOf(name);
            Ef.text(ctx, displayGroupName(name), 11, y + 4, fg);
            String count = enabledOf(modules) + "/" + modules.size();
            // 切角会啃掉右下角，文字右边缘必须退出切角宽度，否则末位数字会画到背景上
            int countX = 6 + chipW - cut - 3 - Ef.textWidth(count);
            Ef.text(ctx, count, countX, y + 4, current ? Ef.ON_SIGNAL : p.ink2());
            y += CHIP_H + CHIP_GAP;
        }
    }

    private String displayGroupName(String name) {
        return switch (name) {
            case "Chat" -> "聊天";
            case "Combat" -> "战斗";
            case "Extra" -> "额外";
            case "Interaction" -> "交互";
            case "Inv" -> "背包";
            case "Mine" -> "挖掘";
            case "Model" -> "模型";
            case "Move" -> "移动";
            case "Render" -> "渲染";
            case "Slimefun" -> "粘液";
            case "Survival" -> "生存";
            case "Tasks" -> "任务";
            default -> name;
        };
    }

    private boolean clickRail(EfWidget self, double mouseX, double mouseY, int button) {
        int w = self.getTextureWidth();
        int mx = (int) (mouseX - self.x());
        int my = (int) (mouseY - self.y());
        if (mx < 6 || mx > w - 8) {
            return false;
        }
        int y = 42;
        for (String name : groupNames()) {
            if (my >= y && my < y + CHIP_H) {
                if (!name.equals(group)) {
                    group = name;
                    query = "";
                    animStart = System.currentTimeMillis();
                    if (searchBox != null) {
                        searchBox.setValue("");
                    }
                    List<BaseModule> modules = modulesOf(group);
                    selected = modules.isEmpty() ? null : modules.get(0);
                }
                return true;
            }
            y += CHIP_H + CHIP_GAP;
        }
        return false;
    }

    // ------------------------------------------------------------------ 模块列表

    private int searchWidth(int mainW) {
        return Math.max(80, Math.min(150, mainW / 3));
    }

    private int searchX(int mainW) {
        return mainW - 10 - searchWidth(mainW);
    }

    /** 搜索过滤后的清单；空查询就是整组。 */
    private List<BaseModule> shown() {
        List<BaseModule> all = modulesOf(group);
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return all;
        }
        return all.stream()
                .filter(m -> (moduleLabel(m) + " " + m.getName()).toLowerCase(Locale.ROOT).contains(q))
                .toList();
    }

    /** 入场进度：每行错开一点点，缓动近似 snap（很快到位、稍后停稳）。 */
    private float rowProgress(int index, int count) {
        long elapsed = System.currentTimeMillis() - animStart;
        int stagger = Math.min(38, Math.max(8, 520 / Math.max(1, count)));
        float t = (elapsed - (long) index * stagger) / 260.0F;
        if (t <= 0.0F) {
            return 0.0F;
        }
        if (t >= 1.0F) {
            return 1.0F;
        }
        return Ef.snap(t);
    }

    private void paintMain(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        Ef.Palette p = pal();
        int w = self.getTextureWidth();
        int h = self.getTextureHeight();
        int my = (int) (mouseY - self.y());
        int mx = (int) (mouseX - self.x());
        Ef.fill(ctx, 0, 0, w, h, p.paper());

        List<BaseModule> all = modulesOf(group);
        List<BaseModule> modules = shown();
        hoveredModule = null;

        // 搜索框的框体：输入框自己只画字，边框、前缀和聚焦提示在这里画
        int boxW = searchWidth(w);
        int boxX = searchX(w);
        boolean focused = searchBox != null && searchBox.isFocused();
        Ef.fill(ctx, boxX, 2, boxW, 13, p.panel());
        Ef.cutOutline(ctx, boxX, 2, boxW, 13, focused ? p.ink() : p.rule(), Ef.CUT);
        if (focused) {
            // 聚焦时下边换 2px 的当前位置线，颜色跟主题走（浅色是墨、深色是强调色）
            Ef.fill(ctx, boxX + 1, 12, boxW - 2, 2, p.trace());
        }
        Ef.tracked(ctx, "//", boxX + 4, 4, p.ink2(), 1);
        if (searchBox != null) {
            searchBox.setTextColor(p.ink());
        }

        Ef.trackedWide(ctx, "// " + group.toUpperCase(Locale.ROOT), 10, 8, p.ink2(), 1, WIDE);
        Ef.text(ctx, displayGroupName(group), 10, 17, p.ink());

        String meta = enabledOf(all) + " / " + all.size() + " 启用";
        Ef.text(ctx, meta, w - 10 - Ef.textWidth(meta), 17, p.ink2());
        Ef.rule(ctx, 10, HEAD_H - 4, w - 20, p.rule());

        int count = visibleRows();
        int start = firstRow(modules);
        for (int i = 0; i < count; i++) {
            int index = start + i;
            if (index >= modules.size()) {
                break;
            }
            paintRow(ctx, p, w, HEAD_H + i * rowH(), modules.get(index), index, mx, my, rowProgress(i, count));
        }

        if (modules.isEmpty()) {
            String empty = query.isEmpty()
                    ? "这个分组里还没有模块。"
                    : "没有匹配「" + query + "」的模块，换个词试试。";
            Ef.text(ctx, empty, 12, HEAD_H + 6, p.ink2());
        }

        Ef.scrollbar(ctx, w - 3, HEAD_H, h - HEAD_H - 4, start, modules.size(), count, p.rule(), p.ink2());

        if (hoveredModule != null) {
            Ef.tooltip(ctx, p, tipLines(hoveredModule), mx, my, w, h);
        }
    }

    private void paintRow(
            VDrawContext ctx,
            Ef.Palette p,
            int w,
            int y,
            BaseModule module,
            int index,
            int mouseX,
            int mouseY,
            float reveal) {
        int rh = rowH();
        int lh = Ef.lineH();
        float alpha = Math.max(0.0F, Math.min(1.0F, reveal));
        int shift = (int) ((1.0F - alpha) * 6.0F);

        boolean hover = mouseY >= y && mouseY < y + rh && mouseX >= 0 && mouseX < w;
        boolean isSelected = module == selected;
        if (hover) {
            hoveredModule = module;
            Ef.fill(ctx, 0, y, w, rh, Ef.mix(p.paper(), p.ink(), 0.05f));
        }
        if (hover || isSelected) {
            Ef.fill(ctx, 0, y, 4, rh, p.trace());
        }

        int ink = Ef.alpha(p.ink(), alpha);
        int ink2 = Ef.alpha(p.ink2(), alpha);
        int rule = Ef.alpha(p.rule(), alpha);
        int tagBg = Ef.alpha(p.tagBg(), alpha);
        int tagFg = Ef.alpha(p.tagFg(), alpha);
        int signal = Ef.alpha(Ef.SIGNAL, alpha);
        int onSignal = Ef.alpha(Ef.ON_SIGNAL, alpha);

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(hover ? ROW_SHIFT : 0, shift);
        try {
            int tagH = lh + 2;
            int tagY = y + (rh - tagH) / 2;
            int tx = 10;
            String no = String.format(Locale.ROOT, "No.%03d", index + 1);
            int tagW = Math.max(30, Ef.textWidth(no) + 9);
            Ef.cutFill(ctx, tx, tagY, tagW, tagH, isSelected ? signal : tagBg, Ef.CUT);
            Ef.text(ctx, no, tx + 3, tagY + 1, isSelected ? onSignal : tagFg);

            // 行尾那块：有总开关就是「启用/停用」，没有总开关就写这一页有多少项设置
            String chip = chipText(module);
            int chipW = Ef.textWidth(chip) + 8;
            int chipH = lh + 2;
            int chipX = w - 12 - chipW;
            int chipY = y + (rh - chipH) / 2;
            boolean chipHover = hover && mouseX >= chipX - 3 && mouseX <= chipX + chipW + 3;

            int nameX = tx + tagW + 6;
            int nameW = Math.max(10, chipX - nameX - 10);
            String cn = moduleLabel(module);
            String en = module.getName();
            // 没有翻译的模块只画一行，否则两行字一模一样，看着像重影
            boolean twoLines = !en.equals(cn) && !en.isEmpty();
            int gap = 2;
            int blockH = twoLines ? lh * 2 + gap : lh;
            int textTop = y + (rh - blockH) / 2;
            Ef.text(ctx, Ef.ellipsis(cn, nameW), nameX, textTop, ink);
            if (twoLines) {
                Ef.text(ctx, Ef.ellipsis(en, nameW), nameX, textTop + lh + gap, ink2);
            }

            FlagRef flag = module.getBindFlag();
            if (flag == null) {
                int bg = chipHover ? Ef.mix(p.chip(), p.ink(), 0.14F) : p.chip();
                Ef.cutFill(ctx, chipX, chipY, chipW, chipH, Ef.alpha(bg, alpha), Ef.CUT);
                Ef.text(ctx, chip, chipX + 4, chipY + 1, ink2);
            } else if (flag.get()) {
                int bg = chipHover ? Ef.mix(ink, signal, 0.22F) : ink;
                Ef.fill(ctx, chipX, chipY, chipW, chipH, bg);
                Ef.text(ctx, chip, chipX + 4, chipY + 1, Ef.alpha(p.paper(), alpha));
            } else {
                Ef.dashedOutline(ctx, chipX, chipY, chipW, chipH, rule);
                Ef.text(ctx, chip, chipX + 4, chipY + 1, ink2);
            }

        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    private boolean clickMain(EfWidget self, double mouseX, double mouseY, int button) {
        int my = (int) (mouseY - self.y());
        int mx = (int) (mouseX - self.x());
        List<BaseModule> modules = shown();
        int start = firstRow(modules);
        if (my < HEAD_H) {
            return false;
        }
        int index = start + (my - HEAD_H) / rowH();
        if (index < 0 || index >= modules.size()) {
            return false;
        }
        BaseModule module = modules.get(index);
        if (button == 1) {
            selected = module;
            openConfig(module);
            return true;
        }
        // 悬停时整行右移，行尾块跟着走，命中框也要跟着挪
        int chipLeft = chipX(self.getTextureWidth(), module) + ROW_SHIFT;
        boolean onChip = mx >= chipLeft - 3
                && mx <= chipLeft + Ef.textWidth(chipText(module)) + 11
                && my < HEAD_H + (index - start + 1) * rowH();
        if (onChip) {
            // 直接点行尾那块：有开关就切，没开关就进它的设置页
            selected = module;
            activate(module);
            return true;
        }
        if (module != selected) {
            // 第一次点只选中，不动开关
            selected = module;
            return true;
        }
        activate(module);
        return true;
    }

    private boolean scrollMain(EfWidget self, double mouseX, double mouseY, double amount) {
        List<BaseModule> modules = shown();
        int max = Math.max(0, modules.size() - visibleRows());
        int next = (int) (scrollOf(group) - Math.signum(amount) * 2);
        scrollTop.put(group, Math.max(0, Math.min(max, next)));
        return true;
    }

    // ------------------------------------------------------------------ 详情

    private void paintDetail(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        Ef.Palette p = pal();
        int w = self.getTextureWidth();
        int h = self.getTextureHeight();
        int mx = (int) (mouseX - self.x());
        int my = (int) (mouseY - self.y());
        Ef.fill(ctx, 0, 0, w, h, p.paper());
        Ef.fill(ctx, 0, 0, 1, h, p.rule());

        int px = 8;
        int py = 10;
        int pw = w - 16;
        int ph = h - 20;
        boolean over = mx >= px && mx <= px + pw && my >= py && my <= py + ph;
        if (over && !hoverDetail) {
            hoverDetailStart = System.currentTimeMillis();
        }
        hoverDetail = over;
        // 看板按"朝向中间那块"切角：这块在右侧，所以切左下
        Ef.cutFill(ctx, px, py, pw, ph, p.panel(), Ef.CUT_PANEL, Ef.Corner.BL);
        float tabT = over ? Ef.mech(Ef.progress(hoverDetailStart, 400.0F)) : 0.0F;
        int lead = Math.max(8, pw / 7);
        Ef.fill(ctx, px, py, lead + Math.round((pw - lead) * tabT), 3, p.ink());

        int cx = px + 8;
        int innerW = pw - 16;
        int lh = Ef.lineH();
        int cy = py + 10;
        Ef.trackedWide(ctx, "// MODULE", cx, cy, p.ink2(), 1, WIDE);
        cy += lh + 3;
        if (selected == null) {
            Ef.text(ctx, "左侧选一个模块。", cx, cy, p.ink2());
            return;
        }

        Ef.text(ctx, Ef.ellipsis(moduleLabel(selected), innerW), cx, cy, p.ink());
        cy += lh;
        Ef.trackedWide(ctx, selected.getName().toUpperCase(Locale.ROOT), cx, cy, p.ink2(), 1, WIDE);
        cy += lh + 3;

        int stateH = lh + 2;
        FlagRef bindFlag = selected.getBindFlag();
        if (bindFlag == null) {
            // 没有总开关的模块不显示启停，改写成这一页有多少项设置
            String kind = settingsLabel(selected);
            int kindW = Ef.textWidth(kind) + 8;
            Ef.cutFill(ctx, cx, cy, kindW, stateH, p.chip(), Ef.CUT);
            Ef.text(ctx, kind, cx + 4, cy + 1, p.ink2());
        } else {
            boolean on = bindFlag.get();
            String state = on ? "启用" : "停用";
            int stateW = Ef.textWidth(state) + 8;
            if (on) {
                Ef.fill(ctx, cx, cy, stateW, stateH, p.ink());
                Ef.text(ctx, state, cx + 4, cy + 1, p.paper());
            } else {
                Ef.dashedOutline(ctx, cx, cy, stateW, stateH, p.rule());
                Ef.text(ctx, state, cx + 4, cy + 1, p.ink2());
            }
        }
        cy += stateH + 5;

        List<BaseModule> modules = modulesOf(group);
        int index = modules.indexOf(selected);
        String meta = index < 0
                ? displayGroupName(group)
                : String.format(Locale.ROOT, "No.%03d", index + 1) + "  │  " + displayGroupName(group);
        Ef.text(ctx, meta, cx, cy, p.ink2());
        cy += lh + 5;

        String desc = "";
        List<Component> tips = descriptionOf(selected);
        if (!tips.isEmpty()) {
            desc = plain(tips.get(0));
        }
        if (desc.isEmpty()) {
            desc = "这个模块没有写说明。";
        }
        if (bindFlag == null) {
            // 没有总开关的模块，先说明开关在哪
            desc = "没有总开关，功能在设置里逐项开关。" + desc;
        }
        List<String> lines = Ef.wrap(desc, innerW);
        int ly = cy;
        for (int i = 0; i < Math.min(4, lines.size()); i++) {
            Ef.text(ctx, lines.get(i), cx, ly, p.ink2());
            ly += lh + 2;
        }

        Ef.rule(ctx, cx, ly + 4, innerW, p.rule());
        int figY = ly + 12;
        Ef.text(ctx, String.valueOf(enabledOf(modules)), cx, figY, p.ink());
        Ef.text(ctx, "本组启用", cx, figY + lh + 2, p.ink2());
        Ef.text(ctx, String.valueOf(modules.size()), cx + innerW / 2, figY, p.ink());
        Ef.text(ctx, "本组总数", cx + innerW / 2, figY + lh + 2, p.ink2());

        btnX = cx;
        btnY = figY + lh + 16;
        btnW = Math.min(96, innerW);
        btnH = Math.max(14, lh + 5);
        boolean pressed = System.currentTimeMillis() - buttonPressAt < 100L;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(0, pressed ? 1 : 0);
        try {
            boolean overBtn = mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH;
            if (overBtn && !hoverButton) {
                hoverButtonStart = System.currentTimeMillis();
            }
            hoverButton = overBtn;
            float wipe = overBtn ? Ef.mech(Ef.progress(hoverButtonStart, 280.0F)) : 0.0F;
            // 主要按钮：静止墨底，悬停时强调色从左擦入，字在色块经过时翻成墨色
            Ef.wipeCut(ctx, btnX, btnY, btnW, btnH, p.ink(), Ef.SIGNAL, wipe, Ef.CUT_WIDE, Ef.Corner.BR);
            int front = Math.round(btnW * wipe);
            Ef.text(ctx, "打开配置", btnX + 6, btnY + (btnH - lh) / 2 + 1, front > 6 ? p.ink() : p.paper());
        } finally {
            ctx.getMatrices().popMatrix();
        }
        Ef.text(ctx, "右键模块行也能打开", cx, btnY + btnH + 6, p.ink2());
    }

    private boolean clickDetail(EfWidget self, double mouseX, double mouseY, int button) {
        if (selected == null || button != 0) {
            return false;
        }
        int my = (int) (mouseY - self.y());
        int mx = (int) (mouseX - self.x());
        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
            buttonPressAt = System.currentTimeMillis();
            openConfig(selected);
            return true;
        }
        return false;
    }

    /** 打开本插件自己的配置页；仍留在新界面里，不跳回旧布局。 */
    private void openConfig(BaseModule module) {
        // openFromCurrent 会把当前屏设成 parent，宿主在 onClose 里把 setScreen 重定向回 parent，
        // 所以"返回"不用自己管。
        ScreenAccess.of(new EndfieldModuleConfigScreen(module, settings)).openFromCurrent();
    }

    // ------------------------------------------------------------------ 状态条

    private void paintFooter(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        Ef.Palette p = pal();
        int w = self.getTextureWidth();
        Ef.fill(ctx, 0, 0, w, FOOT_H, p.panel());
        Ef.rule(ctx, 0, 0, w, p.rule());
        String left = totalEnabled() + " / " + totalModules() + " 已启用";
        Ef.text(ctx, left, 8, 3, p.ink2());
        String mid = displayGroupName(group) + "  │  " + modulesOf(group).size() + " 项";
        Ef.text(ctx, mid, 8 + Ef.textWidth(left) + 12, 3, p.ink2());
        String right = "单击选中 │ 再点切换 │ 右键配置";
        if (w - 8 - Ef.textWidth(right) > 8 + Ef.textWidth(left) + 12 + Ef.textWidth(mid) + 8) {
            Ef.text(ctx, right, w - 8 - Ef.textWidth(right), 3, p.ink2());
        }
    }
}
