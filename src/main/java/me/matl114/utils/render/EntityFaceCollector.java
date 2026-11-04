package me.matl114.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.api.VDrawContext;
import me.matl114.versioned.impl.Render_v1_21_11;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * 实体模型高亮收集器，两条路各自服务一种观感：
 *
 * <ul>
 *   <li>{@link #render2D}（ESP 用的那条）：把面交给 {@link MaskPostRenderer} 做屏幕空间掩膜合成，
 *       填充色由调用方给（ESP 传 alpha=32 的淡罩），轮廓由 boundary 收集器单独画。掩膜按实体贴图
 *       alpha 镂空，所以内层面不会漏出来，描边宽度是固定像素、与距离无关。</li>
 *   <li>{@link #renderCollected}（3D 重绘，EntityLog / ExplosiveESP 用）：抓到的面按贴图分组，
 *       用原版实体渲染层重画一遍、顶点色染色，贴图细节保留。这条路只在调用方主动要 3D 时才跑。</li>
 * </ul>
 *
 * <p>壳层外扩只是 3D 重绘的附加值：它是世界空间的量，越远越细，不能当 ESP 的轮廓用。
 */
public final class EntityFaceCollector extends EntityModelCollector {
    /** 全亮光照贴图（块光 15 / 天空光 15）：高亮不该跟着世界明暗变化。 */
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * 壳层沿面法线外扩的距离（格）：剪影外沿那圈硬边的世界宽度。
     * 世界空间的量，远处自然收细，与线框的观感一致。
     */
    private static final double SHELL_EXPAND = 0.02;

    /**
     * 壳层整体沿视线后撤的距离（格）。
     *
     * <p>外扩后的壳层在朝向相机的那半表面上落到了染色面之前；若与染色面平级比较深度，
     * 它会整片盖住染色面，把实体变回一块纯色。后撤到染色面与实体本体之后，深度比较才会把壳层
     * 压在实体后面：被本体挡住的部分看不见，只剩剪影之外没有东西可挡的那一圈。
     */
    private static final double SHELL_BACKSTEP = 0.02;

    /**
     * 壳层的采样贴图：必须全白且全不透明，顶点色才能原样成为壳层颜色。
     *
     * <p>原版没有 1×1 纯白贴图（{@code textures/misc} 下全是渐变或半透明）；{@code lightning_rod_on}
     * 是原版唯一一张 16×16、每个像素都是 (255,255,255,255) 的贴图，采样任意 uv 都得到纯白。
     */
    private static final Identifier SHELL_TEXTURE =
            Identifier.withDefaultNamespace("textures/block/lightning_rod_on.png");

    private final List<IndexEntry<CapturedQuad>> quads = new ArrayList<>();

    public EntityFaceCollector() {
        super();
    }

    @Override
    protected void collectQuad(CapturedQuad capturedQuad, int color) {
        quads.add(new IndexEntry<>(color, capturedQuad));
    }

    @Override
    protected void renderCollected(PoseStack matrices) {
        if (quads.isEmpty()) return;
        SubmitNodeCollector collector = Render_v1_21_11.getCollector();
        // 不在世界提交上下文（GUI / 2D 通道）时没有可提交的目标。
        if (collector == null) return;

        // 抓到的面整体外扩一层纯色壳，深度上落在实体本体之后，只有边缘那一圈露出来。
        List<IndexEntry<CapturedQuad>> shellQuads = List.copyOf(quads);
        collector.submitCustomGeometry(
                matrices, RenderTypes.entityTranslucent(SHELL_TEXTURE), (pose, consumer) -> {
                    for (IndexEntry<CapturedQuad> entry : shellQuads) {
                        emitShell(consumer, pose, entry);
                    }
                });
    }

    /**
     * 面的法线：抓面时只留了位置与 uv，法线按四边形所在平面现算。
     *
     * <p>方向可能与模型原始朝向相反，但这不影响观感：entity 着色器开了 PER_FACE_LIGHTING，
     * 背面会拿到取反的光照方向，正反两面各自算各自的亮度。退化面没有平面，给个向上的兜底值。
     */
    private static Vec3 normalOf(Vec3[] vertices) {
        Vec3 normal = vertices[1].subtract(vertices[0]).cross(vertices[2].subtract(vertices[0]));
        double length = normal.length();
        if (length < 1.0E-6) {
            return new Vec3(0.0, 1.0, 0.0);
        }
        return normal.scale(1.0 / length);
    }

    /**
     * 一个面写出一层纯色壳：沿面法线正反两个方向各外扩一次，再整体沿视线后撤 {@link #SHELL_BACKSTEP} 格。
     *
     * <p>正反两向都写，是因为抓面侧不保留四边形的环绕方向、法线朝向不可靠：错的那一份落在实体内部，
     * 被本体挡住，多出来的顶点换的是「不依赖绕序」这个不变式。
     */
    private static void emitShell(VertexConsumer consumer, PoseStack.Pose pose, IndexEntry<CapturedQuad> entry) {
        CapturedQuad capturedQuad = entry.val();
        Vec3[] vertices = capturedQuad.vertices();
        Vec3 normal = normalOf(vertices);
        // 壳层是纯色：白色贴图任意 uv 都是白，uv 固定写 (0,0) 即可。
        int color = entry.index() | 0xFF000000;
        for (int side = 0; side < 2; side++) {
            double offset = side == 0 ? SHELL_EXPAND : -SHELL_EXPAND;
            for (Vec3 vertex : vertices) {
                Vec3 shell = awayFromCamera(vertex.add(normal.scale(offset)));
                consumer.addVertex(pose, (float) shell.x, (float) shell.y, (float) shell.z)
                        .setColor(color)
                        .setUv(0.0F, 0.0F)
                        .setLight(FULL_BRIGHT)
                        .setOverlay(0)
                        .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
            }
        }
    }

    /** 顶点沿视线后撤一小段（抓到的坐标都是相机相对量，相机就在原点）。 */
    private static Vec3 awayFromCamera(Vec3 vertex) {
        double length = vertex.length();
        if (length < SHELL_BACKSTEP) {
            return vertex;
        }
        return vertex.scale(1.0 + SHELL_BACKSTEP / length);
    }

    @Override
    public void render2D(VDrawContext context, float partialTick) {
        // 填充：颜色由调用方给（ESP 传 alpha=32 的淡罩），掩膜负责「实体形状 + 贴图镂空」
        renderQuads2D(partialTick, (quads, camera, projection, color) ->
                MaskPostRenderer.draw(quads, camera, projection, color, 0));
    }

    @Override
    protected void clearCollected() {
        quads.clear();
    }
}
