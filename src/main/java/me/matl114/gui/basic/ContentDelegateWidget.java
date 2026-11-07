package me.matl114.gui.basic;

import me.matl114.managers.input.KeyCode;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * this class handles the delegate content's render and click behaviour, but the position and transformation is applied before the handle's
 */
public class ContentDelegateWidget<W extends GuiEventListener & Renderable & NarratableEntry> extends DrawableWidget
        implements Draggable {

    public ContentDelegateWidget(int x, int y, int dx, int dy) {
        super(x, y, dx, dy);
    }
    // you can put nms widget or sth here, not only DrawableWidget

    public W getDelegate() {
        return delegate;
    }

    private W delegate;

    public ContentDelegateWidget<W> setContentDelegate(W delegate) {
        this.delegate = delegate;
        return this;
    }

    public int height() {
        return this.getDelegate() instanceof LayoutElement widget ? widget.getHeight() : this.dy;
    }

    public int width() {
        return this.getDelegate() instanceof LayoutElement widget ? widget.getWidth() : this.dx;
    }

    @Override
    public boolean canSelect() {
        return this.getDelegate() != null
                && ((!(this.getDelegate() instanceof DrawableWidget draw)) || draw.canSelect());
    }

    public void renderInDefaultMatrix(
            VDrawContext context, int mouseX, int mouseY, float delta, boolean disableSelect) {
        super.renderInDefaultMatrix(context, mouseX, mouseY, delta, disableSelect);
        if (this.getDelegate() != null) {
            int translatedMouseX = (mouseX - this.x());

            int translatedMouseY = mouseY - this.y();
            float textureScale = getTextureScale();
            if (textureScale != 1.0f) {
                translatedMouseX = (int) (translatedMouseX / textureScale);
                translatedMouseY = (int) (translatedMouseY / textureScale);
            }
            if (this.getDelegate() instanceof DrawableWidget draw) {

                draw.render0(context, translatedMouseX, translatedMouseY, delta, disableSelect);
            } else {
                var drawContext = context.pushMatrix();
                try {
                    this.getDelegate().extractRenderState(drawContext, translatedMouseX, translatedMouseY, delta);
                } finally {
                    context.popMatrix();
                }
            }
        }
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {

        if (this.getDelegate() != null) {
            int translatedMouseX = (int) (mouseX - this.x());

            int translatedMouseY = (int) (mouseY - this.y());
            float textureScale = getTextureScale();
            if (textureScale != 1.0f) {
                translatedMouseX = (int) (translatedMouseX / textureScale);
                translatedMouseY = (int) (translatedMouseY / textureScale);
            }
            if (this.getDelegate() instanceof DrawableWidget widget) {
                if (widget.onMouseClicked(translatedMouseX, translatedMouseY, button)) {
                    return true;
                }
            } else {
                if (this.getDelegate()
                        .mouseClicked(
                                new MouseButtonEvent(
                                        translatedMouseX,
                                        translatedMouseY,
                                        new MouseButtonInfo(
                                            KeyCode.toSdlMouseButton(button), DrawableWidget.THREAD_SAFE_MODIFIER_CACHE)),
                                DrawableWidget.THREAD_SAFE_DOUBLE_CLICK)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (this.getDelegate() != null) {
            int translatedMouseX = (int) (mouseX - this.x());

            int translatedMouseY = (int) (mouseY - this.y());
            float textureScale = getTextureScale();
            if (textureScale != 1.0f) {
                translatedMouseX = (int) (translatedMouseX / textureScale);
                translatedMouseY = (int) (translatedMouseY / textureScale);
            }
            if (this.getDelegate() instanceof DrawableWidget widget) {
                if (widget.onMouseReleased(translatedMouseX, translatedMouseY, button)) {
                    return true;
                }
            } else {
                if (this.getDelegate()
                        .mouseReleased(new MouseButtonEvent(
                                translatedMouseX,
                                translatedMouseY,
                                new MouseButtonInfo(
                                            KeyCode.toSdlMouseButton(button), DrawableWidget.THREAD_SAFE_MODIFIER_CACHE)))) {
                    return true;
                }
            }
        }

        return false;
    }

    public void onMouseMoved(double mouseX, double mouseY) {
        // should not move
        if (this.getDelegate() != null) {
            int translatedMouseX = (int) (mouseX - this.x());

            int translatedMouseY = (int) (mouseY - this.y());
            float textureScale = getTextureScale();
            if (textureScale != 1.0f) {
                translatedMouseX = (int) (translatedMouseX / textureScale);
                translatedMouseY = (int) (translatedMouseY / textureScale);
            }
            if (this.getDelegate() instanceof DrawableWidget drawableWidget) {
                drawableWidget.onMouseMoved(translatedMouseX, translatedMouseY);
            } else {
                this.getDelegate().mouseMoved(translatedMouseX, translatedMouseY);
            }
        }
    }

    public boolean onMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        // should not drag
        if (this.getDelegate() != null) {
            int translatedMouseX = (int) (mouseX - this.x());

            int translatedMouseY = (int) (mouseY - this.y());
            float textureScale = getTextureScale();
            if (textureScale != 1.0f) {
                translatedMouseX = (int) (translatedMouseX / textureScale);
                translatedMouseY = (int) (translatedMouseY / textureScale);
            }
            if (this.getDelegate() instanceof DrawableWidget widget) {
                if (widget.onMouseDragged(
                        translatedMouseX, translatedMouseY, button, deltaX * textureScale, deltaY * textureScale)) {
                    return true;
                }
            } else {
                if (this.getDelegate()
                        .mouseDragged(
                                new MouseButtonEvent(
                                        translatedMouseX,
                                        translatedMouseY,
                                        new MouseButtonInfo(
                                            KeyCode.toSdlMouseButton(button), DrawableWidget.THREAD_SAFE_MODIFIER_CACHE)),
                                deltaX * textureScale,
                                deltaY * textureScale)) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // should not scrolled
        if (this.getDelegate() != null) {
            int translatedMouseX = (int) (mouseX - this.x());

            int translatedMouseY = (int) (mouseY - this.y());
            float textureScale = getTextureScale();
            if (textureScale != 1.0f) {
                translatedMouseX = (int) (translatedMouseX / textureScale);
                translatedMouseY = (int) (translatedMouseY / textureScale);
            }
            if (this.getDelegate() instanceof DrawableWidget drawable) {
                return drawable.onMouseScrolled(translatedMouseX, translatedMouseY, horizontalAmount, verticalAmount);
            } else {
                return this.getDelegate()
                        .mouseScrolled(translatedMouseX, translatedMouseY, horizontalAmount, verticalAmount);
            }
        }
        return false;
    }

    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.getDelegate() instanceof DrawableWidget drawable) {
            return drawable.onKeyPressed(keyCode, scanCode, modifiers);
        } else
            return this.getDelegate() != null
                    && this.getDelegate().keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
    }

    public boolean onKeyReleased(int keyCode, int scanCode, int modifiers) {
        if (this.getDelegate() instanceof DrawableWidget drawable) {
            return drawable.onKeyReleased(keyCode, scanCode, modifiers);
        } else
            return this.getDelegate() != null
                    && this.getDelegate().keyReleased(new KeyEvent(keyCode, scanCode, modifiers));
    }

    @Override
    public boolean onCharTyped(char chr, int modifiers) {
        if (this.getDelegate() instanceof DrawableWidget drawable) {
            return drawable.onCharTyped(chr, modifiers);
        } else return this.getDelegate() != null && this.getDelegate().charTyped(new CharacterEvent(chr));
    }

    @Override
    public boolean isOver(double mouseX, double mouseY) {
        float textureScale = getTextureScale();
        return this.getDelegate() != null
                && this.getDelegate()
                        .isMouseOver((mouseX - this.x()) / textureScale, (mouseY - this.y()) / textureScale);
    }

    @Override
    public boolean isSelected() {
        return this.getDelegate() != null && this.getDelegate().isFocused();
    }

    public void setSelected(boolean focused) {
        if (this.getDelegate() != null) this.getDelegate().setFocused(focused);
    }

    public NarrationPriority narrationPriority() {
        return this.getDelegate() == null
                ? NarrationPriority.NONE
                : this.getDelegate().narrationPriority();
    }

    @Override
    public boolean isDragging() {
        return this.getDelegate() != null
                && this.getDelegate() instanceof Draggable draggable
                && draggable.isDragging();
    }

    @Override
    public void releaseDrag(Screen screen, double mouseX, double mouseY) {
        if (getDelegate() instanceof Draggable draggable) {
            float textureScale = getTextureScale();
            draggable.releaseDrag(screen, (mouseX - this.x()) / textureScale, (mouseY - this.y()) / textureScale);
        }
    }

    @Override
    public boolean startDrag(Screen screen, double mouseX, double mouseY) {
        if (getDelegate() instanceof Draggable draggable) {
            float textureScale = getTextureScale();
            return draggable.startDrag(screen, (mouseX - this.x()) / textureScale, (mouseY - this.y()) / textureScale);
        }
        return false;
    }
}
