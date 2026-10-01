package me.matl114.hacks.modules.combat;

import java.awt.*;
import java.util.HashSet;
import java.util.Set;
import me.matl114.events.*;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render3D;
import me.matl114.events.packets.PacketStorage;
import me.matl114.events.packets.PacketStorageImpl;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.RenderUtils;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class BackTrack extends BaseModule {
    public BackTrack() {
        super("BackTrack");
        bindFlag(enable);
    }

    public final ModulePath lagUtils = makePath(Configs.COMBAT_CONFIG, "lag-utils");
    public ModulePath bt = lagUtils.add("back-track");
    public final FlagRef enable = flagBuilder(bt.addEnable()).build();

    public final KeyBindRef hotkey =
            moduleEntry(bt.addHotkey(), new MultiKeyBind(), bt.addEnable()).build();

    public final DoubleRef maxDelay =
            doubleBuilder(bt.add("max-delay")).defaultValue(50.0D).build();

    public final FlagRef render = flagBuilder(bt.add("render-old")).build();

    public final DoubleRef maxDistance =
            doubleBuilder(bt.add("max-distance")).defaultValue(5.0D).build();

    public final FlagRef playerOnly = flagBuilder(bt.add("player-only")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(PacketManager.getPacketQueueInEvent(), this::onQueuePlayerPosition);
        registerListener(PacketManager.getQueueShutdownEvent(), this::onShutdownQueue);
        registerListener(Listener.getPreTick(), this::onTick);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
    }

    @Override
    public void onEnableModule() {
        super.onEnableModule();
        onShutdown();
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        onShutdown();
    }

    public Entity currentTarget;
    public Vec3 lastTrackingPosition;
    public volatile boolean shouldDelay;

    public void onShutdown() {
        setNoTarget();
        setNoDelay();
    }

    public void setTarget(Entity entity) {
        currentTarget = entity;
        lastTrackingPosition = entity.position();
    }

    public void setNoTarget() {
        currentTarget = null;
        lastTrackingPosition = null;
    }

    public void setNoDelay() {
        if (shouldDelay) {
            shouldDelay = false;
            PacketManager.scheduleImmediateFlush();
        }
    }

    public void setDelay() {
        shouldDelay = true;
    }

    public void refreshTarget() {
        if (enable.get()) {
            Entity entity = TargetSelector.INSTANCE.searchAttackEntity(
                    maxDistance.get(),
                    true,
                    playerOnly.get() ? (ev) -> ev instanceof Player : (ev) -> ev instanceof LivingEntity);
            if (entity != currentTarget) {
                if (entity != null) {
                    setTarget(entity);
                } else {
                    setNoTarget();
                }
                setNoDelay();
            } else {
                // tick
                if (currentTarget == null) {
                    setNoDelay();
                } else if (lastTrackingPosition == null) {
                    lastTrackingPosition = currentTarget.position();
                } else if (!TargetSelector.INSTANCE.isWithinAttackRange(
                        mc.player.position(),
                        currentTarget.getBoundingBox(),
                        CombatExtra.INSTANCE.getAttackAtTargetRange(currentTarget))) {
                    setNoDelay();
                }
            }
        } else {
            setNoTarget();
            setNoDelay();
        }
    }

    Set<PacketType<?>> movePlayerEntityTypes = new HashSet<>();

    {
        movePlayerEntityTypes.add(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS);
        movePlayerEntityTypes.add(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS_ROT);
    }

    public void onQueuePlayerPosition(Event<PacketStorage> event) {
        if (shouldDelay) {
            long currentMs = System.currentTimeMillis();
            if (event.context.timestampMS() + maxDelay.get() < currentMs) {
                return;
            }
            event.cancel();
        }
        if (enable.get() && currentTarget != null && event.<Boolean>getArgs(1)) {
            var storage = event.context;
            if (storage instanceof PacketStorageImpl impl) {
                var packet = impl.packet();
                if (PacketManager.isAsyncOrNotTransactionS2CPacket(packet)) return;
                if (packet instanceof ClientboundEntityPositionSyncPacket positionSync
                        && positionSync.id() == currentTarget.getId()) {
                    onShutdown();
                    return;
                }
                if (packet instanceof ClientboundTeleportEntityPacket position
                        && position.id() == currentTarget.getId()) {
                    onShutdown();
                    return;
                }
                if (packet instanceof ClientboundMoveEntityPacket entityMove
                        && entityMove.getEntity(mc.level) == currentTarget
                        && entityMove.hasPosition()) {
                    VecDeltaCodec trackedPosition;
                    Vec3 vec3d;
                    if (lastTrackingPosition == null) {
                        trackedPosition = currentTarget.getPositionCodec();
                    } else {
                        trackedPosition = new VecDeltaCodec();
                        trackedPosition.setBase(lastTrackingPosition);
                    }
                    vec3d = trackedPosition.decode(
                            (long) entityMove.getXa(), (long) entityMove.getYa(), (long) entityMove.getZa());
                    boolean lastDelay = shouldDelay;
                    handleTrackEntityPosition(vec3d);
                    lastTrackingPosition = vec3d;
                    if (shouldDelay) {
                        event.cancel();
                    }
                    return;
                }
                if (packet instanceof ClientboundEntityEventPacket entityStatus
                        && entityStatus.getEventId() == EntityEvent.PROTECTED_FROM_DEATH
                        && entityStatus.getEntity(mc.level) == mc.player) {
                    setNoDelay();
                    return;
                }
                if (shouldDelay) {
                    event.cancel();
                }
            }
        }
    }

    public void handleTrackEntityPosition(Vec3 position) {
        if (lastTrackingPosition != null) {
            double attackRange = CombatExtra.INSTANCE.getAttackAtTargetRange(currentTarget) - 0.02;
            AABB currentBox = currentTarget.dimensions.makeBoundingBox(lastTrackingPosition);
            AABB futureBox = currentTarget.dimensions.makeBoundingBox(position);
            boolean currentCanAttack =
                    TargetSelector.INSTANCE.isWithinAttackRange(mc.player.position(), currentBox, attackRange);
            boolean futureCanAttack =
                    TargetSelector.INSTANCE.isWithinAttackRange(mc.player.position(), futureBox, attackRange);
            if (currentCanAttack && !futureCanAttack) {
                setDelay();
            } else if (futureCanAttack) {
                // attack window
                setNoDelay();
            } else {
                Vec3 bestEyePos = TargetSelector.INSTANCE.getBestAttackEyePos(mc.player.position(), currentBox);
                double currentDistance = currentBox.distanceToSqr(bestEyePos);
                double futureDistance = futureBox.distanceToSqr(bestEyePos);
                if (futureDistance > currentDistance) {
                    // leaving
                    setDelay();
                }
            }
        }
    }

    public void onShutdownQueue(Event<Void> event) {
        onShutdown();
    }

    public void onTick(Event<Void> eventTick) {
        if (checkNull()) {
            onShutdown();
            return;
        }
        refreshTarget();
    }

    public void onRender(Event<Render3D> eventMatrixStack) {
        if (render.get() && shouldDelay && lastTrackingPosition != null && currentTarget != null) {
            AABB boundingBox = currentTarget.dimensions.makeBoundingBox(lastTrackingPosition);
            RenderUtils.startDrawVirtual(eventMatrixStack.context.stack());
            try {
                RenderUtils.drawOutlinedBox(
                        eventMatrixStack.context.stack(),
                        boundingBox.getMinPosition(),
                        boundingBox.getMaxPosition(),
                        Color.ORANGE);
            } finally {
                RenderUtils.stopDrawVirtual(eventMatrixStack.context.stack());
            }
        }
    }
}
