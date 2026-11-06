package me.zuogeren.slimefunhelperplus.module.conflux;

import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.chat.InGuiChatBox;
import me.matl114.hacks.utils.config.*;
import me.zuogeren.slimefunhelperplus.module.conflux.ConfluxMapHooks;
import me.zuogeren.slimefunhelperplus.module.conflux.hooks.ConfluxMenuContext;
import me.zuogeren.slimefunhelperplus.module.conflux.hooks.ConfluxMenuTarget;
import me.zuogeren.slimefunhelperplus.module.conflux.hooks.ConfluxTravelWaypointSyncHolder;
import me.zuogeren.slimefunhelperplus.module.conflux.markers.ConfluxMarkerSource;
import me.zuogeren.slimefunhelperplus.module.conflux.waypoints.ConfluxWaypointManager;
import me.zuogeren.slimefunhelperplus.module.conflux.probe.WaypointCaptureProbe;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.plugins.api.PluginHosts;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.module.conflux.LoadedChunkEdgeCache;
import me.matl114.utils.ScreenUtils;
import me.zuogeren.slimefunhelperplus.config.PluginConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * 与 conflux-map 联动的两块功能：
 * <ul>
 *   <li><b>右键位置菜单</b>：提供与 {@link XaeroHelper} 右键菜单等价的“自定义指令 / 自定义补全 / 复制坐标”，
 *       外加一条固定的 {@code /!!travel to <坐标>}（{@code enable-travel-command}）；</li>
 *   <li><b>全屏地图覆盖层</b>：客户端已加载区块的边界线（{@code loaded-chunk-render} +
 *       {@code loaded-chunk-render-color}）；</li>
 *   <li><b>同步旅行目标</b>（{@code travel-goal-sync}）：<b>小地图与全屏地图共用同一条数据</b>，
 *       形态对齐 {@link XaeroHelper} —— 在 conflux 的路径点存储里建一个真路径点
 *       （{@code [SFH] Travel} / 绿色 / 标记 {@code T}），跟着目标 update，到达 / 取消 / 切维度 /
 *       关模块 / 断线时 remove（见
 *       {@code me.matl114.hooks.impl.confluxmap.ConfluxTravelWaypointSync}）。
 *       只有 conflux <b>没提供路径点 API</b>（太老）或者这条路探测失败时，才退回老行为：
 *       在我们自己的覆盖层上画一个只存在一帧的临时标记。</li>
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

    /** 插件自有配置（与定位栏共用一份，见 {@link PluginConfig}）：{@code config/slimefunhelper-plus/config.yml}。 */
    private static final Config CONFLUX_CONFIG = PluginConfig.get();

    public final ModulePath root = makePath(CONFLUX_CONFIG, "conflux-map-extra.conflux-map-helper");

    public final FlagRef enableRightClickCommand =
            flagBuilder(root.add("enable-right-click-command")).build();

    /**
     * 「同步旅行目标」的入口对象：它内部只在 conflux 的路径点 API 可用时才有实现
     * （构造期探测一次，见 {@link ConfluxTravelWaypointSyncHolder}），拿不到就整条退回覆盖层标记。
     *
     * <p>写成字段而不是每次现取，是因为它同时承担「现在是不是该画覆盖层标记」的判断
     * （{@link #shouldDrawTravelMarker()}）：路径点建好之后覆盖层就不再画，避免同一个目标两份；
     * 而它的构造就是那次探测（顺带把探测失败的 WARN 提前到模块构造期，和
     * {@link ConfluxMapHooks} 的自检同节奏）。
     */
    private final ConfluxTravelWaypointSyncHolder travelWaypointSync = new ConfluxTravelWaypointSyncHolder();

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

    /**
     * 同步旅行目标：在 conflux 的<b>路径点列表</b>里建一个真路径点（{@code [SFH] Travel} /
     * 绿色 / 标记 {@code T} / 可见），跟着目标走，任务结束时删掉 —— 与 {@link XaeroHelper}
     * 的同名开关行为一致。
     *
     * <p>拿不到 conflux 的路径点 API 时（老版本 conflux / 结构变了），自动退回本文档开头说的
     * 「覆盖层临时标记」老行为，并在日志里 WARN 一次；两种形态不会同时出现
     * （见 {@link #shouldDrawTravelMarker()}）。
     */
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

    /**
     * 在全屏地图界面里加一个聊天输入框，语义与 {@code XaeroHelper} 的同名开关完全一致：
     * 输入框本体与样式都借宿主 {@code InGuiChatBox} 那一套，我们只负责在 conflux 的界面初始化之后
     * 把它挂上去。
     *
     * <p>为什么还要判 {@code InGuiChatBox#enableOther}：那个开关打开时宿主已经把输入框加进了
     * <b>所有</b>非容器界面（含 conflux 地图），我们再挂一个就是两个框叠在一起。
     */
    public final FlagRef addChatInGuiMap = flagBuilder(root.add("add-chat-input-in-gui-map")).build();

    /**
     * 捕获宿主四个模块（StructureLabel / SearchLabel / EntityLog / Warps）提交的 Xaero 路径点。
     *
     * <p>这一版<b>只写日志不渲染</b>：它要回答的是「这套点能不能拿来用」——有多少、什么坐标、
     * 什么颜色、什么时候被删。实现见 {@link WaypointCaptureProbe}（反射替换 {@code XaeroHooks#minimap}，
     * 宿主零改动）。装了真 Xaero 的玩家这里会自动跳过，不会抢走他们的路径点数据。
     */
    public final FlagRef waypointCapture = flagBuilder(root.add("xaero-waypoint-capture")).build();

    /**
     * 把宿主那批临时点（结构 / 区块记录 / 传送点 / 玩家离开点）画到 conflux 的地图上。
     *
     * <p>形态是 conflux 的「自定义标记」：拉取式、不落盘、进不了它的路点列表，也不能点击 ——
     * 与宿主给 Xaero 的那批 {@code temp=true} 临时点语义正好一一对应。
     * 开关打开时同时启用捕获（{@link WaypointCaptureProbe}），日志那部分仍归
     * {@code xaero-waypoint-capture} 管。
     */
    public final FlagRef waypointMarkers = flagBuilder(root.add("waypoint-markers")).build();

    /**
     * 把 SFH 的点写成 conflux 的**真路径点**（进路径点列表、可编辑、可传送、显示距离）。
     *
     * <p>与 {@link #waypointMarkers} 二选一：标记只是画点，路径点还能被玩家管理；同时开会画两份。
     * 所有由我们写的点都归在 {@link ConfluxWaypointManager#GROUP} 组里，清理以组为边界。
     */
    public final FlagRef waypointMode = flagBuilder(root.add("waypoint-mode"), true).build();

    /**
     * 临时：这些路径点只在本次世界会话里存在 —— 离开世界 / 关开关 / 下次启动都会把
     * {@link ConfluxWaypointManager#GROUP} 整组清空。对齐 Xaero 的 {@code temp} 语义
     * （那边是「不写盘 + 重进世界即消失」），区别只在于 conflux 的写入一定会落盘，
     * 所以这里靠清理把事情拉回同样的可观察结果。
     */
    public final FlagRef waypointTemp = flagBuilder(root.add("waypoint-temp"), true).build();

    /** 路径点写入器；{@code null} = 还没接上 */
    private ConfluxWaypointManager waypointManager;

    /** 写路径点的重试冷却（tick） */
    private int waypointAttachCooldown;

    /** 写路径点接不上的原因只打一次 */
    private boolean waypointAttachWarned;

    /** 只读捕获器：开关关掉时立即还原 hooks 并清空已捕获的点 */
    private final WaypointCaptureProbe waypointProbe = new WaypointCaptureProbe();

    /** 标记取样源；{@code null} = 还没接上（conflux 未就绪或开关没开） */
    private ConfluxMarkerSource markerSource;

    /** 接不上时的重试间隔（tick）：conflux 的 API 在它自己的客户端初始化里才 install，晚几秒是常态 */
    private int markerAttachCooldown;

    /** 接不上的原因只打一次，避免每 2 秒一条 */
    private boolean markerAttachWarned;

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
        // 断开连接（含进 reconfiguration）：我们的路径点属于某个存档，不能跨会话留着
        registerListener(Listener.getServerLeavePoint(), this::onServerLeave);
        // 界面初始化之后：按开关决定要不要往 conflux 的全屏地图里挂聊天输入框
        registerListener(Listener.getPostInitializeScreen(), this::onScreenSetup);
    }

    /** 模块被关（开关）时立刻收掉我们建的路径点，别让它留在地图上 */
    @Override
    public void onDisableModule() {
        super.onDisableModule();
        travelWaypointSync.removeNow();
        detachWaypointWriter();
        detachMarkers();
        waypointProbe.uninstall();
    }

    @Override
    public <W> void unregisterAll() {
        super.unregisterAll();
        loadedChunkEdges.clear();
        travelWaypointSync.removeNow();
        detachWaypointWriter();
        detachMarkers();
        waypointProbe.uninstall();
    }

    /**
     * 断开连接 / 进 reconfiguration：删掉我们的路径点并清空状态。
     *
     * <p>「临时」开着时也试着清 {@code SFH} 组，但**不能指望它**：conflux 自己的
     * {@code onSessionChanged} 先于插件收到 sessionEnded（它的注释写着 last in the listener chain），
     * 那时 store 已经 flush 并置空，我们的 remove 只会拿到 NO_SESSION / NOT_FOUND。
     * 真正的兜底是下次会话开头的启动清扫（{@code syncWaypointWriter} 里那一次 clearGroup）。
     */
    public void onServerLeave(Event<Void> event) {
        travelWaypointSync.removeNow();
        if (waypointManager != null && waypointTemp.get()) {
            waypointManager.clearGroup();
        }
    }

    /**
     * conflux 全屏地图初始化之后，按开关给它挂一个聊天输入框。
     *
     * <p>控件与行为全部来自宿主 {@code InGuiChatBox}（同一个 {@code ChatLikeInputWidget}、
     * 同一份位置/宽度配置、同样是把内容交给 {@code ChatTasks.sayMessage}），我们只负责
     * 「什么时候挂到哪个界面」——与 {@code XaeroHelper#onGuiSetup} 对 Xaero 世界地图做的事同构。
     *
     * <p>为什么先判 {@code enableOther}：那个开关会把输入框加进所有非容器界面，我们再挂就是两个。
     *
     * <p>整段 try/catch：界面初始化在启动路径上，这里出问题最多是「没有那个输入框」，
     * 不能让它把地图界面带崩。
     */
    public void onScreenSetup(Event<Screen> event) {
        if (!addChatInGuiMap.get()) {
            return;
        }
        try {
            Screen screen = event.context;
            if (!ConfluxMapHooks.isMapScreen(screen)) {
                return;
            }
            InGuiChatBox chatBox = InGuiChatBox.INSTANCE;
            if (chatBox == null || chatBox.enableOther.get()) {
                return;
            }
            ScreenAccess.of(screen).addDrawableChildTo(chatBox.createDefaultInputWidget());
        } catch (Throwable e) {
            Debug.getLogger().warn("[ConfluxMapHelper] could not add the chat input to the conflux map screen", e);
        }
    }

    /**
     * 每个客户端 tick 之后刷新一次边界边缓存（与 {@link XaeroHelper#onTickMapRender} 同一个节奏、同一个数据源）。
     *
     * <p>刷新本身很便宜：{@link LoadedChunkEdgeCache#update} 只在区块集合真的变了的时候才重建线段。
     * {@code mc.level == null}（主菜单 / 断线）必须提前挡掉 —— {@link CommonUtils#chunks(boolean)} 返回的
     * 迭代器一构造就解引用 {@code mc.level}，不挡会直接 NPE。
     */
    public void onPostGameTick(Event<LocalPlayer> event) {
        // 探针与 loadedChunkRender 无关，但必须放在它那个提前返回之前，否则关掉区块渲染就收不到样本。
        // 捕获是所有下游开关共用的底座：只要有一个要，就得装。
        // 路径点模式也必须算上 —— 探针是点的唯一来源，不装的话默认配置下什么都写不出来。
        // 但它只在 conflux 可用时才算：conflux 不在时接管探针只会让装 Xaero 的玩家
        // 白白失去 Xaero 侧的 SFH 点，换不来任何东西。
        boolean wantCapture = waypointCapture.get()
                || waypointMarkers.get()
                || (waypointMode.get() && ConfluxMapHooks.getInstance().isEnabled());
        if (wantCapture) {
            waypointProbe.install();
        } else {
            detachMarkers();
            waypointProbe.uninstall();
        }
        // 日志只归「捕获开关」管：只想看地图点的人不该被每 10 秒六行样本刷屏
        waypointProbe.tick(waypointCapture.get());
        syncWaypointWriter();
        syncMarkers();
        syncTravelWaypoint();
        if (!loadedChunkRender.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        try {
            loadedChunkEdges.update(PluginHosts.context().world().chunks(false));
        } catch (Throwable ignored) {
            // 换世界的那一瞬间区块存储可能已经在换引用：跳过这一次即可，下一 tick 会重来
        }
    }

    /** 标记源接不上时的重试间隔：40 tick ≈ 2 秒，够盖住 conflux 的 install 比我们晚的那几秒 */
    private static final int MARKER_ATTACH_RETRY_TICKS = 40;

    /**
     * 按需接上 conflux 的标记取样源。
     *
     * <p>接不上基本只有一个原因：conflux 还没把它自己的 API install 上（它的客户端初始化在我们之后）。
     * 所以按 tick 重试、带冷却，日志只留一次 —— 与旅行点那条路径的取舍一致。
     */
    private void syncMarkers() {
        if (!waypointMarkers.get() || waypointMode.get() || !ConfluxMapHooks.getInstance().isEnabled()) {
            // conflux 不在（或结构与适配目标不符）时连尝试都不做：否则每 2 秒一次 NoClassDefFoundError
            detachMarkers();
            return;
        }
        if (markerSource != null) {
            return;
        }
        if (markerAttachCooldown > 0) {
            --markerAttachCooldown;
            return;
        }
        markerAttachCooldown = MARKER_ATTACH_RETRY_TICKS;
        try {
            markerSource = ConfluxMarkerSource.create(waypointProbe);
            markerAttachWarned = false;
            Debug.getLogger().info("[ConfluxMapHelper] conflux markers attached: the SFH points now show on the conflux map");
        } catch (Throwable e) {
            if (!markerAttachWarned) {
                markerAttachWarned = true;
                Debug.getLogger().debug("[ConfluxMapHelper] conflux markers are not available yet", e);
            }
        }
    }

    /**
     * 按需接上路径点写入器。
     *
     * <p>接上时如果「临时」是开的，先做一次启动清扫：上一次会话（含崩溃）留下的 {@code SFH} 组
     * 会被整组删掉 —— 这正是 Xaero 那边「temp 不落盘、重进世界就没有」的可观察结果。
     */
    private void syncWaypointWriter() {
        // 过滤只该在「临时」开着时生效；关掉之后这些点要能像普通路径点一样留下来
        me.zuogeren.slimefunhelperplus.module.conflux.waypoints.SfhWaypointPersistence
                .setFiltering(waypointMode.get() && waypointTemp.get());
        if (!waypointMode.get() || !ConfluxMapHooks.getInstance().isEnabled()) {
            detachWaypointWriter();
            return;
        }
        if (waypointManager != null) {
            waypointManager.sync(waypointTemp.get());
            return;
        }
        if (waypointAttachCooldown > 0) {
            --waypointAttachCooldown;
            return;
        }
        waypointAttachCooldown = MARKER_ATTACH_RETRY_TICKS;
        try {
            // 启动清扫不在这里做：接上的那一 tick conflux 的 store 可能还没读盘，
            // 由 manager 自己在 sync 里等就绪（见 ConfluxWaypointManager#sweepIfReady）
            ConfluxWaypointManager manager = ConfluxWaypointManager.create(waypointProbe);
            waypointManager = manager;
            waypointAttachWarned = false;
            Debug.getLogger().info("[ConfluxMapHelper] conflux waypoint writer attached, group="
                    + ConfluxWaypointManager.GROUP + " temporary=" + waypointTemp.get());
        } catch (Throwable e) {
            if (!waypointAttachWarned) {
                waypointAttachWarned = true;
                Debug.getLogger().debug("[ConfluxMapHelper] conflux waypoint writer is not available yet", e);
            }
        }
    }

    /** 摘下写入器；「临时」开着时把整组删掉，否则留着让玩家自己管 */
    private void detachWaypointWriter() {
        if (waypointManager == null) {
            return;
        }
        if (waypointTemp.get()) {
            waypointManager.clearGroup();
        }
        waypointManager = null;
        waypointAttachCooldown = 0;
        Debug.getLogger().info("[ConfluxMapHelper] conflux waypoint writer detached");
    }

    /** 摘下标记源：conflux 会在调用线程上重建一次快照，所以关掉之后点会立刻消失 */
    private void detachMarkers() {
        if (markerSource == null) {
            return;
        }
        markerSource.close();
        markerSource = null;
        markerAttachCooldown = 0;
        Debug.getLogger().info("[ConfluxMapHelper] conflux markers detached");
    }

    /**
     * 每个客户端 tick 把「当前旅行目标」同步进 conflux 的路径点存储。
     *
     * <p>{@code travelTask == null} 时也要跑（传 null 进去），因为那一刻正是「到达 / 取消」
     * —— 得把点删掉。真正与 {@link XaeroHelper#onXaeroTempWaypointSync} 同构的只有三点：
     * 同源的数据（{@code TravellingControl.travelTask}）、同样的建/跟/删时机、同一套默认外观。
     *
     * <p>{@code mc.level == null}（主菜单 / 断线）直接跳过：那一刻既没有目标，
     * 也读不到当前维度，删点由 {@link #onServerLeave(Event)} 负责。
     */
    private void syncTravelWaypoint() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        travelWaypointSync.tick(travelGoalSync.get(), currentTravelTarget(), currentDimensionId());
    }

    /**
     * 当前维度的完整 id，形如 {@code minecraft:overworld} —— 与 conflux 的
     * {@code DimensionId#toString()} 同一拼法（{@code namespace + ":" + path}，
     * javap 实测：{@code DimensionId.parse} 按 {@code ':'} 切分，{@code ApiMappers.toApi} 直接把
     * {@code DimensionId.toString()} 当成 {@code ApiWaypoint#dimensionId}）。
     */
    private static String currentDimensionId() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.level == null
                    ? null
                    : mc.level.dimension().identifier().toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 现在该不该在我们自己的覆盖层上画那个临时标记。
     *
     * <p>「同步旅行目标」有两种形态、<b>同一个目标只能出现一份</b>：
     * <ul>
     *   <li>conflux 的路径点 API 可用、且我们真的建出了那个点 → 不画覆盖层（他们自己会画点）；</li>
     *   <li>API 不可用 / 还没建出来 / 建失败 / 点被删 → 画覆盖层标记。</li>
     * </ul>
     * 判断交给 {@link ConfluxTravelWaypointSyncHolder#shouldHideOwnMarker()}：
     * 它只在「我们亲手建出的那个 UUID 还活着」时为真，所以任何一步失败都会自动退回覆盖层，
     * 不会出现「地图上什么都没有」。
     */
    public boolean shouldDrawTravelMarker() {
        return !travelWaypointSync.shouldHideOwnMarker();
    }

    /**
     * 当前旅行目标的位置；没有任务 / 取不到就返回 null。
     *
     * <p>只读 {@link TravellingControl#travelTask}（静态字段），不碰它的任何状态。
     */
    public Vec3 currentTravelTarget() {
        try {
            return PluginHosts.context().travel().currentFlyingTarget();
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
                PluginHosts.context().chat().say("/!!travel to " + formatPosition(clicked), false);
            }));
        }
        if (enableRightClickCommand.get()) {
            for (var format : rightClickCommand.get().list()) {
                String name = ChatUtils.textToPlainString(Component.translatable(
                        "message.module.conflux-map-helper.right-click-command.command", format.formatText(formatMap)));
                event.context.add(new ConfluxMenuContext(name, (clicked) -> {
                    String formatted = format.format(map);
                    PluginHosts.context().chat().say(formatted, false);
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
    public void addCustomWidgets(
            Consumer<DrawableWidget> acceptor, int dx, int dy, int dblank, ElementFactory factory) {
        super.addCustomWidgets(acceptor, dx, dy, dblank, factory);
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
