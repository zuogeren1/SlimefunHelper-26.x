package me.matl114.hacks.utils.entity;

import java.util.*;
import me.matl114.events.Event;
import me.matl114.managers.Tasks;
import me.matl114.utils.MathUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

public class PredictorImpl implements Predictor {
    private static final Minecraft mc = Minecraft.getInstance();
    private int ownerId;
    private Entity owner;
    private final Deque<KnownPosition> positions = new ArrayDeque<>();
    private static final int MAX_HISTORY = 80;
    private Vec3 currentTrackedPosition;
    private float currentSyncedPitch;
    private float currentSyncedYaw;

    public PredictorImpl() {}

    public PredictorImpl(Entity owner) {
        this.owner = owner;
        this.ownerId = owner.getId();
        this.currentTrackedPosition = owner.position();
        this.currentSyncedPitch = owner.getXRot();
        this.currentSyncedYaw = owner.getYRot();
    }

    public void initializeTrackedPosition(int owner, double x, double y, double z, float pitch, float yaw) {
        this.ownerId = owner;
        this.currentTrackedPosition = new Vec3(x, y, z);
        this.currentSyncedPitch = pitch;
        this.currentSyncedYaw = yaw;
    }

    public void tick() {
        synchronized (this) {
            while (positions.size() > MAX_HISTORY) {
                positions.removeFirst();
            }
            Entity localPlayer = mc.player;
            // 26.2 起 Entity#getId() 在 id 未分配（0）时会抛 IllegalStateException，而玩家实体的 id 是
            // ClientPacketListener#handleLogin 先把玩家赋给 this.minecraft.player、随后才 setId(packet.playerId())
            // 时补上的：集成服务端在另一个线程 tick 自己那个 ServerPlayer 时会走到这里，正好落在
            // 「mc.player 已非 null、id 还是 0」的窗口里（实测随机崩服/崩客户端）。
            // 这里本来只想知道「这个预测器跟踪的是不是本地玩家」，改成实体身份比较：owner 就是这个
            // 预测器所属的实体（PlayerEntityMixin 用 new PredictorImpl(this) 创建），同一世界里 id 与
            // 实体一一对应，所以与原来的 id 比较等价，且完全不碰 id。
            if (localPlayer != null && trackedOwner() == localPlayer) {
                // The local player is moved by client-side input between server packets.
                // Keep the synchronised position in step with the client entity until a
                // server position packet supplies a new base position.

                setSyncedPosition(mc.player.position());
                currentSyncedPitch = mc.player.getXRot();
                currentSyncedYaw = mc.player.getYRot();
                addRecord(new KnownPosition(getCurrentPos(), Tasks.getTick()));
            }
        }
    }

    public void onEntityPositionPost(ClientboundTeleportEntityPacket packet) {
        if (packet.id() != ownerId) return;
        synchronized (this) {
            PositionMoveRotation position = apply(packet.change(), packet.relatives());
            setSyncedPosition(position.position());
            currentSyncedPitch = position.xRot();
            currentSyncedYaw = position.yRot();
            addRecord(new KnownPosition(getCurrentPos(), Tasks.getTick()));
        }
    }

    public void onEntityPositionSyncPost(ClientboundEntityPositionSyncPacket packet) {
        if (packet.id() != ownerId) return;
        synchronized (this) {
            PositionMoveRotation position = packet.values();
            setSyncedPosition(position.position());
            currentSyncedPitch = position.xRot();
            currentSyncedYaw = position.yRot();
            addRecord(new KnownPosition(getCurrentPos(), Tasks.getTick()));
        }
    }

    public void onEntityPositionMove(ClientboundMoveEntityPacket packet) {
        if (packet.entityId != ownerId) return;
        synchronized (this) {
            if (mc.player != null && mc.player.getId() == ownerId || packet.hasPosition()) {
                Vec3 position = applyDelta(getCurrentPos(), packet.getXa(), packet.getYa(), packet.getZa());
                setSyncedPosition(position);
            }
            if (packet.hasRotation()) {
                currentSyncedYaw = packet.getYRot();
                currentSyncedPitch = packet.getXRot();
            }
            addRecord(new KnownPosition(getCurrentPos(), Tasks.getTick()));
        }
    }

    /**
     * 把数据包里的相对位移（1/4096 格）加到同步位置上。
     *
     * <p>语义与 26.2 的 {@code net.minecraft.network.protocol.game.VecDeltaCodec#decode} 保持一致：
     * 先把基准坐标按 1/4096 量化（{@code Math.round(v * 4096.0)}），加上增量后再除以 4096；
     * 增量为 0 的分量直接取基准值。直接写 {@code base + delta / 4096.0} 会漏掉量化步骤，
     * 在远离原点处与客户端实际解出的位置有 1/4096 量级偏差，进而影响 getLastKnownPositions 的距离判定。
     */
    private static Vec3 applyDelta(Vec3 base, long deltaX, long deltaY, long deltaZ) {
        if (base == null) return Vec3.ZERO;
        if (deltaX == 0L && deltaY == 0L && deltaZ == 0L) return base;
        double x = deltaX == 0L ? base.x : (Math.round(base.x * 4096.0D) + deltaX) / 4096.0D;
        double y = deltaY == 0L ? base.y : (Math.round(base.y * 4096.0D) + deltaY) / 4096.0D;
        double z = deltaZ == 0L ? base.z : (Math.round(base.z * 4096.0D) + deltaZ) / 4096.0D;
        return new Vec3(x, y, z);
    }

    /**
     * 把数据包里的「相对传送」折算成绝对位置
     */
    private PositionMoveRotation apply(PositionMoveRotation position, Set<Relative> relatives) {
        PositionMoveRotation current = new PositionMoveRotation(
                getCurrentPos() == null ? Vec3.ZERO : getCurrentPos(),
                Vec3.ZERO,
                currentSyncedYaw,
                currentSyncedPitch);
        return PositionMoveRotation.calculateAbsolute(current, position, relatives);
    }

    private void setSyncedPosition(Vec3 position) {
        if (position != null) {
            currentTrackedPosition = position;
        }
    }

    private Entity trackedOwner() {
        if (owner != null) {
            return owner;
        }
        Entity resolved = mc.level == null ? null : mc.level.getEntity(ownerId);
        if (resolved != null) {
            owner = resolved;
        }
        return resolved;
    }

    /**
     * 基于最近两个已知位置（收到的记录）计算当前移动向量
     */
    /**
     * 基于最近两个已知位置（收到的记录）计算当前移动速度（每 tick 的位移向量）
     * @return 速度向量；若 tick 差为 0，则返回零向量（同一时刻无有效速度）
     */
    public Vec3 getKnownDeltaMovement() {
        synchronized (this) {
            if (positions.size() < 2) return Vec3.ZERO;
            Iterator<KnownPosition> it = positions.descendingIterator();
            KnownPosition newest = it.next();
            KnownPosition second = it.next();
            int dt = newest.tick() - second.tick();
            if (dt == 0) {
                // 同一 tick 内无法计算速度，返回零向量（或根据需求返回位移差）
                return newest.vec3d().subtract(second.vec3d());
            }
            Vec3 displacement = newest.vec3d().subtract(second.vec3d());
            return displacement.scale(1.0 / dt);
        }
    }

    @Override
    public Vec3 getCurrentPos() {
        synchronized (this) {
            if (currentTrackedPosition == null) {
                Entity tracked = trackedOwner();
                if (tracked != null) {
                    currentTrackedPosition = tracked.position();
                }
            }
            return currentTrackedPosition == null ? Vec3.ZERO : currentTrackedPosition;
        }
    }

    /**
     * 预测未来位置
     * @param ticksLater 未来刻数（>0）
     * @param method 1=线性回归, 2=二次回归, 3=NVPredictor
     * @param useTicksBefore 只使用过去 useTicksBefore 刻内的历史记录
     */
    public Vec3 predict(int ticksLater, int method, int useTicksBefore) {
        Vec3 trackedPos;
        List<KnownPosition> histRecords = new ArrayList<>();
        KnownPosition lastKnown = null;
        int currentTick = Tasks.getTick();
        boolean add = false;

        synchronized (this) {
            trackedPos = getCurrentPos();
            for (KnownPosition pos : positions) {
                if (pos.tick() >= currentTick - useTicksBefore) {
                    add = true;
                    if (lastKnown != null) {
                        histRecords.add(lastKnown);
                    }
                }
                lastKnown = pos;
            }
        }
        if (ticksLater == 0) return trackedPos;
        Vec3 currentPos = trackedPos;
        // 如果最后一个需要加入。那么add必然为true
        if (lastKnown != null && add) {
            histRecords.add(lastKnown);
        }
        // 如果没有，则直接返回
        if (histRecords.isEmpty()) {
            return currentPos;
        }
        // 最后刻
        // 例如: 我们选取10000 - 10005 6个时间刻进行预测
        // 当前 数据点 10001 10003 10004
        // lastTick0 = 10004
        // firstTick0 = 10001
        // startTick = 10000
        // 我们需要取history = 10000, 10001, 10002, 10003, 10004 这5个点
        // 10000, 10001 <- 10001 数据点
        // 10002 <- 10001, 10003插值
        // 这样》
        int lastTick0 = histRecords.get(histRecords.size() - 1).tick();
        int firstTick0 = histRecords.get(0).tick();
        int startTick0 = currentTick - useTicksBefore;
        if (currentTick + ticksLater < firstTick0) {
            return histRecords.get(0).vec3d();
        }
        if (startTick0 >= lastTick0) {
            // 窗口内没有记录，直接返回当前
            return currentPos;
        }
        // 同步到10000
        if (firstTick0 > startTick0) {
            KnownPosition firstPosition = histRecords.get(0);
            histRecords.add(0, new KnownPosition(firstPosition.vec3d(), startTick0));
            firstTick0 = startTick0;
        }
        // startTick0, statTick0 + 1,.... lastTick0
        // usableTicks - 10000, ... 10004 = 5个点
        int usableTicks = lastTick0 - startTick0 + 1;
        // lastTick0 + 1.。。 currentTick left for empty
        // 10005是empty的 需要在ticksAfter加入
        int blankTicks = currentTick - lastTick0;
        if (usableTicks < 2) {
            return currentPos;
        }

        Vec3[] history = new Vec3[usableTicks];
        int currentIndex = 0;
        KnownPosition pos = histRecords.get(currentIndex);
        KnownPosition lastPos = null;
        for (int i = 0; i < history.length; ++i) {
            int realTick = startTick0 + i;
            // realTick <= historyRecords.getLast().tick()
            while (true) {
                if (pos.tick() == realTick) {
                    history[i] = pos.vec3d();
                    break;
                }
                if (lastPos != null && lastPos.tick() < realTick && pos.tick() > realTick) {
                    // pos.tick > realTick > lastPos.tick
                    history[i] = pos.vec3d()
                            .scale(pos.tick() - realTick)
                            .add(lastPos.vec3d().scale(realTick - lastPos.tick()))
                            .scale(1.0D / (pos.tick() - lastPos.tick()));
                    break;
                }
                lastPos = pos;
                currentIndex += 1;
                if (currentIndex >= histRecords.size()) {
                    // impossible
                    throw new RuntimeException("?   WTF");
                }
                pos = histRecords.get(currentIndex);
            }
        }
        int futureSteps = blankTicks + ticksLater;
        if (futureSteps <= 0) {
            return history[history.length - 1 + futureSteps];
        }
        switch (method) {
            case 1 -> {
                return MathUtils.linearPrediction(history, futureSteps);
            }
            case 2 -> {
                return MathUtils.quadraticPrediction(history, futureSteps);
            }
            case 3 -> {
                Vec3[] ring = Arrays.copyOf(history, history.length);
                int currentIdx = history.length - 1;
                return new MathUtils.NVPredictor(ring, () -> currentIdx).compute(futureSteps);
            }
            case 4 -> {
                Vec3[] ring = Arrays.copyOf(history, history.length);
                int currentIdx = history.length - 1;
                return new MathUtils.RotationalPredictor(ring, () -> currentIdx).compute(futureSteps);
            }
            case 5 -> {
                Vec3[] ring = Arrays.copyOf(history, history.length);
                int currentIdx = history.length - 1;
                return new MathUtils.AcceleratePredictor2(ring, () -> currentIdx).compute(futureSteps);
            }
            case 6 -> {
                Vec3[] ring = Arrays.copyOf(history, history.length);
                int currentIdx = history.length - 1;
                return new MathUtils.AcceleratePredictor(ring, () -> currentIdx).compute(futureSteps);
            }

            default -> {
                return currentPos;
            }
        }
    }

    public List<KnownPosition> getLastKnownPositions(int lastNumber) {
        if (lastNumber <= 0) return Collections.emptyList();

        // 先收集已有的历史记录（从旧到新）
        List<KnownPosition> result;
        Vec3 currentPos;
        synchronized (this) {
            result = new ArrayList<>(positions);
            currentPos = getCurrentPos();
        }
        // 如果历史记录超过所需数量，只保留最后 lastNumber 个
        if (result.size() > lastNumber) {
            result = result.subList(result.size() - lastNumber, result.size());
        }

        // 如果不足，用当前实体位置补全（添加在末尾）
        int missing = lastNumber - result.size();
        if (missing > 0) {
            int currentTick = Tasks.getTick();
            for (int i = 0; i < missing; i++) {
                result.add(new KnownPosition(currentPos, currentTick));
            }
        }

        return result;
    }

    private void addRecord(KnownPosition record) {
        // 若与队尾 tick 相同，则替换（避免重复记录同一时刻）
        var pos = positions.peekLast();
        // remove duplicate packets
        if (!Objects.equals(pos, record)) {
            positions.add(record);
        }
    }

    public static record KnownPosition(Vec3 vec3d, int tick) {}
}
