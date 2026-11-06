package me.zuogeren.slimefunhelperplus.module.conflux.markers;

import cn.net.rms.confluxmap.api.EventRegistration;
import cn.net.rms.confluxmap.api.MarkerApi;
import cn.net.rms.confluxmap.api.MarkerApi.ApiMarker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.module.conflux.SfPointStyle;
import me.zuogeren.slimefunhelperplus.module.conflux.probe.WaypointCaptureProbe;
import me.zuogeren.slimefunhelperplus.module.conflux.probe.WaypointCaptureProbe.CapturedPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

/**
 * 把捕获到的 Xaero 临时点喂给 conflux 的「自定义标记」。
 *
 * <p>形态是<b>拉取式</b>：conflux 每 10 tick（0.5 秒）调一次 {@link #markers()}，我们只回答
 * 「这一刻要画什么」，不维护增删、也不写任何持久数据 —— 与宿主那批 {@code temp=true} 的临时点
 * 语义完全一致（关开关/换世界就消失）。
 *
 * <h2>为什么每个字段都要小心</h2>
 * 这些约束都是从 conflux 0.1.16 的渲染代码里读出来的（{@code CustomMarkerService} →
 * {@code WaypointRenderEntry} → {@code WaypointMarkerRenderer}），逐条对应下面的实现：
 * <ul>
 *   <li>{@code dimensionId} 按<b>字符串全等</b>匹配 {@code DimensionId#toString()}，所以必须写
 *       {@code minecraft:the_nether} 这种完整写法 —— 写成 {@code the_nether} 会被静默丢掉；</li>
 *   <li>方框里只画 {@code label} 的<b>第一个非空白码点</b>，换行不会被处理（会画成怪字形），
 *       所以这里把多行压平、控制字符与 {@code §} 去掉并截断；全屏在缩小到 2.0 以下时把整条
 *       label 单行画在方块右侧、不截断，所以上限取 {@value #MAX_LABEL_LENGTH} 字；</li>
 *   <li>{@code iconItemId} 传 {@code null}：conflux 会兜底成空串并直接返回空图标，既不解析
 *       Identifier 也不查注册表 —— 这是唯一不给每个点每帧制造一次 {@code new ItemStack} 的写法；</li>
 *   <li>{@code colorArgb} 只取 RGB（alpha 被强制成不透明），所以直接用 ARGB 值即可；</li>
 *   <li>conflux 在 {@code renderEntries} 里<b>不做任何裁剪</b>，裁剪只发生在画家那一步，
 *       而每个点每帧仍要算一次图标与 UUID —— 所以这里自己按距离截断到
 *       {@value #MAX_MARKERS} 个。</li>
 * </ul>
 */
public final class ConfluxMarkerSource implements MarkerApi.CustomMarkerProvider {

    /** 登记用的 ownerId：只进日志与排重，不影响渲染 */
    private static final String OWNER_ID = "slimefunhelper-plus/markers";

    /**
     * 一次取样最多交出去多少个点。
     *
     * <p>不是随口定的：conflux 的画家对每个点每帧都要 {@code UUID.nameUUIDFromBytes} + 图标查询，
     * 而 SearchLabel 是按区块建点的（一个存档里上千很正常）。400 已经能铺满一屏还能看清，
     * 再多只是拖帧率。
     */
    private static final int MAX_MARKERS = 400;

    private final WaypointCaptureProbe probe;
    private final EventRegistration registration;
    private volatile boolean warned;
    /** 第一次真的产出过标记时打一行数字：这是唯一能从日志确认「渲染侧确实拿到点」的信号 */
    private boolean reportedCount;

    private ConfluxMarkerSource(MarkerApi markerApi, WaypointCaptureProbe probe) {
        this.probe = probe;
        this.registration = markerApi.registerProvider(OWNER_ID, this);
    }

    /**
     * 探测通过之后才能调：类一加载就会解析 {@link MarkerApi}，conflux 不在时会 NoClassDefFoundError。
     *
     * <p>所以调用方必须先确认 {@code ConfluxMapApi} 能加载（与旅行点那条共用同一个判定）。
     */
    public static ConfluxMarkerSource create(WaypointCaptureProbe probe) throws Exception {
        Class<?> entry = Class.forName("cn.net.rms.confluxmap.api.ConfluxMapApi");
        Optional<?> instance = (Optional<?>) entry.getMethod("instance").invoke(null);
        Object api = instance.orElseThrow(() -> new IllegalStateException("ConfluxMapApi.instance() is empty"));
        MarkerApi markerApi = (MarkerApi) entry.getMethod("markers").invoke(api);
        return new ConfluxMarkerSource(markerApi, probe);
    }

    /** 注销取样源；conflux 会在调用线程上立刻重建一次快照，所以注销后我们的点马上消失 */
    public void close() {
        try {
            registration.close();
        } catch (Throwable e) {
            warnOnce(e);
        }
    }

    @Override
    public Collection<ApiMarker> markers() {
        try {
            return build();
        } catch (Throwable e) {
            // conflux 自己会 catch(Throwable) 并打日志；这里再兜一层是为了不让同一个异常
            // 在我们的代码里被重复抛出、把「为什么没画」变成只能看 conflux 的日志。
            warnOnce(e);
            return List.of();
        }
    }

    private List<ApiMarker> build() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc == null ? null : mc.level;
        if (level == null) {
            return List.of();
        }
        String dimensionId = level.dimension().identifier().toString();
        LocalPlayer player = mc.player;

        List<CapturedPoint> visible = new ArrayList<>();
        for (CapturedPoint point : probe.capture()) {
            if (point.dimension() == null) {
                continue;
            }
            // conflux 自己也按字符串全等过滤维度；这里先滤是为了别把别的维度的点算进上限
            if (!dimensionId.equals(point.dimension().identifier().toString())) {
                continue;
            }
            visible.add(point);
        }
        if (visible.isEmpty()) {
            return List.of();
        }
        if (visible.size() > MAX_MARKERS) {
            visible.sort(Comparator.comparingDouble(point -> distanceSquared(point, player)));
            visible = new ArrayList<>(visible.subList(0, MAX_MARKERS));
        }
        // priority 相同的时候 conflux 按列表顺序画；把远的排前面，近的压在上面
        visible.sort(Comparator.comparingDouble((CapturedPoint point) -> distanceSquared(point, player))
                .reversed());

        List<ApiMarker> markers = new ArrayList<>(visible.size());
        for (CapturedPoint point : visible) {
            markers.add(toMarker(point, dimensionId));
        }
        if (!reportedCount) {
            reportedCount = true;
            Debug.getLogger()
                    .info("[ConfluxMapHelper] conflux markers: {} points in " + dimensionId, markers.size());
        }
        return markers;
    }

    private static ApiMarker toMarker(CapturedPoint point, String dimensionId) {
        double scale = SfPointStyle.coordinateScale(point);
        return new ApiMarker(
                "sfh:" + point.uid(),
                dimensionId,
                point.x() / scale,
                point.y(),
                point.z() / scale,
                SfPointStyle.label(point),
                SfPointStyle.color(point.color()),
                null,
                true,
                true,
                0);
    }

    private static double distanceSquared(CapturedPoint point, LocalPlayer player) {
        if (player == null) {
            return 0.0;
        }
        double scale = SfPointStyle.coordinateScale(point);
        double dx = point.x() / scale - player.getX();
        double dz = point.z() / scale - player.getZ();
        return dx * dx + dz * dz;
    }

    private void warnOnce(Throwable e) {
        if (warned) {
            return;
        }
        warned = true;
        Debug.getLogger().warn("[ConfluxMapHelper] building conflux markers failed; only the first failure is logged", e);
    }
}
