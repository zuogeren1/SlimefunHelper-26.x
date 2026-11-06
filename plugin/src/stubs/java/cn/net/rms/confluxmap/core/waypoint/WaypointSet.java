package cn.net.rms.confluxmap.core.waypoint;

/**
 * conflux 内部的路径点组（编译期桩，不参与打包）。
 *
 * <p>只声明保存路径用到的成员；真类在 common jar 里（0.1.16 反编译：
 * `name()` / `isDefault()` / `collapsed()`）。
 */
public final class WaypointSet {
    public String name() {
        return "";
    }

    public boolean isDefault() {
        return false;
    }

    public boolean collapsed() {
        return false;
    }
}
