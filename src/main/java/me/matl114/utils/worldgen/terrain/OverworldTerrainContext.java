package me.matl114.utils.worldgen.terrain;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import me.matl114.utils.RegistryUtils;
import me.matl114.utils.worldgen.DefaultWorldgenRegistryManager;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
//#if MC < 260300
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.QuartPos;
import net.minecraft.data.worldgen.Carvers;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Climate.Sampler;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import org.jetbrains.annotations.Nullable;
//#endif

/**
 * Vanilla overworld terrain generation for one world seed, built from the built-in data pack
 * rather than any server registry. Instances are immutable and safe to share between threads,
 * like the generator state a server shares between its worldgen workers.
 *
 * <p>{@link #generateChunk} reproduces the chunk statuses that run before features:
 * {@code NOISE} (density functions, noise caves and aquifers, via vanilla's
 * {@code NoiseBasedChunkGenerator.populateNoise}) and {@code CARVERS} (the same loop as
 * {@code NoiseBasedChunkGenerator.carve}). Not reproduced: structure terrain adaptation
 * ({@code Beardifier}), {@code SURFACE} rules (they only swap solid blocks), structure pieces and
 * features of earlier generation steps.
 *
 * <p>Only the noise and carver state shared by other worldgen users is kept on 26.3: that version
 * rewrote {@code NoiseChunk} around {@code DensityVolume}/{@code DensitySampler} and replaced
 * {@code ConfiguredWorldCarver} with {@code WorldCarver.CarverOutput}, so per-block terrain
 * sampling and carver replay are not implemented there and dungeon prediction is only complete on
 * 26.1.2 / 26.2.
 */
public final class OverworldTerrainContext {
    public static final int BOTTOM_Y = -64;
    public static final int HEIGHT = 384;

//#if MC >= 260300
//$$     private final long seed;
//$$     private final RegistryAccess registries;
//$$     private final Holder<NoiseGeneratorSettings> settings;
//$$     private final BiomeSource biomeSource;
//$$     private final RandomState randomState;
//$$     private final NoiseBasedChunkGenerator generator;
//$$     private final LevelHeightAccessor heightLimitView;
//$$     private final Map<Block, Boolean> cannotReplace = new ConcurrentHashMap<>();
//$$     private OverworldTerrainContext(long seed) {
//$$         this.seed = seed;
//$$         this.registries = DefaultWorldgenRegistryManager.registry();
//$$         Holder<MultiNoiseBiomeSourceParameterList> biomeParameters =
//$$                 RegistryUtils.getRegistryEntry(registries, MultiNoiseBiomeSourceParameterLists.OVERWORLD);
//$$         Holder<NoiseGeneratorSettings> generatorSettings =
//$$                 RegistryUtils.getRegistryEntry(registries, NoiseGeneratorSettings.OVERWORLD);
//$$         if (biomeParameters == null || generatorSettings == null) {
//$$             throw new IllegalStateException("Vanilla Overworld worldgen entries are unavailable");
//$$         }
//$$         this.settings = generatorSettings;
//$$         this.biomeSource = MultiNoiseBiomeSource.createFromPreset(biomeParameters);
//$$         this.randomState = RandomState.create(
//$$                 registries.lookupOrThrow(Registries.NOISE), seed, generatorSettings.value());
//$$         this.generator = new NoiseBasedChunkGenerator(biomeSource, generatorSettings);
//$$         this.heightLimitView = LevelHeightAccessor.create(BOTTOM_Y, HEIGHT);
//$$     }
//$$     public static OverworldTerrainContext create(long seed) {
//$$         return new OverworldTerrainContext(seed);
//$$     }
//$$     /**
//$$      * 26.3 把雕刻器重构成 {@code WorldCarver}/{@code CarverOutput}，这里没有回放实现，
//$$      * 一律按「不可雕刻」处理；地牢预测只在 26.1.2 / 26.2 完整。
//$$      */
//$$     public boolean carverReplaceable(BlockState state) {
//$$         return false;
//$$     }
//$$     /** Whether {@code Feature.setBlockStateIf} with {@code FEATURES_CANNOT_REPLACE} must skip this block. */
//$$     public boolean featuresCannotReplace(BlockState state) {
//$$         return cannotReplace.computeIfAbsent(Objects.requireNonNull(state).getBlock(), block -> registries
//$$                 .lookupOrThrow(Registries.BLOCK)
//$$                 .wrapAsHolder(block)
//$$                 .is(BlockTags.FEATURES_CANNOT_REPLACE));
//$$     }
//$$     public long seed() {
//$$         return seed;
//$$     }
//$$     public BiomeSource biomeSource() {
//$$         return biomeSource;
//$$     }
//$$     public RandomState randomState() {
//$$         return randomState;
//$$     }
//$$     public NoiseBasedChunkGenerator generator() {
//$$         return generator;
//$$     }
//$$     public LevelHeightAccessor heightLimitView() {
//$$         return heightLimitView;
//$$     }
//#else

    /** Every overworld biome lists exactly these carvers, in this order (1.21.1 to 26.1). */
    private static final List<ResourceKey<ConfiguredWorldCarver<?>>> OVERWORLD_CARVERS =
            List.of(Carvers.CAVE, Carvers.CAVE_EXTRA_UNDERGROUND, Carvers.CANYON);

    private final long seed;
    private final RegistryAccess registries;
    private final Holder<NoiseGeneratorSettings> settings;
    private final BiomeSource biomeSource;
    private final RandomState randomState;
    private final Sampler climateSampler;
    private final NoiseBasedChunkGenerator generator;
    private final LevelHeightAccessor heightLimitView;
    private final Aquifer.FluidPicker fluidLevelSampler;
    private final List<ConfiguredWorldCarver<?>> carvers;
    private final BiomeManager biomeAccess;
    private final PalettedContainerFactory palettes;
    /** Sections default to stone, so carvers can record every point they would carve. */
    private final PalettedContainerFactory stonePalettes;

    private final Beardifier noStructures = Beardifier.EMPTY;
    private final Map<Block, Boolean> cannotReplace = new ConcurrentHashMap<>();
    private final Map<Block, Boolean> carverReplaceable = new ConcurrentHashMap<>();

    private OverworldTerrainContext(long seed) {
        this.seed = seed;
        this.registries = DefaultWorldgenRegistryManager.registry();
        Holder<MultiNoiseBiomeSourceParameterList> biomeParameters =
                RegistryUtils.getRegistryEntry(registries, MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        Holder<NoiseGeneratorSettings> generatorSettings =
                RegistryUtils.getRegistryEntry(registries, NoiseGeneratorSettings.OVERWORLD);
        if (biomeParameters == null || generatorSettings == null) {
            throw new IllegalStateException("Vanilla Overworld worldgen entries are unavailable");
        }
        this.settings = generatorSettings;
        this.biomeSource = MultiNoiseBiomeSource.createFromPreset(biomeParameters);
        this.randomState = RandomState.create(
                generatorSettings.value(), registries.lookupOrThrow(Registries.NOISE), seed);
        this.climateSampler =
                randomState.sampler();
        this.generator = new NoiseBasedChunkGenerator(biomeSource, generatorSettings);
        this.heightLimitView = LevelHeightAccessor.create(BOTTOM_Y, HEIGHT);
        this.fluidLevelSampler = createFluidLevelSampler(generatorSettings.value());
        Registry<ConfiguredWorldCarver<?>> carverRegistry = registries.lookupOrThrow(Registries.CONFIGURED_CARVER);
        this.carvers = OVERWORLD_CARVERS.stream()
                .map(key -> carverRegistry.get(key).orElseThrow().value())
                .<ConfiguredWorldCarver<?>>map(carver -> carver)
                .toList();
        this.biomeAccess = new BiomeManager(this::noiseBiome, BiomeManager.obfuscateSeed(seed));
        this.palettes = PalettedContainerFactory.create(registries);
        this.stonePalettes = new PalettedContainerFactory(
                palettes.blockStatesStrategy(),
                Blocks.STONE.defaultBlockState(),
                palettes.blockStatesContainerCodec(),
                palettes.biomeStrategy(),
                palettes.defaultBiome(),
                palettes.biomeContainerCodec());
    }

    public static OverworldTerrainContext create(long seed) {
        return new OverworldTerrainContext(seed);
    }

    /** Mirrors the private {@code NoiseBasedChunkGenerator.createFluidLevelSampler}. */
    private static Aquifer.FluidPicker createFluidLevelSampler(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : sea;
    }

    /**
     * Generates one chunk up to (and including) the {@code CARVERS} status. Every carver start
     * within eight chunks is replayed with {@code WorldgenRandom.setCarverSeed(seed + index, x, z)}, as
     * in {@code NoiseBasedChunkGenerator.carve}.
     */
    public ProtoChunk generateChunk(ChunkPos pos) {
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, heightLimitView, palettes, null);
        NoiseChunk sampler = chunk.getOrCreateNoiseChunk(created -> NoiseChunk.forChunk(
                created,
                randomState,
                // What Beardifier.createStructureWeightSampler returns without structures.
                noStructures,
                settings.value(),
                fluidLevelSampler,
                Blender.empty()));
        // The structure accessor is only used to create the sampler, which already exists.
        generator.fillFromNoise(Blender.empty(), randomState, null, chunk).join();
        carve(chunk, sampler, sampler.aquifer());
        return chunk;
    }

    /**
     * The chunk's {@code NOISE} status as a compact block array, filled by one full-height sampler on
     * the calling thread with the same loop as {@code NoiseBasedChunkGenerator.populateNoise} (which would
     * otherwise hop to the shared worldgen executor). Index:
     * {@code ((y - BOTTOM_Y) * 16 + localZ) * 16 + localX}.
     */
    public NoiseBlocks noiseChunk(int chunkX, int chunkZ) {
        return sampleChunk(chunkX, chunkZ, BOTTOM_Y, BOTTOM_Y + HEIGHT).blocks();
    }

    /** A chunk's noise together with the aquifer of the sampler that produced it. */
    public record ChunkNoise(NoiseBlocks blocks, Aquifer aquifer) {}

    /**
     * A chunk's noise over the height band {@code [minY, maxY)} (widened to whole cells), keeping the
     * sampler's aquifer so carving needs no second sampler. That aquifer is built for the same band
     * and must only answer positions inside it.
     */
    public ChunkNoise sampleChunk(int chunkX, int chunkZ, int minY, int maxY) {
        TerrainNoiseSampler[] sampler = new TerrainNoiseSampler[1];
        NoiseBlocks blocks = noiseRegion(chunkX * 16, chunkZ * 16, 16 / cellWidth(), minY, maxY, sampler);
        return new ChunkNoise(blocks, sampler[0].aquifer());
    }

    /**
     * The aquifer vanilla's chunk sampler would use for this chunk, without sampling any noise. It is
     * a pure function of position, so this gives the same fluid decisions as generation.
     */
    public Aquifer aquifer(int chunkX, int chunkZ) {
        NoiseSettings shape = settings.value().noiseSettings().clampToHeightAccessor(heightLimitView);
        return new TerrainNoiseSampler(
                        16 / shape.getCellWidth(),
                        randomState,
                        chunkX * 16,
                        chunkZ * 16,
                        shape,
                        noStructures,
                        settings.value(),
                        fluidLevelSampler)
                .aquifer();
    }

    /** Horizontal size of one noise cell, in blocks (4 in the overworld). */
    public int cellWidth() {
        return settings.value().noiseSettings().getCellWidth();
    }

    /**
     * Noise for one cell column ({@link #cellWidth()} blocks square) over {@code [minY, maxY)}, for
     * neighbour chunks a room only reaches into. Sampler setup dominates small regions, so prefer
     * {@link #sampleChunk} when most cells are needed.
     */
    public NoiseBlocks noiseCell(int cellStartX, int cellStartZ, int minY, int maxY) {
        return noiseRegion(cellStartX, cellStartZ, 1, minY, maxY, null);
    }

    /**
     * Noise for a square of {@code cellsPerSide} x {@code cellsPerSide} cells starting at the
     * cell-aligned block {@code startX}/{@code startZ}, over {@code [minY, maxY)}. One sampler covers
     * them all, which is much cheaper than one {@link #noiseCell} each.
     */
    public NoiseBlocks noiseCells(int startX, int startZ, int cellsPerSide, int minY, int maxY) {
        return noiseRegion(startX, startZ, cellsPerSide, minY, maxY, null);
    }

    /**
     * A square, cell-aligned region of {@code cellsPerSide} cells over the height band
     * {@code [minY, maxY)}, filled like {@code populateNoise}. The band is widened to whole noise
     * cells and clamped to the world. Cell interpolation is anchored to absolute coordinates and the
     * aquifer is a function of position, so a band gives the same blocks as the full-height pass.
     */
    private NoiseBlocks noiseRegion(
            int startX, int startZ, int cellsPerSide, int minY, int maxY, @Nullable TerrainNoiseSampler[] samplerOut) {
        NoiseSettings full = settings.value().noiseSettings().clampToHeightAccessor(heightLimitView);
        int cellWidth = full.getCellWidth();
        int cellHeight = full.getCellHeight();
        int bandMin = Math.max(full.minY(), Math.floorDiv(minY, cellHeight) * cellHeight);
        int bandMax = Math.min(
                full.minY() + full.height(),
                Math.ceilDiv(Math.max(maxY, bandMin + 1), cellHeight) * cellHeight);
        NoiseSettings shape =
                new NoiseSettings(bandMin, bandMax - bandMin, full.noiseSizeHorizontal(), full.noiseSizeVertical());
        int width = cellsPerSide * cellWidth;
        int minimumCellY = Math.floorDiv(shape.minY(), cellHeight);
        int cellCount = Math.floorDiv(shape.height(), cellHeight);
        TerrainNoiseSampler sampler = new TerrainNoiseSampler(
                cellsPerSide, randomState, startX, startZ, shape, noStructures, settings.value(), fluidLevelSampler);
        BlockState defaultBlock = settings.value().defaultBlock();
        NoiseBlocks.Builder blocks = new NoiseBlocks.Builder(width, bandMin, bandMax);
        sampler.initializeForFirstCellX();
        for (int cellX = 0; cellX < cellsPerSide; cellX++) {
            sampler.advanceCellX(cellX);
            for (int cellZ = 0; cellZ < cellsPerSide; cellZ++) {
                for (int cellY = cellCount - 1; cellY >= 0; cellY--) {
                    sampler.selectCellYZ(cellY, cellZ);
                    for (int dy = cellHeight - 1; dy >= 0; dy--) {
                        int y = (minimumCellY + cellY) * cellHeight + dy;
                        sampler.updateForY(y, (double) dy / cellHeight);
                        for (int dx = 0; dx < cellWidth; dx++) {
                            int localX = cellX * cellWidth + dx;
                            sampler.updateForX(startX + localX, (double) dx / cellWidth);
                            for (int dz = 0; dz < cellWidth; dz++) {
                                int localZ = cellZ * cellWidth + dz;
                                sampler.updateForZ(startZ + localZ, (double) dz / cellWidth);
                                BlockState state = sampler.sample();
                                blocks.set(localX, y, localZ, state == null ? defaultBlock : state);
                            }
                        }
                    }
                }
            }
            sampler.swapSlices();
        }
        sampler.stopInterpolation();
        if (samplerOut != null) {
            samplerOut[0] = sampler;
        }
        return blocks.build();
    }

    /**
     * Vanilla's {@code preliminary_surface_level} density function: a cheap 2D estimate of the
     * surface used by aquifers. It is usually below the real surface (by about 15 blocks at the
     * median, up to about 100 on jagged peaks), so callers add a safety margin.
     */
    public double preliminarySurface(int x, int z) {
        return randomState
                .router()
                .preliminarySurfaceLevel()
                .compute(new DensityFunction.SinglePointContext(x, 0, z));
    }

    /**
     * Marks the points carvers would carve in this chunk, using an all-stone chunk.
     *
     * <p>{@code Carver.carveAtPoint} changes a point only if the block there is carvable, and what it
     * writes (lava below the lava level, otherwise {@code aquifer.apply(pos, 0)}, where {@code null}
     * keeps the block) depends only on the position. Carvers here get a stand-in aquifer that writes
     * {@link #CARVED} instead; callers resolve a marked point with {@link #aquifer} only when they
     * read it. Building that aquifer costs about two thirds of carving, and rooms in solid rock
     * rarely read a carved point. The final block is
     * {@code layer == stone || !carvable(noise) ? noise : layer == CARVED ? aquifer(pos) ?? noise : layer}.
     */
    public ProtoChunk carveLayer(ChunkPos pos) {
        // Every section starts as stone (the stonePalettes default). Sections created from a palette
        // count as empty until recounted, and ProtoChunk.getBlockState reports empty sections as air,
        // which carvers would refuse to carve; recounting makes them read as stone.
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, heightLimitView, stonePalettes, null);
        for (LevelChunkSection section : chunk.getSections()) {
            section.recalcBlockCounts();
        }
        // Stone is carvable everywhere, so the layer records every point a carver reaches, whatever
        // the real noise there is; the caller applies the carvable test against the real block.
        carve(chunk, null, MARKING_AQUIFER);
        return chunk;
    }

    /**
     * Written by carvers where the real aquifer has not been consulted yet. Structure void never
     * comes out of noise, an aquifer or a carver, so it cannot be confused with a real block.
     */
    public static final BlockState CARVED = Blocks.STRUCTURE_VOID.defaultBlockState();

    /**
     * Stands in for the aquifer while carving: every query is answered with {@link #CARVED}, which
     * the carver writes as the point's new block. Lava below the lava level is decided by the
     * carver itself and still written as lava. No fluid ticks are needed for a scratch chunk.
     */
    private static final Aquifer MARKING_AQUIFER = new Aquifer() {
        @Override
        public BlockState computeSubstance(DensityFunction.FunctionContext pos, double density) {
            return CARVED;
        }

        @Override
        public boolean shouldScheduleFluidUpdate() {
            return false;
        }
    };

    /** Whether the overworld carvers' {@code #minecraft:overworld_carver_replaceables} contains the block. */
    public boolean carverReplaceable(BlockState state) {
        return carverReplaceable.computeIfAbsent(state.getBlock(), block -> registries
                .lookupOrThrow(Registries.BLOCK)
                .wrapAsHolder(block)
                .is(BlockTags.OVERWORLD_CARVER_REPLACEABLES));
    }

    /**
     * The loop of {@code NoiseBasedChunkGenerator.carve}. {@code sampler} is only used by surface-rule
     * fixes after carving grass, which an all-stone chunk never triggers, so it may be null there.
     */
    private void carve(ProtoChunk chunk, @Nullable NoiseChunk sampler, Aquifer aquifer) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        CarvingContext context = new CarvingContext(
                generator,
                registries,
                heightLimitView,
                sampler,
                randomState,
                settings.value().surfaceRule());
        CarvingMask mask = chunk.getOrCreateCarvingMask();
        ChunkPos center = chunk.getPos();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                ChunkPos start = new ChunkPos(center.x() + dx, center.z() + dz);
                for (int index = 0; index < carvers.size(); index++) {
                    ConfiguredWorldCarver<?> carver = carvers.get(index);
                    random.setLargeFeatureSeed(seed + index, start.x(), start.z());
                    if (carver.isStartChunk(random)) {
                        carver.carve(context, chunk, biomeAccess::getBiome, random, aquifer, start, mask);
                    }
                }
            }
        }
    }

    /** The biome vanilla samples at block coordinates, for callers that need it. */
    public Holder<Biome> getBiome(int blockX, int blockY, int blockZ) {
        return noiseBiome(QuartPos.fromBlock(blockX), QuartPos.fromBlock(blockY), QuartPos.fromBlock(blockZ));
    }

    private Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
        return biomeSource.getNoiseBiome(quartX, quartY, quartZ, climateSampler);
    }

    /** Whether {@code Feature.setBlockStateIf} with {@code FEATURES_CANNOT_REPLACE} must skip this block. */
    public boolean featuresCannotReplace(BlockState state) {
        return cannotReplace.computeIfAbsent(Objects.requireNonNull(state).getBlock(), block -> registries
                .lookupOrThrow(Registries.BLOCK)
                .wrapAsHolder(block)
                .is(BlockTags.FEATURES_CANNOT_REPLACE));
    }

    public long seed() {
        return seed;
    }

    public BiomeSource biomeSource() {
        return biomeSource;
    }

    public RandomState randomState() {
        return randomState;
    }

    public NoiseBasedChunkGenerator generator() {
        return generator;
    }

    public LevelHeightAccessor heightLimitView() {
        return heightLimitView;
    }
//#endif
}
