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
        return new BlockHitResult(crossTargetPose, dir, pos, false);
    }

    public static BlockHitResult createHitResult(BlockPos pos, Direction blockFace) {
        if (mc.player == null) return null;
        Vec3 endVec = Vec3.atCenterOf(pos).relative(blockFace, 0.5);
        return new BlockHitResult(endVec, blockFace, pos, false);
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
        Vec3 vec3d = player.getEyePosition();
        Vec3 look = EntityUtils.pitchYawToRotation(pitch, yaw);
        Vec3 raycast = look.normalize().scale(distance);
        AABB targetBox = target.getBoundingBox();
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
