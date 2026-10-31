package me.matl114.renderer.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.matl114.utils.Debug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;

/**
 * 把一段文本整体的字体替换成指定资源字体后再测量/排版。
 *
 * <p>原版 {@code Font#width}/{@code split} 只看样式里的字体描述，所以这里给每个
 * 字符的样式都塞上 {@link FontDescription.Resource}，再交给同一套 {@link Font} 处理。
 */
public class ResourceFontTextDrawer extends VanillaTextDrawer {
    private static final Set<Identifier> WARNED_IDS = ConcurrentHashMap.newKeySet();
    private final Identifier fontId;
    private final Style fontStyle;
    private final boolean available;

    public ResourceFontTextDrawer(Identifier fontId) {
        this(Minecraft.getInstance().font, fontId);
    }

    public ResourceFontTextDrawer(Font delegate, Identifier fontId) {
        this(delegate, fontId, fontExists(fontId));
    }

    /**
     * @param available 调用方已经自己把这份字体装好了（例如本地 ttf 的 FontSet），跳过资源存在性检查。
     */
    protected ResourceFontTextDrawer(Font delegate, Identifier fontId, boolean available) {
        super(delegate);
        this.fontId = fontId;
        // 26.2: FontManager 对不认识的字体 id 直接回落到 minecraft:missing（每个字符都是空心方块），
        // 所以默认构造器要先确认资源真的存在；不存在就按原版字体绘制——
        // 宁可丢自定义字体，也不能整屏不可读。
        this.available = available;
        this.fontStyle = available ? Style.EMPTY.withFont(new FontDescription.Resource(fontId)) : Style.EMPTY;
        if (!available && WARNED_IDS.add(fontId)) {
            Debug.info("Font " + fontId + " is not loaded, falling back to the vanilla font");
        }
    }

    /** 字体资源是否存在；不存在时本门面的绘制与测量全部退回原版字体。 */
    public boolean isAvailable() {
        return available;
    }

    private static boolean fontExists(Identifier id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        ResourceManager manager = minecraft.getResourceManager();
        if (manager == null) {
            return false;
        }
        return manager.getResource(Identifier.fromNamespaceAndPath(id.getNamespace(), "font/" + id.getPath() + ".json"))
                .isPresent();
    }

    public Identifier fontId() {
        return fontId;
    }

    @Override
    public float getTextHeight() {
        return fontHeight();
    }

    public float getTextYOffset() {
        return getTextHeight() - fontHeight();
    }

    @Override
    public int getWidth(String text) {
        return text == null ? 0 : (int) Math.ceil(delegate.getSplitter().stringWidth(formattedString(text, false)));
    }

    @Override
    public int getWidth(FormattedText text) {
        return (int) Math.ceil(delegate.getSplitter().stringWidth(withFont(text)));
    }

    @Override
    public int getWidth(FormattedCharSequence text) {
        return (int) Math.ceil(delegate.getSplitter().stringWidth(withFont(text)));
    }

    @Override
    public String trimToWidth(String text, int maxWidth, boolean backwards) {
        return backwards
                ? delegate.getSplitter().plainTailByWidth(text, maxWidth, fontStyle)
                : delegate.getSplitter().plainHeadByWidth(text, maxWidth, fontStyle);
    }

    @Override
    public String trimToWidth(String text, int maxWidth) {
        return delegate.getSplitter().plainHeadByWidth(text, maxWidth, fontStyle);
    }

    @Override
    public FormattedText trimToWidth(FormattedText text, int width) {
        return delegate.getSplitter().headByWidth(withFont(text), width, fontStyle);
    }

    @Override
    public Font.PreparedText prepare(
            String text, float x, float y, int color, boolean shadow, int backgroundColor) {
        if (text == null) {
            return null;
        }
        return delegate.prepareText(formattedString(text, true), x, y, color, shadow, false, backgroundColor);
    }

    @Override
    public Font.PreparedText prepare(
            FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean trackEmpty, int backgroundColor) {
        return delegate.prepareText(withFont(text), x, y, color, shadow, trackEmpty, backgroundColor);
    }

    @Override
    public float getWrappedLinesHeight(FormattedText text, int maxWidth) {
        return getTextHeight() * wrapLines(text, maxWidth).size();
    }

    @Override
    public List<FormattedCharSequence> wrapLines(FormattedText text, int width) {
        return Language.getInstance().getVisualOrder(delegate.getSplitter().splitLines(withFont(text), width, fontStyle));
    }

    @Override
    public StringSplitter getTextHandler() {
        return new StringSplitter((codePoint, style) ->
                delegate.getSplitter().stringWidth(FormattedCharSequence.codepoint(codePoint, withFont(style))));
    }

    @Override
    public FormattedCharSequence withFont(FormattedCharSequence text) {
        return sink -> text.accept((index, style, codePoint) -> sink.accept(index, withFont(style), codePoint));
    }

    public FormattedCharSequence formattedString(String text, boolean mirror) {
        String renderedText = mirror && delegate.isBidirectional() ? delegate.bidirectionalShaping(text) : text;
        List<FormattedCharacter> characters = new ArrayList<>();
        StringDecomposer.iterateFormatted(renderedText, fontStyle, (index, style, codePoint) -> {
            characters.add(new FormattedCharacter(index, withFont(style), codePoint));
            return true;
        });
        return sink -> {
            for (FormattedCharacter character : characters) {
                if (!sink.accept(character.index(), character.style(), character.codePoint())) {
                    return false;
                }
            }
            return true;
        };
    }

    /** 把整段富文本换成"本门面的字体 + 原文其余样式"。 */
    public FormattedText withFont(FormattedText text) {
        List<FormattedText> parts = new ArrayList<>();
        text.visit(
                (style, string) -> {
                    parts.add(FormattedText.of(string, withFont(style)));
                    return Optional.empty();
                },
                fontStyle);
        return FormattedText.composite(parts);
    }

    public FormattedCharSequence withFont(Component text) {
        return withFont(text.copy().withStyle(fontStyle).getVisualOrderText());
    }

    private Style withFont(Style style) {
        return available ? style.withFont(new FontDescription.Resource(fontId)) : style;
    }

    private record FormattedCharacter(int index, Style style, int codePoint) {}
}
