package me.matl114.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import lombok.Getter;
import me.matl114.events.annotations.Broadcast;
import me.matl114.events.annotations.ExtraArgs;
import me.matl114.events.channels.EventChannel;
import me.matl114.hooks.impl.confluxmap.ConfluxMenuContext;
import me.matl114.hooks.impl.confluxmap.ConfluxMenuTarget;
import me.matl114.hooks.impl.confluxmap.ConfluxTravelWaypointSyncHolder;
import me.matl114.utils.Debug;
import net.fabricmc.loader.api.FabricLoader;

/**
 * conflux-map 的接入点，形态照抄 {@link XaeroHooks}：
 * 未安装 / 结构不符时一律降级为“不启用”，绝不抛异常。
 *
 * <p>自检在构造期做一次：{@code FullscreenMapScreen} 的
 * {@code locationMenuBounds / locationMenuTarget / renderContents / mouseClicked / keyPressed / viewSession}
 * 必须都在，**而且类型要对**（字段比 {@code field.getType()}、方法比返回类型），
 * 缺一个或类型不符都会把「名字 + 期望类型 + 实际类型」逐条 WARN 出来，
 * 并让 {@link #isEnabled()} 返回 false。
 *
 * <p>只按名字查是不够的：mixin 的 {@code @Shadow} 在没有 refMap 时按「名字 + 描述符」匹配，
 * 名字在、类型变了照样在 apply 阶段炸（26.2 上钩子整体失效、26.1.2 上直接 entrypoint 失败）。
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
     * 为假（conflux 太老 / 探测不过）就整条留在覆盖层上，行为与以前逐字一致。
     * 判断与缓存都在 {@link ConfluxTravelWaypointSyncHolder} 里（那边只碰字符串 + 反射，
     * 真的 API 类型在它之后才被加载）。
     */
    public static boolean isTravelWaypointSyncSupported() {
        return ConfluxTravelWaypointSyncHolder.isAvailable();
    }

    /** 被我们盯上的那个界面（用字符串而不是类字面量，缺依赖时也不会连累类加载） */
    private static final String SCREEN_CLASS = "cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen";

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
            // 期望类型同样用字符串写，不能写成 conflux-map 的类字面量（它们是包级私有类型）。
            String[][] requiredFields = {
                {"locationMenuBounds", "cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapLocationMenu$Bounds"},
                {"locationMenuTarget", "cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapLocationMenu$Target"},
            };
            String[][] requiredMethods = {
                {"renderContents", "void"},
                {"mouseClicked", "boolean"},
                {"keyPressed", "boolean"},
                {"viewSession", "cn.net.rms.confluxmap.core.task.SessionGuard$Session"},
            };

            ArrayList<String> problems = new ArrayList<>();
            Class<?> screen = Class.forName(SCREEN_CLASS);
            for (String[] required : requiredFields) {
                String name = required[0];
                String expected = required[1];
                try {
                    Field field = screen.getDeclaredField(name);
                    if (Modifier.isStatic(field.getModifiers())) {
                        problems.add("field " + name + ": expected " + expected + ", actual <static field>");
                    } else if (!field.getType().getName().equals(expected)) {
                        problems.add("field " + name + ": expected " + expected + ", actual "
                                + field.getType().getName());
                    }
                } catch (Throwable e) {
                    problems.add("field " + name + ": expected " + expected + ", actual <missing>");
                }
            }
            for (String[] required : requiredMethods) {
                String name = required[0];
                String expected = required[1];
                Method found = null;
                for (Method method : screen.getDeclaredMethods()) {
                    if (method.getName().equals(name)) {
                        found = method;
                        break;
                    }
                }
                if (found == null) {
                    problems.add("method " + name + "(): expected " + expected + ", actual <missing>");
                } else if (!found.getReturnType().getName().equals(expected)) {
                    problems.add("method " + name + "(): expected " + expected + ", actual "
                            + found.getReturnType().getName());
                }
            }
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
                // 小地图那层是独立的一条链路（ConfluxMinimapHudMixin，注入 require = 0）：它够不到成员时
                // 只是不画那两层，**不应该**把整个 conflux 模块（含全屏地图那两层）停掉，所以这里只 WARN。
                Debug.getLogger().warn("ConfluxMapHelper: the conflux minimap overlay stays disabled: "
                        + minimapProblem);
            }
        } catch (Throwable e) {
            unsupportedReason = "conflux-map probe failed: " + e;
            Debug.getLogger().warn("ConfluxMapHelper disabled: " + unsupportedReason);
        }
    }

    /** 小地图渲染器（用字符串而不是类字面量：conflux 没装时也不会连累类加载） */
    private static final String MINIMAP_RENDERER_CLASS = "cn.net.rms.confluxmap.mc.ui.hud.MinimapHudRenderer";

    /**
     * 小地图覆盖层（{@code ConfluxMinimapHudMixin}）要够到的那几个成员还在不在。
     *
     * <p>它注入的是 {@code MinimapHudRenderer#drawRadar(GuiDraw, float, float, int, float, PlayerView, float)}，
     * javap 实测三份 jar（0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2）签名逐字一致；它另外还反射读
     * {@code MinimapHudRenderer#config}、{@code ConfluxConfig#minimapZoomIndex / #minimapShape} 与
     * {@code MinimapHudRenderer#BLOCKS_PER_PIXEL}。注入与这些读取全都是<b>软失败</b>（require = 0 + try/catch），
     * 所以改名的后果是“这两层不画”而不是“游戏起不来”—— 代价是它在日志里必须留下痕迹，本方法就是那行痕迹。
     *
     * @return {@code null} 表示小地图那层可用；否则是给日志看的原因
     */
    static String probeMinimapOverlay() {
        try {
            Class<?> renderer = Class.forName(MINIMAP_RENDERER_CLASS);
            // 反射读的两个字段：缺一个都算不出来缩放 / 圆形，直接判为不可用（getDeclaredField 抛异常由外层兜住）
            if (!renderer.getDeclaredField("config")
                    .getType()
                    .getName()
                    .equals("cn.net.rms.confluxmap.core.config.ConfluxConfig")) {
                return "MinimapHudRenderer#config is not a ConfluxConfig";
            }
            if (renderer.getDeclaredField("BLOCKS_PER_PIXEL").getType() != float[].class) {
                return "MinimapHudRenderer#BLOCKS_PER_PIXEL is not a float[]";
            }
            // 期望的参数类型名（逐个比对，光看方法名会在将来加/减参数时误判）
            String[] expected = {
                "cn.net.rms.confluxmap.mc.ui.GuiDraw",
                "float",
                "float",
                "int",
                "float",
                "cn.net.rms.confluxmap.bridge.PlayerView",
                "float"
            };
            for (Method method : renderer.getDeclaredMethods()) {
                if (!method.getName().equals("drawRadar")) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
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
            return "MinimapHudRenderer#drawRadar(GuiDraw,float,float,int,float,PlayerView,float) not found";
        } catch (Throwable e) {
            return "cannot inspect " + MINIMAP_RENDERER_CLASS + ": " + e;
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
