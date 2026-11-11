/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon.mixin;

import me.zuogeren.slimefunhelperplus.module.carbon.CarbonRenderer;
//#if MC >= 260300
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
 * <p>取 {@code renderLevel} 收尾而不是 feature 渲染器内部：那一刻世界已经画完、没有任何 RenderPass 还开着，
 * 可以安全地走裸 GL 绑主目标纹理自绘（{@link me.zuogeren.slimefunhelperplus.module.carbon.GpuFrame} 负责状态存档），
 * 深度缓冲也正好是这一帧世界留下的。
 *
 * <p>描述符必须写全：{@code require = 0} 只在「按名字找不到方法」时降级，
 * 若是按名字找到了、参数表却对不上，Mixin 会直接抛 {@code InvalidInjectionException} 让游戏起不来。
 * 26.3 把 {@code renderLevel} 拆成无参版本（{@code update} / {@code extract} 各司其职），必须分开写。
 */
@Mixin(GameRenderer.class)
public abstract class CarbonFlushMixin {

    //#if MC >= 260300
    //$$ @Inject(method = "renderLevel()V", at = @At("RETURN"), require = 0)
    //$$ private void carbon$flush(CallbackInfo callback) {
    //$$     CarbonRenderer.flush();
    //$$ }
    //#else
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"), require = 0)
    private void carbon$flush(DeltaTracker delta, CallbackInfo callback) {
        CarbonRenderer.flush();
    }
    //#endif
}
