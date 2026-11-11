/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;
import me.matl114.accessors.access.RenderLayerAccess;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL33.*;

/**
 * 一份模型几何 + 它在这一帧的实例变换。
 *
 * <p>顶点布局是自定的（不是 {@code DefaultVertexFormat}）：原版格式在 26.2 用 {@code GpuFormat} 枚举、
 * 26.3 又整包换了，跟着它走要写两套格式映射表。这里改成自己实现 {@link VertexConsumer}，
 * 把 {@code putBakedQuad} 解码出来的值直接写进固定布局，三个版本共用一份。
 *
 * <p>顶点属性与 {@code instanced.vsh} 的 layout 一一对应：
 * 0 位置(vec3) 1 颜色(vec4, 归一化 ubyte) 2 UV0(vec2) 3 UV1(ivec2) 4 UV2(ivec2) 5 法线(vec3)。
 * 实例属性 6..15 由本类按 {@link #INSTANCE_STRIDE} 填。
 */
final class GpuItemBatch implements AutoCloseable {

    /** 位置 12 + 颜色 4 + UV0 8 + UV1 8 + UV2 8 + 法线 12。 */
    private static final int VERTEX_STRIDE = 52;

    /** mat4 64 + Animation 16 + Data 8 + mat3 36 + CopyOffset 12。 */
    private static final int INSTANCE_STRIDE = 136;

    /**
     * 写进 UV1 的标记。
     *
     * <p>{@code instanced.vsh} 拿 {@code UV1.x} 当「开光照」开关、{@code UV1.y} 当「采样光照贴图」开关，
     * 两个都要非 0，逐实例的光照才会生效；写 0 会让整批变成全亮。
     */
    private static final int LIT = 0x00010001;

    /** 首份网格的第一个顶点与总顶点数，用来区分「几何没写进去」和「写进去了但没画出来」。 */
    static String meshProbe;

    private static String round(float value) {
        return String.valueOf(Math.round(value * 1000.0F) / 1000.0);
    }

    private final Identifier texture;
    private final int vao;
    private final int vertexBuffer;
    private final int indexBuffer;
    private final int instanceBuffer;
    private final int indexCount;

    static String matrixProbe;
    private Matrix4f first;
    private ByteBuffer instances;
    private int capacity;
    private int gpuCapacity;
    private int count;
    private long lastFrame;

    private GpuItemBatch(Identifier texture, Mesh mesh) {
        this.texture = texture;
        this.indexCount = mesh.indices.remaining();
        this.vao = glGenVertexArrays();
        this.vertexBuffer = glGenBuffers();
        this.indexBuffer = glGenBuffers();
        this.instanceBuffer = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, mesh.vertices, GL_STATIC_DRAW);
        attribute(0, 3, GL_FLOAT, false, VERTEX_STRIDE, 0);
        attribute(1, 4, GL_UNSIGNED_BYTE, true, VERTEX_STRIDE, 12);
        attribute(2, 2, GL_FLOAT, false, VERTEX_STRIDE, 16);
        integerAttribute(3, 2, 24);
        integerAttribute(4, 2, 32);
        attribute(5, 3, GL_FLOAT, false, VERTEX_STRIDE, 40);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, mesh.indices, GL_STATIC_DRAW);

        glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
        for (int i = 0; i < 4; i++) {
            attribute(6 + i, 4, GL_FLOAT, false, INSTANCE_STRIDE, i * 16);
            glVertexAttribDivisor(6 + i, 1);
        }
        attribute(10, 4, GL_FLOAT, false, INSTANCE_STRIDE, 64);
        attribute(11, 2, GL_FLOAT, false, INSTANCE_STRIDE, 80);
        for (int i = 0; i < 3; i++) {
            attribute(12 + i, 3, GL_FLOAT, false, INSTANCE_STRIDE, 88 + i * 12);
        }
        attribute(15, 3, GL_FLOAT, false, INSTANCE_STRIDE, 124);
        for (int i = 10; i <= 15; i++) {
            glVertexAttribDivisor(i, 1);
        }
        glBindVertexArray(0);
    }

    private static void attribute(int index, int size, int type, boolean normalized, int stride, int offset) {
        glVertexAttribPointer(index, size, type, normalized, stride, offset);
        glEnableVertexAttribArray(index);
    }

    private static void integerAttribute(int index, int size, int offset) {
        glVertexAttribIPointer(index, size, GL_INT, VERTEX_STRIDE, offset);
        glEnableVertexAttribArray(index);
    }

    Identifier texture() {
        return texture;
    }

    int instances() {
        return count;
    }

    int indices() {
        return indexCount;
    }

    static String attribProbe;

    /** 首个实例的平移（仅用于一次性自检的屏幕投影）。 */
    Matrix4f first() {
        return first;
    }

    void reset(long frame) {
        count = 0;
        lastFrame = frame;
    }

    boolean stale(long frame) {
        return frame - lastFrame > 600;
    }

    /**
     * 追加一个实例。
     *
     * <p>实例矩阵直接取提交时的姿态：{@code submitItem} 传进来的 PoseStack 已经是世界空间，
     * 着色器里只要再减掉相机位置即可。
     *
     * <p>这里只动堆内缓冲，GL 侧的重分配留到 {@link #upload()} —— 捕获发生在提交阶段，
     * 那时改 GL 状态会干扰原版后续的绘制。
     */
    void add(Matrix4f transform, int light, long frame) {
        if (instances == null || (count + 1) * INSTANCE_STRIDE > instances.capacity()) {
            grow();
        }
        lastFrame = frame;
        if (count == 0) {
            first = new Matrix4f(transform);
            if (matrixProbe == null) {
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    float value = switch (i) {
                        case 0 -> transform.m00(); case 1 -> transform.m01(); case 2 -> transform.m02(); case 3 -> transform.m03();
                        case 4 -> transform.m10(); case 5 -> transform.m11(); case 6 -> transform.m12(); case 7 -> transform.m13();
                        case 8 -> transform.m20(); case 9 -> transform.m21(); case 10 -> transform.m22(); case 11 -> transform.m23();
                        case 12 -> transform.m30(); case 13 -> transform.m31(); case 14 -> transform.m32(); default -> transform.m33();
                    };
                    text.append(round(value)).append(" ");
                }
                matrixProbe = text.toString();
            }
        }
        instances.position(count * INSTANCE_STRIDE);
        transform.get(instances);
        instances.putFloat(0.0F).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
        instances.putFloat(light & 0xFFFF).putFloat((light >>> 16) & 0xFFFF);
        new Matrix3f(transform).get(instances);
        instances.putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
        count++;
    }

    private void grow() {
        int next = Math.max(64, Math.max(1, count) * 2);
        ByteBuffer replacement = ByteBuffer.allocateDirect(next * INSTANCE_STRIDE).order(ByteOrder.nativeOrder());
        if (instances != null) {
            instances.position(0);
            instances.limit(count * INSTANCE_STRIDE);
            replacement.put(instances);
            instances.clear();
        }
        replacement.position(0);
        instances = replacement;
        capacity = next;
    }

    void upload() {
        if (count == 0) {
            return;
        }
        glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
        if (gpuCapacity < capacity) {
            glBufferData(GL_ARRAY_BUFFER, (long) INSTANCE_STRIDE * capacity, GL_DYNAMIC_DRAW);
            gpuCapacity = capacity;
        }
        instances.position(0);
        instances.limit(count * INSTANCE_STRIDE);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, instances);
        instances.clear();
    }

    void draw() {
        glBindVertexArray(vao);
        glDrawElementsInstanced(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L, count);
    }

    /** 回读 VAO 的真实状态：属性是否启用、绑到哪个 buffer、stride/offset/divisor 是多少。 */
    /**
     * 只读位置属性把这份网格画一次（不用实例矩阵）。
     *
     * <p>它出现 = 顶点缓冲与属性 0 的绑定正确，那么问题只可能在实例属性上；不出现 = 顶点侧就不对。
     */
    void drawSingle(int meshProgram) {
        glUseProgram(meshProgram);
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L);
    }

    void probeInto() {
        attribProbe = probeAttributes();
    }

    String probeAttributes() {
        glBindVertexArray(vao);
        StringBuilder text = new StringBuilder();
        text.append("vao=").append(vao).append(" ok=").append(glIsVertexArray(vao));
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer value = stack.mallocInt(1);
            org.lwjgl.PointerBuffer pointer = stack.mallocPointer(1);
            for (int index : new int[]{0, 1, 3, 6, 10, 15}) {
                glGetVertexAttribiv(index, GL_VERTEX_ATTRIB_ARRAY_ENABLED, value);
                int enabled = value.get(0);
                glGetVertexAttribiv(index, GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING, value);
                int buffer = value.get(0);
                glGetVertexAttribiv(index, GL_VERTEX_ATTRIB_ARRAY_SIZE, value);
                int size = value.get(0);
                glGetVertexAttribiv(index, GL_VERTEX_ATTRIB_ARRAY_STRIDE, value);
                int stride = value.get(0);
                glGetVertexAttribiv(index, GL_VERTEX_ATTRIB_ARRAY_TYPE, value);
                int type = value.get(0);
                int divisor = org.lwjgl.opengl.GL30.glGetVertexAttribi(index, GL_VERTEX_ATTRIB_ARRAY_DIVISOR);
                glGetVertexAttribPointerv(index, GL_VERTEX_ATTRIB_ARRAY_POINTER, pointer);
                text.append(" [").append(index).append(": on=").append(enabled).append(" buf=").append(buffer)
                        .append(" size=").append(size).append(" type=").append(type)
                        .append(" stride=").append(stride).append(" off=").append(pointer.get(0))
                        .append(" div=").append(divisor).append("]");
            }
        }
        int previous = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer size = stack.mallocInt(1);
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            glGetBufferParameteriv(GL_ARRAY_BUFFER, GL_BUFFER_SIZE, size);
            text.append(" vbo=").append(size.get(0));
            glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
            glGetBufferParameteriv(GL_ARRAY_BUFFER, GL_BUFFER_SIZE, size);
            text.append(" ibo=").append(size.get(0)).append(" stride=").append(INSTANCE_STRIDE);
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER, previous);
        }
        glBindVertexArray(0);
        return text.toString();
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vertexBuffer);
        glDeleteBuffers(indexBuffer);
        glDeleteBuffers(instanceBuffer);
    }

    // ---------------------------------------------------------------- 网格构建

    /** 顶点与索引的堆内副本，上传后即可丢弃。 */
    private record Mesh(ByteBuffer vertices, IntBuffer indices) {}

    /**
     * 把 {@code putBakedQuad} 解码出来的值写进自有布局。
     *
     * <p>用原版解码而不是自己拆 {@link BakedQuad}：UV、发光、着色、法线都由它处理，
     * 我们只换落点。索引按 QUADS 每四顶点两个三角形推进。
     */
    private static final class Builder implements VertexConsumer {
        private final ByteBuffer vertices;
        private final IntBuffer indices;
        private int vertexCount;

        private Builder(int quads) {
            vertices = ByteBuffer.allocateDirect(quads * 4 * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
            indices = ByteBuffer.allocateDirect(quads * 6 * Integer.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
        }

        Mesh build() {
            if (meshProbe == null) {
                StringBuilder text = new StringBuilder();
                for (int vertex = 0; vertex < Math.min(4, vertexCount); vertex++) {
                    int base8 = vertex * VERTEX_STRIDE;
                    text.append(" v").append(vertex).append("=(")
                            .append(round(vertices.getFloat(base8))).append(",")
                            .append(round(vertices.getFloat(base8 + 4))).append(",")
                            .append(round(vertices.getFloat(base8 + 8))).append(")"
                            ).append(" uv=(")
                            .append(round(vertices.getFloat(base8 + 16))).append(",")
                            .append(round(vertices.getFloat(base8 + 20))).append(")");
                }
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                for (int vertex = 0; vertex < vertexCount; vertex++) {
                    int base8 = vertex * VERTEX_STRIDE;
                    float px = vertices.getFloat(base8);
                    float py = vertices.getFloat(base8 + 4);
                    float pz = vertices.getFloat(base8 + 8);
                    minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                    minZ = Math.min(minZ, pz); maxZ = Math.max(maxZ, pz);
                }
                meshProbe = text + " verts=" + vertexCount + " idx=" + indices.position()
                        + " bbox=(" + round(minX) + ".." + round(maxX) + "," + round(minY) + ".." + round(maxY)
                        + "," + round(minZ) + ".." + round(maxZ) + ")";
            }
            vertices.position(0);
            indices.position(0);
            return new Mesh(vertices, indices);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        /** 线宽与描边不影响面片，接口要求实现即可（26.2 起是抽象方法，26.1.2 上没有）。 */
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        //#if MC >= 260300
        //$$ @Override
        //$$ public VertexConsumer setUv3(float u, float v) {
        //$$     return this;
        //$$ }
        //#endif

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v,
                              int overlay, int light, float nx, float ny, float nz) {
            if (vertexCount % 4 == 0) {
                int first = vertexCount;
                indices.put(first).put(first + 1).put(first + 2)
                        .put(first).put(first + 2).put(first + 3);
            }
            vertices.putFloat(x).putFloat(y).putFloat(z);
            vertices.put((byte) (color >>> 16)).put((byte) (color >>> 8))
                    .put((byte) color).put((byte) (color >>> 24));
            vertices.putFloat(u).putFloat(v);
            vertices.putInt(LIT & 0xFFFF).putInt((LIT >>> 16) & 0xFFFF);
            vertices.putInt(light & 0xFFFF).putInt((light >>> 16) & 0xFFFF);
            vertices.putFloat(nx).putFloat(ny).putFloat(nz);
            vertexCount++;
        }
    }

    static GpuItemBatch create(List<BakedQuad> quads, int[] tints, RenderType type) {
        Identifier texture = RenderLayerAccess.of(type).getRenderTexture().orElse(null);
        if (texture == null) {
            return null;
        }
        Builder builder = new Builder(quads.size());
        QuadInstance instance = new QuadInstance();
        instance.setOverlayCoords(LIT);
        instance.setLightCoords(0);
        PoseStack pose = new PoseStack();
        PoseStack.Pose last = pose.last();
        for (BakedQuad quad : quads) {
            int index = quad.materialInfo().tintIndex();
            instance.setColor(tints != null && index >= 0 && index < tints.length ? tints[index] : -1);
            builder.putBakedQuad(last, quad, instance);
        }
        return new GpuItemBatch(texture, builder.build());
    }
}
