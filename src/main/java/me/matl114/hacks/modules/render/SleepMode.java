package me.matl114.hacks.modules.render;

import net.minecraft.client.renderer.fog.FogRenderer;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.function.Consumer;
import java.util.stream.Stream;
import me.matl114.accessors.access.ChatScreenAccess;
import me.matl114.managers.command.MainCommand;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.*;
import me.matl114.gui.basic.*;
import me.matl114.gui.elements.ButtonElement;
import me.matl114.gui.elements.LabelElement;
import me.matl114.hacks.RenderTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.*;
import me.matl114.utils.ClientUtils;
import me.matl114.utils.Debug;
import me.matl114.utils.ScreenUtils;
import me.matl114.utils.collections.Point;
import me.matl114.utils.commands.commandGroup.BridgeSubCommand;
import me.matl114.utils.commands.commandGroup.CommandContext;
import me.matl114.utils.commands.commandGroup.SubCommand;
import me.matl114.utils.commands.params.ArgumentInputStream;
import me.matl114.utils.commands.params.SimpleCommandArgs;
import me.matl114.utils.config.ValueAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

public class SleepMode extends BaseModule {
    public static SleepMode INSTANCE;
    private int sleepingLevel = 0;
    public final ModulePath render = makePath(Configs.RENDER_CONFIG, "render");

    public SleepMode() {
        super("SleepMode");
        INSTANCE = this;
    }

    public final KeyBindRef keyBindRef = hotkey(
                    Configs.RENDER_CONFIG, render.add("wake-up-screen").toPath())
            .defaultValue(new MultiKeyBind(KeyCode.KEY_F11))
            .build();

    public final FlagRef runnerOptimize =
            flagBuilder(render.add("sleep-mode-runner-optimize")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        // todo: make them  temporary listeners
        registerListener(Listener.getGameRender(), this::onGameRender, Integer.MIN_VALUE);
        registerListener(Listener.getResolutionChange(), this::onSleepingResizeScreen, Integer.MIN_VALUE);
        registerListener(Listener.getMidSetScreen(), this::interceptScreenSetup, Integer.MIN_VALUE);
        registerListener(Listener.getKeyboardInput(), this::interceptScreenKeyboardAction, Integer.MIN_VALUE);
        registerListener(Listener.getMouseButton(), this::interceptScreenMouseAction, Integer.MIN_VALUE);
        registerListener(Listener.getMouseScroll(), this::interceptScreenMouseScroll, Integer.MIN_VALUE);
        registerListener(Listener.getCharTyped(), this::interceptCharType, Integer.MIN_VALUE);
        registerListener(Listener.getMouseMove(), this::interceptMouseMove, Integer.MIN_VALUE);
        registerListener(Listener.getMouseDrag(), this::interceptMouseDragged, Integer.MIN_VALUE);
        registerListener(Listener.getPreSetScreen(), this::interceptSetScreen, Integer.MIN_VALUE);
        registerCommandBootstrap(this::onSleepCommandBootstrap);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundLevelChunkWithLightPacket.class),
                this::onChunkData,
                Integer.MIN_VALUE);
        registerListener(Listener.getHotKeyTriggeredListener(), this::interceptHotKey, Integer.MIN_VALUE);
    }

    @Override
    public void addCustomWidgets(Consumer<DrawableWidget> acceptor, int dx, int dy, int dblank) {
        super.addCustomWidgets(acceptor, dx, dy, dblank);
        acceptor.accept(createTitle("widget.sleep-mode.command", 0, dblank, dx, dy));
    }

    boolean runnerOptimizeStart = false;

    public void checkOptimizeState() {
        if (!isScreenSleeping()) {
            runnerOptimizeStart = false;
        }
    }

    public void onSleepCommandBootstrap(MainCommand mainCommand) {
        mainCommand.registerSub(new BridgeSubCommand(
                "sleep",
                SubCommand.taskBuilder()
                        .name("sleep")
                        .helper("message.command.sleep.help")
                        .arg(SimpleCommandArgs.argumentBuilder()
                                .name("level")
                                .intValue()
                                .build())
                        .arg(SimpleCommandArgs.argumentBuilder()
                                .name("confirm")
                                .dispatchLastArg((str) -> {
                                    int val = str.getInt();
                                    if (val > 0) {
                                        return Stream.of("confirm");
                                    } else {
                                        return Stream.of("第一个参数请输入正整数");
                                    }
                                })
                                .defaultValue("")
                                .build())
                        .arg(SimpleCommandArgs.argumentBuilder().name("display").build())
                        .post(e -> e.executor(CommandContext.run(this::onSleep)))
                        .build()));
    }

    public void onSleep(ArgumentInputStream re) {
        int level = re.nextClampedInt(1, 3);
        if (level != 1 && level != 2) {
            Debug.chat("请输入范围内的数字: 1~2");
            return;
        }
        String val = re.nextNonnull();
        String val2 = re.nextArg();
        if ("confirm".equals(val)) {
            if (runnerOptimize.get()) {
                runnerOptimizeStart = true;
            }
            Tasks.scheduleDelayed(() -> RenderTasks.getSleepMode().setCustomScreenSleeping(level, val2), 1);
        } else {
            Debug.chat("使用sleep confirm 确认进入睡眠模式, 进入睡眠模式后可以按 "
                    + RenderTasks.getSleepMode().getWakeupButton() + " 键离开");
        }
    }

    public void onGameRender(Event<GameRenderer> rendererEvent) {
        if (isScreenSleeping()) {
            if (sleepingRenderTick(rendererEvent.context(), rendererEvent.getArgs(0))) {
                rendererEvent.cancel();
            }
        }
    }

    public boolean isScreenSleeping() {
        return sleepingLevel != 0;
    }

    public boolean wakeUpScreen() {
        if (setScreenSleeping(0)) {
            if (mc.player != null) Debug.chat(Component.literal("睡眠状态结束, 欢迎回来!").withStyle(ChatFormatting.GREEN));
            return true;
        } else return false;
    }

    public boolean setScreenSleeping(int s) {
        return setCustomScreenSleeping(s, null);
    }

    public boolean setCustomScreenSleeping(int s, String sleep) {
        if (sleepingLevel != s) {

            if (s != 0) {
                sleepingLevel = s;
                setUpSleepingScreen(sleep == null ? getDefaultDisplayText() : Component.literal(sleep));
            } else {
                // sleeping = false;
                sleepingLevel = s;
                // 递归关闭全部sleepingScreen
                //                while (mc.currentScreen != null && mc.currentScreen == sleepingScreenInstance){
                //                    sleepingScreenInstance.close();
                //                }
                sleepingScreenInstance = null;
                currentRenderingSleeping = null;
                if (ClientUtils.getScreen(mc) == null) {
                    ClientUtils.setScreen(mc, null);
                }
            }
            return true;
        }
        return false;
    }

    private Screen sleepingScreenInstance;
    private Screen currentRenderingSleeping;

    public Screen getCurrentRenderingSleeping() {
        return currentRenderingSleeping;
    }

    private class SleepingChatScreen extends ChatScreen implements SleepOverlay {
        Component displayMessage;

        public SleepingChatScreen(String originalChatText, Component displayMessage) {
            super(originalChatText, false);
            this.displayMessage = displayMessage;
        }

        protected void init() {
            super.init();
            sleepingScreenInstance = this;
            DisplayWidget.instance(this.width - 80, 0, 80, 40)
                    .setRenderHandler(LabelElement.instance(displayMessage))
                    .addTo(this);
            shouldFreshSleepScreen = true;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            super.extractRenderState(context, mouseX, mouseY, delta);
            //            Debug.info(mouseX, mouseY, mc.inGameHud.getChatHud().getTextStyleAt(mouseX, mouseY));
            shouldFreshSleepScreen = true;
        }

        public boolean keyPressed(KeyEvent input) {
            // fix: SleepingScreen may be wrongly set on currentScreen
            if (sleepingScreenInstance == this && input.isConfirmation()) {
                // intercept send, else left for super
                this.handleChatInput(this.input.getValue(), true);
                this.input.setValue("");
                ChatScreenAccess.of(this).resetMessageHistoryIndex();
                return true;
            } else return super.keyPressed(input);
        }

        @Override
        public void onClose() {
            // do not close till sleeping is over or game exit
            if (sleepingScreenInstance != this) {
                super.onClose();
            }
            //            if(mc.currentScreen == this){
            //                super.close();
            //                return;
            //            }
            //            if(mc.player == null || !isScreenSleeping()){
            //                super.close();
            //            }
        }
    }

    private class SleepingScreen extends Screen implements SafeSleepingScreen {
        Component displayMessage;

        protected SleepingScreen(Component title, Component displayMessage) {
            super(title);
            this.displayMessage = displayMessage;
        }

        private static final int SIZE = 150;

        @Override
        protected void init() {
            super.init();
            sleepingScreenInstance = this;
            var widget = DisplayWidget.instance(0, 0, this.width - SIZE, this.height - SIZE)
                    .setRenderHandler(LabelElement.instance(displayMessage));
            new DynamicContentWidget<>(
                            () -> widget,
                            ValueAccessor.ofIgnore(() -> revert(Tasks.getTick())),
                            ValueAccessor.ofIgnore(() -> revert(1.618 * Tasks.getTick())))
                    .addTo(this);
            shouldFreshSleepScreen = true;
        }

        private int revert(double v) {
            int val = (int) (v % (2 * SIZE));
            if (val > SIZE) {
                return 2 * SIZE - val;
            } else {
                return val;
            }
        }

        public void renderBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {}
    }

    private class GameExitWhileSleepingScreen extends Screen implements SafeSleepingScreen {
        protected GameExitWhileSleepingScreen() {
            super(Component.empty());
        }

        @Override
        protected void init() {
            super.init();
            sleepingScreenInstance = this;
            DisplayWidget.instance(40, 20, this.width - 80, this.height / 3 - 40)
                    .setRenderHandler(LabelElement.instance(Component.literal("您的游戏在待机中退出,目前已停止刷新")))
                    .addTo(this);
            DisplayWidget.instance(40, this.height / 3 + 20, this.width - 80, this.height / 3 - 40)
                    .setRenderHandler(LabelElement.instance(Component.literal("按 " + getWakeupButton() + " 键退出休眠模式")))
                    .addTo(this);
            ExecutableWidget.instance(40, (this.height * 2) / 3 + 20, this.width - 80, this.height / 3 - 40)
                    .setElementHandler(new ButtonElement(
                            TextProvider.of(Component.literal("点击下方按钮以刷新屏幕")), ButtonAction.run(() -> {
                                if (isScreenSleeping()) {
                                    if (ClientUtils.isPlayerOnline()) {
                                        sleepingScreenInstance = null;
                                        setUpSleepingScreen(getDefaultDisplayText());
                                    } else {
                                        // keep this screen
                                    }
                                }
                            })))
                    .addTo(this);
            shouldFreshSleepScreen = true;
        }

        public void renderBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {}
    }

    private Component getDefaultDisplayText() {
        return Component.literal("按 " + getWakeupButton() + " 键退出休眠模式");
    }

    private static interface SafeSleepingScreen extends SleepOverlay {
        // screen which implement this can keep even when player exit game, which means it does not need mc.player or
        // mc.level or sth
    }
    //
    public void setUpSleepingScreen(Component display) {
        if (sleepingScreenInstance == null) {
            switch (sleepingLevel) {
                case 1:
                    sleepingScreenInstance = new SleepingChatScreen("", display);
                    break;
                default:
                    sleepingScreenInstance = new SleepingScreen(Component.empty(), display);
                    break;
            }
        }
    }

    private void setCurrentRenderingSleeping(Screen screen) {
        if (screen != null) {
            mc.mouseHandler.releaseMouse();
            KeyMapping.releaseAll();
            currentRenderingSleeping = screen;
            currentRenderingSleeping.init(
                    mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());

        } else {
            currentRenderingSleeping = null;
            // reset cursor and keybinds
            if (ClientUtils.getScreen(mc) != null) {
                mc.mouseHandler.releaseMouse();
                KeyMapping.releaseAll();
            } else {
                mc.mouseHandler.grabMouse();
                mc.getSoundManager().resume();
            }
        }
    }

    public boolean ensureSleepingScreen() {
        if (!isScreenSleeping()) {
            return false;
        }
        boolean refresh = false;
        if (ClientUtils.isPlayerOnline()) {
            if (currentRenderingSleeping != sleepingScreenInstance) {
                setCurrentRenderingSleeping(sleepingScreenInstance);
                refresh = true;
            }
        } else {
            if (!(sleepingScreenInstance instanceof GameExitWhileSleepingScreen)) {
                setCurrentRenderingSleeping(sleepingScreenInstance = new GameExitWhileSleepingScreen());
                refresh = true;
            }
        }
        if (refresh) {
            // clear current  view
            // 必须连**颜色**缓冲一起清：vanilla 是在 GameRenderer.render() 开头做
            // clearColorAndDepthTextures(...)，而这里把整个 render() 取消了，
            // 只清深度的话颜色缓冲会留着上一帧 → 画面"卡在世界渲染的最后一帧"；
            // 上游的表现是纯黑，就是因为它清成了 clearColorOverride。
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .clearColorAndDepthTextures(
                            ClientUtils.getMainRenderTarget(mc).getColorTexture(),
                            mc.gameRenderer.gameRenderState.guiRenderState.clearColorOverride,
                            ClientUtils.getMainRenderTarget(mc).getDepthTexture(),
                            1.0);
            mc.gameRenderer.gameRenderState.guiRenderState.reset();
            //            mc.getFramebuffer().clear(true);
            //            mc.getFramebuffer().endRead();
            //            mc.getFramebuffer().beginWrite(true);
            return true;
        }
        return true;
    }

    private boolean shouldFreshSleepScreen = false;

    public void onSleepingResizeScreen(Event<Point> event) {
        if (currentRenderingSleeping != null) {
            currentRenderingSleeping.resize(event.context.x, event.context.y);
        }
    }

    public boolean sleepingRenderTick(GameRenderer gameRenderer, DeltaTracker tickCounter) {

        if (ensureSleepingScreen()) {
            if (currentRenderingSleeping != null) {
                shouldFreshSleepScreen = true;
                if (shouldFreshSleepScreen) {
                    shouldFreshSleepScreen = false;
                    mc.gameRenderer.globalSettingsUniform.update(
                            mc.getWindow().getWidth(),
                            mc.getWindow().getHeight(),
                            (Double) mc.options.glintStrength().get(),
                            mc.level == null ? 0L : mc.level.getGameTime(),
                            tickCounter,
                            mc.options.getMenuBackgroundBlurriness(),
                            mc.gameRenderer.mainCamera().position(),
                            mc.options.textureFiltering().get() == TextureFilteringMethod.RGSS);

                    int i = (int) (mc.mouseHandler.xpos()
                            * (double) mc.getWindow().getGuiScaledWidth()
                            / (double) mc.getWindow().getScreenWidth());
                    int j = (int) (mc.mouseHandler.ypos()
                            * (double) mc.getWindow().getGuiScaledHeight()
                            / (double) mc.getWindow().getScreenHeight());

                    // 同上：只清深度会让画面停在世界渲染的最后一帧
                    RenderSystem.getDevice()
                            .createCommandEncoder()
                            .clearColorAndDepthTextures(
                                    ClientUtils.getMainRenderTarget(mc).getColorTexture(),
                                    mc.gameRenderer.gameRenderState.guiRenderState.clearColorOverride,
                                    ClientUtils.getMainRenderTarget(mc).getDepthTexture(),
                                    1.0);
                    mc.gameRenderer.gameRenderState.guiRenderState.reset();
                    GuiGraphicsExtractor drawContext =
                            new GuiGraphicsExtractor(mc, mc.gameRenderer.gameRenderState.guiRenderState, i, j);

                    currentRenderingSleeping.extractRenderState(drawContext, i, j, tickCounter.getGameTimeDeltaTicks());
                    // 26.2: GuiRenderer.render() 无参，且 incrementFrameNumber() 已移除
                    //#if MC >= 26.2
                    mc.gameRenderer.guiRenderer.render();
                    //#else
                    //$$ mc.gameRenderer.guiRenderer.render(
                    //$$         mc.gameRenderer.fogRenderer.getBuffer(FogRenderer.FogMode.NONE));
                    //#endif
                    // vanilla 在 render() 之后会跟一次 endFrame()（内部是 itemAtlas.endFrame()），
                    // 取消 render() 的场景下别漏掉
                    mc.gameRenderer.guiRenderer.endFrame();
                    drawContext.applyCursor(mc.getWindow());
                    mc.gameRenderer.resourcePool.endFrame();
                }
            } else {
                setUpSleepingScreen(getDefaultDisplayText());
            }
            return true;
        }
        return false;
    }

    public void interceptScreenSetup(Event<Screen> event) {
        if (isScreenSleeping()) {
            event.cancel();
        }
    }

    public void interceptScreenKeyboardAction(Event<KeyboardAction> event) {
        if (isScreenSleeping()) {
            event.cancel();
            if (event.context.keyCode() == keyBindRef.get().getLastKey()) {
                wakeUpScreen();
                return;
            }
            if (sleepingScreenInstance != null) {
                // KeyboardInput 通道的载荷就是 KeyboardAction 记录，四个值都在里面。
                // 这里以前读的是 event.extraArgs[0..3]，但发布方（SimpleInputManager.onKeyInput）
                // 构造 Event 时没传 extraArgs，所以那个数组长度为 0 →
                // 睡眠模式期间按任意非唤醒键都会抛 ArrayIndexOutOfBoundsException。
                KeyboardAction action = event.context();
                ScreenUtils.simulateKeyAction(
                        sleepingScreenInstance,
                        action.keyCode(),
                        action.scannCode(),
                        action.action(),
                        action.modifier());
            }
        }
    }

    public void interceptScreenMouseAction(Event<MouseClickAction> event) {
        if (isScreenSleeping()) {
            event.cancel();
            if (sleepingScreenInstance != null) {
                ScreenUtils.simulateMouseButton(
                        sleepingScreenInstance,
                        event.context.eventButton(),
                        event.context.action(),
                        event.context.mode());
            }
        }
    }

    public void interceptScreenMouseScroll(Event<MouseScrollAction> event) {
        if (isScreenSleeping()) {
            event.cancel();

            if (sleepingScreenInstance != null) {
                ScreenUtils.simulateMouseScroll(
                        sleepingScreenInstance, event.context.horizontal(), event.context.vertical());
            }
        }
    }

    public void interceptCharType(Event<CharTypedAction> event) {
        if (isScreenSleeping()) {
            event.cancel();
            if (sleepingScreenInstance != null) {
                sleepingScreenInstance.charTyped(new CharacterEvent(event.context.chr()));
            }
        }
    }

    public void interceptMouseMove(Event<MouseMoveAction> event) {
        if (isScreenSleeping()) {
            event.cancel();
            if (sleepingScreenInstance != null) {
                sleepingScreenInstance.mouseMoved(event.context.mouseX(), event.context.mouseY());
            }
        }
    }

    public void interceptMouseDragged(Event<MouseDragAction> event) {
        if (isScreenSleeping()) {
            event.cancel();
            if (sleepingScreenInstance != null) {
                sleepingScreenInstance.mouseDragged(
                        new MouseButtonEvent(
                                event.context.mouseX(), event.context.mouseY(), event.context.mouse().activeButton),
                        event.context.deltaX(),
                        event.context.deltaY());
            }
        }
    }

    private static interface SleepOverlay {}

    public String getWakeupButton() {
        return keyBindRef.get().getKeyStr();
    }

    public void interceptSetScreen(Event<Screen> setScreen) {
        if (setScreen.context instanceof SleepOverlay) {
            setScreen.cancel();
            //
            ClientUtils.setScreen(mc, null);
        }
    }

    public void interceptHotKey(Event<IHotKey> eventHotKey) {
        if (isScreenSleeping()) {
            eventHotKey.cancel();
        }
    }

    public void onChunkData(Event<ClientboundLevelChunkWithLightPacket> dataS2CPacket) {
        if (!runnerOptimizeStart) return;
        checkOptimizeState();
        if (checkNull()) return;
        if (runnerOptimizeStart && mc.player.getY() > mc.level.getMinY() + mc.level.getHeight()) {
            dataS2CPacket.cancel();
        }
    }

    // TODO: add status renderer , inGameHud
}
