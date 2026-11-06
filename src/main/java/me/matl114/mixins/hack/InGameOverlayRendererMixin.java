package me.matl114.mixins.hack;

import com.mojang.blaze3d.vertex.PoseStack;
import me.matl114.hacks.modules.render.NoRender;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
//#if MC >= 260300
//$$ import net.minecraft.client.renderer.state.level.PlayerRenderState;
//#endif
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.component.DataComponents;
//#if MC >= 260300
//$$ import net.minecraft.resources.Identifier;
//#endif
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ScreenEffectRenderer.class)
public abstract class InGameOverlayRendererMixin {
    //#if MC >= 260300
    //$$ // 26.3 的物品激活状态归 PlayerRenderState.ItemActivationRenderState，该字段已不存在
    //#else
    @Shadow
    private ItemStack itemActivationItem;
    //#endif

    // 26.2 起渲染改为"提交节点"模式：
    // renderTex/renderWater/renderFire (MultiBufferSource) -> submitBlockSprite/submitWater/submitFire
    // (SubmitNodeCollector)。renderTex 在 26.1.2 还少一个 int color 参数 ——
    // 所以不仅 method 名要分版本，handler 的参数表也得跟着分。
    // MultiBufferSource 只在 26.1.2 存在（26.2 已删除），所以那一行不写 import、
    // 直接用全限定名；它在 26.2 视图里本来就是 //$$ 注释，不影响编译。
    // 26.3 换了这两个入口的入参：submitBlockSprite 改收 (Identifier, u0..v1) 而不是 atlas sprite，
    // submitWater 改收 PlayerRenderState.WaterOverlay 而不是 Minecraft，参数表因此分三档。
    //#if MC >= 26.2
    @Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
    //#else
    //$$ @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
    //#endif
    private static void onNoRender0(
            //#if MC >= 260300
            //$$ Identifier atlasLocation,
            //$$ float u0,
            //$$ float v0,
            //$$ float u1,
            //$$ float v1,
            //#else
            TextureAtlasSprite sprite,
            //#endif
            PoseStack matrices,
            //#if MC >= 26.2
            SubmitNodeCollector submitNodeCollector,
            int color,
            //#else
            //$$ net.minecraft.client.renderer.MultiBufferSource bufferSource,
            //#endif
            CallbackInfo ci) {
        if (NoRender.INSTANCE.noWallOverlay()) {
            ci.cancel();
        }
    }

    //#if MC >= 26.2
    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    //#else
    //$$ @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    //#endif
    private static void onNoRender1(
            //#if MC >= 260300
            //$$ PlayerRenderState.WaterOverlay waterOverlay,
            //#else
            Minecraft client,
            //#endif
            PoseStack matrices,
            //#if MC >= 26.2
            SubmitNodeCollector submitNodeCollector,
            //#else
            //$$ net.minecraft.client.renderer.MultiBufferSource bufferSource,
            //#endif
            CallbackInfo ci) {
        if (NoRender.INSTANCE.noLiquidOverlay()) {
            ci.cancel();
        }
    }

    //#if MC >= 26.2
    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    //#else
    //$$ @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    //#endif
    private static void onNoRender2(
            PoseStack matrices,
            //#if MC >= 26.2
            SubmitNodeCollector submitNodeCollector,
            //#else
            //$$ net.minecraft.client.renderer.MultiBufferSource bufferSource,
            //#endif
            TextureAtlasSprite sprite,
            CallbackInfo ci) {
        if (NoRender.INSTANCE.noFireOverlay()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderItemActivationAnimation", at = @At("HEAD"), cancellable = true)
    private void onNoRender3(
            //#if MC >= 260300
            //$$ PlayerRenderState playerRenderState,
            //$$ PoseStack matrices,
            //$$ float tickProgress,
            //$$ SubmitNodeCollector submitNodeCollector,
            //#else
            PoseStack matrices,
            float tickProgress,
            SubmitNodeCollector submitNodeCollector,
            //#endif
            CallbackInfo ci) {
        //#if MC >= 260300
        //$$ // 26.3 的激活物品在渲染状态里，等价于 26.2 的 itemActivationItem
        //$$ PlayerRenderState.ItemActivationRenderState activation = playerRenderState.itemActivation;
        //$$ if (NoRender.INSTANCE.noTotemOverlay()
        //$$         && activation != null
        //$$         && activation.item.get(DataComponents.DEATH_PROTECTION) != null) {
        //$$     ci.cancel();
        //$$ }
        //#else
        if (NoRender.INSTANCE.noTotemOverlay()
                && itemActivationItem != null
                && itemActivationItem.get(DataComponents.DEATH_PROTECTION) != null) {
            ci.cancel();
        }
        //#endif
    }
}
