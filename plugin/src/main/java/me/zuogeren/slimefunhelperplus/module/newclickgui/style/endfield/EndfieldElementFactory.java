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
                if (on && usable) {
                    Ef.cutFill(context, 0, 0, w, h, p.ink(), Ef.CUT);
                } else if (shouldHighlight) {
                    Ef.cutFill(context, 0, 0, w, h, p.chip(), Ef.CUT);
                } else {
                    Ef.cutOutline(context, 0, 0, w, h, p.rule(), Ef.CUT);
                }
                Component raw = provider.getText(widget);
                if (raw == null) {
                    return;
                }
                String shown = Ef.ellipsis(raw.getString(), Math.max(0, w - 8));
                int color = on && usable ? p.paper() : (usable ? p.ink() : p.ink2());
                Ef.text(context, shown, (w - Ef.textWidth(shown)) / 2, (h - Ef.lineH()) / 2, color);
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
