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
     * 在 GUI 之前把这一帧攒下的 ESP 掩膜合成到主目标上。
     *
     * <p>合成点必须落在 {@code GuiRenderer.render(...)} 之前：掩膜是世界层标注，
     * GUI 打开时必须被 GUI 盖住；放到 {@code render()} 的 RETURN 会让描边浮在 GUI 上面。
     * 此时世界几何已经画完、掩膜也已经采集完，不会漏帧。
     */
    //#if MC >= 260300
    //$$ @Inject(
    //$$         method = "render",
    //$$         at =
    //$$                 @At(
    //$$                         value = "INVOKE",
    //$$                         target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V",
    //$$                         shift = At.Shift.BEFORE))
    //$$ private void onFrameEnd(CallbackInfo ci) {
    //$$     MaskPostRenderer.flush();
    //$$ }
    //#elseif MC >= 260200
    @Inject(
            method = "render",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V",
                            shift = At.Shift.BEFORE))
    private void onFrameEnd(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        MaskPostRenderer.flush();
    }
    //#else
    //$$ @Inject(
    //$$         method = "render",
    //$$         at =
    //$$                 @At(
    //$$                         value = "INVOKE",
    //$$                         target =
    //$$                                 "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
    //$$                         shift = At.Shift.BEFORE))
    //$$ private void onFrameEnd(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
    //$$     MaskPostRenderer.flush();
    //$$ }
    //#endif
}
