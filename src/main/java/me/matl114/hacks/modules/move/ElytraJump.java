package me.matl114.hacks.modules.move;

import java.util.*;
import me.matl114.events.Event;
import me.matl114.events.PacketManager;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render3D;
import me.matl114.events.packets.PacketStorage;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.task.MovementManager;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.PlayerInputUtils;
import me.matl114.hacks.utils.RaycastUtils;
import me.matl114.hacks.utils.config.OptionalPrimitive;
import me.matl114.hacks.utils.config.Pos3;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.move.goal.GoalNearBlockPos;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hooks.BaritoneHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.CollisionUtil;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class ElytraJump extends BaseModule implements LegalMovementManager.MovementModifier {
    static LegalMovementManager.DelegateMovementModifier instance;

    public ElytraJump() {
        super("ElytraJump");
        MovementManager.registerMovementPipeline(this);
        bindFlag(enable);
    }

    ModulePath root = makePath(Configs.MOV_CONFIG, "elytra.elytra-flight-legit.elytra-jump");
    public final FlagRef enable = flagBuilder(root.addEnable()).build();
    public final KeyBindRef hotkey =
            moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final FlagRef routeLock = flagBuilder(root.add("route-lock")).build();
    public final KeyBindRef routeLockHotkey = toggleHotkey(
                    root.add("route-lock-hotkey"), new MultiKeyBind(), root.add("route-lock"))
            .build();

    public final NBTRef<OptionalPrimitive<Pos3>> destination = builder(
                    root.add("destination"), OptionalPrimitive.type(Pos3.class))
            .defaultValue(new OptionalPrimitive<>(false, Pos3.TYPE, new Pos3(0, 0, 0)))
            .build();
    //

    public final DoubleRef pitch = doubleBuilder(root.add("pitch"))
            .defaultValue(80.0D)
            .validator(Configs.doubleRange(-90.0D, 90.0D))
            .build();

    public final FlagRef sneak = flagBuilder(root.add("sneak")).build();

    public final DoubleRef groundHeight = doubleBuilder(root.add("ground-height"))
            .defaultValue(3.0D)
            .validator(Configs.doubleRange(0.0D, 10.0D))
            .build();

    public final FlagRef axisStrict = flagBuilder(root.add("axis-strict")).build();

    public final FlagRef usePacketQueue = flagBuilder(root.add("queue-packets"))
            .updateListener(s -> {
                if (!s) {
                    flushImmediately();
                }
            })
            .build();

    private boolean workThisTick;
    private static final int AVOID_OBSTACLE_YAW_RANGE = 50;
    private static final int OBSTACLE_SEARCH_TICKS = 50;
    private static final double ROUTE_SEARCH_DISTANCE = 128.0D;
    private static final double ROUTE_SEARCH_STEP = 0.5D;
    private Route route;
    private BlockPos routeSafeTarget;
    private boolean climbingStep;
    private boolean ownsRoadPath;
    private final RenderCollector<List<Vec3>> lineCollector = RenderCollectors.createLinesCollector();

    private record Route(Vec3 origin, Vec3 direction) {
        private Route {
            origin = origin.with(Direction.Axis.Y, Math.floor(origin.y));
            direction = direction.with(Direction.Axis.Y, 0.0D).normalize();
        }

        private Vec3 project(Vec3 position) {
            double alongRoute = position.subtract(origin).dot(direction);
            return origin.add(direction.scale(Math.max(0.0D, alongRoute)));
        }
    }

    private void flushImmediately() {
        if (checkNull()) return;
        PacketManager.schedule(() -> {
            if (queueing) {
                // check for fallflying
                PacketManager.flushOutBound(() -> {
                    queueing = false;
                });
            }
        });
    }

    private void startQueue() {
        if (checkNull()) return;
        PacketManager.schedule(() -> {
            queueing = true;
        });
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(PacketManager.getPacketQueueOutEvent(), this::onPacketQueue);
        registerListener(PacketManager.getQueueShutdownEvent(), this::onPacketFlush);
        registerListener(RenderListener.getRender3DEvent(), this::onRender3D);
    }

    private void onRender3D(Event<Render3D> event) {
        if (checkNull() || !routeLock.get()) return;
        lineCollector.render3D(event.context.stack(), event.context.partialTicks());
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        climbingStep = false;
        clearRoadPath();
        if (usePacketQueue.get()) {
            flushImmediately();
        }
    }

    public void onPacketFlush() {
        queueing = false;
    }

    boolean queueing = false;

    public void onPacketQueue(Event<PacketStorage.Send> eventIn) {
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

    private int searchRayLength(float newYaw, int groundY) {
        double currentSpeed = mc.player.getDeltaMovement().horizontalDistance();
        Vec3 direction = EntityUtils.pitchYawToRotation(0, newYaw).normalize().scale(currentSpeed);
        for (var i = 0; i < OBSTACLE_SEARCH_TICKS; ++i) {
            List<BlockPos> obs = checkForObstacles(direction, i, i + 1, groundY + 1, 3);
            if (!obs.isEmpty()) {
                return i;
            }
        }
        return OBSTACLE_SEARCH_TICKS;
    }

    @Override
    public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {
        lineCollector.clear();
        workThisTick = false;
        BlockPos currentLandingBlock = null;
        if (!routeLock.get()) {
            if (route != null) clearRoute();
        } else if (route == null) {
            enable.set(true);
        }

        if (enable.get()) {
            // set the pitch first to avoid conflict with other mode
            List<BlockPos> groundings = CollisionUtil.getIntersectingBlockPositions(
                    mc.level, mc.player.getBoundingBox().expandTowards(0, -groundHeight.get(), 0), false);
            workThisTick = !groundings.isEmpty();
            if (workThisTick) {
                currentLandingBlock = groundings.stream()
                        .max(Comparator.comparingDouble(BlockPos::getY))
                        .orElseThrow();
            }
        }

        BaritoneHooks baritone = BaritoneHooks.getInstance();
        boolean baritonePathing = baritone.isBaritonePathing()
                || baritone.isBaritoneGoalPathingActive()
                || baritone.isBaritoneElytraProcessing();
        if (baritonePathing) {
            workThisTick = false;
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
            movementManagerEvent.context.markForResetRot();
        }

        if (routeLock.get() && route == null) {
            initializeRoute(currentLandingBlock, !baritonePathing);
        }

        Vec3 routeProjection = null;
        boolean routeInRange = false;
        if (routeLock.get() && route != null) {
            routeProjection = route.project(mc.player.position());
            double horizontalProjectionDistanceSquared =
                    mc.player.position().subtract(routeProjection).horizontalDistanceSqr();
            routeInRange = horizontalProjectionDistanceSquared < 100.0D;
            if (horizontalProjectionDistanceSquared <= 256.0D * 256.0D) {
                lineCollector.submit(
                        List.of(
                                routeProjection,
                                routeProjection.add(route.direction().scale(256.0D))),
                        0xFF00FF00);
            }
        }

        if (routeInRange) {
            if (hasReachedDestination(mc.player.position())) {
                if (enable.get()) enable.set(false);
                routeLock.set(false);
                workThisTick = false;
                clearRoute();
                return;
            }
            float routeYaw = EntityUtils.rotationToYaw(route.direction());
            boolean forwardBlocked = hasForwardObstacle();
            int routeGroundY = BlockPos.containing(route.origin()).getY() - 1;
            int currentPlaneY = currentLandingBlock != null ? currentLandingBlock.getY() : routeGroundY;
            boolean blockedStep = forwardBlocked && !canStepForward(currentPlaneY);
            climbingStep = workThisTick && forwardBlocked && canStepForward(currentPlaneY);

            if (workThisTick) {
                int groundY = currentLandingBlock.getY();
                float maxYaw = routeYaw;
                int maxLen = 0;
                for (var i = 0; i < AVOID_OBSTACLE_YAW_RANGE; i += 2) {
                    float newYaw = routeYaw + i;
                    int len = searchRayLength(newYaw, groundY);
                    if (len >= OBSTACLE_SEARCH_TICKS) {
                        maxYaw = newYaw;
                        maxLen = len;
                        break;
                    } else if (maxLen < len) {
                        maxLen = len;
                        maxYaw = newYaw;
                    }
                    if (i == 0) continue;
                    newYaw = routeYaw - i;
                    len = searchRayLength(newYaw, groundY);
                    if (len >= OBSTACLE_SEARCH_TICKS) {
                        maxYaw = newYaw;
                        maxLen = len;
                        break;
                    } else if (maxLen < len) {
                        maxLen = len;
                        maxYaw = newYaw;
                    }
                }
                if (!baritonePathing) mc.player.setYRot(maxYaw);
            }
            if (!baritonePathing) movementManagerEvent.context.markForResetRot();
            updateRouteRecovery(routeProjection, currentLandingBlock, forwardBlocked, blockedStep);
        } else {
            climbingStep = false;
            clearRoadPath();
        }
    }

    private boolean hasForwardObstacle() {
        Vec3 expected =
                EntityUtils.pitchYawToRotation(0.0F, mc.player.getYRot()).scale(0.2D);
        Vec3 actual = MovTasks.simulateMovement(mc.player, mc.player.position(), expected, false);
        return !Mth.equal(actual.x, expected.x)
                || !Mth.equal(actual.y, expected.y)
                || !Mth.equal(actual.z, expected.z);
    }

    private boolean canStepForward(int currentPlaneY) {
        Vec3 forward = EntityUtils.pitchYawToRotation(0.0F, mc.player.getYRot()).scale(0.2D);
        var playerBox = mc.player.getBoundingBox();
        var raisedBox = playerBox.move(0.0D, currentPlaneY + 1.0D - playerBox.minY, 0.0D);
        return mc.level.noCollision(mc.player, raisedBox.move(forward));
    }

    private void initializeRoute(BlockPos currentLandingBlock, boolean adjustYaw) {
        int groundY = currentLandingBlock != null
                ? currentLandingBlock.getY()
                : BlockPos.containing(mc.player.position().add(0.0D, -0.1D, 0.0D)).getY();
        float yaw = mc.player.getYRot();
        if (axisStrict.get() && adjustYaw) {
            yaw = axis(yaw);
            PlayerStateManager.setPlayerYawSafe(mc.player, yaw);
        }
        route = new Route(
                mc.player.position().with(Direction.Axis.Y, groundY + 1.0D),
                EntityUtils.pitchYawToRotation(0.0F, yaw));
        routeSafeTarget = null;
    }

    private boolean hasReachedDestination(Vec3 playerPosition) {
        OptionalPrimitive<Pos3> target = destination.get();
        if (!target.isPresent()) return false;
        Pos3 position = target.getValue();
        Vec3 targetProjection = route.project(new Vec3(position.x(), position.y(), position.z()));
        return playerPosition.subtract(targetProjection).dot(route.direction()) >= 0.0D;
    }

    private void updateRouteRecovery(
            Vec3 routeProjection, BlockPos currentLandingBlock, boolean forwardBlocked, boolean blockedStep) {
        if (blockedStep) workThisTick = false;
        if (!enable.get() || currentLandingBlock == null) {
            clearRoadPath();
            return;
        }
        int routeGroundY = BlockPos.containing(route.origin()).getY() - 1;
        if (currentLandingBlock.getY() == routeGroundY && !blockedStep) {
            routeSafeTarget = null;
            clearRoadPath();
            return;
        }

        if (mc.player.onGround()) {
            if (!forwardBlocked) {
                routeSafeTarget = null;
                clearRoadPath();
                return;
            }
            routeSafeTarget = findSafeRouteTarget(routeProjection, blockedStep ? 2.0D : 0.0D);
        }
        if (routeSafeTarget == null) {
            clearRoadPath();
            return;
        }
        GoalNearBlockPos goal = new GoalNearBlockPos(
                BlockPos.containing(Vec3.atBottomCenterOf(routeSafeTarget).add(route.direction())));
        if (goal.isInGoal(mc.player.position())) {
            routeSafeTarget = null;
            clearRoadPath();
            return;
        }
        if (!BaritoneHooks.getInstance().isBaritoneAPISupported()) {
            clearRoadPath();
            return;
        }

        workThisTick = false;
        if (!BaritoneHooks.getInstance().isBaritoneGoalPathingActive()) {
            BaritoneHooks.getInstance().setBaritoneCurrentGoal(goal);
            ownsRoadPath = true;
        }
    }

    private BlockPos findSafeRouteTarget(Vec3 routeProjection, double minimumDistance) {
        if (routeSafeTarget != null
                && Vec3.atBottomCenterOf(routeSafeTarget).subtract(routeProjection).dot(route.direction())
                        >= minimumDistance
                && hasSafeRouteFloor(Vec3.atBottomCenterOf(routeSafeTarget))) {
            return routeSafeTarget;
        }
        for (double distance = minimumDistance; distance <= ROUTE_SEARCH_DISTANCE; distance += ROUTE_SEARCH_STEP) {
            Vec3 candidate = routeProjection.add(route.direction().scale(distance));
            BlockPos candidateTarget = BlockPos.containing(candidate);
            if (hasSafeRouteFloor(Vec3.atBottomCenterOf(candidateTarget))) {
                return candidateTarget;
            }
        }
        return null;
    }

    private boolean hasSafeRouteFloor(Vec3 feetPosition) {
        var playerBox = mc.player.dimensions.makeBoundingBox(feetPosition);
        if (CollisionUtil.isBoxCollided(mc.level, mc.player, playerBox)) return false;

        int supportY = BlockPos.containing(feetPosition).getY() - 1;
        int minBlockX = BlockPos.containing(playerBox.minX, feetPosition.y, feetPosition.z)
                .getX();
        int maxBlockX = BlockPos.containing(playerBox.maxX, feetPosition.y, feetPosition.z)
                .getX();
        int minBlockZ = BlockPos.containing(feetPosition.x, feetPosition.y, playerBox.minZ)
                .getZ();
        int maxBlockZ = BlockPos.containing(feetPosition.x, feetPosition.y, playerBox.maxZ)
                .getZ();
        for (int x = minBlockX; x <= maxBlockX; ++x) {
            for (int z = minBlockZ; z <= maxBlockZ; ++z) {
                BlockPos supportPos = new BlockPos(x, supportY, z);
                var shape = mc.level.getBlockState(supportPos).getCollisionShape(mc.level, supportPos);
                for (var collisionBox : shape.toAabbs()) {
                    var worldBox = collisionBox.move(supportPos);
                    if (Math.abs(worldBox.maxY - feetPosition.y) < 0.05D
                            && worldBox.maxX > playerBox.minX
                            && worldBox.minX < playerBox.maxX
                            && worldBox.maxZ > playerBox.minZ
                            && worldBox.minZ < playerBox.maxZ) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void clearRoute() {
        climbingStep = false;
        lineCollector.clear();
        clearRoadPath();
        route = null;
        routeSafeTarget = null;
    }

    private void clearRoadPath() {
        if (ownsRoadPath && BaritoneHooks.getInstance().isBaritoneGoalPathingActive()) {
            BaritoneHooks.getInstance().cancelBaritone();
        }
        ownsRoadPath = false;
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

    boolean lastFallFly = false;

    @Override
    public void applyAfterInputTick(Event<LegalMovementManager> movementManagerEvent) {
        var re = PlayerInputUtils.of(mc.player);
        if (climbingStep && !mc.player.isFallFlying()) {
            re.jump(true).sprint(true).forward(true).sneak(false).applyInput(mc.player);
            mc.player.setSprinting(true);
        } else if (workThisTick) {
            if (mc.player.onGround()) {
                re.jump(true).sprint(true).forward(true).sneak(sneak.get()).applyInput(mc.player);
                mc.player.setSprinting(true);
            } else {
                if (!mc.player.isFallFlying()) {
                    if (mc.player.tryToStartFallFlying()) {
                        ElytraExtra.INSTANCE.tryStartFallFlying();
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
                startQueue();
            }
        } else {
            flushImmediately();
            ;
        }
        lastFallFly = mc.player.isFallFlying();
    }
}
