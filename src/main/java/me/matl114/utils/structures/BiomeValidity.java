package me.matl114.utils.structures;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/** Compares biome membership by registry key across independently loaded default registries. */
final class BiomeValidity {
    private BiomeValidity() {}

    static boolean contains(HolderSet<Biome> allowedBiomes, Holder<Biome> biome) {
        ResourceKey<Biome> biomeKey = biome.unwrapKey().orElse(null);
        return biomeKey != null
                && allowedBiomes.stream()
                        .anyMatch(entry -> entry.unwrapKey().map(biomeKey::equals).orElse(false));
    }
}
