package me.matl114.gui;

import java.util.Iterator;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.basic.Draggable;
import me.matl114.gui.basic.DrawableWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.MustBeInvokedByOverriders;

public class GenericScreen extends Screen implements NarratableEntry, Draggable {
    protected int backgroundWidth;
    protected int backgroundDefaultHeight;
    protected int backgroundHeight;
    protected int x;
    protected int y;
    protected Component titleLabel;
    protected float currentShrink = 1.0F;
    public static final Minecraft mc = Minecraft.getInstance();

    public GenericScreen setTitleLabel(Component text) {
        this.titleLabel = text;
        return this;
    }

    public Component getTitleLabel(DrawableWidget widget) {
        return titleLabel;
    }

    public GenericScreen(Component title, int backgroundWidth, int backgroundDefaultHeight) {
        super(title);
        setTitleLabel(title);
        this.backgroundDefaultHeight = backgroundDefaultHeight;
        this.backgroundWidth = backgroundWidth;
        this.backgroundHeight = backgroundDefaultHeight;
    }

    @MustBeInvokedByOverriders
    protected void init0() {
        this.x = (this.width - this.backgroundWidth) / 2;
        if (this.height > this.backgroundDefaultHeight + 24) {
            this.backgroundHeight = this.backgroundDefaultHeight;
            this.y = (this.height - this.backgroundHeight) / 2;
        } else {
            this.y = 12;
            this.backgroundHeight = this.height - 24;
        }
    }

    protected void init() {
        super.init();
        init0();
        refreshWidgets();
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    @MustBeInvokedByOverriders
    protected void refreshWidgets() {}

    @Override
    public final boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // call for all children
        Iterator var5 = this.children().iterator();
        GuiEventListener element;
        do {
            if (!var5.hasNext()) {
                return false;
            }

            element = (GuiEventListener) var5.next();
            if (element.isMouseOver(mouseX, mouseY)
                    && element.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        } while (true);
    }

    protected Draggable draggingElement = null;
    protected int draggingButton = -1;

    protected boolean doubleClicking = false;

    @Override
    public final boolean mouseReleased(MouseButtonEvent click) {
        if (click.button() == this.draggingButton) {
            releaseDrag(this, click.x(), click.y());
            this.draggingButton = -1;
        }
        return super.mouseReleased(click);
    }

    @Override
    public final boolean mouseClicked(MouseButtonEvent click, boolean input) {
        // remove the fucking super method
        boolean val = false;
        for (GuiEventListener element : this.children()) {
            if (element.mouseClicked(click, input)) {
                this.setFocused(element);
                if (click.button() == 0) {
                    this.setDragging(true);
                }

                val = true;
                break;
            }
        }
        if (click.button() == 0 || click.button() == 1) {
            if (startDrag(this, click.x(), click.y())) {
                this.draggingButton = click.button();
            }
        }
        return val;
    }

    @Override
    public final boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return this.draggingElement != null
                && click.button() == this.draggingButton
                && this.draggingElement.mouseDragged(click, deltaX, deltaY);
    }

    public final boolean keyPressed(KeyEvent click) {
        if (super.keyPressed(click)) {
            return true;
            // we mixin the input field of these
            // it will return tru at keyPressed
        } else if (this.minecraft.options.keyInventory.matches(click)) {
            this.onClose();
            return true;
        }
        return true;
    }

    public void resetScreen() {
        // schedule refresh
        this.rebuildWidgets();
        // mc.executeSync(()->this.init(mc,mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight()));
    }

    @Override
    public void updateNarration(NarrationElementOutput builder) {}

    @Override
    public NarrationPriority narrationPriority() {
        return this.isFocused() ? NarratableEntry.NarrationPriority.FOCUSED : NarratableEntry.NarrationPriority.NONE;
    }
    // implement our shit interface for dragging
    @Override
    public void releaseDrag(Screen screen, double mouseX, double mouseY) {
        if (draggingElement != null) {
            // stop dragging here
            draggingElement.releaseDrag(this, mouseX, mouseY);
            draggingElement = null;
        }
    }

    @Override
    public boolean startDrag(Screen screen, double mouseX, double mouseY) {
        for (var iter : this.children()) {
            if (iter instanceof Draggable drag && drag.startDrag(this, mouseX, mouseY)) {
                // start drag this element
                draggingElement = drag;
                return true;
            }
        }
        return false;
    }

    public ScreenAccess access() {
        return ScreenAccess.of(this);
    }
}
