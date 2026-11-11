/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon;

import com.mojang.blaze3d.vertex.PoseStack;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
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
import static org.lwjgl.opengl.GL40.GL_MAX_VERTEX_ATTRIBS;
import static org.lwjgl.opengl.GL40.GL_MAX_VERTEX_ATTRIBS;

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
     * 到绘制出口再读，拿到的很可能已经不是这一帧世界渲染用的矩阵。所以矩阵与位置都在帧首拷一份。
     */
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static float cameraX, cameraY, cameraZ;
    private static int frame;
    private static int framesSeen;

    /** 诊断计数：注入点被调用多少次、真正接管多少次。开关是否生效、锚点是否命中，全看这两条。 */
    private static long submissions;
    private static long taken;
    private static long lastReport;

    /** 同一份模型几何只烘一次；键用列表身份，避免每帧对 BakedQuad 记录做深哈希。 */
    private static final Map<java.util.List<BakedQuad>, GpuItemBatch> BATCHES = new IdentityHashMap<>();

    /** 每个拦截原因的计数：哪一道判定把掉落物挡回去，一眼能看出来。 */
    private static final Map<String, Integer> SKIPS = new java.util.LinkedHashMap<>();
    private static String sample;
    private static String probe;
    private static String drawProbe;
    private static boolean selfChecked;
    private static boolean readbackDone;
    private static final StringBuilder READBACK = new StringBuilder();

    private static String r(double value) {
        return String.valueOf(Math.round(value));
    }

    /** 实例矩阵三个基向量的长度，用来判断模型的显示变换（缩放）有没有进到矩阵里。 */
    private static String scale(Matrix4f matrix, int column) {
        float x = column == 0 ? matrix.m00() : column == 1 ? matrix.m01() : matrix.m02();
        float y = column == 0 ? matrix.m10() : column == 1 ? matrix.m11() : matrix.m12();
        float z = column == 0 ? matrix.m20() : column == 1 ? matrix.m21() : matrix.m22();
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        return String.valueOf(Math.round(length * 1000.0F) / 1000.0);
    }

    private static void skip(String reason) {
        SKIPS.merge(reason, 1, Integer::sum);
    }

    private static int program;
    private static int solidProgram;
    private static int triangleProgram;
    private static int emptyVao;
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

    public static CameraRenderState camera() {
        return camera;
    }

    public static int frame() {
        return frame;
    }

    /**
     * 帧首快照。
     *
     * <p>绘制出口（{@code GameRenderer#renderLevel} 收尾）拿不到相机状态，只能在提交阶段顺带存下来；
     * 两个版本的提交入口不同，由 {@code CarbonFrameMixin} 分版本注入。
     */
    public static void beginFrame(CameraRenderState state) {
        camera = state;
        VIEW.set(state.viewRotationMatrix);
        PROJECTION.set(state.projectionMatrix);
        cameraX = (float) state.pos.x;
        cameraY = (float) state.pos.y;
        cameraZ = (float) state.pos.z;
        framesSeen++;
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
        submissions++;
        java.util.List<BakedQuad> baked = normalize(quads);
        if (baked == null) {
            skip("notList");
            return false;
        }
        if (baked.isEmpty()) {
            skip("empty");
            return false;
        }
        if (foil == ItemStackRenderState.FoilType.SPECIAL) {
            skip("specialFoil");
            return false;
        }
        RenderType type = baked.get(0).materialInfo().itemRenderType();
        if (type == null) {
            skip("nullType");
            return false;
        }
        if (sample == null) {
            sample = "ctx=" + context + " foil=" + foil + " quads=" + baked.size()
                    + " type=" + type + " tints=" + (tints == null ? "null" : tints.length);
        }
        for (BakedQuad quad : baked) {
            if (quad.materialInfo().itemRenderType() != type) {
                skip("mixedType");
                return false;
            }
        }
        GpuItemBatch batch = BATCHES.get(baked);
        if (batch == null) {
            batch = GpuItemBatch.create(baked, tints, type);
            if (batch == null) {
                skip("noTexture");
                return false;
            }
            batch.reset(frame);
            BATCHES.put(baked, batch);
        }
        Matrix4f transform = new Matrix4f(pose.last().pose());
        batch.add(transform, light, frame);
        taken++;
        if (probe == null) {
            CameraRenderState state = camera;
            probe = "t=(" + r(transform.m30()) + "," + r(transform.m31()) + "," + r(transform.m32()) + ")"
                    + " s=(" + scale(transform, 0) + "," + scale(transform, 1) + "," + scale(transform, 2) + ")"
                    + " cam=" + (state == null ? "null" : "(" + r(state.pos.x) + "," + r(state.pos.y) + "," + r(state.pos.z) + ")")
                    + " vw=" + BATCHES.size();
        }
        return true;
    }

    /**
     * 把提交里的 quad 载体统一成列表。
     *
     * <p>26.1.2 / 26.2 直接给 {@code List<BakedQuad>}；26.3 改给 {@code ItemQuads} ——
     * 它把 solid 与 translucent 拆成两份（半透明需要独立混合），三个列表里 {@code all()} 是合集。
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
        if (!capturing()) {
            return;
        }
        report();
        if (camera == null) {
            return;
        }
        boolean any = false;
        for (GpuItemBatch batch : BATCHES.values()) {
            if (batch.instances() > 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        if (!ensureProgram()) {
            return;
        }
        try {
            drawFrame();
        } catch (Throwable exception) {
            // 诊断/移植期的任何异常都不许崩渲染线程：记一次、停掉接管，退回原版
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
        // 必须自建 FBO 挂主目标的颜色/深度纹理：renderLevel 收尾时绑定的是 0 号窗口缓冲，
        // 画进去会被随后的整帧 blit 覆盖掉（表现为物品消失、其余画面正常）。
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
            if (CarbonModule.noDepth || CarbonModule.canary) {
                glDisable(GL_DEPTH_TEST);
            }
            if (CarbonModule.canary) {
                if (!ensureSolidProgram()) {
                    return;
                }
                glUseProgram(solidProgram);
                uniform(glGetUniformLocation(solidProgram, "View"), new Matrix4f().scaling(0.1F));
                uniform(glGetUniformLocation(solidProgram, "Projection"), new Matrix4f());
            } else {
                glUseProgram(program);
                uploadUniforms(gpu);
            }
            if (CarbonModule.canary) {
                // 判定 FBO 是否被呈现：擦一块品红方块。看得见 = 目标没问题，看不见 = 我们画在了不参与呈现的表上
                int[] area = new int[4];
                glGetIntegerv(GL_VIEWPORT, area);
                glEnable(GL_SCISSOR_TEST);
                glScissor(area[0] + area[2] / 2 - 100, area[1] + area[3] / 2 - 100, 200, 200);
                glClearColor(1.0F, 0.0F, 1.0F, 1.0F);
                glClear(GL_COLOR_BUFFER_BIT);
                glDisable(GL_SCISSOR_TEST);
                glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            }
            if (GpuItemBatch.attribProbe == null && !BATCHES.isEmpty()) {
                GpuItemBatch probe = BATCHES.values().iterator().next();
                probe.probeInto();
            }
            if (CarbonModule.canary) {
                drawCanaryTriangle();
                drawCanaryMesh();
                glUseProgram(program);
            }
            if (drawProbe == null) {
                int[] viewport = new int[4];
                glGetIntegerv(GL_VIEWPORT, viewport);
                int[] count = new int[1];
                glGetIntegerv(GL_MAX_VERTEX_ATTRIBS, count);
                drawProbe = "fbo=" + glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
                        + " prog=" + glGetInteger(GL_CURRENT_PROGRAM)
                        + " vp=" + viewport[0] + "x" + viewport[1] + "/" + viewport[2] + "x" + viewport[3]
                        + " depth=" + glGetBoolean(GL_DEPTH_TEST)
                        + " attrs=" + count[0];
            }
            if (!selfChecked && !BATCHES.isEmpty()) {
                selfChecked = true;
                readback(gpu, "before");
            }
            for (GpuItemBatch batch : BATCHES.values()) {
                if (batch.instances() == 0) {
                    continue;
                }
                batch.upload();
                gpu.bindTexture(0, batch.texture());
                batch.draw();
            }
            if (selfChecked && !readbackDone) {
                readbackDone = true;
                readback(gpu, "depthOn");
                glDisable(GL_DEPTH_TEST);
                for (GpuItemBatch batch : BATCHES.values()) {
                    if (batch.instances() > 0) {
                        batch.draw();
                    }
                }
                readback(gpu, "depthOff");
                glEnable(GL_DEPTH_TEST);
            }
            glBindVertexArray(0);
        }
        for (GpuItemBatch batch : BATCHES.values()) {
            batch.reset(frame);
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
        CameraRenderState state = camera;
        if (CarbonModule.canary) {
            // 金丝雀：把相机相对坐标缩放进裁剪空间 + 单位投影，实例位置不必改——只要管线通，屏幕中心必有东西
            uniform(uView, new Matrix4f().scaling(0.1F));
            uniform(uProjection, new Matrix4f());
            glUniform3f(uCamera, 0.0F, 0.0F, 0.0F);
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
            glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
            int block = glGetUniformBlockIndex(program, "Lighting");
            if (block != -1) {
                glUniformBlockBinding(program, block, 3);
            }
            return;
        }
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

    /**
     * 最简诊断程序：只读位置与实例矩阵，输出纯色。
     *
     * <p>用它把「几何/VAO 是否工作」与「片元路径（UV、光照贴图、discard）是否正确」彻底分开 ——
     * 只要它能在屏幕上留下像素，就说明顶点与绘制参数全对。
     */
    private static boolean ensureSolidProgram() {
        if (solidProgram != 0) {
            return true;
        }
        int vertex = 0;
        int fragment = 0;
        int created = 0;
        try {
            vertex = compile(GL_VERTEX_SHADER, "/assets/slimefunhelperplus/shaders/carbon/debug_solid.vsh");
            fragment = compile(GL_FRAGMENT_SHADER, "/assets/slimefunhelperplus/shaders/carbon/debug_solid.fsh");
            created = glCreateProgram();
            glAttachShader(created, vertex);
            glAttachShader(created, fragment);
            glLinkProgram(created);
            if (glGetProgrami(created, GL_LINK_STATUS) == GL_FALSE) {
                throw new IllegalStateException(glGetProgramInfoLog(created));
            }
            solidProgram = created;
            created = 0;
            return true;
        } catch (Throwable exception) {
            programFailed = true;
            me.matl114.utils.Debug.info("[CarbonRender] solid shader failed: " + exception);
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

    /**
     * 不读任何顶点属性的三角形。
     *
     * <p>坐标由 {@code gl_VertexID} 在着色器里直接算出，绘制时不绑定任何 VBO/VAO 属性 ——
     * 它出现即证明「绘制调用本身能上屏」，那么问题就只可能在属性绑定上。
     */
    private static boolean drawCanaryTriangle() {
        if (triangleProgram == 0) {
            int vertex = 0;
            int fragment = 0;
            int created = 0;
            try {
                vertex = compile(GL_VERTEX_SHADER, "/assets/slimefunhelperplus/shaders/carbon/debug_triangle.vsh");
                fragment = compile(GL_FRAGMENT_SHADER, "/assets/slimefunhelperplus/shaders/carbon/debug_triangle.fsh");
                created = glCreateProgram();
                glAttachShader(created, vertex);
                glAttachShader(created, fragment);
                glLinkProgram(created);
                if (glGetProgrami(created, GL_LINK_STATUS) == GL_FALSE) {
                    throw new IllegalStateException(glGetProgramInfoLog(created));
                }
                triangleProgram = created;
                created = 0;
                emptyVao = glGenVertexArrays();
            } catch (Throwable exception) {
                me.matl114.utils.Debug.info("[CarbonRender] triangle shader failed: " + exception);
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
        glUseProgram(triangleProgram);
        glBindVertexArray(emptyVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        return true;
    }

    private static int meshProgram;

    /** 用只读位置属性的程序画第一份网格，单独验证「顶点属性是否工作」。 */
    private static void drawCanaryMesh() {
        if (meshProgram == 0) {
            int vertex = 0;
            int fragment = 0;
            int created = 0;
            try {
                vertex = compile(GL_VERTEX_SHADER, "/assets/slimefunhelperplus/shaders/carbon/debug_mesh.vsh");
                fragment = compile(GL_FRAGMENT_SHADER, "/assets/slimefunhelperplus/shaders/carbon/debug_mesh.fsh");
                created = glCreateProgram();
                glAttachShader(created, vertex);
                glAttachShader(created, fragment);
                glLinkProgram(created);
                if (glGetProgrami(created, GL_LINK_STATUS) == GL_FALSE) {
                    throw new IllegalStateException(glGetProgramInfoLog(created));
                }
                meshProgram = created;
                created = 0;
            } catch (Throwable exception) {
                me.matl114.utils.Debug.info("[CarbonRender] mesh shader failed: " + exception);
                return;
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
        if (!BATCHES.isEmpty()) {
            BATCHES.values().iterator().next().drawSingle(meshProgram);
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

    /**
     * 每 5 秒往日志里打一行统计。
     *
     * <p>掉物品时 {@code submissions} 与 {@code taken} 应当一起涨、{@code batches} 保持稳定；
     * 若 {@code batches} 一直涨，说明模型列表每帧都是新对象、缓存键失效了。
     */
    private static void report() {
        long now = System.currentTimeMillis();
        if (now - lastReport < 5000L) {
            return;
        }
        lastReport = now;
        me.matl114.utils.Debug.info("[CarbonRender] submissions=" + submissions
                + " taken=" + taken + " batches=" + BATCHES.size()
                + " frames=" + framesSeen + " frame=" + frame + " skips=" + SKIPS
                + " gl=" + glGetError() + " probe=[" + probe + "] projections=" + projections()
                + " idx=" + indexTotal() + " n=" + instanceTotal() + " draw=[" + drawProbe + "] mesh=[" + GpuItemBatch.meshProbe + "]"
                + " rb=[" + READBACK + "]"
                + " m=[" + GpuItemBatch.matrixProbe + "]"
                + " vao=[" + GpuItemBatch.attribProbe + "]"
                + " u=[" + uView + "," + uProjection + "," + uCamera + "," + uClock + "," + uGroundScaleY
                + "," + uFogShape + "," + uGlintPass + "," + uGlintAlpha + "," + uColorModulator + ","
                + uTextureMat + "," + uFogStart + "," + uFogEnd + "," + uRenderFogStart + "," + uRenderFogEnd
                + "," + uFogColor + "]");
    }

    /**
     * 一次性像素回读：在「首个实例投影到屏幕的位置」读 1 个像素。
     *
     * <p>绘制前后各读一次，再在关掉深度后读一次 —— 三个值就能区分
     * 「没画出任何像素」与「画出来了但被后续覆盖」。
     */
    private static void readback(GpuFrame gpu, String stage) {
        CameraRenderState state = camera;
        if (state == null) {
            READBACK.append(stage).append("=nocamera ");
            return;
        }
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        int width = Math.max(1, viewport[2]);
        int height = Math.max(1, viewport[3]);
        Matrix4f transform = null;
        for (GpuItemBatch batch : BATCHES.values()) {
            transform = batch.first();
            if (transform != null) {
                break;
            }
        }
        if (transform == null) {
            READBACK.append(stage).append("=notransform ");
            return;
        }
        float x = transform.m30();
        float y = transform.m31();
        float z = transform.m32();
        org.joml.Vector4f clip = PROJECTION.mul(VIEW, new Matrix4f())
                .transform(new org.joml.Vector4f(x, y, z, 1.0F));
        int sx = -1;
        int sy = -1;
        if (clip.w != 0.0F) {
            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            sx = Math.round((ndcX * 0.5F + 0.5F) * width);
            sy = Math.round((ndcY * 0.5F + 0.5F) * height);
        }
        if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
            READBACK.append(stage).append("=offscreen(").append(sx).append(",").append(sy).append(") ");
            return;
        }
        int readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        // glReadPixels 读的是 READ 帧缓冲，只绑 DRAW 会读到别的目标 —— 这里显式对齐到同一个 FBO
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(4);
            glReadPixels(sx, sy, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            READBACK.append(stage).append("=").append(Integer.toHexString(buffer.get(0) & 0xFF))
                    .append(Integer.toHexString(buffer.get(1) & 0xFF))
                    .append(Integer.toHexString(buffer.get(2) & 0xFF))
                    .append("@").append(sx).append(",").append(sy)
                    .append(" vp=").append(width).append("x").append(height).append(" ");
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
        }
    }

    private static int indexTotal() {
        int total = 0;
        for (GpuItemBatch batch : BATCHES.values()) {
            total += batch.indices();
        }
        return total;
    }

    private static int instanceTotal() {
        int total = 0;
        for (GpuItemBatch batch : BATCHES.values()) {
            total += batch.instances();
        }
        return total;
    }

    private static String projections() {
        return fmt(PROJECTION.m00()) + "/" + fmt(PROJECTION.m11()) + "/" + fmt(PROJECTION.m22()) + "/" + fmt(PROJECTION.m23());
    }

    private static String fmt(float value) {
        return String.valueOf(Math.round(value * 1000.0F) / 1000.0);
    }

    public static void invalidate() {
        lastReport = 0L;
        camera = null;
        frame = 0;
        for (GpuItemBatch batch : BATCHES.values()) {
            batch.close();
        }
        BATCHES.clear();
    }

    public static String statistics() {
        return "carbon: enabled=" + enabled + " supported=" + supported() + " frames=" + framesSeen
                + " patched=" + (capturing() ? "on" : "off");
    }
}
