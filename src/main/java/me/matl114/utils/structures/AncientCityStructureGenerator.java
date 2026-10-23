package me.matl114.utils.structures;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** Runs Minecraft's 1.21.11 jigsaw expansion for a seed-derived Ancient City candidate. */
public final class AncientCityStructureGenerator {
    private static final Identifier ANCIENT_CITY = Identifier.withDefaultNamespace("ancient_city");

    private AncientCityStructureGenerator() {}

    public static Optional<Result> generate(
            long seed, StructurePosition candidate, CubiomesOverworldBiomeSampler sampler) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(sampler);
        if (!candidate.config().containsStructure(ANCIENT_CITY)) {
            return Optional.empty();
        }
        if (!sampler.isAncientCityBiomeViable(seed, candidate)) {
            return Optional.empty();
        }

        RegistryAccess registries = DefaultWorldgenRegistryManager.registry();
        Registry<Structure> structures = registries.lookupOrThrow(Registries.STRUCTURE);
        Structure structure = structures.getValue(ANCIENT_CITY);
        if (!(structure instanceof JigsawStructure jigsaw)) {
            return Optional.empty();
        }

        StructureTemplateManager templates = DefaultStructureTemplateResources.templates();
        LevelHeightAccessor heightLimit = sampler.heightLimitView();
        ChunkPos chunk = new ChunkPos(Math.toIntExact(candidate.chunkX()), Math.toIntExact(candidate.chunkZ()));
        Structure.GenerationContext context = new Structure.GenerationContext(
                registries,
                sampler.terrainGenerator(),
                sampler.biomeSource(),
                sampler.randomState(),
                templates,
                seed,
                chunk,
                heightLimit,
                biome -> BiomeValidity.contains(jigsaw.biomes(), biome));
        Optional<Structure.GenerationStub> generated = jigsaw.findValidGenerationPoint(context);
        if (generated.isEmpty()) {
            return Optional.empty();
        }

        StructurePiecesBuilder builder = generated.get().getPiecesBuilder();
        List<net.minecraft.world.level.levelgen.structure.StructurePiece> generatedPieces =
                builder.build().pieces();
        if (generatedPieces.isEmpty()) {
            return Optional.empty();
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (net.minecraft.world.level.levelgen.structure.StructurePiece piece : generatedPieces) {
            minX = Math.min(minX, piece.getBoundingBox().minX());
            minZ = Math.min(minZ, piece.getBoundingBox().minZ());
            maxX = Math.max(maxX, piece.getBoundingBox().maxX());
            maxZ = Math.max(maxZ, piece.getBoundingBox().maxZ());
        }
        List<Piece> pieces = generatedPieces.stream()
                .filter(PoolElementStructurePiece.class::isInstance)
                .map(PoolElementStructurePiece.class::cast)
                .filter(piece -> piece.getElement() instanceof SinglePoolElement)
                .map(piece -> {
                    SinglePoolElement element = (SinglePoolElement) piece.getElement();
                    Identifier template = element.getTemplateLocation();
                    BlockPos origin = piece.getPosition().immutable();
                    Rotation rotation = piece.getRotation();
                    return new Piece(
                            template,
                            origin,
                            rotation,
                            AncientCityChestTemplateData.worldChests(template, origin, rotation));
                })
                .toList();
        return Optional.of(new Result(candidate, generated.get().position(), minX, minZ, maxX, maxZ, pieces));
    }

    public record Result(
            StructurePosition candidate, BlockPos origin, int minX, int minZ, int maxX, int maxZ, List<Piece> pieces) {
        public Result {
            origin = origin.immutable();
            pieces = List.copyOf(pieces);
        }

        public List<AncientCityChestTemplateData.WorldChest> chests() {
            return pieces.stream().flatMap(piece -> piece.chests().stream()).toList();
        }

        StructureReferenceOrder.Bounds referenceBounds() {
            return new StructureReferenceOrder.Bounds(minX, minZ, maxX, maxZ);
        }
    }

    public record Piece(
            Identifier template,
            BlockPos origin,
            Rotation rotation,
            List<AncientCityChestTemplateData.WorldChest> chests) {
        public Piece {
            origin = origin.immutable();
            chests = List.copyOf(chests);
        }
    }
}
