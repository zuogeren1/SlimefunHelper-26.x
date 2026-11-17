package me.zuogeren.slimefunhelperplus.module.custommodel.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.concurrent.atomic.AtomicBoolean;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelManager;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ItemClassifier;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ModelRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.model.animal.parrot.ParrotModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.renderer.entity.ParrotRenderer;
import net.minecraft.client.renderer.entity.state.ParrotRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    protected abstract float getWhiteOverlayProgress(LivingEntityRenderState state);

    @Shadow
    protected abstract float getFlipDegrees();

    private static final float HALF_WIDTH = 0.3F;
    private static final float HEIGHT = 1.8F;

    /** 隐形到看不见身体时原版给的整体 ARGB（{@code LivingEntityRenderer#submit} 里的 654311423）。 */
    private static final int INVISIBLE_TINT = 654311423;
    /** 不透明：{@code ARGB.multiply} 的乘法单位元，原版 {@code getModelTint} 缺省也是它。 */
    private static final int OPAQUE_TINT = -1;
    /** 轮廓趟用的满亮度（原版 {@code submitModel} 给 outline 的那一档）。 */
    private static final int FULL_BRIGHT = 15728880;

    /** 原版翅膀贴图（{@code WingsLayer} 在皮肤没有自带鞘翅/披风时用它）。 */
    private static final Identifier ELYTRA_TEXTURE = Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");

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
        // 卡片预览：合成实体编号认领自己的 runtime，姿态由卡片自己采样好，这里只出图。
        // 走同一条提交路径是为了复用渲染类型、发光趟与图层逻辑，不必另写一套渲染器。
        ModelRuntime card = CustomModelManager.cardRuntime(avatar.id);
        if (card != null) {
            submitCardModel(card, avatar, poseStack, collector, avatar.lightCoords);
            callbackInfo.cancel();
            return;
        }
        if (localPlayer == null || avatar.id != localPlayer.getId()) {
            return;
        }
        int light = avatar.lightCoords;
        // 原版 submitModel 逐顶点写进去的两个通道：受击红闪走 overlay 坐标，隐形走整体 alpha。
        // 自定义几何由 ModelRuntime 出图、它只认 light，所以在这一层把两个通道补回去。
        // 判据照 LivingEntityRenderer#submit 的字节码：bodyVisible = !isInvisible；
        // translucent = !bodyVisible && !isInvisibleToPlayer（只有这一档才给 654311423 的整体 alpha）。
        boolean bodyVisible = !avatar.isInvisible;
        boolean translucent = !bodyVisible && !avatar.isInvisibleToPlayer;
        boolean glowing = avatar.appearsGlowing();
        int overlay = LivingEntityRenderer.getOverlayCoords(avatar, this.getWhiteOverlayProgress(avatar));
        int tint = translucent ? INVISIBLE_TINT : OPAQUE_TINT;
        // 预览页的实体走 GUI 的 PiP 通道：GuiEntityRenderer 自己 new 一个 CameraRenderState，
        // 从不经过 Camera#extractRenderState，initialized 恒为 false；世界里的实体拿的是
        // GameRenderer 每帧填好的那份，initialized 为 true。世界玩家必须继续用世界实例，
        // 否则面板背后的角色会跟着预览实例播动作。
        //
        // 不能用「预览页开着」这种全局标记：GUI 的实体出图是延迟的，paint 里只登记
        // GuiEntityRenderState，真正出图在之后的 PiP 阶段，那时标记早已复位。
        boolean previewRender = camera != null && !camera.initialized;
        ModelRuntime runtime = previewRender ? CustomModelManager.runtime() : CustomModelManager.worldRuntime();
        if (runtime != null) {
            poseStack.pushPose();
            applyEntityTransform(avatar, poseStack, runtime);
            float ageSeconds = avatar.ageInTicks / 20.0F;
            RenderType mainType = mainRenderType(runtime, bodyVisible, translucent, glowing);
            if (mainType != null) {
                collector.submitCustomGeometry(poseStack, mainType,
                        (pose, buffer) -> runtime.render(pose, tinted(buffer, tint, overlay), light, avatar,
                                localPlayer, ageSeconds));
            }
            // 发光轮廓是原版 submitModel 的第二趟：outlineColor 非 0 时再交一遍几何，
            // 亮度满档、overlay 不参与、颜色取队伍色。HEAD 取消把这一趟一起砍掉了，这里补回来。
            // 主趟本身就是 outline 类型时（隐形 + 发光）原版只交一趟，不能重复提交。
            if (glowing && mainType != null && !mainType.isOutline()) {
                collector.submitCustomGeometry(poseStack, RenderTypes.outline(runtime.texture()),
                        (pose, buffer) -> runtime.render(
                                pose, tinted(buffer, avatar.outlineColor, OverlayTexture.NO_OVERLAY),
                                FULL_BRIGHT, avatar, localPlayer, ageSeconds));
            }
            // 发光骨第二趟：只出 ysmGlow 骨，emissive 渲染类型 + 满亮度，排在主趟之后
            // （与 SPM 的 IGeoRenderer:48-111 同序）。没有发光骨的模型不提交，省一趟空绘制。
            if (mainType != null && runtime.hasGlowBones()) {
                collector.submitCustomGeometry(poseStack,
                        RenderTypes.entityTranslucentEmissive(runtime.texture()),
                        (pose, buffer) -> runtime.renderGlow(pose, tinted(buffer, tint, overlay), FULL_BRIGHT));
            }
            if (mainType != null) {
                renderHeldItems(runtime, localPlayer, poseStack, collector, light);
                renderLayers(runtime, avatar, localPlayer, poseStack, collector, light);
            }
            poseStack.popPose();
            if (LOGGED.compareAndSet(false, true)) {
                CustomModelModule.logDebug("[SFH+] custom model rendered: " + CustomModelManager.loadedFile()
                        + " texture=" + runtime.texture());
            }
        } else {
            Identifier texture = avatar.skin.body().texturePath();
            if (texture == null) {
                return;
            }
            if (bodyVisible || translucent) {
                collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture),
                        (pose, buffer) -> emitBox(pose, tinted(buffer, tint, overlay), light));
            }
            if (LOGGED.compareAndSet(false, true)) {
                CustomModelModule.logDebug("[SFH+] custom model debug box rendered, texture=" + texture);
            }
        }
        callbackInfo.cancel();
    }

    /**
     * 卡片预览的出图：几何来自卡片自己的 runtime，姿态是它最近一次采样好的快照。
     *
     * <p>不复刻 {@link #applyEntityTransform}：卡片要的是「模型本身长什么样」，
     * 实体姿态（睡觉、游泳、潜行）属于世界里的玩家，套到卡片上只会让十个模型摆出同一个姿势。
     * 朝向由卡片在 PoseStack 上自己转。
     */
    private void submitCardModel(ModelRuntime runtime, AvatarRenderState avatar, PoseStack poseStack,
            SubmitNodeCollector collector, int light) {
        // 卡片走 EntityRenderDispatcher.submit → 原版 LivingEntityRenderer.submit，那里有
        // scale(-1,-1,1) 把 Y 向下的原版模型翻正；我们的几何在反序列化阶段已经是 Y 向上，
        // 再翻一次就是倒立。这里先翻回来抵消掉。
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        // 只补作者声明的整体缩放（YSM 默认 0.7），**不补** AvatarRenderer 的 0.9375。
        //
        // YSM 的选择界面走 {@code renderAnimatableEntity → IGeoRenderer.preRender}，
        // 那里只有 {@code scale(widthScale, heightScale, widthScale)}，不经 {@code LivingEntityRenderer}，
        // 所以没有 0.9375 这一层。卡片要跟 YSM 的观感对齐就不能多乘，否则小 6.25%。
        poseStack.scale(avatar.scale, avatar.scale, avatar.scale);
        poseStack.scale(runtime.heightScale(), runtime.widthScale(), runtime.heightScale());
        RenderType mainType = runtime.hasTranslucentPixels()
                ? RenderTypes.entityTranslucent(runtime.texture())
                : RenderTypes.entityCutout(runtime.texture());
        collector.submitCustomGeometry(poseStack, mainType,
                (pose, buffer) -> runtime.renderSnapshot(pose, buffer, light));
        if (runtime.hasGlowBones()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(runtime.texture()),
                    (pose, buffer) -> runtime.renderGlowSnapshot(pose, buffer, FULL_BRIGHT));
        }
    }

    /**
     * 主趟的渲染类型，逐分支对齐 {@code LivingEntityRenderer#getRenderType}：
     * 半透明档（隐形但队友可见）走 translucent，正常档走 cutout，只有轮廓时走 outline，
     * 其余（隐形且看不见）返回 {@code null} 表示不提交。
     *
     * <p>原版正常档用的是 {@code model.renderType(texture)}，玩家模型就是 {@code entityCutout}；
     * 半透明档原版用 {@code entityTranslucentCullItemTarget}，那是给物品实体目标缓冲用的，
     * 自定义几何走主目标，所以这里用 {@code entityTranslucent}。
     *
     * <p>cutout 把 alpha 直接切掉，软边头发/纱/翅膀会被硬切。SPM 按贴图整体判
     * （{@code GeoReplacedEntityRenderer:159} 的 {@code isTranslucentTexture}），
     * 这里同样按整张贴图判（{@link ModelRuntime#hasTranslucentPixels}），因为 RenderType 是整趟的。
     */
    private static RenderType mainRenderType(ModelRuntime runtime, boolean bodyVisible, boolean translucent,
            boolean glowing) {
        if (translucent) {
            return RenderTypes.entityTranslucent(runtime.texture());
        }
        if (bodyVisible) {
            return runtime.hasTranslucentPixels()
                    ? RenderTypes.entityTranslucent(runtime.texture())
                    : RenderTypes.entityCutout(runtime.texture());
        }
        return glowing ? RenderTypes.outline(runtime.texture()) : null;
    }

    /** 把颜色与 overlay 两个通道钉成给定值的转发器，见 {@link TintedVertexConsumer}。 */
    private static VertexConsumer tinted(VertexConsumer buffer, int color, int overlay) {
        return new TintedVertexConsumer(buffer, color, overlay);
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


    /**
     * 原版 {@code AvatarRenderer} 的四个图层：鞘翅、肩头鹦鹉、头顶物、披风。
     *
     * <p>HEAD 取消把 {@code LivingEntityRenderer#submit} 的图层循环一起砍掉了，这几层要在这里补回来，
     * 否则滑翔没翅膀、肩上鹦鹉消失。挂点用模型自己的定位组
     * （{@code PlayerLocator} 的 {@code ElytraLocator} / {@code *ShoulderLocator}），
     * 与 YSM 的 {@code CustomPlayerElytraLayer} / {@code CustomParrotOnShoulderLayer} 同形；
     * 模型没有对应定位组时退回原版的固定偏移。
     *
     * <p>顺序照 {@code AvatarRenderer} 构造器：装备层之后是手持物，再是箭矢/耳朵/披风/翅膀/鹦鹉。
     * 这里只补自定义模型会遮住的两层，箭矢与耳朵由模型自己表现。
     *
     * <p><b>不画头顶方块/头颅</b>：YSM 的 {@code CustomPlayerHeadLayer} 会挂，但自定义模型的头部
     * 通常自带发型/帽子，再叠一个南瓜或头颅会穿模；玩家戴南瓜的意图是「看不见脸」，
     * 而自定义模型本来就不显示原版脸，所以这里直接不画。
     */
    private static void renderLayers(ModelRuntime runtime, AvatarRenderState avatar, LocalPlayer player,
            PoseStack poseStack, SubmitNodeCollector collector, int light) {
        EntityModelSet models = Minecraft.getInstance().getEntityModels();
        if (models == null) {
            return;
        }
        renderElytra(runtime, avatar, player, poseStack, collector, light, models);
        renderParrots(runtime, avatar, poseStack, collector, light, models);
    }

    /**
     * 鞘翅：{@code WingsLayer} 的等价物。
     *
     * <p>原版把翅膀挂在 {@code translate(0, 0, 0.125)} 之后，YSM 挂在 {@code ElytraLocator} 上并额外
     * {@code translate(0, 1.5, 0) · rotZ(180°) · scale(2)}。
     *
     * <p><b>没有 {@code ElytraLocator} 就不画</b>：YSM 的 {@code CustomPlayerElytraLayer} 走的是
     * {@code visitLocatorGroup}（不是 {@code OrDefault}），空组时回调根本不触发。退回原版偏移会把
     * 一对倒着的原版翅膀糊在模型背后 —— 模型作者没摆过挂点，那个位置本来就没有翅膀。
     *
     * <p><b>要抵消模型的整体缩放</b>：YSM 的图层跑在 {@code setupRotations} 之后、
     * {@code IGeoRenderer#renderEarly} 之前（{@code GeoReplacedEntityRenderer:168-176}），
     * 那时 pose 上还没有 {@code widthScale}；我们这里排在 {@link #applyEntityTransform} 之后，
     * 已经乘过 0.7 之类的整体缩放，不除回去翅膀会比 YSM 大一圈。
     */
    private static void renderElytra(ModelRuntime runtime, AvatarRenderState avatar, LocalPlayer player,
            PoseStack poseStack, SubmitNodeCollector collector, int light, EntityModelSet models) {
        ItemStack chest = avatar.chestEquipment;
        if (chest == null || chest.isEmpty() || !chest.is(Items.ELYTRA)) {
            return;
        }
        ElytraModel model = new ElytraModel(models.bakeLayer(ModelLayers.ELYTRA));
        model.setupAnim(avatar);
        Identifier texture = elytraTexture(avatar);
        int count = runtime.locatorGroupSize("ElytraLocator");
        if (count == 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            Matrix4f locator = runtime.locatorMatrix("ElytraLocator", i);
            if (locator == null) {
                continue;
            }
            poseStack.pushPose();
            poseStack.mulPose(locator);
            // 与 SPM 的 CustomPlayerElytraLayer:50-58 逐行一致：locator 矩阵之后直接
            // translate(0,1.5,0) · rotZ(180) · scale(2)，**不做 widthScale 补偿**。
            // SPM 的图层同样跑在模型整体缩放之后，它没补偿就是对的 —— 补偿反而把翅膀推偏。
            poseStack.translate(0.0F, 1.5F, 0.0F);
            rotateDegrees(poseStack, Axis.ZP, 180.0F);
            // 1.0 是实机调出来的：2.0 明显偏大、0.5 明显偏小，取中间。
            // 按模型高度自适应的公式不可靠 —— 语料里骨骼 pivot 的 Y 跨度含大量离群骨
            // （ysmGlow/特效骨能到 ±90 格），算出来的身高与实际渲染尺寸对不上。
            poseStack.scale(1.0F, 1.0F, 1.0F);
            submitModel(collector, model, avatar, poseStack, texture, light);
            poseStack.popPose();
        }
    }

    /** 鞘翅贴图：皮肤自带优先，其次披风（且披风可见），最后原版翅膀贴图。 */
    private static Identifier elytraTexture(AvatarRenderState avatar) {
        PlayerSkin skin = avatar.skin;
        if (skin != null) {
            if (skin.elytra() != null) {
                return skin.elytra().texturePath();
            }
            if (skin.cape() != null && avatar.showCape) {
                return skin.cape().texturePath();
            }
        }
        return ELYTRA_TEXTURE;
    }

    /**
     * 肩头鹦鹉：{@code ParrotOnShoulderLayer} 的等价物。
     *
     * <p>原版按 {@code translate(±0.4, -1.5, 0)} 挂在身体上；YSM 挂在 {@code *ShoulderLocator} 上，
     * 只额外 {@code translate(0, 1.5, 0) · rotZ(180°)}。有定位组时用后者。
     */
    private static void renderParrots(ModelRuntime runtime, AvatarRenderState avatar, PoseStack poseStack,
            SubmitNodeCollector collector, int light, EntityModelSet models) {
        if (avatar.parrotOnLeftShoulder == null && avatar.parrotOnRightShoulder == null) {
            return;
        }
        ParrotModel model = new ParrotModel(models.bakeLayer(ModelLayers.PARROT));
        renderParrot(runtime, avatar, poseStack, collector, light, model, avatar.parrotOnLeftShoulder, true);
        renderParrot(runtime, avatar, poseStack, collector, light, model, avatar.parrotOnRightShoulder, false);
    }

    private static void renderParrot(ModelRuntime runtime, AvatarRenderState avatar, PoseStack poseStack,
            SubmitNodeCollector collector, int light, ParrotModel model, Parrot.Variant variant,
            boolean leftShoulder) {
        if (variant == null) {
            return;
        }
        String locator = leftShoulder ? "LeftShoulderLocator" : "RightShoulderLocator";
        int count = runtime.locatorGroupSize(locator);
        ParrotRenderState state = parrotState(avatar, variant);
        if (count == 0) {
            poseStack.pushPose();
            poseStack.translate(leftShoulder ? 0.4F : -0.4F, avatar.isCrouching ? -1.3F : -1.5F, 0.0F);
            model.setupAnim(state);
            submitModel(collector, model, state, poseStack, ParrotRenderer.getVariantTexture(variant), light);
            poseStack.popPose();
            return;
        }
        for (int i = 0; i < count; i++) {
            Matrix4f locatorPose = runtime.locatorMatrix(locator, i);
            if (locatorPose == null) {
                continue;
            }
            poseStack.pushPose();
            poseStack.mulPose(locatorPose);
            poseStack.translate(0.0F, 1.5F, 0.0F);
            rotateDegrees(poseStack, Axis.ZP, 180.0F);
            // 同鞘翅：抵消 applyEntityTransform 已经乘上的模型整体缩放
            poseStack.scale(1.0F / runtime.heightScale(), 1.0F / runtime.widthScale(),
                    1.0F / runtime.heightScale());
            model.setupAnim(state);
            submitModel(collector, model, state, poseStack, ParrotRenderer.getVariantTexture(variant), light);
            poseStack.popPose();
        }
    }

    /** 肩头鹦鹉的渲染状态：原版 {@code ParrotOnShoulderLayer} 只填这几个字段。 */
    private static ParrotRenderState parrotState(AvatarRenderState avatar, Parrot.Variant variant) {
        ParrotRenderState state = new ParrotRenderState();
        state.pose = ParrotModel.Pose.ON_SHOULDER;
        state.variant = variant;
        state.ageInTicks = avatar.ageInTicks;
        state.walkAnimationPos = avatar.walkAnimationPos;
        state.walkAnimationSpeed = avatar.walkAnimationSpeed;
        state.yRot = avatar.yRot;
        state.xRot = avatar.xRot;
        return state;
    }

    /**
     * 提交一个原版模型：26.2 的 {@code submitModel} 末尾多一个 {@code CrumblingOverlay} 参数，26.3 去掉了。
     *
     * <p>两个版本都收 {@code (Model, S, PoseStack, Identifier, int light, int overlay, int tint)} 这一段，
     * 只有尾部不同，所以只在这一处拆版本。
     */
    private static <S> void submitModel(SubmitNodeCollector collector, net.minecraft.client.model.Model<? super S> model,
            S state, PoseStack poseStack, Identifier texture, int light) {
//#if MC >= 260300
//$$         collector.submitModel(model, state, poseStack, texture, light, OverlayTexture.NO_OVERLAY, -1);
//#else
        collector.submitModel(model, state, poseStack, texture, light, OverlayTexture.NO_OVERLAY, -1, null);
//#endif
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
        // 模型自己在 *Sword 锚点下画了剑：再叠一把原版剑就是双剑（SPM SwordReplacementHelper:20-30）
        if (stack.is(ItemTags.SWORDS) && runtime.hasSwordGeometry(rightHand)) {
            return;
        }
        Matrix4f hand = runtime.handItemMatrix(rightHand, ItemClassifier.grip(stack));
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

    /**
     * 只改颜色与 overlay 两个通道的转发器。
     *
     * <p>几何由 {@link ModelRuntime#render} 出图，它逐顶点写死 {@code setColor(255,255,255,255)} 与
     * {@code setOverlay(NO_OVERLAY)}；而受击红闪、隐形 alpha、发光轮廓的颜色都只有渲染状态
     * （{@code AvatarRenderState}）知道。在提交这一层套一层转发器，就不用把渲染状态塞进
     * {@code ModelRuntime} 的出图签名，也不用改它的顶点代码。
     */
    private static final class TintedVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final int color;
        private final int overlay;

        TintedVertexConsumer(VertexConsumer delegate, int color, int overlay) {
            this.delegate = delegate;
            this.color = color;
            this.overlay = overlay;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.delegate.setColor(this.color);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            this.delegate.setColor(this.color);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.delegate.setUv2(u, v);
            return this;
        }

//#if MC >= 260300
//$$         /** 26.3 的第三组 uv 是贴片/闪光用的 decal uv，与颜色/overlay 无关，原样转发。 */
//$$         @Override
//$$         public VertexConsumer setUv3(float u, float v) {
//$$             this.delegate.setUv3(u, v);
//$$             return this;
//$$         }
//#endif

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            this.delegate.setLineWidth(width);
            return this;
        }

        @Override
        public VertexConsumer setOverlay(int packedOverlay) {
            this.delegate.setOverlay(this.overlay);
            return this;
        }

        @Override
        public VertexConsumer setLight(int packedLight) {
            this.delegate.setLight(packedLight);
            return this;
        }
    }
}