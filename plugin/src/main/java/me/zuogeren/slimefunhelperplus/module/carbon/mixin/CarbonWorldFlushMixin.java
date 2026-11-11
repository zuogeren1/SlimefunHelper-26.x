/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon.mixin;

import me.zuogeren.slimefunhelperplus.module.carbon.CarbonRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 世界帧刚画完就出图（早于 HUD 分支）。
 *
 * <p>26.2 / 26.1.2 没有 {@code render3dHud}：主目标深度是在 {@code renderLevel} 里、切 HUD 投影之后才处理的，
 * 而那些版本的出图点一旦落到那之后，深度纹理里就只剩空的远平面（实测屏幕正中读出来是 0.0），掉落物会穿墙。
 * 这里把出图点提前到 {@code LevelRenderer} 的世界帧收尾——帧图已经执行完、还没有任何 HUD 相关状态。
 *
 * <p>26.3 的同名方法签名不同，注入点找不到就按 {@code require = 0} 跳过，那一版仍走 {@code render3dHud}。
 * 回调刻意不声明目标参数：这样即使换个版本解析失败，也不会牵扯到版本专属类型的加载。
 */
@Mixin(LevelRenderer.class)
public abstract class CarbonWorldFlushMixin {

    //#if MC >= 260200
    @Inject(
            method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            at = @At("RETURN"),
            require = 0)
    private void carbon$flushAfterWorld(CallbackInfo callback) {
        CarbonRenderer.flush();
    }
    //#else
    //$$ @Inject(
    //$$         method = "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V",
    //$$         at = @At("RETURN"),
    //$$         require = 0)
    //$$ private void carbon$flushAfterWorld(CallbackInfo callback) {
    //$$     CarbonRenderer.flush();
    //$$ }
    //#endif
}
