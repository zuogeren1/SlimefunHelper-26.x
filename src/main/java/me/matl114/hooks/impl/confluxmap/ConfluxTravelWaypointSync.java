package me.matl114.hooks.impl.confluxmap;

import cn.net.rms.confluxmap.api.WaypointApi;
import cn.net.rms.confluxmap.api.WaypointApi.ApiWaypoint;
import cn.net.rms.confluxmap.api.WaypointApi.WaypointEdit;
import cn.net.rms.confluxmap.api.WaypointApi.WaypointMutation;
import cn.net.rms.confluxmap.api.WaypointApi.WaypointType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import me.matl114.utils.Debug;
import net.minecraft.world.phys.Vec3;

/**
 * 把当前旅行目标同步成 conflux-map 的<b>真路径点</b>（对齐 {@code XaeroHelper#onXaeroTempWaypointSync}）。
 *
 * <p>这个类<b>直接引用</b> {@code cn.net.rms.confluxmap.api.*}，因此<b>只允许</b>经由
 * {@link ConfluxTravelWaypointSyncHolder}（先 {@code Class.forName} 探测过、且整段 try/catch）
 * 被加载；任何常加载的类都不要碰它（原因见 Holder 的类注释）。
 * 除了 Holder 之外，全仓的 {@code ConfluxMapApi} 字样都只出现在注释 / {@code {@code}} 里
 * —— 也就是说「conflux 没装时会不会炸」这个问题只有 Holder 一个答案点。
 *
 * <h2>生命周期状态机</h2>
 * <pre>
 *   IDLE ──旅行任务出现 + 开关打开 + 维度已知──▶ 建点（add）──▶ 跟随（update）
 *     ▲                                                        │
 *     └──── 任务结束 / 取消 / 维度变化 / 开关关闭 / 关模块 / 断线 ┘（remove）
 * </pre>
 * 触发条件与 {@code XaeroHelper} 一一对应：
 * <ul>
 *   <li><b>建</b>：本 tick 有旅行目标、开关为真、且我们手上没有活着的点；</li>
 *   <li><b>跟</b>：点已建好，目标的 XZ 走的距离 ≥ {@link #UPDATE_POSITION_EPSILON}
 *       （或 Y 变了），或距上次发送已过 {@link #UPDATE_INTERVAL_TICKS} tick；</li>
 *   <li><b>删</b>：旅行任务消失（到达 / 取消 / 被别的任务顶掉）、维度变化（含切世界）、
 *       开关被关、模块被关（{@code onDisableModule}）、模块被移除（{@code unregisterAll}）、
 *       断开连接（{@code serverLeavePoint}）。</li>
 * </ul>
 * 失败一律是「整条退回去」而不是「半死不活」：{@code disabled} 一旦置上就再也不建点，
 * 覆盖层标记顶上（{@code ConfluxTravelWaypointSyncHolder#shouldHideOwnMarker()} 从此为假）。
 * 唯一的例外是 {@code NOT_FOUND} —— 那表示用户自己把点删了，我们只放下这一个点、
 * 继续等下一个旅行任务，因为下一次 add 完全可能成功。
 *
 * <p>还有一个「顺手清垃圾」的入口：新任务开始时（每次任务只做一次、按维度缓存）先在
 * {@code ConfluxMapApi#waypoints()} 里找<b>名字与标记字母都精确等于我们那两个常量</b>的遗留点删掉
 * —— 上一次崩在 {@code remove} 之前、或直接被杀进程时会留下这种点。判断保守到「两个字段
 * 逐字相等」，用户自己起的 {@code [SFH] Travel} 会被误删，这是明知且接受的代价（见报告）。
 *
 * <h2>并发</h2>
 * 所有 {@code tick} / {@code removeNow} 都跑在客户端主线程上（事件监听器与 {@code onDisableModule} 都是）。
 * {@code add / update / remove} 返回的 {@link java.util.concurrent.CompletableFuture}，其回调
 * 在 conflux 侧是「主线程同步执行」：{@code ConfluxMapApiImpl.callOnMain} 的字节码是
 * 「已在渲染线程则当场执行 {@code Supplier} 并返回 {@code completedFuture}，否则投递到主线程后
 * complete」—— 两条路径的回调都落在主线程。
 * <b>但那是实现细节、不是契约</b>，所以回调里刻意只做两件事：读写 {@link #generation} /
 * {@link #liveId} 这些 volatile 字段、打日志；<b>不碰任何游戏状态</b>（不读 {@code mc.level}、
 * 不调 {@code Minecraft}）。将来真要在这里碰游戏状态，记得先
 * {@code Minecraft.getInstance().isSameThread()} 判断，不是主线程就 {@code execute(...)} 回主线程。
 * 时序用 {@link #generation} 卡：回调回来时若版本已变（点被删 / 换维度 / 关模块 / 断线）就不再改状态，
 * 若它刚建出一个点则当场删掉（见 {@link #discardLatePoint}）。
 */
public final class ConfluxTravelWaypointSync {
    /** 路径点名（与 {@code XaeroHelper} 版逐字一致） */
    public static final String WAYPOINT_NAME = "[SFH] Travel";
    /** 地图上的标记字母（与 {@code XaeroHelper} 版逐字一致） */
    public static final String WAYPOINT_MARKER = "T";
    /** 绿色，对应 {@code XaeroHelper} 版用的 {@code ChatFormatting.GREEN} */
    public static final int WAYPOINT_COLOR = 0xFF55FF55;
    /** y 夹取范围与取整规则照抄 {@code XaeroHelper} 版（见 {@link #targetBlockY}） */
    private static final double TARGET_Y_LIMIT = 512.0;
    /** 目标移动超过这么多格才发一次 update（避免每 tick 一次写操作） */
    private static final double UPDATE_POSITION_EPSILON = 0.5;
    /** 目标几乎没动时，最多隔这么多 tick 也刷一次（保证「一直没动」时 conflux 侧不会落后太久） */
    private static final int UPDATE_INTERVAL_TICKS = 100;
    /**
     * 同一维度内「刚删完又要在原地重建」时，等这么多 tick 再发 add。
     *
     * <p>为什么要等：删与建是两个独立的写请求，conflux 的 {@code WaypointMutation} 是
     * <b>同步</b>落地的（{@code ConfluxMapApiImpl.callOnMain} 在渲染线程上当场执行并返回
     * {@code completedFuture}），所以一 tick 就够；留几 tick 是给「万一它哪天改成异步落盘」的护栏
     * —— 真异步时先 add 后 remove 会把刚建的点删掉，反过来只是晚 4 tick 出现。
     */
    private static final int READD_COOLDOWN_TICKS = 4;
    /** 「只 warn 一次」的闸（不同阶段各自一把，免得先失败的那个把后面所有日志吃掉） */
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED_ADD =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED_UPDATE =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final WaypointApi waypoints;
    private final WaypointType normalType;

    /** 我们那个点的 UUID；{@code null} = 手上没有活着的点 */
    private volatile UUID liveId;
    /** 点所属维度（{@code namespace:path}）：删点时要用它把 conflux 的「当前」存储切回去 */
    private volatile String liveDimension;
    /** 点所属的路径点集合名（{@code ApiWaypoint#setName()}）：更新时带上它，别把点挪到别的集合 */
    private volatile String liveGroup;
    /** 本版本里「最后一次请求发送出去」的位置；{@code null} = 还没发过 */
    private volatile Vec3 lastSent;
    /** 距离上次发送过了多少 tick（只由主线程读写） */
    private int positionTicks;
    /** 版本号：任何「点作废」的动作都 +1，回调靠它判断自己是不是过期了 */
    private volatile int generation;
    /** {@code add} 在飞（防止同一个任务里重复建点） */
    private volatile boolean adding;
    /** 已经「顺手清过垃圾」的维度集合（每个维度每个游戏进程一次；主线程独占，不需要同步） */
    private final List<String> sweptDimensions = new ArrayList<>();
    /** 同维度重建点前的冷却计时（只由主线程读写） */
    private int readdCooldown;
    /**
     * 这条路已经确定走不通了（conflux 明确拒绝写 / 我们自己判定它不可用）：
     * 从此不再尝试建点，覆盖层标记顶上 —— 不然每 tick 都会重试一次注定失败的写操作。
     */
    private boolean disabled;

    private ConfluxTravelWaypointSync(WaypointApi waypoints, WaypointType normalType) {
        this.waypoints = waypoints;
        this.normalType = normalType;
    }

    /**
     * 由 Holder 在探测通过后调用；失败（拿不到实例 / 拿不到 {@code NORMAL}）由 Holder 兜住并降级。
     *
     * <p>{@code ConfluxMapApi.instance()} 在 conflux 没装或版本太老时是
     * {@code Optional.empty()}（它由 conflux 自己的 {@code ConfluxMapApiImpl} 在初始化时 install）。
     */
    static ConfluxTravelWaypointSync create() throws Exception {
        // 入口是接口静态方法（javap 实测：{@code public static Optional<ConfluxMapApi> instance()}），
        // 反射调用省掉一个「只有这一处用到」的 import —— 这本来就是为老版本 conflux 准备的分支。
        Class<?> entry = Class.forName("cn.net.rms.confluxmap.api.ConfluxMapApi");
        java.util.Optional<?> instance =
                (java.util.Optional<?>) entry.getMethod("instance").invoke(null);
        Object api = instance.orElseThrow(() -> new IllegalStateException(
                "ConfluxMapApi.instance() is empty (conflux-map is too old)"));
        WaypointApi waypoints = (WaypointApi) entry.getMethod("waypoints").invoke(api);
        // waypoints() 在 conflux 还没建好会话时返回的对象一样能用（list() 给空表、写操作给
        // NO_SESSION），所以这里不需要等到有会话才建这个对象。
        return new ConfluxTravelWaypointSync(waypoints, WaypointType.NORMAL);
    }

    // ------------------------------------------------------------------ 对外

    /** 一个玩家 tick 一次（主线程） */
    void tick(boolean travelGoalSync, Vec3 target, String dimensionId) {
        if (disabled) {
            return;
        }
        boolean wantPath = travelGoalSync && target != null;

        if (!wantPath) {
            // 到达 / 取消（target == null）或开关被关：把点删掉，状态回到 IDLE
            if (liveId != null) {
                removeLivePoint("the travel task is gone or travel-goal-sync was turned off");
            }
            return;
        }
        if (dimensionId == null || dimensionId.isEmpty()) {
            // 维度读不到时不建点：宁可这一 tick 不建，也不要往错的维度里塞（下一 tick 会重来）
            if (liveId != null) {
                removeLivePoint("the current dimension is unknown");
            }
            return;
        }
        if (liveId != null && !Objects.equals(liveDimension, dimensionId)) {
            // 切维度 / 切世界：目标所在的世界变了，旧点必须删掉再在新维度里重建
            removeLivePoint("the dimension changed to " + dimensionId);
        }
        if (adding) {
            return;
        }
        if (liveId != null) {
            if (needsUpdate(target)) {
                sendUpdate(target, dimensionId);
            }
            return;
        }
        if (readdCooldown > 0) {
            // 刚在同一维度删掉一个点：等它先落地，再发新的 add（见 READD_COOLDOWN_TICKS）
            --readdCooldown;
            return;
        }
        // 真正的「这个维度第一次要点」才去清遗留（不是每个 tick 都列一遍路径点表）
        sweepLeftoversOnce(dimensionId);
        createPoint(target, dimensionId);
    }

    /**
     * conflux 侧是不是已经有一个活的、属于我们的路径点。
     *
     * <p>只认「我们亲手建出来的那个 UUID」—— 不用名字去反查：名字可能撞上用户自己建的
     * {@code [SFH] Travel}，那时候把我们的覆盖层收掉、画的却是别人的点，反而更糟。
     */
    boolean isPointLive() {
        return !disabled && liveId != null;
    }

    /** 关模块 / 断线：立刻删点并清空（不再重建） */
    void removeNow() {
        // 同样不看 {@code disabled}：万一放弃的那一刻手上还有点，这里仍要把它清掉
        if (liveId != null) {
            removeLivePoint("ConfluxMapHelper was turned off or the server was left");
        }
        lastSent = null;
        positionTicks = 0;
    }

    // ------------------------------------------------------------------ 建 / 跟 / 删

    private void createPoint(Vec3 target, String dimensionId) {
        WaypointEdit edit = buildEdit(target, dimensionId);
        int request = generation;
        adding = true;
        try {
            waypoints.add(edit).whenComplete((mutation, error) -> onAddDone(request, mutation, error));
        } catch (Throwable e) {
            adding = false;
            disabled = true;
            warnOnce(WARNED_ADD, "create", e);
        }
    }

    private void sendUpdate(Vec3 target, String dimensionId) {
        UUID id = liveId;
        if (id == null) {
            return;
        }
        WaypointEdit edit = buildEdit(target, dimensionId);
        lastSent = target;
        positionTicks = 0;
        int request = generation;
        try {
            waypoints.update(id, edit).whenComplete((mutation, error) -> onUpdateDone(request, mutation, error));
        } catch (Throwable e) {
            disabled = true;
            warnOnce(WARNED_UPDATE, "update", e);
        }
    }

    /**
     * 删掉 {@link #liveId} 那个点。
     *
     * <p>调之前要先让 {@link #liveDimension} 指向它所属的维度：conflux 的写操作落在
     * {@code WaypointService.current()}（也就是「当前会话的那个 world / dimension 的存储」）上，
     * <b>但只删自己记下的那个 UUID</b>，点不在这个存储里时结果是 {@code NOT_FOUND}、什么都不动
     * —— 所以跨维度删不掉时不会误伤别人，只是留下一个点（下次启动的顺手清理会收掉）。
     * 这个前置条件由 {@link #tick} 保证（发现维度变了、先删再重建）。
     */
    private void removeLivePoint(String reason) {
        // 注意：这里<b>不看</b> {@link #disabled} —— 「放弃」的最后一步恰恰就是删掉已经建出来的那个点。
        UUID id = liveId;
        String dimension = liveDimension;
        liveId = null;
        liveDimension = null;
        liveGroup = null;
        lastSent = null;
        positionTicks = 0;
        readdCooldown = READD_COOLDOWN_TICKS;
        ++generation;
        if (id == null) {
            return;
        }
        try {
            waypoints.remove(id)
                    .whenComplete((mutation, error) -> logMutation("remove", reason, mutation, error));
        } catch (Throwable e) {
            Debug.getLogger()
                    .warn("ConfluxMapHelper failed to remove the conflux travel waypoint of " + dimension, e);
        }
    }

    private void sweepLeftoversOnce(String dimensionId) {
        if (sweptDimensions.contains(dimensionId)) {
            return;
        }
        sweptDimensions.add(dimensionId);
        List<ApiWaypoint> all;
        try {
            all = waypoints.list();
        } catch (Throwable e) {
            Debug.getLogger().warn("ConfluxMapHelper failed to scan the conflux waypoint list for leftovers", e);
            return;
        }
        if (all == null) {
            return;
        }
        for (ApiWaypoint waypoint : all) {
            if (waypoint == null
                    || !WAYPOINT_NAME.equals(waypoint.name())
                    || !WAYPOINT_MARKER.equals(waypoint.markerLabel())) {
                continue;
            }
            Debug.getLogger()
                    .info(
                            "[ConfluxMapHelper] removing the leftover travel waypoint {} ({}) of {}",
                            waypoint.id(),
                            waypoint.name(),
                            waypoint.dimensionId());
            try {
                waypoints.remove(waypoint.id())
                        .whenComplete((mutation, error) -> logMutation(
                                "remove leftover", waypoint.name(), mutation, error));
            } catch (Throwable e) {
                Debug.getLogger().warn("ConfluxMapHelper failed to remove the leftover travel waypoint", e);
            }
        }
    }

    // ------------------------------------------------------------------ 回调

    private void onAddDone(int request, WaypointMutation mutation, Throwable error) {
        if (generation == request) {
            adding = false;
        }
        if (error != null) {
            warnOnce(WARNED_ADD, "create", error);
            return;
        }
        if (mutation == null || mutation.waypoint() == null) {
            warnOnce(WARNED_ADD, "create", new IllegalStateException("conflux returned no waypoint: " + mutation));
            return;
        }
        if (generation != request) {
            // 过期回调：这中间任务结束 / 切了维度 / 关了模块，刚建出来的点必须当场清掉
            discardLatePoint(mutation.waypoint().id());
            return;
        }
        if (!isApplied(mutation)) {
            // 这里是「conflux 明确拒绝」（只读存档 / 没有会话 / INVALID ...）：重试也是同样结果，
            // 记一次日志就整条退回去，别再每 tick 发一次注定失败的写请求。
            disabled = true;
            warnOnce(WARNED_ADD, "create", new IllegalStateException("conflux refused the waypoint: "
                    + mutation.result() + ", falling back to the overlay marker"));
            return;
        }
        liveId = mutation.waypoint().id();
        liveDimension = mutation.waypoint().dimensionId();
        liveGroup = mutation.waypoint().setName();
        lastSent = null;
        positionTicks = UPDATE_INTERVAL_TICKS;
        Debug.getLogger()
                .info(
                        "[ConfluxMapHelper] travel waypoint created: {} ({}) in {}",
                        liveId,
                        WAYPOINT_NAME,
                        liveDimension);
    }

    private void onUpdateDone(int request, WaypointMutation mutation, Throwable error) {
        if (generation != request) {
            return;
        }
        if (error != null) {
            warnOnce(WARNED_UPDATE, "update", error);
            return;
        }
        if (mutation == null) {
            return;
        }
        WaypointMutation.Result result = mutation.result();
        if (result == WaypointMutation.Result.NOT_FOUND) {
            // 用户在 conflux 侧删掉了我们的点：认账，退回覆盖层标记，别每 tick 再刷一次无效 update
            Debug.getLogger()
                    .info(
                            "[ConfluxMapHelper] the travel waypoint {} was removed on the conflux side, "
                                    + "falling back to the overlay marker",
                            liveId);
            liveId = null;
            liveDimension = null;
            liveGroup = null;
            lastSent = null;
            positionTicks = 0;
            ++generation;
            return;
        }
        if (!isApplied(mutation)) {
            // 更新被拒（只读 / 没有会话 ...）：点还在，但我们已经跟不动它了。与其让它停在一个
            // 过期坐标上，不如把它删掉、整条退回覆盖层 —— 覆盖层至少是实时跟着目标走的。
            Debug.getLogger()
                    .info(
                            "[ConfluxMapHelper] conflux did not apply the travel waypoint update ({}), "
                                    + "falling back to the overlay marker",
                            result);
            removeLivePoint("conflux stopped applying our updates");
            disabled = true;
        }
    }

    /** 过期 add 回调用：把刚建出来的那个点删掉（它已经不在我们的状态机里了） */
    private void discardLatePoint(UUID id) {
        if (disabled) {
            return;
        }
        Debug.getLogger().info("[ConfluxMapHelper] discarding a travel waypoint created too late: {}", id);
        try {
            waypoints.remove(id).whenComplete((mutation, error) -> logMutation("discard late", String.valueOf(id), mutation, error));
        } catch (Throwable e) {
            Debug.getLogger().warn("ConfluxMapHelper failed to discard a late travel waypoint", e);
        }
    }

    private static void logMutation(String stage, String detail, WaypointMutation mutation, Throwable error) {
        if (error != null) {
            Debug.getLogger().warn("ConfluxMapHelper failed to " + stage + " the conflux travel waypoint", error);
        } else if (mutation != null && mutation.result() != WaypointMutation.Result.APPLIED) {
            // 删一个已经不存在的点时 conflux 会给 NOT_FOUND，这是正常的，不当异常报
            Debug.getLogger().info("[ConfluxMapHelper] {} ({}) -> {}", stage, detail, mutation.result());
        }
    }

    private static boolean isApplied(WaypointMutation mutation) {
        return mutation.result() == WaypointMutation.Result.APPLIED
                || mutation.result() == WaypointMutation.Result.NO_CHANGE;
    }

    private static void warnOnce(java.util.concurrent.atomic.AtomicBoolean gate, String stage, Throwable e) {
        if (gate.compareAndSet(false, true)) {
            Debug.getLogger()
                    .warn("ConfluxMapHelper failed to " + stage
                            + " the conflux travel waypoint, travel-goal-sync stays on the overlay marker from now on",
                            e);
        }
    }

    // ------------------------------------------------------------------ 编辑对象 / 坐标

    private WaypointEdit buildEdit(Vec3 target, String dimensionId) {
        WaypointEdit.Builder builder = WaypointEdit.builder()
                .name(WAYPOINT_NAME)
                .dimensionId(dimensionId)
                .position(target.x, targetBlockY(target), target.z)
                .colorArgb(WAYPOINT_COLOR)
                .visible(true)
                // conflux 的跨维度显隐：与 XaeroHelper 版一致置 true（切维度时点仍可见），
                // 真正的「跨维度」生命周期由我们删/重建来管
                .crossDimensionVisible(true)
                .type(normalType)
                .markerLabel(WAYPOINT_MARKER);
        String group = liveGroup;
        if (group != null) {
            // 只看得到 ApiWaypoint#setName()，它的来源是 Waypoint#group（javap 实测 ApiMappers.toApi）。
            // 不带上它，conflux 会按 null -> "" 落到默认集合，把点从用户选中的集合里挪出去。
            builder.group(group);
        }
        // iconItemId 故意不设：conflux 那边 null -> ""，也就是用它自己那套默认图标，
        // 不抢用户/他们配置里选的图标。
        return builder.build();
    }

    /**
     * 目标的方块 Y，规则与 {@code XaeroHelper} 版逐字一致：先夹到 {@code [-512, 512]} 再截断取整。
     *
     * <p><b>为什么照抄夹取</b>：旅行目标来自 {@code TravelInfo#getCurrentFlyingTarget()}，在
     * {@code pos0} 缺失时会退化成「玩家位置 + 朝向 10000 格」，y 直接取玩家 y；
     * 正常路径下它就是玩家用 {@code /!!travel to} 给的目的地，y 也是玩家给的。
     * 也就是说 y 是<b>来源不可控</b>的输入：超界（世界高度上限、或者别的模组算出来的
     * 怪值）会让路径点落在地图可绘区之外、甚至叠到别的维度的高度带上。
     * Xaero 版早就用 512 兜了这一手，两个实现共用同一个可观测行为更容易验收与对表；
     * 而且路径点是<b>持久数据</b>（会被写进存档），比覆盖层那个「只画一帧」的标记更需要护栏。
     */
    private static double targetBlockY(Vec3 target) {
        return (int) Math.clamp(target.y, -TARGET_Y_LIMIT, TARGET_Y_LIMIT);
    }

    /** 目标动了没有：XZ 走得够远、或 Y 变了、或距上次发送够久 */
    private boolean needsUpdate(Vec3 target) {
        Vec3 sent = lastSent;
        if (sent == null) {
            return true;
        }
        if (sent.y != target.y) {
            return true;
        }
        double dx = target.x - sent.x;
        double dz = target.z - sent.z;
        if (dx * dx + dz * dz >= UPDATE_POSITION_EPSILON * UPDATE_POSITION_EPSILON) {
            return true;
        }
        return ++positionTicks >= UPDATE_INTERVAL_TICKS;
    }

}
