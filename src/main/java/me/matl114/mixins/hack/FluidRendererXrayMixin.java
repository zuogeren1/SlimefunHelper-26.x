package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.matl114.hacks.modules.render.Xray;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Xray 的流体渲染部分：按流体跳过整次着色、把 alpha 写进顶点色、改走半透明层、补回被挡的侧面。
 *
 * <p>26.x 的 {@code FluidRenderer} 不再自带 {@code VertexConsumer}，而是通过
 * {@code FluidRenderer.Output#getBuilder(ChunkSectionLayer)} 现取，层由 {@code FluidModel#layer()} 决定；
 * 因此「让流体变成半透明」必须同时做两件事：在这里把取层的参数换成 {@code TRANSLUCENT}，
 * 再在顶点色里写入 alpha。只改顶点色而不换层的话，alpha 会被写进不透明管线，画面不会变透明。
 *
 * <p>状态用 {@link ThreadLocal} 而非字段保存：区块重建在多个工作线程上并发进行，
 * {@code tesselate} 内部的调用链同线程，线程局部量正好够用且不会互相串。
 */
@Environment(EnvType.CLIENT)
@Mixin(FluidRenderer.class)
public abstract class FluidRendererXrayMixin {

    @Unique
    private static final ThreadLocal<Integer> slimefunhelper$xrayAlpha = ThreadLocal.withInitial(() -> -1);

    @Unique
    private static final ThreadLocal<BlockAndTintGetter> slimefunhelper$xrayLevel = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<BlockPos> slimefunhelper$xrayPos = new ThreadLocal<>();

    @Unique
    private static void slimefunhelper$clearContext() {
        slimefunhelper$xrayAlpha.remove();
        slimefunhelper$xrayLevel.remove();
        slimefunhelper$xrayPos.remove();
    }

    @Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
    private void slimefunhelper$applyXray(
            BlockAndTintGetter level,
            BlockPos pos,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci) {
        int alpha = Xray.INSTANCE == null ? -1 : Xray.getFluidAlpha(fluidState, pos);
        slimefunhelper$xrayAlpha.set(alpha);
        slimefunhelper$xrayLevel.set(level);
        slimefunhelper$xrayPos.set(pos);
        if (alpha == 0) {
            // 整个流体不可见：直接取消，省掉下面的逐面判定。
            slimefunhelper$clearContext();
            ci.cancel();
        }
    }

    @Inject(method = "tesselate", at = @At("RETURN"))
    private void slimefunhelper$clearXray(CallbackInfo ci) {
        slimefunhelper$clearContext();
    }

    @WrapOperation(
            method = "tesselate",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/block/FluidRenderer$Output;getBuilder(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer slimefunhelper$makeFluidLayerTranslucent(
            FluidRenderer.Output output, ChunkSectionLayer layer, Operation<VertexConsumer> original) {
        int alpha = slimefunhelper$xrayAlpha.get();
        return alpha > 0 && alpha < 255
                ? original.call(output, ChunkSectionLayer.TRANSLUCENT)
                : original.call(output, layer);
    }

    @ModifyArg(
            method = "vertex",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/blaze3d/vertex/VertexConsumer;addVertex(FFFIFFIIFFF)V"),
            index = 3)
    private int slimefunhelper$xrayVertexColor(int original) {
        int alpha = slimefunhelper$xrayAlpha.get();
        // addVertex 的 11 个参数里第 4 个（index 3）是 ARGB 颜色，alpha 在最高 8 位；
        // 其余位是流体染色，不能动。
        return alpha > 0 && alpha < 255 ? (original & 0x00FFFFFF) | (alpha << 24) : original;
    }

    @ModifyReturnValue(method = "shouldRenderFace", at = @At("RETURN"))
    private static boolean slimefunhelper$showFluidSides(
            boolean original, FluidState fluidState, BlockState blockState, Direction direction, FluidState neighborFluid) {
        if (original || direction.getAxis().isVertical()) {
            return original;
        }
        int alpha = slimefunhelper$xrayAlpha.get();
        if (alpha <= 0 || alpha >= 255) {
            return original;
        }
        BlockAndTintGetter level = slimefunhelper$xrayLevel.get();
        BlockPos pos = slimefunhelper$xrayPos.get();
        if (level == null || pos == null) {
            return original;
        }
        // 邻格仍是流体时补面会把整片水域的内部面全部画出来（面数暴涨且贴图重复），只补方块邻居。
        if (!neighborFluid.isEmpty()) {
            return original;
        }
        Xray xray = Xray.INSTANCE;
        if (xray == null) {
            return original;
        }
        BlockPos neighborPos = pos.relative(direction);
        return xray.isBlocked(level.getBlockState(neighborPos).getBlock(), neighborPos);
    }

    /**
     * 26.x 里「本面要不要跳过」的判定被拆成两个方法：{@code shouldRenderFace} 只管「邻格是不是同一种流体 /
     * 自身是否挡住」，真正把面剔掉的是 {@code isFaceOccludedByNeighbor}（返回 true = 不画）。它的参数形状与上游
     * yarn 的 {@code shouldSkipRendering(Direction, float, BlockState)} 一一对应、极性也相同，是那个注入点的等价物。
     */
    @ModifyReturnValue(method = "isFaceOccludedByNeighbor", at = @At("RETURN"))
    private static boolean slimefunhelper$showOccludedFluidSides(
            boolean original, Direction direction, float height, BlockState neighborState) {
        if (!original || direction.getAxis().isVertical()) {
            return original;
        }
        BlockPos pos = slimefunhelper$xrayPos.get();
        Xray xray = Xray.INSTANCE;
        if (pos == null || xray == null || !Xray.isXrayActive()) {
            return original;
        }
        // 邻居被 Xray 隐藏后本该露出来的那一面仍然被它挡住，这里把它放行。
        return !xray.isBlocked(neighborState.getBlock(), pos.relative(direction));
    }
}
