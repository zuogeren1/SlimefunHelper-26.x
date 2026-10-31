package me.matl114.utils.render;

//#if MC >= 260200
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

/**
 * 捕获式 {@link SubmitNodeCollector}：把实体渲染器的提交转成「面」而不是顶点缓冲。
 *
 * <p>26.2 的实体几何没有可替换的 {@code VertexConsumer} 入口了：{@code EntityRenderDispatcher#submit}
 * 只把内容塞进 {@link SubmitNodeCollector}，真正的顶点要等到 {@code ModelFeatureRenderer#prepareModel}
 * 才由 {@code model.renderToBuffer(poseStack, buffer, ...)} 写入 GPU（{@code ModelFeatureRenderer.java:28-40}）。
 * 所以这里自建一个 collector 接过提交，在 {@link #submitModel} 里把同一条渲染路径再走一遍，
 * 只是把顶点交给 {@link CapturingVertexConsumer} 抄下来。
 *
 * <p>除 {@link #submitModel} 与 {@link #submitCustomGeometry} 外的提交（名牌、影子、粒子、方块、物品…）
 * 都与「实体模型的面」无关，全部空实现。
 */
public final class CapturingSubmitNodeCollector implements SubmitNodeCollector {
    /** 每抓到一个面就回调一次（面, 颜色）。 */
    private final BiConsumer<EntityModelCollector.CapturedQuad, Integer> sink;

    /** 当前实体的颜色（由 {@link #begin} 设置）。 */
    private int color;

    /** 当前实体本体的贴图；抓完第一个模型后清空，见 {@link #submitModel}。 */
    private Identifier texture;

    public CapturingSubmitNodeCollector(BiConsumer<EntityModelCollector.CapturedQuad, Integer> sink) {
        this.sink = sink;
    }

    /** 开始抓一个实体：颜色来自收集器条目，贴图由调用方按实体渲染器解析（可能为空）。 */
    public void begin(int color, Identifier texture) {
        this.color = color;
        this.texture = texture;
    }

    /** 全部提交都要经过它，但抓面只关心顺序无关的原始几何，直接返回自己。 */
    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    /**
     * 抓到的是真模型面：这里复刻 {@code ModelFeatureRenderer#prepareModel}（{@code ModelFeatureRenderer.java:28-40}）
     * 的两步 —— 先 {@code model.setupAnim(state)}，再 {@code model.renderToBuffer(...)}，
     * 只是把 buffer 换成捕获式的，所以顶点位置/uv 与真正画出来时完全一致。
     *
     * <p>描边遍（{@code renderType.isOutline()}）是同一份几何的第二份，抓了会让描边收集器重复找边，直接丢掉。
     */
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
        if (renderType.isOutline()) {
            return;
        }
        // 只有第一个模型带贴图：LivingEntityRenderer 先提交本体（LivingEntityRenderer.java:95），
        // 之后才是护甲/附加层；附加层的贴图与本体不同，套本体的贴图重画会糊，按纯色面处理更干净。
        Identifier quadTexture = this.texture;
        this.texture = null;
        CapturingVertexConsumer consumer = new CapturingVertexConsumer(quadTexture, this::emit);
        model.setupAnim(state);
        model.renderToBuffer(poseStack, consumer, lightCoords, overlayCoords, tintedColor);
        consumer.finish();
    }

    /**
     * 自定义几何（光束 / 闪电 / 画…）也走 {@code CustomGeometryRenderer(PoseStack.Pose, VertexConsumer)}，
     * 这里用同一个 pose 喂捕获式的 buffer 即可。
     *
     * <p>只接四边形拓扑：线段（钓鱼线）和三角形按四顶点切分会串成假面。
     */
    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        if (renderType.isOutline() || renderType.primitiveTopology() != PrimitiveTopology.QUADS) {
            return;
        }
        CapturingVertexConsumer consumer = new CapturingVertexConsumer(this.texture, this::emit);
        customGeometryRenderer.render(poseStack.last(), consumer);
        consumer.finish();
    }

    private void emit(EntityModelCollector.CapturedQuad quad) {
        sink.accept(quad, color);
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {}

    @Override
    public void submitNameTag(
            PoseStack poseStack,
            Vec3 nameTagAttachment,
            int offset,
            Component name,
            boolean seeThrough,
            int lightCoords,
            CameraRenderState camera) {}

    @Override
    public void submitText(
            PoseStack poseStack,
            float x,
            float y,
            FormattedCharSequence string,
            boolean dropShadow,
            Font.DisplayMode displayMode,
            int lightCoords,
            int color,
            int backgroundColor,
            int outlineColor) {}

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {}

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {}

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {}

    @Override
    public void submitBlockModel(
            PoseStack poseStack,
            RenderType renderType,
            List<BlockStateModelPart> parts,
            int[] tintLayers,
            int lightCoords,
            int overlayCoords,
            int outlineColor) {}

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {}

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean afterTerrain) {}

    @Override
    public void submitItem(
            PoseStack poseStack,
            ItemDisplayContext displayContext,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            int[] tintLayers,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType foilType) {}

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {}

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {}
}
//#else
//$$ /**
//$$  * 26.1.2 侧的空壳：该版本的提交节点接口形态不同（submitModelPart / submitParticleGroup 等签名都不一样），
//$$  * 本轮只在 26.2 上恢复抓面，所以这里只留一个可编译的同名类，供两个版本共用的源码引用点解析。
//$$  */
//$$ public final class CapturingSubmitNodeCollector {
//$$     private CapturingSubmitNodeCollector() {}
//$$ }
//#endif
