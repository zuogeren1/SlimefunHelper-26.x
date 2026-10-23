package me.matl114.hacks.utils;

import java.util.Objects;
import java.util.stream.Stream;
import me.matl114.utils.MathUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public interface RayCastCalculator<T> {
    Minecraft client = Minecraft.getInstance();

    AABB getEffectiveBox(T target);

    default Vec3 getRaycast(Vec3 pos, T target) {
        AABB box = getEffectiveBox(target);
        ;
        double range = getReachDistance(target);
        Vec3 fallbackEye = pos.add(0, this.client.player.dimensions.eyeHeight(), 0);
        return getPotentialEyeHeights(pos)
                .filter(eyePos -> box.distanceToSqr(eyePos) <= MathUtils.s2(range))
                .map(eyePos -> {
                    Vec3 ray = raycastDirection(box, eyePos, range);
                    if (box.clip(eyePos, eyePos.add(ray.scale(range))).isPresent()) {
                        return ray;
                    } else {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> raycastDirection(box, fallbackEye, range));
    }

    default Stream<Vec3> getPotentialEyeHeights(Vec3 pos) {
        return Stream.of(pos.add(0, this.client.player.dimensions.eyeHeight(), 0));
    }

    default boolean canRayCast(Vec3 pos, T target, float pitch, float yaw) {
        AABB box = getEffectiveBox(target);
        double range = getReachDistance(target);
        Vec3 direction = EntityUtils.pitchYawToRotation(pitch, yaw).normalize();
        return getPotentialEyeHeights(pos).anyMatch(eyePos -> box.clip(eyePos, eyePos.add(direction.scale(range)))
                .isPresent());
    }

    default Vec3 raycastDirection(AABB box, Vec3 eyePos, double range) {
        Vec3 centerDirection = box.getCenter().subtract(eyePos).normalize();
        double raycastDistance = Math.max(0.0D, range - 0.09178D);
        if (box.clip(eyePos, eyePos.add(centerDirection.scale(raycastDistance)))
                .isPresent()) {
            return centerDirection;
        }
        Vec3 point = MathUtils.magnitudePoint(box.inflate(-1.0E-7), eyePos);
        return point.subtract(eyePos).normalize();
    }

    double getReachDistance(T val);
}
