package me.matl114.hooks.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import me.matl114.hacks.modules.render.Xray;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer", remap = false)
public abstract class SodiumDefaultFluidRendererXrayMixin {
    @Shadow
    @Final
    private int[] quadColors;

    @Unique
    private int slimefunhelper$xrayAlpha = -1;

    @Unique
    private boolean slimefunhelper$forceXrayFluidSides;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void slimefunhelper$applyXray(
            @Coerce Object level,
            BlockState blockState,
            FluidState fluidState,
            BlockPos blockPos,
            BlockPos offset,
            @Coerce Object collector,
            @Coerce Object meshBuilder,
            @Coerce Object material,
            @Coerce Object colorProvider,
            @Coerce Object fluidModel,
            CallbackInfo ci) {
        slimefunhelper$xrayAlpha = Xray.INSTANCE == null ? -1 : Xray.getFluidAlpha(fluidState, blockPos);
        slimefunhelper$forceXrayFluidSides = Xray.INSTANCE != null && Xray.isXrayActive();
        if (slimefunhelper$xrayAlpha == 0) {
            slimefunhelper$forceXrayFluidSides = false;
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0, expect = 0)
    private void slimefunhelper$resetXray(
            @Coerce Object level,
            BlockState blockState,
            FluidState fluidState,
            BlockPos blockPos,
            BlockPos offset,
            @Coerce Object collector,
            @Coerce Object meshBuilder,
            @Coerce Object material,
            @Coerce Object colorProvider,
            @Coerce Object fluidModel,
            CallbackInfo ci) {
        slimefunhelper$xrayAlpha = -1;
        slimefunhelper$forceXrayFluidSides = false;
    }

    @ModifyReturnValue(method = "isFullBlockFluidSideVisible", at = @At("RETURN"), require = 0, expect = 0)
    private boolean slimefunhelper$showFullBlockFluidSide(
            boolean original, BlockGetter view, BlockPos selfPos, Direction facing, FluidState fluidState) {
        if (original || !slimefunhelper$forceXrayFluidSides || facing.getAxis().isVertical()) return original;
        Xray xray = Xray.INSTANCE;
        if (xray == null) return original;
        BlockState otherState = view.getBlockState(selfPos.relative(facing));
        if (!xray.isBlocked(otherState.getBlock(), null)) return original;
        return !otherState.getFluidState().getType().isSame(fluidState.getType());
    }

    @ModifyReturnValue(
            method =
                    "isFluidSideExposed(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;F)Z",
            at = @At("RETURN"),
            require = 0,
            expect = 0,
            remap = false)
    private boolean slimefunhelper$showFluidSide(
            boolean original, BlockState ownState, BlockState otherState, Direction facing, float height) {
        if (original || !slimefunhelper$forceXrayFluidSides || facing.getAxis().isVertical()) return original;
        Xray xray = Xray.INSTANCE;
        if (xray == null) return original;
        if (!xray.isBlocked(otherState.getBlock(), null)) return original;
        return !otherState.getFluidState().getType().isSame(ownState.getFluidState().getType());
    }

    @ModifyReturnValue(
            method =
                    "isFluidSideExposed(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;F)Z",
            at = @At("RETURN"),
            require = 0,
            expect = 0,
            remap = false)
    private boolean slimefunhelper$showSliceFluidSide(
            boolean original,
            BlockAndTintGetter level,
            BlockState ownState,
            BlockPos otherPos,
            Direction facing,
            float height) {
        if (original || !slimefunhelper$forceXrayFluidSides || facing.getAxis().isVertical()) return original;
        Xray xray = Xray.INSTANCE;
        if (xray == null) return original;
        BlockState otherState = level.getBlockState(otherPos);
        if (!xray.isBlocked(otherState.getBlock(), null)) return original;
        return !otherState.getFluidState().getType().isSame(ownState.getFluidState().getType());
    }

    @Inject(method = "getUpFaceExposureByNeighbors", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void slimefunhelper$showUpFluidFace(
            @Coerce Object level, BlockPos origin, FluidState fluidState, CallbackInfoReturnable<Integer> cir) {
        if (slimefunhelper$forceXrayFluidSides) cir.setReturnValue(3);
    }

    @Inject(method = "updateQuad", at = @At("TAIL"), require = 0, expect = 0)
    private void slimefunhelper$setFluidQuadAlpha(
            @Coerce Object quad,
            @Coerce Object level,
            BlockPos pos,
            @Coerce Object lighter,
            Direction direction,
            @Coerce Object facing,
            float brightness,
            @Coerce Object colorProvider,
            FluidState fluidState,
            CallbackInfo ci) {
        int alpha = slimefunhelper$xrayAlpha;
        if (alpha > 0 && alpha < 255) {
            for (int vertex = 0; vertex < 4; vertex++) {
                quadColors[vertex] = (quadColors[vertex] & 0x00FFFFFF) | (alpha << 24);
            }
        }
    }

    @ModifyArg(
            method = "render",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/DefaultFluidRenderer;writeQuad(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuilder;Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;Lnet/minecraft/core/BlockPos;Lnet/caffeinemc/mods/sodium/client/model/quad/ModelQuadView;Lnet/caffeinemc/mods/sodium/client/model/quad/properties/ModelQuadFacing;Z)V",
                            remap = false),
            index = 2,
            require = 0,
            expect = 0)
    private Material slimefunhelper$useTranslucentMaterial(Material original) {
        int alpha = slimefunhelper$xrayAlpha;
        return alpha > 0 && alpha < 255 ? DefaultMaterials.TRANSLUCENT : original;
    }
}
