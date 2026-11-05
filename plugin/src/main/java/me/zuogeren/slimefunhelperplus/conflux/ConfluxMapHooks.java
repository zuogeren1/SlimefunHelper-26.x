package me.zuogeren.slimefunhelperplus.conflux;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import me.matl114.events.annotations.Broadcast;
import me.matl114.events.annotations.ExtraArgs;
import me.matl114.events.channels.EventChannel;
import me.matl114.hooks.IHooks;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMenuContext;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxMenuTarget;
import me.zuogeren.slimefunhelperplus.conflux.hooks.ConfluxTravelWaypointSyncHolder;
import net.fabricmc.loader.api.FabricLoader;

/**
 * conflux-map 的接入点，形态照抄 {@link XaeroHooks}：
 * 未安装 / 结构不符时一律降级为“不启用”，绝不抛异常。
 *
 * <h2>自检盯的是什么</h2>
 * 适配目标是 conflux <b>0.1.16</b>（26.1.2 / 26.2 / 26.3 都有产物）。0.1.16 把右键位置菜单的整块状态
 * 从 {@code FullscreenMapScreen} 搬进了 {@code MapLocationMenuController}，小地图也拆成了几个 painter，
 * 所以「成员在不在、类型对不对」必须逐条核 —— 只按名字查是不够的：mixin 的注入按
 * 「名字 + 描述符」匹配，名字在、类型变了照样匹配不上，而 {@code @Shadow} 更是找不到就 apply 失败。
 *
 * <p>本模块的注入全部是 {@code require = 0} + 反射读状态（见 {@code ConfluxStateAccess}），
 * 结构不符的后果是「这两块功能不生效」，不是「游戏起不来」。这里负责把那句话写进日志。
 */
public class ConfluxMapHooks implements IHooks {
    public static final ConfluxMapHooks INSTANCE = new ConfluxMapHooks();

    public static ConfluxMapHooks getInstance() {
        return INSTANCE;
    }

    /** conflux-map 的模组 id */
    public static final String MOD_ID = "confluxmap";

    /**
     * conflux 的<b>路径点 API</b>（{@code META-INF/jars/api-x.y.z.jar}）能不能用。
     *
     * <p>只有它为真时，「同步旅行目标」才会走<b>真路径点</b>那条路、并把我们覆盖层上的临时标记收起来；
     * 为假（conflux 太老 / 探测不过）就整条留在覆盖层上。判断与缓存都在
     * {@link ConfluxTravelWaypointSyncHolder} 里（那边只碰字符串 + 反射，真的 API 类型在它之后才被加载）。
     */
    public static boolean isTravelWaypointSyncSupported() {
        return ConfluxTravelWaypointSyncHolder.isAvailable();
    }

    private static final String SCREEN_CLASS = "cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen";

    private static final String CONTROLLER_CLASS = "cn.net.rms.confluxmap.mc.ui.screen.MapLocationMenuController";

    private static final String MENU_CLASS = "cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapLocationMenu";

    private static final String MINIMAP_RENDERER_CLASS = "cn.net.rms.confluxmap.mc.ui.hud.MinimapHudRenderer";

    private static final String MINIMAP_RADAR_CLASS = "cn.net.rms.confluxmap.mc.ui.hud.MinimapRadarPainter";

    private static final String SESSION_CLASS = "cn.net.rms.confluxmap.core.task.SessionGuard$Session";

    private static final String GUI_DRAW_CLASS = "cn.net.rms.confluxmap.mc.ui.GuiDraw";

    private static final String PLAYER_VIEW_CLASS = "cn.net.rms.confluxmap.bridge.PlayerView";

    private static final String CONFLUX_CONFIG_CLASS = "cn.net.rms.confluxmap.core.config.ConfluxConfig";

    boolean enable;
    String unsupportedReason;

    @Getter
    @Broadcast
    @ExtraArgs({ConfluxMenuTarget.class})
    private static final EventChannel<ArrayList<ConfluxMenuContext>> locationMenuOption = new EventChannel<>();

    public ConfluxMapHooks() {
        enable = false;
        unsupportedReason = null;
        try {
            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
                unsupportedReason = "conflux-map is not installed";
                return;
            }
            // 这些清单只能当局部变量！一旦提成 static 字段，javac 会按文本顺序生成 <clinit>，
            // 构造 INSTANCE 时数组还是 null -> arraylength NPE，被 catch(Throwable) 吞掉，
            // 结果就是 isEnabled() 恒 false、一条菜单都不产出。
            Class<?> screen = Class.forName(SCREEN_CLASS);
            List<String> problems = new ArrayList<>();
            requireField(screen, problems, "locationMenu", CONTROLLER_CLASS);
            requireField(screen, problems, "centerX", "double");
            requireField(screen, problems, "centerZ", "double");
            requireField(screen, problems, "scale", "double");
            requireMethod(screen, problems, "renderContents", "void", GUI_DRAW_CLASS, "int", "int", "float");
            requireMethod(screen, problems, "renderAfterWidgets", "void", GUI_DRAW_CLASS, "int", "int", "float");
            requireMethod(
                    screen, problems, "mouseClicked", "boolean", "net.minecraft.client.input.MouseButtonEvent", "boolean");
            requireMethod(screen, problems, "keyPressed", "boolean", "net.minecraft.client.input.KeyEvent");
            requireMethod(screen, problems, "viewSession", SESSION_CLASS);
            requireMethod(screen, problems, "viewingLiveSession", "boolean");
            requireMethod(
                    screen,
                    problems,
                    "locationMenuButtonSpecs",
                    "java.util.List",
                    MENU_CLASS + "$Target",
                    "cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry",
                    "java.util.UUID",
                    "boolean");

            Class<?> controller = Class.forName(CONTROLLER_CLASS);
            requireField(controller, problems, "locationMenuBounds", MENU_CLASS + "$Bounds");
            requireField(controller, problems, "locationMenuTarget", MENU_CLASS + "$Target");
            requireMethod(controller, problems, "drawLocationMenu", "void", GUI_DRAW_CLASS);
            requireMethod(controller, problems, "dismissLocationMenu", "void");

            if (!problems.isEmpty()) {
                unsupportedReason = "conflux-map structure changed: " + String.join("; ", problems);
                Debug.getLogger().warn("ConfluxMapHelper disabled: " + unsupportedReason);
                for (String problem : problems) {
                    Debug.getLogger().warn("  - " + problem);
                }
                return;
            }
            enable = true;
            String minimapProblem = probeMinimapOverlay();
            if (minimapProblem != null) {
                // 小地图那两层是独立的一条链路（ConfluxMinimapHudMixin / ConfluxMinimapRadarMixin，都是
                // require = 0）：它够不到成员时只是不画那两层，**不应该**把整块 conflux 功能停掉，所以只 WARN。
                Debug.getLogger().warn("ConfluxMapHelper: the conflux minimap overlay stays disabled: " + minimapProblem);
            }
        } catch (Throwable e) {
            unsupportedReason = "conflux-map probe failed: " + e;
            Debug.getLogger().warn("ConfluxMapHelper disabled: " + unsupportedReason);
        }
    }

    private static void requireField(Class<?> owner, List<String> problems, String name, String expectedType) {
        try {
            Field field = owner.getDeclaredField(name);
            if (Modifier.isStatic(field.getModifiers())) {
                problems.add("field " + name + ": expected " + expectedType + ", actual <static field>");
            } else if (!field.getType().getName().equals(expectedType)) {
                problems.add("field " + name + ": expected " + expectedType + ", actual " + field.getType().getName());
            }
        } catch (Throwable e) {
            problems.add("field " + name + ": expected " + expectedType + ", actual <missing>");
        }
    }

    private static void requireMethod(
            Class<?> owner, List<String> problems, String name, String expectedReturn, String... expectedParams) {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == expectedParams.length) {
                found = method;
                break;
            }
        }
        if (found == null) {
            problems.add("method " + name + "(" + String.join(", ", expectedParams) + "): actual <missing>");
            return;
        }
        if (!found.getReturnType().getName().equals(expectedReturn)) {
            problems.add("method " + name + ": expected " + expectedReturn + ", actual "
                    + found.getReturnType().getName());
            return;
        }
        Class<?>[] parameters = found.getParameterTypes();
        for (int i = 0; i < expectedParams.length; ++i) {
            if (!parameters[i].getName().equals(expectedParams[i])) {
                problems.add("method " + name + " parameter " + i + ": expected " + expectedParams[i] + ", actual "
                        + parameters[i].getName());
                return;
            }
        }
    }

    /**
     * 小地图那两层要够到的东西还在不在：缩放表与配置（{@code MinimapHudRenderer}），
     * 以及覆盖层的注入点（{@code MinimapRadarPainter#drawRadar}）。
     *
     * <p>注入与这些读取全都是<b>软失败</b>（{@code require = 0} + try/catch），所以改名的后果是
     * “这几层不画”而不是“游戏起不来”—— 代价是它必须在日志里留下痕迹，本方法就是那行痕迹。
     *
     * @return {@code null} 表示小地图那层可用；否则是给日志看的原因
     */
    static String probeMinimapOverlay() {
        try {
            Class<?> renderer = Class.forName(MINIMAP_RENDERER_CLASS);
            if (!renderer.getDeclaredField("config").getType().getName().equals(CONFLUX_CONFIG_CLASS)) {
                return "MinimapHudRenderer#config is not a ConfluxConfig";
            }
            if (renderer.getDeclaredField("BLOCKS_PER_PIXEL").getType() != float[].class) {
                return "MinimapHudRenderer#BLOCKS_PER_PIXEL is not a float[]";
            }
            Class<?> radar = Class.forName(MINIMAP_RADAR_CLASS);
            for (Method method : radar.getDeclaredMethods()) {
                if (!method.getName().equals("drawRadar")) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                String[] expected = {
                    GUI_DRAW_CLASS, "float", "float", "int", "float", PLAYER_VIEW_CLASS, "float", "float", "float"
                };
                if (parameters.length != expected.length || method.getReturnType() != void.class) {
                    continue;
                }
                boolean matches = true;
                for (int i = 0; i < expected.length; ++i) {
                    if (!parameters[i].getName().equals(expected[i])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    return null;
                }
            }
            return "MinimapRadarPainter#drawRadar(GuiDraw,float,float,int,float,PlayerView,float,float,float) not found";
        } catch (Throwable e) {
            return "cannot inspect the conflux minimap: " + e;
        }
    }

    @Override
    public boolean isEnabled() {
        return enable;
    }

    public String getUnsupportedReason() {
        return unsupportedReason;
    }
}
