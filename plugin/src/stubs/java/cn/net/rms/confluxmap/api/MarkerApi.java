package cn.net.rms.confluxmap.api;

import java.util.Collection;
import java.util.List;

/**
 * conflux-map 的「自定义标记」入口（编译期桩，不参与打包）。
 *
 * <p>真类在 conflux-map 的 JiJ 内嵌包 {@code META-INF/jars/api-x.y.z.jar} 里，<b>不在</b>编译类路径上。
 * 签名按 javap 逐字抄自 0.1.16（26.1.2 / 26.2 两份 jar 一致）：
 * <pre>
 * public interface cn.net.rms.confluxmap.api.MarkerApi {
 *   EventRegistration registerProvider(String, MarkerApi$CustomMarkerProvider);
 *   List&lt;MarkerApi$ApiMarker&gt; markers();
 * }
 * </pre>
 *
 * <p><b>拉取式</b>：conflux 自己按固定节奏调 {@code CustomMarkerProvider#markers()} 取样（见
 * {@code CustomMarkerService} 的 {@code SAMPLE_INTERVAL_TICKS}），我们只负责「给定这一刻要画什么」，
 * 不需要也不应该自己维护增删。这正是宿主那批 {@code temp=true} 临时点的天然对应物 ——
 * 它们同样不落盘、同样由「谁持有」决定生命周期。
 */
public interface MarkerApi {

    /** 注册一个取样源；{@code ownerId} 只用于日志与排重。关掉登记走 {@link EventRegistration#close()} */
    EventRegistration registerProvider(String ownerId, CustomMarkerProvider provider);

    /** 当前所有源合起来的标记快照（只读；我们一般不调，留着是为了排障） */
    List<ApiMarker> markers();

    /**
     * 一个标记（真类为 {@code public final class ... extends Record}，javap 实测）。
     *
     * <p>{@code id} 是<b>我们</b>给的身份，conflux 会拿它派生稳定 UUID（{@code CustomMarkerService#stableId}），
     * 所以同一个点在不同取样里必须给同一个 id。
     *
     * <p>{@code dimensionId} 与 {@code ApiWaypoint} 同一写法（{@code DimensionId#toString()}，形如
     * {@code minecraft:overworld}）。
     */
    record ApiMarker(
            String id,
            String dimensionId,
            double x,
            double y,
            double z,
            String label,
            int colorArgb,
            String iconItemId,
            boolean showOnMinimap,
            boolean showOnFullscreen,
            int priority) {}

    /** 取样源：conflux 每过一个取样间隔就来问一次「这一刻有哪些标记」 */
    interface CustomMarkerProvider {
        Collection<ApiMarker> markers();
    }
}
