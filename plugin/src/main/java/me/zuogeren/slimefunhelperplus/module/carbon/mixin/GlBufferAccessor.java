/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon.mixin;

//#if MC >= 260300
//$$ import com.mojang.renderpearl.backend.opengl.GlBuffer;
//#else
import com.mojang.blaze3d.opengl.GlBuffer;
//#endif
import me.zuogeren.slimefunhelperplus.module.carbon.CarbonBufferAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlBuffer.class)
public interface GlBufferAccessor extends CarbonBufferAccess {
    @Override
    @Accessor("handle")
    int carbon$handle();
}
