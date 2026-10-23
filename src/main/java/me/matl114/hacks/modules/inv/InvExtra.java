package me.matl114.hacks.modules.inv;

import me.matl114.utils.ClientUtils;

import com.google.common.util.concurrent.Runnables;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.access.HandledScreenAccess;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.events.impl.SlotClickAction;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class InvExtra extends BaseModule {
    public static InvExtra INSTANCE;
    private final Deque<Runnable> postTickGhostHandCallbacks = new ArrayDeque<>();
    private final Deque<Runnable> nextTickGhostHandCallbacks = new ArrayDeque<>();
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
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundClientTickEndPacket.class),
                this::onClientTickEnd,
                Integer.MAX_VALUE);
        registerListener(Listener.getPreTick(), this::onNewTick);
        registerListener(Listener.getServerLeavePoint(), event -> {
            postTickGhostHandCallbacks.clear();
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

    List<Runnable> inventoryCallbacks = new ArrayList<>();
    List<Integer> hotBarCallbacks = new ArrayList<>();

    private void onClientTickEnd(Event<ServerboundClientTickEndPacket> event) {
        if (event.isCancelled()) return;
        // Movement-post actions may add inventory callbacks during this packet's post-schedule phase.
        duringCallback = true;
        try {
            drainGhostHandCallbacks();
        } finally {
            duringCallback = false;
        }
        if (!inventoryCallbacks.isEmpty()) {
            try {
                MovTasks.getMovExtra().sendSprintPacketsForInventoryAction();
                List<Runnable> inventoryTasks = new ArrayList<>(inventoryCallbacks);
                PacketManager.schedulePostScheduleCallback(event.context, () -> {
                    for (Runnable r : inventoryTasks) {
                        r.run();
                    }
                });

            } finally {
                inventoryCallbacks.clear();
            }
        }
        if (!hotBarCallbacks.isEmpty()) {
            try {
                int lastSelected = hotBarCallbacks.getLast();
                mc.player.getInventory().setSelectedSlot(lastSelected);
                PostManager.INSTANCE.addNextTransactionAction((ch2) -> {
                    PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(lastSelected);
                });
            } finally {
                hotBarCallbacks.clear();
            }
        }
    }

    private void onNewTick(Event<Void> event) {
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

    public boolean pushNextTickGhostHandCallback(Runnable runnable) {
        // must ensure that this tick 's nextTick Task already processed
        tickNextTickCallbacks();
        if (runnable != null && runnable != Runnables.doNothing()) {
            nextTickGhostHandCallbacks.addLast(runnable);
            return true;
        }
        return false;
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
                OptionalInt slotIndex = mc.player.containerMenu.findSlot(mc.player.getInventory(), hand);
                if (slotIndex.isPresent()) {
                    int swapped = slotIndex.getAsInt();
                    if (swapped >= 0) {
                        MovExtra.INSTANCE.sendPacketsForInventoryAction();
                        mc.gameMode.handleContainerInput(
                                mc.player.containerMenu.containerId,
                                swapped,
                                selected,
                                ContainerInput.SWAP,
                                mc.player);
                        return wrapInvTask(() -> {
                            mc.gameMode.handleContainerInput(
                                    mc.player.containerMenu.containerId,
                                    swapped,
                                    selected,
                                    ContainerInput.SWAP,
                                    mc.player);
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

    private Runnable swapInventoryIndexToHand(int hand) {
        int selected = InventoryUtils.getSelectedSlot();
        if (selected != hand) {
            //            if (hand < 9) {
            //                PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(hand);
            //                return () -> {
            //                    PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(selected);
            //                };
            //            } else {
            OptionalInt slotIndex = mc.player.containerMenu.findSlot(mc.player.getInventory(), hand);
            if (slotIndex.isPresent()) {
                int swapped = slotIndex.getAsInt();
                if (swapped >= 0) {
                    MovTasks.getMovExtra().sendPacketsForInventoryAction();
                    mc.gameMode.handleContainerInput(
                            mc.player.containerMenu.containerId, swapped, selected, ContainerInput.SWAP, mc.player);
                    return wrapInvTask(() -> {
                        mc.gameMode.handleContainerInput(
                                mc.player.containerMenu.containerId,
                                swapped,
                                selected,
                                ContainerInput.SWAP,
                                mc.player);
                    });
                } else {
                    return null;
                }
            } else {
                return null;
            }
            // }
        }
        return Runnables.doNothing();
    }

    private Runnable swapInventoryIndexToOffhand(int hand) {
        if (hand == 40) return Runnables.doNothing();
        OptionalInt slotIndex = mc.player.containerMenu.findSlot(mc.player.getInventory(), hand);
        if (slotIndex.isPresent()) {
            int swapped = slotIndex.getAsInt();
            if (swapped >= 0) {
                MovTasks.getMovExtra().sendPacketsForInventoryAction();

                mc.gameMode.handleContainerInput(
                        mc.player.containerMenu.containerId, swapped, 40, ContainerInput.SWAP, mc.player);
                return wrapInvTask(() -> {
                    mc.gameMode.handleContainerInput(
                            mc.player.containerMenu.containerId, swapped, 40, ContainerInput.SWAP, mc.player);
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
        OptionalInt slotIndex = mc.player.containerMenu.findSlot(mc.player.getInventory(), selected);
        if (slotIndex.isPresent()) {
            return swapScreenSlots(slot, slotIndex.getAsInt());
        } else {
            return null;
        }
    }

    public Runnable swapInventorySlotToOffhand(int slot) {
        int selected = 40;
        OptionalInt slotIndex = mc.player.containerMenu.findSlot(mc.player.getInventory(), selected);
        if (slotIndex.isPresent()) {
            return swapScreenSlots(slot, slotIndex.getAsInt());
        } else {
            return null;
        }
    }

    public Runnable swapInventoryIndexes(int slot1, int slot2) {
        ;
        OptionalInt slotIndex = mc.player.containerMenu.findSlot(mc.player.getInventory(), slot1);
        OptionalInt slotIndex2 = mc.player.containerMenu.findSlot(mc.player.getInventory(), slot2);
        if (slotIndex.isPresent() && slotIndex2.isPresent()) {
            return swapScreenSlots(slotIndex.getAsInt(), slotIndex2.getAsInt());
        } else {
            return null;
        }
    }

    public Runnable swapScreenSlots(int armorSlot, int targetSlot) {
        if (armorSlot == targetSlot) return Runnables.doNothing();
        var handler = ClientPlayerAccess.of(mc.player).getServerScreenHandler();
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
        var handler = ClientPlayerAccess.of(mc.player).getServerScreenHandler();
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
            ClientPlayerAccess access = ClientPlayerAccess.of(player);
            access.clearKeepedInventory(true);
            Debug.chat(Component.literal("已清除界面历史记录"));
        }
    }

    public enum ActionType {
        INV,
        HOT_BAR;
    }
}
