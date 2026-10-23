package me.matl114.mixins.hack;

import me.matl114.hacks.modules.render.NoRender;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateModelSeedMixin {
    @Shadow
    public abstract Block getBlock();

    @Inject(method = "getSeed(Lnet/minecraft/core/BlockPos;)J", at = @At("HEAD"), cancellable = true)
    private void noTextureOffset(BlockPos pos, CallbackInfoReturnable<Long> cir) {
        if (NoRender.INSTANCE.noTextureOffset()
                && BuiltInRegistries.BLOCK.getKey(this.getBlock()).getNamespace().equals("minecraft")) {
            cir.setReturnValue(0L);
        }
    }
}
