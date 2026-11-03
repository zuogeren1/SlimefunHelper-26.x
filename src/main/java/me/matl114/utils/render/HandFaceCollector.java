package me.matl114.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import me.matl114.utils.Debug;
import me.matl114.versioned.impl.Render_v1_21_11;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3fc;
//#if MC >= 260200
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.world.phys.shapes.VoxelShape;
//#else
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
//#endif

/**
 * 第一人称手部的「发光壳层 + 边框」：抓下这一帧手部提交的每一个面，再按逐面外扩重提交两层。
 *
 * <p>手部几何在两个版本上都只走三条出口：手臂 {@code submitModelPart}（{@code AvatarRenderer#renderHand} 每只手交一个
 * {@link ModelPart}）、手持物 {@code submitItem}（{@code ItemStackRenderState$LayerRenderState#submit}）、
 * 地图等自定义几何 {@code submitCustomGeometry}。本类覆盖第一条与第二条来抓面，第三条只转发：
 * 地图的几何不是模型面，抓下来会得到一层面片而不是手。
 *
 * <p>重提交的姿势要凑齐执行期那一次 model view（{@code RenderType#draw} 画的时候读 {@code RenderSystem} 的
 * model view 栈顶）：抓到的顶点已经乘过提交时的 pose，根上正是那张 model view 的逆。两个版本的消费时机不同 ——
 * 26.2 的重提交与手部进同一次 flush，执行期乘的还是提交时那张，姿势取 identity；26.1.2 的手部由
 * {@code ItemInHandRenderer#renderHandsWithItems} 自己先 flush，本类补交的面留到外层 {@code renderLevel} 才画，
 * 那一趟 model view 已经弹回 identity，姿势就得把提交时那张带回来。复用 {@code ItemInHandRenderer} 的 PoseStack
 * 会再乘一次模型矩阵。
 *
 * <p>壳层是纯色而不是手部贴图：抓面侧拿不到与每个面配套的贴图（模型用 sprite、手持物每层各一张），套错就串图。
 */
public final class HandFaceCollector {
    /** 壳层顶点沿「面质心 → 顶点」方向的外扩比例。 */
    private static final float SHELL_EXPAND = 0.04F;

    /** 一帧内抓到的面，每个面四个已乘过 pose 的顶点。 */
    private final List<Vec3[]> faces = new ArrayList<>();

    private boolean collecting;

    private boolean failureReported;

    /**
     * 开始一帧的抓取，返回要喂给手部提交的 collector。
     *
     * @param delegate 真实的手部 collector（{@code GameRenderer} 的 hand storage），每一条提交都会原样转发给它
     */
    public SubmitNodeCollector begin(SubmitNodeCollector delegate) {
        this.faces.clear();
        this.collecting = true;
        return new Capture(delegate);
    }

    /** 抓取途中出错时的兜底：半个帧的面没有意义，留着会错位到下一帧。 */
    public void clear() {
        this.collecting = false;
        this.faces.clear();
    }

    /** 首次失败留痕：静默失败会表现成「开了发光没反应」，之后再刷日志只会把日志淹掉。 */
    public void reportFailure(Throwable failure) {
        if (!this.failureReported) {
            this.failureReported = true;
            Debug.info("hand glow failed", failure);
        }
    }

    /**
     * 把抓到的面重提交成壳层与边线，调用点必须在手部提交之后、同一个 collector 被消费之前。
     *
     * <p>壳层 alpha 为 0 时不提交壳层、边线照画：{@code glow-alpha} 只作用在填充色上，
     * 边框用的是另一个满 alpha 的颜色（上游同样把两者分开合成）。
     */
    public void flush(SubmitNodeCollector target, int shellColor, int boundaryColor) {
        this.collecting = false;
        try {
            if (this.faces.isEmpty()) {
                return;
            }
            List<Vec3[]> shell = new ArrayList<>(this.faces.size());
            for (Vec3[] face : this.faces) {
                if (face.length == 4) {
                    shell.add(expand(face));
                }
            }
            if (shell.isEmpty()) {
                return;
            }
            // 顶点要等到执行期才被顶点消费者读到，而 faces 下一帧就被清空：外扩后的快照是唯一数据源
            PoseStack pose = new PoseStack();
            applyResubmitPose(pose);
            if (alpha(shellColor) > 0) {
                submitShell(target, pose, shell, shellColor);
            }
            if (alpha(boundaryColor) > 0) {
                submitBoundary(target, pose, shell, boundaryColor);
            }
        } finally {
            this.faces.clear();
        }
    }

    /**
     * 补上执行期还会乘、而抓面时已经反乘掉的那张 model view，重提交的面才落在手部自己的位置上。
     *
     * <p>两个版本的消费时机不同：26.2 与手部进同一次 flush，{@code renderItemInHand} 压上去的 model view 还没弹，
     * 姿势取 identity；26.1.2 的手部由 {@code renderHandsWithItems} 自己先 flush 掉，本类补交的面要等到外层
     * {@code renderLevel} 那一趟，此时 model view 栈已经弹回 identity，姿势必须把提交时那张带回来。
     */
    private static void applyResubmitPose(PoseStack pose) {
        //#if MC >= 260200
        // 26.2：执行期乘的就是抓面时反乘掉的那一张，这里再乘一次会把壳层挪开
        //#else
        //$$ pose.mulPose(RenderSystem.getModelViewMatrix());
        //#endif
    }

    private static void submitShell(SubmitNodeCollector target, PoseStack poseStack, List<Vec3[]> faces, int color) {
        target.submitCustomGeometry(poseStack, Render_v1_21_11.QUADS_NO_CULL, (pose, buffer) -> {
            for (Vec3[] face : faces) {
                for (Vec3 vertex : face) {
                    buffer.addVertex(pose, (float) vertex.x, (float) vertex.y, (float) vertex.z)
                            .setColor(color);
                }
            }
        });
    }

    private static void submitBoundary(SubmitNodeCollector target, PoseStack poseStack, List<Vec3[]> faces, int color) {
        target.submitCustomGeometry(poseStack, Render_v1_21_11.LINES, (pose, buffer) -> {
            for (Vec3[] face : faces) {
                for (int index = 0; index < 4; index++) {
                    submitEdge(pose, buffer, face[index], face[(index + 1) & 3], color);
                }
            }
        });
    }

    /**
     * LINES 管线的顶点格式带法线与线宽，少任意一项都会被丢掉；法线取边方向，退化边（两个顶点重合）
     * 退回 +X，避免归一化出 NaN 把整批顶点带崩。
     */
    private static void submitEdge(PoseStack.Pose pose, VertexConsumer buffer, Vec3 from, Vec3 to, int color) {
        Vec3 edge = to.subtract(from);
        double length = edge.length();
        Vec3 normal = length < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : edge.scale(1.0D / length);
        float normalX = (float) normal.x;
        float normalY = (float) normal.y;
        float normalZ = (float) normal.z;
        buffer.addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
                .setColor(color)
                .setNormal(normalX, normalY, normalZ)
                .setLineWidth(1.5F);
        buffer.addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
                .setColor(color)
                .setNormal(normalX, normalY, normalZ)
                .setLineWidth(1.5F);
    }

    /**
     * 逐面外扩而不是按整手质心缩放：一次手部提交里两只手是分开提交的，但这个采集器跨两次提交共用，
     * 按整体质心缩放会把两只手各自推离自己的位置。
     */
    private static Vec3[] expand(Vec3[] face) {
        Vec3 center = face[0].add(face[1]).add(face[2]).add(face[3]).scale(0.25D);
        Vec3[] expanded = new Vec3[4];
        for (int index = 0; index < 4; index++) {
            expanded[index] = center.add(face[index].subtract(center).scale(1.0D + SHELL_EXPAND));
        }
        return expanded;
    }

    private static int alpha(int color) {
        return (color >>> 24) & 0xFF;
    }

    /**
     * 手部提交的中转 collector：先原样转发给真实 collector，再抄一份几何。
     *
     * <p>顺序不能反：抓面要重放一次模型渲染，重放抛异常时手部不能跟着缺件。
     */
    private final class Capture implements SubmitNodeCollector {
        private final SubmitNodeCollector delegate;

        private Capture(SubmitNodeCollector delegate) {
            this.delegate = delegate;
        }

        /** 手部链路从不调用 {@code order}，返回自己才能让后续提交继续落到本类。 */
        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return this;
        }

        /**
         * 手臂：两个版本的六参默认实现分别转到不同方法（26.2 转 {@code Model.Simple → submitModel}，
         * 26.1.2 转十一参抽象方法），六参签名是唯一两版本一致的入口，所以抓面挂在这里。
         */
        @Override
        public void submitModelPart(
                ModelPart part,
                PoseStack poseStack,
                RenderType renderType,
                int lightCoords,
                int overlayCoords,
                TextureAtlasSprite sprite) {
            this.delegate.submitModelPart(part, poseStack, renderType, lightCoords, overlayCoords, sprite);
            if (collecting) {
                captureModelPart(part, poseStack, lightCoords, overlayCoords);
            }
        }

        @Override
        public void submitItem(
                PoseStack poseStack,
                ItemDisplayContext displayContext,
                int lightCoords,
                int overlayCoords,
                int outlineColor,
                int[] tintLayers,
                List<BakedQuad> quads,
                ItemStackRenderState.FoilType foilType) {
            this.delegate.submitItem(
                    poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
            if (!collecting || quads.isEmpty()) {
                return;
            }
            // 物品的本地变换已经压进 pose，按面取顶点就够；贴图不参与纯色壳层，不挂 texture
            PoseStack.Pose pose = poseStack.last();
            CapturingVertexConsumer consumer = new CapturingVertexConsumer(null, HandFaceCollector.this::addFace);
            for (BakedQuad quad : quads) {
                for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
                    Vector3fc position = quad.position(vertex);
                    consumer.addVertex(pose, position.x(), position.y(), position.z());
                }
            }
            consumer.finish();
        }

        /**
         * 自定义几何只转发：手部链路上它只承载地图画面，画进壳层会多出一层平面；
         * 不抓也不能不转发，否则手里拿地图时画面整块消失。
         */
        @Override
        public void submitCustomGeometry(
                PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
            this.delegate.submitCustomGeometry(poseStack, renderType, renderer);
        }

        @Override
        public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
            this.delegate.submitShadow(poseStack, radius, pieces);
        }

        //#if MC >= 260200
        @Override
        public void submitNameTag(
                PoseStack poseStack,
                Vec3 attachment,
                int offset,
                Component name,
                boolean seeThrough,
                int lightCoords,
                CameraRenderState camera) {
            this.delegate.submitNameTag(poseStack, attachment, offset, name, seeThrough, lightCoords, camera);
        }
        //#else
        //$$ @Override
        //$$ public void submitNameTag(
        //$$         PoseStack poseStack,
        //$$         Vec3 attachment,
        //$$         int offset,
        //$$         Component name,
        //$$         boolean seeThrough,
        //$$         int lightCoords,
        //$$         double distanceSquared,
        //$$         CameraRenderState camera) {
        //$$     this.delegate.submitNameTag(
        //$$             poseStack, attachment, offset, name, seeThrough, lightCoords, distanceSquared, camera);
        //$$ }
        //#endif

        @Override
        public void submitText(
                PoseStack poseStack,
                float x,
                float y,
                FormattedCharSequence text,
                boolean dropShadow,
                Font.DisplayMode displayMode,
                int lightCoords,
                int color,
                int backgroundColor,
                int outlineColor) {
            this.delegate.submitText(
                    poseStack, x, y, text, dropShadow, displayMode, lightCoords, color, backgroundColor, outlineColor);
        }

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
            this.delegate.submitFlame(poseStack, renderState, rotation);
        }

        @Override
        public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
            this.delegate.submitLeash(poseStack, leashState);
        }

        @Override
        public <S> void submitModel(
                Model<? super S> model,
                S state,
                PoseStack poseStack,
                RenderType renderType,
                int lightCoords,
                int overlayCoords,
                int tintedColor,
                TextureAtlasSprite sprite,
                int outlineColor,
                ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            this.delegate.submitModel(
                    model,
                    state,
                    poseStack,
                    renderType,
                    lightCoords,
                    overlayCoords,
                    tintedColor,
                    sprite,
                    outlineColor,
                    crumblingOverlay);
        }

        @Override
        public void submitBlockModel(
                PoseStack poseStack,
                RenderType renderType,
                List<BlockStateModelPart> parts,
                int[] tintLayers,
                int lightCoords,
                int overlayCoords,
                int outlineColor) {
            this.delegate.submitBlockModel(
                    poseStack, renderType, parts, tintLayers, lightCoords, overlayCoords, outlineColor);
        }

        //#if MC >= 260200
        @Override
        public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState renderState, int outlineColor) {
            this.delegate.submitMovingBlock(poseStack, renderState, outlineColor);
        }

        @Override
        public void submitBreakingBlockModel(
                PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
            this.delegate.submitBreakingBlockModel(poseStack, parts, progress);
        }

        @Override
        public void submitShapeOutline(
                PoseStack poseStack,
                VoxelShape shape,
                RenderType renderType,
                int color,
                float width,
                boolean afterTerrain) {
            this.delegate.submitShapeOutline(poseStack, shape, renderType, color, width, afterTerrain);
        }

        @Override
        public void submitQuadParticleGroup(QuadParticleRenderState particles) {
            this.delegate.submitQuadParticleGroup(particles);
        }

        @Override
        public void submitGizmoPrimitives(
                DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
            this.delegate.submitGizmoPrimitives(group, camera, onTop);
        }
        //#else
        //$$ @Override
        //$$ public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState renderState) {
        //$$     this.delegate.submitMovingBlock(poseStack, renderState);
        //$$ }
        //$$
        //$$ @Override
        //$$ public void submitBreakingBlockModel(
        //$$         PoseStack poseStack, BlockStateModel model, long seed, int progress) {
        //$$     this.delegate.submitBreakingBlockModel(poseStack, model, seed, progress);
        //$$ }
        //$$
        //$$ /**
        //$$  * 26.1.2 把手臂的提交提成这个十一参抽象方法，本类抓面走的是六参覆盖，这里只做转发：
        //$$  * 两边都抓会把同一次手臂提交记两遍。
        //$$  */
        //$$ @Override
        //$$ public void submitModelPart(
        //$$         ModelPart part,
        //$$         PoseStack poseStack,
        //$$         RenderType renderType,
        //$$         int lightCoords,
        //$$         int overlayCoords,
        //$$         TextureAtlasSprite sprite,
        //$$         boolean sheeted,
        //$$         boolean hasFoil,
        //$$         int outlineColor,
        //$$         ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
        //$$         int order) {
        //$$     this.delegate.submitModelPart(
        //$$             part,
        //$$             poseStack,
        //$$             renderType,
        //$$             lightCoords,
        //$$             overlayCoords,
        //$$             sprite,
        //$$             sheeted,
        //$$             hasFoil,
        //$$             outlineColor,
        //$$             crumblingOverlay,
        //$$             order);
        //$$ }
        //$$
        //$$ @Override
        //$$ public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer renderer) {
        //$$     this.delegate.submitParticleGroup(renderer);
        //$$ }
        //#endif
    }

    /** 重放一次手臂模型渲染，把顶点抄进捕获式 buffer；push/pop 只是保证重放抛异常时不动 vanilla 的 pose。 */
    private void captureModelPart(ModelPart part, PoseStack poseStack, int lightCoords, int overlayCoords) {
        CapturingVertexConsumer consumer = new CapturingVertexConsumer(null, this::addFace);
        try {
            poseStack.pushPose();
            try {
                part.render(poseStack, consumer, lightCoords, overlayCoords, -1);
            } finally {
                poseStack.popPose();
            }
        } finally {
            consumer.finish();
        }
    }

    private void addFace(EntityModelCollector.CapturedQuad quad) {
        Vec3[] vertices = quad.vertices();
        if (vertices.length == 4) {
            this.faces.add(vertices);
        }
    }
}
