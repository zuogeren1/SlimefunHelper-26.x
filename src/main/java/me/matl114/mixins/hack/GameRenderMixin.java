package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import me.matl114.hacks.modules.render.HandView;
import me.matl114.hacks.modules.render.NoRender;
import me.matl114.hacks.modules.render.RenderExtra;
import me.matl114.utils.render.HandFaceCollector;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
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

    /**
     * 第一人称手部发光：把这一帧的手部提交换成抓面 collector，提交结束后立刻把壳层与边线重提交回同一个 collector。
     *
     * <p>挂在 {@code submitHandsWithItems} 的那一处调用上，而不是另起一个渲染事件：重提交的面与手部自己的提交进的是
     * 同一个 collector。两者的消费时机并不一致 —— 26.2 由 {@code renderItemInHand} 末尾的 {@code renderAllFeatures}
     * 一并消费，26.1.2 是 {@code renderHandsWithItems} 先在末尾把手部 flush 掉、补交的面留到外层 {@code renderLevel}
     * 才画；两者之间差的那次 model view 由 {@link HandFaceCollector} 在重提交姿势里补回。
     *
     * <p>异常不能冒泡：会跳过 {@code renderItemInHand} 的 {@code modelViewStack.popMatrix}，
     * 之后每一帧的 model view 都被污染，比「不发光」严重得多。
     */
    //#if MC >= 260200
    @WrapOperation(
            method = "renderItemInHand",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/ItemInHandRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    //#else
    //$$ @WrapOperation(
    //$$         method = "renderItemInHand",
    //$$         at =
    //$$                 @At(
    //$$                         value = "INVOKE",
    //$$                         target =
    //$$                                 "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    //#endif
    private void captureHandGlow(
            ItemInHandRenderer renderer,
            float frameInterp,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            LocalPlayer player,
            int lightCoords,
            Operation<Void> original) {
        HandView handView = HandView.INSTANCE;
        if (handView == null || !handView.glowEnabled()) {
            original.call(renderer, frameInterp, poseStack, submitNodeCollector, player, lightCoords);
            return;
        }
        HandFaceCollector handFaces = handView.getHandFaces();
        try {
            original.call(
                    renderer, frameInterp, poseStack, handFaces.begin(submitNodeCollector), player, lightCoords);
        } finally {
            try {
                handFaces.flush(submitNodeCollector, handView.glowShellColor(), handView.glowBoundaryColor());
            } catch (Exception failure) {
                handFaces.clear();
                handFaces.reportFailure(failure);
            }
        }
    }
}
