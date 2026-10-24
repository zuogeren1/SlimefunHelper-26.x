package me.matl114.utils.structures;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** Simulates vanilla 1.21.11 loot for supported chest tables. */
public final class VanillaGoldenAppleLootPredictor {
    private static final Identifier DESERT_PYRAMID = Identifier.withDefaultNamespace("chests/desert_pyramid");
    private static final Identifier ANCIENT_CITY = Identifier.withDefaultNamespace("chests/ancient_city");
    private static final Identifier ANCIENT_CITY_ICE_BOX = Identifier.withDefaultNamespace("chests/ancient_city_ice_box");
    private static final Identifier RUINED_PORTAL = Identifier.withDefaultNamespace("chests/ruined_portal");
    private static final Identifier BOOK_ID = Identifier.withDefaultNamespace("book");
    private static final Identifier SUSPICIOUS_STEW_ID = Identifier.withDefaultNamespace("suspicious_stew");

    private static final List<Pool> DESERT_PYRAMID_POOLS = List.of(
            new Pool(
                    2,
                    4,
                    List.of(
                            Entry.uniform(5, Identifier.withDefaultNamespace("diamond"), 1, 3),
                            Entry.uniform(15, Identifier.withDefaultNamespace("iron_ingot"), 1, 5),
                            Entry.uniform(15, Identifier.withDefaultNamespace("gold_ingot"), 2, 7),
                            Entry.uniform(15, Identifier.withDefaultNamespace("emerald"), 1, 3),
                            Entry.uniform(25, Identifier.withDefaultNamespace("bone"), 4, 6),
                            Entry.uniform(25, Identifier.withDefaultNamespace("spider_eye"), 1, 3),
                            Entry.uniform(25, Identifier.withDefaultNamespace("rotten_flesh"), 3, 7),
                            Entry.uniform(20, Identifier.withDefaultNamespace("leather"), 1, 5),
                            Entry.item(15, Identifier.withDefaultNamespace("copper_horse_armor")),
                            Entry.item(15, Identifier.withDefaultNamespace("iron_horse_armor")),
                            Entry.item(10, Identifier.withDefaultNamespace("golden_horse_armor")),
                            Entry.item(5, Identifier.withDefaultNamespace("diamond_horse_armor")),
                            Entry.randomlyEnchanted(20, Identifier.withDefaultNamespace("book")),
                            Entry.item(20, Identifier.withDefaultNamespace("golden_apple")),
                            Entry.item(2, Identifier.withDefaultNamespace("enchanted_golden_apple")),
                            Entry.empty(15))),
            new Pool(
                    4,
                    4,
                    List.of(
                            Entry.uniform(10, Identifier.withDefaultNamespace("bone"), 1, 8),
                            Entry.uniform(10, Identifier.withDefaultNamespace("gunpowder"), 1, 8),
                            Entry.uniform(10, Identifier.withDefaultNamespace("rotten_flesh"), 1, 8),
                            Entry.uniform(10, Identifier.withDefaultNamespace("string"), 1, 8),
                            Entry.uniform(10, Identifier.withDefaultNamespace("sand"), 1, 8))),
            new Pool(
                    1,
                    1,
                    List.of(
                            Entry.empty(6),
                            Entry.fixed(1, Identifier.withDefaultNamespace("dune_armor_trim_smithing_template"), 2))));

    private static final List<Pool> RUINED_PORTAL_POOLS = List.of(
            new Pool(
                    4,
                    8,
                    List.of(
                            Entry.uniform(40, Identifier.withDefaultNamespace("obsidian"), 1, 2),
                            Entry.uniform(40, Identifier.withDefaultNamespace("flint"), 1, 4),
                            Entry.uniform(40, Identifier.withDefaultNamespace("iron_nugget"), 9, 18),
                            Entry.item(40, Identifier.withDefaultNamespace("flint_and_steel")),
                            Entry.item(40, Identifier.withDefaultNamespace("fire_charge")),
                            Entry.item(15, Identifier.withDefaultNamespace("golden_apple")),
                            Entry.uniform(15, Identifier.withDefaultNamespace("gold_nugget"), 4, 24),
                            Entry.randomlyEnchanted(15, Identifier.withDefaultNamespace("golden_sword")),
                            Entry.randomlyEnchanted(15, Identifier.withDefaultNamespace("golden_axe")),
                            Entry.randomlyEnchanted(15, Identifier.withDefaultNamespace("golden_hoe")),
                            Entry.randomlyEnchanted(15, Identifier.withDefaultNamespace("golden_shovel")),
                            Entry.randomlyEnchanted(15, Identifier.withDefaultNamespace("golden_pickaxe")),
                            Entry.randomlyEnchanted(15, Identifier.withDefaultNamespace("golden_boots")),
                            Entry.randomlyEnchanted(15, Identifier.withDefaultNamespace("golden_chestplate")),
                            Entry.randomlyEnchanted(15, Identifier.withDefaultNamespace("golden_helmet")),
                            Entry.randomlyEnchanted(15, Identifier.withDefaultNamespace("golden_leggings")),
                            Entry.uniform(5, Identifier.withDefaultNamespace("glistering_melon_slice"), 4, 12),
                            Entry.item(5, Identifier.withDefaultNamespace("golden_horse_armor")),
                            Entry.item(5, Identifier.withDefaultNamespace("light_weighted_pressure_plate")),
                            Entry.uniform(5, Identifier.withDefaultNamespace("golden_carrot"), 4, 12),
                            Entry.item(5, Identifier.withDefaultNamespace("clock")),
                            Entry.uniform(5, Identifier.withDefaultNamespace("gold_ingot"), 2, 8),
                            Entry.item(1, Identifier.withDefaultNamespace("bell")),
                            Entry.item(1, Identifier.withDefaultNamespace("enchanted_golden_apple")),
                            Entry.uniform(1, Identifier.withDefaultNamespace("gold_block"), 1, 2))),
            new Pool(1, 1, List.of(Entry.empty(1), Entry.uniform(2, Identifier.withDefaultNamespace("lodestone"), 1, 2))));

    private static final List<Pool> ANCIENT_CITY_ICE_BOX_POOLS = List.of(new Pool(
            4,
            10,
            List.of(
                    Entry.uniform(1, Identifier.withDefaultNamespace("suspicious_stew"), 2, 6),
                    Entry.uniform(1, Identifier.withDefaultNamespace("golden_carrot"), 1, 10),
                    Entry.uniform(1, Identifier.withDefaultNamespace("baked_potato"), 1, 10),
                    Entry.uniform(2, Identifier.withDefaultNamespace("packed_ice"), 2, 6),
                    Entry.uniform(4, Identifier.withDefaultNamespace("snowball"), 2, 6))));

    private static final List<Pool> LEGACY_DESERT_PYRAMID_POOLS = createLegacyDesertPyramidPools();
    private static final List<Pool> LEGACY_ANCIENT_CITY_POOLS = ancientCityPools(LootVersion.V1_21_1);
    private static final List<Pool> V1_21_11_ANCIENT_CITY_POOLS = ancientCityPools(LootVersion.V1_21_11);
    private static final List<Pool> V26_1_ANCIENT_CITY_POOLS = ancientCityPools(LootVersion.V26_1);
    private static final List<Pool> V26_2_ANCIENT_CITY_POOLS = ancientCityPools(LootVersion.V26_2);

    private static List<Pool> createLegacyDesertPyramidPools() {
        List<Entry> entries = new ArrayList<>(DESERT_PYRAMID_POOLS.getFirst().entries());
        // Before 1.21.11, the leather and copper horse armor slots were a saddle slot.
        entries.set(7, Entry.item(20, Identifier.withDefaultNamespace("saddle")));
        entries.remove(8);
        return List.of(new Pool(2, 4, entries), DESERT_PYRAMID_POOLS.get(1), DESERT_PYRAMID_POOLS.get(2));
    }

    private static List<Pool> ancientCityPools(LootVersion version) {
        List<Entry> entries = new ArrayList<>();
        entries.add(Entry.uniform(1, Identifier.withDefaultNamespace("enchanted_golden_apple"), 1, 2));
        entries.add(Entry.item(1, Identifier.withDefaultNamespace("music_disc_otherside")));
        entries.add(Entry.fixed(2, Identifier.withDefaultNamespace("compass"), 1));
        entries.add(Entry.uniform(2, Identifier.withDefaultNamespace("sculk_catalyst"), 1, 2));
        if (version.ancientCityHasNameTag()) {
            entries.add(Entry.item(2, Identifier.withDefaultNamespace("name_tag")));
        }
        entries.add(Entry.fixed(2, Identifier.withDefaultNamespace("diamond_hoe"), 1)
                .withDamage(0.8F, 1.0F)
                .withEnchantment(EnchantmentOperation.WITH_LEVELS, 30, 50));
        entries.add(Entry.fixed(2, Identifier.withDefaultNamespace("lead"), 1));
        entries.add(Entry.fixed(2, Identifier.withDefaultNamespace("diamond_horse_armor"), 1));
        entries.add(
                version.ancientCityUsesLeatherInsteadOfSaddle()
                        ? Entry.uniform(2, Identifier.withDefaultNamespace("leather"), 1, 5)
                        : Entry.item(2, Identifier.withDefaultNamespace("saddle")));
        entries.add(Entry.item(2, Identifier.withDefaultNamespace("music_disc_13")));
        entries.add(Entry.item(2, Identifier.withDefaultNamespace("music_disc_cat")));
        entries.add(Entry.item(2, Identifier.withDefaultNamespace("diamond_leggings"))
                .withEnchantment(EnchantmentOperation.WITH_LEVELS, 30, 50));
        entries.add(
                Entry.item(3, Identifier.withDefaultNamespace("book")).withEnchantment(EnchantmentOperation.RANDOM_FIXED, 1, 3));
        entries.add(Entry.uniform(3, Identifier.withDefaultNamespace("sculk"), 4, 10));
        entries.add(Entry.uniform(3, Identifier.withDefaultNamespace("sculk_sensor"), 1, 3));
        entries.add(Entry.uniform(3, Identifier.withDefaultNamespace("candle"), 1, 4));
        entries.add(Entry.uniform(3, Identifier.withDefaultNamespace("amethyst_shard"), 1, 15));
        entries.add(Entry.uniform(3, Identifier.withDefaultNamespace("experience_bottle"), 1, 3));
        entries.add(Entry.uniform(3, Identifier.withDefaultNamespace("glow_berries"), 1, 15));
        entries.add(Entry.item(3, Identifier.withDefaultNamespace("iron_leggings"))
                .withEnchantment(EnchantmentOperation.WITH_LEVELS, 20, 39));
        entries.add(Entry.uniform(4, Identifier.withDefaultNamespace("echo_shard"), 1, 3));
        entries.add(Entry.uniform(4, Identifier.withDefaultNamespace("disc_fragment_5"), 1, 3));
        entries.add(Entry.uniform(5, Identifier.withDefaultNamespace("potion"), 1, 3).withStrongRegenerationPotion());
        entries.add(Entry.randomlyEnchanted(5, Identifier.withDefaultNamespace("book")));
        entries.add(Entry.uniform(5, Identifier.withDefaultNamespace("book"), 3, 10));
        entries.add(Entry.uniform(5, Identifier.withDefaultNamespace("bone"), 1, 15));
        entries.add(Entry.uniform(5, Identifier.withDefaultNamespace("soul_torch"), 1, 15));
        entries.add(Entry.uniform(7, Identifier.withDefaultNamespace("coal"), 6, 15));

        return List.of(
                new Pool(5, 10, entries),
                new Pool(
                        1,
                        1,
                        List.of(
                                Entry.empty(75),
                                Entry.item(4, Identifier.withDefaultNamespace("ward_armor_trim_smithing_template")),
                                Entry.item(1, Identifier.withDefaultNamespace("silence_armor_trim_smithing_template")))));
    }

    private VanillaGoldenAppleLootPredictor() {}

    /**
     * Returns the complete generated stacks before the chest's final slot shuffle. The returned
     * multiset includes every pool and all stack functions; zero loot seeds use world sequence state
     * and therefore cannot be reconstructed from the chest seed alone.
     */
    public static Optional<List<ItemStack>> generateContents(
            Identifier lootTable, long lootSeed, LootVersion lootVersion) {
        return simulate(lootTable, lootSeed, lootVersion).map(GeneratedLoot::stacks);
    }

    /**
     * Generates contents from the caller's current random-sequence state. This is required when
     * vanilla's LootTableSeed is zero; the supplied random source is advanced by loot generation.
     */
    public static Optional<List<ItemStack>> generateContents(
            Identifier lootTable, RandomSource random, LootVersion lootVersion) {
        return simulate(lootTable, random, lootVersion).map(GeneratedLoot::stacks);
    }

    /** Returns the final 27 chest slots, including vanilla stack splitting and slot shuffling. */
    public static Optional<List<ItemStack>> generateInventoryContents(
            Identifier lootTable, long lootSeed, LootVersion lootVersion) {
        return simulate(lootTable, lootSeed, lootVersion)
                .map(generated -> fillChest(generated.stacks(), generated.random()));
    }

    /** Generates final chest slots from the caller's current random-sequence state. */
    public static Optional<List<ItemStack>> generateInventoryContents(
            Identifier lootTable, RandomSource random, LootVersion lootVersion) {
        return simulate(lootTable, random, lootVersion)
                .map(generated -> fillChest(generated.stacks(), generated.random()));
    }

    private static Optional<GeneratedLoot> simulate(Identifier lootTable, long lootSeed, LootVersion lootVersion) {
        if (lootSeed == 0L) {
            return Optional.empty();
        }

        return simulate(lootTable, RandomSource.create(lootSeed), lootVersion);
    }

    private static Optional<GeneratedLoot> simulate(Identifier lootTable, RandomSource random, LootVersion lootVersion) {
        List<Pool> pools = poolsFor(lootTable, lootVersion);
        if (pools == null) {
            return Optional.empty();
        }
        java.util.Objects.requireNonNull(random);
        HolderLookup.RegistryLookup<Enchantment> enchantments =
                DefaultWorldgenRegistryManager.registry().lookupOrThrow(Registries.ENCHANTMENT);
        Optional<HolderSet.Named<Enchantment>> randomLootSet =
                enchantments.get(EnchantmentTags.ON_RANDOM_LOOT);

        List<ItemStack> contents = new ArrayList<>();
        for (Pool pool : pools) {
            int rolls = pool.rolls(random);
            for (int roll = 0; roll < rolls; roll++) {
                Entry entry = pool.select(random);
                if (entry.empty()) {
                    continue;
                }
                Optional<ItemStack> stack = generateEntry(entry, random, enchantments, randomLootSet);
                if (stack.isEmpty()) {
                    return Optional.empty();
                }
                addProcessedStacks(contents, stack.get());
            }
        }
        return Optional.of(new GeneratedLoot(List.copyOf(contents), random));
    }

    /** Mirrors LootTable.processStacks before LootTable.supplyInventory fills the chest. */
    private static void addProcessedStacks(List<ItemStack> contents, ItemStack stack) {
        if (stack.getCount() < stack.getMaxStackSize()) {
            contents.add(stack);
            return;
        }

        int remaining = stack.getCount();
        while (remaining > 0) {
            ItemStack processedStack = stack.copyWithCount(Math.min(stack.getMaxStackSize(), remaining));
            remaining -= processedStack.getCount();
            contents.add(processedStack);
        }
    }

    private static List<ItemStack> fillChest(List<ItemStack> generatedStacks, RandomSource random) {
        List<Integer> freeSlots = new ArrayList<>(27);
        for (int slot = 0; slot < 27; slot++) {
            freeSlots.add(slot);
        }
        Util.shuffle(freeSlots, random);

        List<ItemStack> stacks = new ArrayList<>(generatedStacks);
        spreadStacks(stacks, freeSlots.size(), random);
        List<ItemStack> inventory = new ArrayList<>(java.util.Collections.nCopies(27, ItemStack.EMPTY));
        for (ItemStack stack : stacks) {
            if (freeSlots.isEmpty()) {
                break;
            }
            inventory.set(freeSlots.remove(freeSlots.size() - 1), stack);
        }
        return List.copyOf(inventory);
    }

    private static void spreadStacks(List<ItemStack> stacks, int freeSlots, RandomSource random) {
        List<ItemStack> splitCandidates = new ArrayList<>();
        for (var iterator = stacks.iterator(); iterator.hasNext(); ) {
            ItemStack stack = iterator.next();
            if (stack.isEmpty()) {
                iterator.remove();
            } else if (stack.getCount() > 1) {
                splitCandidates.add(stack);
                iterator.remove();
            }
        }

        while (freeSlots - stacks.size() - splitCandidates.size() > 0 && !splitCandidates.isEmpty()) {
            // Vanilla Mth.nextInt skips RNG when min == max; Random.nextInt(1) would consume a draw.
            ItemStack stack = splitCandidates.remove(Mth.nextInt(random, 0, splitCandidates.size() - 1));
            ItemStack split = stack.split(Mth.nextInt(random, 1, stack.getCount() / 2));
            if (stack.getCount() > 1 && random.nextBoolean()) {
                splitCandidates.add(stack);
            } else {
                stacks.add(stack);
            }
            if (split.getCount() > 1 && random.nextBoolean()) {
                splitCandidates.add(split);
            } else {
                stacks.add(split);
            }
        }

        stacks.addAll(splitCandidates);
        Util.shuffle(stacks, random);
    }

    private record GeneratedLoot(List<ItemStack> stacks, RandomSource random) {}

    public static Optional<Boolean> containsEnchantedGoldenApple(
            Identifier lootTable, long lootSeed, LootVersion lootVersion) {
        Optional<List<ItemStack>> contents = generateContents(lootTable, lootSeed, lootVersion);
        return contents.map(stacks -> stacks.stream().anyMatch(stack -> stack.is(Items.ENCHANTED_GOLDEN_APPLE)));
    }

    private static Optional<ItemStack> generateEntry(
            Entry entry,
            RandomSource random,
            HolderLookup.RegistryLookup<Enchantment> enchantments,
            Optional<HolderSet.Named<Enchantment>> randomLootSet) {
        int count = entry.minCount();
        ItemStack stack;
        Item item = entry.item();
        if (item == null) {
            return Optional.empty();
        }
        if (SUSPICIOUS_STEW_ID.equals(entry.itemId())) {
            boolean nightVision = random.nextInt(2) == 0;
            int minDuration = nightVision ? 7 : 5;
            int maxDuration = nightVision ? 10 : 7;
            int duration = (minDuration + random.nextInt(maxDuration - minDuration + 1)) * 20;
            stack = new ItemStack(Items.SUSPICIOUS_STEW);
            stack.set(
                    DataComponents.SUSPICIOUS_STEW_EFFECTS,
                    SuspiciousStewEffects.EMPTY.withEffectAdded(new SuspiciousStewEffects.Entry(
                            nightVision ? MobEffects.NIGHT_VISION : MobEffects.BLINDNESS, duration)));
            count += random.nextInt(entry.maxCount() - entry.minCount() + 1);
            stack.setCount(count);
        } else {
            if (entry.randomCount() && entry.minCount() < entry.maxCount()) {
                count += random.nextInt(entry.maxCount() - entry.minCount() + 1);
            }
            if (entry.strongRegenerationPotion()) {
                stack = PotionContents.createItemStack(
                        Items.POTION, Potions.STRONG_REGENERATION);
                stack.setCount(count);
            } else {
                stack = new ItemStack(item, count);
            }
        }

        if (entry.damageMin() != null) {
            float damageFraction = entry.damageMin() + random.nextFloat() * (entry.damageMax() - entry.damageMin());
            int damage = (int) Math.floor(stack.getMaxDamage() * (1.0F - Math.clamp(damageFraction, 0.0F, 1.0F)));
            stack.setDamageValue(damage);
        }

        switch (entry.enchantment()) {
            case NONE -> {}
            case WITH_LEVELS -> {
                int level = entry.minEnchantmentLevel()
                        + random.nextInt(entry.maxEnchantmentLevel() - entry.minEnchantmentLevel() + 1);
                stack = EnchantmentHelper.enchantItem(
                        random,
                        stack,
                        level,
                        randomLootSet
                                .<java.util.stream.Stream<Holder<Enchantment>>>map(HolderSet::stream)
                                .orElseGet(() -> enchantments.listElements().map(registryEntry ->
                                        (Holder<Enchantment>) registryEntry)));
            }
            case RANDOM_TAG -> {
                if (randomLootSet.isEmpty()) {
                    return Optional.empty();
                }
                ItemStack candidateStack = stack;
                List<Holder<Enchantment>> choices = randomLootSet.get().stream()
                        .filter(enchantment -> BOOK_ID.equals(entry.itemId())
                                || enchantment.value().isSupportedItem(candidateStack))
                        .toList();
                if (choices.isEmpty()) {
                    return Optional.of(stack);
                }
                Holder<Enchantment> selected = choices.get(random.nextInt(choices.size()));
                int minLevel = selected.value().getMinLevel();
                int maxLevel = selected.value().getMaxLevel();
                int level = minLevel >= maxLevel ? minLevel : minLevel + random.nextInt(maxLevel - minLevel + 1);
                stack = enchant(stack, selected, level);
            }
            case RANDOM_FIXED -> {
                random.nextInt(1); // The table's only option is minecraft:swift_sneak.
                Holder<Enchantment> selected = enchantments.getOrThrow(
                        ResourceKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace("swift_sneak")));
                int level = entry.minEnchantmentLevel()
                        + random.nextInt(entry.maxEnchantmentLevel() - entry.minEnchantmentLevel() + 1);
                stack = enchant(stack, selected, level);
            }
        }
        return Optional.of(stack);
    }

    private static ItemStack enchant(ItemStack stack, Holder<Enchantment> enchantment, int level) {
        if (stack.is(Items.BOOK)) {
            stack = new ItemStack(Items.ENCHANTED_BOOK, stack.getCount());
        }
        stack.enchant(enchantment, level);
        return stack;
    }

    private static List<Pool> poolsFor(Identifier lootTable, LootVersion lootVersion) {
        if (DESERT_PYRAMID.equals(lootTable)) {
            return lootVersion.hasCopperHorseArmor() ? DESERT_PYRAMID_POOLS : LEGACY_DESERT_PYRAMID_POOLS;
        }
        if (ANCIENT_CITY.equals(lootTable)) {
            return switch (lootVersion) {
                case V1_21_1, V1_21_4 -> LEGACY_ANCIENT_CITY_POOLS;
                case V1_21_11 -> V1_21_11_ANCIENT_CITY_POOLS;
                case V26_1 -> V26_1_ANCIENT_CITY_POOLS;
                case V26_2 -> V26_2_ANCIENT_CITY_POOLS;
            };
        }
        if (ANCIENT_CITY_ICE_BOX.equals(lootTable)) {
            return ANCIENT_CITY_ICE_BOX_POOLS;
        }
        if (RUINED_PORTAL.equals(lootTable)) {
            return lootVersion.hasLodestonePortalPool() ? RUINED_PORTAL_POOLS : RUINED_PORTAL_POOLS.subList(0, 1);
        }
        return null;
    }

    private enum EnchantmentOperation {
        NONE,
        RANDOM_TAG,
        RANDOM_FIXED,
        WITH_LEVELS
    }

    private record Pool(int minRolls, int maxRolls, List<Entry> entries) {
        private int rolls(RandomSource random) {
            return minRolls + (minRolls == maxRolls ? 0 : random.nextInt(maxRolls - minRolls + 1));
        }

        private Entry select(RandomSource random) {
            int totalWeight = entries.stream().mapToInt(Entry::weight).sum();
            int selection = random.nextInt(totalWeight);
            for (Entry entry : entries) {
                selection -= entry.weight();
                if (selection < 0) {
                    return entry;
                }
            }
            throw new AssertionError("Loot pool selection exceeded its total weight");
        }
    }

    private record Entry(
            int weight,
            Identifier itemId,
            Item item,
            int minCount,
            int maxCount,
            boolean randomCount,
            Float damageMin,
            Float damageMax,
            EnchantmentOperation enchantment,
            int minEnchantmentLevel,
            int maxEnchantmentLevel,
            boolean strongRegenerationPotion) {
        private static Entry empty(int weight) {
            return create(weight, null, 0, 0, false);
        }

        private static Entry item(int weight, Identifier itemId) {
            return create(weight, itemId, 1, 1, false);
        }

        private static Entry fixed(int weight, Identifier itemId, int count) {
            return create(weight, itemId, count, count, false);
        }

        private static Entry uniform(int weight, Identifier itemId, int min, int max) {
            return create(weight, itemId, min, max, true);
        }

        private static Entry randomlyEnchanted(int weight, Identifier itemId) {
            return item(weight, itemId).withEnchantment(EnchantmentOperation.RANDOM_TAG, 0, 0);
        }

        private Entry withDamage(float min, float max) {
            return new Entry(
                    weight,
                    itemId,
                    item,
                    minCount,
                    maxCount,
                    randomCount,
                    min,
                    max,
                    enchantment,
                    minEnchantmentLevel,
                    maxEnchantmentLevel,
                    strongRegenerationPotion);
        }

        private Entry withEnchantment(EnchantmentOperation operation, int minLevel, int maxLevel) {
            return new Entry(
                    weight,
                    itemId,
                    item,
                    minCount,
                    maxCount,
                    randomCount,
                    damageMin,
                    damageMax,
                    operation,
                    minLevel,
                    maxLevel,
                    strongRegenerationPotion);
        }

        private Entry withStrongRegenerationPotion() {
            return new Entry(
                    weight,
                    itemId,
                    item,
                    minCount,
                    maxCount,
                    randomCount,
                    damageMin,
                    damageMax,
                    enchantment,
                    minEnchantmentLevel,
                    maxEnchantmentLevel,
                    true);
        }

        private boolean empty() {
            return itemId == null;
        }

        private static Entry create(int weight, Identifier itemId, int min, int max, boolean randomCount) {
            return new Entry(
                    weight,
                    itemId,
                    itemId != null && BuiltInRegistries.ITEM.containsKey(itemId) ? BuiltInRegistries.ITEM.getValue(itemId) : null,
                    min,
                    max,
                    randomCount,
                    null,
                    null,
                    EnchantmentOperation.NONE,
                    0,
                    0,
                    false);
        }
    }
}
