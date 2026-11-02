package me.matl114.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.api.VRender;
import net.minecraft.world.phys.Vec3;

/**
 * 画实体剪影：每个抓到的面都沿四条边连线（共享边会画两次，视觉上就是更亮的轮廓）。
 *
 * <p>上游 1.2.1.3 把「共享边相互抵消」的旧实现换成了逐面画边，这里与之一致。
 */
public class EntityOutlineCollector extends EntityModelCollector {
    private final List<IndexEntry<CapturedQuad>> quads = new ArrayList<>();

    public EntityOutlineCollector() {
        super();
    }

    @Override
    protected void collectQuad(CapturedQuad capturedQuad, int color) {
        if (capturedQuad.vertices().length == 4) {
            quads.add(new IndexEntry<>(color, capturedQuad));
        }
    }

    @Override
    protected void renderCollected(PoseStack matrices) {
        if (quads.isEmpty()) return;

        VRender.getInstance().createLinesLayer((operation, vertexConsumer) -> {
            for (IndexEntry<CapturedQuad> entry : quads) {
                Vec3[] vertices = entry.val().vertices();
                for (int index = 0; index < 4; index++) {
                    operation.drawLine(
                            matrices, vertexConsumer, vertices[index], vertices[(index + 1) & 3], entry.index());
                }
            }
        });
    }

    @Override
    protected void clearCollected() {
        quads.clear();
    }
}
