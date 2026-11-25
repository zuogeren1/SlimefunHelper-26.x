package me.matl114.hooks.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import me.matl114.hacks.modules.render.Xray;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext", remap = false)
public abstract class SodiumBlockRenderContextXrayMixin {
    @Shadow
    protected BlockState state;

    @Shadow
    protected BlockAndTintGetter level;

    @Shadow
    protected BlockPos pos;

    @Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void slimefunhelper$forceWhitelistedFace(Direction facing, CallbackInfoReturnable<Boolean> cir) {
        Xray xray = Xray.INSTANCE;
        if (xray == null || !Xray.isXrayActive()) return;
        if (!xray.isBlocked(state.getBlock(), null)) {
            cir.setReturnValue(true);
        }
    }

    @ModifyReturnValue(method = "shouldDrawSide", at = @At("RETURN"), require = 0, expect = 0)
    private boolean slimefunhelper$modifyDrawSide(boolean original, Direction facing) {
        Xray xray = Xray.INSTANCE;
        if (xray == null || !Xray.isXrayActive()) return original;
        return xray.modifyDrawSide(state, level, pos, facing, original);
    }
}
