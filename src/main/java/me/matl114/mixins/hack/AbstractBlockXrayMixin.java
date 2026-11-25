package me.matl114.mixins.hack;

import me.matl114.hacks.modules.render.Xray;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xray 的亮度部分：开启时把所有方块的环境光遮蔽亮度顶到 1.0。
 *
 * <p>AO 亮度最终取自 {@code BlockState#getShadeBrightness}（{@code BlockModelLighter.Cache} 直接调它，
 * 默认对满碰撞方块返回 0.2），所以注入点必须落在 {@code BlockBehaviour.BlockStateBase}；
 * 只改方块子类的话，被 Xray 露出来的矿石侧面依旧会按 0.2 压暗，看上去仍然发黑。
 */
@Environment(EnvType.CLIENT)
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class AbstractBlockXrayMixin {
    @Inject(method = "getShadeBrightness", at = @At("HEAD"), cancellable = true)
    private void slimefunhelper$xrayFullBright(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (Xray.isXrayActive()) cir.setReturnValue(1.0F);
    }
}
