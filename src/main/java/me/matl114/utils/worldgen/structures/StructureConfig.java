package me.matl114.utils.worldgen.structures;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
//#if MC >= 260300
//$$ import net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement.FrequencyReductionMethod;
//#else
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.FrequencyReductionMethod;
//#endif

public final class StructureConfig {
    private static final Map<Identifier, StructureConfig> VANILLA = createVanillaConfigs();

    private final Identifier structureSetId;
    private final Set<Identifier> structureIds;
    private final int salt;
    private final int spacing;
    private final int separation;
    private final RandomSpreadType spreadType;
    private final float frequency;
    private final FrequencyReductionMethod frequencyReductionMethod;

    private StructureConfig(
            Identifier structureSetId,
            Set<Identifier> structureIds,
            int salt,
            int spacing,
            int separation,
            RandomSpreadType spreadType,
            float frequency,
            FrequencyReductionMethod frequencyReductionMethod) {
        if (spacing <= separation || separation < 0) {
            throw new IllegalArgumentException("Structure spacing must be greater than separation");
        }
        if (frequency < 0.0F || frequency > 1.0F || Float.isNaN(frequency)) {
            throw new IllegalArgumentException("Structure frequency must be between 0 and 1");
        }
        this.structureSetId = Objects.requireNonNull(structureSetId);
        this.structureIds = Set.copyOf(structureIds);
        this.salt = salt;
        this.spacing = spacing;
        this.separation = separation;
        this.spreadType = Objects.requireNonNull(spreadType);
        this.frequency = frequency;
        this.frequencyReductionMethod = Objects.requireNonNull(frequencyReductionMethod);
    }

    public static StructureConfig of(
            Identifier structureSetId,
            Set<Identifier> structureIds,
            int salt,
            int spacing,
            int separation,
            RandomSpreadType spreadType) {
        return of(
                structureSetId,
                structureIds,
                salt,
                spacing,
                separation,
                spreadType,
                1.0F,
                FrequencyReductionMethod.DEFAULT);
    }

    public static StructureConfig of(
            Identifier structureSetId,
            Set<Identifier> structureIds,
            int salt,
            int spacing,
            int separation,
            RandomSpreadType spreadType,
            float frequency,
            FrequencyReductionMethod frequencyReductionMethod) {
        return new StructureConfig(
                structureSetId,
                structureIds,
                salt,
                spacing,
                separation,
                spreadType,
                frequency,
                frequencyReductionMethod);
    }

    public static Map<Identifier, StructureConfig> vanillaConfigs() {
        return VANILLA;
    }

    private static Map<Identifier, StructureConfig> createVanillaConfigs() {
        Map<Identifier, StructureConfig> configs = new LinkedHashMap<>();
        configs.put(
                Identifier.withDefaultNamespace("end_cities"),
                of(
                        Identifier.withDefaultNamespace("end_cities"),
                        Set.of(Identifier.withDefaultNamespace("end_city")),
                        10387313,
                        20,
                        11,
                        RandomSpreadType.TRIANGULAR));
        configs.put(
                Identifier.withDefaultNamespace("ancient_cities"),
                of(
                        Identifier.withDefaultNamespace("ancient_cities"),
                        Set.of(Identifier.withDefaultNamespace("ancient_city")),
                        20083232,
                        24,
                        8,
                        RandomSpreadType.LINEAR));
        configs.put(
                Identifier.withDefaultNamespace("trial_chambers"),
                of(
                        Identifier.withDefaultNamespace("trial_chambers"),
                        Set.of(Identifier.withDefaultNamespace("trial_chambers")),
                        94251327,
                        34,
                        12,
                        RandomSpreadType.LINEAR));
        configs.put(
                Identifier.withDefaultNamespace("desert_pyramids"),
                of(
                        Identifier.withDefaultNamespace("desert_pyramids"),
                        Set.of(Identifier.withDefaultNamespace("desert_pyramid")),
                        14357617,
                        32,
                        8,
                        RandomSpreadType.LINEAR));
        configs.put(
                Identifier.withDefaultNamespace("ruined_portals"),
                of(
                        Identifier.withDefaultNamespace("ruined_portals"),
                        Set.of(
                                Identifier.withDefaultNamespace("ruined_portal"),
                                Identifier.withDefaultNamespace("ruined_portal_desert"),
                                Identifier.withDefaultNamespace("ruined_portal_jungle"),
                                Identifier.withDefaultNamespace("ruined_portal_swamp"),
                                Identifier.withDefaultNamespace("ruined_portal_mountain"),
                                Identifier.withDefaultNamespace("ruined_portal_ocean"),
                                Identifier.withDefaultNamespace("ruined_portal_nether")),
                        34222645,
                        40,
                        15,
                        RandomSpreadType.LINEAR));
        configs.put(
                Identifier.withDefaultNamespace("mineshafts"),
                of(
                        Identifier.withDefaultNamespace("mineshafts"),
                        Set.of(
                                Identifier.withDefaultNamespace("mineshaft"),
                                Identifier.withDefaultNamespace("mineshaft_mesa")),
                        0,
                        1,
                        0,
                        RandomSpreadType.LINEAR,
                        0.004F,
                        FrequencyReductionMethod.LEGACY_TYPE_3));
        return Map.copyOf(configs);
    }

    public Identifier structureSetId() {
        return structureSetId;
    }

    public Set<Identifier> structureIds() {
        return structureIds;
    }

    public OptionalInt salt() {
        return OptionalInt.of(salt);
    }

    public int spacing() {
        return spacing;
    }

    public int separation() {
        return separation;
    }

    public RandomSpreadType spreadType() {
        return spreadType;
    }

    public float frequency() {
        return frequency;
    }

    public FrequencyReductionMethod frequencyReductionMethod() {
        return frequencyReductionMethod;
    }

    public boolean passesFrequencyReduction(long worldSeed, long chunkX, long chunkZ) {
        return frequency >= 1.0F
                || frequencyReductionMethod.shouldGenerate(
                        worldSeed, salt, Math.toIntExact(chunkX), Math.toIntExact(chunkZ), frequency);
    }

    public boolean containsStructure(Identifier structureId) {
        return structureIds.contains(structureId);
    }

    StructureChunk getStartChunk(long seed, long regionX, long regionZ) {
        // regionX/Z are region indices, not chunk coordinates. Java long overflow
        // intentionally preserves the low 64 bits used by cubiomes' uint64_t math.
        JavaLegacyRandom random = new JavaLegacyRandom(seed + regionX * 341873128712L + regionZ * 132897987541L + salt);
        int range = spacing - separation;
        int offsetX = randomOffset(random, range);
        int offsetZ = randomOffset(random, range);
        return new StructureChunk(regionX * spacing + offsetX, regionZ * spacing + offsetZ);
    }

    private int randomOffset(JavaLegacyRandom random, int range) {
        if (spreadType == RandomSpreadType.TRIANGULAR) {
            return (random.nextInt(range) + random.nextInt(range)) / 2;
        }
        return random.nextInt(range);
    }

    record StructureChunk(long chunkX, long chunkZ) {}
}
