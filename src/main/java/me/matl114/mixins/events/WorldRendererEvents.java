package me.matl114.mixins.events;

import com.mojang.blaze3d.vertex.PoseStack;
import me.matl114.events.RenderListener;
import me.matl114.versioned.impl.Render_v1_21_11;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import me.matl114.hacks.utils.RenderUtils;

@Mixin(LevelRenderer.class)
public abstract class WorldRendererEvents {
    /**
     * 26.2 的世界渲染改为两阶段：先 {@code submitFeatures(...)} 把所有内容提交进
     * {@link SubmitNodeCollector}，随后 {@code prepareFrame(...)} 统一排序渲染。
     *
     * <p>因此自定义几何的注入点从旧的 {@code renderLevel} RETURN 改到 {@code submitFeatures}
     * 的 RETURN —— 此时 vanilla 内容已提交完毕，我们追加的几何会与之一起参与排序与渲染，
     * 深度与遮挡关系保持正确。
     *
     * <p>26.1.2 没有 {@code submitFeatures}，最后一个提交动作是
     * {@code submitBlockDestroyAnimation(PoseStack, SubmitNodeCollector, LevelRenderState)}，
     * 因此挂它的 RETURN —— 位置等价。两版参数表不同，所以要分版本写。
     */
    //#if MC >= 26.2
    @Inject(method = "submitFeatures", at = @At("RETURN"))
    //#else
    //$$ @Inject(method = "submitBlockDestroyAnimation", at = @At("RETURN"))
    //#endif
    private void onAfterSubmitFeatures(
            //#if MC >= 26.2
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            boolean renderOutline,
            //#else
            //$$ PoseStack poseStack,
            //$$ SubmitNodeCollector submitNodeCollector,
            //$$ LevelRenderState levelRenderState,
            //#endif
            CallbackInfo ci) {
        CameraRenderState cameraState = levelRenderState.cameraRenderState;
        RenderListener.setWorldModelViewMatrix(new Matrix4f(cameraState.viewRotationMatrix));
        // 26.2: worldBasicProjectionMatrix 也必须设 —— NameTag 和 RenderCollectors 都读它
        // （之前漏了，它一直是单位矩阵，导致文字/收集器类渲染位置全错）
        RenderListener.setWorldBasicProjectionMatrix(new Matrix4f(cameraState.projectionMatrix));
        RenderListener.setWorldProjectionMatrix(new Matrix4f(cameraState.projectionMatrix));

        // 26.2: 这里必须用单位 PoseStack。
        // 调用方（RenderUtils.drawSolidBox / drawOutlinedBox）已经把坐标减掉了相机位置，
        // 平移已处理；RenderPipeline 会自己应用视图旋转 —— 如果这里再 mulPose 一次
        // viewRotationMatrix，旋转就被乘了两次（R²），框会随视线转动而偏移/变歪。
        PoseStack matrixStack = new PoseStack();

        Render_v1_21_11.beginSubmit(submitNodeCollector, matrixStack);
        try {
            DeltaTracker tracker = Minecraft.getInstance().getDeltaTracker();
            float tickDelta = tracker == null ? 0.0F : tracker.getGameTimeDeltaPartialTick(false);
            RenderListener.renderWorldTasks(matrixStack, tickDelta);
        } finally {
            Render_v1_21_11.endSubmit();
        }
    }

    // TODO(26.2): "取消某个实体渲染"的钩子需要重新设计。
    // 旧的 LevelRenderer.extractVisibleEntities 已不存在，且 EntityRenderDispatcher.shouldRender
    // 在 LevelRenderer 里已经没有任何调用点了（两阶段提交下实体可见性在别处决定）。
    // 要恢复这个功能，需要找到新的实体状态提取/剔除位置再挂。
    //     @WrapOperation(
    //             method = "extractVisibleEntities",
    //             at =
    //                     @At(
    //                             value = "INVOKE",
    //                             target =
    //
    // "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    //     public boolean onEntityRenderEvent(
    //             EntityRenderDispatcher instance,
    //             Entity entity,
    //             Frustum frustum,
    //             double x,
    //             double y,
    //             double z,
    //             Operation<Boolean> original) {
    //         Event<Entity> event = new Event<>(entity, true, false);
    //         RenderListener.getEntityRenderListener().handleValue(event);
    //         if (event.isCancelled()) {
    //             return false;
    //         }
    //         return original.call(instance, entity, frustum, x, y, z);
    //     }
}
