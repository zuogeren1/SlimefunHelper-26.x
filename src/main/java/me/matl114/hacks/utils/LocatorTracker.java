package me.matl114.hacks.utils;

import com.mojang.datafixers.util.Either;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.matl114.managers.Tasks;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.TrackedWaypoint;

/**
 * 定位栏（Locator Bar）坐标反推器。
 *
 * <p>定位栏只对外暴露每个 waypoint 的<b>水平方位角</b>（{@link TrackedWaypoint#yawAngleToCamera}）、
 * 上下方向与（部分类型的）距离，<b>不暴露坐标</b>：4 个几何子类与它们的字段在 26.2/26.1.2 都是 private。
 * 所以“看不见的玩家”只能靠自身移动产生视差，用两次以上的方位射线求交来反推。
 *
 * <p><b>角度口径</b>（与 {@code Vec3iWaypoint#yawAngleToCamera} 的源码逐字对应）：
 * <pre>
 *   d  = camera.position() - target;    d' = rotateClockwise90(d) = (-d.z, d.y, d.x)
 *   rel = wrapDegrees(atan2(d'.z, d'.x) * 180/PI - camera.yaw())
 *   =&gt; theta = wrapDegrees(camera.yaw() + rel)    // 从观测点看目标、按 MC yaw 口径的绝对朝向
 *   =&gt; 单位方向 = (-sin(theta), cos(theta))        // XZ 平面
 * </pre>
 * 该式与相机 yaw 无关（自洽），并已用数值仿真按源码公式校验过。
 *
 * <p><b>为什么用最小二乘而不是两两求交取中位数</b>：服务端的方位角是“变化超过 0.5° 才补发”的阶梯值
 * （{@code EntityAzimuthConnection#update}），可见玩家的方位角通常远在 332 格外，单对观测的夹角只有 1°~2°，
 * 两两求交误差可达十几格；把窗口内全部观测一起做最小二乘（观测点到射线的垂距平方和最小）
 * 在同一组数据下误差可压到 1~4 格。
 *
 * <p><b>局限</b>：定位栏只有水平信息，反推出的只有 XZ 坐标、没有 Y；目标自己也在移动时会被抹平，
 * 因此显示时必须标成估计值。
 */
public class LocatorTracker {
    public static final LocatorTracker INSTANCE = new LocatorTracker();

    /** 单个 waypoint 最多保留的观测条数。 */
    public static final int MAX_OBSERVATIONS = 64;

    /** 方位角变化超过该值就补一条观测（服务端的补发阈值是 0.5°）。 */
    private static final double RECORD_ANGLE = 0.02D;

    /** 观测条数不足该值时不做几何门槛，先攒够样本。 */
    private static final int MIN_SAMPLES = 3;

    /** 解算结果与上一次相差不超过该值时做平滑，避免数字跳动。 */
    private static final double SMOOTH_SNAP = 32.0D;
    private static final double SMOOTH_ALPHA = 0.5D;

    /** 用有限距离交叉校验解算结果时允许的误差（相对 + 绝对）。 */
    private static final double CHECK_RELATIVE = 0.25D;
    private static final double CHECK_ABSOLUTE = 16.0D;

    /** 法方程退化判定：方位几乎共线时行列式会趋近 0。 */
    private static final double DET_EPS = 1.0e-6D;

    /**
     * 方位角型 waypoint 的距离下限：服务端只在目标 &gt; 332 格（{@code isReallyFar}）时才发这种连接，
     * 交会结果明显更近就说明两条射线近于平行、解歪了。
     */
    private static final double AZIMUTH_MIN_DISTANCE = 250.0D;

    /** 一次观测：观测点 XZ + 当时的绝对朝向（度）。 */
    public static class Observation {
        public final double x;
        public final double z;
        public final float heading;
        public final int tick;

        public Observation(double x, double z, float heading, int tick) {
            this.x = x;
            this.z = z;
            this.heading = heading;
            this.tick = tick;
        }
    }

    /** 反推结果（水平坐标）。 */
    public static class Estimate {
        public final double x;
        public final double z;
        public final int samples;
        public final double span;
        public final double baseline;
        public final int tick;
        public final boolean fromDistance;

        public Estimate(
                double x,
                double z,
                int samples,
                double span,
                double baseline,
                int tick,
                boolean fromDistance) {
            this.x = x;
            this.z = z;
            this.samples = samples;
            this.span = span;
            this.baseline = baseline;
            this.tick = tick;
            this.fromDistance = fromDistance;
        }

        public double distanceTo(double px, double pz) {
            return Math.hypot(this.x - px, this.z - pz);
        }
    }

    /** 调试用：一个 waypoint 当前的追踪状态。 */
    public static class Status {
        public final Either<UUID, String> id;
        public final int samples;
        public final double span;
        public final double baseline;
        public final boolean finiteDistance;
        public final double distance;
        public final Estimate estimate;

        public Status(
                Either<UUID, String> id,
                int samples,
                double span,
                double baseline,
                boolean finiteDistance,
                double distance,
                Estimate estimate) {
            this.id = id;
            this.samples = samples;
            this.span = span;
            this.baseline = baseline;
            this.finiteDistance = finiteDistance;
            this.distance = distance;
            this.estimate = estimate;
        }
    }

    private final Map<Either<UUID, String>, ArrayDeque<Observation>> observations = new HashMap<>();
    private final Map<Either<UUID, String>, Estimate> estimates = new HashMap<>();
    private final Map<Either<UUID, String>, double[]> distances = new HashMap<>();
    private final Map<Either<UUID, String>, Integer> lastSeen = new HashMap<>();
    private final Map<Either<UUID, String>, Boolean> finiteType = new HashMap<>();
    private int lastTick = Integer.MIN_VALUE;
    private int waypointCount;
    private int lastStatusTick = Integer.MIN_VALUE;

    /** 换世界 / 断线时清空，避免把上一个世界的观测带过来。 */
    public void clear() {
        this.observations.clear();
        this.estimates.clear();
        this.distances.clear();
        this.lastSeen.clear();
        this.finiteType.clear();
        this.lastTick = Integer.MIN_VALUE;
    }

    public Estimate get(Either<UUID, String> id) {
        return this.estimates.get(id);
    }

    public List<Map.Entry<Either<UUID, String>, Estimate>> snapshot() {
        return new ArrayList<>(this.estimates.entrySet());
    }

    /** 上一刻收到的定位栏 waypoint 数量（未过滤自己之前的口径之外：这里就是可用的条数）。 */
    public int getWaypointCount() {
        return this.waypointCount;
    }

    /**
     * 调试快照：每个正在追踪的 waypoint 现在攒了多少观测、跨度/基线够不够、有没有解出来。
     *
     * <p>“跑了很远还是没结果”时靠它区分三种情况：根本没有 waypoint、观测几何不够、解算被校验挡掉。
     */
    public List<Status> statuses() {
        List<Status> list = new ArrayList<>();
        for (Map.Entry<Either<UUID, String>, ArrayDeque<Observation>> entry : this.observations.entrySet()) {
            ArrayDeque<Observation> queue = entry.getValue();
            if (queue.isEmpty()) {
                continue;
            }
            Observation first = queue.peekFirst();
            Observation last = queue.peekLast();
            double minHeading = 0.0D;
            double maxHeading = 0.0D;
            double baseline = 0.0D;
            for (Observation observation : queue) {
                double delta = Mth.wrapDegrees(observation.heading - first.heading);
                minHeading = Math.min(minHeading, delta);
                maxHeading = Math.max(maxHeading, delta);
                baseline = Math.max(baseline, Math.hypot(observation.x - last.x, observation.z - last.z));
            }
            double[] dist = this.distances.get(entry.getKey());
            boolean finite = dist != null;
            list.add(new Status(
                    entry.getKey(),
                    queue.size(),
                    maxHeading - minHeading,
                    baseline,
                    finite,
                    finite ? dist[0] : Double.NaN,
                    this.estimates.get(entry.getKey())));
        }
        list.sort((a, b) -> Double.compare(b.span, a.span));
        return list;
    }

    /**
     * 每个游戏刻采一帧定位栏数据并尝试解算。
     *
     * @param minSpanDeg   参与解算所需的最小方位跨度（度）
     * @param minBaseline  参与解算所需的最小基线（格）
     * @param maxAgeTicks  观测与结果的保留时长
     */
    public void tick(Minecraft mc, double minSpanDeg, double minBaseline, int maxAgeTicks) {
        if (mc.level == null || mc.player == null) {
            this.clear();
            return;
        }
        int now = Tasks.getTick();
        if (now == this.lastTick) {
            return;
        }
        this.lastTick = now;
        Camera camera = mc.gameRenderer.mainCamera();
        Entity from = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        Vec3 camPos = camera.position();
        // 服务端的方位角是按“服务端已知的玩家位置”算的，而坐标型 waypoint 的方位角按相机算：
        // 观测点必须跟着口径走，否则 freecam / 第三人称会整体错位。
        Vec3 playerPos = mc.player.position();
        Level level = mc.level;
        Set<Either<UUID, String>> seen = new HashSet<>();
        mc.player.connection.getWaypointManager().forEachWaypoint(from, waypoint -> {
            Either<UUID, String> id = waypoint.id();
            if (id.left().map(uuid -> uuid.equals(from.getUUID())).orElse(false)) {
                return;
            }
            // 第三参数只用于实体位置插值，方位角型 waypoint 根本不读它
            double rel = waypoint.yawAngleToCamera(level, camera, entity -> 0.0F);
            if (Double.isNaN(rel) || Double.isInfinite(rel)) {
                return;
            }
            seen.add(id);
            double distSq = waypoint.distanceSquared(from);
            boolean finite = Double.isFinite(distSq);
            Vec3 origin = finite ? camPos : playerPos;
            this.record(
                    id,
                    origin.x,
                    origin.z,
                    Mth.wrapDegrees(camera.yaw() + (float) rel),
                    distSq,
                    finite,
                    now,
                    maxAgeTicks);
        });
        this.waypointCount = seen.size();
        this.solveAll(camPos, playerPos, minSpanDeg, minBaseline, maxAgeTicks, now, seen);
    }

    private void record(
            Either<UUID, String> id,
            double x,
            double z,
            float heading,
            double distSq,
            boolean finite,
            int now,
            int maxAgeTicks) {
        ArrayDeque<Observation> queue = this.observations.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        Boolean previousType = this.finiteType.put(id, finite);
        if (previousType != null && previousType != finite) {
            // 服务端换了连接类型（区块 ↔ 方位），观测点口径变了，旧观测全部作废
            queue.clear();
            this.estimates.remove(id);
            this.distances.remove(id);
        }
        Observation last = queue.peekLast();
        boolean turned =
                last == null || Math.abs(Mth.wrapDegrees(heading - last.heading)) >= RECORD_ANGLE;
        if (turned || queue.size() < MIN_SAMPLES) {
            queue.addLast(new Observation(x, z, heading, now));
        }
        while (queue.size() > MAX_OBSERVATIONS) {
            queue.pollFirst();
        }
        while (!queue.isEmpty() && now - queue.peekFirst().tick > maxAgeTicks) {
            queue.pollFirst();
        }
        if (Double.isFinite(distSq)) {
            this.distances.put(id, new double[] {Math.sqrt(distSq), now});
        }
        this.lastSeen.put(id, now);
    }

    private void solveAll(
            Vec3 camPos,
            Vec3 playerPos,
            double minSpanDeg,
            double minBaseline,
            int maxAgeTicks,
            int now,
            Set<Either<UUID, String>> seen) {
        for (Map.Entry<Either<UUID, String>, ArrayDeque<Observation>> entry : this.observations.entrySet()) {
            Either<UUID, String> id = entry.getKey();
            if (!seen.contains(id)) {
                continue;
            }
            ArrayDeque<Observation> queue = entry.getValue();
            double[] dist = this.distances.get(id);
            double finiteDistance =
                    dist != null && now - (int) dist[1] <= maxAgeTicks ? dist[0] : Double.NaN;
            Estimate solved = this.solve(queue, minSpanDeg, minBaseline);
            if (solved == null) {
                // 交会还没成形时，若服务端给了距离，先用“方位 + 距离”的单点解顶上
                Observation last = queue.peekLast();
                if (!Double.isNaN(finiteDistance) && last != null) {
                    double theta = Math.toRadians(last.heading);
                    solved = new Estimate(
                            last.x - Math.sin(theta) * finiteDistance,
                            last.z + Math.cos(theta) * finiteDistance,
                            1,
                            0.0D,
                            0.0D,
                            now,
                            true);
                }
            } else if (Double.isNaN(finiteDistance)) {
                // 方位角型只能靠交会：服务端只在目标 &gt; 332 格时才这么发，结果明显更近就是几何退化解歪了
                double distance = solved.distanceTo(playerPos.x, playerPos.z);
                if (distance < AZIMUTH_MIN_DISTANCE) {
                    solved = null;
                }
            } else {
                // 坐标型：交会出的距离必须与服务端给的距离对得上，否则这次交会不可信
                double error = Math.abs(solved.distanceTo(camPos.x, camPos.z) - finiteDistance);
                if (error > Math.max(CHECK_ABSOLUTE, CHECK_RELATIVE * finiteDistance)) {
                    solved = null;
                }
            }
            if (solved == null) {
                continue;
            }
            Estimate previous = this.estimates.get(id);
            if (previous != null && previous.distanceTo(solved.x, solved.z) <= SMOOTH_SNAP) {
                solved = new Estimate(
                        previous.x + (solved.x - previous.x) * SMOOTH_ALPHA,
                        previous.z + (solved.z - previous.z) * SMOOTH_ALPHA,
                        solved.samples,
                        solved.span,
                        solved.baseline,
                        solved.tick,
                        solved.fromDistance);
            }
            this.estimates.put(id, solved);
        }
        this.prune(now, maxAgeTicks);
    }

    /** 最小二乘解：最小化各观测点到各自方位射线的垂距平方和，法方程 2x2 直接求解。 */
    private Estimate solve(ArrayDeque<Observation> queue, double minSpanDeg, double minBaseline) {
        if (queue.size() < MIN_SAMPLES) {
            return null;
        }
        Observation first = queue.peekFirst();
        Observation last = queue.peekLast();
        double minHeading = 0.0D;
        double maxHeading = 0.0D;
        double baseline = 0.0D;
        for (Observation observation : queue) {
            double delta = Mth.wrapDegrees(observation.heading - first.heading);
            minHeading = Math.min(minHeading, delta);
            maxHeading = Math.max(maxHeading, delta);
            baseline = Math.max(baseline, Math.hypot(observation.x - last.x, observation.z - last.z));
        }
        double span = maxHeading - minHeading;
        if (span < minSpanDeg || baseline < minBaseline) {
            return null;
        }
        double a11 = 0.0D;
        double a12 = 0.0D;
        double a22 = 0.0D;
        double b1 = 0.0D;
        double b2 = 0.0D;
        for (Observation observation : queue) {
            double theta = Math.toRadians(observation.heading);
            // 射线方向 u = (-sinθ, cosθ) 的法向量 n = (cosθ, sinθ)
            double nx = Math.cos(theta);
            double nz = Math.sin(theta);
            double c = nx * observation.x + nz * observation.z;
            a11 += nx * nx;
            a12 += nx * nz;
            a22 += nz * nz;
            b1 += nx * c;
            b2 += nz * c;
        }
        double det = a11 * a22 - a12 * a12;
        if (!Double.isFinite(det) || Math.abs(det) < DET_EPS) {
            return null;
        }
        double x = (b1 * a22 - b2 * a12) / det;
        double z = (a11 * b2 - a12 * b1) / det;
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            return null;
        }
        return new Estimate(x, z, queue.size(), span, baseline, last.tick, false);
    }

    private void prune(int now, int maxAgeTicks) {
        this.lastSeen.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > maxAgeTicks) {
                this.observations.remove(entry.getKey());
                this.estimates.remove(entry.getKey());
                this.distances.remove(entry.getKey());
                this.finiteType.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }
}
