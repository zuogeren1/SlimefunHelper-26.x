package me.matl114.hacks.utils.entity;

import me.matl114.hacks.modules.combat.CombatExtra;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.PlayerInputUtils;
import me.matl114.hooks.ViaFabricPlusHooks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class EntityMovementStatus<T extends Entity> implements Cloneable {
    public EntityMovementStatus(T entity) {
        this.entity = entity;
        onGround = entity.onGround();
        horizontalCollision = entity.horizontalCollision;
        verticalCollision = entity.verticalCollision;
        groundCollision = entity.verticalCollisionBelow;
        pos = entity.position();
        pitch = entity.getXRot();
        yaw = entity.getYRot();
        vec = entity.getDeltaMovement();
        speed = entity.flyDist;
        distanceTraveled = entity.moveDist;
        sprinting = entity.isSprinting();
        touchingWater = entity.isInWater();
        collidedSoftly = entity.minorHorizontalCollision;
        submergedInWater = entity.isUnderWater();
        inPowderSnow = entity.isInPowderSnow;
    }

    public T entity;
    public boolean onGround;
    public boolean horizontalCollision;
    public boolean verticalCollision;
    public boolean groundCollision;
    public boolean collidedSoftly;
    public Vec3 pos;
    public float pitch;
    public float yaw;
    public Vec3 vec;
    public float speed;
    public float distanceTraveled;
    public boolean sprinting;
    public boolean touchingWater;
    public boolean submergedInWater;
    public boolean inPowderSnow;

    @Override
    @SuppressWarnings("unchecked")
    public EntityMovementStatus<T> clone() {
        try {
            return (EntityMovementStatus<T>) super.clone();
        } catch (CloneNotSupportedException exception) {
            throw new AssertionError(exception);
        }
    }

    /** Advances this snapshot by one tick without moving or updating the source entity. */
    public EntityMovementStatus<T> move0(PlayerInputUtils.Input input) {
        if (entity instanceof Player && entity.isPassenger()) return this;
        if (entity instanceof LivingEntity && !(entity instanceof LocalPlayer)) {
            pos = CombatExtra.INSTANCE
                    .getTracker(entity)
                    .getNextInterpolatedPosition(entity, 1, CombatExtra.INSTANCE.maySkipInterpolationTick());
            return this;
        }
        if (!(entity instanceof LocalPlayer)) {
            Vec3 tracked = entity.trackingPosition();
            pos = tracked.equals(Vec3.ZERO) ? entity.position() : tracked;
            return this;
        }
        Vec3 movement = vec;
        boolean legacyVelocityThreshold =
                ViaFabricPlusHooks.getInstance().getCurrentVersion().isLowerOrEqualTo(21, 4);
        boolean stopHorizontal = movement.horizontalDistanceSqr() < 9.0E-6;
        double x = (legacyVelocityThreshold ? Math.abs(movement.x) < 0.003 : stopHorizontal) ? 0.0 : movement.x;
        double z = (legacyVelocityThreshold ? Math.abs(movement.z) < 0.003 : stopHorizontal) ? 0.0 : movement.z;
        movement = new Vec3(x, Math.abs(movement.y) < 0.003 ? 0.0 : movement.y, z);
        if (entity instanceof LocalPlayer) {
            if (input.sneak() || input.backward() || input.forwardSpeed() == 0) {
                sprinting = false;
            } else if (input.sprint()) {
                sprinting = true;
            }
        }
        float slipperiness = onGround
                ? entity.level()
                        .getBlockState(entity.getBlockPosBelowThatAffectsMyMovement())
                        .getBlock()
                        .getFriction()
                : 1.0F;
        boolean gliding = entity instanceof LivingEntity living && living.isFallFlying();
        boolean flying = entity instanceof LocalPlayer player && player.getAbilities().flying;
        boolean inFluid = entity instanceof LivingEntity living && living.isInLiquid();
        if (flying) {
            int verticalInput = input.upwardSpeed();
            if (verticalInput != 0 && entity instanceof LocalPlayer player) {
                movement =
                        movement.add(0.0, verticalInput * player.getAbilities().getFlyingSpeed() * 3.0F, 0.0);
            }
        }
        if (entity instanceof Player player && player.isSwimming()) {
            double lookY = Vec3.directionFromRotation(pitch, yaw).y;
            double factor = lookY < -0.2 ? 0.085 : 0.06;
            if (lookY <= 0.0 || input.jump()) {
                movement = movement.add(0.0, (lookY - movement.y) * factor, 0.0);
            }
        }
        if (gliding) {
            movement = calculateGlidingVelocity();
        } else if (entity instanceof LivingEntity living) {
            boolean canJumpFromGround = !inFluid
                    || entity.isInWater() && entity.getFluidHeight(FluidTags.WATER) <= entity.getFluidJumpThreshold()
                    || entity.isInLava() && entity.getFluidHeight(FluidTags.LAVA) <= entity.getFluidJumpThreshold();
            if (input.jump() && onGround && canJumpFromGround) {
                float jump = (float) living.getAttributeValue(Attributes.JUMP_STRENGTH) + living.getJumpBoostPower();
                movement = new Vec3(movement.x, Math.max(jump, movement.y), movement.z);
                if (sprinting) {
                    float angle = yaw * 0.017453292F;
                    movement = movement.add(-Mth.sin(angle) * 0.2, 0.0, Mth.cos(angle) * 0.2);
                }
            }
            movement = inFluid
                    ? calculateFluidMoveVelocity(input, movement)
                    : calculateLastMoveVelocity(input.forwardSpeed(), input.sidewaysSpeed(), 0.0F, movement);
        }

        if (entity instanceof Player player && pos.equals(entity.position())) {
            movement = player.maybeBackOffFromEdge(movement, MoverType.SELF);
        }
        AABB box = entity.getBoundingBox().move(pos.subtract(entity.position()));
        Vec3 adjusted = entity.noPhysics
                ? movement
                : pos.equals(entity.position())
                        ? entity.collide(movement)
                        : Entity.collideBoundingBox(
                                entity,
                                movement,
                                box,
                                entity.level(),
                                entity.level().getEntityCollisions(entity, box.expandTowards(movement)));
        pos = pos.add(adjusted);
        horizontalCollision = movement.x != adjusted.x || movement.z != adjusted.z;
        verticalCollision = movement.y != adjusted.y;
        groundCollision = verticalCollision && movement.y < 0.0;
        onGround = groundCollision || (onGround && movement.y == 0.0 && !verticalCollision);

        if (!gliding && entity instanceof LivingEntity living && !inFluid) {
            double gravity = entity.getGravity();
            if (movement.y <= 0.0 && living.hasEffect(MobEffects.SLOW_FALLING)) {
                gravity = Math.min(gravity, 0.01);
            }
            double nextY = movement.y;
            var levitation = living.getEffect(MobEffects.LEVITATION);
            if (levitation != null) {
                nextY += (0.05 * (levitation.getAmplifier() + 1) - nextY) * 0.2;
            } else if (!flying && !entity.level().hasChunkAt(entity.blockPosition())) {
                nextY = pos.y > entity.level().getMinY() ? -0.1 : 0.0;
            } else if (!flying) {
                nextY -= gravity;
            }
            nextY = flying ? movement.y * 0.6 : nextY * 0.98;
            vec = new Vec3(movement.x * slipperiness * 0.91F, nextY, movement.z * slipperiness * 0.91F);
        } else if (inFluid && entity instanceof LivingEntity living) {
            if (entity.isInWater()) {
                float drag = sprinting ? 0.9F : 0.8F;
                float efficiency = (float) living.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY);
                if (!onGround) efficiency *= 0.5F;
                drag += (0.54600006F - drag) * efficiency;
                if (living.hasEffect(MobEffects.DOLPHINS_GRACE)) drag = 0.96F;
                vec = movement.multiply(drag, 0.800000011920929, drag);
                vec = living.getFluidFallingAdjustedMovement(entity.getGravity(), movement.y <= 0.0, vec);
            } else {
                vec = living.getFluidFallingAdjustedMovement(
                        entity.getGravity(), movement.y <= 0.0, movement.multiply(0.5, 0.8, 0.5));
            }
        } else {
            vec = movement;
        }
        if (horizontalCollision) {
            vec = new Vec3(movement.x != adjusted.x ? 0.0 : vec.x, vec.y, movement.z != adjusted.z ? 0.0 : vec.z);
        }
        if (verticalCollision) vec = new Vec3(vec.x, 0.0, vec.z);
        return this;
    }

    private Vec3 calculateGlidingVelocity() {
        Vec3 direction = Vec3.directionFromRotation(pitch, yaw);
        float angle = pitch * 0.017453292F;
        double horizontalDirection = direction.horizontalDistance();
        double horizontalSpeed = vec.horizontalDistance();
        double gravity = entity.getGravity();
        if (vec.y <= 0.0 && entity instanceof LivingEntity living && living.hasEffect(MobEffects.SLOW_FALLING)) {
            gravity = Math.min(gravity, 0.01);
        }
        double cosineSquared = Mth.square(Math.cos(angle));
        Vec3 result = vec.add(0.0, gravity * (-1.0 + cosineSquared * 0.75), 0.0);
        if (result.y < 0.0 && horizontalDirection > 0.0) {
            double lift = result.y * -0.1 * cosineSquared;
            result = result.add(
                    direction.x * lift / horizontalDirection, lift, direction.z * lift / horizontalDirection);
        }
        if (angle < 0.0F && horizontalDirection > 0.0) {
            double lift = horizontalSpeed * -Mth.sin(angle) * 0.04;
            result = result.add(
                    -direction.x * lift / horizontalDirection, lift * 3.2, -direction.z * lift / horizontalDirection);
        }
        if (horizontalDirection > 0.0) {
            result = result.add(
                    (direction.x / horizontalDirection * horizontalSpeed - result.x) * 0.1,
                    0.0,
                    (direction.z / horizontalDirection * horizontalSpeed - result.z) * 0.1);
        }
        return result.multiply(0.9900000095367432, 0.9800000190734863, 0.9900000095367432);
    }

    private Vec3 calculateFluidMoveVelocity(PlayerInputUtils.Input input, Vec3 currentVelocity) {
        Vec2 direction = movementInputVector(input.sidewaysSpeed(), input.forwardSpeed());
        direction = EntityUtils.applyMovementFactors(entity, direction);
        float speed = 0.02F;
        if (entity.isInWater() && entity instanceof LivingEntity living) {
            float efficiency = (float) living.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY);
            if (!onGround) efficiency *= 0.5F;
            speed += (living.getSpeed() - speed) * efficiency;
        }
        Vec3 velocity = currentVelocity.add(
                EntityUtils.movementInputToVelocity(new Vec3(direction.x, 0.0, direction.y), speed, yaw));
        if (input.jump()
                && (!onGround
                        || entity.isInWater()
                                && entity.getFluidHeight(FluidTags.WATER) > entity.getFluidJumpThreshold()
                        || entity.isInLava()
                                && entity.getFluidHeight(FluidTags.LAVA) > entity.getFluidJumpThreshold())) {
            velocity = velocity.add(0.0, 0.04, 0.0);
        }
        return velocity;
    }

    private static Vec2 movementInputVector(int sideward, int forward) {
        Vec2 input = new Vec2(sideward, forward);
        return ViaFabricPlusHooks.getInstance().getCurrentVersion().isLowerOrEqualTo(21, 4) ? input : input.normalized();
    }

    public void restore() {
        this.entity.horizontalCollision = horizontalCollision;
        this.entity.verticalCollision = verticalCollision;
        this.entity.verticalCollisionBelow = groundCollision;
        this.entity.minorHorizontalCollision = collidedSoftly;
        this.restorePosRot();
        this.restoreOnGround();

        this.restoreVelocity();
        this.entity.flyDist = speed;
        this.entity.moveDist = distanceTraveled;
        this.entity.setSprinting(sprinting);
    }

    public void restoreOnGround() {
        this.entity.setOnGround(onGround);
    }

    public void restorePosRot() {
        this.restoreRotation();
        this.restorePos();
    }

    public void restoreRotation() {
        EntityUtils.setEntityPitchSafe(this.entity, pitch);
        if (this.entity instanceof LocalPlayer clientPlayer) {
            PlayerStateManager.setPlayerYawSafe(clientPlayer, yaw);
        } else {
            EntityUtils.setEntityYawSafe(this.entity, yaw);
        }
    }

    public void restorePos() {
        this.entity.setPos(pos);
        entity.wasTouchingWater = touchingWater;
        entity.wasEyeInWater = submergedInWater;
        entity.isInPowderSnow = inPowderSnow;
    }

    public void restoreVelocity() {
        this.entity.setDeltaMovement(vec);
    }

    public Vec3 calculateLastMoveVelocity(int forward, int sideward) {
        return calculateLastMoveVelocity(
                forward, sideward, entity instanceof LivingEntity living ? living.yya : 0, vec);
    }

    private Vec3 calculateLastMoveVelocity(int forward, int sideward, float upward, Vec3 currentVelocity) {
        if (this.entity instanceof LivingEntity livingEntity) {
            Vec2 vec2f = movementInputVector(sideward, forward);
            vec2f = EntityUtils.applyMovementFactors(this.entity, vec2f);
            Vec3 vec3d2 = new Vec3(vec2f.x, upward, vec2f.y);
            float f = onGround
                    ? this.entity
                            .level()
                            .getBlockState(this.entity.getBlockPosBelowThatAffectsMyMovement())
                            .getBlock()
                            .getFriction()
                    : 1.0F;
            float speed = livingEntity.getFrictionInfluencedSpeed(f);
            if (sprinting != livingEntity.isSprinting()) {
                speed *= sprinting ? 1.3F : 1.0F / 1.3F;
            }
            Vec3 more = EntityUtils.movementInputToVelocity(vec3d2, speed, yaw);
            Vec3 velocity = currentVelocity.add(more);
            velocity = applyClimbingSpeed0(livingEntity, velocity);
            return velocity;
        } else {
            return this.entity.getDeltaMovement();
        }
    }

    private static Vec3 applyClimbingSpeed0(LivingEntity living, Vec3 motion) {
        if (!living.onClimbable()) return motion;
        double x = Mth.clamp(motion.x, -0.15000000596046448, 0.15000000596046448);
        double y = Math.max(motion.y, -0.15000000596046448);
        double z = Mth.clamp(motion.z, -0.15000000596046448, 0.15000000596046448);
        if (y < 0.0
                && living instanceof Player
                && living.isSuppressingSlidingDownLadder()
                && !living.getInBlockState().is(Blocks.SCAFFOLDING)) {
            y = 0.0;
        }
        return new Vec3(x, y, z);
    }
}
