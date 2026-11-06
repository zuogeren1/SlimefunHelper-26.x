package me.matl114.utils.structures;

import me.matl114.utils.RegistryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate.Sampler;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
//#if MC >= 260300
//$$ import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
//#endif

/** Seeded Nether biome and raw-noise sampling for ruined portal generation. */
public final class CubiomesNetherBiomeSampler {
    private final BiomeSource biomeSource;
    private final Sampler biomeNoise;
    private final RandomState randomState;
    private final NoiseBasedChunkGenerator terrainGenerator;
    private final LevelHeightAccessor heightLimitView;

    private CubiomesNetherBiomeSampler(
            BiomeSource biomeSource,
            Sampler biomeNoise,
            RandomState randomState,
            NoiseBasedChunkGenerator terrainGenerator) {
        this.biomeSource = biomeSource;
        this.biomeNoise = biomeNoise;
        this.randomState = randomState;
        this.terrainGenerator = terrainGenerator;
        this.heightLimitView = LevelHeightAccessor.create(0, 128);
    }

    public static CubiomesNetherBiomeSampler create(long seed) {
        HolderLookup.Provider registries = DefaultWorldgenRegistryManager.registry();
        Holder<MultiNoiseBiomeSourceParameterList> biomeParameters =
                RegistryUtils.getRegistryEntry(registries, MultiNoiseBiomeSourceParameterLists.NETHER);
        Holder<NoiseGeneratorSettings> generatorSettings =
                RegistryUtils.getRegistryEntry(registries, NoiseGeneratorSettings.NETHER);
        if (biomeParameters == null || generatorSettings == null) {
            throw new IllegalStateException("Vanilla Nether worldgen entries are unavailable");
        }
        BiomeSource biomeSource = MultiNoiseBiomeSource.createFromPreset(biomeParameters);
        RandomState randomState = RandomState.create(
                //#if MC >= 260300
                //$$ registries.lookupOrThrow(Registries.NOISE), seed, generatorSettings.value());
                //#else
                generatorSettings.value(), registries.lookupOrThrow(Registries.NOISE), seed);
                //#endif
        return new CubiomesNetherBiomeSampler(
                biomeSource,
                //#if MC >= 260300
                //$$ randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED),
                //#else
                randomState.sampler(),
                //#endif
                randomState,
                new NoiseBasedChunkGenerator(biomeSource, generatorSettings));
    }

    /** Samples the candidate chunk center at quart Y=0, matching the viewer's biome lookup. */
    public net.minecraft.resources.Identifier getRuinedPortalBiomeId(StructurePosition structure) {
        int quartX = Math.toIntExact(structure.chunkX() * 4L + 2L);
        int quartZ = Math.toIntExact(structure.chunkZ() * 4L + 2L);
        //#if MC >= 260300
        //$$ ResourceKey<Biome> biomeKey = biomeSource
        //$$         .createResolver(biomeNoise)
        //$$         .getNoiseBiome(quartX, 0, quartZ)
        //#else
        ResourceKey<Biome> biomeKey = biomeSource
                .getNoiseBiome(quartX, 0, quartZ, biomeNoise)
        //#endif
                .unwrapKey()
                .orElseThrow(() -> new IllegalStateException("Sampled biome has no registry key"));
        return biomeKey.identifier();
    }

    /** Applies vanilla's final ruined-portal biome-tag check at the generated origin. */
    public boolean isRuinedPortalBiomeValid(
            net.minecraft.resources.Identifier structureId, net.minecraft.core.BlockPos origin) {
        int quartX = origin.getX() >> 2;
        int quartY = origin.getY() >> 2;
        int quartZ = origin.getZ() >> 2;
        //#if MC >= 260300
        //$$ Holder<Biome> biome = biomeSource.createResolver(biomeNoise).getNoiseBiome(quartX, quartY, quartZ);
        //#else
        Holder<Biome> biome = biomeSource.getNoiseBiome(quartX, quartY, quartZ, biomeNoise);
        //#endif
        var structure = DefaultWorldgenRegistryManager.registry()
                .lookupOrThrow(Registries.STRUCTURE)
                .getValue(structureId);
        return structure != null && BiomeValidity.contains(structure.biomes(), biome);
    }

    /** Reproduces RuinedPortalStructure.findSuitableY for the IN_NETHER placement. */
    public int getRuinedPortalOriginY(RuinedPortalVariant variant, StructurePosition structure, long seed) {
        if (variant.verticalPlacement() != RuinedPortalVariant.VerticalPlacement.IN_NETHER) {
            throw new IllegalArgumentException("The selected portal setup is not the Nether placement");
        }

        RuinedPortalVariant.HorizontalBounds bounds = variant.horizontalBounds(structure);
        JavaLegacyRandom random = variant.randomAtOriginHeight(seed, structure);
        int y;
        if (variant.airPocket()) {
            y = betweenInclusive(random, 32, 100);
        } else if (random.nextFloat() < 0.5F) {
            y = betweenInclusive(random, 27, 29);
        } else {
            y = betweenInclusive(random, 29, 100);
        }

        NoiseColumn[] columns = {
            terrainGenerator.getBaseColumn(bounds.minX(), bounds.minZ(), heightLimitView, randomState),
            terrainGenerator.getBaseColumn(bounds.maxX(), bounds.minZ(), heightLimitView, randomState),
            terrainGenerator.getBaseColumn(bounds.minX(), bounds.maxZ(), heightLimitView, randomState),
            terrainGenerator.getBaseColumn(bounds.maxX(), bounds.maxZ(), heightLimitView, randomState)
        };
        int minimumY = heightLimitView.getMinY() + 15;
        for (int candidateY = y; candidateY > minimumY; candidateY--) {
            int opaqueCorners = 0;
            for (NoiseColumn column : columns) {
                if (Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(column.getBlock(candidateY))
                        && ++opaqueCorners == 3) {
                    return candidateY;
                }
            }
        }
        return minimumY;
    }

    /** Checks a vanilla structure processor's protected-block rule against default seeded terrain. */
    public boolean canPlaceGeneratedStructureBlock(BlockPos position) {
        if (!heightLimitView.isInsideBuildHeight(position.getY())) {
            return false;
        }
        BlockState terrainState = terrainGenerator
                .getBaseColumn(position.getX(), position.getZ(), heightLimitView, randomState)
                .getBlock(position.getY());
        Registry<Block> blocks = DefaultWorldgenRegistryManager.registry().lookupOrThrow(Registries.BLOCK);
        Holder<Block> blockHolder =
                blocks.getResourceKey(terrainState.getBlock()).flatMap(blocks::get).orElse(null);
        return blockHolder == null || !blockHolder.is(BlockTags.FEATURES_CANNOT_REPLACE);
    }

    public boolean canPlaceRuinedPortalChest(BlockPos position) {
        return canPlaceGeneratedStructureBlock(position);
    }

    private static int betweenInclusive(JavaLegacyRandom random, int minimum, int maximum) {
        return minimum + random.nextInt(maximum - minimum + 1);
    }
}
