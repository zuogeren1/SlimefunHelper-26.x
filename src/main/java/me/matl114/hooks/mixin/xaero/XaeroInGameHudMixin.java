package me.matl114.hooks.mixin.xaero;

import me.matl114.hacks.utils.ClientUtils;

import me.matl114.accessors.access.GuiScreenAccess;
import me.matl114.hacks.modules.survival.XaeroHelper;
import me.matl114.hooks.XaeroHooks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#if MC >= 260200
import net.minecraft.client.gui.Hud;
//#else
//$$ import net.minecraft.client.gui.Gui;
//#endif
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
//#if MC >= 260200
@Mixin(Hud.class)
//#else
//$$ @Mixin(Gui.class)
//#endif
public abstract class XaeroInGameHudMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Unique
    Screen cachedScreen;

    @Inject(method = "extractRenderState", at = @At("HEAD"), order = 1)
    private void onTransparentGuiFix(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (XaeroHelper.INSTANCE.transparentGuiMapFix.get()
                && XaeroHooks.getInstance().isXaeroPlusEnable()
//#if MC >= 260200
                && XaeroHooks.getInstance().isGuiMap(ClientUtils.getScreen(minecraft))) {
//#else
//$$                 && XaeroHooks.getInstance().isGuiMap(minecraft.screen)) {
//#endif
//#if MC >= 260200
            cachedScreen = ClientUtils.getScreen(minecraft);
//#else
//$$             cachedScreen = minecraft.screen;
//#endif
            // 裸字段写入：不能用 gui.setScreen()，那会每帧触发整套屏幕生命周期
//#if MC >= 260200
            ((GuiScreenAccess) minecraft.gui).setScreenRaw(null);
//#else
//$$             ((GuiScreenAccess) minecraft).setScreenRaw(null);
//#endif
        }
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), order = 99999)
    private void onTransparentGuiRestore(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (cachedScreen != null) {
//#if MC >= 260200
            ((GuiScreenAccess) minecraft.gui).setScreenRaw(cachedScreen);
//#else
//$$             ((GuiScreenAccess) minecraft).setScreenRaw(cachedScreen);
//#endif
            cachedScreen = null;
        }
    }
}
