package me.matl114.hacks.utils;

import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;
import me.matl114.hacks.utils.move.RaycastRotationSolver;
import me.matl114.hacks.utils.move.Rotation;
import me.matl114.utils.MathUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public interface RayCastCalculator<T> {
    Minecraft client = Minecraft.getInstance();

    AABB getEffectiveBox(T target);

    default Vec3 getRaycast(Vec3 pos, T target) {
        AABB box = getEffectiveBox(target);
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
        return getPotentialEyeHeights(pos)
                .anyMatch(eyePos ->
                        box.contains(eyePos) || RaycastRotationSolver.canRayCast(box, eyePos, range, pitch, yaw));
    }

    default @Nullable Rotation findRotation(Vec3 pos, T target, Rotation locked, float pitch, float yaw) {
        if (locked.hasPitch() && locked.hasYaw()) return locked;
        float lockedPitch = locked.getPitch(pitch);
        float lockedYaw = locked.getYaw(yaw);
        AABB box = getEffectiveBox(target);
        double range = getReachDistance(target);
        Iterator<Vec3> eyes = getPotentialEyeHeights(pos).iterator();
        while (eyes.hasNext()) {
            Vec3 eye = eyes.next();
            if (box.contains(eye)) return locked;
            if (!locked.hasRotate()) {
                if (box.distanceToSqr(eye) > MathUtils.s2(range)) continue;
                Vec3 ray = raycastDirection(box, eye, range);
                if (box.clip(eye, eye.add(ray.scale(range))).isPresent()) return Rotation.from(ray);
                continue;
            }
            if (RaycastRotationSolver.canRayCast(box, eye, range, lockedPitch, lockedYaw))
                return Rotation.from(lockedPitch, lockedYaw);
            if (locked.hasPitch()) {
                Float solvedYaw = RaycastRotationSolver.solveYaw(box, eye, range, lockedPitch, lockedYaw);
                if (solvedYaw != null) return Rotation.from(lockedPitch, solvedYaw);
            } else {
                Float solvedPitch = RaycastRotationSolver.solvePitch(box, eye, range, lockedYaw, lockedPitch);
                if (solvedPitch != null) return Rotation.from(solvedPitch, lockedYaw);
            }
        }
        if (!locked.hasRotate()) {
            Vec3 fallbackEye = pos.add(0, this.client.player.dimensions.eyeHeight(), 0);
            return Rotation.from(raycastDirection(box, fallbackEye, range));
        }
        return null;
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
