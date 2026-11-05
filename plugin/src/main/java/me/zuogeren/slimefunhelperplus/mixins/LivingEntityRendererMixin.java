package me.zuogeren.slimefunhelperplus.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import me.zuogeren.slimefunhelperplus.friendwine.FriendWine;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「朋友的酒」的渲染注入点。
 *
 * <p>只包住 {@code LivingEntityRenderer#submit} 里「模型 + 装备图层」那一段：
 * 在方法 HEAD 压栈并施加伸缩/旋转，在原版 {@code popPose()} 之后、
 * {@code super.submit(...)}（名字牌与拴绳）之前出栈。这样变形的只有模型本身，
 * 名字牌、拴绳、影子、冒火都不跟着扭。
 *
 * <p>两个版本（26.2 / 26.1.2）该方法与 {@code super.submit} 调用的描述符逐字节一致
 * （javap 已对过：{@code super.submit} 都落在 {@code popPose} 之后、{@code return} 之前的那条
 * {@code invokespecial} 上），所以这里不需要版本分叉。
 *
 * <p>HEAD 与 super.submit 前各判一次 {@link FriendWine#shouldApply}：判定只读配置与实体类型，
 * 同一次调用内结果稳定，不会出现「压了没弹」的失衡。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
    private void sfhplus$friendWineBegin(
            LivingEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera,
            CallbackInfo callbackInfo) {
        FriendWine module = FriendWine.INSTANCE;
        if (module == null || !module.shouldApply(state)) {
            return;
        }
        poseStack.pushPose();
        module.applyTransform(poseStack);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", shift = At.Shift.BEFORE))
    private void sfhplus$friendWineEnd(
            LivingEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera,
            CallbackInfo callbackInfo) {
        FriendWine module = FriendWine.INSTANCE;
        if (module == null || !module.shouldApply(state)) {
            return;
        }
        poseStack.popPose();
    }
}
