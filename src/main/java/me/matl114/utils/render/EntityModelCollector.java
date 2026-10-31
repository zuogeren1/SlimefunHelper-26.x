package me.matl114.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
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

/**
 * 实体模型几何收集器：把实体身上的每一个面截下来，交给子类决定怎么重画
 * （描边、单线框、贴图重绘）。
 *
 * <p>26.2 的实体渲染已改为 {@code EntityRenderDispatcher#submit(..., SubmitNodeCollector, ...)}，
 * 提交阶段不再经过可替换的 {@code VertexConsumer}，所以{@link #capture} 改成「自建捕获式 collector」：
 * 对每个待画实体先 {@code createRenderState}，再把 {@link CapturingSubmitNodeCollector} 当作提交目标
 * 喂给 dispatcher，由它把模型顶点交回 {@link CapturingVertexConsumer} 还原成面。
 * 抓到的顶点是相机相对坐标（与 {@code LevelRenderer} 提交实体时用的坐标系一致），
 * 与其余 {@link RenderCollector} 实现的画法对得上。
 */
public class EntityModelCollector extends RenderCollector.Impl<Entity> {
    protected final List<IndexEntry<Entity>> entities = new ArrayList<>();

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
        clearCollected();
    }

    @Override
    public void render3D(PoseStack matrices, float partialTick) {
        clearCollected();
        if (entities.isEmpty()) {
            return;
        }
        capture(matrices, partialTick);
        renderCollected(matrices);
        clearCollected();
    }

    @Override
    public void render2D(VDrawContext vDrawContext, float partialTick) {}

    /** 抓取实体模型几何：逐个实体走一遍提交，但提交目标换成捕获式的 collector。 */
    protected void capture(PoseStack matrices, float partialTick) {
        //#if MC >= 260200
        CapturingSubmitNodeCollector collector = new CapturingSubmitNodeCollector(this::collectQuad);
        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        Vec3 cameraPos = RenderUtils.getCameraPos();
        CameraRenderState cameraState = new CameraRenderState();
        cameraState.pos = cameraPos;
        for (IndexEntry<Entity> entry : entities) {
            Entity entity = entry.val();
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            EntityRenderer<? super Entity, ?> renderer = dispatcher.getRenderer(entity);
            try {
                EntityRenderState renderState = renderer.createRenderState(entity, partialTick);
                collector.begin(entry.index(), resolveTexture(renderer, renderState));
                // 传相机相对坐标（与 LevelRenderer 提交实体时一致），抓到的顶点也就落在相机相对空间
                dispatcher.submit(
                        renderState,
                        cameraState,
                        renderState.x - cameraPos.x,
                        renderState.y - cameraPos.y,
                        renderState.z - cameraPos.z,
                        matrices,
                        collector);
            } catch (Exception ignored) {
                // 单个实体没有渲染器 / 渲染器自己抛错，都不该把调试功能升级成崩溃：跳过它，继续抓别的实体
            }
        }
        //#else
        //$$ // 26.1.2 的提交节点接口形态不同（submitModelPart / submitParticleGroup 签名都不一样），本轮只在 26.2 恢复抓面。
        //#endif
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
