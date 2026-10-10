package me.matl114.hacks.modules.survival;

import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.ChatTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.move.TravellingControl;
import me.matl114.hacks.utils.config.*;
import me.matl114.hooks.ConfluxMapHooks;
import me.matl114.hooks.impl.confluxmap.ConfluxMenuContext;
import me.matl114.hooks.impl.confluxmap.ConfluxMenuTarget;
import me.matl114.managers.Configs;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.CommonUtils;
import me.matl114.utils.LoadedChunkEdgeCache;
import me.matl114.utils.ScreenUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * 与 conflux-map 联动的两块功能：
 * <ul>
 *   <li><b>右键位置菜单</b>：提供与 {@link XaeroHelper} 右键菜单等价的“自定义指令 / 自定义补全 / 复制坐标”，
 *       外加一条固定的 {@code /!!travel to <坐标>}（{@code enable-travel-command}）；</li>
 *   <li><b>全屏地图覆盖层</b>：客户端已加载区块的边界线（{@code loaded-chunk-render} +
 *       {@code loaded-chunk-render-color}）与当前旅行目标的临时标记（{@code travel-goal-sync}）。</li>
 * </ul>
 *
 * <p>模块本身只负责收集条目（交给 {@link ConfluxMapHooks}）与维护数据（边界边缓存，
 * 见 {@link LoadedChunkEdgeCache}）：绘制与命中判定在 {@code ConfluxMapScreenMixin} +
 * {@code ConfluxMenuOverlay} / {@code ConfluxMapOverlay} 里完成。
 */
public class ConfluxMapHelper extends BaseModule {
    public static ConfluxMapHelper INSTANCE;

    public ConfluxMapHelper() {
        super("ConfluxMapHelper");
        INSTANCE = this;
    }

    public final ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "conflux-map-extra.conflux-map-helper");

    public final FlagRef enableRightClickCommand =
            flagBuilder(root.add("enable-right-click-command")).build();

    private static final List<String> LIST_FORMATS = List.of("world", "pos", "pos_str", "x", "y", "z");

    public final NBTRef<PrimitiveList<StringFormat>> rightClickCommand = builder(
                    root.add("right-click-command-list"), PrimitiveList.type(StringFormat.class))
            .defaultValue(new PrimitiveList<>(
                    NBTTypes.STRING_FORMAT_TYPE,
                    List.of(new StringFormat(LIST_FORMATS, "/tp {pos}")),
                    new StringFormat(LIST_FORMATS, "")))
            .build();

    public final NBTRef<PrimitiveList<StringFormat>> rightClickSuggest = builder(
                    root.add("right-click-suggest-list"), PrimitiveList.type(StringFormat.class))
            .defaultValue(
                    new PrimitiveList<>(NBTTypes.STRING_FORMAT_TYPE, List.of(), new StringFormat(LIST_FORMATS, "")))
            .build();

    public final FlagRef enableCopyCoords = flagBuilder(root.add("enable-copy-coords"))
            .defaultValue(true)
            .build();

    /** 右键菜单里那条固定的 {@code /!!travel to <坐标>}（对齐 XaeroHelper 默认指令列表里的同一条） */
    public final FlagRef enableTravelCommand =
            flagBuilder(root.add("enable-travel-command")).defaultValue(true).build();

    /** 在全屏地图上画客户端已加载区块的边界线 */
    public final FlagRef loadedChunkRender =
            flagBuilder(root.add("loaded-chunk-render")).build();

    public final NBTRef<WrapColor> loadedChunkColor = builder(root.add("loaded-chunk-render-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.RED)))
            .build();

    /** 把当前旅行目标画成地图上的临时标记（不写进 conflux 的路径点存储） */
    public final FlagRef travelGoalSync =
            flagBuilder(root.add("travel-goal-sync")).build();

    /**
     * conflux <b>HUD 小地图</b>的缩放：<b>每像素多少格</b>（与他们的 {@code BLOCKS_PER_PIXEL} 同一量纲）。
     *
     * <p>conflux 自己只给 4 个离散档位（{@code 0.5 / 1 / 2 / 4}，由 {@code ConfluxConfig.minimapZoomIndex} 选），
     * 这里允许 <b>0.1 ~ 8</b> 的任意值：<b>越大越“缩得远”</b>（0.1 = 放到最大，8 = 缩到最远）。
     *
     * <ul>
     *   <li><b>0（默认）= 不覆盖</b>，完全跟随 conflux 自己的档位 —— 老行为逐字节不变；</li>
     *   <li>{@code > 0} = 覆盖，超出 0.1 ~ 8 的部分<b>夹紧</b>（12 当 8、0.05 当 0.1），不报错。</li>
     * </ul>
     *
     * <p>生效范围是<b>他们小地图自己的绘制</b>（瓦片 / 传送门区块高亮 / 玩家轨迹 / 方位字母 / 路径点 /
     * 自定义标记 / 雷达点 / 玩家箭头）<b>加上</b>我们画在同一张小地图上的两层覆盖（见 {@code ConfluxMinimapHudMixin}）——
     * 两边共用 {@code ConfluxMinimapZoom} 这一个出口，保证不会出现“瓦片缩了、我们的区块边界没缩”的错位。
     * 全屏地图<b>不受影响</b>（它有自己的 {@code scale}）。
     *
     * <p>取值与夹紧都写在 {@code me.matl114.hooks.impl.confluxmap.ConfluxMinimapZoom} 里。
     */
    public final DoubleRef minimapBlocksPerPixel = builder(root.add("minimap-blocks-per-pixel"), Double.class)
            .defaultValue(0.0)
            .build();

    /** 已加载区块的边界边缓存：数据在这里按 tick 刷新，绘制在 ConfluxMapScreenMixin 里读 */
    private final LoadedChunkEdgeCache loadedChunkEdges = new LoadedChunkEdgeCache();

    public LoadedChunkEdgeCache loadedChunkEdges() {
        return loadedChunkEdges;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(ConfluxMapHooks.getLocationMenuOption(), this::onConfluxMenuCollect);
        registerListener(Listener.getPostGameTick(), this::onPostGameTick);
    }

    @Override
    public <W> void unregisterAll() {
        super.unregisterAll();
        loadedChunkEdges.clear();
    }

    /**
     * 每个客户端 tick 之后刷新一次边界边缓存（与 {@link XaeroHelper#onTickMapRender} 同一个节奏、同一个数据源）。
     *
     * <p>刷新本身很便宜：{@link LoadedChunkEdgeCache#update} 只在区块集合真的变了的时候才重建线段。
     * {@code mc.level == null}（主菜单 / 断线）必须提前挡掉 —— {@link CommonUtils#chunks(boolean)} 返回的
     * 迭代器一构造就解引用 {@code mc.level}，不挡会直接 NPE。
     */
    public void onPostGameTick(Event<LocalPlayer> event) {
        if (!loadedChunkRender.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        try {
            loadedChunkEdges.update(CommonUtils.chunks(false));
        } catch (Throwable ignored) {
            // 换世界的那一瞬间区块存储可能已经在换引用：跳过这一次即可，下一 tick 会重来
        }
    }

    /**
     * 当前旅行目标的位置；没有任务 / 取不到就返回 null。
     *
     * <p>只读 {@link TravellingControl#travelTask}（静态字段），不碰它的任何状态。
     */
    public Vec3 currentTravelTarget() {
        try {
            var task = TravellingControl.travelTask;
            return task == null ? null : task.getCurrentFlyingTarget();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 与 {@link XaeroHelper} 的右键菜单完全一致的占位符说明 */
    private static final Map<String, Object> formatMap = ImmutableMap.<String, Object>builder()
            .put("pos", Component.translatable("message.module.conflux-map-helper.right-click-command.pos"))
            .put("pos_str", Component.translatable("message.module.conflux-map-helper.right-click-command.pos_str"))
            .put("x", Component.translatable("message.module.conflux-map-helper.right-click-command.pos_x"))
            .put("y", Component.translatable("message.module.conflux-map-helper.right-click-command.pos_y"))
            .put("z", Component.translatable("message.module.conflux-map-helper.right-click-command.pos_z"))
            .build();

    private static String formatPosition(ConfluxMenuTarget target) {
        return "%d %d %d".formatted(target.x(), target.y(), target.z());
    }

    public void onConfluxMenuCollect(Event<ArrayList<ConfluxMenuContext>> event) {
        if (!ConfluxMapHooks.getInstance().isEnabled()) {
            return;
        }
        ConfluxMenuTarget target = event.getArgs(0);
        if (target == null) {
            return;
        }
        Map<String, String> map = ImmutableMap.<String, String>builder()
                .put("world", target.worldPath())
                .put("pos", formatPosition(target))
                .put("pos_str", "%d,%d,%d".formatted(target.x(), target.y(), target.z()))
                .put("x", String.valueOf(target.x()))
                .put("y", String.valueOf(target.y()))
                .put("z", String.valueOf(target.z()))
                .build();
        // 固定条目放在最前：ConfluxMenuOverlay.MAX_ENTRIES = 4，排在用户自己配的条目后面会被挤掉
        if (enableTravelCommand.get()) {
            String travelName = ChatUtils.textToPlainString(Component.translatable(
                    "message.module.conflux-map-helper.right-click-command.travel",
                    Component.translatable("message.module.conflux-map-helper.right-click-command.pos")));
            event.context.add(new ConfluxMenuContext(travelName, (clicked) -> {
                ChatTasks.sayMessage("/!!travel to " + formatPosition(clicked), false);
            }));
        }
        if (enableRightClickCommand.get()) {
            for (var format : rightClickCommand.get().list()) {
                String name = ChatUtils.textToPlainString(Component.translatable(
                        "message.module.conflux-map-helper.right-click-command.command", format.formatText(formatMap)));
                event.context.add(new ConfluxMenuContext(name, (clicked) -> {
                    String formatted = format.format(map);
                    ChatTasks.sayMessage(formatted, false);
                }));
            }
            for (var format : rightClickSuggest.get().list()) {
                String name = ChatUtils.textToPlainString(Component.translatable(
                        "message.module.conflux-map-helper.right-click-command.suggest", format.formatText(formatMap)));
                event.context.add(new ConfluxMenuContext(name, (clicked) -> {
                    String formatted = format.format(map);
                    ScreenUtils.openChatScreen(formatted);
                }));
            }
        }
        if (enableCopyCoords.get()) {
            String name = ChatUtils.textToPlainString(
                    Component.translatable("message.module.conflux-map-helper.right-click-command.copy-coords"));
            event.context.add(new ConfluxMenuContext(name, (clicked) -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.keyboardHandler != null) {
                    mc.keyboardHandler.setClipboard(formatPosition(clicked));
                }
            }));
        }
    }

    @Override
    public void addCustomWidgets(Consumer<DrawableWidget> acceptor, int dx, int dy, int dblank) {
        super.addCustomWidgets(acceptor, dx, dy, dblank);
        acceptor.accept(createTitle(
                ConfluxMapHooks.getInstance().isEnabled()
                        ? "widget.conflux-map-helper.conflux-map-enable"
                        : "widget.conflux-map-helper.conflux-map-not-support",
                0,
                dblank,
                dx,
                dy));
    }
}
