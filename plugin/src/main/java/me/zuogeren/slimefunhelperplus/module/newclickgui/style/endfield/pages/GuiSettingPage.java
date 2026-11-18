package me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield.pages;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import me.matl114.gui.interfaces.ElementFactories;
import me.matl114.gui.interfaces.ElementStyle;
import me.matl114.gui.interfaces.ElementType;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.utils.config.GuiSetting;
import me.matl114.hacks.utils.config.StringEnum;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.config.WrapRGBA;
import me.matl114.hacks.utils.gui.AlignType;
import me.matl114.hacks.utils.gui.CustomGuiConfig;
import me.matl114.hacks.utils.gui.GuiStyle;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.NBTType;
import me.matl114.managers.config.Ref;
import me.matl114.utils.config.BaseAttrKeyValue;
import me.matl114.utils.config.NumberClamp;
import me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.Settings;
import net.minecraft.resources.Identifier;

/**
 * GUI 设置的详细页：把 {@link GuiSetting} 的十来个分量摊成一行一项。
 *
 * <p>宿主把这一整份设置塞进一个私有编辑屏，外观是它自己那套；这里不重写编辑器，
 * 而是把每个分量包成一只读当前值、写回整份记录的 {@code Ref}，
 * 于是开关、滑杆、颜色、下拉全都沿用本界面已有的控件——外观与别的配置页一致，
 * 命中与写回也走同一条链路。
 *
 * <p>颜色分量拆成两行：色值走颜色控件，不透明度走 0–255 的数值控件。
 * 合成一个复合控件要另算一套几何与命中，而这两件事各自都已经有测过的实现。
 */
public class GuiSettingPage extends EndfieldModuleConfigScreen {
    /** 行名沿用宿主的翻译键，本页只补分节标题与不透明度那几行。 */
    private static final String PREFIX = "widget.gui-settings.";

    private final List<WrapperConfigRef<?>> rows;

    public GuiSettingPage(WrapperConfigRef<GuiSetting> target, Settings settings) {
        this(build(target.ref()), settings);
    }

    private GuiSettingPage(List<WrapperConfigRef<?>> rows, Settings settings) {
        super("GUI SETTINGS", "GUI 设置", null, () -> rows, settings, null);
        this.rows = rows;
    }

    /** 风格与配色只影响宿主旧界面，本界面按自己的版式画。 */
    @Override
    protected String hint() {
        return "风格与配色只影响旧界面";
    }

    @Override
    protected String extraStats() {
        return "共 " + rows.size() + " 项";
    }

    // ------------------------------------------------------------------ 分量 → 行

    private static List<WrapperConfigRef<?>> build(Ref<GuiSetting> ref) {
        List<WrapperConfigRef<?>> out = new ArrayList<>();
        if (ref == null) {
            return out;
        }

        // 通用文本
        addColor(out, ref, "text-config", "color",
                () -> setting(ref).textConfig().color(),
                v -> ref.setValue(setting(ref).withTextConfig(setting(ref).textConfig().withColor(v))),
                () -> true);
        addEnum(out, "text-config", "text_align",
                () -> setting(ref).textConfig().textAlign(),
                v -> ref.setValue(setting(ref).withTextConfig(setting(ref).textConfig().withTextAlign(v))));

        // 背景 / 标题 / 标签 / 按钮 / 输入框 / 选择项：一个风格 id 加它自己的配色
        addStyleGroup(out, ref, ElementType.BACKGROUND, "background", "background_style", "background_color",
                CustomGuiConfig::backgroundColor, CustomGuiConfig::withBackgroundColor);
        addStyleGroup(out, ref, ElementType.TITLE, "title", "title_style", "title_color",
                CustomGuiConfig::titleColor, CustomGuiConfig::withTitleColor);
        addStyleGroup(out, ref, ElementType.LABEL, "label", "label_style", "label_color",
                CustomGuiConfig::labelColor, CustomGuiConfig::withLabelColor);
        addStyleGroup(out, ref, ElementType.BUTTON, "button", "button_style", "button_active_color",
                CustomGuiConfig::buttonActiveColor, CustomGuiConfig::withButtonActiveColor);
        addColor(out, ref, "button", "active_frame_color",
                () -> configOf(ref, ElementType.BUTTON).activeFrameColor(),
                v -> writeConfig(ref, ElementType.BUTTON, c -> c.withActiveFrameColor(v)),
                usesConfig(ref, ElementType.BUTTON));
        addColor(out, ref, "button", "inactive_frame_color",
                () -> configOf(ref, ElementType.BUTTON).inactiveFrameColor(),
                v -> writeConfig(ref, ElementType.BUTTON, c -> c.withInactiveFrameColor(v)),
                usesConfig(ref, ElementType.BUTTON));
        addColor(out, ref, "button", "button_inactive_color",
                () -> configOf(ref, ElementType.BUTTON).buttonInactiveColor(),
                v -> writeConfig(ref, ElementType.BUTTON, c -> c.withButtonInactiveColor(v)),
                usesConfig(ref, ElementType.BUTTON));
        addEnum(out, "button", "button_text_align",
                () -> setting(ref).alignConfig().buttonTextAlign(),
                v -> ref.setValue(setting(ref).withAlignConfig(setting(ref).alignConfig().withButtonTextAlign(v))));

        addStyleGroup(out, ref, ElementType.INPUT, "input", "input_style", "text_color",
                CustomGuiConfig::textColor, CustomGuiConfig::withTextColor);
        addColor(out, ref, "input", "text_background",
                () -> configOf(ref, ElementType.INPUT).textBackground(),
                v -> writeConfig(ref, ElementType.INPUT, c -> c.withTextBackground(v)),
                usesConfig(ref, ElementType.INPUT));
        addEnum(out, "input", "input_text_align",
                () -> setting(ref).alignConfig().inputTextAlign(),
                v -> ref.setValue(setting(ref).withAlignConfig(setting(ref).alignConfig().withInputTextAlign(v))));

        // 滑条：总开关关掉时后三项不出现，与宿主编辑屏的联动一致
        out.add(row(new SettingFlagRef(
                () -> setting(ref).enableSlider(),
                v -> ref.setValue(setting(ref).withEnableSlider(v))), "slider", "enable_slider"));
        addShown(out, styleRow(ref, ElementType.SLIDER, "slider", "slider_style"),
                () -> setting(ref).enableSlider());
        addColor(out, ref, "slider", "slider_color",
                () -> configOf(ref, ElementType.SLIDER).sliderColor(),
                v -> writeConfig(ref, ElementType.SLIDER, c -> c.withSliderColor(v)),
                () -> setting(ref).enableSlider() && usesConfig(ref, ElementType.SLIDER).getAsBoolean());
        addShown(out, enumRow("slider", "slider_text_align",
                        () -> setting(ref).alignConfig().sliderTextAlign(),
                        v -> ref.setValue(setting(ref).withAlignConfig(
                                setting(ref).alignConfig().withSliderTextAlign(v)))),
                () -> setting(ref).enableSlider());

        addStyleGroup(out, ref, ElementType.SELECTOR_TAB, "selector-tab", "style", "selected_color",
                CustomGuiConfig::selectorSelectedColor, CustomGuiConfig::withSelectorSelectedColor);
        addColor(out, ref, "selector-tab", "hover_color",
                () -> configOf(ref, ElementType.SELECTOR_TAB).selectorHoverColor(),
                v -> writeConfig(ref, ElementType.SELECTOR_TAB, c -> c.withSelectorHoverColor(v)),
                usesConfig(ref, ElementType.SELECTOR_TAB));
        addColor(out, ref, "selector-tab", "normal_color",
                () -> configOf(ref, ElementType.SELECTOR_TAB).selectorNormalColor(),
                v -> writeConfig(ref, ElementType.SELECTOR_TAB, c -> c.withSelectorNormalColor(v)),
                usesConfig(ref, ElementType.SELECTOR_TAB));
        return out;
    }

    /** 一个「风格 id + 它自己的主配色」：配色只在选中的风格用自定义配色时才出现。 */
    private static void addStyleGroup(
            List<WrapperConfigRef<?>> out,
            Ref<GuiSetting> ref,
            ElementType type,
            String section,
            String styleField,
            String colorField,
            Function<CustomGuiConfig, WrapRGBA> getter,
            BiFunction<CustomGuiConfig, WrapRGBA, CustomGuiConfig> with) {
        out.add(styleRow(ref, type, section, styleField));
        addColor(out, ref, section, colorField,
                () -> getter.apply(configOf(ref, type)),
                v -> writeConfig(ref, type, c -> with.apply(c, v)),
                usesConfig(ref, type));
    }

    private static WrapperConfigRef<?> styleRow(Ref<GuiSetting> ref, ElementType type, String section, String field) {
        List<String> ids = ElementFactories.ids().stream().map(Identifier::toString).toList();
        return row(new DelegateRef<>(
                () -> new StringEnum(setting(ref).style(type).toString(), ids),
                v -> {
                    Identifier id = Identifier.tryParse(v.value());
                    ref.setValue(withStyle(setting(ref), type, id == null ? GuiStyle.CUSTOM : id));
                },
                StringEnum.TYPE), section, field);
    }

    private static void addColor(
            List<WrapperConfigRef<?>> out,
            Ref<GuiSetting> ref,
            String section,
            String field,
            Supplier<WrapRGBA> getter,
            Consumer<WrapRGBA> setter,
            BooleanSupplier visible) {
        addShown(out, row(new DelegateRef<>(
                () -> new WrapColor(getter.get().textColor()),
                v -> setter.accept(getter.get().withTextColor(v.color())),
                WrapColor.TYPE), section, field), visible);
        addShown(out, row(new SettingIntRef(
                () -> getter.get().alpha(),
                v -> setter.accept(getter.get().withAlpha(v))), section, field + ".alpha"), visible);
    }

    private static void addEnum(
            List<WrapperConfigRef<?>> out,
            String section,
            String field,
            Supplier<AlignType> getter,
            Consumer<AlignType> setter) {
        out.add(enumRow(section, field, getter, setter));
    }

    /**
     * 枚举分量：{@code EnumRef} 的 {@code get} / {@code set} 都是 final，改不了读写路径，
     * 所以拿一只普通的 {@code EnumRef} 当值容器，写回靠更新监听器转给整份记录。
     *
     * <p>本页是这份设置唯一的写入口，容器里的值与记录不会分叉。
     */
    private static WrapperConfigRef<?> enumRow(
            String section, String field, Supplier<AlignType> getter, Consumer<AlignType> setter) {
        EnumRef<AlignType> ref = new EnumRef<>(getter.get());
        ref.addUpdateListener(setter::accept);
        return row(ref, section, field);
    }

    private static void addShown(List<WrapperConfigRef<?>> out, WrapperConfigRef<?> ref, BooleanSupplier visible) {
        ref.addShowPredicate(visible);
        out.add(ref);
    }

    // ------------------------------------------------------------------ 读写辅助

    private static GuiSetting setting(Ref<GuiSetting> ref) {
        GuiSetting value = ref.getValue();
        return value == null ? GuiSetting.DEFAULT : value;
    }

    private static CustomGuiConfig configOf(Ref<GuiSetting> ref, ElementType type) {
        GuiSetting value = setting(ref);
        return value.customConfig(value.style(type));
    }

    private static void writeConfig(
            Ref<GuiSetting> ref, ElementType type, Function<CustomGuiConfig, CustomGuiConfig> updater) {
        GuiSetting value = setting(ref);
        Identifier id = value.style(type);
        ref.setValue(value.withCustomConfig(id, updater.apply(value.customConfig(id))));
    }

    private static BooleanSupplier usesConfig(Ref<GuiSetting> ref, ElementType type) {
        return () -> {
            ElementStyle style = ElementFactories.get(setting(ref).style(type));
            return style != null && style.useConfig();
        };
    }

    private static GuiSetting withStyle(GuiSetting value, ElementType type, Identifier id) {
        return switch (type) {
            case BACKGROUND -> value.withBackgroundStyle(id);
            case TITLE -> value.withTitleStyle(id);
            case LABEL -> value.withLabelStyle(id);
            case BUTTON -> value.withButtonStyle(id);
            case INPUT -> value.withInputStyle(id);
            case SLIDER -> value.withSliderStyle(id);
            case SELECTOR_TAB -> value.withSelectorTabStyle(id);
        };
    }

    private static WrapperConfigRef<?> row(Ref<?> ref, String section, String field) {
        return new WrapperConfigRef<>(ref, null, new String[] {PREFIX + section, field});
    }

    // ------------------------------------------------------------------ 合成 Ref

    /** 读当前值、写回整份记录的通用引用：控件外观由 {@code NBTType} 决定。 */
    private static final class DelegateRef<T> extends Ref<T> {
        private final Supplier<T> getter;
        private final Consumer<T> setter;
        private final NBTType<T> type;

        DelegateRef(Supplier<T> getter, Consumer<T> setter, NBTType<T> type) {
            this.getter = getter;
            this.setter = setter;
            this.type = type;
        }

        @Override
        public T getValue() {
            return getter.get();
        }

        @Override
        public void setValue(T value) {
            setter.accept(value);
        }

        @Override
        public Object getAsPrimitive() {
            return getter.get();
        }

        @Override
        public <W> boolean isSameTypeWith(Ref<W> ref) {
            return false;
        }

        @Override
        public <W> boolean copyValueFrom(Ref<W> otherRef) {
            return false;
        }

        @Override
        protected BaseAttrKeyValue<T> _createKeyValue0(String key) {
            return type.createAttrKeyValue(key, getter.get());
        }
    }

    /** 开关分量：外观与别的开关一致，读写都落到整份记录上。 */
    private static final class SettingFlagRef extends FlagRef {
        private final BooleanSupplier getter;
        private final Consumer<Boolean> setter;

        SettingFlagRef(BooleanSupplier getter, Consumer<Boolean> setter) {
            super(false);
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        public boolean get() {
            return getter.getAsBoolean();
        }

        @Override
        public void set(boolean value) {
            if (validateUpdateValue(value)) {
                setter.accept(value);
                callUpdate();
            }
        }
    }

    /** 数值分量：不透明度用它，上下界交给滑杆。 */
    private static final class SettingIntRef extends IntRef {
        private final IntSupplier getter;
        private final IntConsumer setter;

        SettingIntRef(IntSupplier getter, IntConsumer setter) {
            super(0);
            this.getter = getter;
            this.setter = setter;
            addValidator(new NumberClamp<>(0, 255));
        }

        @Override
        public int get() {
            return getter.getAsInt();
        }

        @Override
        public void set(int value) {
            if (validateUpdateValue(value)) {
                setter.accept(value);
                callUpdate();
            }
        }
    }
}
