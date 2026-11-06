package me.matl114.mixins.events;

import me.matl114.utils.ClientUtils;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import me.matl114.accessors.access.GuiScreenAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 屏幕事件：PreSetScreen / MidSetScreen / PostSetScreen。
 *
 * <p>26.2 把屏幕管理整体从 {@code Minecraft} 搬到了 {@link Gui}
 * （{@code Minecraft.screen} 字段与 {@code Minecraft.setScreen} 均已移除，
 * 变为 {@code Gui.screen} / {@code Gui.setScreen}），所以这三个注入点从
 * {@code MinecraftClientEvents} 迁移到这里。挂在 {@code Gui.setScreen} 上
 * 也顺带覆盖了所有直接调用 {@code ClientUtils.setScreen(mc, ...)} 的路径。
 */
@Environment(EnvType.CLIENT)
//#if MC >= 26.2
@Mixin(Gui.class)
//#else
//$$ @Mixin(Minecraft.class)
//#endif
public abstract class GuiScreenEvents implements GuiScreenAccess {

    @Unique
    @Override
    public void setScreenRaw(Screen screen) {
        this.screen = screen;
    }

    @Shadow
    @Nullable
    private Screen screen;

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    public void onPreSetScreen(Screen screen, CallbackInfo ci, @Local(argsOnly = true) LocalRef<Screen> screenRef) {
        if (!Listener.getPreSetScreen().isEmpty()) {
            Event<Screen> screenEvent = new Event<>(screen, true, true);
            Listener.getPreSetScreen().handleValue(screenEvent);
            if (screenEvent.isCancelled()) {
                ci.cancel();
            } else {
                if (screenEvent.context != screen) {
                    screenRef.set(screenEvent.context);
                }
            }
        }
    }

    /**
     * 在 {@code this.screen = screen} 赋值之后触发（Gui.setScreen 中对该字段的第 3 次访问），
     * 此时 {@code this.screen} 已是新屏幕、但尚未 {@code added()/init()}。
     */
    @Inject(
            method = "setScreen",
            at =
                    @At(
                            value = "FIELD",
                            //#if MC >= 26.2
                            target = "Lnet/minecraft/client/gui/Gui;screen:Lnet/minecraft/client/gui/screens/Screen;",
                            //#else
                            //$$ target = "Lnet/minecraft/client/Minecraft;screen:Lnet/minecraft/client/gui/screens/Screen;",
                            //#endif
                            //#if MC >= 260300
                            //$$ // 26.3 的 setScreen 在赋值前还读了三次 this.screen（clearFocus/removed），
                            //$$ // 写入是第 4 次访问，所以这里要 ordinal = 3 才是「赋值之后」。
                            //$$ ordinal = 3,
                            //#else
                            ordinal = 2,
                            //#endif
                            shift = At.Shift.AFTER),
            cancellable = true)
    public void onMidSetScreen(Screen screen, CallbackInfo ci) {
        if (!Listener.getMidSetScreen().isEmpty()) {
            Event<Screen> screenEvent = new Event<>(this.screen, true, false);
            Listener.getMidSetScreen().handleValue(screenEvent);
            if (screenEvent.isCancelled()) {
                ci.cancel();
                // FIX: even if post set is cancelled , the screen must be initialized or exception will be thrown
                if (this.screen != null) {
                    Minecraft mc = Minecraft.getInstance();
                    this.screen.init(
                            mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                }
                Listener.getPostSetScreen().broadcast(this.screen);
            }
        }
    }

    @Inject(method = "setScreen", at = @At("RETURN"))
    public void onPostSetScreen(Screen screen, CallbackInfo ci) {
        Listener.getPostSetScreen().broadcast(this.screen);
    }
}
