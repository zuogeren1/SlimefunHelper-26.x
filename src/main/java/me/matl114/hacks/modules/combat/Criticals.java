package me.matl114.hacks.modules.combat;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.access.PlayerMoveC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.CombatTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.move.FloatingUtils;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.modules.task.MovementManager;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.enums.SetBackTriggerType;
import me.matl114.hacks.utils.tasks.TimerExecutor;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.entity.PlayerInputUtils;
import me.matl114.versioned.api.VPacket;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
//#if MC >= 260300
//$$ import net.minecraft.network.protocol.game.ServerboundPunchPacket;
//#else
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
//#endif
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class Criticals extends BaseModule implements LegalMovementManager.MovementModifier {
    public final ModulePath attBot = makePath(Configs.COMBAT_CONFIG, "att-bot");
    public final ModulePath criticals = attBot.add("criticals");

    public final FlagRef enable = flagBuilder(criticals.add("enable")).build();

    public final KeyBindRef hotkey = moduleEntry(
                    criticals.add("hotkey"), new MultiKeyBind(), criticals.add("enable"), moduleMeta(() -> this.mode))
            .build();

    public final EnumRef<Mode> mode =
            builder(criticals.add("mode"), Mode.class).defaultValue(Mode.PACKET).build();

    public final FlagRef groundOnly = flagBuilder(criticals.add("ground-only"))
            .show(() -> mode.get().isIn(Mode.FREEZE, Mode.GRIM_GROUND_SIMULATION))
            .build();

    public final FlagRef targetAround = flagBuilder(criticals.add("target-only"))
            .show(() -> mode.get().isIn(Mode.FREEZE, Mode.GRIM_GROUND_SIMULATION))
            .build();

    public final FlagRef movementOkFreeze = flagBuilder(criticals.add("movement-ok-freeze"))
            .show(() -> mode.get().isIn(Mode.FREEZE))
            .build();

    public final FlagRef movementOkGround = flagBuilder(criticals.add("movement-ok-ground"))
            .show(() -> mode.get().isIn(Mode.GRIM_GROUND_SIMULATION))
            .build();

    public final FlagRef autoFakeGround = builder(criticals.add("auto-fake-ground-height"), Boolean.class)
            .defaultValue(true)
            .show(() -> mode.get().isIn(Mode.GRIM_GROUND_SIMULATION))
            .build();

    public final FlagRef autoWalk = builder(criticals.add("auto-walk-resync"), Boolean.class)
            .defaultValue(true)
            .show(() -> mode.get().isIn(Mode.GRIM_GROUND_SIMULATION))
            .build();

    public final EnumRef<SetBackTriggerType> setBackType = builder(
                    criticals.add("set-back-mode"), SetBackTriggerType.class)
            .defaultValue(SetBackTriggerType.SIMULATION)
            .show(() -> mode.get().isIn(Mode.GRIM_GROUND_SIMULATION))
            .build();

    public final FlagRef delaySwap = flagBuilder(criticals.add("delay-swap"))
            .show(() -> mode.get().isIn(Mode.GRIM_GROUND_SIMULATION))
            .build();

    public final FlagRef inWallPacket = flagBuilder(criticals.add("in-wall-packet"))
            .show(() -> mode.get().isNotIn(Mode.FREEZE, Mode.PACKET))
            .build();

    public final FlagRef inAirFreeze = builder(criticals.add("in-air-freeze"), Boolean.class)
            .defaultValue(true)
            .show(() -> mode.get().isIn(Mode.FREEZE))
            .build();

    public final FlagRef inWallFreezeCritical = builder(criticals.add("in-wall-freeze"), Boolean.class)
            .defaultValue(true)
            .show(() -> mode.get().isIn(Mode.FREEZE))
            .build();

    public final FlagRef stopWallCriticalWhenMoving = flagBuilder(criticals.add("stop-wall-when-moving"))
            .show(() -> mode.get() == Mode.GRIM_WALL
                    || (mode.get().isNotIn(Mode.FREEZE, Mode.PACKET) && inWallPacket.get())
                    || (mode.get() == Mode.FREEZE && inWallFreezeCritical.get()))
            .build();

    public final DoubleRef customInWallJumpFreezeHeight = builder(criticals.add("custom-in-wall-height"), Double.class)
            .defaultValue(0.05)
            .validator(Configs.doubleRange(0, 1))
            .show(() -> mode.get().isIn(Mode.FREEZE))
            .build();

    public final DoubleRef customInWallJumpPacketHeight = builder(
                    criticals.add("custom-in-wall-packet-height"), Double.class)
            .defaultValue(0.05)
            .validator(Configs.doubleRange(0, 1))
            .show(() -> mode.get().isNotIn(Mode.FREEZE))
            .build();

    public final IntRef cooldownCritical = builder(criticals.add("critical-cooldown"), Integer.class)
            .defaultValue(10)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    static LegalMovementManager.DelegateMovementModifier instance;

    public Criticals() {
        super("Criticals");
        MovementManager.registerMovementPipeline(this);
        bindFlag(enable);
    }

    // todo: wall critical

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPacketPoint().getChannel(ServerboundAttackPacket.class), this::onPlayerAttack);
        //#if MC >= 260300
        //$$ registerListener(Listener.getPacketPoint().getChannel(ServerboundPunchPacket.class), this::onSwing);
        //#else
        registerListener(Listener.getPacketPoint().getChannel(ServerboundSwingPacket.class), this::onSwing);
        //#endif
        //        registerListener(
        //                Listener.getPacketPostHandlePoint().getChannel(PlayerPositionLookS2CPacket.class),
        //                this::onTeleportConfirm2);
        //        registerListener(
        //            Listener.getPacketPostSendPoint().getChannel(TeleportConfirmC2SPacket.class),
        // this::onTeleportConfirm);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundMovePlayerPacket.class), this::onTeleportConfirmPre);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
        ;
    }

    private boolean canNotCrit() {
        return mc.player.isInWater()
                || mc.player.isPassenger()
                || PlayerStateManager.INSTANCE.lastInWeb
                || PlayerStateManager.INSTANCE.lastSlowByBlock;
    }

    public void onPlayerAttack(Event<ServerboundAttackPacket> event) {
        // can not crit in water or boat
        if (checkNull() || event.isCancelled() || canNotCrit()) return;
        if (PlayerStateManager.INSTANCE.fallDistance > 1E-6) return;
        boolean canRun = cooldownTimer.canRun(cooldownCritical.get());
        cooldownTimer.mark();
        if (!canRun) return;
        if (enable.get()
                && mc.level != null
                && mc.level.getEntity(event.context.entityId()) instanceof LivingEntity lv) {
            handleCritical(event);
        }
    }

    int walkCnt = 0;
    boolean nextAttackIsKillarua = false;
    TimerExecutor cooldownTimer = new TimerExecutor();

    public void handleCritical(Event<ServerboundAttackPacket> event) {
        // todo: handle wall critical, handle in wall critical

        // todo: handle sprint, handle inWater, handle condition

        if (inWallPacket.get()
                && (PlayerStateManager.INSTANCE.lastInWall || PlayerStateManager.INSTANCE.lastUnderBlock)
                && mode.get().isNotIn(Mode.FREEZE, Mode.PACKET)) {
            if (shouldSkipWallCritical()) return;
            handleCriticalWall(event);
            return;
        }
        var x = mc.player.getX();
        var y = mc.player.getY();
        var z = mc.player.getZ();
        switch (mode.get()) {
            case PACKET -> {
                // do not influence tp
                if (!CombatTasks.getAttack().canUseTp()) {
                    PlayerStateManager.INSTANCE.sendSprintStatus(false, true);
                    mc.getConnection().send(VPacket.newPositionAndOnGround(x, y + 5.0E-4, z, false, false));
                    mc.getConnection().send(VPacket.newPositionAndOnGround(x, y + 1.0E-4, z, false, false));
                }
            }
            //            case OLD_GRIM_V2 -> {
            //                if (mc.player.isOnGround()
            //                        && !PlayerInputUtils.of(mc.player).hasWASDMovement()) {
            //                    mc.getConnection().sendPacket(VPacket.newPositionAndOnGround(x, y + 0.0625, z,
            // false, false));
            //                    mc.getConnection().sendPacket(VPacket.newPositionAndOnGround(x, y, z, false,
            // false));
            //                    mc.getConnection().sendPacket(VPacket.newPositionAndOnGround(x, y + 1.0E-7, z,
            // false, false));
            //                    mc.getConnection().sendPacket(VPacket.newPositionAndOnGround(x, y, z, false,
            // false));
            //                }
            //            }
            //            case OLD_GRIM_V3 -> {
            //                if (mc.player.isOnGround()
            //                        && !PlayerInputUtils.of(mc.player).hasWASDMovement()) {
            //                    mc.getConnection().sendPacket(VPacket.newPositionAndOnGround(x, y, z, true,
            // false));
            //                    mc.getConnection().sendPacket(VPacket.newPositionAndOnGround(x, y + 0.0625, z,
            // false, false));
            //                    mc.getConnection().sendPacket(VPacket.newPositionAndOnGround(x, y + 0.04535,
            // z, false, false));
            //                }
            //            }
            case FREEZE -> {
                if (lastOnGroundT) {
                    mc.getConnection()
                            .send(VPacket.newLookAndOnGround(
                                    mc.player.getYRot(), mc.player.getXRot(), false, mc.player.horizontalCollision));
                }
            }
            case GRIM_GROUND_SIMULATION -> {
                if (mc.player.onGround()) {
                    PlayerStateManager.INSTANCE.sendSprintStatus(false, true);
                    if (!shouldApplyGrimGroundSimulationAutoFakeGround()) {
                        mc.getConnection()
                                .send(VPacket.newPositionAndOnGround(x, y + MIN_HEIGHT_DELTA, z, true, false));
                    }
                    // trigger simulation to sync our position from y + 1.0E-5 -> y, critical
                    switch (setBackType.get()) {
                        case CRASH_PACKETS -> {
                            mc.getConnection()
                                    .send(PlayerMoveC2SPacketAccess.setCause(
                                            VPacket.newPositionAndOnGround(
                                                    x, Double.POSITIVE_INFINITY, z, false, false),
                                            PlayerMoveC2SPacketAccess.Cause.TRIGGER_SIMULATION));
                        }
                        case SIMULATION -> {
                            mc.getConnection()
                                    .send(PlayerMoveC2SPacketAccess.setCause(
                                            VPacket.newPositionAndOnGround(x, y + 1, z, false, false),
                                            PlayerMoveC2SPacketAccess.Cause.TRIGGER_SIMULATION));
                        }
                    }
                    event.cancel();
                    cache = event.context;
                    cachedHandStack =
                            mc.player.getItemInHand(InteractionHand.MAIN_HAND).copy();
                    lastStartCacheTick = Tasks.getTick();
                    fakeMovementThisTick = true;
                }
            }
            case GRIM_WALL -> {
                if (mc.player.onGround()
                        && (PlayerStateManager.INSTANCE.lastInWall || PlayerStateManager.INSTANCE.lastUnderBlock)) {
                    if (shouldSkipWallCritical()) return;
                    handleCriticalWall(event);
                }
            }
        }
    }

    private boolean shouldSkipWallCritical() {
        if (ViaFabricPlusHooks.isSupportEndTick()) return true;
        if (!stopWallCriticalWhenMoving.get()) return false;

        int movementTick = PlayerStateManager.INSTANCE.lastRealMovementPacketTick;
        return movementTick >= Tasks.getTick() - 1;
    }

    public void handleCriticalWall(Event<ServerboundAttackPacket> event) {
        var x = mc.player.getX();
        var y = mc.player.getY();
        var z = mc.player.getZ();
        mc.getConnection()
                .send(VPacket.newPositionAndOnGround(x, y + customInWallJumpPacketHeight.get(), z, false, false));
        mc.getConnection()
                .send(VPacket.newPositionAndOnGround(
                        x, y + customInWallJumpPacketHeight.get() * 0.75, z, false, false));
        ClientPlayerAccess.of(mc.player).resyncPos();
    }

    //#if MC >= 260300
    //$$ public void onSwing(Event<ServerboundPunchPacket> eventSwing) {
    //$$     onSwingEvent(eventSwing);
    //$$ }
    //#else
    public void onSwing(Event<ServerboundSwingPacket> eventSwing) {
        onSwingEvent(eventSwing);
    }
    //#endif

    private void onSwingEvent(Event<?> eventSwing) {
        cooldownTimer.mark();
        if (cache != null) {
            eventSwing.cancel();
        }
    }

    boolean fakeMovementThisTick;
    ServerboundAttackPacket cache;
    ItemStack cachedHandStack;
    int setbackFlag = 0;
    int lastStartCacheTick = 0;
    //    public void onTeleportConfirm(Event<TeleportConfirmC2SPacket> event) {
    //        setbackFlag = 2;
    //        if (cache != null) {
    //            Listener.sendPacketNoEvents(cache);
    //            cache = null;
    //            mc.player.swingHand(Hand.MAIN_HAND);
    //        }
    //    }

    public void onTeleportConfirmPre(Event<ServerboundMovePlayerPacket> event) {
        if (cache != null
                && event.context instanceof PlayerMoveC2SPacketAccess acc
                && acc.getCause() == PlayerMoveC2SPacketAccess.Cause.SET_BACK) {
            setbackFlag = 1;
            Entity entity = mc.level.getEntity(cache.entityId());
            var pkt0 = event.context;
            Vec2 useLegacySnap = null;
            if (entity != null) {
                boolean canDirectlyHit = RaycastUtils.canRaycastHit(
                        mc.player,
                        pkt0.getXRot(PlayerStateManager.INSTANCE.lastPitch),
                        pkt0.getYRot(PlayerStateManager.INSTANCE.lastYaw),
                        entity);
                if (canDirectlyHit) {
                    // escape rot
                } else {
                    Vec3 predictedEyePos = mc.player.getEyePosition();
                    Vec3 eyePos = entity.getEyePosition();
                    Vec3 cacheDirection = eyePos.subtract(predictedEyePos).normalize();
                    var py = EntityUtils.rotationToPitchYaw(cacheDirection);
                    if (ViaFabricPlusHooks.isSupportDupRot()) {
                        useLegacySnap = py;
                    } else {
                        acc.setPitch(py.x);
                        acc.setYaw(EntityUtils.getSafeYaw(PlayerStateManager.INSTANCE.lastYaw, py.y));
                    }
                }
            }
            var pkt = cache;
            var stack = cachedHandStack;
            final Vec2 legacySnapTarget = useLegacySnap;
            PacketManager.schedulePostScheduleCallback(event.context, () -> {
                var weapon = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
                // Debug.chat("Attack");
                if (legacySnapTarget != null) {
                    LegacySnapRotManager.INSTANCE.snapAt(legacySnapTarget.x, legacySnapTarget.y, false);
                }
                Runnable callback = null;
                if (delaySwap.get()
                        && stack != null
                        && !stack.isEmpty()
                        && !ItemStack.isSameItemSameComponents(weapon, stack)) {
                    var res = InventoryUtils.findPlayerItem(
                            it -> ItemStack.isSameItemSameComponents(it, stack), true, false);
                    if (res != null) {
                        callback = InvExtra.INSTANCE.swapItemToHand(res.index(), false, GhostHandMode.INV_SWAP);
                    }
                }
                Listener.sendPacketNoEvents(pkt);
                ClientUtils.swing(mc.player, InteractionHand.MAIN_HAND);
                if (callback != null) {
                    callback.run();
                }
            });
            cache = null;
            cachedHandStack = null;
            lastSetBackCriticalTick = Tasks.getTick();
        }
    }

    boolean lastFall = false;
    boolean lastOnGroundT = false;
    int fakeTicks = 0;
    // grim ground critical optimize

    public boolean shouldApplyCriticalConditionCheck() {
        return hasNoMovement() && hasTargetNear();
    }

    public boolean shouldApplyGrimGroundSimulationAutoFakeGround() {
        return (autoFakeGround.get() || nextAttackIsKillarua)
                && mc.player.onGround()
                && shouldApplyCriticalConditionCheck();
    }

    public static final double MIN_HEIGHT_THRESHOLD = 1E-4;
    public static final double MIN_HEIGHT_DELTA = 1E-5;

    boolean lastShiftUp = false;
    Vec3 storedRotation1205;
    int lastSetBackCriticalTick = 0;

    @Override
    public void applyBeforeMovementPacketModify(Event<LegalMovementManager> movementManagerEvent) {
        // todo: optimize using falldistance
        boolean lastLastOnGround = lastOnGroundT;
        lastOnGroundT = mc.player.onGround() && !movementManagerEvent.context.playerStatus.onGround;
        if (enable.get()
                && mode.get() == Mode.FREEZE
                && shouldApplyCriticalConditionCheck()
                && !mc.player.isFallFlying()) {
            boolean shouldApplyFreeze = false;
            how_to_apply:
            {
                if (inWallFreezeCritical.get()
                        && (PlayerStateManager.INSTANCE.lastUnderBlock || PlayerStateManager.INSTANCE.lastInWall)
                        && !shouldSkipWallCritical()) {
                    // wall shit
                    double jumpMin = customInWallJumpFreezeHeight.get() / 4.0D;
                    AABB oldBox = mc.player.dimensions.makeBoundingBox(movementManagerEvent.context.playerStatus.pos);
                    Set<BlockPos> moveablePoses =
                            new HashSet<>(CollisionUtil.getIntersectingBlockPositions(mc.level, oldBox, false));
                    List<BlockPos> moveDownWards =
                            CollisionUtil.getIntersectingBlockPositions(mc.level, oldBox.move(0, -jumpMin, 0), false);
                    boolean realOnGround = moveDownWards.stream().anyMatch(pos -> !moveablePoses.contains(pos));
                    if (realOnGround) {
                        double yLevel = mc.player.getY() + jumpMin * 4;
                        movementManagerEvent.context.playerStatus.restorePos();
                        mc.player.setPos(mc.player.position().with(Direction.Axis.Y, yLevel));
                        mc.player.setOnGround(false);
                        lastShiftUp = true;
                        break how_to_apply;
                    } else {
                        if (PlayerStateManager.INSTANCE.fallDistance > 0) {
                            mc.player.setOnGround(false);
                            shouldApplyFreeze = true;
                            break how_to_apply;
                        } else if (lastShiftUp) {
                            lastShiftUp = false;
                            movementManagerEvent.context.playerStatus.restorePos();
                            mc.player.setPos(mc.player.position().add(0, -jumpMin, 0));
                            mc.player.setOnGround(false);
                            break how_to_apply;
                        }
                    }
                }
                if (inAirFreeze.get()) {
                    if (groundOnly.get()) {
                        shouldApplyFreeze = lastLastOnGround || lastOnGroundT;
                        if (shouldApplyFreeze) {
                            lastOnGroundT = true;
                        }
                    } else {
                        shouldApplyFreeze =
                                mc.player.getY() < movementManagerEvent.context.playerStatus.pos.y && lastFall;
                    }
                }
            }

            if (shouldApplyFreeze) {
                FloatingUtils.INSTANCE.setGrimFloatingTick(true);
                FloatingUtils.INSTANCE.setForceSilent(true);
                mc.player.setOnGround(false);
                mc.player.setSprinting(false);
            }
        }
        lastFall = mc.player.getY() < movementManagerEvent.context.playerStatus.pos.y;
        // fake height
        if (setbackFlag > 0) {
            setbackFlag -= 1;
        }
        if (enable.get()
                && mode.get() == Mode.GRIM_GROUND_SIMULATION
                && !canNotCrit()
                && lastSetBackCriticalTick + 2 <= Tasks.getTick()
                && shouldApplyGrimGroundSimulationAutoFakeGround()) {
            double yLevel = mc.player.getY();

            if (setbackFlag <= 0) {
                // may be flag as duplicate rot
                double delta;
                boolean move = mc.player
                                        .position()
                                        .subtract(movementManagerEvent.context.playerStatus.pos)
                                        .horizontalDistanceSqr()
                                > MathUtils.s2(2E-4)
                        || Math.abs(mc.player.position().y - movementManagerEvent.context.playerStatus.pos.y) > 2E-4;
                delta = MIN_HEIGHT_DELTA;
                double thres = MIN_HEIGHT_THRESHOLD;
                double thresNeg = 1.0D / thres;
                double newYLevel = (((int) (yLevel * thresNeg)) * thres) + delta;
                Vec3 pos = mc.player.position();
                mc.player.setPos(pos.with(Direction.Axis.Y, newYLevel));
                if (!Objects.equals(pos, mc.player.position())) {
                    // must resend because of ojng's shit move threshold
                    if (!move && ViaFabricPlusHooks.isSupportDupRot()) {
                        storedRotation1205 = mc.player.getLookAngle();
                        PlayerStateManager.INSTANCE.restoreLastRotation(mc.player);
                    }
                    ClientPlayerAccess.of(mc.player).resyncPos();
                }
            }
        }

        if (fakeMovementThisTick) {
            movementManagerEvent.cancel();
            movementManagerEvent.context.playerStatus.restorePos();
            fakeMovementThisTick = false;
        }
    }

    public boolean hasTargetNear() {
        if (targetAround.get()) {
            var entity = CombatTasks.getTargetSelector()
                    .searchAttackEntity(CombatTasks.getCombatExtra().getAttackRange(), false);
            return entity != null;
        }
        return true;
    }

    public boolean movementOk() {
        return switch (mode.get()) {
            case FREEZE -> movementOkFreeze.get();
            case GRIM_GROUND_SIMULATION -> movementOkGround.get();
            default -> false;
        };
    }

    public boolean hasNoMovement() {
        var re = PlayerInputUtils.of(mc.options);
        if (movementOk()) {
            return !re.jump() && !re.sneak();
        } else {
            return !re.hasMovement() && !re.sneak();
        }
    }

    @Override
    public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {
        nextAttackIsKillarua = CombatTasks.getAttack().delayAttacking;
    }

    @Override
    public void applyAfterInputTick(Event<LegalMovementManager> movementManagerEvent) {
        PlayerInputUtils.Input input = PlayerInputUtils.of(mc.player);
        if (setbackFlag > 0 && mode.get() == Mode.GRIM_GROUND_SIMULATION && autoWalk.get()) {
            if (!input.hasWASDMovement()) {
                walkCnt += 1;
                if (walkCnt % 2 == 0) {
                    input.left(true);
                } else {
                    input.right(true);
                }
            }
        }
        if (lastStartCacheTick + 3 >= Tasks.getTick()) {
            input.sprint(false).forward(false);
            mc.player.setSprinting(false);
        }
        input.applyInput(mc.player);
        LegalMovementManager.MovementModifier.super.applyAfterInputTick(movementManagerEvent);
    }

    @Override
    public boolean postModify(Event<LegalMovementManager> movementManagerEvent, boolean enabledThisTick) {
        if (storedRotation1205 != null) {
            PlayerStateManager.setPlayerRotationSafe(mc.player, storedRotation1205);
            LegacySnapRotManager.INSTANCE.snapAt(storedRotation1205, false);
            storedRotation1205 = null;
        }
        return true;
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> eventModule) {
        if (eventModule.context().getValue().isOptionPreset()) return;
        switch (eventModule.context.getValue()) {
            case AC_GRIM_LEGACY -> {
                if (mode.get().isIn(Mode.PACKET)) {
                    mode.set(Mode.GRIM_GROUND_SIMULATION);
                }
                autoFakeGround.set(false);
                autoWalk.set(false);
            }
            case AC_GRIM -> {
                if (mode.get().isIn(Mode.PACKET)) {
                    mode.set(Mode.GRIM_GROUND_SIMULATION);
                }
                autoFakeGround.set(true);
                autoWalk.set(true);
            }
            case HACKING, VANILLA -> {
                mode.set(Mode.PACKET);
            }
            default -> {}
        }
    }

    public static enum Mode implements ConfigEnum {
        PACKET,
        FREEZE,
        //        OLD_GRIM_V2,
        //        OLD_GRIM_V3,
        GRIM_GROUND_SIMULATION,
        GRIM_WALL,
        TEST;

        @Override
        public String getConfigEnumType() {
            return "critical_mode";
        }
    }
}
