package me.matl114.hacks.modules.move;

import java.util.*;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.events.packets.PacketStorage;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.mine.QueueMine;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.move.goal.GoalNearBlockPos;
import me.matl114.hooks.BaritoneHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.CollisionUtil;
import me.matl114.utils.RaycastUtils;
import me.matl114.utils.entity.PlayerInputUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class ElytraJump extends BaseModule implements LegalMovementManager.MovementModifier {
    static LegalMovementManager.DelegateMovementModifier instance;

    public ElytraJump() {
        super("ElytraJump");
        if (instance == null) {
            instance = new LegalMovementManager.DelegateMovementModifier(this::cast);
            MovTasks.PLAYER_PIPELINE_0.addMovementModifierFactory(() -> instance);
        }
        instance.setDelegate(this::cast);
        bindFlag(enable);
    }

    ModulePath root = makePath(Configs.MOV_CONFIG, "elytra.elytra-flight-legit.elytra-jump");
    public final FlagRef enable = flagBuilder(root.addEnable()).build();
    public final KeyBindRef hotkey =
            moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();
    //

    public final DoubleRef pitch =
            doubleBuilder(root.add("pitch")).defaultValue(80.0D).build();

    public final FlagRef sneak = flagBuilder(root.add("sneak")).build();

    public final DoubleRef groundHeight =
            doubleBuilder(root.add("ground-height")).defaultValue(3.0D).build();

    public final FlagRef axisStrict = flagBuilder(root.add("axis-strict")).build();
    public final FlagRef autoMineStoneAndNetherrackObstacles =
            flagBuilder(root.add("auto-mine-obstacles")).build();

    public final FlagRef autoAvoidObstacle =
            flagBuilder(root.add("auto-avoid-obstacles")).build();

    public final IntRef avoidObstacleYawRange = intBuilder(root.add("avoid-obstacles-yaw-range"))
            .defaultValue(50)
            .show(this.autoAvoidObstacle::get)
            .build();

    public final IntRef predictTicks = intBuilder(root.add("avoid-obstacles-predict-ticks"))
            .defaultValue(80)
            .build();

    public final FlagRef avoidHoles = flagBuilder(root.add("avoid-holes")).build();

    public final FlagRef usePacketQueue = flagBuilder(root.add("queue-packets"))
            .updateListener(s -> {
                if (!s) {
                    flushImmediately();
                }
            })
            .build();

    boolean workThisTick = false;
    BlockPos currentLandingBlock;
    List<BlockPos> obstacles = new ArrayList<>();
    private static final int OBSTACLE_LIMIT = 5;
    private int lastStableHeight = Integer.MIN_VALUE;
    private int stableHeightCounter;
    private BlockPos lastStableBlockTarget;
    private boolean autoWalkAvoidObstacle;
    private boolean needPathingToRoad;
    private boolean ownsRoadPath;

    private void flushImmediately() {
        if (queueing) {
            queueing = false;
            if (checkNull()) return;
            // check for fallflying
            PacketManager.flushOutBound();
        }
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onPreInputEvent);
        registerListener(PacketManager.getPacketQueueOutEvent(), this::onPacketQueue);
        registerListener(PacketManager.getQueueShutdownEvent(), this::onPacketFlush);
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        clearRoadPath();
        autoWalkAvoidObstacle = false;
        if (usePacketQueue.get()) {
            flushImmediately();
        }
    }

    public void onPreInputEvent(Event<Void> event) {
        if (workThisTick
                && currentLandingBlock != null
                && autoMineStoneAndNetherrackObstacles.get()
                && obstacles != null
                && !obstacles.isEmpty()) {
            if (obstacles.size() <= OBSTACLE_LIMIT) {
                obstacles.forEach(re -> QueueMine.INSTANCE.sumitMine(re));
            }
        }
    }

    public void onPacketFlush(Event<Void> event) {
        queueing = false;
    }

    boolean queueing = false;

    public void onPacketQueue(Event<PacketStorage> eventIn) {
        if (queueing && usePacketQueue.get()) {
            PacketType<?> packetType = eventIn.context.packetType();
            if (PacketManager.isAsyncOrNotTransactionC2SPacket(packetType)) {
                return;
            }
            eventIn.cancel();
        }
    }

    private List<BlockPos> checkForObstacles(Vec3 horizontalDirection, double min, double max, int baseY, int height) {
        Vec3 center = mc.player.position().with(Direction.Axis.Y, baseY);
        Vec3 velocity = horizontalDirection.with(Direction.Axis.Y, 0);
        center = center.add(velocity.scale(min));
        Vec3 checkCenter = center.add(velocity.scale(max - min)).add(0, 1, 0);
        Vec3 center1 = center.add(-0.4, 0.1, -0.4);
        Vec3 center2 = center.add(0.4, 0.1, 0.4);
        Vec3 checkCenter1 = checkCenter.add(-0.4, -0.1, -0.4);
        Vec3 checkCenter2 = checkCenter.add(0.4, -0.1, 0.4);
        Set<BlockPos> basePoses = new HashSet<>();
        List<BlockPos> checkPoses = new ArrayList<>();
        for (var re : RaycastUtils.createRaycastBlockPoses(center1, checkCenter1)) {
            basePoses.add(re);
        }
        for (var re : RaycastUtils.createRaycastBlockPoses(center2, checkCenter2)) {
            basePoses.add(re);
        }
        for (var re : basePoses) {
            for (var i = 0; i < height; ++i) {
                BlockPos testPos = re.offset(0, i, 0);
                if (!mc.level
                        .getBlockState(testPos)
                        .getCollisionShape(mc.level, testPos)
                        .isEmpty()) {
                    checkPoses.add(re.offset(0, i, 0));
                }
            }
        }
        return checkPoses;
    }

    public int searchRayLength(float newYaw) {

        double currentSpeed = mc.player.getDeltaMovement().horizontalDistance();
        Vec3 direction = EntityUtils.pitchYawToRotation(0, newYaw).normalize().scale(currentSpeed);
        for (var i = 0; i < predictTicks.get(); ++i) {
            if (autoAvoidObstacle.get()) {
                List<BlockPos> obs = checkForObstacles(direction, i, i + 1, currentLandingBlock.getY() + 1, 3);
                if (!obs.isEmpty()) {
                    return i;
                }
            }
            if (avoidHoles.get()) {
                List<BlockPos> obs = checkForObstacles(direction, i, i + 1, currentLandingBlock.getY(), 1);
                if (obs.isEmpty()) {
                    return i;
                }
            }
        }
        return predictTicks.get();
    }

    @Override
    public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {
        workThisTick = false;
        int lastLanding = currentLandingBlock == null ? Integer.MIN_VALUE : currentLandingBlock.getY();
        if (enable.get()) {
            // set the pitch first to avoid conflict with other mode
            List<BlockPos> groundings = CollisionUtil.getIntersectingBlockPositions(
                    mc.level, mc.player.getBoundingBox().expandTowards(0, -groundHeight.get(), 0), false);
            workThisTick = !groundings.isEmpty();
            if (workThisTick) {
                currentLandingBlock = groundings.stream()
                        .max(Comparator.comparingDouble(BlockPos::getY))
                        .orElseThrow();
            } else {
                currentLandingBlock = null;
            }
            if (workThisTick) {
                Vec3 velocity = mc.player.getDeltaMovement().with(Direction.Axis.Y, 0);

                var b = checkForObstacles(velocity, -1, 3, currentLandingBlock.getY() + 1, 3);
                List<BlockPos> miningBlocks = new ArrayList<>();
                for (var re : b) {
                    BlockState state = mc.level.getBlockState(re);
                    if (!state.isAir() && !state.liquid() && state.getDestroySpeed(mc.level, re) < 3.0) {
                        miningBlocks.add(re);
                    }
                }
                obstacles = miningBlocks;
            } else {
                obstacles = List.of();
            }
        }
        if (workThisTick) {
            if (lastStableHeight < -114514) {
                lastStableHeight = currentLandingBlock.getY();
            }
            if (lastStableHeight == currentLandingBlock.getY()) {
                Vec3 playerPos = mc.player.position().with(Direction.Axis.Y, lastStableHeight + 1);
                Vec3 velocity = PlayerStateManager.INSTANCE.lastKnownClientVelocity.with(Direction.Axis.Y, 0);
                if (velocity.lengthSqr() < 1.0E-4) {
                    velocity = EntityUtils.pitchYawToRotation(0, PlayerStateManager.INSTANCE.lastYaw);
                }
                Vec3 dir = EntityUtils.pitchYawToRotation(0, axis(EntityUtils.rotationToYaw(velocity.normalize())));
                lastStableBlockTarget = BlockPos.containing(playerPos.add(dir.scale(10)));
            }
            if (lastLanding == currentLandingBlock.getY()) {
                stableHeightCounter += 1;
                if (stableHeightCounter > 200) {
                    lastStableHeight = currentLandingBlock.getY();
                }
            } else {
                stableHeightCounter = 0;
            }
        } else if (!enable.get()) {
            lastStableHeight = Integer.MIN_VALUE;
            stableHeightCounter = 0;
            lastStableBlockTarget = null;
            autoWalkAvoidObstacle = false;
            clearRoadPath();
        }
        // 2 blocks lower
        if (workThisTick) {
            if (mc.player.isFallFlying() || lastFallFly) {
                if (mc.player.getDeltaMovement().y >= 0) {
                    mc.player.setXRot((float) pitch.get());
                } else {
                    mc.player.setXRot((float) pitch.get());
                }
            }
            if (axisStrict.get()) {
                PlayerStateManager.setPlayerYawSafe(mc.player, axis(mc.player.getYRot()));
            }
            if (autoAvoidObstacle.get() || avoidHoles.get()) {
                float yaw = mc.player.getYRot();
                float maxYaw = yaw;
                int maxLen = 0;

                for (var i = 0; i < avoidObstacleYawRange.get(); ++i) {
                    float newYaw = yaw + i;
                    int len = searchRayLength(newYaw);
                    if (len >= predictTicks.get()) {
                        maxYaw = newYaw;
                        maxLen = len;
                        break;
                    } else if (maxLen < len) {
                        maxLen = len;
                        maxYaw = newYaw;
                    }
                    if (i == 0) continue;
                    newYaw = yaw - i;
                    len = searchRayLength(newYaw);
                    if (len >= predictTicks.get()) {
                        maxYaw = newYaw;
                        maxLen = len;
                        break;
                    } else if (maxLen < len) {
                        maxLen = len;
                        maxYaw = newYaw;
                    }
                }
                mc.player.setYRot(maxYaw);
            }
            movementManagerEvent.context.markForResetRot();
        }
        if (workThisTick
                && lastStableHeight >= currentLandingBlock.getY()
                && avoidHoles.get()
                && CollisionUtil.isBoxCollided(
                        mc.level,
                        mc.player,
                        mc.player
                                .dimensions
                                .makeBoundingBox(Vec3.atBottomCenterOf(currentLandingBlock)
                                        .add(0, 1, 0))
                                .move(EntityUtils.pitchYawToRotation(0, mc.player.getYRot())))) {
            // check horizontal collision
            Vec3 simulationMove = MovTasks.simulateMovement(
                    mc.player,
                    mc.player.position().with(Direction.Axis.Y, currentLandingBlock.getY() + 1),
                    EntityUtils.pitchYawToRotation(0, mc.player.getYRot()),
                    false);
            if (simulationMove.lengthSqr() < 1E-1) {
                autoWalkAvoidObstacle = true;
                workThisTick = false;
            } else {
                autoWalkAvoidObstacle = false;
            }

        } else {

            autoWalkAvoidObstacle = false;
        }
        if (autoWalkAvoidObstacle) {
            if (mc.player.getY() >= lastStableHeight + 1) {
                autoWalkAvoidObstacle = false;
            } else if (mc.player.getY() < lastStableHeight) {
                needPathingToRoad = true;
                autoWalkAvoidObstacle = false;
            }
        }
        updateRoadPathing();
    }

    private void updateRoadPathing() {
        if (!enable.get() || lastStableHeight == Integer.MIN_VALUE || lastStableBlockTarget == null) {
            clearRoadPath();
            return;
        }
        if (!needPathingToRoad && mc.player.getY() < lastStableHeight) {
            needPathingToRoad = true;
        }
        if (!needPathingToRoad) return;

        workThisTick = false;
        autoWalkAvoidObstacle = false;
        if (currentLandingBlock != null
                && currentLandingBlock.getY() >= lastStableHeight
                && new GoalNearBlockPos(lastStableBlockTarget).isInGoal(mc.player.position())) {
            clearRoadPath();
            return;
        }
        if (!BaritoneHooks.getInstance().isBaritoneAPISupported()) {
            clearRoadPath();
            return;
        }
        if (!BaritoneHooks.getInstance().isBaritoneGoalPathingActive()) {
            BaritoneHooks.getInstance().setBaritoneCurrentGoal(new GoalNearBlockPos(lastStableBlockTarget));
            ownsRoadPath = true;
        }
    }

    private void clearRoadPath() {
        if (ownsRoadPath && BaritoneHooks.getInstance().isBaritoneGoalPathingActive()) {
            BaritoneHooks.getInstance().cancelBaritone();
        }
        ownsRoadPath = false;
        needPathingToRoad = false;
    }

    private float axis(float currentYaw) {
        float yaw = EntityUtils.normalizeYaw(currentYaw);
        float nearest = Math.round(yaw / 45.0f) * 45.0f;

        // 处理 -180 / 180 附近
        if (nearest > 180) nearest -= 360;
        if (nearest < -180) nearest += 360;

        // 判断与最近轴线的角度差
        float diff = Math.abs(yaw - nearest);
        if (diff > 180) diff = 360 - diff;

        return diff <= 10 ? nearest : yaw;
    }

    boolean lastOnGround = false;

    boolean lastFallFly = false;

    @Override
    public void applyAfterInputTick(Event<LegalMovementManager> movementManagerEvent) {
        var re = PlayerInputUtils.of(mc.player);
        if (autoWalkAvoidObstacle) {
            re.jump(true).sprint(true).forward(true).sneak(false).applyInput(mc.player);
        }
        if (workThisTick) {
            if (mc.player.onGround()) {
                re.jump(true).sprint(true).forward(true).sneak(sneak.get()).applyInput(mc.player);

                mc.player.setSprinting(true);

            } else {
                if (!mc.player.isFallFlying()) {
                    if (mc.player.tryToStartFallFlying()) {
                        MovTasks.getMovExtra().sendPacketsForPreStartFallFlying();
                        mc.getConnection()
                                .send(new ServerboundPlayerCommandPacket(
                                        mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                        MovTasks.getMovExtra().sendPacketsForPostStartFallFlying();
                    }
                }
                re.sprint(true).jump(false).forward(true).sneak(sneak.get()).applyInput(mc.player);
            }
        }
        if (workThisTick && usePacketQueue.get()) {
            if (mc.player.onGround()) {
                flushImmediately();
            }
            if (re.jump()) {
                queueing = true;
            }
        } else {
            flushImmediately();
            ;
        }
        lastOnGround = mc.player.onGround();
        lastFallFly = mc.player.isFallFlying();
    }

    @Override
    public boolean postModify(Event<LegalMovementManager> event, boolean enableThisTick) {

        return true;
    }
}
