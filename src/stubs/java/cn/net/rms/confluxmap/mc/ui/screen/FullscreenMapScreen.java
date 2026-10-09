package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.task.SessionGuard;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * conflux-map 的全屏地图界面（编译期桩，不参与打包）。
 * 真实 jar 中为 public final class FullscreenMapScreen extends ConfluxScreen，
 * 下面这些成员在 0.1.7 与 0.1.9（26.2 / 26.1.2）里都在，mixin 依赖它们。
 *
 * <p>这里声明成 abstract 只是为了让这个桩不必再实现 ConfluxScreen 的抽象方法，
 * 不影响 mixin 的 @Shadow —— 影子的成员是真类上的，编译期只要求名字/描述符能对上。
 *
 * <p><b>桩里的类型必须与真类一致</b>（javap 实测 0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2 三份 jar 相同）：
 * locationMenuBounds 是 FullscreenMapLocationMenu$Bounds、locationMenuTarget 是 ...$Target、
 * viewSession() 返回 SessionGuard$Session。桩里写个宽松类型（例如把字段写成 Object）
 * 会诱导出描述符对不上的 @Shadow —— javac 查不出来，mixin apply 阶段才炸。
 * 本模块用不到 locationMenuWaypoint（真类型是 core.waypoint.WaypointRenderEntry）与
 * locationMenuPlayerId，所以这里不声明它们。
 */
public abstract class FullscreenMapScreen extends ConfluxScreen {
    private FullscreenMapLocationMenu.Bounds locationMenuBounds;
    private FullscreenMapLocationMenu.Target locationMenuTarget;

    public FullscreenMapScreen() {
        super(null);
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    public boolean keyPressed(KeyEvent event) {
        return false;
    }

    private SessionGuard.Session viewSession() {
        return null;
    }

    private void openLocationMenu(double mouseX, double mouseY) {}

    private void dismissLocationMenu() {}
}
