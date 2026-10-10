package cn.net.rms.confluxmap.api;

import java.util.Optional;

/**
 * conflux-map 的插件入口（编译期桩，不参与打包）。
 *
 * <p>真类在 conflux-map 的 JiJ 内嵌包 {@code META-INF/jars/api-x.y.z.jar} 里，**不在**工程当前的
 * 编译类路径上。签名按 javap 逐字抄自三份 jar（0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2，
 * 三者 {@code api/} 下同样 36 个类、本入口逐字一致）：
 * <pre>
 * public interface cn.net.rms.confluxmap.api.ConfluxMapApi {
 *   String version(); ConfluxMapEvents events(); WaypointApi waypoints();
 *   MapDataApi mapData(); MarkerApi markers(); ActionApi actions();
 *   static Optional&lt;ConfluxMapApi&gt; instance();
 *   static void install(ConfluxMapApi); static void uninstall();
 * }
 * </pre>
 *
 * <p>本仓库只用到 {@link #instance()} 与 {@link #waypoints()}；其余成员真类里也在，
 * 但为了不让桩跟着 conflux 的迭代跑，这里只声明我们真正调用的部分
 * （桩只需要让 {@code me.matl114.hooks.impl.confluxmap.ConfluxTravelWaypointSync} 编译过）。
 *
 * <p><b>广度</b>：{@link #instance()} 是接口静态方法，只在 conflux-map 自己的
 * {@code ConfluxMapApiImpl} 初始化时才被 {@code install(...)} 填上 —— 拿不到
 * （{@link Optional#empty()}）就说明 conflux 没装或版本太老。
 */
public interface ConfluxMapApi {
    static Optional<ConfluxMapApi> instance() {
        return Optional.empty();
    }

    WaypointApi waypoints();
}
