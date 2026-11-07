package me.zuogeren.slimefunhelperplus.module.newclickgui.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.Constants;
import me.matl114.gui.GenericScreen;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.utils.config.OptionalPrimitive;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.config.WrapEnum;
import me.matl114.managers.config.ConfigEnum;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.FloatRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.LongRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.Ref;
import me.matl114.managers.input.KeyCode;
import me.matl114.managers.input.MultiKeyBind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.config.NumberClamp;
import me.matl114.utils.config.AttrKeyValue;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.newclickgui.NewClickGui;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages.EndfieldPages;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/**
 * 模块配置页：把 {@code getEditableConfig()} 里的条目按真实配置前缀分组，画成一张表单。
 *
 * <p>整页只有**一个**绘制控件、铺满屏幕，所有绘制与命中都用屏幕绝对坐标。
 * 之前按窗口切成几个子控件时，坐标系一层套一层（控件偏移 + 局部 + 裁剪），
 * 每加一个特性就要重算一遍，这里直接去掉那一层。
 *
 * <p>控件分两类：开关 / 整数步进 / 比例滑杆 / 枚举下拉 / 键位绑定 / 颜色各走专用控件；
 * 其余类型（列表、引用、格式化文本等）统一走「对象 → 文本 → 对象」——
 * 文本由 ref 自己的 {@code AttrKeyValue} 负责转换，和原配置界面同一套 stringify 工厂，
 * 所以这里不再有「不支持」的分支。
 */
public class EndfieldModuleConfigScreen extends GenericScreen {
    private static final int BAR_H = 22;
    private static final int FOOT_H = 14;
    private static final int ROW_H = 18;
    private static final int SECTION_H = 24;
    private static final int PAD = 14;
    /** 数值槽位的固定宽度：宽度稳定，滑轨才不会随数值跳动。 */
    private static final int TEXT_W = 40;
    /** 行尾重置按钮占的宽度。 */
    private static final int RESET_W = 16;

    /** 调色盘的格子步长：方块与色相条都按它铺，命中按同一格宽折算，点到哪格就是哪格。 */
    private static final int PAL_CELL = 4;
    /** 饱和度/明度方块：26×16 格；宽度贴着标题行的内宽取，方块右边才不留空。 */
    private static final int PAL_SV_W = 104;
    private static final int PAL_SV_H = 64;
    /** 色相条：与方块同宽，一格一色相。 */
    private static final int PAL_HUE_H = 12;
    /** 方块与色相条之间的空档。 */
    private static final int PAL_GAP = 4;
    /** 面板内边距。 */
    private static final int PAL_PAD = 8;
    /** 标题行（{@code // COLOR} 与当前十六进制）的高度。 */
    private static final int PAL_HEAD = 14;
    /** 动作节的分节键：不会与任何配置路径相同（配置路径里不可能出现 NUL）。 */
    private static final String ACTION_SECTION = "\u0000actions";
    /** 数值格的下标：-1 是整值，>= 0 是记录分量，-2 是「可选值」里那一层。 */
    private static final int OPTIONAL_PART = -2;

    /** 模块页就有它；好友/宏/快捷键/全配置这类"非模块页"为 null，命令栏不出启用块。 */
    private final BaseModule module;

    /** 命令栏的英文眉标与中文标题。 */
    private final String latin;

    private final String title;

    /** 这一页要渲染哪些配置项：模块页是模块自己的，其它页由调用方拼。 */
    private final java.util.function.Supplier<List<WrapperConfigRef<?>>> source;

    /** 作为顶部分类页时，命令栏画整条分类条并把这一项标成当前；模块配置页为 null（画返回 + 标题）。 */
    private final String tabKey;

    /** 命令栏：分类页与其他页共用同一条，切分类在同一屏里完成。 */
    private EndfieldTabBar tabBar;

    /** 命令栏实际高度（分类换行时会变高）。 */
    private int barH = BAR_H;

    protected final EndfieldSettings settings;

    private int winX;
    private int winY;
    private int winW;
    private int winH;

    private int contentLeft;
    private int contentWidth;
    private int scroll;
    private int totalHeight;

    private final List<Row> rows = new ArrayList<>();

    /** 打开中的枚举下拉：绘制与命中都用记录下来的矩形；{@code dropPart >= 0} 表示下拉的是记录里的那一格。 */
    private WrapperConfigRef<?> dropdown;
    private int dropPart = -1;
    private int dropX;
    private int dropY;
    private int dropW;

    /** 非原生类型交给宿主控件：按 ref 缓存实例，位置每帧跟着行走。 */
    private final Map<WrapperConfigRef<?>, DrawableWidget> editors = new IdentityHashMap<>();
    /** 本帧每个可见行留给宿主控件的矩形（绝对坐标）。 */
    private final Map<WrapperConfigRef<?>, int[]> editorRects = new IdentityHashMap<>();

    /** 悬停中的行与它的说明缓存。 */
    private WrapperConfigRef<?> hovered;
    private final Map<WrapperConfigRef<?>, List<Component>> tooltipCache = new IdentityHashMap<>();

    /** 页面自己的动作行：排在配置项之后，绘制与命中共用这份布局。 */
    private final List<ActionRow> actionRows = new ArrayList<>();
    private Action hoveredAction;

    /** 正在等待按键的键位项。 */
    private WrapperConfigRef<?> keyTarget;

    /** 正在拖动的滑杆：part < 0 表示整值，否则是记录里的分量下标。 */
    private WrapperConfigRef<?> dragRef;
    private int dragPart = -1;
    private int dragTrackX;
    private int dragTrackW;
    private double dragMin;
    private double dragMax;

    /** 手动输入数值的输入框。 */
    private WrapperConfigRef<?> numberTarget;
    private int numberPart = -1;
    private int numberRowY;
    /** 数值槽的左边界：可选值那一行的槽比普通行左边多让出一个开关的位置。 */
    private int numberSlotX;
    private EditBox numberBox;
    private String lastNumberKey = "";

    /** 手动输入颜色的输入框：目标与行位置记下来，输入框按它们贴着色块长出来。 */
    private WrapperConfigRef<?> colorTarget;
    private int colorRowY;
    private EditBox colorBox;
    private String lastColorKey = "";

    /**
     * 打开中的调色盘：目标项与它的锚点。
     *
     * <p>锚点在打开那一刻定下，之后颜色怎么变都不挪面板——否则每取一次色面板就跟着跳，
     * 手正按着的那一格就跑了。
     */
    private WrapperConfigRef<?> palette;
    private int paletteAnchorX;
    private int paletteAnchorW;
    private int paletteRowY;

    /** 每个配置项的文本读写句柄：createKeyValue 会挂监听，不能每帧重建。 */
    private final Map<WrapperConfigRef<?>, AttrKeyValue<?>> handles = new IdentityHashMap<>();

    /** 没有显式约束的数值，其推导区间只算一次，避免跨数量级时轨道跳档；复合数值按分量分开记。 */
    private final Map<String, double[]> derivedBounds = new HashMap<>();

    /** 一帧内布局出来的行，绘制时写入、点击时读取；坐标为屏幕绝对坐标。 */
    private record Row(int y, int height, WrapperConfigRef<?> ref, String section, boolean header) {}

    /**
     * 页面自己的动作：配置表之外的一个操作，整行可点。
     *
     * <p>不是每一项设置都能装成 {@code Ref}（名单、字体文件都在宿主模块自己的存储里），
     * 给它们造一个假 Ref 只会让配置表里出现`改动不会保存`的假项，所以另起一节。
     *
     * @param label  行名，写动作对应的对象
     * @param detail 右侧那句现状（人数、当前字体……），没有就传空串
     * @param chip   行尾可点块的文案，写动词
     * @param tip    悬停说明，空串表示不出说明框
     */
    public record Action(String label, String detail, String chip, String tip, Runnable run) {}

    /** 动作行的位置与它对应的动作。 */
    private record ActionRow(int y, int height, Action action) {}

    public EndfieldModuleConfigScreen(BaseModule module, EndfieldSettings settings) {
        this(
                module.getName().toUpperCase(Locale.ROOT),
                EndfieldClickGuiScreen.moduleLabel(module),
                module,
                module::getEditableConfig,
                settings,
                null);
    }

    /**
     * 通用配置页：标题与配置项由调用方给，模块可以没有。
     *
     * <p>顶部分类里的「好友 / 宏 / 快捷键 / 基础 / GUI / 字体」本质上都是"一串配置项"，
     * 只是来源不同（某个模块、两个模块拼起来、所有模块里的键位……），所以共用这一屏。
     */
    public EndfieldModuleConfigScreen(
            String latin,
            String title,
            BaseModule module,
            java.util.function.Supplier<List<WrapperConfigRef<?>>> source,
            EndfieldSettings settings) {
        this(latin, title, module, source, settings, null);
    }

    /**
     * 作为顶部分类页打开：命令栏换成整条分类条，{@code tabKey} 是这一页自己的键。
     * 这样从一页切到另一页是"同一个窗口里换内容"，不会叠出一层新的屏幕。
     */
    public EndfieldModuleConfigScreen(
            String latin,
            String title,
            BaseModule module,
            java.util.function.Supplier<List<WrapperConfigRef<?>>> source,
            EndfieldSettings settings,
            String tabKey) {
        super(Component.empty(), 0, 0);
        this.latin = latin;
        this.title = title;
        this.module = module;
        this.source = source;
        this.settings = settings;
        this.tabKey = tabKey;
    }

    // ------------------------------------------------------------------ 子类钩子
    // 各分类页共用这一屏的排版与控件；下面几个钩子是给页面自己加"标题下的工具条"和页脚文字的。

    /** 工具区高度：页面要放搜索框、筛选块时就报一个高度，第一行会整体下移这么多。 */
    protected int toolHeight() {
        return 0;
    }

    /** 画工具区；坐标是内容坐标（已经扣过滚动），宽是正文宽度。 */
    protected void paintTool(
            VDrawContext ctx, Ef.Palette p, int x, int y, int width, int mouseX, int mouseY) {}

    /** 工具区点击；{@code contentY} 已换算成内容坐标。 */
    protected boolean clickTool(double mouseX, double contentY, int button) {
        return false;
    }

    /** 空列表时那句完整的话。模块页与非模块页说法不同。 */
    protected String emptyText() {
        return module == null ? "这一页还没有可编辑的设置。" : "这个模块还没有可编辑的设置。";
    }

    /** 子类可以再筛一遍（搜索框用）：返回 false 的项不进列表。 */
    protected boolean accept(WrapperConfigRef<?> ref) {
        return true;
    }

    /** 行名：翻译键取不到就退到路径最后一段，和行里显示的一致。 */
    protected static String rowLabel(WrapperConfigRef<?> ref) {
        String key = ref.keyName();
        return Component.translatableWithFallback(key, leaf(key)).getString();
    }

    protected int bodyTopPx() {
        return bodyTop();
    }

    protected int contentLeftPx() {
        return contentLeft;
    }

    protected int contentWidthPx() {
        return contentWidth;
    }

    /** 页脚右侧的提示，默认是数值控件的操作说明。 */
    protected String hint() {
        return "拖滑杆 │ 右键减一";
    }

    /** 页脚左侧统计的补充文字（例如"共 42 项"），默认空。 */
    protected String extraStats() {
        return "";
    }

    /**
     * 页面自己的动作行，默认没有。
     *
     * <p>每次布局与点击都会取一遍，所以实现里只做便宜的事（读一个计数、当前值）；
     * 真正重的活（扫字体目录这类）留给动作本身，点下去才做。
     */
    protected List<Action> actions() {
        return List.of();
    }

    protected boolean light() {
        return settings != null && settings.light();
    }

    protected Ef.Palette pal() {
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
        // 布局重来（开屏、改窗口大小）时行会挪位：浮层的锚点跟着失效，先收起来
        this.palette = null;
        if (tabKey != null) {
            this.tabBar = new EndfieldTabBar();
        }
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        winW = Math.min(this.width - 8, Math.max(280, Math.round(this.width * 0.78F)));
        winH = Math.min(this.height - 8, Math.max(200, Math.round(this.height * 0.78F)));
        winX = (this.width - winW) / 2;
        winY = (this.height - winH) / 2;
        barH = tabBar == null ? BAR_H : tabBar.measure(winW, light());
        layoutContent();

        EfWidget.of(0, 0, this.width, this.height, this::paint)
                .click(this::click)
                .drag(this::drag, () -> dragRef != null)
                .scroll(this::wheel)
                .keys(this::captureKey)
                .addTo(this);
    }

    /**
     * 输入框是原版控件，增删不能发生在点击/渲染的遍历里，
     * 所以统一在这个每 tick 调一次的位置做。
     */
    private void startNumberBox() {
        EditBox box = new EditBox(
                Minecraft.getInstance().font,
                numberSlotX + 3,
                numberRowY + Math.max(0, numberPart) * ROW_H + 4,
                TEXT_W - 6,
                9,
                Component.empty());
        box.setBordered(false);
        box.setMaxLength(16);
        this.numberBox = box;
        this.lastNumberKey = numberKey(numberTarget, numberPart, numberRowY);
        applyNumberText(box, numberTarget, numberPart);
        this.addRenderableWidget(box);
        this.setFocused(box);
    }

    private static String numberKey(WrapperConfigRef<?> target, int part, int rowY) {
        return System.identityHashCode(target) + ":" + part + ":" + rowY;
    }

    private String numberTextOf(WrapperConfigRef<?> target, int part) {
        if (target == null) {
            return "";
        }
        if (part == OPTIONAL_PART) {
            Object inner = optionalInner(target.ref().getValue());
            return inner instanceof Number number ? formatNumber(number.doubleValue()) : "";
        }
        if (part < 0) {
            return valueText(target.ref());
        }
        Object[] parts = recordParts(target.ref().getValue());
        if (parts == null || part >= parts.length || !(parts[part] instanceof Number number)) {
            return "";
        }
        return formatNumber(number.doubleValue());
    }

    /**
     * 把当前值写进输入框，并把 responder 重新绑到 (目标, 分量) 这一对上。
     *
     * <p>{@code EditBox.setValue} 会同步回调 responder，所以程序写值时必须先摘掉 responder：
     * 否则「切到另一格」会被当成用户输入，把新一格的数值写进旧一格。
     * responder 里带的也是绑定时的那一对，不受之后字段变化影响。
     */
    private void applyNumberText(EditBox box, WrapperConfigRef<?> target, int part) {
        box.setResponder(null);
        try {
            String text = numberTextOf(target, part);
            if (!text.equals(box.getValue())) {
                box.setValue(text);
            }
        } finally {
            box.setResponder(text -> {
                if (box == this.numberBox) {
                    commitNumber(target, part, text);
                }
            });
        }
    }

    private void commitNumber(WrapperConfigRef<?> target, int part, String text) {
        if (target == null) {
            return;
        }
        try {
            double value = Double.parseDouble(text.trim().replace(',', '.'));
            if (part == OPTIONAL_PART) {
                writeOptionalNumber(target.ref(), value);
            } else if (part < 0) {
                setNumeric(target.ref(), value);
            } else {
                setValue(target.ref(), withNumericPart(target.ref().getValue(), part, value));
            }
        } catch (NumberFormatException ignored) {
            // 打到一半（"-"、"."）解析不了是正常的
        }
    }

    /**
     * 颜色输入框：长在 {@link #colorGeometry()} 算出的十六进制那一段上，与绘制共用同一份几何。
     *
     * <p>和数值框同一条规矩：原版控件只在 tick 里增删，点击与渲染期间只改字段。
     */
    private void startColorBox() {
        ColorGeometry geo = colorGeometry();
        EditBox box = new EditBox(
                Minecraft.getInstance().font,
                geo.hexX(),
                colorRowY + 4,
                geo.hexW(),
                9,
                Component.empty());
        box.setBordered(false);
        box.setMaxLength(16);
        this.colorBox = box;
        this.lastColorKey = colorKey(colorTarget, colorRowY);
        applyColorText(box, colorTarget);
        this.addRenderableWidget(box);
        this.setFocused(box);
    }

    private static String colorKey(WrapperConfigRef<?> target, int rowY) {
        return System.identityHashCode(target) + ":" + rowY;
    }

    /**
     * 把当前值写进输入框，并把 responder 重新绑到目标项上。
     *
     * <p>{@code EditBox.setValue} 会同步回调 responder，程序写值前必须先摘掉：
     * 否则「换个颜色再开编辑」会被当成用户输入，把显示值写回配置。
     */
    private void applyColorText(EditBox box, WrapperConfigRef<?> target) {
        box.setResponder(null);
        try {
            String text = hexOf(colorOf(target));
            if (!text.equals(box.getValue())) {
                box.setValue(text);
            }
        } finally {
            box.setResponder(text -> {
                if (box == this.colorBox) {
                    commitColor(target, text);
                }
            });
        }
    }

    /**
     * 输入合法才写回，非法输入保持原值。
     *
     * <p>十六进制按定长六位挡：{@code #} 开头的串 TextColor 只当十六进制解释，{@code "#12"} 会被收成
     * RGB(0x12)，打到一半的半截值不能就这样改掉配置。命名色不另抄表，仍交给
     * {@link WrapColor#WrapColor(String)} 解析；它只认小写名，而 ChatFormatting 的写法是全大写，所以先转小写。
     */
    private void commitColor(WrapperConfigRef<?> target, String text) {
        if (target == null) {
            return;
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("#") && !fullHex(value)) {
            return;
        }
        try {
            setValue(target.ref(), new WrapColor(value));
        } catch (RuntimeException ignored) {
            // 名字拼错或者打到一半（空串、单独的 "#"）：等打完，不拿半截值覆盖配置
        }
    }

    /** 整串是否就是 {@code #} 加六位十六进制：输入框只认这一种十六进制写法。 */
    private static boolean fullHex(String value) {
        if (value.length() != 7) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 宿主控件的增删不能发生在点击/渲染的遍历里，所以每 tick 同步一次：
     * 这一帧还在的行跟着走，消失的行卸掉，新出现的行建出来。
     */
    @Override
    public void tick() {
        super.tick();
        if (numberTarget == null) {
            if (numberBox != null) {
                numberBox.setResponder(null);
                this.removeWidget(numberBox);
                numberBox = null;
            }
        } else if (numberBox == null) {
            startNumberBox();
        } else if (!numberKey(numberTarget, numberPart, numberRowY).equals(lastNumberKey)) {
            // 换一格就换一只输入框：不复用，也就没有"旧绑定写新值"的机会
            numberBox.setResponder(null);
            this.removeWidget(numberBox);
            numberBox = null;
            startNumberBox();
        }
        if (colorTarget == null) {
            if (colorBox != null) {
                colorBox.setResponder(null);
                this.removeWidget(colorBox);
                colorBox = null;
            }
        } else if (colorBox == null) {
            startColorBox();
        } else if (!colorKey(colorTarget, colorRowY).equals(lastColorKey)) {
            // 换一格就换一只输入框：不复用，也就没有"旧绑定写新值"的机会
            colorBox.setResponder(null);
            this.removeWidget(colorBox);
            colorBox = null;
            startColorBox();
        }
        Iterator<Map.Entry<WrapperConfigRef<?>, DrawableWidget>> it = editors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WrapperConfigRef<?>, DrawableWidget> entry = it.next();
            int[] rect = editorRects.get(entry.getKey());
            if (rect == null) {
                this.removeWidget(entry.getValue());
                it.remove();
            } else {
                entry.getValue().setX(rect[0]);
                entry.getValue().setY(rect[1]);
            }
        }
        for (Map.Entry<WrapperConfigRef<?>, int[]> entry : editorRects.entrySet()) {
            if (editors.containsKey(entry.getKey())) {
                continue;
            }
            int[] rect = entry.getValue();
            try {
                DrawableWidget widget = handle(entry.getKey())
                        .generateValueWidget(NewClickGui.elementFactory(), rect[0], rect[1], rect[2], rect[3]);
                if (widget != null) {
                    this.addRenderableWidget(widget);
                    editors.put(entry.getKey(), widget);
                }
            } catch (Throwable ignored) {
                // 这个类型没有可用的编辑器，保持只读
            }
        }
    }

    /**
     * 本屏自己画的控件：开关 / 数值 / 枚举 / 键位 / 颜色，以及"一条记录里几个 double"（例如 {@code Vec2}）。
     * 后者也必须在列，否则行里画的是自己的滑杆、点击却让给宿主控件，两头都没接上。
     */
    private boolean nativeControl(WrapperConfigRef<?> ref) {
        Ref<?> value = ref.ref();
        return value instanceof FlagRef
                || value instanceof IntRef
                || value instanceof LongRef
                || value instanceof DoubleRef
                || value instanceof FloatRef
                || value instanceof EnumRef<?>
                || value instanceof KeyBindRef
                || value.getValue() instanceof WrapColor
                || value.getValue() instanceof OptionalPrimitive<?>
                || recordParts(value.getValue()) != null;
    }

    // ------------------------------------------------------------------ 数据

    private List<WrapperConfigRef<?>> visibleRefs() {
        List<WrapperConfigRef<?>> out = new ArrayList<>();
        for (WrapperConfigRef<?> ref : source.get()) {
            if (ref.shouldShow() && accept(ref)) {
                out.add(ref);
            }
        }
        return out;
    }

    private static String sectionKey(WrapperConfigRef<?> ref) {
        String[] path = ref.path();
        if (path == null || path.length <= 1) {
            return "";
        }
        return String.join(".", Arrays.copyOf(path, path.length - 1));
    }

    private static String leaf(String key) {
        if (key.isEmpty()) {
            return "";
        }
        String[] parts = key.split("\\.");
        return parts[parts.length - 1];
    }

    private static String sectionTitle(String key) {
        if (key.isEmpty()) {
            return "主设置";
        }
        return Component.translatableWithFallback("config.index." + key, leaf(key)).getString();
    }

    /** 配置项名字：没有翻译时退到这个键的最后一段，别把一整条路径铺出来。 */
    private static String label(WrapperConfigRef<?> ref) {
        String key = ref.keyName();
        return Component.translatableWithFallback(key, leaf(key)).getString();
    }

    private static String valueText(Ref<?> ref) {
        Object value = ref.getValue();
        if (value == null) {
            return "-";
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            if (Math.abs(d - Math.rint(d)) < 1.0e-6) {
                return String.format(Locale.ROOT, "%.0f", d);
            }
            return String.format(Locale.ROOT, "%.2f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        if (value instanceof Boolean flag) {
            return flag ? "开" : "关";
        }
        if (value instanceof Enum<?> e) {
            String key = "configenum." + enumType(e) + "." + e.name().toLowerCase(Locale.ROOT);
            return Component.translatableWithFallback(key, e.name()).getString();
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private AttrKeyValue<Object> handle(WrapperConfigRef<?> ref) {
        return (AttrKeyValue<Object>) handles.computeIfAbsent(
                ref, r -> (AttrKeyValue<?>) r.ref().createKeyValue(r.keyName()));
    }

    /**
     * 上下界：先看 {@link NumberClamp} 校验器，缺一侧就按宿主那套兜底推出来。
     *
     * <p>推导出来的那一侧要缓存：每帧按当前值重推的话，数值一跨数量级轨道就跟着变，
     * 拖动中的比例会突然跳档。只有当前值真的跑出缓存区间时才按新的数量级扩一档。
     */
    private double[] bounds(WrapperConfigRef<?> ref, double current, int part) {
        Double fixedMin = null;
        Double fixedMax = null;
        for (Object validator : handle(ref).getValidators()) {
            if (validator instanceof NumberClamp<?> clamp) {
                if (clamp.min() != null) {
                    double value = clamp.min().doubleValue();
                    fixedMin = fixedMin == null ? value : Math.max(fixedMin, value);
                }
                if (clamp.max() != null) {
                    double value = clamp.max().doubleValue();
                    fixedMax = fixedMax == null ? value : Math.min(fixedMax, value);
                }
            }
        }
        String key = System.identityHashCode(ref) + "#" + part;
        double[] derived = derivedBounds.get(key);
        boolean escaped = derived == null
                || (fixedMin == null && current < derived[0])
                || (fixedMax == null && current > derived[1]);
        if (escaped) {
            derived = deriveBounds(current, fixedMin, fixedMax);
            derivedBounds.put(key, derived);
        }
        double min = fixedMin != null ? fixedMin : derived[0];
        double max = fixedMax != null ? fixedMax : derived[1];
        if (max <= min) {
            max = min + 1.0;
        }
        return new double[] {min, max};
    }

    private static double[] deriveBounds(double current, Double fixedMin, Double fixedMax) {
        double min;
        double max;
        if (fixedMin == null && fixedMax == null) {
            // 完全没有约束：按当前值的数量级取一段区间，跨档时再扩
            double magnitude = Math.max(1.0, Math.abs(current));
            double top = Math.pow(10.0, Math.ceil(Math.log10(magnitude)));
            max = top;
            min = current < 0 ? -top : 0.0;
        } else if (fixedMin == null) {
            max = fixedMax;
            min = max <= 10 ? max - 10 : 0.0;
        } else if (fixedMax == null) {
            min = fixedMin;
            max = min + Math.max(1.0, Math.abs(min) * 0.5);
        } else {
            min = fixedMin;
            max = fixedMax;
        }
        return new double[] {min, max};
    }

    /** 「可选值」里那一层：没启用时值是空的，读出来就是 null。 */
    private static Object optionalInner(Object value) {
        return value instanceof OptionalPrimitive<?> optional ? optional.getValue() : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void writeOptionalNumber(Ref<?> ref, double value) {
        Object raw = ref.getValue();
        // 用裸类型接：withValue 的参数是通配捕获，带 ? 就没法把新值放回去
        if (!(raw instanceof OptionalPrimitive optional)) {
            return;
        }
        Object inner = optional.getValue();
        if (!(inner instanceof Number number)) {
            return;
        }
        ((Ref) ref).setValue(optional.withValue(coerce(number.getClass(), value)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void writeOptionalOption(Ref<?> ref, Object option) {
        Object raw = ref.getValue();
        if (!(raw instanceof OptionalPrimitive optional)) {
            return;
        }
        // 内层是 WrapEnum 的要原样包回去，宿主按包装类型序列化
        Object next = optional.getValue() instanceof WrapEnum<?> ? new WrapEnum<>((ConfigEnum) option) : option;
        ((Ref) ref).setValue(optional.withValue(next));
    }

    /**
     * 一条记录里的几格：数值各自走滑杆，枚举走下拉，别的类型返回 null 交回宿主控件。
     *
     * <p>两格到四格都接：宿主里两个数值的记录（{@code Vec2}）和
     * 「两个数值 + 一个枚举」的记录（{@code PredictArgument}）都很常见。
     */
    private static Object[] recordParts(Object value) {
        if (value == null || !value.getClass().isRecord()) {
            return null;
        }
        RecordComponent[] parts = value.getClass().getRecordComponents();
        if (parts.length < 2 || parts.length > 4) {
            return null;
        }
        Object[] out = new Object[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                Object part = parts[i].getAccessor().invoke(value);
                if (!(part instanceof Number) && !(part instanceof Enum<?>)) {
                    return null;
                }
                out[i] = part;
            }
        } catch (Throwable e) {
            return null;
        }
        return out;
    }

    /** 换掉记录里的一格；数值会先按那一格的声明类型还原（int / float / long…），枚举原样放回。 */
    private static Object withPart(Object value, int index, Object next) {
        try {
            RecordComponent[] parts = value.getClass().getRecordComponents();
            Object[] args = new Object[parts.length];
            for (int i = 0; i < parts.length; i++) {
                args[i] = i == index ? next : parts[i].getAccessor().invoke(value);
            }
            Class<?>[] types = new Class<?>[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
            }
            return value.getClass().getDeclaredConstructor(types).newInstance(args);
        } catch (Throwable e) {
            return value;
        }
    }

    private static Object withNumericPart(Object value, int index, double next) {
        try {
            RecordComponent[] parts = value.getClass().getRecordComponents();
            if (index >= 0 && index < parts.length) {
                return withPart(value, index, coerce(parts[index].getType(), next));
            }
        } catch (Throwable ignored) {
            // 读不出记录结构就保持原值
        }
        return value;
    }

    private static Object coerce(Class<?> type, double value) {
        if (type == double.class || type == Double.class) {
            return value;
        }
        if (type == float.class || type == Float.class) {
            return (float) value;
        }
        if (type == int.class || type == Integer.class) {
            return (int) Math.round(value);
        }
        if (type == long.class || type == Long.class) {
            return Math.round(value);
        }
        if (type == short.class || type == Short.class) {
            return (short) Math.round(value);
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) Math.round(value);
        }
        return value;
    }

    /**
     * 下拉里要列哪些取值：普通枚举项看整个 Ref，记录里的枚举项看那一格，可选值看它的内层。
     * {@code WrapEnum} 只是把枚举包了一层，取值表仍按里面的枚举类型列。
     */
    private static Object[] optionsFor(WrapperConfigRef<?> ref, int part) {
        Object value = enumValueOf(ref.ref().getValue(), part);
        return value instanceof Enum<?> e ? e.getDeclaringClass().getEnumConstants() : new Object[0];
    }

    private static Object currentOf(WrapperConfigRef<?> ref, int part) {
        return enumValueOf(ref.ref().getValue(), part);
    }

    private static Object enumValueOf(Object raw, int part) {
        Object value = raw;
        if (part == OPTIONAL_PART) {
            value = optionalInner(raw);
        } else if (part >= 0) {
            Object[] parts = recordParts(raw);
            value = parts != null && part < parts.length ? parts[part] : null;
        }
        return value instanceof WrapEnum<?> wrap ? wrap.value : value;
    }

    private static String enumLabel(Object option) {
        if (option instanceof Enum<?> e) {
            String key = "configenum." + enumType(e) + "." + e.name().toLowerCase(Locale.ROOT);
            return Component.translatableWithFallback(key, e.name()).getString();
        }
        return String.valueOf(option);
    }

    private static String keyText(KeyBindRef ref) {
        MultiKeyBind bind = ref.get();
        if (bind == null || bind.isEmpty()) {
            return "未绑定";
        }
        String text = bind.getKeyStr();
        return text.isEmpty() ? "未绑定" : text;
    }

    private static String enumType(Enum<?> value) {
        if (value instanceof me.matl114.managers.config.ConfigEnum configEnum) {
            return configEnum.getConfigEnumType().replace('_', '-');
        }
        return value.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setValue(Ref<?> ref, Object value) {
        ((Ref) ref).setValue(value);
    }

    // ------------------------------------------------------------------ 布局

    private int bodyTop() {
        return winY + barH + 2;
    }

    private int bodyHeight() {
        return winH - barH - FOOT_H - 4;
    }

    /**
     * 内容区的左边界与宽度：只由窗口矩形决定。
     *
     * <p>子类在 {@code refreshWidgets} 里就要建原版输入框（{@code contentLeftPx()} 那些钩子），
     * 而那一步早于第一次绘制，所以布局不能只发生在 {@code buildRows} 里。
     */
    private void layoutContent() {
        contentLeft = winX + PAD;
        contentWidth = Math.min(winW - PAD * 2, 560);
    }

    private void buildRows() {
        rows.clear();
        actionRows.clear();
        layoutContent();

        int y = bodyTop() + toolHeight() - scroll;
        String current = null;
        for (WrapperConfigRef<?> ref : visibleRefs()) {
            String section = sectionKey(ref);
            if (!section.equals(current)) {
                current = section;
                rows.add(new Row(y, SECTION_H, ref, section, true));
                y += SECTION_H;
            }
            Object[] parts = recordParts(ref.ref().getValue());
            int height = parts != null ? ROW_H * parts.length : ROW_H;
            rows.add(new Row(y, height, ref, section, false));
            y += height;
        }
        List<Action> actions = actions();
        if (!actions.isEmpty()) {
            rows.add(new Row(y, SECTION_H, null, ACTION_SECTION, true));
            y += SECTION_H;
            for (Action action : actions) {
                if (action == null) {
                    continue;
                }
                actionRows.add(new ActionRow(y, ROW_H, action));
                y += ROW_H;
            }
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
        editorRects.clear();
        hovered = null;
        hoveredAction = null;

        paintBar(ctx, p, mouseX, mouseY);
        paintBody(ctx, p, mouseX, mouseY);
        paintDropdown(ctx, p, mouseX, mouseY);
        paintFooter(ctx, p);
        paintFrame(ctx, p);
        // 调色盘是浮层：它越过正文裁剪与页脚，所以画在最后，但仍让说明框盖在上面
        paintPalette(ctx, p, mouseX, mouseY);
        paintTooltip(ctx, p, mouseX, mouseY);
    }

    private void paintBar(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        if (tabKey != null) {
            // 作为顶部分类的一页：命令栏就是整条分类条，当前项高亮
            tabBar.paint(ctx, winX, winY, winW, tabKey, light(), mouseX, mouseY);
            return;
        }
        Ef.fill(ctx, winX, winY, winW, barH, Ef.BAR_BG);

        boolean backHot = mouseX >= winX + 6 && mouseX <= winX + 40 && mouseY >= winY + 5 && mouseY <= winY + 17;
        if (backHot) {
            Ef.cutFill(ctx, winX + 6, winY + 5, 34, 12, Ef.BAR_RULE, Ef.CUT);
        } else {
            Ef.cutOutline(ctx, winX + 6, winY + 5, 34, 12, Ef.BAR_INK2, Ef.CUT);
        }
        Ef.text(ctx, "< 返回", winX + 9, winY + 6, Ef.BAR_INK);

        int titleX = winX + 48;
        Ef.text(ctx, title, titleX, winY + 6, Ef.BAR_INK);
        Ef.trackedWide(ctx, latin, titleX + Ef.textWidth(title) + 8, winY + 7, Ef.BAR_INK2, 1, 1.2F);

        if (module == null || module.getBindFlag() == null) {
            return;
        }
        boolean on = module.getBindFlag().get();
        String state = on ? "启用" : "停用";
        int stateW = Ef.textWidth(state) + 8;
        int stateX = winX + winW - 8 - stateW;
        if (on) {
            Ef.fill(ctx, stateX, winY + 5, stateW, 12, Ef.BAR_INK);
            Ef.text(ctx, state, stateX + 4, winY + 6, Ef.BAR_BG);
        } else {
            Ef.dashedOutline(ctx, stateX, winY + 5, stateW, 12, Ef.BAR_INK2);
            Ef.text(ctx, state, stateX + 4, winY + 6, Ef.BAR_INK2);
        }
    }

    private void paintBody(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        int top = bodyTop();
        int height = bodyHeight();
        Ef.fill(ctx, winX, top, winW, height, p.paper());

        ctx.enableScissor(winX, top, winX + winW, top + height);
        try {
            if (rows.isEmpty()) {
                Ef.text(ctx, emptyText(), contentLeft, top + toolHeight() + 6, p.ink2());
            } else {
                for (Row row : rows) {
                    if (row.y + row.height < top || row.y > top + height) {
                        continue;
                    }
                    if (row.header) {
                        paintSection(ctx, p, row.y, row.section);
                    } else {
                        paintRow(ctx, p, row.y, row.ref, mouseX, mouseY);
                    }
                }
            }
            for (ActionRow row : actionRows) {
                if (row.y + row.height < top || row.y > top + height) {
                    continue;
                }
                paintAction(ctx, p, row, mouseX, mouseY);
            }
            if (toolHeight() > 0) {
                // 工具条是固定的：先铺一层纸面盖住滚过去的行，再画它自己
                Ef.fill(ctx, contentLeft - 4, top, contentWidth + 8, toolHeight(), p.paper());
                paintTool(ctx, p, contentLeft, top, contentWidth, mouseX, mouseY);
            }
        } finally {
            ctx.disableScissor();
        }
        Ef.scrollbar(ctx, winX + winW - 4, top + 2, height - 6, scroll, totalHeight, height, p.rule(), p.ink2());
    }

    private void paintSection(VDrawContext ctx, Ef.Palette p, int y, String section) {
        // 动作节不是配置路径，没有翻译键可用，眉标与标题都写死
        boolean actions = ACTION_SECTION.equals(section);
        String head = actions
                ? "ACTIONS"
                : (section.isEmpty() ? "MAIN" : leaf(section).replace('-', ' ').toUpperCase(Locale.ROOT));
        String eyebrow = "// " + head;
        Ef.trackedWide(ctx, eyebrow, contentLeft, y + 7, p.ink2(), 1, 1.2F);
        int titleX = contentLeft + Math.max(72, Ef.trackedWideWidth(eyebrow, 1, 1.2F) + 10);
        Ef.text(ctx, actions ? "操作" : sectionTitle(section), titleX, y + 7, p.ink());
        Ef.rule(ctx, contentLeft, y + SECTION_H - 6, contentWidth, p.rule());
    }

    private void paintRow(
            VDrawContext ctx, Ef.Palette p, int y, WrapperConfigRef<?> ref, int mouseX, int mouseY) {
        int right = controlRight();
        Object[] rowParts = recordParts(ref.ref().getValue());
        int rowH = rowParts != null ? ROW_H * rowParts.length : ROW_H;
        boolean hover = mouseY >= y && mouseY < y + rowH && mouseX >= contentLeft - 4 && mouseX <= right + 4;
        if (hover && dropdown == null) {
            hovered = ref;
        }
        if (hover) {
            Ef.fill(ctx, contentLeft - 4, y, contentWidth + 8, rowH, Ef.mix(p.paper(), p.ink(), 0.04f));
            Ef.fill(ctx, contentLeft - 4, y, 3, rowH, p.trace());
        }
        int labelW = slotRow(ref.ref().getValue()) ? Math.max(24, labelWidth() - 28) : labelWidth();
        Ef.text(ctx, Ef.ellipsis(label(ref), labelW), contentLeft, y + 5, p.ink());
        Ef.rule(ctx, contentLeft - 4, y + rowH - 1, contentWidth + 8, Ef.alpha(p.rule(), 0.55F));

        // 行尾的重置：宿主自带图标，只有带默认值的项才画
        if (ref.ref().hasDefaultValue()) {
            int iconX = contentLeft + contentWidth - 13;
            boolean hot = mouseX >= iconX - 2 && mouseX <= iconX + 12 && mouseY >= y && mouseY < y + 12;
            if (hot) {
                Ef.cutFill(ctx, iconX - 1, y + 3, 12, 12, p.chip(), Ef.CUT);
            }
            Ef.icon(ctx, Constants.RESET_BUTTON_SPRITE, iconX + 1, y + 4, 9, hot ? p.ink() : p.ink2());
        }

        Ref<?> value = ref.ref();
        Object raw = value.getValue();
        Object[] parts = recordParts(raw);

        if (value instanceof FlagRef flag) {
            int sw = 20;
            int sh = 9;
            int sy = y + (ROW_H - sh) / 2;
            if (flag.get()) {
                Ef.fill(ctx, right - sw, sy, sw, sh, p.ink());
                Ef.fill(ctx, right - 9, sy + 2, 5, 5, p.paper());
            } else {
                Ef.dashedOutline(ctx, right - sw, sy, sw, sh, p.ink2());
                Ef.fill(ctx, right - sw + 2, sy + 2, 5, 5, p.ink2());
            }
        } else if (isNumeric(value)) {
            double numeric = numericOf(value);
            double[] range = bounds(ref, numeric, -1);
            paintSlider(ctx, p, y, right, valueText(value), numeric, range[0], range[1], "",
                    numberTarget == ref && numberPart < 0);
        } else if (value instanceof EnumRef<?>) {
            String text = valueText(value) + "   ▾";
            int textW = Ef.textWidth(text);
            int chipX = right - textW - 10;
            Ef.cutFill(ctx, chipX, y + 3, textW + 10, 11, dropdown == ref ? p.ink() : p.chip(), Ef.CUT);
            Ef.text(ctx, text, chipX + 5, y + 5, dropdown == ref ? p.paper() : p.ink());
        } else if (value instanceof KeyBindRef bindRef) {
            boolean listening = keyTarget == ref;
            String text = listening ? "按任意键…" : keyText(bindRef);
            int textW = Ef.textWidth(text);
            int chipX = right - textW - 12;
            // 等待按键是"需要看一眼"的状态，用强调色填充；强调色当线当字在浅色纸面上看不见
            if (listening) {
                Ef.cutFill(ctx, chipX, y + 3, textW + 12, 12, Ef.SIGNAL, Ef.CUT);
                Ef.text(ctx, text, chipX + 6, y + 5, Ef.ON_SIGNAL);
            } else {
                Ef.cutOutline(ctx, chipX, y + 3, textW + 12, 12, p.rule(), Ef.CUT);
                Ef.text(ctx, text, chipX + 6, y + 5, p.ink());
            }
        } else if (raw instanceof OptionalPrimitive<?> optional) {
            paintOptional(ctx, p, y, ref, optional);
        } else if (parts != null) {
            // 一条记录里的几格：每格占一行，行名用字段名；数值拖滑杆，枚举点开下拉
            String[] names = componentNames(raw);
            for (int i = 0; i < parts.length; i++) {
                int partY = y + i * ROW_H;
                Object part = parts[i];
                if (part instanceof Number number) {
                    double numeric = number.doubleValue();
                    double[] range = bounds(ref, numeric, i);
                    paintSlider(ctx, p, partY, right, formatNumber(numeric),
                            numeric, range[0], range[1], names[i],
                            numberTarget == ref && numberPart == i);
                } else if (part instanceof Enum<?>) {
                    boolean open = dropdown == ref && dropPart == i;
                    paintPartEnum(ctx, p, partY, right, names[i], enumLabel(part), open);
                }
            }
        } else if (raw instanceof WrapColor) {
            paintColor(ctx, p, y, ref);
        } else {
            // 其余类型由宿主自带的编辑器负责绘制：这里只登记矩形并留白
            int boxW = Math.min(260, Math.max(120, contentWidth / 3));
            editorRects.put(ref, new int[] {right - boxW, y + 2, boxW, ROW_H - 4});
            Ef.fill(ctx, right - boxW, y + 3, boxW, ROW_H - 6, Ef.alpha(p.chip(), 0.35F));
        }
    }

    /**
     * 颜色行画出来的三段：整块切角底、色块、十六进制文本。
     *
     * <p>绘制、手输输入框与命中都取这一份几何，三处不会各算一套：
     * 命中区不会比画出来的块宽，也不会盖到行尾的重置图标上。
     */
    private record ColorGeometry(int chipX, int chipW, int swatchX, int swatchW, int hexX, int hexW) {}

    /**
     * 颜色行的几何：整块右对齐到 {@link #controlRight()}，行尾那一列留给重置图标。
     *
     * <p>十六进制段按定长的 {@code #RRGGBB} 量宽（值只换字符不换位数），
     * 块宽因此不随取值跳动，调色盘锚在它上面也不会被推走。
     */
    private ColorGeometry colorGeometry() {
        int pad = 2;
        int swatch = 12;
        int gap = 5;
        // 段宽 = 七字符 + 2px 光标余量：输入框也用这段宽，光标才不会被框切掉
        int hexW = Ef.textWidth("#RRGGBB") + 2;
        int chipW = pad + swatch + gap + hexW + 3;
        int chipX = controlRight() - chipW;
        return new ColorGeometry(chipX, chipW, chipX + pad, swatch, chipX + pad + swatch + gap, hexW);
    }

    /**
     * 颜色行：切角块里一块 12×12 的色块加它的十六进制值。
     *
     * <p>色块与文本是两段可点：色块开调色盘，文本进手输。手输期间只画框，
     * 字由输入框自己画，所以框与输入框都从 {@link #colorGeometry()} 取位置。
     */
    private void paintColor(VDrawContext ctx, Ef.Palette p, int y, WrapperConfigRef<?> ref) {
        ColorGeometry box = colorGeometry();
        if (colorTarget == ref) {
            Ef.cutOutline(ctx, box.chipX(), y + 3, box.chipW(), 12, p.ink(), Ef.CUT);
            return;
        }
        int rgb = colorOf(ref);
        Ef.cutFill(ctx, box.chipX(), y + 3, box.chipW(), 12, p.chip(), Ef.CUT);
        Ef.fill(ctx, box.swatchX(), y + 3, box.swatchW(), 12, 0xFF000000 | (rgb & 0xFFFFFF));
        Ef.text(ctx, hexOf(rgb), box.hexX(), y + 5, p.ink());
    }

    /** 颜色行的当前值：取不到就按白画，绘制路径不能因为一个坏值停住。 */
    private static int colorOf(WrapperConfigRef<?> ref) {
        Object value = ref.ref().getValue();
        if (value instanceof WrapColor color && color.color() != null) {
            return color.asRGB();
        }
        return 0xFFFFFF;
    }

    /** 显示一律写成 {@code #RRGGBB}：输入框预填的就是这个串，而它本身能被再解析回同一个值。 */
    private static String hexOf(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /**
     * 调色盘面板与它里面两块取色区的矩形。
     *
     * <p>绘制、命中、取格都读这一份：面板不管怎么翻，位置只有一个来源。
     */
    private record PaletteBox(int x, int y, int w, int h, int svX, int svY, int hueX, int hueY) {}

    /**
     * 面板贴在这一行的色块下方、右对齐色块；下方放不下就翻到行上方，再整体夹进窗口。
     *
     * <p>面板要整块留在窗口里：翻出去的那一条既看不见也点不到，
     * 取色区少一格就等于少了那一片颜色。
     */
    private PaletteBox paletteBox() {
        // 标题行要同时装下"// COLOR"和定长的 #RRGGBB：面板取两者之和与方块的较大者，
        // 换成自定义字体也不会把这两段挤到一起
        int headW = Ef.trackedWideWidth("// COLOR", 1, 1.2F) + PAL_GAP * 2 + Ef.textWidth("#RRGGBB");
        int w = PAL_PAD * 2 + Math.max(PAL_SV_W, headW);
        int h = PAL_PAD * 2 + PAL_HEAD + PAL_SV_H + PAL_GAP + PAL_HUE_H;
        int x = Math.max(winX + 4, Math.min(winX + winW - 4 - w, paletteAnchorX + paletteAnchorW - w));
        int y = paletteRowY + ROW_H + 2;
        if (y + h > winY + winH - 4) {
            y = paletteRowY - h - 2;
        }
        y = Math.max(winY + 4, Math.min(winY + winH - 4 - h, y));
        int svX = x + PAL_PAD;
        int svY = y + PAL_PAD + PAL_HEAD;
        return new PaletteBox(x, y, w, h, svX, svY, svX, svY + PAL_SV_H + PAL_GAP);
    }

    private static boolean inPalette(PaletteBox box, int x, int y) {
        return x >= box.x() && x <= box.x() + box.w() && y >= box.y() && y <= box.y() + box.h();
    }

    /**
     * 调色盘：一块 26×16 的饱和度/明度方块加一条色相，当前色用 1px 框标出来。
     *
     * <p>就展开在这一页上，不换屏：颜色是边看行里边调的，换个屏就看不见它落回这一行的样子。
     * 方块与色相条按 {@link #PAL_CELL} 铺格子，画的和点的都是同一格，
     * 所以点哪一格、取到的就是那一格中心的颜色；锚点与选中格都由当前值反算，重开面板也对得上。
     */
    private void paintPalette(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        if (palette == null) {
            return;
        }
        if (!visibleRefs().contains(palette)) {
            // 目标行被筛掉或消失了（换页、搜索）：面板没有可调的对象
            palette = null;
            return;
        }
        PaletteBox box = paletteBox();
        int rgb = colorOf(palette);
        float[] hsb = hsbOf(rgb);

        // 面板：底与边框走同一个切口，切角才真是缺一块，而不是在直角上划一道斜线
        int cut = Ef.CUT_PANEL;
        Ef.cutFill(ctx, box.x(), box.y(), box.w(), box.h(), p.panel(), cut);
        // 斜边逐格 1px 画：12 档的描边贴图是 16→12 拉过来的，会摊成 2px 的糊带，与 1px 直边不同粗细
        Ef.fill(ctx, box.x(), box.y(), box.w(), 1, p.ink());
        Ef.fill(ctx, box.x(), box.y(), 1, box.h(), p.ink());
        Ef.fill(ctx, box.x() + box.w() - 1, box.y(), 1, box.h() - cut, p.ink());
        Ef.fill(ctx, box.x(), box.y() + box.h() - 1, box.w() - cut, 1, p.ink());
        for (int i = 0; i < cut; i++) {
            Ef.fill(ctx, box.x() + box.w() - cut + i, box.y() + box.h() - 1 - i, 1, 1, p.ink());
        }
        Ef.trackedWide(ctx, "// COLOR", box.x() + PAL_PAD, box.y() + PAL_PAD - 3, p.ink2(), 1, 1.2F);
        String hex = hexOf(rgb);
        Ef.text(ctx, hex, box.x() + box.w() - PAL_PAD - Ef.textWidth(hex), box.y() + PAL_PAD - 3, p.ink());

        // 饱和度/明度：横向铺饱和度、纵向铺明度，色相取当前值
        int cols = PAL_SV_W / PAL_CELL;
        int rows = PAL_SV_H / PAL_CELL;
        for (int row = 0; row < rows; row++) {
            float v = 1.0F - (row + 0.5F) / rows;
            for (int col = 0; col < cols; col++) {
                float s = (col + 0.5F) / cols;
                Ef.fill(ctx, box.svX() + col * PAL_CELL, box.svY() + row * PAL_CELL,
                        PAL_CELL, PAL_CELL, opaque(Color.HSBtoRGB(hsb[0], s, v)));
            }
        }
        markCell(ctx, p.ink(), box.svX(), box.svY(), cols, rows, PAL_CELL,
                (int) (hsb[1] * cols), (int) ((1.0F - hsb[2]) * rows));

        // 色相条：一格格换，下标与取格用同一套
        int hues = PAL_SV_W / PAL_CELL;
        for (int col = 0; col < hues; col++) {
            float hue = (col + 0.5F) / hues;
            Ef.fill(ctx, box.hueX() + col * PAL_CELL, box.hueY(), PAL_CELL, PAL_HUE_H,
                    opaque(Color.HSBtoRGB(hue, 1.0F, 1.0F)));
        }
        markCell(ctx, p.ink(), box.hueX(), box.hueY(), hues, 1, PAL_HUE_H,
                (int) (hsb[0] * hues), 0);

        if (inPalette(box, mouseX, mouseY)) {
            // 鼠标此刻指的是取色区，不是底下那一行：不要顺带出它的说明
            hovered = null;
            hoveredAction = null;
        }
    }

    /** 把选中格框出来：1px 墨线框整格，两套主题下都看得出那格是当前色。 */
    private void markCell(VDrawContext ctx, int color, int x, int y, int cols, int rows, int cellH,
            int col, int row) {
        int c = Math.max(0, Math.min(cols - 1, col));
        int r = Math.max(0, Math.min(rows - 1, row));
        int cx = x + c * PAL_CELL;
        int cy = y + r * PAL_CELL;
        int h = rows > 1 ? PAL_CELL : cellH;
        Ef.fill(ctx, cx, cy, PAL_CELL, 1, color);
        Ef.fill(ctx, cx, cy + h - 1, PAL_CELL, 1, color);
        Ef.fill(ctx, cx, cy, 1, h, color);
        Ef.fill(ctx, cx + PAL_CELL - 1, cy, 1, h, color);
    }

    /**
     * 一条动作行：左边写操作对象，右边写现状，行尾一块可点的动词。
     *
     * <p>整行都能点，所以悬停时和配置行走同一套反馈（浅底 + 左侧当前位置线），
     * 行尾那块只是把「这里能按」画出来——切角 = 可点。
     */
    private void paintAction(VDrawContext ctx, Ef.Palette p, ActionRow row, int mouseX, int mouseY) {
        Action action = row.action;
        int right = contentLeft + contentWidth;
        boolean hover = mouseY >= row.y
                && mouseY < row.y + row.height
                && mouseX >= contentLeft - 4
                && mouseX <= right + 4;
        if (hover) {
            hoveredAction = action;
            Ef.fill(ctx, contentLeft - 4, row.y, contentWidth + 8, row.height, Ef.mix(p.paper(), p.ink(), 0.04f));
            Ef.fill(ctx, contentLeft - 4, row.y, 3, row.height, p.trace());
        }

        String chip = action.chip() == null ? "" : action.chip();
        int chipW = chip.isEmpty() ? 0 : Ef.textWidth(chip) + 10;
        int chipX = right - chipW;
        int textW = Math.max(40, chipX - 14 - contentLeft);
        Ef.text(ctx, Ef.ellipsis(action.label(), textW), contentLeft, row.y + 5, p.ink());

        String detail = action.detail() == null ? "" : action.detail();
        if (!detail.isEmpty()) {
            int detailW = Math.max(24, textW - Ef.textWidth(action.label()) - 8);
            String shown = Ef.ellipsis(detail, detailW);
            Ef.text(ctx, shown, chipX - 10 - Ef.textWidth(shown), row.y + 5, p.ink2());
        }
        if (!chip.isEmpty()) {
            Ef.cutFill(ctx, chipX, row.y + 3, chipW, 11, hover ? p.ink() : p.chip(), Ef.CUT);
            Ef.text(ctx, chip, chipX + 5, row.y + 5, hover ? p.paper() : p.ink());
        }
        Ef.rule(ctx, contentLeft - 4, row.y + row.height - 1, contentWidth + 8, Ef.alpha(p.rule(), 0.55F));
    }

    /** 说明文字：和原界面同一个约定，取 {@code <键名>.tooltips}。 */
    private List<Component> tooltips(WrapperConfigRef<?> ref) {
        return tooltipCache.computeIfAbsent(
                ref, r -> ChatUtils.parseTooltipsTranslation(r.keyName() + ".tooltips", DUMMY_TOOLTIP));
    }

    private static final String DUMMY_TOOLTIP = "\u0000none";

    private void paintTooltip(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        if (hoveredAction != null) {
            String tip = hoveredAction.tip();
            if (tip != null && !tip.isEmpty()) {
                Ef.tooltip(ctx, p, Ef.wrap(tip, 230), mouseX, mouseY, this.width, this.height);
            }
            return;
        }
        if (hovered == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Component component : tooltips(hovered)) {
            if (component == null) {
                continue;
            }
            String text = component.getString();
            if (text.isEmpty() || DUMMY_TOOLTIP.equals(text)) {
                continue;
            }
            lines.addAll(Ef.wrap(text, 230));
        }
        if (lines.isEmpty()) {
            return;
        }
        Ef.tooltip(ctx, p, lines, mouseX, mouseY, this.width, this.height);
    }

    /**
     * 一条滑杆：可选短标签、滑轨、数值。滑杆只表示比例，
     * 真实取值由调用方按 [min,max] 映射回来。
     */
    private void paintSlider(
            VDrawContext ctx, Ef.Palette p, int y, int right, String text, double value,
            double min, double max, String name, boolean editing) {
        int sliderW = sliderWidth();
        int sliderX = right - TEXT_W - 8 - sliderW;
        int lineY = y + ROW_H / 2;

        paintPartName(ctx, p, y, sliderX - 12, name);
        Ef.fill(ctx, sliderX, lineY, sliderW, 1, p.rule());
        double ratio = max > min ? (value - min) / (max - min) : 0;
        int knob = (int) (sliderW * Math.max(0, Math.min(1, ratio)));
        Ef.fill(ctx, sliderX, lineY, knob, 1, p.ink());
        Ef.fill(ctx, sliderX + knob - 1, lineY - 3, 2, 7, p.ink());
        if (editing) {
            // 输入框自己画字，这里只给个框；框的位置和输入框完全对齐
            Ef.cutOutline(ctx, right - TEXT_W, y + 3, TEXT_W, 11, p.ink(), Ef.CUT);
        } else {
            Ef.text(ctx, text, right - Ef.textWidth(text), y + 5, p.ink());
        }
    }

    /** 分量行名：右对齐贴着控件，宽度按可用空间截断，长字段名不会压到行名上。 */
    private void paintPartName(VDrawContext ctx, Ef.Palette p, int y, int rightEdge, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        int available = Math.max(0, Math.min(150, rightEdge - contentLeft - 8));
        if (available < 18) {
            return;
        }
        String shown = Ef.ellipsis(name, available);
        Ef.tracked(ctx, shown, rightEdge - Ef.trackedWidth(shown, 1), y + 5, p.ink2(), 1);
    }

    /** 这一行尾要单独占一个开关位（可选值），行名得相应缩短。 */
    private static boolean slotRow(Object value) {
        return value instanceof OptionalPrimitive<?>;
    }

    /**
     * 「可选值」：行尾一个开关决定这一项是否生效，右边是内层值自己的控件。
     *
     * <p>没启用时值区写一块「未启用」，留空会被当成坏掉的控件；
     * 内层可以是数值（滑杆）、枚举（下拉），也可以是 {@code WrapEnum} 包着的枚举。
     */
    private void paintOptional(VDrawContext ctx, Ef.Palette p, int y, WrapperConfigRef<?> ref,
            OptionalPrimitive<?> optional) {
        int right = controlRight();
        int sw = 20;
        int sh = 9;
        int sy = y + (ROW_H - sh) / 2;
        if (optional.isPresent()) {
            Ef.fill(ctx, right - sw, sy, sw, sh, p.ink());
            Ef.fill(ctx, right - 9, sy + 2, 5, 5, p.paper());
        } else {
            Ef.dashedOutline(ctx, right - sw, sy, sw, sh, p.ink2());
            Ef.fill(ctx, right - sw + 2, sy + 2, 5, 5, p.ink2());
        }

        int innerRight = right - 28;
        Object inner = optional.getValue();
        if (!optional.isPresent() || inner == null) {
            String off = "未启用";
            int offW = Ef.textWidth(off) + 10;
            Ef.dashedOutline(ctx, innerRight - offW, y + 3, offW, 11, p.ink2());
            Ef.text(ctx, off, innerRight - offW + 5, y + 5, p.ink2());
            return;
        }
        if (inner instanceof WrapEnum<?> wrap) {
            String text = wrap.value instanceof Enum<?> e
                    ? enumLabel(e)
                    : (wrap.valueString == null ? "—" : wrap.valueString);
            paintPartEnum(ctx, p, y, innerRight, "", text, dropdown == ref && dropPart == OPTIONAL_PART);
        } else if (inner instanceof Enum<?> e) {
            paintPartEnum(ctx, p, y, innerRight, "", enumLabel(e), dropdown == ref && dropPart == OPTIONAL_PART);
        } else if (inner instanceof Number number) {
            double numeric = number.doubleValue();
            double[] range = bounds(ref, numeric, OPTIONAL_PART);
            paintSlider(ctx, p, y, innerRight, formatNumber(numeric), numeric, range[0], range[1], "",
                    numberTarget == ref && numberPart == OPTIONAL_PART);
        }
    }

    /** 记录里的一格枚举：切角块 + 下拉箭头，点击展开取值表。 */
    private void paintPartEnum(
            VDrawContext ctx, Ef.Palette p, int y, int right, String name, String text, boolean open) {
        String label = text + "   ▾";
        int textW = Ef.textWidth(label);
        int chipX = right - textW - 10;
        Ef.cutFill(ctx, chipX, y + 3, textW + 10, 11, open ? p.ink() : p.chip(), Ef.CUT);
        Ef.text(ctx, label, chipX + 5, y + 5, open ? p.paper() : p.ink());
        paintPartName(ctx, p, y, chipX - 12, name);
    }

    private static boolean isNumeric(Ref<?> value) {
        return value instanceof IntRef
                || value instanceof LongRef
                || value instanceof DoubleRef
                || value instanceof FloatRef;
    }

    private static double numericOf(Ref<?> value) {
        Object raw = value.getValue();
        return raw instanceof Number number ? number.doubleValue() : 0;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setNumeric(Ref<?> ref, double value) {
        if (ref instanceof IntRef intRef) {
            intRef.setValue((int) Math.round(value));
        } else if (ref instanceof LongRef longRef) {
            longRef.setValue(Math.round(value));
        } else if (ref instanceof DoubleRef doubleRef) {
            doubleRef.setValue(Math.round(value * 100.0) / 100.0);
        } else if (ref instanceof FloatRef floatRef) {
            floatRef.setValue((float) (Math.round(value * 100.0) / 100.0));
        }
    }

    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0e-6) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String[] componentNames(Object value) {
        RecordComponent[] parts = value.getClass().getRecordComponents();
        String[] names = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            names[i] = spacedUpper(parts[i].getName());
        }
        return names;
    }

    /** 字段名是驼峰（{@code ticksLater}），行名要的是两个大写词（{@code TICKS LATER}）。 */
    private static String spacedUpper(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 4);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }

    /** 下拉面板的上沿：放不下就翻到行上方，绘制与命中共用同一个判断。 */
    private int dropTop(int height) {
        int top = dropY;
        if (top + height > winY + winH - FOOT_H) {
            top = Math.max(winY + barH, dropY + ROW_H - height);
        }
        return top;
    }

    private void paintDropdown(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        if (dropdown == null) {
            return;
        }
        Object[] options = optionsFor(dropdown, dropPart);
        if (options.length == 0) {
            dropdown = null;
            dropPart = -1;
            return;
        }
        int rowH = 13;
        int height = options.length * rowH + 4;
        int top = dropTop(height);
        Ef.fill(ctx, dropX, top, dropW, height, p.panel());
        Ef.cutOutline(ctx, dropX, top, dropW, height, p.ink(), Ef.CUT);

        Object current = currentOf(dropdown, dropPart);
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

    private void paintFooter(VDrawContext ctx, Ef.Palette p) {
        int y = winY + winH - FOOT_H;
        Ef.fill(ctx, winX, y, winW, FOOT_H, p.panel());
        Ef.rule(ctx, winX, y, winW, p.rule());
        String left = visibleRefs().size() + " 项设置";
        String extra = extraStats();
        if (!extra.isEmpty()) {
            left = left + "  │  " + extra;
        }
        Ef.text(ctx, left, winX + 8, y + 3, p.ink2());
        String mid = module == null || module.getModuleManager() == null ? "" : module.getModuleManager().getName();
        Ef.text(ctx, mid, winX + 8 + Ef.textWidth(left) + 12, y + 3, p.ink2());
        String hint = hint();
        Ef.text(ctx, hint, winX + winW - 8 - Ef.textWidth(hint), y + 3, p.ink2());
    }

    private void paintFrame(VDrawContext ctx, Ef.Palette p) {
        int edge = p.ink();
        Ef.fill(ctx, winX, winY, winW, 1, edge);
        Ef.fill(ctx, winX, winY + winH - 1, winW, 1, edge);
        Ef.fill(ctx, winX, winY, 1, winH, edge);
        Ef.fill(ctx, winX + winW - 1, winY, 1, winH, edge);
    }

    // ------------------------------------------------------------------ 交互

    private boolean click(EfWidget self, double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        // 上一次拖动在这里收尾：重新点击就重新判定
        dragRef = null;
        // 点别处收起数值/颜色输入框；真的点在它们的控件上时，各自的 hit 方法会把它重新设回来
        numberTarget = null;
        // 颜色输入框盖在行尾的值区上，落在它框里的这一下是去挪光标的：不接、也不收起编辑
        // （画布不接，容器会把点击继续派给输入框自己；重建输入框则会把正在打的内容换回旧值）
        if (colorTarget != null && colorBox != null && colorBox.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        colorTarget = null;
        buildRows();

        if (dropdown != null) {
            Object[] options = optionsFor(dropdown, dropPart);
            int rowH = 13;
            int top = dropTop(options.length * rowH + 4);
            if (mx >= dropX && mx <= dropX + dropW && my >= top && my <= top + options.length * rowH + 4) {
                int index = (my - top - 2) / rowH;
                if (index >= 0 && index < options.length) {
                    applyOption(dropdown, dropPart, options[index]);
                }
            }
            dropdown = null;
            dropPart = -1;
            return true;
        }

        if (palette != null) {
            PaletteBox box = paletteBox();
            if (inPalette(box, mx, my)) {
                if (button == 0) {
                    pickPalette(box, mx, my);
                }
                return true;
            }
            // 面板外的那一下只关面板：放它过去就会顺手改到底下的行。
            // 命令栏是导航不是行，落在那里照常切页。
            palette = null;
            if (my < winY || my >= winY + barH) {
                return true;
            }
        }

        if (my >= winY && my < winY + barH) {
            if (tabKey != null) {
                String hit = tabBar.click(winX, winY, winW, tabKey, light(), mx, my);
                if (EndfieldTabBar.THEME.equals(hit)) {
                    if (settings != null) {
                        settings.light(!settings.light());
                    }
                    return true;
                }
                if (hit != null && !hit.equals(tabKey)) {
                    switchPage(hit);
                    return true;
                }
                return false;
            }
            if (mx >= winX + 6 && mx <= winX + 42) {
                back();
                return true;
            }
            // 非模块页（好友/宏/快捷键…）没有启用块，命令栏只处理返回
            FlagRef bindFlag = module == null ? null : module.getBindFlag();
            if (bindFlag == null) {
                return false;
            }
            boolean on = bindFlag.get();
            String state = on ? "启用" : "停用";
            int stateW = Ef.textWidth(state) + 8;
            int stateX = winX + winW - 8 - stateW;
            if (mx >= stateX && mx <= stateX + stateW) {
                bindFlag.toggle();
                return true;
            }
            return false;
        }

        if (my < bodyTop() || my > bodyTop() + bodyHeight()) {
            return false;
        }
        if (toolHeight() > 0 && clickTool(mx, my, button)) {
            return true;
        }
        if (button == 0) {
            for (ActionRow row : actionRows) {
                if (my < row.y || my >= row.y + row.height) {
                    continue;
                }
                if (mx < contentLeft - 4 || mx > contentLeft + contentWidth + 4) {
                    return false;
                }
                row.action.run().run();
                return true;
            }
        }
        for (Row row : rows) {
            if (row.header || my < row.y || my >= row.y + row.height) {
                continue;
            }
            int resetX = contentLeft + contentWidth - 14;
            if (row.ref().ref().hasDefaultValue()
                    && mx >= resetX
                    && mx <= resetX + 14
                    && my >= row.y
                    && my < row.y + 12) {
                setValue(row.ref().ref(), row.ref().ref().getDefaultValue());
                return true;
            }
            if (!nativeControl(row.ref())) {
                // 交给嵌进来的宿主控件自己处理
                return false;
            }
            return apply(row.ref(), mx, my, button, row.y);
        }
        return false;
    }

    /** 键位捕获：下一次按键直接写回配置，右键清除由 apply 处理。 */
    private boolean captureKey(EfWidget self, int keyCode, int scanCode, int modifiers) {
        if (keyTarget == null) {
            return false;
        }
        String name = KeyCode.getNameForKey(keyCode);
        if (name != null) {
            setValue(keyTarget.ref(), new MultiKeyBind(new String[] {name}, false, false));
            keyTarget = null;
        }
        return true;
    }

    private boolean wheel(EfWidget self, double mouseX, double mouseY, double amount) {
        if (mouseY < bodyTop() || mouseY > bodyTop() + bodyHeight()) {
            return false;
        }
        // 滚动会挪动行，下拉与浮层跟着旧坐标走就会错位：直接收起来
        dropdown = null;
        dropPart = -1;
        palette = null;
        numberTarget = null;
        colorTarget = null;
        buildRows();
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (Math.signum(amount) * 18)));
        return true;
    }

    private boolean apply(WrapperConfigRef<?> wrapper, int mouseX, int mouseY, int button, int rowY) {
        Ref<?> ref = wrapper.ref();
        int right = controlRight();

        if (ref instanceof FlagRef flag) {
            if (mouseX >= right - 24) {
                flag.set(!flag.get());
                return true;
            }
            return false;
        }
        if (isNumeric(ref)) {
            return hitNumber(wrapper, -1, right, mouseX, button, rowY, valueText(ref), numericOf(ref));
        }
        if (ref.getValue() instanceof OptionalPrimitive<?> optional) {
            // 行尾开关：只切「这一项是否生效」，不碰内层值
            if (mouseX >= right - 24) {
                setValue(ref, optional.withPresent(!optional.isPresent()));
                return true;
            }
            Object inner = optional.getValue();
            if (!optional.isPresent() || inner == null) {
                return false;
            }
            int innerRight = right - 28;
            if (inner instanceof Number number) {
                double current = number.doubleValue();
                return hitNumber(wrapper, OPTIONAL_PART, innerRight, mouseX, button, rowY,
                        formatNumber(current), current);
            }
            if (inner instanceof Enum<?> || inner instanceof WrapEnum<?>) {
                if (button == 0) {
                    openDropdown(wrapper, OPTIONAL_PART, rowY);
                    return true;
                }
            }
            return false;
        }
        if (ref.getValue() instanceof WrapColor) {
            return hitColor(wrapper, mouseX, mouseY, button, rowY);
        }
        Object[] parts = recordParts(ref.getValue());
        if (parts != null) {
            int index = Math.max(0, Math.min(parts.length - 1, (mouseY - rowY) / ROW_H));
            Object part = parts[index];
            if (part instanceof Number number) {
                double current = number.doubleValue();
                return hitNumber(wrapper, index, right, mouseX, button, rowY, formatNumber(current), current);
            }
            if (part instanceof Enum<?> && button == 0) {
                openDropdown(wrapper, index, rowY);
                return true;
            }
            return false;
        }
        if (ref instanceof EnumRef<?>) {
            openDropdown(wrapper, -1, rowY);
            return true;
        }
        if (ref instanceof KeyBindRef bindRef) {
            if (button == 1) {
                bindRef.setValue(new MultiKeyBind());
                keyTarget = null;
            } else {
                keyTarget = wrapper;
            }
            return true;
        }
        return true;
    }

    /** 记下这次下拉盖住的是哪一格：普通枚举项 {@code part = -1}，记录分量传它的下标。 */
    private void openDropdown(WrapperConfigRef<?> wrapper, int part, int rowY) {
        dropdown = wrapper;
        dropPart = part;
        dropW = Math.min(180, Math.max(120, contentWidth / 3));
        dropX = controlRight() - dropW;
        dropY = rowY + Math.max(0, part) * ROW_H + ROW_H;
    }

    private void applyOption(WrapperConfigRef<?> wrapper, int part, Object option) {
        if (part == OPTIONAL_PART) {
            writeOptionalOption(wrapper.ref(), option);
        } else if (part < 0) {
            setValue(wrapper.ref(), option);
        } else {
            setValue(wrapper.ref(), withPart(wrapper.ref().getValue(), part, option));
        }
    }

    /** 数字行的一击：滑轨起拖，数值槽开输入框，右键退一档。 */
    private boolean hitNumber(
            WrapperConfigRef<?> wrapper, int part, int right, int mouseX, int button, int rowY, String text,
            double current) {
        int trackW = sliderWidth();
        int trackX = right - TEXT_W - 8 - trackW;
        if (button == 1) {
            double magnitude = Math.abs(current);
            double step = magnitude >= 10 ? 1 : (magnitude >= 1 ? 0.5 : 0.1);
            applyValue(wrapper, part, current - step);
            return true;
        }
        if (mouseX >= trackX - 3 && mouseX <= trackX + trackW + 3) {
            dragRef = wrapper;
            dragPart = part;
            dragTrackX = trackX;
            dragTrackW = trackW;
            double[] range = bounds(wrapper, current, part);
            dragMin = range[0];
            dragMax = range[1];
            applyRatio(wrapper, part, current, (mouseX - trackX) / (double) trackW);
            return true;
        }
        if (mouseX >= right - TEXT_W - 4) {
            numberTarget = wrapper;
            numberPart = part;
            numberRowY = rowY;
            numberSlotX = right - TEXT_W;
            if (numberBox != null) {
                // 旧绑定立刻失效：tick 里会按新目标重写文本并重挂 responder
                numberBox.setResponder(null);
                // 点击会被本屏的整屏控件吃掉，焦点要还给输入框，否则点一下就没法继续打字
                this.setFocused(numberBox);
            }
            return true;
        }
        return false;
    }

    /**
     * 颜色行分两段可点：色块开调色盘，十六进制文本进手输。
     *
     * <p>两段矩形都来自 {@link #colorGeometry()}：命中不会比画出来的块宽，也不会压到行尾的重置图标。
     * 同一时刻只留一个编辑态，开一边就把另一边收掉。
     *
     * <p>进手输时这里只记目标，输入框留给 tick 去建——已经有一只时先摘掉它的 responder，
     * 让 tick 按新目标重挂：复用旧框的话，摘掉的 responder 不会再挂回去，之后打字就不写回配置了。
     * 焦点也只能在 tick 里给：点击处理完，容器会把焦点交回铺满屏幕的这块画布。
     */
    private boolean hitColor(WrapperConfigRef<?> wrapper, int mouseX, int mouseY, int button, int rowY) {
        if (button != 0 || mouseY < rowY + 3 || mouseY >= rowY + 15) {
            return false;
        }
        ColorGeometry box = colorGeometry();
        if (mouseX >= box.swatchX() && mouseX < box.swatchX() + box.swatchW()) {
            // 收起走的是"点面板外"——面板永远不盖住它自己锚定的这块色块
            palette = wrapper;
            paletteAnchorX = box.swatchX();
            paletteAnchorW = box.swatchW();
            paletteRowY = rowY;
            colorTarget = null;
            return true;
        }
        if (mouseX >= box.hexX() && mouseX < box.hexX() + box.hexW()) {
            palette = null;
            colorTarget = wrapper;
            colorRowY = rowY;
            lastColorKey = "";
            if (colorBox != null) {
                colorBox.setResponder(null);
            }
            return true;
        }
        return false;
    }

    /**
     * 面板里的一击：方块取该格的饱和度/明度并沿用当前色相，色相条只换色相、保留当前饱和度与明度。
     *
     * <p>取的是格子中心，与画出来的那一格对齐；每次点击立刻写回配置，
     * 行里的色块与十六进制跟着变，不用关面板再看结果。
     */
    private void pickPalette(PaletteBox box, int mouseX, int mouseY) {
        float[] hsb = hsbOf(colorOf(palette));
        int cols = PAL_SV_W / PAL_CELL;
        if (mouseY >= box.hueY() && mouseY < box.hueY() + PAL_HUE_H) {
            int col = cellAt(mouseX - box.hueX(), cols);
            if (col >= 0) {
                writeColor(Color.HSBtoRGB((col + 0.5F) / cols, hsb[1], hsb[2]));
            }
            return;
        }
        int rows = PAL_SV_H / PAL_CELL;
        if (mouseX < box.svX() || mouseX >= box.svX() + PAL_SV_W
                || mouseY < box.svY() || mouseY >= box.svY() + PAL_SV_H) {
            return;
        }
        int col = cellAt(mouseX - box.svX(), cols);
        int row = cellAt(mouseY - box.svY(), rows);
        if (col >= 0 && row >= 0) {
            writeColor(Color.HSBtoRGB(hsb[0], (col + 0.5F) / cols, 1.0F - (row + 0.5F) / rows));
        }
    }

    /** 落在第几格；点在取色区的留白上返回 -1，不拿边角的值去改颜色。 */
    private static int cellAt(int offset, int cells) {
        int cell = offset / PAL_CELL;
        return offset >= 0 && cell < cells ? cell : -1;
    }

    private void writeColor(int rgb) {
        if (palette != null) {
            setValue(palette.ref(), new WrapColor(TextColor.fromRgb(rgb & 0xFFFFFF)));
        }
    }

    /** 当前值换算成 HSB：选中格与色相位置都由它反算，重开面板时与当前颜色一致。 */
    private static float[] hsbOf(int rgb) {
        return Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
    }

    /** HSB 转成能直接铺的不透明色：取色区不跟窗口透明度一起淡，否则看不出选中的是哪个颜色。 */
    private static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    private void applyRatio(WrapperConfigRef<?> wrapper, int part, double current, double ratio) {
        double[] range = bounds(wrapper, current, part);
        applyRange(wrapper, part, range[0], range[1], ratio);
    }

    private void applyRange(WrapperConfigRef<?> wrapper, int part, double min, double max, double ratio) {
        double clamped = Math.max(0, Math.min(1, ratio));
        applyValue(wrapper, part, min + clamped * (max - min));
    }

    private void applyValue(WrapperConfigRef<?> wrapper, int part, double next) {
        if (part == OPTIONAL_PART) {
            writeOptionalNumber(wrapper.ref(), next);
        } else if (part < 0) {
            setNumeric(wrapper.ref(), next);
        } else {
            setValue(wrapper.ref(), withNumericPart(wrapper.ref().getValue(), part, next));
        }
    }

    /** 拖动中：滑轨矩形按按下时记下的那份算，避免数值变化导致轨道左右跳。 */
    private boolean drag(EfWidget self, double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragRef == null) {
            return false;
        }
        // 用按下那一刻的区间，拖动过程中区间不跟着数值变
        applyRange(dragRef, dragPart, dragMin, dragMax, (mouseX - dragTrackX) / (double) dragTrackW);
        return true;
    }

    /** 控件可用的右边界：行尾要给重置按钮留一列。 */
    private int controlRight() {
        return contentLeft + contentWidth - RESET_W;
    }

    private int sliderWidth() {
        return Math.min(56, Math.max(28, contentWidth / 5));
    }

    /** 标签可用宽度：控件区左侧那一段，别让长名字压到滑轨上。 */
    private int labelWidth() {
        return Math.max(40, controlRight() - TEXT_W - 8 - sliderWidth() - 10 - contentLeft);
    }

    /** 切到另一个分类：同一个窗口里换内容，不再叠一层屏。 */
    private void switchPage(String key) {
        Screen next = "Module".equals(key)
                ? new EndfieldClickGuiScreen(settings)
                : EndfieldPages.create(key, settings);
        if (next != null) {
            ScreenAccess.of(this).switchToScreen(next);
        }
    }

    private void back() {
        onClose();
    }
}
