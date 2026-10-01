package me.matl114.hacks.modules.move;

import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.combat.ElytraBot;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.OptionalPrimitive;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.move.ElytraOptimizeUtils;
import me.matl114.hacks.utils.move.FlightVelocity;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.Debug;
import me.matl114.utils.entity.PlayerInputUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class ElytraFlight extends BaseModule implements LegalMovementManager.MovementModifier {
    public static ElytraFlight INSTANCE;
    public final ModulePath elytra = makePath(Configs.MOV_CONFIG, "elytra");
    public final ModulePath simpleFlightControl = elytra.add("simple-flight-control");
    public final ModulePath customFireworks = elytra.add("custom-fireworks");

    public FlagRef enable =
            flagBuilder(simpleFlightControl.add("enable-control")).build();

    public KeyBindRef hotkey = moduleEntry(
                    simpleFlightControl.add("enable-control-hotkey"),
                    new MultiKeyBind(),
                    simpleFlightControl.add("enable-control"),
                    moduleMeta(() -> this.controlMode))
            .build();

    public final DoubleRef packetMotion = builder(customFireworks.add("motion-amount"), DoubleRef.TYPE)
            .defaultValue(0.05)
            .validator(Configs.doubleRange(0.0, 10000.0))
            .build();

    public final EnumRef<ElytraExtra.MotionMode> motionMode = builder(
                    simpleFlightControl.add("motion-mode"), ElytraExtra.MotionMode.class)
            .defaultValue(ElytraExtra.MotionMode.VOID)
            .build();

    public final IntRef fireworksRotationLastingTicks = builder(
                    simpleFlightControl.add("firework-rotation-last-ticks"), Integer.class)
            .defaultValue(3)
            .show(() -> motionMode.get().isIn(ElytraExtra.MotionMode.FIRE_WORKS))
            .build();

    public final FlagRef rotateWhenVoid = builder(simpleFlightControl.add("rotate-when-void-motion"), Boolean.class)
            .defaultValue(true)
            .show(() -> motionMode.get().isIn(ElytraExtra.MotionMode.VOID))
            .build();

    public final EnumRef<Mode> controlMode = builder(simpleFlightControl.add("flight-mode"), Mode.class)
            .defaultValue(Mode.CONTROL)
            .build();

    public final KeyBindRef motionToggle = hotkey(simpleFlightControl.add("flight-toggle"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler(this::toggleMode))
            .build();

    public final FlagRef useFloatingUtils =
            flagBuilder(simpleFlightControl.add("use-floating-utils")).build();
    public final FlagRef horizontalFlyNoGravity = builder(
                    simpleFlightControl.add("horizontal-no-gravity"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef pauseWhenAccelerate = builder(simpleFlightControl.add("pause-when-accelerate"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef landAutoClose =
            flagBuilder(simpleFlightControl.add("land-auto-close")).build();

    public final NBTRef<OptionalPrimitive<Integer>> takeOffOptimize = builder(
                    simpleFlightControl.add("hold-jump-takeoff-ticks"), OptionalPrimitive.INT_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.INT_TYPE, 10))
            .build();

    public final DoubleRef motionArg = builder(simpleFlightControl.add("motion-lerp-argument"), DoubleRef.TYPE)
            .defaultValue(1.0D)
            .build();

    public final FlagRef motionArgLerpStarting = builder(simpleFlightControl.add("motion-lerp-starting"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef useAutoRescale = flagBuilder(simpleFlightControl.add("use-auto-rescale"))
            .show(() -> ElytraExtra.INSTANCE.autoRescale.get())
            .build();

    public final NBTRef<OptionalPrimitive<Double>> overridePullupAngle = builder(
                    simpleFlightControl.add("override-pullup-angle"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.DOUBLE_TYPE, 45.0D))
            .validator(s -> s.getValue() > 0 && s.getValue() < 90)
            .show(() -> this.controlMode.get().isIn(Mode.CONTROL))
            .build();

    public final NBTRef<OptionalPrimitive<Double>> overrideDownwardAngle = builder(
                    simpleFlightControl.add("override-downward-angle"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.DOUBLE_TYPE, 45.0D))
            .validator(s -> s.getValue() > 0 && s.getValue() < 90)
            .show(() -> this.controlMode.get().isIn(Mode.CONTROL))
            .build();

    public final NBTRef<OptionalPrimitive<Double>> overrideHorizontalFlyAngle = builder(
                    simpleFlightControl.add("override-horizontal-fly-angle"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.DOUBLE_TYPE, 0.1))
            .validator(s -> s.getValue() > -90 && s.getValue() < 90)
            .show(() -> this.controlMode.get().isIn(Mode.CONTROL))
            .build();

    public final FlagRef autoRescaleBestClimbingSpeed = flagBuilder(
                    simpleFlightControl.add("use-auto-rescale-best-climbing-speed"))
            .show(() -> ElytraExtra.INSTANCE.autoRescale.get())
            .build();

    boolean currentTakeOff = false;
    public final FlagRef autoFly =
            flagBuilder(simpleFlightControl.add("auto-fly")).build();

    public final KeyBindRef autoFlyKey = toggleHotkey(
                    simpleFlightControl.add("auto-fly-hotkey"), new MultiKeyBind(), simpleFlightControl.add("auto-fly"))
            .build();

    public final FlagRef autoFlyAutoJumpOff =
            flagBuilder(simpleFlightControl.add("auto-fly-auto-jump-off")).build();

    public final FlagRef autoFlyLandAutoClose =
            flagBuilder(simpleFlightControl.add("auto-fly-land-auto-close")).build();

    private static LegalMovementManager.DelegateMovementModifier instance;

    public ElytraFlight() {
        super("ElytraFlight");
        if (instance == null) {
            instance = new LegalMovementManager.DelegateMovementModifier(this::cast);
            MovTasks.PLAYER_PIPELINE_0.addMovementModifierFactory(() -> instance);
        }
        instance.setDelegate(this::cast);
        bindFlag(enable);
        INSTANCE = this;
    }

    @Override
    public int priority() {
        return PRIORITY_COMMON;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onPresetLoad);
    }

    public boolean toggleMode() {
        controlMode.next();
        Debug.chat(
                ChatUtils.stringToText("&c[ElytraFlight] &fMode switch to"),
                controlMode.get().getDisplay());
        return false;
    }

    public boolean shouldControlFlight() {
        return enable.get() || (ElytraBot.INSTANCE.canControlFlight());
    }

    public boolean shouldFlyRocketOnFirstOff() {
        if (shouldControlFlight()) {
            if (autoFly.get()) return true;
            if (PlayerInputUtils.of(mc.options).hasMovementControl()) return true;
            return !useFloatingUtils.get();
        } else {
            return false;
        }
    }

    Vec3 lastVelocity = null;
    int holdJumpCounter = 0;

    public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {
        LocalPlayer player = movementManagerEvent.context.playerStatus.entity;
        if (player.isFallFlying()) {
            currentTakeOff = true;
        }
        if (mc.options.keyJump.isDown()) {
            holdJumpCounter++;
        } else {
            holdJumpCounter = 0;
        }

        if (shouldControlFlight()) {
            if (!player.isFallFlying()
                    && takeOffOptimize.get().isPresent()
                    && holdJumpCounter >= takeOffOptimize.get().getValue()) {
                if (!mc.player.onGround() && mc.player.tryToStartFallFlying()) {
                    MovExtra.INSTANCE.sendPacketsForPreStartFallFlying();
                    mc.getConnection()
                            .send(new ServerboundPlayerCommandPacket(
                                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    MovExtra.INSTANCE.sendPacketsForPostStartFallFlying();
                }
            }
            if (player.isFallFlying()) {
                if (lastVelocity == null) {
                    lastVelocity = Vec3.ZERO;
                }
                if (MovTasks.getElytraGrimAccelerate().enable.get()) {
                    // GrimAccelerate on
                    // close
                    return;
                }
                Vec3 controlMotion = new Vec3(0, 0, 0);
                boolean shouldControl = false;
                double motionAmount = this.packetMotion.get();
                boolean shouldCheckRocket = false;
                boolean shouldControlRotation = false;
                PlayerInputUtils.Input input = PlayerInputUtils.of(mc.options);
                switch (controlMode.get()) {
                    case CONTROL -> {
                        boolean packetMotion = true;

                        Vec3 movementInput = new Vec3(input.sidewaysSpeed(), input.upwardSpeed(), input.forwardSpeed());
                        Vec3 velocity = EntityUtils.movementInputToVelocity(movementInput, 1.0F, player.getYRot());
                        if (useAutoRescale.get()
                                && autoRescaleBestClimbingSpeed.get()
                                && movementInput.horizontalDistance() > 0) {
                            if (movementInput.y > 0 && velocity.y > 0) {

                                velocity = ElytraOptimizeUtils.calculateBestPullupSpeed(velocity);
                            } else if (movementInput.y < 0 && velocity.y < 0) {
                                velocity = ElytraOptimizeUtils.calculateBestDownForwardSpeed(velocity, false);
                            }
                        }
                        if (movementInput.horizontalDistanceSqr() > 0.0D) {
                            if (movementInput.y > 0) {
                                if (overridePullupAngle.get().isPresent()) {
                                    double angle = overridePullupAngle.get().getValue();
                                    double yLevel =
                                            Math.tan(Math.abs(Math.toRadians(angle))) * velocity.horizontalDistance();
                                    velocity = velocity.with(Direction.Axis.Y, yLevel);
                                }
                            } else if (movementInput.y < 0) {
                                if (overrideDownwardAngle.get().isPresent()) {
                                    double angle = overrideDownwardAngle.get().getValue();
                                    double yLevel =
                                            Math.tan(Math.abs(Math.toRadians(angle))) * velocity.horizontalDistance();
                                    velocity = velocity.with(Direction.Axis.Y, -yLevel);
                                }
                            } else {
                                if (overrideHorizontalFlyAngle.get().isPresent()) {
                                    double angle =
                                            overrideHorizontalFlyAngle.get().getValue();
                                    double yLevel = Math.tan(Math.toRadians(angle)) * velocity.horizontalDistance();
                                    velocity = velocity.with(Direction.Axis.Y, -yLevel);
                                }
                            }
                        }

                        if (motionMode.get() == ElytraExtra.MotionMode.FIRE_WORKS) {
                            packetMotion = false;
                            shouldCheckRocket = true;
                            if (MovTasks.getElytraExtra().canFireworkControlMotion()) {
                                packetMotion = true;
                            }
                            if (ElytraExtra.INSTANCE.canFireworkControlMotion(fireworksRotationLastingTicks.get())) {
                                shouldControlRotation = true;
                            }
                        } else if (motionMode.get() == ElytraExtra.MotionMode.VOID) {
                            if (rotateWhenVoid.get()) {
                                shouldControlRotation = true;
                            }
                        }
                        controlMotion = velocity;
                        if (packetMotion) {
                            shouldControl = true;
                        }
                    }
                    case ROTATION -> {
                        // todo
                        boolean packetMotion = true;
                        Vec3 velocity = EntityUtils.lookCoordToPos(
                                player.getXRot(), player.getYRot(), input.sidewaysSpeed(), 0, input.forwardSpeed());
                        Vec3 vertical = new Vec3(0, input.upwardSpeed(), 0);
                        velocity = velocity.add(vertical);

                        if (input.upwardSpeed() > 0
                                && velocity.y > 0
                                && useAutoRescale.get()
                                && autoRescaleBestClimbingSpeed.get()) {
                            velocity = ElytraOptimizeUtils.calculateBestPullupSpeed(velocity);
                        }

                        if (motionMode.get() == ElytraExtra.MotionMode.FIRE_WORKS) {
                            packetMotion = false;
                            shouldCheckRocket = true;
                            if (MovTasks.getElytraExtra().canFireworkControlMotion()) {
                                packetMotion = true;
                            }
                            if (ElytraExtra.INSTANCE.canFireworkControlMotion(fireworksRotationLastingTicks.get())) {
                                shouldControlRotation = true;
                            }
                        } else if (motionMode.get() == ElytraExtra.MotionMode.VOID) {
                            if (rotateWhenVoid.get()) {
                                shouldControlRotation = true;
                            }
                        }
                        controlMotion = velocity;
                        if (packetMotion) {
                            shouldControl = true;
                        }
                    }
                }
                if (controlMotion.lengthSqr() < 1E-6 && autoFly.get()) {
                    controlMotion = mc.player.getLookAngle();
                }
                Vec3 wayVector = controlMotion.normalize();

                Vec3 realVector = wayVector.scale(motionAmount);
                double a = this.motionArg.get();
                if (a != 1.0D
                        && (motionArgLerpStarting.get()
                                || lastVelocity.lengthSqr() > 0.01 * motionAmount * motionAmount)) {
                    realVector = realVector.scale(a).add(lastVelocity.scale(1.0D - a));
                    // 当玩家在操纵的时候，永远保持最高速
                    // 否则以1 - a为系数衰减
                    if (wayVector.lengthSqr() > 1E-6) {
                        realVector = realVector.normalize().scale(motionAmount);
                    }
                }
                // add custom elytra event for bot to control elytra
                FlightVelocity velocity =
                        new FlightVelocity(realVector, motionAmount, FlightVelocity.Mode.ELYTRA_FLIGHT);
                Listener.getCustomListener().broadcast(new EventContainer<>(FlightVelocity.class, velocity));
                realVector = velocity.toVelocity();
                lastVelocity = realVector;
                if (lastVelocity.length() < 0.1 * motionAmount) {
                    lastVelocity = Vec3.ZERO;
                }
                // add FloatingUtils
                if (FloatingUtils.INSTANCE.workGrimFloatingThisTick()) {
                    realVector = Vec3.ZERO;
                    shouldControl = true;
                    shouldCheckRocket = false;
                    shouldControlRotation = false;
                } else if (useFloatingUtils.get() && realVector.lengthSqr() < 1e-4) {
                    if (!MovTasks.getElytraExtra().canFireworkControlMotion()) {
                        if (ElytraExtra.INSTANCE.shouldApplyOnGroundFly()
                                && PlayerStateManager.INSTANCE.lastHasGroundSupport) {
                            shouldControl = true;
                            shouldCheckRocket = false;
                        } else {
                            realVector = Vec3.ZERO;
                            FloatingUtils.INSTANCE.setGrimFloatingTick(true);
                            shouldControl = true;
                            shouldCheckRocket = false;
                            shouldControlRotation = false;
                        }
                    } else {
                        shouldControl = true;
                        shouldCheckRocket = false;
                    }
                }
                // ground check
                if (ElytraExtra.INSTANCE.shouldApplyOnGroundFly()
                        && PlayerStateManager.INSTANCE.lastHasGroundSupport
                        && realVector.y < 0) {
                    realVector = realVector.with(Direction.Axis.Y, 0);
                }
                if (shouldControlRotation && realVector.lengthSqr() > 5e-3) {
                    // fliter zero control
                    if (!movementManagerEvent.context.hasImportantRotation()) {
                        if (realVector.horizontalDistanceSqr() > 1E-7) {
                            movementManagerEvent.context.pushImportantRotation(true, true);
                            Vec2 py = EntityUtils.rotationToPitchYaw(realVector.normalize());
                            movementManagerEvent.context.markForResetRot();
                            EntityUtils.setEntityPitchSafe(mc.player, py.x);
                            PlayerStateManager.setPlayerYawSafe(mc.player, py.y);
                        } else {
                            movementManagerEvent.context.pushImportantRotation(true, false);
                            float pitch = EntityUtils.rotationToPitch(realVector.normalize());
                            movementManagerEvent.context.markForResetRot();
                            EntityUtils.setEntityPitchSafe(mc.player, pitch);
                        }
                    }
                    // pitch reset to trigger grim lastPitch lastYaw update
                    if (useAutoRescale.get()) {
                        float yaw = mc.player.getYRot();
                        float newYaw = (Tasks.getTick() % 2 == 0) ? yaw + 0.001F : yaw - 0.001F;
                        PlayerStateManager.setPlayerYawSafe(mc.player, newYaw);
                        Vec3 newVectorRot = EntityUtils.pitchYawToRotation(mc.player.getXRot(), newYaw);
                        realVector = newVectorRot.normalize().scale(realVector.length());
                    }
                }
                if (shouldControl) {
                    if (Math.abs(realVector.y) <= 1e-2 && horizontalFlyNoGravity.get() && !mc.player.onGround()) {
                        modifyNoGravity = player.isNoGravity();
                        player.setNoGravity(true);
                    }
                    mc.player.setDeltaMovement(
                            (motionMode.get() == ElytraExtra.MotionMode.FIRE_WORKS && useAutoRescale.get())
                                    ? ElytraExtra.INSTANCE.applyAxisLimit(
                                            realVector,
                                            mc.player.getXRot(),
                                            mc.player.getYRot(),
                                            !mc.player.isNoGravity())
                                    : realVector);
                } else if (realVector.length() > 0) {
                    //                    Debug.info(
                    //                            "Lost Control",
                    //                            PlayerStateManager.INSTANCE.lastKnownClientVelocity,
                    //                            EntityUtils.calculateGlidingVelocity(
                    //                                    mc.player,
                    //                                    PlayerStateManager.INSTANCE.lastKnownClientVelocity,
                    //                                    mc.player.getRotationVector(),
                    //                                    true),
                    //                            mc.player.getDeltaMovement());
                }

                if (shouldCheckRocket) {
                    MovTasks.getElytraExtra().launchFirework(mc.player.getXRot(), mc.player.getYRot());
                }
            } else {
                lastVelocity = null;
                if (autoFly.get() && autoFlyAutoJumpOff.get()) {
                    ElytraExtra.INSTANCE.autoTakeoff();
                }
            }
        } else {
            lastVelocity = null;
        }
        if (currentTakeOff && !mc.player.isFallFlying() && mc.player.onGround()) {
            if (enable.get() && landAutoClose.get()) {
                HotKeyUtils.wrapFlagAsToggle(
                                simpleFlightControl.add("enable-control").toPath(), enable)
                        .run();
            }
            if (autoFly.get() && autoFlyLandAutoClose.get()) {
                HotKeyUtils.wrapFlagAsToggle(simpleFlightControl.add("auto-fly").toPath(), autoFly)
                        .run();
            }
            currentTakeOff = false;
        }
    }

    Boolean modifyNoGravity = null;

    @Override
    public boolean postModify(Event<LegalMovementManager> movementManagerEvent, boolean enabledThisTick) {
        if (modifyNoGravity != null) {
            movementManagerEvent.context().playerStatus.entity.setNoGravity(modifyNoGravity);
            modifyNoGravity = null;
        }

        return true;
    }

    public void onPresetLoad(Event<EventContainer<ModulePreset>> presetEvent) {
        switch (presetEvent.context.getValue()) {
            case HACKING, VANILLA, AC_COMMON -> {
                motionMode.set(ElytraExtra.MotionMode.VOID);
            }
            case AC_VULCAN -> {
                motionMode.set(ElytraExtra.MotionMode.VOID);
                if (packetMotion.get() > 2.5F) {
                    packetMotion.set(2.5F);
                }
            }
            case AC_GRIM_LEGACY, AC_GRIM, AC_MATRIX -> {
                motionMode.set(ElytraExtra.MotionMode.FIRE_WORKS);
            }
        }
    }

    public enum Mode implements ConfigEnum {
        CONTROL,
        ROTATION;

        @Override
        public String getConfigEnumType() {
            return "elytramode";
        }
    }
}
