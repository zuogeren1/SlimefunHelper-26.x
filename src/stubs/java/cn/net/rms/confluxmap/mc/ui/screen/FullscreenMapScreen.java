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

    /**
     * 全屏地图视口的三个几何字段。javap 实测（0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2 三份 jar 一致）：
     * {@code private double centerX;} / {@code private double centerZ;} / {@code private double scale;}
     * 都是<b>声明在本类自己身上</b>的实例字段（不是从 Screen 继承的），我们的 mixin 直接 @Shadow 它们。
     * 投影式（他们自己的代码里到处都是）：{@code screenX = width / 2.0 + (worldX - centerX) / scale}。
     */
    private double centerX;
    private double centerZ;
    private double scale;

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

    /**
     * 视口是不是「当前存档 + 当前维度」的实时会话。
     * 真类里的实现是比较 {@code viewSession()} 与 {@code gameBridge.session()} 的 world/dimension；
     * 三份 jar 里签名都是 {@code ()Z}，我们的地图覆盖层靠它决定画不画。
     */
    private boolean viewingLiveSession() {
        return false;
    }

    protected void renderContents(cn.net.rms.confluxmap.mc.ui.GuiDraw draw, int mouseX, int mouseY, float tickDelta) {}

    /**
     * 真类里由 {@code FullscreenMapScreen} 覆写（声明在基类 {@code ConfluxScreen} 上），
     * javap 实测 0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2 三份 jar 都有、签名一致。
     * 他们的热键提示（0.1.9 的 {@code drawHotkeyHints}）就画在这里。
     */
    protected void renderAfterWidgets(
            cn.net.rms.confluxmap.mc.ui.GuiDraw draw, int mouseX, int mouseY, float tickDelta) {}

    /**
     * <b>0.1.9 独有</b>（0.1.7 没有，javap 实测三份 jar）：嵌入模式里由 {@code ConfluxScreen.keyPressed}
     * 直接调用的那条热键捷径。我们的 mixin 在它上面挂的是 {@code require = 0} 的 HEAD 注入 ——
     * 这个桩只是让签名有据可查，<b>不要</b>因为它存在就写成 require = 1 或 @Shadow。
     */
    protected boolean embeddedMenuHotkeyPressed(int keyCode) {
        return false;
    }

    /**
     * 真签名（javap 实测 0.1.7-26.2 与 0.1.9-26.2 / 0.1.9-26.1.2 三份 jar 一致）：
     * {@code java.util.List<FullscreenMapLocationMenu$ButtonSpec> locationMenuButtonSpecs(
     * FullscreenMapLocationMenu$Target, WaypointRenderEntry, java.util.UUID, boolean)}。
     *
     * <p>这里返回 raw {@code java.util.List}：{@code ButtonSpec} 是**包级私有**类型，
     * 签名里点名它 javac 会直接报错；泛型在字节码里本来就被擦成 {@code Ljava/util/List;}，
     * 所以 raw 签名与真方法描述符完全一致（mixin 的 @Shadow 也按这个描述符匹配）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private java.util.List locationMenuButtonSpecs(
            FullscreenMapLocationMenu.Target target,
            cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry waypoint,
            java.util.UUID playerId,
            boolean deletePending) {
        return null;
    }

    private void openLocationMenu(double mouseX, double mouseY) {}

    private void dismissLocationMenu() {}

    /**
     * 他们画右键位置菜单面板的那一句。javap 实测三份 jar 都是
     * {@code private void drawLocationMenu(cn.net.rms.confluxmap.mc.ui.GuiDraw)}，
     * 而且是 {@code renderContents} 的<b>最后一句</b>（菜单没打开时也照样调用）——
     * 我们的地图覆盖层就注入在它的 HEAD（地图内容之后、原版控件之前）。
     */
    private void drawLocationMenu(cn.net.rms.confluxmap.mc.ui.GuiDraw draw) {}
}
