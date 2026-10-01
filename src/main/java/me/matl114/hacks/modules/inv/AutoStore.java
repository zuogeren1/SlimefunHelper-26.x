package me.matl114.hacks.modules.inv;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.InvTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.TaskManagers;
import me.matl114.managers.config.FlagRef;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class AutoStore extends BaseModule {
    // do it later
    public final ModulePath autoInv = makePath(Configs.INV_CONFIG, "auto-inv");
    public final ModulePath autoStore = autoInv.add("auto-store");

    public AutoStore() {
        super("AutoStore");
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(autoStore.add("enable")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        TaskManagers.getToggleManager().register(TaskManagers.PREFIX_BUTTON_TOGGLE + "." + "auto-store", enable);
        registerListener(Listener.getPreGameTick(), this::onTick);
    }
    // todo: rewrite this
    public void onTick(Event<LocalPlayer> event) {
        if (enable.get()) {
            var player = event.context();
            Screen screen = InvTasks.getCurrentServerScreen(player);
            if (screen instanceof AbstractContainerScreen<?> handledScreen) {
                var handler = handledScreen.getMenu();
                if (handledScreen instanceof CreativeModeInventoryScreen || handledScreen instanceof InventoryScreen) {
                    return;
                }

                IntList inputSlot = new IntArrayList();
                IntList outputSlot = new IntArrayList();
                for (int i = 0; i < handler.slots.size(); ++i) {
                    Slot slot = handler.slots.get(i);
                    if (slot.container instanceof Inventory) {
                        inputSlot.add(i);
                    } else {
                        outputSlot.add(i);
                    }
                }

                for (int i : inputSlot) {
                    ItemStack stack = handler.slots.get(i).getItem();
                    // left one is enough
                    // left two please
                    if (stack != null && !stack.isEmpty() && stack.getCount() >= 4) {
                        // when trying to remove full stack, ensure that cursor is empty
                        if (!handler.getCarried().isEmpty()) {
                            ItemStack stackt = handler.getCarried();
                            int slot = anyMatch(handler.slots, stackt, stackt.getCount(), outputSlot.toIntArray());
                            if (slot >= 0) {
                                InvTasks.getClickExecutor().execute(() -> {
                                    if (mc.gameMode == null
                                            || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                                        return false;
                                    }
                                    mc.gameMode.handleContainerInput(
                                            handledScreen.getMenu().containerId,
                                            slot,
                                            0,
                                            ContainerInput.PICKUP,
                                            player);
                                    return true;
                                });
                            } else {
                                InvTasks.getClickExecutor().execute(() -> {
                                    if (mc.gameMode == null
                                            || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                                        return false;
                                    }
                                    mc.gameMode.handleContainerInput(
                                            handledScreen.getMenu().containerId, slot, 0, ContainerInput.THROW, player);
                                    return true;
                                });
                            }
                            return;
                        }
                        // cursor is empty, we can execute transform
                        int toTransfer = (stack.getCount() + 1) / 2;
                        int slot = anyMatch(handler.slots, stack, toTransfer, outputSlot.toIntArray());
                        if (slot >= 0) {

                            InvTasks.getClickExecutor().execute(() -> {
                                if (mc.gameMode == null
                                        || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                                    return false;
                                }
                                mc.gameMode.handleContainerInput(handledScreen.getMenu().containerId, i, 1, ContainerInput.PICKUP, player);
                                mc.gameMode.handleContainerInput(handledScreen.getMenu().containerId, slot, 0, ContainerInput.PICKUP, player);
                                return true;
                            });
                            return;
                        }
                    }
                }
            }
        }
    }

    private static int anyMatch(NonNullList<Slot> slots, ItemStack stack, int amount, int... index) {
        for (int i : index) {
            ItemStack stack2 = slots.get(i).getItem();
            // can place stack with amount on it,
            if (stack2 != null
                    && (stack2.isEmpty()
                            || stack2.getCount() + amount <= stack2.getMaxStackSize()
                                    && ItemStack.isSameItemSameComponents(stack, stack2))) {
                return i;
            }
        }
        return -1;
    }
}
