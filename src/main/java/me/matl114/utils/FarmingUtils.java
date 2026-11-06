package me.matl114.utils;

import com.mojang.datafixers.util.Pair;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import me.matl114.utils.annotations.NeedTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//#if MC >= 260300
//$$ import net.minecraft.core.component.DataComponents;
//#endif
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.camel.CamelHusk;
import net.minecraft.world.entity.animal.equine.ZombieHorse;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CopperGolemStatueBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBedBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LeafLitterBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.PumpkinBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

@NeedTest
public class FarmingUtils {
    private static final Set<Item> BREED_ITEM_CANDIDATES = setOf(
            Items.WHEAT,
            Items.CARROT,
            Items.POTATO,
            Items.BEETROOT,
            Items.WHEAT_SEEDS,
            Items.MELON_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.BEETROOT_SEEDS,
            Items.TORCHFLOWER_SEEDS,
            Items.PITCHER_POD,
            Items.GOLDEN_CARROT,
            Items.DANDELION,
            Items.BAMBOO,
            Items.SWEET_BERRIES,
            Items.GLOW_BERRIES,
            Items.SEAGRASS,
            Items.SLIME_BALL,
            Items.TROPICAL_FISH_BUCKET,
            Items.SPIDER_EYE,
            Items.CACTUS,
            Items.WARPED_FUNGUS,
            Items.CRIMSON_FUNGUS,
            Items.SNOWBALL,
            Items.RED_MUSHROOM,
            Items.RABBIT_FOOT,
            Items.HAY_BLOCK,
            Items.SUGAR,
            Items.APPLE,
            Items.GOLDEN_APPLE,
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.COD,
            Items.COOKED_COD,
            Items.SALMON,
            Items.COOKED_SALMON,
            Items.TROPICAL_FISH,
            Items.PUFFERFISH,
            Items.RABBIT_STEW,
            Items.BEEF,
            Items.COOKED_BEEF,
            Items.PORKCHOP,
            Items.COOKED_PORKCHOP,
            Items.MUTTON,
            Items.COOKED_MUTTON,
            Items.CHICKEN,
            Items.COOKED_CHICKEN,
            Items.RABBIT,
            Items.COOKED_RABBIT,
            Items.ROTTEN_FLESH,
            Items.PUFFERFISH_BUCKET,
            Items.COD_BUCKET,
            Items.SALMON_BUCKET,
            Items.OPEN_EYEBLOSSOM,
            Items.POPPY,
            Items.BLUE_ORCHID,
            Items.ALLIUM,
            Items.AZURE_BLUET,
            Items.RED_TULIP,
            Items.ORANGE_TULIP,
            Items.WHITE_TULIP,
            Items.PINK_TULIP,
            Items.OXEYE_DAISY,
            Items.CORNFLOWER,
            Items.LILY_OF_THE_VALLEY,
            Items.WITHER_ROSE,
            Items.TORCHFLOWER,
            Items.SUNFLOWER,
            Items.LILAC,
            Items.PEONY,
            Items.ROSE_BUSH,
            Items.PITCHER_PLANT,
            Items.FLOWERING_AZALEA_LEAVES,
            Items.FLOWERING_AZALEA,
            Items.MANGROVE_PROPAGULE,
            Items.CHERRY_LEAVES,
            Items.PINK_PETALS,
            Items.WILDFLOWERS,
            Items.CHORUS_FLOWER,
            Items.SPORE_BLOSSOM,
            Items.CACTUS_FLOWER);

    private static final Set<Class<? extends Entity>> FOOD_ONLY_ENTITY_TYPES =
            Set.of(CamelHusk.class, HappyGhast.class, ZombieHorse.class);

    private static final Predicate<ItemStack> ALWAYS_TRUE = stack -> true;

    @NeedTest
    public static PlantType getPlantType(Item item) {
        for (PlantType plantType : PlantType.values()) {
            if (plantType.getSeedItem() == item) {
                return plantType;
            }
        }
        return null;
    }

    @NeedTest
    public static PlantType getPlantType(Block block) {
        for (PlantType plantType : PlantType.values()) {
            if (plantType.getRelatedBlocks().contains(block)) {
                return plantType;
            }
        }
        return null;
    }

    @NeedTest
    public static BlockHitResult tryPlantAt(Level world, BlockPos pos, PlantType plantType) {
        if (world == null || pos == null || plantType == null) {
            return null;
        }

        BlockState current = world.getBlockState(pos);
        if (!current.isAir() && !current.is(Blocks.WATER)) {
            return null;
        }

        for (BlockState state : plantType.getPlacementStates()) {
            if (state.canSurvive(world, pos)) {
                Direction supportDirection = getSupportDirection(state);
                BlockPos supportPos = pos.relative(supportDirection);
                return new BlockHitResult(
                        Vec3.atCenterOf(supportPos), supportDirection.getOpposite(), supportPos, false);
            }
        }
        return null;
    }

    @NeedTest
    public static Pair<Predicate<ItemStack>, BlockHitResult> getInteractTransition(
            Level world, BlockPos pos, BlockState state1, BlockState state2) {
        if (world == null || pos == null || state1 == null || state2 == null) {
            return null;
        }

        Block block1 = state1.getBlock();
        Block block2 = state2.getBlock();

        if (block1 == block2) {
            if (block1 instanceof SlabBlock
                    && state1.getValue(SlabBlock.TYPE) != SlabType.DOUBLE
                    && state2.equals(
                            state1.setValue(SlabBlock.TYPE, SlabType.DOUBLE).setValue(SlabBlock.WATERLOGGED, false))) {
                BlockHitResult hit = state1.getValue(SlabBlock.TYPE) == SlabType.BOTTOM
                        ? hit(pos, Direction.UP, 0.5, 1.0, 0.5)
                        : hit(pos, Direction.DOWN, 0.5, 0.0, 0.5);
                return Pair.of(isItem(block1.asItem()), hit);
            }

            if (block1 instanceof SnowLayerBlock
                    && state2.equals(state1.setValue(
                            SnowLayerBlock.LAYERS, Math.min(8, state1.getValue(SnowLayerBlock.LAYERS) + 1)))) {
                return Pair.of(isItem(block1.asItem()), hit(pos, Direction.UP, 0.5, 1.0, 0.5));
            }

            if (block1 instanceof CandleBlock) {
                if (state1.getValue(CandleBlock.CANDLES) < 4
                        && state2.equals(
                                state1.setValue(CandleBlock.CANDLES, state1.getValue(CandleBlock.CANDLES) + 1))) {
                    return Pair.of(isItem(block1.asItem()), null);
                }
                if (state1.getValue(CandleBlock.LIT) && state2.equals(state1.setValue(CandleBlock.LIT, false))) {
                    return Pair.of(ItemStack::isEmpty, null);
                }
                if (!state1.getValue(CandleBlock.LIT)
                        && !state1.getValue(CandleBlock.WATERLOGGED)
                        && state2.equals(state1.setValue(CandleBlock.LIT, true))) {
                    return Pair.of(isAnyOf(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE), null);
                }
            }

            if (block1 instanceof SeaPickleBlock
                    && state1.getValue(SeaPickleBlock.PICKLES) < 4
                    && state2.equals(
                            state1.setValue(SeaPickleBlock.PICKLES, state1.getValue(SeaPickleBlock.PICKLES) + 1))) {
                return Pair.of(isItem(block1.asItem()), null);
            }

            if (block1 instanceof FlowerBedBlock
                    && state1.getValue(FlowerBedBlock.AMOUNT) < 4
                    && state2.equals(
                            state1.setValue(FlowerBedBlock.AMOUNT, state1.getValue(FlowerBedBlock.AMOUNT) + 1))) {
                return Pair.of(isItem(block1.asItem()), null);
            }

            if (block1 instanceof LeafLitterBlock
                    && state1.getValue(LeafLitterBlock.AMOUNT) < 4
                    && state2.equals(
                            state1.setValue(LeafLitterBlock.AMOUNT, state1.getValue(LeafLitterBlock.AMOUNT) + 1))) {
                return Pair.of(isItem(block1.asItem()), null);
            }

            if (block1 instanceof RepeaterBlock && state2.equals(state1.cycle(RepeaterBlock.DELAY))) {
                return Pair.of(ALWAYS_TRUE, null);
            }

            if (block1 instanceof ComparatorBlock
                    && state1.getValue(ComparatorBlock.MODE) != state2.getValue(ComparatorBlock.MODE)
                    && state2.getValue(ComparatorBlock.MODE)
                            == state1.cycle(ComparatorBlock.MODE).getValue(ComparatorBlock.MODE)
                    && state1.getValue(ComparatorBlock.FACING) == state2.getValue(ComparatorBlock.FACING)) {
                return Pair.of(ALWAYS_TRUE, null);
            }

            if (block1 instanceof NoteBlock
                    && state2.equals(state1.setValue(NoteBlock.NOTE, (state1.getValue(NoteBlock.NOTE) + 1) % 25))) {
                return Pair.of(ALWAYS_TRUE, hit(pos, Direction.NORTH, 0.5, 0.5, 0.0));
            }

            if (block1 instanceof DoorBlock && state2.equals(state1.cycle(DoorBlock.OPEN))) {
                return Pair.of(ALWAYS_TRUE, null);
            }

            if (block1 instanceof TrapDoorBlock && state2.equals(state1.cycle(TrapDoorBlock.OPEN))) {
                return Pair.of(ALWAYS_TRUE, null);
            }

            if (block1 instanceof FenceGateBlock
                    && state1.getValue(FenceGateBlock.FACING) == state2.getValue(FenceGateBlock.FACING)
                    && state2.equals(state1.cycle(FenceGateBlock.OPEN))) {
                return Pair.of(ALWAYS_TRUE, null);
            }

            if (block1 instanceof LeverBlock && state2.equals(state1.cycle(LeverBlock.POWERED))) {
                return Pair.of(ALWAYS_TRUE, null);
            }

            if (block1 instanceof ButtonBlock
                    && !state1.getValue(ButtonBlock.POWERED)
                    && state2.equals(state1.setValue(ButtonBlock.POWERED, true))) {
                return Pair.of(ALWAYS_TRUE, null);
            }

            if (block1 instanceof CakeBlock
                    && state1.getValue(CakeBlock.BITES) < 6
                    && state2.equals(state1.setValue(CakeBlock.BITES, state1.getValue(CakeBlock.BITES) + 1))) {
                return Pair.of(ALWAYS_TRUE, null);
            }

            if (block1 instanceof RespawnAnchorBlock
                    && state1.getValue(RespawnAnchorBlock.CHARGE) < 4
                    && state2.equals(state1.setValue(
                            RespawnAnchorBlock.CHARGE, state1.getValue(RespawnAnchorBlock.CHARGE) + 1))) {
                return Pair.of(isItem(Items.GLOWSTONE), null);
            }

            if (block1 instanceof FlowerPotBlock pot1 && block2 instanceof FlowerPotBlock pot2) {
                if (pot1.getPotted() != Blocks.AIR && pot2.getPotted() == Blocks.AIR) {
                    return Pair.of(ItemStack::isEmpty, null);
                }
            }

            if (block1 instanceof BeehiveBlock
                    && state1.getValue(BeehiveBlock.HONEY_LEVEL) >= 5
                    && state2.equals(state1.setValue(BeehiveBlock.HONEY_LEVEL, 0))) {
                return Pair.of(isAnyOf(Items.SHEARS, Items.GLASS_BOTTLE), null);
            }

            if (block1 instanceof ComposterBlock) {
                int level1 = state1.getValue(ComposterBlock.LEVEL);
                int level2 = state2.getValue(ComposterBlock.LEVEL);
                if (level1 == 0 && level2 == 1) {
                    return Pair.of(FarmingUtils::canIncreaseComposterLevel, null);
                }
                if (level1 == 8 && level2 == 0) {
                    return Pair.of(ALWAYS_TRUE, null);
                }
            }

            if (block1 instanceof CopperGolemStatueBlock
                    && state1.getValue(CopperGolemStatueBlock.POSE).getNextPose()
                            == state2.getValue(CopperGolemStatueBlock.POSE)
                    && state1.getValue(CopperGolemStatueBlock.FACING) == state2.getValue(CopperGolemStatueBlock.FACING)
                    && state1.getValue(CopperGolemStatueBlock.WATERLOGGED)
                            == state2.getValue(CopperGolemStatueBlock.WATERLOGGED)) {
                return Pair.of(FarmingUtils::isStatuePoseSwitchItem, null);
            }
        }

        if (block1 instanceof CakeBlock && block2 instanceof CandleCakeBlock) {
            if (state1.getValue(CakeBlock.BITES) == 0) {
                Item candleItem = getRequiredCandleItem(block2);
                if (candleItem != null) {
                    return Pair.of(isItem(candleItem), null);
                }
            }
        }

        if (block1 instanceof CandleCakeBlock && block2 instanceof CakeBlock) {
            if (state2.equals(Blocks.CAKE.defaultBlockState().setValue(CakeBlock.BITES, 1))) {
                return Pair.of(ALWAYS_TRUE, null);
            }
        }

        if (block1 instanceof CandleCakeBlock && block2 instanceof CandleCakeBlock) {
            if (state1.getValue(CandleCakeBlock.LIT) && state2.equals(state1.setValue(CandleCakeBlock.LIT, false))) {
                return Pair.of(ItemStack::isEmpty, hit(pos, Direction.UP, 0.5, 0.75, 0.5));
            }
            if (!state1.getValue(CandleCakeBlock.LIT) && state2.equals(state1.setValue(CandleCakeBlock.LIT, true))) {
                return Pair.of(isAnyOf(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE), null);
            }
        }

        if (block1 instanceof FlowerPotBlock pot1 && block2 instanceof FlowerPotBlock pot2) {
            if (pot1.getPotted() == Blocks.AIR && pot2.getPotted() != Blocks.AIR) {
                Block content = pot2.getPotted();
                return Pair.of(
                        stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == content,
                        null);
            }
        }

        if (block1 instanceof PumpkinBlock && block2 == Blocks.CARVED_PUMPKIN) {
            Direction facing = state2.getValue(CarvedPumpkinBlock.FACING);
            return Pair.of(isItem(Items.SHEARS), hit(pos, facing, 0.5, 0.5, 0.5));
        }

        return null;
    }

    @NeedTest
    public static boolean isBreedable(Entity entity) {
        return entity instanceof Animal
                && !isFoodOnlyEntity(entity)
                && !getBreedItems(entity).isEmpty();
    }

    @NeedTest
    public static Set<ItemStack> getBreedItems(Entity entity) {
        if (!(entity instanceof Animal animalEntity) || isFoodOnlyEntity(entity)) {
            return Set.of();
        }

        Set<ItemStack> result = new LinkedHashSet<>();
        for (Item item : BREED_ITEM_CANDIDATES) {
            ItemStack stack = new ItemStack(item);
            if (animalEntity.isFood(stack)) {
                result.add(stack);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @NeedTest
    private static Predicate<ItemStack> isItem(Item item) {
        return stack -> stack != null && stack.is(item);
    }

    @NeedTest
    private static Predicate<ItemStack> isAnyOf(Item... items) {
        return stack -> {
            if (stack == null) {
                return false;
            }
            for (Item item : items) {
                if (stack.is(item)) {
                    return true;
                }
            }
            return false;
        };
    }

    @NeedTest
    private static boolean canIncreaseComposterLevel(ItemStack stack) {
        //#if MC >= 260300
        //$$ return stack != null && stack.get(DataComponents.COMPOSTABLE) != null;
        //#else
        return stack != null
                && ComposterBlock.COMPOSTABLES.containsKey(stack.getItem())
                && ComposterBlock.COMPOSTABLES.getFloat(stack.getItem()) > 0.0F;
        //#endif
    }

    @NeedTest
    private static boolean isStatuePoseSwitchItem(ItemStack stack) {
        return stack != null && !stack.is(ItemTags.AXES) && !stack.is(Items.HONEYCOMB);
    }

    @NeedTest
    private static Item getRequiredCandleItem(Block candleCakeBlock) {
        var blockId = BuiltInRegistries.BLOCK.getKey(candleCakeBlock);
        String path = blockId.getPath();
        if (!path.endsWith("_cake")) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(blockId.withPath(path.substring(0, path.length() - 5)));
        return item == Items.AIR ? null : item;
    }

    @NeedTest
    private static BlockHitResult hit(BlockPos pos, Direction side, double x, double y, double z) {
        return new BlockHitResult(new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z), side, pos, false);
    }

    @NeedTest
    private static boolean isFoodOnlyEntity(Entity entity) {
        for (Class<? extends Entity> type : FOOD_ONLY_ENTITY_TYPES) {
            if (type.isInstance(entity)) {
                return true;
            }
        }
        return false;
    }

    @NeedTest
    private static Direction getSupportDirection(BlockState state) {
        if (state.hasProperty(CocoaBlock.FACING)) {
            return state.getValue(CocoaBlock.FACING);
        }
        if (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) {
            return Direction.UP;
        }
        return Direction.DOWN;
    }

    @SafeVarargs
    @NeedTest
    private static <T> Set<T> setOf(T... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    @NeedTest
    private static Set<BlockState> allStates(Block... blocks) {
        Set<BlockState> result = new LinkedHashSet<>();
        for (Block block : blocks) {
            result.addAll(block.getStateDefinition().getPossibleStates());
        }
        return Collections.unmodifiableSet(result);
    }

    @NeedTest
    private static Set<Block> blocksOfStates(Set<BlockState> states) {
        Set<Block> result = new LinkedHashSet<>();
        for (BlockState state : states) {
            result.add(state.getBlock());
        }
        return Collections.unmodifiableSet(result);
    }

    @NeedTest
    private static boolean isVerticalHarvestable(Level world, BlockPos pos, Block... blocks) {
        BlockState state = world.getBlockState(pos);
        BlockState below = world.getBlockState(pos.below());
        for (Block block : blocks) {
            if (state.is(block)) {
                for (Block support : blocks) {
                    if (below.is(support)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @NeedTest
    private static boolean isBambooHarvestable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.is(Blocks.BAMBOO)) {
            return false;
        }
        BlockState below = world.getBlockState(pos.below());
        return below.is(Blocks.BAMBOO) || below.is(Blocks.BAMBOO_SAPLING);
    }

    @NeedTest
    private static boolean isAttachedFruitHarvestable(Level world, BlockPos pos, Block fruit, Block attachedStem) {
        BlockState state = world.getBlockState(pos);
        if (!state.is(fruit)) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (world.getBlockState(pos.relative(direction)).is(attachedStem)) {
                return true;
            }
        }
        return false;
    }

    @NeedTest
    private static boolean isPitcherHarvestable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.is(Blocks.PITCHER_CROP)) {
            return false;
        }
        if (state.getValue(PitcherCropBlock.HALF) == DoubleBlockHalf.LOWER) {
            return state.getValue(PitcherCropBlock.AGE) >= 4;
        }
        BlockState below = world.getBlockState(pos.below());
        return below.is(Blocks.PITCHER_CROP)
                && below.getValue(PitcherCropBlock.HALF) == DoubleBlockHalf.LOWER
                && below.getValue(PitcherCropBlock.AGE) >= 4;
    }

    @NeedTest
    public enum PlantType {
        WHEAT(
                Items.WHEAT_SEEDS,
                true,
                allStates(Blocks.WHEAT),
                Set.of(Blocks.WHEAT),
                setOf(Blocks.WHEAT.defaultBlockState()),
                (world, pos) -> {
                    BlockState state = world.getBlockState(pos);
                    return state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) >= 7;
                }),
        CARROT(
                Items.CARROT,
                true,
                allStates(Blocks.CARROTS),
                Set.of(Blocks.CARROTS),
                setOf(Blocks.CARROTS.defaultBlockState()),
                (world, pos) -> {
                    BlockState state = world.getBlockState(pos);
                    return state.is(Blocks.CARROTS) && state.getValue(CropBlock.AGE) >= 7;
                }),
        POTATO(
                Items.POTATO,
                true,
                allStates(Blocks.POTATOES),
                Set.of(Blocks.POTATOES),
                setOf(Blocks.POTATOES.defaultBlockState()),
                (world, pos) -> {
                    BlockState state = world.getBlockState(pos);
                    return state.is(Blocks.POTATOES) && state.getValue(CropBlock.AGE) >= 7;
                }),
        BEETROOT(
                Items.BEETROOT_SEEDS,
                true,
                allStates(Blocks.BEETROOTS),
                Set.of(Blocks.BEETROOTS),
                setOf(Blocks.BEETROOTS.defaultBlockState()),
                (world, pos) -> {
                    BlockState state = world.getBlockState(pos);
                    return state.is(Blocks.BEETROOTS) && state.getValue(BeetrootBlock.AGE) >= 3;
                }),
        TORCHFLOWER(
                Items.TORCHFLOWER_SEEDS,
                true,
                allStates(Blocks.TORCHFLOWER_CROP, Blocks.TORCHFLOWER),
                Set.of(Blocks.TORCHFLOWER),
                setOf(Blocks.TORCHFLOWER_CROP.defaultBlockState()),
                (world, pos) -> world.getBlockState(pos).is(Blocks.TORCHFLOWER)),
        PITCHER(
                Items.PITCHER_POD,
                true,
                allStates(Blocks.PITCHER_CROP),
                Set.of(Blocks.PITCHER_CROP),
                setOf(Blocks.PITCHER_CROP.defaultBlockState().setValue(PitcherCropBlock.HALF, DoubleBlockHalf.LOWER)),
                FarmingUtils::isPitcherHarvestable),
        MELON(
                Items.MELON_SEEDS,
                false,
                allStates(Blocks.MELON_STEM, Blocks.ATTACHED_MELON_STEM, Blocks.MELON),
                Set.of(Blocks.MELON),
                setOf(Blocks.MELON_STEM.defaultBlockState()),
                (world, pos) -> isAttachedFruitHarvestable(world, pos, Blocks.MELON, Blocks.ATTACHED_MELON_STEM)),
        PUMPKIN(
                Items.PUMPKIN_SEEDS,
                false,
                allStates(Blocks.PUMPKIN_STEM, Blocks.ATTACHED_PUMPKIN_STEM, Blocks.PUMPKIN),
                Set.of(Blocks.PUMPKIN),
                setOf(Blocks.PUMPKIN_STEM.defaultBlockState()),
                (world, pos) -> isAttachedFruitHarvestable(world, pos, Blocks.PUMPKIN, Blocks.ATTACHED_PUMPKIN_STEM)),
        NETHER_WART(
                Items.NETHER_WART,
                true,
                allStates(Blocks.NETHER_WART),
                Set.of(Blocks.NETHER_WART),
                setOf(Blocks.NETHER_WART.defaultBlockState()),
                (world, pos) -> {
                    BlockState state = world.getBlockState(pos);
                    return state.is(Blocks.NETHER_WART) && state.getValue(NetherWartBlock.AGE) >= 3;
                }),
        COCOA(
                Items.COCOA_BEANS,
                true,
                allStates(Blocks.COCOA),
                Set.of(Blocks.COCOA),
                setOf(Blocks.COCOA.defaultBlockState()),
                (world, pos) -> {
                    BlockState state = world.getBlockState(pos);
                    return state.is(Blocks.COCOA) && state.getValue(CocoaBlock.AGE) >= 2;
                }),
        SWEET_BERRY(
                Items.SWEET_BERRIES,
                false,
                allStates(Blocks.SWEET_BERRY_BUSH),
                Set.of(Blocks.SWEET_BERRY_BUSH),
                setOf(Blocks.SWEET_BERRY_BUSH.defaultBlockState()),
                (world, pos) -> {
                    BlockState state = world.getBlockState(pos);
                    return state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(SweetBerryBushBlock.AGE) > 1;
                }),
        CACTUS(
                Items.CACTUS,
                false,
                allStates(Blocks.CACTUS, Blocks.CACTUS_FLOWER),
                Set.of(Blocks.CACTUS, Blocks.CACTUS_FLOWER),
                setOf(Blocks.CACTUS.defaultBlockState()),
                (world, pos) -> world.getBlockState(pos).is(Blocks.CACTUS_FLOWER)
                        || isVerticalHarvestable(world, pos, Blocks.CACTUS)),
        SUGAR_CANE(
                Items.SUGAR_CANE,
                false,
                allStates(Blocks.SUGAR_CANE),
                Set.of(Blocks.SUGAR_CANE),
                setOf(Blocks.SUGAR_CANE.defaultBlockState()),
                (world, pos) -> isVerticalHarvestable(world, pos, Blocks.SUGAR_CANE)),
        BAMBOO(
                Items.BAMBOO,
                false,
                allStates(Blocks.BAMBOO, Blocks.BAMBOO_SAPLING),
                Set.of(Blocks.BAMBOO),
                setOf(Blocks.BAMBOO_SAPLING.defaultBlockState(), Blocks.BAMBOO.defaultBlockState()),
                FarmingUtils::isBambooHarvestable),
        KELP(
                Items.KELP,
                false,
                allStates(Blocks.KELP, Blocks.KELP_PLANT),
                Set.of(Blocks.KELP, Blocks.KELP_PLANT),
                setOf(Blocks.KELP.defaultBlockState()),
                (world, pos) -> isVerticalHarvestable(world, pos, Blocks.KELP, Blocks.KELP_PLANT)),
        GLOW_BERRY(
                Items.GLOW_BERRIES,
                false,
                allStates(Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT),
                Set.of(Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT),
                setOf(Blocks.CAVE_VINES.defaultBlockState()),
                (world, pos) -> {
                    BlockState state = world.getBlockState(pos);
                    return (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT))
                            && state.getValue(CaveVines.BERRIES);
                });

        private final Item seedItem;
        private final boolean needReplant;
        private final Set<BlockState> optionalStates;
        private final Set<Block> harvestableBlocks;
        private final Set<BlockState> placementStates;
        private final Set<Block> relatedBlocks;
        private final BiPredicate<Level, BlockPos> harvestPredicate;

        PlantType(
                Item seedItem,
                boolean needReplant,
                Set<BlockState> optionalStates,
                Set<Block> harvestableBlocks,
                Set<BlockState> placementStates,
                BiPredicate<Level, BlockPos> harvestPredicate) {
            this.seedItem = seedItem;
            this.needReplant = needReplant;
            this.optionalStates = optionalStates;
            this.harvestableBlocks = harvestableBlocks;
            this.placementStates = placementStates;
            this.relatedBlocks = blocksOfStates(optionalStates);
            this.harvestPredicate = harvestPredicate;
        }

        @NeedTest
        public Item getSeedItem() {
            return seedItem;
        }

        @NeedTest
        public Set<BlockState> getOptionalStates() {
            return optionalStates;
        }

        @NeedTest
        public boolean needReplant() {
            return needReplant;
        }

        @NeedTest
        public Set<Block> getHarvestableBlocks() {
            return harvestableBlocks;
        }

        @NeedTest
        public boolean canHarvest(Level world, BlockPos pos) {
            return harvestPredicate.test(world, pos);
        }

        @NeedTest
        public Set<BlockState> getPlacementStates() {
            return placementStates;
        }

        @NeedTest
        public Set<Block> getRelatedBlocks() {
            return relatedBlocks;
        }
    }
}
