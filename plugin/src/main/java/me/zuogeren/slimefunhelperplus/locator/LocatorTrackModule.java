package me.zuogeren.slimefunhelperplus.locator;

import com.mojang.datafixers.util.Either;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.render.INameTag;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.utils.Debug;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * 定位栏坐标反推：把「只有定位栏方位角、本地没有实体」的玩家坐标估算出来，
 * 作为额外行挂到宿主的名单（{@code NameList}）里。
 *
 * <p>本模块只负责两件事：每 tick 用 {@link LocatorTracker} 采集/解算，以及把结果包装成
 * 宿主 {@link INameTag.RowProvider} 需要的行；列表怎么排版、行高多少、颜色取哪几个，
 * 全部仍由宿主决定 —— 插件不碰渲染，也不碰宿主的玩家数据。
 *
 * <p>配置在插件自己的 {@code config/slimefunhelper-plus/locator.yml}：
 * 首次加载会把宿主 {@code sfhelper-configs/render.yml} 里的旧值搬过来
 * （见 {@link LocatorConfigMigration}）。
 */
public class LocatorTrackModule extends BaseModule {

    private static final Config CONFIG = LocatorConfigMigration.loadConfig();

    public final ModulePath root = makePath(CONFIG, "locator-bar");

    public final FlagRef enable = flagBuilder(root.addEnable()).defaultValue(true).build();

    /** 反推总开关；关掉就清空观测，列表里那些 "~[x, z]" 行随之消失。 */
    public final FlagRef locatorTrack =
            flagBuilder(root.add("locator-track")).defaultValue(true).build();

    /** 参与解算所需的最小方位跨度（度）。 */
    public final DoubleRef locatorMinSpan = doubleBuilder(root.add("locator-track-min-span"))
            .defaultValue(1.0D)
            .validator(Configs.doubleRange(0.2D, 30.0D))
            .build();

    /** 参与解算所需的最小基线（格）。 */
    public final DoubleRef locatorMinBaseline = doubleBuilder(root.add("locator-track-min-baseline"))
            .defaultValue(16.0D)
            .validator(Configs.doubleRange(1.0D, 1000.0D))
            .build();

    /** 观测与反推结果的保留时长（刻）。 */
    public final IntRef locatorMaxAge = intBuilder(root.add("locator-track-max-age"))
            .defaultValue(1200)
            .validator(Configs.intRange(40, 12000))
            .build();

    /** 调试：列表里多一行状态，聊天按 2 秒节流打印每个点的样本/跨度/基线。 */
    public final FlagRef locatorDebug =
            flagBuilder(root.add("locator-track-debug")).build();

    private final LocatorTracker tracker = new LocatorTracker();

    /**
     * 注册/注销必须是同一个实例：方法引用每次求值都会新建对象，
     * 直接写 {@code this::provideRows} 两遍会导致注销不掉。
     */
    private final INameTag.RowProvider rowProvider = this::provideRows;

    private int lastDebugTick = -1000;

    public LocatorTrackModule() {
        super("LocatorTrack");
        bindFlag(enable);
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getWorldSwitchPoint(), this::onWorldSwitch);
        INameTag.registerRowProvider(rowProvider);
    }

    @Override
    public void unregisterAll() {
        INameTag.unregisterRowProvider(rowProvider);
        tracker.clear();
        super.unregisterAll();
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        tracker.clear();
    }

    /** 换世界（含换维度）时坐标系整体改变，旧观测必须作废。 */
    public void onWorldSwitch(Event<Level> event) {
        tracker.clear();
    }

    /**
     * 宿主名单每重建一次列表就调一次。
     *
     * <p>采集放在这里而不是自己的 tick 监听里：名单只有启用时才会重建，
     * 所以「没在看列表就不采数据」是天然的，不用另外判断宿主模块的开关。
     */
    private List<INameTag.ExtraRow> provideRows(INameTag owner) {
        if (checkNull() || !enable.get() || !locatorTrack.get()) {
            tracker.clear();
            return List.of();
        }
        tracker.tick(mc, locatorMinSpan.get(), locatorMinBaseline.get(), locatorMaxAge.get());
        List<INameTag.ExtraRow> rows = new ArrayList<>();
        for (var entry : tracker.snapshot()) {
            var id = entry.getKey();
            var estimate = entry.getValue();
            UUID uuid = id.left().orElse(null);
            // 本地已有实体的玩家不重复显示（他们有真实坐标）
            if (uuid != null && isPlayerLoaded(uuid)) {
                continue;
            }
            double distance = estimate.distanceTo(mc.player.getX(), mc.player.getZ());
            MutableComponent detail = Component.literal(
                            "~[%d, %d] ".formatted(Mth.floor(estimate.x), Mth.floor(estimate.z)))
                    .withColor(owner.infoColor.get().asRGB());
            detail.append(Component.literal("d:%dm".formatted((int) Math.round(distance)))
                    .withColor(owner.distColor.get().asRGB()));
            if (!Double.isNaN(estimate.sigma)) {
                detail.append(Component.literal(" ±%dm".formatted((int) Math.round(estimate.sigma)))
                        .withColor(owner.distColor.get().asRGB()));
            }
            detail.append(Component.literal(" n=%d".formatted(estimate.samples))
                    .withColor(owner.infoColor.get().asRGB()));
            int ageTicks = Tasks.getTick() - estimate.tick;
            if (ageTicks > 60) {
                // 超过 3 秒没刷新（对方潜行/下线/解算被门槛挡住）→ 标出这是旧值
                detail.append(Component.literal(" +%ds".formatted(ageTicks / 20))
                        .withColor(owner.infoColor.get().asRGB()));
            }
            rows.add(new INameTag.ExtraRow(
                    Component.literal(displayName(id)).withColor(owner.nameColor.get().asRGB()), detail));
        }
        if (locatorDebug.get()) {
            rows.add(new INameTag.ExtraRow(debugLine(owner), null));
            debugChat();
        }
        return rows;
    }

    /** 名字解析兜底：tab 列表 → 命名 waypoint → 短 UUID，保证“有数据就一定有行”。 */
    public String displayName(Either<UUID, String> id) {
        UUID uuid = id.left().orElse(null);
        if (uuid == null) {
            return id.right().orElse("waypoint");
        }
        String name = resolvePlayerName(uuid);
        return name == null ? "?" + uuid.toString().substring(0, 8) : name;
    }

    /** 调试行：一眼看出“有没有 waypoint / 攒了几条观测 / 跨度与基线够不够”。 */
    private Component debugLine(INameTag owner) {
        var statuses = tracker.statuses();
        String text;
        if (statuses.isEmpty()) {
            text = "定位栏调试: waypoint=" + tracker.getWaypointCount() + " 追踪=0";
        } else {
            var best = statuses.get(0);
            text = String.format(
                    "定位栏调试: wp=%d 追踪=%d 最佳 %s n=%d 跨=%.2f° 基=%.0f",
                    tracker.getWaypointCount(),
                    statuses.size(),
                    displayName(best.id),
                    best.samples,
                    best.span,
                    best.baseline);
        }
        return Component.literal(text).withColor(owner.infoColor.get().asRGB());
    }

    /** 聊天日志（2 秒节流）：逐点给出样本数/跨度/基线/距离/解，便于把日志贴回来定位。 */
    private void debugChat() {
        int now = Tasks.getTick();
        if (now - this.lastDebugTick < 40) {
            return;
        }
        this.lastDebugTick = now;
        var statuses = tracker.statuses();
        Debug.chat("[Locator] waypoint=" + tracker.getWaypointCount() + " tracked=" + statuses.size());
        for (var status : statuses) {
            Debug.chat(String.format(
                    "[Locator] %s %s n=%d span=%.2f base=%.1f dist=%s est=%s",
                    displayName(status.id),
                    status.finiteDistance ? "coord" : "azimuth",
                    status.samples,
                    status.span,
                    status.baseline,
                    Double.isNaN(status.distance) ? "-" : String.format("%.0f", status.distance),
                    status.estimate == null
                            ? "none"
                            : String.format(
                                    "%.0f,%.0f sigma=%.0f",
                                    status.estimate.x,
                                    status.estimate.z,
                                    status.estimate.sigma)));
        }
    }

    private boolean isPlayerLoaded(UUID uuid) {
        for (Player player : mc.level.players()) {
            if (player.getUUID().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    /** 反推结果的可读名字：取自 tab 列表；拿不到就退回短 UUID。 */
    private String resolvePlayerName(UUID uuid) {
        if (mc.getConnection() == null) {
            return null;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
        return info == null ? null : info.getProfile().name();
    }
}
