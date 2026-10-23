package me.matl114.mixins.events;

import me.matl114.utils.ClientUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.Setter;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.accessors.interfaces.MetadataHolder;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.RenderScreen;
import me.matl114.gui.basic.DisplayWidget;
import me.matl114.versioned.api.VDrawContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Environment(EnvType.CLIENT)
@Mixin(Screen.class)
public abstract class ScreenEvents extends AbstractContainerEventHandler implements MetadataHolder, ScreenAccess {
    @Unique
    List<Consumer<Screen>> initializeTasks;

    public void addInitTask(Consumer<Screen> runnable) {
        if (initializeTasks == null) {
            initializeTasks = new ArrayList<>();
        }
        initializeTasks.add(runnable);
    }

    @Unique
    List<Runnable> screenCloseFuture;

    public void addCloseFuture(Runnable runnable) {
        if (screenCloseFuture == null) {
            screenCloseFuture = new ArrayList<>();
        }
        screenCloseFuture.add(runnable);
    }

    @Inject(method = "onClose", at = @At(value = "RETURN"))
    private void onScreenClsoe(CallbackInfo ci) {
        Listener.getPostCloseScreen().broadcast((Screen) (AbstractContainerEventHandler) this);
        if (screenCloseFuture != null) {
            for (Runnable runnable : screenCloseFuture) {
                runnable.run();
            }
        }
    }

    @Inject(
            method = "init(II)V",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/screens/Screen;setInitialFocus()V",
                            shift = At.Shift.AFTER))
    public void onPostInitialization(int width, int height, CallbackInfo ci) {
        // first initialize
        Listener.getPostInitializeScreen().broadcast((Screen) (AbstractContainerEventHandler) this);
        if (initializeTasks != null) {
            for (Consumer<Screen> runnable : initializeTasks) {
                runnable.accept((Screen) (AbstractContainerEventHandler) this);
            }
        }
    }

    @Inject(
            method = "init(II)V",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/screens/Screen;repositionElements()V",
                            shift = At.Shift.AFTER))
    public void onClearAndInit(CallbackInfo ci) {
        Listener.getPostInitializeScreen().broadcast((Screen) (AbstractContainerEventHandler) this);
        if (initializeTasks != null) {
            for (Consumer<Screen> runnable : initializeTasks) {
                runnable.accept((Screen) (AbstractContainerEventHandler) this);
            }
        }
    }

    @Inject(
            method = "resize",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/screens/Screen;repositionElements()V",
                            shift = At.Shift.AFTER))
    public void onResize(int width, int height, CallbackInfo ci) {
        Listener.getPostInitializeScreen().broadcast((Screen) (AbstractContainerEventHandler) this);
        if (initializeTasks != null) {
            for (Consumer<Screen> runnable : initializeTasks) {
                runnable.accept((Screen) (AbstractContainerEventHandler) this);
            }
        }
    }

    @Shadow
    protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(
            T drawableElement);

    @Shadow
    protected void removeWidget(GuiEventListener child) {}

    @Shadow
    protected abstract <T extends Renderable> T addRenderableOnly(T drawable);

    @Unique
    public <T extends GuiEventListener & Renderable & NarratableEntry> T addDrawableChildTo(T drawable) {
        if (drawable instanceof DisplayWidget display) {
            addRenderableOnly(display);
            return drawable;
        } else {
            return addRenderableWidget(drawable);
        }
    }

    @Unique
    public void removeChildFrom(GuiEventListener val) {
        removeWidget(val);
    }

    @Getter
    @Setter
    @Unique
    Screen parent = null;

    @Unique
    public void open() {
        ClientUtils.setScreen(Minecraft.getInstance(), (Screen) (Object) this);
    }

    @Unique
    public void openFromCurrent() {
        parent = ClientUtils.getScreen();
        open();
    }

    @Unique
    public void openFrom(Screen parent) {
        this.parent = parent;
        open();
    }

    @Unique
    public void switchToScreen(Screen anotherScreen) {
        Screen p = this.parent;
        this.parent = null;
        ScreenAccess.of(anotherScreen).setParent(p);
        ClientUtils.setScreen(Minecraft.getInstance(), anotherScreen);
    }

    public void switchFromCurrent() {
        Screen current = ClientUtils.getScreen();
        if (current == null) {
            this.parent = null;
        } else {
            this.parent = ((ScreenEvents) (Object) current).parent;
            ((ScreenEvents) (Object) current).parent = null;
        }
        ClientUtils.setScreen(Minecraft.getInstance(), (Screen) (Object) this);
    }

    @ModifyArgs(
            method = "onClose",
            at =
                    @At(
                            value = "INVOKE",
                            target =
//#if MC >= 26.2
                                    "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
//#else
//$$                                 "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
//#endif
    public void onRedirectReturnScreen(Args args) {
        if (parent != null) {
            args.set(0, parent);
            parent = null;
        }
    }

    @Inject(
            method = "extractRenderStateWithTooltipAndSubtitles",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
                            shift = At.Shift.BEFORE),
            cancellable = true)
    private void onRenderPre(
            GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!RenderListener.getRenderScreen()
                .fireEvent(new RenderScreen(
                        VDrawContext.of(context),
                        (Screen) (Object) this,
                        RenderScreen.Stage.PRE,
                        mouseX,
                        mouseY,
                        deltaTicks))) {
            ci.cancel();
        }
    }

    @Inject(
            method = "extractRenderStateWithTooltipAndSubtitles",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
                            shift = At.Shift.BEFORE),
            cancellable = true)
    private void onRenderMid(
            GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!RenderListener.getRenderScreen()
                .fireEvent(new RenderScreen(
                        VDrawContext.of(context),
                        (Screen) (Object) this,
                        RenderScreen.Stage.MID,
                        mouseX,
                        mouseY,
                        deltaTicks))) {
            ci.cancel();
        }
    }

    @Inject(
            method = "extractRenderStateWithTooltipAndSubtitles",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;extractDeferredElements(IIF)V",
                            shift = At.Shift.BEFORE),
            cancellable = true)
    private void onRenderPost(
            GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!RenderListener.getRenderScreen()
                .fireEvent(new RenderScreen(
                        VDrawContext.of(context),
                        (Screen) (Object) this,
                        RenderScreen.Stage.POST,
                        mouseX,
                        mouseY,
                        deltaTicks))) {
            ci.cancel();
        }
    }
}

