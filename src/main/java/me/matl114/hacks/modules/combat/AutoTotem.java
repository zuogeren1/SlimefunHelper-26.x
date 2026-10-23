package me.matl114.hacks.modules.combat;

import java.util.List;
import java.util.Random;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.InvTasks;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.task.PostManager;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.hacks.utils.tasks.StateExecutor;
import me.matl114.hacks.utils.tasks.TimerExecutor;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.Debug;
import me.matl114.utils.InventoryUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AutoTotem extends BaseModule {
    private final Random inventorRandom = new Random();
    public final ModulePath totem = makePath(Configs.COMBAT_CONFIG, "totem");

    public AutoTotem() {
        super("AutoTotem");
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(totem.add("auto-totem")).build();

    public final KeyBindRef hotkey = moduleEntry(
                    totem.add("auto-totem-hotkey"), new MultiKeyBind(), totem.add("auto-totem"))
            .build();

    public final FlagRef log = builder(totem.add("auto-totem-log"), Boolean.class)
            .defaultValue(true)
            .build();

    public final IntRef cooldown = builder(totem.add("totem-swap-cooldown"), IntRef.TYPE)
            .defaultValue(1)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef forcedHotbarSlot = intBuilder(totem.add("auto-totem-hotbar-slot"))
            .defaultValue(-1)
            .validator(Configs.intRange(-1, 8))
            .build();

    public final FlagRef mainHandTotem = flagBuilder(totem.add("main-hand-totem"), true)
            .build();

    public final NBTRef<EntrySet<Item>> enableHandItems = builder(
                    totem.add("enable-hand-items"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(new Regex("^()$"), BuiltInRegistries.ITEM))
            .build();

    public final FlagRef antiMiss = flagBuilder(totem.add("anti-miss")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreGameTick(), this::onTick);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
        registerListener(Listener.getPacketPoint().getChannel(ClientboundEntityEventPacket.class), this::onTotem);
        registerListener(Listener.getPlayerInitConfiguration(), this::onPlayerInit);
        registerListener(Listener.getPlayerScrollHotBar(), this::onTotemMainHand);
    }

    private boolean canBeAccepted(ItemStack ex) {
        return ex.getItem() == Items.TOTEM_OF_UNDYING || enableHandItems.get().test(ex.getItem());
    }

    StateExecutor noTotem = new StateExecutor();
    // todo: add legal mode (swap hand)
    public void onTick() {
        if (enable.get()) {
            onTotemLazy();
        }
    }

    private void handleTotemSwapFailure() {
        noTotem.state(true, () -> {
            if (log.get()) {
                logI18N("message.module.auto-totem.not-found");
            }
        });
    }

    //    int lastStartSwap114514 = 0;
    //    int swapCnt1919810 = 0;
    TimerExecutor swapFrequency = new TimerExecutor();
    int swapCntCounter = 0;
    int reswapCounter = 0;
    private int forcedHotbarPreviousSlot = -1;

    private void handleTotemSwapSuccess() {
        noTotem.state(false);
        swap.mark();
        swapFrequency.run(20, () -> swapCntCounter = 0);
        if (++swapCntCounter > 5) {
            swapCntCounter = 0;
            if (log.get()) {
                logI18N("message.module.auto-totem.swap-too-frequent");
            }
        }
    }

    public void onTotemLazy() {
        int forcedSlot = getForcedHotbarSlot();

        // stop from duplicate swap
        if (mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) {
            restoreHotBar(forcedSlot, false);
            restoreForcedHotbar();
            return;
        }
        if (!canBeAccepted(mc.player.getOffhandItem())) {
            // well looks
            int toSlot = 40;
            if (!swap.canRun(cooldown.get())) {
                return;
            }
            swapTo(toSlot);
            if (!swap.canRun(cooldown.get())) {
                return;
            }
            if (forcedSlot >= 0) {
                restoreHotBar(forcedSlot, false);
            }
        } else {
            // not totem
            if (forcedSlot >= 0) {
                restoreHotBar(forcedSlot, true);
                if (mainHandTotem.get()
                        && mc.player.getInventory().getItem(forcedSlot).getItem() == Items.TOTEM_OF_UNDYING
                        && InventoryUtils.getSelectedSlot() != forcedSlot) {
                    if (++reswapCounter > 1) {
                        forcedHotbarPreviousSlot = InventoryUtils.getSelectedSlot();
                        PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(forcedSlot);
                        reswapCounter = 0;
                    }
                } else {
                    reswapCounter = 0;
                }
            } else if (mc.player.getMainHandItem().getItem() != Items.TOTEM_OF_UNDYING) {
                if (mainHandTotem.get()) {
                    int toSlot = InventoryUtils.getSelectedSlot();
                    if (!swap.canRun(cooldown.get())) {
                        return;
                    }
                    swapTo(toSlot);
                }
            }
        }
    }

    public void onTotemMainHand(Event<Integer> event) {
        if (event.isCancelled()) return;
        int forcedSlot;
        if (enable.get() && mainHandTotem.get() && (forcedSlot = getForcedHotbarSlot()) >= 0) {
            if (mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) {
                return;
            }
            if (canBeAccepted(mc.player.getOffhandItem())
                    && mc.player.getInventory().getItem(forcedSlot).getItem() == Items.TOTEM_OF_UNDYING
                    && event.context != forcedSlot) {
                if (InventoryUtils.getSelectedSlot() != forcedSlot) {
                    event.context(forcedSlot);
                } else {
                    event.cancel();
                }
            }
        }
    }

    private int getForcedHotbarSlot() {
        int forcedSlot = forcedHotbarSlot.get();
        return forcedSlot >= 0 && forcedSlot < 9 ? forcedSlot : -1;
    }

    private void restoreHotBar(int forcedSlot, boolean forceTotem) {
        if (forcedSlot < 0 || forcedSlot >= 9) {
            return;
        }
        ItemStack item = mc.player.getInventory().getItem(forcedSlot);
        boolean hasTotem = forceTotem ? item.getItem() == Items.TOTEM_OF_UNDYING : canBeAccepted(item);
        if (!hasTotem) {
            if (swap.canRun(cooldown.get())) {
                swapTo(forcedSlot);
            }
            return;
        }
    }

    private void restoreForcedHotbar() {
        if (forcedHotbarPreviousSlot < 0) {
            return;
        }
        int previousSlot = forcedHotbarPreviousSlot;
        forcedHotbarPreviousSlot = -1;
        if (!checkNull()) {
            // 与 InvExtra 的 hotBarCallbacks 同队列，避免同一 tick 重复同步选中槽
            PostManager.INSTANCE.addNextTransactionAction(
                    ch2 -> PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(previousSlot));
        }
    }

    private void swapTo(int toSlot) {
        AbstractContainerMenu handled = ClientPlayerAccess.of(mc.player).getServerScreenHandler();
        List<Slot> slots = handled.slots;
        for (var i = 0; i < slots.size(); ++i) {
            if ((slots.get(i).container instanceof Inventory || handled == mc.player.inventoryMenu)
                    && slots.get(i).getItem().getItem() == Items.TOTEM_OF_UNDYING) {
                MovTasks.getMovExtra().sendPacketsForInventoryAction();
                InvTasks.clickSlotAsync(i, toSlot, ContainerInput.SWAP);
                Debug.debug("handle swap success");
                handleTotemSwapSuccess();
                return;
            }
        }
        handleTotemSwapFailure();
    }

    TimerExecutor swap = new TimerExecutor();

    public void onTotem(Event<ClientboundEntityEventPacket> eventTotem) {
        if (checkNull()) return;
        if (enable.get()
                && antiMiss.get()
                && eventTotem.context.getEventId() == EntityEvent.PROTECTED_FROM_DEATH
                && eventTotem.context.getEntity(mc.level) == mc.player) {
            ItemStack stackInMainHand = mc.player.getMainHandItem();
            int consumeSlot =
                    stackInMainHand.getItem() == Items.TOTEM_OF_UNDYING ? InventoryUtils.getSelectedSlot() : 40;
            mc.player.getInventory().setItem(consumeSlot, ItemStack.EMPTY);
            AbstractContainerMenu handled = ClientPlayerAccess.of(mc.player).getServerScreenHandler();
            List<Slot> slots = handled.slots;
            // revert usage to avoid conflict
            for (var i = slots.size() - 1; i >= 0; --i) {
                if (i != consumeSlot
                        && (slots.get(i).container instanceof Inventory || handled == mc.player.inventoryMenu)
                        && slots.get(i).getItem().getItem() == Items.TOTEM_OF_UNDYING) {
                    MovTasks.getMovExtra().sendPacketsForInventoryAction();
                    InvTasks.clickSlotAsync(i, consumeSlot, ContainerInput.SWAP);
                    handleTotemSwapSuccess();
                    Debug.debug("handle antimiss success");
                    // pre tick
                    swap.mark(1);
                    return;
                }
            }
            handleTotemSwapFailure();
        }
    }

    public void onPlayerInit() {
        noTotem.state(false);
    }

    public void onModulePreset() {}
}
