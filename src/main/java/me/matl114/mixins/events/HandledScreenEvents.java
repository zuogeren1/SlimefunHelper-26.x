package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.RenderSlot;
import me.matl114.versioned.api.VDrawContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
@Environment(EnvType.CLIENT)
public abstract class HandledScreenEvents extends Screen {

    protected HandledScreenEvents(Component title) {
        super(title);
    }

    @WrapOperation(
            method = "extractSlots",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V"))
    public void onRenderSlot(
            AbstractContainerScreen instance,
            GuiGraphicsExtractor context,
            Slot slot,
            int mouseX,
            int mouseY,
            Operation<Void> original) {
        if (!RenderListener.getRenderSlot()
                .fireEvent(new RenderSlot(VDrawContext.of(context), instance, slot, mouseX, mouseY))) {
            return;
        }
        original.call(instance, context, slot, mouseX, mouseY);
    }

    // fix mouse scroll dispatch
    @Inject(method = "mouseScrolled", at = @At("RETURN"), cancellable = true)
    public void onMouseScrolled(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount,
            CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        cir.setReturnValue(super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount));
    }
}
