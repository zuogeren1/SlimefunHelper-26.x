package me.matl114.hacks.modules.move;

import java.util.Optional;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.task.MovementManager;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.OptionalPrimitive;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.versioned.api.VPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

public class FloatingUtils extends BaseModule implements LegalMovementManager.MovementModifier {
    static LegalMovementManager.DelegateMovementModifier instance;
    public final ModulePath velocityManagement = makePath(Configs.MOV_CONFIG, "velocity-management");
    public final ModulePath floatingUtils = velocityManagement.add("floating-utils");
    public final ModulePath grimFloating = floatingUtils.add("grim-floating");
    public final ModulePath conditionalFreeze = floatingUtils.add("conditional-freeze");
    public final ModulePath elytraSlowFalling = floatingUtils.add("elytra-slow-falling");
    public static FloatingUtils INSTANCE;

    public FloatingUtils() {
        super("FloatingUtils");
        MovementManager.registerMovementPipeline(this);
        INSTANCE = this;
    }

    public final FlagRef enableGrim = flagBuilder(grimFloating.addEnable()).build();

    public final KeyBindRef hotkeyGrim = moduleEntry(
                    grimFloating.addHotkey(), new MultiKeyBind(), grimFloating.addEnable())
            .build();

    public final FlagRef onGroundFloat =
            flagBuilder(grimFloating.add("on-ground-float")).build();

    public final FlagRef useSnap =
            flagBuilder(grimFloating.add("use-snap-packet")).build();

    public final NBTRef<OptionalPrimitive<Double>> freezeVoid = builder(
                    conditionalFreeze.add("freeze-void"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.DOUBLE_TYPE, -2.0D))
            .build();

    public final NBTRef<OptionalPrimitive<Double>> freezeAbsoluteY = builder(
                    conditionalFreeze.add("freeze-absolute-y"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.DOUBLE_TYPE, 256.0D))
            .build();

    public final FlagRef enableElytraSlowFall =
            flagBuilder(elytraSlowFalling.addEnable()).build();

    public final KeyBindRef hotkeySlowFall = moduleEntry(
                    elytraSlowFalling.addHotkey(), new MultiKeyBind(), elytraSlowFalling.addEnable())
            .build();

    boolean forceFloatingThisTick = false;
    boolean forceOnGroundVia1205 = false;
    boolean useSnapPacket = false;

    public void setGrimFloatingTick(boolean t) {
        forceFloatingThisTick = t;
    }

    public void setForceOnGroundVia(boolean t) {
        forceOnGroundVia1205 = t;
    }

    public void setForceSilent(boolean t) {
        forceSilentThisTick = t;
    }

    public void setSendPacketIgnoreRotation(boolean t) {
        forceNotSilentThisTick = t;
    }

    public void setUseSnapRotPacket(boolean t) {
        useSnapPacket = t;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreGameTick(), this::onPreGameTick);
    }

    @Override
    public int priority() {
        return PRIORITY_MONITOR;
    }

    ServerboundMovePlayerPacket storedPacket;
    // fix timer
    boolean forceSilentThisTick = false;
    boolean forceNotSilentThisTick = false;

    public boolean workGrimFloatingThisTick() {
        return (enableGrim.get() // && !mc.player.isOnGround()
                )
                || forceFloatingThisTick;
    }

    public void onPreGameTick() {
        if (checkNull()) return;
        if (!workGrimFloatingThisTick() && enableElytraSlowFall.get()) {
            if (mc.player.isFallFlying() && !mc.player.onGround()) {
                boolean rotateYaw = Tasks.getTick() % 2 == 0;
                RotationManager.INSTANCE.request(RotationManager.ofDirection(
                                Optional.of(0.0F),
                                rotateYaw ? Optional.of(mc.player.getYRot() + 180.0F) : Optional.empty())
                        .applyMoveFix(false)
                        .priority(0));
            }
        }
    }

    @Override
    public void applyBeforeTravelTick(Event<LegalMovementManager> movementManagerEvent, Event<Vec3> moveEvent) {
        if (workGrimFloatingThisTick()) {
            movementManagerEvent.cancel();
        }
    }

    boolean lastUsingOnGroundDeceive;

    @Override
    public void applyBeforeMovementPacketModify(Event<LegalMovementManager> movementManagerEvent) {
        boolean conditionalFreeze = false;
        if (!conditionalFreeze && freezeVoid.get().isPresent()) {
            if (mc.player.getY() <= mc.level.getMinY() + freezeVoid.get().getValue()) {
                conditionalFreeze = true;
            }
        }
        if (!conditionalFreeze && freezeAbsoluteY.get().isPresent()) {
            if (mc.player.getY() <= freezeAbsoluteY.get().getValue()) {
                conditionalFreeze = true;
            }
        }
        if (conditionalFreeze) {
            forceFloatingThisTick = true;
        }

        if (workGrimFloatingThisTick()) {
            movementManagerEvent.cancel();
            movementManagerEvent.context.playerStatus.restorePos();
            // also reset onground status to avoid false flag
            boolean useOnGroundFloat = ((onGroundFloat.get()) || (forceOnGroundVia1205));
            if (useOnGroundFloat) {
                storedPacket =
                        LegacySnapRotManager.INSTANCE.createSnapAt(mc.player.getXRot(), mc.player.getYRot(), true);
                mc.player.setOnGround(true);
                lastUsingOnGroundDeceive = true;
            } else {
                if (lastUsingOnGroundDeceive) {
                    lastUsingOnGroundDeceive = false;
                    storedPacket = LegacySnapRotManager.INSTANCE.createSnapAt(
                            mc.player.getXRot(), mc.player.getYRot(), PlayerStateManager.INSTANCE.lastHasGroundSupport);
                } else {
                    storedPacket = VPacket.newLookAndOnGround(
                            mc.player.getYRot(),
                            mc.player.getXRot(),
                            mc.player.onGround(),
                            mc.player.horizontalCollision);
                    if (useSnapPacket) {
                        storedPacket = LegacySnapRotManager.INSTANCE.createAsSnap(storedPacket);
                    }
                }
            }
        } else {
            lastUsingOnGroundDeceive = false;
        }
    }

    @Override
    public boolean postModify(Event<LegalMovementManager> movementManagerEvent, boolean enabledThisTick) {
        forceFloatingThisTick = false;
        forceOnGroundVia1205 = false;

        useSnapPacket = useSnap.get();
        if (storedPacket != null) {
            // optimize current, only if rotation different, or has No Position packet, send duplicate packet
            if (!forceSilentThisTick) {
                boolean shouldSend = false;
                if (forceNotSilentThisTick) {
                    shouldSend = true;
                } else if (PlayerStateManager.INSTANCE.isRotationDifferent(
                        storedPacket.getXRot(mc.player.getXRot()), storedPacket.getYRot(mc.player.getYRot()))) {
                    shouldSend = true;
                }
                if (shouldSend) {
                    mc.getConnection().send(storedPacket);
                }
            }
            // Listener.sendPacketNoEvents(storedPacket);
            storedPacket = null;
        }
        forceSilentThisTick = false;
        forceNotSilentThisTick = false;
        return true;
    }
}
