package me.matl114.hacks.modules.extra;

import me.matl114.utils.ClientUtils;
import com.google.common.util.concurrent.Runnables;
import java.util.Comparator;
import java.util.List;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.gui.GenericScreen;
import me.matl114.gui.presets.choices.QuestionScreen;
import me.matl114.hacks.MainTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.StringRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.Debug;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.ItemStackUtils;
import me.matl114.versioned.api.VEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class ClientExtra extends BaseModule {
    public static ClientExtra INSTANCE;

    public ClientExtra() {
        super("ClientExtra");
        INSTANCE = this;
    }

    public final ModulePath other = makePath(Configs.EXTRA_CONFIG, "other");

    public final FlagRef noCrash = builder(other.add("no-client-crash"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef keepInServer =
            flagBuilder(other.add("client-crash-keep-in-server")).build();

    public final FlagRef noEntityCrash =
            flagBuilder(other.add("no-entity-crash")).build();

    public final FlagRef noBlockEntityCrash =
            flagBuilder(other.add("no-block-entity-crash")).build();

    public final FlagRef noNtwException = builder(other.add("no-disconnect-on-network-error"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef noDecodeException = builder(other.add("no-disconnect-on-packet-decode"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef noUnexpected = builder(other.add("no-disconnect-on-packet-unexpected"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef portalGui =
            flagBuilder(other.add("keep-gui-open-on-portal")).build();

    public final StringRef clientBrandName = builder(other.add("client-brand-name"), StringRef.TYPE)
            .defaultValue("")
            .build();

    public final KeyBindRef cursorSwitchKey = hotkey(other.add("cursor-switch-hotkey"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.asHandler(this::onCursorLockSwitch))
            .build();

    public final KeyBindRef blankScreenKey = hotkey(other.add("create-blank-transparent-screen"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.asHandler(this::onBlankScreenCreate))
            .build();

    public final FlagRef logServerExiting =
            flagBuilder(other.add("log-self-server-leaving")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getClientMainExit(), this::onCrash);
        registerListener(
                Listener.getExceptionListener().getChannel(Listener.ExceptionType.PACKET_HANDLE_EXCEPTION),
                this::onNetworkException);
        registerListener(
                Listener.getExceptionListener().getChannel(Listener.ExceptionType.ENTITY_TICK),
                this::onEntityException);
        registerListener(
                Listener.getExceptionListener().getChannel(Listener.ExceptionType.BLOCK_ENTITY_TICK),
                this::onBlockEntityException);
        registerListener(
                Listener.getExceptionListener().getChannel(Listener.ExceptionType.PACKET_DECODE_EXCEPTION),
                this::onDecodeException);

        registerListener(
                Listener.getExceptionListener().getChannel(Listener.ExceptionType.UNKNOWN_CHANNEL_EXCEPTION),
                this::onUnexpectedException);
        registerListener(Listener.getServerLeavePoint(), this::onServerLeave);
    }

    private final Component questionCrash =
            Component.literal("你的游戏刚才因为未知原因崩溃,但是SlimefunHelper拦截了它").withStyle(ChatFormatting.RED);

    private void exitGame() {
        mc.stop();
    }

    public void onCrash(Event<Minecraft> event) {
        if (event.canCancel() && event.context().isRunning() && noCrash.get()) {
            event.cancel();
            CrashReport report = event.getArgs(0);
            // must disconnect from server here
            String msg = (report == null ? "null" : report.getTitle());
            String detailedMessage = (report == null ? "null" : report.getExceptionMessage());
            // remove
            detailedMessage = detailedMessage.replace("\t", "");
            String[] lines = detailedMessage.split("\\r?\\n");
            StringBuilder sb = new StringBuilder();
            int maxLines = Math.min(lines.length, 6);
            for (int i = 0; i < maxLines; i++) {
                if (i > 0) sb.append("\n");
                sb.append(lines[i]);
            }
            if (maxLines > 1 && maxLines < lines.length) {
                sb.append("\n......(%d行)".formatted(lines.length - maxLines));
            }
            detailedMessage = sb.toString();
            Component literal = ChatUtils.stringToText("&c你的游戏刚刚崩溃了,但是SlimefunHelper拦截了它\n报错信息: " + msg + "\n"
                    + detailedMessage + "\n如果你须与寻求帮助,请点击下方按钮打开错误报告\n而不是发送这个界面的截图");
            List<QuestionScreen.Solution> crashSolutions = List.of(
                    QuestionScreen.Solution.of(
                            Component.literal("我已知晓, 继续游戏").withStyle(ChatFormatting.GREEN), Runnables.doNothing()),
                    QuestionScreen.Solution.of(Component.literal("打开报告, 继续游戏").withStyle(ChatFormatting.YELLOW), () -> {
                        if (report != null) {
                            var path = report.getSaveFile();
                            if (path != null) {
                                Util.getPlatform().openPath(report.getSaveFile().getParent());
                                Util.getPlatform().openPath(report.getSaveFile());
                            }
                        }
                    }),
                    QuestionScreen.Solution.of(
                            Component.literal("我已知晓, 退出游戏").withStyle(ChatFormatting.RED), this::exitGame));
            QuestionScreen screen = new QuestionScreen(literal, crashSolutions);
            checkClientData(screen);
        }
    }

    public void onNetworkException(Event<Listener.WrapperException> event) {
        if (noNtwException.get()) {
            Listener.WrapperException we = event.context();
            Packet<?> packet = event.getArgs(0);
            PacketListener listener = event.getArgs(1);
            Throwable exception = we.exception();
            if (mc.player != null) {
                Debug.chat(Component.literal("Error while handling a network packet: ")
                        .withStyle(ChatFormatting.RED)
                        .append(Component.literal(packet.getClass().getSimpleName())));
                Debug.chat(
                        exception.getClass().getSimpleName(),
                        ":",
                        Component.literal(exception.getMessage() == null ? "Exception: null" : exception.getMessage()));
            }
            Debug.info("Packet Exception INFO :");
            Debug.info("  PacketListener : ", listener);
            Debug.info("  Packet :", packet);
            Debug.info("Exception StackTrace:");
            Debug.info(exception);
            event.cancel();
        }
    }

    public void onEntityException(Event<Listener.WrapperException> event) {
        if (noEntityCrash.get()) {
            Listener.WrapperException we = event.context();
            Entity entity = event.getArgs(0);
            event.cancel();
            if (!entity.isRemoved()) {
                // try fix common issues:
                Throwable exception = we.exception();
                Debug.chat(
                        "Error while ticking entity:",
                        entity.getDisplayName(),
                        entity instanceof Player player
                                ? "(%s)".formatted(player.getScoreboardName())
                                : "(%s)".formatted(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
                Debug.chat(
                        exception.getClass().getSimpleName(),
                        ":",
                        Component.literal(exception.getMessage() == null ? "Exception: null" : exception.getMessage()));
                // try fix common issues
                if (!validVec3d(entity.position())) {
                    Debug.chat("Invalid Position detected!");
                    entity.setPos(Vec3.ZERO);
                }
                if (!validVec3d(entity.getDeltaMovement())) {
                    Debug.chat("Invalid Velocity detected!");
                    entity.setDeltaMovement(Vec3.ZERO);
                }
                if (!Double.isFinite(entity.getXRot()) || !Double.isFinite(entity.getYRot())) {
                    Debug.chat("Invalid Rotation detected!");
                    entity.setXRot(0);
                    entity.setYRot(0);
                }
                Debug.info("Entity Exception INFO :");
                Debug.info("  Entity : ", entity);
                try {
                    Debug.info("  EntityNBT : ", VEntity.saveEntityNbt(entity));
                } catch (Throwable e) {
                }
                Debug.info("Exception StackTrace:");
                Debug.info(exception);
            }
        }
    }

    public void onBlockEntityException(Event<Listener.WrapperException> event) {
        if (noBlockEntityCrash.get()) {
            Listener.WrapperException we = event.context();
            TickingBlockEntity entity = event.getArgs(0);
            Level world = event.getArgs(1);
            event.cancel();
            if (!entity.isRemoved()) {
                Throwable exception = we.exception();
                Debug.chat(
                        "Error while ticking blockEntity at world:",
                        ChatUtils.getDisplayedLocation(Vec3.atLowerCornerOf(entity.getPos())),
                        "World:",
                        world.dimension().identifier());
                Debug.chat(
                        exception.getClass().getSimpleName(),
                        ":",
                        Component.literal(exception.getMessage() == null ? "Exception: null" : exception.getMessage()));
                Debug.info("BlockEntity Exception INFO :");
                Debug.info("  World : ", world.dimension().identifier());
                Debug.info("  BlockEntityPos : ", entity);
                try {
                    BlockEntity be = world.getBlockEntity(entity.getPos());
                    Debug.info(" BlockEntity : ", be == null ? null : be.getType());
                    if (be != null) {
                        Debug.info(" BlockEntityNBT : ", be.saveWithoutMetadata(ItemStackUtils.registry()));
                    }
                    BlockState state = world.getBlockState(entity.getPos());
                    Debug.info(" BlockState : ", state);
                } catch (Throwable e) {
                }
                Debug.info("Exception StackTrace:");
                Debug.info(exception);
            }
        }
    }

    public void onDecodeException(Event<Listener.WrapperException> event) {
        if (noDecodeException.get()) {
            Listener.WrapperException we = event.context();
            PacketListener packet = event.getArgs(0);
            Throwable exception = we.exception();
            if (packet instanceof ClientGamePacketListener playListener) {
                if (mc.player != null) {
                    Debug.chat(
                            Component.literal("Error while decoding packet: ").withStyle(ChatFormatting.RED));
                    Debug.chat(
                            exception.getClass().getSimpleName(),
                            ":",
                            Component.literal(
                                    exception.getMessage() == null ? "Exception: null" : exception.getMessage()));
                }
                Debug.info("Exception StackTrace:");
                Debug.info(exception);
                event.cancel();
            }
        }
    }

    public void onUnexpectedException(Event<Listener.WrapperException> event) {
        if (noUnexpected.get()) {
            Listener.WrapperException we = event.context();
            PacketListener packet = event.getArgs(0);
            Throwable exception = we.exception();
            if (packet instanceof ClientGamePacketListener playListener) {
                if (mc.player != null) {
                    Debug.chat(
                            Component.literal("Error while receiving packet: ").withStyle(ChatFormatting.RED));
                    Debug.chat(
                            exception.getClass().getSimpleName(),
                            ":",
                            Component.literal(
                                    exception.getMessage() == null ? "Exception: null" : exception.getMessage()));
                }
                Debug.info("Exception StackTrace:");
                Debug.info(exception);
                event.cancel();
            }
        }
    }

    public boolean validVec3d(Vec3 vec3d) {
        return Double.isFinite(vec3d.x) && Double.isFinite(vec3d.y) && Double.isFinite(vec3d.z);
    }

    int lastCrashTick = 0;

    protected void checkClientData(Screen screen) {
        ScreenAccess currentScreen = ScreenAccess.of(ClientUtils.getScreen(mc));
        Screen parentScreen = (currentScreen instanceof QuestionScreen ? currentScreen.getParent() : ClientUtils.getScreen(mc));
        // continue crash, force exit
        boolean shouldKeep = keepInServer.get() && lastCrashTick < Tasks.getTick() - 10;
        if (shouldKeep
                && mc.player != null
                && mc.level != null
                && mc.gui != null
                && mc.getConnection() != null
                && mc.gameMode != null) {
            ScreenAccess.of(screen).openFrom(parentScreen);
        } else {
            // 严重问题, 直接退出游戏回到主屏幕
            MainTasks.disconnectImmediately();
            ScreenAccess.of(screen).openFrom(null);
        }
        lastCrashTick = Tasks.getTick();
    }

    public void onCursorLockSwitch() {
        if (mc.mouseHandler != null) {
            if (mc.mouseHandler.isMouseGrabbed()) {
                mc.mouseHandler.releaseMouse();
            } else {
                mc.mouseHandler.grabMouse();
            }
        }
    }

    public void onBlankScreenCreate() {
        new GenericScreen(Component.empty(), 0, 0).access().openFromCurrent();
    }

    public void onServerLeave(Event<Void> eventVoid) {
        if (mc.player != null && logServerExiting.get()) {
            Debug.info("Player leaving server log:");
            Debug.info("  - Reconfiguration: ", !eventVoid.<Boolean>getArgs(0));
            Debug.info("  - Name: " + mc.player.getScoreboardName());
            Debug.info("  - Pos: " + mc.player.position());
            if (mc.level != null) {
                Debug.info("  - World: " + mc.level.dimension().identifier());
            }
            Debug.info("  - Health: " + mc.player.getHealth());
            Debug.info("  - Hand item: " + mc.player.getMainHandItem());
            Debug.info("  - Offhand item: " + mc.player.getOffhandItem());
            Debug.info("  - FallFlying: " + mc.player.isFallFlying());
            int count = (int) InventoryUtils.computePlayerInventory(
                    s -> s.is(Items.TOTEM_OF_UNDYING) ? (double) s.getCount() : null, false);
            Debug.info("  - TotemCount: " + count);
            if (mc.level != null) {
                List<AbstractClientPlayer> players = mc.level.players();
                Debug.info("  - Players in visual range: " + players.size());
                List<AbstractClientPlayer> playersSort = players.stream()
                        .sorted(Comparator.comparingDouble(s -> s.position().distanceToSqr(mc.player.position())))
                        .toList();
                for (var re : playersSort) {
                    if (re != mc.player) {
                        Debug.info("    - Name: " + re.getScoreboardName() + ", Pos: " + re.position()
                                + ", dist: %.2f"
                                        .formatted(re.position()
                                                .subtract(mc.player.position())
                                                .length()));
                    }
                }
            }
        }
    }
}
