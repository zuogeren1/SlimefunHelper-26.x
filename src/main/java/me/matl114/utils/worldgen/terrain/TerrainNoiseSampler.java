package me.matl114.utils.worldgen.terrain;

//#if MC >= 260300
//$$ // 26.3 把 NoiseChunk 改成「一次建好 DensityVolume + DensitySampler」的形式，只剩 volume()/aquifer()/cachingSamplers()，
//$$ // 不再暴露逐格采样状态机，无法按行驱动；因此本类不提供 26.3 实现，依赖它的地形/地牢预测在 26.3 降级为「不支持」。
//#else
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jetbrains.annotations.Nullable;

/** Exposes vanilla's protected per-block sample so a sampler can cover only some cell rows. */
final class TerrainNoiseSampler extends NoiseChunk {
    TerrainNoiseSampler(
            int horizontalCellCount,
            RandomState randomState,
            int startBlockX,
            int startBlockZ,
            NoiseSettings shape,
            DensityFunctions.BeardifierOrMarker beardifying,
            NoiseGeneratorSettings settings,
            Aquifer.FluidPicker fluidLevelSampler) {
        super(
                horizontalCellCount,
                randomState,
                startBlockX,
                startBlockZ,
                shape,
                beardifying,
                settings,
                fluidLevelSampler,
                Blender.empty());
    }

    @Nullable
    BlockState sample() {
        return getInterpolatedState();
    }
}
//#endif
