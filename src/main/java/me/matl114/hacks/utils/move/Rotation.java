package me.matl114.hacks.utils.move;

import javax.annotation.Nullable;
import lombok.With;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.EntityUtils;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

@With
public record Rotation(@Nullable Float pitch, @Nullable Float yaw) {
    public static Rotation EMPTY = new Rotation();

    private Rotation() {
        this(null, null);
    }

    public static Rotation from(Vec2 vec2f) {
        return new Rotation(vec2f.x, vec2f.y);
    }

    public static Rotation from(Vec3 vec3d) {
        if (vec3d.horizontalDistance() == 0) {
            return new Rotation(EntityUtils.rotationToPitch(vec3d), null);
        }
        var vec2f = EntityUtils.rotationToPitchYaw(vec3d);
        return new Rotation(vec2f.x, vec2f.y);
    }

    public static Rotation from(float pitch, float yaw) {
        return new Rotation(pitch, yaw);
    }

    public float getPitch() {
        return pitch;
    }

    public float getYaw() {
        return yaw;
    }

    public boolean hasPitch() {
        return pitch != null;
    }

    public boolean hasYaw() {
        return yaw != null;
    }

    public boolean hasRotate() {
        return hasPitch() || hasYaw();
    }

    public float getPitch(float pitch) {
        return hasPitch() ? getPitch() : pitch;
    }

    public float getYaw(float pitch) {
        return hasYaw() ? getYaw() : pitch;
    }

    public Vec2 toPitchYaw() {
        if (!hasPitch() && !hasYaw()) {
            return null;
        }
        return new Vec2(getPitch(PlayerStateManager.INSTANCE.lastPitch), getYaw(PlayerStateManager.INSTANCE.lastYaw));
    }
}
