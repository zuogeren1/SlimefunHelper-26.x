/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon.mixin;

import me.zuogeren.slimefunhelperplus.module.carbon.CarbonRenderer;
//#if MC >= 260300
//$$ import net.minecraft.client.renderer.state.OptionsRenderState;
//$$ import net.minecraft.client.renderer.state.level.CameraRenderState;
//$$ import net.minecraft.client.renderer.state.level.PlayerRenderState;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#else
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#endif
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

/**
 * 绘制出口。
 *
 * <p>26.3 取 {@code render3dHud} 的开头：{@code renderLevel} 里先 {@code levelRenderer.render}、再 {@code render3dHud}，
 * 而主目标的深度是在 {@code render3dHud} 内部（画手之前）被清掉的 —— 晚于这一点就只剩一张全 0 的远平面，
 * 掉落物会穿墙可见。早于这一点，深度还是这一帧世界留下的，既没有打开的 RenderPass，后处理也还没跑。
 * 26.2 与 26.1.2 没有 {@code render3dHud}：主目标深度是在 {@code renderLevel} 里、切 HUD 投影之后、画手之前被清掉的，
 * 所以出图点取「{@code Projection.setupPerspective} 调用之前」——那一句在 {@code renderLevel} 里恰好一次，
 * 且此时世界已经画完、深度还是世界的、没有任何 RenderPass 开着。
 *
 * <p>描述符必须写全：{@code require = 0} 只在「按名字找不到方法」时降级，
 * 若是按名字找到了、参数表却对不上，Mixin 会直接抛 {@code InvalidInjectionException} 让游戏起不来。
 * 26.3 把 {@code renderLevel} 拆成无参版本（{@code update} / {@code extract} 各司其职），必须分开写。
 */
@Mixin(GameRenderer.class)
public abstract class CarbonFlushMixin {

    //#if MC >= 260300
    //$$ @Inject(
    //$$         method = "render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",
    //$$         at = @At("HEAD"),
    //$$         require = 0)
    //$$ private void carbon$flush(
    //$$         CameraRenderState camera, PlayerRenderState player, OptionsRenderState options, boolean renderHand, CallbackInfo callback) {
    //$$     CarbonRenderer.flush();
    //$$ }
    //#else
    @Inject(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V",
                    shift = At.Shift.BEFORE),
            require = 0)
    private void carbon$flush(DeltaTracker delta, CallbackInfo callback) {
        CarbonRenderer.flush();
    }
    //#endif
}
