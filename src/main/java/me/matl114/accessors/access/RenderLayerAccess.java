package me.matl114.accessors.access;

import java.util.Optional;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

public interface RenderLayerAccess {
    public Optional<Identifier> getRenderTexture();

    static RenderLayerAccess of(RenderType layer) {
        return (RenderLayerAccess) layer;
    }
}
