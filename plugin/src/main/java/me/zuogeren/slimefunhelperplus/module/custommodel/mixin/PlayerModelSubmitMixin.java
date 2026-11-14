package me.zuogeren.slimefunhelperplus.module.custommodel.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
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
import net.minecraft.util.Mth;
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
    protected abstract void scale(LivingEntityRenderState state, PoseStack poseStack);

    @Shadow
    protected abstract boolean isShaking(LivingEntityRenderState state);

    @Shadow
    protected abstract float getFlipDegrees();

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
            applyEntityTransform(avatar, poseStack, runtime);
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
     * <p>只取「姿势」那一段：鞘翅平飞、游泳、睡觉、倒立都写在这一段里，
     * 模型自己再算一遍就会与实体姿态脱节（逐分支复刻见 {@link #applyPose}）。
     * 后面的 {@code scale(-1,-1,1)} 与 {@code translate(0,-1.501,0)} 不复刻 ——
     * 反序列化阶段已经把 YSM 的坐标烘成「Y 向上、脚在原点」，再翻一次会把模型倒过来。
     */
    private void applyEntityTransform(AvatarRenderState state, PoseStack poseStack, ModelRuntime runtime) {
        if (state.hasPose(Pose.SLEEPING)) {
            Direction bedOrientation = state.bedOrientation;
            if (bedOrientation != null) {
                float headOffset = state.eyeHeight - 0.1F;
                poseStack.translate(-bedOrientation.getStepX() * headOffset, 0.0F,
                        -bedOrientation.getStepZ() * headOffset);
            }
        }
        poseStack.scale(state.scale, state.scale, state.scale);
        applyPose(state, poseStack);
        // 玩家尺寸缩放（AvatarRenderer 覆写成 0.9375）：vanilla 在 setupRotations 之后做这一步，
        // 少了模型整体会大 6.25%
        this.scale(state, poseStack);
        // 模型作者在 properties 里声明的整体缩放（YSM 默认 0.7）。
        // 两个轴是**交叉**用的（X/Z 取 heightScale、Y 取 widthScale），照 SPM 的 IGeoRenderer#renderEarly 原样搬；
        // 宽高相同的模型看不出差别，但宽高不同的模型必须这样才对得上。
        poseStack.scale(runtime.heightScale(), runtime.widthScale(), runtime.heightScale());
        // 平飞俯仰在 applyPose 里就没有施加（见上一段注释），这里不需要再抵消。
        // runtime.handlesElytraPitch() 保留给将来的「跟随原版姿态」开关：那时在这里加回俯仰即可。
    }

    /**
     * 复刻 {@code AvatarRenderer#setupRotations} 最终交给 {@code LivingEntityRenderer#setupRotations} 的那一层。
     *
     * <p>不能直接调 {@code this.setupRotations}：死亡翻滚与激流自旋会再叠一层实体级旋转，而这两个动作
     * 模型动画自己会做（YSM 是在调 super 之前把 {@code deathTime}/{@code isAutoSpinAttack} 临时清零）。
     * {@code state} 是与其它图层共享的渲染状态，不能照抄那套改写-还原，只能在复刻时摘掉这两条分支；
     * 摘掉之后这两个状态会落到睡觉、倒立分支上，与 YSM 清零后走到的位置一致。
     * 睡觉、倒立、{@code isShaking} 抖动、平飞俯仰与朝向、游泳前倾都照原样保留。
     */
    private void applyPose(AvatarRenderState state, PoseStack poseStack) {
        float bodyRot = state.bodyRot;
        if (this.isShaking(state)) {
            bodyRot += (float) (Math.cos(Mth.floor(state.ageInTicks) * 3.25F) * Math.PI * 0.4F);
        }
        if (!state.hasPose(Pose.SLEEPING)) {
            rotateDegrees(poseStack, Axis.YP, 180.0F - bodyRot);
        }
        if (state.hasPose(Pose.SLEEPING)) {
            Direction bedOrientation = state.bedOrientation;
            rotateDegrees(poseStack, Axis.YP,
                    bedOrientation != null ? bedDirectionYaw(bedOrientation) : bodyRot);
            rotateDegrees(poseStack, Axis.ZP, this.getFlipDegrees());
            rotateDegrees(poseStack, Axis.YP, 270.0F);
        } else if (state.isUpsideDown) {
            poseStack.translate(0.0F, (state.boundingBoxHeight + 0.1F) / state.scale, 0.0F);
            rotateDegrees(poseStack, Axis.ZP, 180.0F);
        }
        // 平飞俯仰与「按移动方向扭转」这里都不加：YSM 的渲染器直接继承 LivingEntityRenderer，
        // 它调到的 setupRotations 根本没有平飞分支（那是 AvatarRenderer 才有的），所以 YSM 模型
        // 在平飞时身体既不前倾也不随移动方向转 —— 姿态全部由 elytra_fly 片段自己摆。
        // 游泳倾斜这里也不加，理由和上一段一样：原版把 swimAmount 的分支写在 AvatarRenderer 里，
        // 而 YSM 的渲染器继承的是 LivingEntityRenderer（全库对 swimAmount 零引用）。YSM 模型的游泳姿态
        // 由 swim / swim_stand 片段自己摆，多这一层会把身体压成原版那种水平前扑。
    }

    /**
     * 原版 {@code LivingEntityRenderer#sleepDirectionToRotation} 是 private static，shadow 不到；
     * 沿用同名会被 Mixin 判成与目标类的成员重名，所以另起一个名字。
     */
    private static float bedDirectionYaw(Direction direction) {
        return switch (direction) {
            case SOUTH -> 90.0F;
            case WEST -> 0.0F;
            case NORTH -> 270.0F;
            case EAST -> 180.0F;
            default -> 0.0F;
        };
    }

    /**
     * {@code PoseStack} 的旋转入口三个版本各不相同（26.3 没有 {@code mulPose(Quaternionfc)}），
     * 但 {@code Pose#rotate(Quaternionfc)} 三者都有、也正是 {@code mulPose} 的内部实现，
     * 走这一层就不必为版本差异拆 {@code //#if}。
     */
    private static void rotateDegrees(PoseStack poseStack, Axis axis, float degrees) {
        poseStack.last().rotate(axis.rotationDegrees(degrees));
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