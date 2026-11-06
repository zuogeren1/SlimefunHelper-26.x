package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.matl114.hacks.RenderTasks;
import me.matl114.utils.ColorUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(LoadingOverlay.class)
public abstract class SplashOverlayMixin {
    @Inject(
            method = "extractRenderState",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    //#if MC >= 260300
                                    //$$ "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
                                    //#else
                                    "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
                                    //#endif
                            ordinal = 1,
                            shift = At.Shift.AFTER))
    private void onRenderOverlay(
            GuiGraphicsExtractor context,
            int mouseX,
            int mouseY,
            float deltaTicks,
            CallbackInfo ci,
            @Local(ordinal = 3) float alpha) {
        if (RenderTasks.getCustomOverlay().enable.get()) {
            Identifier identifier = Identifier.tryParse(
                    RenderTasks.getCustomOverlay().texturePath.get());
            int i = context.guiWidth();
            int j = context.guiHeight();
            int color = RenderTasks.getCustomOverlay().color.get();
            context.fillGradient(0, 0, i, j, color, color);
            context.innerBlit(
                    RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                    identifier,
                    0,
                    i,
                    0,
                    j,
                    0,
                    1,
                    0,
                    1,
                    ColorUtils.withAlphaInt(-1, alpha));
        }
    }

    @ModifyExpressionValue(
            method = "extractProgressBar",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;color(IIII)I"))
    private int onOverrideProgressbar(int original, @Local(ordinal = 5) int j) {
        return RenderTasks.getCustomOverlay().colorProgressbar.get().withAlpha(j);
    }
}
