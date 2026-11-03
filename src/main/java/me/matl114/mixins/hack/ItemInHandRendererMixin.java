package me.matl114.mixins.hack;

import com.mojang.blaze3d.vertex.PoseStack;
import me.matl114.hacks.modules.render.HandView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第一人称手部变换：vanilla 每只手提交一次，这里在 HEAD push + 应用变换、RETURN pop。
 *
 * <p>HEAD / RETURN 都落在 vanilla 的 {@code if (!player.isScoping())} 外层，举镜时也严格配对；
 * 不要改成在 if 内部注入，否则会漏 pop，PoseStack 抛 NoSuchElementException。
 *
 * <p>26.2 的方法名是 {@code submitArmWithItem}，26.1.2 是 {@code renderArmWithItem}，签名相同。
 */
@Environment(EnvType.CLIENT)
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    @Inject(
            //#if MC >= 260200
            method = "submitArmWithItem",
            //#else
            //$$ method = "renderArmWithItem",
            //#endif
            at = @At("HEAD"))
    private void applyHandTransform(
            AbstractClientPlayer player,
            float frameInterp,
            float xRot,
            InteractionHand hand,
            float attack,
            ItemStack itemStack,
            float inverseArmHeight,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int lightCoords,
            CallbackInfo ci) {
        HandView handView = HandView.INSTANCE;
        if (handView == null || !handView.isActive()) {
            return;
        }
        poseStack.pushPose();
        handView.applyHandTransform(poseStack, hand, itemStack.isEmpty());
    }

    @Inject(
            //#if MC >= 260200
            method = "submitArmWithItem",
            //#else
            //$$ method = "renderArmWithItem",
            //#endif
            at = @At("RETURN"))
    private void restoreHandTransform(
            AbstractClientPlayer player,
            float frameInterp,
            float xRot,
            InteractionHand hand,
            float attack,
            ItemStack itemStack,
            float inverseArmHeight,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int lightCoords,
            CallbackInfo ci) {
        HandView handView = HandView.INSTANCE;
        if (handView != null && handView.isActive()) {
            poseStack.popPose();
        }
    }
}
