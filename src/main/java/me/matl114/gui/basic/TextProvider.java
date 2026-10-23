package me.matl114.gui.basic;

import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public interface TextProvider {
    Component getText(DrawableWidget element);

    // DO NO CALL
    default FormattedCharSequence getLabel(DrawableWidget element) {
        Component text = getText(element);
        return text == null ? null : text.getVisualOrderText();
    }

    default TextAlign getAlign(DrawableWidget element) {
        return TextAlign.MIDDLE;
    }

    static TextProvider of(Component text) {
        return of(element -> text);
    }

    static TextProvider of(Supplier<Component> text) {
        return of(element -> text.get());
    }

    static TextProvider of(Function<DrawableWidget, Component> text) {
        return text::apply;
    }

    static TextProvider of(Component text, TextAlign align) {
        return of(element -> text, align);
    }

    static TextProvider of(Supplier<Component> text, TextAlign align) {
        return of(element -> text.get(), align);
    }

    static TextProvider of(Function<DrawableWidget, Component> text, TextAlign align) {
        return withAlign(of(text), align);
    }

    static TextProvider of(Function<DrawableWidget, Component> text, Function<DrawableWidget, TextAlign> align) {
        TextProvider provider = of(text);
        return new TextProvider() {
            @Override
            public Component getText(DrawableWidget element) {
                return provider.getText(element);
            }

            @Override
            public TextAlign getAlign(DrawableWidget element) {
                return align.apply(element);
            }
        };
    }

    static TextProvider withAlign(TextProvider provider, TextAlign align) {
        if (align == null) {
            return provider;
        }
        return new TextProvider() {
            @Override
            public Component getText(DrawableWidget element) {
                return provider.getText(element);
            }

            @Override
            public TextAlign getAlign(DrawableWidget element) {
                return align;
            }
        };
    }
}
