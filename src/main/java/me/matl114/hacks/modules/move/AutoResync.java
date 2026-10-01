package me.matl114.hacks.modules.move;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.utils.Debug;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.MathUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

@SuppressWarnings("all")
public class AutoResync extends BaseModule {
    public final ModulePath moveSafety = makePath(Configs.MOV_CONFIG, "move-safety");

    public static AutoResync INSTANCE;

    public AutoResync() {
        super("AutoResync");
        INSTANCE = this;
    }

    public Optional<Vec3> pos;
    public int ticksTilExpire;

    public final FlagRef autoResyncRot =
            flagBuilder(moveSafety.add("auto-resync-rotation")).build();

    public final FlagRef modifyPacketRot =
            flagBuilder(moveSafety.add("auto-resync-rot-modify-packet")).build();

    public final FlagRef noVelocitySetback =
            flagBuilder(moveSafety.add("auto-resync-velocity")).build();

    public final FlagRef autoResyncPos =
            flagBuilder(moveSafety.add("auto-resync-pos")).build();

    public final DoubleRef autoResyncPosDistance = builder(moveSafety.add("auto-resync-distance"), DoubleRef.TYPE)
            .defaultValue(10.0D)
            .build();

    public final FlagRef logAutoResync =
            flagBuilder(moveSafety.add("log-auto-resync-request")).build();

    public final IntRef expireTick = builder(moveSafety.add("auto-resync-request-expire-tick"), IntRef.TYPE)
            .defaultValue(10)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final FlagRef recursive =
            flagBuilder(moveSafety.add("auto-resync-request-recursively")).build();

    public void setAutoResyncSchedule(Optional<Vec3> pos) {
        this.setAutoResyncSchedule(pos, expireTick.get());
    }

    public void setAutoResyncSchedule(Optional<Vec3> pos, int ticksExpire) {
        this.pos = pos;
        this.ticksTilExpire = ticksExpire + Tasks.getTick();
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPacketPoint().getChannel(ClientboundPlayerPositionPacket.class), this::onSetBack);
        registerListener(
                Listener.getPacketPreHandlePoint().getChannel(ClientboundPlayerPositionPacket.class),
                this::onPreSetBack);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundPlayerPositionPacket.class),
                this::onPostSetBack);
        registerListener(
                Listener.getPacketPreHandlePoint().getChannel(ClientboundPlayerRotationPacket.class),
                this::onPreRotate);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundPlayerRotationPacket.class),
                this::onPostRotate);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
        registerListener(Listener.getPlayerRespawnPoint(), this::onWorldSwitch);
    }

    int worldSwitchTick = 0;

    public void onWorldSwitch(Event<LocalPlayer> event) {
        worldSwitchTick = Tasks.getTick();
    }

    public Vec2 restoreRot = null;

    public void onSetBack(Event<ClientboundPlayerPositionPacket> event) {
        if (checkNull()) return;
        if (event.isCancelled()) return;
        if (mc.player == null) return;
        // just switch world for no more than 10 second, it is a game join, do not apply any resync
        if (worldSwitchTick + 100 > Tasks.getTick()) return;
        if (mc.player.position().equals(Vec3.ZERO)) {
            // ignoring first spawn packets
            return;
        }
        if (mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            // do not modify spectator tp
            return;
        }
        boolean currentOnGround = mc.player.onGround();
        if (ticksTilExpire > Tasks.getTick() && pos != null) {
            // auto resync
            Vec3 resyncToPos = pos.orElseGet(mc.player::position);
            ClientboundPlayerPositionPacket packet1 = event.context;
            Vec3 resyncPos = getPosition(packet1);
            double sqdistance = resyncPos.distanceToSqr(mc.player.position());
            double sqdistance2 = resyncPos.distanceToSqr(resyncToPos);
            if (sqdistance > 1E-4
                    && sqdistance < MathUtils.s2(128)
                    && sqdistance2 > 1E-4
                    && sqdistance2 < MathUtils.s2(128)) {
                // don't so far, it may be a real teleport
                if (logAutoResync.get()) {
                    Debug.chat("Auto Resync triggered!");
                }
                mc.getConnection().send(new ServerboundAcceptTeleportationPacket(packet1.id()));
                executeResyncTo(resyncPos, resyncToPos, currentOnGround);
                if (recursive.get()) {
                    mc.player.setPos(resyncToPos);
                    setAutoResyncSchedule(Optional.empty());
                }
                event.cancel();
                return;
            }
        }
        if (autoResyncPos.get()) {
            Vec3 resyncToPos = mc.player.position();
            BlockPos blockPos = BlockPos.containing(resyncToPos);
            // do not resync in unloaded chunks
            if (mc.level.getChunkSource().hasChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4)) {
                ClientboundPlayerPositionPacket packet1 = event.context;
                Vec3 resyncPos = getPosition(packet1);
                double sqDistance = resyncToPos.distanceToSqr(resyncPos);
                if (autoResyncPosDistance.get() > 0 && sqDistance < MathUtils.s2(autoResyncPosDistance.get())) {
                    mc.getConnection().send(new ServerboundAcceptTeleportationPacket(packet1.id()));
                    executeResyncTo(resyncPos, resyncToPos, currentOnGround);
                    event.cancel();
                    return;
                }
            }
        }
        // remove rot
        boolean recreate = false;
        var packet = event.context();
        Set<Relative> flags = packet.relatives();
        Set<Relative> newFlags = null;
        PositionMoveRotation pos = packet.change();
        Vec3 position = pos.position();
        Vec3 deltaMovement = pos.deltaMovement();
        float yaw = pos.yRot();
        float pitch = pos.xRot();
        if (autoResyncRot.get()) {
            if (modifyPacketRot.get()) {
                recreate = true;
                if (newFlags == null) {
                    newFlags = new HashSet<>(flags);
                }
                newFlags.add(Relative.X_ROT);
                newFlags.add(Relative.Y_ROT);
                yaw = 0;
                pitch = 0;
            }
        }
        if (recreate && newFlags != null) {
            event.context(new ClientboundPlayerPositionPacket(
                    packet.id(), new PositionMoveRotation(position, deltaMovement, yaw, pitch), newFlags));
        }
    }

    public void onPreSetBack(Event<ClientboundPlayerPositionPacket> event) {
        if (checkNull()) return;
        if (autoResyncRot.get() && !modifyPacketRot.get()) {
            restoreRot = new Vec2(mc.player.getXRot(), mc.player.getYRot());
            mc.player.setXRot(PlayerStateManager.INSTANCE.lastPitch);
            mc.player.setYRot(PlayerStateManager.INSTANCE.lastYaw);
        }
    }

    public void onPreRotate(Event<ClientboundPlayerRotationPacket> eventRotate) {
        if (checkNull()) return;
        if (autoResyncRot.get()) {
            if (modifyPacketRot.get()) {
                eventRotate.cancel();
            } else {
                restoreRot = new Vec2(mc.player.getXRot(), mc.player.getYRot());
                mc.player.setXRot(PlayerStateManager.INSTANCE.lastPitch);
                mc.player.setYRot(PlayerStateManager.INSTANCE.lastYaw);
            }
        }
    }

    public void onPostSetBack(Event<ClientboundPlayerPositionPacket> event) {
        if (checkNull()) return;
        if (checkNull()) return;
        if (restoreRot != null) {
            EntityUtils.setEntityPitchSafe(mc.player, restoreRot.x);
            PlayerStateManager.setPlayerYawSafe(mc.player, restoreRot.y);
            // fucking very important. shit
            ClientPlayerAccess.of(mc.player).resyncRot();
            restoreRot = null;
        }
    }

    public void onPostRotate(Event<ClientboundPlayerRotationPacket> eventRotate) {
        if (checkNull()) return;
        if (restoreRot != null) {
            EntityUtils.setEntityPitchSafe(mc.player, restoreRot.x);
            PlayerStateManager.setPlayerYawSafe(mc.player, restoreRot.y);
            restoreRot = null;
        }
    }

    public void executeResyncTo(Vec3 resyncPos, Vec3 resyncToPos, boolean currentOnGround) {
        mc.player.setPos(resyncPos);
        mc.player.setOnGround(false);
        //                    mc.getConnection().sendPacket(new
        // PlayerMoveC2SPacket.PositionAndOnGround(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
        // false));
        if (currentOnGround) {
            resyncToPos = resyncToPos.add(0, 1e-6, 0);
        }
        MovTasks.scheduleTpInternal(MovTasks.createPlayerMovContext(), resyncToPos, 200, false, true, true);
        ticksTilExpire = -1;
        pos = null;
    }

    public Vec3 getPosition(ClientboundPlayerPositionPacket packet) {
        PositionMoveRotation entityPosition = PositionMoveRotation.of(mc.player);
        PositionMoveRotation entityPosition2 =
                PositionMoveRotation.calculateAbsolute(entityPosition, packet.change(), packet.relatives());
        return entityPosition2.position();
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        switch (event.context.getValue()) {
            case HACKING, VANILLA, AC_VULCAN -> {
                if (autoResyncPosDistance.get() < 0) {
                    autoResyncPosDistance.set(-autoResyncPosDistance.get());
                }
            }
            default -> {
                if (autoResyncPosDistance.get() > 0) {
                    autoResyncPosDistance.set(-autoResyncPosDistance.get());
                }
            }
        }
    }
}
