package me.matl114.hacks.modules.interact;

import com.google.common.util.concurrent.Runnables;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.matl114.accessors.access.HitResultAccess;
import me.matl114.accessors.access.PlayerInteractBlockC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.RenderTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.enums.BypassMode;
import me.matl114.hooks.LitematicaHooks;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
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
import net.minecraft.world.item.Item;
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
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import me.matl114.hacks.utils.EntityUtils;

public class BlockRotate extends BaseModule {
    public final ModulePath blockRotate = makePath(Configs.INTERACT_CONFIG, "block-rotate");
    public final ModulePath tempSchematic = blockRotate.add("temporary-schematic");
    public final ModulePath litematicaFix = blockRotate.add("litematica-shit-fix");
    public final ModulePath placeCorrect = blockRotate.add("simple-placement-correct");
    public static BlockRotate INSTANCE;

    public BlockRotate() {
        super("BlockRotate");
        bindFlag(enable);
        INSTANCE = this;
    }

    public final FlagRef enable =
            builder(blockRotate.add("enable"), Boolean.class).defaultValue(true).build();

    public final EnumRef<BypassMode> bypassMode = builder(
                    blockRotate.add("yaw-deceive-bypass-mode"), BypassMode.class)
            .defaultValue(BypassMode.NO_BYPASS)
            .build();

    public final EnumRef<BypassMode> bypassMode2 = builder(
                    blockRotate.add("rotate-bypass-mode"), BypassMode.class)
            .defaultValue(BypassMode.NO_BYPASS)
            .build();

    public final FlagRef enable3 = builder(tempSchematic.add("enable"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef clientTempFix = builder(tempSchematic.add("client-state-temp-fix"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef enable2 = flagBuilder(litematicaFix.add("enable")).build();

    public final FlagRef legal = flagBuilder(litematicaFix.add("legal-look")).build();

    public final FlagRef enable4 = flagBuilder(placeCorrect.add("enable")).build();

    //    public final FlagRef enable3 =
    //            flagBuilder(litematicaFix.add("enable-easyplace-post-fix")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemOnPacket.class),
                this::onPreSendInteractBlockRotate);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onPresetLoad);
        registerListener(Listener.getWorldSwitchPoint(), this::onWorldChange);
        registerListener(Listener.getPostGameTick(), this::onUpdate);
    }

    public boolean enableBlockRotateModify() {
        return enable3.get() || enable2.get();
    }

    final Map<BlockPos, TemporarySchematic> tempSchematics = new ConcurrentHashMap<>();

    public void onWorldChange(Event<Level> eventWorld) {
        tempSchematics.clear();
    }

    int timer = 0;

    public void onUpdate(Event<LocalPlayer> eventUpdate) {
        if (timer++ > 20) {
            timer = 0;
            var iter = tempSchematics.entrySet().iterator();
            while (iter.hasNext()) {
                var entry = iter.next();
                if (entry.getValue().expire()) {
                    iter.remove();
                }
            }
        }
    }

    public void addTempStateSchematic(BlockPos pos, BlockState state) {
        addTempStateSchematic(pos, state, 1);
    }

    public void addTempStateSchematic(BlockPos pos, BlockState state, int lastingTicks) {
        if (state != null) {
            tempSchematics.put(pos, new TemporarySchematic(pos, lastingTicks, state));
        }
    }

    // can not bypass
    public void onPreSendInteractBlockRotate(Event<ServerboundUseItemOnPacket> e) {
        if (e.isCancelled()) return;
        if (enable.get() && enableBlockRotateModify()) {
            if (e.context instanceof PlayerInteractBlockC2SPacketAccess paccess) {
                //
                Vec2 currentPy = new Vec2(PlayerStateManager.INSTANCE.lastPitch, PlayerStateManager.INSTANCE.lastYaw);
                PitchYawDeceive deceivePy = null;
                Vec3 lookRot = null;
                if (paccess.hasUseContext()) {
                    Event<PitchYawDeceive> yawDeceive = new Event<>(new PitchYawDeceive(), false, true);
                    Event<BlockPos> playerLookAt = new Event<>(null, false, true);
                    if (paccess.getUseContext().blockPlace()) {
                        PlayerInteractBlockC2SPacketAccess.UseContext context = paccess.getUseContext();
                        Item blockItem = context.stack().getItem();

                        handlePlaceCorrectLitematica(blockItem, e.context, context, yawDeceive, playerLookAt);
                        handlePlaceCorrectTemperarySchematic(blockItem, e.context, context, yawDeceive);
                        handleSimpleInteractCorrect(
                                e.context.getHitResult().getBlockPos(), playerLookAt);
                    } else if (paccess.getUseContext().isAccepted()) {
                        BlockState oldState = paccess.getUseContext().oldState();
                        BlockPos interactState = e.context.getHitResult().getBlockPos();
                        BlockState newState = mc.level.getBlockState(interactState);
                        if (oldState != newState) {
                            // handle yaw fix
                            handleInteractCorrectLitematica(interactState, newState, yawDeceive, playerLookAt);
                        }
                        handleSimpleInteractCorrect(interactState, playerLookAt);
                    }
                    if (yawDeceive.context != null && yawDeceive.context.hasDeceive()) {
                        deceivePy = yawDeceive.context;
                    }
                    if (playerLookAt.context != null) {
                        lookRot = InteractionTasks.createBlockRayCastDirection(playerLookAt.context);
                    }
                }
                if (deceivePy != null
                        && ViaFabricPlusHooks.getInstance().getCurrentVersion().isHigherOrEqualTo(21, 0)) {
                    if (bypassMode.get() == BypassMode.BYPASS_GRIM) {
                        // to ensure the rotate is successfully done
                        // use a wrong sequence id to ensure that this packet cancelled by grimac
                        Listener.sendPacketNoEvents(new ServerboundUseItemOnPacket(
                                InteractionHand.OFF_HAND, e.context.getHitResult(), e.context.getSequence() - 1));
                    }
                    mc.getConnection()
                            .send(new ServerboundUseItemPacket(
                                    InteractionHand.MAIN_HAND,
                                    e.context.getSequence(),
                                    deceivePy.getYaw(currentPy.y),
                                    deceivePy.getPitch(currentPy.x)));
                    PlayerInteractBlockC2SPacketAccess.of(e.context).setSequence(NetworkUtils.generateNextSequence());
                }
                if (deceivePy != null
                        && ViaFabricPlusHooks.getInstance().getCurrentVersion().isLowerOrEqualTo(20, 8)) {
                    LegacySnapRotManager.INSTANCE.snapAt(
                            deceivePy.getPitch(currentPy.x), deceivePy.getYaw(currentPy.y), true);
                }
                if (lookRot != null
                        && (bypassMode2.get().hasAc()
                                || (deceivePy != null
                                        && ViaFabricPlusHooks.getInstance()
                                                .getCurrentVersion()
                                                .isLowerOrEqualTo(20, 8)))) {
                    if (ViaFabricPlusHooks.isSupportDupRot()) {
                        var packet = LegacySnapRotManager.INSTANCE.createSnapAt(
                                lookRot);
                        PacketManager.schedulePostSendPacket(e.context, packet);
                    } else {
                        InteractionTasks.addPostRotationCorrectTask(lookRot, Runnables.doNothing());
                    }
                }
            }
        }
    }

    public void handlePlaceCorrectTemperarySchematic(
            Item item,
            ServerboundUseItemOnPacket packet,
            PlayerInteractBlockC2SPacketAccess.UseContext useContext,
            Event<PitchYawDeceive> yawDeceive) {
        if (enable3.get()) {
            // ?
            BlockHitResult packetHitResult = packet.getHitResult();
            BlockState litematicaState;
            BlockPos modifyingBlockPos = useContext.getPlaceBlockPos(packet.getHand(), packetHitResult);
            TemporarySchematic schematic = tempSchematics.remove(modifyingBlockPos);
            if (schematic == null || schematic.expire()) {
                return;
            }
            BlockState clientState = mc.level.getBlockState(modifyingBlockPos);
            litematicaState = schematic.targetState;
            // no need for fix
            if (clientState == litematicaState) {
                return;
            }
            BlockHitResult newPacketHitResult =
                    handlePlaceCorrect(item, modifyingBlockPos, litematicaState, packet, true);
            if (newPacketHitResult != null) {
                // wrong state, need correct
                // do not rotate, because other module will rotate itself
                //                packetHitResult = newPacketHitResult;
                //                if (legal.get()) {
                //                    look.context(packetHitResult.getBlockPos());
                //                }
                handleYawDeceive(litematicaState, yawDeceive.context);
                if (clientTempFix.get()) {
                    mc.level.setBlock(modifyingBlockPos, litematicaState, WorldUtils.UPDATE_BLOCK_NO_PHYSICS);
                }
            }
        }
    }

    public void handlePlaceCorrectLitematica(
            Item item,
            ServerboundUseItemOnPacket packet,
            PlayerInteractBlockC2SPacketAccess.UseContext useContext,
            Event<PitchYawDeceive> yawDeceive,
            Event<BlockPos> look) {

        if (enable2.get() && LitematicaHooks.getInstance().isEnabled()) {
            BlockHitResult packetHitResult = packet.getHitResult();
            BlockState litematicaState;
            Level litematicaWorld = LitematicaHooks.getInstance().getSchematicWorld();

            BlockPos modifyingBlockPos = useContext.getPlaceBlockPos(packet.getHand(), packetHitResult);
            if (!LitematicaHooks.getInstance().isPositionWithinRange(modifyingBlockPos)) return;

            litematicaState = litematicaWorld.getBlockState(modifyingBlockPos);
            BlockHitResult newPacketHitResult = handlePlaceCorrect(
                    item,
                    modifyingBlockPos,
                    litematicaState,
                    packet,
                    useContext.oldState().isAir());
            if (newPacketHitResult != null) {
                // wrong state, need correct
                packetHitResult = newPacketHitResult;
                BlockState clientState = mc.level.getBlockState(modifyingBlockPos);
                if (litematicaState != clientState) {
                    BlockHitResult easyPlaceResult = LitematicaHooks.getInstance()
                            .getEasyPlaceClickedPosition(packetHitResult, litematicaState, clientState);
                    if (easyPlaceResult != null) {
                        HitResultAccess access = HitResultAccess.of(packetHitResult);
                        access.setPos(easyPlaceResult.getLocation());
                    }
                }
                if (legal.get()) {
                    if (!RaycastUtils.canRaycastHit(
                            mc.player,
                            PlayerStateManager.INSTANCE.lastPitch,
                            PlayerStateManager.INSTANCE.lastYaw,
                            packetHitResult.getBlockPos(),
                            InteractExtra.INSTANCE.getBlockReachDistance())) {
                        look.context(packetHitResult.getBlockPos());
                    }
                }
                handleYawDeceive(litematicaState, yawDeceive.context);
            }
            RenderTasks.debugBlockHitResult(packetHitResult);
        }
    }

    public void handleInteractCorrectLitematica(
            BlockPos pos, BlockState newState, Event<PitchYawDeceive> yawDeceive, Event<BlockPos> look) {
        if (enable2.get() && LitematicaHooks.getInstance().isEnabled()) {
            Level litematicaWorld = LitematicaHooks.getInstance().getSchematicWorld();
            if (!LitematicaHooks.getInstance().isPositionWithinRange(pos)) return;
            BlockState litematicaState = litematicaWorld.getBlockState(pos);
            if (legal.get()
                    && !RaycastUtils.canRaycastHit(
                            mc.player,
                            PlayerStateManager.INSTANCE.lastPitch,
                            PlayerStateManager.INSTANCE.lastYaw,
                            pos,
                            InteractExtra.INSTANCE.getBlockReachDistance())) {
                look.context(pos);
            }
            if (litematicaState.getBlock() == newState.getBlock() && litematicaState != newState) {
                handleYawInteractDeceive(litematicaState, yawDeceive.context);
            }
        }
    }

    public void handleSimpleInteractCorrect(BlockPos interactPos, Event<BlockPos> look) {
        if (enable4.get()
                && legal.get()
                && look.context == null
                && !RaycastUtils.canRaycastHit(
                        mc.player,
                        PlayerStateManager.INSTANCE.lastPitch,
                        PlayerStateManager.INSTANCE.lastYaw,
                        interactPos,
                        InteractExtra.INSTANCE.getBlockReachDistance())) {
            look.context(interactPos);
        }
    }

    public BlockHitResult handlePlaceCorrect(
            Item item,
            BlockPos modifyingBlockPos,
            BlockState targetState,
            ServerboundUseItemOnPacket packet,
            boolean forceCorrect) {
        BlockHitResult packetHitResult = packet.getHitResult();
        BlockState clientState = mc.level.getBlockState(modifyingBlockPos);
        if (!targetState.isAir()
                && targetState.getBlock().asItem() == item
                && targetState.getBlock() == clientState.getBlock()) {
            // sb easy place, use illegal hitResult or shit
            if (forceCorrect) {
                // handle airplace shit
                BlockHitResult hitResult = correctEasyPlaceHitResult(packetHitResult, targetState);
                // RenderTasks.debugBlockHitResult(hitResult);
                PlayerInteractBlockC2SPacketAccess.of(packet).setBlockHitResult(hitResult);
                packetHitResult = hitResult;
            }
            return packetHitResult;
        }
        return null;
    }

    public BlockHitResult correctEasyPlaceHitResult(BlockHitResult hitResult, BlockState targetState) {
        BlockPos placingPos = InteractUtils.getCurrentPlacePos(mc.player, hitResult);
        var result = InteractionTasks.createSpecificStateHitResult(
                hitResult.getDirection().getOpposite(), placingPos, targetState, false, false);
        return result == null ? hitResult : result.val();
    }

    public void onPresetLoad(Event<EventContainer<ModulePreset>> e) {
        switch (e.context.getValue()) {
            case AC_GRIM, AC_GRIM_LEGACY -> bypassMode.set(BypassMode.BYPASS_GRIM);
            default -> bypassMode.set(BypassMode.NO_BYPASS);
        }
        switch (e.context.getValue()) {
            case AC_GRIM, AC_GRIM_LEGACY -> bypassMode2.set(BypassMode.BYPASS_GRIM);
            default -> bypassMode2.set(BypassMode.NO_BYPASS);
        }
    }

    public static void handleYawDeceive(BlockState targetState, PitchYawDeceive deceive) {
        Block block = targetState.getBlock();
        if (block instanceof FaceAttachedHorizontalDirectionalBlock lever) {
            if (targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.FLOOR
                    || targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.CEILING) {
                Direction direction = targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
                deceive.yaw = EntityUtils.directionToPitchYaw(direction).y;
                return;
            }
        }
        if (block instanceof ObserverBlock ob) {
            Vec2 pitchYaw = EntityUtils.directionToPitchYaw(targetState.getValue(ObserverBlock.FACING));
            deceive.pitch = pitchYaw.x;
            deceive.yaw = pitchYaw.y;
            return;
        }
        if (block instanceof PistonBaseBlock ps) {
            Vec2 pitchYaw = EntityUtils.directionToPitchYaw(
                    targetState.getValue(PistonBaseBlock.FACING).getOpposite());
            deceive.pitch = pitchYaw.x;
            deceive.yaw = pitchYaw.y;
            return;
        }
        if (block instanceof DispenserBlock disp) {
            Vec2 pitchYaw = EntityUtils.directionToPitchYaw(
                    targetState.getValue(DispenserBlock.FACING).getOpposite());
            deceive.pitch = pitchYaw.x;
            deceive.yaw = pitchYaw.y;
            return;
        }
        if (block instanceof BarrelBlock barrelBlock) {
            Vec2 pitchYaw = EntityUtils.directionToPitchYaw(
                    targetState.getValue(BarrelBlock.FACING).getOpposite());
            deceive.pitch = pitchYaw.x;
            deceive.yaw = pitchYaw.y;
            return;
        }
        if (block instanceof CrafterBlock crafterBlock) {
            FrontAndTop orientation = targetState.getValue(BlockStateProperties.ORIENTATION);
            Direction facing = orientation.front();
            Direction rotation = orientation.top();
            Vec2 pitchYaw;
            switch (facing) {
                case DOWN -> {
                    pitchYaw = EntityUtils.rotationToPitchYaw(
                            Vec3.atLowerCornerOf(rotation.getOpposite().getUnitVec3i())
                                    .add(0, 4, 0)
                                    .normalize());
                }
                case UP -> {
                    pitchYaw = EntityUtils.rotationToPitchYaw(Vec3.atLowerCornerOf(rotation.getUnitVec3i())
                            .add(0, -4, 0)
                            .normalize());
                }
                default -> {
                    pitchYaw = EntityUtils.rotationToPitchYaw(
                            Vec3.atLowerCornerOf(facing.getOpposite().getUnitVec3i())
                                    .normalize());
                }
            }
            deceive.pitch = pitchYaw.x;
            deceive.yaw = pitchYaw.y;
            return;
        }

        // 以下分支只修改 yaw，保持玩家当前 pitch，因此只赋值 deceive.yaw
        if (block instanceof AbstractFurnaceBlock) {
            Direction facing = targetState.getValue(AbstractFurnaceBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof ChiseledBookShelfBlock) {
            Direction facing = targetState.getValue(HorizontalDirectionalBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof VaultBlock) {
            Direction facing = targetState.getValue(VaultBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof LoomBlock) {
            Direction facing = targetState.getValue(LoomBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof GlazedTerracottaBlock) {
            Direction facing = targetState.getValue(GlazedTerracottaBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof BeehiveBlock) {
            Direction facing = targetState.getValue(BeehiveBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof DiodeBlock) {
            Direction facing = targetState.getValue(DiodeBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof StonecutterBlock) {
            Direction facing = targetState.getValue(StonecutterBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        // 以下方块不需要取反
        if (block instanceof FenceGateBlock) {
            Direction facing = targetState.getValue(FenceGateBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing);
            return;
        }
        if (block instanceof DoorBlock) {
            Direction facing = targetState.getValue(DoorBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing);
            return;
        }
        if (block instanceof CampfireBlock) {
            Direction facing = targetState.getValue(CampfireBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing);
            return;
        }
        if (block instanceof DecoratedPotBlock) {
            Direction facing = targetState.getValue(BlockStateProperties.HORIZONTAL_FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing);
            return;
        }
        if (block instanceof StairBlock) {
            Direction facing = targetState.getValue(StairBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing);
            return;
        }
        if (block instanceof CalibratedSculkSensorBlock) {
            Direction facing = targetState.getValue(CalibratedSculkSensorBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing);
            return;
        }
        // 需要取反的分支
        if (block instanceof EnderChestBlock) {
            Direction facing = targetState.getValue(EnderChestBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof DriedGhastBlock) {
            Direction facing = targetState.getValue(DriedGhastBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof ShelfBlock) {
            Direction facing = targetState.getValue(ShelfBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof LecternBlock) {
            Direction facing = targetState.getValue(LecternBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof CopperGolemStatueBlock) {
            Direction facing = targetState.getValue(CopperGolemStatueBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof TrapDoorBlock) {
            Direction facing = targetState.getValue(TrapDoorBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof ChestBlock) {
            Direction facing = targetState.getValue(ChestBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing.getOpposite());
            return;
        }
        if (block instanceof AnvilBlock) {
            Direction facing = targetState.getValue(AnvilBlock.FACING);
            Direction playerFacing = facing.getCounterClockWise();
            deceive.yaw = EntityUtils.rotationToYaw(playerFacing);
            return;
        }
    }

    public static void handleYawInteractDeceive(BlockState targetState, PitchYawDeceive deceive) {
        Block block = targetState.getBlock();
        if (block instanceof FenceGateBlock fenceGateBlock) {
            Direction facing = targetState.getValue(FenceGateBlock.FACING);
            deceive.yaw = EntityUtils.rotationToYaw(facing);
            return;
        }
    }

    public static class PitchYawDeceive {
        Float pitch = null;
        Float yaw = null;

        public boolean hasDeceive() {
            return pitch != null || yaw != null;
        }

        public float getPitch(float currentPitch) {
            return pitch != null ? pitch : currentPitch;
        }

        public float getYaw(float currentYaw) {
            return yaw != null ? yaw : currentYaw;
        }
    }

    public static class TemporarySchematic {
        BlockPos blockPos;
        int expireTick;
        BlockState targetState;

        public TemporarySchematic(BlockPos blockPos, int lastTicks, BlockState targetState) {
            this.blockPos = blockPos;
            this.expireTick = lastTicks + Tasks.getTick();
            this.targetState = targetState;
        }

        public boolean expire() {
            return Tasks.getTick() > expireTick;
        }
    }
}
