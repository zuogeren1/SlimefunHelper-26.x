package me.matl114.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.matl114.hacks.utils.render.RenderElements;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.api.VRender;
import net.minecraft.world.phys.Vec3;

/** 只画外轮廓：某条边被两个面共享就相互抵消，剩下的就是剪影。 */
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
