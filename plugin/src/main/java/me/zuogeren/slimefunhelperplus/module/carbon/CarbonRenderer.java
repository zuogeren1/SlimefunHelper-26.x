/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon;

import com.mojang.blaze3d.vertex.PoseStack;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL33.*;

/**
 * 实例化渲染的全局状态与绘制出口。
 *
 * <p>开关由 {@link CarbonModule} 的配置驱动；这里只关心「这一帧要不要接管」。
 * 任何一项前提不满足（模块关闭、驱动不到 OpenGL 3.3、绘制线程不对、模型不是单材质）都必须静默退回原版 ——
 * 本模块是可选增强，坏掉的代价必须是「没效果」而不是「画面花掉」或「启动崩溃」。
 *
 * <p>接管点只有一个：{@code SubmitNodeStorage#submitItem}。它是物品提交的唯一入口，
 * 26.1.2 / 26.2 / 26.3 上的参数表差别极小，因此不必跟着各版本的 feature 渲染器重构走。
 */
public final class CarbonRenderer {

    private static final String VERTEX = "/assets/slimefunhelperplus/shaders/carbon/instanced.vsh";
    private static final String FRAGMENT = "/assets/slimefunhelperplus/shaders/carbon/instanced.fsh";

    private static volatile boolean enabled;
    private static volatile boolean available = true;
    private static boolean checked;

    private static CameraRenderState camera;
    /**
     * 帧首快照。
     *
     * <p>{@code CameraRenderState} 是复用对象，整帧里会被不同阶段改写；只在帧首存引用、
     * 到绘制出口再读，拿到的很可能已经不是这一帧世界渲染用的矩阵。所以矩阵在帧首拷一份。
     */
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static int frame;

    /** 同一份模型几何只烘一次；键见 {@link BatchKey}。 */
    private static final Map<BatchKey, GpuItemBatch> BATCHES = new java.util.HashMap<>();

    /**
     * 批表键。
     *
     * <p>不能用列表身份：26.2 每帧重建渲染状态，每次提交都是一份新的 {@code List}（里面的 {@link BakedQuad}
     * 还是同一批对象），身份键会让每条提交都新建一个 batch 且永不命中。
     * 这里取列表里首/中/末三个 quad 的对象身份 + 尺寸 + 渲染类型 + 染色，跨帧稳定。
     */
    private record BatchKey(RenderType type, int size, int first, int middle, int last, int tints) {
        static BatchKey of(java.util.List<BakedQuad> quads, int[] tints, RenderType type) {
            int size = quads.size();
            return new BatchKey(type, size,
                    System.identityHashCode(quads.get(0)),
                    System.identityHashCode(quads.get(size / 2)),
                    System.identityHashCode(quads.get(size - 1)),
                    tints == null ? 0 : java.util.Arrays.hashCode(tints));
        }
    }

    private static int program;
    private static boolean programFailed;
    private static int framebuffer;
    private static boolean framebufferReported;
    private static volatile boolean framebufferBroken;
    private static boolean drawFailed;
    private static volatile boolean capturingOff;

    private static int uView = -1, uProjection = -1, uCamera = -1, uClock = -1, uGroundScaleY = -1, uFogShape = -1;
    private static int uGlintPass = -1, uGlintAlpha = -1, uColorModulator = -1, uTextureMat = -1;
    private static int uFogStart = -1, uFogEnd = -1, uRenderFogStart = -1, uRenderFogEnd = -1, uFogColor = -1;

    private CarbonRenderer() {}

    public static void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        invalidate();
    }

    public static boolean enabled() {
        return enabled;
    }

    /**
     * 这一帧的深度比较方向。
     *
     * <p>26.2 起投影是反向 Z（近=1 远=0），26.1.2 及更早是正向 Z（近=0 远=1）。
     * 两者在投影矩阵上只差 m22（反向 ≈0、正向 ≈-1）；方向搞反会把整片物品剔掉。
     */
    static int depthFunction() {
        return PROJECTION.m22() < -0.5F ? GL_LEQUAL : GL_GEQUAL;
    }

    /** 驱动能力只在渲染线程第一次询问时探测一次；结果缓存在 {@link #available}。 */
    static boolean supported() {
        if (!checked) {
            // 非渲染线程问 GL 会直接抛，且一旦记为不可用就不会再探测，所以只在渲染线程上做一次性探测
            if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
                return available;
            }
            checked = true;
            try {
                var capabilities = GL.getCapabilities();
                available = capabilities.OpenGL33;
            } catch (Throwable exception) {
                available = false;
            }
        }
        return available;
    }

    public static boolean capturing() {
        return enabled && supported() && !programFailed && !framebufferBroken && !capturingOff;
    }

    /**
     * 帧首快照。
     *
     * <p>绘制出口拿不到相机状态，只能在提交阶段顺带存下来；两个版本的提交入口不同，
     * 由 {@code CarbonFrameMixin} 分版本注入。
     */
    public static void beginFrame(CameraRenderState state) {
        camera = state;
        VIEW.set(state.viewRotationMatrix);
        PROJECTION.set(state.projectionMatrix);
        if (capturing()) {
            frame++;
        }
    }

    /**
     * 物品提交的接管点，返回 {@code true} 表示已接管、调用方要丢弃这次原版提交。
     *
     * <p>只接管世界里的掉落物（{@code GROUND}），且要求整个模型只用一个渲染类型 ——
     * 多材质模型要拆成多次绘制，留到后面再做。
     */
    public static boolean captureItem(
            PoseStack pose,
            ItemDisplayContext context,
            int light,
            int overlay,
            int outline,
            int[] tints,
            Object quads,
            ItemStackRenderState.FoilType foil) {
        if (!capturing() || context != ItemDisplayContext.GROUND || outline != 0) {
            return false;
        }
        java.util.List<BakedQuad> baked = normalize(quads);
        if (baked == null || baked.isEmpty()) {
            return false;
        }
        if (foil == ItemStackRenderState.FoilType.SPECIAL) {
            return false;
        }
        RenderType type = baked.get(0).materialInfo().itemRenderType();
        if (type == null) {
            return false;
        }
        for (BakedQuad quad : baked) {
            if (quad.materialInfo().itemRenderType() != type) {
                return false;
            }
        }
        BatchKey key = BatchKey.of(baked, tints, type);
        GpuItemBatch batch = BATCHES.get(key);
        if (batch == null) {
            batch = GpuItemBatch.create(baked, tints, type);
            if (batch == null) {
                return false;
            }
            BATCHES.put(key, batch);
        }
        batch.add(new Matrix4f(pose.last().pose()), light, frame);
        return true;
    }

    /**
     * 把提交里的 quad 载体统一成列表。
     *
     * <p>26.1.2 / 26.2 直接给 {@code List<BakedQuad>}；26.3 改给 {@code ItemQuads} ——
     * 它把 solid 与 translucent 拆成两份，三个列表里 {@code all()} 是合集。
     */
    private static java.util.List<BakedQuad> normalize(Object quads) {
        //#if MC >= 260300
        //$$ if (quads instanceof net.minecraft.client.resources.model.geometry.ItemQuads item) {
        //$$     return item.all();
        //$$ }
        //#endif
        if (quads instanceof java.util.List<?> list) {
            @SuppressWarnings("unchecked")
            java.util.List<BakedQuad> baked = (java.util.List<BakedQuad>) list;
            return baked;
        }
        return null;
    }

    /** 一帧的绘制出口，位于原版世界渲染全部结束之后、渲染线程上。 */
    public static void flush() {
        if (!capturing() || camera == null) {
            return;
        }
        boolean any = false;
        for (GpuItemBatch batch : BATCHES.values()) {
            if (batch.instances() > 0) {
                any = true;
                break;
            }
        }
        if (!any || !ensureProgram()) {
            return;
        }
        try {
            drawFrame();
        } catch (Throwable exception) {
            // 任何异常都不许崩渲染线程：记一次、停掉接管，退回原版
            disable("draw failed: " + exception);
        }
    }

    private static void disable(String reason) {
        if (!drawFailed) {
            drawFailed = true;
            me.matl114.utils.Debug.info("[CarbonRender] " + reason);
        }
        capturingOff = true;
    }

    private static void drawFrame() {
        if (framebuffer == 0) {
            framebuffer = glGenFramebuffers();
        }
        // 自建 FBO 挂主目标的颜色/深度纹理：绘制出口绑定的不一定是参与呈现的目标，
        // 直接画进当前绑定会被随后的整帧 blit 覆盖掉（表现为物品消失、其余画面正常）。
        try (GpuFrame gpu = new GpuFrame(framebuffer, false)) {
            if (!gpu.complete()) {
                // 放弃接管而不是硬画：不完整 FBO 的绘制会被静默丢弃，继续接管就只是把物品藏起来
                framebufferBroken = true;
                if (!framebufferReported) {
                    framebufferReported = true;
                    me.matl114.utils.Debug.info("[CarbonRender] framebuffer incomplete: " + gpu.status());
                }
                return;
            }
            glUseProgram(program);
            uploadUniforms(gpu);
            for (GpuItemBatch batch : BATCHES.values()) {
                if (batch.instances() == 0) {
                    continue;
                }
                batch.upload();
                gpu.bindTexture(0, batch.texture());
                batch.draw();
            }
            glBindVertexArray(0);
        }
        for (GpuItemBatch batch : BATCHES.values()) {
            batch.reset();
        }
        BATCHES.values().removeIf(batch -> {
            if (batch.stale(frame)) {
                batch.close();
                return true;
            }
            return false;
        });
    }

    private static void uploadUniforms(GpuFrame gpu) {
        uniform(uView, VIEW);
        uniform(uProjection, PROJECTION);
        // 提交阶段拿到的 PoseStack 已经是相机相对坐标（EntityRenderDispatcher#submit 收的就是相对坐标，
        // 内部再 translate 到姿态里），所以这里必须传 0 —— 传相机位置等于把物品又推远一次，会整片飞出画面。
        glUniform3f(uCamera, 0.0F, 0.0F, 0.0F);
        glUniform1f(uClock, 0.0F);
        glUniform1f(uGroundScaleY, 0.0F);
        glUniform1i(uFogShape, 1);
        glUniform1i(uGlintPass, 0);
        glUniform1f(uGlintAlpha, 0.0F);
        glUniform4f(uColorModulator, 1.0F, 1.0F, 1.0F, 1.0F);
        glUniform4f(uFogColor, 0.0F, 0.0F, 0.0F, 0.0F);
        glUniform1f(uFogStart, 0.0F);
        glUniform1f(uFogEnd, 1.0E9F);
        glUniform1f(uRenderFogStart, 0.0F);
        glUniform1f(uRenderFogEnd, 1.0E9F);
        uniform(uTextureMat, new Matrix4f());
        glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
        glUniform1i(glGetUniformLocation(program, "Sampler1"), 1);
        glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
        int lighting = glGetUniformBlockIndex(program, "Lighting");
        if (lighting != -1) {
            glUniformBlockBinding(program, lighting, 3);
        }
    }

    private static void uniform(int location, Matrix4f matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            matrix.get(buffer);
            glUniformMatrix4fv(location, false, buffer);
        }
    }

    private static boolean ensureProgram() {
        if (program != 0 || programFailed) {
            return program != 0;
        }
        int vertex = 0;
        int fragment = 0;
        int created = 0;
        try {
            vertex = compile(GL_VERTEX_SHADER, VERTEX);
            fragment = compile(GL_FRAGMENT_SHADER, FRAGMENT);
            created = glCreateProgram();
            glAttachShader(created, vertex);
            glAttachShader(created, fragment);
            glLinkProgram(created);
            if (glGetProgrami(created, GL_LINK_STATUS) == GL_FALSE) {
                throw new IllegalStateException(glGetProgramInfoLog(created));
            }
            program = created;
            created = 0;
            uView = glGetUniformLocation(program, "View");
            uProjection = glGetUniformLocation(program, "Projection");
            uCamera = glGetUniformLocation(program, "Camera");
            uClock = glGetUniformLocation(program, "Clock");
            uGroundScaleY = glGetUniformLocation(program, "GroundScaleY");
            uFogShape = glGetUniformLocation(program, "FogShape");
            uGlintPass = glGetUniformLocation(program, "GlintPass");
            uGlintAlpha = glGetUniformLocation(program, "GlintAlpha");
            uColorModulator = glGetUniformLocation(program, "ColorModulator");
            uTextureMat = glGetUniformLocation(program, "TextureMat");
            uFogStart = glGetUniformLocation(program, "FogStart");
            uFogEnd = glGetUniformLocation(program, "FogEnd");
            uRenderFogStart = glGetUniformLocation(program, "RenderFogStart");
            uRenderFogEnd = glGetUniformLocation(program, "RenderFogEnd");
            uFogColor = glGetUniformLocation(program, "FogColor");
            return true;
        } catch (Throwable exception) {
            programFailed = true;
            me.matl114.utils.Debug.info("[CarbonRender] shader program failed: " + exception);
            return false;
        } finally {
            if (created != 0) {
                glDeleteProgram(created);
            }
            if (vertex != 0) {
                glDeleteShader(vertex);
            }
            if (fragment != 0) {
                glDeleteShader(fragment);
            }
        }
    }

    private static int compile(int type, String path) throws Exception {
        try (InputStream stream = CarbonRenderer.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing shader " + path);
            }
            int shader = glCreateShader(type);
            glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
                String log = glGetShaderInfoLog(shader);
                glDeleteShader(shader);
                throw new IllegalStateException(log);
            }
            return shader;
        }
    }

    public static void invalidate() {
        camera = null;
        frame = 0;
        for (GpuItemBatch batch : BATCHES.values()) {
            batch.close();
        }
        BATCHES.clear();
    }
}
