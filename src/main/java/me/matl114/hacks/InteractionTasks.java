package me.matl114.hacks;

import java.util.*;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import lombok.Getter;
import me.matl114.events.Listener;
import me.matl114.events.impl.UseItemOnBlock;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.hacks.api.ModuleManager;
import me.matl114.hacks.modules.HackModules;
import me.matl114.hacks.modules.interact.*;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.modules.move.RotationManager;
import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Tasks;
import me.matl114.utils.*;
import me.matl114.utils.collections.FlagEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.InfestedRotatedPillarBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import me.matl114.utils.RaycastUtils;
import me.matl114.hacks.utils.EntityUtils;

public class InteractionTasks {
    public static void init() {}

    private static Minecraft mc = Minecraft.getInstance();
    private static final Set<Block> weirdBoxes = Set.of(Blocks.LECTERN);
    private static final Set<Block> buggyBoxes = Set.of(
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.CHORUS_PLANT,
            Blocks.KELP,
            Blocks.KELP_PLANT,
            Blocks.TWISTING_VINES,
            Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES,
            Blocks.WEEPING_VINES_PLANT,
            Blocks.REDSTONE_WIRE);
    //
    //    public static void placeBlock(int idx, BlockHitResult result){
    //
    //    }

    public static boolean interactItem(InteractionHand hand, boolean realInteract, boolean swingHand) {
        return interactItem(
                hand,
                PlayerStateManager.INSTANCE.lastPitch,
                PlayerStateManager.INSTANCE.lastYaw,
                realInteract,
                swingHand);
    }

    public static boolean interactItem(InteractionHand hand, Vec3 rot, boolean realInteract, boolean swingHand) {
        return interactItem(
                hand, EntityUtils.rotationToPitch(rot), EntityUtils.rotationToYaw(rot), realInteract, swingHand);
    }

    public static boolean interactItem(InteractionHand hand, float pitch, float yaw, boolean realInteract, boolean swingHand) {
        if (realInteract || !ViaFabricPlusHooks.isSupportDupRot()) {
            Vec2 storePY = new Vec2(mc.player.getXRot(), mc.player.getYRot());
            EntityUtils.setEntityPitchSafe(mc.player, pitch);
            PlayerStateManager.setPlayerYawSafe(mc.player, yaw);
            var actionResult = mc.gameMode.useItem(mc.player, hand);
            if (swingHand) {
                InteractUtils.swingHandIfSuccess(actionResult, hand);
            }
            mc.player.setXRot(storePY.x);
            mc.player.setYRot(storePY.y);
            return actionResult.consumesAction();
        } else {
            LegacySnapRotManager.INSTANCE.snapAt(pitch, yaw, false);
            return false;
        }
    }

    public static void interactBlock(InteractionHand hand, BlockHitResult result, boolean swing) {
        Vec2 storePY = new Vec2(mc.player.getXRot(), mc.player.getYRot());
        // use true fucking rotation .
        mc.player.setXRot(PlayerStateManager.INSTANCE.lastPitch);
        mc.player.setYRot(PlayerStateManager.INSTANCE.lastYaw);
        InteractionResult actionResult2 = mc.gameMode.useItemOn(mc.player, hand, result);
        mc.player.setXRot(storePY.x);
        mc.player.setYRot(storePY.y);
        if (swing) {
            InteractUtils.swingHandIfSuccess(actionResult2, hand);
            return;
        }
    }

    public static void interactEntity(Player player, Entity entity, InteractionHand hand, boolean swing) {
        // 26.2: MultiPlayerGameMode 只保留 4 参数的 interact（含 EntityHitResult），
        // 原 interactAt + interact 的两段式已合并。
        InteractionResult result = mc.gameMode.interact(
                player, entity, RaycastUtils.createRealHitResult(entity, player.getEyePosition()), hand);
        if (swing) {
            InteractUtils.swingHandIfSuccess(result, hand);
        }
    }

    public static void addPostRotationCorrectTask(BlockPos pos, Runnable callback) {
        RotationManager.INSTANCE.request(RotationManager.ofBlock(() -> pos).postTick(callback));
    }

    public static Vec3 createBlockRayCastDirection(BlockPos pos) {
        return createBlockRayCastDirection(pos, mc.player.position());
    }

    public static Vec3 createBlockRayCastDirection(BlockPos pos, Vec3 playerPos) {
        return InteractExtra.INSTANCE.getRaycast(playerPos, pos);
    }

    public static boolean handlePlaceMode(LegalInteractMode mode, BlockHitResult result, InteractionHand hand) {
        return handlePlaceMode(mode, result, hand, true);
    }

    public static boolean canHandleInteract(LegalInteractMode mode, BlockHitResult result) {
        if (canDirectHit(result)) {
            return true;
        }
        return switch (mode) {
            case USEITEM_PACKET, LEGACY_SLIENT_ROT, NONE -> true;
            case DELAY_MOVEMENT -> !RotationManager.INSTANCE.isOccupied();
            case MOVEMENT_POST -> false;
        };
    }

    private static boolean canDirectHit(BlockHitResult result) {
        return RaycastUtils.canRaycastHit(
                mc.player,
                PlayerStateManager.INSTANCE.lastPitch,
                PlayerStateManager.INSTANCE.lastYaw,
                result.getBlockPos(),
                InteractExtra.INSTANCE.getBlockReachDistance());
    }

    public static boolean handlePlaceMode(LegalInteractMode mode, BlockHitResult result, InteractionHand hand, boolean swingHand) {
        boolean directlyHit = canDirectHit(result);
        switch (mode) {
            case USEITEM_PACKET -> {
                Vec2 rotation = EntityUtils.rotationToPitchYaw(
                        createBlockRayCastDirection(result.getBlockPos()).normalize());
                mc.gameMode.startPrediction(
                        mc.level, (i) -> new ServerboundUseItemPacket(hand, i, rotation.y, rotation.x));
                InteractionTasks.interactBlock(hand, result, swingHand);
                return true;
            }
            case DELAY_MOVEMENT -> {
                if (!directlyHit) {
                    if (!RotationManager.INSTANCE.request(RotationManager.ofBlock(result::getBlockPos))) {
                        return false;
                    }
                    RotationManager.INSTANCE.startQueuePacket();
                }
                InteractionTasks.interactBlock(hand, result, swingHand);
                return true;
            }
            case MOVEMENT_POST -> {
                if (!directlyHit) {
                    InteractionTasks.addPostRotationCorrectTask(result.getBlockPos(), () -> {
                        InteractionTasks.interactBlock(hand, result, swingHand);
                    });
                    return false;
                } else {
                    InteractionTasks.interactBlock(hand, result, swingHand);
                    return true;
                }
            }
            case LEGACY_SLIENT_ROT -> {
                if (!directlyHit) {
                    Vec2 rotation = EntityUtils.rotationToPitchYaw(createBlockRayCastDirection(result.getBlockPos()));
                    LegacySnapRotManager.INSTANCE.snapAt(rotation.x, rotation.y, false);
                }
                InteractionTasks.interactBlock(hand, result, swingHand);
                return true;
            }
            case NONE -> {
                InteractionTasks.interactBlock(hand, result, swingHand);
                return true;
            }
        }
        throw new IllegalStateException("Unhandled interaction mode: " + mode);
    }

    public static void finishInteractGhostHand(LegalInteractMode mode, Runnable callback) {
        if (callback == null) return;
        if (!mode.canMultiRotPlace()) {
            InvExtra.INSTANCE.pushPostTickGhostHandCallback(callback);
        } else {
            callback.run();
        }
    }

    public static boolean checkInHead(BlockPos targetPos, Vec3 playerPos) {
        AABB box = AABB.unitCubeFromLowerCorner(Vec3.atLowerCornerOf(targetPos));
        return interactExtra.getPotentialEyeHeights(playerPos).anyMatch(box::contains);
    }

    public static boolean isSpecialPositionPlaceState(BlockState state) {
        return state.is(BlockTags.FENCES)
                || state.is(BlockTags.WALLS)
                || state.is(BlockTags.DOORS)
                || state.is(BlockTags.STAIRS)
                || weirdBoxes.contains(state.getBlock())
                || buggyBoxes.contains(state.getBlock());
    }

    public static AABB getPositionPlaceBoundary(BlockPos pos, BlockState state) {
        var shape = state.getShape(mc.level, pos, CollisionContext.of(mc.player));
        if (shape.isEmpty()) {
            shape = state.getCollisionShape(mc.level, pos);
        }
        if (shape.isEmpty()) {
            return new AABB(pos);
        }

        List<AABB> shapeBoxes = shape.toAabbs();
        AABB boundary = new AABB(pos);
        for (AABB localBox : shapeBoxes) {
            AABB box = localBox.move(pos);
            boundary = new AABB(
                    Math.max(boundary.minX, box.minX),
                    Math.max(boundary.minY, box.minY),
                    Math.max(boundary.minZ, box.minZ),
                    Math.min(boundary.maxX, box.maxX),
                    Math.min(boundary.maxY, box.maxY),
                    Math.min(boundary.maxZ, box.maxZ));
        }
        return boundary;
    }

    public static boolean checkPositionPlace(BlockPos pos, Direction face, Vec3 playerPos) {
        Vec3 plateCenter = Vec3.atCenterOf(pos).relative(face, 0.5d);
        BlockState state = mc.level.getBlockState(pos);
        if (!state.isCollisionShapeFullBlock(mc.level, pos) || isSpecialPositionPlaceState(state)) {
            AABB boundary = getPositionPlaceBoundary(pos, state);
            plateCenter = switch (face) {
                case DOWN -> new Vec3(pos.getX() + 0.5, boundary.minY, pos.getZ() + 0.5);
                case UP -> new Vec3(pos.getX() + 0.5, boundary.maxY, pos.getZ() + 0.5);
                case NORTH -> new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, boundary.minZ);
                case SOUTH -> new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, boundary.maxZ);
                case WEST -> new Vec3(boundary.minX, pos.getY() + 0.5, pos.getZ() + 0.5);
                case EAST -> new Vec3(boundary.maxX, pos.getY() + 0.5, pos.getZ() + 0.5);};
        }
        Vec3 directionVec = Vec3.atLowerCornerOf(face.getUnitVec3i());
        Vec3 finalPlaceCenter = plateCenter;
        return interactExtra
                .getPotentialEyeHeights(playerPos)
                .anyMatch(eye -> eye.subtract(finalPlaceCenter).dot(directionVec) > 0);
    }

    public static boolean checkPositionPlace(BlockHitResult hitResult) {
        if (hitResult.isInside()) {
            return checkInHead(hitResult.getBlockPos(), mc.player.position());
        } else {
            return checkPositionPlace(hitResult.getBlockPos(), hitResult.getDirection(), mc.player.position());
        }
    }

    public static BlockHitResult createHitResult(BlockPos pos, Vec3 playerPos) {
        Vec3 lookVec = Vec3.atCenterOf(pos).subtract(playerPos.add(0, mc.player.getEyeHeight(), 0));
        Direction dir = Direction.getApproximateNearest(lookVec.x, lookVec.y, lookVec.z).getOpposite();
        List<Direction> list = new ArrayList<>();
        list.add(dir);
        for (var direction : new Direction[] {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
        }) {
            if (direction != dir) {
                list.add(direction);
            }
        }
        for (var direction : list) {
            if (checkInHead(pos, playerPos) || checkPositionPlace(pos, dir, playerPos)) {
                return new BlockHitResult(Vec3.atCenterOf(pos).relative(direction, 0.5), direction, pos, true);
            }
        }
        return RaycastUtils.createHitResult(pos, dir);
    }

    public static BlockHitResult createHitResult(BlockPos pos, Direction blockFace) {
        Vec3 endVec = Vec3.atCenterOf(pos).relative(blockFace, 0.5);
        return new BlockHitResult(endVec, blockFace, pos, checkInHead(pos, mc.player.position()));
    }

    public static FlagEntry<BlockHitResult> getInteractEntry(BlockHitResult result) {
        boolean mayInteract = InteractUtils.isInteractAcceptable(
                mc.level, mc.player, result.getBlockPos(), mc.level.getBlockState(result.getBlockPos()));
        return new FlagEntry<>(mayInteract, result);
    }
    public static boolean checkInteractRange(BlockPos interactBlockPos, Vec3 playerPos, double range) {
        return interactExtra.isWithinInteractRange(playerPos, interactBlockPos, range);
    }

    public static FlagEntry<BlockHitResult> getPlaceSupportingResult(
            BlockPos blockPos, boolean enableAirPlace, boolean enablePositionPlace) {
        return getPlaceSupportingResult(
                mc.player.position(),
                blockPos,
                mc.player.getNearestViewDirection(),
                enableAirPlace,
                enablePositionPlace);
    }

    public static FlagEntry<BlockHitResult> getPlaceSupportingResult(
            BlockPos blockPos, Direction preferredDirection, boolean enableAirPlace, boolean enablePositionPlace) {
        return getPlaceSupportingResult(
                mc.player.position(), blockPos, preferredDirection, enableAirPlace, enablePositionPlace);
    }

    public static FlagEntry<BlockHitResult> getPlaceSupportingResult(
            Vec3 playerPos, BlockPos blockPos, boolean enableAirPlace, boolean enablePositionPlace) {
        return getPlaceSupportingResult(
                playerPos, blockPos, mc.player.getNearestViewDirection(), enableAirPlace, enablePositionPlace);
    }

    public static FlagEntry<BlockHitResult> getPlaceSupportingResult(
            Vec3 playerPos,
            BlockPos blockPos,
            Direction preferredDirection,
            boolean enableAirPlace,
            boolean enablePositionPlace) {
        return getPlaceSupportingResult(
                playerPos,
                blockPos,
                preferredDirection,
                interactExtra.getBlockReachDistance(),
                enableAirPlace,
                enablePositionPlace);
    }

    public static FlagEntry<BlockHitResult> getPlaceSupportingResult(
            Vec3 playerPos,
            BlockPos blockPos,
            Direction preferredDirection,
            double interactRange,
            boolean enableAirPlace,
            boolean enablePositionPlace) {
        Direction dir = preferredDirection;
        List<Direction> order = new ArrayList<>();
        order.add(dir);
        for (var direction : new Direction[] {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
        }) {
            if (direction != dir) {
                order.add(direction);
            }
        }
        Vec3 centerPos = Vec3.atCenterOf(blockPos);
        if (enableAirPlace) {
            if (!order.isEmpty() && checkInteractRange(blockPos, playerPos, interactRange)) {
                Direction availableDirection = order.get(0);
                Vec3 plateCenter = centerPos.relative(availableDirection, 0.5);
                return new FlagEntry<>(
                        false, new BlockHitResult(plateCenter, availableDirection.getOpposite(), blockPos, false));
            }
        }
        FlagEntry<BlockHitResult> result = null;
        BlockState currentState = mc.level.getBlockState(blockPos);
        if (enableAirPlace || (!currentState.isAir() && !currentState.liquid() && currentState.canBeReplaced())) {
            if (checkInteractRange(blockPos, playerPos, interactRange)) {
                for (Direction direction : order) {
                    Vec3 plateCenter = centerPos.relative(direction, 0.5);
                    boolean mayInteract =
                            InteractUtils.isInteractAcceptable(mc.level, mc.player, blockPos, currentState);
                    if (checkInHead(blockPos, playerPos)) {
                        // ?
                        var re = new FlagEntry<>(
                                mayInteract, new BlockHitResult(plateCenter, direction.getOpposite(), blockPos, true));
                        if (!re.flag()) {
                            return re;
                        } else if (result == null) {
                            result = re;
                        }
                    } else {
                        if (enablePositionPlace || checkPositionPlace(blockPos, direction.getOpposite(), playerPos)) {
                            var re = new FlagEntry<>(
                                    mayInteract,
                                    new BlockHitResult(plateCenter, direction.getOpposite(), blockPos, false));
                            if (!re.flag()) {
                                return re;
                            } else if (result == null) {
                                result = re;
                            }
                        }
                    }
                }
            }
        }
        for (var direction : order) {
            Vec3 plateCenter = centerPos.relative(direction, 0.5);
            Vec3 interactBlockCenter = centerPos.relative(direction, 1.0D);
            BlockPos targetPos = BlockPos.containing(interactBlockCenter);
            if (!checkInteractRange(targetPos, playerPos, interactRange)) {
                continue;
            }
            BlockState interactState = mc.level.getBlockState(targetPos);
            if ((interactState.isAir() || interactState.liquid() || interactState.canBeReplaced())) {
                continue;
            }
            boolean mayInteract =
                    InteractUtils.isInteractAcceptable(mc.level, mc.player, targetPos, interactState);

            if (checkInHead(targetPos, playerPos)) {
                // ?
                var re = new FlagEntry<>(
                        mayInteract, new BlockHitResult(plateCenter, direction.getOpposite(), targetPos, true));
                if (!re.flag()) {
                    return re;
                } else if (result == null) {
                    result = re;
                }
            } else {
                if (enablePositionPlace || checkPositionPlace(targetPos, direction.getOpposite(), playerPos)) {
                    var re = new FlagEntry<>(
                            mayInteract, new BlockHitResult(plateCenter, direction.getOpposite(), targetPos, false));
                    if (!re.flag()) {
                        return re;
                    } else if (result == null) {
                        result = re;
                    }
                }
            }
        }
        return result;
    }

    @Nonnull
    public static List<FlagEntry<BlockHitResult>> getAllPlaceSupportingResult(
            Vec3 playerPos,
            BlockPos blockPos,
            Direction preferredDirection,
            boolean enableAirPlace,
            boolean enablePositionPlace) {
        return getAllPlaceSupportingResult(
                playerPos,
                blockPos,
                preferredDirection,
                interactExtra.getBlockReachDistance(),
                enableAirPlace,
                enablePositionPlace);
    }

    @Nonnull
    public static List<FlagEntry<BlockHitResult>> getAllPlaceSupportingResult(
            Vec3 playerPos,
            BlockPos blockPos,
            Direction preferredDirection,
            double interactRange,
            boolean enableAirPlace,
            boolean enablePositionPlace) {
        List<FlagEntry<BlockHitResult>> result = new ArrayList<>();
        Direction dir = preferredDirection;
        List<Direction> order = new ArrayList<>();
        order.add(dir);
        for (var direction : new Direction[] {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
        }) {
            if (direction != dir) {
                order.add(direction);
            }
        }
        Vec3 centerPos = Vec3.atCenterOf(blockPos);
        BlockState currentState = mc.level.getBlockState(blockPos);
        if (enableAirPlace || (!currentState.isAir() && !currentState.liquid() && currentState.canBeReplaced())) {
            if (checkInteractRange(blockPos, playerPos, interactRange)) {
                for (Direction direction : order) {
                    Vec3 plateCenter = centerPos.relative(direction, 0.5);
                    boolean mayInteract =
                            InteractUtils.isInteractAcceptable(mc.level, mc.player, blockPos, currentState);
                    if (checkInHead(blockPos, playerPos)) {
                        // ?
                        var re = new FlagEntry<>(
                                mayInteract, new BlockHitResult(plateCenter, direction.getOpposite(), blockPos, true));
                        if (!re.flag()) {
                            result.add(re);
                        }
                    } else {
                        if (enablePositionPlace || checkPositionPlace(blockPos, direction.getOpposite(), playerPos)) {
                            var re = new FlagEntry<>(
                                    mayInteract,
                                    new BlockHitResult(plateCenter, direction.getOpposite(), blockPos, false));
                            if (!re.flag()) {
                                result.add(re);
                            }
                        }
                    }
                }
            }
        }

        for (var direction : order) {
            Vec3 plateCenter = centerPos.relative(direction, 0.5);
            Vec3 interactBlockCenter = centerPos.relative(direction, 1.0D);
            BlockPos targetPos = BlockPos.containing(interactBlockCenter);
            if (!checkInteractRange(targetPos, playerPos, interactRange)) {
                continue;
            }
            BlockState interactState = mc.level.getBlockState(targetPos);
            if ((interactState.isAir() || interactState.liquid() || interactState.canBeReplaced())) {
                continue;
            }
            boolean mayInteract =
                    InteractUtils.isInteractAcceptable(mc.level, mc.player, targetPos, interactState);
            if (checkInHead(targetPos, playerPos)) {
                // ?
                result.add(new FlagEntry<>(
                        mayInteract, new BlockHitResult(plateCenter, direction.getOpposite(), targetPos, true)));
            } else {
                if (enablePositionPlace || checkPositionPlace(blockPos, direction.getOpposite(), playerPos)) {
                    result.add(new FlagEntry<>(
                            mayInteract, new BlockHitResult(plateCenter, direction.getOpposite(), targetPos, false)));
                }
            }
        }
        return result;
    }

    public static FlagEntry<BlockHitResult> createSpecificStateHitResult(
            BlockPos placeTargetBlock, BlockState targetState, boolean enableAirPlace, boolean enablePositionPlace) {
        return createSpecificStateHitResult(
                mc.player.getNearestViewDirection(), placeTargetBlock, targetState, enableAirPlace, enablePositionPlace);
    }

    public static FlagEntry<BlockHitResult> createSpecificStateHitResult(
            Direction preferredDirection,
            BlockPos placeTargetBlock,
            BlockState targetState,
            boolean enableAirPlace,
            boolean enablePositionPlace) {
        boolean currentSneaking = mc.player.isSecondaryUseActive();
        Block block = targetState.getBlock();
        BlockState currentState = mc.level.getBlockState(placeTargetBlock);
        if (block instanceof ScaffoldingBlock) {
            return getScaffoldingPlacement(
                    preferredDirection, placeTargetBlock, targetState, enableAirPlace, enablePositionPlace);
        }
        Vec3 centerPos = Vec3.atCenterOf(placeTargetBlock);
        Vec3 playerFeetPos = mc.player.position();
        double interactRange = interactExtra.getBlockReachDistance();
        List<Direction> order = new ArrayList<>(6);
        FlagEntry<BlockHitResult> result = getDirectReplacingPlacement(
                preferredDirection, placeTargetBlock, currentState, targetState, enableAirPlace, enablePositionPlace);
        if (result != null && currentSneaking == result.flag()) {
            return result;
        }
        if (block instanceof StairBlock) {
            Half half = targetState.getValue(StairBlock.HALF);
            for (var direction : new Direction[] {
                Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
            }) {
                if (direction == Direction.DOWN && half == Half.TOP
                        || direction == Direction.UP && half == Half.BOTTOM) {
                    continue;
                }
                Vec3 plateCenter = centerPos.relative(direction, 0.5);
                Vec3 interactBlockCenter = centerPos.relative(direction, 1.0D);
                BlockPos targetPos = BlockPos.containing(interactBlockCenter);
                if (!checkInteractRange(targetPos, playerFeetPos, interactRange)) {
                    continue;
                }
                Vec3 interactPos = (direction == Direction.DOWN || direction == Direction.UP)
                        ? plateCenter
                        : plateCenter.add(0, 0.25 * (half == Half.TOP ? 1 : -1), 0);
                BlockState interactState = mc.level.getBlockState(targetPos);
                if (interactState.isAir() || interactState.liquid() || interactState.canBeReplaced()) {
                    continue;
                }
                boolean mayInteract =
                        InteractUtils.isInteractOnBlockAcceptable(mc.level, mc.player, targetPos, interactState);
                if (checkInHead(targetPos, playerFeetPos)) {
                    // ?
                    var re = new FlagEntry<>(
                            mayInteract, new BlockHitResult(interactPos, direction.getOpposite(), targetPos, true));
                    if (!matchesSpecificPlacementState(placeTargetBlock, targetState, re.val())) {
                        continue;
                    }
                    if (currentSneaking == re.flag()) {
                        return re;
                    } else if (result == null) {
                        result = re;
                    }
                } else {
                    if (enablePositionPlace || checkPositionPlace(targetPos, direction.getOpposite(), playerFeetPos)) {
                        var re = new FlagEntry<>(
                                mayInteract,
                                new BlockHitResult(interactPos, direction.getOpposite(), targetPos, false));
                        if (!matchesSpecificPlacementState(placeTargetBlock, targetState, re.val())) {
                            continue;
                        }
                        if (currentSneaking == re.flag()) {
                            return re;
                        } else if (result == null) {
                            result = re;
                        }
                    }
                }
            }
        } else if (block instanceof SlabBlock) {
            SlabType type = targetState.getValue(SlabBlock.TYPE);
            int sgn;
            if (type == SlabType.DOUBLE) {
                sgn = 0;
            } else if (type == SlabType.TOP) {
                sgn = 1;
            } else if (type == SlabType.BOTTOM) {
                sgn = -1;
            } else {
                sgn = 0;
            }
            for (var direction : new Direction[] {
                Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
            }) {
                if (direction == Direction.UP && type == SlabType.BOTTOM
                        || direction == Direction.DOWN && type == SlabType.TOP) {
                    continue;
                }
                Vec3 plateCenter = centerPos.relative(direction, 0.5);
                Vec3 interactBlockCenter = centerPos.relative(direction, 1.0D);
                BlockPos targetPos = BlockPos.containing(interactBlockCenter);
                if (!checkInteractRange(targetPos, playerFeetPos, interactRange)) {
                    continue;
                }
                // check double condition
                BlockState interactState = mc.level.getBlockState(targetPos);
                // this will make the interactState become DOUBLE
                if (interactState.is(targetState.getBlock())
                        && interactState.getValue(SlabBlock.TYPE) != SlabType.DOUBLE
                        && interactState.getValue(SlabBlock.TYPE) != targetState.getValue(SlabBlock.TYPE)) {
                    continue;
                }
                Vec3 interactPos = (direction == Direction.DOWN || direction == Direction.UP)
                        ? plateCenter
                        : plateCenter.add(0, 0.25 * (double) sgn, 0);
                if ((interactState.isAir() || interactState.liquid() || interactState.canBeReplaced())) {
                    continue;
                }
                boolean mayInteract =
                        InteractUtils.isInteractOnBlockAcceptable(mc.level, mc.player, targetPos, interactState);
                if (checkInHead(targetPos, playerFeetPos)) {
                    // ?
                    var re = new FlagEntry<>(
                            mayInteract, new BlockHitResult(interactPos, direction.getOpposite(), targetPos, true));
                    if (!matchesSpecificPlacementState(placeTargetBlock, targetState, re.val())) {
                        continue;
                    }
                    if (currentSneaking == re.flag()) {
                        return re;
                    } else if (result == null) {
                        result = re;
                    }
                } else {
                    if (enablePositionPlace || checkPositionPlace(targetPos, direction.getOpposite(), playerFeetPos)) {
                        var re = new FlagEntry<>(
                                mayInteract,
                                new BlockHitResult(interactPos, direction.getOpposite(), targetPos, false));
                        if (!matchesSpecificPlacementState(placeTargetBlock, targetState, re.val())) {
                            continue;
                        }
                        if (currentSneaking == re.flag()) {
                            return re;
                        } else if (result == null) {
                            result = re;
                        }
                    }
                }
            }
            // DOUBLE 类型不修改
        } else if (block instanceof TrapDoorBlock) {
            Half half = targetState.getValue(TrapDoorBlock.HALF);
            for (var direction : new Direction[] {
                Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
            }) {
                if (direction == Direction.DOWN && half == Half.TOP
                        || direction == Direction.UP && half == Half.BOTTOM) {
                    continue;
                }
                if (direction.getAxis().isHorizontal()
                        && targetState.getValue(TrapDoorBlock.FACING) != direction.getOpposite()) {
                    continue;
                }
                Vec3 plateCenter = centerPos.relative(direction, 0.5);
                Vec3 interactBlockCenter = centerPos.relative(direction, 1.0);
                BlockPos targetPos = BlockPos.containing(interactBlockCenter);
                if (!checkInteractRange(targetPos, playerFeetPos, interactRange)) {
                    continue;
                }
                BlockState interactState = mc.level.getBlockState(targetPos);
                // 交互点：对于垂直方向使用 plateCenter，对于水平方向需要根据 HALF 调整 Y 偏移
                Vec3 interactPos;
                if (direction == Direction.DOWN || direction == Direction.UP) {
                    interactPos = plateCenter;
                } else {
                    double yOffset = (half == Half.TOP) ? 0.25 : -0.25;
                    interactPos = plateCenter.add(0, yOffset, 0);
                }
                if ((interactState.isAir() || interactState.liquid() || interactState.canBeReplaced())) {
                    continue;
                }
                boolean mayInteract =
                        InteractUtils.isInteractOnBlockAcceptable(mc.level, mc.player, targetPos, interactState);
                if (checkInHead(targetPos, playerFeetPos)) {
                    // ?
                    var re = new FlagEntry<>(
                            mayInteract, new BlockHitResult(interactPos, direction.getOpposite(), targetPos, true));
                    if (!matchesSpecificPlacementState(placeTargetBlock, targetState, re.val())) {
                        continue;
                    }
                    if (currentSneaking == re.flag()) {
                        return re;
                    } else if (result == null) {
                        result = re;
                    }
                } else {
                    if (enablePositionPlace || checkPositionPlace(targetPos, direction.getOpposite(), playerFeetPos)) {
                        var re = new FlagEntry<>(
                                mayInteract,
                                new BlockHitResult(interactPos, direction.getOpposite(), targetPos, false));
                        if (!matchesSpecificPlacementState(placeTargetBlock, targetState, re.val())) {
                            continue;
                        }
                        if (currentSneaking == re.flag()) {
                            return re;
                        } else if (result == null) {
                            result = re;
                        }
                    }
                }
            }
        } else {
            // Validate complete hit results using the target block's placement rules.
            Predicate<BlockHitResult> stateFilter = hit -> true;
            boolean forceSneak = false;
            if (block instanceof BaseTorchBlock || block instanceof LeverBlock) {
                stateFilter = createTorchOrLeverPlacementFilter(placeTargetBlock, targetState);
            } else if (usesPlacementDirections(block)) {
                stateFilter = hit -> matchesDirectionalPlacement(placeTargetBlock, targetState, hit);
            } else if (block instanceof EndRodBlock) {
                Direction targetFacing = targetState.getValue(EndRodBlock.FACING);
                // EndRodBlock: getPlacementState 直接 with(FACING, ctx.getDirection())
                stateFilter = hit -> hit.getDirection() == targetFacing;
            } else if (block instanceof ChestBlock) {
                ChestType targetChestType = targetState.getValue(ChestBlock.TYPE);
                Direction targetFacing = targetState.getValue(ChestBlock.FACING);
                BlockPos pos = placeTargetBlock;

                if (mc.player.isSecondaryUseActive()) {

                    // ---- 预检查：双箱能否形成 ----
                    if (targetChestType != ChestType.SINGLE) {
                        Direction left = targetFacing.getCounterClockWise();
                        Direction right = targetFacing.getClockWise();
                        boolean canForm = false;
                        for (Direction d : new Direction[] {left, right}) {
                            BlockPos neighborPos = pos.relative(d);
                            BlockState neighborState = mc.level.getBlockState(neighborPos);
                            if (neighborState.getBlock() instanceof ChestBlock
                                    && neighborState.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                                    && neighborState.getValue(ChestBlock.FACING) == targetFacing) {
                                canForm = true;
                                break;
                            }
                        }
                        if (!canForm) {
                            targetChestType = ChestType.SINGLE; // 降级
                        }
                    }

                    // ---- 根据是否潜行处理 ----
                    if (targetChestType == ChestType.SINGLE) {
                        // 单箱：排除会导致合并的水平面
                        stateFilter = hit -> {
                            Direction side = hit.getDirection();
                            if (!side.getAxis().isHorizontal()) return true;
                            BlockPos neighborPos = pos.relative(side.getOpposite());
                            BlockState neighborState = mc.level.getBlockState(neighborPos);
                            if (!(neighborState.getBlock() instanceof ChestBlock)) return true;
                            if (neighborState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return true;
                            Direction neighborFacing = neighborState.getValue(ChestBlock.FACING);
                            return neighborFacing.getAxis() == side.getAxis();
                        };
                    } else {
                        // 双箱（此时 canForm 一定为 true）：只保留能精确形成该双箱的水平面
                        final ChestType finalTargetChestType = targetChestType;
                        stateFilter = hit -> {
                            Direction side = hit.getDirection();
                            if (!side.getAxis().isHorizontal()) return false;
                            BlockPos neighborPos = pos.relative(side.getOpposite());
                            BlockState neighborState = mc.level.getBlockState(neighborPos);
                            if (!(neighborState.getBlock() instanceof ChestBlock)) return false;
                            if (neighborState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return false;
                            Direction neighborFacing = neighborState.getValue(ChestBlock.FACING);
                            if (neighborFacing.getAxis() == side.getAxis()) return false;
                            ChestType finalChestType = (neighborFacing.getCounterClockWise() == side.getOpposite())
                                    ? ChestType.RIGHT
                                    : ChestType.LEFT;
                            return neighborFacing == targetFacing && finalChestType == finalTargetChestType;
                        };
                    }
                } else {
                    // 非下蹲：不过滤 stateFilter，但单箱时检查是否需 forceSneak
                    if (targetChestType == ChestType.SINGLE) {
                        Direction left = targetFacing.getCounterClockWise();
                        Direction right = targetFacing.getClockWise();
                        boolean canMerge = false;
                        for (BlockPos neighborPos : new BlockPos[] {pos.relative(left), pos.relative(right)}) {
                            BlockState neighborState = mc.level.getBlockState(neighborPos);
                            if (neighborState.getBlock() instanceof ChestBlock
                                    && neighborState.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                                    && neighborState.getValue(ChestBlock.FACING) == targetFacing) {
                                canMerge = true;
                                break;
                            }
                        }
                        if (canMerge) {
                            forceSneak = true;
                        }
                    }
                }
            } else if (block instanceof BellBlock) {
                // BellBlock: 在水平方向时，FACING 设置为 ctx.getDirection().getOpposite()
                // 垂直方向时 FACING 使用 getHorizontalPlayerFacing，不依赖 getSide
                Direction targetFacing = targetState.getValue(BellBlock.FACING);
                if (targetFacing.getAxis().isHorizontal()) {
                    // 只允许与 targetFacing 相反的方向（因为 with(FACING, direction.getOpposite())）
                    Direction allowedSide = targetFacing.getOpposite();
                    stateFilter = hit -> hit.getDirection() == allowedSide;
                }
                // 如果 targetFacing 垂直，则保留所有方向（因为垂直时 FACING 不由 getSide 决定）
            } else if (block instanceof LightningRodBlock) {
                Direction targetFacing = targetState.getValue(LightningRodBlock.FACING);
                // LightningRodBlock: 直接 with(FACING, ctx.getDirection())
                stateFilter = hit -> hit.getDirection() == targetFacing;
            } else if (block instanceof ShulkerBoxBlock) {
                Direction targetFacing = targetState.getValue(ShulkerBoxBlock.FACING);
                // ShulkerBoxBlock: 直接 with(FACING, ctx.getDirection())
                stateFilter = hit -> hit.getDirection() == targetFacing;
            } else if (block instanceof HopperBlock) {
                Direction targetFacing = targetState.getValue(HopperBlock.FACING);
                // HopperBlock: getPlacementState 逻辑
                //   direction = ctx.getDirection().getOpposite()
                //   if direction.getAxis() == Y -> final = DOWN, else final = direction
                // 因此允许的 getSide 需满足：
                //   如果 targetFacing == DOWN，则允许 UP 或 DOWN
                //   如果 targetFacing 水平，则允许 targetFacing.getOpposite()
                if (targetFacing == Direction.DOWN) {
                    stateFilter = hit -> hit.getDirection() == Direction.UP || hit.getDirection() == Direction.DOWN;
                } else {
                    Direction direction = targetFacing.getOpposite();
                    stateFilter = hit -> hit.getDirection() == direction;
                }
            } else if (block instanceof InfestedRotatedPillarBlock) {
                Direction.Axis targetAxis = targetState.getValue(RotatedPillarBlock.AXIS);
                // InfestedRotatedPillarBlock: with(RotatedPillarBlock.AXIS, ctx.getDirection().getAxis())
                // 允许的方向轴必须等于 targetAxis
                stateFilter = hit -> hit.getDirection().getAxis() == targetAxis;
            } else if (block instanceof AmethystClusterBlock) {
                Direction targetFacing = targetState.getValue(AmethystClusterBlock.FACING);
                // AmethystClusterBlock: 直接 with(FACING, ctx.getDirection())
                stateFilter = hit -> hit.getDirection() == targetFacing;
            }
            // 其他方块不做过滤（保留所有方向）
            Direction dir = preferredDirection;
            order.add(dir);
            for (var direction : new Direction[] {
                Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
            }) {
                if (direction != dir) {
                    order.add(direction);
                }
            }
            if (!currentState.isAir() && !currentState.liquid() && currentState.canBeReplaced()) {
                if (checkInteractRange(placeTargetBlock, playerFeetPos, interactRange)) {
                    for (Direction direction : order) {
                        Vec3 plateCenter = centerPos.relative(direction, 0.5);
                        boolean mayInteract = InteractUtils.isInteractOnBlockAcceptable(
                                mc.level, mc.player, placeTargetBlock, currentState);
                        if (checkInHead(placeTargetBlock, playerFeetPos)) {
                            // ?
                            var re = new FlagEntry<>(
                                    mayInteract,
                                    new BlockHitResult(plateCenter, direction.getOpposite(), placeTargetBlock, true));
                            if (!stateFilter.test(re.val())) {
                                continue;
                            }
                            if (!re.flag()) {
                                return re;
                            } else if (result == null) {
                                result = re;
                            }
                        } else {
                            if (enablePositionPlace
                                    || checkPositionPlace(placeTargetBlock, direction.getOpposite(), playerFeetPos)) {
                                var re = new FlagEntry<>(
                                        mayInteract,
                                        new BlockHitResult(
                                                plateCenter, direction.getOpposite(), placeTargetBlock, false));
                                if (!stateFilter.test(re.val())) {
                                    continue;
                                }
                                if (!re.flag()) {
                                    return re;
                                } else if (result == null) {
                                    result = re;
                                }
                            }
                        }
                    }
                }
            }
            for (var direction : order) {
                Vec3 plateCenter = centerPos.relative(direction, 0.5);
                Vec3 interactBlockCenter = centerPos.relative(direction, 1.0D);
                BlockPos targetPos = BlockPos.containing(interactBlockCenter);
                if (!checkInteractRange(targetPos, playerFeetPos, interactRange)) {
                    continue;
                }
                BlockState interactState = mc.level.getBlockState(targetPos);
                if ((interactState.isAir() || interactState.liquid() || interactState.canBeReplaced())) {
                    continue;
                }
                boolean mayInteract =
                        InteractUtils.isInteractOnBlockAcceptable(mc.level, mc.player, targetPos, interactState);
                if (checkInHead(targetPos, playerFeetPos)) {
                    // ?
                    var re = new FlagEntry<>(
                            mayInteract || forceSneak,
                            new BlockHitResult(plateCenter, direction.getOpposite(), targetPos, true));
                    if (!stateFilter.test(re.val())) {
                        continue;
                    }
                    if (currentSneaking == re.flag()) {
                        return re;
                    } else if (result == null) {
                        result = re;
                    }
                } else {
                    if (enablePositionPlace || checkPositionPlace(targetPos, direction.getOpposite(), playerFeetPos)) {
                        var re = new FlagEntry<>(
                                mayInteract || forceSneak,
                                new BlockHitResult(plateCenter, direction.getOpposite(), targetPos, false));
                        if (!stateFilter.test(re.val())) {
                            continue;
                        }
                        if (currentSneaking == re.flag()) {
                            return re;
                        } else if (result == null) {
                            result = re;
                        }
                    }
                }
            }
        }
        return result;
    }

    private static boolean matchesSpecificPlacementState(
            BlockPos placeTargetBlock, BlockState targetState, BlockHitResult hitResult) {
        if (!(targetState.getBlock().asItem() instanceof BlockItem blockItem)) {
            return false;
        }
        BlockPlaceContext context =
                new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, new ItemStack(blockItem), hitResult);
        context = blockItem.updatePlacementContext(context);
        return context != null
                && context.getClickedPos().equals(placeTargetBlock)
                && targetState.equals(blockItem.getPlacementState(context));
    }

    private static FlagEntry<BlockHitResult> getScaffoldingPlacement(
            Direction preferredDirection,
            BlockPos placeTargetBlock,
            BlockState targetState,
            boolean enableAirPlace,
            boolean enablePositionPlace) {
        Block block = targetState.getBlock();
        // Holding scaffolding makes existing scaffolding replaceable, but the item extends it instead.
        if (mc.level.getBlockState(placeTargetBlock).is(block) || !mc.level.isInWorldBounds(placeTargetBlock)) {
            return null;
        }
        BlockItem item = (BlockItem) block.asItem();
        ItemStack stack = new ItemStack(item);
        Vec3 playerPos = mc.player.position();
        double range = interactExtra.getBlockReachDistance();
        List<Direction> order = new ArrayList<>(6);
        order.add(preferredDirection);
        for (Direction direction : Direction.values()) {
            if (direction != preferredDirection) {
                order.add(direction);
            }
        }
        // Match getPlaceSupportingResult: try every target hit before searching neighbors.
        BlockState currentState = mc.level.getBlockState(placeTargetBlock);
        Vec3 centerPos = Vec3.atCenterOf(placeTargetBlock);
        if ((enableAirPlace || (!currentState.isAir() && !currentState.liquid() && currentState.canBeReplaced()))
                && checkInteractRange(placeTargetBlock, playerPos, range)
                && (mc.player.isSecondaryUseActive()
                        || !InteractUtils.isInteractOnBlockAcceptable(
                                mc.level, mc.player, placeTargetBlock, currentState, stack))) {
            for (Direction direction : order) {
                Direction side = direction.getOpposite();
                BlockHitResult hit = new BlockHitResult(
                        centerPos.relative(direction, 0.5),
                        side,
                        placeTargetBlock,
                        checkInHead(placeTargetBlock, playerPos));
                if (!enablePositionPlace && !checkPositionPlace(hit)) {
                    continue;
                }
                BlockPlaceContext context = new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, stack, hit);
                if (!context.replacingClickedOnBlock() || !context.canPlace()) {
                    continue;
                }
                BlockPlaceContext placement = item.updatePlacementContext(context);
                if (placement != null
                        && placement.getClickedPos().equals(placeTargetBlock)
                        && item.getPlacementState(placement) != null) {
                    return new FlagEntry<>(false, hit);
                }
            }
        }
        for (Direction direction : order) {
            BlockPos interactPos = placeTargetBlock.relative(direction);
            if (!checkInteractRange(interactPos, playerPos, range)) {
                continue;
            }
            BlockState interactState = mc.level.getBlockState(interactPos);
            if (interactState.isAir()
                    || interactState.liquid()
                    || interactState.canBeReplaced()
                    || interactState.getBlock() instanceof ScaffoldingBlock) {
                continue;
            }
            if (!mc.player.isSecondaryUseActive()
                    && InteractUtils.isInteractOnBlockAcceptable(
                            mc.level, mc.player, interactPos, interactState, stack)) {
                continue;
            }
            BlockHitResult hit = new BlockHitResult(
                    centerPos.relative(direction, 0.5),
                    direction.getOpposite(),
                    interactPos,
                    checkInHead(interactPos, playerPos));
            if (!enablePositionPlace && !checkPositionPlace(hit)) {
                continue;
            }
            BlockPlaceContext context = new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, stack, hit);
            if (context.replacingClickedOnBlock()
                    || !context.getClickedPos().equals(placeTargetBlock)
                    || !context.canPlace()) {
                continue;
            }
            BlockPlaceContext placement = item.updatePlacementContext(context);
            if (placement != null
                    && placement.getClickedPos().equals(placeTargetBlock)
                    && item.getPlacementState(placement) != null) {
                return new FlagEntry<>(false, hit);
            }
        }
        // Expand only along the six directions, and only through continuous scaffolding.
        for (Direction direction : order) {
            BlockPos.MutableBlockPos cursor = placeTargetBlock.mutable().move(direction);
            boolean reachedInteractionRange = false;
            // Horizontal extension reaches at most seven blocks; vertical extension has no such limit.
            searchChain:
            for (int distance = 1; mc.level.isInWorldBounds(cursor); ++distance) {
                boolean withinInteractionRange = false;
                BlockState cursorState = null;
                // Try the scaffold itself, then its six neighbors with their face toward the scaffold.
                for (int hitIndex = 0; hitIndex <= order.size(); ++hitIndex) {
                    Direction neighborDirection = hitIndex == 0 ? null : order.get(hitIndex - 1);
                    BlockPos interactPos =
                            neighborDirection == null ? cursor.immutable() : cursor.relative(neighborDirection);
                    if (!checkInteractRange(interactPos, playerPos, range)) {
                        continue;
                    }
                    withinInteractionRange = true;
                    if (cursorState == null) {
                        cursorState = mc.level.getBlockState(cursor);
                        if (!cursorState.is(block)) {
                            break searchChain;
                        }
                    }
                    BlockState interactState = mc.level.getBlockState(interactPos);
                    if (neighborDirection != null) {
                        // Neighbors must be real, non-replaceable supports, never another scaffold.
                        if (interactState.isAir()
                                || interactState.liquid()
                                || interactState.canBeReplaced()
                                || interactState.getBlock() instanceof ScaffoldingBlock) {
                            continue;
                        }
                    }
                    if (!mc.player.isSecondaryUseActive()
                            && InteractUtils.isInteractOnBlockAcceptable(
                                    mc.level, mc.player, interactPos, interactState, stack)) {
                        continue;
                    }
                    boolean inside = checkInHead(interactPos, playerPos);
                    Direction side = neighborDirection == null
                            ? preferredDirection.getOpposite()
                            : neighborDirection.getOpposite();
                    if (neighborDirection == null) {
                        Direction extension = direction.getOpposite();
                        if (mc.player.isSecondaryUseActive()) {
                            side = inside ? extension.getOpposite() : extension;
                        } else if (extension == Direction.UP) {
                            // Any face except UP extends upward; choose one facing the player's eyes.
                            Vec3 view = mc.player.getEyePosition().subtract(Vec3.atCenterOf(interactPos));
                            side = Direction.getApproximateNearest(view.x, Math.min(0.0, view.y), view.z);
                        } else if (extension == mc.player.getDirection()) {
                            side = Direction.UP;
                        } else {
                            continue;
                        }
                    }
                    BlockHitResult hit =
                            new BlockHitResult(Vec3.atCenterOf(interactPos).relative(side, 0.5), side, interactPos, inside);
                    if (!enablePositionPlace && !checkPositionPlace(hit)) {
                        continue;
                    }
                    BlockPlaceContext context = new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, stack, hit);
                    // Self hits replace the clicked block; neighbor hits must offset into the scaffold.
                    if (context.replacingClickedOnBlock() != (neighborDirection == null)) {
                        continue;
                    }
                    // Neighbor hits must resolve to the same scaffold before the item extends it.
                    if (!context.getClickedPos().equals(cursor) || !context.canPlace()) {
                        continue;
                    }
                    // Use the current sneak state; never request sneaking to change the direction.
                    BlockPlaceContext placement = item.updatePlacementContext(context);
                    if (placement == null
                            || !placement.getClickedPos().equals(placeTargetBlock)
                            || item.getPlacementState(placement) == null) {
                        continue;
                    }
                    return new FlagEntry<>(false, hit);
                }
                if (!withinInteractionRange && reachedInteractionRange) {
                    // Early-stop only after checking all seven possible interaction positions.
                    break;
                }
                reachedInteractionRange |= withinInteractionRange;
                if (direction.getAxis().isHorizontal() && distance >= ScaffoldingBlock.STABILITY_MAX_DISTANCE) {
                    break;
                }
                // Before entering range, the chain must still be continuous.
                if (cursorState == null && !mc.level.getBlockState(cursor).is(block)) {
                    break;
                }
                cursor.move(direction);
            }
        }
        return null;
    }

    private static Predicate<BlockHitResult> createTorchOrLeverPlacementFilter(
            BlockPos placeTargetBlock, BlockState targetState) {
        Block block = targetState.getBlock();
        Direction targetSide;
        if (block instanceof LeverBlock) {
            AttachFace face = targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
            if (face == AttachFace.WALL) {
                targetSide = targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
            } else if (face == AttachFace.FLOOR) {
                targetSide = Direction.UP;
            } else {
                targetSide = Direction.DOWN;
            }
        } else if (targetState.hasProperty(HorizontalDirectionalBlock.FACING)) {
            targetSide = targetState.getValue(HorizontalDirectionalBlock.FACING);
        } else {
            targetSide = Direction.UP;
        }
        if (!targetState.canSurvive(mc.level, placeTargetBlock)) {
            return hit -> false;
        }
        BlockState standingTorchState =
                block instanceof BaseTorchBlock && block.asItem() instanceof BlockItem torchItem
                        ? torchItem.getBlock().defaultBlockState()
                        : null;
        for (Direction side : Direction.values()) {
            if (side == targetSide) {
                continue;
            }
            boolean canPlace;
            if (block instanceof LeverBlock) {
                BlockState placementState = block.defaultBlockState();
                if (side.getAxis() == Direction.Axis.Y) {
                    placementState = placementState
                            .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, side == Direction.UP ? AttachFace.FLOOR : AttachFace.CEILING)
                            .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACING));
                } else {
                    placementState = placementState
                            .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL)
                            .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, side);
                }
                canPlace = placementState.canSurvive(mc.level, placeTargetBlock);
            } else if (side == Direction.UP) {
                canPlace = standingTorchState != null && standingTorchState.canSurvive(mc.level, placeTargetBlock);
            } else if (side.getAxis().isHorizontal()) {
                canPlace = WallTorchBlock.canSurvive(mc.level, placeTargetBlock, side);
            } else {
                canPlace = false;
            }
            if (canPlace) {
                return hit -> !hit.getBlockPos().equals(placeTargetBlock) && hit.getDirection() == targetSide;
            }
        }
        return hit -> true;
    }

    private static boolean usesPlacementDirections(Block block) {
        return block instanceof FaceAttachedHorizontalDirectionalBlock
                || block instanceof WallTorchBlock
                || block instanceof RedstoneWallTorchBlock
                || block instanceof WallSkullBlock
                || block instanceof WallSignBlock
                || block instanceof WallHangingSignBlock
                || block instanceof WallBannerBlock
                || block instanceof VineBlock
                || block instanceof TripWireHookBlock
                || block instanceof BaseCoralWallFanBlock
                || block instanceof CocoaBlock
                || block instanceof MultifaceBlock
                || block instanceof LanternBlock
                || block instanceof LadderBlock;
    }

    private static boolean matchesDirectionalPlacement(
            BlockPos placeTargetBlock, BlockState targetState, BlockHitResult hitResult) {
        if (hitResult.getBlockPos().equals(placeTargetBlock)) {
            return false;
        }
        Block block = targetState.getBlock();
        BlockPlaceContext context =
                new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, new ItemStack(block.asItem()), hitResult);
        if (!context.getClickedPos().equals(placeTargetBlock) || !context.canPlace()) {
            return false;
        }
        Direction side = hitResult.getDirection();
        Direction direction = side.getOpposite();
        BlockState placementState = block.defaultBlockState();
        if (block instanceof FaceAttachedHorizontalDirectionalBlock) {
            if (direction.getAxis() == Direction.Axis.Y) {
                // BlockRotate supplies the target horizontal facing for floor/ceiling placement.
                placementState = placementState
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, direction == Direction.UP ? AttachFace.CEILING : AttachFace.FLOOR)
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACING));
            } else {
                placementState = placementState
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL)
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, direction.getOpposite());
            }
            return placementState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)
                    && placementState.getValue(FaceAttachedHorizontalDirectionalBlock.FACING) == targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACING)
                    && placementState.canSurvive(mc.level, placeTargetBlock);
        }
        if (block instanceof LanternBlock) {
            if (direction.getAxis() != Direction.Axis.Y) {
                return false;
            }
            placementState = placementState.setValue(LanternBlock.HANGING, direction == Direction.UP);
            return placementState.getValue(LanternBlock.HANGING).equals(targetState.getValue(LanternBlock.HANGING))
                    && placementState.canSurvive(mc.level, placeTargetBlock);
        }
        if (block instanceof VineBlock) {
            if (direction == Direction.DOWN) {
                return false;
            }
            var property = VineBlock.PROPERTY_BY_DIRECTION.get(direction);
            BlockState currentState = mc.level.getBlockState(placeTargetBlock);
            if (!targetState.getValue(property) || (currentState.is(block) && currentState.getValue(property))) {
                return false;
            }
            if (VineBlock.isAcceptableNeighbour(mc.level, placeTargetBlock.relative(direction), direction)) {
                return true;
            }
            if (direction.getAxis() == Direction.Axis.Y) {
                return false;
            }
            BlockState aboveState = mc.level.getBlockState(placeTargetBlock.above());
            return aboveState.is(block) && aboveState.getValue(property);
        }
        if (block instanceof MultifaceBlock multiface) {
            var property = MultifaceBlock.getFaceProperty(direction);
            BlockState currentState = mc.level.getBlockState(placeTargetBlock);
            return targetState.hasProperty(property)
                    && targetState.getValue(property)
                    && !(currentState.is(block) && currentState.getValue(property))
                    && multiface.getStateForPlacement(currentState, mc.level, placeTargetBlock, direction) != null;
        }
        if (block instanceof WallHangingSignBlock hangingSign) {
            // Its sign plane must be perpendicular to the clicked face's axis.
            Direction facing = targetState.getValue(WallHangingSignBlock.FACING);
            return side.getAxis().isHorizontal()
                    && facing.getAxis() != side.getAxis()
                    && hangingSign.canAttachTo(mc.level, targetState, hitResult.getBlockPos(), side);
        }
        if (!direction.getAxis().isHorizontal()) {
            return false;
        }
        Direction facing = block instanceof CocoaBlock ? direction : direction.getOpposite();
        if (facing != targetState.getValue(HorizontalDirectionalBlock.FACING)) {
            return false;
        }
        placementState = placementState.setValue(HorizontalDirectionalBlock.FACING, facing);
        if (block instanceof WallSkullBlock) {
            return !mc.level.getBlockState(placeTargetBlock.relative(direction)).canBeReplaced(context);
        }
        if (block instanceof LadderBlock && !context.replacingClickedOnBlock()) {
            BlockState supportState = mc.level.getBlockState(placeTargetBlock.relative(direction));
            if (supportState.is(block) && supportState.getValue(LadderBlock.FACING) == side) {
                return false;
            }
        }
        return placementState.canSurvive(mc.level, placeTargetBlock);
    }


    private static FlagEntry<BlockHitResult> getDirectReplacingPlacement(
            Direction preferredDirection,
            BlockPos placeTargetBlock,
            BlockState currentState,
            BlockState targetState,
            boolean enableAirPlace,
            boolean enablePositionPlace) {
        if (currentState == null
                || targetState == null
                || ((currentState.isAir() || currentState.liquid()) && !enableAirPlace)
                || !checkInteractRange(placeTargetBlock, mc.player.position(), interactExtra.getBlockReachDistance())) {
            return null;
        }
        // Let the per-block predicates decide whether direct target hits are allowed.
        if (!currentState.isAir()
                && !currentState.liquid()
                && (usesPlacementDirections(targetState.getBlock())
                        || targetState.getBlock() instanceof BaseTorchBlock)) {
            return null;
        }

        Vec3 playerFeetPos = mc.player.position();
        List<Direction> order = new ArrayList<>(6);
        Direction preferredSide = preferredDirection.getOpposite();
        order.add(preferredSide);
        for (Direction direction : new Direction[] {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
        }) {
            if (direction != preferredSide) {
                order.add(direction);
            }
        }
        FlagEntry<BlockHitResult> result = null;
        boolean inside = checkInHead(placeTargetBlock, playerFeetPos);
        for (Direction side : order) {
            Vec3 hitPos = getDirectReplacingHitPos(placeTargetBlock, currentState, targetState, side);
            BlockHitResult hitResult = new BlockHitResult(hitPos, side, placeTargetBlock, false);
            BlockPlaceContext placementContext = new BlockPlaceContext(
                    mc.player,
                    InteractionHand.MAIN_HAND,
                    new ItemStack(targetState.getBlock().asItem()),
                    hitResult);
            if (!placementContext.replacingClickedOnBlock()) {
                continue;
            }

            if (!inside) {
                if (!enablePositionPlace && !checkPositionPlace(placeTargetBlock, side, playerFeetPos)) {
                    continue;
                }
            }
            hitResult = new BlockHitResult(hitPos, side, placeTargetBlock, inside);
            BlockState placedState =
                    InteractUtils.getBlockPlacement(targetState.getBlock(), mc.player, mc.level, hitResult);
            if (!targetState.equals(placedState)) {
                continue;
            }
            boolean mayInteract =
                    InteractUtils.isInteractAcceptable(mc.level, mc.player, placeTargetBlock, currentState);
            FlagEntry<BlockHitResult> re = new FlagEntry<>(mayInteract, hitResult);
            if (!re.flag()) {
                return re;
            }
            if (result == null) {
                result = re;
            }
        }
        return result;
    }

    private static Vec3 getDirectReplacingHitPos(
            BlockPos placeTargetBlock, BlockState currentState, BlockState targetState, Direction side) {
        Vec3 hitPos = Vec3.atCenterOf(placeTargetBlock).relative(side, 0.5D);
        if (!(currentState.getBlock() instanceof SlabBlock)
                || !currentState.is(targetState.getBlock())
                || !side.getAxis().isHorizontal()) {
            return hitPos;
        }

        SlabType currentType = currentState.getValue(SlabBlock.TYPE);
        if (currentType == SlabType.BOTTOM) {
            return hitPos.add(0.0D, 0.25D, 0.0D);
        }
        if (currentType == SlabType.TOP) {
            return hitPos.add(0.0D, -0.25D, 0.0D);
        }
        return hitPos;
    }

    // here we use real eyePos because this idiot water-place is calculated by server
    public static FlagEntry<Vec2> createLiquidPlacementRaycast(Vec3 eyePos, BlockPos pos, BlockState targetState) {
        if (mc.level == null || mc.player == null) {
            return null;
        }

        boolean isLiquidState = targetState.liquid()
                && (targetState.getFluidState().is(FluidTags.WATER)
                        || targetState.getFluidState().is(FluidTags.LAVA));
        boolean isWaterloggedState =
                !targetState.liquid() && targetState.getFluidState().is(FluidTags.WATER);
        double interactionRange = AttributeUtils.getPlayerBlockInteractionRange(mc.player);

        Direction preferredDirection = mc.player.getNearestViewDirection().getOpposite();
        List<Direction> directions = new ArrayList<>();
        directions.add(preferredDirection);
        for (Direction direction : new Direction[] {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
        }) {
            if (direction != preferredDirection) {
                directions.add(direction);
            }
        }
        FlagEntry<Vec2> result = null;
        for (Direction direction : directions) {
            // definitely can not interact from

            BlockPos interactPos;
            Direction hitSide;
            BlockState hitState;
            boolean sneakFlag;
            if (isWaterloggedState) {
                interactPos = pos;
                hitSide = direction;
                sneakFlag = false;
                hitState = mc.level.getBlockState(interactPos);
            } else if (isLiquidState) {
                interactPos = pos.relative(direction.getOpposite());
                hitSide = direction;
                hitState = mc.level.getBlockState(interactPos);
                sneakFlag = hitState.getBlock() instanceof LiquidBlockContainer fillable
                        && fillable.canPlaceLiquid(
                                mc.player,
                                mc.level,
                                interactPos,
                                hitState,
                                targetState.getFluidState().getType());
            } else {
                continue;
            }
            Vec3 facingDirection = Vec3.atCenterOf(interactPos).subtract(eyePos);

            if (new Vec3(direction.getUnitVec3i()).dot(facingDirection) > 0) {
                continue;
            }
            if (hitState.isAir() || hitState.liquid()) {
                continue;
            }

            for (Vec3 hitPoint : createLiquidPlacementFacePoints(interactPos, hitState, hitSide)) {
                Vec3 look = hitPoint.subtract(eyePos);
                if (look.lengthSqr() < 1.0E-12 || look.lengthSqr() > interactionRange * interactionRange) {
                    continue;
                }

                Vec2 rotation = EntityUtils.rotationToPitchYaw(look.normalize());
                Vec3 rotationVec = EntityUtils.pitchYawToRotation(rotation.x, rotation.y);
                BlockHitResult raycastResult = mc.level.clip(new ClipContext(
                        eyePos,
                        eyePos.add(rotationVec.scale(interactionRange)),
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        mc.player));
                if (raycastResult.getType() != HitResult.Type.BLOCK) {
                    continue;
                }
                if (raycastResult.getBlockPos().equals(interactPos) && raycastResult.getDirection() == hitSide) {
                    var re = new FlagEntry<>(sneakFlag, rotation);
                    if (re.flag() == mc.player.isShiftKeyDown()) {
                        return re;
                    } else if (result == null) {
                        result = re;
                    }
                }
            }
        }
        return result;
    }

    private static List<Vec3> createLiquidPlacementFacePoints(BlockPos pos, BlockState state, Direction side) {
        List<Vec3> points = new ArrayList<>();
        for (AABB localBox : state.getShape(mc.level, pos).toAabbs()) {
            AABB box = localBox.move(pos).inflate(-1.0E-7, -1.0E-7, -1.0E-7);
            if (box.getXsize() <= 0 || box.getYsize() <= 0 || box.getZsize() <= 0) {
                continue;
            }
            addLiquidPlacementFacePoints(points, box, side);
        }
        return points;
    }

    private static void addLiquidPlacementFacePoints(List<Vec3> points, AABB box, Direction side) {
        double minX = box.minX;
        double midX = (box.minX + box.maxX) * 0.5D;
        double maxX = box.maxX;
        double minY = box.minY;
        double midY = (box.minY + box.maxY) * 0.5D;
        double maxY = box.maxY;
        double minZ = box.minZ;
        double midZ = (box.minZ + box.maxZ) * 0.5D;
        double maxZ = box.maxZ;

        switch (side) {
            case DOWN ->
                addLiquidPlacementGrid(points, box.minY, minX, midX, maxX, minZ, midZ, maxZ, Direction.Axis.Y, true);
            case UP ->
                addLiquidPlacementGrid(points, box.maxY, minX, midX, maxX, minZ, midZ, maxZ, Direction.Axis.Y, true);
            case NORTH ->
                addLiquidPlacementGrid(points, box.minZ, minX, midX, maxX, minY, midY, maxY, Direction.Axis.Z, false);
            case SOUTH ->
                addLiquidPlacementGrid(points, box.maxZ, minX, midX, maxX, minY, midY, maxY, Direction.Axis.Z, false);
            case WEST ->
                addLiquidPlacementGrid(points, box.minX, minY, midY, maxY, minZ, midZ, maxZ, Direction.Axis.X, false);
            case EAST ->
                addLiquidPlacementGrid(points, box.maxX, minY, midY, maxY, minZ, midZ, maxZ, Direction.Axis.X, false);
        }
    }

    private static void addLiquidPlacementGrid(
            List<Vec3> points,
            double fixed,
            double minA,
            double midA,
            double maxA,
            double minB,
            double midB,
            double maxB,
            Direction.Axis axis,
            boolean horizontalPlane) {
        if (horizontalPlane) {
            points.add(new Vec3(midA, fixed, midB));
            points.add(new Vec3(minA, fixed, midB));
            points.add(new Vec3(maxA, fixed, midB));
            points.add(new Vec3(midA, fixed, minB));
            points.add(new Vec3(midA, fixed, maxB));
            points.add(new Vec3(minA, fixed, minB));
            points.add(new Vec3(minA, fixed, maxB));
            points.add(new Vec3(maxA, fixed, minB));
            points.add(new Vec3(maxA, fixed, maxB));
            return;
        }

        switch (axis) {
            case X -> {
                points.add(new Vec3(fixed, midA, midB));
                points.add(new Vec3(fixed, minA, midB));
                points.add(new Vec3(fixed, maxA, midB));
                points.add(new Vec3(fixed, midA, minB));
                points.add(new Vec3(fixed, midA, maxB));
                points.add(new Vec3(fixed, minA, minB));
                points.add(new Vec3(fixed, minA, maxB));
                points.add(new Vec3(fixed, maxA, minB));
                points.add(new Vec3(fixed, maxA, maxB));
            }
            case Z -> {
                points.add(new Vec3(midA, midB, fixed));
                points.add(new Vec3(minA, midB, fixed));
                points.add(new Vec3(maxA, midB, fixed));
                points.add(new Vec3(midA, minB, fixed));
                points.add(new Vec3(midA, maxB, fixed));
                points.add(new Vec3(minA, minB, fixed));
                points.add(new Vec3(minA, maxB, fixed));
                points.add(new Vec3(maxA, minB, fixed));
                points.add(new Vec3(maxA, maxB, fixed));
            }
            default -> {}
        }
    }

    private static Entity lastInteractEntity = null;
    private static int lastInteractTimestamp = -1;

    private static void listenInteractEntityPacket(ServerboundInteractPacket packet) {
        // 26.2: ServerboundInteractPacket 只剩交互语义（攻击已拆为 ServerboundAttackPacket），
        // 不再有 Action 多态，故此处无需判断 action 类型。
        lastInteractEntity = mc.level.getEntity(packet.entityId);
        lastInteractTimestamp = Tasks.getTick();
    }

    public static BlockPos predictBlockScreenFrom(Predicate<Block> targetBlock) {
        int timeStamp = Tasks.getTick();
        // 在一秒内反应的 可以考虑
        List<UseItemOnBlock> potentialHit = getSequencedActionManager()
                .getCurrentPendingBlockPlace()
                .filter(s -> {
                    var lastInteact = s.hitResult();
                    return targetBlock.test(
                            mc.level.getBlockState(lastInteact.getBlockPos()).getBlock());
                })
                .toList();
        Optional<UseItemOnBlock> lastInteract =
                potentialHit.isEmpty() ? Optional.empty() : Optional.of(potentialHit.getLast());
        return lastInteract
                .map(UseItemOnBlock::hitResult)
                .map(BlockHitResult::getBlockPos)
                .orElse(null);
    }

    public static Entity predictEntityScreenFrom(Predicate<Entity> targetBlock) {
        int timeStamp = Tasks.getTick();
        // 在一秒内反应的 可以考虑
        if (timeStamp < lastInteractTimestamp + 20
                && lastInteractEntity != null
                && lastInteractEntity.isAlive()
                && targetBlock.test(lastInteractEntity)) {
            return lastInteractEntity;
            // block Type not match,
        }
        return RaycastUtils.rayTraceSpecificEntity(targetBlock).orElse(null);
    }

    @ApiMethod
    @Getter
    public static final ModuleGroup moduleManager = new ModuleGroup("Interaction");

    @Getter
    private static InteractExtra interactExtra;

    @Getter
    private static SequencedActionManager sequencedActionManager;

    @Getter
    private static GuiInteract guiInteract;

    @Getter
    private static AutoClick autoClick;

    @Getter
    private static Interact interact;

    @Getter
    private static Scaffold scaffold;

    @Getter
    private static TpInteract tpInteract;

    @Getter
    private static Airplace airplace;

    @Getter
    private static AutoSurround autoSurround;

    @Getter
    private static SelfTrap selfTrap;

    @Getter
    private static BlockRotate blockRotate;

    @Getter
    private static PrinterRewrite printerRewrite;

    @Getter
    private static NoInteract noInteract;

    @Getter
    private static AutoPlate autoPlate;

    @Getter
    private static AutoSlab autoSlab;

    @Getter
    private static AutoRide autoRide;

    @Getter
    private static AutoEat autoEat;

    @Getter
    private static AutoUse autoUse;

    @Getter
    private static InteractManager interactManager;

    private static void initModules(ModuleManager m) {
        interactExtra = new InteractExtra().register(m);
        sequencedActionManager = new SequencedActionManager().register(m);
        guiInteract = new GuiInteract().register(m);
        autoClick = new AutoClick().register(m);
        interact = new Interact().register(m);
        scaffold = new Scaffold().register(m);
        tpInteract = new TpInteract().register(m);
        airplace = new Airplace().register(m);
        autoSurround = new AutoSurround().register(m);
        selfTrap = new SelfTrap().register(m);
        blockRotate = new BlockRotate().register(m);
        printerRewrite = new PrinterRewrite().register(m);
        autoPlate = new AutoPlate().register(m);
        autoSlab = new AutoSlab().register(m);
        noInteract = new NoInteract().register(m);
        autoRide = new AutoRide().register(m);
        autoEat = new AutoEat().register(m);
        autoUse = new AutoUse().register(m);
        interactManager = new InteractManager().register(m);
    }

    static {
        moduleManager.registerFactories(InteractionTasks::initModules);
        HackModules.registerModuleGroup(moduleManager);
        Listener.registerSinglePacketListener(
                ServerboundInteractPacket.class, InteractionTasks::listenInteractEntityPacket);
    }
}
