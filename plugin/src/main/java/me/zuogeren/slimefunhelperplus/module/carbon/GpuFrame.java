/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon;

//#if MC >= 260300
//$$ import com.mojang.renderpearl.api.textures.FilterMode;
//$$ import com.mojang.renderpearl.backend.opengl.GlSampler;
//$$ import com.mojang.renderpearl.backend.opengl.GlTexture;
//#else
import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.FilterMode;
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * 一次绘制的 GL 状态快照，退出时原样还原。
 *
 * <p>不还原就会污染 {@code RenderPipeline} 的状态缓存 —— 新版管线自己记账「当前绑了什么」，
 * 我们绕过它直接改 GL，之后它认为没变就不会重设，表现是后续原版渲染错乱。
 * 因此这里保存项刻意覆盖到管线关心的全部可观测状态（program / VAO / FBO / viewport /
 * 3 组纹理与采样器 / 深度混合剔除各开关 / UBO binding）。
 *
 * <p>颜色与深度不另建缓冲：直接把主目标的纹理挂到自有 FBO 上，
 * 这样深度测试与遮挡关系和原版连续，不需要复制。
 */
final class GpuFrame implements AutoCloseable {

    private final MemoryStack memory = MemoryStack.stackPush();
    private final int program = glGetInteger(GL_CURRENT_PROGRAM);
    private final int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
    private final int framebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    private final int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
    private final IntBuffer viewport = memory.mallocInt(4);
    private final ByteBuffer colorMask = memory.malloc(4);
    private final int[] textures = new int[3];
    private final int[] samplers = new int[3];
    private final boolean depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND), cull = glIsEnabled(GL_CULL_FACE);
    private final boolean scissor = glIsEnabled(GL_SCISSOR_TEST), offset = glIsEnabled(GL_POLYGON_OFFSET_FILL);
    private final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
    private final int depthFunction = glGetInteger(GL_DEPTH_FUNC);
    private final int sourceRgb = glGetInteger(GL_BLEND_SRC_RGB), destinationRgb = glGetInteger(GL_BLEND_DST_RGB);
    private final int sourceAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), destinationAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
    private final int equationRgb = glGetInteger(GL_BLEND_EQUATION_RGB), equationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
    private final int uniformBuffer = glGetInteger(GL_UNIFORM_BUFFER_BINDING);
    private final int lights = glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 3);
    private final long lightStart = glGetInteger64i(GL_UNIFORM_BUFFER_START, 3), lightSize = glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, 3);
    private final int[] sharedBuffers;
    private final long[] sharedStarts, sharedSizes;
    private final int status;
    private boolean depthReady;


    GpuFrame(int target, boolean shared) {
        this(target, shared, true);
    }

    /**
     * @param attach 为 {@code false} 时不另建 FBO、不改编 viewport，直接画进当前绑定的目标。
     *               26.x 的绘制出口在整帧世界渲染结束之后，此刻绑定的就是世界目标；
     *               自己挂纹理反而可能挂到一张不参与呈现的纹理上（物品消失、其余画面正常）。
     */
    GpuFrame(int target, boolean shared, boolean attach) {
        sharedBuffers = shared ? new int[2] : null;
        sharedStarts = shared ? new long[2] : null;
        sharedSizes = shared ? new long[2] : null;
        if (shared) {
            var projection = RenderSystem.getProjectionMatrixBuffer();
            var fog = RenderSystem.getShaderFog();
            var sources = java.util.List.of(projection, fog);
            for (int i = 0; i < 2; i++) {
                sharedBuffers[i] = glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, i + 4);
                sharedStarts[i] = glGetInteger64i(GL_UNIFORM_BUFFER_START, i + 4);
                sharedSizes[i] = glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, i + 4);
                var source = sources.get(i);
                glBindBufferRange(GL_UNIFORM_BUFFER, i + 4, ((CarbonBufferAccess) source.buffer()).carbon$handle(), source.offset(), source.length());
            }
        }
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
        for (int unit = 0; unit < 3; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            textures[unit] = glGetInteger(GL_TEXTURE_BINDING_2D);
            samplers[unit] = glGetInteger(GL_SAMPLER_BINDING);
        }
        var minecraft = Minecraft.getInstance();
        //#if MC >= 260200
        var renderTarget = minecraft.gameRenderer.mainRenderTarget();
        //#else
        //$$ var renderTarget = minecraft.getMainRenderTarget();
        //#endif
        // 出图点在所有 RenderPass 之外，呈现的就是主目标本身；此时 output*Override 若还留着值，
        // 那是上一个 pass 的残留，跟着它走会画进一张不参与呈现的纹理（表现为「画了但看不见」）。
        //#if MC >= 260300
        //$$ var color = renderTarget.getColorTexture();
        //$$ var depth = renderTarget.getDepthTexture();
        //#else
        var color = renderTarget.getColorTexture();
        var depth = renderTarget.getDepthTexture();
        //#endif
        if (!attach) {
            status = GL_FRAMEBUFFER_COMPLETE;
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(true);
            glColorMask(true, true, true, true);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_POLYGON_OFFSET_FILL);
            glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, ((GlTexture) minecraft.gameRenderer.levelLightmap().texture()).glId());
            glBindSampler(2, ((GlSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)).getId());
            var lights = RenderSystem.getShaderLights();
            if (lights != null) {
                glBindBufferRange(GL_UNIFORM_BUFFER, 3, ((CarbonBufferAccess) lights.buffer()).carbon$handle(), lights.offset(), lights.length());
            }
            return;
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ((GlTexture) color).glId(), 0);
        // 挂主目标自己的深度纹理做遮挡。两个前提缺一不可：
        //   ① 出图点必须在 render3dHud 清深度之前（见 CarbonFlushMixin），否则挂上来的是全 0 的远平面；
        //   ② 比较方向跟版本走：26.2 起是反向 Z（近=1 远=0，GEQUAL），26.1.2 及更早是正向 Z（LEQUAL），
        //     方向错了会把整片物品剔掉（表现为「看不见」或「只有被挡住的地方才看得见」）。
        // 世界深度后面还有后处理与手部通道要用；这里只写自己的片元（远处被 GEQUAL 剔掉），手部通道之前会清深度。
        boolean withDepth = false;
        try {
            if (depth instanceof GlTexture depthTexture) {
                glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTexture.glId(), 0);
                withDepth = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
                if (!withDepth) {
                    // 多重采样 / 格式不匹配时退回「只挂颜色」，宁可穿墙也不要整块不完整
                    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, 0, 0);
                }
            }
        } catch (Throwable ignored) {
            withDepth = false;
        }
        depthReady = withDepth;
        status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glViewport(0, 0, color.getWidth(0), color.getHeight(0));
        // 深度必须可写：只测不写的话，同一物件自己的面之间没有先后，最后画的那面会盖住前面的，
        // 表现为「侧面缺失、内壁可见」。原版 item 管线本来就是 GEQUAL + writeDepth，这里与它一致。
        if (withDepth) {
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(CarbonRenderer.depthFunction());
            glDepthMask(true);
        } else {
            glDisable(GL_DEPTH_TEST);
            glDepthMask(false);
        }
        glColorMask(true, true, true, true);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, ((GlTexture) minecraft.gameRenderer.levelLightmap().texture()).glId());
        glBindSampler(2, ((GlSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)).getId());
        var lighting = RenderSystem.getShaderLights();
        if (lighting != null) {
            glBindBufferRange(GL_UNIFORM_BUFFER, 3, ((CarbonBufferAccess) lighting.buffer()).carbon$handle(), lighting.offset(), lighting.length());
        }
    }

    /** 这次绘制是否拿到了可用的世界深度（拿不到就退回「不遮挡」）。 */
    boolean depth() {
        return depthReady;
    }

    /** FBO 是否完整；不完整时调用方必须放弃绘制，否则只会静默丢帧。 */
    boolean complete() {
        return status == GL_FRAMEBUFFER_COMPLETE;
    }

    int status() {
        return status;
    }

    void bindTexture(int unit, Identifier id) {
        var texture = Minecraft.getInstance().getTextureManager().getTexture(id);
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, ((GlTexture) texture.getTexture()).glId());
        glBindSampler(unit, ((GlSampler) texture.getSampler()).getId());
    }

    @Override
    public void close() {
        for (int unit = 0; unit < 3; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_2D, textures[unit]);
            glBindSampler(unit, samplers[unit]);
        }
        glActiveTexture(activeTexture);
        if (depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
        if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
        if (offset) glEnable(GL_POLYGON_OFFSET_FILL); else glDisable(GL_POLYGON_OFFSET_FILL);
        glDepthFunc(depthFunction);
        glDepthMask(depthMask);
        glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
        glBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
        glBlendEquationSeparate(equationRgb, equationAlpha);
        if (lights != 0 && lightSize > 0) glBindBufferRange(GL_UNIFORM_BUFFER, 3, lights, lightStart, lightSize);
        else glBindBufferBase(GL_UNIFORM_BUFFER, 3, lights);
        if (sharedBuffers != null) {
            for (int i = 0; i < 2; i++) {
                if (sharedBuffers[i] != 0 && sharedSizes[i] > 0) glBindBufferRange(GL_UNIFORM_BUFFER, i + 4, sharedBuffers[i], sharedStarts[i], sharedSizes[i]);
                else glBindBufferBase(GL_UNIFORM_BUFFER, i + 4, sharedBuffers[i]);
            }
        }
        glBindBuffer(GL_UNIFORM_BUFFER, uniformBuffer);
        glUseProgram(program);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        memory.close();
    }
}
