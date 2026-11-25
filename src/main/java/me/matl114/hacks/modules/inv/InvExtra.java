package me.matl114.hacks.modules.inv;

import me.matl114.hacks.utils.ClientUtils;

import com.google.common.util.concurrent.Runnables;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import me.matl114.accessors.access.HandledScreenAccess;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.SlotClickAction;
import me.matl114.events.impl.UseItem;
import me.matl114.events.impl.UseItemOnBlock;
import me.matl114.hacks.InvTasks;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.move.MovExtra;
import me.matl114.hacks.modules.task.PostManager;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.hooks.ViaProtocols;
import me.matl114.managers.Configs;
import me.matl114.managers.TaskManagers;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.KeyCode;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.Debug;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.ScreenUtils;
import me.matl114.utils.collections.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class InvExtra extends BaseModule {
    private static final int HOTBAR_ITEM_USAGE_WINDOW_SIZE = 27;
    public static InvExtra INSTANCE;
    private final Deque<Runnable> postTickGhostHandCallbacks = new ArrayDeque<>();
    private final Deque<Runnable> nextTickGhostHandCallbacks = new ArrayDeque<>();
    private final Deque<Item> hotbarItemUsageWindow = new ArrayDeque<>(HOTBAR_ITEM_USAGE_WINDOW_SIZE);
    private final Map<Item, Integer> hotbarItemUsageCounts = new HashMap<>();
    public final ModulePath inventory = makePath(Configs.INV_CONFIG, "inventory");

    public InvExtra() {
        super("InvExtra");
        INSTANCE = this;
    }

    public final DoubleRef inventoryClickLimit = doubleBuilder(inventory.add("packet-limit"))
            .defaultValue(40.0D)
            .validator(Configs.doubleRange(0.0, 1000.0))
            .build();

    public final FlagRef invGrimFix =
            flagBuilder(inventory.add("move-click-grim-fix")).build();

    public final FlagRef invSprintGrimFix =
            flagBuilder(inventory.add("sprint-click-grim-fix")).build();

    public final FlagRef expandInventory =
            flagBuilder(inventory.add("expand-backpack-inventory")).build();

    public final KeyBindRef pickItemHotkey = hotkey(inventory.add("pick-item"))
            .defaultValue(new MultiKeyBind(KeyCode.KEY_LEFT_CONTROL, KeyCode.MOUSE_BUTTON_3))
            .registerHotkey(HotKeyUtils.asHandler(this::onPickItem))
            .build();

    public final ModulePath keepInv = inventory.add("keep-inv");
    public final FlagRef enableKeepInv = flagBuilder(keepInv).build();

    public static final String CLEAR_KEEP = "clear-keep";

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreClickSlot(), this::onClickSlot);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundContainerClosePacket.class), this::onCloseScreen);
        registerListener(Listener.getPostGameTick(), this::onClientTickEnd, Integer.MAX_VALUE);
        registerListener(Listener.getPostTick(), this::onClientPostTickEnd, Integer.MIN_VALUE);
        registerListener(Listener.getPreTick(), this::onNewTick, Integer.MIN_VALUE + 11);
        registerListener(Listener.getPostPlayerUseItem(), this::onPostUseItem);
        registerListener(Listener.getPostPlayerUseItemOnBlock(), this::onPostUseItemOnBlock);
        registerListener(Listener.getServerLeavePoint(), event -> {
            postTickGhostHandCallbacks.clear();
            nextTickGhostHandCallbacks.clear();
            resetHotbarItemUsageWindow();
        });
        TaskManagers.getToggleManager().register(TaskManagers.PREFIX_BUTTON_TOGGLE + "." + "keep-inv", enableKeepInv);
        TaskManagers.getTaskManager().register(TaskManagers.PREFIX_BUTTON_TASKS + "." + CLEAR_KEEP, this::clearKeep);
    }

    public void onClickSlot(Event<SlotClickAction> event) {
        onInvClick(event.context.syncId());
    }

    public void onInvClick(int syncId) {
        // check if it is manually clicked
        if (ClientUtils.getScreen(mc) instanceof AbstractContainerScreen<?> handled && handled.getMenu().containerId == syncId) {
            // do not fix all of them
            // some module may use MultiAction to gain advantage
            if (invSprintGrimFix.get()) {
                // fix GuiMove situation
                MovTasks.getMovExtra().sendSprintPacketsForInventoryAction();
            }
        }
        if (invGrimFix.get()) {
            // do not support viafabric, I guess
            // just send input packets, do not change sprint status
            // do not send the fucking sprint packets, shit
            MovTasks.getMovExtra().sendInputPacketsForInventoryAction();
        }
    }

    public void onCloseScreen(Event<ServerboundContainerClosePacket> closeS2C) {
        if (expandInventory.get() && closeS2C.context.getContainerId() == mc.player.inventoryMenu.containerId) {
            closeS2C.cancel();
        }
    }

    public void clearKeepedInventory() {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        // the retained server handler is the one the server still has open, so close that id first
        AbstractContainerMenu handler = InventoryManager.INSTANCE.getCurrentServerScreenHandler(player);
        if (handler != null && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundContainerClosePacket(handler.containerId));
        }
        player.closeContainer();
    }

    List<Runnable> inventoryCallbacks = new ArrayList<>();
    List<Integer> hotBarCallbacks = new ArrayList<>();
    List<Runnable> invPostTickCallbacks = List.of();

    private void onClientTickEnd() {
        duringCallback = true;
        try {
            drainGhostHandCallbacks();
        } finally {
            duringCallback = false;
        }
        if (!inventoryCallbacks.isEmpty()) {
            try {
                MovTasks.getMovExtra().sendSprintPacketsForInventoryAction();
                invPostTickCallbacks = new ArrayList<>(inventoryCallbacks);
            } finally {
                inventoryCallbacks.clear();
            }
        }
        if (!hotBarCallbacks.isEmpty()) {
            try {
                int lastSelected = hotBarCallbacks.getLast();
                InventoryUtils.setSelectedSlot(lastSelected);
                PostManager.INSTANCE.addNextTransactionAction((ch2) -> {
                    PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(lastSelected);
                });
            } finally {
                hotBarCallbacks.clear();
            }
        }
    }

    private void onClientPostTickEnd() {
        try {
            for (var runnable : invPostTickCallbacks) {
                runnable.run();
            }
        } finally {
            invPostTickCallbacks = List.of();
        }
    }

    private void onNewTick() {
        if (checkNull()) return;
        tickNextTickCallbacks();
    }

    int lastRunningTicks;

    public void tickNextTickCallbacks() {
        if (lastRunningTicks != Tasks.getTick()) {
            lastRunningTicks = Tasks.getTick();
            while (!nextTickGhostHandCallbacks.isEmpty()) {
                Runnable r = nextTickGhostHandCallbacks.removeLast();
                r.run();
            }
        }
    }

    public boolean pushPostTickGhostHandCallback(Runnable callback) {
        if (callback != null && callback != Runnables.doNothing()) {
            postTickGhostHandCallbacks.push(callback);
            return true;
        }
        return false;
    }

    public void pushHotbarRestoreAfterTick(int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot >= 9) return;
        pushPostTickGhostHandCallback(() -> {
            if (mc.player != null) {
                PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(hotbarSlot);
            }
        });
    }

    public boolean pushNextTickGhostHandCallback(Runnable runnable) {
        // must ensure that this tick 's nextTick Task already processed
        tickNextTickCallbacks();
        if (runnable != null && runnable != Runnables.doNothing()) {
            nextTickGhostHandCallbacks.addLast(runnable);
            return true;
        }
        return false;
    }

    public boolean pushNextTickPostTickGhostHandCallback(Runnable callback) {
        if (callback == null || callback == Runnables.doNothing()) {
            return false;
        }
        return pushNextTickGhostHandCallback(() -> postTickGhostHandCallbacks.addLast(callback));
    }

    public boolean hasPostTickGhostHandCallback() {
        return !postTickGhostHandCallbacks.isEmpty();
    }

    public boolean hasNextTickGhostHandCallback() {
        return !nextTickGhostHandCallbacks.isEmpty();
    }

    public void drainGhostHandCallbacks() {
        while (!postTickGhostHandCallbacks.isEmpty()) {
            postTickGhostHandCallbacks.pop().run();
        }
    }

    public Runnable switchOrSwapInventoryIndexToHand(int hand) {
        int selected = InventoryUtils.getSelectedSlot();
        if (selected != hand) {
            if (hand < 9) {
                PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(hand);
                return wrapHotbarTask(selected);
            } else {
                AbstractContainerMenu handler = InventoryManager.INSTANCE.getCurrentServerScreenHandler(mc.player);
                OptionalInt slotIndex = handler.findSlot(mc.player.getInventory(), hand);
                if (slotIndex.isPresent()) {
                    int swapped = slotIndex.getAsInt();
                    if (swapped >= 0) {
                        MovExtra.INSTANCE.sendPacketsForInventoryAction();
                        mc.gameMode.handleContainerInput(
                                handler.containerId, swapped, selected, ContainerInput.SWAP, mc.player);
                        return wrapInvTask(() -> {
                            AbstractContainerMenu handler2 = InventoryManager.INSTANCE.getCurrentServerScreenHandler(mc.player);
                            OptionalInt slotIndex2 = handler2.findSlot(mc.player.getInventory(), hand);
                            if (slotIndex2.isPresent()) {
                                int swapped2 = slotIndex2.getAsInt();
                                mc.gameMode.handleContainerInput(
                                        handler2.containerId, swapped2, selected, ContainerInput.SWAP, mc.player);
                            }
                        });
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            }
        }
        return Runnables.doNothing();
    }

    public boolean canSwapItemToHand(int hand, boolean offhand, GhostHandMode mode) {
        if (mc.player == null || hand < 0 || hand >= InventoryUtils.getPlayerInvSize()) return false;
        if (mode == GhostHandMode.HOT_BAR_ONLY && !offhand && hand != 40) return hand < 9;
        AbstractContainerMenu handler = InventoryManager.INSTANCE.getCurrentServerScreenHandler(mc.player);
        return handler != null && handler.findSlot(mc.player.getInventory(), hand).isPresent();
    }

    public Runnable swapItemToHand(int hand, boolean offhand) {
        if (offhand) {
            return swapInventoryIndexToOffhand(hand);
        }
        if (hand == 40) {
            return swapInventoryIndexToHand(40);
        }
        if (hand >= 0 && hand < 9) {
            return swapInventoryHotBar(hand);
        }
        return swapInventoryIndexToHand(hand);
    }

    public Runnable swapItemToHand(int hand, boolean offhand, GhostHandMode mode) {
        if (offhand) {
            return swapInventoryIndexToOffhand(hand);
        }
        if (hand == 40) {
            return swapInventoryIndexToHand(40);
        }
        return switch (mode) {
            case INV_SWAP, INV_CLICK -> swapInventoryIndexToHand(hand);
            case HOT_BAR_ONLY -> swapInventoryHotBar(hand);
        };
    }

    /**
     * 把背包里的物品借道空/少用的快捷栏槽位换到手上，返回调用后要恢复的手上槽位。
     *
     * <p>比起直接 {@link #swapItemToHand(int, boolean, GhostHandMode)}，这样不会把玩家当前手上的东西
     * 挤到背包深处，只会在快捷栏内部换来换去，事后由调用方用 {@link #pushHotbarRestoreAfterTick(int)} 复原。
     */
    public Runnable swapItemToHotbarCache(int inventoryIndex) {
        if (mc.player == null || inventoryIndex < 0 || inventoryIndex >= 36) return null;

        if (inventoryIndex < 9) {
            return swapInventoryHotBar(inventoryIndex);
        }

        int hotbarSlot = findHotbarCacheSlot();
        if (hotbarSlot < 0 || hotbarSlot >= 9) {
            return null;
        }
        int current = InventoryUtils.getSelectedSlot();
        try {
            InventoryUtils.setSelectedSlot(hotbarSlot);
            if (swapItemToHand(inventoryIndex, false) == null) {
                return null;
            }
        } finally {
            InventoryUtils.setSelectedSlot(current);
        }

        return swapInventoryHotBar(hotbarSlot);
    }

    private int findHotbarCacheSlot() {
        Inventory playerInventory = mc.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (playerInventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        int leastUsedSlot = -1;
        int leastUsage = Integer.MAX_VALUE;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = playerInventory.getItem(slot);
            // 有耐久或不死图腾的槽位被换走代价太大，不参与挑选
            if (stack.get(DataComponents.MAX_DAMAGE) != null || stack.is(Items.TOTEM_OF_UNDYING)) {
                continue;
            }
            int usage = hotbarItemUsageCounts.getOrDefault(stack.getItem(), 0);
            if (usage < leastUsage) {
                leastUsedSlot = slot;
                leastUsage = usage;
            }
        }
        return leastUsedSlot >= 0 ? leastUsedSlot : InventoryUtils.getSelectedSlot();
    }

    private void onPostUseItemOnBlock(Event<UseItemOnBlock> event) {
        recordMainHandUse(event.context.hand(), event.context.handItem());
    }

    private void onPostUseItem(Event<UseItem> event) {
        recordMainHandUse(event.context.hand(), event.context.handItem());
    }

    private void recordMainHandUse(InteractionHand hand, ItemStack stack) {
        if (mc.player == null || hand != InteractionHand.MAIN_HAND || stack.isEmpty()) return;
        Item item = stack.getItem();
        if (hotbarItemUsageWindow.size() == HOTBAR_ITEM_USAGE_WINDOW_SIZE) {
            Item evicted = hotbarItemUsageWindow.removeFirst();
            hotbarItemUsageCounts.computeIfPresent(evicted, (ignored, count) -> count == 1 ? null : count - 1);
        }
        hotbarItemUsageWindow.addLast(item);
        hotbarItemUsageCounts.merge(item, 1, Integer::sum);
    }

    private void resetHotbarItemUsageWindow() {
        hotbarItemUsageWindow.clear();
        hotbarItemUsageCounts.clear();
    }

    private Runnable swapInventoryIndexToHand(int hand) {
        int selected = InventoryUtils.getSelectedSlot();
        if (selected != hand) {
            //            if (hand < 9) {
            //                PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(hand);
            //                return () -> {
            //                    PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(selected);
            //                };
            //            } else {
            AbstractContainerMenu handler = InventoryManager.INSTANCE.getCurrentServerScreenHandler(mc.player);
            // 界面上没有副手/背包槽时也能借 40 号交换：用当前选中槽位替它做占位
            if (hand == 40) {
                OptionalInt slotIndex = handler.findSlot(mc.player.getInventory(), selected);
                if (slotIndex.isPresent()) {
                    int swapped = slotIndex.getAsInt();
                    if (swapped >= 0) {
                        MovTasks.getMovExtra().sendPacketsForInventoryAction();
                        mc.gameMode.handleContainerInput(handler.containerId, swapped, hand, ContainerInput.SWAP, mc.player);
                        return wrapInvTask(() -> {
                            AbstractContainerMenu handler2 = InventoryManager.INSTANCE.getCurrentServerScreenHandler(mc.player);
                            OptionalInt slotIndex2 = handler2.findSlot(mc.player.getInventory(), selected);
                            if (slotIndex2.isPresent()) {
                                int swapped2 = slotIndex2.getAsInt();
                                mc.gameMode.handleContainerInput(
                                        handler2.containerId, swapped2, hand, ContainerInput.SWAP, mc.player);
                            }
                        });
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            } else {
                OptionalInt slotIndex = handler.findSlot(mc.player.getInventory(), hand);
                if (slotIndex.isPresent()) {
                    int swapped = slotIndex.getAsInt();
                    if (swapped >= 0) {
                        MovTasks.getMovExtra().sendPacketsForInventoryAction();
                        mc.gameMode.handleContainerInput(
                                handler.containerId, swapped, selected, ContainerInput.SWAP, mc.player);
                        return wrapInvTask(() -> {
                            AbstractContainerMenu handler2 = InventoryManager.INSTANCE.getCurrentServerScreenHandler(mc.player);
                            OptionalInt slotIndex2 = handler2.findSlot(mc.player.getInventory(), hand);
                            if (slotIndex2.isPresent()) {
                                int swapped2 = slotIndex2.getAsInt();
                                mc.gameMode.handleContainerInput(
                                        handler2.containerId, swapped2, selected, ContainerInput.SWAP, mc.player);
                            }
                        });
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            }
            // }
        }
        return Runnables.doNothing();
    }

    private Runnable swapInventoryIndexToOffhand(int hand) {
        if (hand == 40) return Runnables.doNothing();
        AbstractContainerMenu handler = InventoryManager.INSTANCE.getCurrentServerScreenHandler(mc.player);
        OptionalInt slotIndex = handler.findSlot(mc.player.getInventory(), hand);
        if (slotIndex.isPresent()) {
            int swapped = slotIndex.getAsInt();
            if (swapped >= 0) {
                MovTasks.getMovExtra().sendPacketsForInventoryAction();

                mc.gameMode.handleContainerInput(handler.containerId, swapped, 40, ContainerInput.SWAP, mc.player);
                return wrapInvTask(() -> {
                    AbstractContainerMenu handler2 = InventoryManager.INSTANCE.getCurrentServerScreenHandler(mc.player);
                    OptionalInt slotIndex2 = handler2.findSlot(mc.player.getInventory(), hand);
                    if (slotIndex2.isPresent()) {
                        int swapped2 = slotIndex2.getAsInt();
                        mc.gameMode.handleContainerInput(handler2.containerId, swapped2, 40, ContainerInput.SWAP, mc.player);
                    }
                });
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    private Runnable swapInventoryHotBar(int hand) {
        if (hand < 0 || hand >= 9) return null;
        int selected = InventoryUtils.getSelectedSlot();
        if (selected == hand) {
            return Runnables.doNothing();
        }
        PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(hand);
        return wrapHotbarTask(selected);
    }

    //    public Runnable swapInventoryIndex(int a, int b){
    //
    //    }

    public Runnable swapInventorySlotToHand(int slot) {
        int selected = InventoryUtils.getSelectedSlot();
        AbstractContainerMenu handler = InventoryManager.INSTANCE.getCurrentServerScreenHandler(mc.player);
        OptionalInt slotIndex = handler.findSlot(mc.player.getInventory(), selected);
        if (slotIndex.isPresent()) {
            return swapScreenSlots(slot, slotIndex.getAsInt());
        } else {
            return null;
        }
    }

    public Runnable swapInventorySlotToOffhand(int slot) {
        int selected = 40;
        AbstractContainerMenu handler = InventoryManager.INSTANCE.getCurrentServerScreenHandler(mc.player);
        OptionalInt slotIndex = handler.findSlot(mc.player.getInventory(), selected);
        if (slotIndex.isPresent()) {
            return swapScreenSlots(slot, slotIndex.getAsInt());
        } else {
            return null;
        }
    }

    public Runnable swapInventoryIndexes(int slot1, int slot2) {
        ;
        AbstractContainerMenu handler = InventoryManager.INSTANCE.getCurrentServerScreenHandler(mc.player);
        OptionalInt slotIndex = handler.findSlot(mc.player.getInventory(), slot1);
        OptionalInt slotIndex2 = handler.findSlot(mc.player.getInventory(), slot2);
        if (slotIndex.isPresent() && slotIndex2.isPresent()) {
            return swapScreenSlots(slotIndex.getAsInt(), slotIndex2.getAsInt());
        } else {
            return null;
        }
    }

    public Runnable swapScreenSlots(int armorSlot, int targetSlot) {
        if (armorSlot == targetSlot) return Runnables.doNothing();
        var handler = InvTasks.getCurrentServerScreenHandler(mc.player);
        var slots = handler.slots;
        if (slots.size() <= armorSlot || slots.size() <= targetSlot) {
            return null;
        }
        MovTasks.getMovExtra().sendPacketsForInventoryAction();
        var targetSlotInstance = handler.slots.get(targetSlot);
        if (targetSlotInstance.container instanceof Inventory
                && (targetSlotInstance.getContainerSlot() < 9 || targetSlotInstance.getContainerSlot() == 40)) {
            // use number operation
            int target = targetSlotInstance.getContainerSlot();
            mc.gameMode.handleContainerInput(handler.containerId, armorSlot, target, ContainerInput.SWAP, mc.player);
            return wrapInvTask(() -> {
                mc.gameMode.handleContainerInput(handler.containerId, armorSlot, target, ContainerInput.SWAP, mc.player);
            });
        } else {
            var armorSlotInstance = handler.slots.get(armorSlot);
            if (armorSlotInstance.container instanceof Inventory
                    && (armorSlotInstance.getContainerSlot() < 9 || armorSlotInstance.getContainerSlot() == 40)) {
                int target = armorSlotInstance.getContainerSlot();
                mc.gameMode.handleContainerInput(handler.containerId, targetSlot, target, ContainerInput.SWAP, mc.player);
                return wrapInvTask(() -> {
                    mc.gameMode.handleContainerInput(handler.containerId, targetSlot, target, ContainerInput.SWAP, mc.player);
                });
            } else {
                // fuck, do not kick me.
                swapTwoIdiotSlot(handler, targetSlot, armorSlot);
                return wrapInvTask(() -> {
                    swapTwoIdiotSlot(handler, targetSlot, armorSlot);
                });
            }
        }
    }

    public void mergeScreenSlotTo(int from, int to) {
        if (from == to) return;
        var handler = InvTasks.getCurrentServerScreenHandler(mc.player);
        var slots = handler.slots;
        if (slots.size() <= from || slots.size() <= to) {
            return;
        }
        MovTasks.getMovExtra().sendPacketsForInventoryAction();
        var fromSlotInstance = handler.slots.get(from);
        var toSlotInstance = handler.slots.get(to);
        ItemStack fromStack = fromSlotInstance.getItem();
        if (fromStack.isEmpty()) {
            return;
        }
        if (!toSlotInstance.mayPlace(fromStack)) {
            return;
        }

        if (toSlotInstance.getItem().isEmpty()
                || !ItemStack.isSameItemSameComponents(fromStack, toSlotInstance.getItem())) {
            swapScreenSlots(from, to);
        } else {
            ItemStack toStack = toSlotInstance.getItem();
            int maxSize = toStack.getMaxStackSize();
            boolean overStack = fromStack.getCount() + toStack.getCount() > maxSize;
            ItemStack stackInCursor = handler.getCarried();
            mc.gameMode.handleContainerInput(handler.containerId, from, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(handler.containerId, to, 0, ContainerInput.PICKUP, mc.player);
            if (overStack) {
                mc.gameMode.handleContainerInput(handler.containerId, from, 0, ContainerInput.PICKUP, mc.player);
            }
        }
    }

    private void swapTwoIdiotSlot(AbstractContainerMenu handler, int targetSlot, int armorSlot) {
        int fuckingHotbar114514 = InventoryUtils.getSelectedSlot() == 8 ? 7 : 8;
        // swap target to hotbar, hotbar to target
        mc.gameMode.handleContainerInput(
                handler.containerId, targetSlot, fuckingHotbar114514, ContainerInput.SWAP, mc.player);
        // swap hotbar to armor, armor to hotbar
        mc.gameMode.handleContainerInput(
                handler.containerId, armorSlot, fuckingHotbar114514, ContainerInput.SWAP, mc.player);
        // swap the rest
        mc.gameMode.handleContainerInput(
                handler.containerId, targetSlot, fuckingHotbar114514, ContainerInput.SWAP, mc.player);
    }

    boolean duringCallback = false;

    private Runnable wrapInvTask(Runnable runnable) {
        return () -> {
            if (duringCallback) {
                inventoryCallbacks.add(runnable);
            } else {
                MovTasks.getMovExtra().sendPacketsForInventoryAction();
                runnable.run();
            }
        };
    }

    private Runnable wrapHotbarTask(int swap) {
        return () -> {
            if (duringCallback) {
                hotBarCallbacks.add(swap);
            } else {
                PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(swap);
            }
        };
    }

    public boolean onPickItem() {

        if (mc.player == null) return false;
        Screen nowScreen = InvTasks.getCurrentServerScreen(mc.player);
        if (!mc.player.isCreative() && nowScreen instanceof AbstractContainerScreen<?> handled) {
            Point mouseCoord = ScreenUtils.getMouseCoord(mc);
            Slot slot = HandledScreenAccess.of(handled).reallyGetSlotAt(mouseCoord.x, mouseCoord.y);
            if (slot != null) {
                if (slot.container instanceof Inventory) {
                    if (slot.getContainerSlot() >= 36) {
                        Debug.chat("Invalid slot for player Inventory", slot.getContainerSlot());
                    } else {
                        if (ViaFabricPlusHooks.getInstance().isViaEnabled()
                                && ViaFabricPlusHooks.getInstance()
                                        .getCurrentVersion()
                                        .isLowerOrEqualTo(21, 3)) {
                            // use via shit to send pickup packet
                            var wrapper = ViaFabricPlusHooks.getInstance().createViaPacket();
                            wrapper.writePacketType(
                                    ViaProtocols.V1_21_2_TO_1_21_4, "pick_item".toUpperCase(Locale.ROOT));
                            wrapper.write("VAR_INT", slot.getContainerSlot());
                            wrapper.scheduleSendToServer(ViaProtocols.V1_21_2_TO_1_21_4, true);
                            Debug.chat("run pickup");
                        } else {
                            Debug.chat("No Longer support this feat in version "
                                    + ViaFabricPlusHooks.getInstance().getCurrentVersion());
                        }
                        // mc.gameMode.pickFromInventory(slot.getIndex());
                    }
                    return true;
                } else {
                    Debug.chat("Invalid slot outside player Inventory");
                }
            }
        }
        return false;
    }

    public void clearKeep() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            clearKeepedInventory();
            Debug.chat(Component.literal("已清除界面历史记录"));
        }
    }

    public enum ActionType {
        INV,
        HOT_BAR;
    }
}
