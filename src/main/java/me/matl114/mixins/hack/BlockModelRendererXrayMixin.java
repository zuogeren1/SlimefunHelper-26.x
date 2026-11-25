package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.QuadInstance;
import me.matl114.hacks.modules.render.Xray;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Xray 的方块渲染部分：按方块跳过整次着色、把 alpha 落进 {@link QuadInstance}、补回被邻居挡住的面。
 *
 * <p>26.x 的方块渲染不再直接往 {@code VertexConsumer} 写顶点，而是由 {@code ModelBlockRenderer}
 * 交出四边面加 {@link QuadInstance}，真正的落盘发生在 {@code BlockQuadOutput} 里。顶点颜色来自
 * {@code QuadInstance}，所以 alpha 只能在 {@code putQuadWithTint} 里写 —— 那是本类唯一调用
 * {@code BlockQuadOutput.put} 的位置，写在这里才能覆盖 AO 与 flat 两条分支。
 *
 * <p>alpha 借 {@link Xray} 的线程局部量传递：{@code tesselateBlock → tesselateAmbientOcclusion /
 * tesselateFlat → putQuadWithTint} 全程同线程，中间没有能把「当前方块」带到下面的参数，
 * 用线程局部量比自己维护字段安全（区块重建本身是多线程的）。
 */
@Environment(EnvType.CLIENT)
@Mixin(ModelBlockRenderer.class)
public abstract class BlockModelRendererXrayMixin {

    @Inject(method = "tesselateBlock", at = @At("HEAD"), cancellable = true)
    private void slimefunhelper$applyXray(
            BlockQuadOutput output,
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            BlockStateModel model,
            long seed,
            CallbackInfo ci) {
        if (Xray.INSTANCE == null) {
            return;
        }
        int alpha = Xray.getAlpha(state, pos);
        Xray.setCurrentAlpha(alpha);
        if (alpha == 0) {
            // 整个方块不可见：直接取消，省掉下面所有的面剔除与着色计算。
            Xray.clearCurrentAlpha();
            ci.cancel();
        }
    }

    @Inject(method = "tesselateBlock", at = @At("RETURN"))
    private void slimefunhelper$clearXrayAlpha(CallbackInfo ci) {
        if (Xray.INSTANCE != null) {
            Xray.clearCurrentAlpha();
        }
    }

    @WrapOperation(
            method = "putQuadWithTint",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/block/BlockQuadOutput;put(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"))
    private void slimefunhelper$xrayQuadAlpha(
            BlockQuadOutput output,
            float x,
            float y,
            float z,
            BakedQuad quad,
            QuadInstance instance,
            Operation<Void> original) {
        int alpha = Xray.INSTANCE == null ? -1 : Xray.currentAlpha();
        if (alpha > 0 && alpha < 255) {
            // 四个顶点各自的颜色可能已经被 AO/染色改过，只替换最高 8 位的 alpha，保留 RGB。
            for (int vertex = 0; vertex < 4; vertex++) {
                instance.setColor(vertex, (instance.getColor(vertex) & 0x00FFFFFF) | (alpha << 24));
            }
        }
        original.call(output, x, y, z, quad, instance);
    }

    @ModifyReturnValue(method = "shouldRenderFace", at = @At("RETURN"))
    private boolean slimefunhelper$modifyDrawSide(
            boolean original, BlockAndTintGetter level, BlockState state, Direction facing, BlockPos neighborPos) {
        Xray xray = Xray.INSTANCE;
        if (xray == null) {
            return original;
        }
        // 第 4 个参数是 tesselateFlat 里 scratchPos.setWithOffset(pos, facing) 得到的那个相邻格坐标
        // （紧接着 shouldRenderFace 内部就用它去 getBlockState），而 Block.shouldRenderFace 比的是
        // 「本方块 vs 邻格」，所以本方块坐标要由邻格反推，不能直接用 neighborPos。
        return xray.modifyDrawSide(state, level, neighborPos.relative(facing.getOpposite()), facing, original);
    }
}
