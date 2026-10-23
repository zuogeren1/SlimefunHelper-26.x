package me.matl114.renderer.text;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

public class VanillaTextDrawer implements TextDrawer {
    protected final Font delegate;

    public VanillaTextDrawer() {
        this(Minecraft.getInstance().font);
    }

    public VanillaTextDrawer(Font delegate) {
        this.delegate = delegate;
    }

    @Override
    public Font unwrap() {
        return delegate;
    }

    @Override
    public int fontHeight() {
        return delegate.lineHeight;
    }

    @Override
    public String mirror(String text) {
        return delegate.bidirectionalShaping(text);
    }

    @Override
    public boolean isRightToLeft() {
        return delegate.isBidirectional();
    }

    @Override
    public int getWidth(String text) {
        return delegate.width(text);
    }

    @Override
    public int getWidth(FormattedText text) {
        return delegate.width(text);
    }

    @Override
    public int getWidth(FormattedCharSequence text) {
        return delegate.width(text);
    }

    @Override
    public String trimToWidth(String text, int maxWidth, boolean backwards) {
        return delegate.plainSubstrByWidth(text, maxWidth, backwards);
    }

    @Override
    public String trimToWidth(String text, int maxWidth) {
        return delegate.plainSubstrByWidth(text, maxWidth);
    }

    @Override
    public FormattedText trimToWidth(FormattedText text, int width) {
        return delegate.substrByWidth(text, width);
    }

    @Override
    public Font.PreparedText prepare(
            String text, float x, float y, int color, boolean shadow, int backgroundColor) {
        return delegate.prepareText(text, x, y, color, shadow, backgroundColor);
    }

    @Override
    public Font.PreparedText prepare(
            FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean trackEmpty, int backgroundColor) {
        return delegate.prepareText(text, x, y, color, shadow, trackEmpty, backgroundColor);
    }

    @Override
    public float getWrappedLinesHeight(FormattedText text, int maxWidth) {
        return delegate.wordWrapHeight(text, maxWidth);
    }

    @Override
    public List<FormattedCharSequence> wrapLines(FormattedText text, int width) {
        return delegate.split(text, width);
    }

    @Override
    public StringSplitter getTextHandler() {
        return delegate.getSplitter();
    }
}
