package cn.net.rms.confluxmap.core.waypoint;

import java.nio.file.Path;
import org.apache.logging.log4j.Logger;

/**
 * conflux 的路径点落盘入口（编译期桩，不参与打包）。
 *
 * <p>0.1.16 反编译：`public static void save(Path, WaypointStore.State, Logger)`，普通保存与
 * 退出时的 `saveOutgoingSnapshot` 都走它；另一个 `save(Path, List<Waypoint>, Logger)` 重载
 * 只是包一层 State。签名逐字来自 javap。
 */
public final class WaypointIo {

    public static void save(Path file, WaypointStore.State state, Logger logger) {}

    public static void save(Path file, java.util.List<Waypoint> waypoints, Logger logger) {}
}
