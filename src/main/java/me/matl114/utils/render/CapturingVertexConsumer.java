package me.matl114.utils.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * 捕获式 {@link VertexConsumer}：不写显存，只把顶点抄进内存，够四个就还原成一个面。
 *
 * <p>26.2 的模型几何仍然是逐顶点提交的：{@code ModelPart.Cube#compile(PoseStack.Pose, VertexConsumer, int, int, int)}
 * 对每个面连续调用四次 {@code builder.addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz)}
 * （{@code ModelPart.java:336} 处的调用，落到 {@code VertexConsumer.java:29} 的默认实现），
 * 所以「每四个顶点一个面」就能把模型的每一个面原样还原出来。
 *
 * <p>uv 由四个顶点的最小/最大 u、v 组成矩形：{@code ModelPart.Polygon} 的构造器
 * （{@code ModelPart.java:343-360}）把四个角直接 remap 成矩形四角，这个矩形就是贴图采样范围。
 * 顶点顺序按 uv 角点重排成 (u0,v0) (u0,v1) (u1,v1) (u1,v0)，与 {@link UV#getU(int)} / {@link UV#getV(int)}
 * 的取法一致；不重排的话贴图会跟着顶点提交顺序转 90 度。镜像（mirror）立方体的 uv 角点同样能对上，
 * 因为它按角点查找而不是按序号。
 *
 * <p>四个角点出现重复（非矩形 uv，例如退化面）时退回提交顺序，此时贴图可能有偏移但几何仍然正确。
 */
public final class CapturingVertexConsumer implements VertexConsumer {
    /** 一个面四个顶点：模型立方体的每个 polygon 都恰好提交四个顶点。 */
    private static final int VERTICES_PER_QUAD = 4;

    /** 这个面用哪张贴图重画；为空表示纯色面。 */
    private final Identifier texture;
    private final Consumer<EntityModelCollector.CapturedQuad> sink;

    private final float[] posX = new float[VERTICES_PER_QUAD];
    private final float[] posY = new float[VERTICES_PER_QUAD];
    private final float[] posZ = new float[VERTICES_PER_QUAD];
    private final float[] uCoord = new float[VERTICES_PER_QUAD];
    private final float[] vCoord = new float[VERTICES_PER_QUAD];
    private int count;

    public CapturingVertexConsumer(Identifier texture, Consumer<EntityModelCollector.CapturedQuad> sink) {
        this.texture = texture;
        this.sink = sink;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (count == VERTICES_PER_QUAD) {
            flush();
        }
        posX[count] = x;
        posY[count] = y;
        posZ[count] = z;
        uCoord[count] = 0.0F;
        vCoord[count] = 0.0F;
        count++;
        return this;
    }

    /** uv 由调用方在 addVertex 之后紧跟着设置（见 VertexConsumer 的 11 参数默认 addVertex），所以写进最后一个顶点。 */
    @Override
    public VertexConsumer setUv(float u, float v) {
        if (count > 0) {
            uCoord[count - 1] = u;
            vCoord[count - 1] = v;
        }
        return this;
    }

    /** 颜色/光照/法线都不参与「面」的定义，CapturedQuad 里也没有对应字段，直接丢弃。 */
    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
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

//#if MC >= 260300
//$$     /** 26.3 新增的第三组 uv（贴片/闪光用的 decal uv）同样不参与「面」的定义，跟 setUv1/setUv2 一样丢弃。 */
//$$     @Override
//$$     public VertexConsumer setUv3(float u, float v) {
//$$         return this;
//$$     }
//#endif

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }

    /**
     * 一次模型渲染结束时调用：最后一个面之后不会再有 addVertex 来触发 flush，
     * 不补这一下就会永远丢掉模型最后一组四个顶点（少了最后一个面）。
     */
    public void finish() {
        flush();
    }

    private void flush() {
        if (count == VERTICES_PER_QUAD) {
            sink.accept(buildQuad());
        }
        // 不足四个顶点（线段等非四边形拓扑）直接丢弃，不拼假面
        count = 0;
    }

    private EntityModelCollector.CapturedQuad buildQuad() {
        float minU = uCoord[0];
        float maxU = uCoord[0];
        float minV = vCoord[0];
        float maxV = vCoord[0];
        for (int i = 1; i < VERTICES_PER_QUAD; i++) {
            minU = Math.min(minU, uCoord[i]);
            maxU = Math.max(maxU, uCoord[i]);
            minV = Math.min(minV, vCoord[i]);
            maxV = Math.max(maxV, vCoord[i]);
        }

        // 0=(u0,v0) 1=(u1,v0) 2=(u0,v1) 3=(u1,v1)
        int[] corners = new int[VERTICES_PER_QUAD];
        Arrays.fill(corners, -1);
        boolean rectangular = true;
        for (int i = 0; i < VERTICES_PER_QUAD; i++) {
            int corner = (uCoord[i] == maxU ? 1 : 0) | (vCoord[i] == maxV ? 2 : 0);
            if (corners[corner] >= 0) {
                rectangular = false;
                break;
            }
            corners[corner] = i;
        }

        int[] order = rectangular
                ? new int[] {corners[0], corners[2], corners[3], corners[1]}
                : new int[] {0, 1, 2, 3};
        Vec3[] vertices = new Vec3[VERTICES_PER_QUAD];
        for (int i = 0; i < VERTICES_PER_QUAD; i++) {
            int source = order[i];
            vertices[i] = new Vec3(posX[source], posY[source], posZ[source]);
        }

        if (texture == null) {
            return EntityModelCollector.CapturedQuad.solid(vertices);
        }
        return EntityModelCollector.CapturedQuad.textured(vertices, new UV(minU, minV, maxU, maxV), texture);
    }
}
