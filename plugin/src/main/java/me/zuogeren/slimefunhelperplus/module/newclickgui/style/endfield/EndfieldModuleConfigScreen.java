package me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.GenericScreen;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.gui.basic.ExecutableWidget;
import me.matl114.gui.basic.SubScreenWidget;
import me.matl114.gui.elements.IconElement;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.modules.task.ConfigManager;
import me.matl114.hacks.utils.config.BoundedPrimitiveFlagMap;
import me.matl114.hacks.utils.config.EntityTypeEntrySet;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Holder;
import me.matl114.hacks.utils.config.LabelVec2;
import me.matl114.hacks.utils.config.LabelVec3;
import me.matl114.hacks.utils.config.ItemStackTemplate;
import me.matl114.hacks.utils.config.GuiSetting;
import me.matl114.hacks.InvTasks;
import net.minecraft.core.registries.BuiltInRegistries;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.gui.Constants;
import me.matl114.hacks.utils.config.StringEnum;
import me.matl114.hacks.utils.config.StringFormat;
import me.matl114.hacks.utils.config.OptionalPrimitive;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.Primitive;
import me.matl114.hacks.utils.config.PrimitiveList;
import me.matl114.hacks.utils.config.PrimitiveMap;
import me.matl114.hacks.utils.config.RegexList;
import me.matl114.managers.config.ListRef;
import java.util.regex.Pattern;
import me.matl114.hacks.utils.config.Vec2;
import me.matl114.hacks.utils.config.Vec3;
import me.matl114.hacks.utils.config.WidgetPos;
import me.matl114.managers.config.NBTType;
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
import me.matl114.managers.config.StringRef;
import me.matl114.managers.input.KeyCode;
import me.matl114.managers.input.MultiKeyBind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.config.NumberClamp;
import me.matl114.utils.config.AttrKeyValue;
import me.matl114.utils.config.WrapperFactory;
import me.matl114.utils.config.kv.WrapperAttrKeyValue;
import me.zuogeren.slimefunhelperplus.hostgui.HostControls;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelManager;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import me.zuogeren.slimefunhelperplus.module.custommodel.ModelCatalog;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ModelRuntime;
import me.zuogeren.slimefunhelperplus.module.newclickgui.NewClickGui;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.Settings;
import me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield.pages.EndfieldPages;
import me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield.pages.GuiSettingPage;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.Registry;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import me.matl114.utils.ItemStackUtils;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.level.block.Block;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

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

    /**
     * 字段框的高度与内边距：外侧 1px 边 + 内侧 2px 聚焦环，字从框外沿往里第 3 像素起。
     * 9px 的行高要 15px 的框才放得下，再矮聚焦环就压到字上了。
     */
    private static final int FIELD_H = 16;
    private static final int FIELD_PAD = 4;
    /** 字段框在行内下移的像素：行高 18 放 15 的框，上下各留一点，框不贴着行间的细线。 */
    private static final int FIELD_TOP = 1;
    /** 值框右侧箭头占的宽度：点这一段开候选列表，点其余位置开编辑。 */
    private static final int ARROW_W = 14;
    /** GUI 设置行的入口块文案：这一格点开的是宿主那一整套编辑屏。 */
    private static final String GUI_SETTING_CHIP = "编辑   ▾";
    /** 聚焦环的粗细，以及它离边框内侧的距离（CSS 的 outline-offset: -1px）。 */
    private static final int FIELD_RING = 2;
    private static final int FIELD_RING_INSET = 0;

    /** 文本值为空串时的显示：空是合法取值（例如音乐路径空 = 不播），画成一个字免得像坏掉的控件。 */
    private static final String EMPTY_TEXT = "空";

    /** 名单面板的尺寸上限：实际宽高再按窗口夹一次，面板必须整块落在窗口里。 */
    private static final int LIST_W = 280;
    private static final int LIST_H = 240;
    /** 面板内边距与标题行高：搜索框排在标题下，列表排在搜索框下。 */
    private static final int LIST_PAD = 8;
    private static final int LIST_HEAD = 12;
    /** 搜索框就是一只字段，取同一份框高；候选行高同时是滚动的步长，绘制与命中按同一格数。 */
    private static final int LIST_SEARCH_H = FIELD_H;
    private static final int LIST_ROW = 16;

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

    /** 模块自定义控件那一节：同样不是配置路径，标题写死。 */
    private static final String CUSTOM_SECTION = "\u0000custom";
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

    protected final Settings settings;

    private int winX;
    private int winY;
    private int winW;
    private int winH;

    private int contentLeft;
    private int contentWidth;
    private int scroll;
    private int totalHeight;

    private final List<Row> rows = new ArrayList<>();

    /** 下拉每行高度。 */
    private static final int DROP_ROW_H = 13;
    /** 下拉最多显示几行；超出部分靠滚轮翻。 */
    private static final int DROP_MAX_ROWS = 12;
    /** 下拉的滚动偏移（行）。 */
    private int dropScroll;

    /** 打开中的枚举下拉：绘制与命中都用记录下来的矩形；{@code dropPart >= 0} 表示下拉的是记录里的那一格。 */
    private WrapperConfigRef<?> dropdown;
    private int dropPart = -1;
    private int dropX;
    private int dropY;
    private int dropW;

    /** 非原生类型交给宿主控件：按 ref 缓存实例，位置每帧跟着行走。 */
    /** 本帧每个可见行留给宿主控件的矩形（绝对坐标）。 */

    /** 悬停中的行与它的说明缓存。 */
    private WrapperConfigRef<?> hovered;
    private final Map<WrapperConfigRef<?>, List<Component>> tooltipCache = new IdentityHashMap<>();

    /** 页面自己的动作行：排在配置项之后，绘制与命中共用这份布局。 */
    private final List<ActionRow> actionRows = new ArrayList<>();
    private Action hoveredAction;
    /**
     * 悬停时被截断的完整文本：下拉选项与收起态的值框都会用省略号裁短，
     * 裁短之后只有悬停才看得到全名。为空表示这一帧没有需要补全的文本。
     */
    private String truncatedHover;

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
     * 手动编辑文本配置的输入框：目标、行位置、草稿与开框时那一份原值。
     *
     * <p>草稿只在提交时写回配置：打到一半的路径不该被当成新值存下去。
     * 原值用来判断有没有真的改过——输入框有长度上限，原样提交会把超长值截短。
     */
    private WrapperConfigRef<?> textTarget;
    private int textRowY;
    private EditBox textBox;
    private String lastTextKey = "";
    private String textDraft;
    private String textOriginal;

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

    /**
     * 打开中的名单面板：目标项。
     *
     * <p>面板的位置每帧从目标行现算（见 {@link #listBox()}），不记锚点：
     * 行会随滚动、筛选、改窗口挪位，记下的坐标一挪就错，而这几件事本来就会先收起面板。
     */
    private WrapperConfigRef<?> listPanel;
    /** 面板里的搜索词：收起面板就作废，下次打开是全量。 */
    private String listFilter = "";
    /** 列表的像素滚动量：只按 {@link #LIST_ROW} 的整数倍挪，绘制与命中才能共用同一套行下标。 */
    private int listScroll;
    private EditBox listSearchBox;

    /**
     * 候选 id 索引，按注册表记一份：注册表在启动后基本不动，但数据包重载会改它，
     * 所以连着建索引时的大小一起存，大小变了就重排。
     */
    private final Map<Registry<?>, IdIndex> idIndex = new IdentityHashMap<>();

    /** 上一次筛选的结果：拼音转换不便宜，同一个搜索词每帧重算会把界面拖慢。 */
    private Registry<?> shownRegistry;
    private String shownFilter = "";
    private List<Identifier> shownCache;

    /** 每个配置项的文本读写句柄：createKeyValue 会挂监听，不能每帧重建。 */
    private final Map<WrapperConfigRef<?>, AttrKeyValue<?>> handles = new IdentityHashMap<>();

    /** 这一帧鼠标停在哪一条格式化串的参数按钮上：说明框要列出它的占位符。 */
    private StringFormat formatArgumentHover;

    /** 这一帧鼠标停在哪一条格式化串的颜色编辑器按钮上：说明框要带实时预览。 */
    private StringFormat editorHover;

    /** 这一帧鼠标停在物品模板的编辑按钮上。 */
    private boolean itemTemplateHover;

    /** 模块自己塞进来的控件：按 {@link #customKey} 缓存，位置由 buildRows 排。 */
    private final List<DrawableWidget> customWidgets = new ArrayList<>();

    /**
     * 自定义控件的缓存键：模块、正文宽度、主题任一变化才重建。
     *
     * <p>不能每帧重建：{@code addCustomWidgets} 里有的模块要扫系统字体目录（{@code Files.walk}），
     * 有的要建几百个按钮；每帧做一次就是每帧一次磁盘遍历加一次全量重建。
     */
    private String customKey;

    /** 每个自定义控件对应的行 Y，与 {@link #customWidgets} 同序；buildRows 时填。 */
    private final List<Integer> customRowY = new ArrayList<>();

    /** 已经挂到屏幕上的那一批：与 customWidgets 不是同一份对象时整批换掉。 */
    private final List<DrawableWidget> mountedCustom = new ArrayList<>();


    /** 没有显式约束的数值，其推导区间只算一次，避免跨数量级时轨道跳档；复合数值按分量分开记。 */
    private final Map<String, double[]> derivedBounds = new HashMap<>();

    /** 一帧内布局出来的行，绘制时写入、点击时读取；坐标为屏幕绝对坐标。 */
    private record Row(int y, int height, WrapperConfigRef<?> ref, String section, boolean header) {}

    /** 一块可点件的矩形。 */
    private record Box(int x, int y, int w, int h) {}

    /** 一份候选 id 索引，以及建它时的注册表大小。 */
    private record IdIndex(int size, List<Identifier> ids) {}

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

    public EndfieldModuleConfigScreen(BaseModule module, Settings settings) {
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
            Settings settings) {
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
            Settings settings,
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
        if (module == null) {
            return "这一页还没有可编辑的设置。";
        }
        return actions().isEmpty()
                ? "这个模块还没有可编辑的设置。"
                : "这个模块没有可编辑的设置；下面这一节是它自己的操作。";
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
        // 「Config」没有配置项，它的页面就是宿主的快照保存/读取；这里把它接成两条动作行，
        // 否则模块页会是一张空表。
        if (module instanceof ConfigManager manager) {
            return List.of(
                    new Action(
                            "保存配置快照",
                            "把当前配置写成一份快照",
                            "保存",
                            "选一个位置保存快照：当前所有配置一起写成文件，以后可以整份读回来。",
                            () -> manager.openSnapshotScreen(NewClickGui.elementFactory(), true)),
                    new Action(
                            "读取配置快照",
                            "从快照文件整份恢复",
                            "读取",
                            "选一个快照文件读回来：文件里的配置会整份套到当前配置上。",
                            () -> manager.openSnapshotScreen(NewClickGui.elementFactory(), false)));
        }
        if (module instanceof CustomModelModule custom) {
            String current = custom.model.get();
            return List.of(
                    new Action(
                            "模型：" + (current.isBlank() ? "未选择" : ModelCatalog.displayName(current)),
                            "点击切换到下一个",
                            "▸",
                            "在 config/slimefunhelper-plus/models 里循环切换；也可以直接在「模型」一行里填文件名。",
                            () -> custom.model.set(ModelCatalog.cycle(custom.model.get()))),
                    new Action(
                            "重新扫描模型",
                            "models 目录：" + ModelCatalog.names().size() + " 个",
                            "↻",
                            "重新扫描模型目录；扫描结果用于切换与选择。",
                            ModelCatalog::rescan),
                    new Action(
                            "重载当前模型",
                            current.isBlank() ? "还没有选择模型" : current,
                            "⟳",
                            "重新解密、解析并注册当前模型。",
                            () -> CustomModelManager.reloadAsync(custom.model.get())),
                    new Action(
                            "选择模型",
                            current.isBlank() ? "还没有选择模型" : ModelCatalog.displayName(current),
                            "选",
                            "打开模型选择页：点卡片即切换模型，预览区右上角的齿轮进动作预览；热键在「模型选择热键」一行绑定。",
                            custom::openModelPicker),
                    new Action(
                            "动画轮盘",
                            currentExtraLabel(),
                            "◎",
                            "打开轮盘切换服装预设与额外动作；热键在「动画轮盘热键」一行绑定。",
                            custom::openRoulette));
        }
        return List.of();
    }

    /** 动作行副标题：服装预设 + 额外动作，各自可能为空。 */
    private static String currentExtraLabel() {
        ModelRuntime runtime = CustomModelManager.runtime();
        if (runtime == null) {
            return "模型未加载";
        }
        String variant = labelOf(runtime, runtime.activeVariantKey());
        String extra = labelOf(runtime, runtime.activeExtraKey());
        return (variant.isEmpty() ? "默认服装" : variant) + (extra.isEmpty() ? "" : " · " + extra);
    }

    private static String labelOf(ModelRuntime runtime, String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        for (ModelRuntime.ExtraOption option : runtime.extras()) {
            if (option.key().equals(key)) {
                return option.label();
            }
        }
        return key;
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
        this.listPanel = null;
        // 重新 init 会把子控件全部清掉：这份记录跟着作废，否则 tick 会以为它们还在屏幕上。
        // 缓存键也要清：控件列表已空，键还留着的话 buildRows 会以为不必重建。
        this.mountedCustom.clear();
        this.customWidgets.clear();
        this.customRowY.clear();
        this.customKey = null;
        // 输入框随旧控件树一起拆掉了：先把打的字提交掉，再作废目标（没在编辑时这一下什么都不做）
        closeTextEdit();
        if (tabKey != null) {
            this.tabBar = new EndfieldTabBar();
        }
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        // 重新 init（例如从宿主界面返回）会把子控件全部清掉，但 editors 里还留着这些已经不在屏幕上的对象：
        // 不清掉它，tick 会以为这一行已经有编辑器，宿主控件再也建不回来
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
        EditBox box = HostControls.editBox(
                numberSlotX + FIELD_PAD,
                numberRowY + Math.max(0, numberPart) * ROW_H + FIELD_TOP + FIELD_PAD,
                TEXT_W - FIELD_PAD * 2,
                16,
                null);
        this.numberBox = box;
        this.lastNumberKey = numberKey(numberTarget, numberPart, numberRowY);
        applyNumberText(box, numberTarget, numberPart);
        this.addRenderableWidget(box);
        this.setFocused(box);
    }

    /**
     * 把模块自定义控件挂到屏幕上，只挂视口内的那些。
     *
     * <p>与输入框同一条规矩：增删控件不能发生在点击/渲染的遍历里，所以统一在 tick 做。
     *
     * <p>必须按视口裁剪：字体列表这类模块一次塞几百个按钮，全挂上去每帧都要走一遍渲染与命中，
     * 屏幕外那些既看不见又白花时间。挂上去的集合只在滚动跨过一行时才变，
     * 所以这里按对象身份比对，没变就只更新位置。
     */
    private void syncCustomWidgets() {
        int top = bodyTop();
        int bottom = top + bodyHeight();
        List<DrawableWidget> want = new ArrayList<>();
        for (int i = 0; i < customWidgets.size() && i < customRowY.size(); i++) {
            int rowY = customRowY.get(i);
            if (rowY + ROW_H < top || rowY > bottom) {
                continue;
            }
            DrawableWidget widget = customWidgets.get(i);
            widget.setX(contentLeft);
            widget.setY(rowY);
            want.add(widget);
        }
        if (want.size() == mountedCustom.size()
                && java.util.stream.IntStream.range(0, want.size()).allMatch(i -> want.get(i) == mountedCustom.get(i))) {
            return;
        }
        for (DrawableWidget old : mountedCustom) {
            this.removeWidget(old);
        }
        mountedCustom.clear();
        for (DrawableWidget widget : want) {
            this.addRenderableWidget(widget);
            mountedCustom.add(widget);
        }
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
            } else if (target.ref().getValue() instanceof LabelVec2 label2) {
                Vec2 data = part == 0
                        ? new Vec2(value, label2.data().y())
                        : new Vec2(label2.data().x(), value);
                setValue(target.ref(), label2.withData(data));
            } else if (target.ref().getValue() instanceof LabelVec3 label3) {
                Vec3 data = switch (part) {
                    case 0 -> new Vec3(value, label3.data().y(), label3.data().z());
                    case 1 -> new Vec3(label3.data().x(), value, label3.data().z());
                    default -> new Vec3(label3.data().x(), label3.data().y(), value);
                };
                setValue(target.ref(), label3.withData(data));
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
        EditBox box = HostControls.editBox(
                geo.hexX(), colorRowY + FIELD_TOP + FIELD_PAD, geo.hexW(), 16, null);
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
     * 文本配置的输入框：长在同一个值区矩形里，框由 {@link #paintFieldFrame} 画，这里只管字。
     *
     * <p>和数值、颜色两只同一条规矩：原版控件只在 tick 里增删，点击与渲染期间只改字段。
     */
    private void startTextBox() {
        Box field = textValueBox(textRowY);
        TextEditBox box = new TextEditBox(
                field.x() + FIELD_PAD,
                field.y() + FIELD_PAD,
                field.w() - FIELD_PAD * 2,
                9,
                this::closeTextEdit);
        box.setBordered(false);
        // 路径可能很长，上限只挡手滑；原值超长时提交前会先比对，不会被这一项截短
        box.setMaxLength(1024);
        this.textBox = box;
        this.lastTextKey = textKey(textTarget, textRowY);
        applyTextBoxText(box, textTarget);
        this.addRenderableWidget(box);
        this.setFocused(box);
    }

    private static String textKey(WrapperConfigRef<?> target, int rowY) {
        return System.identityHashCode(target) + ":" + rowY;
    }

    /** 文本编辑框里该显示什么：字符串配置就是它自己，正则取表达式原文。 */
    private static String textOf(WrapperConfigRef<?> target) {
        Object raw = target == null ? null : target.ref().getValue();
        if (raw instanceof Regex regex) {
            return regex.regex();
        }
        if (raw instanceof StringFormat format) {
            // toString 是对象身份，编辑框里要的是格式串本身
            return format.formatString();
        }
        if (raw instanceof ItemStackTemplate template) {
            return template.itemId().toString();
        }
        return raw == null ? "" : raw.toString();
    }

    /**
     * 把配置值写进输入框，并把 responder 重新绑到目标项上。
     *
     * <p>{@code EditBox.setValue} 会同步回调 responder，程序写值前必须先摘掉：
     * 否则「换一行再开编辑」会被当成用户输入，把旧值写回新目标。
     * 草稿只记下来、不动配置，提交那一步才写回，所以打到一半的字不会存盘。
     */
    private void applyTextBoxText(EditBox box, WrapperConfigRef<?> target) {
        String text = textOf(target);
        this.textOriginal = text;
        this.textDraft = text;
        box.setResponder(null);
        try {
            if (!text.equals(box.getValue())) {
                box.setValue(text);
            }
        } finally {
            box.setResponder(value -> {
                if (box == this.textBox) {
                    this.textDraft = value;
                }
            });
        }
    }

    /**
     * 把草稿写回配置：提交的是 trim 之后的原文，空串照写（空路径 = 不播）。
     *
     * <p>没改过就不写回：输入框有长度上限，原样提交会把超长值截短一截。
     */
    private void commitText() {
        WrapperConfigRef<?> target = textTarget;
        String draft = textDraft;
        if (target == null || draft == null) {
            return;
        }
        String next = draft.trim();
        if (next.equals(textOriginal)) {
            return;
        }
        if (target.ref().getValue() instanceof Regex) {
            // 非法表达式不落配置：打到一半的括号不该把这一项变成永远匹配不上的正则
            try {
                setValue(target.ref(), new Regex(next));
                this.textOriginal = next;
            } catch (RuntimeException ignored) {
                // 表达式还不完整，等打完
            }
            return;
        }
        if (target.ref().getValue() instanceof ItemStackTemplate template) {
            // 只换物品类型，数据组件原样保留；id 解析不出来就不落配置
            Identifier id = Identifier.tryParse(next);
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                template.setType(id);
                setValue(target.ref(), template);
            }
            this.textOriginal = next;
            return;
        }
        if (target.ref().getValue() instanceof Holder<?> holder) {
            // 解析失败就不落配置：写坏一个 id 会让这一项永远指向不存在的条目
            Holder.parse(next).ifSuccess(parsed -> setValue(target.ref(), parsed));
            this.textOriginal = next;
            return;
        }
        if (target.ref().getValue() instanceof StringFormat format) {
            // 只换格式串，参数表与 colorString 原样保留：那是这一项的契约，不该被编辑值顺手改掉
            setValue(target.ref(), format.withFormatString(next));
            this.textOriginal = next;
            return;
        }
        setValue(target.ref(), next);
        this.textOriginal = next;
    }

    /**
     * 收起文本编辑：先把打的字提交掉，再作废目标。
     *
     * <p>没在编辑时什么都不做——点击与滚动每一下都会叫到这里，不能顺手清掉别处的焦点
     * （名单的搜索框也靠这一个焦点收字）。目标已经作废的那一段里先把框藏起来：
     * 原版控件要等 tick 才能拆，不藏的话行里画的值与输入框里的字会叠在同一格上。
     */
    private void closeTextEdit() {
        if (textTarget == null) {
            return;
        }
        commitText();
        this.textTarget = null;
        this.textDraft = null;
        this.textOriginal = null;
        if (textBox != null) {
            // 目标已经作废：原版控件要等 tick 才拆，这一小段里不能再让它接键
            textBox.setResponder(null);
            textBox.setVisible(false);
        }
        // 焦点留在已经作废的框上，按下的键会落进一只看不见的输入框里
        this.setFocused(null);
    }

    /**
     * 文本输入框：Enter 直接提交。
     *
     * <p>原版 {@code EditBox} 不接 Enter（它只处理退格、方向键与复制粘贴），
     * 而按键只派给拿到焦点的那一个控件，所以提交只能在这一层拦下来。
     * Esc 不拦：它仍走 {@code Screen}，照原样关整屏。
     */
    private static final class TextEditBox extends EditBox {
        private final Runnable submit;

        TextEditBox(int x, int y, int width, int height, Runnable submit) {
            super(Minecraft.getInstance().font, x, y, width, height, Component.empty());
            this.submit = submit;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            int key = event.key();
            if (key == KeyCode.KEY_ENTER || key == KeyCode.KEY_KP_ENTER) {
                this.submit.run();
                return true;
            }
            return super.keyPressed(event);
        }
    }

    /**
     * 宿主控件的增删不能发生在点击/渲染的遍历里，所以每 tick 同步一次：
     * 这一帧还在的行跟着走，消失的行卸掉，新出现的行建出来。
     */
    @Override
    public void tick() {
        super.tick();
        // 自定义控件的位置来自 buildRows 排出的行：tick 可能先于第一次绘制跑，
        // 不先建一次的话首帧挂上去的控件全在 (0,0)
        buildRows();
        syncCustomWidgets();
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
        if (textTarget == null) {
            if (textBox != null) {
                textBox.setResponder(null);
                this.removeWidget(textBox);
                textBox = null;
            }
        } else if (textBox == null) {
            startTextBox();
        } else if (!textKey(textTarget, textRowY).equals(lastTextKey)) {
            // 换一行就换一只输入框：不复用，也就没有"旧绑定写新值"的机会
            textBox.setResponder(null);
            this.removeWidget(textBox);
            textBox = null;
            startTextBox();
        }
        ListBox panelBox = listBox();
        if (panelBox == null) {
            if (listPanel != null) {
                // 目标行不在这一屏上了（换页、筛选）：面板没有可列的集合
                listPanel = null;
            }
            if (listSearchBox != null) {
                listSearchBox.setResponder(null);
                this.removeWidget(listSearchBox);
                listSearchBox = null;
            }
        } else if (listSearchBox == null) {
            startListSearch(panelBox);
        }
        // 宿主控件不再嵌进本屏：没有专用控件的类型由 paintReadOnly 画成只读文本
    }

    /**
     * 本屏自己画的控件：开关 / 数值 / 枚举 / 键位 / 颜色 / 名单 / 文本，以及"一条记录里几个 double"（例如 {@code Vec2}）。
     * 后者也必须在列，否则行里画的是自己的滑杆、点击却让给宿主控件，两头都没接上。
     *
     * <p>不在列的类型一律画成只读文本（{@link #paintReadOnly}）：宿主那套编辑器是另一套外观，
     * 混进同一张表里比暂时不可编辑更难看。
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
                || value.getValue() instanceof EntrySet<?>
                || value.getValue() instanceof OptionalPrimitive<?>
                || value instanceof StringRef
                || value.getValue() instanceof StringEnum
                || value.getValue() instanceof StringFormat
                || value.getValue() instanceof PrimitiveList<?>
                || value.getValue() instanceof WidgetPos
                || value.getValue() instanceof PrimitiveMap<?, ?>
                || value.getValue() instanceof Holder<?>
                || value.getValue() instanceof ItemStackTemplate
                || value.getValue() instanceof GuiSetting
                || value.getValue() instanceof EntityTypeEntrySet
                || value.getValue() instanceof BoundedPrimitiveFlagMap<?>
                || value.getValue() instanceof LabelVec2
                || value.getValue() instanceof LabelVec3
                || value instanceof ListRef
                || value.getValue() instanceof RegexList
                || value.getValue() instanceof Regex
                || recordParts(value.getValue()) != null
                || booleanParts(value.getValue()) != null;
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
     * <p>两格到八格都接：宿主里两个数值的记录（{@code Vec2}）、
     * 「两个数值 + 一个枚举」的记录（{@code PredictArgument}）都很常见。
     */
    private static Object[] recordParts(Object value) {
        if (value == null || !value.getClass().isRecord()) {
            return null;
        }
        RecordComponent[] parts = value.getClass().getRecordComponents();
        // 上限 8 格：再多就不是「一条记录里几个数」而是嵌套结构了，逐格滑杆排不下
        if (parts.length < 2 || parts.length > 8) {
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

    /**
     * 一条记录里的几格开关：全是布尔才算，混着别的类型就交回只读。
     *
     * <p>与 {@link #recordParts} 分开认：开关和滑杆的行高一样，但画法与命中都不同，
     * 合成一个数组会让「这一格是数值还是开关」在每处都要再判一次。
     */
    private static boolean[] booleanParts(Object value) {
        if (value == null || !value.getClass().isRecord()) {
            return null;
        }
        RecordComponent[] parts = value.getClass().getRecordComponents();
        if (parts.length < 2 || parts.length > 4) {
            return null;
        }
        boolean[] out = new boolean[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                Object part = parts[i].getAccessor().invoke(value);
                if (!(part instanceof Boolean flag)) {
                    return null;
                }
                out[i] = flag;
            }
        } catch (Throwable e) {
            return null;
        }
        return out;
    }

    /** 这一行占几格：记录按分量数铺开，其余类型都是一格。绘制与布局必须取同一个数。 */
    private static int rowParts(Object value) {
        Object[] parts = recordParts(value);
        if (parts != null) {
            return parts.length;
        }
        boolean[] bools = booleanParts(value);
        if (bools != null) {
            return bools.length;
        }
        // 位置配置画两行滑杆（X / Y），行高必须跟着算两行，否则下一行会压上来
        if (value instanceof WidgetPos) {
            return 2;
        }
        // 带标签的向量：标签是记录里的字符串，数值是内层的 Vec2 / Vec3
        if (value instanceof LabelVec2) {
            return 2;
        }
        if (value instanceof LabelVec3) {
            return 3;
        }
        return 1;
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
    /**
     * 字符串型配置的候选值提供者：键是配置路径，值是候选列表。
     *
     * <p>枚举型配置自带 {@code getEnumConstants()}，但字符串型（比如模型文件名）的候选是运行时才知道的，
     * 只能由模块自己注册。没注册的字符串仍然走文本框。
     */
    private static final java.util.Map<String, java.util.function.Supplier<java.util.List<String>>>
            STRING_CHOICES = new java.util.concurrent.ConcurrentHashMap<>();

    /** 给一个字符串型配置注册候选值；传 {@code null} 取消注册。 */
    public static void registerStringChoices(String path,
            java.util.function.Supplier<java.util.List<String>> choices) {
        if (path == null || path.isEmpty()) {
            return;
        }
        if (choices == null) {
            STRING_CHOICES.remove(path);
        } else {
            STRING_CHOICES.put(path, choices);
        }
    }

    /**
     * 按配置路径取候选值。
     *
     * <p>给宿主侧用：宿主配置页的值控件由 {@code AttrKeyValue#generateValueWidget} 统一创建，
     * 插件在那里注入时只拿得到 {@code getKeyName()}，拿不到 {@code WrapperConfigRef}。
     * 键格式与 {@link #registerStringChoices} 一致（{@code custom-model.model} 这种点分全路径）。
     */
    public static java.util.List<String> stringChoicesOfPath(String path) {
        if (path == null || path.isEmpty()) {
            return java.util.List.of();
        }
        java.util.function.Supplier<java.util.List<String>> supplier = STRING_CHOICES.get(path);
        if (supplier == null) {
            return java.util.List.of();
        }
        java.util.List<String> choices = supplier.get();
        return choices == null ? java.util.List.of() : choices;
    }

    /** 这个字符串配置有没有候选值；有就走下拉而不是文本框。 */
    private static java.util.List<String> stringChoicesOf(WrapperConfigRef<?> ref) {
        String path = pathOf(ref);
        java.util.function.Supplier<java.util.List<String>> supplier = path == null ? null : STRING_CHOICES.get(path);
        if (supplier == null) {
            return java.util.List.of();
        }
        java.util.List<String> choices = supplier.get();
        return choices == null ? java.util.List.of() : choices;
    }

    /** 配置项的完整路径（{@code a.b.c}），与 {@link #registerStringChoices} 的键一致。 */
    private static String pathOf(WrapperConfigRef<?> ref) {
        String[] parts = ref.path();
        return parts == null || parts.length == 0 ? null : String.join(".", parts);
    }

    private static Object[] optionsFor(WrapperConfigRef<?> ref, int part) {
        if (part < 0) {
            java.util.List<String> choices = stringChoicesOf(ref);
            if (!choices.isEmpty()) {
                return choices.toArray();
            }
            if (ref.ref().getValue() instanceof StringEnum choice) {
                return choice.values().toArray();
            }
        }
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
        return winY + barH;
    }

    /** 正文一直铺到页脚上沿：中间留空的那几像素没有任何绘制，会透出背后的世界。 */
    private int bodyHeight() {
        return winH - barH - FOOT_H;
    }

    /**
     * 内容区的左边界与宽度：只由窗口矩形决定。
     *
     * <p>子类在 {@code refreshWidgets} 里就要建原版输入框（{@code contentLeftPx()} 那些钩子），
     * 而那一步早于第一次绘制，所以布局不能只发生在 {@code buildRows} 里。
     */
    private void layoutContent() {
        contentLeft = winX + PAD;
        contentWidth = winW - PAD * 2;
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
            int height = ROW_H * rowParts(ref.ref().getValue());
            rows.add(new Row(y, height, ref, section, false));
            y += height;
        }
        // 模块自己往配置页塞的控件（聊天密钥、服务器存储…）：宿主也是这么接的，
        // 不接的话这些模块的入口在本界面里整个消失。
        // 工厂换成本界面那一套：控件仍是宿主的元素类，只是配色与形状跟着本界面走，
        // 否则这一节会是一整片宿主外观，与上面的自绘行对不上。
        String key = System.identityHashCode(module) + ":" + contentWidth + ":" + light();
        if (!key.equals(customKey)) {
            customKey = key;
            customWidgets.clear();
            if (module != null) {
                module.addCustomWidgets(
                        widget -> customWidgets.add(widget), contentWidth, ROW_H, 6,
                        new EndfieldElementFactory(light(), controlRight() - contentLeft));
            }
            // 宿主自己造的那几个图标按钮绕过了工厂（列表入口写死了绿色精灵图），挂载前换成本界面的画法
            restyleCustomWidgets();
        }
        customRowY.clear();
        if (!customWidgets.isEmpty()) {
            rows.add(new Row(y, SECTION_H, null, CUSTOM_SECTION, true));
            y += SECTION_H;
            for (int i = 0; i < customWidgets.size(); i++) {
                rows.add(new Row(y, ROW_H, null, CUSTOM_SECTION, false));
                customRowY.add(y);
                y += ROW_H;
            }
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

    /**
     * 把宿主自己造的图标按钮换成本界面的画法。
     *
     * <p>模块的 {@code addCustomWidgets} 里，输入框、开关都走工厂，但列表入口是宿主
     * {@code WidgetUtils.createOpenListModifyScreenButton} 直接造的：它把写死的绿色精灵图
     * 塞进 {@code IconElement}，工厂连插手的机会都没有。所以只能在这一层换掉——
     * 控件本身（点击、说明框）原样保留，只换渲染。
     *
     * <p>只认「图标 + 点击」这一种形态：别的图标按钮（文件树、颜色编辑器…）各有各的语义，
     * 一律不动，免得把不相干的按钮也画成列表入口。
     */
    private void restyleCustomWidgets() {
        for (DrawableWidget widget : customWidgets) {
            if (!(widget instanceof SubScreenWidget sub)) {
                continue;
            }
            for (DrawableWidget child : sub.childrenRenderOrder()) {
                if (!(child instanceof ExecutableWidget exec) || !(exec.getHandler() instanceof IconElement icon)) {
                    continue;
                }
                if (!Constants.LIST_TAG_SPRITE.equals(icon.getTextureId(null, child, false))) {
                    continue;
                }
                exec.setRenderHandler(new EndfieldElementFactory(light())
                        .createListButton((element, widget0, mouseButton) -> icon.onClick(widget0, 0, 0, mouseButton)));
            }
        }
    }

    private int maxScroll() {
        return Math.max(0, totalHeight - bodyHeight() + 8);
    }

    // ------------------------------------------------------------------ 绘制

    private void paint(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        Ef.Palette p = pal();
        buildRows();
        hovered = null;
        hoveredAction = null;
        truncatedHover = null;
        formatArgumentHover = null;
        editorHover = null;
        itemTemplateHover = false;

        paintBar(ctx, p, mouseX, mouseY);
        paintBody(ctx, p, mouseX, mouseY);
        paintDropdown(ctx, p, mouseX, mouseY);
        paintFooter(ctx, p);
        paintFrame(ctx, p);
        // 调色盘与名单面板是浮层：它们越过正文裁剪与页脚，所以画在最后，但仍让说明框盖在上面
        paintPalette(ctx, p, mouseX, mouseY);
        paintListPanel(ctx, p, mouseX, mouseY);
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
                    } else if (row.ref != null) {
                        paintRow(ctx, p, row.y, row.ref, mouseX, mouseY);
                    }
                    // 自定义控件自己画自己，这里只留出行高
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
        boolean custom = CUSTOM_SECTION.equals(section);
        String head = actions
                ? "ACTIONS"
                : custom
                        ? "CUSTOM"
                        : (section.isEmpty() ? "MAIN" : leaf(section).replace('-', ' ').toUpperCase(Locale.ROOT));
        String eyebrow = "// " + head;
        Ef.trackedWide(ctx, eyebrow, contentLeft, y + 7, p.ink2(), 1, 1.2F);
        int titleX = contentLeft + Math.max(72, Ef.trackedWideWidth(eyebrow, 1, 1.2F) + 10);
        String title = actions ? "操作" : custom ? "模块工具" : sectionTitle(section);
        Ef.text(ctx, title, titleX, y + 7, p.ink());
        Ef.rule(ctx, contentLeft, y + SECTION_H - 6, contentWidth, p.rule());
    }

    private void paintRow(
            VDrawContext ctx, Ef.Palette p, int y, WrapperConfigRef<?> ref, int mouseX, int mouseY) {
        int right = controlRight();
        int rowH = ROW_H * rowParts(ref.ref().getValue());
        // 浮层盖住的行不算悬停：下拉/名单面板打开时，鼠标下面是浮层，不是这一行
        boolean covered = dropdown != null || listPanel != null;
        boolean hover = !covered
                && mouseY >= y && mouseY < y + rowH && mouseX >= contentLeft - 4 && mouseX <= right + 4;
        // 编辑文本时不出说明框：输入框是后加的 renderable，画在整块画布之上，说明框会被它压住
        if (hover && textTarget == null) {
            hovered = ref;
        }
        if (hover) {
            Ef.fill(ctx, contentLeft - 4, y, contentWidth + 8, rowH, Ef.mix(p.paper(), p.ink(), 0.04f));
            Ef.fill(ctx, contentLeft - 4, y, 3, rowH, p.trace());
        }
        // 文本行的值区占住行右半段，行名按它让出的宽度截断，长路径压不到行名上
        Box textValue = ref.ref() instanceof StringRef
                        || ref.ref().getValue() instanceof Regex
                        || ref.ref().getValue() instanceof Holder<?>
                ? textValueBox(y)
                : null;
        int labelW = textValue != null
                ? Math.max(40, textValue.x() - contentLeft - 10)
                : (slotRow(ref.ref().getValue()) ? Math.max(24, labelWidth() - 28) : labelWidth());
        Ef.text(ctx, Ef.ellipsis(label(ref), labelW), contentLeft, y + 5, p.ink());
        Ef.rule(ctx, contentLeft - 4, y + rowH - 1, contentWidth + 8, Ef.alpha(p.rule(), 0.55F));

        // 行尾的重置：宿主自带图标，只有带默认值的项才画
        if (ref.ref().hasDefaultValue()) {
            int iconX = contentLeft + contentWidth - 13;
            boolean hot = mouseX >= iconX - 2 && mouseX <= iconX + 12 && mouseY >= y && mouseY < y + 12;
            if (hot) {
                Ef.cutFill(ctx, iconX - 1, y + 3, 12, 12, p.chip(), Ef.CUT);
            }
            Ef.icon(ctx, HostControls.resetIcon(), iconX + 1, y + 4, 9, hot ? p.ink() : p.ink2());
        }

        Ref<?> value = ref.ref();
        Object raw = value.getValue();
        Object[] parts = recordParts(raw);
        boolean[] bools = booleanParts(raw);

        if (value instanceof FlagRef flag) {
            paintSwitch(ctx, p, y, right, flag.get());
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
        } else if (bools != null) {
            // 一条记录里的几格开关：每格占一行，行名用字段名，开关贴着行尾
            String[] names = componentNames(raw);
            for (int i = 0; i < bools.length; i++) {
                int partY = y + i * ROW_H;
                paintPartName(ctx, p, partY, right - 28, names[i]);
                paintSwitch(ctx, p, partY, right, bools[i]);
            }
        } else if (raw instanceof WrapColor) {
            paintColor(ctx, p, y, ref);
        } else if (raw instanceof EntrySet<?> set) {
            paintEntrySet(ctx, p, y, ref, set);
        } else if (value instanceof StringRef text && !stringChoicesOf(ref).isEmpty()) {
            // 有候选值的字符串：值框本身可编辑，右侧箭头开候选列表。
            // 只给下拉会让人填不进扫描不到的路径（绝对路径、还没放进 models 的文件）。
            Box field = textValueBox(y);
            boolean open = dropdown == ref;
            if (textTarget == ref) {
                paintFieldFrame(ctx, p, field.x(), field.y(), field.w(), field.h(), true, false);
                if (textBox != null) {
                    // 输入框默认的白字在浅色主题的 panel 底上看不见
                    textBox.setTextColor(p.ink());
                }
            } else {
                boolean hot = inBox(field.x(), field.y(), field.w(), field.h(), mouseX, mouseY);
                paintFieldFrame(ctx, p, field.x(), field.y(), field.w(), field.h(), open, hot);
                String shown = text.get();
                boolean empty = shown == null || shown.isEmpty();
                String label = empty ? EMPTY_TEXT : shown;
                String clipped = Ef.ellipsis(label, field.w() - FIELD_PAD * 2 - ARROW_W);
                Ef.text(ctx, clipped, field.x() + FIELD_PAD, field.y() + FIELD_PAD,
                        empty ? p.ink2() : p.ink());
                // 箭头画在值框右端：它才是「开候选」的那一块，其余位置都是编辑
                String arrow = "▾";
                Ef.text(ctx, arrow, field.x() + field.w() - ARROW_W + (ARROW_W - Ef.textWidth(arrow)) / 2,
                        field.y() + FIELD_PAD, open ? p.ink() : p.ink2());
                // 值框会裁短：悬停时把全名补出来
                if (!clipped.equals(label) && hot) {
                    truncatedHover = label;
                }
            }
        } else if (value instanceof StringRef text) {
            paintTextValue(ctx, p, textValue, ref, text.get(), mouseX, mouseY);
        } else if (raw instanceof StringEnum choice) {
            // 有限字符串候选：与枚举同一套下拉，值框显示当前项
            String text = choice.value() + "   ▾";
            int textW = Ef.textWidth(text);
            int chipX = right - textW - 10;
            boolean open = dropdown == ref;
            Ef.cutFill(ctx, chipX, y + 3, textW + 10, 11, open ? p.ink() : p.chip(), Ef.CUT);
            Ef.text(ctx, text, chipX + 5, y + 5, open ? p.paper() : p.ink());
        } else if (value instanceof ListRef listRef) {
            // 字符串列表：与名单同一套计数块，点开宿主列表编辑屏
            paintCountChip(ctx, p, y, ref, listRef.get().size() + " 项");
        } else if (raw instanceof RegexList regexList) {
            paintCountChip(ctx, p, y, ref, regexList.patterns().size() + " 项");
        } else if (raw instanceof ItemStackTemplate template) {
            paintItemTemplate(ctx, p, y, ref, template, mouseX, mouseY);
        } else if (raw instanceof Holder<?> holder) {
            // 注册表条目：值框里写「注册表|条目 id」，与宿主存盘格式一致，写回时再解析
            paintTextValue(ctx, p, textValue, ref, holder.asString(), mouseX, mouseY);
        } else if (raw instanceof PrimitiveMap<?, ?> map) {
            paintCountChip(ctx, p, y, ref, map.toPrimitiveMap().size() + " 项");
        } else if (raw instanceof WidgetPos pos) {
            paintWidgetPos(ctx, p, y, ref, pos, mouseX, mouseY);
        } else if (raw instanceof PrimitiveList<?> list) {
            // 列表类配置与名单同一套外观：一个「N 项 ▾」切角块，点开进面板
            paintCountChip(ctx, p, y, ref, list.list().size() + " 项");
        } else if (raw instanceof StringFormat format) {
            paintStringFormat(ctx, p, y, ref, format, mouseX, mouseY);
        } else if (raw instanceof Regex regex) {
            // 正则与字符串同一套值框：写回时重新构造 Regex，非法表达式不落配置
            paintTextValue(ctx, p, textValue, ref, regex.regex(), mouseX, mouseY);
        } else if (raw instanceof GuiSetting) {
            paintGuiSetting(ctx, p, y);
        } else if (raw instanceof EntityTypeEntrySet set) {
            paintCountChip(ctx, p, y, ref, set.set().size() + " 项");
        } else if (raw instanceof BoundedPrimitiveFlagMap<?> flags) {
            paintCountChip(ctx, p, y, ref, flagCount(flags) + " 项");
        } else if (raw instanceof LabelVec2 label2) {
            paintLabelVec(ctx, p, y, ref, label2.xLabel(), label2.yLabel(), null,
                    label2.data().x(), label2.data().y(), 0, mouseX, mouseY);
        } else if (raw instanceof LabelVec3 label3) {
            paintLabelVec(ctx, p, y, ref, label3.xLabel(), label3.yLabel(), label3.zLabel(),
                    label3.data().x(), label3.data().y(), label3.data().z(), mouseX, mouseY);
        } else {
            // 没有专用控件的类型：按值形态画成只读文本，不再退回宿主控件。
            // 一半自绘一半宿主会让同一张表里出现两种外观，宁可这一格暂时不可编辑。
            paintReadOnly(ctx, p, y, right, raw);
        }
    }

    /**
     * 没有专用控件的类型画成只读文本：右对齐、次要色、悬停出完整值。
     *
     * <p>这些类型（物品模板、音效、GUI 设置、列表映射…）宿主各有自己的编辑器，但那是另一套外观；
     * 同一张表里混两种控件比暂时不可编辑更难看，所以这里只显示、不接点击。
     */
    private void paintReadOnly(VDrawContext ctx, Ef.Palette p, int y, int right, Object raw) {
        String text = readOnlyText(raw);
        int maxW = Math.max(60, contentWidth / 2);
        String shown = Ef.ellipsis(text, maxW);
        Ef.text(ctx, shown, right - Ef.textWidth(shown), y + 5, p.ink2());
        if (!shown.equals(text)) {
            truncatedHover = text;
        }
    }

    /** 只读值的文本形式：能读成一句就一句，读不出来就报类型名。 */
    private static String readOnlyText(Object raw) {
        if (raw == null) {
            return "-";
        }
        if (raw instanceof Number || raw instanceof Boolean || raw instanceof CharSequence) {
            return String.valueOf(raw);
        }
        if (raw instanceof Enum<?> e) {
            return enumLabel(e);
        }
        String text = String.valueOf(raw);
        // 记录与集合的 toString 常常是一长串字段名，读不出重点，退回类型名
        return text.length() > 64 ? raw.getClass().getSimpleName() : text;
    }

    /**
     * 格式化串：值框 + 右侧两个按钮。
     *
     * <p>两个按钮不是装饰：参数提示列出这一项能用的 {@code {占位符}}，颜色编辑器打开宿主的格式代码页。
     * 少了它们，用户只能靠猜占位符名字，写错一个字母就是一条永远不生效的提示。
     */
    private void paintStringFormat(
            VDrawContext ctx, Ef.Palette p, int y, WrapperConfigRef<?> ref, StringFormat format,
            int mouseX, int mouseY) {
        StringFormatGeometry geo = stringFormatGeometry(y, format);
        boolean editing = textTarget == ref;
        if (editing) {
            paintFieldFrame(ctx, p, geo.fieldX(), geo.fieldY(), geo.fieldW(), geo.fieldH(), true, false);
            if (textBox != null) {
                textBox.setTextColor(p.ink());
            }
        } else {
            boolean hot = inBox(geo.fieldX(), geo.fieldY(), geo.fieldW(), geo.fieldH(), mouseX, mouseY);
            if (hot) {
                paintFieldFrame(ctx, p, geo.fieldX(), geo.fieldY(), geo.fieldW(), geo.fieldH(), false, true);
            } else {
                // 与文本行同一套静态态：只画一条虚线底边，悬停才现出整框
                int lineY = geo.fieldY() + geo.fieldH() - 2;
                for (int i = 0; i < geo.fieldW(); i += 3) {
                    Ef.fill(ctx, geo.fieldX() + i, lineY, Math.min(2, geo.fieldW() - i), 1, p.rule());
                }
            }
            String shown = Ef.ellipsis(format.formatString(), geo.fieldW() - FIELD_PAD * 2);
            Ef.text(ctx, shown, geo.fieldX() + geo.fieldW() - FIELD_PAD - Ef.textWidth(shown),
                    geo.fieldY() + FIELD_PAD, p.ink());
        }
        // 参数提示：只有声明了占位符才画，否则按钮点开是一张空表
        if (geo.argX() >= 0) {
            boolean hot = inBox(geo.argX(), geo.btnY(), geo.btnW(), geo.btnH(), mouseX, mouseY);
            if (hot) {
                Ef.cutFill(ctx, geo.argX(), geo.btnY(), geo.btnW(), geo.btnH(), p.chip(), Ef.CUT);
            }
            Ef.icon(ctx, Constants.FORMATTING_TEXTURE_SPRITE, geo.argX(), geo.btnY(), geo.btnW(),
                    hot ? p.ink() : p.ink2());
            if (hot) {
                formatArgumentHover = format;
            }
        }
        // 颜色代码编辑器：只有 colorString 的项才有意义
        if (geo.editX() >= 0) {
            boolean hot = inBox(geo.editX(), geo.btnY(), geo.btnW(), geo.btnH(), mouseX, mouseY);
            if (hot) {
                Ef.cutFill(ctx, geo.editX(), geo.btnY(), geo.btnW(), geo.btnH(), p.chip(), Ef.CUT);
            }
            Ef.icon(ctx, Constants.EDITOR_SPRITE, geo.editX(), geo.btnY(), geo.btnW(),
                    hot ? p.ink() : p.ink2());
            if (hot) {
                editorHover = format;
            }
        }
    }

    /** 格式化串那一行的几何：值框让出右侧按钮，绘制与命中共用。 */
    private record StringFormatGeometry(
            int fieldX, int fieldY, int fieldW, int fieldH,
            int argX, int editX, int btnY, int btnW, int btnH) {}

    private StringFormatGeometry stringFormatGeometry(int y, StringFormat format) {
        int right = controlRight();
        int btn = 13;
        int gap = 2;
        // 按钮从右往左排，只给真正会画的那几个留位置：
        // 固定按两个算的话，没有颜色编辑器的项右边会空出一格
        int cursor = right;
        int editX = -1;
        int argX = -1;
        if (format.colorString()) {
            editX = cursor - btn;
            cursor = editX - gap;
        }
        if (!format.formattingArgument().isEmpty()) {
            argX = cursor - btn;
            cursor = argX - gap;
        }
        // 值框右对齐到最后一个按钮左边，与文本行同一套几何：左对齐会横跨整行、把行名盖住
        int fieldW = Math.max(120, Math.min(280, contentWidth / 2));
        int fieldX = Math.max(contentLeft, cursor - fieldW);
        return new StringFormatGeometry(
                fieldX, y + FIELD_TOP, fieldW, FIELD_H,
                argX, editX, y + 2, btn, btn);
    }

    /**
     * 字段框：直角、panel 底、1px 边，聚焦时边框内侧再压一圈 2px 的当前位置线。
     *
     * <p>切角在这套外观里表示"可点击"，输入框是往里写东西的格子，所以是直角。
     * 聚焦环画在边框内侧而不是向外扩：框的外沿尺寸不随状态变，行距与相邻控件不会因为聚焦挪位。
     * 环取 {@code trace}：深色主题是强调色，浅色主题退成墨色——浅纸上黄线看不见。
     *
     * <p>数值、颜色、名单搜索与文本四处都走这一处，不再各画一套。
     */
    private static void paintFieldFrame(
            VDrawContext ctx, Ef.Palette p, int x, int y, int w, int h, boolean focused, boolean hovered) {
        Ef.fill(ctx, x, y, w, h, p.panel());
        if (focused) {
            // 聚焦只留一圈 2px 的当前位置线：再叠 1px 边框就成了双线，框里给字的地方被吃掉一圈
            Ef.fill(ctx, x + FIELD_RING_INSET, y + FIELD_RING_INSET, w - FIELD_RING_INSET * 2, FIELD_RING, p.trace());
            Ef.fill(ctx, x + FIELD_RING_INSET, y + h - FIELD_RING_INSET - FIELD_RING,
                    w - FIELD_RING_INSET * 2, FIELD_RING, p.trace());
            Ef.fill(ctx, x + FIELD_RING_INSET, y + FIELD_RING_INSET, FIELD_RING, h - FIELD_RING_INSET * 2, p.trace());
            Ef.fill(ctx, x + w - FIELD_RING_INSET - FIELD_RING, y + FIELD_RING_INSET,
                    FIELD_RING, h - FIELD_RING_INSET * 2, p.trace());
            return;
        }
        int edge = hovered ? p.ink2() : p.rule();
        Ef.fill(ctx, x, y, w, 1, edge);
        Ef.fill(ctx, x, y + h - 1, w, 1, edge);
        Ef.fill(ctx, x, y, 1, h, edge);
        Ef.fill(ctx, x + w - 1, y, 1, h, edge);
    }

    /**
     * 文本值区的矩形：宽度取正文的一半并夹在 [120,280]，右边缘对齐到 {@link #controlRight()}。
     *
     * <p>宽度固定才好在同一段里截断：值长短不一时框不会忽宽忽窄，点击区与输入框也有准数。
     * 右边缘到 {@code controlRight()} 为止，行尾那一列留给重置图标。
     */
    private Box textValueBox(int rowY) {
        int w = Math.max(120, Math.min(280, contentWidth / 2));
        return new Box(controlRight() - w, rowY + FIELD_TOP, w, FIELD_H);
    }

    /**
     * 文本行：默认只写值，悬停时现出字段框表示这一格能改，编辑时框交给输入框。
     *
     * <p>值右对齐到框的内沿（不是 {@code controlRight()}）：框一出现字不会横向跳一格，
     * 悬停也就不会让值左右挪。编辑期间字由输入框自己画，这里只给框，两边取同一份几何。
     */
    private void paintTextValue(
            VDrawContext ctx, Ef.Palette p, Box box, WrapperConfigRef<?> ref, String current,
            int mouseX, int mouseY) {
        if (textTarget == ref) {
            paintFieldFrame(ctx, p, box.x(), box.y(), box.w(), box.h(), true, false);
            if (textBox != null) {
                // 输入框默认的白字在浅色主题的 panel 底上看不见
                textBox.setTextColor(p.ink());
            }
            return;
        }
        if (inBox(box.x(), box.y(), box.w(), box.h(), mouseX, mouseY)) {
            paintFieldFrame(ctx, p, box.x(), box.y(), box.w(), box.h(), false, true);
        } else {
            // 静态态只画一条虚线底边：不抢视线，又能看出这一格能点开编辑。
            // 比行的分隔线高 1px 且断成短划，两条线不会并成一条；悬停与聚焦时都不画
            int lineY = box.y() + box.h() - 2;
            for (int i = 0; i < box.w(); i += 3) {
                Ef.fill(ctx, box.x() + i, lineY, Math.min(2, box.w() - i), 1, p.rule());
            }
        }
        boolean empty = current == null || current.isEmpty();
        String shown = Ef.ellipsis(empty ? EMPTY_TEXT : current, box.w() - FIELD_PAD * 2);
        Ef.text(ctx, shown, box.x() + box.w() - FIELD_PAD - Ef.textWidth(shown), box.y() + FIELD_PAD,
                empty ? p.ink2() : p.ink());
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
            // 手输的只有十六进制那一段：左边色块是另一件可点件（开调色盘），框住它会把两段说成同一件事
            paintFieldFrame(ctx, p, box.hexX() - FIELD_PAD, y + FIELD_TOP, box.hexW() + FIELD_PAD * 2,
                    FIELD_H, true, false);
            if (colorBox != null) {
                colorBox.setTextColor(p.ink());
            }
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

    // ------------------------------------------------------------------ 名单面板

    /**
     * 名单行：切角块里写「N 项」和展开箭头，块本身就是面板的开关。
     *
     * <p>行里只画计数不画内容：这一行固定 18px 高，名单动辄上百项，铺不开；
     * 真正的勾选在点开的面板里做，计数留着让人一眼看出点下去有没有改到东西。
     */
    private void paintEntrySet(VDrawContext ctx, Ef.Palette p, int y, WrapperConfigRef<?> ref, EntrySet<?> set) {
        Box chip = entrySetChip(set, y);
        boolean open = listPanel == ref;
        Ef.cutFill(ctx, chip.x(), chip.y(), chip.w(), chip.h(), open ? p.ink() : p.chip(), Ef.CUT);
        Ef.text(ctx, entrySetChipText(set), chip.x() + 5, chip.y() + 2, open ? p.paper() : p.ink());
    }

    /**
     * GUI 设置：一个入口块，点开宿主那一整套编辑屏。
     *
     * <p>它由十来个分量组成（文本、背景、标题、按钮、输入框、滑条、选择项），
     * 还带「当前风格用不用自定义配色」「滑条关掉时后两项不出现」这类联动；
     * 自绘一份等于把宿主的编辑器重写一遍，两边的默认值还会各自漂移。
     * 所以这里只摆入口，编辑能力借宿主的——与键值表、列表同一个思路。
     */
    private void paintGuiSetting(VDrawContext ctx, Ef.Palette p, int y) {
        Box chip = guiSettingChip(y);
        Ef.cutFill(ctx, chip.x(), chip.y(), chip.w(), chip.h(), p.chip(), Ef.CUT);
        Ef.text(ctx, GUI_SETTING_CHIP, chip.x() + 5, chip.y() + 2, p.ink());
    }

    /** 有界开关表里打开了几项：只数开着的，与宿主编辑屏的语义一致。 */
    private static int flagCount(BoundedPrimitiveFlagMap<?> flags) {
        int count = 0;
        for (Object value : flags.toMap().values()) {
            if (Boolean.TRUE.equals(value)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 带标签的向量：每格占一行，行名取记录里自带的标签。
     *
     * <p>标签是数据的一部分（{@code LabelVec2} 的 {@code xLabel}），不是字段名，
     * 所以不能走 {@link #componentNames}——那个读的是记录分量名，会画成「X LABEL」。
     */
    private void paintLabelVec(
            VDrawContext ctx, Ef.Palette p, int y, WrapperConfigRef<?> ref,
            String xLabel, String yLabel, String zLabel, double x, double y2, double z,
            int mouseX, int mouseY) {
        String[] labels = {xLabel, yLabel, zLabel};
        double[] values = {x, y2, z};
        int count = zLabel == null ? 2 : 3;
        int right = controlRight();
        for (int i = 0; i < count; i++) {
            int partY = y + i * ROW_H;
            String text = formatNumber(values[i]);
            int width = Math.max(TEXT_W, Ef.textWidth(text) + 12);
            int slotX = right - width;
            int slotY = partY + FIELD_TOP;
            paintPartName(ctx, p, partY, slotX - 8, labelText(labels[i]));
            if (numberTarget == ref && numberPart == i) {
                paintFieldFrame(ctx, p, slotX, slotY, width, FIELD_H, true, false);
                continue;
            }
            boolean hot = inBox(slotX, slotY, width, FIELD_H, mouseX, mouseY);
            if (hot) {
                paintFieldFrame(ctx, p, slotX, slotY, width, FIELD_H, false, true);
            } else {
                int lineY = slotY + FIELD_H - 2;
                for (int k = 0; k < width; k += 3) {
                    Ef.fill(ctx, slotX + k, lineY, Math.min(2, width - k), 1, p.rule());
                }
            }
            Ef.text(ctx, text, slotX + width - 6 - Ef.textWidth(text), partY + 5, p.ink());
        }
    }

    /** 标签是翻译键：取不到就原样显示，别让一格坏掉整行。 */
    private static String labelText(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return Component.translatableWithFallback(key, key).getString();
    }

    /** GUI 设置入口块的矩形：绘制与命中取同一份，块才不会「看得见点不到」。 */
    private Box guiSettingChip(int rowY) {
        int width = Ef.textWidth(GUI_SETTING_CHIP) + 10;
        return new Box(controlRight() - width, rowY + 3, width, 12);
    }

    /**
     * 列表类配置的计数块：与名单同一套外观，但点开的是宿主的列表编辑器。
     *
     * <p>元素带 {@code NBTType}，增删行要按元素类型重建，自绘一份等于重写宿主的列表编辑器；
     * 这里只把「有几项」摆出来，点开交给宿主——外观上仍是一个切角块，不会露出宿主控件。
     */
    private void paintCountChip(VDrawContext ctx, Ef.Palette p, int y, WrapperConfigRef<?> ref, String text) {
        String label = text + "   ▾";
        int width = Ef.textWidth(label) + 10;
        int x = controlRight() - width;
        boolean open = listPanel == ref;
        Ef.cutFill(ctx, x, y + 3, width, 12, open ? p.ink() : p.chip(), Ef.CUT);
        Ef.text(ctx, label, x + 5, y + 5, open ? p.paper() : p.ink());
    }

    /**
     * 把宿主的列表编辑器作为浮层开到这一行下方。
     *
     * <p>列表元素带 {@code NBTType}，增删行要按元素类型重建，自绘一份等于重写宿主的编辑器；
     * 所以这里只借它的编辑能力，浮层位置与关闭时机仍由本屏控制——行里那个切角块才是入口，
     * 不会出现「一半自绘一半宿主」的割裂。
     */
    private void openHostEditor(WrapperConfigRef<?> wrapper, int rowY) {
        Object raw = wrapper.ref().getValue();
        if (raw instanceof PrimitiveMap<?, ?> map) {
            openMapEditor(wrapper, map);
            return;
        }
        if (!(raw instanceof PrimitiveList<?> list)) {
            return;
        }
        // 直接开宿主的列表编辑屏：它自带增删行与逐元素输入，
        // 而 generateValueWidget 产出的是「打开列表编辑」的按钮，塞进浮层只会得到一个巨大的按钮。
        //
        // 宿主的编辑屏要的是 AttrKeyValue<List<W>>，而这一项是 AttrKeyValue<PrimitiveList<W>>：
        // 必须包一层转换，否则它会把 List 直接写回 PrimitiveList 字段，运行时抛类型错。
        try {
            AttrKeyValue<PrimitiveList<Object>> source =
                    (AttrKeyValue<PrimitiveList<Object>>) (AttrKeyValue<?>) handle(wrapper);
            NBTType<Object> elementType = (NBTType<Object>) list.elementType();
            WrapperFactory<List<Object>, PrimitiveList<Object>> wrapperFactory = WrapperFactory.of(
                    next -> new PrimitiveList<>(elementType, next, list.defaultPrimitive()),
                    PrimitiveList::list);
            AttrKeyValue<List<Object>> converted = new WrapperAttrKeyValue<>(source, wrapperFactory);
            NBTTypes.openListModifyScreen(
                    NewClickGui.elementFactory(),
                    converted,
                    (NBTType<Object>) list.elementType(),
                    list::createNewElement,
                    300,
                    20);
        } catch (Throwable e) {
            // 这个类型没有可用的编辑器：保持只读，但要把原因打出来，否则「点了没反应」无从查起
            me.matl114.utils.Debug.info("[SFH+] 列表编辑器打开失败: " + e);
        }
    }

    /**
     * 位置配置：行尾一个 type 切换块，下面两行滑杆。
     *
     * <p>{@code type} 决定哪两个字段生效（0 = 百分比、1 = 绝对长度），
     * 所以只画当前生效的那一对——四个字段全摆出来会让人以为都能调。
     */
    private void paintWidgetPos(
            VDrawContext ctx, Ef.Palette p, int y, WrapperConfigRef<?> ref, WidgetPos pos,
            int mouseX, int mouseY) {
        int right = controlRight();
        boolean percent = pos.getType() == 0;
        String mode = percent ? "百分比" : "绝对";
        int modeW = Ef.textWidth(mode) + 10;
        int modeX = right - modeW;
        boolean hot = inBox(modeX, y + 3, modeW, 12, mouseX, mouseY);
        Ef.cutFill(ctx, modeX, y + 3, modeW, 12, hot ? p.ink() : p.chip(), Ef.CUT);
        Ef.text(ctx, mode, modeX + 5, y + 5, hot ? p.paper() : p.ink());

        Vec2 pair = percent ? pos.toPercentage() : pos.toLength();
        String[] names = percent ? new String[] {"X 比例", "Y 比例"} : new String[] {"X 长度", "Y 长度"};
        double[] values = {pair.x(), pair.y()};
        // 滑杆与数值要让开行尾那个 type 块：两者都右对齐到 controlRight() 会叠在一起
        int sliderRight = right - modeW - 6;
        for (int i = 0; i < 2; i++) {
            int rowY = y + i * ROW_H;
            double[] range = bounds(ref, values[i], i);
            paintSlider(ctx, p, rowY, sliderRight, formatNumber(values[i]), values[i], range[0], range[1],
                    names[i], numberTarget == ref && numberPart == i);
        }
    }

    /** 位置行的一击：行尾切 type，两行滑杆各管一格。 */
    private boolean hitWidgetPos(
            WrapperConfigRef<?> wrapper, WidgetPos pos, int mouseX, int mouseY, int button, int rowY) {
        if (button != 0) {
            return false;
        }
        int right = controlRight();
        String mode = pos.getType() == 0 ? "百分比" : "绝对";
        int modeW = Ef.textWidth(mode) + 10;
        if (inBox(right - modeW, rowY + 3, modeW, 12, mouseX, mouseY)) {
            setValue(wrapper.ref(), pos.withType((pos.getType() + 1) % 2));
            return true;
        }
        int index = Math.max(0, Math.min(1, (mouseY - rowY) / ROW_H));
        Vec2 pair = pos.getType() == 0 ? pos.toPercentage() : pos.toLength();
        double current = index == 0 ? pair.x() : pair.y();
        return hitNumber(wrapper, index, right - modeW - 6, mouseX, button, rowY, formatNumber(current), current);
    }

    /**
     * 键值表：与列表同一套入口，只是宿主的编辑器换成键值对那一版。
     *
     * <p>同样要包一层 {@code WrapperAttrKeyValue}：宿主编辑屏写回的是 {@code Map}，
     * 而这一项存的是 {@code PrimitiveMap}，不转换会在 accept 时抛类型错。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void openMapEditor(WrapperConfigRef<?> wrapper, PrimitiveMap<?, ?> map) {
        try {
            // 键值表的编辑器由 NBTType 自己生成：手写 createArrayMapLike 的泛型参数既啰嗦又容易错，
            // 这里直接借它产出的控件，再塞进一个独立屏——与列表那条路同一个思路
            AttrKeyValue<Object> source = (AttrKeyValue<Object>) (AttrKeyValue<?>) handle(wrapper);
            DrawableWidget editor = source.generateValueWidget(
                    NewClickGui.elementFactory(), 0, 0, 300, 20);
            if (editor == null) {
                return;
            }
            ScreenAccess.of(new HostEditorScreen(editor, 300, 20)).openFromCurrent();
        } catch (Throwable e) {
            me.matl114.utils.Debug.info("[SFH+] 键值表编辑器打开失败: " + e);
        }
    }

    /** 带标签向量的一击：每格一个数值槽，点开就地编辑。 */
    private boolean hitLabelVec(
            WrapperConfigRef<?> wrapper, int count, double x, double y2, double z,
            int mouseX, int mouseY, int button, int rowY) {
        if (button != 0) {
            return false;
        }
        int right = controlRight();
        double[] values = {x, y2, z};
        int index = Math.max(0, Math.min(count - 1, (mouseY - rowY) / ROW_H));
        String text = formatNumber(values[index]);
        int width = Math.max(TEXT_W, Ef.textWidth(text) + 12);
        int slotX = right - width;
        int slotY = rowY + index * ROW_H + FIELD_TOP;
        if (!inBox(slotX, slotY, width, FIELD_H, mouseX, mouseY)) {
            return false;
        }
        numberTarget = wrapper;
        numberPart = index;
        numberRowY = rowY;
        return true;
    }

    /** GUI 设置行的一击：只有入口块接，点开宿主那一整套编辑屏。 */
    private boolean hitGuiSetting(WrapperConfigRef<?> wrapper, int mouseX, int mouseY, int button, int rowY) {
        Box chip = guiSettingChip(rowY);
        if (button != 0 || !inBox(chip.x(), chip.y(), chip.w(), chip.h(), mouseX, mouseY)) {
            return false;
        }
        openGuiSettingEditor(wrapper);
        return true;
    }

    /**
     * 打开 GUI 设置的详细页：本界面自己的屏，不是宿主那一套。
     *
     * <p>那一份设置由十来个分量组成，摊成一行一项正好是本界面已有的控件形态；
     * 宿主编辑屏的外观与这里对不上，所以只借它的数据结构，不借它的界面。
     */
    @SuppressWarnings("unchecked")
    private void openGuiSettingEditor(WrapperConfigRef<?> wrapper) {
        try {
            WrapperConfigRef<GuiSetting> target = (WrapperConfigRef<GuiSetting>) wrapper;
            ScreenAccess.of(new GuiSettingPage(target, settings)).openFromCurrent();
        } catch (Throwable e) {
            me.matl114.utils.Debug.info("[SFH+] GUI 设置页打开失败: " + e);
        }
    }

    /** 键值表行的一击：与列表同一块命中区。 */
    private boolean hitPrimitiveMap(
            WrapperConfigRef<?> wrapper, PrimitiveMap<?, ?> map, int mouseX, int mouseY, int button, int rowY) {
        String label = map.toPrimitiveMap().size() + " 项   ▾";
        int width = Ef.textWidth(label) + 10;
        int x = controlRight() - width;
        if (button != 0 || !inBox(x, rowY + 3, width, 12, mouseX, mouseY)) {
            return false;
        }
        openHostEditor(wrapper, rowY);
        return true;
    }

    /**
     * 物品模板：值框显示物品 id，行尾一个按钮打开宿主的物品编辑屏。
     *
     * <p>模板里除了 id 还有整套数据组件（附魔、名字、数量…），自绘一份等于重写物品编辑器；
     * 所以值框只管 id，要改组件就点按钮——与列表、键值表同一个思路。
     */
    private void paintItemTemplate(
            VDrawContext ctx, Ef.Palette p, int y, WrapperConfigRef<?> ref, ItemStackTemplate template,
            int mouseX, int mouseY) {
        int right = controlRight();
        int btn = 13;
        int gap = 2;
        int btnX = right - btn;
        // 与 hitText 取同一份几何：值框右对齐到按钮左边，命中才不会错位
        Box base = textValueBox(y);
        int fieldW = Math.max(120, Math.min(base.w(), btnX - gap - contentLeft));
        Box field = new Box(btnX - gap - fieldW, y + FIELD_TOP, fieldW, FIELD_H);
        boolean editing = textTarget == ref;
        if (editing) {
            paintFieldFrame(ctx, p, field.x(), field.y(), field.w(), field.h(), true, false);
            if (textBox != null) {
                textBox.setTextColor(p.ink());
            }
        } else {
            boolean hot = inBox(field.x(), field.y(), field.w(), field.h(), mouseX, mouseY);
            if (hot) {
                paintFieldFrame(ctx, p, field.x(), field.y(), field.w(), field.h(), false, true);
            } else {
                int lineY = field.y() + field.h() - 2;
                for (int i = 0; i < field.w(); i += 3) {
                    Ef.fill(ctx, field.x() + i, lineY, Math.min(2, field.w() - i), 1, p.rule());
                }
            }
            String shown = Ef.ellipsis(template.itemId().toString(), field.w() - FIELD_PAD * 2);
            Ef.text(ctx, shown, field.x() + field.w() - FIELD_PAD - Ef.textWidth(shown),
                    field.y() + FIELD_PAD, p.ink());
        }
        boolean hot = inBox(btnX, y + 2, btn, btn, mouseX, mouseY);
        if (hot) {
            Ef.cutFill(ctx, btnX, y + 2, btn, btn, p.chip(), Ef.CUT);
        }
        Ef.icon(ctx, Constants.EDITOR_SPRITE, btnX, y + 2, btn, hot ? p.ink() : p.ink2());
        if (hot) {
            itemTemplateHover = true;
        }
    }

    /** 物品模板行的一击：值框改 id，行尾按钮开物品编辑屏。 */
    private boolean hitItemTemplate(
            WrapperConfigRef<?> wrapper, ItemStackTemplate template, int mouseX, int mouseY, int button, int rowY) {
        if (button != 0) {
            return false;
        }
        int btnX = controlRight() - 13;
        if (inBox(btnX, rowY + 2, 13, 13, mouseX, mouseY)) {
            // itemStack() 解不出来时返回 EMPTY 而不是 null：按 isEmpty 判，否则会开一个空槽编辑屏
            ItemStack current = template.itemStack();
            if (current == null || current.isEmpty()) {
                return true;
            }
            InvTasks.openEditScreen(current, edited -> {
                template.setItemStack(edited);
                setValue(wrapper.ref(), template);
            });
            return true;
        }
        // 值框的命中要按 paintItemTemplate 那份几何算：它让开了行尾按钮，与 textValueBox 不同
        Box base = textValueBox(rowY);
        int fieldW = Math.max(120, Math.min(base.w(), btnX - 2 - contentLeft));
        Box field = new Box(btnX - 2 - fieldW, rowY + FIELD_TOP, fieldW, FIELD_H);
        if (!inBox(field.x(), field.y(), field.w(), field.h(), mouseX, mouseY)) {
            return false;
        }
        return beginTextEdit(wrapper, rowY);
    }

    /** 这一击是不是落在计数块上：计数块与行尾重置图标会重叠，判定要按同一份几何。 */
    private boolean inCountChip(Ref<?> ref, int mouseX, int mouseY, int rowY) {
        // 判 ref 不判 value：ListRef 是 Ref 类型，getValue() 拿到的是 ArrayList
        Object value = ref.getValue();
        String label;
        if (ref instanceof ListRef listRef) {
            label = listRef.get().size() + " 项   ▾";
        } else if (value instanceof RegexList regexList) {
            label = regexList.patterns().size() + " 项   ▾";
        } else if (value instanceof PrimitiveList<?> list) {
            label = list.list().size() + " 项   ▾";
        } else if (value instanceof PrimitiveMap<?, ?> map) {
            label = map.toPrimitiveMap().size() + " 项   ▾";
        } else {
            return false;
        }
        int width = Ef.textWidth(label) + 10;
        int x = controlRight() - width;
        return inBox(x, rowY + 3, width, 12, mouseX, mouseY);
    }

    /** 计数块的一击：点它打开这一项的列表编辑器。 */
    private boolean hitCountChip(
            WrapperConfigRef<?> wrapper, int count, int mouseX, int mouseY, int button, int rowY) {
        String label = count + " 项   ▾";
        int width = Ef.textWidth(label) + 10;
        int x = controlRight() - width;
        if (button != 0 || !inBox(x, rowY + 3, width, 12, mouseX, mouseY)) {
            return false;
        }
        Object raw = wrapper.ref().getValue();
        if (raw instanceof EntityTypeEntrySet set) {
            openEntityTypeSet(wrapper, set);
            return true;
        }
        if (raw instanceof BoundedPrimitiveFlagMap<?> flags) {
            openBoundedFlagMap(wrapper, flags);
            return true;
        }
        openListEditor(wrapper);
        return true;
    }

    /**
     * 打开实体类型集合的编辑屏。
     *
     * <p>这一项不是 {@code EntrySet}：它由「生成组 + 追加 + 排除」三段拼出来，
     * 宿主的注册表选择屏只认一个扁平集合，套上去会把三段结构压平。
     * 所以借它自己的编辑屏，写回时整份替换。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void openEntityTypeSet(WrapperConfigRef<?> wrapper, EntityTypeEntrySet set) {
        try {
            ScreenAccess.of(new me.matl114.gui.presets.choices.EntityTypeEntrySetScreen(
                            set, next -> setValue(wrapper.ref(), next), NewClickGui.elementFactory()))
                    .openFromCurrent();
        } catch (Throwable e) {
            me.matl114.utils.Debug.info("[SFH+] 实体集合编辑屏打开失败: " + e);
        }
    }

    /**
     * 打开有界开关表（HUD 元素、皮肤部件…）的编辑屏。
     *
     * <p>键是枚举、值是布尔，宿主按「键清单 + 值类型」生成一张表；
     * 这里直接借它产出的控件，塞进一个独立屏——与键值表那条路同一个思路。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void openBoundedFlagMap(WrapperConfigRef<?> wrapper, BoundedPrimitiveFlagMap<?> flags) {
        try {
            AttrKeyValue<Object> source = (AttrKeyValue<Object>) (AttrKeyValue<?>) handle(wrapper);
            DrawableWidget widget = source.generateValueWidget(NewClickGui.elementFactory(), 0, 0, 300, 20);
            if (widget != null) {
                ScreenAccess.of(new HostEditorScreen(widget, 300, 20)).openFromCurrent();
            }
        } catch (Throwable e) {
            me.matl114.utils.Debug.info("[SFH+] 有界开关表编辑屏打开失败: " + e);
        }
    }

    /**
     * 字符串列表与正则列表的编辑器。
     *
     * <p>两者都是「一串可增删的条目」，宿主的 {@code openListModifyScreen} 正好是这个形状；
     * 元素类型不同（String / Regex），所以按值类型分派。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void openListEditor(WrapperConfigRef<?> wrapper) {
        // 判的是 ref 不是 value：ListRef 是 Ref 类型，getValue() 拿到的是 ArrayList
        Ref<?> ref = wrapper.ref();
        Object raw = ref.getValue();
        try {
            AttrKeyValue<Object> source = (AttrKeyValue<Object>) (AttrKeyValue<?>) handle(wrapper);
            if (ref instanceof ListRef listRef) {
                // ListRef 的 keyValue 是快照（AttrKeyValue.list 拷了一份），直接交给宿主编辑屏
                // 写回只会改那份拷贝。包一层把 accept 转回 ListRef.setValue，配置才真的变。
                WrapperFactory<List<String>, List<String>> factory = WrapperFactory.of(
                        next -> {
                            listRef.setValue(new java.util.ArrayList<>(next));
                            return new java.util.ArrayList<>(next);
                        },
                        java.util.ArrayList::new);
                AttrKeyValue<List<String>> live = new WrapperAttrKeyValue<>(
                        (AttrKeyValue<List<String>>) (AttrKeyValue<?>) source, factory);
                NBTTypes.openListModifyScreen(
                        NewClickGui.elementFactory(),
                        live,
                        NBTTypes.STRING_TYPE,
                        () -> "",
                        300,
                        20);
                return;
            }
            if (raw instanceof RegexList regexList) {
                // RegexList 是 record(List<Pattern>)，而宿主的 REGEX_TYPE 就是 NBTType<Pattern>：
                // 直接按 Pattern 列表编辑，不用再包一层 Regex
                WrapperFactory<List<Pattern>, RegexList> factory =
                        WrapperFactory.of(RegexList::new, RegexList::patterns);
                AttrKeyValue<RegexList> typed = (AttrKeyValue<RegexList>) (AttrKeyValue<?>) source;
                AttrKeyValue<List<Pattern>> converted = new WrapperAttrKeyValue<>(typed, factory);
                NBTTypes.openListModifyScreen(
                        NewClickGui.elementFactory(),
                        converted,
                        NBTTypes.REGEX_TYPE,
                        () -> Pattern.compile(""),
                        300,
                        20);
            }
        } catch (Throwable e) {
            me.matl114.utils.Debug.info("[SFH+] 列表编辑器打开失败: " + e);
        }
    }

    /** 列表行的一击：只有计数块接，点它把宿主的列表编辑器开到这一页上。 */
    private boolean hitPrimitiveList(
            WrapperConfigRef<?> wrapper, PrimitiveList<?> list, int mouseX, int mouseY, int button, int rowY) {
        String label = list.list().size() + " 项   ▾";
        int width = Ef.textWidth(label) + 10;
        int x = controlRight() - width;
        if (button != 0 || !inBox(x, rowY + 3, width, 12, mouseX, mouseY)) {
            return false;
        }
        openHostEditor(wrapper, rowY);
        return true;
    }

    private static String entrySetChipText(EntrySet<?> set) {
        return set.set().size() + " 项   ▾";
    }

    /** 计数块的矩形：绘制与命中共用这一份，块宽跟着「N 项」的位数走，两边不会算岔。 */
    private Box entrySetChip(EntrySet<?> set, int rowY) {
        String text = entrySetChipText(set);
        int width = Ef.textWidth(text) + 10;
        return new Box(controlRight() - width, rowY + 3, width, 12);
    }

    /**
     * 名单行的一击：只有计数块接，点它把名单面板开到这一页上。
     *
     * <p>面板开着时这一下会先被「面板外只关面板」吃掉，所以这里只负责打开——
     * 再点一次同一块就是收起。打开时收起别的浮层：同一时刻只留一个。
     */
    private boolean hitEntrySet(
            WrapperConfigRef<?> wrapper, EntrySet<?> set, int mouseX, int mouseY, int button, int rowY) {
        Box chip = entrySetChip(set, rowY);
        if (button != 0 || !inBox(chip.x(), chip.y(), chip.w(), chip.h(), mouseX, mouseY)) {
            return false;
        }
        listPanel = wrapper;
        listFilter = "";
        listScroll = 0;
        palette = null;
        return true;
    }

    /**
     * 名单面板的矩形与它里面三段（标题、搜索框、候选列表）的位置。
     *
     * <p>右边缘对到 {@link #controlRight()}，与计数块同一条线：项数从 9 变 10 时块会变宽，
     * 面板要是贴着块走就会跟着挪。上下贴在目标行外侧，放不下翻到行上方，最后整体夹进窗口。
     */
    private record ListBox(
            int x, int y, int w, int h, int searchX, int searchY, int searchW, int listX, int listY, int listW,
            int listH) {}

    private ListBox listBox() {
        int rowY = -1;
        if (listPanel != null) {
            for (Row row : rows) {
                if (!row.header && row.ref == listPanel) {
                    rowY = row.y;
                    break;
                }
            }
        }
        if (rowY < 0) {
            return null;
        }
        int w = Math.max(120, Math.min(LIST_W, winW - 8));
        int h = Math.max(80, Math.min(LIST_H, winH - 8));
        int x = Math.max(winX + 4, Math.min(winX + winW - 4 - w, controlRight() - w));
        int y = rowY + ROW_H + 2;
        if (y + h > winY + winH - 4) {
            y = rowY - h - 2;
        }
        y = Math.max(winY + 4, Math.min(winY + winH - 4 - h, y));
        int innerW = w - LIST_PAD * 2;
        int searchX = x + LIST_PAD;
        int searchY = y + LIST_PAD + LIST_HEAD;
        int listY = searchY + LIST_SEARCH_H + 5;
        return new ListBox(x, y, w, h, searchX, searchY, innerW, x + LIST_PAD, listY, innerW,
                y + h - LIST_PAD - listY);
    }

    /** 搜索框前缀 {@code //} 占的宽度：输入框、占位文字与命中都按这一格让位。 */
    private static int searchPrefixW() {
        return Ef.trackedWidth("//", 1) + 4;
    }

    /**
     * 面板里的搜索框：一只原版 EditBox，只在 tick 里建、拆。
     *
     * <p>与数值、颜色两只同一套规矩：程序写值前先摘 responder，responder 认的是建它的那只框，
     * 换框之后旧框再回调也写不到新面板里。
     */
    private void startListSearch(ListBox box) {
        EditBox editor = HostControls.editBox(
                box.searchX() + 4 + searchPrefixW(),
                box.searchY() + FIELD_PAD,
                Math.max(24, box.searchW() - 8 - searchPrefixW()),
                64,
                null);
        this.listSearchBox = editor;
        applyListText(editor);
        this.addRenderableWidget(editor);
        this.setFocused(editor);
    }

    private void applyListText(EditBox editor) {
        editor.setResponder(null);
        try {
            if (!listFilter.equals(editor.getValue())) {
                editor.setValue(listFilter);
            }
        } finally {
            editor.setResponder(text -> {
                if (editor == this.listSearchBox) {
                    this.listFilter = text;
                    // 换了过滤词就回到列表顶部，否则会停在越界的位置上
                    this.listScroll = 0;
                }
            });
        }
    }

    /**
     * 名单面板：标题、搜索框、候选行与滚动条，整块画在面板自己这一层。
     *
     * <p>它和调色盘一样是浮层，所以画在帧与页脚之后；候选行由这里直接画，
     * 不交给宿主控件——宿主的列表编辑会另开一屏，而勾选要能立刻看见行上的计数变化。
     */
    private void paintListPanel(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        if (listPanel == null || !(listPanel.ref().getValue() instanceof EntrySet<?> set)) {
            listPanel = null;
            return;
        }
        ListBox box = listBox();
        if (box == null) {
            // 目标行不在这一屏上了（换页、筛选）：面板没有可列的集合
            listPanel = null;
            return;
        }
        int cut = Ef.CUT_PANEL;
        Ef.cutFill(ctx, box.x(), box.y(), box.w(), box.h(), p.panel(), cut);
        // 斜边逐格 1px 画：12 档的描边贴图是 16→12 拉过来的，会摊成 2px 的糊带
        Ef.fill(ctx, box.x(), box.y(), box.w(), 1, p.ink());
        Ef.fill(ctx, box.x(), box.y(), 1, box.h(), p.ink());
        Ef.fill(ctx, box.x() + box.w() - 1, box.y(), 1, box.h() - cut, p.ink());
        Ef.fill(ctx, box.x(), box.y() + box.h() - 1, box.w() - cut, 1, p.ink());
        for (int i = 0; i < cut; i++) {
            Ef.fill(ctx, box.x() + box.w() - cut + i, box.y() + box.h() - 1 - i, 1, 1, p.ink());
        }

        int headY = box.y() + LIST_PAD - 3;
        Ef.trackedWide(ctx, "// ENTITIES", box.x() + LIST_PAD, headY, p.ink2(), 1, 1.2F);
        String stats = "已选 " + set.set().size() + " / 共 " + sortedIds(set).size();
        Ef.text(ctx, stats, box.x() + box.w() - LIST_PAD - Ef.textWidth(stats), headY, p.ink());
        Ef.rule(ctx, box.x() + LIST_PAD, box.searchY() - 4, box.w() - LIST_PAD * 2, Ef.alpha(p.rule(), 0.55F));

        paintFieldFrame(ctx, p, box.searchX(), box.searchY(), box.searchW(), LIST_SEARCH_H,
                listSearchBox != null && listSearchBox.isFocused(),
                inBox(box.searchX(), box.searchY(), box.searchW(), LIST_SEARCH_H, mouseX, mouseY));
        // 搜索变体的 // 前缀：输入框与占位文字都从它后面起，字不会压到前缀上
        Ef.tracked(ctx, "//", box.searchX() + 4, box.searchY() + FIELD_PAD, p.ink2(), 1);
        if (listSearchBox != null) {
            // 输入框自己画字：不改颜色的话深色主题还行，浅色面板上几乎看不见
            listSearchBox.setTextColor(p.ink());
        }
        if (listFilter.isEmpty()) {
            // 输入框只在有字时自己画字，空的时候要这里补上提示
            Ef.text(ctx, "搜索生物", box.searchX() + 4 + searchPrefixW(), box.searchY() + FIELD_PAD, p.ink2());
        }

        List<Identifier> shown = listShown(set);
        int visible = Math.max(1, box.listH() / LIST_ROW);
        int first = listScroll / LIST_ROW;
        for (int i = 0; i < visible && first + i < shown.size(); i++) {
            int rowY = box.listY() + i * LIST_ROW;
            Identifier id = shown.get(first + i);
            Object entry = set.registry().getValue(id);
            if (inBox(box.listX(), rowY, box.listW(), LIST_ROW, mouseX, mouseY)) {
                Ef.fill(ctx, box.listX(), rowY, box.listW(), LIST_ROW, Ef.mix(p.panel(), p.ink(), 0.06F));
            }
            if (set.set().contains(entry)) {
                Ef.fill(ctx, box.listX(), rowY, 3, LIST_ROW, p.trace());
            }
            String idText = Ef.ellipsis(id.toString(), Math.max(40, box.listW() / 2));
            int idWidth = Ef.textWidth(idText);
            int nameWidth = Math.max(16, box.listW() - 9 - idWidth - 8);
            Ef.text(ctx, Ef.ellipsis(entryName(entry, id), nameWidth), box.listX() + 7, rowY + 3, p.ink());
            Ef.text(ctx, idText, box.listX() + box.listW() - idWidth, rowY + 3, p.ink2());
        }
        if (shown.isEmpty()) {
            Ef.text(ctx, "没有匹配项", box.listX(), box.listY() + 3, p.ink2());
        }
        Ef.scrollbar(ctx, box.x() + box.w() - 4, box.listY(), box.listH(), listScroll,
                shown.size() * LIST_ROW, box.listH(), p.rule(), p.ink2());
        if (inBox(box.x(), box.y(), box.w(), box.h(), mouseX, mouseY)) {
            // 鼠标此刻在面板上，指的不是底下那一行：不要顺带出它的说明
            hovered = null;
            hoveredAction = null;
        }
    }

    /** 面板里点在第几行：滚动量是整行挪的，行下标直接由行号推出来。 */
    private int listRowAt(ListBox box, int mouseX, int mouseY) {
        if (mouseX < box.listX() || mouseX >= box.listX() + box.listW()
                || mouseY < box.listY() || mouseY >= box.listY() + box.listH()) {
            return -1;
        }
        return listScroll / LIST_ROW + (mouseY - box.listY()) / LIST_ROW;
    }

    /**
     * 面板里的一击：按当时的过滤结果取第 {@code index} 项，切换选中状态并立刻写回。
     *
     * <p>写回的是复制出来的一份集合：宿主那份 {@code EntrySet} 可能正被别处读着，
     * 原地改会改到别人手里的那一份，{@code idList()} 里缓存的 id 也不会跟着更新。
     */
    private void toggleListEntry(int index) {
        if (listPanel == null || !(listPanel.ref().getValue() instanceof EntrySet<?> set)) {
            return;
        }
        List<Identifier> shown = listShown(set);
        if (index < 0 || index >= shown.size()) {
            return;
        }
        Object entry = set.registry().getValue(shown.get(index));
        if (entry == null) {
            return;
        }
        Set<Object> next = new LinkedHashSet<>(set.set());
        if (!next.remove(entry)) {
            next.add(entry);
        }
        writeEntrySet(listPanel, set.registry(), next);
    }

    /** 把选好的那份集合写成新的 {@code EntrySet}：构造器自己会包一层 LinkedHashSet，勾选顺序留着。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void writeEntrySet(WrapperConfigRef<?> target, Registry<?> registry, Set<Object> selection) {
        setValue(target.ref(), new EntrySet(registry, selection));
    }

    /**
     * 候选 id：按 {@code 命名空间:路径} 排好序，注册表自己的遍历顺序不适合直接铺出来。
     *
     * <p>返回的是缓存本身，调用方只读。
     */
    private List<Identifier> sortedIds(EntrySet<?> set) {
        Registry<?> registry = set.registry();
        int size = registry.keySet().size();
        IdIndex cached = idIndex.get(registry);
        if (cached != null && cached.size() == size) {
            return cached.ids();
        }
        List<Identifier> ids = new ArrayList<>(registry.keySet());
        ids.sort(Comparator.comparing(Identifier::toString));
        idIndex.put(registry, new IdIndex(size, ids));
        return ids;
    }

    /**
     * 这一屏要列哪些候选：id 与显示名各筛一遍；显示名交给宿主的 {@link FilterService#nameMatch}，
     * 于是中文名可以用全拼与首字母搜（「lieyanren」/「lyr」都能命中「烈焰人」），和宿主自己的列表一致。
     *
     * <p>结果按「注册表 + 搜索词」缓存：拼音转换不便宜，每帧对几百个候选重算会拖慢界面，
     * 只有搜索词变了才重算一次。
     */
    private List<Identifier> listShown(EntrySet<?> set) {
        List<Identifier> ids = sortedIds(set);
        if (listFilter.isEmpty()) {
            return ids;
        }
        Registry<?> registry = set.registry();
        if (shownCache != null && shownRegistry == registry && shownFilter.equals(listFilter)) {
            return shownCache;
        }
        String needle = listFilter.toLowerCase(Locale.ROOT);
        // 拼音是连写的：允许在查询里随手加空格/下划线（「lie yan ren」「lie_yan_ren」），
        // 比对前先把这两种分隔符去掉；id 那一侧仍按原样匹配，下划线在 id 里是有意义的
        String compact = needle.replace(" ", "").replace("_", "");
        String pinyinNeedle = compact.isEmpty() ? needle : compact;
        List<Identifier> out = new ArrayList<>();
        for (Identifier id : ids) {
            String name = entryName(registry.getValue(id), id);
            if (id.toString().toLowerCase(Locale.ROOT).contains(needle)
                    || HostControls.nameMatch(name, pinyinNeedle)) {
                out.add(id);
            }
        }
        shownRegistry = registry;
        shownFilter = listFilter;
        shownCache = out;
        return out;
    }

    /** 候选的显示名：实体用内置名（「僵尸」），其它注册表退回 id 的路径段。 */
    private static String entryName(Object entry, Identifier id) {
        // 注册表里的条目大多自带显示名，只有拿不到时才退回 id 的路径段：
        // 退回 id 的那一列在中文界面里全是 acacia_boat 这种，读不出是什么
        if (entry instanceof EntityType<?> type) {
            return type.getDescription().getString();
        }
        if (entry instanceof Item item) {
            // 26.2 的 Item#getName 读的是 ItemStack 上的 ITEM_NAME 组件：空栈没有这个组件，
            // 直接传 EMPTY 会得到空串（名称列整列空白）。组件未绑定时也只能走翻译键。
            return ItemStackUtils.itemComponentsBound()
                    ? item.getName(new ItemStack(item)).getString()
                    : Component.translatable(item.getDescriptionId()).getString();
        }
        if (entry instanceof Block block) {
            return block.getName().getString();
        }
        if (entry instanceof MobEffect effect) {
            return effect.getDisplayName().getString();
        }
        if (entry instanceof Potion potion) {
            return Component.translatable(Items.POTION.getDescriptionId() + ".effect." + potion.name())
                    .getString();
        }
        return id.getPath();
    }

    private int listMaxScroll(ListBox box) {
        if (listPanel == null || !(listPanel.ref().getValue() instanceof EntrySet<?> set)) {
            return 0;
        }
        int max = listShown(set).size() * LIST_ROW - box.listH();
        // 上取整到整行：滚动量始终是行的整数倍，最后一屏才不会露出半行
        return max <= 0 ? 0 : ((max + LIST_ROW - 1) / LIST_ROW) * LIST_ROW;
    }

    private static boolean inBox(int x, int y, int w, int h, int mouseX, int mouseY) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
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
        // 浮层（下拉、名单面板）盖在正文之上：鼠标落在它们身上时，底下的行既不该高亮，
        // 也不该把自己的说明框顶出来 —— 那会让人以为浮层里那一条是这一行。
        boolean covered = dropdown != null || listPanel != null;
        boolean hover = !covered
                && mouseY >= row.y
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
        if (formatArgumentHover != null) {
            // 参数提示优先：它盖住的那一行本来也不该出别的说明
            List<String> lines = new ArrayList<>();
            for (Component tip : StringFormat.generateTooltipsForArgument(formatArgumentHover.formattingArgument())) {
                lines.addAll(Ef.wrap(tip.getString(), 240));
            }
            formatArgumentHover = null;
            Ef.tooltip(ctx, p, lines, mouseX, mouseY, this.width, this.height);
            return;
        }
        if (itemTemplateHover) {
            itemTemplateHover = false;
            Ef.tooltip(ctx, p, List.of("打开物品编辑屏：附魔、名字、数量等组件在那里改"), mouseX, mouseY,
                    this.width, this.height);
            return;
        }
        if (editorHover != null) {
            // 与宿主同一份文案：说明 + 当前内容的实时预览。预览带颜色代码，必须按 Component 画
            List<Component> lines = editorHover.generateColorStringPreview();
            editorHover = null;
            Ef.tooltipStyled(ctx, p, lines, mouseX, mouseY, this.width, this.height);
            return;
        }
        // 被截断的文本优先：它回答的是「这一条到底是什么」，比配置说明更急
        if (truncatedHover != null) {
            Ef.tooltip(ctx, p, Ef.wrap(truncatedHover, 230), mouseX, mouseY, this.width, this.height);
            return;
        }
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
            // 输入框自己画字，这里只给个框：框比字多出 FIELD_PAD，聚焦环压在边框内侧
            paintFieldFrame(ctx, p, right - TEXT_W, y + FIELD_TOP, TEXT_W, FIELD_H, true, false);
            if (numberBox != null) {
                numberBox.setTextColor(p.ink());
            }
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
        // 悬停时行底会提亮一档，次要色压在上面会显得更暗；跟着换成主色，对比度才不掉
        int ink = hovered != null ? p.ink() : p.ink2();
        Ef.tracked(ctx, shown, rightEdge - Ef.trackedWidth(shown, 1), y + 5, ink, 1);
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

    /** 开关：开是实心块加一个纸色缺口，关是虚线框加一个小方块，与行尾的可选值开关同一套画法。 */
    private void paintSwitch(VDrawContext ctx, Ef.Palette p, int y, int right, boolean on) {
        int sw = 20;
        int sh = 9;
        int sy = y + (ROW_H - sh) / 2;
        if (on) {
            Ef.fill(ctx, right - sw, sy, sw, sh, p.ink());
            Ef.fill(ctx, right - 9, sy + 2, 5, 5, p.paper());
        } else {
            Ef.dashedOutline(ctx, right - sw, sy, sw, sh, p.ink2());
            Ef.fill(ctx, right - sw + 2, sy + 2, 5, 5, p.ink2());
        }
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
        int rowH = DROP_ROW_H;
        // 候选多时限制高度并滚动：模型文件动辄几十上百个，全画出来会顶出屏幕
        int visible = Math.min(options.length, DROP_MAX_ROWS);
        int height = visible * rowH + 4;
        int top = dropTop(height);
        dropScroll = Math.max(0, Math.min(options.length - visible, dropScroll));
        Ef.fill(ctx, dropX, top, dropW, height, p.panel());
        Ef.cutOutline(ctx, dropX, top, dropW, height, p.ink(), Ef.CUT);

        Object current = currentOf(dropdown, dropPart);
        for (int i = 0; i < visible; i++) {
            int index = dropScroll + i;
            int oy = top + 2 + i * rowH;
            boolean hover = mouseX >= dropX && mouseX <= dropX + dropW && mouseY >= oy && mouseY < oy + rowH;
            boolean active = options[index] == current;
            if (hover) {
                Ef.fill(ctx, dropX + 1, oy, dropW - 2, rowH, Ef.mix(p.panel(), p.ink(), 0.08F));
            }
            if (active) {
                Ef.fill(ctx, dropX + 1, oy, 2, rowH, p.trace());
            }
            String label = enumLabel(options[index]);
            String shown = Ef.ellipsis(label, dropW - 14);
            // 被裁短的那一条在悬停时补出全名：模型文件名动辄三四十个字符，省略号后面才是区分点
            if (hover && !shown.equals(label)) {
                truncatedHover = label;
            }
            Ef.text(ctx, shown, dropX + 8, oy + 3, active ? p.ink() : p.ink2());
        }
        // 滚动条：只在真的滚得动时画，免得短列表也挂一条
        if (options.length > visible) {
            int trackX = dropX + dropW - 3;
            int trackH = height - 4;
            Ef.fill(ctx, trackX, top + 2, 2, trackH, Ef.mix(p.panel(), p.ink(), 0.12F));
            int thumbH = Math.max(8, trackH * visible / options.length);
            int thumbY = top + 2 + (trackH - thumbH) * dropScroll / Math.max(1, options.length - visible);
            Ef.fill(ctx, trackX, thumbY, 2, thumbH, p.ink2());
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
        // 文本输入框盖在值区上：落在它框里的这一下是去挪光标的，不提交、也不收起编辑
        // （画布不接，容器会把点击继续派给输入框自己；重建输入框则会把正在打的内容换回旧值）
        if (textTarget != null && textBox != null && textBox.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        // 点到别处就是提交：文本只在提交时写回配置，不提交等于把打好的字丢掉
        closeTextEdit();
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
            int rowH = DROP_ROW_H;
            int visible = Math.min(options.length, DROP_MAX_ROWS);
            int top = dropTop(visible * rowH + 4);
            if (mx >= dropX && mx <= dropX + dropW && my >= top && my <= top + visible * rowH + 4) {
                // 命中要加上滚动偏移：画出来的是 dropScroll 之后的那一段
                int index = dropScroll + (my - top - 2) / rowH;
                if (index >= 0 && index < options.length) {
                    applyOption(dropdown, dropPart, options[index]);
                }
            }
            dropdown = null;
            dropPart = -1;
            dropScroll = 0;
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

        if (listPanel != null) {
            ListBox box = listBox();
            if (box != null && inBox(box.x(), box.y(), box.w(), box.h(), mx, my)) {
                if (listSearchBox != null) {
                    if (listSearchBox.isMouseOver(mouseX, mouseY)) {
                        // 落在输入框里：这一下是去挪光标的，不接、也不收起面板
                        return false;
                    }
                    // 落在边框或 // 前缀上：整格都算这一格（搜索变体），点哪都该把焦点给它
                    if (inBox(box.searchX(), box.searchY(), box.searchW(), LIST_SEARCH_H, mx, my)) {
                        this.setFocused(listSearchBox);
                        return true;
                    }
                }
                if (button == 0) {
                    toggleListEntry(listRowAt(box, mx, my));
                }
                return true;
            }
            // 面板外只关面板，不穿透；命令栏是导航，放行照常切页
            listPanel = null;
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
            // 自定义控件行没有配置项：它自己接点击，这里跳过
            if (row.header || row.ref == null || my < row.y || my >= row.y + row.height) {
                continue;
            }
            // 行只在正文这一条里接点击：右侧留白在窗口内但没有控件，落在那里的点击不该打中左边的开关
            if (mx < contentLeft - 4 || mx > contentLeft + contentWidth + 4) {
                return false;
            }
            // 重置图标在正文最右一格；计数块右对齐到 controlRight()，两者会重叠 2px。
            // 计数块优先：它才是这一行的主控件，落在它上面的点击不该变成重置。
            int resetX = contentLeft + contentWidth - 14;
            if (row.ref().ref().hasDefaultValue()
                    && mx >= resetX
                    && mx <= resetX + 14
                    && my >= row.y
                    && my < row.y + 12
                    && !inCountChip(row.ref().ref(), mx, my, row.y)) {
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
        // 下拉打开时滚轮先滚它：候选多到要滚动时，滚轮是唯一能翻的手段
        if (dropdown != null) {
            Object[] options = optionsFor(dropdown, dropPart);
            int visible = Math.min(options.length, DROP_MAX_ROWS);
            if (options.length > visible) {
                int step = (int) Math.signum(amount);
                dropScroll = Math.max(0, Math.min(options.length - visible, dropScroll - step));
                return true;
            }
        }
        // 滚动等于离开这一格：正在编辑的文本先提交，打的字不该丢
        closeTextEdit();
        if (listPanel != null) {
            ListBox box = listBox();
            int step = (int) (Math.signum(amount) * LIST_ROW);
            if (box != null && inBox(box.x(), box.y(), box.w(), box.h(), (int) mouseX, (int) mouseY)) {
                // 面板上的滚轮只滚列表，正文一行都不动
                listScroll = Math.max(0, Math.min(listMaxScroll(box), listScroll - step));
                return true;
            }
            listPanel = null;
        }
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
            // 开关只吃行尾那一段：右边更远的地方没有控件，别让留白里的点击也算到它头上
            if (mouseX >= right - 24 && mouseX <= right) {
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
            if (mouseX >= right - 24 && mouseX <= right) {
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
        if (ref.getValue() instanceof EntrySet<?> set) {
            return hitEntrySet(wrapper, set, mouseX, mouseY, button, rowY);
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
        boolean[] bools = booleanParts(ref.getValue());
        if (bools != null) {
            // 开关只吃行尾那一段：与单值开关同一块命中区，留白里的点击不算
            if (button != 0 || mouseX < right - 24 || mouseX > right) {
                return false;
            }
            int index = Math.max(0, Math.min(bools.length - 1, (mouseY - rowY) / ROW_H));
            setValue(ref, withPart(ref.getValue(), index, !bools[index]));
            return true;
        }
        if (ref instanceof EnumRef<?> || ref.getValue() instanceof StringEnum) {
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
        if (ref.getValue() instanceof Regex) {
            return hitText(wrapper, mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof StringFormat format) {
            return hitStringFormat(wrapper, format, mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof PrimitiveList<?> list) {
            return hitPrimitiveList(wrapper, list, mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof WidgetPos pos) {
            return hitWidgetPos(wrapper, pos, mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof PrimitiveMap<?, ?> map) {
            return hitPrimitiveMap(wrapper, map, mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof Holder<?>) {
            return hitText(wrapper, mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof ItemStackTemplate template) {
            return hitItemTemplate(wrapper, template, mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof GuiSetting) {
            return hitGuiSetting(wrapper, mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof EntityTypeEntrySet set) {
            return hitCountChip(wrapper, set.set().size(), mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof BoundedPrimitiveFlagMap<?> flags) {
            return hitCountChip(wrapper, flagCount(flags), mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof LabelVec2 label2) {
            return hitLabelVec(wrapper, 2, label2.data().x(), label2.data().y(), 0,
                    mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof LabelVec3 label3) {
            return hitLabelVec(wrapper, 3, label3.data().x(), label3.data().y(), label3.data().z(),
                    mouseX, mouseY, button, rowY);
        }
        if (ref instanceof ListRef listRef) {
            return hitCountChip(wrapper, listRef.get().size(), mouseX, mouseY, button, rowY);
        }
        if (ref.getValue() instanceof RegexList regexList) {
            return hitCountChip(wrapper, regexList.patterns().size(), mouseX, mouseY, button, rowY);
        }
        if (ref instanceof StringRef) {
            // 有候选值的：点右端箭头开候选，点其余位置开编辑（值框本身可手输）
            if (!stringChoicesOf(wrapper).isEmpty()) {
                Box field = textValueBox(rowY);
                if (inBox(field.x(), field.y(), field.w(), field.h(), mouseX, mouseY)
                        && mouseX >= field.x() + field.w() - ARROW_W) {
                    openDropdown(wrapper, -1, rowY);
                    return true;
                }
            }
            return hitText(wrapper, mouseX, mouseY, button, rowY);
        }
        return true;
    }

    /** 格式化串的命中：两个按钮各管一件事，其余位置开编辑。 */
    private boolean hitStringFormat(
            WrapperConfigRef<?> wrapper, StringFormat format, int mouseX, int mouseY, int button, int rowY) {
        if (button != 0) {
            return false;
        }
        StringFormatGeometry geo = stringFormatGeometry(rowY, format);
        if (geo.argX() >= 0
                && inBox(geo.argX(), geo.btnY(), geo.btnW(), geo.btnH(), mouseX, mouseY)) {
            // 参数提示是纯展示：说明框由 paintStringFormat 记下的 hover 出，这里不做事
            return true;
        }
        if (geo.editX() >= 0 && inBox(geo.editX(), geo.btnY(), geo.btnW(), geo.btnH(), mouseX, mouseY)) {
            StringFormat.openWikiColorString();
            return true;
        }
        return hitText(wrapper, mouseX, mouseY, button, rowY);
    }

    /** 记下这次下拉盖住的是哪一格：普通枚举项 {@code part = -1}，记录分量传它的下标。 */
    private void openDropdown(WrapperConfigRef<?> wrapper, int part, int rowY) {
        dropdown = wrapper;
        dropPart = part;
        dropW = Math.min(180, Math.max(120, contentWidth / 3));
        dropX = controlRight() - dropW;
        dropY = rowY + Math.max(0, part) * ROW_H + ROW_H;
        listPanel = null;
    }

    private void applyOption(WrapperConfigRef<?> wrapper, int part, Object option) {
        if (wrapper.ref().getValue() instanceof StringEnum choice && option instanceof String next) {
            setValue(wrapper.ref(), new StringEnum(next, choice.values()));
            return;
        }
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
        if (mouseX >= right - TEXT_W - 4 && mouseX <= right) {
            numberTarget = wrapper;
            numberPart = part;
            numberRowY = rowY;
            numberSlotX = right - TEXT_W;
            listPanel = null;
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
            listPanel = null;
            return true;
        }
        if (mouseX >= box.hexX() && mouseX < box.hexX() + box.hexW()) {
            palette = null;
            listPanel = null;
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
     * 文本行的命中：只有值区那一块接左键，点开就把输入框排上（由 tick 建）。
     *
     * <p>同一时刻只留一个编辑态：开这一格就把调色盘、名单、下拉与别的输入框都收掉，
     * 否则后加的那个 renderable 会盖在浮层上。焦点只能在 tick 里给：
     * 点击处理完，容器会把焦点交回铺满屏幕的这块画布。
     */
    private boolean hitText(WrapperConfigRef<?> wrapper, int mouseX, int mouseY, int button, int rowY) {
        if (button != 0) {
            return false;
        }
        Box field = textValueBox(rowY);
        if (!inBox(field.x(), field.y(), field.w(), field.h(), mouseX, mouseY)) {
            return false;
        }
        return beginTextEdit(wrapper, rowY);
    }

    /** 开始就地编辑这一行：命中判定由调用方做，这里只负责挂上目标。 */
    private boolean beginTextEdit(WrapperConfigRef<?> wrapper, int rowY) {
        textTarget = wrapper;
        textRowY = rowY;
        textDraft = null;
        textOriginal = null;
        lastTextKey = "";
        palette = null;
        listPanel = null;
        dropdown = null;
        dropPart = -1;
        numberTarget = null;
        colorTarget = null;
        if (textBox != null) {
            // 旧绑定立刻失效：tick 里会按新目标重写文本并重挂 responder
            textBox.setResponder(null);
        }
        return true;
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
