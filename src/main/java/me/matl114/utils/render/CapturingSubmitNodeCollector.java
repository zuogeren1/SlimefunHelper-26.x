package me.matl114.utils.render;

//#if MC >= 260200
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.UVPair;
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
import org.joml.Vector3fc;

/**
 * 捕获式 {@link SubmitNodeCollector}：把实体渲染器的提交转成「面」而不是顶点缓冲。
 *
 * <p>26.2 的实体几何没有可替换的 {@code VertexConsumer} 入口了：{@code EntityRenderDispatcher#submit}
 * 只把内容塞进 {@link SubmitNodeCollector}，真正的顶点要等到 {@code ModelFeatureRenderer#prepareModel}
 * 才由 {@code model.renderToBuffer(poseStack, buffer, ...)} 写入 GPU（{@code ModelFeatureRenderer.java:28-40}）。
 * 所以这里自建一个 collector 接过提交，在 {@link #submitModel} 里把同一条渲染路径再走一遍，
 * 只是把顶点交给 {@link CapturingVertexConsumer} 抄下来。
 *
 * <p>除 {@link #submitModel}、{@link #submitCustomGeometry} 与 {@link #submitItem} 外的提交（名牌、影子、粒子、方块…）
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

    /**
     * 物品（掉落物 / 展示框 / 手持 / 物品展示实体）走的是另一条提交：进到这里时模型已经烘成 {@link BakedQuad}
     * （{@code ItemStackRenderState.LayerRenderState#submit} 把 quads 直接交出去），物品的本地变换也已经压进 pose，
     * 所以按 {@code ItemFeatureRenderer#prepareMainSubmit}（{@code ItemFeatureRenderer.java:60-70}）的读法
     * 把四边形逐顶点喂进捕获式 buffer：位置乘 pose，uv 用 quad 自带的图集坐标。
     *
     * <p>贴图按 quad 的 sprite 取图集：物品的各层可能分别落在 items / blocks 图集上，按面取才不会串图；
     * 图集 id 配合 atlas 空间 uv 与本仓库 {@code DrawContext_v1_21_11#drawGuiTextureQuad} 的用法一致。
     *
     * <p>不按 {@code outlineColor} 过滤：{@code SubmitNodeCollection#submitItem} 只会在这一次调用里
     * 把同一份 quads 再塞进 outline 阶段，并不是第二次提交；滤掉的话发光实体手上的物品会整块消失。
     */
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
        if (quads.isEmpty()) {
            return;
        }
        PoseStack.Pose pose = poseStack.last();
        Identifier quadTexture = quads.getFirst().materialInfo().sprite().atlasLocation();
        CapturingVertexConsumer consumer = new CapturingVertexConsumer(quadTexture, this::emit);
        for (BakedQuad quad : quads) {
            Identifier texture = quad.materialInfo().sprite().atlasLocation();
            if (!Objects.equals(texture, quadTexture)) {
                consumer.finish();
                consumer = new CapturingVertexConsumer(texture, this::emit);
                quadTexture = texture;
            }
            for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
                Vector3fc position = quad.position(vertex);
                long packedUv = quad.packedUV(vertex);
                consumer.addVertex(pose, position.x(), position.y(), position.z())
                        .setUv(UVPair.unpackU(packedUv), UVPair.unpackV(packedUv));
            }
        }
        consumer.finish();
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {}

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {}
}
//#else
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import java.util.List;
//$$ import java.util.Objects;
//$$ import java.util.function.BiConsumer;
//$$ import net.minecraft.client.gui.Font;
//$$ import net.minecraft.client.model.Model;
//$$ import net.minecraft.client.model.geom.ModelPart;
//$$ import net.minecraft.client.model.geom.builders.UVPair;
//$$ import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
//$$ import net.minecraft.client.renderer.SubmitNodeCollector;
//$$ import net.minecraft.client.renderer.block.MovingBlockRenderState;
//$$ import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
//$$ import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
//$$ import net.minecraft.client.renderer.entity.state.EntityRenderState;
//$$ import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
//$$ import net.minecraft.client.renderer.item.ItemStackRenderState;
//$$ import net.minecraft.client.renderer.rendertype.RenderType;
//$$ import net.minecraft.client.renderer.state.level.CameraRenderState;
//$$ import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//$$ import net.minecraft.client.resources.model.geometry.BakedQuad;
//$$ import net.minecraft.network.chat.Component;
//$$ import net.minecraft.resources.Identifier;
//$$ import net.minecraft.util.FormattedCharSequence;
//$$ import net.minecraft.util.Unit;
//$$ import net.minecraft.world.item.ItemDisplayContext;
//$$ import net.minecraft.world.phys.Vec3;
//$$ import org.joml.Quaternionf;
//$$ import org.joml.Vector3fc;
//$$
//$$ /**
//$$  * 捕获式 {@link SubmitNodeCollector}：把实体渲染器的提交转成「面」而不是顶点缓冲。
//$$  *
//$$  * <p>该版本的实体几何同样没有可替换的 {@code VertexConsumer} 入口：{@code EntityRenderDispatcher#submit}
//$$  * 只把内容塞进 {@link SubmitNodeCollector}，真正的顶点要等到 {@code ModelFeatureRenderer#renderModel}
//$$  * 才由 {@code model.renderToBuffer(poseStack, buffer, ...)} 写入 GPU（{@code ModelFeatureRenderer.java:87-126}）。
//$$  * 所以这里自建一个 collector 接过提交，在 {@link #submitModel} 里把同一条渲染路径再走一遍，
//$$  * 只是把顶点交给 {@link CapturingVertexConsumer} 抄下来。
//$$  *
//$$  * <p>该版本的 {@link OrderedSubmitNodeCollector} 与 26.2 的成员集不同：名牌多一个到相机的距离平方、
//$$  * {@code submitModelPart} 是抽象方法、粒子按 {@code submitParticleGroup} 提交、没有 gizmo / shapeOutline 一类的提交；
//$$  * 差异逐条见对应覆写方法。
//$$  *
//$$  * <p>除 {@link #submitModel}、{@link #submitModelPart}、{@link #submitCustomGeometry} 与 {@link #submitItem} 外的提交
//$$  * （名牌、影子、粒子、方块…）都与「实体模型的面」无关，全部空实现。
//$$  */
//$$ public final class CapturingSubmitNodeCollector implements SubmitNodeCollector {
//$$     /** 每抓到一个面就回调一次（面, 颜色）。 */
//$$     private final BiConsumer<EntityModelCollector.CapturedQuad, Integer> sink;
//$$
//$$     /** 当前实体的颜色（由 {@link #begin} 设置）。 */
//$$     private int color;
//$$
//$$     /** 当前实体本体的贴图；抓完第一个模型后清空，见 {@link #submitModel}。 */
//$$     private Identifier texture;
//$$
//$$     public CapturingSubmitNodeCollector(BiConsumer<EntityModelCollector.CapturedQuad, Integer> sink) {
//$$         this.sink = sink;
//$$     }
//$$
//$$     /** 开始抓一个实体：颜色来自收集器条目，贴图由调用方按实体渲染器解析（可能为空）。 */
//$$     public void begin(int color, Identifier texture) {
//$$         this.color = color;
//$$         this.texture = texture;
//$$     }
//$$
//$$     /** 全部提交都要经过它，但抓面只关心顺序无关的原始几何，直接返回自己。 */
//$$     @Override
//$$     public OrderedSubmitNodeCollector order(int order) {
//$$         return this;
//$$     }
//$$
//$$     /**
//$$      * 抓到的是真模型面：这里复刻 {@code ModelFeatureRenderer#renderModel} 的两步 —— 先 {@code model.setupAnim(state)}，
//$$      * 再 {@code model.renderToBuffer(...)}，只是把 buffer 换成捕获式的，所以顶点位置/uv 与真正画出来时完全一致。
//$$      *
//$$      * <p>描边遍（{@code renderType.isOutline()}）是同一份几何的第二份，抓了会让描边收集器重复找边，直接丢掉。
//$$      */
//$$     @Override
//$$     public <S> void submitModel(
//$$             Model<? super S> model,
//$$             S state,
//$$             PoseStack poseStack,
//$$             RenderType renderType,
//$$             int lightCoords,
//$$             int overlayCoords,
//$$             int tintedColor,
//$$             TextureAtlasSprite sprite,
//$$             int outlineColor,
//$$             ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
//$$         if (renderType.isOutline()) {
//$$             return;
//$$         }
//$$         // 只有第一个模型带贴图：LivingEntityRenderer 先提交本体（LivingEntityRenderer.java:94），
//$$         // 之后才是护甲/附加层；附加层的贴图与本体不同，套本体的贴图重画会糊，按纯色面处理更干净。
//$$         Identifier quadTexture = this.texture;
//$$         this.texture = null;
//$$         CapturingVertexConsumer consumer = new CapturingVertexConsumer(quadTexture, this::emit);
//$$         model.setupAnim(state);
//$$         model.renderToBuffer(poseStack, consumer, lightCoords, overlayCoords, tintedColor);
//$$         consumer.finish();
//$$     }
//$$
//$$     /**
//$$      * 该版本把单独一个 {@link ModelPart}（第一人称手臂等）当作独立提交，渲染时走
//$$      * {@code ModelPartFeatureRenderer} 的 {@code modelPart.render(...)}；这里包成 {@code Model.Simple}
//$$      * 交给 {@link #submitModel}，与 26.2 的 default 实现同路，两种提交共用同一条抓取路径。
//$$      */
//$$     @Override
//$$     public void submitModelPart(
//$$             ModelPart modelPart,
//$$             PoseStack poseStack,
//$$             RenderType renderType,
//$$             int lightCoords,
//$$             int overlayCoords,
//$$             TextureAtlasSprite sprite,
//$$             boolean sheeted,
//$$             boolean hasFoil,
//$$             int tintedColor,
//$$             ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
//$$             int outlineColor) {
//$$         submitModel(
//$$                 new Model.Simple(modelPart, ignored -> renderType),
//$$                 Unit.INSTANCE,
//$$                 poseStack,
//$$                 renderType,
//$$                 lightCoords,
//$$                 overlayCoords,
//$$                 tintedColor,
//$$                 sprite,
//$$                 outlineColor,
//$$                 crumblingOverlay);
//$$     }
//$$
//$$     /**
//$$      * 自定义几何（光束 / 闪电 / 画…）也走 {@code CustomGeometryRenderer(PoseStack.Pose, VertexConsumer)}，
//$$      * 这里用同一个 pose 喂捕获式的 buffer 即可。
//$$      *
//$$      * <p>只接四边形拓扑：线段（钓鱼线）按四顶点切分会串成假面。该版本没有 {@code PrimitiveTopology}，
//$$      * 拓扑从 {@code RenderType#mode()} 取。
//$$      */
//$$     @Override
//$$     public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
//$$         if (renderType.isOutline() || renderType.mode() != VertexFormat.Mode.QUADS) {
//$$             return;
//$$         }
//$$         CapturingVertexConsumer consumer = new CapturingVertexConsumer(this.texture, this::emit);
//$$         customGeometryRenderer.render(poseStack.last(), consumer);
//$$         consumer.finish();
//$$     }
//$$
//$$     private void emit(EntityModelCollector.CapturedQuad quad) {
//$$         sink.accept(quad, color);
//$$     }
//$$
//$$     @Override
//$$     public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {}
//$$
//$$     /** 与 26.2 只差一个 {@code distanceToCameraSq}：该版本由提交方给出到相机的距离平方。 */
//$$     @Override
//$$     public void submitNameTag(
//$$             PoseStack poseStack,
//$$             Vec3 nameTagAttachment,
//$$             int offset,
//$$             Component name,
//$$             boolean seeThrough,
//$$             int lightCoords,
//$$             double distanceToCameraSq,
//$$             CameraRenderState camera) {}
//$$
//$$     @Override
//$$     public void submitText(
//$$             PoseStack poseStack,
//$$             float x,
//$$             float y,
//$$             FormattedCharSequence string,
//$$             boolean dropShadow,
//$$             Font.DisplayMode displayMode,
//$$             int lightCoords,
//$$             int color,
//$$             int backgroundColor,
//$$             int outlineColor) {}
//$$
//$$     @Override
//$$     public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {}
//$$
//$$     @Override
//$$     public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {}
//$$
//$$     /** 该版本的移动方块提交不带轮廓颜色；它与实体模型面无关。 */
//$$     @Override
//$$     public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState) {}
//$$
//$$     @Override
//$$     public void submitBlockModel(
//$$             PoseStack poseStack,
//$$             RenderType renderType,
//$$             List<BlockStateModelPart> parts,
//$$             int[] tintLayers,
//$$             int lightCoords,
//$$             int overlayCoords,
//$$             int outlineColor) {}
//$$
//$$     /** 该版本传方块模型 + 种子（parts 在方块渲染阶段展开），不是烘好的 parts 列表。 */
//$$     @Override
//$$     public void submitBreakingBlockModel(PoseStack poseStack, BlockStateModel model, long seed, int progress) {}
//$$
//$$     /**
//$$      * 物品（掉落物 / 展示框 / 手持 / 物品展示实体）走的是另一条提交：进到这里时模型已经烘成 {@link BakedQuad}
//$$      * （{@code ItemStackRenderState.LayerRenderState#submit} 把 quads 直接交出去），物品的本地变换也已经压进 pose，
//$$      * 所以按 {@code ItemFeatureRenderer#renderItem} 的读法把四边形逐顶点喂进捕获式 buffer：位置乘 pose，
//$$      * uv 用 quad 自带的图集坐标。
//$$      *
//$$      * <p>贴图按 quad 的 sprite 取图集：物品的各层可能分别落在 items / blocks 图集上，按面取才不会串图；
//$$      * 图集 id 配合 atlas 空间 uv 与本仓库 {@code DrawContext_v1_21_11#drawGuiTextureQuad} 的用法一致。
//$$      *
//$$      * <p>不按 {@code outlineColor} 过滤：{@code SubmitNodeCollection#submitItem} 只会在这一次调用里
//$$      * 把同一份 quads 再塞进 outline 阶段，并不是第二次提交；滤掉的话发光实体手上的物品会整块消失。
//$$      */
//$$     @Override
//$$     public void submitItem(
//$$             PoseStack poseStack,
//$$             ItemDisplayContext displayContext,
//$$             int lightCoords,
//$$             int overlayCoords,
//$$             int outlineColor,
//$$             int[] tintLayers,
//$$             List<BakedQuad> quads,
//$$             ItemStackRenderState.FoilType foilType) {
//$$         if (quads.isEmpty()) {
//$$             return;
//$$         }
//$$         PoseStack.Pose pose = poseStack.last();
//$$         Identifier quadTexture = quads.getFirst().materialInfo().sprite().atlasLocation();
//$$         CapturingVertexConsumer consumer = new CapturingVertexConsumer(quadTexture, this::emit);
//$$         for (BakedQuad quad : quads) {
//$$             Identifier texture = quad.materialInfo().sprite().atlasLocation();
//$$             if (!Objects.equals(texture, quadTexture)) {
//$$                 consumer.finish();
//$$                 consumer = new CapturingVertexConsumer(texture, this::emit);
//$$                 quadTexture = texture;
//$$             }
//$$             for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
//$$                 Vector3fc position = quad.position(vertex);
//$$                 long packedUv = quad.packedUV(vertex);
//$$                 consumer.addVertex(pose, position.x(), position.y(), position.z())
//$$                         .setUv(UVPair.unpackU(packedUv), UVPair.unpackV(packedUv));
//$$             }
//$$         }
//$$         consumer.finish();
//$$     }
//$$
//$$     @Override
//$$     public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer particleGroupRenderer) {}
//$$ }
//#endif
