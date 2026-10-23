package me.matl114.hacks.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Pair;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import me.matl114.accessors.events.EntityAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.hacks.RenderTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.utils.*;
import me.matl114.utils.containers.MetaData;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;
import me.matl114.hacks.utils.EntityUtils;

public class ProjectileESP extends BaseModule {
    private static final double MAX_PROJECTILE_DISTANCE_SQUARED = 256.0 * 256.0;

    public final ModulePath detectEntity = makePath(Configs.RENDER_CONFIG, "detect-entity");
    public final ModulePath calculateTrace = detectEntity.add("calculate-trace");
    public static ProjectileESP INSTANCE;

    public ProjectileESP() {
        super("ProjectileESP");
        bindFlag(enable);
        INSTANCE = this;
    }

    public final FlagRef enable = flagBuilder(calculateTrace).build();

    public final FlagRef calculateFireball =
            flagBuilder(detectEntity.add("cal-fireball")).build();

    public final FlagRef calculateArrow =
            flagBuilder(detectEntity.add("cal-projectile")).build();

    public final FlagRef renderFireball =
            flagBuilder(detectEntity.add("render-fireball")).build();

    public final FlagRef renderArrow =
            flagBuilder(detectEntity.add("render-projectile")).build();

    private final RenderCollector<List<Vec3>> trajectoryCollector = RenderCollectors.createLinesCollector();
    private final RenderCollector<Entity> entityImpactCollector =
            RenderCollectors.createEntityBoxCollector(false, true, false);
    private final RenderCollector<AABB> impactCollector = RenderCollectors.createBoxCollector(false, true, false);

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getEntityClientVelocityUpdate(), this::onVelocityFireball);
        registerListener(Listener.getEntityClientVelocityUpdate(), this::onVelocityArrow);
        registerListener(Listener.getPostGameTick(), this::updateRenderCollectors);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
        registerListener(Listener.getServerEntitySpawnListener(), this::onEntitySpawn);
    }

    private static final String flagCalculateProjectile = "slimefunhelper:calculate_projectile";

    public void onEntitySpawn(Event<Entity> event) {
        if (enable.get()
                && calculateFireball.get()
                && event.context() instanceof AbstractHurtingProjectile projectile) {
            onVelocityFireballCal(projectile, projectile.getDeltaMovement());
        }
    }

    public void onVelocityFireball(Event<Vec3> fireballEvent) {
        if (enable.get() && calculateFireball.get()) {
            Entity entity = fireballEvent.getArgs(0);
            if (entity instanceof AbstractHurtingProjectile fireball) {
                Vec3 vec = fireballEvent.context();
                onVelocityFireballCal(fireball, vec);
            }
        }
    }

    public void onVelocityFireballCal(AbstractHurtingProjectile fireball, Vec3 vec) {
        if (isWithinPlayerRange(fireball.position()) && vec.lengthSqr() > 1e-10) {
            EntityAccess<AbstractHurtingProjectile> access = EntityAccess.of(fireball);
            if (access.getMetadata().get(this, flagCalculateProjectile) == null) {
                access.getMetadata().put(this, flagCalculateProjectile, Boolean.TRUE);
                calLineTrace(fireball.position(), vec, fireball.getType());
            }
        }
    }

    public void onVelocityArrow(Event<Vec3> arrowEvent) {
        if (enable.get() && calculateArrow.get()) {
            Entity entity = arrowEvent.getArgs(0);
            if (entity instanceof ThrownTrident trident) {

            } else if (entity instanceof AbstractArrow arrow) {
                Vec3 vec = arrowEvent.context();
                if (isWithinPlayerRange(arrow.position()) && vec.lengthSqr() > 1e-10) {
                    EntityAccess<AbstractArrow> access = EntityAccess.of(arrow);
                    MetaData metaData = access.getMetadata();
                    Integer integer = metaData.get(this, flagCalculateProjectile);
                    if (integer != null) {
                        if (integer >= 3) {
                            arrow.setDeltaMovement(vec);
                            calArrowTrace(arrow);
                        }
                        metaData.put(this, flagCalculateProjectile, integer + 1);
                    } else {
                        metaData.put(this, flagCalculateProjectile, 1);
                    }
                }
            }
        }
    }

    public static void calLineTrace(Vec3 fireballPosition, Vec3 power, EntityType<?> type) {
        // (x - x0)/px = (y - y0)/py = (z - z0)/pz
        if (mc.player != null && isWithinPlayerRange(fireballPosition)) {
            // 给行进方向norm
            power = power.normalize();
            var playerPos = mc.player.getEyePosition();
            var deltaTo = playerPos.subtract(fireballPosition);
            // 求出玩家位置在行进方向上的投影长度
            var projLen = deltaTo.dot(power);
            if (projLen > 0) {
                // 勾股定理求出最短距离
                var projPoint = fireballPosition.add(power.scale(projLen));
                var lookAtProjPoint = projPoint.subtract(playerPos);
                var minDist = lookAtProjPoint.length();
                Vector2d planeVec = new Vector2d(lookAtProjPoint.x, lookAtProjPoint.z);
                // cal direction
                Vector2d playerLookat = EntityUtils.getEntityLookXZ(mc.player);
                boolean front = planeVec.dot(playerLookat) > 0;
                Debug.chat(
                        type.getDescription(),
                        "trace:",
                        Component.literal("%.2f".formatted(minDist)).withStyle(ChatFormatting.RED),
                        (front ? Component.literal("in front of") : Component.literal("at back of"))
                                .withStyle(ChatFormatting.GREEN),
                        "you");
            } else {
                Debug.chat("Fireball trace update: not towards you");
            }
        } else {
            // Debug.info("null player");
        }
    }

    public static void calArrowTrace(AbstractArrow arrow) {
        if (mc.player != null && isWithinPlayerRange(arrow.position())) {
            if (arrow.getOwner() == mc.player) return;
            if (mc.player.position().distanceToSqr(arrow.position()) < 0.1) {
                // might be shot by player using something
                return;
            }
            // 给行进方向norm
            Vec3 vec3d = arrow.getDeltaMovement();
            Vec3 vecDirection = vec3d.normalize();
            var playerPos = mc.player.getEyePosition();
            var deltaTo = playerPos.subtract(arrow.position());
            // 求出玩家位置在行进方向上的投影长度
            var projLen = deltaTo.dot(vecDirection);
            if (projLen > 0) {
                // 勾股定理求出最短距离
                List<Vec3> preciseLine = ArrowPredictor.of(arrow, 0.0F).predictLine(400);
                Vec3 proj = null;
                double lenSquared = 144000000;
                for (var vec : preciseLine) {
                    double len = vec.distanceToSqr(playerPos);
                    if (len < lenSquared) {
                        proj = vec;
                        lenSquared = len;
                    }
                }
                if (proj == null) return;
                Vector2d planeVec = new Vector2d(proj.x, proj.z);
                double minDist = Math.sqrt(lenSquared);
                // cal direction
                Vector2d playerLookat = EntityUtils.getEntityLookXZ(mc.player);
                boolean front = planeVec.dot(playerLookat) > 0;
                Debug.chat(
                        "Arrow trace update:",
                        Component.literal("%.2f".formatted(minDist)).withStyle(ChatFormatting.RED),
                        (front ? Component.literal("in front of") : Component.literal("at back of"))
                                .withStyle(ChatFormatting.GREEN),
                        "you");
            } else {
                Debug.chat("Arrow trace update: not towards you");
            }
        } else {
            // Debug.info("null player");
        }
    }

    private void updateRenderCollectors() {
        trajectoryCollector.clear();
        entityImpactCollector.clear();
        impactCollector.clear();
        if (checkNull() || !enable.get()) return;

        for (var entity : mc.level.entitiesForRendering()) {
            if (!isWithinPlayerRange(entity.position())) continue;
            if (entity instanceof AbstractHurtingProjectile explosive && renderFireball.get()) {
                submitTrajectory(predictFireballTrace(explosive), Color.RED, false);
            }
            if (!renderArrow.get()) continue;
            if (entity instanceof AbstractSkeleton skeleton && !(skeleton instanceof WitherSkeleton)) {
                if (skeleton.isUsingItem() && skeleton.getUseItem().getItem() instanceof BowItem) {
                    submitTrajectory(ArrowPredictor.of(skeleton, 0.0F).predictLine(400), Color.YELLOW, true);
                }
            } else if (entity instanceof Player player) {
                submitPlayerTrajectory(player);
            } else if (entity instanceof CrossbowAttackMob crossbow) {
                if (crossbow instanceof LivingEntity living
                        && living.isUsingItem()
                        && living.getUseItem().getItem() instanceof ProjectileWeaponItem) {
                    submitTrajectory(ArrowPredictor.of(crossbow, 0.0F).predictLine(400), Color.YELLOW, true);
                }
            } else if (entity instanceof AbstractArrow projectile
                    && !projectile.onGround()
                    && projectile.getDeltaMovement().lengthSqr() > 1e-5) {
                submitTrajectory(ArrowPredictor.of(projectile, 0.0F).predictLine(400), Color.RED, true);
            }
        }
    }

    private void submitPlayerTrajectory(Player player) {
        for (var hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).getItem() instanceof ProjectileWeaponItem item) {
                var data = ArrowPredictor.of(player, item, hand, 0.0F).predictLineWithHitResult(400);
                if (data.getFirst().size() > 3
                        && isWithinPlayerRange(data.getFirst().getFirst())) {
                    trajectoryCollector.submit(data.getFirst(), Color.RED.getRGB());
                    if (data.getSecond() instanceof EntityHitResult hit) {
                        entityImpactCollector.submit(
                                hit.getEntity(),
                                ColorUtils.withAlpha(Color.GREEN, 0.25F).getRGB());
                    } else {
                        submitImpact(data.getFirst().getLast());
                    }
                }
                return;
            }
        }
    }

    private void submitTrajectory(List<Vec3> trajectory, Color color, boolean submitImpact) {
        if (trajectory.size() <= 3 || !isWithinPlayerRange(trajectory.getFirst())) return;
        trajectoryCollector.submit(trajectory, color.getRGB());
        if (submitImpact) submitImpact(trajectory.getLast());
    }

    private void submitImpact(Vec3 position) {
        impactCollector.submit(
                new AABB(position.add(RenderTasks.SMALL_FROM), position.add(RenderTasks.SMALL_TO)),
                ColorUtils.withAlpha(Color.GREEN, 0.25F).getRGB());
    }

    public void onRender(Event<Render3D> stackE) {
        if (!enable.get()) return;
        var stack = stackE.context.stack();
        RenderUtils.startDrawVirtual(stack);
        try {
            trajectoryCollector.render3D(stack, stackE.context.partialTicks());
            entityImpactCollector.render3D(stack, stackE.context.partialTicks());
            impactCollector.render3D(stack, stackE.context.partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(stack);
        }
    }

    public static class ArrowPredictor {
        Vec3 pos;
        Vec3 vec;
        Entity owner;
        private static final RandomSource random = net.minecraft.util.RandomSource.create();
        private static Vec3 lastRand;
        private static int lastRandTime = 0;

        private static Vec3 getArrowRand() {
            if (true) return Vec3.ZERO;
            if (lastRandTime + 20 < Tasks.getTick()) {
                lastRandTime = Tasks.getTick();
                float uncertainty = 1.0f;
                lastRand = new Vec3(
                        random.triangle(0.0, 0.0172275 * (double) uncertainty),
                        random.triangle(0.0, 0.0172275 * (double) uncertainty),
                        random.triangle(0.0, 0.0172275 * (double) uncertainty));
            }
            return lastRand;
        }

        private static Vec3 calculateVelocity(double x, double y, double z, float power) {
            return (new Vec3(x, y, z)).normalize().add(getArrowRand()).scale((double) power);
        }

        public static ArrowPredictor of(AbstractSkeleton entity, float tickDelta) {
            Vec3 originPos = new Vec3(entity.getX(), entity.getEyeY() - 0.10000000149011612, entity.getZ())
                    .add(RenderUtils.getLerpedDelta(entity, tickDelta));
            Vec3 facing = entity.getLookAngle();
            double d = facing.x();
            double f = facing.z();
            double g = Math.sqrt(d * d + f * f);

            //            Debug.info(entity.getTarget());
            Vec3 vec3d = calculateVelocity(d, facing.y + g * 0.2, f, 1.6F);
            return new ArrowPredictor(originPos, vec3d, entity);
        }

        private static float getPullProgress(int useTicks) {
            float f = (float) useTicks / 20.0F;
            f = (f * f + f * 2.0F) / 3.0F;
            if (f > 1.0F) {
                f = 1.0F;
            }

            return f;
        }

        private static Vec3 getHandOffset(Player player, InteractionHand hand) {
            double yaw = Math.toRadians(player.getYRot());
            HumanoidArm mainArm = mc.options.mainHand().get();

            boolean rightSide = mainArm == HumanoidArm.RIGHT && hand == InteractionHand.MAIN_HAND
                    || mainArm == HumanoidArm.LEFT && hand == InteractionHand.OFF_HAND;

            double sideMultiplier = rightSide ? -1 : 1;
            double handOffsetX = Math.cos(yaw) * 0.16 * sideMultiplier;
            double handOffsetZ = Math.sin(yaw) * 0.16 * sideMultiplier;

            return new Vec3(handOffsetX, 0, handOffsetZ);
        }

        public static ArrowPredictor of(
                Player player, ProjectileWeaponItem weaponItem, InteractionHand hand, float tickDelta) {
            Vec3 vec3d;
            final Vec3 offset = getHandOffset(player, hand);
            Vec3 pos = new Vec3(player.getX(), player.getEyeY() - 0.10000000149011612, player.getZ())
                    .add(offset)
                    .add(RenderUtils.getLerpedDelta(player, tickDelta));
            if (weaponItem instanceof BowItem) {
                int usingTicks =
                        (player.isUsingItem() && player.getUsedItemHand() == hand) ? player.getTicksUsingItem() : 1000;
                float progress = getPullProgress(usingTicks);
                float speed = progress * 3.0f;
                Vec3 facing = player.getLookAngle();
                vec3d = calculateVelocity(facing.x, facing.y, facing.z, speed);
                // add player velocity here
                Vec3 infect0 = player.getDeltaMovement();
                vec3d = vec3d.add(infect0.x, player.onGround() ? 0.0D : infect0.y, infect0.z);
            } else {
                Vec3 facing = player.getViewVector(1.0f);
                float speed = 3.15F;
                vec3d = calculateVelocity(facing.x, facing.y, facing.z, speed);
            }
            return new ArrowPredictor(pos, vec3d, player) {
                @Override
                public List<Vec3> predictLine(int ticks) {
                    List<Vec3> list = super.predictLine(ticks);
                    int size = list.size();
                    if (size == 0) return list;
                    List<Vec3> list3d = new ArrayList<>();
                    for (int i = 0; i < size; ++i) {
                        list3d.add(list.get(i).subtract(offset.scale(((i + 1) / (double) size))));
                    }
                    return list3d;
                }
            };
        }

        public static ArrowPredictor of(AbstractArrow arrow, float tickDelta) {
            return new ArrowPredictor(RenderUtils.getLerpedPos(arrow, tickDelta), arrow.getDeltaMovement(), arrow);
        }

        public static ArrowPredictor of(CrossbowAttackMob user, float tickDelta) {
            Entity player = (Entity) user;
            Vec3 facing = (player).getViewVector(1.0f);
            float speed = 1.6F;

            Vec3 vec3d = calculateVelocity(facing.x, facing.y, facing.z, speed);
            Vec3 pos = new Vec3(player.getX(), player.getEyeY() - 0.10000000149011612, player.getZ())
                    .add(RenderUtils.getLerpedDelta((Entity) user, tickDelta));
            return new ArrowPredictor(pos, vec3d, player);
        }

        public ArrowPredictor(Vec3 pos, Vec3 vec, Entity owner) {
            this.pos = pos;
            this.vec = vec;
            this.owner = owner;
        }

        public static enum Type {
            SKELETON,
            PLAYER,
            ARROW,
            CROSSBOW;
        }

        public List<Vec3> predictLine(int ticks) {
            Vec3 rangeCenter = mc.player == null ? null : mc.player.position();
            if (rangeCenter != null && !isWithinPlayerRange(pos, rangeCenter)) return List.of();
            Vec3 arrowPos = pos;
            Vec3 arrowMotion = vec;
            double gravity = EntityUtils.getProjectileGravity(Items.BOW);
            List<Vec3> path = new ArrayList<>();
            Vec3 lastPos;
            if (this.vec.lengthSqr() < 1e-5) {
                return List.of();
            }
            for (int i = 0; i < ticks; i++) {
                // add to path
                path.add(arrowPos);
                // apply motion
                arrowPos = arrowPos.add(arrowMotion.scale(0.1));
                boolean rangeExceeded = rangeCenter != null && !isWithinPlayerRange(arrowPos, rangeCenter);
                if (rangeExceeded) {
                    arrowPos = clipToPlayerRange(path.getLast(), arrowPos, rangeCenter);
                    path.add(arrowPos);
                }

                // apply air friction
                arrowMotion = arrowMotion.scale(0.999);

                // apply gravity
                arrowMotion = arrowMotion.add(0, -gravity * 0.1, 0);

                if (path.size() > 2) {
                    lastPos = path.get(path.size() - 2);
                    HitResult hitResult = RaycastUtils.raycastOnlyBlockCollisions(lastPos, arrowPos);
                    if ((hitResult != null && hitResult.getType() == HitResult.Type.BLOCK)
                            || RaycastUtils.raycastHitAnyEntityExceptPlayer(owner, lastPos, arrowPos)) {
                        break;
                    }
                }
                if (rangeExceeded) break;
            }
            return path;
        }

        public Pair<List<Vec3>, HitResult> predictLineWithHitResult(int ticks) {
            Vec3 rangeCenter = mc.player == null ? null : mc.player.position();
            if (rangeCenter != null && !isWithinPlayerRange(pos, rangeCenter)) return Pair.of(List.of(), null);
            Vec3 arrowPos = pos;
            Vec3 arrowMotion = vec;
            double gravity = EntityUtils.getProjectileGravity(Items.BOW);
            List<Vec3> path = new ArrayList<>();
            Vec3 lastPos;
            if (this.vec.lengthSqr() < 1e-5) {
                return Pair.of(List.of(), null);
            }
            HitResult result = null;
            for (int i = 0; i < ticks; i++) {
                // add to path
                path.add(arrowPos);
                // apply motion
                arrowPos = arrowPos.add(arrowMotion.scale(0.1));
                boolean rangeExceeded = rangeCenter != null && !isWithinPlayerRange(arrowPos, rangeCenter);
                if (rangeExceeded) {
                    arrowPos = clipToPlayerRange(path.getLast(), arrowPos, rangeCenter);
                    path.add(arrowPos);
                }

                // apply air friction
                arrowMotion = arrowMotion.scale(0.999);

                // apply gravity
                arrowMotion = arrowMotion.add(0, -gravity * 0.1, 0);

                if (path.size() > 2) {
                    lastPos = path.get(path.size() - 2);
                    result = RaycastUtils.raycastOnlyBlockCollisions(lastPos, arrowPos);
                    if (result != null && result.getType() != HitResult.Type.MISS) {
                        break;
                    }
                    result = RaycastUtils.raycastHitEntityExceptPlayerResult(owner, lastPos, arrowPos);
                    if (result != null && result.getType() != HitResult.Type.MISS) {
                        break;
                    }
                    result = null;
                }
                if (rangeExceeded) break;
            }
            return Pair.of(path, result);
        }
    }

    public static ArrayList<Vec3> predictFireballTrace(AbstractHurtingProjectile fireball) {
        ArrayList<Vec3> trace = new ArrayList<>();
        Vec3 startpos = fireball.position();
        if (!isWithinPlayerRange(startpos)) return trace;
        Vec3 rangeCenter = mc.player == null ? null : mc.player.position();
        Vec3 lastPos = startpos;

        float drag = 0.95F;
        Vec3 motion = fireball.getDeltaMovement();
        Vec3 power = motion.normalize().scale(fireball.accelerationPower);

        trace.add(startpos);

        for (int i = 0; i < 400; ++i) {
            Vec3 nextPos = startpos.add(motion);
            boolean rangeExceeded = rangeCenter != null && !isWithinPlayerRange(nextPos, rangeCenter);
            if (rangeExceeded) {
                nextPos = clipToPlayerRange(startpos, nextPos, rangeCenter);
            }
            startpos = nextPos;
            motion = motion.add(power).scale(drag);
            trace.add(startpos);
            if (trace.size() > 2) {
                lastPos = trace.get(trace.size() - 2);
                if (RaycastUtils.raycastAnySolidBlock(fireball, lastPos, startpos)
                        || RaycastUtils.raycastHitAnyEntityExceptPlayer(fireball, lastPos, startpos)) {
                    break;
                }
            }
            if (rangeExceeded) break;
        }
        return trace;
    }

    private static boolean isWithinPlayerRange(Vec3 position) {
        return mc.player == null || isWithinPlayerRange(position, mc.player.position());
    }

    private static boolean isWithinPlayerRange(Vec3 position, Vec3 playerPosition) {
        return playerPosition.distanceToSqr(position) <= MAX_PROJECTILE_DISTANCE_SQUARED;
    }

    private static Vec3 clipToPlayerRange(Vec3 from, Vec3 to, Vec3 playerPosition) {
        Vec3 direction = to.subtract(from);
        Vec3 offset = from.subtract(playerPosition);
        double a = direction.lengthSqr();
        if (a <= 1.0E-12) return from;

        double b = 2.0 * offset.dot(direction);
        double c = offset.lengthSqr() - MAX_PROJECTILE_DISTANCE_SQUARED;
        double discriminant = Math.max(0.0, b * b - 4.0 * a * c);
        double fraction = (-b + Math.sqrt(discriminant)) / (2.0 * a);
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return from.add(direction.scale(fraction));
    }
}
