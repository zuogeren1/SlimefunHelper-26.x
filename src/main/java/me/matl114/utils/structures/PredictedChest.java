package me.matl114.utils.structures;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/** A generated chest position and its seeded loot-table invocation. */
public record PredictedChest(BlockPos position, Identifier lootTable, long lootSeed, LootVersion lootVersion) {
    public PredictedChest {
        Objects.requireNonNull(lootVersion);
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
