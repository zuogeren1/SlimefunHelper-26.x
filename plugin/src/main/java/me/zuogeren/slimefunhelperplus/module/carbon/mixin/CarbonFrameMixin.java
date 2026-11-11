/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon.mixin;

import me.zuogeren.slimefunhelperplus.module.carbon.CarbonRenderer;
//#if MC >= 260200
import net.minecraft.client.renderer.SubmitNodeCollector;
//#else
//$$ import net.minecraft.client.Camera;
//$$ import net.minecraft.client.DeltaTracker;
//$$ import net.minecraft.client.renderer.culling.Frustum;
//#endif
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 帧首快照的注入点。
 *
 * <p>26.1.2 的世界渲染仍是「提取可见实体 → 各自提交」，最后一击落在 {@code extractVisibleEntities}；
 * 26.2 起改成两阶段提交，提取被 {@code submitFeatures} 收拢（26.3 签名相同）。
 * 两者都带 {@link LevelRenderState}，相机状态从它上面取，所以注入体只差参数表。
 */
@Mixin(LevelRenderer.class)
public abstract class CarbonFrameMixin {

    //#if MC >= 260200
    @Inject(
            method = "submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V",
            at = @At("HEAD"),
            require = 0)
    private void carbon$beginFrame(
            LevelRenderState levelRenderState, SubmitNodeCollector collector, boolean renderOutline, CallbackInfo callback) {
        CarbonRenderer.beginFrame(levelRenderState.cameraRenderState);
    }
    //#else
    //$$ @Inject(
    //$$         method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
    //$$         at = @At("HEAD"),
    //$$         require = 0)
    //$$ private void carbon$beginFrame(
    //$$         Camera camera, Frustum frustum, DeltaTracker delta, LevelRenderState levelRenderState, CallbackInfo callback) {
    //$$     CarbonRenderer.beginFrame(levelRenderState.cameraRenderState);
    //$$ }
    //#endif
}
