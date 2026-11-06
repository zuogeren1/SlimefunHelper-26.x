package me.zuogeren.slimefunhelperplus.module.conflux.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.modules.survival.XaeroHelper;
import me.matl114.hooks.XaeroHooks;
import me.matl114.hooks.impl.xaerowaypoints.IXWaypoint;
import me.matl114.hooks.impl.xaerowaypoints.IXWaypointAccess;
import me.matl114.hooks.impl.xaerowaypoints.IXWaypointFactory;
import me.matl114.utils.Debug;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Xaero 路径点流量的只读捕获器：验证「宿主的 StructureLabel / SearchLabel / EntityLog / Warps
 * 提交的点，能不能原样拿来做 conflux 的标记」。
 *
 * <p><b>为什么是反射而不是 mixin</b>：这四个模块都通过 {@link IXWaypointFactory} 出厂、
 * 经 {@link IXWaypointAccess} 挂到当前世界。只要把 {@code XaeroHooks#minimap} 换成一个
 * 「永远说自己启用、工厂返回我们自己的实现」的桩，宿主那一整套
 * {@code XaeroHelper.createWaypoint / submitWaypoint / updateWaypoint / removeWaypoint / removeSub}
 * 就会原封不动跑到我们这里 —— 宿主零改动，四个模块一次全覆盖。
 *
 * <p><b>只捕获、不渲染</b>：这一版只往日志里写数字与样本，用来回答三个问题：
 * 点有多少、长什么样（名字/坐标/颜色/是否临时）、什么时候被删。渲染是下一步的事，
 * 因为坐标尺度与颜色映射都还没实测过。
 *
 * <p><b>默认不动别人的数据</b>：开关默认关，关掉即还原成原来的 hooks 实例。开启期间 SFH 的临时点
 * 不再进 Xaero 的路径点集合（被本探针接管），所以装 Xaero 的实例上打开它会在日志里留一条 WARN ——
 * 这是验证期有意的行为，不改 Xaero 已有的任何路径点，关掉开关立刻恢复。
 *
 * <p><b>副作用（预期内）</b>：宿主 {@code XaeroHelper.onXaeroTempWaypointSync()} 会以为小地图在，
 * 于是「同步旅行目标」那条也会跑起来，把 {@code [SFH] Travel} 提交到这里。这不影响玩家看到的东西
 * （我们不渲染），但在统计里会多出一个 {@code XaeroHelper/Travel} 类别 —— 那正是它的用途：
 * 顺带确认这条链路也是通的。
 */
public final class WaypointCaptureProbe {

    /** 汇总节奏：200 tick（10 秒）一行，够密也不会刷屏 */
    private static final int LOG_INTERVAL_TICKS = 200;

    /** 汇总里最多列几个样本 */
    private static final int MAX_SAMPLES = 6;

    private final Object lock = new Object();

    /** 当前活着的点。用 identity 语义：宿主传进来的是同一批对象引用 */
    private final Map<IXWaypoint, Boolean> live = new IdentityHashMap<>();

    /** 顺序视图，供 {@link IXWaypointAccess#get(int)} / {@code remove(int)} 使用 */
    private final List<IXWaypoint> order = new ArrayList<>();

    private final ProbeAccess access = new ProbeAccess();

    private long created;
    private long added;
    private long removed;
    private long updated;
    private long refreshes;
    private long ticks;
    private long lastLoggedTick = -LOG_INTERVAL_TICKS;

    private boolean installed;
    private boolean installFailed;
    private Field minimapField;
    private Object originalHooks;

    /**
     * 把 {@code XaeroHooks#minimap} 换成捕获桩。
     *
     * <p>幂等：已经装上就直接返回。真 Xaero 小地图在场时照样接管（验证期需要），但会在日志里留 WARN；
     * 失败只记一次，不按 tick 重试。
     */
    public boolean install() {
        if (installed) {
            return true;
        }
        if (installFailed) {
            // 每 tick 都会调到这里；失败一次就停手，否则反射被拒时日志会按 20 条/秒刷屏
            return false;
        }
        try {
            XaeroHooks hooks = XaeroHooks.getInstance();
            // 必须在替换之前问：替换之后这个判断只会回答「我们自己在」
            boolean realMinimap = hooks.isXaeroMiniMapEnable();
            Field field = XaeroHooks.class.getDeclaredField("minimap");
            field.setAccessible(true);
            Object original = field.get(hooks);
            field.set(hooks, new ProbeMiniMapHooks(this));
            this.minimapField = field;
            this.originalHooks = original;
            this.installed = true;
            if (realMinimap) {
                // 这是验证用的开关，默认关；打开时接管是有意的，但代价必须留在日志里
                Debug.getLogger().warn("[ConfluxMapHelper] xaero waypoint probe took over a LIVE xaero minimap:"
                        + " while this flag is on, SFH's temporary waypoints go to the probe instead of xaero");
            }
            Debug.getLogger().info("[ConfluxMapHelper] xaero waypoint probe installed: XaeroHooks#minimap now returns the capture factory");
            return true;
        } catch (Throwable e) {
            installFailed = true;
            Debug.getLogger().warn("[ConfluxMapHelper] xaero waypoint probe could not install; nothing was replaced", e);
            return false;
        }
    }

    /** 还原成装之前的 hooks 实例，并清空已捕获的点（避免关掉开关后还占着内存） */
    public void uninstall() {
        // 关掉开关视为「下次重试」，失败标记跟着开关走
        installFailed = false;
        if (!installed) {
            return;
        }
        installed = false;
        try {
            if (minimapField != null) {
                minimapField.set(XaeroHooks.getInstance(), originalHooks);
            }
        } catch (Throwable e) {
            Debug.getLogger().warn("[ConfluxMapHelper] xaero waypoint probe could not restore XaeroHooks#minimap", e);
        }
        minimapField = null;
        originalHooks = null;
        purgeHostBindings();
        synchronized (lock) {
            live.clear();
            order.clear();
        }
        Debug.getLogger().info("[ConfluxMapHelper] xaero waypoint probe removed");
    }

    /**
     * 把宿主 {@code XaeroHelper#submittedWaypoints} 里<b>属于本探针</b>的绑定摘掉。
     *
     * <p>不做这一步会崩：宿主每次 tick 都会 {@code syncWaypointAccess()}，一旦工厂换回真 Xaero，
     * 它会把 {@code submittedWaypoints} 里的对象<b>原样</b> attach 回真集合，而
     * {@code XaeroWaypointSetImpl} 的每个方法都走 {@code WaypointWrapper.unwrap(...)} 的强制转换 ——
     * 我们那种「不是 WaypointWrapper」的对象进去就是 ClassCastException。
     *
     * <p>只摘自己造的：探针装上之前就存在的真 WaypointWrapper 必须留着，它们换回去之后还要继续用。
     */
    private void purgeHostBindings() {
        try {
            XaeroHelper helper = XaeroHelper.INSTANCE;
            if (helper == null) {
                return;
            }
            Field bindings = XaeroHelper.class.getDeclaredField("submittedWaypoints");
            bindings.setAccessible(true);
            if (bindings.get(helper) instanceof List<?> list) {
                list.removeIf(this::isOwnBinding);
            }
            Field travel = XaeroHelper.class.getDeclaredField("travelPoint");
            travel.setAccessible(true);
            if (travel.get(helper) instanceof IXWaypoint waypoint && waypoint instanceof ProbeWaypoint) {
                travel.set(helper, null);
            }
            Field accessField = XaeroHelper.class.getDeclaredField("access");
            accessField.setAccessible(true);
            if (accessField.get(helper) == access) {
                accessField.set(helper, null);
            }
        } catch (Throwable e) {
            Debug.getLogger().warn("[ConfluxMapHelper] xaero waypoint probe could not purge the host waypoint bindings", e);
        }
    }

    /** {@code WaypointBinding} 是宿主里的 record，取值器叫 {@code waypoint()}；跨包只能反射读 */
    private boolean isOwnBinding(Object binding) {
        try {
            Method accessor = binding.getClass().getDeclaredMethod("waypoint");
            accessor.setAccessible(true);
            return accessor.invoke(binding) instanceof ProbeWaypoint;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 给渲染侧的一次性快照。
     *
     * <p>交出去的是「已经脱下 {@code IXWaypoint}」的值对象：conflux 的取样在自己节奏上跑，
     * 不能让它拿着内部对象去做类型判断，也不该让它看见宿主接口。
     */
    public List<CapturedPoint> capture() {
        synchronized (lock) {
            List<CapturedPoint> points = new ArrayList<>(order.size());
            for (IXWaypoint waypoint : order) {
                if (waypoint instanceof ProbeWaypoint point) {
                    points.add(new CapturedPoint(
                            point.uid(),
                            point.dimension(),
                            point.getX(),
                            point.getY(),
                            point.getZ(),
                            point.getName(),
                            point.getInitials(),
                            point.getColor(),
                            point.preScaled(),
                            point.coordinateScale()));
                }
            }
            return points;
        }
    }

    /** 一个捕获到的点的值视图（见 {@link #capture()}） */
    public record CapturedPoint(
            long uid,
            ResourceKey<Level> dimension,
            double x,
            double y,
            double z,
            String name,
            String initials,
            int color,
            boolean preScaled,
            double coordinateScale) {}

    /**
     * 每个客户端 tick 调一次。
     *
     * @param verbose 是否按节奏打汇总。控制台探针与「真正渲染」共用这套捕获，
     *                渲染时不需要那六行样本，日志交给 {@code verbose} 决定
     */
    public void tick(boolean verbose) {
        if (!installed) {
            return;
        }
        long current;
        synchronized (lock) {
            current = ++ticks;
        }
        if (!verbose || current - lastLoggedTick < LOG_INTERVAL_TICKS) {
            return;
        }
        lastLoggedTick = current;
        logSummary(current);
    }

    private void logSummary(long currentTick) {
        List<IXWaypoint> snapshot;
        long createdNow;
        long addedNow;
        long removedNow;
        long updatedNow;
        long refreshNow;
        synchronized (lock) {
            snapshot = new ArrayList<>(order);
            createdNow = created;
            addedNow = added;
            removedNow = removed;
            updatedNow = updated;
            refreshNow = refreshes;
        }
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (IXWaypoint waypoint : snapshot) {
            kinds.merge(classify(waypoint.getName()), 1, Integer::sum);
        }
        Debug.getLogger()
                .info("[ConfluxMapHelper] waypoint probe t={} live={} created={} added={} removed={} updated={} refresh={} kinds={}",
                        currentTick, snapshot.size(), createdNow, addedNow, removedNow, updatedNow, refreshNow, kinds);
        Debug.getLogger().info("[ConfluxMapHelper] waypoint probe host: " + hostState());
        int shown = Math.min(snapshot.size(), MAX_SAMPLES);
        for (int i = 0; i < shown; ++i) {
            Debug.getLogger().info("[ConfluxMapHelper]   probe#" + (i + 1) + " " + describe(snapshot.get(i)));
        }
    }

    /**
     * 宿主侧状态，用来一眼判断链路断在哪一环。
     *
     * <p>{@code accessAttached=false}：宿主 {@code submitWaypoint} 只会往它自己的列表里塞，
     * 永远到不了 {@link ProbeAccess}，此时 {@code hostBindings} 会涨而 {@code live} 不动。
     *
     * <p>{@code modules} 里 {@code absent} = 模块还没被创建（宿主按需建组），
     * {@code false} = 模块存在但开关没开 —— 这两种情况下 {@code created=0} 是预期结果，不是接错了。
     */
    private String hostState() {
        boolean helperPresent = false;
        boolean accessAttached = false;
        int hostBindings = -1;
        try {
            XaeroHelper helper = XaeroHelper.INSTANCE;
            helperPresent = helper != null;
            if (helper != null) {
                Field accessField = XaeroHelper.class.getDeclaredField("access");
                accessField.setAccessible(true);
                accessAttached = accessField.get(helper) == access;
                Field bindingsField = XaeroHelper.class.getDeclaredField("submittedWaypoints");
                bindingsField.setAccessible(true);
                if (bindingsField.get(helper) instanceof Collection<?> collection) {
                    hostBindings = collection.size();
                }
            }
        } catch (Throwable e) {
            return "unreadable: " + e;
        }
        return "helper=" + helperPresent + " accessAttached=" + accessAttached
                + " hostBindings=" + hostBindings + " modules=" + moduleStates();
    }

    /** 四个点源模块自己的开关状态 */
    private static String moduleStates() {
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < MODULE_HANDLES.length; ++i) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(MODULE_HANDLES[i][2]).append('=').append(moduleState(MODULE_HANDLES[i][0], MODULE_HANDLES[i][1]));
        }
        return text.append('}').toString();
    }

    private static String moduleState(String ownerName, String fieldName) {
        try {
            Field field = Class.forName(ownerName).getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            if (value == null) {
                return "absent";
            }
            return value instanceof BaseModule module ? String.valueOf(module.isActive()) : "?";
        } catch (Throwable e) {
            return "unknown";
        }
    }

    /** 四个点源模块的持有位置：{@code 类名, 静态字段, 展示名} */
    private static final String[][] MODULE_HANDLES = {
        {"me.matl114.hacks.SurvivalTasks", "structureLabel", "StructureLabel"},
        {"me.matl114.hacks.SurvivalTasks", "searchLabel", "SearchLabel"},
        {"me.matl114.hacks.RenderTasks", "entityLog", "EntityLog"},
        {"me.matl114.hacks.ExtraTasks", "warps", "Warps"},
    };

    private static String describe(IXWaypoint waypoint) {
        String name = waypoint.getName();
        if (name != null) {
            // 多行名字（SearchLabel）压成一行，日志才读得懂
            name = name.replace("\r", "").replace("\n", " | ");
        }
        return "name=\"" + name + "\""
                + " xyz=(" + waypoint.getX() + "," + waypoint.getY() + "," + waypoint.getZ() + ")"
                + " color=" + waypoint.getColor()
                + " purpose=" + waypoint.getPurpose()
                + " temp=" + waypoint.isTemp()
                + " initials=\"" + waypoint.getInitials() + "\"";
    }

    /**
     * 按名字前缀推断来源模块。
     *
     * <p>为什么靠名字而不是 owner：{@code IXWaypointAccess#add} 拿不到 owner ——
     * owner 只存在于宿主的 {@code submittedWaypoints} 私有列表里，而本路径刻意不 mixin 宿主。
     * 四个模块的命名恰好互不重叠（{@code $} 前缀 / {@code [SFH] ...} / {@code Label:} / 结构名），
     * 所以这条推断在验证阶段足够准；等真渲染时再换成宿主侧的显式归属。
     *
     * <p>未知前缀统一记 {@code other}：出现了就说明有我们没预料到的点源，值得单独看一眼。
     */
    static String classify(String name) {
        if (name == null) {
            return "unknown";
        }
        if (name.startsWith("$")) {
            return "Warps";
        }
        if (name.startsWith("[SFH] Faraway:")) {
            return "EntityLog/Faraway";
        }
        if (name.startsWith("[SFH] ReLogin:")) {
            return "EntityLog/ReLogin";
        }
        if (name.startsWith("[SFH] Log:")) {
            return "EntityLog/Log";
        }
        if (name.startsWith("[SFH] Travel")) {
            return "XaeroHelper/Travel";
        }
        if (name.startsWith("Label:")) {
            return "SearchLabel";
        }
        if (name.startsWith("Ancient City")) {
            return "StructureLabel/AC";
        }
        if (name.startsWith("Trial Chambers")) {
            return "StructureLabel/TC";
        }
        if (name.startsWith("Desert Pyramid")) {
            return "StructureLabel/DP";
        }
        if (name.startsWith("Ruined Portal")) {
            return "StructureLabel/RP";
        }
        if (name.startsWith("End City") || name.startsWith("Elytra End City")) {
            return "StructureLabel/EndCity";
        }
        return "other";
    }

    /**
     * 这个来源提交前是否乘过 {@code coordinateScale}。
     *
     * <p>依据是四个模块的现有写法：StructureLabel（{@code createWaypointSpec}）、SearchLabel
     * （{@code createWaypoint}）、Warps（{@code addCurrentWaypoint}）都乘了
     * {@code dimensionType().coordinateScale()}；EntityLog 与 XaeroHelper 的旅行点没有乘。
     * 未知来源按「没乘」处理 —— 除错一次会同时错掉位置和后续的跨维度换算，宁可不除。
     */
    static boolean isPreScaled(String name) {
        String kind = classify(name);
        return kind.startsWith("StructureLabel") || "SearchLabel".equals(kind) || "Warps".equals(kind);
    }

    private void onAdd(IXWaypoint waypoint) {
        if (waypoint == null) {
            return;
        }
        synchronized (lock) {
            if (live.put(waypoint, Boolean.TRUE) == null) {
                order.add(waypoint);
            }
            ++added;
        }
    }

    private void onRemove(IXWaypoint waypoint) {
        if (waypoint == null) {
            return;
        }
        synchronized (lock) {
            if (live.remove(waypoint) != null) {
                order.remove(waypoint);
                ++removed;
            }
        }
    }

    private void onUpdate() {
        synchronized (lock) {
            ++updated;
        }
    }

    private void onRefresh() {
        synchronized (lock) {
            ++refreshes;
        }
    }

    /** 装给 {@code XaeroHooks} 的桩：只要「说自己在」+ 交出工厂，宿主其余分支就会走我们的实现 */
    static final class ProbeMiniMapHooks extends XaeroHooks.XaeroMiniMapHooks {
        private final WaypointCaptureProbe probe;

        ProbeMiniMapHooks(WaypointCaptureProbe probe) {
            this.probe = probe;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public IXWaypointFactory getWaypointFactory() {
            return probe.access.factory;
        }
    }

    /** 工厂：宿主每次 {@code createWaypoint} 都落到这里 */
    final class ProbeFactory implements IXWaypointFactory {
        @Override
        public IXWaypoint createWaypoint(
                int x, int y, int z, String name, String initials, int color, int type, boolean temp, boolean yIncluded) {
            long uid;
            synchronized (lock) {
                uid = ++created;
            }
            Level level = Minecraft.getInstance().level;
            return new ProbeWaypoint(
                    level == null ? null : level.dimension(),
                    uid,
                    isPreScaled(name),
                    level == null ? 1.0 : level.dimensionType().coordinateScale(),
                    x,
                    y,
                    z,
                    name,
                    initials,
                    color,
                    type,
                    temp,
                    yIncluded);
        }

        @Override
        public IXWaypointAccess getCurrentWaypointSet() {
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.level == null ? null : access;
        }

        @Override
        public ResourceKey<Level> getCurrentWorld() {
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.level == null ? null : mc.level.dimension();
        }
    }

    /**
     * 世界的路径点集合：宿主的 add / remove / update / requestRefresh 全部落在这里。
     *
     * <p>语义上它只是「一个会记账的列表」——不写任何文件、不进 conflux 的数据结构，
     * 所以开关关掉就彻底消失，和 Xaero 的临时点一样不污染存档。
     */
    final class ProbeAccess implements IXWaypointAccess {
        final ProbeFactory factory = new ProbeFactory();

        @Override
        public String getName() {
            return "sfh-waypoint-probe";
        }

        @Override
        public void addTo(List<IXWaypoint> collector) {
            synchronized (lock) {
                collector.addAll(order);
            }
        }

        @Override
        public void add(IXWaypoint waypoint, boolean front) {
            if (front) {
                synchronized (lock) {
                    if (live.put(waypoint, Boolean.TRUE) == null) {
                        order.add(0, waypoint);
                    }
                    ++added;
                }
                return;
            }
            add(waypoint);
        }

        @Override
        public void add(IXWaypoint waypoint) {
            onAdd(waypoint);
        }

        @Override
        public void addAll(Collection<IXWaypoint> waypoints, boolean front) {
            if (waypoints == null) {
                return;
            }
            for (IXWaypoint waypoint : waypoints) {
                add(waypoint, front);
            }
        }

        @Override
        public void addAll(Collection<IXWaypoint> waypoints) {
            addAll(waypoints, false);
        }

        @Override
        public void remove(IXWaypoint waypoint) {
            onRemove(waypoint);
        }

        @Override
        public IXWaypoint remove(int slot) {
            IXWaypoint removedOne;
            synchronized (lock) {
                if (slot < 0 || slot >= order.size()) {
                    return null;
                }
                removedOne = order.remove(slot);
                live.remove(removedOne);
                ++removed;
            }
            return removedOne;
        }

        @Override
        public void removeAll(Collection<IXWaypoint> waypoints) {
            if (waypoints == null) {
                return;
            }
            for (IXWaypoint waypoint : waypoints) {
                onRemove(waypoint);
            }
        }

        @Override
        public void removeIf(Predicate<IXWaypoint> predicate) {
            if (predicate == null) {
                return;
            }
            List<IXWaypoint> matched = new ArrayList<>();
            synchronized (lock) {
                for (IXWaypoint waypoint : order) {
                    if (predicate.test(waypoint)) {
                        matched.add(waypoint);
                    }
                }
            }
            for (IXWaypoint waypoint : matched) {
                onRemove(waypoint);
            }
        }

        @Override
        public void clear() {
            synchronized (lock) {
                removed += order.size();
                order.clear();
                live.clear();
            }
        }

        @Override
        public boolean isEmpty() {
            synchronized (lock) {
                return order.isEmpty();
            }
        }

        @Override
        public int size() {
            synchronized (lock) {
                return order.size();
            }
        }

        @Override
        public void update(IXWaypoint waypoint, Consumer<IXWaypoint> updater) {
            if (waypoint == null || updater == null) {
                return;
            }
            updater.accept(waypoint);
            onUpdate();
        }

        @Override
        public IXWaypoint get(int slot) {
            synchronized (lock) {
                return slot < 0 || slot >= order.size() ? null : order.get(slot);
            }
        }

        @Override
        public IXWaypoint set(int slot, IXWaypoint waypoint) {
            synchronized (lock) {
                if (slot < 0 || slot >= order.size()) {
                    return null;
                }
                IXWaypoint previous = order.set(slot, waypoint);
                live.remove(previous);
                live.put(waypoint, Boolean.TRUE);
                return previous;
            }
        }

        @Override
        public void requestRefresh() {
            onRefresh();
        }
    }

    /** 宿主这边只读写不生成产物的临时点；字段就是 {@code IXWaypoint} 的全套形态 */
    static final class ProbeWaypoint implements IXWaypoint {
        /**
         * 建点那一刻的维度。
         *
         * <p>宿主接口不发维度参数（Xaero 的路径点本身就属于「当前世界」），但 conflux 的标记是
         * 带 {@code dimensionId} 的扁平列表，所以只能在这里按创建时机记下来；
         * 跨维度残留由宿主自己的世界切换清理负责。
         */
        private final ResourceKey<Level> dimension;
        /**
         * 进程内唯一且终生不变的序号，用来做 conflux 标记的 {@code id}。
         *
         * <p>conflux 只要求 id 非空白，同维度同 id 会派生出同一个 UUID，但它<b>不去重</b>
         * （两个点都画）；反过来同一个点每次取样必须给同一个 id。用自增序号两条都满足，
         * 也不依赖名字/坐标的哈希（那两样都会在 {@code updateWaypoint} 里变）。
         */
        private final long uid;
        /**
         * 这个点在提交前有没有被宿主乘过 {@link #coordinateScale}。
         *
         * <p>宿主四个来源口径不统一：StructureLabel / SearchLabel / Warps 会乘
         * {@code dimensionType().coordinateScale()}（沿用 Xaero 的「主世界尺度」写法），
         * EntityLog 与 XaeroHelper 的旅行点则是原始坐标。conflux 要的是**该维度的原始坐标**，
         * 所以只有乘过的那批要在渲染前除回去 —— 判断在建点时就做掉，别等到渲染时猜。
         */
        private final boolean preScaled;
        /** 建点时的 {@code coordinateScale}（主世界/末地 1.0，下界 8.0） */
        private final double coordinateScale;
        private int x;
        private int y;
        private int z;
        private String name;
        private final String initials;
        private int color;
        private int purpose;
        private final boolean temp;
        private final boolean yIncluded;
        private final long createdAt = System.currentTimeMillis();

        ProbeWaypoint(
                ResourceKey<Level> dimension,
                long uid,
                boolean preScaled,
                double coordinateScale,
                int x,
                int y,
                int z,
                String name,
                String initials,
                int color,
                int purpose,
                boolean temp,
                boolean yIncluded) {
            this.dimension = dimension;
            this.uid = uid;
            this.preScaled = preScaled;
            this.coordinateScale = coordinateScale;
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
            this.initials = initials;
            this.color = color;
            this.purpose = purpose;
            this.temp = temp;
            this.yIncluded = yIncluded;
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return y;
        }

        @Override
        public int getZ() {
            return z;
        }

        @Override
        public void setX(int x) {
            this.x = x;
        }

        @Override
        public void setY(int y) {
            this.y = y;
        }

        @Override
        public void setZ(int z) {
            this.z = z;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String getInitials() {
            return initials;
        }

        @Override
        public int getColor() {
            return color;
        }

        @Override
        public void setColor(int color) {
            this.color = color;
        }

        @Override
        public int getPurpose() {
            return purpose;
        }

        @Override
        public void setPurpose(int purpose) {
            this.purpose = purpose;
        }

        @Override
        public boolean isTemp() {
            return temp;
        }

        @Override
        public boolean isYInclude() {
            return yIncluded;
        }

        @Override
        public long getCreatedAt() {
            return createdAt;
        }

        /** conflux 标记要的维度；建点时不在世界则为 {@code null} */
        ResourceKey<Level> dimension() {
            return dimension;
        }

        /** 见字段注释：conflux 标记的 {@code id} 用它 */
        long uid() {
            return uid;
        }

        boolean preScaled() {
            return preScaled;
        }

        double coordinateScale() {
            return coordinateScale;
        }
    }
}
