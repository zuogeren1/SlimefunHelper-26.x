package me.matl114.hacks.modules.move;

import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.access.PlayerMoveC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.versioned.api.VPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class LegacySnapRotManager extends BaseModule {
    public static LegacySnapRotManager INSTANCE;

    public LegacySnapRotManager() {
        super("LegacySnapRotManager");
        INSTANCE = this;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getEntityPreTickListener().getChannel(EntityTypes.PLAYER), this::onPrePlayerTick);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundUseItemPacket.class), this::onInteractItem);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundMovePlayerPacket.class),
                this::onSendPlayerPosRotPacket,
                Integer.MIN_VALUE);
    }

    Vec2 lastSnapPitchYaw;

    public void onPrePlayerTick(Event<Player> eventPre) {
        if (mc.player != null && eventPre.context == mc.player) {
            resyncSnap();
        }
    }

    public boolean betweenViaPacket;

    public void onSendPlayerPosRotPacket(Event<ServerboundMovePlayerPacket> event) {
        if (betweenViaPacket
                && ViaFabricPlusHooks.isSupportDupRot()
                && event.context instanceof ServerboundMovePlayerPacket.PosRot move
                && move instanceof PlayerMoveC2SPacketAccess acc) {
            acc.setCause(PlayerMoveC2SPacketAccess.Cause.LEGACY_SNAP);
        }
        // reset snap packets because there are other rot packets
        if (event.context.hasRotation()) {
            lastSnapPitchYaw = null;
        }
    }

    public void onInteractItem(Event<ServerboundUseItemPacket> eventInteract) {
        if (false && lastSnapPitchYaw != null) {
            //#if MC >= 260300
            //$$ float pitch = eventInteract.context.xRot();
            //$$ float yaw = eventInteract.context.yRot();
            //#else
            float pitch = eventInteract.context.getXRot();
            float yaw = eventInteract.context.getYRot();
            //#endif
            if (EntityUtils.isRotationDifferent(lastSnapPitchYaw.x, pitch, lastSnapPitchYaw.y, yaw)
                    && PlayerStateManager.INSTANCE.isRotationDifferent(pitch, yaw)) {
                snapAt(pitch, yaw, false);
            }
        }
    }

    public void resyncSnap() {
        if (lastSnapPitchYaw != null && PlayerStateManager.INSTANCE.isRotationDifferent()) {
            ClientPlayerAccess access = ClientPlayerAccess.of(mc.player);
            access.resyncRot();
        }
        lastSnapPitchYaw = null;
    }

    public void snapAt(Vec3 look, boolean force) {
        Vec2 py = EntityUtils.rotationToPitchYaw(look.normalize());
        snapAt(py.x, py.y, force);
    }

    public void snapAt(float pitch, float yaw, boolean force) {
        if (force || PlayerStateManager.INSTANCE.isRotationDifferent(pitch, yaw)) {
            mc.getConnection().send(createSnapAt(pitch, yaw));
        }
        lastSnapPitchYaw = new Vec2(pitch, yaw);
    }

    public ServerboundMovePlayerPacket createSnapAt(Vec3 look) {
        Vec2 py = EntityUtils.rotationToPitchYaw(look.normalize());
        return createSnapAt(py.x, py.y);
    }

    public ServerboundMovePlayerPacket createSnapAt(Vec3 look, boolean onGroundOverride) {
        Vec2 py = EntityUtils.rotationToPitchYaw(look.normalize());
        return createSnapAt(py.x, py.y, onGroundOverride);
    }

    public ServerboundMovePlayerPacket createSnapAt(float pitch, float yaw) {
        float lastYaw = PlayerStateManager.INSTANCE.lastYaw;
        return PlayerMoveC2SPacketAccess.setCause(
                VPacket.newFull(
                        PlayerStateManager.INSTANCE.lastX,
                        PlayerStateManager.INSTANCE.lastY,
                        PlayerStateManager.INSTANCE.lastZ,
                        EntityUtils.getSafeYaw(lastYaw, yaw),
                        EntityUtils.getSafePitch(pitch),
                        PlayerStateManager.INSTANCE.lastOnGround,
                        mc.player.horizontalCollision),
                PlayerMoveC2SPacketAccess.Cause.LEGACY_SNAP);
    }

    public ServerboundMovePlayerPacket createSnapAt(float pitch, float yaw, boolean onGroundOverride) {
        float lastYaw = PlayerStateManager.INSTANCE.lastYaw;
        return PlayerMoveC2SPacketAccess.setCause(
                VPacket.newFull(
                        PlayerStateManager.INSTANCE.lastX,
                        PlayerStateManager.INSTANCE.lastY,
                        PlayerStateManager.INSTANCE.lastZ,
                        EntityUtils.getSafeYaw(lastYaw, yaw),
                        EntityUtils.getSafePitch(pitch),
                        onGroundOverride,
                        mc.player.horizontalCollision),
                PlayerMoveC2SPacketAccess.Cause.LEGACY_SNAP);
    }

    public ServerboundMovePlayerPacket createAsSnap(ServerboundMovePlayerPacket full) {
        var pkt = VPacket.newFull(
                full.getX(PlayerStateManager.INSTANCE.lastX),
                full.getY(PlayerStateManager.INSTANCE.lastY),
                full.getZ(PlayerStateManager.INSTANCE.lastZ),
                PlayerStateManager.INSTANCE.lastYaw,
                PlayerStateManager.INSTANCE.lastPitch,
                full.isOnGround(),
                VPacket.getCollisionFlag(full));
        PlayerMoveC2SPacketAccess.of(pkt).setCause(PlayerMoveC2SPacketAccess.Cause.LEGACY_SNAP);
        return pkt;
    }

    public void sendAsSnap(ServerboundMovePlayerPacket full) {
        ServerboundMovePlayerPacket recreateFull = createAsSnap(full);
        mc.getConnection().send(recreateFull);
    }
}
