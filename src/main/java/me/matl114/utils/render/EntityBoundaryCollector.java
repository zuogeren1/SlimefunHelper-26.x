package me.matl114.utils.render;

/**
 * 实体轮廓（boundary）收集器。
 *
 * <p>上游 1.2.1.3 这一层走的是遮罩后处理：把实体面渲染进离屏目标，再用 mask shader 合成剪影
 * （{@code MaskPostRenderer} + {@code RenderCollector.renderQuads2D}）。26.2 没有
 * {@code VertexConsumerProvider} / {@code RenderDispatcher}，那套管线无法直译，因此这里退化为
 * 「抓面之后按剪影画边线」——与 {@link EntityOutlineCollector} 共用实现，
 * 但在 {@code EntityESP} 里与线框盒（{@code EntityBoxCollector}）是两个不同的视觉层。
 */
public final class EntityBoundaryCollector extends EntityOutlineCollector {
    public EntityBoundaryCollector() {
        super();
    }
}
