package me.matl114.renderer.text;

import me.matl114.plugins.internal.MainPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;

public final class CometTextDrawer extends ResourceFontTextDrawer {
    public static final Identifier COMET_FONT_ID = MainPlugin.identifier("comet");

    public CometTextDrawer() {
        this(Minecraft.getInstance().font);
    }

    public CometTextDrawer(Font delegate) {
        super(delegate, COMET_FONT_ID);
    }
}
