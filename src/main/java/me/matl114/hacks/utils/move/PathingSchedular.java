package me.matl114.hacks.utils.move;

import me.matl114.hacks.modules.interact.SequencedActionManager;
import me.matl114.utils.ClientUtils;

import com.mojang.datafixers.util.Pair;
import java.util.*;
import java.util.function.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import me.matl114.accessors.access.ChunkAccess;
import me.matl114.accessors.interfaces.TileInventory;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.hacks.modules.interact.Interact;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.inv.ChestHistory;
import me.matl114.hacks.modules.inv.KitReplenish;
import me.matl114.hacks.modules.survival.SchedularSettings;
import me.matl114.hacks.utils.config.Vec3;
import me.matl114.hacks.utils.move.goal.GoalDirection;
import me.matl114.hacks.utils.move.goal.GoalNear;
import me.matl114.hacks.utils.move.goal.IPathGoal;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hooks.BaritoneHooks;
import me.matl114.managers.Tasks;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.ItemStackUtils;
import me.matl114.utils.MathUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.algorithms.StateMachine;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.config.ValueAccessor;
import me.matl114.utils.render.RenderCollector;
import me.matl114.utils.world.ContainerPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.*;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

@Accessors(fluent = true)
public class PathingSchedular {
    static final Minecraft mc = Minecraft.getInstance();
    public static IndexEntry<PathingSchedular> currentWorkingInstance = null;
    private static final int PATH_STOP_SEARCH_RADIUS = 4;
    private static final int PATH_STOP_SEARCH_VERTICAL = 6;

    public static void ensureStart(PathingSchedular schedular) {
        if (currentWorkingInstance != null && currentWorkingInstance.val() != schedular) {
            currentWorkingInstance.val().engine.end();
            currentWorkingInstance = null;
        }
        currentWorkingInstance = new IndexEntry<>(Tasks.getTick(), schedular);
        schedular.engine.start();
    }

    public static void ensureEnd(PathingSchedular schedular) {
        if (currentWorkingInstance != null && currentWorkingInstance.val() == schedular) {
            schedular.engine.end();
            currentWorkingInstance = null;
        }
    }

    public PathingSchedular() {
        engine = SchedularSettings.INSTANCE.createEngine();
        machine = new StateMachine(
                State.NONE.ordinal(),
                this::update,
                this::onStateNone,
                this::onStateInitializeAndExploreEnvironment,
                this::onStateReplenish,
                this::onStateProcess,
                this::onStateDischarge,
                this::onStateExpandStorage);
        machine.registerListener(State.NONE.ordinal(), this::onResetStateToNone);
        machine.registerListener(State.INITIALIZE_EXPLORE.ordinal(), this::onSwitchInitialize);
    }

    public void reset() {
        machine.setState(State.NONE.ordinal());
    }

    @Nullable
    @Setter
    BooleanSupplier active;

    @Nullable
    @Setter
    Supplier<IPathGoal> processGoal;

    @Nullable
    @Setter
    BooleanSupplier replenish;

    @Nullable
    @Setter
    BooleanSupplier discharge;

    @Nullable
    @Setter
    Predicate<AbstractContainerScreen<?>> replenishAction;
    //    @Nullable
    //    @Setter
    //    Predicate<HandledScreen<?>> dischargeAction;
    @Nullable
    @Setter
    Predicate<ItemStack> dischargePlayerInventory;

    @Nullable
    @Setter
    Supplier<Iterable<ContainerPosition>> containerSourceOverride;

    @Nullable
    @Setter
    BiPredicate<ContainerPosition, Container> replenishmentSourcePredicate;

    @Nullable
    @Setter
    BiPredicate<ContainerPosition, Container> dischargeSourcePredicate;

    final StateMachine machine;

    @Nonnull
    @Setter
    PathingEngine engine;

    @Setter
    boolean enable;

    LocalPlayer player;

    @Nonnull
    BlockPos startPos = BlockPos.ZERO;

    @Setter
    @Getter
    HashSet<ContainerPosition> replenishSource = new HashSet<>();

    @Setter
    @Getter
    HashSet<ContainerPosition> dischargeSource = new HashSet<>();

    @Setter
    @Getter
    HashSet<ContainerPosition> optionalHasShulkerBoxOrChestSource = new HashSet<>();

    RenderCollector<AABB> renderCollector = RenderCollectors.createBoxCollector(true, false, false);

    @Setter
    @Getter
    boolean autoAfk = false;

    public PathingSchedular mine(boolean bool) {
        engine.setCanMine(bool);
        return this;
    }

    public int update(StateMachine machine, int state) {
        if (!enable) {
            machine.markForEndState();
            return State.NONE.ordinal();
        }
        return state;
    }

    public void onResetStateToNone(boolean bl) {
        if (bl) {
            replenishSource.clear();
            dischargeSource.clear();
            optionalHasShulkerBoxOrChestSource.clear();
            exploreChestBlock = null;
            renderCollector.clear();
            startPos = BlockPos.ZERO;
            pendingExploreBlocks = null;
            exploreChestBlock = null;
            currentDischargeActionTarget = null;
            currentReplenishActionTarget = null;
            currentWaitingOpenContainer = null;
            currentExpansionActionTarget = null;
            engine.sumitGoal(null);
        }
    }

    public int onStateNone(StateMachine machine) {
        startPos = player.getOnPos().offset(0, 1, 0);
        if (enable) {
            return State.INITIALIZE_EXPLORE.ordinal();
        }
        return State.NONE.ordinal();
    }

    HashSet<ContainerPosition> pendingExploreBlocks;
    ContainerPosition exploreChestBlock;

    private boolean isEmptyShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof ShulkerBoxBlock
                && ItemStackUtils.hasInPatch(stack, DataComponents.CONTAINER)
                && stack.get(DataComponents.CONTAINER)
                        .nonEmptyItemCopyStream()
                        .findFirst()
                        .isEmpty();
    }

    private void analysisContainer(ContainerPosition pos, Container handledScreen) {
        if (replenishmentSourcePredicate != null && replenishmentSourcePredicate.test(pos, handledScreen)) {
            replenishSource.add(pos);
        }
        if (dischargeSourcePredicate != null && dischargeSourcePredicate.test(pos, handledScreen)) {
            dischargeSource.add(pos);
        } else {
            boolean hasNoEmpty = false;
            for (var re : InventoryUtils.iterable(handledScreen)) {
                if (!re.isEmpty()) {
                    hasNoEmpty = true;
                    break;
                }
            }
            if (!hasNoEmpty) {
                dischargeSource.add(pos);
            }
        }
        if (SchedularSettings.INSTANCE.enableAutoExpandShulker.get()
                && InventoryUtils.findItem(handledScreen, this::isEmptyShulkerBox, false) != null) {
            optionalHasShulkerBoxOrChestSource.add(pos);
        }
        if (SchedularSettings.INSTANCE.enableAutoExpandChest.get()
                && InventoryUtils.findItem(handledScreen, Items.CHEST) != null) {
            optionalHasShulkerBoxOrChestSource.add(pos);
        }
    }

    public void tickExplore() {
        if (exploreChestBlock == null) {
            return;
        }
        if (!isContainerStillValid(exploreChestBlock)) {
            exploreChestBlock = null;
            return;
        }
        ChestHistory.Entry history = ChestHistory.INSTANCE.getEntry(exploreChestBlock);
        if (history != null) {
            analysisContainer(exploreChestBlock, history.getInventory());
            exploreChestBlock = null;
            return;
        }
        if (ClientUtils.getScreen(mc) instanceof AbstractContainerScreen<?> screen
                && screen instanceof TileInventory tileInventoryScreen
                && Objects.equals(tileInventoryScreen.getContainerPosition(), exploreChestBlock)) {
            analysisContainer(exploreChestBlock, InventoryUtils.getTopInventory(screen));
            screen.onClose();
            exploreChestBlock = null;
            return;
        }
        pathToOrNearStop(exploreChestBlock.getFirst().getPos(), 1.5);
        if (InteractExtra.INSTANCE.isWithinInteractRange(
                mc.player.position(), exploreChestBlock.getFirst().getPos())) {
            BlockPos pos = exploreChestBlock.getFirst().getPos();
            if (!SequencedActionManager.INSTANCE.isWaitingResponse(pos)) {
                Interact.INSTANCE.interactBlock(pos);
            }
        }
    }

    public void onSwitchInitialize(boolean bl) {
        pendingExploreBlocks = null;
    }

    private boolean isContainerStillValid(ContainerPosition pos) {
        BlockPos left = pos.getFirst().getPos();
        BlockEntity be = mc.level.getBlockEntity(left);
        return be instanceof BarrelBlockEntity || be instanceof ChestBlockEntity || be instanceof ShulkerBoxBlockEntity;
    }

    public int onStateInitializeAndExploreEnvironment(StateMachine machine) {
        if (exploreChestBlock != null) {
            tickExplore();
            machine.markForEndState();
            return State.INITIALIZE_EXPLORE.ordinal();
        }
        if (pendingExploreBlocks == null) {
            boolean needCalculateReplenishment = false;
            if (replenish != null) {
                needCalculateReplenishment = SchedularSettings.INSTANCE.enableAutoReplenish.get();
            }
            boolean needCalculateDischarge = false;
            if (discharge != null) {
                needCalculateDischarge = SchedularSettings.INSTANCE.enableAutoDischarge.get();
            }
            pendingExploreBlocks = new HashSet<>();
            if (needCalculateReplenishment || needCalculateDischarge) {
                if (containerSourceOverride != null) {
                    containerSourceOverride.get().forEach(pendingExploreBlocks::add);
                } else {
                    net.minecraft.world.phys.Vec3 currentPos = player.position();
                    Vec3 expansion = SchedularSettings.INSTANCE.scannChestRange.get();
                    AABB box = new AABB(currentPos, currentPos).inflate(expansion.x(), expansion.y(), expansion.z());
                    net.minecraft.world.phys.Vec3 pos0 = box.getMinPosition();
                    net.minecraft.world.phys.Vec3 pos1 = box.getMaxPosition();
                    ChunkPos chunk0 = MathUtils.toChunkPos(pos0);
                    ChunkPos chunk1 = MathUtils.toChunkPos(pos1);
                    for (var x = chunk0.x; x <= chunk1.x; x++) {
                        for (var z = chunk0.z; z <= chunk1.z; z++) {
                            net.minecraft.world.level.chunk.ChunkAccess chunk =
                                    mc.level.getChunkSource().getChunkNow(x, z);
                            if (chunk != null) {
                                for (var re : ChunkAccess.of(chunk).blockEntityEntries()) {
                                    BlockPos pos = re.getKey();
                                    ContainerPosition containerPosition = ContainerPosition.resolve(mc.level, pos);
                                    if (isContainerStillValid(containerPosition)
                                            && box.intersects(containerPosition.getBoundingBox())) {
                                        pendingExploreBlocks.add(containerPosition);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            var iter = pendingExploreBlocks.iterator();
            while (iter.hasNext()) {
                var container = iter.next();
                ChestHistory.Entry currentAvailableEntry = ChestHistory.INSTANCE.getEntry(container);
                if (currentAvailableEntry != null) {
                    iter.remove();
                    analysisContainer(container, currentAvailableEntry.getInventory());
                }
            }
        }
        if (pendingExploreBlocks.isEmpty()) {
            return State.PROCESS.ordinal();
        } else {
            exploreChestBlock = pendingExploreBlocks.stream()
                    .min(Comparator.comparingDouble(s -> s.getCenterPosition().distanceToSqr(mc.player.position())))
                    .orElse(null);
            pendingExploreBlocks.remove(exploreChestBlock);
            return State.INITIALIZE_EXPLORE.ordinal();
        }
    }

    ContainerPosition currentReplenishActionTarget;

    public int onStateReplenish(StateMachine machine) {
        if (currentReplenishActionTarget != null && !isContainerStillValid(currentReplenishActionTarget)) {
            if (!currentReplenishActionTarget.isDouble()) {
                ContainerPosition tryResolve = ContainerPosition.resolve(
                        mc.level, currentReplenishActionTarget.getFirst().getPos());
                if (tryResolve.isDouble()) {
                    replenishSource.add(tryResolve);
                }
            }
            replenishSource.remove(currentReplenishActionTarget);
            currentReplenishActionTarget = null;
        }
        if (replenishSource != null && !replenishSource.isEmpty()) {
            if (currentReplenishActionTarget == null) {
                currentReplenishActionTarget = replenishSource.stream()
                        .min(Comparator.comparingDouble(
                                s -> mc.player.position().distanceToSqr(s.getCenterPosition())))
                        .orElseThrow();
            }
            BlockPos leftPos = currentReplenishActionTarget.getFirst().getPos();
            if (ClientUtils.getScreen(mc) instanceof TileInventory tile
                    && Objects.equals(tile.getContainerPosition(), currentReplenishActionTarget)) {
                AbstractContainerScreen<?> screen = tile.castHandled();
                if (replenishAction != null) {
                    if (!replenishAction.test(screen)) {
                        replenishSource.remove(currentReplenishActionTarget);
                        currentReplenishActionTarget = null;
                        screen.onClose();
                    }
                } else {
                    Container topInventory = InventoryUtils.getTopInventory(screen);
                    if (topInventory.isEmpty()) {
                        replenishSource.remove(currentReplenishActionTarget);
                        currentReplenishActionTarget = null;
                        screen.onClose();
                    } else {
                        int actionLimit = SchedularSettings.INSTANCE.defaultInventoryActionPerTick.get();
                        for (var re = 0; re < topInventory.getContainerSize(); ++re) {
                            if (!topInventory.getItem(re).isEmpty()) {
                                mc.gameMode.handleContainerInput(
                                        screen.getMenu().containerId, re, 0, ContainerInput.QUICK_MOVE, mc.player);
                                if (--actionLimit == 0) {
                                    break;
                                }
                            }
                        }
                    }
                }
            } else {
                pathToOrNearStop(leftPos, 1.5);
                if (InteractExtra.INSTANCE.isWithinInteractRange(player.position(), leftPos)) {
                    BlockPos pos = leftPos;
                    if (!SequencedActionManager.INSTANCE.isWaitingResponse(pos)) {
                        Interact.INSTANCE.interactBlock(pos);
                    }
                }
            }
            machine.markForEndState();
            return State.PROCESS.ordinal();
        } else {
            SchedularSettings.INSTANCE.logNoContainer();
            machine.markForEndState();
            if (SchedularSettings.INSTANCE.containerSourceHotReload.get()) {
                SchedularSettings.INSTANCE.logReloadContainer();
                return State.NONE.ordinal();
            } else {
                return State.PROCESS.ordinal();
            }
        }
    }

    public int onStateProcess(StateMachine machine) {
        if (discharge != null && discharge.getAsBoolean()) {
            return State.DISCHARGE.ordinal();
        }
        if (replenish != null && replenish.getAsBoolean()) {
            return State.REPLENISH.ordinal();
        }
        IPathGoal goalBlock = processGoal != null ? processGoal.get() : null;
        if (goalBlock != null) {
            engine.sumitGoal(goalBlock);
        } else {
            if (autoAfk) {
                pathToOrNearStop(startPos, 1.0);
            } else {
                engine.sumitGoal(null);
            }
        }
        machine.markForEndState();
        return State.PROCESS.ordinal();
    }

    ContainerPosition currentDischargeActionTarget;

    public int onStateDischarge(StateMachine machine) {
        if (currentDischargeActionTarget != null && !isContainerStillValid(currentDischargeActionTarget)) {
            if (!currentDischargeActionTarget.isDouble()) {
                ContainerPosition tryResolve = ContainerPosition.resolve(
                        mc.level, currentDischargeActionTarget.getFirst().getPos());
                if (tryResolve.isDouble()) {
                    dischargeSource.add(tryResolve);
                }
            }
            dischargeSource.remove(currentDischargeActionTarget);
            currentDischargeActionTarget = null;
        }
        Predicate<ItemStack> stackPredicate = dischargePlayerInventory != null
                ? dischargePlayerInventory
                : (stack) -> {
                    return !stack.isEmpty() && stack.getMaxDamage() == 0;
                };
        Predicate<IndexEntry<ItemStack>> combinedPlayerInventoryFinder =
                (entry) -> entry.index() >= SchedularSettings.INSTANCE.hotbarProtectRange.get()
                        && stackPredicate.test(entry.val());
        if (InventoryUtils.findInventory(mc.player.getInventory(), combinedPlayerInventoryFinder, false) == null) {
            currentDischargeActionTarget = null;
            machine.markForEndState();
            return State.PROCESS.ordinal();
        }

        if (dischargeSource != null && !dischargeSource.isEmpty()) {
            if (currentDischargeActionTarget == null) {
                currentDischargeActionTarget = dischargeSource.stream()
                        .filter(s -> {
                            ChestHistory.Entry entry = ChestHistory.INSTANCE.getEntry(s);
                            if (entry == null) return true;
                            Container inventory = entry.getInventory();
                            if (InventoryUtils.findItem(inventory, Items.AIR) != null) {
                                return true;
                            }
                            return InventoryUtils.streamInventory(mc.player.getInventory())
                                    .filter(stackPredicate)
                                    .anyMatch(sample -> InventoryUtils.findItem(
                                                    inventory,
                                                    stack -> {
                                                        if (stack.isEmpty()) return true;
                                                        return stack.getCount() < stack.getMaxStackSize()
                                                                && ItemStack.isSameItemSameComponents(stack, sample);
                                                    },
                                                    true)
                                            != null);
                        })
                        .min(Comparator.comparingDouble(
                                s -> mc.player.position().distanceToSqr(s.getCenterPosition())))
                        .orElse(null);
            }
            if (currentDischargeActionTarget == null) {
                SchedularSettings.INSTANCE.logNoSuitableContainer();
            } else {
                BlockPos leftPos = currentDischargeActionTarget.getFirst().getPos();
                if (ClientUtils.getScreen(mc) instanceof TileInventory tile
                        && Objects.equals(tile.getContainerPosition(), currentDischargeActionTarget)) {
                    AbstractContainerScreen<?> screen = tile.castHandled();
                    Container topInventory = InventoryUtils.getTopInventory(screen);
                    Container downInventory = InventoryUtils.getBottomInventory(screen);
                    int size = topInventory.getContainerSize();
                    int op = SchedularSettings.INSTANCE.defaultInventoryActionPerTick.get();
                    boolean hasOp = false;
                    for (int i = 0; i < downInventory.getContainerSize(); ++i) {
                        ItemStack stack = downInventory.getItem(i);
                        if (!stack.isEmpty() && stackPredicate.test(stack)) {
                            if (InventoryUtils.findItem(
                                            topInventory,
                                            item -> item.count() == 0
                                                    || (item.count() < item.getMaxStackSize()
                                                            && ItemStack.isSameItemSameComponents(item, stack)),
                                            true)
                                    != null) {
                                mc.gameMode.handleContainerInput(
                                        screen.getMenu().containerId,
                                        i + size,
                                        0,
                                        ContainerInput.QUICK_MOVE,
                                        mc.player);
                                hasOp = true;
                                if (--op == 0) {
                                    break;
                                }
                            }
                        }
                    }
                    boolean canHasOp = InventoryUtils.findItem(
                                    topInventory,
                                    item -> item.count() == 0 || item.count() < item.getMaxStackSize(),
                                    true)
                            != null;
                    if (canHasOp) {
                        if (!hasOp) {
                            currentDischargeActionTarget = null;
                            screen.onClose();
                        }
                    } else {
                        dischargeSource.remove(currentDischargeActionTarget);
                        currentDischargeActionTarget = null;
                        screen.onClose();
                    }
                } else {
                    pathToOrNearStop(leftPos, 1.5);
                    if (InteractExtra.INSTANCE.isWithinInteractRange(player.position(), leftPos)) {
                        BlockPos pos = leftPos;
                        if (!SequencedActionManager.INSTANCE.isWaitingResponse(pos)) {
                            Interact.INSTANCE.interactBlock(pos);
                        }
                    }
                }
                return State.DISCHARGE.ordinal();
            }
        } else {
            SchedularSettings.INSTANCE.logNoContainer();
        }
        machine.markForEndState();
        if (SchedularSettings.INSTANCE.enableAutoExpandChest.get()
                || SchedularSettings.INSTANCE.enableAutoExpandShulker.get()) {
            SchedularSettings.INSTANCE.logExpandContainer();
            return State.REPLENISH_CHEST_OR_SHULKER.ordinal();
        }
        if (SchedularSettings.INSTANCE.containerSourceHotReload.get()) {
            SchedularSettings.INSTANCE.logReloadContainer();
            return State.NONE.ordinal();
        } else {
            return State.PROCESS.ordinal();
        }
    }

    private boolean canUseToExpandStorage(ItemStack stack) {
        if (SchedularSettings.INSTANCE.enableAutoExpandShulker.get() && isEmptyShulkerBox(stack)) {
            return true;
        }
        if (SchedularSettings.INSTANCE.enableAutoExpandChest.get()
                && stack.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof ChestBlock) {
            return true;
        }
        return false;
    }

    ContainerPosition currentExpansionActionTarget;
    BlockPos currentWaitingOpenContainer;

    public int onStateExpandStorage(StateMachine machine) {
        if (currentExpansionActionTarget != null && !isContainerStillValid(currentExpansionActionTarget)) {
            optionalHasShulkerBoxOrChestSource.remove(currentExpansionActionTarget);
            currentExpansionActionTarget = null;
        }
        if (currentWaitingOpenContainer != null) {
            if (mc.level.getBlockEntity(currentWaitingOpenContainer) != null) {
                if (ClientUtils.getScreen(mc) instanceof TileInventory tileInventory
                        && tileInventory.getContainerPosition() != null
                        && tileInventory.getContainerPosition().contains(currentWaitingOpenContainer)) {
                    currentWaitingOpenContainer = null;
                    dischargeSource.add(tileInventory.getContainerPosition());
                    machine.markForEndState();
                    return State.DISCHARGE.ordinal();
                } else {
                    BlockPos pos = currentWaitingOpenContainer;
                    if (!SequencedActionManager.INSTANCE.isWaitingResponse(pos)) {
                        Interact.INSTANCE.interactBlock(pos);
                    }
                }
                return State.REPLENISH_CHEST_OR_SHULKER.ordinal();
            } else {
                currentWaitingOpenContainer = null;
            }
        }
        IndexEntry<ItemStack> container = InventoryUtils.findPlayerItem(this::canUseToExpandStorage, false, false);
        if (container != null) {
            boolean isShulker = isEmptyShulkerBox(container.val());
            Pair<BlockPos, BlockHitResult> searchPos = (isShulker
                            ? KitReplenish.INSTANCE.searchAvailableShulkerPosition()
                            : KitReplenish.INSTANCE.searchAvailableChestLikePosition(false))
                    .findFirst()
                    .orElse(null);
            if (searchPos != null) {
                Interact.INSTANCE.interactBlock(searchPos.getSecond());
                currentWaitingOpenContainer = searchPos.getFirst();
                machine.markForEndState();
            } else {
                engine.sumitGoal(new GoalDirection(Direction.NORTH));
            }
            machine.markForEndState();
            return State.REPLENISH_CHEST_OR_SHULKER.ordinal();
        } else {
            if (currentExpansionActionTarget != null && !isContainerStillValid(currentExpansionActionTarget)) {
                optionalHasShulkerBoxOrChestSource.remove(currentExpansionActionTarget);
                currentExpansionActionTarget = null;
            }
            if (optionalHasShulkerBoxOrChestSource != null && !optionalHasShulkerBoxOrChestSource.isEmpty()) {
                if (currentExpansionActionTarget == null) {
                    currentExpansionActionTarget = optionalHasShulkerBoxOrChestSource.stream()
                            .min(Comparator.comparingDouble(
                                    s -> s.getCenterPosition().distanceToSqr(mc.player.position())))
                            .orElseThrow();
                }
                if (ClientUtils.getScreen(mc) instanceof TileInventory tileInventory
                        && Objects.equals(tileInventory.getContainerPosition(), currentExpansionActionTarget)) {
                    AbstractContainerScreen<?> screen = tileInventory.castHandled();
                    Container topInventory = InventoryUtils.getTopInventory(screen);
                    int size = topInventory.getContainerSize();
                    boolean hasExpansion = false;
                    for (int i = 0; i < size; i++) {
                        ItemStack stack = topInventory.getItem(i);
                        if (canUseToExpandStorage(stack)) {
                            hasExpansion = true;
                            mc.gameMode.handleContainerInput(
                                    screen.getMenu().containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
                            break;
                        }
                    }
                    if (!hasExpansion) {
                        optionalHasShulkerBoxOrChestSource.remove(currentExpansionActionTarget);
                        currentExpansionActionTarget = null;
                    }
                } else {
                    BlockPos leftPos = currentExpansionActionTarget.getFirst().getPos();
                    pathToOrNearStop(leftPos, 1.5);
                    if (InteractExtra.INSTANCE.isWithinInteractRange(player.position(), leftPos)) {
                        BlockPos pos = leftPos;
                        if (!SequencedActionManager.INSTANCE.isWaitingResponse(pos)) {
                            Interact.INSTANCE.interactBlock(pos);
                        }
                    }
                }
                machine.markForEndState();
                return State.REPLENISH_CHEST_OR_SHULKER.ordinal();
            } else {
                SchedularSettings.INSTANCE.logNoContainer();
                machine.markForEndState();
                return State.PROCESS.ordinal();
            }
        }
    }

    public static IPathGoal pathToOrNearStopGoal(BlockPos pos, double distance) {
        if (pos == null) {
            return null;
        }
        BlockPos stopPos = findNearbyStandableStop(pos);
        if (stopPos != null) {
            if (net.minecraft.world.phys.Vec3.atCenterOf(stopPos).distanceToSqr(mc.player.position())
                    < MathUtils.s2(distance)) {
                return null;
            }
            return new GoalNear(stopPos, distance);
        }
        AABB blockBox = new AABB(pos);
        if (blockBox.distanceToSqr(mc.player.getEyePosition()) < MathUtils.s2(distance)) {
            return null;
        }
        return new GoalNear(pos, distance);
    }

    public void pathToOrNearStop(BlockPos pos, double distance) {
        engine.sumitGoal(pathToOrNearStopGoal(pos, distance));
    }

    @Nullable
    private static BlockPos findNearbyStandableStop(BlockPos targetPos) {
        if (mc.level == null || mc.player == null) {
            return null;
        }
        int maxY = Math.min(mc.level.getMaxY() - 2, targetPos.getY() + PATH_STOP_SEARCH_VERTICAL);
        int minY = mc.level.getMinY();
        if (maxY < minY) {
            return null;
        }
        BlockPos.MutableBlockPos supportPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos standPos = new BlockPos.MutableBlockPos();
        double bestScore = Double.POSITIVE_INFINITY;
        BlockPos bestPos = null;
        for (int radius = 0; radius <= PATH_STOP_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = targetPos.getX() + dx;
                    int z = targetPos.getZ() + dz;
                    for (int y = maxY; y >= minY; y--) {
                        supportPos.set(x, y, z);
                        standPos.set(x, y + 1, z);
                        if (!isValidLandingSpot(supportPos, standPos)) {
                            continue;
                        }
                        double score = net.minecraft.world.phys.Vec3.atCenterOf(standPos)
                                        .distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(targetPos))
                                + radius * 0.01;
                        if (score < bestScore) {
                            bestScore = score;
                            bestPos = standPos.immutable();
                        }
                        break;
                    }
                }
            }
            if (bestPos != null) {
                return bestPos;
            }
        }
        return null;
    }

    private static boolean isValidLandingSpot(BlockPos supportPos, BlockPos standPos) {
        if (mc.level == null || mc.player == null) {
            return false;
        }
        if (supportPos.getY() < mc.level.getMinY() || standPos.getY() >= mc.level.getMaxY()) {
            return false;
        }
        var supportState = mc.level.getBlockState(supportPos);
        if (supportState.isAir() || supportState.liquid()) {
            return false;
        }
        var supportShape = supportState.getCollisionShape(mc.level, supportPos, CollisionContext.of(mc.player));
        if (supportShape.isEmpty()) {
            return false;
        }
        if (!isPassableForPath(standPos)) {
            return false;
        }
        BlockPos abovePos = new BlockPos(standPos.getX(), standPos.getY() + 1, standPos.getZ());
        return isPassableForPath(abovePos);
    }

    private static boolean isPassableForPath(BlockPos pos) {
        if (mc.level == null || mc.player == null) {
            return false;
        }
        var state = mc.level.getBlockState(pos);
        if (state.liquid()) {
            return false;
        }
        return state.getCollisionShape(mc.level, pos, CollisionContext.of(mc.player))
                .isEmpty();
    }

    public boolean canRun() {
        return currentWorkingInstance == null
                || currentWorkingInstance.val() == this
                || currentWorkingInstance.index() < Tasks.getTick() - 10;
    }

    public boolean isPathing() {
        return engine.isPathing();
    }

    public static boolean isCurrentPathing() {
        return currentWorkingInstance != null && currentWorkingInstance.val().isPathing();
    }

    public void disable() {
        enable = false;
        ensureEnd(this);
        reset();
    }

    public void tickPathing(LocalPlayer player) {
        renderCollector.clear();
        if (active != null) {
            enable = active.getAsBoolean();
        }
        if (!enable) {
            disable();
            return;
        }
        if (canRun()) {
            ensureStart(this);
        } else {
            engine.sumitGoal(null);
            return;
        }
        if (this.player != player) {
            this.player = player;
            machine.setState(State.NONE.ordinal());
        }
        if (SchedularSettings.INSTANCE.holdReset.get().isAllPressed()) {
            reset();
            SchedularSettings.INSTANCE.logHoldReset();
        } else if (SchedularSettings.INSTANCE.pause.get()) {
            SchedularSettings.INSTANCE.logPause();
            engine.sumitGoal(null);
        } else {
            machine.step();
        }
        if (SchedularSettings.INSTANCE.enableRender.get()) {
            int replenishColor =
                    SchedularSettings.INSTANCE.colorReplenishment.get().withAlpha(255);
            int dischargeColor = SchedularSettings.INSTANCE.colorDischarge.get().withAlpha(255);
            int supportColor =
                    SchedularSettings.INSTANCE.colorShulkerSupport.get().withAlpha(255);
            int goalColor = SchedularSettings.INSTANCE.colorGoal.get().withAlpha(255);
            for (var pos : replenishSource) {
                renderCollector.submit(pos.getBoundingBox(), replenishColor);
            }
            for (var pos : dischargeSource) {
                renderCollector.submit(pos.getBoundingBox(), dischargeColor);
            }
            for (var pos : optionalHasShulkerBoxOrChestSource) {
                renderCollector.submit(pos.getBoundingBox(), supportColor);
            }
            IPathGoal goal = engine.getCurrentGoal();
            if (goal != null) {
                net.minecraft.world.phys.Vec3 sample = goal.sample();
                if (sample != null) {
                    renderCollector.submit(new AABB(sample.add(-0.4, 0, -0.4), sample.add(0.4, 0.8, 0.4)), goalColor);
                }
            }
        }
    }

    public void renderPathing(Event<Render3D> event) {
        if (!SchedularSettings.INSTANCE.enableRender.get()) {
            return;
        }
        RenderUtils.startDrawVirtual(event.context.stack());
        try {
            renderCollector.render3D(event.context.stack(), event.context.partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(event.context.stack());
        }
    }

    static enum State {
        NONE,
        INITIALIZE_EXPLORE,
        REPLENISH,
        PROCESS,
        DISCHARGE,
        REPLENISH_CHEST_OR_SHULKER;
    }

    public interface PathingEngine {
        void tick();

        void sumitGoal(IPathGoal pos);

        IPathGoal getCurrentGoal();

        void start();

        void end();

        boolean isPathing();

        void setCanMine(boolean canMine);
    }

    public static class NonePathingEngine implements PathingEngine {
        IPathGoal currentGoal = null;
        boolean canMine;

        @Override
        public void tick() {}

        @Override
        public void sumitGoal(IPathGoal pos) {}

        @Override
        public IPathGoal getCurrentGoal() {
            return currentGoal;
        }

        @Override
        public void start() {}

        @Override
        public void end() {}

        @Override
        public boolean isPathing() {
            return false;
        }

        @Override
        public void setCanMine(boolean canMine) {
            this.canMine = canMine;
        }
    }

    public static class BaritonePathingEngine implements PathingEngine {
        IPathGoal lastSumitGoal = null;
        boolean canMine = false;
        boolean storedValue;
        boolean started = false;

        @Override
        public void tick() {}

        @Override
        public void sumitGoal(IPathGoal pos) {
            if (pos != null && pos.isInGoal(mc.player.position())) {
                pos = null;
            }
            if (Objects.equals(pos, lastSumitGoal)) {
                if ((pos != null) == BaritoneHooks.getInstance().isBaritoneGoalPathingActive()) {
                    return;
                }
            }
            lastSumitGoal = pos;
            BaritoneHooks.getInstance().setBaritoneCurrentGoal(pos);
        }

        @Override
        public IPathGoal getCurrentGoal() {
            return lastSumitGoal;
        }

        @Override
        public void start() {
            if (!started) {
                started = true;
                ValueAccessor<Boolean> bl = BaritoneHooks.getInstance().getSetting("allowBreak");
                storedValue = bl.getValue();
                bl.setValue(canMine);
            }
        }

        @Override
        public void end() {
            if (started) {
                started = false;
                ValueAccessor<Boolean> bl = BaritoneHooks.getInstance().getSetting("allowBreak");
                bl.setValue(storedValue);
            }
        }

        @Override
        public boolean isPathing() {
            return lastSumitGoal != null && BaritoneHooks.getInstance().isBaritoneGoalPathingActive();
        }

        @Override
        public void setCanMine(boolean canMine) {
            this.canMine = canMine;
        }
    }
}
