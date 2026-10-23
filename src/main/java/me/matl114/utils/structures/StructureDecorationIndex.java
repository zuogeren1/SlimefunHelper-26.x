package me.matl114.utils.structures;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Resolves the per-generation-step structure index used by vanilla's
 * {@code ChunkGenerator.applyBiomeDecoration} random stream.
 */
public final class StructureDecorationIndex {
    private StructureDecorationIndex() {}

    public static Map<Identifier, Index> collectDefault() {
        return collect(DefaultWorldgenRegistryManager.registry());
    }

    public static Map<Identifier, Index> collect(HolderLookup.Provider registries) {
        HolderLookup<Structure> structures = registries.lookupOrThrow(Registries.STRUCTURE);
        Map<GenerationStep.Decoration, Integer> nextIndex = new EnumMap<>(GenerationStep.Decoration.class);
        Map<Identifier, Index> result = new LinkedHashMap<>();
        structures.listElements().forEachOrdered(entry -> {
            Structure structure = entry.value();
            GenerationStep.Decoration step = structure.step();
            int index = nextIndex.getOrDefault(step, 0);
            Identifier id = entry.key().identifier();
            result.put(id, new Index(step.ordinal(), index));
            nextIndex.put(step, index + 1);
        });
        return Map.copyOf(result);
    }

    public record Index(int generationStep, int structureIndex) {}
}
