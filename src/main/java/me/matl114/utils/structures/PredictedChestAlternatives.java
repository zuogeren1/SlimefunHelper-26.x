package me.matl114.utils.structures;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/** A known chest loot invocation whose world position has multiple possible Y values. */
public record PredictedChestAlternatives(
        List<BlockPos> possiblePositions, Identifier lootTable, long lootSeed, LootVersion lootVersion) {
    public PredictedChestAlternatives {
        Objects.requireNonNull(lootVersion);
        possiblePositions =
                possiblePositions.stream().map(BlockPos::immutable).distinct().toList();
        if (possiblePositions.isEmpty()) {
            throw new IllegalArgumentException("At least one possible chest position is required");
        }
    }

    public Optional<List<ItemStack>> generateContents() {
        return VanillaGoldenAppleLootPredictor.generateContents(lootTable, lootSeed, lootVersion);
    }

    public Optional<List<ItemStack>> generateContents(RandomSource randomSequence) {
        return VanillaGoldenAppleLootPredictor.generateContents(lootTable, randomSequence, lootVersion);
    }

    public Optional<List<ItemStack>> generateInventoryContents() {
        return VanillaGoldenAppleLootPredictor.generateInventoryContents(lootTable, lootSeed, lootVersion);
    }

    public Optional<List<ItemStack>> generateInventoryContents(RandomSource randomSequence) {
        return VanillaGoldenAppleLootPredictor.generateInventoryContents(lootTable, randomSequence, lootVersion);
    }

    public Optional<Boolean> containsEnchantedGoldenApple() {
        return VanillaGoldenAppleLootPredictor.containsEnchantedGoldenApple(lootTable, lootSeed, lootVersion);
    }
}
