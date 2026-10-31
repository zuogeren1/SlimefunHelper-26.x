package me.matl114.hacks.modules.move;

import me.matl114.hacks.modules.task.MovementManager;
import me.matl114.utils.ClientUtils;

import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.hacks.KeyBindAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.move.FlightVelocity;
import me.matl114.managers.*;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.Debug;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.entity.PlayerInputUtils;
import me.matl114.versioned.api.VPacket;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

public class Flight extends BaseModule implements LegalMovementManager.MovementModifier {
    private Vec3 pendingFlightVelocity;
    public final ModulePath moveSafety = makePath(Configs.MOV_CONFIG, "move-safety");
    public final ModulePath flight = moveSafety.add("flight");
    public final ModulePath moveSpeed = makePath(Configs.MOV_CONFIG, "move-speed");
    public static Flight INSTANCE;

    public Flight() {
        super("Flight");
        INSTANCE = this;
        bindFlag(canFly);
        MovementManager.registerMovementPipeline(this);
    }

    public final FlagRef canFly =
            flagBuilder(flight.add("flight-enable")).defaultValue(false).build();

    public final KeyBindRef keybind = moduleEntry(
                    flight.add("flight-enable-hotkey"),
                    new MultiKeyBind(),
                    flight.add("flight-enable"),
                    moduleMeta(() -> this.flightMode))
            .build();

    public final EnumRef<Mode> flightMode = builder(flight.add("flight-mode"), Mode.class)
            .defaultValue(Mode.CREATIVE)
            .build();

    public final KeyBindRef switchMode = hotkey(flight.add("flight-mode-switch-hotkey"), new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler(this::toggleMode))
            .build();

    public final FlagRef doAntiKick =
            builder(flight.add("antikick"), Boolean.class).defaultValue(true).build();

    public final EnumRef<AntiKickMode> antiKickMode = builder(flight.add("antikick-mode"), AntiKickMode.class)
            .defaultValue(AntiKickMode.PACKET)
            .build();

    public final IntRef antiKickPeriod = intBuilder(flight.add("antikick-period"))
            .defaultValue(60)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final DoubleRef motionSpeed = doubleBuilder(flight.add("motion-speed"))
            .defaultValue(4.0D)
            .validator(Configs.doubleRange(0.0D, 50.0D))
            .build();

    public final FlagRef overrideFlySpeed = builder(moveSpeed.add("fly-speed"), Boolean.class)
            .defaultValue(false)
            .build();

    public final KeyBindRef overridingSpeedKeybind = moduleEntry(
                    moveSpeed.add("toggle-flight-speed"), new MultiKeyBind(), moveSpeed.add("fly-speed"))
            .build();

    public final DoubleRef overrideFlySpeedCreative = builder(moveSpeed.add("fly-speed-creative"), Double.class)
            .defaultValue(0.8)
            .build();

    public final DoubleRef overrideFlySpeedSurvival = builder(moveSpeed.add("fly-speed-survival"), Double.class)
            .defaultValue(0.4)
            .build();

    public final FlagRef overrideWalkSpeed =
            flagBuilder(moveSpeed.add("walk-speed")).build();

    public final KeyBindRef overridingWalkSpeedKeybind = moduleEntry(
                    moveSpeed.add("toggle-walk-speed"), new MultiKeyBind(), moveSpeed.add("walk-speed"))
            .build();
    public final DoubleRef overridingWalkSpeedAll = builder(moveSpeed.add("walk-speed-override"), Double.class)
            .defaultValue(0.1)
            .build();

    public final FlagRef onGroundWhenMine =
            flagBuilder(flight.add("onground-when-mine")).build();

    //    public final FlagRef fake1 =
    //            flagBuilder(Configs.MOV_CONFIG, MOVE_FLIGHT_SAFETY_1).build();

    public boolean serverSideCanFly = false;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onEnableModule() {
        super.onEnableModule();
        if (mc.player != null) {
            serverSideCanFly = mc.player.getAbilities().mayfly;
        }
    }

    public void onDisableModule() {
        super.onDisableModule();
        if (mc.player != null) {
            // cancel fly when disable
            mc.player.getAbilities().mayfly = serverSideCanFly;
            if (mc.player.getAbilities().flying && !serverSideCanFly) {
                mc.player.getAbilities().flying = false;
            }
        }
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPacketListenerPoint(ClientboundPlayerAbilitiesPacket.class), this::onAbility);
        registerListener(
                Listener.getPacketListenerPoint(ServerboundPlayerAbilitiesPacket.class), this::onAbilityUpdate);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onPresetLoad);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class), this::onStartMine);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerInputPacket.class), this::onInterceptFlyInput);
    }

    public void onAbility(Event<ClientboundPlayerAbilitiesPacket> event) {
        var packet1 = event.context();
        serverSideCanFly = packet1.canFly();
        if (mc.player != null) {
            Abilities abilities = mc.player.getAbilities();
            // abilities.allowFlying = abilities.allowFlying;
            abilities.instabuild = packet1.canInstabuild();
            abilities.invulnerable = packet1.isInvulnerable();
            if (!isActive()) {
                abilities.mayfly = packet1.canFly();
            }
            if (!overrideFlySpeed.get()) {
                abilities.setFlyingSpeed(packet1.getFlyingSpeed());
            }
            abilities.setWalkingSpeed(packet1.getWalkingSpeed());
            // mc.player.getAbilities().flying = isFly;
        } else {
            // sometimes the player hasn't enter the game, because this is accepted in async thread, so run main
            // delay to avoid forever loop because
            Tasks.scheduleDelayed(
                    () -> {
                        onAbility(event);
                    },
                    10);
        }
        event.cancel();
    }

    public void onAbilityUpdate(Event<ServerboundPlayerAbilitiesPacket> event) {
        if (isActive() && !serverSideCanFly) {
            event.cancel();
        }
    }

    public double getOverridingFlySpeed() {
        return (mc.player != null && mc.gameMode.getPlayerMode().isCreative())
                ? overrideFlySpeedCreative.get()
                : overrideFlySpeedSurvival.get();
    }

    public double getOverridingWalkSpeed() {
        return overridingWalkSpeedAll.get();
    }

    public void toggleMode() {
        Mode mode = flightMode.get();
        int ordinal = mode.ordinal() + 1;
        Mode[] flightModes = Mode.values();
        Mode newMode = flightModes[ordinal % flightModes.length];
        flightMode.set(newMode);
        Debug.chat("toggle flight mode to", newMode.getDisplay());
    }

    @Override
    public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {
        LocalPlayer player = movementManagerEvent.context.playerStatus.entity;
        pendingFlightVelocity = null;
        if (isActive()) {
            if (!player.getAbilities().mayfly) {
                player.getAbilities().mayfly = true;
            }
        } else {
            player.getAbilities().mayfly = serverSideCanFly;
        }
        boolean handled =
                switch (flightMode.get()) {
                    case CREATIVE -> {
                        if (isActive() && player.getAbilities().flying && !player.isPassenger()) {
                            PlayerInputUtils.Input input = PlayerInputUtils.of(mc.options);
                            double flySpeed = player.getAbilities().getFlyingSpeed();
                            double horizontalSpeed = flySpeed * (player.isSprinting() ? 2.0D : 1.0D);
                            double verticalSpeed = flySpeed * 3.0D;
                            Vec3 horizontalVelocity = EntityUtils.movementInputToVelocity(
                                    new Vec3(input.sidewaysSpeed(), 0.0D, input.forwardSpeed()),
                                    (float) horizontalSpeed,
                                    player.getYRot());
                            Vec3 velocity = new Vec3(
                                    horizontalVelocity.x, input.upwardSpeed() * verticalSpeed, horizontalVelocity.z);
                            player.setDeltaMovement(velocity);
                            if (antiKickMode.get() != AntiKickMode.PACKET) {
                                dispatchAntiKick(player);
                                velocity = player.getDeltaMovement();
                            }
                            setFlightVelocity(player, velocity, Math.hypot(horizontalSpeed, verticalSpeed));
                        } else if (antiKickMode.get() != AntiKickMode.PACKET) {
                            dispatchAntiKick(player);
                        }
                        yield true;
                    }
                    case MOTION -> {
                        if (isActive() && player.getAbilities().flying && !player.isPassenger()) {
                            PlayerInputUtils.Input input = PlayerInputUtils.of(mc.options);
                            Vec3 movementInput =
                                    new Vec3(input.sidewaysSpeed(), input.upwardSpeed(), input.forwardSpeed());
                            double speed = motionSpeed.get();
                            Vec3 velocity =
                                    EntityUtils.movementInputToVelocity(movementInput, (float) speed, player.getYRot());
                            player.setDeltaMovement(velocity);
                            velocity = dispatchAntiKickMotion(velocity);
                            setFlightVelocity(player, velocity, speed);
                            yield true;
                        }
                        yield false;
                    }
                    case JETPACK -> {
                        if (isActive()) {
                            PlayerInputUtils.Input input = PlayerInputUtils.of(mc.options);
                            if (input.jump()) {
                                Vec3 vec3d = new Vec3(0, 1, 0);
                                vec3d = dispatchAntiKickMotion(vec3d);
                                if (vec3d.y > 0.8) {
                                    player.jumpFromGround();
                                } else {
                                    Vec3 playerVec = player.getDeltaMovement();
                                    player.setDeltaMovement(playerVec.x, vec3d.y, playerVec.z);
                                }
                                yield true;
                            }
                        }
                        yield false;
                    }
                };
        if (!handled && isActive() && mc.player.getAbilities().flying) {
            if (antiKickMode.get() != AntiKickMode.PACKET) {
                dispatchAntiKick(player);
            }
        }
    }

    private void setFlightVelocity(LocalPlayer player, Vec3 velocity, double maxVelocity) {
        FlightVelocity flightVelocity = new FlightVelocity(velocity, maxVelocity, FlightVelocity.Mode.MOTION_FLIGHT);
        Listener.getCustomListener().broadcast(new EventContainer<>(FlightVelocity.class, flightVelocity));
        pendingFlightVelocity = flightVelocity.toVelocity();
        player.setDeltaMovement(pendingFlightVelocity);
    }

    @Override
    public void applyBeforeTravelTick(
            Event<LegalMovementManager> movementManagerEvent, Event<Vec3> movementInputEvent) {
        Vec3 velocity = pendingFlightVelocity;
        LocalPlayer player = movementManagerEvent.context().playerStatus.entity;
        if (velocity == null
                || !isActive()
                || flightMode.get() == Mode.JETPACK
                || !player.getAbilities().flying
                || player.isPassenger()) {
            return;
        }

        movementInputEvent.cancel();
        player.move(MoverType.SELF, velocity);
        player.setDeltaMovement(velocity);
    }

    @Override
    public void applyAfterInputTick(Event<LegalMovementManager> movementManagerEvent) {
        LegalMovementManager.MovementModifier.super.applyAfterInputTick(movementManagerEvent);
    }

    public void dispatchAntiKick(LocalPlayer player) {
        boolean fakeGilde = false;
        if (((isActive() && doAntiKick.get()))) {
            antiKick(player, fakeGilde);
        }
    }

    private int mineTick = 0;
    private int lastTimeModifyOnGround = 0;

    @Override
    public void applyBeforeMovementPacketModify(Event<LegalMovementManager> movementManagerEvent) {
        var player = movementManagerEvent.context.playerStatus.entity;
        boolean onGround = player.onGround();
        if (!onGround && onGroundWhenMine.get() && isInMiningAction() && !player.getAbilities().instabuild) {
            // instabreak problems
            mineTick = 2;

            player.setOnGround(true);
            // server side onGround may change without noticing us, so
            ClientPlayerAccess.of(player).resyncOnGround();
            lastTimeModifyOnGround = 2;
        } else if (mineTick > 0) {
            --mineTick;
            player.setOnGround(true);
            // server side onGround may change without noticing us, so
            ClientPlayerAccess.of(player).resyncOnGround();
            lastTimeModifyOnGround = onGround ? 1 : 2;
        }
        if (antiKickMode.get() == AntiKickMode.PACKET) {
            dispatchAntiKickPacket(player);
        }
    }

    public boolean isInMiningAction() {
        // compact for minebot and instant mining
        return mc.gameMode.isDestroying() || (lastStartMinePacket + 1 >= Tasks.getTick());
    }

    private int lastStartMinePacket = 0;

    public void onStartMine(Event<ServerboundPlayerActionPacket> actionPacket) {
        if (onGroundWhenMine.get()
                && actionPacket.context().getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            lastStartMinePacket = Tasks.getTick();
        }
    }

    @Override
    public boolean postModify(Event<LegalMovementManager> movementManagerEvent, boolean enabledThisTick) {
        // do not restore, because client need this to calculate mining speed
        //        if(lastTimeModifyOnGround > 0){
        //            movementManagerEvent.context.playerStatus.entity.setOnGround(lastTimeModifyOnGround == 1);
        //            lastTimeModifyOnGround = 0;
        //        }
        lastTimeModifyOnGround = 0;
        return true;
    }

    // server constant
    private static final double antiKickOffset = 0.032D;
    // antikick module
    private int antiKickCount = 0;
    private int antiKickPacketTicksRemaining = 0;
    private int lastAntiKickPacketTick = -1;
    private double antiKickOffset0;
    private boolean escapeMotionReset = false;
    private double preservedLastMotion = 0.0D;
    private boolean waitingForServerResponse;
    // todo: rewrite
    public void antiKick(LocalPlayer player, boolean fakeGliding) {
        if (MovTasks.seenAsFloating(fakeGliding)) {
            antiKickCount++;
        } else {
            antiKickCount = 0;
        }
        if (antiKickCount > antiKickPeriod.get()) {
            antiKickCount = 0;
            escapeMotionReset = !shouldResetMotion();
            preservedLastMotion = player.getDeltaMovement().y;
            setMotionY(-antiKickOffset);
            // randomly fall down twice
            waitingForServerResponse = true; // Tasks.getTickRandom()%3 == 0;
            antiKickOffset0 = antiKickOffset - 0.008;
            return;
        }
        if (!escapeMotionReset) {
            if (waitingForServerResponse) {
                setMotionY(-antiKickOffset);
                antiKickOffset0 += antiKickOffset - 0.008;
                // there is no fucking packet for response
                waitingForServerResponse = false;

                // continue fall down til server respond
            } else {
                setMotionY(antiKickOffset0 + preservedLastMotion - 0.0);
                antiKickOffset0 = 0D;
                preservedLastMotion = 0.0D;
                Tasks.scheduleDelayed(this::restoreKeyPresses, 1);
                // set end
                escapeMotionReset = true;
            }
        }

        //        }

    }

    public Vec3 dispatchAntiKickMotion(Vec3 controlMotion) {
        boolean fakeGilde = false;
        if (antiKickMode.get() != AntiKickMode.PACKET && (fakeGilde || (isActive() && doAntiKick.get()))) {
            return processAntiKickMotion(controlMotion, fakeGilde);
        }
        return controlMotion;
    }

    private void dispatchAntiKickPacket(LocalPlayer player) {
        if (!isActive() || !doAntiKick.get()) {
            antiKickCount = 0;
            antiKickPacketTicksRemaining = 0;
            return;
        }
        boolean seenAsFloating = MovTasks.seenAsFloating(false);
        if (!seenAsFloating && antiKickPacketTicksRemaining == 0) {
            antiKickCount = 0;
            return;
        }

        int currentTick = Tasks.getTick();
        if (lastAntiKickPacketTick == currentTick) {
            return;
        }
        if (antiKickPacketTicksRemaining == 0) {
            if (++antiKickCount <= antiKickPeriod.get()) {
                return;
            }
            antiKickCount = 0;
            antiKickPacketTicksRemaining = 2;
        }

        Vec3 currentPos = player.position();
        Vec3 simulatedMovement = MovTasks.simulateMovement(player, currentPos, new Vec3(0, 0.1, 0), true);
        Vec3 packetPos = currentPos.add(simulatedMovement);
        mc.getConnection()
                .send(VPacket.newPositionAndOnGround(
                        packetPos.x, packetPos.y, packetPos.z, player.onGround(), false));

        Vec3 simulatedMovement2 = MovTasks.simulateMovement(player, packetPos, new Vec3(0, -0.1, 0), true);
        mc.player.setPos(packetPos.add(simulatedMovement2));
        lastAntiKickPacketTick = currentTick;
        antiKickPacketTicksRemaining--;
    }

    double lastFloatingPosition = 0.0D;
    int floatingCount = 0;

    public boolean shouldResetMotion() {
        double floatingPosition = mc.player.getY();
        if (floatingCount == 0) {
            floatingCount = 1;
            lastFloatingPosition = floatingPosition;
            return false;
        }
        if (Math.abs(floatingPosition - lastFloatingPosition) > antiKickOffset * 3) {
            // try reset
            double oldFloatingPosition = lastFloatingPosition;
            lastFloatingPosition = floatingPosition;
            floatingCount = 1;
            if (oldFloatingPosition < lastFloatingPosition) {
                // up fly must
                return true;
            } else {
                // optimize downfly rest
                if (MovTasks.ENGIN.checkEnvironmentCollision(
                        mc.player, mc.player.position().add(0, -3 * antiKickOffset, 0), false)) {
                    return true;
                }
                return false;
            }
        } else {
            // optimize afk
            lastFloatingPosition = floatingPosition;
            floatingCount += 1;
            if (floatingCount > 10) {
                return true;
            }
            return false;
        }
    }

    public Vec3 processAntiKickMotion(Vec3 controlMotion, boolean fakeGlide) {
        if (MovTasks.seenAsFloating(fakeGlide)) {
            antiKickCount++;
        } else {
            antiKickCount = 0;
        }
        if (antiKickCount > MovTasks.getFlight().antiKickPeriod.get()) {
            antiKickCount = 0;
            // calculate whether need escapeMotionReset
            escapeMotionReset = !shouldResetMotion();
            preservedLastMotion = controlMotion.y;
            // randomly fall down twice
            waitingForServerResponse = true; // Tasks.getTickRandom()%3 == 0;
            antiKickOffset0 = antiKickOffset - 0.008;
            return new Vec3(controlMotion.x, -antiKickOffset, controlMotion.z);
        }
        if (!escapeMotionReset) {
            if (waitingForServerResponse) {
                antiKickOffset0 += antiKickOffset - 0.008;
                // there is no fucking packet for response
                waitingForServerResponse = false;
                return new Vec3(controlMotion.x, -antiKickOffset, controlMotion.z);
                // continue fall down til server respond
            } else {
                antiKickOffset0 = 0D;
                preservedLastMotion = 0.0D;
                // set end
                escapeMotionReset = true;
                return new Vec3(controlMotion.x, antiKickOffset0 + preservedLastMotion - 0.0, controlMotion.z);
            }
        }
        return controlMotion;

        //        }
    }

    public void onInterceptFlyInput() {
        //        if(fake1.get() && !serverSideCanFly && mc.player.getAbilities().flying){
        //            // hack fly
        //            inputPacketEvent.cancel();
        //        }
    }

    private void setMotionY(double motionY) {

        mc.options.keyShift.setDown(false);
        mc.options.keyJump.setDown(false);
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x, motionY, velocity.z);
    }

    private void restoreKeyPresses() {
        // bugfix when shift click in screen, this key is reset to fall
        if (ClientUtils.getScreen(mc) == null) {

            KeyBindAccess.of(mc.options.keyJump).resetKeyState();
            KeyBindAccess.of(mc.options.keyShift).resetKeyState();
        }
    }

    public void onPresetLoad(Event<EventContainer<ModulePreset>> presetEvent) {
        if (presetEvent.context().getValue().isOptionPreset()) return;
        var modulePreset = presetEvent.context().getValue();
        switch (modulePreset) {
            case AC_GRIM, AC_GRIM_LEGACY, AC_MATRIX -> {
                canFly.set(false);
            }
            default -> {
                canFly.set(true);
            }
        }
        switch (modulePreset) {
            case AC_GRIM, AC_GRIM_LEGACY -> {
                overrideFlySpeed.set(false);
                overrideWalkSpeed.set(false);
            }
        }
        switch (modulePreset) {
            case HACKING -> {
                onGroundWhenMine.set(true);
            }
            default -> {
                onGroundWhenMine.set(false);
            }
        }
    }

    public enum Mode implements ConfigEnum {
        CREATIVE,
        MOTION,
        JETPACK;

        @Override
        public String getConfigEnumType() {
            return "flight_mode";
        }
    }

    public enum AntiKickMode implements ConfigEnum {
        MOTION,
        PACKET;

        @Override
        public String getConfigEnumType() {
            return "flight_antikick_mode";
        }
    }
}
