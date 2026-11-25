package me.matl114.hooks.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import me.matl114.hacks.modules.render.Xray;
import net.caffeinemc.mods.sodium.client.model.light.data.LightDataAccess;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.model.light.data.LightDataAccess", remap = false)
public abstract class SodiumLightDataAccessXrayMixin {
    @Shadow
    protected BlockAndTintGetter level;

    @Shadow
    @Final
    private BlockPos.MutableBlockPos pos;

    @ModifyReturnValue(method = "compute", at = @At("RETURN"), require = 0, expect = 0)
    private int slimefunhelper$fullBright(int original, int x, int y, int z) {
        Xray xray = Xray.INSTANCE;
        if (xray == null || !Xray.isXrayActive()) return original;
        pos.set(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (xray.isBlocked(state.getBlock(), pos)) return original;
        return original | LightDataAccess.packBL(15) | LightDataAccess.packSL(15);
    }
}
