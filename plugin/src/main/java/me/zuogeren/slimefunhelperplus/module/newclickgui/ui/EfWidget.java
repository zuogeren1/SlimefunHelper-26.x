package me.zuogeren.slimefunhelperplus.module.newclickgui.ui;

import me.matl114.gui.basic.DrawableWidget;
import me.matl114.versioned.api.VDrawContext;

/**
 * 一块自己画自己的画布。
 *
 * <p>宿主把 {@link DrawableWidget} 的矩阵先平移到元素原点再调 {@code renderInDefaultMatrix}，
 * 所以这里画的东西一律用局部坐标 (0,0)-(dx,dy)；悬停判定仍用 {@link #isOver(double, double)} 的屏幕坐标。
 *
 * <p>不继承宿主的 ElementHandler 体系，是因为新界面要的形状（切角、细线、等宽数字）宿主那套没有，
 * 硬接反而两边都不像。
 */
public class EfWidget extends DrawableWidget {
    public interface Painter {
        void paint(EfWidget self, VDrawContext ctx, int mouseX, int mouseY);
    }

    public interface Clicker {
        boolean onClick(EfWidget self, double mouseX, double mouseY, int button);
    }

    public interface Scroller {
        boolean onScroll(EfWidget self, double mouseX, double mouseY, double amount);
    }

    public interface Dragger {
        boolean onDrag(EfWidget self, double mouseX, double mouseY, int button, double deltaX, double deltaY);
    }

    public interface Keys {
        boolean onKey(EfWidget self, int keyCode, int scanCode, int modifiers);
    }

    private final Painter painter;
    private Clicker clicker;
    private Scroller scroller;
    private Keys keyHandler;
    private Dragger dragger;
    private java.util.function.BooleanSupplier dragGate;

    public EfWidget(int x, int y, int dx, int dy, Painter painter) {
        super(x, y, dx, dy);
        this.painter = painter;
    }

    public static EfWidget of(int x, int y, int dx, int dy, Painter painter) {
        return new EfWidget(x, y, dx, dy, painter);
    }

    public EfWidget click(Clicker clicker) {
        this.clicker = clicker;
        return this;
    }

    public EfWidget scroll(Scroller scroller) {
        this.scroller = scroller;
        return this;
    }

    public EfWidget drag(Dragger dragger, java.util.function.BooleanSupplier gate) {
        this.dragger = dragger;
        this.dragGate = gate;
        return this;
    }

    /**
     * 只有这一次点击真的落在可拖动的东西上才接管拖动。
     * 铺满屏幕的控件如果无条件接管，会抢掉底下宿主控件的拖动。
     */
    @Override
    public boolean startDrag(net.minecraft.client.gui.screens.Screen screen, double mouseX, double mouseY) {
        return dragger != null && dragGate != null && dragGate.getAsBoolean();
    }

    public EfWidget keys(Keys keys) {
        this.keyHandler = keys;
        return this;
    }

    /** 不在宿主元素体系里，别让容器把它当成可悬停选中的元素去抢焦点。 */
    @Override
    public boolean canSelect() {
        return false;
    }

    @Override
    public void renderInDefaultMatrix(
            VDrawContext context, int mouseX, int mouseY, float delta, boolean disableSelect) {
        if (painter != null) {
            painter.paint(this, context, mouseX, mouseY);
        }
    }

    /**
     * 宿主的 GenericScreen 会把点击派发给所有子控件、不做坐标预判，
     * 所以边界检查必须在这里做一次：不在自己框里的点击一律不接。
     */
    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!isOver(mouseX, mouseY)) {
            return false;
        }
        return clicker != null && clicker.onClick(this, mouseX, mouseY, button);
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return dragger != null && dragger.onDrag(this, mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!isOver(mouseX, mouseY)) {
            return false;
        }
        return scroller != null && scroller.onScroll(this, mouseX, mouseY, verticalAmount);
    }

    @Override
    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        return keyHandler != null && keyHandler.onKey(this, keyCode, scanCode, modifiers);
    }
}
