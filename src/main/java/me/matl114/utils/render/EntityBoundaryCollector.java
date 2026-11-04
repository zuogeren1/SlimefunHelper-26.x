package me.matl114.utils.render;

import me.matl114.versioned.api.VDrawContext;

/**
 * 实体边界（boundary）收集器：只画掩膜剪影外沿的一圈，不填充。
 *
 * <p>与 {@link EntityFaceCollector} 共用同一份模型面与同一条掩膜管线，区别只在合成参数：
 * 填充色传 0、描边色传实体颜色，于是只有 {@code mask_outline} 那一趟生效 ——
 * 5x5 邻域、2.5 像素半径、只画掩膜之外的部分，所以宽度与距离无关，也不会盖住实体本体。
 */
public final class EntityBoundaryCollector extends EntityOutlineCollector {
    public EntityBoundaryCollector() {
        super();
    }

    @Override
    public void render2D(VDrawContext context, float partialTick) {
        renderQuads2D(partialTick, (quads, camera, projection, color) ->
                MaskPostRenderer.draw(quads, camera, projection, 0, color));
    }
}
