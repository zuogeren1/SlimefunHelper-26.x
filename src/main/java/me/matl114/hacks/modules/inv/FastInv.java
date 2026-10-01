package me.matl114.hacks.modules.inv;

import me.matl114.accessors.access.HandledScreenAccess;
import me.matl114.hacks.InvTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.managers.Configs;
import me.matl114.managers.TaskManagers;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.KeyCode;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.ItemStackUtils;
import me.matl114.utils.ScreenUtils;
import me.matl114.utils.collections.Point;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;

public class FastInv extends BaseModule {
    public final ModulePath fastInv = makePath(Configs.INV_CONFIG, "fastinv");

    public FastInv() {
        super("FastInv");
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(fastInv.add("fast-inv")).build();

    public final FlagRef enableLeftOne = flagBuilder(fastInv.add("left-one")).build();

    public final FlagRef enableDrop = flagBuilder(fastInv.add("apply-drop")).build();

    public final FlagRef enableShift = flagBuilder(fastInv.add("apply-shift")).build();

    public final FlagRef enableEmptyClick =
            flagBuilder(fastInv.add("empty-click")).build();

    public final KeyBindRef shiftAction = hotkey(
                    Configs.INV_CONFIG,
                    fastInv.add("fast-mov").toPath(),
                    new MultiKeyBind(KeyCode.KEY_LEFT_SHIFT, KeyCode.MOUSE_BUTTON_1))
            .registerHotkey(HotKeyUtils.asNonInputHandler(this::onShiftAction))
            .build();

    public final KeyBindRef dropAction = hotkey(
                    Configs.INV_CONFIG,
                    fastInv.add("fast-drop").toPath(),
                    new MultiKeyBind(KeyCode.KEY_LEFT_SHIFT, KeyCode.KEY_Q))
            .registerHotkey(HotKeyUtils.asNonInputHandler(this::onDropAction))
            .build();

    public static final String TAKE_ALL = "take-all";
    public static final String SAVE_ALL = "save-all";

    @Override
    public void registerAll() {
        super.registerAll();
        TaskManagers.getToggleManager().register(TaskManagers.PREFIX_BUTTON_TOGGLE + "." + "fast-inv", enable);
        TaskManagers.getToggleManager().register(TaskManagers.PREFIX_BUTTON_TOGGLE + "." + "left-one", enableLeftOne);
        TaskManagers.getTaskManager().register(TaskManagers.PREFIX_BUTTON_TASKS + "." + TAKE_ALL, this::takeAll);
        TaskManagers.getTaskManager().register(TaskManagers.PREFIX_BUTTON_TASKS + "." + SAVE_ALL, this::saveAll);
    }

    public boolean onShiftAction() {
        if (mc.player == null) return false;
        Screen nowScreen = InvTasks.getCurrentServerScreen(mc.player);
        // filter inventory screen
        if (nowScreen instanceof AbstractContainerScreen<?> handled && !(nowScreen instanceof InventoryScreen)) {
            AbstractContainerMenu handler = handled.getMenu();
            Point mouseCoord = ScreenUtils.getMouseCoord(mc);
            Slot slot = HandledScreenAccess.of(handled).reallyGetSlotAt(mouseCoord.x, mouseCoord.y);
            if (enable.get() && enableShift.get()) {
                if (slot != null) {
                    // Debug.info("debug at ",slot.getContainerSlot());
                    int index = handler.slots.indexOf(slot);
                    // Debug.info("index at", index);
                    if (index >= 0) {
                        ItemStack template = slot.getItem();
                        if (!enableEmptyClick.get() && template.isEmpty()) {
                            return false;
                        }
                        ItemStack cleanedStack = ItemStackUtils.getCleanedItem(template, false, false);
                        var slots = handled.getMenu().slots;
                        for (var re = 0; re < slots.size(); re++) {
                            Slot sl = slots.get(re);
                            if ((sl.container instanceof Inventory)
                                    != (slot.container instanceof Inventory)) {
                                continue;
                            }
                            final int idx = re;
                            InvTasks.getClickExecutor().execute(() -> {
                                if (mc.gameMode == null
                                        || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                                    return false;
                                }
                                ItemStack stack = sl.getItem();
                                if (!ItemStack.isSameItem(stack, cleanedStack)
                                        || !ItemStack.isSameItemSameComponents(
                                                ItemStackUtils.getCleanedItem(stack, false, false), cleanedStack)) {
                                    return false;
                                }

                                mc.gameMode.handleContainerInput(
                                        handler.containerId, idx, 0, ContainerInput.QUICK_MOVE, mc.player);
                                return true;
                            });
                        }
                        return true;
                    }
                }
            } else if (enableLeftOne.get()) {
                if (slot != null) {
                    // Debug.info("debug at ",slot.getContainerSlot());
                    int index = handler.slots.indexOf(slot);
                    // Debug.info("index at", index);
                    if (index >= 0) {
                        InvTasks.quickMoveSlot(handler, slot.getContainerSlot(), true, true);
                    }
                }
            }
        }
        return false;
    }

    public boolean onDropAction() {
        if (mc.player == null) return false;
        if (enable.get() && enableDrop.get()) {
            Screen nowScreen = InvTasks.getCurrentServerScreen(mc.player);
            if (nowScreen instanceof AbstractContainerScreen<?> handled) {
                Point mouseCoord = ScreenUtils.getMouseCoord(mc);
                Slot slot = HandledScreenAccess.of(handled).reallyGetSlotAt(mouseCoord.x, mouseCoord.y);
                if (slot != null) {
                    var handler = handled.getMenu();
                    int index = handler.slots.indexOf(slot);
                    if (index >= 0) {
                        ItemStack template = slot.getItem();
                        if (!enableEmptyClick.get() && template.isEmpty()) {
                            return true;
                        }
                        ItemStack cleanedStack = ItemStackUtils.getCleanedItem(template, false, false);
                        var slots = handled.getMenu().slots;
                        InvTasks.getClickExecutor().execute(() -> {
                            if (mc.gameMode == null
                                    || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                                return false;
                            }
                            if (handler.getCarried().isEmpty()) {
                                return false;
                            }
                            ItemStack stack = handler.getCarried();
                            if (!ItemStack.isSameItem(stack, cleanedStack)
                                    || !ItemStack.isSameItemSameComponents(
                                            ItemStackUtils.getCleanedItem(stack, false, false), cleanedStack)) {
                                mc.gameMode.handleContainerInput(
                                        handler.containerId, index, 0, ContainerInput.PICKUP, mc.player);
                                mc.gameMode.handleContainerInput(
                                        handler.containerId, -999, 0, ContainerInput.PICKUP, mc.player);
                                return true;
                            }
                            mc.gameMode.handleContainerInput(handler.containerId, -999, 0, ContainerInput.PICKUP, mc.player);
                            return true;
                        });
                        for (var re = 0; re < slots.size(); re++) {
                            Slot sl = slots.get(re);
                            final int idx = re;
                            InvTasks.getClickExecutor().execute(() -> {
                                if (mc.gameMode == null
                                        || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                                    return false;
                                }
                                ItemStack stack = sl.getItem();
                                if (!ItemStack.isSameItem(stack, cleanedStack)
                                        || !ItemStack.isSameItemSameComponents(
                                                ItemStackUtils.getCleanedItem(stack, false, false), cleanedStack)) {
                                    return false;
                                }

                                mc.gameMode.handleContainerInput(
                                        handler.containerId, idx, 1, ContainerInput.THROW, mc.player);
                                return true;
                            });
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void takeAll() {
        if (checkNull()) return;
        AbstractContainerMenu nowScreen = InvTasks.getCurrentServerScreenHandler(mc.player);
        if (!(nowScreen instanceof InventoryMenu)) {
            AbstractContainerMenu handler = nowScreen;
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.getSlot(i);
                if (!(slot.container instanceof Inventory)) {
                    final int index = i;
                    InvTasks.getClickExecutor().execute(() -> {
                        if (mc.gameMode == null
                                || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                            return false;
                        }
                        ItemStack stack = slot.getItem();
                        if (enableEmptyClick.get() || !stack.isEmpty()) {
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, index, 1, ContainerInput.QUICK_MOVE, mc.player);
                            return true;
                        } else {
                            return false;
                        }
                    });
                }
            }
        }
    }

    public void saveAll() {
        if (checkNull()) return;
        AbstractContainerMenu nowScreen = InvTasks.getCurrentServerScreenHandler(mc.player);
        if (!(nowScreen instanceof InventoryMenu)) {
            AbstractContainerMenu handler = nowScreen;
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.getSlot(i);
                if (slot.container instanceof Inventory) {
                    final int index = i;
                    InvTasks.getClickExecutor().execute(() -> {
                        if (mc.gameMode == null
                                || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                            return false;
                        }
                        ItemStack stack = slot.getItem();
                        if (enableEmptyClick.get() || !stack.isEmpty()) {
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, index, 1, ContainerInput.QUICK_MOVE, mc.player);
                            return true;
                        } else {
                            return false;
                        }
                    });
                }
            }
        }
    }
}
