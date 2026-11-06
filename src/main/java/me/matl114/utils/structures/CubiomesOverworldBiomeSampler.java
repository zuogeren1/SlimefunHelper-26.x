package me.matl114.utils.structures;

import me.matl114.utils.RegistryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
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

/**
 * Seeded 1.18+ Overworld biome and depth sampling following cubiomes' modern
 * climate-noise sampling and structure viability coordinates.
 */
public final class CubiomesOverworldBiomeSampler {
    private final long seed;
    private final BiomeSource biomeSource;
    private final Sampler biomeNoise;
    private final RandomState randomState;
    private final CubiomesDepthSampler cubiomesDepth;
    private final NoiseBasedChunkGenerator terrainGenerator;
    private final LevelHeightAccessor heightLimitView;

    private CubiomesOverworldBiomeSampler(
            long seed,
            BiomeSource biomeSource,
            Sampler biomeNoise,
            RandomState randomState,
            NoiseBasedChunkGenerator terrainGenerator) {
        this.seed = seed;
        this.biomeSource = biomeSource;
        this.biomeNoise = biomeNoise;
        this.randomState = randomState;
        this.cubiomesDepth = new CubiomesDepthSampler(randomState);
        this.terrainGenerator = terrainGenerator;
        this.heightLimitView = LevelHeightAccessor.create(-64, 384);
    }

    public static CubiomesOverworldBiomeSampler create(long seed) {
        HolderLookup.Provider registries = DefaultWorldgenRegistryManager.registry();
        Holder<MultiNoiseBiomeSourceParameterList> biomeParameters =
                RegistryUtils.getRegistryEntry(registries, MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        Holder<NoiseGeneratorSettings> generatorSettings =
                RegistryUtils.getRegistryEntry(registries, NoiseGeneratorSettings.OVERWORLD);
        if (biomeParameters == null || generatorSettings == null) {
            throw new IllegalStateException("Vanilla Overworld worldgen entries are unavailable");
        }
        BiomeSource biomeSource = MultiNoiseBiomeSource.createFromPreset(biomeParameters);
        RandomState randomState = RandomState.create(
                //#if MC >= 260300
                //$$ registries.lookupOrThrow(Registries.NOISE), seed, generatorSettings.value());
                //#else
                generatorSettings.value(), registries.lookupOrThrow(Registries.NOISE), seed);
                //#endif
        return new CubiomesOverworldBiomeSampler(
                seed,
                biomeSource,
                //#if MC >= 260300
                //$$ randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED),
                //#else
                randomState.sampler(),
                //#endif
                randomState,
                new NoiseBasedChunkGenerator(biomeSource, generatorSettings));
    }

    public boolean isAncientCityBiomeViable(long seed, StructurePosition structure) {
        BlockPos sample = CubiomesAncientCityBiome.samplePosition(seed, structure);
        return biomeAtQuart(sample.getX() >> 2, sample.getY() >> 2, sample.getZ() >> 2)
                .is(Biomes.DEEP_DARK);
    }

    /** Checks the vanilla Trial Chambers biome tag at the candidate chunk's center and sea level. */
    public boolean isTrialChambersBiomeViable(StructurePosition structure) {
        var trialChambers = DefaultWorldgenRegistryManager.registry()
                .lookupOrThrow(Registries.STRUCTURE)
                .getValue(Identifier.withDefaultNamespace("trial_chambers"));
        if (trialChambers == null) {
            return false;
        }
        int quartX = Math.toIntExact(structure.chunkX() * 4L + 2L);
        int quartY = terrainGenerator.getSeaLevel() >> 2;
        int quartZ = Math.toIntExact(structure.chunkZ() * 4L + 2L);
        return BiomeValidity.contains(trialChambers.biomes(), biomeAtQuart(quartX, quartY, quartZ));
    }

    /** Cubiomes viewer's ruined-portal biome sample: chunk center at quart Y=0. */
    public Identifier getRuinedPortalBiomeId(StructurePosition structure) {
        int quartX = Math.toIntExact(structure.chunkX() * 4L + 2L);
        int quartZ = Math.toIntExact(structure.chunkZ() * 4L + 2L);
        ResourceKey<Biome> biomeKey = biomeAtQuart(quartX, 0, quartZ)
                .unwrapKey()
                .orElseThrow(() -> new IllegalStateException("Sampled biome has no registry key"));
        return biomeKey.identifier();
    }

    /** Applies vanilla's second portal biome check at the generated structure origin. */
    public boolean isRuinedPortalBiomeValid(Identifier structureId, BlockPos origin) {
        Holder<Biome> biome = biomeAtQuart(origin.getX() >> 2, origin.getY() >> 2, origin.getZ() >> 2);
        var structure = DefaultWorldgenRegistryManager.registry()
                .lookupOrThrow(Registries.STRUCTURE)
                .getValue(structureId);
        return structure != null && BiomeValidity.contains(structure.biomes(), biome);
    }

    /** Cubiomes Viewer 1.21.11 biome and approximate four-corner depth filter. */
    public boolean isDesertPyramidCubiomesViable(StructurePosition structure) {
        int quartX = Math.toIntExact(structure.chunkX() * 4L + 2L);
        int quartZ = Math.toIntExact(structure.chunkZ() * 4L + 2L);
        if (!biomeAtQuart(quartX, 319 >> 2, quartZ).is(Biomes.DESERT)) {
            return false;
        }

        int blockX = Math.toIntExact(structure.blockX());
        int blockZ = Math.toIntExact(structure.blockZ());
        int[][] corners = {
            {0, 0},
            {21, 21},
            {0, 21},
            {21, 0}
        };
        for (int[] corner : corners) {
            double depth = cubiomesDepth.sample(blockX + corner[0], blockZ + corner[1]);
            if (depth < 0.48D) {
                return false;
            }
        }
        return true;
    }

    /** Reproduces the vanilla 1.21.11 BasicTempleStructure checks that decide if a start exists. */
    public boolean isDesertPyramidViable(StructurePosition structure) {
        DesertPyramidVariant variant = DesertPyramidVariant.generate(seed, structure);
        int startX = Math.toIntExact(structure.blockX());
        int startZ = Math.toIntExact(structure.blockZ());
        Heightmap.Types startHeightmap = Heightmap.Types.WORLD_SURFACE_WG;
        int minCornerHeight = Math.min(
                Math.min(
                        terrainGenerator.getBaseHeight(
                                startX, startZ, startHeightmap, heightLimitView, randomState),
                        terrainGenerator.getBaseHeight(
                                startX + variant.sizeX(), startZ, startHeightmap, heightLimitView, randomState)),
                Math.min(
                        terrainGenerator.getBaseHeight(
                                startX, startZ + variant.sizeZ(), startHeightmap, heightLimitView, randomState),
                        terrainGenerator.getBaseHeight(
                                startX + variant.sizeX(),
                                startZ + variant.sizeZ(),
                                startHeightmap,
                                heightLimitView,
                                randomState)));
        if (minCornerHeight < terrainGenerator.getSeaLevel()) {
            return false;
        }

        var desertPyramid = DefaultWorldgenRegistryManager.registry()
                .lookupOrThrow(Registries.STRUCTURE)
                .getValue(Identifier.withDefaultNamespace("desert_pyramid"));
        if (desertPyramid == null) {
            return false;
        }
        int centerX = Math.toIntExact(structure.chunkX() * 16L + 8L);
        int centerZ = Math.toIntExact(structure.chunkZ() * 16L + 8L);
        int centerY =
                terrainGenerator.getBaseHeight(centerX, centerZ, startHeightmap, heightLimitView, randomState);
        Holder<Biome> startBiome = biomeAtQuart(centerX >> 2, centerY >> 2, centerZ >> 2);
        return BiomeValidity.contains(desertPyramid.biomes(), startBiome);
    }

    /** Samples the same raw noise height that vanilla structure placement queries. */
    public int getHeight(int blockX, int blockZ, Heightmap.Types heightmap) {
        return terrainGenerator.getBaseHeight(blockX, blockZ, heightmap, heightLimitView, randomState);
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

    NoiseBasedChunkGenerator terrainGenerator() {
        return terrainGenerator;
    }

    BiomeSource biomeSource() {
        return biomeSource;
    }

    RandomState randomState() {
        return randomState;
    }

    LevelHeightAccessor heightLimitView() {
        return heightLimitView;
    }

    /** Returns the lowest raw-noise height over a structure footprint, inclusive. */
    public int getLowestHeight(int minBlockX, int minBlockZ, int sizeX, int sizeZ, Heightmap.Types heightmap) {
        int lowest = Integer.MAX_VALUE;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                lowest = Math.min(lowest, getHeight(minBlockX + x, minBlockZ + z, heightmap));
            }
        }
        return lowest;
    }

    public int getRuinedPortalOriginY(RuinedPortalVariant variant, StructurePosition structure, long seed) {
        RuinedPortalVariant.HorizontalBounds bounds = variant.horizontalBounds(structure);
        boolean oceanFloor = variant.verticalPlacement() == RuinedPortalVariant.VerticalPlacement.ON_OCEAN_FLOOR;
        Heightmap.Types heightmap = oceanFloor ? Heightmap.Types.OCEAN_FLOOR_WG : Heightmap.Types.WORLD_SURFACE_WG;
        int height = getHeight(bounds.centerX(), bounds.centerZ(), heightmap) - 1;
        int templateHeight = variant.templateData().sizeY();
        JavaLegacyRandom random = variant.randomAtOriginHeight(seed, structure);
        int minY = heightLimitView.getMinY() + 15;

        switch (variant.verticalPlacement()) {
            case IN_MOUNTAIN -> height = choosePlacementHeight(random, 70, height - templateHeight);
            case UNDERGROUND -> height = choosePlacementHeight(random, minY, height - templateHeight);
            case PARTLY_BURIED -> height = height - templateHeight + random.nextInt(7) + 2;
            case IN_NETHER -> throw new IllegalArgumentException(
                    "Nether ruined portals require a Nether RandomState sampler");
            case ON_LAND_SURFACE, ON_OCEAN_FLOOR -> {}
        }
        return lowerPortalToThreeGroundCorners(bounds, height, heightmap, minY);
    }

    private int lowerPortalToThreeGroundCorners(
            RuinedPortalVariant.HorizontalBounds bounds, int height, Heightmap.Types heightmap, int minimumHeight) {
        NoiseColumn[] columns = {
            terrainGenerator.getBaseColumn(bounds.minX(), bounds.minZ(), heightLimitView, randomState),
            terrainGenerator.getBaseColumn(bounds.maxX(), bounds.minZ(), heightLimitView, randomState),
            terrainGenerator.getBaseColumn(bounds.minX(), bounds.maxZ(), heightLimitView, randomState),
            terrainGenerator.getBaseColumn(bounds.maxX(), bounds.maxZ(), heightLimitView, randomState)
        };
        for (int y = height; y > minimumHeight; y--) {
            int matchingCorners = 0;
            for (NoiseColumn column : columns) {
                if (heightmap.isOpaque().test(column.getBlock(y))) {
                    matchingCorners++;
                    if (matchingCorners == 3) {
                        return y;
                    }
                }
            }
        }
        return Math.min(height, minimumHeight);
    }

    private static int choosePlacementHeight(JavaLegacyRandom random, int minimum, int maximum) {
        return minimum < maximum ? betweenInclusive(random, minimum, maximum) : maximum;
    }

    private static int betweenInclusive(JavaLegacyRandom random, int minimum, int maximum) {
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private Holder<Biome> biomeAtQuart(int quartX, int quartY, int quartZ) {
        //#if MC >= 260300
        //$$ return biomeSource.createResolver(biomeNoise).getNoiseBiome(quartX, quartY, quartZ);
        //#else
        return biomeSource.getNoiseBiome(quartX, quartY, quartZ, biomeNoise);
        //#endif
    }
}
