package cn.net.rms.confluxmap.core.waypoint;

import java.util.List;

/**
 * conflux 内部的路径点存储（编译期桩，不参与打包）。
 *
 * <p>只用 {@code State}：它是落盘前那一刻的不可变快照，0.1.16 反编译为
 * `final class State { List<WaypointSet> sets; List<Waypoint> waypoints; boolean persistenceWritable; }`，
 * 构造器 `(List, List, boolean)`，访问器 `sets()/waypoints()/persistenceWritable()`。
 */
public final class WaypointStore {

    public static final class State {
        public State(List<WaypointSet> sets, List<Waypoint> waypoints, boolean persistenceWritable) {}

        public List<WaypointSet> sets() {
            return List.of();
        }

        public List<Waypoint> waypoints() {
            return List.of();
        }

        public boolean persistenceWritable() {
            return true;
        }
    }
}
