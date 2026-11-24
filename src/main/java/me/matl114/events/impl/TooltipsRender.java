package me.matl114.events.impl;

import java.util.List;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * 一次 tooltip 绘制的现场快照：绘制上下文、已排好的组件、位置与位置求解器。
 *
 * <p>tooltip 的位置在真正提交渲染之前才最终确定，因此这里把 {@code x}/{@code y} 暴露为可变字段，
 * 监听方（如滚动 tooltip）可以就地调整偏移，再由调用方用调整后的坐标提交。
 */
public class TooltipsRender {
    public final VDrawContext context;
    public final List<ClientTooltipComponent> components;
    public final ClientTooltipPositioner positioner;

    @Nullable
    public final Identifier texture;

    public int x;
    public int y;

    public TooltipsRender(
            VDrawContext context,
            List<ClientTooltipComponent> components,
            int x,
            int y,
            ClientTooltipPositioner positioner,
            @Nullable Identifier texture) {
        this.context = context;
        this.components = components;
        this.x = x;
        this.y = y;
        this.positioner = positioner;
        this.texture = texture;
    }
}
