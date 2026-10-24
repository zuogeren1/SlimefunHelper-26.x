package me.matl114.mixins.hack;

import com.mojang.blaze3d.vertex.PoseStack;
import me.matl114.hacks.modules.render.NoRender;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ScreenEffectRenderer.class)
public abstract class InGameOverlayRendererMixin {
    @Shadow
    private ItemStack itemActivationItem;

    // 26.2 起渲染改为"提交节点"模式：
    // renderTex/renderWater/renderFire (MultiBufferSource) -> submitBlockSprite/submitWater/submitFire
    // (SubmitNodeCollector)。renderTex 在 26.1.2 还少一个 int color 参数 ——
    // 所以不仅 method 名要分版本，handler 的参数表也得跟着分。
    // MultiBufferSource 只在 26.1.2 存在（26.2 已删除），所以那一行不写 import、
    // 直接用全限定名；它在 26.2 视图里本来就是 //$$ 注释，不影响编译。
    //#if MC >= 26.2
    @Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
    //#else
    //$$ @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
    //#endif
    private static void onNoRender0(
            TextureAtlasSprite sprite,
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
            Minecraft client,
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
            PoseStack matrices, float tickProgress, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        if (NoRender.INSTANCE.noTotemOverlay()
                && itemActivationItem != null
                && itemActivationItem.get(DataComponents.DEATH_PROTECTION) != null) {
            ci.cancel();
        }
    }
}
