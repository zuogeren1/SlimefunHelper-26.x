package me.matl114.mixins.events;

import me.matl114.utils.ClientUtils;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.MouseDragAction;
import me.matl114.events.impl.MouseMoveAction;
import me.matl114.managers.input.SimpleInputManager;
import me.matl114.utils.ScreenUtils;
import me.matl114.utils.collections.FPoint;
import me.matl114.utils.collections.Point;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(value = MouseHandler.class, priority = 1)
public abstract class MouseEvents {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Shadow
    public abstract double xpos();

    @Shadow
    public abstract double ypos();
    //    @Inject(method = "onCursorPos",
    //            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Mouse;hasResolutionChanged:Z", ordinal = 0))
    //    private void onMouseMove(long handle, double xpos, double ypos, CallbackInfo ci)
    //    {//暂时没东西
    //
    //    }

    @Shadow
    public MouseButtonInfo activeButton;

    @Inject(
            method = "onScroll",
            cancellable = true,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    //#if MC >= 26.2
                                    "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;"))
                                    //#else
                                    //$$ "Lnet/minecraft/client/Minecraft;getOverlay()Lnet/minecraft/client/gui/screens/Overlay;"))
                                    //#endif
    private void onMouseScroll(long handle, double xOffset, double yOffset, CallbackInfo ci) { // 暂时没东西
        if (ClientUtils.getOverlay() == null) {
            if (SimpleInputManager.getInstance().onMouseScroll(xOffset, yOffset)) {
                ci.cancel();
            }
        }
    }

    @WrapOperation(
            method = "onScroll",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V"))
    private void onScrollInventorySelectedSlot(Inventory instance, int slot, Operation<Void> original) {
        Event<Integer> hotBar = new Event<>(slot, true, true);
        Listener.getPlayerScrollHotBar().handleValue(hotBar);
        if (hotBar.isCancelled()) return;
        original.call(instance, (int) hotBar.context());
    }

    @Inject(
            method = "onButton",
            cancellable = true,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    //#if MC >= 26.2
                                    "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;",
                                    //#else
                                    //$$ "Lnet/minecraft/client/Minecraft;getOverlay()Lnet/minecraft/client/gui/screens/Overlay;",
                                    //#endif
                            ordinal = 0,
                            shift = At.Shift.BEFORE))
    private void onMouseClick(
            long window,
            MouseButtonInfo input,
            int action,
            CallbackInfo ci,
            @Local(ordinal = 1) MouseButtonInfo input2) {
        Point coord = ScreenUtils.getMouseCoord(this.minecraft, (MouseHandler) (Object) this);
        if (SimpleInputManager.getInstance()
                .onMouseClick(coord.x, coord.y, input2.button(), action, input2.modifiers())) {
            ci.cancel();
        }
    }

    @WrapOperation(
            method = "handleAccumulatedMovement",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseMoved(DD)V"))
    private void onMouseMove(Screen instance, double f, double g, Operation<Void> original) {
        Event<MouseMoveAction> event =
                new Event<>(new MouseMoveAction((MouseHandler) (Object) this, f, g), true, false);
        Listener.getMouseMove().handleValue(event);
        if (!event.isCancelled()) {
            original.call(instance, f, g);
        }
    }

    @WrapOperation(
            method = "handleAccumulatedMovement",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/screens/Screen;mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z"))
    private boolean onMouseDrag(
            Screen instance, MouseButtonEvent click, double v1, double v2, Operation<Boolean> original) {
        Event<MouseDragAction> event = new Event<>(
                new MouseDragAction((MouseHandler) (Object) this, click.x(), click.y(), v1, v2), true, false);
        Listener.getMouseDrag().handleValue(event);
        if (!event.isCancelled()) {
            return original.call(instance, click, v1, v2);
        }
        return false;
    }

    @Inject(
            method = "handleAccumulatedMovement",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"),
            cancellable = true)
    private void onScreenNull(CallbackInfo ci) {
        // handle screen is null case, we should also send Events
        if (ClientUtils.getScreen() == null
                && ClientUtils.getOverlay() == null) {
            double f = xpos()
                    * (double) this.minecraft.getWindow().getGuiScaledWidth()
                    / (double) this.minecraft.getWindow().getScreenWidth();
            double g = ypos()
                    * (double) this.minecraft.getWindow().getGuiScaledHeight()
                    / (double) this.minecraft.getWindow().getScreenHeight();
            Event<MouseMoveAction> event =
                    new Event<>(new MouseMoveAction((MouseHandler) (Object) this, f, g), true, false);
            Listener.getMouseMove().handleValue(event);

            if (this.activeButton != null) {
                double h = this.accumulatedDX
                        * (double) this.minecraft.getWindow().getGuiScaledWidth()
                        / (double) this.minecraft.getWindow().getScreenWidth();
                double i = this.accumulatedDY
                        * (double) this.minecraft.getWindow().getGuiScaledHeight()
                        / (double) this.minecraft.getWindow().getScreenHeight();
                Event<MouseDragAction> event2 =
                        new Event<>(new MouseDragAction((MouseHandler) (Object) this, f, g, h, i), true, false);
                Listener.getMouseDrag().handleValue(event2);
            }
        }
    }

    @WrapOperation(
            method = "turnPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void onMouseUpdateLook(LocalPlayer instance, double x, double y, Operation<Void> original) {
        Event<FPoint> event = new Event<>(new FPoint(x, y), true, true);
        Listener.getPlayerChangeLook().handleValue(event);
        if (!event.isCancelled()) {
            original.call(instance, event.context().x, event.context().y);
        }
    }
}
