package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.TooltipsRender;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 把 tooltip 的最终绘制位置暴露给事件监听方。
 *
 * <p>tooltip 的坐标在提交渲染前才确定，滚动类功能需要在这个时刻调整 y 偏移，
 * 因此这里包住 {@code tooltip} 本身：先广播事件，再用事件里可能被改过的坐标调用原方法。
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class DrawContextEvents {
    @WrapMethod(method = "tooltip")
    private void onRenderTooltip(
            Font font,
            List<ClientTooltipComponent> components,
            int x,
            int y,
            ClientTooltipPositioner positioner,
            @Nullable Identifier texture,
            Operation<Void> original) {
        TooltipsRender tooltipRender = new TooltipsRender(
                VDrawContext.of((GuiGraphicsExtractor) (Object) this), components, x, y, positioner, texture);
        RenderListener.getRenderTooltipEvent().broadcast(tooltipRender);
        original.call(font, components, tooltipRender.x, tooltipRender.y, positioner, texture);
    }
}
