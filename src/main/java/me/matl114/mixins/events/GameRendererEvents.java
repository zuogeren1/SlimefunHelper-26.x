package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import me.matl114.events.Event;
import me.matl114.events.RenderListener;
import me.matl114.utils.render.MaskPostRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
@Environment(EnvType.CLIENT)
public abstract class GameRendererEvents {

    @ModifyExpressionValue(
            method = "renderLevel",
            at =
                    @At(
                            // 26.2: 不再走 OptionInstance.get()，改为读 OptionsRenderState.bobView 字段
                            value = "FIELD",
                            target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;bobView:Z",
                            ordinal = 0))
    private boolean onRenderWorld(boolean bobView, @Local PoseStack matrixStack) {
        Event<PoseStack> event = new Event<>(matrixStack, true, false);
        if (!bobView) {
            event.cancel();
        }
        RenderListener.getApplyWorldBobView().handleValue(event);
        return !event.isCancelled();
    }

    /**
     * 帧末尾把这一帧攒下的 ESP 掩膜合成到主目标上。
     *
     * <p>合成必须发生在 GUI 之后：26.x 的 2D 事件在 {@code extractRenderState} 阶段广播，
     * 那时 GUI 还没真正画，而掩膜合成是一个独立的 {@code RenderPass}，只能在这里发。
     */
    //#if MC >= 260300
    //$$ @Inject(method = "render", at = @At("RETURN"))
    //$$ private void onFrameEnd(CallbackInfo ci) {
    //$$     MaskPostRenderer.flush();
    //$$ }
    //#else
    @Inject(method = "render", at = @At("RETURN"))
    private void onFrameEnd(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        MaskPostRenderer.flush();
    }
    //#endif
}
