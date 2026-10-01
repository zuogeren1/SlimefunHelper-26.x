package me.matl114.hacks.modules.combat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.BlockUpdate;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.InvTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.interact.Interact;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.interact.SequencedActionManager;
import me.matl114.hacks.modules.inv.ChestHistory;
import me.matl114.hacks.modules.mine.QueueMine;
import me.matl114.hacks.modules.move.MovExtra;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.OptionalPrimitive;
import me.matl114.hacks.utils.tasks.TimerExecutor;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.InteractUtils;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.MathUtils;
import me.matl114.utils.ScreenUtils;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;

public class AntiReplenish extends BaseModule {
    public AntiReplenish() {
        super("AntiReplenish");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.COMBAT_CONFIG, "combat-utils.anti-replenish");
    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();
    public List<Vec3i> blockSeq = new ArrayList<>();
    public final DoubleRef searchRange = doubleBuilder(root.add("search-range"))
            .defaultValue(4.5D)
            .validator(Configs.doubleRange(0, 100))
            .updateListener(s -> blockSeq = MathUtils.create3DPointListAroundPlayer(s))
            .build();

    public final IntRef delay = intBuilder(root.add("delay")).defaultValue(10).build();

    public final FlagRef zeroTick = flagBuilder(root.add("zero-tick")).build();

    public final FlagRef steal = flagBuilder(root.add("steal")).build();

    public final FlagRef stealPart = flagBuilder(root.add("steal-only-part")).build();

    public final FlagRef drop = flagBuilder(root.add("drop")).build();

    public final FlagRef autoClose =
            builder(root.add("auto-close"), Boolean.class).defaultValue(true).build();

    public final FlagRef autoMine = flagBuilder(root.add("auto-mine")).build();

    public final FlagRef eatingAbort = builder(root.add("using-item-abort"), Boolean.class)
            .defaultValue(false)
            .build();

    public final NBTRef<OptionalPrimitive<Double>> dropPitch = builder(
                    root.add("drop-pitch"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.DOUBLE_TYPE, 89.0D))
            .validator(s -> s.getValue() >= -90 && s.getValue() <= 90)
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onPreHandleEvent);
        registerListener(Listener.getBlockUpdateListener(), this::onBlockUpdate);
        registerListener(Listener.getWorldSwitchPoint(), this::onSwitchWorld);
    }

    @Override
    public void addCustomWidgets(Consumer<DrawableWidget> acceptor, int dx, int dy, int dblank) {
        super.addCustomWidgets(acceptor, dx, dy, dblank);
        acceptor.accept(createTitle("widget.interact.interact-block.use-argument", 0, dblank, dx, dy));
        acceptor.accept(createTitle("widget.queue-mine.mine.use-argument", 0, dblank, dx, dy));
    }

    final Set<BlockPos> cachedStolenPoses = new HashSet<>();
    boolean pendingWaitingScreen = false;
    TimerExecutor timerExecutor = new TimerExecutor();

    public void onPreHandleEvent(Event<Void> event) {
        if (enable.get()) {
            cachedStolenPoses.removeIf(s -> !(mc.level.getBlockState(s).getBlock() instanceof ShulkerBoxBlock));
            if (eatingAbort.get() && mc.player.isUsingItem()) return;
            if (pendingWaitingScreen && !zeroTick.get()) {
                return;
            }

            BlockPos currentPlayerPos = mc.player.blockPosition();
            for (var re : blockSeq) {
                BlockPos testPos = currentPlayerPos.offset(re);
                if (!cachedStolenPoses.contains(testPos)
                        && mc.level.getBlockState(testPos).getBlock() instanceof ShulkerBoxBlock
                        && mc.level.getBlockEntity(testPos) instanceof ShulkerBoxBlockEntity be
                        && (!ChestHistory.INSTANCE.isShulkerBoxPlacedBySelf(testPos) && !doesShulkerContainsAnyData(be))
                        && !mc.player.isSecondaryUseActive()
                        && InteractUtils.canShulkerOpen(mc.level, testPos, mc.level.getBlockState(testPos))
                        && InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), testPos)) {
                    if (timerExecutor.canRun(delay.get())) {
                        Consumer<AbstractContainerMenu> runnable = (ch) -> {
                            timerExecutor.mark();
                            execute(ch);
                            cachedStolenPoses.add(testPos);
                            if (autoClose.get())
                                mc.getConnection().send(new ServerboundContainerClosePacket(ch.containerId));
                        };
                        if (zeroTick.get()) {
                            Interact.INSTANCE.interactBlock(testPos);
                            InvTasks.executePredictInventoryAction(be, runnable);
                            ScreenUtils.getOpenScreenFuture().thenAccept((handled) -> {
                                if (autoClose.get()) mc.player.closeContainer();
                            });
                        } else {
                            if (!SequencedActionManager.INSTANCE.isWaitingResponse(testPos)) {
                                Interact.INSTANCE.interactBlock(testPos);
                                pendingWaitingScreen = true;
                                ScreenUtils.getOpenScreenFuture().thenAccept((handled) -> {
                                    pendingWaitingScreen = false;
                                    runnable.accept(handled.getMenu());
                                    if (autoClose.get()) mc.player.clientSideCloseContainer();
                                });
                            }
                        }
                    }

                    if (autoMine.get()) {
                        cachedStolenPoses.add(testPos);
                        QueueMine.INSTANCE.sumitMine(testPos);
                    }
                    return;
                }
            }
        }
    }

    private boolean doesShulkerContainsAnyData(ShulkerBoxBlockEntity blockEntity) {
        return InventoryUtils.streamInventory(blockEntity).anyMatch(s -> !s.isEmpty());
    }

    private void onSwitchWorld(Event<Level> event) {
        cachedStolenPoses.clear();
    }

    private void execute(AbstractContainerMenu handler) {
        if (handler == mc.player.inventoryMenu) return;
        MovExtra.INSTANCE.sendPacketsForInventoryAction();
        if (steal.get()) {
            for (var i = 0; i < 27; ++i) {
                mc.gameMode.handleContainerInput(handler.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
            }
        } else if (stealPart.get()) {
            int total = (int) InventoryUtils.streamInventory(mc.player.getInventory())
                    .filter(ItemStack::isEmpty)
                    .count();
            for (var i = 0; i < total; ++i) {
                mc.gameMode.handleContainerInput(handler.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
            }
        }
        if (drop.get()) {
            if (dropPitch.get().isPresent()) {
                InteractionTasks.interactItem(
                        InteractionHand.MAIN_HAND, (float) (double) dropPitch.get().getValue(), mc.player.getYRot(), false, false);
            }
            for (var i = 0; i < 27; ++i) {
                mc.gameMode.handleContainerInput(handler.containerId, i, 1, ContainerInput.THROW, mc.player);
            }
        }
    }

    public void onBlockUpdate(Event<BlockUpdate> event) {
        if (enable.get()) {
            if (event.context.oldState().getBlock() instanceof ShulkerBoxBlock
                    && !(event.context.newState().getBlock() instanceof ShulkerBoxBlock)) {
                cachedStolenPoses.remove(event.context.pos());
            }
        }
    }
}
