package me.zuogeren.slimefunhelperplus.module.custommodel.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.concurrent.atomic.AtomicBoolean;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelManager;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ModelRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 用自定义模型替换本地玩家的原版模型：在 {@code LivingEntityRenderer#submit} 的 HEAD 取消，
 * 几何用 {@code SubmitNodeCollector#submitCustomGeometry} 提交；这样宿主抓面（ESP/掩膜）
 * 拿到的是同一个 collector，不需要额外适配。
 *
 * <p>取消 HEAD 会把原版的模型、装备层、手持物层一起去掉：装备由模型自带，手持物在这里按
 * {@code RightHandLocator/LeftHandLocator} 重新挂上（见 {@link ModelRuntime#handItemMatrix}）。
 * 影子由 {@code EntityRenderDispatcher} 单独提交，不受影响；本地玩家本来就不显示名牌。
 *
 * <p>优先级 900 必须早于默认 1000：插件自己的 {@code FriendWine} 也在 HEAD 压栈，
 * 它先压、我们再取消，它的 {@code popPose} 就永远不会执行。
 */
@Mixin(value = LivingEntityRenderer.class, priority = 900)
public abstract class PlayerModelSubmitMixin {

    @Shadow
    protected abstract void setupRotations(LivingEntityRenderState state, PoseStack poseStack, float bodyRot,
            float entityScale);

    private static final float HALF_WIDTH = 0.3F;
    private static final float HEIGHT = 1.8F;

    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void sfhplus$renderCustomModel(
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
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (localPlayer == null || avatar.id != localPlayer.getId()) {
            return;
        }
        int light = avatar.lightCoords;
        ModelRuntime runtime = CustomModelManager.runtime();
        if (runtime != null) {
            poseStack.pushPose();
            applyEntityTransform(avatar, poseStack);
            float ageSeconds = avatar.ageInTicks / 20.0F;
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(runtime.texture()),
                    (pose, buffer) -> runtime.render(pose, buffer, light, avatar, localPlayer, ageSeconds));
            renderHeldItems(runtime, localPlayer, poseStack, collector, light);
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

    /**
     * 复刻 {@code LivingEntityRenderer#submit} 出模型之前的实体系变换。
     *
     * <p>只取「姿势」那一段：鞘翅平飞、游泳、死亡倒地、激流旋转都写在 {@code setupRotations} 里，
     * 模型自己再算一遍就会与实体姿态脱节。
     * 后面的 {@code scale(-1,-1,1)} 与 {@code translate(0,-1.501,0)} 不复刻 ——
     * 反序列化阶段已经把 YSM 的坐标烘成「Y 向上、脚在原点」，再翻一次会把模型倒过来。
     */
    private void applyEntityTransform(AvatarRenderState state, PoseStack poseStack) {
        if (state.hasPose(Pose.SLEEPING)) {
            Direction bedOrientation = state.bedOrientation;
            if (bedOrientation != null) {
                float headOffset = state.eyeHeight - 0.1F;
                poseStack.translate(-bedOrientation.getStepX() * headOffset, 0.0F,
                        -bedOrientation.getStepZ() * headOffset);
            }
        }
        poseStack.scale(state.scale, state.scale, state.scale);
        this.setupRotations(state, poseStack, state.bodyRot, state.scale);
    }

    private static void renderHeldItems(ModelRuntime runtime, LocalPlayer player, PoseStack poseStack,
            SubmitNodeCollector collector, int light) {
        ItemModelResolver resolver = Minecraft.getInstance().getItemModelResolver();
        if (resolver == null) {
            return;
        }
        HumanoidArm mainArm = player.getMainArm();
        HumanoidArm offArm = mainArm == HumanoidArm.LEFT ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
        renderHeldItem(runtime, resolver, player, player.getMainHandItem(), mainArm, poseStack, collector, light);
        renderHeldItem(runtime, resolver, player, player.getOffhandItem(), offArm, poseStack, collector, light);
    }

    private static void renderHeldItem(ModelRuntime runtime, ItemModelResolver resolver, LocalPlayer player,
            ItemStack stack, HumanoidArm arm, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        boolean rightHand = arm == HumanoidArm.RIGHT;
        Matrix4f hand = runtime.handItemMatrix(rightHand);
        if (hand == null) {
            return;
        }
        ItemDisplayContext context = rightHand
                ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                : ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        ItemStackRenderState renderState = new ItemStackRenderState();
        resolver.updateForTopItem(renderState, stack, context, player.level(), player,
                player.getId() + context.ordinal());
        poseStack.pushPose();
        poseStack.mulPose(hand);
        renderState.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
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