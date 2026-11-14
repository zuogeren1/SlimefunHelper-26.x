package me.zuogeren.slimefunhelperplus.module.custommodel.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelManager;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第一人称手臂：模型替换后原版皮肤手臂和自定义模型不是一套，开关打开时把它藏掉，只留手里的物品。
 *
 * <p>拦的是 {@code AvatarRenderer#renderRightHand/renderLeftHand} —— 第一人称（包括手持地图）画手臂
 * 都走这两个方法；26.3 删掉了 {@code ItemInHandRenderer}，但这两个方法三个版本都在。
 *
 * <p>YSM 的第一人称是模型自带的 {@code armModel}（外加 {@code fp_arm} 动画），还没接；
 * 在那之前先给一个「不露出原版手臂」的选项，默认关。
 */
@Mixin(value = AvatarRenderer.class, priority = 900)
public abstract class FirstPersonArmMixin {

    @Inject(
            method = "renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void sfhplus$hideRightHand(PoseStack poseStack, SubmitNodeCollector collector, int light,
            Identifier texture, boolean sleeveVisible, CallbackInfo callbackInfo) {
        if (shouldHide()) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void sfhplus$hideLeftHand(PoseStack poseStack, SubmitNodeCollector collector, int light,
            Identifier texture, boolean sleeveVisible, CallbackInfo callbackInfo) {
        if (shouldHide()) {
            callbackInfo.cancel();
        }
    }

    private static boolean shouldHide() {
        CustomModelModule module = CustomModelModule.INSTANCE;
        return module != null && module.isActive() && CustomModelManager.runtime() != null
                && module.hideFirstPersonArm.get();
    }
}