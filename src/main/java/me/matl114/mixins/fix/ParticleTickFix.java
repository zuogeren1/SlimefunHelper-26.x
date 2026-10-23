package me.matl114.mixins.fix;

import me.matl114.hacks.RenderTasks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Particle.class)
public abstract class ParticleTickFix {
    @Shadow
    protected boolean hasPhysics;

    @Shadow
    @Final
    protected ClientLevel level;

    @Unique
    private boolean previousHasPhysics;

    @Unique
    private boolean physicsOverrideActive;

    @Inject(method = "move(DDD)V", at = @At("HEAD"))
    private void onMove(CallbackInfo ci) {
        if (level.isClientSide()
                && RenderTasks.getRenderOptimize().enableParticleTickOpt.get()) {
            previousHasPhysics = hasPhysics;
            physicsOverrideActive = true;
            hasPhysics = false;
        }
    }

    @Inject(method = "move(DDD)V", at = @At("RETURN"))
    private void restoreCollisionState(CallbackInfo ci) {
        if (physicsOverrideActive) {
            hasPhysics = previousHasPhysics;
            physicsOverrideActive = false;
        }
    }
}
