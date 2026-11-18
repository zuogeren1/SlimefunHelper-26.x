package me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import me.matl114.gui.Constants;
import me.matl114.gui.basic.AbstractElement;
import me.matl114.gui.basic.ButtonAction;
import me.matl114.gui.basic.ColorSampler;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.gui.basic.ElementHandler;
import me.matl114.gui.basic.ExecutableWidget;
import me.matl114.gui.basic.TextAlign;
import me.matl114.gui.basic.TextProvider;
import me.matl114.gui.basic.TooltipHandler;
import me.matl114.gui.complex.BoxElement;
import me.matl114.gui.elements.SelectBackgroundElement;
import me.matl114.gui.elements.SlidingInputElement;
import me.matl114.gui.elements.TextFieldElement;
import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.utils.config.Value;
import me.matl114.utils.config.ValueAccessor;
import me.matl114.utils.config.WrapperFactory;
import me.matl114.utils.ChatUtils;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.newclickgui.NewClickGui;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 本界面风格的自定义控件工厂。
 *
 * <p>模块的 {@code addCustomWidgets} 只认 {@link ElementFactory}：标题、标签、按钮、输入框
 * 都交给工厂去造。宿主默认给的是 {@code CustomElementFactory}，画出来是宿主那套配色与圆角，
 * 混在自绘的配置页里一眼就能看出是两套东西。
 *
 * <p>这里只换外观，不换能力：控件仍是宿主那几个元素类（输入框、滑动输入、选择背景），
 * 所以输入法、光标、拖动、校验这些行为一个都不用重写；配色与几何改从 {@link Ef.Palette} 取。
 */
public final class EndfieldElementFactory implements ElementFactory {
    /** 开关本体宽：与配置页行尾那个小开关同一个尺寸。 */
    private static final int SWITCH_W = 20;

    private final boolean light;

    /**
     * 行右边界在控件坐标系里的 x；{@code 0} 表示不知道（独立屏、宿主浮层），此时开关退回控件自己的右端。
     *
     * <p>模块自定义控件挂在 {@code SubScreenWidget} 里，坐标是相对那一行的；
     * 而开关要贴的是整行的右端——宿主给开关的宽度只有行高那么宽，按控件自己算会落在值区左边缘。
     */
    private final int rowRight;

    public EndfieldElementFactory(boolean light) {
        this(light, 0);
    }

    public EndfieldElementFactory(boolean light, int rowRight) {
        this.light = light;
        this.rowRight = rowRight;
    }

    private Ef.Palette pal() {
        return Ef.palette(light).withAlpha(NewClickGui.opacity());
    }

    private ColorSampler ink() {
        return () -> pal().ink();
    }

    private ColorSampler paper() {
        return () -> pal().paper();
    }

    private ColorSampler chip() {
        return () -> pal().chip();
    }

    private ColorSampler panel() {
        return () -> pal().panel();
    }

    private ColorSampler trace() {
        return () -> pal().trace();
    }

    // ------------------------------------------------------------------ 背景与底板

    @Override
    public ElementHandler createBackground() {
        return createBackground(null);
    }

    @Override
    public ElementHandler createBackground(@Nullable ColorSampler overrideColor) {
        ColorSampler fill = overrideColor == null ? paper() : overrideColor;
        return new AbstractElement() {
            @Override
            public void renderCentered0(
                    DrawableWidget element,
                    VDrawContext context,
                    int mouseX,
                    int mouseY,
                    float delta,
                    float alpha,
                    boolean shouldHighlight) {
                Ef.fill(context, 0, 0, element.getTextureWidth(), element.getTextureHeight(), fill.getColorInt());
            }
        };
    }

    @Override
    public ElementHandler createPlate(boolean hasInteract, @Nullable ColorSampler overrideColor) {
        ColorSampler fill = overrideColor == null ? chip() : overrideColor;
        return new AbstractElement() {
            @Override
            public void renderCentered0(
                    DrawableWidget element,
                    VDrawContext context,
                    int mouseX,
                    int mouseY,
                    float delta,
                    float alpha,
                    boolean shouldHighlight) {
                // 切角块是这套界面的基本形状：底板也用它，圆角会显得是另一套东西
                Ef.cutFill(
                        context, 0, 0, element.getTextureWidth(), element.getTextureHeight(),
                        fill.getColorInt(), Ef.CUT);
            }
        };
    }

    // ------------------------------------------------------------------ 文字

    @Override
    public ElementHandler createLabel(TextProvider provider, TooltipHandler handler) {
        return createLabel(provider, handler, false);
    }

    @Override
    public ElementHandler createLabel(TextProvider provider, TooltipHandler handler, boolean splitter) {
        return text(provider, handler, false);
    }

    @Override
    public ElementHandler createText(TextProvider provider, TooltipHandler handler) {
        return createText(provider, handler, false);
    }

    @Override
    public ElementHandler createText(TextProvider provider, TooltipHandler handler, boolean splitter) {
        return text(provider, handler, false);
    }

    @Override
    public ElementHandler createTitle(TextProvider provider, TooltipHandler handler) {
        return createTitle(provider, handler, false);
    }

    @Override
    public ElementHandler createTitle(TextProvider provider, TooltipHandler handler, boolean splitter) {
        return text(provider, handler, true);
    }

    /**
     * 文字控件：标题与标签都走这里，区别只在字色与是否带一条分隔线。
     *
     * <p>不缩放：宿主的 {@code RawTextElement} 会把字压扁塞进框里，字号一变就与旁边的自绘行对不齐。
     * 放不下时按 {@link Ef#ellipsis} 截断，悬停由说明框补全。
     */
    private ElementHandler text(TextProvider provider, TooltipHandler handler, boolean title) {
        ElementHandler element = new AbstractElement() {
            @Override
            public void renderCentered0(
                    DrawableWidget widget,
                    VDrawContext context,
                    int mouseX,
                    int mouseY,
                    float delta,
                    float alpha,
                    boolean shouldHighlight) {
                Component raw = provider.getText(widget);
                if (raw == null) {
                    return;
                }
                int w = widget.getTextureWidth();
                int h = widget.getTextureHeight();
                String shown = Ef.ellipsis(raw.getString(), Math.max(0, w - 4));
                Ef.text(context, shown, 2, (h - Ef.lineH()) / 2, title ? pal().ink() : pal().ink2());
                if (title) {
                    Ef.rule(context, 0, h - 1, w, pal().rule());
                }
            }
        };
        return handler == null ? element : element.withTooltips(handler);
    }

    // ------------------------------------------------------------------ 按钮

    @Override
    public ElementHandler createButton(
            TextProvider provider,
            TooltipHandler handler,
            ButtonAction action,
            BooleanSupplier active,
            BooleanSupplier enable) {
        ElementHandler element = new BoxElement(action) {
            @Override
            public void renderCentered0(
                    DrawableWidget widget,
                    VDrawContext context,
                    int mouseX,
                    int mouseY,
                    float delta,
                    float alpha,
                    boolean shouldHighlight) {
                int w = widget.getTextureWidth();
                int h = widget.getTextureHeight();
                boolean on = active.getAsBoolean();
                boolean usable = enable.getAsBoolean();
                Ef.Palette p = pal();
                // 激活态不整块填色：按钮宽度常常是整行，填满会变成一大块色板，
                // 与行里那些小控件完全不是一套东西。用描边 + 左侧一条强调线表示。
                if (shouldHighlight) {
                    Ef.cutFill(context, 0, 0, w, h, p.chip(), Ef.CUT);
                } else {
                    Ef.cutOutline(context, 0, 0, w, h, on && usable ? p.ink() : p.rule(), Ef.CUT);
                }
                if (on && usable) {
                    Ef.fill(context, 0, 0, 2, h, p.trace());
                }
                Component raw = provider.getText(widget);
                if (raw == null) {
                    return;
                }
                String shown = Ef.ellipsis(raw.getString(), Math.max(0, w - 8));
                int color = usable ? p.ink() : p.ink2();
                Ef.text(context, shown, (w - Ef.textWidth(shown)) / 2, (h - Ef.lineH()) / 2, color);
            }
        };
        return handler == null ? element : element.withTooltips(handler);
    }

    /**
     * 控件自己的右端：知道行右边界就贴行尾，否则退回控件右端。
     *
     * <p>宿主给开关的宽度只有行高那么宽（{@code createToggleButton(..., dy, dy)}），
     * 按控件自己算会落在值区左边缘，与配置页行尾那个开关对不上。
     */
    private int switchRight(DrawableWidget widget) {
        if (rowRight <= 0) {
            return widget.getTextureWidth();
        }
        return rowRight - widget.getX();
    }

    /**
     * 开关：与配置页行尾那个小开关同一套画法——开是实心块加纸色缺口，关是虚线框加小方块。
     *
     * <p>所有 {@code createToggleButton} 重载最终都汇到这一个，覆写它一处就够。
     * 不能走 {@link #createButton}：那是整行宽的按钮，开关画成整行块既不像开关，也压掉行名。
     */
    @Override
    public ElementHandler createToggleButton(
            TextProvider provider, TooltipHandler handler, ValueAccessor<Boolean> accessor, boolean frame) {
        ElementHandler element = new BoxElement(ButtonAction.run(() -> accessor.setValue(!accessor.getValue()))) {
            @Override
            public void renderCentered0(
                    DrawableWidget widget,
                    VDrawContext context,
                    int mouseX,
                    int mouseY,
                    float delta,
                    float alpha,
                    boolean shouldHighlight) {
                int h = widget.getTextureHeight();
                boolean on = accessor.getValue();
                Ef.Palette p = pal();
                int right = switchRight(widget);
                int sx = right - SWITCH_W;
                int sy = (h - 9) / 2;
                if (on) {
                    Ef.fill(context, sx, sy, SWITCH_W, 9, p.ink());
                    Ef.fill(context, sx + SWITCH_W - 7, sy + 2, 5, 5, p.paper());
                } else {
                    Ef.dashedOutline(context, sx, sy, SWITCH_W, 9, p.ink2());
                    Ef.fill(context, sx + 2, sy + 2, 5, 5, p.ink2());
                }
                Component raw = provider.getText(widget);
                if (raw == null) {
                    return;
                }
                // 行名占左边，给开关让出 28px
                String shown = Ef.ellipsis(raw.getString(), Math.max(0, sx - 12));
                Ef.text(context, shown, 2, (h - Ef.lineH()) / 2, p.ink());
            }
        };
        return handler == null ? element : element.withTooltips(handler);
    }

    /**
     * 开关控件：把控件本身挪到画出来的那个位置。
     *
     * <p>宿主给值区开关的宽度只有行高那么宽（{@code createToggleButton(..., dy, dy)}），
     * 而开关画在行尾；控件不跟着挪过去的话，画出来的位置与能点的位置就是两处。
     *
     * <p>两种形态分开处理：值区开关（宽度不超过行高）只占行尾那一格，与配置页自己的
     * {@code right - 24 .. right} 一致；带行名的整行开关（宽度大于行高）铺满到行尾，
     * 行名照旧画在左边。
     */
    private DrawableWidget toggleWidget(int x, int y, int dx, int dy, ElementHandler handler) {
        int nx = x;
        int nw = dx;
        if (rowRight > 0) {
            if (dx <= dy) {
                nw = Math.max(dx, SWITCH_W + 4);
                nx = rowRight - nw;
            } else {
                nw = Math.max(dx, rowRight - x);
            }
        }
        ExecutableWidget widget = ExecutableWidget.instance(nx, y, nw, dy);
        widget.setElementHandler(handler);
        return widget;
    }

    @Override
    public DrawableWidget createToggleButton(
            String translationKey, ValueAccessor<Boolean> accessor, int x, int y, int dx, int dy) {
        return createToggleButton(
                TextProvider.of(Component.translatable(translationKey)),
                TooltipHandler.of(ChatUtils.parseTooltipsTranslation(translationKey + ".tooltips", "")),
                accessor, true, x, y, dx, dy);
    }

    @Override
    public DrawableWidget createToggleButton(
            String translationKey, ValueAccessor<Boolean> accessor, boolean frame, int x, int y, int dx, int dy) {
        return createToggleButton(
                TextProvider.of(Component.translatable(translationKey)),
                TooltipHandler.of(ChatUtils.parseTooltipsTranslation(translationKey + ".tooltips", "")),
                accessor, frame, x, y, dx, dy);
    }

    @Override
    public DrawableWidget createToggleButton(
            String translationKey, ValueAccessor<Boolean> accessor, int x, int y, int dx, int dy, boolean frame) {
        return createToggleButton(translationKey, accessor, frame, x, y, dx, dy);
    }

    @Override
    public DrawableWidget createToggleButton(
            Supplier<Component> text,
            Supplier<List<Component>> tooltips,
            ValueAccessor<Boolean> accessor,
            int x,
            int y,
            int dx,
            int dy) {
        return createToggleButton(el -> text.get(), TooltipHandler.of(tooltips), accessor, true, x, y, dx, dy);
    }

    @Override
    public DrawableWidget createToggleButton(
            Supplier<Component> text,
            Supplier<List<Component>> tooltips,
            ValueAccessor<Boolean> accessor,
            boolean frame,
            int x,
            int y,
            int dx,
            int dy) {
        return createToggleButton(el -> text.get(), TooltipHandler.of(tooltips), accessor, frame, x, y, dx, dy);
    }

    @Override
    public DrawableWidget createToggleButton(
            TextProvider provider,
            TooltipHandler handler,
            ValueAccessor<Boolean> accessor,
            boolean frame,
            int x,
            int y,
            int dx,
            int dy) {
        return toggleWidget(x, y, dx, dy, createToggleButton(provider, handler, accessor, frame));
    }

    /**
     * 列表入口：切角块里写「N 项 ▾」，与配置页自己的名单行同一套外观。
     *
     * <p>宿主造这个按钮时直接用了写死的绿色精灵图（{@code WidgetUtils} 里 {@code IconElement.fixedGui}），
     * 工厂连插手的机会都没有；所以由本界面在挂载前把那个图标换掉，见
     * {@code EndfieldModuleConfigScreen#restyleCustomWidgets}。
     */
    public ElementHandler createListButton(ButtonAction action) {
        ElementHandler element = new BoxElement(action) {
            @Override
            public void renderCentered0(
                    DrawableWidget widget,
                    VDrawContext context,
                    int mouseX,
                    int mouseY,
                    float delta,
                    float alpha,
                    boolean shouldHighlight) {
                int w = widget.getTextureWidth();
                int h = widget.getTextureHeight();
                Ef.Palette p = pal();
                if (shouldHighlight) {
                    Ef.cutFill(context, 0, 0, w, h, p.chip(), Ef.CUT);
                }
                Ef.icon(context, Constants.LIST_TAG_SPRITE, 0, 0, Math.min(w, h),
                        shouldHighlight ? p.ink() : p.ink2());
            }
        };
        return element.withTooltips(TooltipHandler.of(Constants.openListEditTooltips()));
    }

    @Override
    public ElementHandler createElement(
            ElementHandler handler, @Nullable TooltipHandler tooltips, @Nullable ButtonAction action) {
        ElementHandler element = new BoxElement(action == null ? ButtonAction.empty() : action).combineRender(handler);
        return tooltips == null ? element : element.withTooltips(tooltips);
    }

    // ------------------------------------------------------------------ 输入

    /**
     * 输入框：与配置页自己的值槽同一套外观——{@code panel} 底、{@code rule} 细边，聚焦时边线转 {@code trace}。
     *
     * <p>宿主默认给的是浅灰硬边框，混在自绘行里一眼能看出是两套东西；
     * 边框色必须显式设，不设就退回宿主那个 {@code 0xFFA0A0A0}。
     */
    @Override
    public <T> ElementHandler createInput(Value<T> value) {
        TextFieldElement field = TextFieldElement.ofValue(value)
                .setBackgroundColor(panel())
                .setTextColor(ink())
                .setAlign(TextAlign.LEFT);
        field.setBorderColorProvider((widget, focused) -> focused ? pal().trace() : pal().rule());
        return field;
    }

    @Override
    public <T> ElementHandler createSlidingInput(Value<T> value, WrapperFactory<Double, T> sliderFactory) {
        return new SlidingInputElement<>(value, sliderFactory)
                .setSliderColor(trace())
                .setBackgroundColor(chip())
                .setTextColor(ink())
                .setAlign(TextAlign.MIDDLE);
    }

    // ------------------------------------------------------------------ 选择背景

    @Override
    public SelectBackgroundElement createSelectorTab(ValueAccessor<Boolean> selected) {
        return new SelectBackgroundElement(selected, trace(), chip(), paper());
    }
}
