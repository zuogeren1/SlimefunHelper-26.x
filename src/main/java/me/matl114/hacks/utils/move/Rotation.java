package me.matl114.hacks.utils.move;

import javax.annotation.Nullable;
import lombok.With;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.EntityUtils;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * 一次旋转请求：pitch / yaw 各自可以为空，表示「这一轴不改」。
 *
 * <p>旋转请求常常只关心其中一个轴（例如只锁 yaw 的放置、只压 pitch 的抬头），
 * 用可空字段表达「不指定」比用哨兵角度更安全：调用方可以据此把未指定的轴补成当前值，
 * 而不是把 0 当成真实角度写进玩家状态。
 */
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
