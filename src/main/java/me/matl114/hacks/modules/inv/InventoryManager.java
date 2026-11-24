package me.matl114.hacks.modules.inv;

import java.util.function.Consumer;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.utils.ClientUtils;
import me.matl114.utils.ScreenUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;

/**
 * 记录服务端当前真正打开的那个容器界面。
 *
 * <p>客户端在收到打开界面的包之后、界面真正建好之前会有一段时间 {@code player.containerMenu} 仍是旧值，
 * 而预测性物品栏操作（{@link #executePredictInventoryAction}）必须拿到「服务端认为现在开着哪个容器」，
 * 因此这里按包与界面事件自行维护一份，并在离开服务器 / 切世界时清空。
 */
public class InventoryManager extends BaseModule {
    public int lastSyncId = 0;

    public AbstractContainerScreen<?> currentOpenScreen;
    public AbstractContainerMenu currentOpenScreenHandler;

    public static InventoryManager INSTANCE;

    public InventoryManager() {
        super("InventoryManager");
        INSTANCE = this;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPacketPoint().getChannel(ClientboundOpenScreenPacket.class), this::onOpenScreen);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundMountScreenOpenPacket.class), this::onOpenMountScreen);
        registerListener(Listener.getPostOpenHandledScreen(), this::onOpenHandledScreen, Integer.MIN_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundContainerClosePacket.class),
                this::onCloseScreen,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundContainerClosePacket.class),
                this::onCloseHandledScreen,
                Integer.MAX_VALUE);

        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundContainerSetSlotPacket.class),
                this::onScreenHandlerSlotUpdate);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundContainerSetContentPacket.class),
                this::onInventoryUpdate);

        registerListener(Listener.getGameJoinPoint(), this::onGameJoin);
        registerListener(Listener.getServerLeavePoint(), this::resetTrackedScreen);
        registerListener(Listener.getWorldSwitchPoint(), this::resetTrackedScreen);
    }

    public AbstractContainerMenu getCurrentServerScreenHandler(LocalPlayer player) {
        if (player == null) {
            return null;
        }
        return currentOpenScreenHandler != null ? currentOpenScreenHandler : player.containerMenu;
    }

    public Screen getCurrentServerScreen(LocalPlayer player) {
        return getCurrentServerOpeningScreen(player);
    }

    public AbstractContainerScreen<?> getCurrentServerOpeningScreen(LocalPlayer player) {
        if (player == null) {
            return null;
        }
        AbstractContainerScreen<?> nowScreen = currentOpenScreen;
        if (nowScreen == null && ClientUtils.getScreen() instanceof AbstractContainerScreen<?> handled) {
            nowScreen = handled;
        }
        return nowScreen instanceof CreativeModeInventoryScreen ? null : nowScreen;
    }

    private void resetTrackedScreen() {
        currentOpenScreen = null;
        currentOpenScreenHandler = null;
    }

    private void onOpenScreen(Event<ClientboundOpenScreenPacket> packet) {
        lastSyncId = packet.context.getContainerId();
    }

    private void onOpenMountScreen(Event<ClientboundMountScreenOpenPacket> packet) {
        lastSyncId = packet.context.getContainerId();
    }

    private void onOpenHandledScreen(Event<AbstractContainerScreen<?>> event) {
        resetTrackedScreen();
        AbstractContainerScreen<?> screen = event.context;
        if (screen instanceof CreativeModeInventoryScreen) {
            return;
        }
        currentOpenScreenHandler = screen.getMenu();
        if (screen.getMenu() == mc.player.containerMenu) {
            currentOpenScreen = screen;
        } else {
            currentOpenScreen = null;
        }
    }

    private void onCloseScreen(Event<ClientboundContainerClosePacket> event) {
        if (event.isCancelled()) {
            return;
        }
        int containerId = event.context.getContainerId();
        if (currentOpenScreenHandler != null && currentOpenScreenHandler.containerId == containerId) {
            resetTrackedScreen();
        }
    }

    private void onCloseHandledScreen(Event<ServerboundContainerClosePacket> event) {
        if (event.isCancelled()) {
            return;
        }
        if (currentOpenScreenHandler != null && currentOpenScreenHandler.containerId == event.context.getContainerId()) {
            resetTrackedScreen();
        }
    }

    private void onScreenHandlerSlotUpdate(Event<ClientboundContainerSetSlotPacket> event) {
        LocalPlayer player = mc.player;
        AbstractContainerMenu handler = currentOpenScreenHandler;
        if (player != null
                && handler != null
                && handler != player.containerMenu
                && event.context.getContainerId() == handler.containerId) {
            handler.setItem(event.context.getSlot(), event.context.getStateId(), event.context.getItem());
        }
    }

    private void onInventoryUpdate(Event<ClientboundContainerSetContentPacket> event) {
        LocalPlayer player = mc.player;
        AbstractContainerMenu handler = currentOpenScreenHandler;
        if (player != null
                && handler != null
                && handler != player.containerMenu
                && event.context.containerId() == handler.containerId) {
            handler.initializeContents(event.context.stateId(), event.context.items(), event.context.carriedItem());
        }
    }

    private void onGameJoin() {
        lastSyncId = 0;
        resetTrackedScreen();
    }

    /**
     * 用「预测的容器 id」临时替换 {@code player.containerMenu} 执行一次物品栏操作，
     * 让服务端在真正打开界面之前就接受这次点击。
     */
    public void executePredictInventoryAction(Container topInventory, Consumer<AbstractContainerMenu> callback) {
        int nextPredictedIndex = (lastSyncId % 100) + 1;
        LocalPlayer player = Minecraft.getInstance().player;
        AbstractContainerMenu fakeScreenHandler = new ChestMenu(
                ScreenUtils.getGenericScreenType(topInventory.getContainerSize()),
                nextPredictedIndex,
                player.getInventory(),
                topInventory,
                ((topInventory.getContainerSize() - 1) / 9) + 1);
        AbstractContainerMenu handler = player.containerMenu;
        try {
            player.containerMenu = fakeScreenHandler;
            callback.accept(fakeScreenHandler);
        } finally {
            player.containerMenu = handler;
        }
    }
}
