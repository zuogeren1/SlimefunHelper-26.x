/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon;

/**
 * 取 {@code GpuBuffer} 背后的 GL 缓冲句柄。
 *
 * <p>三个版本的 {@code GlBuffer} 都有 {@code handle} 字段，但只有 26.2 起才补了同名公有方法
 * （26.1.2 是 {@code protected int handle}，没有 getter），接口上更是从来没有 —— 于是用
 * {@code @Accessor} 读字段，三个版本共用一份实现，不必为版本差异再分叉。
 */
public interface CarbonBufferAccess {
    int carbon$handle();
}
