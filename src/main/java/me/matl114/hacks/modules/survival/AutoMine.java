package me.matl114.hacks.modules.survival;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.hacks.MineTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.mine.MineExtra;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.enums.MineTargetingMode;
import me.matl114.hacks.utils.move.PathingSchedular;
import me.matl114.hacks.utils.move.goal.GoalNear;
import me.matl114.hacks.utils.move.goal.IPathGoal;
import me.matl114.managers.Configs;
import me.matl114.managers.config.ConfigEnum;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.AttributeUtils;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.ItemStackUtils;
import me.matl114.utils.MathUtils;
import me.matl114.utils.RegistryUtils;
import me.matl114.utils.WorldUtils;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.api.VPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class AutoMine extends BaseModule {
    private static final int DEFAULT_MAX_INSTANT_MINE = 30;
    private static final int SEARCH_VERTICAL_MARGIN = 2;
    private static final int DURABILITY_MULTIPLY = 4;
    private static final int MIN_DURABILITY_LIMIT = 9;

    private final PathingSchedular pathingSchedular = new PathingSchedular();
    private BatchPhase batchPhase = BatchPhase.PREPARE;
    private BlockPos anchorStandPos;
    private List<BlockPos> route = Collections.emptyList();
    private int routeIndex;
    private int collectRouteIndex;
    private BlockPos lastMinePos;
    private final Set<UUID> lockedCollectDrops = new HashSet<>();

    public AutoMine() {
        super("AutoMine");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "survival-mine-utils.auto-mine");
    public final FlagRef enable = flagBuilder(root.addEnable()).build();
    public final KeyBindRef hotkey =
            moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final EnumRef<Mode> mode =
            builder(root.add("mode"), Mode.class).defaultValue(Mode.BATCH).build();

    public final FlagRef enableBaritone =
            flagBuilder(root.add("enable-baritone")).defaultValue(true).build();

    public final NBTRef<EntrySet<Block>> blockWhitelist = builder(root.add("whitelist"), EntrySet.<Block>parameter())
            .defaultValue(new EntrySet<>(
                    new Regex("^(sand|red_sand|gravel|clay|dirt|grass_block)$"), BuiltInRegistries.BLOCK))
            .build();

    public final IntRef startDownOffset = intBuilder(root.add("start-down-offset"))
            .defaultValue(3)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef heightSearchLimit = intBuilder(root.add("height-search-limit"))
            .defaultValue(24)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final IntRef horizontalRange = intBuilder(root.add("horizontal-range"))
            .defaultValue(8)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef mineHeight = intBuilder(root.add("mine-height"))
            .defaultValue(6)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final IntRef routeSpacing = intBuilder(root.add("route-spacing"))
            .defaultValue(3)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final DoubleRef collectEnterDistance = doubleBuilder(root.add("collect-enter-distance"))
            .defaultValue(8.0)
            .validator(Configs.doubleRange(0.0D, Double.MAX_VALUE))
            .build();

    public final DoubleRef collectSearchRadius = doubleBuilder(root.add("collect-search-radius"))
            .defaultValue(8.0)
            .validator(Configs.doubleRange(0.0D, Double.MAX_VALUE))
            .build();

    public final FlagRef mineDuringCollect =
            flagBuilder(root.add("mine-during-collect")).defaultValue(true).build();

    public final FlagRef refreshCollectDrops =
            flagBuilder(root.add("refresh-collect-drops")).defaultValue(true).build();

    public final IntRef requiredEmptySlots = intBuilder(root.add("required-empty-slots"))
            .defaultValue(1)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final EnumRef<MineTargetingMode> legalMode = builder(
                    root.add("legal-mode"), MineTargetingMode.class)
            .defaultValue(MineTargetingMode.NO_BYPASS)
            .build();

    public final FlagRef considerCooldown = builder(root.add("consider-cooldown"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef autoSwap =
            flagBuilder(root.add("auto-swap")).defaultValue(true).build();

    public final FlagRef toolProtect =
            flagBuilder(root.add("durability-protect")).defaultValue(true).build();

    public final FlagRef doubleBreak = flagBuilder(root.add("use-double-break")).build();

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
        batchPhase = BatchPhase.PREPARE;
        anchorStandPos = null;
        route = Collections.emptyList();
        routeIndex = 0;
        collectRouteIndex = 0;
        lastMinePos = null;
        lockedCollectDrops.clear();
        pathingSchedular.disable();
    }

    public void onPreInputEvent() {
        if (checkNull() || !enable.get()) {
            return;
        }
        switch (mode.get()) {
            case BATCH -> tickBatch();
            case ACCURATE -> clearState();
        }
    }

    public void onRender(Event<Render3D> event) {
        if (enable.get() && mode.get() == Mode.BATCH) {
            pathingSchedular.renderPathing(event);
        }
    }

    private void tickBatch() {
        ensureBatchPlan();
        updateBatchPhase();
        pathingSchedular.tickPathing(mc.player);
        if (shouldTickMine()) {
            onMineCommon(this::findNextMinePosLayeredDown);
            updateBatchPhase();
        }
    }

    private void ensureBatchPlan() {
        if (anchorStandPos != null) {
            return;
        }
        BlockPos currentStandPos = mc.player.getOnPos().offset(0, 1, 0);
        anchorStandPos =
                new BlockPos(currentStandPos.getX(), resolveTargetStandY(currentStandPos), currentStandPos.getZ());
        route = buildBatchRoute(anchorStandPos);
        routeIndex = 0;
        collectRouteIndex = 0;
        batchPhase = route.isEmpty() ? BatchPhase.FINISHED : BatchPhase.PREPARE;
    }

    private int resolveTargetStandY(BlockPos currentStandPos) {
        int fixedY = Math.max(mc.level.getMinY() + 1, currentStandPos.getY() - startDownOffset.get());
        int minY = Math.max(mc.level.getMinY(), fixedY - heightSearchLimit.get());
        for (int y = fixedY - 1; y >= minY; --y) {
            BlockPos sample = new BlockPos(currentStandPos.getX(), y, currentStandPos.getZ());
            if (!isMineable(mc.level.getBlockState(sample))) {
                return y + 1;
            }
        }
        return fixedY;
    }

    private List<BlockPos> buildBatchRoute(BlockPos center) {
        int range = horizontalRange.get();
        int step = Math.max(1, routeSpacing.get());
        List<Integer> xs = new ArrayList<>();
        List<Integer> zs = new ArrayList<>();
        for (int x = center.getX() - range; x <= center.getX() + range; x += step) {
            xs.add(x);
        }
        if (xs.isEmpty() || xs.get(xs.size() - 1) != center.getX() + range) {
            xs.add(center.getX() + range);
        }
        for (int z = center.getZ() - range; z <= center.getZ() + range; z += step) {
            zs.add(z);
        }
        if (zs.isEmpty() || zs.get(zs.size() - 1) != center.getZ() + range) {
            zs.add(center.getZ() + range);
        }
        List<BlockPos> result = new ArrayList<>();
        boolean reverse = false;
        for (int z : zs) {
            if (!reverse) {
                for (int x : xs) {
                    BlockPos pos = new BlockPos(x, center.getY(), z);
                    if (hasMineableAroundWaypoint(pos)) {
                        result.add(pos);
                    }
                }
            } else {
                for (int i = xs.size() - 1; i >= 0; --i) {
                    BlockPos pos = new BlockPos(xs.get(i), center.getY(), z);
                    if (hasMineableAroundWaypoint(pos)) {
                        result.add(pos);
                    }
                }
            }
            reverse = !reverse;
        }
        if (result.isEmpty()) {
            result.add(center);
        }
        return result;
    }

    private void updateBatchPhase() {
        if (anchorStandPos == null || shouldDischarge()) {
            return;
        }
        switch (batchPhase) {
            case PREPARE -> {
                if (isNear(anchorStandPos, 1.75)) {
                    batchPhase = BatchPhase.MINE;
                }
            }
            case MINE -> {
                advanceRouteIndex();
                if (routeIndex >= route.size()) {
                    if (hasCollectTarget(true)) {
                        enterCollectMode();
                    } else {
                        batchPhase = BatchPhase.FINISHED;
                    }
                    return;
                }
                if (shouldEnterCollectMode()) {
                    enterCollectMode();
                }
            }
            case COLLECT -> {
                if (findCollectTarget(refreshCollectDrops.get()) != null) {
                    return;
                }
                if (collectRouteIndex >= 0) {
                    return;
                }
                advanceRouteIndex();
                if (routeIndex < route.size()) {
                    batchPhase = BatchPhase.MINE;
                } else if (!hasCollectTarget(true)) {
                    batchPhase = BatchPhase.FINISHED;
                }
            }
            case FINISHED -> {
                if (hasCollectTarget(true)) {
                    enterCollectMode();
                }
            }
        }
    }

    private void advanceRouteIndex() {
        while (routeIndex < route.size()) {
            if (hasMineableAroundWaypoint(route.get(routeIndex))) {
                return;
            }
            routeIndex++;
        }
    }

    private boolean shouldEnterCollectMode() {
        if (!hasCollectTarget(true)) {
            return false;
        }
        if (findNextMinePosLayeredDown() != null) {
            return false;
        }
        if (routeIndex >= route.size()) {
            return true;
        }
        return mc.player.position().distanceToSqr(Vec3.atCenterOf(route.get(routeIndex)))
                > MathUtils.s2(collectEnterDistance.get());
    }

    private void enterCollectMode() {
        batchPhase = BatchPhase.COLLECT;
        collectRouteIndex = Math.min(route.size() - 1, Math.max(routeIndex - 1, 0));
        lockedCollectDrops.clear();
        if (!refreshCollectDrops.get()) {
            AABB box = mc.player
                    .getBoundingBox()
                    .inflate(collectSearchRadius.get(), SEARCH_VERTICAL_MARGIN, collectSearchRadius.get());
            for (ItemEntity item : mc.level.getEntities(EntityTypes.ITEM, box, this::isCollectibleDrop)) {
                lockedCollectDrops.add(item.getUUID());
            }
        }
    }

    private boolean shouldTickMine() {
        if (mode.get() != Mode.BATCH || anchorStandPos == null || shouldDischarge()) {
            return false;
        }
        return switch (batchPhase) {
            case MINE -> true;
            case COLLECT -> mineDuringCollect.get();
            default -> false;
        };
    }

    private IPathGoal processGoal() {
        if (mode.get() != Mode.BATCH || anchorStandPos == null) {
            return null;
        }
        return switch (batchPhase) {
            case PREPARE -> PathingSchedular.pathToOrNearStopGoal(anchorStandPos, 1.25);
            case MINE -> processMineGoal();
            case COLLECT -> processCollectGoal();
            case FINISHED -> null;
        };
    }

    private IPathGoal processMineGoal() {
        advanceRouteIndex();
        if (routeIndex >= route.size()) {
            return null;
        }
        return PathingSchedular.pathToOrNearStopGoal(route.get(routeIndex), 1.25);
    }

    private IPathGoal processCollectGoal() {
        ItemEntity target = findCollectTarget(refreshCollectDrops.get());
        if (target != null) {
            return new GoalNear(target.position(), 1.25);
        }
        while (collectRouteIndex >= 0) {
            BlockPos targetPos = route.get(collectRouteIndex);
            if (isNear(targetPos, 1.5)) {
                collectRouteIndex--;
                continue;
            }
            return PathingSchedular.pathToOrNearStopGoal(targetPos, 1.25);
        }
        return null;
    }

    private boolean shouldDischarge() {
        return countEmptyInventorySlots() < requiredEmptySlots.get();
    }

    private int countEmptyInventorySlots() {
        int count = 0;
        for (int i = 0; i < 36; ++i) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private boolean shouldDischargeStack(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        return blockWhitelist.get().test(blockItem.getBlock());
    }

    private boolean isCollectibleDrop(ItemEntity itemEntity) {
        return itemEntity != null
                && !itemEntity.isRemoved()
                && !itemEntity.getItem().isEmpty();
    }

    private boolean hasCollectTarget(boolean dynamic) {
        return findCollectTarget(dynamic) != null;
    }

    private ItemEntity findCollectTarget(boolean dynamic) {
        AABB box = mc.player
                .getBoundingBox()
                .inflate(collectSearchRadius.get(), SEARCH_VERTICAL_MARGIN, collectSearchRadius.get());
        return mc
                .level
                .getEntities(EntityTypes.ITEM, box, item -> {
                    if (!isCollectibleDrop(item)) {
                        return false;
                    }
                    return dynamic || lockedCollectDrops.contains(item.getUUID());
                })
                .stream()
                .min(Comparator.comparingDouble(item -> item.position().distanceToSqr(mc.player.position())))
                .orElse(null);
    }

    private boolean hasMineableAroundWaypoint(BlockPos standPos) {
        return findMinePosAround(standPos, false) != null;
    }

    private BlockPos findNextMinePosLayeredDown() {
        return findMinePosAround(getMiningBasePos(), true);
    }

    private BlockPos getMiningBasePos() {
        return new BlockPos(mc.player.getBlockX(), anchorStandPos.getY(), mc.player.getBlockZ());
    }

    private BlockPos findMinePosAround(BlockPos standPos, boolean requireReachable) {
        for (int y = mineHeight.get() - 1; y >= 0; --y) {
            for (var plate : InteractExtra.INSTANCE.getPlatesAround()) {
                BlockPos targetPos = standPos.offset(plate.x, y, plate.y);
                if (!isInsidePlan(targetPos)) {
                    continue;
                }
                if (requireReachable) {
                    if (!checkDistanceAndCondition(targetPos)) {
                        continue;
                    }
                } else if (!isMineable(mc.level.getBlockState(targetPos))) {
                    continue;
                }
                return targetPos;
            }
        }
        return null;
    }

    private boolean isInsidePlan(BlockPos pos) {
        if (anchorStandPos == null) {
            return false;
        }
        return Math.abs(pos.getX() - anchorStandPos.getX()) <= horizontalRange.get()
                && Math.abs(pos.getZ() - anchorStandPos.getZ()) <= horizontalRange.get()
                && pos.getY() >= anchorStandPos.getY()
                && pos.getY() < anchorStandPos.getY() + mineHeight.get();
    }

    private boolean isMineable(BlockState state) {
        if (state == null || state.isAir() || state.liquid()) {
            return false;
        }
        Block block = state.getBlock();
        return block.defaultDestroyTime() >= 0.0F && blockWhitelist.get().test(block);
    }

    private boolean checkDistanceAndCondition(BlockPos newPos) {
        if (newPos == null) {
            return false;
        }
        var access = PlayerInteractionAccess.of(mc.gameMode);
        if (Objects.equals(access.getCurrentFailBreakPos(), newPos)) {
            return false;
        }
        if (!isInsidePlan(newPos)) {
            return false;
        }
        if (!isMineable(mc.level.getBlockState(newPos))) {
            return false;
        }
        return !MineTasks.distanceOutOfReach(newPos, mc.player.getEyePosition());
    }

    private int onMineCommon(Supplier<BlockPos> posFinder) {
        int tryMine = 0;
        Vec2 originPy = new Vec2(mc.player.getXRot(), mc.player.getYRot());
        do {
            if (!checkDistanceAndCondition(lastMinePos)) {
                lastMinePos = posFinder.get();
            }
            if (lastMinePos == null) {
                break;
            }
            if (considerCooldown.get() && MineExtra.INSTANCE.getMiningPacketCooldown(1) > 0) {
                break;
            }
            PlayerInteractionAccess.of(mc.gameMode).setMiningCooldown(0);

            BlockState mineState = mc.level.getBlockState(lastMinePos);
            IndexEntry<ItemStack> bestStack = autoSwap.get()
                    ? InventoryUtils.findBestPlayerItem(
                            stack -> {
                                if (isDurabilityOk(stack)) {
                                    return (double) WorldUtils.getPlayerBlockBreakingSpeedWithCanMineMultiply(
                                            mc.player, mineState, stack);
                                }
                                return null;
                            },
                            true,
                            true)
                    : InventoryUtils.getSelectedItem();
            if (bestStack == null || !isDurabilityOk(bestStack.val())) {
                if (toolProtect.get()) {
                    enable.set(false);
                    break;
                } else {
                    bestStack = InventoryUtils.getSelectedItem();
                }
            }
            InvExtra.INSTANCE.swapItemToHand(bestStack.index(), false, GhostHandMode.INV_SWAP);
            float speed = MineExtra.INSTANCE.predictBlockBreakingSpeedAt(lastMinePos);
            tryMine += 1;
            Vec3 shouldFacing = Vec3.atCenterOf(lastMinePos).subtract(mc.player.getEyePosition());
            Direction dir = Direction.getApproximateNearest(shouldFacing).getOpposite();
            switch (legalMode.get()) {
                case SWING_HAND_AND_ROT -> {
                    Vec3 rotate2f = mc.player.getLookAngle();
                    Vec3 rotateXZ = new Vec3(rotate2f.x, 0, rotate2f.z);
                    if (rotateXZ.dot(shouldFacing) < 0) {
                        PlayerStateManager.setPlayerYawSafe(mc.player, mc.player.getYRot() + 180);
                        mc.getConnection()
                                .send(VPacket.newLookAndOnGround(
                                        mc.player.getYRot(),
                                        mc.player.getXRot(),
                                        mc.player.onGround(),
                                        mc.player.horizontalCollision));
                    }
                }
                case SWING_HAND_AND_TARGET -> {
                    Vec3 facing = shouldFacing.normalize();
                    Vec2 pitchYaw = EntityUtils.rotationToPitchYaw(facing);
                    if (Math.abs(EntityUtils.getSafeYawDiff(mc.player.getYRot(), pitchYaw.y)) > 30) {
                        mc.player.setXRot(pitchYaw.x);
                        mc.player.setYRot(pitchYaw.y);
                        mc.getConnection()
                                .send(VPacket.newLookAndOnGround(
                                        mc.player.getYRot(),
                                        mc.player.getXRot(),
                                        mc.player.onGround(),
                                        mc.player.horizontalCollision));
                    }
                }
            }
            mc.gameMode.continueDestroyBlock(lastMinePos, dir);
            if (legalMode.get().hasSwing()) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
            if (!MineExtra.INSTANCE.shouldTreatAsInstantBreak(speed)) {
                var access = PlayerInteractionAccess.of(mc.gameMode);
                if (doubleBreak.get()
                        && Objects.equals(access.getCurrentMiningPos(), lastMinePos)
                        && access.isFailBreakEmpty()) {
                    access.sendFailBreakCurrentPos(null);
                } else {
                    break;
                }
            }
        } while (!mc.gameMode.isDestroying() && tryMine < DEFAULT_MAX_INSTANT_MINE);
        if (mc.player.getXRot() != originPy.x || mc.player.getYRot() != originPy.y) {
            mc.player.setXRot(originPy.x);
            mc.player.setYRot(originPy.y);
            ClientPlayerAccess.of(mc.player).resyncRot();
        }
        return tryMine;
    }

    private boolean isDurabilityOk(ItemStack item) {
        if (item.count() == 0) {
            return true;
        }
        int durabilityLimit;
        if (item.get(DataComponents.UNBREAKABLE) != null) {
            durabilityLimit = 0;
        } else if (item.get(DataComponents.MAX_DAMAGE) != null) {
            Holder<Enchantment> unbreaking =
                    RegistryUtils.getRegistryEntry(ItemStackUtils.registry(), Enchantments.UNBREAKING);
            int multiply = 1;
            if (unbreaking != null) {
                multiply = EnchantmentHelper.getItemEnchantmentLevel(unbreaking, item) + 1;
            }
            durabilityLimit = (DURABILITY_MULTIPLY * 2) / multiply;
        } else {
            return true;
        }
        int max = Math.max(MIN_DURABILITY_LIMIT, durabilityLimit);
        return item.getDamageValue() <= item.getMaxDamage() - max;
    }

    private boolean isNear(BlockPos pos, double distance) {
        return pos != null && mc.player.position().distanceToSqr(Vec3.atCenterOf(pos)) <= MathUtils.s2(distance);
    }

    {
        pathingSchedular
                .mine(true)
                .active(this::isPathingEnabled)
                .processGoal(this::processGoal)
                .discharge(this::shouldDischarge)
                .dischargePlayerInventory(this::shouldDischargeStack);
    }

    private boolean isPathingEnabled() {
        return enable.get() && mode.get() == Mode.BATCH && enableBaritone.get() && batchPhase != BatchPhase.FINISHED;
    }

    public enum Mode implements ConfigEnum {
        BATCH,
        ACCURATE;

        @Override
        public String getConfigEnumType() {
            return "auto_mine_pathing_mode";
        }
    }

    private enum BatchPhase {
        PREPARE,
        MINE,
        COLLECT,
        FINISHED
    }
}
