package me.matl114.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.api.VDrawContext;

public interface RenderCollector<B> {
    void submit(B val, int color);

    void clear();

    void render3D(PoseStack matrices, float partialTick);

    void render2D(VDrawContext vDrawContext, float partialTick);

    /**
     * 旧调用点入口：拿不到 partialTick 的场合按 0 处理（插值静止）。
     *
     * @deprecated 新代码请用带 {@code partialTick} 的重载
     */
    @Deprecated
    default void render3D(PoseStack matrices) {
        render3D(matrices, 0.0F);
    }

    /** @deprecated 新代码请用带 {@code partialTick} 的重载 */
    @Deprecated
    default void render2D(VDrawContext vDrawContext) {
        render2D(vDrawContext, 0.0F);
    }

    public abstract static class Impl<B> implements RenderCollector<B> {
        protected List<IndexEntry<B>> entries = new ArrayList<>();

        @Override
        public void submit(B val, int color) {
            entries.add(new IndexEntry<>(color, val));
        }

        public void clear() {
            if (!entries.isEmpty()) {
                entries.clear();
            }
        }
    }
}
