package me.matl114.hacks.utils;

import com.google.common.base.Preconditions;
import com.mojang.datafixers.util.Pair;
import java.util.*;
import java.util.function.Predicate;
import me.matl114.utils.world.AlignedFace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import me.matl114.utils.ApiMethod;

@ApiMethod
public class RaycastUtils {
    private static final Minecraft mc = Minecraft.getInstance();

    public static boolean raycastAnySolidBlock(Entity e, Vec3 from, Vec3 to) {
        BlockHitResult bResult = raycastSolidBlockResult(e, from, to);
        return bResult != null && bResult.getType() != HitResult.Type.MISS;
    }

    public static BlockHitResult raycastSolidBlockResult(Entity e, Vec3 from, Vec3 to) {
        return mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
    }

    public static boolean raycastHitAnyEntity(Entity e, Vec3 from, Vec3 to) {
        var re = ProjectileUtil.getEntityHitResult(
                e, from, to, new AABB(from, to), es -> !es.isSpectator() && es.isPickable(), 16384);
        return re != null && re.getType() != HitResult.Type.MISS;
    }

    public static boolean raycastHitAnyEntityExceptPlayer(Entity e, Vec3 from, Vec3 to) {
        var re = raycastHitEntityExceptPlayerResult(e, from, to);
        return re != null && re.getType() != HitResult.Type.MISS;
    }

    public static EntityHitResult raycastHitEntityExceptPlayerResult(Entity e, Vec3 from, Vec3 to) {
        return ProjectileUtil.getEntityHitResult(
                e,
                from,
                to,
                new AABB(from, to),
                es -> !es.isSpectator() && es.isPickable() && es != mc.player && es instanceof RemotePlayer,
                16384);
    }

    public static boolean raycastHitAnyBlockOrEntity(Entity owner, Vec3 from, Vec3 to) {
        return raycastAnySolidBlock(owner, from, to) || raycastHitAnyEntity(owner, from, to);
    }

    public static BlockHitResult createRealHitResult(BlockPos pos) {
        Vec3 startVec = mc.player.getEyePosition(1.0f);
        Vec3 endVec = Vec3.atCenterOf(pos);
        Vec3 ray = startVec.subtract(endVec);
        Direction dir = Direction.getApproximateNearest(ray.x, ray.y, ray.z);
        Vec3 crossTargetPose = ray.lengthSqr() > 0.25
                ? switch (dir) {
                    case DOWN -> startVec.subtract(ray.scale((startVec.y - (endVec.y - 0.5)) / ray.y));
                    case UP -> startVec.subtract(ray.scale((startVec.y - (endVec.y + 0.5)) / ray.y));
                    case NORTH -> startVec.subtract(ray.scale((startVec.z - (endVec.z - 0.5)) / ray.z));
                    case SOUTH -> startVec.subtract(ray.scale((startVec.z - (endVec.z + 0.5)) / ray.z));
                    case WEST -> startVec.subtract(ray.scale((startVec.x - (endVec.x - 0.5)) / ray.x));
                    case EAST -> startVec.subtract(ray.scale((startVec.x - (endVec.x + 0.5)) / ray.x));
                }
                : endVec.relative(dir, 0.5);
        return new BlockHitResult(crossTargetPose, dir, pos, isVanillaInsideBlock(pos, startVec, endVec));
    }

    public static BlockHitResult createHitResult(BlockPos pos, Direction blockFace) {
        if (mc.player == null) return null;
        Vec3 endVec = Vec3.atCenterOf(pos).relative(blockFace, 0.5);
        return new BlockHitResult(
                endVec, blockFace, pos, isVanillaInsideBlock(pos, mc.player.getEyePosition(1.0f), endVec));
    }

    private static boolean isVanillaInsideBlock(BlockPos pos, Vec3 start, Vec3 end) {
        BlockHitResult raycastResult = mc.level
                .getBlockState(pos)
                .getShape(mc.level, pos, CollisionContext.of(mc.player))
                .clip(start, end, pos);
        return raycastResult != null && raycastResult.isInside();
    }

    public static EntityHitResult createRealHitResult(Entity entity, Vec3 playerEyePos) {
        AABB box = entity.getBoundingBox();
        Vec3 to = box.getCenter();
        var raycastSurface = box.clip(playerEyePos, to);
        if (raycastSurface != null && raycastSurface.isPresent()) {
            return new EntityHitResult(entity, raycastSurface.get());
        } else {
            return new EntityHitResult(entity);
        }
    }

    public static Optional<BlockPos> rayTraceSpecificBlock(Predicate<Block> blockPredicate) {
        if (mc.level == null || mc.player == null) return Optional.empty();
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHitResult = (BlockHitResult) mc.hitResult;
            Block block = mc.level.getBlockState(blockHitResult.getBlockPos()).getBlock();
            if (blockPredicate.test(block)) {
                return Optional.of(blockHitResult.getBlockPos());
            }
        }
        for (var pos : createRaycastBlockPoses(
                mc.player.getEyePosition(),
                mc.player.getEyePosition().add(mc.player.getLookAngle().scale(6)))) {
            Block block = mc.level.getBlockState(pos).getBlock();
            if (blockPredicate.test(block)) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    public static Optional<Entity> rayTraceSpecificEntity(Predicate<Entity> entityPredicate) {
        if (mc.level == null || mc.player == null) return Optional.empty();
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHitResult = (EntityHitResult) mc.hitResult;
            if (entityPredicate.test(entityHitResult.getEntity())) {
                return Optional.of(entityHitResult.getEntity());
            }
        }
        Vec3 rayCastStart = mc.player.getEyePosition();
        Vec3 rayCastEnd =
                mc.player.getEyePosition().add(mc.player.getLookAngle().scale(6));
        AABB including = new AABB(rayCastStart, rayCastEnd);
        for (var re : mc.level.getEntities(mc.player, including, entityPredicate)) {
            if (re.getBoundingBox().clip(rayCastStart, rayCastEnd).isPresent()) {
                return Optional.of(re);
            }
        }
        return Optional.empty();
    }

    private static final Comparator<Vec3i> PRIORITIZE_LEAST_BLOCK_DISTANCE = Comparator.comparingDouble(
            vec -> -Vec3.atLowerCornerOf(vec).add(0.5, 0.5, 0.5).distanceToSqr(mc.player.position()));

    public static HitResult findBestBlockPlacement(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.canBeReplaced()) {
            // zzz
            return null;
        } else {
            return null;
        }
    }

    private static Vec3 findTargetPointOnFace(BlockState currState, BlockPos currPos, Direction direction) {
        List<AABB> shapeBBs = currState
                .getShape(mc.level, currPos, CollisionContext.of(mc.player))
                .toAabbs();

        return shapeBBs.stream()
                .map(it -> {
                    AlignedFace face = getBoxFace(it, direction);

                    AlignedFace searchFace = face;

                    // Try to aim at the upper portion of the block which makes it easier to switch from full blocks to
                    // half blocks
                    if (searchFace.getTo().y >= 0.9) {
                        AlignedFace truncatedFace = searchFace.truncateY(0.6);
                        if (truncatedFace != null && !truncatedFace.isEmpty()) {
                            searchFace = truncatedFace;
                        }
                    }

                    Vec3 targetPos = searchFace.getCenter();

                    if (targetPos == null) {
                        return (Pair) null;
                    }

                    return new Pair<AlignedFace, Vec3>(searchFace, targetPos);
                })
                .filter(Objects::<Pair<AlignedFace, Vec3>>nonNull)
                .max(Comparator.comparingDouble((it) ->
                                // 取其中direction系列的分量 选择离得最近的
                                ((Pair<AlignedFace, Vec3>) it)
                                        .getSecond()
                                        .subtract(new Vec3(0.5, 0.5, 0.5))
                                        .multiply(Vec3.atLowerCornerOf(direction.getUnitVec3i()))
                                        .lengthSqr())
                        .thenComparingDouble(it -> ((Pair<AlignedFace, Vec3>) it).getSecond().y))
                .map(Pair::getSecond)
                .map(Vec3.class::cast)
                .orElse(null);
    }

    public static AlignedFace getBoxFace(AABB box, Direction direction) {
        return switch (direction) {
            case Direction.DOWN ->
                new AlignedFace(new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.minY, box.maxZ));

            case Direction.UP ->
                new AlignedFace(new Vec3(box.minX, box.maxY, box.minZ), new Vec3(box.maxX, box.maxY, box.maxZ));

            case Direction.SOUTH ->
                new AlignedFace(new Vec3(box.minX, box.minY, box.maxZ), new Vec3(box.maxX, box.maxY, box.maxZ));

            case Direction.NORTH ->
                new AlignedFace(new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.maxY, box.minZ));

            case Direction.EAST ->
                new AlignedFace(new Vec3(box.maxX, box.minY, box.minZ), new Vec3(box.maxX, box.maxY, box.maxZ));

            case Direction.WEST ->
                new AlignedFace(new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.minX, box.maxY, box.maxZ));
        };
    }

    public static HitResult createCrossHairHitResult(
            Entity camera, double blockInteractionRange, double entityInteractionRange, float tickDelta) {
        double d = Math.max(blockInteractionRange, entityInteractionRange);
        double e = Mth.square(d);
        Vec3 vec3d = camera.getEyePosition(tickDelta);
        HitResult hitResult = camera.pick(d, tickDelta, false);
        double f = hitResult.getLocation().distanceToSqr(vec3d);
        if (hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            e = f;
            d = Math.sqrt(e);
        }

        Vec3 vec3d2 = camera.getViewVector(tickDelta);
        Vec3 vec3d3 = vec3d.add(vec3d2.x * d, vec3d2.y * d, vec3d2.z * d);
        float g = 1.0F;
        AABB box = camera.getBoundingBox().expandTowards(vec3d2.scale(d)).inflate(1.0, 1.0, 1.0);
        EntityHitResult entityHitResult = ProjectileUtil.getEntityHitResult(
                camera,
                vec3d,
                vec3d3,
                box,
                (entity) -> {
                    return !entity.isSpectator() && entity.isPickable();
                },
                e);
        return entityHitResult != null && entityHitResult.getLocation().distanceToSqr(vec3d) < f
                ? ensureTargetInRange(entityHitResult, vec3d, entityInteractionRange)
                : ensureTargetInRange(hitResult, vec3d, blockInteractionRange);
    }

    public static HitResult createEntityOnlyCrossHairResult(
            Entity camera, double entityInteractionRange, float tickDelta, Predicate<Entity> filter) {
        double d = entityInteractionRange;
        double e = Mth.square(d);
        Vec3 vec3d = camera.getEyePosition(tickDelta);
        Vec3 vec3d2 = camera.getViewVector(tickDelta);
        Vec3 vec3d3 = vec3d.add(vec3d2.x * d, vec3d2.y * d, vec3d2.z * d);
        AABB box = camera.getBoundingBox().expandTowards(vec3d2.scale(d)).inflate(1.0, 1.0, 1.0);
        EntityHitResult entityHitResult = ProjectileUtil.getEntityHitResult(
                camera,
                vec3d,
                vec3d3,
                box,
                (entity) -> {
                    return !entity.isSpectator() && entity.isPickable() && (filter == null || filter.test(entity));
                },
                e);
        return entityHitResult != null && entityHitResult.getLocation().distanceToSqr(vec3d) < e
                ? ensureTargetInRange(entityHitResult, vec3d, entityInteractionRange)
                : null;
    }

    private static HitResult ensureTargetInRange(HitResult hitResult, Vec3 cameraPos, double interactionRange) {
        Vec3 vec3d = hitResult.getLocation();
        if (!vec3d.closerThan(cameraPos, interactionRange)) {
            Vec3 vec3d2 = hitResult.getLocation();
            Direction direction = Direction.getApproximateNearest(
                    vec3d2.x - cameraPos.x, vec3d2.y - cameraPos.y, vec3d2.z - cameraPos.z);
            return BlockHitResult.miss(vec3d2, direction, BlockPos.containing(vec3d2));
        } else {
            return hitResult;
        }
    }

    public static Iterable<BlockPos> createRaycastBlockPoses(Vec3 start, Vec3 end) {
        return () -> createRaycastBlockPosIterator(start, end);
    }

    public static Iterable<BlockPos> createRaycastBlockPoses(Vec3 start, Vec3 end, boolean enableThreshold) {
        return () -> createRaycastBlockPosIterator(start, end, enableThreshold);
    }

    public static Iterator<BlockPos> createRaycastBlockPosIterator(Vec3 start, Vec3 end) {
        return createRaycastBlockPosIterator(start, end, false);
    }

    public static Iterator<BlockPos> createRaycastBlockPosIterator(Vec3 start, Vec3 end, boolean enableThreshold) {

        // 起点与终点重合时，只返回起点所在方块
        if (start.equals(end)) {
            List<BlockPos> blocks = new ArrayList<>();
            blocks.add(BlockPos.containing(start));
            return blocks.iterator();
        }

        double threshold = enableThreshold ? -1.0E-7D : 0.0D;
        double d = Mth.lerp(threshold, end.x, start.x);
        double e = Mth.lerp(threshold, end.y, start.y);
        double f = Mth.lerp(threshold, end.z, start.z);
        double g = Mth.lerp(threshold, start.x, end.x);
        double h = Mth.lerp(threshold, start.y, end.y);
        double i = Mth.lerp(threshold, start.z, end.z);

        // check pos = start + t * (end - start)

        // origin
        BlockPos pos = BlockPos.containing(g, h, i);

        // direction
        double m = d - g;
        double n = e - h;
        double o = f - i;
        int p = Mth.sign(m);
        int q = Mth.sign(n);
        int r = Mth.sign(o);

        // t + 这么多， 则在该轴上前进1单位
        // 1/d * d = 1;
        double s = p == 0 ? Double.MAX_VALUE : (double) p / m;
        double t = q == 0 ? Double.MAX_VALUE : (double) q / n;
        double u = r == 0 ? Double.MAX_VALUE : (double) r / o;

        return new Iterator<BlockPos>() {
            int j = pos.getX();
            int k = pos.getY();
            int l = pos.getZ();

            // 到达下一个整数边界所需要的值, 我们需要比较三者较小的,同时在更新的时候同步更新这些, 当三者均达到1的时候说明不再有方块
            double v = s * (p > 0 ? 1.0 - Mth.frac(g) : Mth.frac(g));
            double w = t * (q > 0 ? 1.0 - Mth.frac(h) : Mth.frac(h));
            double x = u * (r > 0 ? 1.0 - Mth.frac(i) : Mth.frac(i));

            BlockPos next = pos;

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                } else {
                    if (v <= 1.0 || w <= 1.0 || x <= 1.0) {
                        if (v < w) {
                            if (v < x) {
                                j += p;
                                v += s;
                            } else {
                                l += r;
                                x += u;
                            }
                        } else if (w < x) {
                            k += q;
                            w += t;
                        } else {
                            l += r;
                            x += u;
                        }
                        next = (new BlockPos(j, k, l));
                        return true;
                    }
                }
                return false;
            }

            @Override
            public BlockPos next() {
                BlockPos nextPos = next;
                Preconditions.checkNotNull(nextPos);
                next = null;
                return nextPos;
            }
        };
    }

    public static Iterator<BlockPos> createRaycastBlockPosIterator(AABB box, Vec3 movement, boolean enableThreshold) {
        return new BoxRaycastBlockPosIterator(box, movement, enableThreshold);
    }

    private static final class BoxRaycastBlockPosIterator implements Iterator<BlockPos> {
        private static final double THRESHOLD = 1.0E-7D;
        private static final int MIN_X = 0;
        private static final int MAX_X = 1;
        private static final int MIN_Y = 2;
        private static final int MAX_Y = 3;
        private static final int MIN_Z = 4;
        private static final int MAX_Z = 5;

        private final double[] faceOrigins = new double[6];
        private final double[] faceMovement = new double[6];
        private final double[] nextPlanes = new double[6];
        private final double[] nextTimes = new double[6];
        private final int[] minBlocks = new int[3];
        private final int[] maxBlocks = new int[3];
        private int cursorX;
        private int cursorY;
        private int cursorZ;
        private boolean initialBlocksRemaining;
        private Iterator<BlockPos> enteringLayer = Collections.emptyIterator();
        private BlockPos next;

        private BoxRaycastBlockPosIterator(AABB box, Vec3 movement, boolean enableThreshold) {
            Vec3 initialOffset = enableThreshold ? movement.scale(THRESHOLD) : Vec3.ZERO;
            Vec3 traversalMovement = enableThreshold ? movement.scale(1.0D - 2.0D * THRESHOLD) : movement;
            AABB startBox = new AABB(
                    box.minX + initialOffset.x,
                    box.minY + initialOffset.y,
                    box.minZ + initialOffset.z,
                    box.maxX + initialOffset.x,
                    box.maxY + initialOffset.y,
                    box.maxZ + initialOffset.z);

            faceOrigins[MIN_X] = startBox.minX;
            faceOrigins[MAX_X] = startBox.maxX;
            faceOrigins[MIN_Y] = startBox.minY;
            faceOrigins[MAX_Y] = startBox.maxY;
            faceOrigins[MIN_Z] = startBox.minZ;
            faceOrigins[MAX_Z] = startBox.maxZ;
            faceMovement[MIN_X] = faceMovement[MAX_X] = traversalMovement.x;
            faceMovement[MIN_Y] = faceMovement[MAX_Y] = traversalMovement.y;
            faceMovement[MIN_Z] = faceMovement[MAX_Z] = traversalMovement.z;

            minBlocks[0] = (int) Math.floor(startBox.minX);
            minBlocks[1] = (int) Math.floor(startBox.minY);
            minBlocks[2] = (int) Math.floor(startBox.minZ);
            maxBlocks[0] = (int) Math.ceil(startBox.maxX) - 1;
            maxBlocks[1] = (int) Math.ceil(startBox.maxY) - 1;
            maxBlocks[2] = (int) Math.ceil(startBox.maxZ) - 1;

            initializeFace(MIN_X, true);
            initializeFace(MAX_X, false);
            initializeFace(MIN_Y, true);
            initializeFace(MAX_Y, false);
            initializeFace(MIN_Z, true);
            initializeFace(MAX_Z, false);

            cursorX = minBlocks[0];
            cursorY = minBlocks[1];
            cursorZ = minBlocks[2];
            initialBlocksRemaining =
                    minBlocks[0] <= maxBlocks[0] && minBlocks[1] <= maxBlocks[1] && minBlocks[2] <= maxBlocks[2];
        }

        private void initializeFace(int face, boolean minimumFace) {
            double velocity = faceMovement[face];
            nextPlanes[face] = nextBoundaryPlane(faceOrigins[face], velocity, minimumFace);
            nextTimes[face] =
                    velocity == 0.0D ? Double.POSITIVE_INFINITY : (nextPlanes[face] - faceOrigins[face]) / velocity;
        }

        private static double nextBoundaryPlane(double coordinate, double velocity, boolean minimumFace) {
            if (velocity > 0.0D) {
                return minimumFace ? Math.floor(coordinate) + 1.0D : Math.ceil(coordinate);
            }
            if (velocity < 0.0D) {
                return minimumFace ? Math.floor(coordinate) : Math.ceil(coordinate) - 1.0D;
            }
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            while (true) {
                if (initialBlocksRemaining) {
                    next = nextInitialBlock();
                    return true;
                }
                if (enteringLayer.hasNext()) {
                    next = enteringLayer.next();
                    return true;
                }
                if (!advanceToNextEvent()) {
                    return false;
                }
            }
        }

        private BlockPos nextInitialBlock() {
            BlockPos blockPos = new BlockPos(cursorX, cursorY, cursorZ);
            if (cursorZ < maxBlocks[2]) {
                cursorZ++;
            } else {
                cursorZ = minBlocks[2];
                if (cursorY < maxBlocks[1]) {
                    cursorY++;
                } else {
                    cursorY = minBlocks[1];
                    if (cursorX < maxBlocks[0]) {
                        cursorX++;
                    } else {
                        initialBlocksRemaining = false;
                    }
                }
            }
            return blockPos;
        }

        private boolean advanceToNextEvent() {
            double eventTime = Double.POSITIVE_INFINITY;
            for (double nextTime : nextTimes) {
                eventTime = Math.min(eventTime, nextTime);
            }
            if (!Double.isFinite(eventTime) || eventTime > 1.0D) {
                return false;
            }

            boolean[] crossedFaces = new boolean[6];
            for (int face = 0; face < nextTimes.length; face++) {
                if (nextTimes[face] != eventTime) {
                    continue;
                }
                crossedFaces[face] = true;
                int axis = face / 2;
                int step = faceMovement[face] > 0.0D ? 1 : -1;
                if ((face & 1) == 0) {
                    minBlocks[axis] += step;
                } else {
                    maxBlocks[axis] += step;
                }
                nextPlanes[face] += step;
                nextTimes[face] = (nextPlanes[face] - faceOrigins[face]) / faceMovement[face];
            }

            Set<BlockPos> newBlocks = new LinkedHashSet<>();
            if (faceMovement[MAX_X] > 0.0D && crossedFaces[MAX_X]) {
                addXLayer(newBlocks, maxBlocks[0]);
            } else if (faceMovement[MIN_X] < 0.0D && crossedFaces[MIN_X]) {
                addXLayer(newBlocks, minBlocks[0]);
            }
            if (faceMovement[MAX_Y] > 0.0D && crossedFaces[MAX_Y]) {
                addYLayer(newBlocks, maxBlocks[1]);
            } else if (faceMovement[MIN_Y] < 0.0D && crossedFaces[MIN_Y]) {
                addYLayer(newBlocks, minBlocks[1]);
            }
            if (faceMovement[MAX_Z] > 0.0D && crossedFaces[MAX_Z]) {
                addZLayer(newBlocks, maxBlocks[2]);
            } else if (faceMovement[MIN_Z] < 0.0D && crossedFaces[MIN_Z]) {
                addZLayer(newBlocks, minBlocks[2]);
            }
            enteringLayer = newBlocks.iterator();
            return true;
        }

        private void addXLayer(Set<BlockPos> blocks, int x) {
            for (int y = minBlocks[1]; y <= maxBlocks[1]; y++) {
                for (int z = minBlocks[2]; z <= maxBlocks[2]; z++) {
                    blocks.add(new BlockPos(x, y, z));
                }
            }
        }

        private void addYLayer(Set<BlockPos> blocks, int y) {
            for (int x = minBlocks[0]; x <= maxBlocks[0]; x++) {
                for (int z = minBlocks[2]; z <= maxBlocks[2]; z++) {
                    blocks.add(new BlockPos(x, y, z));
                }
            }
        }

        private void addZLayer(Set<BlockPos> blocks, int z) {
            for (int x = minBlocks[0]; x <= maxBlocks[0]; x++) {
                for (int y = minBlocks[1]; y <= maxBlocks[1]; y++) {
                    blocks.add(new BlockPos(x, y, z));
                }
            }
        }

        @Override
        public BlockPos next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            BlockPos blockPos = next;
            next = null;
            return blockPos;
        }
    }

    @Nullable
    public static HitResult raycastOnlyBlockCollisions(Vec3 start, Vec3 end) {
        for (var block : createRaycastBlockPoses(start, end, true)) {
            BlockState state = mc.level.getBlockState(block);
            VoxelShape shape2 = state.getCollisionShape(mc.level, block);
            if (shape2.isEmpty()) continue;
            BlockHitResult result = shape2.clip(start, end, block);
            if (result != null && result.getType() == HitResult.Type.BLOCK) {
                return result;
            }
        }
        return null;
    }

    public static double getFirstIntersection(double start, double dir, double step) {
        if (dir > 0) {
            return (Math.floor(start) + 1 - start) * step;
        } else if (dir < 0) {
            return (start - Math.floor(start)) * step;
        } else {
            return Double.POSITIVE_INFINITY;
        }
    }

    public static boolean canRaycastHit(Player player, float pitch, float yaw, Entity target) {
        return canRaycastHit(player, pitch, yaw, target, player.entityInteractionRange());
    }

    public static boolean canRaycastHit(Player player, float pitch, float yaw, Entity target, double distance) {
        return canRaycastHit(player, pitch, yaw, target.getBoundingBox(), distance);
    }

    public static boolean canRaycastHit(Player player, float pitch, float yaw, AABB targetBox, double distance) {
        Vec3 vec3d = player.getEyePosition();
        Vec3 look = EntityUtils.pitchYawToRotation(pitch, yaw);
        Vec3 raycast = look.normalize().scale(distance);
        return targetBox.clip(vec3d, vec3d.add(raycast)).isPresent();
    }

    public static boolean canRaycastHit(Player player, float pitch, float yaw, BlockPos pos, double distance) {
        Vec3 vec3d = player.getEyePosition();
        Vec3 look = EntityUtils.pitchYawToRotation(pitch, yaw);
        Vec3 raycast = look.normalize().scale(distance);
        AABB targetBox = new AABB(pos);
        return targetBox.clip(vec3d, vec3d.add(raycast)).isPresent();
    }
}
