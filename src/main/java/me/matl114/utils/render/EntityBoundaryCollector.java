package me.matl114.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.api.VRender;
import net.minecraft.world.phys.Vec3;

/**
 * 实体轮廓（boundary）收集器：把抓到的每个面都画成满 alpha 的实心四边形。
 *
 * <p>这是「A 档近似」。上游 1.2.1.3 这一层走的是遮罩后处理：面先画进离屏目标得到掩膜（填充），
 * 再由 {@code mask_outline} 沿掩膜外侧扩出 2.5px 合成一圈描边（见
 * {@code build/_ref/out/task-D-degraded-closures.md} 的 §1.1）；26.2 复原那套要离屏 {@code TextureTarget}
 * + 三条自建管线 + 一个 {@code GameRenderer} hook（同一文档的 B 档），这里改用现成管线近似。
 *
 * <p>为什么这样就等于剪影：{@code QUADS_NO_CULL} 不做深度测试、不剔背面、颜色是满 alpha，
 * 于是所有面（含背面）都用同一个不透明颜色覆盖，混合时 {@code dst = src}——重叠不累加、不会出现内部接缝，
 * 屏幕上的像素集合就正好是所有投影面多边形的并集。不需要朝向剔除，也不需要 CPU 侧求并。
 * 反过来说，颜色 alpha 一旦小于 255（比如 {@link EntityFaceCollector} 那层的 32），重叠面会被混成
 * 「X 光半透明」，那就不是剪影了，所以 {@link #collectQuad} 里把 alpha 压满。
 *
 * <p>层用的是已注册的 {@code Render_v1_21_11.QUADS_NO_CULL}（{@code DEBUG_FILLED_SNIPPET} +
 * {@code CompareOp.ALWAYS_PASS} 且不写深度 + {@code withCull(false)}，顶点格式 {@code POSITION_COLOR}），
 * 与 {@link EntityFaceCollector} 画纯色面走的是同一条管线。
 *
 * <p>已知局限（其中前两条旧的「逐面画线」实现同样有）：3D 提交阶段画出的内容会被随后画的半透明
 * （水、玻璃、粒子）盖住；做不出上游那圈 2.5px 外扩描边（那要 B 档）；贴图 alpha 的镂空会被填实。
 */
public final class EntityBoundaryCollector extends EntityModelCollector {
    private final List<IndexEntry<CapturedQuad>> quads = new ArrayList<>();

    public EntityBoundaryCollector() {
        super();
    }

    @Override
    protected void collectQuad(CapturedQuad capturedQuad, int color) {
        // 只有四边形面能当实心面画（抓面侧本来就只吐四边形，这里是防御性过滤）。
        // 顺手把 alpha 压满：半透明会让重叠面互相混合，出来的是 X 光实体而不是剪影。
        if (capturedQuad.vertices().length == 4) {
            quads.add(new IndexEntry<>(color | 0xFF000000, capturedQuad));
        }
    }

    @Override
    protected void renderCollected(PoseStack matrices) {
        if (quads.isEmpty()) return;

        VRender.getInstance().createQuadsLayer((operation, vertexConsumer) -> {
            for (IndexEntry<CapturedQuad> entry : quads) {
                Vec3[] vertices = entry.val().vertices();
                operation.drawQuad(
                        matrices,
                        vertexConsumer,
                        new Quad(vertices[0], vertices[1], vertices[2], vertices[3]),
                        ColorQuad.of(entry.index()));
            }
        }, false);
    }

    @Override
    protected void clearCollected() {
        quads.clear();
    }
}
