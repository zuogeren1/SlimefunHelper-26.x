package me.matl114.hacks.utils.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public record LocalEntityPredictor(Entity entity) implements Predictor {
    @Override
    public Vec3 getKnownDeltaMovement() {
        return Vec3.ZERO;
    }

    @Override
    public Vec3 getCurrentPos() {
        return entity.position();
    }

    @Override
    public Vec3 predict(int ticksLater, int method, int useTickBefore) {
        return entity.position();
    }

    @Override
    public void tick() {}
}
