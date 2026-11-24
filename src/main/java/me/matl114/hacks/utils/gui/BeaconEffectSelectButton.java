package me.matl114.hacks.utils.gui;

import java.util.List;
import lombok.Getter;
import me.matl114.utils.ScreenUtils;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

public class BeaconEffectSelectButton extends AbstractButton {
    public static final List<Holder<MobEffect>> EFFECTS_BEACON = List.of(
            MobEffects.SPEED,
            MobEffects.HASTE,
            MobEffects.RESISTANCE,
            MobEffects.JUMP_BOOST,
            MobEffects.STRENGTH,
            MobEffects.REGENERATION);
    private static final int SIZE = EFFECTS_BEACON.size();
    private static Identifier NO_PATH = new Identifier("minecraft", "container/beacon/cancel");
    static final Identifier BUTTON_HIGHLIGHTED_TEXTURE =
            new Identifier("minecraft", "container/beacon/button_highlighted");
    static final Identifier BUTTON_TEXTURE = new Identifier("minecraft", "container/beacon/button");
    int currentIndex = 0;

    @Getter
    Holder<MobEffect> currentEffect;

    Identifier currentSprite;

    private void updateCurrentEffect() {
        currentIndex %= (SIZE + 1);
        if (currentIndex == 0) {
            this.currentEffect = null;
            this.currentSprite = null;
        } else {
            this.currentEffect = EFFECTS_BEACON.get(currentIndex - 1);
            //#if MC >= 26.2
            this.currentSprite = net.minecraft.client.gui.Hud.getMobEffectSprite(this.currentEffect);
            //#else
            //$$ this.currentSprite = net.minecraft.client.gui.Gui.getMobEffectSprite(this.currentEffect);
            //#endif
        }
        setTooltip(Tooltip.create(createNarrationMessage()));
    }

    public BeaconEffectSelectButton(int i, int j, int k, int l, Component text) {
        super(i, j, k, l, text);
        updateCurrentEffect();
    }

    @Override
    public void onPress(InputWithModifiers input) {
        currentIndex = currentIndex + EFFECTS_BEACON.size() + 1 + (ScreenUtils.hasShiftDown() ? -1 : 1);
        updateCurrentEffect();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        Identifier identifier;
        if (this.isHoveredOrFocused()) {
            identifier = BUTTON_HIGHLIGHTED_TEXTURE;
        } else {
            identifier = BUTTON_TEXTURE;
        }

        context.blitSprite(RenderPipelines.GUI_TEXTURED, identifier, this.getX(), this.getY(), this.width, this.height);
        this.renderExtra(context);
    }

    public void extractWidgetRenderState(VDrawContext context, int mouseX, int mouseY, float delta) {}

    protected void renderExtra(GuiGraphicsExtractor context) {
        if (this.currentSprite != null) {
            context.blitSprite(
                    RenderPipelines.GUI_TEXTURED, this.currentSprite, this.getX() + 2, this.getY() + 2, 18, 18);
        } else {
            context.blitSprite(RenderPipelines.GUI_TEXTURED, NO_PATH, this.getX() + 2, this.getY() + 2, 18, 18);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
        this.defaultButtonNarrationText(builder);
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return getMessage()
                .copy()
                .append(
                        this.currentEffect == null
                                ? Component.translatable("widget.gui.beacon-effect-select-button.no-selection")
                                : Component.translatable(
                                        this.currentEffect.value().getDescriptionId()));
    }
}
