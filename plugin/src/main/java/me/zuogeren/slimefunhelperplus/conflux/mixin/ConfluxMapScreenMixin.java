package me.zuogeren.slimefunhelperplus.conflux.mixin;

import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapLocationMenu;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import me.zuogeren.slimefunhelperplus.conflux.ConfluxMapHelper;
import me.zuogeren.slimefunhelperplus.conflux.ConfluxMapHooks;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMapOverlay;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMenuButtonSpec;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMenuContext;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMenuOverlay;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMenuRowHost;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMenuTarget;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxStateAccess;
import me.matl114.utils.Debug;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把 Xaero 世界地图那套“右键自定义指令”等价地搬到 conflux-map 的全屏地图右键位置菜单上。
 *
 * <p>本轮（用户反馈：条目另起一块、有缝）改成<b>真正并入他们的列表</b>：
 * <ul>
 *   <li>{@code locationMenuButtonSpecs(...)} 的 {@code RETURN} 上，把我们的条目作为额外的
 *       {@code ButtonSpec} 追加进<b>他们返回的那个 List</b>。他们随后在
 *       {@code addLocationMenuButtons()} 里遍历这个列表造原版 {@code Button}，
 *       于是我们这几行拿到的是与他们完全相同的控件、间距（{@code buttonY(i) = y + 3 + 22*i}）
 *       与热键提示（conflux 的 {@code drawHotkeyHints} 按列表下标画数字）。
 *       label 是交给他们的 {@code labelKey}（{@code Texts.translatable(labelKey)} → 未知 key
 *       回落成字面量），<b>文字由原生按钮自己居中画</b>，所以我们只负责在追加时按像素截断
 *       （{@link ConfluxMenuOverlay#labelMaxWidth}）；tooltipKey 给的是我们自己的语言 key
 *       （{@code hoveredLocationActionTooltip()} 会对这个值直接 {@code Texts.translatable}，
 *       传 null 会在渲染悬浮提示时炸）；</li>
 *   <li>面板矩形由他们的 {@code FullscreenMapLocationMenu.place(...)} 按<b>原生</b>条目数算出来，
 *       我们只是把这块面板往下补齐到能装下多的行（{@code renderContents} 的 RETURN，
 *       在原版控件之前），见 {@link ConfluxMenuOverlay#drawPanelExtension}；</li>
 *   <li>数字提示（<b>只有数字</b>）画在 {@code renderAfterWidgets} 的 RETURN —— 必须在原版控件
 *       <b>之后</b>，否则会被他们按钮的背景盖住；文字不在这里画（原生按钮画过了，再画就是重影）。
 *       conflux 的 {@code drawHotkeyHints} 会自己给列表下标 < 5 的槽位画数字，所以那几行必须让给他们
 *       （判据 {@link ConfluxMenuOverlay#shouldDrawHotkeyDigit}，槽位数由反射探测一次后缓存）；</li>
 *   <li>点击与数字键仍然由 {@code mouseClicked} / {@code keyPressed} 的 HEAD 注入先手接管，
 *       拦下就 {@code setReturnValue(true)}；嵌入模式还多一道
 *       {@code embeddedMenuHotkeyPressed} 的 HEAD 注入（嵌入模式走的是这条捷径），
 *       三处都拦下之后原生按钮的 onPress 才会带着我们的占位 {@code Action} 落到
 *       {@code runLocationAction}（见 {@link #slimefunhelper$placeholderAction}）。</li>
 *   <li><b>命中我们那一行时连菜单一起收掉</b>：三处注入都走
 *       {@link #slimefunhelper$dismissLocationMenuThenRunAction(int)}，内部顺序与他们
 *       {@code performPendingLocationAction()} 一致 —— 先 {@code dismissLocationMenu()}，
 *       再跑我们的动作，最后不留下可命中的过期状态。<b>只有</b>“我们补出来的面板区域”
 *       （行间缝隙 / 内边距）继续保持只吞不关，与原生面板内边距的行为一致。</li>
 * </ul>
 *
 * <p>为什么不“@Shadow 包级私有成员再自己造按钮”：真类里的 {@code FullscreenMapLocationMenu}
 * 及其嵌套 {@code Bounds} / {@code ButtonSpec} 都是<b>包级私有</b>的，javac 连类型名都不让引用
 * （实测：拿真 jar 做 classpath，该类型的局部变量直接用不了）。Mixin 只能把代码合并进目标类，
 * 不能让我们的普通类进到那个包里，所以凡是“包级私有类型”的<b>实例</b>都只能用反射经手，
 * 而 {@code Bounds.buttonX()/buttonY(int)/buttonWidth()} 这类成员则是按字节码公式在
 * {@link ConfluxMenuOverlay} 里重算 —— conflux 0.1.16（26.1.2 与 26.2 两份 jar）
 * 里这些方法体逐条一致。
 *
 * <p>所有注入体内部一律 {@code try/catch (Throwable)}：我们出问题最多不显示这几行，
 * 绝不允许影响到他们的地图界面。
 */
/*
 * 隐式契约（动这块之前先读完，别再往包外搬）：
 * 本 mixin 里对 FullscreenMapLocationMenu 及其包级私有嵌套类型 Bounds / Target 的**任何**调用，
 * 都只能留在这个文件内。javap 实测（conflux 0.1.16，26.1.2 与 26.2 两份 jar 一致）：
 *   final class FullscreenMapLocationMenu$Bounds extends java.lang.Record   // 包级私有
 *   final class FullscreenMapLocationMenu$Target extends java.lang.Record   // 包级私有
 *   Bounds: public x()/y()/width()/height()，包私有 buttonX()/buttonY(int)/buttonWidth()/contains(double,double)
 *   Target: public blockX()/blockZ()，包私有 blockY()（返回 OptionalInt）
 * 只有本 mixin 被合并进目标类（cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen）之后，
 * 我们才以「同包」身份拿到这些包级私有类型/成员的访问权；一旦把这段逻辑抽到 me.matl114.* 的普通类里，
 * 字节码里就出现跨包访问包级私有类型/成员，运行期立刻 IllegalAccessError（验收方已用包外探针实测复现）。
 * 需要复用时只能往外传 ConfluxMenuTarget 这种脱敏后的自有类型。
 */
@Pseudo
@Environment(EnvType.CLIENT)
@Mixin(FullscreenMapScreen.class)
public abstract class ConfluxMapScreenMixin implements ConfluxMenuRowHost {
    /**
     * 位置菜单的面板矩形（{@code FullscreenMapLocationMenu$Bounds}），菜单没开时为 {@code null}。
     *
     * <p>0.1.16 起这块状态挂在屏幕的 {@code locationMenu}（{@code MapLocationMenuController}）上，
     * 更早的版本挂在屏幕自己身上 —— 两种布局的字段名一样，统一由
     * {@link ConfluxStateAccess#bounds(Object)} 定位。这里拿到 {@code Object} 之后转成桩类型：
     * 转换与后续的成员调用都发生在<b>被合并进 {@code FullscreenMapScreen} 的这个类</b>里，
     * 所以对包级私有类型 / 成员是按「同包」访问的，运行期不会 IllegalAccessError。
     *
     * <p>不使用 {@code @Shadow} 的原因见 {@link ConfluxStateAccess} 的类注释：成员搬家之后
     * shadow 找不到就是 apply 阶段失败（整局起不来），而我们的硬约束是「结构不认识就整块不生效」。
     */
    @Unique
    private FullscreenMapLocationMenu.Bounds slimefunhelper$bounds() {
        Object value = ConfluxStateAccess.bounds(this);
        return value instanceof FullscreenMapLocationMenu.Bounds bounds ? bounds : null;
    }

    /** 当前位置菜单指向的目标（{@code FullscreenMapLocationMenu$Target}），菜单没开时为 {@code null} */
    @Unique
    private FullscreenMapLocationMenu.Target slimefunhelper$target() {
        Object value = ConfluxStateAccess.target(this);
        return value instanceof FullscreenMapLocationMenu.Target target ? target : null;
    }

    /**
     * 注入没跑到（列表为空提前返回）时的兜底：位置菜单最常见的四条目。
     *
     * <p>面板补齐与命中判定都以「原生条目数」为基准，量不到时不能塌成 0，否则我们的行会画到
     * 面板标题上、点击判定也会整体上移。
     */
    @Unique
    private static final int slimefunhelper$FALLBACK_NATIVE_COUNT = 4;

    /** 当前视口的会话（{@code SessionGuard$Session}），拿不到返回 {@code null} */
    @Unique
    private SessionGuard.Session slimefunhelper$viewSession() {
        Object value = ConfluxStateAccess.viewSession(this);
        return value instanceof SessionGuard.Session session ? session : null;
    }

    /**
     * 我们自己的运行态：条目、面板补齐后的延伸块、面板当前指向的目标。
     *
     * <p>注意：这些 @Unique 实例字段<b>不能</b>依赖声明处初始化器 —— 验收实测中它曾经是 null，
     * reset() 里第一次 clear() 就是 NPE（26.2 渲染期直接崩地图界面、面板也永远不显示）。
     * 所以这里不写初始化器，所有读写统一走下面的懒初始化访问器。
     */
    @Unique
    private List<ConfluxMenuContext> slimefunhelper$locationMenuOptions;

    @Unique
    private ConfluxMenuTarget slimefunhelper$locationMenuClickTarget;

    /** 这一帧我们那几行的行数（面板补齐、命中判定都要用；0 表示不显示） */
    @Unique
    private int slimefunhelper$extraRowsShown;

    /** conflux 自己的条目数。在 {@code locationMenuButtonSpecs} 返回处（追加我们之前）量到 */
    @Unique
    private int slimefunhelper$nativeSpecCount;

    /** Target#blockY 的缓存（反射，跨包不可直接调用） */
    @Unique
    private static Method slimefunhelper$BLOCK_Y;

    /** 占位 Action 常量的缓存（枚举是闭集，只借一个已有的值） */
    @Unique
    private static Class<?> slimefunhelper$ACTION_CLASS;

    /** 探测 hotkeyLabel(int) 时最多试到第几个下标（conflux 只有 5 个） */
    @Unique
    private static final int slimefunhelper$HOTKEY_PROBE_LIMIT = 16;

    /**
     * 是否已经探测过 conflux 的热键槽位数。
     *
     * <p>故意用 boolean 而不是「哨兵值」：boolean 的默认值 {@code false} 正好就是「还没探测」，
     * 所以就算 mixin 没能把 {@code @Unique} 静态字段的初始化器并进目标类的 {@code <clinit>}，
     * 这里也会老老实实去探测一次，而不是把默认的 0 当成「conflux 没有热键提示」。
     */
    @Unique
    private static boolean slimefunhelper$hotkeySlotsProbed;

    /**
     * conflux 自己会画数字提示的槽位数（探测一次后缓存）：新版 = {@code HOTKEY_KEYS.length} = 5，
     * 没有这套提示的老版本 = 0。我们的行落在这些槽位里时数字由他们画，之外才由我们补。
     */
    @Unique
    private static volatile int slimefunhelper$confluxHotkeySlotCount;

    /** 异常只记一次日志，避免每帧刷屏 */
    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean slimefunhelper$warned =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Unique
    private static void slimefunhelper$warnOnce(String stage, Throwable e) {
        if (slimefunhelper$warned.compareAndSet(false, true)) {
            Debug.getLogger().warn("ConfluxMapHelper failed to " + stage + ", the extra menu rows are disabled until restart", e);
        }
    }

    /**
     * 把我们的条目并进 conflux 自己的按钮列表（由 {@code ConfluxMenuControllerMixin} 调用）。
     *
     * <p>时序：{@code openLocationMenu()} → {@code captureLocationMenu()}（用原生条目数算面板高）→
     * {@code rebuildWaypointControls()} → {@code addLocationMenuButtons()}（遍历
     * <b>控制器自己的</b> {@code locationMenuButtonSpecs(...)} 返回值造按钮）。
     * 0.1.16 里屏幕上的同名方法只是转发，所以追加发生在控制器那一次调用之后，
     * 通过 {@link ConfluxMenuRowHost} 回到这里 —— 此刻 {@code locationMenuBounds} 已经就位，
     * 标签按面板宽度截断才有据可依。
     *
     * <p>追加进去之后，行位置、按钮背景与热键数字都按「他们列表的一部分」自动处理，
     * 不需要我们再补画。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public void slimefunhelper$appendMenuRows(List<?> specs, int nativeCount) {
        try {
            // 返回 null 或空列表说明不是我们盯的那个调用点，不要动
            if (specs == null || specs.isEmpty()) {
                return;
            }
            List<ConfluxMenuContext> options = slimefunhelper$collectLocationMenuOptions();
            if (options.isEmpty()) {
                return;
            }
            // 从他们已经造好的第一条上借出两个类型：ButtonSpec 本体 与 Action 枚举（都是包级私有）
            Object sample = specs.getFirst();
            Object action = slimefunhelper$placeholderAction(sample);
            if (sample == null || action == null) {
                return;
            }
            // 原生条目数决定面板几何、命中判定与日志口径，先记下来
            this.slimefunhelper$nativeSpecCount = nativeCount;
            int lines = Math.min(options.size(), ConfluxMenuOverlay.MAX_ENTRIES);
            Font font = slimefunhelper$menuFont();
            List raw = specs;
            for (int i = 0; i < lines; ++i) {
                ConfluxMenuContext option = options.get(i);
                // label 是交给他们的 labelKey：原生按钮会用 Texts.translatable(labelKey) 自己把文字
                // 居中画出来，所以交出去的必须是**已经截断到装得下**的文本；
                // tooltipKey 绝不能是 null —— hoveredLocationActionTooltip() 会直接
                // Texts.translatable(我们放进去的值)，null 会在渲染悬浮提示时炸。
                Object spec = ConfluxMenuButtonSpec.create(
                        sample.getClass(),
                        action,
                        slimefunhelper$buttonLabel(option.label(), nativeCount, i, font),
                        slimefunhelper$LOCATION_MENU_TOOLTIP_KEY,
                        true);
                if (spec == null) {
                    return;
                }
                raw.add(spec);
            }
        } catch (Throwable e) {
            slimefunhelper$warnOnce("join the location menu button list", e);
        }
    }

    /**
     * 我们那几行的悬浮提示 key（原生条目用的是 conflux 自己的
     * {@code confluxmap.map.location_menu.*.tooltip}）。
     *
     * <p>他们造的按钮会把这个 key 原样塞进 {@code locationActionTooltips}，悬浮时由
     * {@code hoveredLocationActionTooltip()} 走 {@code Texts.translatable(key)} 渲染 ——
     * 传 null 的话那句 translatable 会在渲染时炸（{@code TranslatableContents.decompose()} 会拿
     * null 去跑格式匹配），所以这里必须是真 key。
     */
    @Unique
    private static final String slimefunhelper$LOCATION_MENU_TOOLTIP_KEY =
            "message.module.conflux-map-helper.location-menu.tooltip";

    /** 地图界面的字体；拿不到就返回 null（调用方退化成“不截断”） */
    @Unique
    private static Font slimefunhelper$menuFont() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc == null ? null : mc.font;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 交给原生按钮当 {@code labelKey} 的文本：超宽时先按像素截断。
     *
     * <p>为什么必须在<b>追加 spec 的时候</b>就截断：文字是他们的 {@code Widgets.button(...)} 用
     * {@code Texts.translatable(labelKey)} 渲染的（未知 key 直接回落成字面量），居中画在按钮里；
     * 超出按钮宽度之后由原版那套“滚动 / 裁切”接管 —— 那一刻我们既看不到也改不了，
     * 只能保证交出去的文字本身就装得下（可用宽度见 {@link ConfluxMenuOverlay#labelMaxWidth}）。
     *
     * @param label       条目文本（{@code ConfluxMenuContext#label()}，收集阶段已经是纯文本）
     * @param nativeCount 原生条目数（决定这一行的热键数字，从而决定要给数字留多少位置）
     * @param index       我们这一行的下标
     */
    @Unique
    private String slimefunhelper$buttonLabel(String label, int nativeCount, int index, Font font) {
        String text = label == null ? "" : label;
        FullscreenMapLocationMenu.Bounds bounds = slimefunhelper$bounds();
        if (bounds == null) {
            // 没有面板几何就算不出可用宽度；这时他们的 addLocationMenuButtons 本来就取不到
            // buttonX()，这一行根本画不出来，原样交出去即可
            return text;
        }
        int maxWidth = ConfluxMenuOverlay.labelMaxWidth(bounds.x(), bounds.width(), nativeCount, index, font);
        return ConfluxMenuOverlay.truncateLabel(text, maxWidth, font);
    }

    /**
     * 第一段绘制：在他们 {@code drawLocationMenu(draw)} 之后（{@code renderContents} 的 RETURN）
     * 把面板向下补齐到能装下我们那几行。
     *
     * <p>顺序依据（{@code ConfluxScreen#extractRenderState} 的字节码，0.1.16）：
     * <pre>
     *   renderContents(draw, ...)        // 他们的 drawLocationMenu(draw) 在里面，画面板背景
     *   Screen.extractRenderState(...)   // 原版控件：他们造的按钮在这里画
     *   renderAfterWidgets(draw, ...)    // 他们的热键提示在这里画
     * </pre>
     * 所以补背景必须放在第一段（放在后面会盖住已经画好的按钮），数字提示反而必须放到第三段
     * （放在第一段会被按钮的背景覆盖）。
     */
    @Inject(method = "renderContents", at = @At("RETURN"), require = 1)
    private void slimefunhelper$onRenderContentsReturn(
            GuiDraw draw, int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
        try {
            this.slimefunhelper$extraRowsShown = 0;
            if (slimefunhelper$bounds() == null || draw == null) {
                slimefunhelper$clearLocationMenuState();
                return;
            }
            List<ConfluxMenuContext> options = slimefunhelper$collectLocationMenuOptions();
            ConfluxMenuTarget target = slimefunhelper$currentLocationTarget();
            if (target == null || options.isEmpty()) {
                slimefunhelper$clearLocationMenuState();
                return;
            }
            this.slimefunhelper$locationMenuClickTarget = target;
            slimefunhelper$syncLocationMenuOptions(options);

            FullscreenMapLocationMenu.Bounds bounds = slimefunhelper$bounds();
            int boundsX = bounds.x();
            int boundsY = bounds.y();
            int width = bounds.width();
            int height = bounds.height();
            int nativeCount = slimefunhelper$nativeButtonCount();
            int rows = slimefunhelper$extraRowsShown =
                    Math.min(options.size(), ConfluxMenuOverlay.MAX_ENTRIES);

            boolean extended = ConfluxMenuOverlay.drawPanelExtension(
                    boundsX, boundsY, width, height, nativeCount, rows, draw::fill);
            slimefunhelper$logMerge(nativeCount, rows, boundsX, boundsY, width, height, extended);
        } catch (Throwable e) {
            this.slimefunhelper$extraRowsShown = 0;
            slimefunhelper$clearLocationMenuState();
            slimefunhelper$warnOnce("extend the location menu panel", e);
        }
    }

    /**
     * 第二段绘制：与原版控件同一批画完之后，补上 conflux <b>没画</b>的那几行数字提示。
     *
     * <p>文字<b>不在这里画</b>：原生按钮已经按我们传进去的 {@code labelKey} 居中画过一遍了，
     * 这里再画一遍就是用户看到的重影。conflux 的 {@code drawHotkeyHints} 会自己给列表下标 < 5 的槽位
     * 画绿色数字（他们画在前面），所以我们只补他们画不到的那些槽位；没有那套提示的版本
     * （探测出的 {@code confluxHotkeySlots} = 0）里所有数字都由我们画。
     */
    @Inject(method = "renderAfterWidgets", at = @At("RETURN"), require = 1)
    private void slimefunhelper$onRenderAfterWidgets(GuiDraw draw, int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
        try {
            int rows = this.slimefunhelper$extraRowsShown;
            FullscreenMapLocationMenu.Bounds bounds = slimefunhelper$bounds();
            if (rows <= 0 || bounds == null || draw == null) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.font == null) {
                return;
            }
            ConfluxMenuOverlay.drawHotkeyDigits(
                    bounds.x(), bounds.y(), bounds.width(), slimefunhelper$nativeButtonCount(), rows,
                    slimefunhelper$confluxHotkeySlots(), mc.font,
                    (text, x, y, color) -> draw.drawTextWithShadow(mc.font, text, x, y, color));
        } catch (Throwable e) {
            slimefunhelper$warnOnce("draw the extra location menu hotkey digits", e);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 1)
    private void slimefunhelper$onLocationMenuMouseClicked(
            MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        try {
            int rows = this.slimefunhelper$extraRowsShown;
            FullscreenMapLocationMenu.Bounds bounds = slimefunhelper$bounds();
            if (rows <= 0 || bounds == null || event == null || event.button() != 0) {
                return;
            }
            int index = slimefunhelper$locationMenuIndexAt(event.x(), event.y());
            if (index >= 0) {
                // 命中了我们的一行：先 dismissLocationMenu() 再跑动作（对齐他们的 performPendingLocationAction），
                // 中间由 slimefunhelper$dismissLocationMenuThenRunAction 负责把我们的运行态也清掉。
                // 判据要在 dismiss 之前算完：dismiss 会把 locationMenuBounds 置 null。
                slimefunhelper$dismissLocationMenuThenRunAction(index);
                // 必须在这里拦下：他们下一句就是“点在面板外就 dismiss”，而我们的行在面板外。
                // 我们已经在上面 dismiss 过了，这里只是把事件吞掉，别让他们的分支再走一遍。
                cir.setReturnValue(true);
                return;
            }
            // 点在补出来的面板区域（行间缝隙 / 内边距）里：不能当成“点外面”把菜单关掉。
            // 这里<b>只吞不关</b>，与原生一致 —— 他们自己的面板内边距（buttonY 之间的那几像素）
            // 落进 locationMenuBounds.contains() 之后走的是“super.mouseClicked -> 没人接 ->
            // pendingAction 仍为 null -> performPendingLocationAction() 什么都不做”，菜单同样留着。
            if (slimefunhelper$isOverExtraRows(event.x(), event.y())) {
                cir.setReturnValue(true);
            }
        } catch (Throwable e) {
            slimefunhelper$clearLocationMenuState();
            slimefunhelper$warnOnce("handle location menu click", e);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 1)
    private void slimefunhelper$onLocationMenuKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (this.slimefunhelper$extraRowsShown <= 0 || slimefunhelper$bounds() == null || event == null) {
                return;
            }
            int index = ConfluxMenuOverlay.hotkeyIndex(slimefunhelper$nativeButtonCount(), event.key());
            if (index < 0 || index >= slimefunhelper$extraRowsShown) {
                return;
            }
            // 数字键命中：同样先 dismissLocationMenu() 再跑动作 —— 他们的 embeddedMenuHotkeyPressed
            // 也是 pendingLocationAction -> performPendingLocationAction() -> dismiss 再 run；
            // 他们的 keyPressed 走的是同一条 performPendingLocationAction，所以这里对齐同一条顺序。
            slimefunhelper$dismissLocationMenuThenRunAction(index);
            // 必须在 HEAD 拦下：他们的 keyPressed 会先问 embeddedMenuHotkeyPressed（0.1.9 的热键槽位）。
            // 我们的行在他们的列表里，占的正好是他们第 nativeCount+index+1 个槽位，与显示的数字一致。
            cir.setReturnValue(true);
        } catch (Throwable e) {
            slimefunhelper$clearLocationMenuState();
            slimefunhelper$warnOnce("handle location menu hotkey", e);
        }
    }

    /**
     * 第三道保险：嵌入模式（小地图 / 分屏里那块地图）由 {@code ConfluxScreen.keyPressed}
     * 直接调 {@code embeddedMenuHotkeyPressed(keyCode)}，是条不完全经过
     * {@code FullscreenMapScreen.keyPressed} 的捷径；万一将来多出别的调用点绕过上面那个 HEAD 注入，
     * 这里再拦一次，让我们的行在任何路径下都跑我们自己的动作。
     *
     * <p>必须 {@code require = 0}：这个方法是较新的 conflux 才有的，（javap 实测老版本没有；将来改签名也一样），
     * require = 1 会让整个 mixin 在这些版本上 apply 失败，连带地图界面一起坏掉。
     *
     * <p>命中我们的槽位就 {@code setReturnValue(true)}，并走与另外两处完全相同的
     * {@code slimefunhelper$dismissLocationMenuThenRunAction(index)}（先 dismiss 再跑动作）；
     * 不是我们的键一律放行，交给他们的 {@code actionForHotkey} 处理他们自己那几行。
     */
    @Inject(method = "embeddedMenuHotkeyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void slimefunhelper$onEmbeddedMenuHotkeyPressed(int keyCode, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (this.slimefunhelper$extraRowsShown <= 0 || slimefunhelper$bounds() == null) {
                return;
            }
            int index = ConfluxMenuOverlay.hotkeyIndex(slimefunhelper$nativeButtonCount(), keyCode);
            if (index < 0 || index >= this.slimefunhelper$extraRowsShown) {
                return;
            }
            // conflux 原生这条捷径同样是 pendingLocationAction -> performPendingLocationAction()（dismiss 再 run），
            // 我们的行并进他们列表之后也吃同一套语义：先关菜单，再跑我们的动作。
            slimefunhelper$dismissLocationMenuThenRunAction(index);
            cir.setReturnValue(true);
        } catch (Throwable e) {
            slimefunhelper$clearLocationMenuState();
            slimefunhelper$warnOnce("handle the embedded location menu hotkey", e);
        }
    }

    /** 收集条目（他们的事件广播），并脱下 conflux 的类型 */
    @Unique
    private List<ConfluxMenuContext> slimefunhelper$collectLocationMenuOptions() {
        ArrayList<ConfluxMenuContext> options = new ArrayList<>();
        if (!ConfluxMapHooks.getInstance().isEnabled()) {
            // conflux 没装、或版本结构与适配目标不符：整块不产出条目（覆盖层那边同样有门禁）
            return options;
        }
        try {
            ConfluxMapHooks.getLocationMenuOption().broadcast(options, slimefunhelper$currentLocationTarget());
        } catch (Throwable e) {
            slimefunhelper$warnOnce("collect location menu options", e);
            options.clear();
        }
        return options;
    }

    /** 把 conflux 侧的类型全部收在这个方法里，外面只见我们自己的类型 */
    @Unique
    private ConfluxMenuTarget slimefunhelper$currentLocationTarget() {
        FullscreenMapLocationMenu.Target target = slimefunhelper$target();
        if (target == null) {
            return null;
        }
        String dimensionId = "minecraft:overworld";
        String worldPath = "overworld";
        try {
            SessionGuard.Session session = slimefunhelper$viewSession();
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
        FullscreenMapLocationMenu.Bounds bounds = slimefunhelper$bounds();
        return bounds == null ? 0 : bounds.y();
    }

    /**
     * {@code Target.blockY()} 是包私有方法，而 Target 本身也是包级私有类型，
     * 跨包不能直接调用；这里用反射读。0.1.16 里它在（语义：地面 + 1，未知则 empty）。
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

    /**
     * conflux 自己会画数字提示的槽位数；整个进程只探测一次，之后走缓存。
     *
     * <p>较新的 conflux 有 {@code drawHotkeyHints} + {@code hotkeyLabel(int)} + {@code HOTKEY_KEYS}；
     * 没有这套提示的老版本里槽位数 = 0，我们那几行的数字全部由我们画。
     *
     * <p>全部走反射：{@code FullscreenMapLocationMenu} 及其成员是包级私有 / private 的，
     * 而且老版本上压根没有这些成员 —— 任何 {@code @Shadow} 或 require = 1 的注入都会直接挂。
     */
    @Unique
    private static int slimefunhelper$confluxHotkeySlots() {
        if (slimefunhelper$hotkeySlotsProbed) {
            return slimefunhelper$confluxHotkeySlotCount;
        }
        int slots = slimefunhelper$probeConfluxHotkeySlots();
        slimefunhelper$confluxHotkeySlotCount = slots;
        slimefunhelper$hotkeySlotsProbed = true;
        return slots;
    }

    /**
     * 主判据是 {@code hotkeyLabel(i)} 的<b>实际返回值</b>：他们的绘制循环就是
     * {@code String label = hotkeyLabel(index); if (label == null) return;}，
     * 所以「前几个下标拿得到标签」与他们「会画哪几行」本来就是同一件事 ——
     * 既不依赖 {@code HOTKEY_KEYS} 这个私有字段，也不受 {@code drawHotkeyHints} 的签名变化影响。
     *
     * <p>只有 {@code hotkeyLabel} 在、却调不动时（例如将来改成实例方法）才退回读
     * {@code HOTKEY_KEYS.length}；两样都拿不到就按 0 处理（= 所有数字都由我们画）。
     */
    @Unique
    private static int slimefunhelper$probeConfluxHotkeySlots() {
        Class<?> menu = FullscreenMapLocationMenu.class;
        try {
            Method hotkeyLabel = menu.getDeclaredMethod("hotkeyLabel", int.class);
            hotkeyLabel.setAccessible(true);
            int slots = 0;
            for (int index = 0; index < slimefunhelper$HOTKEY_PROBE_LIMIT; ++index) {
                if (hotkeyLabel.invoke(null, index) == null) {
                    break;
                }
                slots = index + 1;
            }
            return slots;
        } catch (Throwable ignored) {
        }
        try {
            Field keys = menu.getDeclaredField("HOTKEY_KEYS");
            keys.setAccessible(true);
            Object value = keys.get(null);
            if (value instanceof int[] array) {
                return array.length;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 取一个**已存在**的 {@code Action} 常量给我们的 ButtonSpec 占位。
     *
     * <p>枚举是闭集，不能新增值（用户硬约束），所以只能借一个现成的。选 {@code CLEAR_HIGHLIGHT}：
     * <ul>
     *   <li><b>副作用最小</b>：{@code runLocationAction} 里它只做
     *       {@code waypointHighlightState.clear()}，而那个 clear 就是 {@code this.target = null} ——
     *       只影响客户端那个「已高亮的位置 / 路径点」标记，不发包、不换界面、不传送、不删数据；</li>
     *   <li><b>不碰他们的按钮字段</b>：{@code addLocationMenuButtons()} 末尾的 switch 只给
     *       {@code SET_WAYPOINT / EDIT_WAYPOINT / SHARE_LOCATION / TELEPORT} 赋值
     *       （{@code setWaypointLocationButton / editWaypointLocationButton / shareLocationButton /
     *       teleportLocationButton}），{@code CLEAR_HIGHLIGHT} 落在那个空的 fall-through 分支里。
     *       之前借的 {@code TELEPORT} 会把他们那个 {@code teleportLocationButton} 覆盖成我们最后一行，
     *       连「传送目标不可用」的提示分支都会被带偏；</li>
     *   <li><b>0.1.16 两份 jar 都有</b>：javap 实测 26.1.2 / 26.2 的
     *       {@code Action} 都是同样这 11 个常量（含 {@code CLEAR_HIGHLIGHT}）。</li>
     * </ul>
     *
     * <p><b>为什么还得这么小心</b>：正常路径上我们的行走不到 {@code runLocationAction} ——
     * {@code mouseClicked} / {@code keyPressed} / {@code embeddedMenuHotkeyPressed} 三处 HEAD 注入都会
     * 先手拦下并 {@code setReturnValue(true)}。但运行态（{@code extraRowsShown}）可能因为异常或时序
     * 被清成 0 而按钮还在，那一刻原版控件自己的 onPress 仍会把 {@code pendingLocationAction}
     * 设成这个占位常量、进而 {@code performPendingLocationAction()} → {@code runLocationAction}。
     * <b>最坏情况：用户当前高亮的位置标记被清掉</b>（再点一次「高亮」即可恢复）。
     * 换成 {@code TELEPORT} 的话，同一条路径会真的把人传送过去 —— 代价完全不对等。
     */
    @Unique
    private static Object slimefunhelper$placeholderAction(Object sample) {
        if (sample == null) {
            return null;
        }
        try {
            Class<?> actionClass = slimefunhelper$ACTION_CLASS;
            if (actionClass == null) {
                Method actionAccessor = sample.getClass().getDeclaredMethod("action");
                actionAccessor.setAccessible(true);
                Object existing = actionAccessor.invoke(sample);
                if (existing == null) {
                    return null;
                }
                actionClass = existing.getClass();
                slimefunhelper$ACTION_CLASS = actionClass;
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class<? extends Enum>) actionClass.asSubclass(Enum.class), "CLEAR_HIGHLIGHT");
            return value;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * conflux 自己的条目数。
     *
     * <p>来源是 {@code locationMenuButtonSpecs} 返回处量到的 {@link #slimefunhelper$nativeSpecCount}：
     * 那是他们**当次真的会画出来的**条目数（含被禁用的），比别处推算更准。
     * 注入没跑到（例如列表为空提前返回）时用兜底值，保证几何不塌。
     */
    @Unique
    private int slimefunhelper$nativeButtonCount() {
        int count = this.slimefunhelper$nativeSpecCount;
        return count > 0 ? count : slimefunhelper$FALLBACK_NATIVE_COUNT;
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
    private int slimefunhelper$locationMenuIndexAt(double mouseX, double mouseY) {
        FullscreenMapLocationMenu.Bounds bounds = slimefunhelper$bounds();
        int rows = this.slimefunhelper$extraRowsShown;
        if (bounds == null || rows <= 0) {
            return -1;
        }
        int nativeCount = slimefunhelper$nativeButtonCount();
        for (int i = 0; i < rows; ++i) {
            if (ConfluxMenuOverlay.isOverRow(
                    bounds.x(), bounds.y(), bounds.width(), nativeCount, i, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /** 鼠标是否落在「我们补出来的那块面板区域」里 */
    @Unique
    private boolean slimefunhelper$isOverExtraRows(double mouseX, double mouseY) {
        FullscreenMapLocationMenu.Bounds bounds = slimefunhelper$bounds();
        int rows = this.slimefunhelper$extraRowsShown;
        if (bounds == null || rows <= 0) {
            return false;
        }
        return ConfluxMenuOverlay.isOverExtraRows(
                bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                slimefunhelper$nativeButtonCount(), rows, mouseX, mouseY);
    }


    /**
     * 跑我们的动作，并<b>对齐原生</b>地收起位置菜单。
     *
     * <p>为什么必须收：我们的行已经并进他们的列表，点它 == 点他们面板里的一行。
     * 原生顺序是（{@code MapLocationMenuController#performPendingLocationAction()}，0.1.16 反编译）：
     * <pre>
     *   ConfluxStateAccess.dismiss(this);                                   // 1876-1885
     *   this.runLocationAction(action, target, waypoint, playerId, this);
     * </pre>
     * 先关菜单再执行 —— 因为动作里可能打开聊天界面 / 别的 Screen（例如他们的 SHARE_LOCATION），
     * 反过来做就会先开界面、再被 dismiss 的 {@code rebuildWaypointControls()} 在已换掉的界面上做文章。
     * 所以这里同样先 {@code dismissLocationMenu()}，再跑我们的动作。
     *
     * <p>dismiss 之后我们的动作仍然跑得动：它读的是<b>我们自己的</b>运行态
     * （{@code slimefunhelper$locationMenuOptions} / {@code slimefunhelper$locationMenuClickTarget}），
     * 而 {@code dismissLocationMenu()} 只清他们自己的字段，碰不到我们这两个。
     *
     * <p>{@code clearLocationMenuState()} 紧跟在 dismiss 后面、动作之前：这一步不能省，
     * 否则同一帧 / 下一帧还可能用同一批过期条目再命中一次。它同时把我们的行数归零，
     * 于是 {@code embeddedMenuHotkeyPressed}（嵌入模式的捷径）也拦不下任何东西了。
     *
     * <p>dismiss 抛异常时仍然执行动作并清掉我们自己的状态（用户已经点了，功能不该跟着陪葬），
     * 但绝不在“菜单还开着”的前提下留下可命中的状态 —— 否则下一次点击会再跑一遍同一个动作。
     * 这里用 {@code Exception} 而不是 {@code Throwable}：{@code Error} 该原样上报；
     * 日志走 {@code Debug.getLogger().warn} 并打了去重闸，不会每次点击都刷一条。
     */
    @Unique
    private void slimefunhelper$dismissLocationMenuThenRunAction(int index) {
        // 条目与目标必须在 dismiss/clear **之前**抓下来：clearLocationMenuState() 会清空
        // slimefunhelper$locationMenuOptions() 这个活列表，也会把 locationMenuClickTarget 置 null，
        // 放到后面再读就是永远读不到东西（点击「菜单照关、动作不执行」的成因）。
        List<ConfluxMenuContext> options = slimefunhelper$locationMenuOptions();
        ConfluxMenuContext option = index >= 0 && index < options.size() ? options.get(index) : null;
        ConfluxMenuTarget target = this.slimefunhelper$locationMenuClickTarget;
        try {
            ConfluxStateAccess.dismiss(this);
        } catch (Exception e) {
            slimefunhelper$warnDismissOnce(e);
        }
        try {
            slimefunhelper$clearLocationMenuState();
        } catch (Exception e) {
            slimefunhelper$warnOnce("clear our location menu state", e);
        }
        if (option != null && target != null) {
            option.accept(target);
        }
    }

    /** dismissLocationMenu 失败只报一次，避免每次点击都刷一条 */
    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean slimefunhelper$dismissWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Unique
    private static void slimefunhelper$warnDismissOnce(Throwable e) {
        if (slimefunhelper$dismissWarned.compareAndSet(false, true)) {
            Debug.getLogger().warn(
                    "ConfluxMapHelper failed to dismiss the conflux location menu; our rows still work but the menu may stay open",
                    e);
        }
    }

    /** 面板没了 / 出异常：只清我们的运行态，不碰他们的任何字段 */
    @Unique
    private void slimefunhelper$clearLocationMenuState() {
        this.slimefunhelper$extraRowsShown = 0;
        this.slimefunhelper$locationMenuClickTarget = null;
        slimefunhelper$locationMenuOptions().clear();
    }

    /**
     * 实机可验证信号：把「并进列表」的几何打一次日志（只在数字变化时），验收方按行数/坐标即可判断。
     */
    @Unique
    private void slimefunhelper$logMerge(
            int nativeCount, int rows, int boundsX, int boundsY, int width, int height, boolean extended) {
        int hotkeySlots = slimefunhelper$confluxHotkeySlots();
        int signature = (nativeCount * 31 + rows) * 31 + hotkeySlots;
        if (signature == slimefunhelper$lastLoggedSignature) {
            return;
        }
        slimefunhelper$lastLoggedSignature = signature;
        Debug.getLogger()
                .info("[ConfluxMapHelper] location menu merged: nativeRows={} ourRows={} panelX={} panelY={} panelWidth={} nativePanelHeight={} extended={} firstOurRowTopY={} rowHeight={} confluxHotkeySlots={} ourDigitRows={}",
                        nativeCount, rows, boundsX, boundsY, width, height, extended,
                        ConfluxMenuOverlay.rowY(boundsY, nativeCount), ConfluxMenuOverlay.BUTTON_HEIGHT,
                        hotkeySlots, ConfluxMenuOverlay.ownDigitRowCount(nativeCount, rows, hotkeySlots));
    }

    @Unique
    private static int slimefunhelper$lastLoggedSignature = -1;

}
