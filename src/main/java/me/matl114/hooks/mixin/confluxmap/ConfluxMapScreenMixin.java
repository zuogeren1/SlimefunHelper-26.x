package me.matl114.hooks.mixin.confluxmap;

import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapLocationMenu;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import me.matl114.hooks.ConfluxMapHooks;
import me.matl114.hooks.impl.confluxmap.ConfluxMenuContext;
import me.matl114.hooks.impl.confluxmap.ConfluxMenuOverlay;
import me.matl114.hooks.impl.confluxmap.ConfluxMenuTarget;
import me.matl114.utils.Debug;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把 Xaero 世界地图那套“右键自定义指令”等价地搬到 conflux-map 的全屏地图右键位置菜单上。
 *
 * <p>全程只读他们的状态 + 自绘一块面板，不改他们任何代码：
 * <ul>
 *   <li>{@code renderContents} 的 {@code RETURN} 正好落在他们 {@code drawLocationMenu(draw)} 之后，
 *       所以在那里追加自己的面板就画在他们面板之上；</li>
 *   <li>{@code locationMenuBounds}/{@code locationMenuTarget} 是 private，用 {@code @Shadow} 影子字段拿到；</li>
 *   <li>{@code FullscreenMapLocationMenu$Target} 是**包级私有**类型，它的 {@code blockY()}
 *       也是包私有，从本包外不能直接调用，所以用反射读，读不到就走兜底 Y。</li>
 * </ul>
 *
 * <p>三个注入体内部一律 {@code try/catch (Throwable)}：我们出问题最多不显示这块面板，
 * 绝不允许影响到他们的地图界面。
 */
/*
 * 隐式契约（动这块之前先读完，别再往包外搬）：
 * 本 mixin 里对 FullscreenMapLocationMenu 及其包级私有嵌套类型 Bounds / Target 的**任何**调用，
 * 都只能留在这个文件内。javap 实测（0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2 三份 jar 一致）：
 *   final class FullscreenMapLocationMenu$Bounds extends java.lang.Record   // 包级私有
 *   final class FullscreenMapLocationMenu$Target extends java.lang.Record   // 包级私有
 *   Bounds: public x()/y()/width()/height()，包私有 buttonX()/buttonY(int)/buttonWidth()/contains(double,double)
 *   Target: public blockX()/blockZ()，包私有 blockY()（返回 OptionalInt，0.1.7 与 0.1.9 都在）
 * 只有本 mixin 被合并进目标类（cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen）之后，
 * 我们才以「同包」身份拿到这些包级私有类型/成员的访问权；一旦把这段逻辑抽到 me.matl114.* 的普通类里，
 * 字节码里就出现跨包访问包级私有类型/成员，运行期立刻 IllegalAccessError（验收方已用包外探针实测复现）。
 * 需要复用时只能往外传 ConfluxMenuTarget 这种脱敏后的自有类型。
 */
@Pseudo
@Environment(EnvType.CLIENT)
@Mixin(FullscreenMapScreen.class)
public abstract class ConfluxMapScreenMixin {
    @Shadow
    private FullscreenMapLocationMenu.Bounds locationMenuBounds;

    @Shadow
    private FullscreenMapLocationMenu.Target locationMenuTarget;

    @Shadow
    private SessionGuard.Session viewSession() {
        throw new AssertionError();
    }

    /**
     * 我们自己的运行态：条目、面板矩形、面板当前指向的目标。
     *
     * <p>注意：这个 @Unique 实例字段<b>不能</b>依赖声明处初始化器 —— 验收实测中它曾经是 null，
     * reset() 里第一次 clear() 就是 NPE（26.2 渲染期直接崩地图界面、面板也永远不显示）。
     * 所以这里不写初始化器，所有读写统一走下面的懒初始化访问器。
     */
    @Unique
    private List<ConfluxMenuContext> slimefunhelper$locationMenuOptions;

    @Unique
    private Rectangle slimefunhelper$locationMenuPanel;

    @Unique
    private ConfluxMenuTarget slimefunhelper$locationMenuClickTarget;

    /** Target#blockY 的缓存（反射，跨包不可直接调用） */
    @Unique
    private static Method slimefunhelper$BLOCK_Y;

    /** 异常只记一次日志，避免每帧刷屏 */
    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean slimefunhelper$warned =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Unique
    private static void slimefunhelper$warnOnce(String stage, Throwable e) {
        if (slimefunhelper$warned.compareAndSet(false, true)) {
            Debug.getLogger().warn("ConfluxMapHelper failed to " + stage + ", this panel is disabled until restart", e);
        }
    }

    @Inject(method = "renderContents", at = @At("RETURN"), require = 1)
    private void slimefunhelper$onRenderLocationMenu(
            GuiDraw draw, int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
        try {
            if (this.locationMenuBounds == null || draw == null) {
                slimefunhelper$resetLocationMenu();
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            Rectangle panel = slimefunhelper$collectLocationMenu(mc);
            if (panel == null) {
                slimefunhelper$resetLocationMenu();
                return;
            }
            this.slimefunhelper$locationMenuPanel = panel;
            ConfluxMenuOverlay.drawPanel(panel, draw::fill);
            if (mc != null && mc.font != null) {
                ConfluxMenuOverlay.drawEntries(
                        panel,
                        slimefunhelper$locationMenuOptions(),
                        mc.font,
                        (text, x, y, color) -> draw.drawTextWithShadow(mc.font, text, x, y, color));
            }
        } catch (Throwable e) {
            slimefunhelper$resetLocationMenu();
            slimefunhelper$warnOnce("render location menu", e);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 1)
    private void slimefunhelper$onLocationMenuMouseClicked(
            MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (this.slimefunhelper$locationMenuPanel == null || event == null || event.button() != 0) {
                return;
            }
            int index =
                    slimefunhelper$locationMenuIndexAt(event.x(), event.y());
            if (index < 0) {
                return;
            }
            slimefunhelper$runLocationMenuAction(index);
            // 必须在这里拦下：他们下一句就是“点在面板外就 dismiss”
            cir.setReturnValue(true);
        } catch (Throwable e) {
            slimefunhelper$resetLocationMenu();
            slimefunhelper$warnOnce("handle location menu click", e);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 1)
    private void slimefunhelper$onLocationMenuKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (this.slimefunhelper$locationMenuPanel == null || event == null) {
                return;
            }
            int index = ConfluxMenuOverlay.hotkeyIndex(event.key());
            if (index < 0 || index >= slimefunhelper$locationMenuOptionCount()) {
                return;
            }
            slimefunhelper$runLocationMenuAction(index);
            cir.setReturnValue(true);
        } catch (Throwable e) {
            slimefunhelper$resetLocationMenu();
            slimefunhelper$warnOnce("handle location menu hotkey", e);
        }
    }

    /**
     * 收集条目并算出面板矩形，返回 null 表示这次不该显示面板。
     * 只在 renderContents 的 RETURN 处调用（渲染线程），不涉及并发。
     */
    @Unique
    private Rectangle slimefunhelper$collectLocationMenu(Minecraft mc) {
        if (mc == null || mc.getWindow() == null) {
            return null;
        }
        ConfluxMenuTarget target = slimefunhelper$currentLocationTarget();
        if (target == null) {
            return null;
        }
        ArrayList<ConfluxMenuContext> options = new ArrayList<>();
        try {
            ConfluxMapHooks.getLocationMenuOption().broadcast(options, target);
        } catch (Throwable e) {
            slimefunhelper$warnOnce("collect location menu options", e);
            return null;
        }
        if (options.isEmpty()) {
            this.slimefunhelper$locationMenuClickTarget = null;
            return null;
        }
        this.slimefunhelper$locationMenuClickTarget = target;
        slimefunhelper$syncLocationMenuOptions(options);
        FullscreenMapLocationMenu.Bounds bounds = this.locationMenuBounds;
        // 屏幕尺寸直接用窗口的 GUI 缩放尺寸，等价于 Screen.width/height，但不依赖跨类影子
        Rectangle panel = ConfluxMenuOverlay.computePanel(
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight(),
                bounds.x(),
                bounds.y(),
                bounds.height(),
                options.size());
        if (panel == null) {
            this.slimefunhelper$locationMenuClickTarget = null;
        }
        return panel;
    }

    /** 把 conflux 侧的类型全部收在这个方法里，外面只见我们自己的类型 */
    @Unique
    private ConfluxMenuTarget slimefunhelper$currentLocationTarget() {
        FullscreenMapLocationMenu.Target target = this.locationMenuTarget;
        if (target == null) {
            return null;
        }
        String dimensionId = "minecraft:overworld";
        String worldPath = "overworld";
        try {
            SessionGuard.Session session = this.viewSession();
            if (session != null && session.dimension() != null) {
                dimensionId = session.dimension().namespace() + ":" + session.dimension().path();
                worldPath = session.dimension().path();
            }
        } catch (Throwable e) {
            slimefunhelper$warnOnce("read conflux session dimension", e);
        }
        int x = target.blockX();
        int z = target.blockZ();
        return new ConfluxMenuTarget(dimensionId, worldPath, x, slimefunhelper$resolveTargetY(target), z);
    }

    /** 目标 Y = 地面 + 1；拿不到就用玩家当前 Y，再不行退回他们面板的 y */
    @Unique
    private int slimefunhelper$resolveTargetY(FullscreenMapLocationMenu.Target target) {
        try {
            OptionalInt groundY = slimefunhelper$invokeBlockY(target);
            if (groundY != null && groundY.isPresent()) {
                return groundY.getAsInt();
            }
        } catch (Throwable ignored) {
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                return (int) Math.floor(mc.player.getY());
            }
        } catch (Throwable ignored) {
        }
        FullscreenMapLocationMenu.Bounds bounds = this.locationMenuBounds;
        return bounds == null ? 0 : bounds.y();
    }

    /**
     * {@code Target.blockY()} 是包私有方法，而 Target 本身也是包级私有类型，
     * 跨包不能直接调用；这里用反射读。它在 0.1.7 与 0.1.9 里都存在（语义：地面 + 1）。
     */
    @Unique
    private static OptionalInt slimefunhelper$invokeBlockY(Object target) throws Exception {
        Method method = slimefunhelper$blockYMethod(target.getClass());
        Object value = method.invoke(target);
        return value instanceof OptionalInt optional ? optional : OptionalInt.empty();
    }

    @Unique
    private static Method slimefunhelper$blockYMethod(Class<?> targetClass) throws NoSuchMethodException {
        Method cached = slimefunhelper$BLOCK_Y;
        if (cached != null && cached.getDeclaringClass() == targetClass) {
            return cached;
        }
        Method found = targetClass.getDeclaredMethod("blockY");
        found.setAccessible(true);
        slimefunhelper$BLOCK_Y = found;
        return found;
    }

    /** 条目表的懒初始化访问器：初始化器不可依赖（见字段注释），所有读写都必须过这里 */
    @Unique
    private List<ConfluxMenuContext> slimefunhelper$locationMenuOptions() {
        if (this.slimefunhelper$locationMenuOptions == null) {
            this.slimefunhelper$locationMenuOptions = new ArrayList<>();
        }
        return this.slimefunhelper$locationMenuOptions;
    }

    @Unique
    private void slimefunhelper$syncLocationMenuOptions(List<ConfluxMenuContext> options) {
        List<ConfluxMenuContext> entries = slimefunhelper$locationMenuOptions();
        entries.clear();
        entries.addAll(options);
    }

    @Unique
    private int slimefunhelper$locationMenuOptionCount() {
        return Math.min(slimefunhelper$locationMenuOptions().size(), ConfluxMenuOverlay.MAX_ENTRIES);
    }

    @Unique
    private int slimefunhelper$locationMenuIndexAt(double mouseX, double mouseY) {
        Rectangle panel = this.slimefunhelper$locationMenuPanel;
        if (!ConfluxMenuOverlay.contains(panel, mouseX, mouseY)) {
            return -1;
        }
        int index = (int) ((mouseY - panel.y - ConfluxMenuOverlay.PANEL_PADDING) / ConfluxMenuOverlay.LINE_HEIGHT);
        return index >= 0 && index < slimefunhelper$locationMenuOptionCount() ? index : -1;
    }

    @Unique
    private void slimefunhelper$runLocationMenuAction(int index) {
        List<ConfluxMenuContext> options = slimefunhelper$locationMenuOptions();
        ConfluxMenuTarget target = this.slimefunhelper$locationMenuClickTarget;
        if (index < 0 || index >= slimefunhelper$locationMenuOptionCount() || target == null) {
            return;
        }
        options.get(index).accept(target);
    }

    @Unique
    private void slimefunhelper$resetLocationMenu() {
        this.slimefunhelper$locationMenuPanel = null;
        this.slimefunhelper$locationMenuClickTarget = null;
        slimefunhelper$locationMenuOptions().clear();
    }
}
