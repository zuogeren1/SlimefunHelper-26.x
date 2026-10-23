package me.matl114.hacks.modules.extra;

import me.matl114.renderer.TextRenderers;
import me.matl114.utils.ClientUtils;

import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.JavaOps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.gui.GenericScreen;
import me.matl114.gui.McWidgetHelpers;
import me.matl114.gui.basic.*;
import me.matl114.gui.complex.RawTextElement;
import me.matl114.gui.complex.config.KeyValueInputWidget;
import me.matl114.gui.complex.config.ListModifyWidget;
import me.matl114.gui.elements.ButtonElement;
import me.matl114.gui.elements.IconElement;
import me.matl114.gui.elements.LabelElement;
import me.matl114.gui.elements.MultiLineTextElement;
import me.matl114.gui.presets.lists.ListEntryWidgetController;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.FileManager;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.StringRef;
import me.matl114.managers.file.FileStorage;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.Debug;
import me.matl114.utils.ThreadUtils;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.CommonColors;
import org.jetbrains.annotations.Nullable;

public class ServerScanner extends BaseModule {
    public ServerScanner() {
        super("ServerScanner");
        bindFlag(enable);
    }

    public final ModulePath scanner = makePath(Configs.EXTRA_CONFIG, "other.server-scanner");
    public final FlagRef enable =
            builder(scanner.addEnable(), FlagRef.TYPE).defaultValue(true).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPostInitializeScreen().getChannel(JoinMultiplayerScreen.class),
                this::onButtonAddWhenInitialize);
    }

    public void unregisterAll() {
        super.unregisterAll();
        running.set(false);
    }

    private WeakReference<ContentDelegateWidget<ExecutableWidget>> delegateWidget;

    public void onButtonAddWhenInitialize(Event<JoinMultiplayerScreen> screenEvent) {
        if (enable.get()) {
            var mp = screenEvent.context();
            if (delegateWidget != null && delegateWidget.get() != null) {
                ScreenAccess.of(mp).removeChildFrom(delegateWidget.get());
            }
            delegateWidget = null;
            ContentDelegateWidget<ExecutableWidget> widget = new ContentDelegateWidget<>(0, 5, 50, 20);
            ExecutableWidget executableWidget = ExecutableWidget.instance(0, 0, 50, 20)
                    .setElementHandler(new ButtonElement(
                            TextProvider.of(Component.literal("Scanner")), ButtonAction.run(this::openScannerScreen)));
            widget.setContentDelegate(executableWidget);
            widget.addTo(mp);
            delegateWidget = new WeakReference<>(widget);
        }
    }
    // todo: optimize
    private ListEntryWidgetController listEntryController;
    private final StringRef ipField = new StringRef("");
    private final IntRef portRange1 = new IntRef(0);
    private final IntRef portRange2 = new IntRef(0);
    private final IntRef requestDelay = new IntRef(2000);
    private final IntRef limitSample = new IntRef(1000);
    private final FlagRef randomRequest = new FlagRef(true);
    private final FlagRef filter = new FlagRef(true);
    private final FileStorage serverListSave = FileManager.getInstance().getInternalStorage("server-scanner.nbt");

    private final ListTag list() {
        CompoundTag nbt = (CompoundTag) serverListSave.as(NbtOps.INSTANCE);
        if (nbt.get("save-list") instanceof ListTag nbtList) {
            return nbtList;
        }
        nbt = nbt.copy();
        var lst = new ListTag();
        nbt.put("save-list", lst);
        serverListSave.write(nbt, NbtOps.INSTANCE);
        return lst;
    }
    // 内存版本, 为了支持后台运行任务和缓存
    private final List<String> scannedIps = new ArrayList<>();

    {
        list().stream().map(s -> ((StringTag) s).value()).forEach(scannedIps::add);
    }

    private Component logInfo = Component.empty();

    public DrawableWidget createInputWidget() {
        SubScreenWidget subScreenWidget = new SubScreenWidget(0, 0, 400, 40);
        subScreenWidget.addDrawableChild(new KeyValueInputWidget<>(0, 0, 200, 20, 30, ipField.createKeyValue("IP")));
        subScreenWidget.addDrawableChild(
                new KeyValueInputWidget<>(0, 20, 80, 20, 30, portRange1.createKeyValue("PortA")));
        subScreenWidget.addDrawableChild(
                new KeyValueInputWidget<>(80, 20, 80, 20, 30, portRange2.createKeyValue("PortB")));
        subScreenWidget.addDrawableChild(
                new KeyValueInputWidget<>(160, 20, 80, 20, 30, requestDelay.createKeyValue("DelayMS")));
        subScreenWidget.addDrawableChild(
                new KeyValueInputWidget<>(240, 20, 80, 20, 30, limitSample.createKeyValue("Limit")));
        subScreenWidget.addDrawableChild(
                new KeyValueInputWidget<>(320, 20, 40, 20, 20, randomRequest.createKeyValue("R"))
                        .setTooltips(List.of(Component.literal("Random"))));
        subScreenWidget.addDrawableChild(new KeyValueInputWidget<>(360, 20, 40, 20, 20, filter.createKeyValue("F"))
                .setTooltips(List.of(Component.literal("Filter"))));
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(200, 0, 40, 20)
                .setElementHandler(new ButtonElement(
                        TextProvider.of(Component.literal("Scan")), ButtonAction.run(this::startScanTask))));
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(240, 0, 40, 20)
                .setElementHandler(new ButtonElement(
                        TextProvider.of(Component.literal("Stop")), ButtonAction.run(this::abortScanTask))));
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(280, 0, 40, 20)
                .setElementHandler(new ButtonElement(
                        TextProvider.of(Component.literal("AScan")), ButtonAction.run(this::startScanTaskAsync))));
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(320, 0, 80, 20)
                .setElementHandler(new LabelElement((s) -> logInfo, CommonColors.WHITE, TextAlign.MIDDLE)));
        return subScreenWidget;
    }

    public DrawableWidget createOutputWidget() {
        SubScreenWidget subScreenWidget = new SubScreenWidget(0, 300, 400, 60);
        subScreenWidget.addDrawableChild(DisplayWidget.instance(0, 10, 80, 20)
                .setRenderHandler(
                        new ButtonElement(TextProvider.of(Component.literal("Add Server")), ButtonAction.empty())));
        ContentDelegateWidget<EditBox> textField = McWidgetHelpers.createTextFieldEditBox(
                80, 10, 100, 20, s -> this.currentInputAdd = s, this.currentInputAdd);
        subScreenWidget.addDrawableChild(textField);
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(180, 10, 20, 20)
                .setElementHandler(new ButtonElement(
                                TextProvider.of(Component.literal("+").withStyle(ChatFormatting.BOLD)),
                                ButtonAction.run(() -> {
                                    if (!currentInputAdd.isEmpty()) {
                                        refreshSingle(currentInputAdd);
                                        logInfo("已添加 " + currentInputAdd);
                                    }
                                }))
                        .withTooltips(TooltipHandler.of(List.of(Component.literal("Add Server"))))));
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(200, 10, 100, 20)
                .setElementHandler(new ButtonElement(
                        TextProvider.of(Component.literal("Refresh All")), ButtonAction.run(this::refreshServerList))));
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(300, 10, 100, 20)
                .setElementHandler(new ButtonElement(
                        TextProvider.of(Component.literal("Copy Server List")), ButtonAction.run(() -> {
                            JsonArray jsonArray = new JsonArray();
                            List<String> list = List.copyOf(this.scannedIps);
                            for (var str : list) {
                                JsonObject jsonObject = new JsonObject();
                                jsonObject.addProperty("ip", str);
                                ServerData info = this.cachedPingResult.get(str);
                                if (info != null) {
                                    JsonObject el = new JsonObject();
                                    el.addProperty("version", ChatUtils.textToString(info.version));
                                    el.addProperty("motd", ChatUtils.textToString(info.motd));
                                    el.addProperty("status", info.state().name().toLowerCase(Locale.ROOT));
                                    el.addProperty("player_count", ChatUtils.textToString(getPlayerListDisplay(info)));
                                    List<Component> playerList = info.playerList;
                                    if (playerList != null && !playerList.isEmpty()) {
                                        JsonArray jsonArray1 = new JsonArray();
                                        for (var txt : playerList) {
                                            jsonArray1.add(ChatUtils.textToString(txt));
                                        }
                                        el.add("player_list", jsonArray1);
                                    }
                                    jsonObject.add("meta", el);
                                }
                                jsonArray.add(jsonObject);
                            }
                            mc.keyboardHandler.setClipboard(new GsonBuilder()
                                    .disableHtmlEscaping()
                                    .create()
                                    .toJson(jsonArray));
                            logInfo("已拷贝IP列表");
                        }))));
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(0, 30, 80, 20)
                .setElementHandler(
                        new ButtonElement(TextProvider.of(Component.literal("Remove Server")), ButtonAction.empty())));
        ContentDelegateWidget<EditBox> textField2 = McWidgetHelpers.createTextFieldEditBox(
                80, 30, 100, 20, s -> this.currentInputRemove = s, this.currentInputRemove);
        subScreenWidget.addDrawableChild(textField2);
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(180, 30, 20, 20)
                .setElementHandler(new ButtonElement(
                                TextProvider.of(Component.literal("-").withStyle(ChatFormatting.BOLD)),
                                ButtonAction.run(() -> {
                                    if (!currentInputRemove.isEmpty()) {
                                        removeAll(currentInputRemove);
                                        logInfo("已移除 " + currentInputRemove);
                                    }
                                }))
                        .withTooltips(TooltipHandler.of(List.of(Component.literal("Remove Server"))))));
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(200, 30, 100, 20)
                .setElementHandler(new ButtonElement(
                        TextProvider.of(Component.literal("Refresh Shown")), ButtonAction.run(() -> {
                            List<String> refreshList = new ArrayList<>();
                            for (var entry : this.lastRenderTick.object2IntEntrySet()) {
                                if (entry.getIntValue() > Tasks.getTick() - updateInterval) {
                                    refreshList.add(entry.getKey());
                                }
                            }
                            refreshServerList(refreshList, 100);
                        }))));
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(300, 30, 100, 20)
                .setElementHandler(
                        new ButtonElement(TextProvider.of(Component.literal("Back")), ButtonAction.run(() -> {
                            if (ClientUtils.getScreen(mc) != null) ClientUtils.getScreen(mc).onClose();
                        }))));

        return subScreenWidget;
    }

    public void logInfo(String message) {
        logInfo = Component.nullToEmpty(message);
    }

    public void warn(String message) {
        logInfo = Component.literal(message).withStyle(ChatFormatting.YELLOW);
    }

    private AtomicBoolean running = new AtomicBoolean(false);

    public void startScanTask() {
        logInfo("");
        if (running.get()) {
            warn("当前任务暂未结束");
            return;
        }
        Debug.info("start scan with", ipField.get(), portRange1.get(), portRange2.get());
        int range1 = portRange1.get();
        int range2 = portRange2.get();
        if (range1 > range2) {
            warn("PortA需要比PortB小");
            return;
        }
        String ipField = this.ipField.get();
        int limitSample = this.limitSample.get();
        int sleepMs = this.requestDelay.get();
        boolean random = this.randomRequest.get();
        boolean filter = this.filter.get();
        try {
            InetSocketAddress address = new InetSocketAddress(ipField, range1);
            InetSocketAddress address2 = new InetSocketAddress(ipField, range2);
        } catch (Throwable e) {
            warn("输入的IP地址格式有误");
            return;
        }
        running.set(true);
        Random rand = new Random();
        EventLoopGroupHolder backend = EventLoopGroupHolder.remote(mc.options.useNativeTransport());
        Set<String> scannCopy = new HashSet<>(scannedIps);
        CompletableFuture.runAsync(() -> {
            ServerStatusPinger pinger = new ServerStatusPinger();
            int current = range1;
            boolean except = false;
            loop:
            for (var i = 0; i < limitSample; ++i) {
                if (!running.get()) {
                    logInfo("任务已终止!");
                    break loop;
                }
                String fullIp;
                int retry = 0;
                do {
                    current = random ? rand.nextInt(range1, range2) : current + 1;
                    if (current > range2) {
                        except = true;
                        break loop;
                    }
                    fullIp = ipField + ":" + current;
                    if (++retry > 10000) {
                        except = true;
                        break loop;
                    }
                } while (scannCopy.contains(fullIp));
                logInfo("扫描" + fullIp);
                scannCopy.add(fullIp);
                pingServer(pinger, backend, fullIp, filter);
                try {
                    logInfo("间隔中...");
                    Thread.sleep(sleepMs);
                } catch (Throwable e) {
                }
            }
            if (except) {
                logInfo("扫描中断");
            } else {
                logInfo("扫描结束");
            }
            running.set(false);
        });
    }

    public void startScanTaskAsync() {
        logInfo("");
        if (running.get()) {
            warn("当前任务暂未结束");
            return;
        }
        Debug.info("start scan with", ipField.get(), portRange1.get(), portRange2.get());
        int range1 = portRange1.get();
        int range2 = portRange2.get();
        if (range1 > range2) {
            warn("PortA需要比PortB小");
            return;
        }
        String ipField = this.ipField.get();
        int limitSample = this.limitSample.get();
        boolean random = this.randomRequest.get();
        boolean filter = this.filter.get();
        try {
            InetSocketAddress address = new InetSocketAddress(ipField, range1);
            InetSocketAddress address2 = new InetSocketAddress(ipField, range2);
        } catch (Throwable e) {
            warn("输入的IP地址格式有误");
            return;
        }
        running.set(true);
        Random rand = new Random();
        EventLoopGroupHolder backend = EventLoopGroupHolder.remote(mc.options.useNativeTransport());
        Set<String> scanCopy = Set.copyOf(scannedIps);
        CompletableFuture.runAsync(() -> {
            ServerStatusPinger pinger = new ServerStatusPinger();
            try (ThreadPoolExecutor executor = (ThreadPoolExecutor)
                    Executors.newFixedThreadPool(16, ThreadUtils.daemonThreadFactory("sfh-server-scan"))) {
                List<CompletableFuture<Void>> completableFutures = new ArrayList<>(limitSample);
                int current = range1;
                Set<String> currentQuery = ConcurrentHashMap.newKeySet();
                loop:
                for (var i = 0; i < limitSample; ++i) {
                    String fullIp = "";
                    int retry = 0;

                    do {
                        current = random ? rand.nextInt(range1, range2) : current + 1;
                        if (current > range2) {
                            break loop;
                        }
                        fullIp = ipField + ":" + current;
                        if (++retry > 10000) {
                            break loop;
                        }
                    } while (scanCopy.contains(fullIp) || currentQuery.contains(fullIp));
                    currentQuery.add(fullIp);
                    final String ip = fullIp;
                    completableFutures.add(CompletableFuture.runAsync(
                            () -> {
                                if (running.get()) {
                                    logInfo("扫描" + ip);
                                    pingServer(pinger, backend, ip, filter);
                                }
                            },
                            executor));
                }
                logInfo("异步处理请求中...");
                CompletableFuture.allOf(completableFutures.toArray(new CompletableFuture[completableFutures.size()]))
                        .join();
                logInfo("异步扫描结束");
            }

            running.set(false);
        });
    }

    public void abortScanTask() {
        logInfo("");
        if (!running.get()) {
            logInfo("当前无运行中任务");
            return;
        }
        logInfo("任务终止中...");
        running.set(false);
    }

    public void pingServer(ServerStatusPinger pinger, EventLoopGroupHolder backend, String ip, boolean filter) {
        ServerData pingingInfo = new ServerData("SlimefunHelper scanner", ip, ServerData.Type.OTHER);

        try {
            ServerAddress address = ServerAddress.parseString(ip);
            Optional<ResolvedServerAddress> optional = ServerNameResolver.DEFAULT.resolveAddress(address);
            if (optional.isPresent()) {
                try {
                    pinger.pingServer(
                            pingingInfo,
                            () -> {},
                            () -> {
                                pingingInfo.setState(ServerData.State.SUCCESSFUL);
                            },
                            backend);
                    addScannResult(ip, pingingInfo);
                } catch (Exception e) {
                    pingingInfo.setState(ServerData.State.UNREACHABLE);
                    if (!filter) {
                        addScannResult(ip, pingingInfo);
                    } else {
                        addScannExceptionResult(ip, pingingInfo);
                    }
                }
            } else {
                pingingInfo.setState(ServerData.State.UNREACHABLE);
                if (!filter) {
                    addScannResult(ip, pingingInfo);
                } else {
                    addScannExceptionResult(ip, pingingInfo);
                }
            }
        } catch (Throwable e) {
        }
    }

    public void clearScanResult() {
        if (listEntryController != null) {
            // clear controller first because if ip is clear, then dirty mark will not work
            listEntryController.clear();
        }
        cachedPingResult.clear();
        scannedIps.clear();
        saveServerList();
    }

    public void refreshServerList() {
        refreshServerList(scannedIps, requestDelay.get());
    }

    public void refreshServerList(List<String> refreshList, int delay) {
        if (running.get()) {
            logInfo("当前任务暂未结束");
            return;
        }
        running.set(true);
        CompletableFuture.runAsync(() -> {
            logInfo("");
            ServerStatusPinger pinger = new ServerStatusPinger();
            EventLoopGroupHolder backend = EventLoopGroupHolder.remote(mc.options.useNativeTransport());
            List<String> list = List.copyOf(refreshList);
            for (var lst : list) {
                if (!running.get()) {
                    logInfo("刷新中断");
                    return;
                }
                logInfo("刷新" + lst + "中");
                pingServer(pinger, backend, lst, false);
                try {
                    Thread.sleep(delay);
                } catch (Throwable e) {
                }
            }
            logInfo("已完成刷新");
            running.set(false);
        });
    }

    public void refreshSingle(String ip) {
        CompletableFuture.runAsync(() -> {
            ServerStatusPinger pinger = new ServerStatusPinger();
            EventLoopGroupHolder backend = EventLoopGroupHolder.remote(mc.options.useNativeTransport());
            pingServer(pinger, backend, ip, false);
        });
    }

    public void removeAll(String ip) {
        mc.execute(() -> {
            if (scannedIps.removeIf(s -> s.startsWith(ip))) {
                saveServerList();
                if (listEntryController != null) {
                    listEntryController.resync();
                }
            }
        });
    }

    private String currentInputAdd = "";
    private String currentInputRemove = "";

    public String createCurrentInputServer() {
        return currentInputAdd;
    }

    public void addScannResult(String ip, ServerData serverInfo) {
        mc.execute(() -> {
            cachedPingResult.put(ip, serverInfo);
            boolean has = false;
            for (var i = 0; i < scannedIps.size(); ++i) {
                if (Objects.equals(ip, scannedIps.get(i))) {
                    has = true;
                    if (listEntryController != null) {
                        listEntryController.update(i);
                    } else {
                        break;
                    }
                }
            }
            if (!has) {
                scannedIps.add(ip);
                if (listEntryController != null) {
                    listEntryController.update(scannedIps.size() - 1);
                }
                saveServerList();
            }
        });
    }

    public void addScannExceptionResult(String ip, ServerData info) {
        info.setState(ServerData.State.UNREACHABLE);
        mc.execute(() -> {
            cachedPingResult.put(ip, info);
            if (listEntryController != null) {
                for (var i = 0; i < scannedIps.size(); ++i) {
                    if (Objects.equals(ip, scannedIps.get(i))) {
                        if (listEntryController != null) {
                            listEntryController.update(i);
                        } else {
                            break;
                        }
                    }
                }
            }
            // do not add, only update
        });
    }

    public void saveServerList() {
        Map<String, List<String>> saveStruct = Map.of("save-list", scannedIps);
        serverListSave.write(saveStruct, JavaOps.INSTANCE);
        serverListSave.markDirty(true);
    }

    private final Map<String, FaviconTexture> openResources = new ConcurrentHashMap<>();

    public void closeResources() {
        openResources.clear();
        lastRenderTick.clear();
    }

    private ListModifyWidget selectWidget;

    public void openScannerScreen() {
        saveServerList();
        closeResources();
        // use single instance,
        if (listEntryController == null || selectWidget == null) {
            listEntryController = ListEntryWidgetController.mutable(
                    scannedIps, this::createCurrentInputServer, this::createJoinServerWidget, 40, 310);
            selectWidget = new ListModifyWidget(listEntryController, 0, 40, 390, 260).appendAddButton(false);
        }
        DrawableWidget inputWidget = createInputWidget();
        DrawableWidget outputWidget = createOutputWidget();
        var screen = new GenericScreen(Component.literal("Server Scanner"), 400, 360) {
            @Override
            protected void init() {
                super.init();
                SubScreenWidget delegate = new SubScreenWidget(this.x, this.y, 400, 360);
                delegate.addDrawableChild(inputWidget);
                delegate.addDrawableChild(selectWidget);
                delegate.addDrawableChild(outputWidget);
                addRenderableWidget(delegate);
            }

            @Override
            public void onClose() {
                super.onClose();
                saveServerList();
                closeResources();
            }
        };

        logInfo("");
        ScreenAccess.of(screen).openFromCurrent();

        refreshServerList(scannedIps.subList(0, Math.min(scannedIps.size(), 10)), 100);
    }

    public Map<String, ServerData> cachedPingResult = new ConcurrentHashMap<>();

    public Object2IntOpenHashMap<String> lastRenderTick = new Object2IntOpenHashMap<>();

    private static final int updateInterval = 200;

    public DrawableWidget createJoinServerWidget(String ip) {
        int textHeight = Math.round(TextRenderers.get().getTextHeight() + 1.0F);
        int textLineStep = Math.round(TextRenderers.get().getTextHeight());
        SubScreenWidget subScreenWidget = new SubScreenWidget(0, 0, 310, 40);
        // draw a highlight frame when mouse is over
        AtomicInteger firstRenderTime = new AtomicInteger(0);
        subScreenWidget.addDrawableChild(DisplayWidget.instance(0, 0, 310, 40)
                .setRenderHandler(new AbstractElement()
                        .setShowTooltips(false)
                        .combineRender(((element, context, mouseX, mouseY, delta, alpha, shouldHighlight) -> {
                            if (firstRenderTime.get() == 0) {
                                firstRenderTime.set(Tasks.getTick());
                            } else {
                                if (Tasks.getTick() > firstRenderTime.get() + 40) {
                                    // stable render element, try do refresh
                                    if (lastRenderTick.getInt(ip) == 0) {
                                        if (!cachedPingResult.containsKey(ip)) {
                                            refreshSingle(ip);
                                        }
                                    } else if (lastRenderTick.getInt(ip) + updateInterval < Tasks.getTick()) {
                                        refreshSingle(ip);
                                    }
                                    lastRenderTick.put(ip, Tasks.getTick());
                                }
                            }
                            if (element.isMouseOver(mouseX, mouseY)) {
                                RenderHandler.drawHighlightFrame(
                                        context,
                                        0,
                                        0,
                                        element.getTextureWidth(),
                                        element.getTextureHeight(),
                                        CommonColors.WHITE);
                            }
                        }))));

        subScreenWidget.addDrawableChild(ExecutableWidget.instance(45, 0, 100, textHeight)
                .setElementHandler(RawTextElement.instance(Component.literal(ip))
                        .setAlignment(TextAlign.LEFT)
                        .withInputHandler(new ButtonElement(TextProvider.of(Component.empty()), ButtonAction.run(() -> {
                            mc.keyboardHandler.setClipboard(ip);
                            logInfo("成功拷贝ip");
                        })))
                        .withTooltips(TooltipHandler.of(List.of(Component.literal("Click to copy ip"))))));
        ServerData info = cachedPingResult.get(ip);

        if (info != null) {
            subScreenWidget.addDrawableChild(
                    ExecutableWidget.instance(2, 2, 36, 36).setElementHandler(createServerIconDisplay(info)));
            subScreenWidget.addDrawableChild(
                    ExecutableWidget.instance(1, 1, 310, 38).setElementHandler(createJoinServerInteract(info)));
            subScreenWidget.addDrawableChild(DisplayWidget.instance(260, 0, 50, 40)
                    .setRenderHandler(TooltipHandler.of(() -> getServerInfoHover(info))));
            subScreenWidget.addDrawableChild(DisplayWidget.instance(260, textLineStep, 50, textHeight)
                    .setRenderHandler(RawTextElement.instance((b) -> getStatusDisplay(info.state()))
                            .setAlignment(TextAlign.RIGHT)));
            subScreenWidget.addDrawableChild(DisplayWidget.instance(260, textLineStep * 2, 50, textHeight)
                    .setRenderHandler(RawTextElement.instance((b) -> getPlayerListDisplay(info))
                            .setAlignment(TextAlign.RIGHT)));

            subScreenWidget.addDrawableChild(DisplayWidget.instance(260, textLineStep * 3, 50, textHeight)
                    .setRenderHandler(RawTextElement.instance((b) -> getServerBrandInfoDisplay(info))
                            .setAlignment(TextAlign.RIGHT)));
            // motd
            subScreenWidget.addDrawableChild(DisplayWidget.instance(50, 10, 270, 30)
                    .setRenderHandler(
                            new MultiLineTextElement((s) -> getServerMotd(info), CommonColors.WHITE, TextAlign.LEFT)));
        } else {
            subScreenWidget.addDrawableChild(
                    DisplayWidget.instance(2, 2, 36, 36).setRenderHandler(LabelElement.instance(Component.empty())));
        }

        // subScreenWidget.addDrawableChild()
        return subScreenWidget;
    }

    private Component getStatusDisplay(ServerData.State status) {
        return switch (status) {
            case INITIAL, PINGING -> Component.literal("Pinging...").withStyle(ChatFormatting.WHITE);
            case UNREACHABLE -> Component.literal("No Connection").withStyle(ChatFormatting.RED);
            case SUCCESSFUL -> Component.literal("Available").withStyle(ChatFormatting.GREEN);
            case INCOMPATIBLE -> Component.literal("Outdated").withStyle(ChatFormatting.YELLOW);
        };
    }

    private Component getPlayerListDisplay(ServerData serverInfo) {
        if (serverInfo.state() == ServerData.State.UNREACHABLE) {
            return Component.empty();
        }
        if (serverInfo.players == null) {
            return Component.literal("加载中...");
        }
        return Component.literal(serverInfo.players.online() + "/" + serverInfo.players.max())
                .withStyle(ChatFormatting.GRAY);
    }

    private Component getServerBrandInfoDisplay(ServerData serverInfo) {
        if (serverInfo.state() == ServerData.State.UNREACHABLE) {
            return Component.empty();
        }
        return serverInfo.version;
    }

    private List<Component> getServerInfoHover(ServerData serverInfo) {
        List<Component> list = new ArrayList<>();
        list.add(Component.literal("服务器协议号:" + serverInfo.protocol));
        list.add(Component.literal("服务器玩家:"));
        list.addAll(serverInfo.playerList);
        return list;
    }

    private Component getServerMotd(ServerData serverInfo) {
        if (serverInfo.state() == ServerData.State.UNREACHABLE || serverInfo.motd == null) {
            return Component.empty();
        }
        return serverInfo.motd;
    }

    private ElementHandler createServerIconDisplay(@Nonnull ServerData serverInfo) {
        return new IconElement.SimpleIconElement(null, null, false, ButtonAction.run(() -> connect(serverInfo))) {
            ServerData info = serverInfo;
            FaviconTexture worldIcon = getWorldIcon();

            public FaviconTexture getWorldIcon() {
                return openResources.computeIfAbsent(
                        info.ip, (s) -> FaviconTexture.forServer(mc.getTextureManager(), s));
            }

            private byte @Nullable [] favicon;

            @Override
            public @Nullable Identifier getTextureId(VDrawContext context, DrawableWidget element, boolean highlight) {
                if (worldIcon.isClosed()) {
                    worldIcon = getWorldIcon();
                }
                byte[] bs = this.info.getIconBytes();
                if (!Arrays.equals(bs, this.favicon)) {
                    if (uploadFavicon(bs)) {
                        this.favicon = bs;
                    } else {
                        this.info.setIconBytes(null);
                    }
                }

                return this.worldIcon.textureLocation();
            }

            private boolean uploadFavicon(byte @Nullable [] bytes) {
                if (bytes == null) {
                    this.worldIcon.clear();
                } else {
                    try {
                        this.worldIcon.upload(NativeImage.read(bytes));
                    } catch (Throwable var3) {
                        return false;
                    }
                }

                return true;
            }
        }.setActive(false);
    }

    private ElementHandler createJoinServerInteract(ServerData serverInfo) {
        AtomicInteger lastClickCounter = new AtomicInteger();
        return new IconElement.SimpleIconElement(null, null, true, (el1, el2, el3) -> {
                    int lastTick = lastClickCounter.get();
                    if (Tasks.getTick() < lastTick + 10) {
                        connect(serverInfo);
                        return true;
                    } else {
                        lastClickCounter.set(Tasks.getTick());
                        return true;
                    }
                })
                .setHighLightColor((el, h) -> ((DrawableWidget) el).isFocused() ? CommonColors.WHITE : null)
                .setShowTooltips(false);
    }

    private void connect(ServerData serverInfo) {
        Screen screen = ClientUtils.getScreen(mc);
        if (screen != null) {
            ConnectScreen.startConnecting(
                    screen, mc, ServerAddress.parseString(serverInfo.ip), serverInfo, false, null);
        }
    }
}
