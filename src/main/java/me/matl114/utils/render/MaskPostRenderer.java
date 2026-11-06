package me.matl114.utils.render;

//#if MC >= 260300
//$$ import com.mojang.renderpearl.api.pipeline.BlendFunction;
//$$ import com.mojang.renderpearl.api.pipeline.ColorTargetState;
//$$ import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//#else
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
//#endif
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.ProjectionType;
//#if MC >= 260300
//$$ import com.mojang.renderpearl.api.commands.RenderPass;
//#else
import com.mojang.blaze3d.systems.RenderPass;
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
//#if MC >= 260300
//$$ import com.mojang.renderpearl.api.textures.FilterMode;
//#else
import com.mojang.blaze3d.textures.FilterMode;
//#endif
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.matl114.events.RenderListener;
import me.matl114.utils.Debug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
//#if MC >= 260300
//#else
import net.minecraft.client.renderer.rendertype.OutputTarget;
//#endif
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
//#if MC >= 260300
//$$ import com.mojang.renderpearl.api.GpuFormat;
//$$ import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
//$$ import net.minecraft.client.renderer.BindGroupLayouts;
//$$ import net.minecraft.client.renderer.StagedVertexBuffer;
//$$ import net.minecraft.client.renderer.rendertype.PreparedRenderType;
//#elseif MC >= 260200
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
//#else
//$$ import com.mojang.blaze3d.shaders.UniformType;
//$$ import com.mojang.blaze3d.vertex.BufferBuilder;
//$$ import com.mojang.blaze3d.vertex.ByteBufferBuilder;
//$$ import com.mojang.blaze3d.vertex.MeshData;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import java.util.OptionalInt;
//#endif

/**
 * 实体掩膜后处理器：把抓到的模型面画进一张离屏掩膜，再用全屏后处理把掩膜合成到主目标上。
 *
 * <p>这是 ESP 高亮的唯一实现。高亮要的是「屏幕空间里一圈固定像素宽的轮廓 + 一层很淡的同色罩」，
 * 而模型面是世界几何：直接用世界几何画壳会随距离变粗变细、内层面也会被画出来，只有回到屏幕空间
 * 比对掩膜才稳定。掩膜内容由 {@code mask_capture} 写入（按实体贴图 alpha 镂空，只留 1.0），
 * 合成由 {@code mask_face}（填充，alpha 随传入颜色）与 {@code mask_outline}（5x5、2.5 像素外扩，
 * 只画掩膜之外的一圈）完成。
 *
 * <p>采集与合成不在同一个时刻：采集在 2D 阶段（{@code Hud#extractRenderState} 期间，
 * 此时只能算数据），合成必须在 GUI 画完之后，所以这里留一帧队列，由渲染帧末尾的钩子统一 flush。
 * 一个实体的掩膜只服务它自己的颜色，因此队列按「实体一次」入队。
 */
public final class MaskPostRenderer {
    /**
     * 没有贴图的实色面用它走同一条掩膜管线。
     *
     * <p>{@code mask_capture} 靠采样贴图 alpha 决定镂空，纯色面没有贴图就没有 alpha 可采；
     * {@code lightning_rod_on} 是原版唯一一张每像素 (255,255,255,255) 的贴图，采样任意 uv 都是不透明白。
     */
    private static final Identifier WHITE_TEXTURE =
            Identifier.withDefaultNamespace("textures/block/lightning_rod_on.png");

    /** 掩膜清空色：全 0，alpha 为 0 表示这里没有实体。 */
    private static final Vector4f CLEAR_COLOR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);

    /** 顶点只写位置与 uv，pose 固定用单位矩阵（抓到的坐标已经是相机相对量）。 */
    private static final PoseStack IDENTITY = new PoseStack();

    /** 单个待合成面：四个顶点 + 每顶点 uv + 来源贴图（null 表示实色面）。 */
    public record Quad(Vec3[] vertices, float[] u, float[] v, Identifier texture) {}

    /** 一帧内待合成的条目：一个实体一份掩膜、一种颜色。 */
    private record Pending(List<Quad> quads, Matrix4f camera, Matrix4f projection, int faceColor, int outlineColor) {}

    private static final RenderPipeline CAPTURE = buildCapturePipeline();

    private static final RenderPipeline FACE = buildCompositePipeline("pipeline/mask_face", "core/mask_face");

    private static final RenderPipeline OUTLINE = buildCompositePipeline("pipeline/mask_outline", "core/mask_outline");

    /** 本帧收集到的掩膜请求；渲染帧末尾由 {@link #flush()} 消费。 */
    private static final List<Pending> PENDING = new ArrayList<>();

    /** 每张贴图一条渲染层：层决定采样哪张贴图，且输出目标是掩膜。 */
    private static final Map<Identifier, RenderType> LAYERS = new HashMap<>();

    private static TextureTarget target;

    private static ProjectionMatrixBuffer projectionBuffer;

//#if MC >= 260200
    /** 26.2 的即时网格：顶点先写进 staging 缓冲，upload 后按 draw 取执行信息。 */
    private static StagedVertexBuffer captureBuffer;
//#endif

    private MaskPostRenderer() {}

    /**
     * 入队一次掩膜合成：几何在 2D 阶段采集，合成留到帧末尾。
     *
     * @param quads 该实体的模型面（相机相对坐标）
     * @param faceColor 填充色，alpha 为 0 表示只画描边
     * @param outlineColor 描边色，alpha 为 0 表示只画填充
     */
    public static void draw(
            List<Quad> quads, Matrix4f camera, Matrix4f projection, int faceColor, int outlineColor) {
        if (quads.isEmpty()) return;
        if (faceColor == 0 && outlineColor == 0) return;
        // 拷贝矩阵：调用方的矩阵对象在帧内会被复用
        PENDING.add(new Pending(List.copyOf(quads), new Matrix4f(camera), new Matrix4f(projection), faceColor, outlineColor));
    }

    /**
     * 帧末尾统一合成。由渲染帧的钩子调用，必须在渲染线程。
     *
     * <p>相机矩阵在这里现取，不用入队时那份：2D 事件在 {@code extractRenderState} 阶段广播，
     * 比 {@code renderLevel} 早，那时读写到的还是上一帧的相机；帧末尾的 {@code RenderListener}
     * 矩阵才是本帧 {@code submitFeatures} 写入的那一份。
     */
    private static boolean reportedFailure = false;

    public static void flush() {
        if (PENDING.isEmpty()) return;
        List<Pending> frame = new ArrayList<>(PENDING);
        PENDING.clear();
        Matrix4f camera = RenderListener.getWorldModelViewMatrix();
        Matrix4f projection = RenderListener.getWorldBasicProjectionMatrix();
        for (Pending pending : frame) {
            Matrix4f view = camera == null ? pending.camera() : new Matrix4f(camera);
            Matrix4f proj = projection == null ? pending.projection() : new Matrix4f(projection);
            try {
                render(pending, view, proj);
            } catch (RuntimeException failure) {
                // 掩膜只是标注，任何一步失败都不该把渲染帧拖崩；下一帧会重新采集
                if (!reportedFailure) {
                    reportedFailure = true;
                    Debug.info("MaskPostRenderer: mask composite failed", failure);
                }
                break;
            }
        }
    }

    private static void render(Pending pending, Matrix4f camera, Matrix4f projection) {
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getWidth();
        int height = minecraft.getWindow().getHeight();
        if (width <= 0 || height <= 0) return;
        ensureTarget(width, height);

//#if MC >= 260200
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), CLEAR_COLOR);
//#else
//$$        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), 0);
//#endif

        // GUI 的裁剪框可能还开着；掩膜要盖住整个屏幕，先把全局 scissor 关掉、画完再恢复
        ScissorState scissor = RenderSystem.getScissorStateForRenderTypeDraws();
        boolean scissorWasEnabled = scissor.enabled();
        RenderSystem.disableScissorForRenderTypeDraws();

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();
        modelView.pushMatrix();
        modelView.set(camera);
        try {
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE);
            drawMask(pending.quads());
        } finally {
            modelView.popMatrix();
            RenderSystem.restoreProjectionMatrix();
            if (scissorWasEnabled) {
                RenderSystem.enableScissorForRenderTypeDraws(
                        scissor.x(), scissor.y(), scissor.width(), scissor.height());
            }
        }

        if (ARGB.alpha(pending.faceColor()) > 0) composite(FACE, pending.faceColor());
        if (ARGB.alpha(pending.outlineColor()) > 0) composite(OUTLINE, pending.outlineColor());
    }

    private static void ensureTarget(int width, int height) {
        if (target == null) {
//#if MC >= 260300
//$$            target = new TextureTarget("model_mask", width, height, GpuFormat.RGBA8_UNORM, null);
//#elseif MC >= 260200
            target = new TextureTarget("model_mask", width, height, false, GpuFormat.RGBA8_UNORM);
//#else
//$$            target = new TextureTarget("model_mask", width, height, false);
//#endif
            projectionBuffer = new ProjectionMatrixBuffer("model_mask");
        } else if (target.width != width || target.height != height) {
            target.resize(width, height);
        }
    }

    /** 一张贴图一条层：{@code mask_capture} 只采样 Sampler0，所以每层换贴图即可。 */
    private static RenderType layer(Identifier texture) {
        return LAYERS.computeIfAbsent(texture, id -> RenderType.create(
                "slimefunhelper:model_mask/" + id,
                RenderSetup.builder(CAPTURE)
                        .withTexture("Sampler0", id)
                        //#if MC >= 260300
                        //#else
                        .setOutputTarget(new OutputTarget("model_mask", MaskPostRenderer::outputTarget))
                        //#endif
                        .createRenderSetup()));
    }

//#if MC >= 260300
//#else
    private static RenderTarget outputTarget() {
        return target;
    }
//#endif

//#if MC >= 260300
//$$    /** 把面写进掩膜：按贴图分组建 draw，一次 upload 后在同一张 pass 里逐个执行。 */
//$$    private static void drawMask(List<Quad> quads) {
//$$        if (captureBuffer == null) {
//$$            captureBuffer = new StagedVertexBuffer(() -> "Model mask", 1536);
//$$        } else {
//$$            // 复用上一帧的 GPU 缓冲；池子用 fence 保证不再被 GPU 引用后才回收
//$$            captureBuffer.endFrame();
//$$        }
//$$
//$$        Map<Identifier, StagedVertexBuffer.Draw> draws = new LinkedHashMap<>();
//$$        for (Quad quad : quads) {
//$$            Identifier texture = textureOf(quad);
//$$            StagedVertexBuffer.Draw draw = draws.get(texture);
//$$            if (draw == null) {
//$$                draw = captureBuffer.appendDraw(DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS);
//$$                draws.put(texture, draw);
//$$            }
//$$            VertexConsumer consumer = captureBuffer.getVertexBuilder(draw);
//$$            emitQuad(consumer, quad);
//$$        }
//$$
//$$        captureBuffer.upload();
//$$        // prepare() 可能触发贴图上传，而 pass 内上传会抛
//$$        // "Close the existing render pass before presenting"；必须在开 pass 之前把整批都准备好。
//$$        Map<Identifier, PreparedRenderType> prepared = new LinkedHashMap<>();
//$$        for (Identifier texture : draws.keySet()) {
//$$            prepared.put(texture, layer(texture).prepare());
//$$        }
//$$        // 输出目标只在这一张 pass 上声明：同一个掩膜、同一批 draw，逐层各开一张 pass 会把开销翻倍
//$$        try (RenderPass pass = RenderSystem.getDevice()
//$$                .createCommandEncoder()
//$$                .createRenderPass(() -> "Model mask", target.getColorTextureView(), Optional.empty())) {
//$$            for (Map.Entry<Identifier, StagedVertexBuffer.Draw> entry : draws.entrySet()) {
//$$                StagedVertexBuffer.ExecuteInfo info = captureBuffer.getExecuteInfo(entry.getValue());
//$$                if (info == null) continue;
//$$                prepared.get(entry.getKey()).drawFromBuffer(info, pass);
//$$            }
//$$        }
//$$    }
//#elseif MC >= 260200
    /** 把面写进掩膜：按贴图分组建 draw，一次 upload 后逐个执行。 */
    private static void drawMask(List<Quad> quads) {
        if (captureBuffer == null) {
            captureBuffer = new StagedVertexBuffer(() -> "Model mask", 1536);
        } else {
            // 复用上一帧的 GPU 缓冲；池子用 fence 保证不再被 GPU 引用后才回收
            captureBuffer.endFrame();
        }

        Map<Identifier, StagedVertexBuffer.Draw> draws = new LinkedHashMap<>();
        for (Quad quad : quads) {
            Identifier texture = textureOf(quad);
            StagedVertexBuffer.Draw draw = draws.get(texture);
            if (draw == null) {
                draw = captureBuffer.appendDraw(DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS);
                draws.put(texture, draw);
            }
            VertexConsumer consumer = captureBuffer.getVertexBuilder(draw);
            emitQuad(consumer, quad);
        }

        captureBuffer.upload();
        for (Map.Entry<Identifier, StagedVertexBuffer.Draw> entry : draws.entrySet()) {
            StagedVertexBuffer.ExecuteInfo info = captureBuffer.getExecuteInfo(entry.getValue());
            if (info == null) continue;
            PreparedRenderType prepared = layer(entry.getKey()).prepare();
            prepared.drawFromBuffer(info);
        }
    }
//#else
//$$    /** 把面写进掩膜：该版本的即时网格是 ByteBufferBuilder + BufferBuilder，按贴图逐组提交。 */
//$$    private static void drawMask(List<Quad> quads) {
//$$        Map<Identifier, List<Quad>> groups = new LinkedHashMap<>();
//$$        for (Quad quad : quads) {
//$$            groups.computeIfAbsent(textureOf(quad), ignored -> new ArrayList<>()).add(quad);
//$$        }
//$$        try (ByteBufferBuilder allocator = new ByteBufferBuilder(1536)) {
//$$            for (Map.Entry<Identifier, List<Quad>> group : groups.entrySet()) {
//$$                BufferBuilder builder =
//$$                        new BufferBuilder(allocator, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
//$$                for (Quad quad : group.getValue()) {
//$$                    emitQuad(builder, quad);
//$$                }
//$$                MeshData mesh = builder.build();
//$$                // draw 会消费并关闭 mesh；空组（没有任何顶点）返回 null
//$$                if (mesh != null) {
//$$                    layer(group.getKey()).draw(mesh);
//$$                }
//$$            }
//$$        }
//$$    }
//#endif

    private static void emitQuad(VertexConsumer consumer, Quad quad) {
        Vec3[] vertices = quad.vertices();
        for (int i = 0; i < vertices.length; i++) {
            Vec3 vertex = vertices[i];
            consumer.addVertex(IDENTITY.last(), (float) vertex.x, (float) vertex.y, (float) vertex.z)
                    .setColor(0xFFFFFFFF)
                    .setUv(quad.u()[i], quad.v()[i]);
        }
    }

    private static Identifier textureOf(Quad quad) {
        return quad.texture() == null ? WHITE_TEXTURE : quad.texture();
    }

    /** 全屏合成：把掩膜当输入贴图，用 {@code mask_face} / {@code mask_outline} 盖到主目标上。 */
    private static void composite(RenderPipeline pipeline, int color) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = mainTarget(minecraft);
        if (mainTarget == null) return;

        var slice = RenderSystem.getDynamicUniforms()
                .writeTransform(
                        new Matrix4f(),
                        new Vector4f(
                                ARGB.red(color) / 255.0F,
                                ARGB.green(color) / 255.0F,
                                ARGB.blue(color) / 255.0F,
                                ARGB.alpha(color) / 255.0F),
                        new Vector3f(),
                        new Matrix4f());

        try (RenderPass pass = createCompositePass(mainTarget)) {
            //#if MC >= 260300
            //$$            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            //#else
            pass.setPipeline(pipeline);
            //#endif
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", slice);
            //#if MC >= 260300
            //$$            pass.setUniform(
            //$$                    "InSampler",
            //$$                    target.getColorTextureView(),
            //$$                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            //#else
            pass.bindTexture(
                    "InSampler",
                    target.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            //#endif
            drawFullscreen(pass);
        }
    }

//#if MC >= 260200
    private static RenderPass createCompositePass(RenderTarget mainTarget) {
        return RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "ESP model mask", mainTarget.getColorTextureView(), Optional.empty());
    }

    private static void drawFullscreen(RenderPass pass) {
        // screenquad 顶点着色器按 gl_VertexID 生成三角形，不需要顶点缓冲
        pass.draw(3, 1, 0, 0);
    }

    private static RenderTarget mainTarget(Minecraft minecraft) {
        return minecraft.gameRenderer.mainRenderTarget();
    }
//#else
//$$    private static RenderPass createCompositePass(RenderTarget mainTarget) {
//$$        return RenderSystem.getDevice()
//$$                .createCommandEncoder()
//$$                .createRenderPass(
//$$                        () -> "ESP model mask", mainTarget.getColorTextureView(), OptionalInt.empty());
//$$    }
//$$
//$$    private static void drawFullscreen(RenderPass pass) {
//$$        pass.draw(0, 3);
//$$    }
//$$
//$$    private static RenderTarget mainTarget(Minecraft minecraft) {
//$$        return minecraft.getMainRenderTarget();
//$$    }
//#endif

//#if MC >= 260300
//$$    /**
//$$     * 掩膜采集管线：世界几何，顶点位置 + uv + 颜色，采样实体贴图，输出到掩膜。
//$$     *
//$$     * <p>顶点着色器复用原版 {@code core/position_tex_color}（它自己就是 ProjMat * ModelViewMat * Position，
//$$     * 并输出 {@code texCoord0} / {@code vertexColor}），与本仓库的 {@code mask_capture.fsh} 完全对得上。
//$$     * 投影与 DynamicTransforms 是两个独立绑定组，各自都要声明。
//$$     */
//$$    private static RenderPipeline buildCapturePipeline() {
//$$        return RenderPipeline.builder()
//$$                .withLocation(Identifier.tryParse("slimefunhelper:pipeline/mask_capture"))
//$$                .withVertexShader("core/position_tex_color")
//$$                .withFragmentShader(Identifier.tryParse("slimefunhelper:core/mask_capture"))
//$$                .withShaderDefine("SLF_SHADER_263")
//$$                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
//$$                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
//$$                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
//$$                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
//$$                .withPrimitiveTopology(PrimitiveTopology.QUADS)
//$$                .withCull(false)
//$$                .withDepthStencilState(Optional.empty())
//$$                .build();
//$$    }
//$$
//$$    /**
//$$     * 合成管线：全屏三角形 + 单张输入贴图。
//$$     *
//$$     * <p>用 public 的 {@link RenderPipelines#POST_PROCESSING_SNIPPET} 拿 TRIANGLES 拓扑与 Globals，
//$$     * 再补 Projection / DynamicTransforms（颜色）与 InSampler（掩膜）三个绑定。混合用 TRANSLUCENT，
//$$     * 掩膜没有覆盖到的像素在片元里 discard，不受混合影响。
//$$     */
//$$    private static RenderPipeline buildCompositePipeline(String path, String fragment) {
//$$        return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
//$$                .withLocation(Identifier.tryParse("slimefunhelper:" + path))
//$$                .withVertexShader("core/screenquad")
//$$                .withFragmentShader(Identifier.tryParse("slimefunhelper:" + fragment))
//$$                .withShaderDefine("SLF_SHADER_263")
//$$                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
//$$                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
//$$                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
//$$                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
//$$                .build();
//$$    }
//#elseif MC >= 260200
    /**
     * 掩膜采集管线：世界几何，顶点位置 + uv + 颜色，采样实体贴图，输出到掩膜。
     *
     * <p>顶点着色器复用原版 {@code core/position_tex_color}（它自己就是 ProjMat * ModelViewMat * Position，
     * 并输出 {@code texCoord0} / {@code vertexColor}），与本仓库的 {@code mask_capture.fsh} 完全对得上。
     */
    private static RenderPipeline buildCapturePipeline() {
        return RenderPipeline.builder()
                .withLocation(Identifier.tryParse("slimefunhelper:pipeline/mask_capture"))
                .withVertexShader("core/position_tex_color")
                .withFragmentShader(Identifier.tryParse("slimefunhelper:core/mask_capture"))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false)
                .withDepthStencilState(Optional.empty())
                .build();
    }

    /**
     * 合成管线：全屏三角形 + 单张输入贴图。
     *
     * <p>用 public 的 {@link RenderPipelines#POST_PROCESSING_SNIPPET} 拿 TRIANGLES 拓扑与 Globals，
     * 再补 DynamicTransforms（颜色）与 InSampler（掩膜）两个绑定。混合用 TRANSLUCENT，
     * 掩膜没有覆盖到的像素在片元里 discard，不受混合影响。
     */
    private static RenderPipeline buildCompositePipeline(String path, String fragment) {
        return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(Identifier.tryParse("slimefunhelper:" + path))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.tryParse("slimefunhelper:" + fragment))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .build();
    }
//#else
//$$    /** 掩膜采集管线：布局用该版本的 uniform/sampler 声明式写法。 */
//$$    private static RenderPipeline buildCapturePipeline() {
//$$        return RenderPipeline.builder()
//$$                .withLocation(Identifier.tryParse("slimefunhelper:pipeline/mask_capture"))
//$$                .withVertexShader("core/position_tex_color")
//$$                .withFragmentShader(Identifier.tryParse("slimefunhelper:core/mask_capture"))
//$$                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
//$$                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
//$$                .withSampler("Sampler0")
//$$                .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
//$$                .withCull(false)
//$$                .withDepthStencilState(Optional.empty())
//$$                .build();
//$$    }
//$$
//$$    /** 合成管线：全屏三角形 + 单张输入贴图。 */
//$$    private static RenderPipeline buildCompositePipeline(String path, String fragment) {
//$$        return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
//$$                .withLocation(Identifier.tryParse("slimefunhelper:" + path))
//$$                .withVertexShader("core/screenquad")
//$$                .withFragmentShader(Identifier.tryParse("slimefunhelper:" + fragment))
//$$                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
//$$                .withSampler("InSampler")
//$$                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
//$$                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
//$$                .withDepthStencilState(Optional.empty())
//$$                .build();
//$$    }
//#endif
}
