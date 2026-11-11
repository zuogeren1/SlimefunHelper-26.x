/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
//#if MC >= 260300
//$$ import net.minecraft.client.resources.model.geometry.ItemQuads;
//#else
import java.util.List;
import net.minecraft.client.resources.model.geometry.BakedQuad;
//#endif
import me.zuogeren.slimefunhelperplus.module.carbon.CarbonRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物品提交的唯一入口。
 *
 * <p>拿它当接管点可以避开「26.2 换了 feature 渲染器、26.3 又换了提交结构」这两次重构，
 * 26.1.2 / 26.2 的参数表逐字节相同，26.3 只把第 7 个参数从 {@code List<BakedQuad>} 换成 {@code ItemQuads}。
 *
 * <p>描述符写全的理由见 {@link CarbonFlushMixin}：{@code require = 0} 不挡签名不符。
 */
@Mixin(SubmitNodeStorage.class)
public abstract class CarbonSubmitCaptureMixin {

    //#if MC >= 260300
    //$$ @Inject(
    //$$         method = "submitItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;III[ILnet/minecraft/client/resources/model/geometry/ItemQuads;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V",
    //$$         at = @At("HEAD"),
    //$$         cancellable = true,
    //$$         require = 0)
    //$$ private void carbon$captureItem(
    //$$         PoseStack pose,
    //$$         ItemDisplayContext context,
    //$$         int light,
    //$$         int overlay,
    //$$         int outline,
    //$$         int[] tints,
    //$$         ItemQuads quads,
    //$$         ItemStackRenderState.FoilType foil,
    //$$         CallbackInfo callback) {
    //$$     if (CarbonRenderer.captureItem(pose, context, light, overlay, outline, tints, quads, foil)) {
    //$$         callback.cancel();
    //$$     }
    //$$ }
    //#else
    @Inject(
            method = "submitItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;III[ILjava/util/List;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void carbon$captureItem(
            PoseStack pose,
            ItemDisplayContext context,
            int light,
            int overlay,
            int outline,
            int[] tints,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType foil,
            CallbackInfo callback) {
        if (CarbonRenderer.captureItem(pose, context, light, overlay, outline, tints, quads, foil)) {
            callback.cancel();
        }
    }
    //#endif
}
