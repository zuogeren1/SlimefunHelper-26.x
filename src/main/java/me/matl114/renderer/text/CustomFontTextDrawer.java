package me.matl114.renderer.text;

import me.matl114.plugins.MainPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;

public final class CustomFontTextDrawer extends ResourceFontTextDrawer {
    public static final Identifier UNICODE_FONT_ID = MainPlugin.identifier("unicode");

    public CustomFontTextDrawer() {
        this(Minecraft.getInstance().font);
    }

    public CustomFontTextDrawer(Font delegate) {
        super(delegate, UNICODE_FONT_ID);
    }
}
