package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.matl114.hacks.modules.render.Xray;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Xray 的区块编译部分：关掉区块级遮挡剔除，并把带 alpha 的方块塞进半透明层。
 *
 * <p>渲染层不再由 {@code BlockRenderLayers#getBlockLayer(BlockState)} 决定，而是由四边面自带的
 * {@code BakedQuad.MaterialInfo#layer()} 决定。区块里的普通方块经 {@code lambda$compile$0} 落盘
 * （那里调 {@code materialInfo().layer()} 选层），{@code lambda$compile$1} 是 forceOpaque 分支、
 * 层硬编码 {@code SOLID}，两处都不经过上游那个静态查表方法，所以只有 {@code lambda$compile$0}
 * 需要接手；树叶分支本来就写进固体层，保持原样。
 *
 * <p>{@code lambda$compile$0} 的调用时机在 {@code ModelBlockRenderer#tesselateBlock} 之内
 * （面从 {@code BlockQuadOutput#put} 交回来），因此能用 {@link Xray#currentAlpha()} 拿到当前方块的 alpha。
 */
@Environment(EnvType.CLIENT)
@Mixin(SectionCompiler.class)
public abstract class SectionBuilderXrayMixin {

    @WrapOperation(
            method = "compile",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/chunk/VisGraph;setOpaque(Lnet/minecraft/core/BlockPos;)V"))
    private void slimefunhelper$disableChunkOcclusion(VisGraph instance, BlockPos pos, Operation<Void> original) {
        // 不能改成 @Inject(cancellable)：那会连整个 compile 一起取消，区块直接没有网格。
        if (Xray.INSTANCE == null || !Xray.isXrayActive()) {
            original.call(instance, pos);
        }
    }

    @WrapOperation(
            method = "lambda$compile$0",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo;layer()Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"))
    private ChunkSectionLayer slimefunhelper$makeBlockLayerTranslucent(
            BakedQuad.MaterialInfo info, Operation<ChunkSectionLayer> original) {
        int alpha = Xray.INSTANCE == null ? -1 : Xray.currentAlpha();
        // 半透明层才走混合管线；不透明层里 alpha 会被忽略，方块看上去还是实心。
        return alpha > 0 && alpha < 255 ? ChunkSectionLayer.TRANSLUCENT : original.call(info);
    }
}
