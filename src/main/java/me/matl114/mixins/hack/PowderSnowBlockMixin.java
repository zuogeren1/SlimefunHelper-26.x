package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.matl114.events.Listener;
import me.matl114.events.impl.SlowMovement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Environment(EnvType.CLIENT)
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {
    @WrapOperation(
            method = "entityInside",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/Entity;makeStuckInBlock(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/Vec3;)V"))
    private void onSlowMovement(
            Entity instance,
            BlockState state,
            Vec3 multiplier,
            Operation<Void> original,
            @Local(argsOnly = true) BlockPos pos) {
        if (instance == Minecraft.getInstance().player) {
            SlowMovement slowMovement = new SlowMovement(multiplier, pos, state);
            if (Listener.getPlayerSlowedByBlockPoint().fireEvent(slowMovement)) {
                original.call(instance, state, slowMovement.movementModifier());
            }
        } else {
            original.call(instance, state, multiplier);
        }
    }
}
