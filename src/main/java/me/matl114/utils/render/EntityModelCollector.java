package me.matl114.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import me.matl114.events.RenderListener;
import me.matl114.utils.Debug;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 实体模型几何收集器：把实体身上的每一个面截下来，交给子类决定怎么重画
 * （3D 贴图重绘、屏幕空间掩膜）。
 *
 * <p>实体渲染在两个版本都走 {@code EntityRenderDispatcher#submit(..., SubmitNodeCollector, ...)}，
 * 提交阶段不再经过可替换的 {@code VertexConsumer}，所以{@link #capture} 用「自建捕获式 collector」：
 * 对每个待画实体先 {@code createRenderState}，再把 {@link CapturingSubmitNodeCollector} 当作提交目标
 * 喂给 dispatcher，由它把模型顶点交回 {@link CapturingVertexConsumer} 还原成面
 * （{@code OrderedSubmitNodeCollector} 的成员集两版本不同，该 collector 因此各有一份实现）。
 * 抓到的顶点是相机相对坐标（与 {@code LevelRenderer} 提交实体时用的坐标系一致），
 * 与其余 {@link RenderCollector} 实现的画法对得上。
 *
 * <p>掩膜路径（{@link #renderQuads2D}）只在 2D 阶段算数据：真正写显存要等 GUI 画完之后，
 * 由 {@link MaskPostRenderer#flush()} 统一执行，所以这里只把面与相机矩阵交给消费方。
 */
public class EntityModelCollector extends RenderCollector.Impl<Entity> {
    /** 抓面失败计数：前几次留痕，之后静默（避免每帧刷屏）。 */
    private static final AtomicInteger CAPTURE_FAILURES = new AtomicInteger();

    protected final List<IndexEntry<Entity>> entities = new ArrayList<>();

    /** 本帧抓到的全部面（{@link #renderQuads2D} 按实体清空重抓）。 */
    private final List<IndexEntry<CapturedQuad>> capturedQuads = new ArrayList<>();

    /** 掩膜消费方：拿到一个实体的全部面 + 该帧的世界相机矩阵 + 该实体的颜色。 */
    @FunctionalInterface
    protected interface MaskConsumer {
        void accept(List<MaskPostRenderer.Quad> quads, Matrix4f camera, Matrix4f projection, int color);
    }

    /** 抓到的单个面：四个顶点 + uv + 来源贴图（纯色面 texture 为 null）。 */
    public record CapturedQuad(Vec3[] vertices, UV uv, Identifier texture) {
        public static CapturedQuad solid(Vec3[] vertices) {
            return new CapturedQuad(vertices, UV.DEFAULT, null);
        }

        public static CapturedQuad textured(Vec3[] vertices, UV uv, Identifier texture) {
            return new CapturedQuad(vertices, uv, texture);
        }
    }

    @Override
    public void submit(Entity val, int color) {
        entities.add(new IndexEntry<>(color, val));
    }

    @Override
    public void clear() {
        super.clear();
        entities.clear();
        capturedQuads.clear();
        clearCollected();
    }

    @Override
    public void render3D(PoseStack matrices, float partialTick) {
        clearCollected();
        if (entities.isEmpty()) {
            return;
        }
        capturedQuads.clear();
        capture(entities, matrices, partialTick);
        renderCollected(matrices);
        clearCollected();
        capturedQuads.clear();
    }

    @Override
    public void render2D(VDrawContext vDrawContext, float partialTick) {}

    /**
     * 按实体逐个抓面并交给 {@code consumer}：一个实体的掩膜只服务它自己的颜色，
     * 所以不能把所有实体合并成一批。
     */
    protected final void renderQuads2D(float partialTick, MaskConsumer consumer) {
        if (entities.isEmpty()) return;
        Matrix4f camera = RenderListener.getWorldModelViewMatrix();
        Matrix4f projection = RenderListener.getWorldBasicProjectionMatrix();
        if (camera == null || projection == null) return;
        Matrix4f cameraCopy = new Matrix4f(camera);
        Matrix4f projectionCopy = new Matrix4f(projection);
        PoseStack identity = new PoseStack();
        for (IndexEntry<Entity> entry : entities) {
            Entity entity = entry.val();
            if (entity == null || entity.isRemoved()) continue;
            capturedQuads.clear();
            capture(List.of(entry), identity, partialTick);
            if (capturedQuads.isEmpty()) continue;
            List<MaskPostRenderer.Quad> quads = new ArrayList<>(capturedQuads.size());
            for (IndexEntry<CapturedQuad> captured : capturedQuads) {
                quads.add(toMaskQuad(captured.val()));
            }
            consumer.accept(quads, cameraCopy, projectionCopy, entry.index());
        }
        capturedQuads.clear();
    }

    /** 抓取实体模型几何：逐个实体走一遍提交，但提交目标换成捕获式的 collector。 */
    private void capture(List<IndexEntry<Entity>> batch, PoseStack matrices, float partialTick) {
        CapturingSubmitNodeCollector collector = new CapturingSubmitNodeCollector((quad, color) -> {
            capturedQuads.add(new IndexEntry<>(color, quad));
            collectQuad(quad, color);
        });
        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        Vec3 cameraPos = RenderUtils.getCameraPos();
        CameraRenderState cameraState = new CameraRenderState();
        cameraState.pos = cameraPos;
        for (IndexEntry<Entity> entry : batch) {
            Entity entity = entry.val();
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            EntityRenderer<? super Entity, ?> renderer = dispatcher.getRenderer(entity);
            try {
                EntityRenderState renderState = renderer.createRenderState(entity, partialTick);
                collector.begin(entry.index(), resolveTexture(renderer, renderState));
                // 传相机相对坐标（与 LevelRenderer 提交实体时一致），抓到的顶点也就落在相机相对空间
                // submit 内部会 push/translate/pop 这个 PoseStack，但它一旦抛异常就弹不回来；
                // 我们在外面再包一层，保证共享的 PoseStack 不会因为抓面失败而失衡。
                matrices.pushPose();
                try {
                    dispatcher.submit(
                            renderState,
                            cameraState,
                            renderState.x - cameraPos.x,
                            renderState.y - cameraPos.y,
                            renderState.z - cameraPos.z,
                            matrices,
                            collector);
                } finally {
                    matrices.popPose();
                }
            } catch (Exception failure) {
                // 单个实体没有渲染器 / 渲染器自己抛错，都不该把调试功能升级成崩溃：跳过它，继续抓别的实体。
                // 但前几次失败要留痕，否则抓面失败会表现成「什么都没画」，无从定位。
                if (CAPTURE_FAILURES.getAndIncrement() < 3) {
                    Debug.info("capture failed for %s".formatted(entity.getType()), failure);
                }
            }
        }
    }

    /** 抓到的面 → 掩膜面：顶点保持原样，uv 按四角展开成逐顶点数组。 */
    private static MaskPostRenderer.Quad toMaskQuad(CapturedQuad captured) {
        Vec3[] vertices = captured.vertices();
        float[] u = new float[vertices.length];
        float[] v = new float[vertices.length];
        UV uv = captured.uv();
        for (int i = 0; i < vertices.length; i++) {
            u[i] = uv.getU(i);
            v[i] = uv.getV(i);
        }
        return new MaskPostRenderer.Quad(vertices, u, v, captured.texture());
    }

    /** 实体本体的贴图；船、画、展示框这类非生物实体没有统一贴图，返回 null 表示按纯色面处理。 */
    @SuppressWarnings("rawtypes")
    private static Identifier resolveTexture(EntityRenderer<? super Entity, ?> renderer, EntityRenderState renderState) {
        // getTextureLocation 在 LivingEntityRenderer 上是 public abstract，但它的形参是 S extends LivingEntityRenderState，
        // 而 renderer 的实参类型是前面解析出来的 capture，二者不能静态对上，只能按擦除类型走。
        if (renderer instanceof LivingEntityRenderer && renderState instanceof LivingEntityRenderState livingState) {
            return ((LivingEntityRenderer) renderer).getTextureLocation(livingState);
        }
        return null;
    }

    protected void collectQuad(CapturedQuad capturedQuad, int color) {}

    protected void renderCollected(PoseStack matrices) {}

    protected void clearCollected() {}
}
