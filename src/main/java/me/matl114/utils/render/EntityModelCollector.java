package me.matl114.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 实体模型几何收集器：把实体身上的每一个面截下来，交给子类决定怎么重画
 * （描边、单线框、贴图重绘）。
 *
 * <p>26.2 的实体渲染已改为 {@code EntityRenderDispatcher#submit(..., SubmitNodeCollector, ...)}，
 * 顶点在提交阶段直接进 GPU，不再经过可替换的 {@code VertexConsumer}，
 * 因此这里的抓面流程需要实现一个 {@code SubmitNodeCollector} 拦截器才能复原。
 * 在补上那一步之前 {@link #capture} 是空实现：调用方照常编译、照常收集实体，
 * 只是暂无几何可画。
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

    /** 抓取实体模型几何；26.2 尚无等价入口，子类可覆写。 */
    protected void capture(PoseStack matrices, float partialTick) {}

    protected void collectQuad(CapturedQuad capturedQuad, int color) {}

    protected void renderCollected(PoseStack matrices) {}

    protected void clearCollected() {}
}
