package me.zuogeren.slimefunhelperplus.module.custommodel.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.concurrent.atomic.AtomicBoolean;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelManager;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ModelRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 自定义模型的第一段竖切：把本地玩家的原版模型换成一个贴图方块。
 *
 * <p>注入点选在 {@code LivingEntityRenderer#submit} 的 HEAD 并取消，几何用
 * {@code SubmitNodeCollector#submitCustomGeometry} 提交；这样宿主抓面（ESP/掩膜）
 * 拿到的是同一个 collector，不需要额外适配。
 *
 * <p>优先级 900 必须早于默认 1000：插件自己的 {@code FriendWine} 也在 HEAD 压栈，
 * 它先压、我们再取消，它的 {@code popPose} 就永远不会执行。
 *
 * <p>只处理本地玩家（{@code AvatarRenderState.id} 比对），他人保持原版；贴图暂用玩家皮肤，
 * 用于确认坐标系与朝向，P3 接真正的模型几何后替换。
 */
@Mixin(value = LivingEntityRenderer.class, priority = 900)
public abstract class PlayerModelSubmitMixin {

    private static final float HALF_WIDTH = 0.3F;
    private static final float HEIGHT = 1.8F;

    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void sfhplus$renderDebugBox(
            LivingEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            CallbackInfo callbackInfo) {
        CustomModelModule module = CustomModelModule.INSTANCE;
        if (module == null || !module.isActive()) {
            return;
        }
        if (!(state instanceof AvatarRenderState avatar) || avatar.skin == null) {
            return;
        }
        var localPlayer = Minecraft.getInstance().player;
        if (localPlayer == null || avatar.id != localPlayer.getId()) {
            return;
        }
        int light = avatar.lightCoords;
        ModelRuntime runtime = CustomModelManager.runtime();
        if (runtime != null) {
            poseStack.pushPose();
            poseStack.last().pose().rotateY((float) Math.toRadians(180.0F - avatar.bodyRot));
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(runtime.texture()),
                    (pose, buffer) -> runtime.render(pose, buffer, light));
            poseStack.popPose();
            if (LOGGED.compareAndSet(false, true)) {
                Debug.info("[SFH+] custom model rendered: " + CustomModelManager.loadedFile()
                        + " texture=" + runtime.texture());
            }
        } else {
            Identifier texture = avatar.skin.body().texturePath();
            if (texture == null) {
                return;
            }
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture),
                    (pose, buffer) -> emitBox(pose, buffer, light));
            if (LOGGED.compareAndSet(false, true)) {
                Debug.info("[SFH+] custom model debug box rendered, texture=" + texture);
            }
        }
        callbackInfo.cancel();
    }

    private static void emitBox(PoseStack.Pose pose, VertexConsumer buffer, int light) {
        float x0 = -HALF_WIDTH;
        float x1 = HALF_WIDTH;
        float y0 = 0.0F;
        float y1 = HEIGHT;
        float z0 = -HALF_WIDTH;
        float z1 = HALF_WIDTH;

        // +X
        emitQuad(buffer, pose, light, 1.0F, 0.0F, 0.0F,
                x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
        // -X
        emitQuad(buffer, pose, light, -1.0F, 0.0F, 0.0F,
                x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        // +Z
        emitQuad(buffer, pose, light, 0.0F, 0.0F, 1.0F,
                x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        // -Z
        emitQuad(buffer, pose, light, 0.0F, 0.0F, -1.0F,
                x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
        // +Y
        emitQuad(buffer, pose, light, 0.0F, 1.0F, 0.0F,
                x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
        // -Y
        emitQuad(buffer, pose, light, 0.0F, -1.0F, 0.0F,
                x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
    }

    private static void emitQuad(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            int light,
            float nx, float ny, float nz,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            float dx, float dy, float dz) {
        emitVertex(buffer, pose, light, ax, ay, az, 0.0F, 1.0F, nx, ny, nz);
        emitVertex(buffer, pose, light, bx, by, bz, 1.0F, 1.0F, nx, ny, nz);
        emitVertex(buffer, pose, light, cx, cy, cz, 1.0F, 0.0F, nx, ny, nz);
        emitVertex(buffer, pose, light, dx, dy, dz, 0.0F, 0.0F, nx, ny, nz);
    }

    private static void emitVertex(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            int light,
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz) {
        buffer.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
