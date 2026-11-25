package me.matl114.hacks.modules.interact;

import com.google.common.util.concurrent.Runnables;
import javax.annotation.Nonnull;
import me.matl114.accessors.access.PendingUpdateManagerAccess;
import me.matl114.accessors.access.PlayerInteractBlockC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.modules.move.RotationManager;
import me.matl114.hacks.utils.enums.BypassMode;
import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.hacks.utils.move.Rotation;
import me.matl114.hooks.LitematicaHooks;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.utils.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CalibratedSculkSensorBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.CopperGolemStatueBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DriedGhastBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.GlazedTerracottaBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.InteractUtils;
import me.matl114.hacks.utils.NetworkUtils;
import me.matl114.hacks.utils.RaycastUtils;
import me.matl114.hacks.utils.WorldUtils;

public class BlockRotate extends BaseModule {
    public final ModulePath blockRotate = makePath(Configs.INTERACT_CONFIG, "block-rotate");
    public final ModulePath yawDeceiveSettings = blockRotate.add("yaw-deceive-settings");
    public final ModulePath litematicaFix = blockRotate.add("litematica-shit-fix");
    public final ModulePath placeCorrect = blockRotate.add("simple-placement-correct");
    public static BlockRotate INSTANCE;

    public BlockRotate() {
        super("BlockRotate");
        INSTANCE = this;
    }

    public final FlagRef yawPacketOrderFix =
            flagBuilder(yawDeceiveSettings.add("yaw-packet-order-fix"), true).build();

    public final FlagRef enable2 = flagBuilder(litematicaFix.add("enable")).build();

    public final EnumRef<LegalInteractMode> look1 = builder(litematicaFix.add("rotate-mode"), LegalInteractMode.class)
            .defaultValue(LegalInteractMode.LEGACY_SLIENT_ROT)
            .build();

    public final FlagRef enable4 = flagBuilder(placeCorrect.add("enable")).build();

    public final EnumRef<LegalInteractMode> look2 = builder(placeCorrect.add("rotate-mode"), LegalInteractMode.class)
            .defaultValue(LegalInteractMode.LEGACY_SLIENT_ROT)
            .build();

    // 其它模块仍按旧字段名读写这几项开关，保留以免破坏既有调用点
    public final EnumRef<BypassMode> bypassMode = builder(blockRotate.add("yaw-deceive-bypass-mode"), BypassMode.class)
            .defaultValue(BypassMode.NO_BYPASS)
            .build();

    public final EnumRef<BypassMode> bypassMode2 = builder(
                    blockRotate.add("rotate-bypass-mode"), BypassMode.class)
            .defaultValue(BypassMode.NO_BYPASS)
            .build();

    public final FlagRef legal = flagBuilder(litematicaFix.add("legal-look")).build();

    //    public final FlagRef enable3 =
    //            flagBuilder(litematicaFix.add("enable-easyplace-post-fix")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemOnPacket.class),
                this::onPreSendInteractBlockRotate);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onPresetLoad);
    }

    public void onPresetLoad(Event<EventContainer<ModulePreset>> e) {
        ModulePreset preset = e.context.getValue();
        if (preset.isOptionPreset()) return;
        switch (preset) {
            case AC_GRIM, AC_GRIM_LEGACY -> bypassMode.set(BypassMode.BYPASS_GRIM);
            default -> bypassMode.set(BypassMode.NO_BYPASS);
        }
        switch (preset) {
            case AC_GRIM, AC_GRIM_LEGACY -> bypassMode2.set(BypassMode.BYPASS_GRIM);
            default -> bypassMode2.set(BypassMode.NO_BYPASS);
        }
        switch (preset) {
            case AC_GRIM_LEGACY -> {
                look1.set(LegalInteractMode.LEGACY_SLIENT_ROT);
                look2.set(LegalInteractMode.LEGACY_SLIENT_ROT);
            }
            case AC_GRIM, AC_MATRIX -> {
                look1.set(LegalInteractMode.DELAY_MOVEMENT);
                look2.set(LegalInteractMode.DELAY_MOVEMENT);
            }
            default -> {
                look1.set(LegalInteractMode.NONE);
                look2.set(LegalInteractMode.NONE);
            }
        }
    }

    public boolean handleYawDeceivePlace(
            LegalInteractMode mode,
            BlockHitResult result,
            InteractionHand hand,
            @Nonnull BlockState targetState,
            boolean swingHand) {
        PitchYawDeceive deceive = getPlaceYawDeceive(result, targetState);
        if (deceive == null) {
            return InteractionTasks.handlePlaceMode(mode, result, hand, swingHand);
        }
        return handleYawDeceiveMode(mode, result, hand, swingHand, deceive);
    }

    private boolean handleYawDeceiveMode(
            LegalInteractMode mode,
            BlockHitResult result,
            InteractionHand hand,
            boolean swingHand,
            PitchYawDeceive deceive) {
        if (deceive == null || !deceive.hasDeceive()) {
            return InteractionTasks.handlePlaceMode(mode, result, hand, swingHand);
        }
        // 不能复用 handlePlaceMode：它可能在放置前就发出旋转包，
        // 而 isForce() 的判定基准是当前服务端 pitch/yaw
        if (deceive.isForce()) {
            boolean legacy = ViaFabricPlusHooks.isSupportDupRot() || mode.canMultiRotPlace();
            sendYawDeceiveRotation(legacy, result, hand, deceive);
        }
        // 取 yawDeceive 之后的射线，grim 不会把高版本 useItem 的旋转算进 raycast
        boolean directlyHit = canDirectHit(result);
        if (!directlyHit && !mode.isIn(LegalInteractMode.NONE)) {
            RotationManager.INSTANCE.startQueuePacket();
        }
        InteractionTasks.interactBlock(hand, result, swingHand);
        switch (mode) {
            case LEGACY_SLIENT_ROT -> {
                if (!directlyHit) {
                    LegacySnapRotManager.INSTANCE.snapAt(
                            InteractionTasks.createBlockRayCastDirection(result.getBlockPos()), true);
                    RotationManager.INSTANCE.flushQueuePackets();
                }
                return true;
            }
            case DELAY_MOVEMENT, MOVEMENT_POST -> {
                if (!directlyHit) {
                    InteractionTasks.addPostRotationCorrectTask(result.getBlockPos(), Runnables.doNothing());
                    return false;
                }
                return true;
            }
            case NONE -> {
                return true;
            }
        }
        return true;
    }

    public boolean hasPlaceYawDeceive(BlockHitResult result, @Nonnull BlockState targetState) {
        var deceive = getPlaceYawDeceive(result, targetState);
        return deceive != null && deceive.hasDeceive();
    }

    private PitchYawDeceive getPlaceYawDeceive(BlockHitResult result, @Nonnull BlockState targetState) {
        if (checkNull()) return null;

        BlockPos interactPos = result.getBlockPos();
        BlockState interactState = mc.level.getBlockState(interactPos);
        if (!mc.player.isSecondaryUseActive()
                && InteractUtils.isInteractOnBlockAcceptable(mc.level, mc.player, interactPos, interactState)) {
            return handleYawInteractDeceive(
                    targetState,
                    PlayerStateManager.INSTANCE.lastServerSidePitch,
                    PlayerStateManager.INSTANCE.lastServerSideYaw);
        }

        return handleYawDeceive(
                result,
                targetState,
                PlayerStateManager.INSTANCE.lastServerSidePitch,
                PlayerStateManager.INSTANCE.lastServerSideYaw);
    }

    private static boolean canDirectHit(BlockHitResult result) {
        return RaycastUtils.canRaycastHit(
                mc.player,
                PlayerStateManager.INSTANCE.lastPitch,
                PlayerStateManager.INSTANCE.lastYaw,
                result.getBlockPos(),
                InteractExtra.INSTANCE.getBlockReachDistance());
    }

    private void sendYawDeceiveRotation(
            boolean legacy, BlockHitResult result, InteractionHand hand, PitchYawDeceive deceive) {
        float pitch = deceive.getPitch(PlayerStateManager.INSTANCE.lastServerSidePitch);
        float yaw = deceive.getYaw(PlayerStateManager.INSTANCE.lastServerSideYaw);
        if (legacy) {
            LegacySnapRotManager.INSTANCE.snapAt(pitch, yaw, true);
            return;
        }
        // 服务端可能把这次 useItem 当成放置，故用对侧手发一个 useItemOn 占住序列号
        InteractionHand oppoHand =
                hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        boolean isInteractable = mc.player.getItemInHand(oppoHand).getItem() instanceof BlockItem;
        if (isInteractable) {
            int sequence = NetworkUtils.generateNextSequence();
            if (yawPacketOrderFix.get()) {
                Listener.sendPacketNoEvents(new ServerboundUseItemOnPacket(oppoHand, result, sequence - 1));
            }
            mc.getConnection().send(new ServerboundUseItemPacket(hand, sequence, yaw, pitch));
        } else {
            if (yawPacketOrderFix.get()) {
                Listener.sendPacketNoEvents(
                        new ServerboundUseItemOnPacket(oppoHand, result, NetworkUtils.generateNextSequence()));
            }
            mc.getConnection()
                    .send(new ServerboundUseItemPacket(hand, NetworkUtils.generateNextSequence(), yaw, pitch));
        }
    }

    // can not bypass
    public void onPreSendInteractBlockRotate(Event<ServerboundUseItemOnPacket> e) {
        if (e.isCancelled()) return;
        //#if MC >= 260300
        //$$ if ((Object) e.context instanceof PlayerInteractBlockC2SPacketAccess paccess) {
        //#else
        if (e.context instanceof PlayerInteractBlockC2SPacketAccess paccess) {
        //#endif
            PitchYawDeceive deceivePy = null;
            BlockPos lookRot = null;
            LegalInteractMode rotateMode = null;
            if (paccess.hasUseContext()) {
                Event<PitchYawDeceive> yawDeceive = new Event<>(new PitchYawDeceive(), false, true);
                Event<LegalInteractMode> playerLookAt = new Event<>(null, false, true);
                if (paccess.getUseContext().isAccepted()
                        && paccess.getUseContext().placePos().isPresent()) {
                    handlePlaceCorrectLitematica(paccess.getUseContext(), e.context, yawDeceive, playerLookAt);
                } else if (paccess.getUseContext().isAccepted()) {
                    //#if MC >= 260300
                    //$$ BlockPos interactState = e.context.hitResult().getBlockPos();
                    //#else
                    BlockPos interactState = e.context.getHitResult().getBlockPos();
                    //#endif
                    BlockState newState = mc.level.getBlockState(interactState);
                    //#if MC >= 260300
                    //$$ BlockState oldState =
                    //$$         PendingUpdateManagerAccess.of(mc.level.getBlockStatePredictionHandler())
                    //$$                 .getPendingBlockState(e.context.sequence(), interactState)
                    //$$                 .orElse(newState);
                    //#else
                    BlockState oldState =
                            PendingUpdateManagerAccess.of(mc.level.getBlockStatePredictionHandler())
                                    .getPendingBlockState(e.context.getSequence(), interactState)
                                    .orElse(newState);
                    //#endif
                    if (oldState != newState) {
                        // handle yaw fix
                        handleInteractCorrectLitematica(interactState, newState, yawDeceive, playerLookAt);
                    }
                }

                //#if MC >= 260300
                //$$ BlockPos packetHitPos = e.context.hitResult().getBlockPos();
                //#else
                BlockPos packetHitPos = e.context.getHitResult().getBlockPos();
                //#endif
                handleSimpleInteractCorrect(packetHitPos, playerLookAt);
                if (yawDeceive.context != null && yawDeceive.context.hasDeceive()) {
                    deceivePy = yawDeceive.context;
                }
                if (playerLookAt.context != null) {
                    lookRot = packetHitPos;
                    rotateMode = playerLookAt.context;
                }
            }

            //#if MC >= 260300
            //$$ int packetSequence = e.context.sequence();
            //$$ InteractionHand packetHand = e.context.hand();
            //$$ BlockHitResult packetHitResult = e.context.hitResult();
            //#else
            int packetSequence = e.context.getSequence();
            InteractionHand packetHand = e.context.getHand();
            BlockHitResult packetHitResult = e.context.getHitResult();
            //#endif
            if (deceivePy != null && deceivePy.hasDeceive() && deceivePy.isForce()) {
                NetworkUtils.restoreSequence(packetSequence);
                boolean legacy =
                        ViaFabricPlusHooks.isSupportDupRot() || (rotateMode == null || rotateMode.canMultiRotPlace());
                sendYawDeceiveRotation(legacy, packetHitResult, packetHand, deceivePy);
                paccess.setSequence(NetworkUtils.generateNextSequence());
            }
            if (lookRot != null) {
                LegalInteractMode finalMode = rotateMode == null
                        ? LegalInteractMode.NONE
                        : (rotateMode == LegalInteractMode.MOVEMENT_POST
                                ? LegalInteractMode.DELAY_MOVEMENT
                                : rotateMode);
                BlockPos lookRot0 = lookRot;
                PacketManager.schedulePostScheduleCallback(e.context, () -> {
                    switch (finalMode) {
                        case LEGACY_SLIENT_ROT -> LegacySnapRotManager.INSTANCE.snapAt(
                                InteractionTasks.createBlockRayCastDirection(lookRot0), false);
                        case DELAY_MOVEMENT -> InteractionTasks.addPostRotationCorrectTask(
                                lookRot0, Runnables.doNothing());
                    }
                });
            }
        }
    }

    public void handlePlaceCorrectLitematica(
            PlayerInteractBlockC2SPacketAccess.UseContext useContext,
            ServerboundUseItemOnPacket packet,
            Event<PitchYawDeceive> yawDeceive,
            Event<LegalInteractMode> look) {
        if (!enable2.get() || !LitematicaHooks.getInstance().isEnabled()) {
            return;
        }
        BlockPos modifyingBlockPos = useContext.placePos().orElseThrow();
        if (!LitematicaHooks.getInstance().isPositionWithinRange(modifyingBlockPos)) return;

        Item item = useContext.stack().getItem();
        BlockState placedState = mc.level.getBlockState(modifyingBlockPos);
        BlockState litematicaState =
                LitematicaHooks.getInstance().getSchematicWorld().getBlockState(modifyingBlockPos);
        if (litematicaState.getBlock() != placedState.getBlock()
                || matchesExceptToleratedProperties(litematicaState, placedState)
                || litematicaState.getBlock().asItem() != item) return;

        //#if MC >= 260300
        //$$ int packetSequence = packet.sequence();
        //#else
        int packetSequence = packet.getSequence();
        //#endif
        // 在预测序列内先还原服务端视角的方块状态，据此判定能否直接交互
        WorldUtils.predictActionAtSequence(packetSequence, () -> {
            //#if MC >= 260300
            //$$ BlockHitResult packetHitResult = packet.hitResult();
            //#else
            BlockHitResult packetHitResult = packet.getHitResult();
            //#endif
            var specificHit = InteractionTasks.createSpecificStateHitResult(
                    packetHitResult.getDirection().getOpposite(), modifyingBlockPos, litematicaState, false, false);
            if (InteractUtils.canInteractAndPlace(mc.player, specificHit)) {
                PlayerInteractBlockC2SPacketAccess.of(packet).setBlockHitResult(specificHit.val());
            }
            //#if MC >= 260300
            //$$ packetHitResult = packet.hitResult();
            //#else
            packetHitResult = packet.getHitResult();
            //#endif
            yawDeceive.context = handleYawDeceive(
                    packetHitResult,
                    litematicaState,
                    PlayerStateManager.INSTANCE.lastServerSidePitch,
                    PlayerStateManager.INSTANCE.lastServerSideYaw);
            if ((look1.get().isLegal()
                            && !RaycastUtils.canRaycastHit(
                                    mc.player,
                                    PlayerStateManager.INSTANCE.lastPitch,
                                    PlayerStateManager.INSTANCE.lastYaw,
                                    packetHitResult.getBlockPos(),
                                    InteractExtra.INSTANCE.getBlockReachDistance()))
                    || (yawDeceive.context != null
                            && yawDeceive.context.hasDeceive()
                            && yawDeceive.context.isForce())) {
                look.context(look1.get());
            }
        });
    }

    public void handleInteractCorrectLitematica(
            BlockPos pos, BlockState newState, Event<PitchYawDeceive> yawDeceive, Event<LegalInteractMode> look) {
        if (enable2.get() && LitematicaHooks.getInstance().isEnabled()) {
            Level litematicaWorld = LitematicaHooks.getInstance().getSchematicWorld();
            if (!LitematicaHooks.getInstance().isPositionWithinRange(pos)) return;
            BlockState litematicaState = litematicaWorld.getBlockState(pos);

            if (litematicaState.getBlock() == newState.getBlock()
                    && !matchesExceptToleratedProperties(litematicaState, newState)) {
                yawDeceive.context = handleYawInteractDeceive(
                        litematicaState,
                        PlayerStateManager.INSTANCE.lastServerSidePitch,
                        PlayerStateManager.INSTANCE.lastServerSideYaw);
            }
            if ((look1.get().isLegal()
                            && !RaycastUtils.canRaycastHit(
                                    mc.player,
                                    PlayerStateManager.INSTANCE.lastPitch,
                                    PlayerStateManager.INSTANCE.lastYaw,
                                    pos,
                                    InteractExtra.INSTANCE.getBlockReachDistance()))
                    || (yawDeceive.context != null
                            && yawDeceive.context.hasDeceive()
                            && yawDeceive.context.isForce())) {
                look.context(look1.get());
            }
        }
    }

    private static boolean matchesExceptToleratedProperties(BlockState targetState, BlockState clientState) {
        if (targetState.getBlock() != clientState.getBlock()) return false;
        if (targetState.hasProperty(BlockStateProperties.WATERLOGGED)) {
            clientState = clientState.setValue(
                    BlockStateProperties.WATERLOGGED, targetState.getValue(BlockStateProperties.WATERLOGGED));
        }
        if (targetState.hasProperty(BlockStateProperties.POWERED)) {
            clientState = clientState.setValue(
                    BlockStateProperties.POWERED, targetState.getValue(BlockStateProperties.POWERED));
        }
        if (targetState.hasProperty(BlockStateProperties.POWER)) {
            clientState = clientState.setValue(
                    BlockStateProperties.POWER, targetState.getValue(BlockStateProperties.POWER));
        }
        return targetState == clientState;
    }

    public void handleSimpleInteractCorrect(BlockPos interactPos, Event<LegalInteractMode> look) {
        if (enable4.get()
                && look2.get().isLegal()
                && look.context == null
                && !RaycastUtils.canRaycastHit(
                        mc.player,
                        PlayerStateManager.INSTANCE.lastPitch,
                        PlayerStateManager.INSTANCE.lastYaw,
                        interactPos,
                        InteractExtra.INSTANCE.getBlockReachDistance())) {
            look.context(look2.get());
        }
    }

    private static PitchYawDeceive handleYawDeceive(
            BlockHitResult hitResult,
            @Nonnull BlockState targetState,
            float currentServerPitch,
            float currentServerYaw) {
        Block block = targetState.getBlock();
        PitchYawDeceive deceive = new PitchYawDeceive();
        boolean replacing = new BlockPlaceContext(
                        mc.player, InteractionHand.MAIN_HAND, new ItemStack(block.asItem()), hitResult)
                .replacingClickedOnBlock();

        Direction placementDirection = block instanceof WallHangingSignBlock
                ? targetState.getValue(WallHangingSignBlock.FACING).getOpposite()
                : (replacing ? getReplacingSupportDirection(hitResult, targetState) : null);
        if (placementDirection != null) {
            deceive.rotation = Rotation.from(EntityUtils.directionToPitchYaw(placementDirection));
            deceive.pitchForce =
                    EntityUtils.pitchYawToDirection(currentServerPitch, currentServerYaw) != placementDirection;
            deceive.yawForce = deceive.pitchForce;
            return deceive;
        }

        if (block instanceof FaceAttachedHorizontalDirectionalBlock) {
            if (targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.FLOOR
                    || targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.CEILING) {
                Direction direction = targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
                deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(direction));
                deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
                if (replacing) {
                    Direction support =
                            targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.FLOOR
                                    ? Direction.DOWN
                                    : Direction.UP;
                    deceive.rotation = deceive.rotation.withPitch(
                            Rotation.from(EntityUtils.directionToPitchYaw(support)).getPitch());
                    deceive.pitchForce =
                            EntityUtils.pitchYawToDirection(currentServerPitch, currentServerYaw) != support;
                }
                return deceive;
            }
        }

        if (block instanceof BaseRailBlock rail) {
            RailShape shape = targetState.getValue(rail.getShapeProperty());
            Direction direction =
                    switch (shape) {
                        case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> Direction.NORTH;
                        case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> Direction.EAST;
                        default -> null;
                    };
            return direction == null ? null : horizontalDeceive(direction, currentServerYaw);
        }

        if (block instanceof BigDripleafBlock) {
            BlockPos placePos = new BlockPlaceContext(
                            mc.player, InteractionHand.MAIN_HAND, new ItemStack(block.asItem()), hitResult)
                    .getClickedPos();
            BlockState below = mc.level.getBlockState(placePos.below());
            if (below.getBlock() instanceof BigDripleafBlock || below.getBlock() instanceof BigDripleafStemBlock) {
                return null;
            }
            return horizontalDeceive(
                    targetState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite(), currentServerYaw);
        }

        if (block instanceof BellBlock) {
            if (hitResult.getDirection().getAxis() == Direction.Axis.Y) {
                return horizontalDeceive(targetState.getValue(BellBlock.FACING), currentServerYaw);
            }
            return null;
        }

        if (block instanceof CarvedPumpkinBlock
                || block instanceof EndPortalFrameBlock
                || block instanceof SmallDripleafBlock) {
            return horizontalDeceive(
                    targetState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite(), currentServerYaw);
        }

        if (block instanceof JigsawBlock && hitResult.getDirection().getAxis() == Direction.Axis.Y) {
            return horizontalDeceive(
                    targetState.getValue(JigsawBlock.ORIENTATION).top().getOpposite(), currentServerYaw);
        }

        if (block instanceof CommandBlock) {
            Direction look = targetState.getValue(CommandBlock.FACING).getOpposite();
            deceive.rotation = Rotation.from(EntityUtils.directionToPitchYaw(look));
            deceive.pitchForce = EntityUtils.pitchYawToDirection(currentServerPitch, currentServerYaw) != look;
            deceive.yawForce = deceive.pitchForce;
            return deceive;
        }

        if (block instanceof ObserverBlock) {
            Direction facing = targetState.getValue(ObserverBlock.FACING);
            deceive.rotation = Rotation.from(EntityUtils.directionToPitchYaw(facing));

            if (EntityUtils.pitchYawToDirection(currentServerPitch, currentServerYaw) == facing) {
                deceive.pitchForce = false;
                deceive.yawForce = false;
            }
            return deceive;
        }

        if (block instanceof PistonBaseBlock) {
            Direction facing = targetState.getValue(PistonBaseBlock.FACING).getOpposite();
            deceive.rotation = Rotation.from(EntityUtils.directionToPitchYaw(facing));

            if (EntityUtils.pitchYawToDirection(currentServerPitch, currentServerYaw) == facing) {
                deceive.pitchForce = false;
                deceive.yawForce = false;
            }
            return deceive;
        }

        if (block instanceof DispenserBlock) {
            Direction facing = targetState.getValue(DispenserBlock.FACING).getOpposite();
            deceive.rotation = Rotation.from(EntityUtils.directionToPitchYaw(facing));

            if (EntityUtils.pitchYawToDirection(currentServerPitch, currentServerYaw) == facing) {
                deceive.pitchForce = false;
                deceive.yawForce = false;
            }
            return deceive;
        }

        if (block instanceof BarrelBlock) {
            Direction facing = targetState.getValue(BarrelBlock.FACING).getOpposite();
            deceive.rotation = Rotation.from(EntityUtils.directionToPitchYaw(facing));

            if (EntityUtils.pitchYawToDirection(currentServerPitch, currentServerYaw) == facing) {
                deceive.pitchForce = false;
                deceive.yawForce = false;
            }
            return deceive;
        }

        if (block instanceof CrafterBlock) {
            FrontAndTop orientation = targetState.getValue(BlockStateProperties.ORIENTATION);
            Direction facing = orientation.front();
            Direction rotation = orientation.top();
            Rotation pitchYaw;

            switch (facing) {
                case DOWN -> pitchYaw = Rotation.from(EntityUtils.rotationToPitchYaw(
                        Vec3.atLowerCornerOf(rotation.getOpposite().getUnitVec3i())
                                .add(0, 4, 0)
                                .normalize()));
                case UP -> pitchYaw = Rotation.from(EntityUtils.rotationToPitchYaw(
                        Vec3.atLowerCornerOf(rotation.getUnitVec3i()).add(0, -4, 0).normalize()));
                default -> pitchYaw = Rotation.from(EntityUtils.rotationToPitchYaw(
                        Vec3.atLowerCornerOf(facing.getOpposite().getUnitVec3i()).normalize()));
            }

            deceive.rotation = pitchYaw;

            Direction currentFacing = EntityUtils.pitchYawToDirection(currentServerPitch, currentServerYaw)
                    .getOpposite();

            Direction horizontalFacing = EntityUtils.yawToHorizontalDirection(currentServerYaw);

            Direction currentRotation =
                    switch (currentFacing) {
                        case DOWN -> horizontalFacing.getOpposite();
                        case UP -> horizontalFacing;
                        default -> Direction.UP;
                    };

            boolean matches = currentFacing == facing && currentRotation == rotation;
            deceive.pitchForce = !matches;
            deceive.yawForce = !matches;

            return deceive;
        }

        if (block instanceof AbstractFurnaceBlock) {
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(AbstractFurnaceBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof ChiseledBookShelfBlock) {
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(HorizontalDirectionalBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof VaultBlock) {
            deceive.rotation = deceive.rotation.withYaw(
                    EntityUtils.rotationToYaw(targetState.getValue(VaultBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof LoomBlock) {
            deceive.rotation = deceive.rotation.withYaw(
                    EntityUtils.rotationToYaw(targetState.getValue(LoomBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof GlazedTerracottaBlock) {
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(GlazedTerracottaBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof BeehiveBlock) {
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(BeehiveBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof DiodeBlock) {
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(DiodeBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof StonecutterBlock) {
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(StonecutterBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof FenceGateBlock) {
            deceive.rotation =
                    deceive.rotation.withYaw(EntityUtils.rotationToYaw(targetState.getValue(FenceGateBlock.FACING)));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof DoorBlock) {
            deceive.rotation =
                    deceive.rotation.withYaw(EntityUtils.rotationToYaw(targetState.getValue(DoorBlock.FACING)));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof BedBlock) {
            deceive.rotation =
                    deceive.rotation.withYaw(EntityUtils.rotationToYaw(targetState.getValue(BedBlock.FACING)));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof CampfireBlock) {
            deceive.rotation = deceive.rotation.withYaw(
                    EntityUtils.rotationToYaw(targetState.getValue(CampfireBlock.FACING)));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof DecoratedPotBlock) {
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(BlockStateProperties.HORIZONTAL_FACING)));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof StairBlock) {
            deceive.rotation =
                    deceive.rotation.withYaw(EntityUtils.rotationToYaw(targetState.getValue(StairBlock.FACING)));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof CalibratedSculkSensorBlock) {
            deceive.rotation = deceive.rotation.withYaw(
                    EntityUtils.rotationToYaw(targetState.getValue(CalibratedSculkSensorBlock.FACING)));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof EnderChestBlock) {
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(EnderChestBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof DriedGhastBlock) {
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(DriedGhastBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof ShelfBlock) {
            deceive.rotation = deceive.rotation.withYaw(
                    EntityUtils.rotationToYaw(targetState.getValue(ShelfBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof LecternBlock) {
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(LecternBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof CopperGolemStatueBlock) {
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(CopperGolemStatueBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof TrapDoorBlock) {
            if (hitResult.getDirection().getAxis().isHorizontal() && !replacing) {
                return null;
            }
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(
                    targetState.getValue(TrapDoorBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof ChestBlock) {
            deceive.rotation = deceive.rotation.withYaw(
                    EntityUtils.rotationToYaw(targetState.getValue(ChestBlock.FACING).getOpposite()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        if (block instanceof AnvilBlock) {
            Direction facing = targetState.getValue(AnvilBlock.FACING);
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(facing.getCounterClockWise()));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        return null;
    }

    private static PitchYawDeceive horizontalDeceive(Direction direction, float currentServerYaw) {
        PitchYawDeceive deceive = new PitchYawDeceive();
        deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(direction));
        deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
        return deceive;
    }

    private static Direction getReplacingSupportDirection(BlockHitResult hitResult, BlockState state) {
        Block block = state.getBlock();
        BlockPos placePos = hitResult.getBlockPos();
        BlockState currentState = mc.level.getBlockState(placePos);
        if (block instanceof FaceAttachedHorizontalDirectionalBlock) {
            return state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.WALL
                    ? state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING).getOpposite()
                    : null;
        }
        if (block instanceof LanternBlock) {
            return state.getValue(LanternBlock.HANGING) ? Direction.UP : Direction.DOWN;
        }
        if (block instanceof VineBlock) {
            BlockState aboveState = mc.level.getBlockState(placePos.above());
            for (Direction direction : Direction.values()) {
                var property = VineBlock.PROPERTY_BY_DIRECTION.get(direction);
                if (property != null
                        && state.getValue(property)
                        && !(currentState.is(block) && currentState.getValue(property))
                        && (VineBlock.isAcceptableNeighbour(mc.level, placePos.relative(direction), direction)
                                || (direction.getAxis().isHorizontal()
                                        && aboveState.is(block)
                                        && aboveState.getValue(property)))) return direction;
            }
            return null;
        }
        if (block instanceof MultifaceBlock multiface) {
            for (Direction direction : Direction.values()) {
                var property = PipeBlock.PROPERTY_BY_DIRECTION.get(direction);
                if (state.hasProperty(property)
                        && state.getValue(property)
                        && multiface.getStateForPlacement(currentState, mc.level, placePos, direction) != null)
                    return direction;
            }
            return null;
        }
        if (block instanceof CocoaBlock) {
            return state.getValue(CocoaBlock.FACING);
        }
        if (block instanceof RedstoneWallTorchBlock) {
            return state.getValue(RedstoneWallTorchBlock.FACING).getOpposite();
        }
        if (block instanceof WallTorchBlock) {
            return state.getValue(WallTorchBlock.FACING).getOpposite();
        }
        if (block instanceof WallSkullBlock) {
            return state.getValue(WallSkullBlock.FACING).getOpposite();
        }
        if (block instanceof WallSignBlock) {
            return state.getValue(WallSignBlock.FACING).getOpposite();
        }
        if (block instanceof WallBannerBlock) {
            return state.getValue(WallBannerBlock.FACING).getOpposite();
        }
        if (block instanceof TripWireHookBlock) {
            return state.getValue(TripWireHookBlock.FACING).getOpposite();
        }
        if (block instanceof BaseCoralWallFanBlock) {
            return state.getValue(BaseCoralWallFanBlock.FACING).getOpposite();
        }
        if (block instanceof LadderBlock) {
            return state.getValue(LadderBlock.FACING).getOpposite();
        }
        return null;
    }

    public static PitchYawDeceive handleYawInteractDeceive(
            BlockState targetState, float currentServerPitch, float currentServerYaw) {
        Block block = targetState.getBlock();

        if (block instanceof FenceGateBlock) {
            PitchYawDeceive deceive = new PitchYawDeceive();
            Direction facing = targetState.getValue(FenceGateBlock.FACING);
            deceive.rotation = deceive.rotation.withYaw(EntityUtils.rotationToYaw(facing));
            deceive.yawForce = Math.abs(Mth.wrapDegrees(currentServerYaw - deceive.rotation.getYaw())) >= 45.0F;
            return deceive;
        }

        return null;
    }

    private static class PitchYawDeceive {
        Rotation rotation = Rotation.EMPTY;
        boolean pitchForce = true;
        boolean yawForce = true;

        public boolean hasDeceive() {
            return rotation.hasRotate();
        }

        public boolean isForce() {
            return (rotation.hasPitch() && pitchForce) || (rotation.hasYaw() && yawForce);
        }

        public float getPitch(float currentPitch) {
            return rotation.hasPitch() && pitchForce ? rotation.getPitch() : currentPitch;
        }

        public float getYaw(float currentYaw) {
            return rotation.hasYaw() && yawForce ? rotation.getYaw() : currentYaw;
        }
    }
}
