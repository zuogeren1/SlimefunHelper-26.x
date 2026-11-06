package me.zuogeren.slimefunhelperplus.conflux.waypoints;

import cn.net.rms.confluxmap.api.WaypointApi;
import cn.net.rms.confluxmap.api.WaypointApi.ApiWaypoint;
import cn.net.rms.confluxmap.api.WaypointApi.WaypointEdit;
import cn.net.rms.confluxmap.api.WaypointApi.WaypointMutation;
import cn.net.rms.confluxmap.api.WaypointApi.WaypointType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.conflux.SfPointStyle;
import me.zuogeren.slimefunhelperplus.conflux.probe.WaypointCaptureProbe;
import me.zuogeren.slimefunhelperplus.conflux.probe.WaypointCaptureProbe.CapturedPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

/**
 * 把捕获到的点写成 conflux 的**真路径点**，并且全部归在 {@value #GROUP} 这一个组里。
 *
 * <p>为什么要「组」：conflux 没有「临时路径点」这个概念（{@code WaypointApi} 的写入一律落盘），
 * 所以 Xaero 那种 {@code temp=true} 的语义只能靠清理策略模拟。组就是清理边界 ——
 * 「临时」打开时，离开世界 / 关开关 / 下次启动都只需要把这一组删干净，玩家自己的路径点一个都不碰。
 *
 * <p>写盘代价：每次 {@code add/update/remove} 都会落到 mod 自己的路径点文件。所以这里的量级
 * 按 {@value #MAX_POINTS} 封顶、按距离取近的，并且只在坐标/名字/颜色真的变了才 update。
 */
public final class ConfluxWaypointManager {

    /** 我们独占的路径点组名；清理、启动清扫都以它为边界 */
    public static final String GROUP = "SFH";

    /**
     * 一次最多写多少个真路径点。
     *
     * <p>conflux 的每次 mutation 都是**整文件重写**（Gson 全量 → .tmp → 原子移动，串行在一个
     * IO 线程上），实测单次约 3~5 ms 且与条目数弱相关。所以「点多」的代价是「次数多」：
     * 百来个点几乎无感，上千个点会排成十几秒的写盘队列。
     *
     * <p>这个上限同时对齐渲染侧：小地图对越界点只做边缘夹取，几百个点会糊成一圈。
     * 标记层给 400 是因为它不落盘、一次性整批返回；这里写盘更贵，收紧到 100。
     */
    private static final int MAX_POINTS = 100;

    /** 建点失败后隔多少 tick 再试（20 tps ≈ 2 秒） */
    private static final int ADD_RETRY_TICKS = 40;

    private final WaypointApi waypoints;
    private final WaypointCaptureProbe probe;

    /** 我们这次会话建出来的点：捕获点 uid → conflux 的 waypoint id */
    private final Map<Long, UUID> owned = new HashMap<>();

    /** 上次写入时的内容签名，用来避免无意义的 update（每次 update 都是一次落盘） */
    private final Map<Long, String> signatures = new HashMap<>();

    /** 建点失败后的重试节流：uid → 允许再试的 tick */
    private final Map<Long, Long> retryAfter = new HashMap<>();

    /** 每次 sync 自增，只用来做节流与「首次产出」日志 */
    private long tick;

    /** 启动清扫是否已经做完（做完之前不建点，否则会把刚建的点也扫掉） */
    private boolean swept;

    /** 等 store 就绪用掉的 tick；超过预算就认为「本来就没有可清的」 */
    private int sweepWaitTicks;

    private boolean reportedCount;

    private boolean warned;

    private ConfluxWaypointManager(WaypointApi waypoints, WaypointCaptureProbe probe) {
        this.waypoints = waypoints;
        this.probe = probe;
    }

    /**
     * 探测通过之后才能调：类一加载就会解析 {@link WaypointApi}，conflux 不在时会 NoClassDefFoundError。
     */
    public static ConfluxWaypointManager create(WaypointCaptureProbe probe) throws Exception {
        Class<?> entry = Class.forName("cn.net.rms.confluxmap.api.ConfluxMapApi");
        Optional<?> instance = (Optional<?>) entry.getMethod("instance").invoke(null);
        Object api = instance.orElseThrow(() -> new IllegalStateException("ConfluxMapApi.instance() is empty"));
        WaypointApi waypoints = (WaypointApi) entry.getMethod("waypoints").invoke(api);
        return new ConfluxWaypointManager(waypoints, probe);
    }

    /**
     * 等 store 就绪的最长等待：20 tick（1 秒），过了就认为这个会话本来就没有可清的残留。
     *
     * <p>落盘已经被 {@code ConfluxWaypointSaveMixin} 过滤掉 SFH 组，所以这个清扫现在只处理
     * **老版本留下的残留**；窗口不必再留那么长，否则每次进世界都要晚一秒才看到点。
     */
    private static final int SWEEP_WAIT_TICKS = 20;

    /**
     * 每个客户端 tick 调一次（主线程）：把捕获集合同步成路径点集合。
     *
     * @param temporary 临时语义：先做一次启动清扫，把上一次会话（含崩溃）留下的整组删掉
     */
    public void sync(boolean temporary) {
        try {
            syncNow(temporary);
        } catch (Throwable e) {
            warnOnce(e);
        }
    }

    private void syncNow(boolean temporary) {
        if (temporary && !swept) {
            // 清扫必须排在「建点」之前：进了世界才知道这一组有没有残留，
            // 而刚接上时 conflux 的 store 可能还没把文件读进来（那时 list() 是空的）。
            if (!sweepIfReady()) {
                return;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc == null ? null : mc.level;
        if (level == null) {
            return;
        }
        String dimensionId = level.dimension().identifier().toString();
        LocalPlayer player = mc.player;

        List<CapturedPoint> wanted = new ArrayList<>();
        for (CapturedPoint point : probe.capture()) {
            if (point.dimension() == null) {
                continue;
            }
            if (!dimensionId.equals(point.dimension().identifier().toString())) {
                continue;
            }
            wanted.add(point);
        }
        if (wanted.size() > MAX_POINTS) {
            wanted.sort(Comparator.comparingDouble(point -> distanceSquared(point, player)));
            wanted = new ArrayList<>(wanted.subList(0, MAX_POINTS));
        }

        ++tick;
        Set<Long> live = new HashSet<>(wanted.size());
        for (CapturedPoint point : wanted) {
            live.add(point.uid());
            String signature = signature(point);
            UUID id = owned.get(point.uid());
            if (id == null) {
                if (tick < retryAfter.getOrDefault(point.uid(), 0L)) {
                    continue;
                }
                // 建点失败（例如还没会话）会一直重试；按点节流，别每 tick 打一次注定失败的写
                retryAfter.put(point.uid(), tick + ADD_RETRY_TICKS);
                add(point, signature);
            } else if (!signature.equals(signatures.get(point.uid()))) {
                update(id, point, signature);
            }
        }
        if (!reportedCount && !owned.isEmpty()) {
            reportedCount = true;
            Debug.getLogger().info("[ConfluxMapHelper] conflux waypoints: {} points in group {}", owned.size(), GROUP);
        }
        // 捕获集合里已经不存在的点（模块删点、世界切换清空）：跟着删掉
        Iterator<Map.Entry<Long, UUID>> iterator = owned.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, UUID> entry = iterator.next();
            if (live.contains(entry.getKey())) {
                continue;
            }
            iterator.remove();
            signatures.remove(entry.getKey());
            remove(entry.getValue());
        }
    }

    private void add(CapturedPoint point, String signature) {
        WaypointEdit edit = edit(point);
        try {
            waypoints.add(edit).whenComplete((mutation, error) -> {
                if (error != null || !applied(mutation) || mutation.waypoint() == null) {
                    return;
                }
                owned.put(point.uid(), mutation.waypoint().id());
                signatures.put(point.uid(), signature);
            });
        } catch (Throwable e) {
            warnOnce(e);
        }
    }

    private void update(UUID id, CapturedPoint point, String signature) {
        try {
            waypoints.update(id, edit(point)).whenComplete((mutation, error) -> {
                if (error == null && applied(mutation)) {
                    signatures.put(point.uid(), signature);
                }
            });
        } catch (Throwable e) {
            warnOnce(e);
        }
    }

    private void remove(UUID id) {
        try {
            waypoints.remove(id);
        } catch (Throwable e) {
            warnOnce(e);
        }
    }

    /**
     * 等 store 就绪之后做一次启动清扫。
     *
     * @return {@code true} = 已经可以开始建点了（清扫完成，或本来就没有残留）
     */
    private boolean sweepIfReady() {
        try {
            List<ApiWaypoint> existing = waypoints.list();
            if (existing == null || existing.isEmpty()) {
                // 空表可能只是「还没读进来」，也可能是真的没有 —— 给 5 秒窗口再看
                return ++sweepWaitTicks >= SWEEP_WAIT_TICKS && markSwept();
            }
            clearGroup();
            return markSwept();
        } catch (Throwable e) {
            warnOnce(e);
            return false;
        }
    }

    private boolean markSwept() {
        swept = true;
        return true;
    }

    /**
     * 把整组删干净（临时语义的落点）。
     *
     * <p>失败不重试：离开世界/关模块的瞬间本来就可能已经没有会话，下一次进游戏还有启动清扫兜着。
     */
    public void clearGroup() {
        owned.clear();
        signatures.clear();
        try {
            List<ApiWaypoint> existing = waypoints.list();
            if (existing == null) {
                return;
            }
            int removed = 0;
            for (ApiWaypoint waypoint : existing) {
                if (waypoint != null && GROUP.equals(waypoint.setName())) {
                    waypoints.remove(waypoint.id());
                    ++removed;
                }
            }
            if (removed > 0) {
                // 这一行就是「临时」的可见证据：启动清扫/离开世界时把上一轮整组抹掉
                Debug.getLogger().info("[ConfluxMapHelper] conflux waypoints: cleared {} leftovers in group {}", removed, GROUP);
            }
        } catch (Throwable e) {
            warnOnce(e);
        }
    }

    private WaypointEdit edit(CapturedPoint point) {
        double scale = SfPointStyle.coordinateScale(point);
        WaypointEdit.Builder builder = WaypointEdit.builder()
                .name(SfPointStyle.label(point))
                .dimensionId(point.dimension().identifier().toString())
                .position(point.x() / scale, point.y(), point.z() / scale)
                .colorArgb(SfPointStyle.color(point.color()))
                .group(GROUP)
                .visible(true)
                // 与宿主在 Xaero 侧的行为一致：临时点也允许跨维度显示，生命周期由删/重建管
                .crossDimensionVisible(true)
                .type(WaypointType.NORMAL);
        return builder.build();
    }

    private static String signature(CapturedPoint point) {
        double scale = SfPointStyle.coordinateScale(point);
        return point.dimension().identifier()
                + "|" + SfPointStyle.label(point)
                + "|" + Math.round(point.x() / scale)
                + "|" + Math.round(point.y())
                + "|" + Math.round(point.z() / scale)
                + "|" + SfPointStyle.color(point.color());
    }

    private static boolean applied(WaypointMutation mutation) {
        return mutation != null && mutation.result() == WaypointMutation.Result.APPLIED;
    }

    private static double distanceSquared(CapturedPoint point, LocalPlayer player) {
        if (player == null) {
            return 0.0;
        }
        double scale = SfPointStyle.coordinateScale(point);
        double dx = point.x() / scale - player.getX();
        double dz = point.z() / scale - player.getZ();
        return dx * dx + dz * dz;
    }

    private void warnOnce(Throwable e) {
        if (warned) {
            return;
        }
        warned = true;
        Debug.getLogger().warn("[ConfluxMapHelper] writing conflux waypoints failed; only the first failure is logged", e);
    }
}
