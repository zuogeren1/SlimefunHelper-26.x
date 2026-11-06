package me.matl114.utils.structures;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
//#if MC >= 260300
//$$ import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
//#endif
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.EndCityPieces;

/** Uses vanilla's default End generator and piece transformer for exact End City anchors. */
final class VanillaEndCityStructureGenerator {
    private static final Identifier END_CITY = Identifier.withDefaultNamespace("end_city");
    private static final String TEMPLATE_PREFIX = "end_city/";

    private VanillaEndCityStructureGenerator() {}

    static Optional<EndCityResult> generate(long seed, StructurePosition candidate, EndCityTemplateData templateData) {
        RegistryAccess.Frozen registries = DefaultWorldgenRegistryManager.registry();
        Registry<Structure> structures = registries.lookupOrThrow(Registries.STRUCTURE);
        Holder.Reference<Structure> structureHolder = structures.get(END_CITY).orElse(null);
        Structure structure = structureHolder == null ? null : structureHolder.value();
        if (structure == null) {
            return Optional.empty();
        }

        var biomeSource = TheEndBiomeSource.create(registries.lookupOrThrow(Registries.BIOME));
        Holder<NoiseGeneratorSettings> settings = registries
                .lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.END);
        RandomState randomState =
                //#if MC >= 260300
                //$$ RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings.value());
                //#else
                RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), seed);
                //#endif
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomeSource, settings);
        LevelHeightAccessor heightLimit = LevelHeightAccessor.create(-64, 384);
        ChunkPos chunk = new ChunkPos(Math.toIntExact(candidate.chunkX()), Math.toIntExact(candidate.chunkZ()));
        StructureStart start = structure.generate(
                structureHolder,
                Level.END,
                registries,
                generator,
                biomeSource,
                //#if MC >= 260300
                //$$ randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED),
                //#endif
                randomState,
                DefaultStructureTemplateResources.templates(),
                seed,
                chunk,
                0,
                heightLimit,
                biome -> BiomeValidity.contains(structure.biomes(), biome));
        if (start == null || start.getPieces().isEmpty()) {
            return Optional.empty();
        }

        List<EndCityPieces.EndCityPiece> vanillaPieces = start.getPieces().stream()
                .filter(EndCityPieces.EndCityPiece.class::isInstance)
                .map(EndCityPieces.EndCityPiece.class::cast)
                .toList();
        if (vanillaPieces.isEmpty()) {
            return Optional.empty();
        }
        EndCityPieces.EndCityPiece base = vanillaPieces.getFirst();
        int terrainHeight = base.templatePosition().getY();
        int rotation = base.getRotation().ordinal();
        List<EndCityPiece> pieces = new ArrayList<>(vanillaPieces.size());
        List<EndCityMarkerPosition> markers = new ArrayList<>();
        var structureContext = DefaultStructureTemplateResources.context(registries);
        for (EndCityPieces.EndCityPiece piece : vanillaPieces) {
            CompoundTag pieceNbt = piece.createTag(structureContext);
            Identifier id = Identifier.withDefaultNamespace("end_city/" + pieceNbt.getStringOr("Template", ""));
            String template = id.getPath().startsWith(TEMPLATE_PREFIX)
                    ? id.getPath().substring(TEMPLATE_PREFIX.length())
                    : id.getPath();
            BlockPos origin = piece.templatePosition();
            var bounds = piece.getBoundingBox();
            EndCityPiece converted = new EndCityPiece(
                    template,
                    piece.getRotation().ordinal(),
                    origin.getX(),
                    origin.getY() - terrainHeight,
                    origin.getZ(),
                    bounds.minX(),
                    bounds.minY() - terrainHeight,
                    bounds.minZ(),
                    bounds.maxX(),
                    bounds.maxY() - terrainHeight,
                    bounds.maxZ());
            pieces.add(converted);

            for (EndCityMarker marker : templateData.markers(template)) {
                int[] transformed = transform(marker.x(), marker.z(), converted.rotation());
                int markerX = converted.x() + transformed[0];
                int markerY = terrainHeight + converted.y() + marker.y();
                int markerZ = converted.z() + transformed[1];
                if (marker.type() == EndCityMarker.Type.CHEST) {
                    markerY--;
                }
                BlockPos position = new BlockPos(markerX, markerY, markerZ);
                boolean generated = converted.contains(markerX, markerY - terrainHeight, markerZ);
                markers.add(new EndCityMarkerPosition(
                        marker.type(),
                        template,
                        position,
                        generated,
                        marker.type() == EndCityMarker.Type.CHEST
                                ? Identifier.withDefaultNamespace("chests/end_city_treasure")
                                        .toString()
                                : null));
            }
        }

        long blockX = candidate.blockX();
        long blockZ = candidate.blockZ();
        boolean outsideCentralIsland = blockX * blockX + blockZ * blockZ >= 1008L * 1008L;
        boolean shipGenerated =
                pieces.stream().anyMatch(piece -> piece.template().equals("ship"));
        return Optional.of(new EndCityResult(
                candidate,
                rotation,
                outsideCentralIsland,
                true,
                shipGenerated,
                EndCityResult.TerrainStatus.VALID,
                terrainHeight,
                pieces,
                markers));
    }

    private static int[] transform(int x, int z, int rotation) {
        return switch (rotation) {
            case 0 -> new int[] {x, z};
            case 1 -> new int[] {-z, x};
            case 2 -> new int[] {-x, -z};
            case 3 -> new int[] {z, -x};
            default -> throw new IllegalStateException("Invalid End City rotation: " + rotation);
        };
    }
}
