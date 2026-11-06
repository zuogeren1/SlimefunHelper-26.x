package me.matl114.mixins.hack;

import com.mojang.blaze3d.vertex.PoseStack;
import me.matl114.hacks.modules.render.HandView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//#if MC >= 260300
//$$ import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
//#else
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
//#endif
import net.minecraft.client.renderer.SubmitNodeCollector;
//#if MC >= 260300
//$$ import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
//$$ import net.minecraft.client.renderer.state.level.PlayerRenderState;
//#endif
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
 * <p>26.2 与 26.3 的方法名都是 {@code submitArmWithItem}，26.1.2 是 {@code renderArmWithItem}；26.2 与 26.1.2 的形参列表
 * 相同，26.3 的宿主类换成 {@code FirstPersonHandsAndItemsRenderer} 且多带一个渲染状态参数，所以回调要跟着分叉。
 */
@Environment(EnvType.CLIENT)
//#if MC >= 260300
//$$ @Mixin(FirstPersonHandsAndItemsRenderer.class)
//#else
@Mixin(ItemInHandRenderer.class)
//#endif
public abstract class ItemInHandRendererMixin {

    @Inject(
            //#if MC >= 260200
            method = "submitArmWithItem",
            //#else
            //$$ method = "renderArmWithItem",
            //#endif
            at = @At("HEAD"))
    //#if MC >= 260300
    //$$ private void applyHandTransform(
    //$$         PlayerRenderState playerState,
    //$$         FirstPersonHandsAndItemsRenderState state,
    //$$         float partialTicks,
    //$$         float xRot,
    //$$         InteractionHand hand,
    //$$         float attack,
    //$$         ItemStack itemStack,
    //$$         float inverseArmHeight,
    //$$         PoseStack poseStack,
    //$$         SubmitNodeCollector submitNodeCollector,
    //$$         int lightCoords,
    //$$         CallbackInfo ci) {
    //$$     HandView handView = HandView.INSTANCE;
    //$$     if (handView == null || !handView.isActive()) {
    //$$         return;
    //$$     }
    //$$     poseStack.pushPose();
    //$$     handView.applyHandTransform(poseStack, hand, itemStack.isEmpty());
    //$$ }
    //#else
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
    //#endif

    @Inject(
            //#if MC >= 260200
            method = "submitArmWithItem",
            //#else
            //$$ method = "renderArmWithItem",
            //#endif
            at = @At("RETURN"))
    //#if MC >= 260300
    //$$ private void restoreHandTransform(
    //$$         PlayerRenderState playerState,
    //$$         FirstPersonHandsAndItemsRenderState state,
    //$$         float partialTicks,
    //$$         float xRot,
    //$$         InteractionHand hand,
    //$$         float attack,
    //$$         ItemStack itemStack,
    //$$         float inverseArmHeight,
    //$$         PoseStack poseStack,
    //$$         SubmitNodeCollector submitNodeCollector,
    //$$         int lightCoords,
    //$$         CallbackInfo ci) {
    //$$     HandView handView = HandView.INSTANCE;
    //$$     if (handView != null && handView.isActive()) {
    //$$         poseStack.popPose();
    //$$     }
    //$$ }
    //#else
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
    //#endif
}
