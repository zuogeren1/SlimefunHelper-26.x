package cn.net.rms.confluxmap.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * conflux-map 的路径点读写入口（编译期桩，不参与打包）。
 *
 * <p>签名按 javap 逐字抄自三份 jar（0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2，三份一致）：
 * <pre>
 * public interface cn.net.rms.confluxmap.api.WaypointApi {
 *   List&lt;WaypointApi$ApiWaypoint&gt; list();
 *   Optional&lt;WaypointApi$ApiWaypoint&gt; get(UUID);
 *   List&lt;String&gt; sets();
 *   CompletableFuture&lt;WaypointApi$WaypointMutation&gt; add(WaypointApi$WaypointEdit);
 *   CompletableFuture&lt;WaypointApi$WaypointMutation&gt; update(UUID, WaypointApi$WaypointEdit);
 *   CompletableFuture&lt;WaypointApi$WaypointMutation&gt; remove(UUID);
 * }
 * </pre>
 *
 * <p><b>线程</b>：三个写方法内部都走 {@code ConfluxMapApiImpl.callOnMain(bridge, supplier)}
 * （javap 实测字节码：{@code GameBridge.isOnRenderThread()} 为真时<b>当场同步执行</b>并返回
 * {@code completedFuture}，否则投递到主线程后 complete）。所以我们返回的 future 回调
 * 基本都在主线程上；实现里仍按「可能在别的线程」写（见 {@code ConfluxTravelWaypointSync}）。
 *
 * <p><b>返回语义</b>：{@code WaypointMutation.Result} 有
 * {@code APPLIED / NO_CHANGE / INVALID / NOT_FOUND / NO_SESSION / READ_ONLY} 六个取值，
 * 失败不抛异常、只体现在 result 上 —— 所以调用方必须看 result，不能只看 future 是否正常完成。
 */
public interface WaypointApi {
    List<ApiWaypoint> list();

    Optional<ApiWaypoint> get(UUID id);

    List<String> sets();

    CompletableFuture<WaypointMutation> add(WaypointEdit edit);

    CompletableFuture<WaypointMutation> update(UUID id, WaypointEdit edit);

    CompletableFuture<WaypointMutation> remove(UUID id);

    /**
     * 只读的路径点视图（真类为 {@code public final class ... extends Record}，javap 实测）。
     *
     * <p>{@code dimensionId} 是 {@code DimensionId#toString()} 的结果，也就是
     * {@code namespace:path}（例：{@code minecraft:overworld}）—— 证据是
     * {@code ApiMappers.toApi(Waypoint)} 的字节码里直接 {@code invokevirtual DimensionId.toString()}，
     * 而 {@code DimensionId#toString()} 的 invokedynamic 是两段拼接；反方向
     * {@code DimensionId.parse(String)} 又按 {@code ':'} 切分（无冒号时 namespace 兜底成
     * {@code minecraft}），两头对得上。
     */
    record ApiWaypoint(
            UUID id,
            String name,
            String dimensionId,
            double x,
            double y,
            double z,
            int colorArgb,
            String setName,
            boolean visible,
            boolean crossDimensionVisible,
            WaypointType type,
            String iconItemId,
            String markerLabel,
            long createdAtEpochMs) {}

    /**
     * 一次写操作的「部分编辑」（真类为 record + 嵌套 Builder，javap 实测）。
     *
     * <p>所有组件都是包装类型：{@code null} = <b>不改这一项</b>；非 null = 覆盖。
     * {@code builder()} 里 {@code group} 就是路径点集合名（{@code ApiWaypoint#setName()} 的来源），
     * 不设它时 conflux 会落到 {@code ""} 这个默认集合 —— 更新时想「原地不动」必须把原值带上。
     */
    record WaypointEdit(
            String name,
            String dimensionId,
            Double x,
            Double y,
            Double z,
            Integer colorArgb,
            String group,
            Boolean visible,
            Boolean crossDimensionVisible,
            WaypointType type,
            String iconItemId,
            String markerLabel) {
        public static Builder builder() {
            return new Builder();
        }

        /** 逐步构造 {@link WaypointEdit}（真类为 {@code public static final class ...$Builder}） */
        public static final class Builder {
            private String name;
            private String dimensionId;
            private Double x;
            private Double y;
            private Double z;
            private Integer colorArgb;
            private String group;
            private Boolean visible;
            private Boolean crossDimensionVisible;
            private WaypointType type;
            private String iconItemId;
            private String markerLabel;

            public Builder name(String value) {
                this.name = value;
                return this;
            }

            public Builder dimensionId(String value) {
                this.dimensionId = value;
                return this;
            }

            public Builder position(double px, double py, double pz) {
                this.x = px;
                this.y = py;
                this.z = pz;
                return this;
            }

            public Builder colorArgb(int value) {
                this.colorArgb = value;
                return this;
            }

            public Builder group(String value) {
                this.group = value;
                return this;
            }

            public Builder visible(boolean value) {
                this.visible = value;
                return this;
            }

            public Builder crossDimensionVisible(boolean value) {
                this.crossDimensionVisible = value;
                return this;
            }

            public Builder type(WaypointType value) {
                this.type = value;
                return this;
            }

            public Builder iconItemId(String value) {
                this.iconItemId = value;
                return this;
            }

            public Builder markerLabel(String value) {
                this.markerLabel = value;
                return this;
            }

            public WaypointEdit build() {
                return new WaypointEdit(
                        name,
                        dimensionId,
                        x,
                        y,
                        z,
                        colorArgb,
                        group,
                        visible,
                        crossDimensionVisible,
                        type,
                        iconItemId,
                        markerLabel);
            }
        }
    }

    /** 路径点种类（真类为 {@code public final class ...$WaypointType extends Enum}） */
    enum WaypointType {
        NORMAL,
        DEATH
    }

    /** 一次写操作的结果（真类为 record {@code (Result, ApiWaypoint)}） */
    record WaypointMutation(Result result, ApiWaypoint waypoint) {
        /** 写操作结局（真类为 {@code ...$WaypointMutation$Result extends Enum}，真类里是 public） */
        public enum Result {
            APPLIED,
            NO_CHANGE,
            INVALID,
            NOT_FOUND,
            NO_SESSION,
            READ_ONLY
        }
    }
}
