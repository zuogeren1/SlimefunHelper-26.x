package cn.net.rms.confluxmap.core.waypoint;

/**
 * conflux-map 的路径点渲染条目（编译期桩，不参与打包）。
 *
 * <p>真类在 conflux-map 的 JiJ 内嵌包 {@code META-INF/jars/common-*.jar} 里（javap 实测：
 * {@code public final class ... extends java.lang.Record}），**不在**工程当前的编译类路径上，
 * 而 {@code FullscreenMapScreen#locationMenuButtonSpecs} 的第二个形参就是它。
 * 本模块只是为了写出那个 @Shadow / @Inject 的签名才需要它，所以这里只需要类型名，
 * 不声明任何成员 —— 桩不进产物，运行期用的仍是 conflux-map 自己的类。
 */
public class WaypointRenderEntry {
    protected WaypointRenderEntry() {}
}
