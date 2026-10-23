package me.matl114.renderer.text;

import java.util.List;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * 一个字体绘制门面。所有尺寸/换行/预排版都走它，真正的"把字画到屏幕上"由调用方
 * （{@code VDrawContext} 或 3D collector）用自己的提交管线完成。
 *
 * <p>26.2 起 {@code Font.draw*} 与 {@code MultiBufferSource} 都已删除，显卡层面的入口只剩
 * {@code Font.prepareText} 与 {@code GuiRenderState}/{@code SubmitNodeCollector}，
 * 所以本接口不再暴露 buffer source 参数，只暴露字体与样式改写能力。
 */
public interface TextDrawer {
    int fontHeight();

    /** 底层原版字体；自造字体（画在自己贴图上的）返回 null。 */
    default Font unwrap() {
        return null;
    }

    default float getTextHeight() {
        return fontHeight();
    }

    String mirror(String text);

    boolean isRightToLeft();

    int getWidth(String text);

    int getWidth(FormattedText text);

    int getWidth(FormattedCharSequence text);

    default int getWidth(Component text) {
        return getWidth(text.getVisualOrderText());
    }

    String trimToWidth(String text, int maxWidth, boolean backwards);

    String trimToWidth(String text, int maxWidth);

    FormattedText trimToWidth(FormattedText text, int width);

    Font.PreparedText prepare(String text, float x, float y, int color, boolean shadow, int backgroundColor);

    Font.PreparedText prepare(
            FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean trackEmpty, int backgroundColor);

    float getWrappedLinesHeight(FormattedText text, int maxWidth);

    List<FormattedCharSequence> wrapLines(FormattedText text, int width);

    StringSplitter getTextHandler();

    /** 把本门面的字体强制写进整段文本的样式；跟随调用方样式的实现可原样返回。 */
    default FormattedCharSequence withFont(FormattedCharSequence text) {
        return text;
    }
}
