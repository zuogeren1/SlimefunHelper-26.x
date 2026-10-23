package me.matl114.hacks.utils.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public record SimpleEntityPredictor(Entity entity) implements Predictor {
    @Override
    public Vec3 getKnownDeltaMovement() {
        return new Vec3(entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo);
    }

    @Override
    public Vec3 getCurrentSyncedPos() {
        return entity.getPositionCodec().getBase();
    }

    @Override
    public Vec3 getCurrentPos() {
        return entity.position();
    }

    @Override
    public Vec3 predict(int ticksLater, int method, int a) {
        return entity.getPosition(ticksLater);
    }

    @Override
    public void tick() {}
}
