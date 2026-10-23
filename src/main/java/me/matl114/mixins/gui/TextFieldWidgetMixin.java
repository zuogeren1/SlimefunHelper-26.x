package me.matl114.mixins.gui;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import me.matl114.accessors.gui.TextFieldAccess;
import me.matl114.gui.McWidgetHelpers;
import me.matl114.gui.basic.ColorProvider;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EditBox.class)
@Environment(EnvType.CLIENT)
public abstract class TextFieldWidgetMixin extends AbstractWidget implements TextFieldAccess {
    @Unique
    private static final ColorProvider ORIGIN_PROVIDER = McWidgetHelpers.getDefaultTextBoxColorProvider();

    @Final
    @Shadow
    private Font font;

    @Shadow
    private String value;

    @Shadow
    private int displayPos;

    @Unique
    EditBox cast() {
        return (EditBox) (Object) this;
    }

    @Unique
    public boolean isMultiLine() {
        return false;
    }

    @Unique
    public void setBorderColorProvider(ColorProvider provider) {
        this.boxColorProvider = provider;
    }

    @Shadow
    private int cursorPos;

    @Shadow
    protected abstract void onValueChange(String newText);

    @Unique
    private ColorProvider boxColorProvider = null;

    public TextFieldWidgetMixin(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @WrapOperation(
            method = "extractWidgetRenderState",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    public void redirectBorderBoxRender(
            GuiGraphicsExtractor instance,
            RenderPipeline pipeline,
            Identifier sprite,
            int x,
            int y,
            int width,
            int height,
            Operation<Void> original) {
        if (boxColorProvider != null) {
            // use custom color provided
            McWidgetHelpers.drawTextWidgetBox(
                    this, instance, x, y, width, height, this.isFocused(), this.boxColorProvider);
        } else {
            original.call(instance, pipeline, sprite, x, y, width, height);
        }
    }

    @Inject(method = "keyPressed", at = @At(value = "RETURN"), cancellable = true)
    public void fixInventoryKeyPressedWhenFocused(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
        if (this.isFocused() && Minecraft.getInstance().options.keyInventory.matches(input)) {
            cir.setReturnValue(true);
        }
    }

    @Unique
    public void dragSelect(int deltaX, int deltaY, boolean shiftDownAction) {
        int i = deltaX;
        if (cast().isBordered()) {
            i -= 4;
        }

        String string = this.font.plainSubstrByWidth(
                this.value.substring(this.displayPos), this.cast().getInnerWidth());
        this.cast().moveCursorTo(this.font.plainSubstrByWidth(string, i).length() + this.displayPos, shiftDownAction);
    }

    @Unique
    public boolean canStartDrag(double mouseX, double mouseY) {
        return this.isMouseOver(mouseX, mouseY);
    }

    @Inject(method = "setFocused", at = @At("HEAD"))
    public void resetSelectOnRelease(boolean focused, CallbackInfo ci) {
        if (!focused) {
            resetSelect();
        }
    }

    @Unique
    public void resetSelect() {
        this.cast().setHighlightPos(this.cursorPos);
        this.onValueChange(this.value);
    }
}
