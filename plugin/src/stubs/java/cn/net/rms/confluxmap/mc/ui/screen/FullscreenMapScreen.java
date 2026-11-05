package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.task.SessionGuard;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * conflux-map 的全屏地图界面（编译期桩，不参与打包）。
 *
 * <p>真实 jar 中为 {@code public final class FullscreenMapScreen extends ConfluxScreen}。
 * 0.1.16 起右键位置菜单的<b>状态</b>搬进了 {@code MapLocationMenuController}（包级私有），屏幕只留下
 * {@code final MapLocationMenuController locationMenu} 这一个字段；那批成员（{@code locationMenuBounds} /
 * {@code locationMenuTarget} / {@code dismissLocationMenu()} / {@code drawLocationMenu(...)}）都在控制器上。
 * 我们的 mixin 因此不写 {@code @Shadow}，状态一律经 {@code ConfluxStateAccess} 反射取。
 *
 * <p>这里只声明本模块用得上的类型级事实：{@code renderContents} 由基类抽象、{@code renderAfterWidgets}
 * 由本类覆写，两者的签名决定两条注入挂在哪儿。其余成员（{@code mouseClicked} / {@code keyPressed} /
 * {@code embeddedMenuHotkeyPressed} / {@code locationMenuButtonSpecs} / {@code viewSession} /
 * {@code centerX,centerZ,scale}）在注入选择器与反射里按名字出现过，桩里不重复声明，避免写出
 * 与真类不一致的宽松类型。
 */
public abstract class FullscreenMapScreen extends ConfluxScreen {
    protected FullscreenMapScreen() {
        super(null);
    }

    protected void renderContents(cn.net.rms.confluxmap.mc.ui.GuiDraw draw, int mouseX, int mouseY, float tickDelta) {}

    protected void renderAfterWidgets(
            cn.net.rms.confluxmap.mc.ui.GuiDraw draw, int mouseX, int mouseY, float tickDelta) {}

    /** 视口是不是「当前存档 + 当前维度」的实时会话（反射调用，签名 {@code ()Z}） */
    private boolean viewingLiveSession() {
        return false;
    }

    /** 当前视口的会话（反射调用，签名 {@code ()Lcn/net/rms/confluxmap/core/task/SessionGuard$Session;}） */
    private SessionGuard.Session viewSession() {
        return null;
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    public boolean keyPressed(KeyEvent event) {
        return false;
    }
}
