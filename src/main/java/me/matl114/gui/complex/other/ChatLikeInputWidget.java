package me.matl114.gui.complex.other;

import java.util.function.Consumer;
import me.matl114.gui.basic.RenderHandler;
import me.matl114.managers.input.KeyCode;
import me.matl114.utils.ClientUtils;
import me.matl114.utils.config.ValueAccessor;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.minecraft.util.Mth;

public class ChatLikeInputWidget extends EditBox {
    Consumer<String> callback;
    int messageHistoryIndex;
    String chatLastMessage = "";
    ValueAccessor<Integer> xValue;
    ValueAccessor<Integer> yValue;

    public ChatLikeInputWidget(
            Font textRenderer,
            ValueAccessor<Integer> xv,
            ValueAccessor<Integer> yv,
            int width,
            int height,
            Consumer<String> enterCallback) {
        super(textRenderer, xv.getValue(), yv.getValue(), width, height, Component.empty());
        this.xValue = xv;
        this.yValue = yv;
        this.callback = enterCallback;
        this.setBordered(false);
    }

    // ⚠ 必须走 super：父类构造链会在 this.xValue = xv 之前虚调用 getX()，
    // 那时 xValue 还是 null。写成 return getX() 会自递归 -> StackOverflowError。
    @Override
    public int getX() {
        if (xValue == null) {
            return super.getX();
        }
        return xValue.getValue();
    }

    @Override
    public int getY() {
        if (yValue == null) {
            return super.getY();
        }
        return yValue.getValue();
    }

    private static final Minecraft mc = Minecraft.getInstance();

    public void setChatFromHistory(int offset) {
        int i = this.messageHistoryIndex + offset;
        int j = ClientUtils.getChat(mc).getRecentChat().size();
        i = Mth.clamp(i, 0, j);
        if (i != this.messageHistoryIndex) {
            if (i == j) {
                this.messageHistoryIndex = j;
                setValue(this.chatLastMessage);
            } else {
                if (this.messageHistoryIndex == j) {
                    // save temp message
                    this.chatLastMessage = getValue();
                }

                setValue((String) ClientUtils.getChat(mc).getRecentChat().get(i));
                // this.chatInputSuggestor.setWindowActive(false);
                this.messageHistoryIndex = i;
            }
        }
    }

    public boolean keyPressed(KeyEvent input) {
//#if MC >= 260300
//$$         return super.keyPressed(input) || keyPressed(input.key(), input.keycode(), input.modifiers());
//#else
        return super.keyPressed(input) || keyPressed(input.key(), input.scancode(), input.modifiers());
//#endif
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {

        if (this.isFocused()) {
            if (keyCode != KeyCode.KEY_ENTER && keyCode != KeyCode.KEY_KP_ENTER) {
                if (keyCode == KeyCode.KEY_UP) {
                    this.setChatFromHistory(-1);
                    return true;
                } else if (keyCode == KeyCode.KEY_DOWN) {
                    this.setChatFromHistory(1);
                    return true;
                } else {
                    return false;
                }
            } else {
                this.onAcceptCallback(getValue());
                setValue("");
                return true;
            }
        } else {
            if (keyCode == KeyCode.KEY_UP) {
                this.setFocused(true);
                return true;
            }
            return false;
        }
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        context.fill(
                this.getX(),
                this.getY() - 2,
                this.getX() + width,
                this.getY() + height - 2,
                mc.options.getBackgroundColor(Integer.MIN_VALUE));
        RenderHandler.drawHighlightFrame(
                VDrawContext.of(context),
                this.getX() - 1,
                this.getY() - 3,
                width + 2,
                height + 2,
                this.isFocused() ? CommonColors.WHITE : CommonColors.GRAY);
        super.extractWidgetRenderState(context, mouseX, mouseY, deltaTicks);
    }

    public void onAcceptCallback(String value) {
        if (callback != null) {
            callback.accept(value);
        }
        // "send" action may trigger
        resetHistoryIndex();
        // avoid focus failure
        //        setFocused(false);
    }

    public void resetHistoryIndex() {
        this.messageHistoryIndex = ClientUtils.getChat(mc).getRecentChat().size();
    }
}
