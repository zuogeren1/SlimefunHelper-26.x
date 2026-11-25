package me.matl114.hacks.modules.survival;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render3D;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.interact.Interact;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.interact.SequencedActionManager;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.mine.QueueMine;
import me.matl114.hacks.utils.FarmingUtils;
import me.matl114.hacks.utils.move.AdjustmentSchedular;
import me.matl114.hacks.utils.move.PathingSchedular;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.collections.IndexEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//#if MC >= 260300
//$$ import net.minecraft.world.level.block.BonemealSource;
//#endif
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class AutoFarm extends BaseModule {
    private static final int SEARCH_VERTICAL_RADIUS = 4;
    private static final double ACTION_RANGE = 4.5D;
    private static final double DROP_SEARCH_RANGE = 1.5D;

    public AutoFarm() {
        super("AutoFarm");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "survival-interact-utils.auto-farm");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final IntRef delay = intBuilder(root.add("delay"))
            .defaultValue(6)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final IntRef mul = intBuilder(root.add("multiply"))
            .defaultValue(1)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final FlagRef useBoneMeal =
            flagBuilder(root.add("use-bone-meal")).defaultValue(false).build();

    public final IntRef plantingRadius = intBuilder(root.add("planting-radius"))
            .defaultValue(8)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final FlagRef baritoneControl =
            flagBuilder(root.add("baritone-control")).defaultValue(true).build();

    public final FlagRef adjustmentControl =
            flagBuilder(root.add("adjustment-control")).defaultValue(true).build();

    private final PathingSchedular pathingSchedular = new PathingSchedular();
    private final AdjustmentSchedular adjustmentSchedular = new AdjustmentSchedular();
    private final Map<BlockPos, FarmingUtils.PlantType> replantRecords = new HashMap<>();
    private final Set<BlockPos> queuedHarvests = new HashSet<>();

    private FarmTarget target;
    private BlockPos lastReplantAttemptPos;
    private boolean lastReplantAttemptFailed;
    private int lastActionTick = Integer.MIN_VALUE;

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onPreInputEvent);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        clearState();
    }

    private void clearState() {
        target = null;
        replantRecords.clear();
        lastReplantAttemptPos = null;
        lastReplantAttemptFailed = false;
        lastActionTick = Integer.MIN_VALUE;
        pathingSchedular.disable();
    }

    private void onPreInputEvent(Event<Void> event) {
        if (checkNull() || !enable.get()) {
            return;
        }

        refreshRecords();
        target = findTarget();

        if (target != null
                && !pathingSchedular.isPathing()
                && isWithinActionRange(target.pos())
                && lastActionTick + delay.get() <= Tasks.getTick()
                && performTarget(target)) {
            lastActionTick = Tasks.getTick();
        }

        pathingSchedular.tickPathing(mc.player);
        adjustmentSchedular.tickAdjustment(mc.player);
    }

    private void onRender(Event<Render3D> event) {
        if (!enable.get()) {
            return;
        }
        if (baritoneControl.get()) {
            pathingSchedular.renderPathing(event);
        }
        if (adjustmentControl.get()) {
            adjustmentSchedular.renderAdjustment(event);
        }
    }

    private FarmTarget findTarget() {
        FarmTarget replant = findReplantTarget();
        if (replant != null) {
            return replant;
        }

        FarmTarget harvest = findHarvestTarget();
        if (harvest != null) {
            return harvest;
        }

        if (useBoneMeal.get() && hasBoneMeal()) {
            return findBoneMealTarget();
        }
        return null;
    }

    private FarmTarget findReplantTarget() {
        return replantRecords.entrySet().stream()
                .map(entry -> {
                    BlockPos pos = entry.getKey();
                    if (!mc.level.getBlockState(pos).isAir()) {
                        return null;
                    }
                    FarmingUtils.PlantType type = entry.getValue();
                    BlockHitResult hit = FarmingUtils.tryPlantAt(mc.level, pos, type);
                    if (hit == null || findSeed(type) == null) {
                        return null;
                    }
                    return new FarmTarget(pos, type, Action.REPLANT);
                })
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(candidate ->
                        Vec3.atCenterOf(candidate.pos()).distanceToSqr(mc.player.position())))
                .orElse(null);
    }

    private FarmTarget findHarvestTarget() {
        return findCropTarget((type, pos, state) -> type.canHarvest(mc.level, pos), Action.HARVEST);
    }

    private FarmTarget findBoneMealTarget() {
        //#if MC >= 260300
        //$$ return findCropTarget(
        //$$         (type, pos, state) -> !SequencedActionManager.INSTANCE.isWaitingBlockResponse(pos)
        //$$                 && state.getBlock() instanceof BonemealableBlock fertilizable
        //$$                 && fertilizable.isValidBonemealTarget(mc.level, pos, state, BonemealSource.INTERACTION),
        //$$         Action.BONE_MEAL);
        //#else
        return findCropTarget(
                (type, pos, state) -> !SequencedActionManager.INSTANCE.isWaitingBlockResponse(pos)
                        && state.getBlock() instanceof BonemealableBlock fertilizable
                        && fertilizable.isValidBonemealTarget(mc.level, pos, state),
                Action.BONE_MEAL);
        //#endif
    }

    private FarmTarget findCropTarget(CropPredicate predicate, Action action) {
        FarmTarget best = null;
        BlockPos playerPos = mc.player.blockPosition();
        int radius = plantingRadius.get();
        for (int x = playerPos.getX() - radius; x <= playerPos.getX() + radius; x++) {
            for (int z = playerPos.getZ() - radius; z <= playerPos.getZ() + radius; z++) {
                for (int y = playerPos.getY() - SEARCH_VERTICAL_RADIUS;
                        y <= playerPos.getY() + SEARCH_VERTICAL_RADIUS;
                        y++) {
                    BlockPos scannedPos = new BlockPos(x, y, z);
                    FarmingUtils.PlantType scannedType = FarmingUtils.getPlantType(
                            mc.level.getBlockState(scannedPos).getBlock());
                    if (scannedType == null) {
                        continue;
                    }

                    BlockPos plantPos = normalizePlantPos(scannedPos);
                    BlockState state = mc.level.getBlockState(plantPos);
                    FarmingUtils.PlantType type = FarmingUtils.getPlantType(state.getBlock());
                    if (type == null || !predicate.test(type, plantPos, state)) {
                        continue;
                    }
                    FarmTarget candidate = new FarmTarget(plantPos, type, action);
                    if (best == null
                            || Vec3.atCenterOf(candidate.pos()).distanceToSqr(mc.player.position())
                                    < Vec3.atCenterOf(best.pos()).distanceToSqr(mc.player.position())) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private void refreshRecords() {
        Iterator<Map.Entry<BlockPos, FarmingUtils.PlantType>> iterator =
                replantRecords.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, FarmingUtils.PlantType> entry = iterator.next();
            if (!SequencedActionManager.INSTANCE.isWaitingBlockPlaceResponse(entry.getKey())
                    && isReplanted(entry.getKey(), entry.getValue())) {
                if (entry.getKey().equals(lastReplantAttemptPos)) {
                    lastReplantAttemptPos = null;
                    lastReplantAttemptFailed = false;
                }
                iterator.remove();
            }
        }
        if (lastReplantAttemptPos != null && !replantRecords.containsKey(lastReplantAttemptPos)) {
            lastReplantAttemptPos = null;
            lastReplantAttemptFailed = false;
        }
    }

    private boolean performTarget(FarmTarget target) {
        return switch (target.action()) {
            case REPLANT -> replant(target);
            case HARVEST -> harvest(target);
            case BONE_MEAL -> boneMeal(target);
        };
    }

    private boolean harvest(FarmTarget target) {
        if (QueueMine.INSTANCE == null) {
            return false;
        }
        boolean submitted = QueueMine.INSTANCE.sumitMine(target.pos());
        if (submitted) {
            if (target.type().needReplant()) {
                replantRecords.putIfAbsent(target.pos(), target.type());
            }
        }
        return submitted;
    }

    private boolean replant(FarmTarget target) {
        if (Interact.INSTANCE == null) {
            return false;
        }
        IndexEntry<ItemStack> seed = findSeed(target.type());
        if (seed == null) {
            return false;
        }
        Runnable restore = InvExtra.INSTANCE.swapItemToHand(seed.index(), false);
        if (restore == null) {
            return false;
        }

        boolean attempted = false;
        try {
            for (int i = 0; i < mul.get(); i++) {
                BlockHitResult hit = FarmingUtils.tryPlantAt(mc.level, target.pos(), target.type());
                if (hit == null) {
                    break;
                }
                attempted = true;
                if (!Interact.INSTANCE.interactBlock(hit)) {
                    break;
                }
            }
        } finally {
            restore.run();
        }

        if (attempted) {
            lastReplantAttemptPos = target.pos();
            lastReplantAttemptFailed = !isReplanted(target.pos(), target.type());
        }
        return attempted;
    }

    private boolean boneMeal(FarmTarget target) {
        if (Interact.INSTANCE == null) {
            return false;
        }
        IndexEntry<ItemStack> boneMeal =
                InventoryUtils.findPlayerItem(stack -> stack.is(Items.BONE_MEAL), false, false);
        if (boneMeal == null) {
            return false;
        }
        Runnable restore = InvExtra.INSTANCE.swapItemToHand(boneMeal.index(), false);
        if (restore == null) {
            return false;
        }

        boolean attempted = false;
        try {
            if (SequencedActionManager.INSTANCE.isWaitingBlockResponse(target.pos())) {
                return false;
            }
            for (int i = 0; i < mul.get(); i++) {
                BlockState state = mc.level.getBlockState(target.pos());
                //#if MC >= 260300
                //$$ if (!(state.getBlock() instanceof BonemealableBlock fertilizable)
                //$$         || !fertilizable.isValidBonemealTarget(
                //$$                 mc.level, target.pos(), state, BonemealSource.INTERACTION)) {
                //$$     break;
                //$$ }
                //#else
                if (!(state.getBlock() instanceof BonemealableBlock fertilizable)
                        || !fertilizable.isValidBonemealTarget(mc.level, target.pos(), state)) {
                    break;
                }
                //#endif
                attempted = true;
                if (!Interact.INSTANCE.interactBlock(target.pos())) {
                    break;
                }
            }
        } finally {
            restore.run();
        }
        return attempted;
    }

    private IndexEntry<ItemStack> findSeed(FarmingUtils.PlantType type) {
        return InventoryUtils.findPlayerItem(stack -> stack.is(type.getSeedItem()), false, false);
    }

    private boolean hasBoneMeal() {
        return InventoryUtils.findPlayerItem(stack -> stack.is(Items.BONE_MEAL), false, false) != null;
    }

    private boolean isReplanted(BlockPos pos, FarmingUtils.PlantType type) {
        BlockState state = mc.level.getBlockState(pos);
        return type.getRelatedBlocks().contains(state.getBlock()) && !type.canHarvest(mc.level, pos);
    }

    private BlockPos normalizePlantPos(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.is(Blocks.PITCHER_CROP) && state.getValue(PitcherCropBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.below();
        }
        return pos;
    }

    private boolean isWithinActionRange(BlockPos pos) {
        return InteractExtra.INSTANCE != null
                && InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), pos, ACTION_RANGE);
    }

    private Vec3 adjustmentGoal() {
        if (!adjustmentControl.get()
                || !lastReplantAttemptFailed
                || lastReplantAttemptPos == null
                || !replantRecords.containsKey(lastReplantAttemptPos)) {
            return null;
        }
        AABB searchBox = new AABB(lastReplantAttemptPos).inflate(DROP_SEARCH_RANGE, 1.0D, DROP_SEARCH_RANGE);
        ItemEntity item = mc.level
                .getEntities(
                        EntityTypes.ITEM,
                        searchBox,
                        entity -> !entity.isRemoved() && !entity.getItem().isEmpty())
                .stream()
                .min(Comparator.comparingDouble(
                        entity -> entity.distanceToSqr(Vec3.atCenterOf(lastReplantAttemptPos))))
                .orElse(null);
        return item == null ? null : item.position();
    }

    {
        pathingSchedular
                .active(() -> enable.get() && baritoneControl.get() && target != null)
                .processGoal(() -> target == null ? null : PathingSchedular.pathToOrNearStopGoal(target.pos(), 1.25D));
        adjustmentSchedular.adjustRange(1.5D).center(this::adjustmentGoal);
    }

    private record FarmTarget(BlockPos pos, FarmingUtils.PlantType type, Action action) {}

    private enum Action {
        REPLANT,
        HARVEST,
        BONE_MEAL
    }

    @FunctionalInterface
    private interface CropPredicate {
        boolean test(FarmingUtils.PlantType type, BlockPos pos, BlockState state);
    }
}
