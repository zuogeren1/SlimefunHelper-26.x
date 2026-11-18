package me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield;

import java.util.function.BooleanSupplier;
import me.matl114.gui.basic.AbstractElement;
import me.matl114.gui.basic.ButtonAction;
import me.matl114.gui.basic.ColorSampler;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.gui.basic.ElementHandler;
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
    private final boolean light;

    public EndfieldElementFactory(boolean light) {
        this.light = light;
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
                int w = widget.getTextureWidth();
                int h = widget.getTextureHeight();
                boolean on = accessor.getValue();
                Ef.Palette p = pal();
                // 开关本体固定 20×9，贴在控件右端：控件本身可能占满整行
                int sw = 20;
                int sh = 9;
                int sx = w - sw;
                int sy = (h - sh) / 2;
                if (on) {
                    Ef.fill(context, sx, sy, sw, sh, p.ink());
                    Ef.fill(context, sx + sw - 7, sy + 2, 5, 5, p.paper());
                } else {
                    Ef.dashedOutline(context, sx, sy, sw, sh, p.ink2());
                    Ef.fill(context, sx + 2, sy + 2, 5, 5, p.ink2());
                }
                Component raw = provider.getText(widget);
                if (raw == null) {
                    return;
                }
                // 行名占左边，给开关让出 28px
                String shown = Ef.ellipsis(raw.getString(), Math.max(0, w - sw - 12));
                Ef.text(context, shown, 2, (h - Ef.lineH()) / 2, p.ink());
            }
        };
        return handler == null ? element : element.withTooltips(handler);
    }

    @Override
    public ElementHandler createElement(
            ElementHandler handler, @Nullable TooltipHandler tooltips, @Nullable ButtonAction action) {
        ElementHandler element = new BoxElement(action == null ? ButtonAction.empty() : action).combineRender(handler);
        return tooltips == null ? element : element.withTooltips(tooltips);
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public <T> ElementHandler createInput(Value<T> value) {
        return TextFieldElement.ofValue(value)
                .setBackgroundColor(chip())
                .setTextColor(ink())
                .setAlign(TextAlign.LEFT);
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
