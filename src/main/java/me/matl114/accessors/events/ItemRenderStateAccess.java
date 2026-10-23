package me.matl114.accessors.events;

import java.util.List;
import me.matl114.events.model.GuiModel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;

public interface ItemRenderStateAccess {
    public List<GuiModel.Entry> getAttachedRenderState();

    public void clearAttachedRenderState();

    public void setDisplayContext(ItemDisplayContext displayContext);

    public static ItemRenderStateAccess of(ItemStackRenderState state) {
        return (ItemRenderStateAccess) state;
    }
}
