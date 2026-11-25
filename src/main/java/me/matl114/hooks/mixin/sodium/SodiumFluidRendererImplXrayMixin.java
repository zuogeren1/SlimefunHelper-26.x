package me.matl114.hooks.mixin.sodium;

import me.matl114.hacks.modules.render.Xray;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl", remap = false)
public abstract class SodiumFluidRendererImplXrayMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void slimefunhelper$hideTransparentFluid(
            @Coerce Object level,
            BlockState blockState,
            FluidState fluidState,
            BlockPos blockPos,
            BlockPos offset,
            @Coerce Object collector,
            @Coerce Object buffers,
            CallbackInfo ci) {
        if (Xray.INSTANCE == null) return;
        if (Xray.getFluidAlpha(fluidState, blockPos) == 0) ci.cancel();
    }
}
