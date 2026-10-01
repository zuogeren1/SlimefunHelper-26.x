package me.matl114.hacks.utils.entity;

import net.minecraft.world.phys.Vec3;

public interface Predictor {
    public Vec3 getKnownDeltaMovement();

    public Vec3 getCurrentPos();

    public Vec3 predict(int ticksLater, int method, int useTickBefore);

    public void tick();
}
