package me.matl114.hacks.modules.move;

import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.task.MovementManager;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.enums.BypassMode;
import me.matl114.hacks.utils.move.FlightVelocity;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.hooks.ViaProtocols;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.ScreenUtils;
import me.matl114.utils.entity.PlayerInputUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

public class BoatFly extends BaseModule implements LegalMovementManager.MovementModifier {
    public static BoatFly INSTANCE;
    private Entity ridingVehicle;
    private int ridingStartTick = Integer.MIN_VALUE;

    public final ModulePath moveSpeed = makePath(Configs.MOV_CONFIG, "move-speed");
    public final ModulePath boatFly = moveSpeed.add("boat-fly");

    public final FlagRef enable = flagBuilder(boatFly.addEnable()).build();
    public final KeyBindRef hotkey = moduleEntry(boatFly.addHotkey(), new MultiKeyBind(), boatFly.addEnable())
            .build();

    public final DoubleRef speed = doubleBuilder(boatFly.add("speed"))
            .defaultValue(1.0D)
            .validator(Configs.doubleRange(0.00D, 50.0D))
            .build();

    public final DoubleRef upSpeed = doubleBuilder(boatFly.add("up-speed"))
            .defaultValue(5.0D)
            .validator(Configs.doubleRange(0.00D, 50.0D))
            .build();

    public final DoubleRef downSpeed = doubleBuilder(boatFly.add("down-speed"))
            .defaultValue(1.0D)
            .validator(Configs.doubleRange(0.00D, 50.0D))
            .build();

    public final FlagRef slowFall = flagBuilder(boatFly.add("slow-fall")).build();

    public final FlagRef sneakProtection =
            flagBuilder(boatFly.add("sneak-protection"), true).build();

    public final FlagRef noClip = flagBuilder(boatFly.add("no-clip")).build();

    public final EnumRef<BypassMode> bypassMode = builder(boatFly.add("bypass-mode"), BypassMode.class)
            .defaultValue(BypassMode.NO_BYPASS)
            .build();

    public BoatFly() {
        super("BoatFly");
        INSTANCE = this;
        bindFlag(enable);
        MovementManager.registerMovementPipeline(this);
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        resetRidingDelay();
    }

    @Override
    public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {
        if (!enable.get()) {
            resetRidingDelay();
            return;
        }

        LocalPlayer player = movementManagerEvent.context().playerStatus.entity;
        if (!isRideControlReady(player)) return;

        Entity vehicle = player.getRootVehicle();
        FlightVelocity flightVelocity = new FlightVelocity(
                getMovement(player),
                Math.hypot(speed.get(), Math.max(upSpeed.get(), downSpeed.get())),
                FlightVelocity.Mode.MOTION_FLIGHT);
        Listener.getCustomListener().broadcast(new EventContainer<>(FlightVelocity.class, flightVelocity));
        Vec3 velocity = flightVelocity.toVelocity();
        double horizontalSpeed = velocity.horizontalDistance();
        if (horizontalSpeed > speed.get()) {
            double scale = speed.get() / horizontalSpeed;
            velocity = new Vec3(velocity.x * scale, velocity.y, velocity.z * scale);
        }
        double verticalSpeed = Math.max(-downSpeed.get(), Math.min(upSpeed.get(), velocity.y));
        velocity = new Vec3(velocity.x, verticalSpeed, velocity.z);

        if (noClip.get()) {
            vehicle.setPos(vehicle.position().add(0.0D, velocity.y, 0.0D));
            vehicle.move(MoverType.SELF, new Vec3(velocity.x, 0.0D, velocity.z));
        } else {
            vehicle.move(MoverType.SELF, velocity);
        }
        vehicle.positionRider(player);
        vehicle.setDeltaMovement(velocity);
        vehicle.fallDistance = 0.0F;
        player.fallDistance = 0.0F;
    }

    private Vec3 getMovement(LocalPlayer player) {
        PlayerInputUtils.Input input = PlayerInputUtils.of(mc.options);
        double sideways = input.sidewaysSpeed();
        double forward = input.forwardSpeed();
        double length = Math.sqrt(sideways * sideways + forward * forward);
        double x = 0.0D;
        double z = 0.0D;
        if (length > 1.0E-4D) {
            sideways /= length;
            forward /= length;
            double yaw = Math.toRadians(player.getYRot());
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            x = (sideways * cos - forward * sin) * speed.get();
            z = (forward * cos + sideways * sin) * speed.get();
        }

        return new Vec3(x, getVerticalSpeed(input), z);
    }

    private double getVerticalSpeed(PlayerInputUtils.Input input) {
        boolean up = input.jump();
        boolean down = input.sneak();
        return up == down ? (slowFall.get() ? -0.05D : 0.0D) : up ? upSpeed.get() : -downSpeed.get();
    }

    @Override
    public void applyAfterInputTick(Event<LegalMovementManager> movementManagerEvent) {
        if (!enable.get()) return;

        LocalPlayer player = movementManagerEvent.context().playerStatus.entity;
        if (!isRideControlReady(player)) return;

        PlayerInputUtils.Input input = PlayerInputUtils.of(player)
                .forward(false)
                .backward(false)
                .left(false)
                .right(false);
        if (sneakProtection.get() && !ScreenUtils.hasCtrlDown()) input.sneak(false);
        input.applyInput(player);
    }

    @Override
    public void applyBeforeInputPacketModify(Event<LegalMovementManager> movementManagerEvent) {
        if (!enable.get() || bypassMode.get() != BypassMode.BYPASS_GRIM) return;

        LocalPlayer player = movementManagerEvent.context().playerStatus.entity;
        if (!isRideControlReady(player)) return;

        ViaFabricPlusHooks hooks = ViaFabricPlusHooks.getInstance();
        if (!hooks.isViaEnabled() || hooks.getCurrentVersion().isHigherOrEqualTo(21, 1)) return;

        movementManagerEvent.cancel();

        PlayerInputUtils.Input input = PlayerInputUtils.of(player);
        ViaFabricPlusHooks.ViaPacketWrapper wrapper = hooks.createViaPacket();
        wrapper.writePacketType(ViaProtocols.V1_20_3_TO_1_20_5, GamePacketTypes.SERVERBOUND_PLAYER_INPUT);
        wrapper.write("FLOAT", Float.NaN);
        wrapper.write("FLOAT", Float.NaN);
        byte flags = 0;
        if (input.jump()) flags = (byte) (flags | 1);
        if (input.sneak()) flags = (byte) (flags | 2);
        wrapper.write("BYTE", flags);
        wrapper.scheduleSendToServer(ViaProtocols.V1_21_TO_1_21_2, true);

        if (mc.getConnection() != null) {
            Entity vehicle = player.getRootVehicle();
            mc.getConnection().send(ServerboundMoveVehiclePacket.fromEntity(vehicle));
        }
    }

    private boolean isRideControlReady(LocalPlayer player) {
        if (!isClientControllableVehicle(player)) {
            resetRidingDelay();
            return false;
        }

        Entity vehicle = player.getRootVehicle();
        if (vehicle != ridingVehicle) {
            ridingVehicle = vehicle;
            ridingStartTick = Tasks.getTick();
            return false;
        }

        return Tasks.getTick() != ridingStartTick;
    }

    private boolean isClientControllableVehicle(LocalPlayer player) {
        return player.isPassenger() && player.getRootVehicle().isLocalInstanceAuthoritative();
    }

    private void resetRidingDelay() {
        ridingVehicle = null;
        ridingStartTick = Integer.MIN_VALUE;
    }
}
