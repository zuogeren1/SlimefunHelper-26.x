package me.matl114.gui.presets.choices;

import me.matl114.gui.GenericBackGroundScreen;
import me.matl114.gui.basic.*;
import me.matl114.gui.elements.ButtonElement;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public abstract class ConfirmingBigScreen extends GenericBackGroundScreen {
    protected ConfirmingBigScreen(Component title) {
        super(title, 480, 360);
    }

    protected ExecutableWidget cancelButtonWidget;
    protected ExecutableWidget confirmButtonWidget;
    protected static int CONTENT_START_Y = 40;
    protected int content_end_y;

    protected void onCloseButton() {
        this.onClose();
    }

    protected abstract boolean canConfirm(ElementHandler elementHandler);

    protected abstract void onConfirmButton();

    private static final Component CANCEL =
            Component.translatable("widget.gui.confirming-big-screen.cancel").withStyle(ChatFormatting.RED);
    private static final Component CONFIRM =
            Component.translatable("widget.gui.confirming-big-screen.confirm").withStyle(ChatFormatting.GREEN);

    public int getContentHeight() {
        return content_end_y - CONTENT_START_Y;
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        this.content_end_y = this.backgroundHeight - 30;
        // 按钮大小 80, 20
        // 放在240 - 90 = 150
        this.cancelButtonWidget = ExecutableWidget.instance(this.x + 150, this.y + this.content_end_y + 5, 80, 20)
                .setElementHandler(new ButtonElement(TextProvider.of(CANCEL), ButtonAction.run(this::onCloseButton)))
                .addTo(this);
        this.confirmButtonWidget = ExecutableWidget.instance(this.x + 250, this.y + this.content_end_y + 5, 80, 20)
                .setElementHandler(new ButtonElement(TextProvider.of(CONFIRM), ButtonAction.run(this::onConfirmButton))
                        .withActiveActionCondition(this::canConfirm))
                .addTo(this);
    }
}
