package me.matl114.utils;

import com.mojang.datafixers.util.Pair;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import me.matl114.utils.collections.FlagEntry;
import me.matl114.versioned.api.VItem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.Bucketable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinArmPose;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.*;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartCommandBlock;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.item.*;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BottleItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.EmptyMapItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.FoodOnAStickItem;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.InstrumentItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.KnowledgeBookItem;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ProjectileItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.SpyglassItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Spawner;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BeaconBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBedBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.LeafLitterBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.PumpkinBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.SuspiciousEffectHolder;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public class InteractUtils {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final Predicate<ItemStack> ALWAYS_TRUE = stack -> true;

    @Nullable
    public static BlockState getBlockPlacement(Block block, Player player, Level world, BlockHitResult blockHitResult) {
        Item blockItem = block.asItem();
        return blockItem instanceof BlockItem blockItem1
                ? getBlockPlacement(blockItem1, player, world, blockHitResult)
                : null;
    }

    @Nullable
    public static BlockState getBlockPlacement(
            BlockItem blockItem, Player player, Level world, BlockHitResult blockHitResult) {
        BlockPlaceContext placement =
                new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(blockItem), blockHitResult);
        placement = blockItem.updatePlacementContext(placement);
        return blockItem.getPlacementState(placement);
    }

    @Nullable
    public static BlockState getBlockPlacement(
            Player player, InteractionHand hand, ItemStack stack, BlockHitResult hitResult) {
        return stack.getItem() instanceof BlockItem blockItem
                ? blockItem.getPlacementState(new BlockPlaceContext(player, hand, stack, hitResult))
                : null;
    }
    // should equals getBlockPlacement(STONE) != null
    public static boolean canCubePlace(Player player, BlockPos pos) {
        // cube
        Level world = player.level();
        BlockState state = Blocks.STONE.defaultBlockState();
        return state.canSurvive(world, pos)
                && world.isUnobstructed(state, pos, CollisionContext.placementContext(player));
    }
    // should equals getBlockPlacement(state.getBlock) != null
    public static boolean canBlockPlace(Player player, BlockPos pos, BlockState state) {
        Level world = player.level();
        return state.canSurvive(world, pos)
                && world.isUnobstructed(state, pos, CollisionContext.placementContext(player));
    }

    public static BlockPos getCurrentPlacePos(Player player, BlockHitResult blockHitResult) {
        BlockPlaceContext placement =
                new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(Blocks.STONE), blockHitResult);
        return placement.getClickedPos();
    }

    public static boolean canCubePlace(Player player, BlockHitResult state) {
        BlockPos pos = getCurrentPlacePos(player, state);
        return canCubePlace(player, pos);
    }

    public static InteractionResult simulateInteract(EntityHitResult entityHitResult) {
        // 1.21.11 这里先做「带命中点」交互，不被接受时回退做「不带命中点」的
        // interactEntity(player, entity, hand)。但 26.2 的 MultiPlayerGameMode
        // 已删掉 3 参版本（javap 确认只剩 interact(Player, Entity, EntityHitResult, InteractionHand)），
        // 旧回退语义无法照搬。移植时误把同一调用复制了一遍，导致首次交互被拒时
        // 会重复发 ServerboundInteractPacket 并再次执行本地交互副作用。
        // 按 InteractionTasks.interactEntity 的同批处理方式：去掉该回退分支。
        InteractionResult actionResult = mc.gameMode.interact(
                mc.player, entityHitResult.getEntity(), entityHitResult, InteractionHand.MAIN_HAND);

        if (actionResult instanceof InteractionResult.Success) {
            InteractionResult.Success success = (InteractionResult.Success) actionResult;
            if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
        return actionResult;
    }

    /**
     * 上游 1.1.7.2 新增的重载，签名与上游一致（EntityHitResult）。
     * 委托到既有 simulateInteract，不重复实现交互副作用。
     */
    public static InteractionResult simulateInteractEntity(EntityHitResult entityHitResult) {
        return simulateInteract(entityHitResult);
    }

    public static void swingHandIfSuccess(InteractionResult actionResult3, InteractionHand hand) {
        if (actionResult3 instanceof InteractionResult.Success) {
            InteractionResult.Success success3 = (InteractionResult.Success) actionResult3;
            if (success3.swingSource() == InteractionResult.SwingSource.CLIENT) {
                mc.player.swing(hand);
            }
        }
    }

    public static boolean canHoldUse(ItemStack stack) {
        return stack.has(DataComponents.CONSUMABLE)
                || stack.has(DataComponents.BLOCKS_ATTACKS)
                || VItem.getInstance().isSpear(stack)
                || stack.getUseDuration(mc.player) > 0;
    }

    public static Set<Block> STATE_MAY_INTERACT = null;

    public static boolean canShulkerOpen(Level world, BlockPos pos, BlockState state) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof ShulkerBoxBlockEntity shulkerBoxBlockEntity) {
            if (shulkerBoxBlockEntity.getAnimationStatus() != ShulkerBoxBlockEntity.AnimationStatus.CLOSED) {
                return true;
            }
        }
        AABB box = Shulker.getProgressDeltaAabb(
                        1.0F, state.getValue(ShulkerBoxBlock.FACING), 0.0F, 0.5F, Vec3.atBottomCenterOf(pos))
                .deflate(1.0E-6);
        return world.noCollision(box);
    }

    public static boolean canEnderChestOpen(Level world, BlockPos pos) {
        return !world.getBlockState(pos.above()).isRedstoneConductor(world, pos.above());
    }

    public static boolean canChestOpen(Level world, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock)) {
            return false;
        }
        if (ChestBlock.isChestBlockedAt(world, pos)) {
            return false;
        }
        if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(state));
            if (ChestBlock.isChestBlockedAt(world, otherPos)) {
                return false;
            }
        }
        return true;
    }

    public static boolean canRespawnAnchorExplode(Level world) {
        String worldName = world.dimension().identifier().toString();
        if (Objects.equals(worldName, "minecraft:overworld") || Objects.equals(worldName, "minecraft:the_end")) {
            return true;
        }
        if (!world.dimensionType().hasCeiling()) {
            return true;
        }
        if (world.dimensionType().attributes().contains(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS)
                && world.dimensionType()
                                .attributes()
                                .get(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS)
                                .argument()
                        instanceof Boolean bl
                && bl) {
            // may not explode
            return false;
        }
        return true;
    }

    private static boolean isInteractableRespawnAnchor(BlockState state, ItemStack stack) {
        int charges = state.getValue(RespawnAnchorBlock.CHARGE);
        if (charges == 0 && !stack.is(Items.GLOWSTONE)) {
            return false;
        }
        return true;
    }

    private static boolean canFenceConsume(Level world, BlockPos pos, @Nullable Player player) {
        if (player == null) {
            return false;
        }
        boolean hasLead = player.getMainHandItem().getItem() instanceof LeadItem
                || player.getOffhandItem().getItem() instanceof LeadItem;
        if (!hasLead) {
            return false;
        }
        List<Leashable> leashables =
                Leashable.leashableInArea(world, Vec3.atCenterOf(pos), entity -> entity.getLeashHolder() == player);
        return !leashables.isEmpty();
    }

    public static boolean canBlockOpenScreen(Level world, BlockState state, BlockPos pos) {
        return state.getMenuProvider(world, pos) != null;
    }

    public static boolean canOpenScreen(Level world, Player player, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof ChestBlock) {
            return canChestOpen(world, pos, state);
        }
        if (block instanceof ShulkerBoxBlock) {
            return canShulkerOpen(world, pos, state);
        }
        if (block instanceof EnderChestBlock) {
            return canEnderChestOpen(world, pos);
        }
        if (block instanceof LecternBlock) {
            return state.hasProperty(LecternBlock.HAS_BOOK) && state.getValue(LecternBlock.HAS_BOOK);
        }
        MenuProvider factory = state.getMenuProvider(world, pos);
        return factory != null;
    }

    /**
     * 上游 1.1.7.2 的新名（isInteractAcceptable -> isInteractOnBlockAcceptable）。
     * 我方保留既有名不动（全仓数十处调用），这里只新增同名重载并委托，避免大规模改名风险。
     */
    public static boolean isInteractOnBlockAcceptable(
            Level world, Player player, BlockPos pos, BlockState state) {
        return isInteractAcceptable(world, player, pos, state);
    }

    public static boolean isInteractOnBlockAcceptable(
            Level world, Player player, BlockPos pos, BlockState state, ItemStack interactStack) {
        return isInteractAcceptable(world, player, pos, state, interactStack);
    }

    public static boolean isInteractAcceptable(Level world, Player player, BlockPos pos, BlockState state) {
        return isInteractAcceptable(world, player, pos, state, ItemStack.EMPTY);
    }

    public static final Set<Block> shovelBlocks = new HashSet<>();

    static {
        shovelBlocks.add(Blocks.GRASS_BLOCK);
        shovelBlocks.add(Blocks.DIRT);
        shovelBlocks.add(Blocks.PODZOL);
        shovelBlocks.add(Blocks.COARSE_DIRT);
        shovelBlocks.add(Blocks.MYCELIUM);
        shovelBlocks.add(Blocks.ROOTED_DIRT);
    }

    public static boolean isInteractAcceptable(
            Level world, Player player, BlockPos pos, BlockState state, ItemStack interactStack) {
        Block block = state.getBlock();
        if (block instanceof RespawnAnchorBlock) {
            return isInteractableRespawnAnchor(state, interactStack);
        }
        if (block instanceof LecternBlock) {
            return state.hasProperty(LecternBlock.HAS_BOOK) && state.getValue(LecternBlock.HAS_BOOK);
        }
        if (block instanceof FenceBlock) {
            return canFenceConsume(world, pos, player);
        }
        if (block instanceof JukeboxBlock) {
            return state.hasProperty(JukeboxBlock.HAS_RECORD) && state.getValue(JukeboxBlock.HAS_RECORD);
        }
        if ((block instanceof CakeBlock || block instanceof CandleCakeBlock) && !player.canEat(false)) {
            return false;
        }
        if (block instanceof PumpkinBlock pumpkinBlock) {
            return interactStack.is(Items.SHEARS);
        }
        if (block instanceof ComposterBlock composterBlock) {
            return (state.hasProperty(ComposterBlock.LEVEL) && state.getValue(ComposterBlock.LEVEL) == 8)
                    || ComposterBlock.COMPOSTABLES.containsKey(interactStack.getItem());
        }
        if (block instanceof BeehiveBlock beehive) {
            return state.hasProperty(BeehiveBlock.HONEY_LEVEL)
                    && state.getValue(BeehiveBlock.HONEY_LEVEL) >= 5
                    && (interactStack.is(Items.SHEARS) || interactStack.is(Items.GLASS_BOTTLE));
        }
        if (block instanceof CampfireBlock campfireBlock) {
            return world.recipeAccess()
                    .propertySet(RecipePropertySet.CAMPFIRE_INPUT)
                    .test(interactStack);
        }
        if (block instanceof AbstractCauldronBlock cauldronBlock) {
            return cauldronBlock.interactions.items.containsKey(interactStack.getItem());
        }
        Item item = interactStack.getItem();
        // 矿车放铁轨
        if (item instanceof MinecartItem && state.is(BlockTags.RAILS)) {
            return true;
        }
        // 盔甲架
        if (item instanceof ArmorStandItem) {
            return true;
        }
        // 末地水晶
        if (item instanceof EndCrystalItem && (state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK))) {
            return true;
        }
        if (item instanceof SpawnEggItem) {
            return state.hasBlockEntity() && world.getBlockEntity(pos) instanceof Spawner
                    || state.getCollisionShape(world, pos).isEmpty();
        }
        // 打火石 / 火焰弹
        if (item instanceof FlintAndSteelItem || item instanceof FireChargeItem) {
            if (CampfireBlock.canLight(state) || CandleBlock.canLight(state) || CandleCakeBlock.canLight(state)) {
                return true;
            }
            BlockPos firePos = pos.relative(Direction.UP);
            if (BaseFireBlock.canBePlacedAt(world, firePos, player.getDirection())) {
                return true;
            }
        }
        // 骨粉
        if (item instanceof BoneMealItem) {
            Block varBoneMealBlock = state.getBlock();
            if (varBoneMealBlock instanceof BonemealableBlock fertilizable
                    && fertilizable.isValidBonemealTarget(world, pos, state)) {
                return true;
            }
            BlockPos sidePos = pos.above();
            if (state.isFaceSturdy(world, pos, Direction.UP)
                    && world.getBlockState(sidePos).is(Blocks.WATER)
                    && world.getFluidState(sidePos).getAmount() == 8) {
                return true;
            }
        }
        // 铲子拍平 / 熄灭营火
        if (item instanceof ShovelItem) {
            if (shovelBlocks.contains(block) && world.getBlockState(pos.above()).isAir()) {
                return true;
            }
            //            if (block instanceof CampfireBlock && state.get(CampfireBlock.LIT)) {
            //                return true;
            //            }
        }
        // 蜂蜜脾上蜡
        if (item instanceof HoneycombItem && HoneycombItem.getWaxed(state).isPresent()) {
            return true;
        }
        // 水瓶变泥
        if (item instanceof PotionItem) {
            PotionContents potionContents =
                    interactStack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            if (potionContents.is(Potions.WATER) && state.is(BlockTags.CONVERTABLE_TO_MUD)) {
                return true;
            }
        }

        if (STATE_MAY_INTERACT == null) {
            HashSet<Block> result = new HashSet<>();
            for (Block entry : BuiltInRegistries.BLOCK) {
                if (entry instanceof GameMasterBlock
                        || entry instanceof SignBlock
                        || entry instanceof DoorBlock
                        || entry instanceof TrapDoorBlock
                        || entry instanceof FenceGateBlock
                        || entry instanceof BedBlock
                        || entry instanceof CakeBlock
                        || entry instanceof CandleCakeBlock
                        || entry instanceof FlowerPotBlock
                        || entry instanceof DecoratedPotBlock
                        || entry instanceof JukeboxBlock
                        || entry instanceof BellBlock
                        || entry instanceof LeverBlock
                        || entry instanceof ButtonBlock
                        || entry instanceof RedStoneOreBlock
                        || entry instanceof NoteBlock
                        || entry instanceof LightBlock
                        || entry instanceof DragonEggBlock
                        || entry instanceof ChestBlock
                        || entry instanceof ShulkerBoxBlock
                        || entry instanceof EnderChestBlock
                        || entry instanceof CraftingTableBlock
                        || entry instanceof StonecutterBlock
                        || entry instanceof LoomBlock
                        || entry instanceof SmithingTableBlock
                        || entry instanceof CartographyTableBlock
                        || entry instanceof GrindstoneBlock
                        || entry instanceof AnvilBlock
                        || entry instanceof BeaconBlock
                        || entry instanceof BarrelBlock
                        || entry instanceof BrewingStandBlock
                        || entry instanceof DispenserBlock
                        || entry instanceof HopperBlock
                        || entry instanceof CrafterBlock
                        || entry instanceof AbstractFurnaceBlock) {
                    result.add(entry);
                }
            }
            STATE_MAY_INTERACT = result;
        }
        return STATE_MAY_INTERACT.contains(block);
    }

    public static final Set<Class<? extends Entity>> ENTITY_MAY_INTERACT;

    static {
        HashSet<Class<? extends Entity>> result = new HashSet<>();
        ENTITY_MAY_INTERACT = result;
    }

    public static boolean isInteractAcceptable(Level world, Player player, Entity entity, ItemStack interactStack) {
        if (world == null || player == null || entity == null) {
            return false;
        }
        if (player.isSpectator() || !entity.isAlive()) {
            return false;
        }
        ItemStack stack = interactStack == null ? ItemStack.EMPTY : interactStack;
        if (isGenericAcceptedInteractItem(entity, stack)) {
            return true;
        }
        if (isVehicleEntityInteractAcceptable(player, entity, stack)) {
            return true;
        }
        if (isSpecialEntityInteractAcceptable(player, entity, stack)) {
            return true;
        }
        return false;
    }

    private static boolean isGenericAcceptedInteractItem(Entity entity, ItemStack stack) {
        if (stack.is(Items.NAME_TAG) && entity instanceof LivingEntity) {
            return true;
        }
        if (stack.getItem() instanceof SpawnEggItem && entity instanceof Mob) {
            return true;
        }
        if (stack.is(Items.LEAD) && entity instanceof Leashable && !(entity instanceof LeashFenceKnotEntity)) {
            return true;
        }
        if (stack.is(Items.SADDLE)
                && entity instanceof LivingEntity livingEntity
                && livingEntity.isEquippableInSlot(stack, EquipmentSlot.SADDLE)) {
            return true;
        }
        if (stack.is(Items.WATER_BUCKET) && entity instanceof Bucketable) {
            return true;
        }
        if (stack.is(Items.SHEARS) && entity instanceof Shearable shearable && shearable.readyForShearing()) {
            return true;
        }
        if (entity instanceof Animal animal && animal.isFood(stack)) {
            return true;
        }
        if (entity instanceof MushroomCow mooshroom) {
            if (!mooshroom.isBaby() && stack.is(Items.BOWL)) {
                return true;
            }
            return mooshroom.getVariant() == MushroomCow.Variant.BROWN
                    && SuspiciousEffectHolder.tryGet(stack.getItem()) != null;
        }
        if (entity instanceof AbstractCow cow) {
            return stack.is(Items.BUCKET) && !cow.isBaby();
        }
        if (entity instanceof Goat goat) {
            return stack.is(Items.BUCKET) && !goat.isBaby();
        }

        if (entity instanceof IronGolem ironGolem) {
            return stack.is(Items.IRON_INGOT) && ironGolem.getHealth() < ironGolem.getMaxHealth();
        }
        if (entity instanceof Armadillo armadillo) {
            return stack.is(Items.BRUSH) && !armadillo.isBaby();
        }
        if (entity instanceof Dolphin) {
            return stack.is(ItemTags.FISHES);
        }
        if (entity instanceof Tadpole) {
            return stack.is(ItemTags.FROG_FOOD) || stack.is(Items.WATER_BUCKET);
        }
        if (entity instanceof Parrot) {
            return stack.is(ItemTags.PARROT_FOOD) || stack.is(ItemTags.PARROT_POISONOUS_FOOD);
        }
        return false;
    }

    private static boolean isVehicleEntityInteractAcceptable(Player player, Entity entity, ItemStack stack) {
        if (entity instanceof ContainerEntity) {
            return true;
        }
        if (entity instanceof MinecartFurnace) {
            return true;
        }
        if (entity instanceof MinecartCommandBlock) {
            return player.canUseGameMasterBlocks();
        }
        if (entity instanceof Minecart minecart) {
            return !player.isSecondaryUseActive() && !minecart.isVehicle();
        }
        if (entity instanceof AbstractBoat) {
            return !player.isSecondaryUseActive();
        }
        return false;
    }

    private static boolean isSpecialEntityInteractAcceptable(Player player, Entity entity, ItemStack stack) {
        if (entity instanceof ArmorStand armorStand) {
            return !armorStand.isMarker();
        }
        if (entity instanceof ItemFrame itemFrame) {
            if (itemFrame.isRemoved()) {
                return false;
            }
            return !itemFrame.getItem().isEmpty() || !stack.isEmpty();
        }
        if (entity instanceof LeashFenceKnotEntity) {
            return true;
        }
        if (entity instanceof Allay allay) {
            if (allay.isDancing() && stack.is(ItemTags.DUPLICATES_ALLAYS) && allay.canDuplicate()) {
                return true;
            }
            if (!allay.hasItemInHand() && !stack.isEmpty()) {
                return true;
            }
            return allay.hasItemInHand() && stack.isEmpty();
        }
        if (entity instanceof Camel camel) {
            if (camel.isBaby()) {
                return camel.isFood(stack);
            }
            return true;
        }
        if (entity instanceof AbstractHorse horse) {
            if (horse.isBaby()) {
                return horse.isFood(stack);
            }
            return true;
        }
        if (entity instanceof Pig pig) {
            if (pig.isSaddled() && !pig.isVehicle() && !player.isSecondaryUseActive()) {
                return true;
            }
        }
        if (entity instanceof Strider strider) {
            if (strider.isSaddled() && !strider.isVehicle() && !player.isSecondaryUseActive()) {
                return true;
            }
        }
        if (entity instanceof Villager villager) {
            return !villager.isTrading() && !villager.isSleeping();
        }
        if (entity instanceof WanderingTrader trader) {
            return !trader.isTrading() && !trader.isBaby();
        }
        if (entity instanceof Wolf wolf) {
            if (wolf.isTame()) {
                if (wolf.isFood(stack) && wolf.getHealth() < wolf.getMaxHealth()) {
                    return true;
                }
                if (wolf.isOwnedBy(player)) {
                    if (stack.getItem() instanceof DyeItem dyeItem
                            && stack.get(net.minecraft.core.component.DataComponents.DYE) != wolf.getCollarColor()) {
                        return true;
                    }
                    if (stack.is(Items.WOLF_ARMOR) && !wolf.isBaby() && !wolf.isWearingBodyArmor()) {
                        return true;
                    }
                    if (wolf.isInSittingPose()
                            && wolf.isWearingBodyArmor()
                            && wolf.getBodyArmorItem().isDamaged()
                            && wolf.getBodyArmorItem().isValidRepairItem(stack)) {
                        return true;
                    }
                    return true;
                }
                return false;
            }
            return stack.is(Items.BONE) && !wolf.isAngry();
        }
        if (entity instanceof Cat cat) {
            if (!cat.isTame()) {
                return cat.isFood(stack);
            }
            if (cat.isOwnedBy(player)) {
                if (stack.getItem() instanceof DyeItem dyeItem
                        && stack.get(net.minecraft.core.component.DataComponents.DYE) != cat.getCollarColor()) {
                    return true;
                }
                if (cat.isFood(stack) && cat.getHealth() < cat.getMaxHealth()) {
                    return true;
                }
                return true;
            }
            return false;
        }
        if (entity instanceof Ocelot ocelot) {
            return !ocelot.isTrusting() && ocelot.isFood(stack);
        }
        if (entity instanceof Parrot parrot) {
            if (!parrot.isTame()) {
                return stack.is(ItemTags.PARROT_FOOD) || stack.is(ItemTags.PARROT_POISONOUS_FOOD);
            }
            return parrot.isOwnedBy(player);
        }
        if (entity instanceof Piglin piglin) {
            return piglin.getArmPose() != PiglinArmPose.ADMIRING_ITEM;
        }
        return false;
    }

    public static boolean isInteractAtAcceptable(
            Level world, Player player, Entity entity, Vec3 hitPos, ItemStack interactStack) {
        if (world == null || player == null || entity == null || hitPos == null) {
            return false;
        }
        if (player.isSpectator() || !entity.isAlive()) {
            return false;
        }
        ItemStack stack = interactStack == null ? ItemStack.EMPTY : interactStack;
        if (entity instanceof ArmorStand armorStand) {
            if (armorStand.isMarker()) {
                return false;
            }
            if (stack.is(Items.NAME_TAG)) {
                return false;
            }
            if (stack.isEmpty()) {
                EquipmentSlot hitSlot = getArmorStandHitSlot(armorStand, hitPos);
                if (canArmorStandUseSlot(armorStand, hitSlot)
                        && !armorStand.getItemBySlot(hitSlot).isEmpty()) {
                    return true;
                }
                EquipmentSlot preferredSlot = EquipmentSlot.MAINHAND;
                if (canArmorStandUseSlot(armorStand, preferredSlot)
                        && !armorStand.getItemBySlot(preferredSlot).isEmpty()) {
                    return true;
                }
                EquipmentSlot offhandSlot = EquipmentSlot.OFFHAND;
                return canArmorStandUseSlot(armorStand, offhandSlot)
                        && !armorStand.getItemBySlot(offhandSlot).isEmpty();
            }
            EquipmentSlot slot = armorStand.getEquipmentSlotForItem(stack);
            if (!canArmorStandUseSlot(armorStand, slot)) {
                return false;
            }
            if (slot.getType() == EquipmentSlot.Type.HAND && !armorStand.showArms()) {
                return false;
            }
            return true;
        }
        return false;
    }

    public static boolean isInteractAcceptable(Level world, Player player, ItemStack interactStack) {
        if (world == null || player == null || interactStack == null || interactStack.isEmpty()) {
            return false;
        }
        if (!interactStack.isItemEnabled(world.enabledFeatures())) {
            return false;
        }
        if (player.getCooldowns().isOnCooldown(interactStack)) {
            return false;
        }

        Consumable consumableComponent = interactStack.get(DataComponents.CONSUMABLE);
        if (consumableComponent != null) {
            return consumableComponent.canConsume(player, interactStack);
        }

        Equippable equippableComponent = interactStack.get(DataComponents.EQUIPPABLE);
        if (equippableComponent != null && equippableComponent.swappable()) {
            if (!player.canUseSlot(equippableComponent.slot())
                    || !equippableComponent.canBeEquippedBy(player.getType().builtInRegistryHolder())) {
                return false;
            }
            ItemStack equippedStack = player.getItemBySlot(equippableComponent.slot());
            return !ItemStack.isSameItemSameComponents(interactStack, equippedStack);
        }

        if (interactStack.has(DataComponents.BLOCKS_ATTACKS)
                || interactStack.has(DataComponents.KINETIC_WEAPON)
                || VItem.getInstance().isSpear(interactStack)) {
            return true;
        }

        Item item = interactStack.getItem();
        if (item instanceof BowItem) {
            return player.hasInfiniteMaterials()
                    || !player.getProjectile(interactStack).isEmpty();
        }
        if (item instanceof CrossbowItem) {
            ChargedProjectiles chargedProjectilesComponent = interactStack.get(DataComponents.CHARGED_PROJECTILES);
            return chargedProjectilesComponent != null && !chargedProjectilesComponent.isEmpty()
                    || !player.getProjectile(interactStack).isEmpty();
        }
        if (item instanceof TridentItem) {
            return true;
        }
        if (item instanceof InstrumentItem) {
            return interactStack.has(DataComponents.INSTRUMENT);
        }
        if (item instanceof FireworkRocketItem) {
            return player.isFallFlying();
        }
        if (item instanceof FoodOnAStickItem) {
            return player.isPassenger();
        }

        return item instanceof SpyglassItem
                || item instanceof BundleItem
                || item instanceof FishingRodItem
                || item instanceof BucketItem
                || item instanceof BoatItem
                || item instanceof PlaceOnWaterBlockItem
                || item instanceof SpawnEggItem
                || item instanceof EmptyMapItem
                || item instanceof BottleItem
                || item instanceof WrittenBookItem
                || item instanceof WritableBookItem
                || item instanceof KnowledgeBookItem
                || item instanceof EnderEyeItem
                || item instanceof ProjectileItem;
    }

    private static EquipmentSlot getArmorStandHitSlot(ArmorStand armorStand, Vec3 hitPos) {
        EquipmentSlot slot = EquipmentSlot.MAINHAND;
        boolean small = armorStand.isSmall();
        double y = hitPos.y / (armorStand.getScale() * armorStand.getAgeScale());
        if (y >= 0.1
                && y < 0.1 + (small ? 0.8 : 0.45)
                && !armorStand.getItemBySlot(EquipmentSlot.FEET).isEmpty()) {
            slot = EquipmentSlot.FEET;
        } else if (y >= 0.9 + (small ? 0.3 : 0.0)
                && y < 0.9 + (small ? 1.0 : 0.7)
                && !armorStand.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
            slot = EquipmentSlot.CHEST;
        } else if (y >= 0.4
                && y < 0.4 + (small ? 1.0 : 0.8)
                && !armorStand.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) {
            slot = EquipmentSlot.LEGS;
        } else if (y >= 1.6 && !armorStand.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
            slot = EquipmentSlot.HEAD;
        } else if (armorStand.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()
                && !armorStand.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
            slot = EquipmentSlot.OFFHAND;
        }
        return slot;
    }

    private static boolean canArmorStandUseSlot(ArmorStand armorStand, EquipmentSlot slot) {
        return slot != EquipmentSlot.BODY && slot != EquipmentSlot.SADDLE && armorStand.canUseSlot(slot);
    }

    public static boolean canInteractAndPlace(Player player, FlagEntry<BlockHitResult> sneak) {
        return sneak != null && (player.isSecondaryUseActive() || !sneak.flag());
    }

    public static boolean canInteractAndPlace(Player player, boolean flag) {
        return player.isSecondaryUseActive() || !flag;
    }

    public static boolean canBeReplaceTo(BlockState fromState, BlockState toState) {
        if (fromState == null || toState == null) {
            return false;
        }
        if (fromState.equals(toState)) {
            return true;
        }

        return !getNextInteractionStep(fromState, toState).isEmpty();
    }

    public static Set<Pair<BlockState, Predicate<ItemStack>>> getNextInteractionStep(
            BlockState fromState, BlockState toState) {
        Set<Pair<BlockState, Predicate<ItemStack>>> result = new HashSet<>();
        if (fromState == null || toState == null) {
            return result;
        }
        Block fromBlock = fromState.getBlock();
        Block targetBlock = toState.getBlock();

        if (fromBlock == targetBlock) {
            if (fromBlock instanceof SlabBlock
                    && fromState.getValue(SlabBlock.TYPE) != SlabType.DOUBLE
                    && toState.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
                result.add(Pair.of(fromState.setValue(SlabBlock.TYPE, SlabType.DOUBLE), isItem(fromBlock.asItem())));
            }
            int layers;
            if (fromBlock instanceof SnowLayerBlock
                    && (layers = fromState.getValue(SnowLayerBlock.LAYERS)) < 8
                    && toState.getValue(SnowLayerBlock.LAYERS) > layers) {
                result.add(Pair.of(fromState.setValue(SnowLayerBlock.LAYERS, layers + 1), isItem(fromBlock.asItem())));
            }
            if (fromBlock instanceof CandleBlock
                    && (layers = fromState.getValue(CandleBlock.CANDLES)) < 4
                    && toState.getValue(CandleBlock.CANDLES) > layers) {
                result.add(Pair.of(fromState.setValue(CandleBlock.CANDLES, layers + 1), isItem(fromBlock.asItem())));
            }
            if (fromBlock instanceof SeaPickleBlock
                    && (layers = fromState.getValue(SeaPickleBlock.PICKLES)) < 4
                    && toState.getValue(SeaPickleBlock.PICKLES) > layers) {
                result.add(Pair.of(fromState.setValue(SeaPickleBlock.PICKLES, layers + 1), isItem(fromBlock.asItem())));
            }
            if (fromBlock instanceof FlowerBedBlock
                    && (layers = fromState.getValue(FlowerBedBlock.AMOUNT)) < 4
                    && toState.getValue(FlowerBedBlock.AMOUNT) > layers) {
                result.add(Pair.of(fromState.setValue(FlowerBedBlock.AMOUNT, layers + 1), isItem(fromBlock.asItem())));
            }
            if (fromBlock instanceof LeafLitterBlock
                    && fromState.getValue(LeafLitterBlock.AMOUNT) < 4
                    && toState.equals(fromState.setValue(
                            LeafLitterBlock.AMOUNT, fromState.getValue(LeafLitterBlock.AMOUNT) + 1))) {
                result.add(Pair.of(toState, isItem(fromBlock.asItem())));
            }
            if (fromBlock instanceof RepeaterBlock
                    && !toState.getValue(RepeaterBlock.DELAY).equals(fromState.getValue(RepeaterBlock.DELAY))) {
                result.add(Pair.of(fromState.cycle(RepeaterBlock.DELAY), ALWAYS_TRUE));
            }
            if (fromBlock instanceof ComparatorBlock
                    && toState.getValue(ComparatorBlock.MODE) != fromState.getValue(ComparatorBlock.MODE)) {
                result.add(Pair.of(fromState.cycle(ComparatorBlock.MODE), ALWAYS_TRUE));
            }
            if (fromBlock instanceof DoorBlock
                    && toState.getValue(DoorBlock.OPEN) != fromState.getValue(DoorBlock.OPEN)) {
                result.add(Pair.of(fromState.cycle(DoorBlock.OPEN), ALWAYS_TRUE));
            }
            if (fromBlock instanceof TrapDoorBlock
                    && toState.getValue(TrapDoorBlock.OPEN) != fromState.getValue(TrapDoorBlock.OPEN)) {
                result.add(Pair.of(fromState.cycle(TrapDoorBlock.OPEN), ALWAYS_TRUE));
            }
            if (fromBlock instanceof FenceGateBlock
                    && toState.getValue(FenceGateBlock.OPEN) != fromState.getValue(FenceGateBlock.OPEN)) {
                result.add(Pair.of(fromState.cycle(FenceGateBlock.OPEN), ALWAYS_TRUE));
            }
            if (fromBlock instanceof LeverBlock
                    && toState.getValue(LeverBlock.POWERED) != fromState.getValue(LeverBlock.POWERED)) {
                result.add(Pair.of(fromState.cycle(LeverBlock.POWERED), ALWAYS_TRUE));
            }
            if (fromBlock instanceof ButtonBlock
                    && !fromState.getValue(ButtonBlock.POWERED)
                    && toState.equals(fromState.setValue(ButtonBlock.POWERED, true))) {
                result.add(Pair.of(toState, ALWAYS_TRUE));
            }
            if (fromBlock instanceof NoteBlock
                    && toState.getValue(NoteBlock.NOTE) != fromState.getValue(NoteBlock.NOTE)) {
                result.add(Pair.of(fromState.cycle(NoteBlock.NOTE), ALWAYS_TRUE));
            }
            if (fromBlock instanceof CandleBlock
                    && fromState.getValue(CandleBlock.LIT)
                    && toState.equals(fromState.setValue(CandleBlock.LIT, false))) {
                result.add(Pair.of(toState, ItemStack::isEmpty));
            }
            if (fromBlock instanceof CandleBlock
                    && !fromState.getValue(CandleBlock.LIT)
                    && !fromState.getValue(CandleBlock.WATERLOGGED)
                    && toState.equals(fromState.setValue(CandleBlock.LIT, true))) {
                result.add(Pair.of(toState, isAnyOf(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE)));
            }
            if (fromBlock instanceof RespawnAnchorBlock
                    && fromState.getValue(RespawnAnchorBlock.CHARGE) < 4
                    && toState.getValue(RespawnAnchorBlock.CHARGE) > fromState.getValue(RespawnAnchorBlock.CHARGE)) {
                result.add(Pair.of(
                        fromState.setValue(
                                RespawnAnchorBlock.CHARGE, fromState.getValue(RespawnAnchorBlock.CHARGE) + 1),
                        isItem(Items.GLOWSTONE)));
            }
            if (fromBlock instanceof CakeBlock
                    && fromState.getValue(CakeBlock.BITES) < 6
                    && toState.equals(fromState.setValue(CakeBlock.BITES, fromState.getValue(CakeBlock.BITES) + 1))) {
                result.add(Pair.of(toState, ALWAYS_TRUE));
            }
            if (fromBlock instanceof FlowerPotBlock fromPot
                    && targetBlock instanceof FlowerPotBlock targetPot
                    && fromPot.getPotted() != Blocks.AIR
                    && targetPot.getPotted() == Blocks.AIR) {
                result.add(Pair.of(toState, ItemStack::isEmpty));
            }
        }

        if (fromBlock instanceof CakeBlock
                && targetBlock instanceof CandleCakeBlock
                && fromState.getValue(CakeBlock.BITES) == 0) {
            Item candleItem = getRequiredCandleItem(targetBlock);
            if (candleItem != null) {
                result.add(Pair.of(toState, isItem(candleItem)));
            }
        }
        if (fromBlock instanceof CandleCakeBlock
                && targetBlock instanceof CakeBlock
                && toState.getValue(CakeBlock.BITES) == 1) {
            result.add(Pair.of(toState, ALWAYS_TRUE));
        }
        if (fromBlock instanceof FlowerPotBlock fromPot
                && targetBlock instanceof FlowerPotBlock targetPot
                && fromPot.getPotted() == Blocks.AIR
                && targetPot.getPotted() != Blocks.AIR) {
            Block content = targetPot.getPotted();
            result.add(Pair.of(
                    toState,
                    stack -> stack != null && stack.getItem() instanceof BlockItem item && item.getBlock() == content));
        }
        if (fromBlock instanceof PumpkinBlock && targetBlock == Blocks.CARVED_PUMPKIN) {
            result.add(Pair.of(toState, isItem(Items.SHEARS)));
        }
        return result;
    }

    private static Predicate<ItemStack> isItem(Item item) {
        return stack -> stack != null && stack.is(item);
    }

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

    private static Item getRequiredCandleItem(Block candleCakeBlock) {
        var blockId = BuiltInRegistries.BLOCK.getKey(candleCakeBlock);
        String path = blockId.getPath();
        if (!path.endsWith("_cake")) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(blockId.withPath(path.substring(0, path.length() - 5)));
        return item == Items.AIR ? null : item;
    }
}
