package me.matl114.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** 用实体包围盒做框/实心/连线，几何来自 {@link RenderCollectors#createBoxCollector}。 */
public final class EntityBoxCollector extends RenderCollector.Impl<Entity> {
    private final RenderCollector<AABB> boxCollector;

    public EntityBoxCollector(boolean drawOutline, boolean drawSolid, boolean drawTraceLine) {
        boxCollector = RenderCollectors.createBoxCollector(drawOutline, drawSolid, drawTraceLine);
    }

    @Override
    public void clear() {
        super.clear();
        boxCollector.clear();
    }

    @Override
    public void render3D(PoseStack matrices, float partialTick) {
        submitLerpedBoxes(partialTick);
        boxCollector.render3D(matrices, partialTick);
        boxCollector.clear();
    }

    @Override
    public void render2D(VDrawContext vDrawContext, float partialTick) {
        submitLerpedBoxes(partialTick);
        boxCollector.render2D(vDrawContext, partialTick);
        boxCollector.clear();
    }

    private void submitLerpedBoxes(float partialTick) {
        for (IndexEntry<Entity> entry : entries) {
            boxCollector.submit(RenderUtils.getLerpedBox(entry.val(), partialTick), entry.index());
        }
    }
}
