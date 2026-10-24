package me.matl114.versioned.impl;

//#if MC >= 260200
import com.mojang.blaze3d.PrimitiveTopology;
//#else
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//#endif
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.awt.*;
import java.util.List;
import java.util.function.Function;
import me.matl114.plugins.internal.MainPlugin;
import me.matl114.renderer.TextRenderers;
import me.matl114.utils.render.ColorQuad;
import me.matl114.utils.render.Quad;
import me.matl114.utils.render.UV;
import me.matl114.versioned.api.VRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Minecraft 26.2 实现。
 *
 * <p>26.1 起渲染模型发生根本变化：{@code MultiBufferSource} / {@code OutlineBufferSource} /
 * {@code DepthTestFunction} 体系被移除，{@code RenderBuffers} 也不再提供 {@code bufferSource()}。
 * 新的模型是「两阶段提交」：
 *
 * <ol>
 *   <li>把绘制内容通过 {@link SubmitNodeCollector} 提交（{@code submitCustomGeometry} /
 *       {@code submitText} / {@code submitItem} 等），期间只记录、不立即绘制；
 *   <li>由 {@code FeatureRenderDispatcher} 统一排序并渲染。
 * </ol>
 *
 * <p>因此本类不再持有全局的 buffer source，而是改为：由渲染入口（见 {@code WorldRendererEvents}
 * 注入 {@code LevelRenderer#submitFeatures} 的 RETURN）通过 {@link #beginSubmit} 设置当前
 * {@link SubmitNodeCollector} 与 {@link PoseStack}，本类在回调中把几何提交进去。
 */
public class Render_v1_21_11 implements VRender, VRender.WrapRenderOperation {

    private static final Minecraft mc = Minecraft.getInstance();

    /** 26.2 无「无深度测试」枚举，用 ALWAYS_PASS + 不写深度表达等价语义。 */
    private static final DepthStencilState NO_DEPTH_TEST_STATE = new DepthStencilState(CompareOp.ALWAYS_PASS, false);

    // ------------------------------------------------------------------
    // 提交上下文：由渲染入口设置，createLayer 期间有效
    // ------------------------------------------------------------------

    private static SubmitNodeCollector currentCollector;
    private static PoseStack currentPoseStack;

    /** 由渲染入口在开始提交前调用。 */
    public static void beginSubmit(SubmitNodeCollector collector, PoseStack poseStack) {
        currentCollector = collector;
        currentPoseStack = poseStack;
    }

    /** 由渲染入口在提交结束后调用，清理上下文。 */
    public static void endSubmit() {
        currentCollector = null;
        currentPoseStack = null;
    }

    public static boolean isSubmitting() {
        return currentCollector != null;
    }

    public static SubmitNodeCollector getCollector() {
        return currentCollector;
    }

    public static PoseStack getPoseStack() {
        return currentPoseStack;
    }

    // ------------------------------------------------------------------
    // 渲染管线 / 渲染层定义
    // ------------------------------------------------------------------

    public static final RenderPipeline DEBUG_LINES =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Identifier.tryParse("slimefunhelper:pipeline/debug_lines"))
                    .withDepthStencilState(NO_DEPTH_TEST_STATE)
                    .build());

    public static final RenderType LINES = RenderType.create(
            "slimefunhelper:debug_lines",
            RenderSetup.builder(DEBUG_LINES)
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    // 26.2: 世界里的线框要画到主帧缓冲；之前设成 ITEM_ENTITY_TARGET
                    // 会被提交到物品/实体目标，导致世界中的方框完全不可见
                    .setOutputTarget(OutputTarget.MAIN_TARGET)
                    // 注意：LINES / LINE_STRIP 拓扑不能开 sortOnUpload ——
                    // 26.2 的 StagedVertexBuffer.appendDraw 会直接抛
                    // IllegalArgumentException: Cannot sort draw with LINES
                    .createRenderSetup());

    public static final RenderPipeline DEBUG_LINES_STRIP =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Identifier.tryParse("slimefunhelper:pipeline/debug_lines_strip"))
                    .withDepthStencilState(NO_DEPTH_TEST_STATE)
                    //#if MC >= 260200
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH)
                    .withPrimitiveTopology(PrimitiveTopology.DEBUG_LINE_STRIP)
                    //#else
                    //$$ .withVertexFormat(
                    //$$         DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, VertexFormat.Mode.DEBUG_LINE_STRIP)
                    //#endif
                    .withCull(false)
                    .build());

    @ApiStatus.Experimental
    public static final RenderType LINES_STRIP = RenderType.create(
            "slimefunhelper:debug_lines_strip",
            RenderSetup.builder(DEBUG_LINES_STRIP)
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    // 26.2: 世界里的线框要画到主帧缓冲；之前设成 ITEM_ENTITY_TARGET
                    // 会被提交到物品/实体目标，导致世界中的方框完全不可见
                    .setOutputTarget(OutputTarget.MAIN_TARGET)
                    // 注意：LINES / LINE_STRIP 拓扑不能开 sortOnUpload ——
                    // 26.2 的 StagedVertexBuffer.appendDraw 会直接抛
                    // IllegalArgumentException: Cannot sort draw with LINES
                    .createRenderSetup());

    public static final RenderPipeline DEBUG_QUADS =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.tryParse("slimefunhelper:pipeline/debug_quads"))
                    .withDepthStencilState(NO_DEPTH_TEST_STATE)
                    .build());

    public static final RenderPipeline DEBUG_RECTS =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.tryParse("slimefunhelper:pipeline/debug_rects"))
                    .withDepthStencilState(NO_DEPTH_TEST_STATE)
                    //#if MC >= 260200
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    //#else
                    //$$ .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
                    //#endif
                    .build());

    public static final RenderType RECTS = RenderType.create(
            "slimefunhelper:debug_rects",
            RenderSetup.builder(DEBUG_RECTS).sortOnUpload().createRenderSetup());

    public static final RenderPipeline DEBUG_RECTS_STRIP =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.tryParse("slimefunhelper:pipeline/debug_rects"))
                    .withDepthStencilState(NO_DEPTH_TEST_STATE)
                    //#if MC >= 260200
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
                    //#else
                    //$$ .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLE_STRIP)
                    //#endif
                    .build());

    public static final RenderType RECTS_STRIP = RenderType.create(
            "slimefunhelper:debug_rects",
            RenderSetup.builder(DEBUG_RECTS_STRIP).sortOnUpload().createRenderSetup());

    public static final RenderType QUADS = RenderType.create(
            "slimefunhelper:debug_quads",
            RenderSetup.builder(DEBUG_QUADS).sortOnUpload().createRenderSetup());

    public static final RenderPipeline DEBUG_QUADS_NO_CULL =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.tryParse("slimefunhelper:pipeline/debug_quads"))
                    .withDepthStencilState(NO_DEPTH_TEST_STATE)
                    .withCull(false)
                    .build());

    public static final RenderType QUADS_NO_CULL = RenderType.create(
            "slimefunhelper:debug_quads",
            RenderSetup.builder(DEBUG_QUADS_NO_CULL).sortOnUpload().createRenderSetup());

    public static final RenderPipeline DEBUG_GUI_3D =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(Identifier.tryParse("slimefunhelper:pipeline/gui_3d"))
                    .withCull(false)
                    .build());

    public static final RenderType GUI_3D = RenderType.create(
            "slimefunhelper:gui_3d",
            RenderSetup.builder(DEBUG_GUI_3D).sortOnUpload().createRenderSetup());

    public static RenderPipeline DEBUG_GUI_TEXTURE_3D =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(Identifier.tryParse("slimefun:pipeline/gui_textured_3d"))
                    .withCull(false)
                    .build());

    public static Function<Identifier, RenderType> GUI_TEXTURE_3D_FACTORY = Util.memoize((identifier -> {
        return RenderType.create(
                "slimefunhelper:gui_textured_3d/" + identifier.toString(),
                RenderSetup.builder(DEBUG_GUI_TEXTURE_3D)
                        .withTexture("Sampler0", identifier)
                        .createRenderSetup());
    }));

    public static Function<Identifier, RenderType> GUI_SPRITE_TEXTURE_3D_FACTORY = Util.memoize((identifier -> {
        return RenderType.create(
                "slimefunhelper:gui_textured_3d/" + identifier.toString(),
                RenderSetup.builder(DEBUG_GUI_TEXTURE_3D)
                        .withTexture("Sampler0", identifier)
                        .createRenderSetup());
    }));

    /**
     * 实体高亮管线：拿实体贴图铺在模型面上，不做深度测试。
     * 片元着色器见 {@code assets/slimefunhelper/shaders/core/entity_highlight.fsh}。
     */
    public static final RenderPipeline ENTITY_HIGHLIGHT =
            RenderPipelines.register(RenderPipeline.builder(RenderPipelines.OUTLINE_SNIPPET)
                    .withLocation(MainPlugin.identifier("pipeline/entity_highlight"))
                    .withFragmentShader(MainPlugin.identifier("core/entity_highlight"))
                    .withDepthStencilState(NO_DEPTH_TEST_STATE)
                    .withCull(false)
                    .build());

    /**
     * 实体高亮层：直接用实体贴图铺在它的模型面上。
     *
     * <p>上游原本自己注册一条 {@code OUTLINE} 片段 + 无深度测试的管线；26.2 里
     * {@code RenderPipelines} 的 snippet 全是 private，独立管线需要 AW 才能拼出来，
     * 这里先用公开的 {@link RenderPipelines#OUTLINE_NO_CULL}，代价是保留深度测试。
     */
    public static Function<Identifier, RenderType> ENTITY_HIGHLIGHT_FACTORY = Util.memoize((identifier -> {
        return RenderType.create(
                MainPlugin.ID + ":entity_highlight/" + identifier.toString(),
                RenderSetup.builder(ENTITY_HIGHLIGHT)
                        .withTexture("Sampler0", identifier)
                        .createRenderSetup());
    }));

    // ------------------------------------------------------------------
    // 层提交
    // ------------------------------------------------------------------

    private void createLayer(RenderType layer, RenderCallback callback) {
        SubmitNodeCollector collector = currentCollector;
        PoseStack poseStack = currentPoseStack;
        if (collector == null || poseStack == null) {
            // 不在渲染提交阶段（例如 GUI 上下文），安全跳过
            return;
        }
        collector.submitCustomGeometry(poseStack, layer, (pose, buffer) -> callback.draw(this, buffer));
    }

    public void createLinesLayer(RenderCallback callback) {
        createLayer(LINES, callback);
    }

    @Override
    public void createLineStripLayer(RenderCallback callback) {
        createLayer(LINES_STRIP, callback);
    }

    public void createQuadsLayer(RenderCallback callback, boolean hasCulling) {
        createLayer(hasCulling ? QUADS : QUADS_NO_CULL, callback);
    }

    public void createTrianglesLayer(RenderCallback callback, boolean hasCulling) {
        createLayer(RECTS, callback);
    }

    @Override
    public void createTriangleStripLayer(RenderCallback callback, boolean hasCulling) {
        createLayer(RECTS_STRIP, callback);
    }

    public void createGuiTexturedLayer(Identifier path, RenderCallback callback) {
        createLayer(GUI_TEXTURE_3D_FACTORY.apply(path), callback);
    }

    public void createSpriteTexturedLayer(TextureAtlasSprite sprite, RenderCallback callback) {
        createLayer(GUI_SPRITE_TEXTURE_3D_FACTORY.apply(sprite.atlasLocation()), callback);
    }

    @Override
    public void createEntityHighlightLayer(Identifier path, RenderCallback callback) {
        createLayer(ENTITY_HIGHLIGHT_FACTORY.apply(path), callback);
    }

    public void createGuiLayer(RenderCallback callback) {
        createLayer(GUI_3D, callback);
    }

    // ------------------------------------------------------------------
    // 顶点绘制（VertexConsumer 在 26.2 中仍存在，逻辑保持不变）
    // ------------------------------------------------------------------

    public void drawLines(PoseStack matrixStack, VertexConsumer consumer, List<Vec3> points, int color) {
        Matrix4f entry = matrixStack.last().pose();
        for (var i = 1; i < points.size(); i++) {
            Vector3f prev = points.get(i - 1).toVector3f();
            Vector3f next = points.get(i).toVector3f();
            Vector3f normal = new Vector3f(next).sub(prev).normalize();
            consumer.addVertex(entry, prev.x, prev.y, prev.z)
                    .setColor(color)
                    .setNormal(normal.x, normal.y, normal.z)
                    .setLineWidth(2);
            consumer.addVertex(entry, next.x, next.y, next.z)
                    .setColor(color)
                    .setNormal(normal.x, normal.y, normal.z)
                    .setLineWidth(2);
        }
    }

    public void drawLine(PoseStack matrixStack, VertexConsumer consumer, Vec3 prevV, Vec3 nextV, int color) {
        Vector3f prev = prevV.toVector3f();
        Vector3f next = nextV.toVector3f();
        Matrix4f entry = matrixStack.last().pose();
        Vector3f normal = new Vector3f(next).sub(prev).normalize();
        consumer.addVertex(entry, prev.x, prev.y, prev.z)
                .setColor(color)
                .setNormal(normal.x, normal.y, normal.z)
                .setLineWidth(1);
        consumer.addVertex(entry, next.x, next.y, next.z)
                .setColor(color)
                .setNormal(normal.x, normal.y, normal.z)
                .setLineWidth(1);
    }

    public void drawOutlinedBox(
            PoseStack matrix4fStack, VertexConsumer bufferBuilder, Vec3 from, Vec3 to, int cachedRenderColor) {
        Matrix4f matrix4f = matrix4fStack.last().pose();
        float minX = (float) from.x();
        float minY = (float) from.y();
        float minZ = (float) from.z();
        float maxX = (float) to.x();
        float maxY = (float) to.y();
        float maxZ = (float) to.z();

        bufferBuilder
                .addVertex(matrix4f, minX, minY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(1, 0, 0)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, maxX, minY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(1, 0, 0)
                .setLineWidth(2);

        bufferBuilder
                .addVertex(matrix4f, maxX, minY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 0, 1)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, maxX, minY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 0, 1)
                .setLineWidth(2);

        bufferBuilder
                .addVertex(matrix4f, minX, minY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(1, 0, 0)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, maxX, minY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(1, 0, 0)
                .setLineWidth(2);

        bufferBuilder
                .addVertex(matrix4f, minX, minY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 0, 1)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, minX, minY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 0, 1)
                .setLineWidth(2);

        bufferBuilder
                .addVertex(matrix4f, minX, minY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 1, 0)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, minX, maxY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 1, 0)
                .setLineWidth(2);

        bufferBuilder
                .addVertex(matrix4f, maxX, minY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 1, 0)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, maxX, maxY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 1, 0)
                .setLineWidth(2);

        bufferBuilder
                .addVertex(matrix4f, maxX, minY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 1, 0)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, maxX, maxY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 1, 0)
                .setLineWidth(2);

        bufferBuilder
                .addVertex(matrix4f, minX, minY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 1, 0)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, minX, maxY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 1, 0)
                .setLineWidth(2);

        bufferBuilder
                .addVertex(matrix4f, minX, maxY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(1, 0, 0)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, maxX, maxY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(1, 0, 0)
                .setLineWidth(2);

        bufferBuilder
                .addVertex(matrix4f, maxX, maxY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 0, 1)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, maxX, maxY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 0, 1)
                .setLineWidth(2);

        bufferBuilder
                .addVertex(matrix4f, minX, maxY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(1, 0, 0)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, maxX, maxY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(1, 0, 0)
                .setLineWidth(2);

        bufferBuilder
                .addVertex(matrix4f, minX, maxY, minZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 0, 1)
                .setLineWidth(2);
        bufferBuilder
                .addVertex(matrix4f, minX, maxY, maxZ)
                .setColor(cachedRenderColor)
                .setNormal(0, 0, 1)
                .setLineWidth(2);
    }

    public void drawSolidBoxQuad(
            PoseStack matrixStack, VertexConsumer bufferBuilder, Vec3 from, Vec3 to, int cachedRenderColor) {
        Matrix4f matrix = matrixStack.last().pose();
        float minX = (float) from.x;
        float minY = (float) from.y;
        float minZ = (float) from.z;
        float maxX = (float) to.x;
        float maxY = (float) to.y;
        float maxZ = (float) to.z;

        bufferBuilder.addVertex(matrix, minX, minY, minZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, maxX, minY, minZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, maxX, minY, maxZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, minX, minY, maxZ).setColor(cachedRenderColor);

        bufferBuilder.addVertex(matrix, minX, maxY, minZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, minX, maxY, maxZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, maxX, maxY, maxZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, maxX, maxY, minZ).setColor(cachedRenderColor);

        bufferBuilder.addVertex(matrix, minX, minY, minZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, minX, maxY, minZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, maxX, maxY, minZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, maxX, minY, minZ).setColor(cachedRenderColor);

        bufferBuilder.addVertex(matrix, maxX, minY, minZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, maxX, maxY, minZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, maxX, maxY, maxZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, maxX, minY, maxZ).setColor(cachedRenderColor);

        bufferBuilder.addVertex(matrix, minX, minY, maxZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, maxX, minY, maxZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, maxX, maxY, maxZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, minX, maxY, maxZ).setColor(cachedRenderColor);

        bufferBuilder.addVertex(matrix, minX, minY, minZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, minX, minY, maxZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, minX, maxY, maxZ).setColor(cachedRenderColor);
        bufferBuilder.addVertex(matrix, minX, maxY, minZ).setColor(cachedRenderColor);
    }

    public void drawSolidBoxTriangle(
            PoseStack matrixStack, VertexConsumer bufferBuilder, Vec3 from, Vec3 to, int cachedRenderColor) {
        Matrix4f matrix = matrixStack.last().pose();

        float minX = (float) Math.min(from.x, to.x);
        float minY = (float) Math.min(from.y, to.y);
        float minZ = (float) Math.min(from.z, to.z);
        float maxX = (float) Math.max(from.x, to.x);
        float maxY = (float) Math.max(from.y, to.y);
        float maxZ = (float) Math.max(from.z, to.z);

        Vec3 v000 = new Vec3(minX, minY, minZ);
        Vec3 v100 = new Vec3(maxX, minY, minZ);
        Vec3 v010 = new Vec3(minX, maxY, minZ);
        Vec3 v110 = new Vec3(maxX, maxY, minZ);
        Vec3 v001 = new Vec3(minX, minY, maxZ);
        Vec3 v101 = new Vec3(maxX, minY, maxZ);
        Vec3 v011 = new Vec3(minX, maxY, maxZ);
        Vec3 v111 = new Vec3(maxX, maxY, maxZ);

        // 底面 (-Y)
        addTriangle(matrix, bufferBuilder, v000, v100, v101, cachedRenderColor, 0, -1, 0);
        addTriangle(matrix, bufferBuilder, v000, v101, v001, cachedRenderColor, 0, -1, 0);
        // 顶面 (+Y)
        addTriangle(matrix, bufferBuilder, v010, v011, v111, cachedRenderColor, 0, 1, 0);
        addTriangle(matrix, bufferBuilder, v010, v111, v110, cachedRenderColor, 0, 1, 0);
        // 前面 (+Z)
        addTriangle(matrix, bufferBuilder, v001, v101, v111, cachedRenderColor, 0, 0, 1);
        addTriangle(matrix, bufferBuilder, v001, v111, v011, cachedRenderColor, 0, 0, 1);
        // 后面 (-Z)
        addTriangle(matrix, bufferBuilder, v000, v010, v110, cachedRenderColor, 0, 0, -1);
        addTriangle(matrix, bufferBuilder, v000, v110, v100, cachedRenderColor, 0, 0, -1);
        // 左面 (-X)
        addTriangle(matrix, bufferBuilder, v000, v001, v011, cachedRenderColor, -1, 0, 0);
        addTriangle(matrix, bufferBuilder, v000, v011, v010, cachedRenderColor, -1, 0, 0);
        // 右面 (+X)
        addTriangle(matrix, bufferBuilder, v100, v110, v111, cachedRenderColor, 1, 0, 0);
        addTriangle(matrix, bufferBuilder, v100, v111, v101, cachedRenderColor, 1, 0, 0);
    }

    private void addTriangle(
            Matrix4f matrix,
            VertexConsumer consumer,
            Vec3 v1,
            Vec3 v2,
            Vec3 v3,
            int color,
            float nx,
            float ny,
            float nz) {
        consumer.addVertex(matrix, (float) v1.x(), (float) v1.y(), (float) v1.z())
                .setColor(color);

        consumer.addVertex(matrix, (float) v2.x(), (float) v2.y(), (float) v2.z())
                .setColor(color);
        consumer.addVertex(matrix, (float) v3.x(), (float) v3.y(), (float) v3.z())
                .setColor(color);
    }

    public void drawQuad(PoseStack matrix4f, VertexConsumer bufferBuilder, Quad quad, ColorQuad colorQuad) {
        var matrix4 = matrix4f.last();
        for (int idx = 0; idx < 4; idx++) {
            var vec3d = quad.get(idx);
            int color = colorQuad.get(idx);
            bufferBuilder
                    .addVertex(matrix4, (float) vec3d.x, (float) vec3d.y, (float) vec3d.z)
                    .setColor(color);
        }
    }

    public void drawTexturedQuad(PoseStack stack, VertexConsumer vertex, Quad quad, UV uv, ColorQuad colorQuad) {
        var entry = stack.last();
        for (var i = 0; i < 4; ++i) {
            Vec3 vec3d = quad.get(i);
            float u = uv.getU(i);
            float v = uv.getV(i);
            int color = colorQuad.get(i);
            vertex.addVertex(entry, (float) vec3d.x, (float) vec3d.y, (float) vec3d.z)
                    .setUv(u, v)
                    .setColor(color);
        }
    }

    // ------------------------------------------------------------------
    // 文字 / 物品（26.2 改为提交到 collector）
    // ------------------------------------------------------------------

    @Override
    public void drawTextCameraCoord(
            FormattedCharSequence orderedText,
            PoseStack stack,
            Vec3 vec3d,
            int displayPositionFlag,
            Color color,
            TextDisplay displayInfo) {
        SubmitNodeCollector collector = currentCollector;
        if (collector == null) {
            return;
        }
        int xAlign = displayPositionFlag % 3;
        int yAlign = displayPositionFlag / 3;
        var textDrawer = TextRenderers.get();
        int width = textDrawer.getWidth(orderedText);
        float xStart = -((width * xAlign) / 2.0F);
        float yStart = -((textDrawer.getTextHeight() * yAlign) / 2.0F);
        stack.pushPose();
        stack.translate(vec3d.x, vec3d.y, vec3d.z);
        stack.scale(1, -1, 1);
        stack.translate(xStart, yStart, 0);
        // 26.2: font.drawInBatch(...) 被 collector.submitText(...) 取代
        collector.submitText(
                stack,
                0,
                0,
                orderedText,
                displayInfo.shadow(),
                displayInfo.layerType(),
                displayInfo.light(),
                color.getRGB(),
                displayInfo.backgroundColor(),
                0);
        stack.popPose();
    }

    @Override
    public void drawItemCameraCoord(
            ItemStack itemStack, PoseStack stack, Vec3 vec3d, ItemDisplayContext context, ItemDisplay displayInfo) {
        SubmitNodeCollector collector = currentCollector;
        if (collector == null) {
            return;
        }
        boolean bl = Vec3.ZERO.equals(vec3d);
        if (!bl) {
            stack.translate(vec3d.x, vec3d.y, vec3d.z);
        }
        ItemStackRenderState state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, itemStack, context, mc.level, null, -999);
        // 26.2: 直接提交到当前 collector，由 FeatureRenderDispatcher 统一渲染
        state.submit(stack, collector, displayInfo.light(), displayInfo.overlay(), displayInfo.outlineColor());
        if (!bl) {
            stack.translate(-vec3d.x, -vec3d.y, -vec3d.z);
        }
    }
}
