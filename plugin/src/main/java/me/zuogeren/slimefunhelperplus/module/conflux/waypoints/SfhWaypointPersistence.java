package me.zuogeren.slimefunhelperplus.module.conflux.waypoints;

import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointSet;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;

/**
 * 保存期过滤：把 {@value ConfluxWaypointManager#GROUP} 组从**落盘快照**里摘掉。
 *
 * <p>语义上等价于 Xaero 的 `if (!w.isTemporary())`：内存里照常有这些点（列表、绘制、传送都正常），
 * 只是文件里一条都不写。下一次进世界从磁盘重建，它们自然不存在 —— 于是「临时」不再依赖清理，
 * 也就没有崩溃残留窗口。
 *
 * <p>只碰快照，不碰 store 本体：{@link WaypointStore.State} 是保存那一刻的拷贝。
 */
public final class SfhWaypointPersistence {

    private SfhWaypointPersistence() {}

    /** @return 去掉 SFH 组之后的新快照；没有可去掉的就原样返回（避免无意义分配） */
    public static WaypointStore.State filter(WaypointStore.State state) {
        if (state == null) {
            return null;
        }
        List<Waypoint> all = state.waypoints();
        List<WaypointSet> allSets = state.sets();
        if (all == null || allSets == null) {
            return state;
        }
        List<Waypoint> kept = new ArrayList<>(all.size());
        for (Waypoint waypoint : all) {
            if (waypoint != null && ConfluxWaypointManager.GROUP.equals(waypoint.group)) {
                continue;
            }
            kept.add(waypoint);
        }
        List<WaypointSet> keptSets = new ArrayList<>(allSets.size());
        for (WaypointSet set : allSets) {
            if (set != null && ConfluxWaypointManager.GROUP.equals(set.name())) {
                continue;
            }
            keptSets.add(set);
        }
        if (kept.size() == all.size() && keptSets.size() == allSets.size()) {
            return state;
        }
        return new WaypointStore.State(keptSets, kept, state.persistenceWritable());
    }

    /**
     * 过滤后内容的指纹，用来跳过「只有 SFH 变了」的写盘。
     *
     * <p>看到 64 位 FNV-1a 而不是 {@code hashCode()}：后者 32 位，路径点多的时候撞一次
     * 就是「该写的没写」。碰撞概率在这里可以接受（同一次会话内内容往返变化撞上的机会极低），
     * 但没必要省这点。</br>
     */
    public static long fingerprint(WaypointStore.State state) {
        WaypointStore.State filtered = filter(state);
        if (filtered == null) {
            return 0L;
        }
        long hash = 0xcbf29ce484222325L;
        for (WaypointSet set : filtered.sets()) {
            if (set == null) {
                continue;
            }
            hash = mix(hash, set.name());
            hash = mix(hash, String.valueOf(set.collapsed()));
        }
        for (Waypoint waypoint : filtered.waypoints()) {
            if (waypoint == null) {
                continue;
            }
            hash = mix(hash, String.valueOf(waypoint.id));
            hash = mix(hash, waypoint.name);
            hash = mix(hash, String.valueOf(waypoint.dimensionId));
            hash = mix(hash, Double.toString(waypoint.x));
            hash = mix(hash, Double.toString(waypoint.y));
            hash = mix(hash, Double.toString(waypoint.z));
            hash = mix(hash, Integer.toString(waypoint.colorArgb));
            hash = mix(hash, waypoint.group);
            hash = mix(hash, String.valueOf(waypoint.visible));
            hash = mix(hash, String.valueOf(waypoint.crossDimensionVisible));
            hash = mix(hash, String.valueOf(waypoint.crossDimensionY));
            hash = mix(hash, String.valueOf(waypoint.type));
            hash = mix(hash, waypoint.iconItemId);
            hash = mix(hash, waypoint.markerLabel);
        }
        return hash;
    }

    private static long mix(long hash, String value) {
        long result = hash;
        if (value != null) {
            for (int i = 0; i < value.length(); ++i) {
                result ^= value.charAt(i);
                result *= 0x100000001b3L;
            }
        }
        result ^= 0x7c;
        result *= 0x100000001b3L;
        return result;
    }
}
