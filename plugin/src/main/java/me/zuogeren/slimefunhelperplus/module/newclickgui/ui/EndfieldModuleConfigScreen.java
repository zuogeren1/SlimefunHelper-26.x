package me.zuogeren.slimefunhelperplus.module.newclickgui.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import me.matl114.gui.Constants;
import me.matl114.gui.GenericScreen;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
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
import net.minecraft.network.chat.Component;

/**
 * 模块配置页：把 {@code getEditableConfig()} 里的条目按真实配置前缀分组，画成一张表单。
 *
 * <p>整页只有**一个**绘制控件、铺满屏幕，所有绘制与命中都用屏幕绝对坐标。
 * 之前按窗口切成几个子控件时，坐标系一层套一层（控件偏移 + 局部 + 裁剪），
 * 每加一个特性就要重算一遍，这里直接去掉那一层。
 *
 * <p>控件分两类：开关 / 整数步进 / 比例滑杆 / 枚举下拉 / 键位绑定各走专用控件；
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

    private final BaseModule module;
    private final EndfieldSettings settings;

    private int winX;
    private int winY;
    private int winW;
    private int winH;

    private int contentLeft;
    private int contentWidth;
    private int scroll;
    private int totalHeight;

    private final List<Row> rows = new ArrayList<>();

    /** 打开中的枚举下拉：绘制与命中都用记录下来的矩形。 */
    private WrapperConfigRef<?> dropdown;
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
    private EditBox numberBox;
    private String lastNumberKey = "";

    /** 每个配置项的文本读写句柄：createKeyValue 会挂监听，不能每帧重建。 */
    private final Map<WrapperConfigRef<?>, AttrKeyValue<?>> handles = new IdentityHashMap<>();

    /** 没有显式约束的数值，其推导区间只算一次，避免跨数量级时轨道跳档；复合数值按分量分开记。 */
    private final Map<String, double[]> derivedBounds = new HashMap<>();

    /** 一帧内布局出来的行，绘制时写入、点击时读取；坐标为屏幕绝对坐标。 */
    private record Row(int y, int height, WrapperConfigRef<?> ref, String section, boolean header) {}

    public EndfieldModuleConfigScreen(BaseModule module, EndfieldSettings settings) {
        super(Component.empty(), 0, 0);
        this.module = module;
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
        winW = Math.min(this.width - 8, Math.max(280, Math.round(this.width * 0.62F)));
        winH = Math.min(this.height - 8, Math.max(200, Math.round(this.height * 0.78F)));
        winX = (this.width - winW) / 2;
        winY = (this.height - winH) / 2;

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
                controlRight() - TEXT_W + 3,
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
        if (part < 0) {
            return valueText(target.ref());
        }
        double[] parts = components(target.ref().getValue());
        return parts != null && part < parts.length ? formatNumber(parts[part]) : "";
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
            if (part < 0) {
                setNumeric(target.ref(), value);
            } else {
                setValue(target.ref(), withComponent(target.ref().getValue(), part, value));
            }
        } catch (NumberFormatException ignored) {
            // 打到一半（"-"、"."）解析不了是正常的
        }
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
     * 本屏自己画的控件：开关 / 数值 / 枚举 / 键位，以及"一条记录里几个 double"（例如 {@code Vec2}）。
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
                || components(value.getValue()) != null;
    }

    // ------------------------------------------------------------------ 数据

    private List<WrapperConfigRef<?>> visibleRefs() {
        List<WrapperConfigRef<?>> out = new ArrayList<>();
        for (WrapperConfigRef<?> ref : module.getEditableConfig()) {
            if (ref.shouldShow()) {
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

    /** 复合数值（记录里的两三个 double）拆成几个滑杆，读不出来就返回 null 交回宿主控件。 */
    private static double[] components(Object value) {
        if (value == null || !value.getClass().isRecord()) {
            return null;
        }
        RecordComponent[] parts = value.getClass().getRecordComponents();
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }
        double[] out = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                Class<?> type = parts[i].getType();
                if (type != double.class && type != float.class) {
                    return null;
                }
                out[i] = ((Number) parts[i].getAccessor().invoke(value)).doubleValue();
            }
        } catch (Throwable e) {
            return null;
        }
        return out;
    }

    private static Object withComponent(Object value, int index, double next) {
        try {
            RecordComponent[] parts = value.getClass().getRecordComponents();
            Object[] args = new Object[parts.length];
            for (int i = 0; i < parts.length; i++) {
                Object current = parts[i].getAccessor().invoke(value);
                double raw = i == index ? next : ((Number) current).doubleValue();
                args[i] = parts[i].getType() == float.class ? (Object) (float) raw : (Object) raw;
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

    private static Object[] enumOptions(Ref<?> ref) {
        Object value = ref.getValue();
        return value instanceof Enum<?> e ? e.getDeclaringClass().getEnumConstants() : new Object[0];
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
        return winY + BAR_H + 2;
    }

    private int bodyHeight() {
        return winH - BAR_H - FOOT_H - 4;
    }

    private void buildRows() {
        rows.clear();
        contentLeft = winX + PAD;
        contentWidth = Math.min(winW - PAD * 2, 560);

        int y = bodyTop() - scroll;
        String current = null;
        for (WrapperConfigRef<?> ref : visibleRefs()) {
            String section = sectionKey(ref);
            if (!section.equals(current)) {
                current = section;
                rows.add(new Row(y, SECTION_H, ref, section, true));
                y += SECTION_H;
            }
            double[] parts = components(ref.ref().getValue());
            int height = parts != null ? ROW_H * parts.length : ROW_H;
            rows.add(new Row(y, height, ref, section, false));
            y += height;
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

        paintBar(ctx, p, mouseX, mouseY);
        paintBody(ctx, p, mouseX, mouseY);
        paintDropdown(ctx, p, mouseX, mouseY);
        paintFooter(ctx, p);
        paintFrame(ctx, p);
        paintTooltip(ctx, p, mouseX, mouseY);
    }

    private void paintBar(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
        Ef.fill(ctx, winX, winY, winW, BAR_H, Ef.BAR_BG);

        boolean backHot = mouseX >= winX + 6 && mouseX <= winX + 40 && mouseY >= winY + 5 && mouseY <= winY + 17;
        if (backHot) {
            Ef.cutFill(ctx, winX + 6, winY + 5, 34, 12, Ef.BAR_RULE, Ef.CUT);
        } else {
            Ef.cutOutline(ctx, winX + 6, winY + 5, 34, 12, Ef.BAR_INK2, Ef.CUT);
        }
        Ef.text(ctx, "< 返回", winX + 9, winY + 6, Ef.BAR_INK);

        int titleX = winX + 48;
        String name = EndfieldClickGuiScreen.moduleLabel(module);
        Ef.text(ctx, name, titleX, winY + 6, Ef.BAR_INK);
        String id = module.getName().toUpperCase(Locale.ROOT);
        Ef.trackedWide(ctx, id, titleX + Ef.textWidth(name) + 8, winY + 7, Ef.BAR_INK2, 1, 1.2F);

        if (module.getBindFlag() == null) {
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
                Ef.text(ctx, "这个模块还没有可编辑的设置。", contentLeft, top + 6, p.ink2());
                return;
            }
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
        } finally {
            ctx.disableScissor();
        }
        Ef.scrollbar(ctx, winX + winW - 4, top + 2, height - 6, scroll, totalHeight, height, p.rule(), p.ink2());
    }

    private void paintSection(VDrawContext ctx, Ef.Palette p, int y, String section) {
        String leaf = section.isEmpty() ? "MAIN" : leaf(section).replace('-', ' ').toUpperCase(Locale.ROOT);
        String eyebrow = "// " + leaf;
        Ef.trackedWide(ctx, eyebrow, contentLeft, y + 7, p.ink2(), 1, 1.2F);
        int titleX = contentLeft + Math.max(72, Ef.trackedWideWidth(eyebrow, 1, 1.2F) + 10);
        Ef.text(ctx, sectionTitle(section), titleX, y + 7, p.ink());
        Ef.rule(ctx, contentLeft, y + SECTION_H - 6, contentWidth, p.rule());
    }

    private void paintRow(
            VDrawContext ctx, Ef.Palette p, int y, WrapperConfigRef<?> ref, int mouseX, int mouseY) {
        int right = controlRight();
        double[] rowParts = components(ref.ref().getValue());
        int rowH = rowParts != null ? ROW_H * rowParts.length : ROW_H;
        boolean hover = mouseY >= y && mouseY < y + rowH && mouseX >= contentLeft - 4 && mouseX <= right + 4;
        if (hover && dropdown == null) {
            hovered = ref;
        }
        if (hover) {
            Ef.fill(ctx, contentLeft - 4, y, contentWidth + 8, rowH, Ef.mix(p.paper(), p.ink(), 0.04f));
            Ef.fill(ctx, contentLeft - 4, y, 3, rowH, p.trace());
        }
        Ef.text(ctx, Ef.ellipsis(label(ref), labelWidth()), contentLeft, y + 5, p.ink());
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
        double[] parts = components(raw);

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
        } else if (parts != null) {
            // 一条记录里的几个数值：每个分量占一行，标签用字段名
            String[] names = componentNames(raw);
            for (int i = 0; i < parts.length; i++) {
                double[] range = bounds(ref, parts[i], i);
                paintSlider(ctx, p, y + i * ROW_H, right, formatNumber(parts[i]),
                        parts[i], range[0], range[1], names[i],
                        numberTarget == ref && numberPart == i);
            }
        } else {
            // 其余类型由宿主自带的编辑器负责绘制：这里只登记矩形并留白
            int boxW = Math.min(260, Math.max(120, contentWidth / 3));
            editorRects.put(ref, new int[] {right - boxW, y + 2, boxW, ROW_H - 4});
            Ef.fill(ctx, right - boxW, y + 3, boxW, ROW_H - 6, Ef.alpha(p.chip(), 0.35F));
        }
    }


    /** 说明文字：和原界面同一个约定，取 {@code <键名>.tooltips}。 */
    private List<Component> tooltips(WrapperConfigRef<?> ref) {
        return tooltipCache.computeIfAbsent(
                ref, r -> ChatUtils.parseTooltipsTranslation(r.keyName() + ".tooltips", DUMMY_TOOLTIP));
    }

    private static final String DUMMY_TOOLTIP = "\u0000none";

    private void paintTooltip(VDrawContext ctx, Ef.Palette p, int mouseX, int mouseY) {
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

        if (!name.isEmpty()) {
            Ef.tracked(ctx, name, sliderX - 16 - Ef.textWidth(name), y + 5, p.ink2(), 1);
        }
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
            names[i] = parts[i].getName().toUpperCase(Locale.ROOT);
        }
        return names;
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
        Object[] options = enumOptions(dropdown.ref());
        if (options.length == 0) {
            dropdown = null;
            return;
        }
        int rowH = 13;
        int height = options.length * rowH + 4;
        int top = dropTop(height);
        Ef.fill(ctx, dropX, top, dropW, height, p.panel());
        Ef.cutOutline(ctx, dropX, top, dropW, height, p.ink(), Ef.CUT);

        Object current = dropdown.ref().getValue();
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
        Ef.text(ctx, left, winX + 8, y + 3, p.ink2());
        String mid = module.getModuleManager() == null ? "" : module.getModuleManager().getName();
        Ef.text(ctx, mid, winX + 8 + Ef.textWidth(left) + 12, y + 3, p.ink2());
        String hint = "拖滑杆 │ 右键减一";
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
        // 点别处收起数值输入框；真的点在数值槽上时 hitNumber 会把它重新设回来
        numberTarget = null;
        buildRows();

        if (dropdown != null) {
            Object[] options = enumOptions(dropdown.ref());
            int rowH = 13;
            int top = dropTop(options.length * rowH + 4);
            if (mx >= dropX && mx <= dropX + dropW && my >= top && my <= top + options.length * rowH + 4) {
                int index = (my - top - 2) / rowH;
                if (index >= 0 && index < options.length) {
                    setValue(dropdown.ref(), options[index]);
                }
            }
            dropdown = null;
            return true;
        }

        if (my >= winY && my < winY + BAR_H) {
            if (mx >= winX + 6 && mx <= winX + 42) {
                back();
                return true;
            }
            boolean on = module.getBindFlag() != null && module.getBindFlag().get();
            String state = on ? "启用" : "停用";
            int stateW = Ef.textWidth(state) + 8;
            int stateX = winX + winW - 8 - stateW;
            if (mx >= stateX && mx <= stateX + stateW) {
                if (module.getBindFlag() != null) {
                    module.getBindFlag().toggle();
                }
                return true;
            }
            return false;
        }

        if (my < bodyTop() || my > bodyTop() + bodyHeight()) {
            return false;
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
        // 滚动会挪动行，下拉跟着旧坐标走就会错位：直接收起来
        dropdown = null;
        numberTarget = null;
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
            return hitNumber(wrapper, -1, mouseX, button, rowY, valueText(ref), numericOf(ref));
        }
        double[] parts = components(ref.getValue());
        if (parts != null) {
            int index = Math.max(0, Math.min(parts.length - 1, (mouseY - rowY) / ROW_H));
            return hitNumber(wrapper, index, mouseX, button, rowY, formatNumber(parts[index]), parts[index]);
        }
        if (ref instanceof EnumRef<?>) {
            dropdown = wrapper;
            dropW = Math.min(180, Math.max(120, contentWidth / 3));
            dropX = right - dropW;
            dropY = rowY + ROW_H;
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

    /** 数字行的一击：滑轨起拖，数值槽开输入框，右键退一档。 */
    private boolean hitNumber(
            WrapperConfigRef<?> wrapper, int part, int mouseX, int button, int rowY, String text, double current) {
        int right = controlRight();
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

    private void applyRatio(WrapperConfigRef<?> wrapper, int part, double current, double ratio) {
        double[] range = bounds(wrapper, current, part);
        applyRange(wrapper, part, range[0], range[1], ratio);
    }

    private void applyRange(WrapperConfigRef<?> wrapper, int part, double min, double max, double ratio) {
        double clamped = Math.max(0, Math.min(1, ratio));
        applyValue(wrapper, part, min + clamped * (max - min));
    }

    private void applyValue(WrapperConfigRef<?> wrapper, int part, double next) {
        if (part < 0) {
            setNumeric(wrapper.ref(), next);
        } else {
            setValue(wrapper.ref(), withComponent(wrapper.ref().getValue(), part, next));
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

    private void back() {
        onClose();
    }
}
