package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.matl114.hacks.modules.render.NoRender;
import me.matl114.hacks.modules.render.RenderExtra;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(GameRenderer.class)
public abstract class GameRenderMixin {

    @ModifyExpressionValue(
            method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F"))
    private float noNauseaWobble(float original) {
        return NoRender.INSTANCE.noNausea() ? 0.0F : original;
    }

    //#if MC >= 26.2
    @Inject(method = "nightVisionScale", at = @At("HEAD"), cancellable = true)
    //#else
    //$$ @Inject(method = "getNightVisionScale", at = @At("HEAD"), cancellable = true)
    //#endif
    private static void getNightVisionStrength(
            LivingEntity entity, float tickDelta, CallbackInfoReturnable<Float> cir) {
        if (RenderExtra.INSTANCE.nightVision.get()) {
            cir.setReturnValue(1.0F);
        }
    }
}
