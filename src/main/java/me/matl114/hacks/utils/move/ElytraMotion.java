package me.matl114.hacks.utils.move;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.With;
import lombok.experimental.Accessors;
import me.matl114.hacks.modules.move.ElytraExtra;
import me.matl114.hacks.utils.EntityUtils;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

@Getter
@Setter
@ToString
@Accessors(chain = true, fluent = true)
public class ElytraMotion {
    @With
    private ElytraExtra.Al algorithm;

    private float pitch;

    private float yaw;

    @With
    private double speed;

    private final double maxSpeed;

    public ElytraMotion(ElytraExtra.Al algorithm, Vec3 rotation, double speed, double maxSpeed) {
        this(algorithm, rotationToPitch(rotation), rotationToYaw(rotation), speed, maxSpeed);
    }

    public ElytraMotion(ElytraExtra.Al algorithm, Vec2 rotation, double speed, double maxSpeed) {
        this(algorithm, rotation.x, rotation.y, speed, maxSpeed);
    }

    public ElytraMotion(ElytraExtra.Al algorithm, float pitch, float yaw, double speed, double maxSpeed) {
        this.algorithm = algorithm;
        this.pitch = pitch;
        this.yaw = yaw;
        this.speed = speed;
        this.maxSpeed = maxSpeed;
    }

    public Vec3 rotation() {
        return EntityUtils.pitchYawToRotation(pitch, yaw);
    }

    public Vec3 movement() {
        return rotation().scale(speed);
    }

    public ElytraMotion rotation(Vec3 rotation) {
        Vec2 pitchYaw = rotationToPitchYaw(rotation, yaw);
        this.pitch = pitchYaw.x;
        this.yaw = pitchYaw.y;

        return this;
    }

    public ElytraMotion rotation(Vec2 rotation) {
        this.pitch = rotation.x;
        this.yaw = rotation.y;

        return this;
    }

    public ElytraMotion rotation(float pitch, float yaw) {
        this.pitch = pitch;
        this.yaw = yaw;

        return this;
    }

    public ElytraMotion speed(double speed) {
        this.speed = Math.clamp(speed, 0, maxSpeed);
        return this;
    }

    public ElytraMotion withRotation(Vec3 vec3d) {
        return new ElytraMotion(algorithm, rotationToPitchYaw(vec3d, yaw), speed, maxSpeed);
    }

    public ElytraMotion withRotation(Vec2 rotation) {
        return new ElytraMotion(algorithm, rotation, speed, maxSpeed);
    }

    public ElytraMotion withRotation(float pitch, float yaw) {
        return new ElytraMotion(algorithm, pitch, yaw, speed, maxSpeed);
    }

    public Vec2 rotationAngles() {
        return new Vec2(pitch, yaw);
    }

    private static Vec2 rotationToPitchYaw(Vec3 rotation) {
        return rotationToPitchYaw(rotation, 0.0F);
    }

    private static Vec2 rotationToPitchYaw(Vec3 rotation, float verticalYaw) {
        if (rotation.horizontalDistanceSqr() < 1E-12) {
            return new Vec2(rotation.y >= 0.0D ? -90.0F : 90.0F, verticalYaw);
        }
        Vec3 normalized = rotation.normalize();
        return EntityUtils.rotationToPitchYaw(normalized);
    }

    private static float rotationToPitch(Vec3 rotation) {
        return rotationToPitchYaw(rotation).x;
    }

    private static float rotationToYaw(Vec3 rotation) {
        return rotationToPitchYaw(rotation).y;
    }
}
