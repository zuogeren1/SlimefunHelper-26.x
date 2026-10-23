package me.matl114.renderer.text;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

public final class SupplierDelegatingTextDrawer implements TextDrawer {
    private final Supplier<? extends TextDrawer> supplier;

    public SupplierDelegatingTextDrawer(Supplier<? extends TextDrawer> supplier) {
        this.supplier = supplier;
    }

    public TextDrawer getDelegate() {
        return supplier.get();
    }

    @Override
    public Font unwrap() {
        return getDelegate().unwrap();
    }

    @Override
    public int fontHeight() {
        return getDelegate().fontHeight();
    }

    @Override
    public float getTextHeight() {
        return getDelegate().getTextHeight();
    }

    @Override
    public String mirror(String text) {
        return getDelegate().mirror(text);
    }

    @Override
    public boolean isRightToLeft() {
        return getDelegate().isRightToLeft();
    }

    @Override
    public int getWidth(String text) {
        return getDelegate().getWidth(text);
    }

    @Override
    public int getWidth(FormattedText text) {
        return getDelegate().getWidth(text);
    }

    @Override
    public int getWidth(FormattedCharSequence text) {
        return getDelegate().getWidth(text);
    }

    @Override
    public int getWidth(Component text) {
        return getDelegate().getWidth(text);
    }

    @Override
    public String trimToWidth(String text, int maxWidth, boolean backwards) {
        return getDelegate().trimToWidth(text, maxWidth, backwards);
    }

    @Override
    public String trimToWidth(String text, int maxWidth) {
        return getDelegate().trimToWidth(text, maxWidth);
    }

    @Override
    public FormattedText trimToWidth(FormattedText text, int width) {
        return getDelegate().trimToWidth(text, width);
    }

    @Override
    public Font.PreparedText prepare(
            String text, float x, float y, int color, boolean shadow, int backgroundColor) {
        return getDelegate().prepare(text, x, y, color, shadow, backgroundColor);
    }

    @Override
    public Font.PreparedText prepare(
            FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean trackEmpty, int backgroundColor) {
        return getDelegate().prepare(text, x, y, color, shadow, trackEmpty, backgroundColor);
    }

    @Override
    public float getWrappedLinesHeight(FormattedText text, int maxWidth) {
        return getDelegate().getWrappedLinesHeight(text, maxWidth);
    }

    @Override
    public List<FormattedCharSequence> wrapLines(FormattedText text, int width) {
        return getDelegate().wrapLines(text, width);
    }

    @Override
    public StringSplitter getTextHandler() {
        return getDelegate().getTextHandler();
    }

    @Override
    public FormattedCharSequence withFont(FormattedCharSequence text) {
        return getDelegate().withFont(text);
    }
}
