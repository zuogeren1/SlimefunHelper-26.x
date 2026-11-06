package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import javax.annotation.Nullable;
import me.matl114.hacks.modules.render.NoRender;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//#if MC >= 260300
//$$ import net.minecraft.client.renderer.texture.UvMapping;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Environment(EnvType.CLIENT)
@Mixin(EquipmentLayerRenderer.class)
public abstract class EquipmentRendererMixin {
    // 26.3: submitModel 去掉了 TextureAtlasSprite / CrumblingOverlay，贴图改由 UvMapping 承载；
    // 三处提交的先后变成 基础层 -> 纹饰 -> 纹饰光效（26.2 是 基础层 -> 光效 -> 纹饰），ordinal 因而逐版本绑定。
    @WrapWithCondition(
            method =
                    "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            //#if MC >= 260300
            //$$ at =
            //$$         @At(
            //$$                 value = "INVOKE",
            //$$                 target =
            //$$                         "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V",
            //$$                 ordinal = 1))
            //#else
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
                            ordinal = 2))
            //#endif
    private <S> boolean noArmorRender0(
            OrderedSubmitNodeCollector instance,
            Model<? super S> model,
            S s,
            PoseStack stack,
            RenderType renderLayer,
            //#if MC >= 260300
            //$$ int lightCoords,
            //$$ int overlayCoords,
            //$$ int tintedColor,
            //$$ @Nullable UvMapping uvMapping,
            //$$ int outlineColor) {
            //#else
            int i1,
            int i2,
            int i3,
            @Nullable TextureAtlasSprite sprite,
            int i4,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            //#endif
        if (NoRender.INSTANCE.noArmorTrimRender()) {
            return false;
        }
        return true;
    }

    @WrapWithCondition(
            method =
                    "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            //#if MC >= 260300
            //$$ at =
            //$$         @At(
            //$$                 value = "INVOKE",
            //$$                 target =
            //$$                         "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V",
            //$$                 ordinal = 0))
            //#else
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
                            ordinal = 0))
            //#endif
    private <S> boolean noArmorRender(
            OrderedSubmitNodeCollector instance,
            Model<? super S> model,
            S s,
            PoseStack stack,
            RenderType renderLayer,
            //#if MC >= 260300
            //$$ int lightCoords,
            //$$ int overlayCoords,
            //$$ int tintedColor,
            //$$ @Nullable UvMapping uvMapping,
            //$$ int outlineColor) {
            //#else
            int i1,
            int i2,
            int i3,
            @Nullable TextureAtlasSprite sprite,
            int i4,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            //#endif
        if (NoRender.INSTANCE.noArmorRender()) {
            return false;
        }
        return true;
    }

    @WrapWithCondition(
            method =
                    "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            //#if MC >= 260300
            //$$ at =
            //$$         @At(
            //$$                 value = "INVOKE",
            //$$                 target =
            //$$                         "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V",
            //$$                 ordinal = 2))
            //#else
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
                            ordinal = 1))
            //#endif
    private <S> boolean noArmorRender2(
            OrderedSubmitNodeCollector instance,
            Model<? super S> model,
            S s,
            PoseStack stack,
            RenderType renderLayer,
            //#if MC >= 260300
            //$$ int lightCoords,
            //$$ int overlayCoords,
            //$$ int tintedColor,
            //$$ @Nullable UvMapping uvMapping,
            //$$ int outlineColor) {
            //#else
            int i1,
            int i2,
            int i3,
            @Nullable TextureAtlasSprite sprite,
            int i4,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            //#endif
        if (NoRender.INSTANCE.noArmorGlintRender()) {
            return false;
        }
        return true;
    }
}
