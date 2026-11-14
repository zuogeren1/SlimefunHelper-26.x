package me.zuogeren.slimefunhelperplus.module.custommodel.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelManager;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ModelRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第一人称手臂：模型自带 {@code mainEntity.armModel} 时用它替换原版皮肤手臂。
 *
 * <p>拦的是 {@code AvatarRenderer#renderRightHand/renderLeftHand} —— 26.2 的第一人称手臂与手持地图
 * 都走这两个方法；26.3 删掉了 {@code ItemInHandRenderer}，但这两个方法两个版本都在，签名一致。
 *
 * <p>这一层拿到的 {@code SubmitNodeCollector} 就是手部 pass 自己的收集器
 * （{@code GameRenderer#renderItemInHand} 里 flush），所以 {@code submitCustomGeometry} 在上面有效；
 * 原版自己的手持地图纸底也是这么画的。**渲染类型必须用 translucent** —— 手部 pass 只收进 translucent 分组，
 * {@code entityCutout} 提交上去会静默不画。
 *
 * <p>手上没有物品时原版才走这两个方法（有物品走的是「物品 + 手臂」那条），所以双手持物时这里是原版物品，
 * 模型手臂不参与。
 */
@Mixin(value = AvatarRenderer.class, priority = 900)
public abstract class FirstPersonArmMixin {

    @Inject(
            method = "renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void sfhplus$renderRightHand(PoseStack poseStack, SubmitNodeCollector collector, int light,
            Identifier texture, boolean sleeveVisible, CallbackInfo callbackInfo) {
        if (!isActive()) {
            return;
        }
        renderArm(poseStack, collector, light, -0.25F, true);
        callbackInfo.cancel();
    }

    @Inject(
            method = "renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void sfhplus$renderLeftHand(PoseStack poseStack, SubmitNodeCollector collector, int light,
            Identifier texture, boolean sleeveVisible, CallbackInfo callbackInfo) {
        if (!isActive()) {
            return;
        }
        renderArm(poseStack, collector, light, 0.25F, false);
        callbackInfo.cancel();
    }

    private static void renderArm(PoseStack poseStack, SubmitNodeCollector collector, int light,
            float offsetX, boolean rightArm) {
        ModelRuntime arm = CustomModelManager.armRuntime();
        if (arm == null) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        float ageSeconds = player == null ? 0.0F : player.tickCount / 20.0F;
        poseStack.pushPose();
        // 手臂几何按 YSM 的模型空间烘出来（Y 向上、脚在原点），先平移到肩高再镜像到原版手臂的模型空间；
        // 这两步与 SPM 的 HandItemRenderer 一致，实机确认位置正确。
        poseStack.translate(offsetX, 1.8F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(arm.texture()),
                (pose, buffer) -> arm.renderFirstPerson(pose, buffer, light, ageSeconds, rightArm));
        poseStack.popPose();
    }

    private static boolean isActive() {
        CustomModelModule module = CustomModelModule.INSTANCE;
        return module != null && module.isActive() && module.hideFirstPersonArm.get();
    }
}
