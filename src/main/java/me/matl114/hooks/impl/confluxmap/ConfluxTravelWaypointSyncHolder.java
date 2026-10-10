package me.matl114.hooks.impl.confluxmap;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import me.matl114.utils.Debug;
import net.minecraft.world.phys.Vec3;

/**
 * 「同步旅行目标到 conflux <b>真路径点</b>」的窄门（对齐 {@code XaeroHelper#onXaeroTempWaypointSync}）。
 *
 * <h2>为什么要隔一层</h2>
 * conflux-map 的 API 类在它的 JiJ 包里（{@code META-INF/jars/api-x.y.z.jar}），
 * <b>没装 conflux 时这些类根本不存在</b>。JVM 的链接是惰性的、但<b>按类</b>的：
 * 只要某个会被无条件加载的类，它的字段描述符或方法体里出现了 {@code cn.net.rms.confluxmap.*}，
 * 那个类一加载就 {@code NoClassDefFoundError}，而且是在 {@code <clinit>} / 首次调用这种最难受的地方炸。
 * 所以：
 * <ul>
 *   <li><b>本类</b>（会被模块无条件碰到）<b>只用字符串 + 反射</b>提 conflux，不出现任何 API 类型；</li>
 *   <li>真正调用 API 的代码全在 {@link ConfluxTravelWaypointSync} 里，只有确定
 *       {@code cn.net.rms.confluxmap.api.ConfluxMapApi} 能加载、成员也齐了，
 *       才 {@code Class.forName(...)} 把它拉进来。</li>
 * </ul>
 *
 * <h2>探测内容与「不求甚解」的边界</h2>
 * 下面 {@link #PROBE_SIGNATURES} 逐条比的是<b>方法名 + 形参类型名 + 返回类型名</b>，
 * 与 {@code ConfluxMapHooks} 自检同一套路数：光看类在不在不够，要有签名。
 * 0.1.7 与 0.1.9 的 {@code api/} 包是同样 36 个类、{@code WaypointApi} 六个方法逐字一致
 * （javap 实测 0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2 三份 jar），但将来多一个参数就会在
 * <b>调用点</b>才炸 —— 那时机是「玩家刚开始旅行」，不如启动时一次性判掉。
 * 判失败 = 退回老路（只在我们自己的覆盖层上画标记），并在日志里留一行原因。
 *
 * <p>探测在 {@code <clinit>} 里做第一次（这样那行 WARN 能落在<b>启动日志</b>里），
 * 但<b>不只有一次</b>：探不到时会在 {@link #tick} 里<b>有界地</b>补探（见 {@link #retryProbe()}）。
 * 因为 {@code ConfluxMapApi.install(...)} 是 conflux 在<b>它自己的</b>客户端初始化里做的，
 * 而我们模块的构造发生在<b>我们的</b>入口点里 —— 两个 mod 的入口点顺序 Fabric 并不保证。
 * 构造期探到 {@code Optional.empty()} 完全可能只是「它还没轮到初始化」，不是「conflux 太老」；
 * 就此认输的话，那一整局都只剩覆盖层标记，看起来就像这个功能没做出来。
 */
public final class ConfluxTravelWaypointSyncHolder {
    /** conflux-map 的 API 入口（字符串，不能写成类字面量） */
    private static final String ENTRY = "cn.net.rms.confluxmap.api.ConfluxMapApi";

    /** 异常只记一次：这种事一旦发生就会每 tick 复现，不设闸会把日志刷爆 */
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /**
     * 「类名 / 方法名 / 形参类型名（逗号分隔）/ 返回类型名」四元组，覆盖我们真正会调到的每一个成员。
     */
    private static final String[][] PROBE_SIGNATURES = {
        {ENTRY, "instance", "", "java.util.Optional"},
        {"cn.net.rms.confluxmap.api.WaypointApi", "list", "", "java.util.List"},
        {
            "cn.net.rms.confluxmap.api.WaypointApi",
            "add",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit",
            "java.util.concurrent.CompletableFuture"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi",
            "update",
            "java.util.UUID,cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit",
            "java.util.concurrent.CompletableFuture"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi",
            "remove",
            "java.util.UUID",
            "java.util.concurrent.CompletableFuture"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit",
            "builder",
            "",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder",
            "name",
            "java.lang.String",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder",
            "dimensionId",
            "java.lang.String",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder",
            "position",
            "double,double,double",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder",
            "colorArgb",
            "int",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder",
            "group",
            "java.lang.String",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder",
            "visible",
            "boolean",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder",
            "crossDimensionVisible",
            "boolean",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder",
            "type",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointType",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder",
            "markerLabel",
            "java.lang.String",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder"
        },
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit$Builder",
            "build",
            "",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointEdit"
        },
        {"cn.net.rms.confluxmap.api.WaypointApi$ApiWaypoint", "id", "", "java.util.UUID"},
        {"cn.net.rms.confluxmap.api.WaypointApi$ApiWaypoint", "name", "", "java.lang.String"},
        {"cn.net.rms.confluxmap.api.WaypointApi$ApiWaypoint", "dimensionId", "", "java.lang.String"},
        {"cn.net.rms.confluxmap.api.WaypointApi$ApiWaypoint", "markerLabel", "", "java.lang.String"},
        {"cn.net.rms.confluxmap.api.WaypointApi$ApiWaypoint", "x", "", "double"},
        {"cn.net.rms.confluxmap.api.WaypointApi$ApiWaypoint", "y", "", "double"},
        {"cn.net.rms.confluxmap.api.WaypointApi$ApiWaypoint", "z", "", "double"},
        {
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointMutation",
            "result",
            "",
            "cn.net.rms.confluxmap.api.WaypointApi$WaypointMutation$Result"
        },
    };

    /**
     * API 可用时才有值；{@code null} = 走老路（覆盖层标记）。
     *
     * <p>{@code <clinit>} 探不到时先为 null，之后由 {@link #retryProbe()} 在 {@code tick} 里补上 ——
     * 所以它<b>不是 final</b>；但它只会从 null 变成非 null，绝不会反过来（消失由内部状态表达）。
     * 跨线程可见性靠 volatile：绘制侧会在渲染路径上问 {@link #shouldHideOwnMarker()}。
     */
    private static volatile ConfluxTravelWaypointSync IMPL;
    /** 不可用时的原因；可用时（含补探成功之后）为 {@code null} */
    private static volatile String UNAVAILABLE_REASON;

    /** 客户端固定 20 tps，只用来把下面两个常量换算成日志里的人话 */
    private static final int TICKS_PER_SECOND = 20;

    /**
     * 补探的<b>间隔</b>：每这么多游戏 tick 重探一次（20 tps ≈ 0.25 秒）。
     *
     * <p>为什么不每 tick 探：conflux 没装时 {@code probeMembers()} 会对入口类做一次注定失败的
     * {@code Class.forName}，而 JVM 只缓存<b>成功</b>的类加载，失败的那次每次都要重新问一遍
     * classloader。4 次/秒 × 30 秒 = 最多 121 次，够快也够便宜。
     */
    private static final int RETRY_INTERVAL_TICKS = 5;

    /**
     * 补探的<b>总预算</b>（游戏 tick）：20 tps ≈ 30 秒，用完就不再探。
     *
     * <p>为什么是这个量级：要盖住「conflux 的 {@code ConfluxMapApi.install(...)} 比我们模块构造晚」
     * 的全部可能。而 {@link #tick} 只在<b>进了世界之后</b>才会被调用（调用方见
     * {@code ConfluxMapHelper#syncTravelWaypoint}：{@code mc.level == null} 时直接返回），
     * 所以这 30 秒实际是「进世界后的头 600 tick」。再长也没意义：conflux 的 install 要么在这之前
     * 就做完了，要么这个进程里永远不会做。
     */
    private static final int RETRY_BUDGET_TICKS = 600;

    /** 补探预算的剩余量（游戏 tick）；{@code <= 0} = 不再探、也不再打日志。只由主线程读写 */
    private static int retryTicksLeft = RETRY_BUDGET_TICKS;
    /** 距上次探测过了多少 tick；初始 = 间隔 - 1，让进世界后的<b>第一个</b> tick 就补探一次（只由主线程读写） */
    private static int ticksSinceProbe = RETRY_INTERVAL_TICKS - 1;

    static {
        String reason = probeOnce();
        if (reason != null) {
            Debug.getLogger()
                    .warn("ConfluxMapHelper: the travel-goal-sync waypoint is not available yet (" + reason
                            + "), falling back to the overlay marker for now; will keep probing every "
                            + RETRY_INTERVAL_TICKS + " ticks for " + (RETRY_BUDGET_TICKS / TICKS_PER_SECOND)
                            + "s");
        }
    }

    /**
     * 探测一次：成功就把实现挂到 {@link #IMPL} 上并返回 {@code null}，失败返回给日志看的原因
     * （同时记进 {@link #UNAVAILABLE_REASON}）。{@code <clinit>} 与 {@link #retryProbe()} 共用它。
     *
     * <p>「返回 {@code null}」与「{@link #IMPL} 非 null」严格等价 —— 补探那边靠这个判断成败。
     */
    private static String probeOnce() {
        try {
            String reason = probeMembers();
            if (reason == null) {
                // create() 要么给出实现、要么抛（它里面那个 Optional 是 orElseThrow）
                IMPL = ConfluxTravelWaypointSync.create();
                UNAVAILABLE_REASON = null;
                return null;
            }
            UNAVAILABLE_REASON = reason;
            return reason;
        } catch (Throwable e) {
            String reason = "cannot load " + ENTRY + ": " + e;
            UNAVAILABLE_REASON = reason;
            return reason;
        }
    }

    /**
     * 有界地补探 conflux 的 API：只在 {@link #tick} 里、主线程上跑，只碰本类的静态字段，不碰游戏状态。
     *
     * <p>每 {@link #RETRY_INTERVAL_TICKS} tick 一次、一共 {@link #RETRY_BUDGET_TICKS} tick
     * （≈ 30 秒）的预算：成功一次就永远不再进来（{@code tick} 只在 {@link #IMPL} 还是 null 时调它），
     * 预算耗尽则彻底停手 —— 不再探测、也不再打日志。
     *
     * <p>失败<b>不</b>打日志：原因已经在 {@code <clinit>} 那一行 WARN 里了。只有「补探成功」和
     * 「预算用尽仍不行」这两个转折点各补一行 info。
     */
    private static void retryProbe() {
        if (retryTicksLeft <= 0) {
            return;
        }
        // 预算里的最后一个 tick 无条件再探一次：好让「还是不行」那行日志正好落在预算的边界上
        boolean last = --retryTicksLeft <= 0;
        if (++ticksSinceProbe < RETRY_INTERVAL_TICKS && !last) {
            return;
        }
        ticksSinceProbe = 0;
        if (probeOnce() == null) {
            Debug.getLogger()
                    .info("ConfluxMapHelper: the conflux waypoint API became available, travel waypoint enabled");
        } else if (last) {
            Debug.getLogger()
                    .info("ConfluxMapHelper: the conflux waypoint API is still unavailable after "
                            + (RETRY_BUDGET_TICKS / TICKS_PER_SECOND)
                            + "s, travel-goal-sync keeps using the overlay marker");
        }
    }

    /**
     * 唯一的作用是「碰一下这个类、触发它的 {@code <clinit>} 探测」，本身不持有任何状态。
     *
     * <p>没有把它做成「一个工具类 + 全静态调用」是因为：那样模块里就没有任何一个<b>类的引用</b>，
     * 第一次调用发生在 {@code onPostGameTick} 里，探测失败的 WARN 会晚到几十秒。
     * 写成字段（在模块构造期 new 一下）能让这行 WARN 出现在启动日志里，
     * 与 {@code ConfluxMapHooks} 的自检同节奏，实机验收时一眼能看到。
     */
    public ConfluxTravelWaypointSyncHolder() {}

    /** {@code null} 表示探测通过；否则是给日志看的原因 */
    private static String probeMembers() {
        for (String[] signature : PROBE_SIGNATURES) {
            Class<?> owner;
            try {
                owner = Class.forName(signature[0]);
            } catch (Throwable e) {
                return signature[0] + " is missing (" + e + ")";
            }
            if (!hasMethod(owner, signature[1], signature[2], signature[3])) {
                return signature[0] + "#" + signature[1] + "(" + signature[2] + ") -> " + signature[3]
                        + " not found";
            }
        }
        return null;
    }

    /** 按「名字 + 形参类型名 + 返回类型名」找方法（形参写逗号分隔的类型名列表，空串 = 无形参） */
    private static boolean hasMethod(Class<?> owner, String name, String parameterTypes, String returnType) {
        String[] expected = parameterTypes.isEmpty() ? new String[0] : parameterTypes.split(",");
        for (Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            Class<?>[] actual = method.getParameterTypes();
            if (actual.length != expected.length) {
                continue;
            }
            if (!method.getReturnType().getName().equals(returnType)) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < expected.length; ++i) {
                if (!actual[i].getName().equals(expected[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 对外接口
    // 下面每一个都在里面再 try/catch(Throwable) 兜一层：探测通过 ≠ 运行期一定不炸
    // （conflux 的 store 可能因为会话切换 / 只读等原因抛），我们最多是「那两层不画」。

    /** conflux 的路径点 API 能不能真的用（false = 保留我们覆盖层上的临时标记） */
    public static boolean isAvailable() {
        return IMPL != null;
    }

    /** 取不到（conflux 没装 / 版本太老 / 结构变了）时的原因，可用时返回 null */
    public static String getUnavailableReason() {
        return UNAVAILABLE_REASON;
    }

    /**
     * 一个玩家 tick 一次；只在主线程调用。
     *
     * <p>实现还不存在时会顺带做一次<b>有界</b>的补探（见 {@link #retryProbe()}）：
     * 探到的那一 tick 直接走正常路径，不丢 tick。
     */
    public static void tick(boolean travelGoalSync, Vec3 target, String dimensionId) {
        ConfluxTravelWaypointSync impl = IMPL;
        if (impl == null) {
            retryProbe();
            impl = IMPL;
            if (impl == null) {
                return;
            }
        }
        try {
            impl.tick(travelGoalSync, target, dimensionId);
        } catch (Throwable e) {
            warnOnce("tick", e);
        }
    }

    /**
     * 现在「我们的标记已经由 conflux 的路径点负责」了吗？
     *
     * <p>绘制侧用它决定要不要再画覆盖层那一份：true = 别再画（避免同一个目标两份）。
     * <b>只有真的存在一个我们建出来、且 conflux 侧还认得的路径点时才为 true</b> ——
     * 建点失败 / 点被删 / 拿不到 API 时都是 false，于是自动退回覆盖层标记，
     * 「地图上什么都没有」这种最坏情况不会出现。
     */
    public static boolean shouldHideOwnMarker() {
        if (IMPL == null) {
            return false;
        }
        try {
            return IMPL.isPointLive();
        } catch (Throwable e) {
            warnOnce("query the waypoint state", e);
            return false;
        }
    }

    /** 模块被关掉 / 被移除 / 断开连接：立刻把我们的点删掉并清空状态 */
    public static void removeNow() {
        if (IMPL == null) {
            return;
        }
        try {
            IMPL.removeNow();
        } catch (Throwable e) {
            warnOnce("remove the travel waypoint", e);
        }
    }

    /** 异常只记一次日志（同一处失败每 tick 都会复现） */
    private static void warnOnce(String stage, Throwable e) {
        if (WARNED.compareAndSet(false, true)) {
            Debug.getLogger()
                    .warn("ConfluxMapHelper failed to " + stage
                            + " the conflux travel waypoint, travel-goal-sync gives up until restart", e);
        }
    }
}
