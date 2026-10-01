package me.matl114.jsApi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.matl114.accessors.access.PlayerInteractBlockC2SPacketAccess;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Listener;
import me.matl114.hacks.InvTasks;
import me.matl114.utils.ApiMethod;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.NetworkUtils;
import me.matl114.utils.RaycastUtils;
import me.matl114.versioned.api.VPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

@ApiMethod
public class PacketHelper {
    static Minecraft mc = Minecraft.getInstance();

    public static Class<? extends Packet<?>> getPacketType(String packetType, boolean s2c) {
        Identifier id = Identifier.tryParse(packetType);
        return Listener.getPacketClassById(id, s2c);
    }

    public static void sendPacket(Packet<?> packet) {
        mc.getConnection().send(packet);
    }

    public static List<String> getAllPacketTypes() {
        return Listener.getRegisteredPacketTypes().keySet().stream()
                .map(PacketType::id)
                .map(Identifier::toString)
                .toList();
    }

    public static int generateSequenceId() {
        return NetworkUtils.generateNextSequence();
    }

    public static void sendInventoryPacket(int slotId, int button, Object actionTypeStr) {
        InvTasks.clickSlotAsync(slotId, button, JsHelper.toEnum(actionTypeStr, ContainerInput.class));
    }

    public static int getLastServerScreenSyncId() {
        return InvTasks.LAST_SYNC_ID;
    }

    public static void sendCloseInventory(int syncId) {
        mc.getConnection().send(new ServerboundContainerClosePacket(syncId));
    }

    public static void sendAttackBlock(int x, int y, int z, Object direction) {
        sendAttackBlock(new BlockPos(x, y, z), direction);
    }

    public static void sendAttackBlock(Object pos, Object direction) {
        Direction dir = JsHelper.toEnum(direction, Direction.class);
        BlockPos blockPos = DataHelper.createBlockPos(pos);
        BlockState blockState;
        if (mc.player.getAbilities().instabuild) {
            mc.gameMode.startPrediction(mc.level, (sequence) -> {
                mc.execute(() -> mc.gameMode.destroyBlock(blockPos));
                return new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, dir, sequence);
            });
        } else {
            blockState = mc.level.getBlockState(blockPos);
            float speed = blockState.getDestroyProgress(mc.player, mc.level, blockPos);
            boolean canInstaMine = speed > 1.0F;
            if (canInstaMine) {
                mc.gameMode.startPrediction(
                        mc.level,
                        (sequence -> new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, dir, sequence)));
                mc.execute(() -> mc.gameMode.destroyBlock(blockPos));
            } else if (!Objects.equals(
                    blockPos, PlayerInteractionAccess.of(mc.gameMode).getCurrentMiningPos())) {
                if (mc.gameMode.isDestroying()) {
                    mc.getConnection()
                            .send(new ServerboundPlayerActionPacket(
                                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                                    PlayerInteractionAccess.of(mc.gameMode).getCurrentMiningPos(),
                                    dir));
                }
                PlayerInteractionAccess.of(mc.gameMode).startMiningBlock(blockPos, dir);
            }
        }
    }

    private static void syncHotbar() {

        PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(InventoryUtils.getSelectedSlot());
    }

    public static void sendInteractBlock(int x, int y, int z, Object direction, boolean offhand) {
        sendInteractBlock(new BlockPos(x, y, z), direction, offhand);
    }

    public static void sendInteractBlock(Object pos, Object direction, boolean offhand) {
        Direction dir = JsHelper.toEnum(direction, Direction.class);
        BlockPos blockPos = DataHelper.createBlockPos(pos);
        BlockHitResult hitResult = RaycastUtils.createHitResult(blockPos, dir);
        syncHotbar();
        mc.gameMode.startPrediction(mc.level, (seq) -> {
            var packet = new ServerboundUseItemOnPacket(
                    offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, hitResult, seq);
            if (packet instanceof PlayerInteractBlockC2SPacketAccess access) {
                access.setUseContext(new PlayerInteractBlockC2SPacketAccess.UseContext(
                        mc.player
                                .getItemInHand(offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND)
                                .copy(),
                        mc.level.getBlockState(hitResult.getBlockPos()),
                        InteractionResult.SUCCESS,
                        Optional.empty()));
            }
            return packet;
        });
    }

    public static void sendInteractEntity(Object entity, boolean offhand) {
        sendInteractEntity(entity, mc.player.isShiftKeyDown(), offhand);
    }

    public static void sendInteractEntity(Object entity, boolean sneaking, boolean offhand) {
        int s;
        if (entity instanceof Integer integer) {
            s = integer.intValue();
        } else {
            Entity e = JsHelper.unwrap(entity, Entity.class);
            s = e.getId();
        }
        syncHotbar();
        mc.gameMode.startPrediction(mc.level, (seq) -> {
            return new ServerboundInteractPacket(
                    s,
                    offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                    net.minecraft.world.phys.Vec3.ZERO,
                    sneaking);
        });
    }

    public static void sendInteractItem(boolean offHand) {
        sendInteractItem(offHand, mc.player.getXRot(), mc.player.getYRot());
    }

    public static void sendInteractItem(boolean offHand, float pitch, float yaw) {
        syncHotbar();
        mc.gameMode.startPrediction(mc.level, (seq) -> {
            return new ServerboundUseItemPacket(
                    offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, seq, yaw, pitch);
        });
    }

    public static void sendInteractEntityAt(Object entity, Object pos, boolean sneaking, boolean offhand) {
        int s;
        if (entity instanceof Integer integer) {
            s = integer.intValue();
        } else {
            Entity e = JsHelper.unwrap(entity, Entity.class);
            s = e.getId();
        }
        Vec3 p = DataHelper.createVec(pos);
        syncHotbar();
        mc.gameMode.startPrediction(mc.level, (seq) -> {
            return new ServerboundInteractPacket(
                    s, offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, p, sneaking);
        });
    }

    public static void sendSwingHand(boolean offhand) {
        mc.getConnection()
                .send(new ServerboundSwingPacket(offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND));
    }

    public static void startMine(Object pos, Object direction) {}

    public static void sendStartMining(Object pos, Object direction) {
        Direction dir = JsHelper.toEnum(direction, Direction.class);
        BlockPos blockPos = DataHelper.createBlockPos(pos);
        // todo: fix packet
        PlayerInteractionAccess.of(mc.gameMode).startMiningBlock(blockPos, dir);
    }

    public static void sendStopMining() {
        var access = PlayerInteractionAccess.of(mc.gameMode); // .sendStopBreakPacket();
        access.sendBreakPacket(true);
    }

    public static void sendStopMining(Object pos, Object direction) {
        Direction dir = JsHelper.toEnum(direction, Direction.class);
        BlockPos blockPos = DataHelper.createBlockPos(pos);
        PlayerInteractionAccess.of(mc.gameMode).sendBreakPacket(blockPos, dir, true);
    }

    public static void sendStopMining(int x, int y, int z, Object direction) {}

    public static void syncSelectedHotbar(int x) {
        PlayerInteractionAccess.of(mc.gameMode).syncSelectedHotbar(x);
    }

    public static void sendClientCommand(Object cmd) {
        ServerboundPlayerCommandPacket.Action mode = JsHelper.toEnum(cmd, ServerboundPlayerCommandPacket.Action.class);
        mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, mode));
    }

    public static void sendMoveOnGround(boolean onGround, boolean horizontalCollision) {
        mc.getConnection().send(VPacket.newOnGroundOnly(onGround, horizontalCollision));
    }

    public static void sendMovePositionAndOnGround(
            double x, double y, double z, boolean isOnGround, boolean collision) {
        mc.getConnection().send(VPacket.newPositionAndOnGround(x, y, z, isOnGround, collision));
    }

    public static void sendMoveLookAndOnGround(float yaw, float pitch, boolean isOnGround, boolean collision) {
        mc.getConnection().send(VPacket.newLookAndOnGround(yaw, pitch, isOnGround, collision));
    }

    public static void sendMoveVehicle(Entity entity) {
        mc.getConnection().send(VPacket.newVehicleMove(JsHelper.unwrap(entity, Entity.class)));
    }

    public static void sendMoveFull(
            double x, double y, double z, float yaw, float pitch, boolean isOnGround, boolean collision) {
        mc.getConnection().send(VPacket.newFull(x, y, z, yaw, pitch, isOnGround, collision));
    }

    public static void sendPlayerAction(Object action) {
        ServerboundPlayerActionPacket.Action actionPacket =
                JsHelper.toEnum(action, ServerboundPlayerActionPacket.Action.class);
        switch (actionPacket) {
            case STAB, SWAP_ITEM_WITH_OFFHAND, DROP_ITEM, DROP_ALL_ITEMS, RELEASE_USE_ITEM -> {}

            default -> throw new IllegalStateException("Unexpected value: " + actionPacket);
        }
        mc.getConnection().send(new ServerboundPlayerActionPacket(actionPacket, BlockPos.ZERO, Direction.DOWN));
    }
}
