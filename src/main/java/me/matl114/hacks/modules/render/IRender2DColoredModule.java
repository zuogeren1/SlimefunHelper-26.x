package me.matl114.hacks.modules.render;

import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

public abstract class IRender2DColoredModule extends IRender2DModule {
    public IRender2DColoredModule() {
        super("IRender2DColoredModule");
    }

    public IRender2DColoredModule(String name) {
        super(name);
    }

    public NBTRef<WrapColor> color = builder(hud.add("color"), WrapColor.class)
            .defaultValue(new WrapColor(TextColor.parseColor("#F05BDA").getOrThrow()))
            .build();

    public FlagRef bold = flagBuilder(hud.add("bold")).defaultValue(true).build();

    public void drawText(VDrawContext vdraw, FormattedCharSequence text) {
        float textHeight = vdraw.getTextDrawer().getTextHeight() + 1;
        int rgb = color.get().withAlpha(255);
        if (right.get()) {
            int width = vdraw.getTextDrawer().getWidth(text);
            vdraw.drawText(vdraw.getTextDrawer(), text, -width, 0, rgb, false);
        } else {
            vdraw.drawText(vdraw.getTextDrawer(), text, 0, 0, rgb, false);
        }
        vdraw.getMatrices().translate(0, textHeight);
    }

    public void drawText(VDrawContext vdraw, Component text) {
        if (bold.get()) {
            text = text.copy().withStyle(ChatFormatting.BOLD);
        }
        drawText(vdraw, text.getVisualOrderText());
    }

    public void drawText(VDrawContext vdraw, String text) {
        MutableComponent text0 = Component.literal(text);
        if (bold.get()) {
            text0 = text0.withStyle(ChatFormatting.BOLD);
        }
        drawText(vdraw, text0.getVisualOrderText());
    }
}
