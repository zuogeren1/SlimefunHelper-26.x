package me.matl114.hacks;

import com.google.common.util.concurrent.Runnables;
import com.mojang.datafixers.util.Pair;
import java.util.*;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import lombok.Getter;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.catchers.PacketCatcherImpl;
import me.matl114.events.impl.UseItemOnBlock;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.hacks.api.ModuleManager;
import me.matl114.hacks.modules.HackModules;
import me.matl114.hacks.modules.interact.*;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Tasks;
import me.matl114.utils.*;
import me.matl114.utils.collections.FlagEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.mutable.MutableObject;
import me.matl114.utils.RaycastUtils;
import me.matl114.hacks.utils.EntityUtils;

public class InteractionTasks {
    public static void init() {}

    private static Minecraft mc = Minecraft.getInstance();
    //
    //    public static void placeBlock(int idx, BlockHitResult result){
    //
    //    }

    public static void interactItem(InteractionHand hand, Vec3 rot, boolean realInteract, boolean swingHand) {
        interactItem(hand, EntityUtils.rotationToPitch(rot), EntityUtils.rotationToYaw(rot), realInteract, swingHand);
    }

    public static void interactItem(
            InteractionHand hand, float pitch, float yaw, boolean realInteract, boolean swingHand) {
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
        } else {
            LegacySnapRotManager.INSTANCE.snapAt(pitch, yaw, false);
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

    public static void addPostRotationCorrectTask(Vec3 look3d, Vec3 eyePos, Runnable callback) {
        addPostRotationCorrectTask(look3d.subtract(eyePos), callback);
    }

    public static void addPostRotationCorrectTask(Vec3 rotationVec, Runnable callback) {
        //        RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
        //            RenderTasks.DEBUG_TICK, new RenderTasks.BoxObject(look3d.add(-0.1, -0.1, -0.1), look3d.add(0.1,
        // 0.1, 0.1), Color.MAGENTA)));
        ClientPlayerAccess.of(mc.player)
                .getLegalMovementManager()
                .addMovementModifier(new LegalMovementManager.MovementModifier() {
                    @Override
                    public int priority() {
                        return PRIORITY_LOW;
                    }

                    @Override
                    public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {
                        LocalPlayer player = movementManagerEvent.context.playerStatus.entity;

                        Vec2 rotation =
                                EntityUtils.rotationToPitchYaw(rotationVec.normalize());
                        movementManagerEvent.context.pushImportantRotation(true, true);
                        PlayerStateManager.setPlayerYawSafe(player, rotation.y);
                        EntityUtils.setEntityPitchSafe(player, rotation.x);
                        //                        py = rotation;
                        movementManagerEvent.context.tryMarkForMoveFix();
                        movementManagerEvent.context.markForResetRot();
                        // RenderTasks.drawBox(MathUtils.createBox(look3d, 0.2D), 300, Color.MAGENTA);
                    }

                    @Override
                    public boolean postModify(
                            Event<LegalMovementManager> movementManagerEvent, boolean enabledThisTick) {
                        callback.run();
                        return false;
                    }
                });
    }

    public static Vec3 createBlockRayCastDirection(BlockPos pos) {
        Vec3 bestEyePos = InteractExtra.INSTANCE.getBestInteractEyePos(mc.player.position(), new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        return createBlockRayCastDirection(pos, bestEyePos);
    }

    public static Vec3 createBlockRayCastDirection(BlockPos pos, Vec3 bestEyePos) {
        Vec3 center = Vec3.atCenterOf(pos);
        AABB box = new AABB(pos);
        var raycastDirection1 =
                center.subtract(bestEyePos).normalize().scale(interactExtra.getBlockReachDistance() - 0.09178);
        if (box.clip(bestEyePos, bestEyePos.add(raycastDirection1)).isPresent()) {
            return raycastDirection1.normalize();
        } else {
            AABB shrinkedBox = box.inflate(-1E-7, -1E-7, -1E-7);
            Vec3 targetingPos = MathUtils.magnitudePoint(shrinkedBox, bestEyePos);
            return targetingPos.subtract(bestEyePos).normalize();
        }
    }

    public static void handlePlaceMode(LegalInteractMode mode, BlockHitResult result, InteractionHand hand) {
        handlePlaceMode(mode, result, hand, true);
    }

    public static void handlePlaceMode(
            LegalInteractMode mode, BlockHitResult result, InteractionHand hand, boolean swingHand) {
        Vec3 bestEyePos = InteractExtra.INSTANCE.getBestInteractEyePos(mc.player.position(), result);
        boolean directlyHit = RaycastUtils.canRaycastHit(
                mc.player,
                PlayerStateManager.INSTANCE.lastPitch,
                PlayerStateManager.INSTANCE.lastYaw,
                result.getBlockPos(),
                InteractExtra.INSTANCE.getBlockReachDistance());
        switch (mode) {
            case USEITEM_PACKET -> {
                Vec2 rotation =
                        EntityUtils.rotationToPitchYaw(createBlockRayCastDirection(result.getBlockPos(), bestEyePos)
                                .normalize());
                mc.gameMode.startPrediction(
                        mc.level, (i) -> new ServerboundUseItemPacket(hand, i, rotation.y, rotation.x));
                InteractionTasks.interactBlock(hand, result, swingHand);
            }
            case DELAY_MOVEMENT -> {
                InteractionTasks.interactBlock(hand, result, swingHand);
                if (!directlyHit) {
                    InteractionTasks.addPostRotationCorrectTask(
                            Vec3.atCenterOf(result.getBlockPos()), bestEyePos, Runnables.doNothing());
                }
            }
            case MOVEMENT_POST -> {
                if (!directlyHit) {
                    MutableObject<ServerboundUseItemOnPacket> catcher = new MutableObject<>();
                    Listener.addPrePacketCatcher(new PacketCatcherImpl<>(ServerboundUseItemOnPacket.class, (eve) -> {
                        if (eve.isCancelled()) return true;
                        catcher.setValue(eve.context);
                        eve.cancel();
                        return true;
                    }));
                    InteractionTasks.interactBlock(hand, result, swingHand);
                    if (catcher.getValue() != null) {
                        var pkt = catcher.getValue();
                        InteractionTasks.addPostRotationCorrectTask(
                                Vec3.atCenterOf(result.getBlockPos()), bestEyePos, () -> mc.getConnection()
                                        .send(pkt));
                    }
                } else {
                    InteractionTasks.interactBlock(hand, result, swingHand);
                }
            }
            case LEGACY_SLIENT_ROT -> {
                if (!directlyHit) {
                    Vec2 rotation = EntityUtils.rotationToPitchYaw(
                            createBlockRayCastDirection(result.getBlockPos(), bestEyePos));
                    LegacySnapRotManager.INSTANCE.snapAt(rotation.x, rotation.y, false);
                }
                InteractionTasks.interactBlock(hand, result, swingHand);
            }
            case NONE -> {
                InteractionTasks.interactBlock(hand, result, swingHand);
            }
        }
    }

    public static boolean checkInHead(BlockPos targetPos, Vec3 playerPos) {
        AABB box = AABB.unitCubeFromLowerCorner(Vec3.atLowerCornerOf(targetPos));
        return interactExtra.getPotentialEyeHeights(playerPos).anyMatch(box::contains);
    }

    public static boolean checkPositionPlace(BlockPos pos, Direction face, Vec3 playerPos) {
        Vec3 plateCenter = Vec3.atCenterOf(pos).relative(face, 0.5d);
        Vec3 directionVec = Vec3.atLowerCornerOf(face.getUnitVec3i());
        return interactExtra
                .getPotentialEyeHeights(playerPos)
                .anyMatch(eye -> eye.subtract(plateCenter).dot(directionVec) > 0);
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
                mc.player.getNearestViewDirection(),
                placeTargetBlock,
                targetState,
                enableAirPlace,
                enablePositionPlace);
    }

    public static FlagEntry<BlockHitResult> createSpecificStateHitResult(
            Direction preferredDirection,
            BlockPos placeTargetBlock,
            BlockState targetState,
            boolean enableAirPlace,
            boolean enablePositionPlace) {
        boolean currentSneaking = mc.player.isSecondaryUseActive();
        Set<Direction> availableSides = new HashSet<>(List.of(Direction.values()));
        Block block = targetState.getBlock();
        BlockState currentState = mc.level.getBlockState(placeTargetBlock);
        Vec3 centerPos = Vec3.atCenterOf(placeTargetBlock);
        Vec3 playerFeetPos = mc.player.position();
        double interactRange = interactExtra.getBlockReachDistance();
        List<Direction> order = new ArrayList<>(6);
        FlagEntry<BlockHitResult> result = getDirectReplacingPlacement(
                preferredDirection, placeTargetBlock, currentState, targetState, enablePositionPlace);
        if (result != null && currentSneaking == result.flag()) {
            return result;
        }
        if (block instanceof StairBlock) {
            Half half = targetState.getValue(StairBlock.HALF);
            order.add(half == Half.TOP ? Direction.UP : Direction.DOWN);
            order.addAll(
                    Arrays.asList(new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}));
            for (var direction : order) {
                Vec3 plateCenter = centerPos.relative(direction, 0.5);
                Vec3 interactBlockCenter = centerPos.relative(direction, 1.0D);
                BlockPos targetPos = BlockPos.containing(interactBlockCenter);
                if (!checkInteractRange(targetPos, playerFeetPos, interactRange)) {
                    continue;
                }
                Vec3 interactPos = (direction == Direction.DOWN || direction == Direction.UP)
                        ? plateCenter
                        : plateCenter.add(0, 0.25 * (half == Half.TOP ? 1 : -1), 0);
                if (enableAirPlace) {
                    return new FlagEntry<>(
                            false, new BlockHitResult(interactPos, direction.getOpposite(), placeTargetBlock, false));
                }
                BlockState interactState = mc.level.getBlockState(targetPos);
                if (interactState.isAir() || interactState.liquid() || interactState.canBeReplaced()) {
                    continue;
                }
                boolean mayInteract =
                        InteractUtils.isInteractAcceptable(mc.level, mc.player, targetPos, interactState);
                if (checkInHead(targetPos, playerFeetPos)) {
                    // ?
                    var re = new FlagEntry<>(
                            mayInteract, new BlockHitResult(interactPos, direction.getOpposite(), targetPos, true));
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
                order.add(Direction.UP);
                order.add(Direction.DOWN);
                sgn = 0;
            } else if (type == SlabType.TOP) {
                order.add(Direction.UP);
                sgn = 1;
            } else if (type == SlabType.BOTTOM) {
                order.add(Direction.DOWN);
                sgn = -1;
            } else {
                sgn = 0;
            }
            order.addAll(
                    Arrays.asList(new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}));
            for (var direction : order) {
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
                if (enableAirPlace) {
                    return new FlagEntry<>(
                            false, new BlockHitResult(interactPos, direction.getOpposite(), placeTargetBlock, false));
                }
                if ((interactState.isAir() || interactState.liquid() || interactState.canBeReplaced())) {
                    continue;
                }
                boolean mayInteract =
                        InteractUtils.isInteractAcceptable(mc.level, mc.player, targetPos, interactState);
                if (checkInHead(targetPos, playerFeetPos)) {
                    // ?
                    var re = new FlagEntry<>(
                            mayInteract, new BlockHitResult(interactPos, direction.getOpposite(), targetPos, true));
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
            // 根据 HALF 决定优先的垂直方向
            if (half == Half.BOTTOM) {
                order.add(Direction.DOWN);
                availableSides.remove(Direction.UP); // 不能从上面点击放置下半活板门
            } else {
                order.add(Direction.UP);
                availableSides.remove(Direction.DOWN); // 不能从下面点击放置上半活板门
            }
            // 添加水平方向
            order.addAll(Arrays.asList(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST));

            for (var direction : order) {
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
                if (enableAirPlace) {
                    return new FlagEntry<>(
                            false, new BlockHitResult(interactPos, direction.getOpposite(), placeTargetBlock, false));
                }
                if ((interactState.isAir() || interactState.liquid() || interactState.canBeReplaced())) {
                    continue;
                }
                boolean mayInteract =
                        InteractUtils.isInteractAcceptable(mc.level, mc.player, targetPos, interactState);
                if (checkInHead(targetPos, playerFeetPos)) {
                    // ?
                    var re = new FlagEntry<>(
                            mayInteract, new BlockHitResult(interactPos, direction.getOpposite(), targetPos, true));
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
                        if (currentSneaking == re.flag()) {
                            return re;
                        } else if (result == null) {
                            result = re;
                        }
                    }
                }
            }
        } else {
            // 对特定方块进行方向过滤（仅基于 getSide 的直接使用）
            boolean forceSneak = false;
            if (block instanceof EndRodBlock) {
                Direction targetFacing = targetState.getValue(EndRodBlock.FACING);
                // EndRodBlock: getPlacementState 直接 with(FACING, ctx.getSide())
                availableSides.removeIf(dir -> dir != targetFacing);
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
                        availableSides.removeIf(side -> {
                            if (!side.getAxis().isHorizontal()) return false;
                            Direction opposite = side.getOpposite();
                            BlockPos neighborPos = pos.relative(opposite);
                            BlockState neighborState = mc.level.getBlockState(neighborPos);
                            if (!(neighborState.getBlock() instanceof ChestBlock)) return false;
                            if (neighborState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return false;
                            Direction neighborFacing = neighborState.getValue(ChestBlock.FACING);
                            return neighborFacing.getAxis() != side.getAxis();
                        });
                    } else {
                        // 双箱（此时 canForm 一定为 true）：只保留能精确形成该双箱的水平面
                        final ChestType finalTargetChestType = targetChestType;
                        availableSides.removeIf(side -> {
                            if (!side.getAxis().isHorizontal()) return true;
                            Direction opposite = side.getOpposite();
                            BlockPos neighborPos = pos.relative(opposite);
                            BlockState neighborState = mc.level.getBlockState(neighborPos);
                            if (!(neighborState.getBlock() instanceof ChestBlock)) return true;
                            if (neighborState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return true;
                            Direction neighborFacing = neighborState.getValue(ChestBlock.FACING);
                            if (neighborFacing.getAxis() == side.getAxis()) return true;
                            ChestType finalChestType = (neighborFacing.getCounterClockWise() == side.getOpposite())
                                    ? ChestType.RIGHT
                                    : ChestType.LEFT;
                            Direction finalFacing = neighborFacing;
                            return !(finalFacing == targetFacing && finalChestType == finalTargetChestType);
                        });
                    }
                } else {
                    // 非下蹲：不过滤 availableSides，但单箱时检查是否需 forceSneak
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
                // BellBlock: 在水平方向时，FACING 设置为 ctx.getSide().getOpposite()
                // 垂直方向时 FACING 使用 getHorizontalPlayerFacing，不依赖 getSide
                Direction targetFacing = targetState.getValue(BellBlock.FACING);
                if (targetFacing.getAxis().isHorizontal()) {
                    // 只允许与 targetFacing 相反的方向（因为 with(FACING, direction.getOpposite())）
                    Direction allowedSide = targetFacing.getOpposite();
                    availableSides.removeIf(dir -> dir != allowedSide);
                }
                // 如果 targetFacing 垂直，则保留所有方向（因为垂直时 FACING 不由 getSide 决定）
            } else if (block instanceof LightningRodBlock) {
                Direction targetFacing = targetState.getValue(LightningRodBlock.FACING);
                // LightningRodBlock: 直接 with(FACING, ctx.getSide())
                availableSides.removeIf(dir -> dir != targetFacing);
            } else if (block instanceof ShulkerBoxBlock) {
                Direction targetFacing = targetState.getValue(ShulkerBoxBlock.FACING);
                // ShulkerBoxBlock: 直接 with(FACING, ctx.getSide())
                availableSides.removeIf(dir -> dir != targetFacing);
            } else if (block instanceof HopperBlock) {
                Direction targetFacing = targetState.getValue(HopperBlock.FACING);
                // HopperBlock: getPlacementState 逻辑
                //   direction = ctx.getSide().getOpposite()
                //   if direction.getAxis() == Y -> final = DOWN, else final = direction
                // 因此允许的 getSide 需满足：
                //   如果 targetFacing == DOWN，则允许 UP 或 DOWN
                //   如果 targetFacing 水平，则允许 targetFacing.getOpposite()
                if (targetFacing == Direction.DOWN) {
                    availableSides.removeIf(dir -> dir != Direction.UP && dir != Direction.DOWN);
                } else {
                    Direction direction = targetFacing.getOpposite();
                    availableSides.removeIf(dir -> dir != direction);
                }
            } else if (block instanceof InfestedRotatedPillarBlock) {
                Direction.Axis targetAxis = targetState.getValue(RotatedPillarBlock.AXIS);
                // RotatedInfestedBlock: with(PillarBlock.AXIS, ctx.getSide().getAxis())
                // 允许的方向轴必须等于 targetAxis
                availableSides.removeIf(dir -> dir.getAxis() != targetAxis);
            } else if (block instanceof AmethystClusterBlock) {
                Direction targetFacing = targetState.getValue(AmethystClusterBlock.FACING);
                // AmethystClusterBlock: 直接 with(FACING, ctx.getSide())
                availableSides.removeIf(dir -> dir != targetFacing);
            } else if (block instanceof WallHangingSignBlock) {
                // 注意：WallHangingSignBlock 已经在 else 分支之前单独处理了？这里补充过滤
                // 挂式告示牌不能放在天花板或地板上，且 FACING 由 getSide 的相反方向决定？实际上其 getPlacementState 遍历水平方向
                // 简化：移除垂直方向，水平方向保留所有（因为最终 FACING 由多个因素决定，但 getSide 用于确定方向之一）
                // 由于我们已经在 TrapdoorBlock 之后处理了 WallHangingSignBlock 的过滤（见之前代码），这里不再重复
            } else if (block instanceof FaceAttachedHorizontalDirectionalBlock) {
                AttachFace face = targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
                if (face == AttachFace.WALL) {
                    Direction facing = targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
                    availableSides.removeIf(dir -> dir != facing);
                } else {
                    Direction dir = face == AttachFace.CEILING ? Direction.DOWN : Direction.UP;
                    availableSides.removeIf(direction -> direction != dir);
                }
            }
            // 其他方块不做过滤（保留所有方向）
            Direction dir = preferredDirection;
            if (availableSides.contains(dir.getOpposite())) {
                order.add(dir);
            }
            for (var direction : new Direction[] {
                Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
            }) {
                if (direction != dir && availableSides.contains(direction.getOpposite())) {
                    order.add(direction);
                }
            }
            if (enableAirPlace || (!currentState.isAir() && !currentState.liquid() && currentState.canBeReplaced())) {
                if (checkInteractRange(placeTargetBlock, playerFeetPos, interactRange)) {
                    for (Direction direction : order) {
                        Vec3 plateCenter = centerPos.relative(direction, 0.5);
                        boolean mayInteract = InteractUtils.isInteractAcceptable(
                                mc.level, mc.player, placeTargetBlock, currentState);
                        if (checkInHead(placeTargetBlock, playerFeetPos)) {
                            // ?
                            var re = new FlagEntry<>(
                                    mayInteract,
                                    new BlockHitResult(plateCenter, direction.getOpposite(), placeTargetBlock, true));
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
            if (enableAirPlace
                    && !order.isEmpty()
                    && checkInteractRange(placeTargetBlock, playerFeetPos, interactRange)) {
                Direction availableDirection = order.get(0);
                Vec3 plateCenter = centerPos.relative(availableDirection, 0.5);
                return new FlagEntry<>(
                        forceSneak,
                        new BlockHitResult(plateCenter, availableDirection.getOpposite(), placeTargetBlock, false));
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
                        InteractUtils.isInteractAcceptable(mc.level, mc.player, targetPos, interactState);
                if (checkInHead(targetPos, playerFeetPos)) {
                    // ?
                    var re = new FlagEntry<>(
                            mayInteract || forceSneak,
                            new BlockHitResult(plateCenter, direction.getOpposite(), targetPos, true));
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

    private static FlagEntry<BlockHitResult> getDirectReplacingPlacement(
            Direction preferredDirection,
            BlockPos placeTargetBlock,
            BlockState currentState,
            BlockState targetState,
            boolean enablePositionPlace) {
        if (currentState == null || targetState == null || currentState.isAir() || currentState.liquid()) {
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
                if (!enablePositionPlace && checkPositionPlace(placeTargetBlock, side, playerFeetPos)) {
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

        boolean isWaterState =
                targetState.liquid() && targetState.getFluidState().is(FluidTags.WATER);
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
            } else if (isWaterState) {
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
