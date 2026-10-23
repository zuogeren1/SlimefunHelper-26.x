package me.matl114.hacks.modules.survival;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import lombok.With;
import me.matl114.events.Event;
import me.matl114.events.impl.MetadataUpdate;
import me.matl114.events.Listener;
import me.matl114.gui.presets.single.RegistryDisplays;
import me.matl114.hacks.WorldTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.extra.EventNotify;
import me.matl114.hacks.modules.extra.SoundNotify;
import me.matl114.hacks.modules.task.ServerStorage;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Holder;
import me.matl114.hacks.utils.world.ChunkStorage;
import me.matl114.hooks.impl.xaerowaypoints.IXWaypoint;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.MathUtils;
import me.matl114.utils.WorldUtils;
import me.matl114.versioned.api.VDataFlag;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class SearchLabel extends BaseModule {
    public final ModulePath travellingControl = makePath(Configs.SURVIVAL_CONFIG, "travelling-control");
    public final ModulePath searchControl = travellingControl.add("search-label");

    public SearchLabel() {
        super("SearchLabel");
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(searchControl.addEnable()).build();

    public final KeyBindRef hotkey = moduleEntry(
                    searchControl.addHotkey(), new MultiKeyBind(), searchControl.addEnable())
            .build();

    public final FlagRef labelImportantBlocks =
            flagBuilder(searchControl.add("enable-important-blocks")).build();

    public final NBTRef<EntrySet<Block>> importantBlocks = builder(
                    searchControl.add("important-blocks"), EntrySet.<Block>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.BLOCK, List.of(Blocks.CHEST, Blocks.SHULKER_BOX)))
            .build();

    public final IntRef importantBlocksCount = intBuilder(searchControl.add("block-counts"))
            .defaultValue(8)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final NBTRef<EntrySet<Block>> instantBlocks = builder(
                    searchControl.add("instant-blocks"), EntrySet.<Block>parameter())
            .defaultValue(
                    new EntrySet<>(BuiltInRegistries.BLOCK, List.of(Blocks.SHULKER_BOX, Blocks.CRAFTER, Blocks.ENDER_CHEST)))
            .build();

    public final FlagRef labelImportantItems =
            flagBuilder(searchControl.add("enable-important-items")).build();

    public final NBTRef<EntrySet<Item>> importantItems = builder(
                    searchControl.add("important-items"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.ITEM, List.of(Items.ELYTRA, Items.FILLED_MAP, Items.SHULKER_BOX)))
            .build();

    public final IntRef importantItemsCount = intBuilder(searchControl.add("item-counts"))
            .defaultValue(1)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final FlagRef labelImportantEntities =
            flagBuilder(searchControl.add("enable-important-entities")).build();

    public final NBTRef<EntrySet<EntityType<?>>> importantEntities = builder(
                    searchControl.add("important-entities"), EntrySet.<EntityType<?>>parameter())
            .defaultValue(new EntrySet<>(
                    BuiltInRegistries.ENTITY_TYPE,
                    List.of(EntityTypes.PLAYER, EntityTypes.CHEST_MINECART, EntityTypes.HOPPER_MINECART)))
            .build();

    public final IntRef importantEntitiesCount = intBuilder(searchControl.add("entity-counts"))
            .defaultValue(4)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final NBTRef<EntrySet<EntityType<?>>> instantEntities = builder(
                    searchControl.add("instant-entities"), EntrySet.<EntityType<?>>parameter())
            .defaultValue(
                    new EntrySet<>(BuiltInRegistries.ENTITY_TYPE, List.of(EntityTypes.MINECART, EntityTypes.HOPPER_MINECART)))
            .build();

    public final FlagRef labelInWorldMap = builder(searchControl.add("label-in-world-map"), Boolean.class)
            .defaultValue(true)
            .updateListener(this::updateWorldMapSettings)
            .build();

    public final FlagRef notify =
            flagBuilder(searchControl.add("notify-when-update-label")).build();

    public final FlagRef notifySound =
            flagBuilder(searchControl.add("sound-when-update-label")).build();

    public final NBTRef<Holder<SoundEvent>> searchLabelSound = builder(
                    searchControl.add("sound"), Holder.<SoundEvent>parameter())
            .defaultValue(Holder.of(
                    BuiltInRegistries.SOUND_EVENT,
                    SoundNotify.SEARCH_LABEL_SOUND.map(net.minecraft.core.Holder::value).orElse(null)))
            .build();

    public final FlagRef log =
            flagBuilder(searchControl.add("log-when-update-label")).build();

    public final FlagRef ignoreStructures =
            flagBuilder(searchControl.add("ignore-structures")).build();

    public final FlagRef removeIfClose =
            flagBuilder(searchControl.add("remove-if-close-to-target")).build();

    private static final String KEY_CHUNK_LABEL_RECORD = "slimefunhelper:search_label_chunk_records";

    private void updateWorldMapSettings(boolean show) {
        if (!enable.get()) {
            return;
        }
        if (show) {
            loadCurrentData();
        } else {
            unloadCurrentData();
            loadedWorld = null;
        }
    }

    private void loadCurrentData() {
        unloadCurrentData();
        if (checkNull()) {
            return;
        }
        var serverStorage = ServerStorage.getStorage();
        if (serverStorage == null) {
            return;
        }
        var currentWorldMap = serverStorage.chunkStorageMap.get(mc.level.dimension());
        if (currentWorldMap == null) {
            loadedWorld = mc.level.dimension();
            return;
        }
        for (var re : currentWorldMap.entrySet()) {
            ChunkPos pos = re.getKey();
            ChunkStorage storage = re.getValue();
            ChunkRecord record = storage.get(KEY_CHUNK_LABEL_RECORD, ChunkRecord.CODEC);
            if (record != null && !record.isEmpty()) {
                if (removeIfClose.get() && record.reached()) {
                    continue;
                }
                addTo(pos, createWaypoint(pos, record));
            }
        }
        loadedWorld = mc.level.dimension();
    }

    private String createLabel(ChunkRecord record) {
        StringBuilder name = new StringBuilder("Label:\n");
        record.typeBlock.ifPresent(block -> name.append("Block:")
                .append(RegistryDisplays.getDisplay(block).getString())
                .append("\n"));
        record.typeItem.ifPresent(block -> name.append("Item:")
                .append(RegistryDisplays.getDisplay(block).getString())
                .append("\n"));
        record.typeEntities.ifPresent(block -> name.append("Entity:")
                .append(RegistryDisplays.getDisplay(block).getString())
                .append("\n"));
        return name.toString();
    }

    private IXWaypoint createWaypoint(ChunkPos pos, ChunkRecord record) {
        int color;
        if (record.typeEntities.isPresent()) {
            color = ChatFormatting.RED.ordinal();
        } else if (record.typeItem.isPresent()) {
            color = ChatFormatting.YELLOW.ordinal();
        } else {
            color = ChatFormatting.GREEN.ordinal();
        }
        double scale = mc.level.dimensionType().coordinateScale();

        return XaeroHelper.INSTANCE.createWaypoint(
                (int) (pos.getMiddleBlockX() * scale),
                64,
                (int) (pos.getMiddleBlockZ() * scale),
                createLabel(record),
                "L",
                color,
                0,
                true,
                true);
    }

    private void addTo(ChunkPos pos, IXWaypoint newWaypoint) {
        remove(pos);
        if (newWaypoint != null) {
            XaeroHelper.INSTANCE.submitWaypoint(this, newWaypoint);
        }
    }

    private void remove(ChunkPos pos) {
        XaeroHelper.INSTANCE.removeWaypoint(this, new BlockPos(pos.getMiddleBlockX(), 64, pos.getMiddleBlockZ()));
    }

    private void update(ChunkPos pos, ChunkRecord record) {
        if (record.isEmpty()) {
            ChunkStorage storage = ServerStorage.getChunkStorage(pos);
            if (storage != null) {
                storage.put(KEY_CHUNK_LABEL_RECORD, null);
            }
            remove(pos);
        } else {
            ChunkStorage storage = ServerStorage.getOrCreateChunkStorage(pos);
            if (storage == null) return;
            storage.put(KEY_CHUNK_LABEL_RECORD, record, ChunkRecord.CODEC);
            if (labelInWorldMap.get()) {
                addTo(pos, createWaypoint(pos, record));
            }
            if (notify.get()) {
                EventNotify.INSTANCE.notify("[SlimefunHelper]自动标点", createLabel(record));
            }
            if (notifySound.get()) {
                SoundNotify.INSTANCE.playSound(
                        Optional.ofNullable(this.searchLabelSound.get().entry()).map(BuiltInRegistries.SOUND_EVENT::wrapAsHolder));
            }
            if (log.get()) {
                logI18N("message.module.search-label.label-update", createLabel(record));
            }
        }
    }

    private void markReached(ChunkPos pos) {
        ChunkStorage storage = ServerStorage.getChunkStorage(pos);
        if (storage == null || storage.get(KEY_CHUNK_LABEL_RECORD) == null) {
            return;
        }
        ChunkRecord record = storage.get(KEY_CHUNK_LABEL_RECORD, ChunkRecord.CODEC);
        if (record.reached()) return;
        if (record.isEmpty()) {
            storage.put(KEY_CHUNK_LABEL_RECORD, null);
            remove(pos);
        } else {
            storage.put(KEY_CHUNK_LABEL_RECORD, record.withReached(true), ChunkRecord.CODEC);
            if (removeIfClose.get()) {
                remove(pos);
            }
        }
    }

    private void unloadCurrentData() {
        XaeroHelper.INSTANCE.removeSub(this);
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(XaeroHelper.getWorldSwitchPoint(), this::onWorldSwitch);
        registerListener(Listener.getPostGameTick(), this::onPostTick);
        registerListener(Listener.getServerEntitySpawnListener(), this::onEntitySpawn);
        registerListener(Listener.getChunkUpdateListener(), this::onChunkPostLoad);
        registerListener(
                Listener.getEntityTrackDataUpdate().getChannel(EntityTypes.ITEM), this::handleItemEntityItemData);
        registerListener(
                Listener.getEntityTrackDataUpdate().getChannel(EntityTypes.ITEM_FRAME), this::handleItemFrameItemData);
    }

    ChunkPos lastChunkPos = null;
    ResourceKey<Level> loadedWorld = null;

    @Override
    public void onEnableModule() {
        super.onEnableModule();
        loadedWorld = null;
        loadCurrentData();
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        unloadCurrentData();
        lastChunkPos = null;
        loadedWorld = null;
    }

    private void onWorldSwitch() {
        lastChunkPos = null;
        loadedWorld = null;
        if (enable.get() && labelInWorldMap.get()) {
            loadCurrentData();
        }
    }

    public void onPostTick() {
        if (enable.get() && labelInWorldMap.get() && !Objects.equals(loadedWorld, mc.level.dimension())) {
            loadCurrentData();
        }
        if (enable.get()) {
            if (!Objects.equals(lastChunkPos, mc.player.chunkPosition())) {
                if (mc.player.onGround()) {
                    lastChunkPos = mc.player.chunkPosition();
                    markReached(mc.player.chunkPosition());
                }
            }
        }
    }

    public void onEntitySpawn(Event<Entity> entityEvent) {
        if (enable.get()
                && labelImportantEntities.get()
                && entityEvent.context.distanceToSqr(mc.player.position()) > 256) {
            ChunkPos pos = entityEvent.context.chunkPosition();
            ChunkStorage storage = ServerStorage.getChunkStorage(pos);
            ChunkRecord record =
                    storage == null ? ChunkRecord.EMPTY : storage.get(KEY_CHUNK_LABEL_RECORD, ChunkRecord.CODEC);
            record = record == null ? ChunkRecord.EMPTY : record;
            ChunkRecord oldRecord = record;
            if (!oldRecord.reached() && oldRecord.typeEntities().isEmpty()) {
                if (instantEntities.get().test(entityEvent.context.getType())) {
                    record = record.withTypeEntities(Optional.of(entityEvent.context.getType()));
                } else if (importantEntities.get().test(entityEvent.context.getType())) {
                    // check all entity in chunk
                    if (1 >= importantEntitiesCount.get()
                            || (int) collectCurrentChunk(pos).stream()
                                                    .filter(s -> importantEntities
                                                            .get()
                                                            .test(s.getType()))
                                                    .count()
                                            + 1
                                    >= importantEntitiesCount.get()) {
                        record = record.withTypeEntities(Optional.of(entityEvent.context.getType()));
                    }
                }
            }
            if (!Objects.equals(record, oldRecord)) {
                update(pos, record);
            }
        }
    }

    public void handleItemEntityItemData(Event<MetadataUpdate> entryUpdateEvent) {
        if (enable.get() && labelImportantItems.get()) {
            var entry = entryUpdateEvent.context().metadata();
            if (entry.id() == VDataFlag.ID_ITEM_ITEMSTACK
                    && (entry.value()) instanceof ItemStack stack
                    && entryUpdateEvent.context.entity() instanceof ItemEntity item) {
                onItemEntity(item, stack);
            }
        }
    }

    public void handleItemFrameItemData(Event<MetadataUpdate> entryUpdateEvent) {
        if (enable.get() && labelImportantItems.get()) {
            var entry = entryUpdateEvent.context().metadata();
            if (entry.id() == VDataFlag.ID_ITEM_FRAME_ITEMSTACK
                    && entry.value() instanceof ItemStack stack
                    && entryUpdateEvent.context.entity() instanceof ItemFrame item) {
                onItemEntity(item, stack);
            }
        }
    }

    public void onItemEntity(Entity entity, ItemStack itemStack) {
        if (!itemStack.isEmpty()
                && importantItems.get().test(itemStack.getItem())
                && entity.position().distanceToSqr(mc.player.position()) > 256) {
            ChunkPos pos = entity.chunkPosition();
            ChunkStorage storage = ServerStorage.getChunkStorage(pos);
            ChunkRecord oldRecord =
                    storage == null ? ChunkRecord.EMPTY : storage.get(KEY_CHUNK_LABEL_RECORD, ChunkRecord.CODEC);
            oldRecord = oldRecord == null ? ChunkRecord.EMPTY : oldRecord;
            if (!oldRecord.reached() && oldRecord.typeItem().isEmpty()) {
                if (1 >= importantItemsCount.get()
                        || 1
                                        + (int) collectCurrentChunk(entity.chunkPosition()).stream()
                                                .filter(s -> {
                                                    if (s instanceof ItemEntity item
                                                            && !item.getItem().isEmpty()) {
                                                        return importantItems
                                                                .get()
                                                                .test(item.getItem()
                                                                        .getItem());
                                                    } else if (s instanceof ItemFrame frame
                                                            && !frame.getItem()
                                                                    .isEmpty()) {
                                                        return importantItems
                                                                .get()
                                                                .test(frame.getItem()
                                                                        .getItem());
                                                    } else {
                                                        return false;
                                                    }
                                                })
                                                .count()
                                >= importantItemsCount.get()) {
                    oldRecord = oldRecord.withTypeItem(Optional.of(itemStack.getItem()));
                    update(pos, oldRecord);
                }
            }
        }
    }

    public void onChunkPostLoad(Event<ChunkPos> eventUpdate) {
        if (enable.get() && labelImportantBlocks.get()) {
            ChunkPos chunkPos = eventUpdate.context;
            Tasks.scheduleDelayed(
                    () -> {
                        WorldTasks.scheduleChunkTask(
                                chunkPos,
                                () -> {
                                    ChunkStorage storage = ServerStorage.getChunkStorage(chunkPos);
                                    ChunkRecord oldRecord = storage == null
                                            ? ChunkRecord.EMPTY
                                            : storage.get(KEY_CHUNK_LABEL_RECORD, ChunkRecord.CODEC);
                                    oldRecord = oldRecord == null ? ChunkRecord.EMPTY : oldRecord;
                                    if (oldRecord.reached()) {
                                        return;
                                    }
                                    ChunkRecord newRecord = oldRecord;
                                    Map<BlockPos, BlockState> allStates = null;
                                    find_block:
                                    {
                                        if (allStates == null) {
                                            allStates = WorldUtils.scannChunk(
                                                    mc.level.getChunk(chunkPos.x, chunkPos.z), (bp, bs) -> !bs.isAir());
                                        }
                                        Map<BlockPos, BlockState> filtered = new HashMap<>();
                                        for (var re : allStates.entrySet()) {
                                            if (instantBlocks
                                                    .get()
                                                    .test(re.getValue().getBlock())) {
                                                newRecord = newRecord.withTypeBlock(Optional.of(
                                                        re.getValue().getBlock()));
                                                break find_block;
                                            }
                                            if (importantBlocks
                                                    .get()
                                                    .test(re.getValue().getBlock())) {
                                                filtered.put(re.getKey(), re.getValue());
                                            }
                                        }
                                        if (ignoreStructures.get()) {
                                            filtered = filterStructureSpecialBlocks(filtered, allStates);
                                            if (!filtered.isEmpty() && filtered.size() >= importantBlocksCount.get()) {
                                                newRecord =
                                                        newRecord.withTypeBlock(Optional.of(filtered.values().stream()
                                                                .findFirst()
                                                                .orElseThrow()
                                                                .getBlock()));
                                            } else {
                                                newRecord = newRecord.withTypeBlock(Optional.empty());
                                            }
                                        } else {
                                            var scannResult =
                                                    filtered.values().stream().toList();
                                            if (!scannResult.isEmpty()
                                                    && scannResult.size() >= importantBlocksCount.get()) {
                                                newRecord = newRecord.withTypeBlock(Optional.of(
                                                        scannResult.get(0).getBlock()));
                                            } else {
                                                newRecord = newRecord.withTypeBlock(Optional.empty());
                                            }
                                        }
                                    }

                                    if (!Objects.equals(newRecord, oldRecord)) {
                                        update(chunkPos, newRecord);
                                    }
                                },
                                true);
                    },
                    2);
        }
    }

    private Set<Entity> collectCurrentChunk(ChunkPos chunkPos) {
        Set<Entity> currentChunkEntities = new HashSet<>();
        for (var re : mc.level.entitiesForRendering()) {
            if (MathUtils.intersectsXZ(
                    re.getBoundingBox(),
                    chunkPos.getMinBlockX(),
                    chunkPos.getMinBlockZ(),
                    chunkPos.getMinBlockX() + 16,
                    chunkPos.getMinBlockZ() + 16)) {
                currentChunkEntities.add(re);
            }
        }
        return currentChunkEntities;
    }

    private static final BlockState DEFAULT_STATE = Blocks.AIR.defaultBlockState();

    private static final Set<Block> OVERWORLD_STRUCTURE_BLOCKS = Set.of(
            Blocks.MOSSY_COBBLESTONE,
            Blocks.STONE_BRICKS,
            Blocks.MOSSY_STONE_BRICKS,
            Blocks.CRACKED_STONE_BRICKS,
            Blocks.CHISELED_STONE_BRICKS,
            Blocks.SANDSTONE,
            Blocks.CUT_SANDSTONE,
            Blocks.CHISELED_SANDSTONE,
            Blocks.PRISMARINE,
            Blocks.PRISMARINE_BRICKS,
            Blocks.DARK_PRISMARINE,
            Blocks.POLISHED_DEEPSLATE,
            Blocks.DEEPSLATE_BRICKS,
            Blocks.DEEPSLATE_TILES,
            Blocks.CHISELED_DEEPSLATE,
            Blocks.TUFF,
            Blocks.POLISHED_TUFF,
            Blocks.TUFF_BRICKS,
            Blocks.CHISELED_TUFF,
            Blocks.CHISELED_TUFF_BRICKS,
            Blocks.TERRACOTTA,
//#if MC >= 26.2
            Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE),
//#else
//$$ Blocks.WHITE_TERRACOTTA,
//#endif
//#if MC >= 26.2
            Blocks.DYED_TERRACOTTA.pick(DyeColor.ORANGE),
//#else
//$$ Blocks.ORANGE_TERRACOTTA,
//#endif
//#if MC >= 26.2
            Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE),
//#else
//$$ Blocks.BLUE_TERRACOTTA,
//#endif
//#if MC >= 26.2
            Blocks.DYED_TERRACOTTA.pick(DyeColor.LIGHT_BLUE),
//#else
//$$ Blocks.LIGHT_BLUE_TERRACOTTA,
//#endif
//#if MC >= 26.2
            Blocks.DYED_TERRACOTTA.pick(DyeColor.RED),
//#else
//$$ Blocks.RED_TERRACOTTA,
//#endif
//#if MC >= 26.2
            Blocks.DYED_TERRACOTTA.pick(DyeColor.YELLOW),
//#else
//$$ Blocks.YELLOW_TERRACOTTA,
//#endif
//#if MC >= 26.2
            Blocks.DYED_TERRACOTTA.pick(DyeColor.BROWN),
//#else
//$$ Blocks.BROWN_TERRACOTTA,
//#endif
//#if MC >= 26.2
            Blocks.DYED_TERRACOTTA.pick(DyeColor.GRAY),
//#else
//$$ Blocks.GRAY_TERRACOTTA,
//#endif
//#if MC >= 26.2
            Blocks.DYED_TERRACOTTA.pick(DyeColor.GREEN),
//#else
//$$ Blocks.GREEN_TERRACOTTA,
//#endif
//#if MC >= 26.2
            Blocks.DYED_TERRACOTTA.pick(DyeColor.BLACK),
//#else
//$$ Blocks.BLACK_TERRACOTTA,
//#endif
            Blocks.OBSIDIAN,
            Blocks.CRYING_OBSIDIAN);

    private static final Set<Block> DESERT_PYRAMID_BLOCKS =
            Set.of(Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE);

    private static final Set<Block> JUNGLE_TEMPLE_BLOCKS = Set.of(Blocks.MOSSY_COBBLESTONE);

    private static final Set<Block> OCEAN_STRUCTURE_BLOCKS =
            Set.of(Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE);

    private static final Set<Block> UNDERGROUND_STRUCTURE_BLOCKS = Set.of(
            Blocks.MOSSY_COBBLESTONE,
            Blocks.STONE_BRICKS,
            Blocks.MOSSY_STONE_BRICKS,
            Blocks.CRACKED_STONE_BRICKS,
            Blocks.CHISELED_STONE_BRICKS,
            Blocks.POLISHED_DEEPSLATE,
            Blocks.DEEPSLATE_BRICKS,
            Blocks.DEEPSLATE_TILES,
            Blocks.CHISELED_DEEPSLATE,
            Blocks.TUFF,
            Blocks.POLISHED_TUFF,
            Blocks.TUFF_BRICKS,
            Blocks.CHISELED_TUFF,
            Blocks.CHISELED_TUFF_BRICKS);

    private static final Set<Block> TRIAL_CHAMBER_BLOCKS = Set.of(
            Blocks.TUFF, Blocks.POLISHED_TUFF, Blocks.TUFF_BRICKS, Blocks.CHISELED_TUFF, Blocks.CHISELED_TUFF_BRICKS);

    private static final Set<Block> ANCIENT_CITY_BLOCKS = Set.of(
            Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_TILES, Blocks.CHISELED_DEEPSLATE);

    private static final Set<Block> PILLAGER_OUTPOST_BLOCKS = Set.of(Blocks.DARK_OAK_LOG);

    private static final Set<Block> WOODLAND_MANSION_BLOCKS = Set.of(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD);

    private static final Set<Block> SHIPWRECK_BLOCKS = Set.of(Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG);

    private static final Set<Block> VILLAGE_BLOCKS = Set.of(Blocks.BELL, Blocks.HAY_BLOCK);

    private static final Set<Block> SWAMP_HUT_BLOCKS = Set.of(Blocks.SPRUCE_LOG, Blocks.SPRUCE_PLANKS);

    private static final Set<Block> IGLOO_BLOCKS = Set.of(Blocks.SNOW_BLOCK, Blocks.SNOW);

    private static final Set<Block> BURIED_TREASURE_BLOCKS = Set.of();

    private static final Set<Block> NETHER_FORTRESS_BLOCKS = Set.of(
            Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS);

    private static final Set<Block> BASTION_BLOCKS = Set.of(
            Blocks.BLACKSTONE,
            Blocks.POLISHED_BLACKSTONE,
            Blocks.POLISHED_BLACKSTONE_BRICKS,
            Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS,
            Blocks.CHISELED_POLISHED_BLACKSTONE);

    private static final Set<Block> NETHER_RUINED_PORTAL_BLOCKS = Set.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);

    private static final Set<Block> END_CITY_BLOCKS = Set.of(
            Blocks.PURPUR_BLOCK,
            Blocks.PURPUR_PILLAR,
            Blocks.PURPUR_STAIRS,
            Blocks.PURPUR_SLAB,
            Blocks.END_STONE_BRICKS);

    private Map<BlockPos, BlockState> filterStructureSpecialBlocks(
            Map<BlockPos, BlockState> state, Map<BlockPos, BlockState> allBlocks) {
        Map<BlockPos, BlockState> filteredValues = new HashMap<>();

        for (Map.Entry<BlockPos, BlockState> entry : state.entrySet()) {
            BlockPos pos = entry.getKey();
            Block block = entry.getValue().getBlock();

            if (block != Blocks.CHEST && block != Blocks.BARREL) {
                continue;
            }

            Block above = allBlocks.getOrDefault(pos.above(), DEFAULT_STATE).getBlock();

            Block below = allBlocks.getOrDefault(pos.below(), DEFAULT_STATE).getBlock();

            if (mc.level.dimensionType().hasCeiling()) {
                if (isNetherStructureContainer(pos, above, below)) {
                    filteredValues.put(pos, entry.getValue());
                }
                continue;
            }

            if (!mc.level.dimensionType().hasSkyLight()) {
                if (isEndStructureContainer(pos, above, below)) {
                    filteredValues.put(pos, entry.getValue());
                }
                continue;
            }

            if (isOverworldStructureContainer(pos, above, below)) {
                filteredValues.put(pos, entry.getValue());
            }
        }

        return filteredValues;
    }

    private boolean isOverworldStructureContainer(BlockPos pos, Block above, Block below) {
        net.minecraft.core.Holder<Biome> biome = mc.level.getBiome(pos);

        if (biome.is(BiomeTags.HAS_DESERT_PYRAMID) && contains(DESERT_PYRAMID_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_JUNGLE_TEMPLE) && contains(JUNGLE_TEMPLE_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_OCEAN_MONUMENT) && contains(OCEAN_STRUCTURE_BLOCKS, above, below)) {
            return true;
        }

        if ((biome.is(BiomeTags.HAS_OCEAN_RUIN_COLD) || biome.is(BiomeTags.HAS_OCEAN_RUIN_WARM))
                && contains(OCEAN_STRUCTURE_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_STRONGHOLD) && contains(UNDERGROUND_STRUCTURE_BLOCKS, above, below)) {
            return true;
        }

        if ((biome.is(BiomeTags.HAS_MINESHAFT) || biome.is(BiomeTags.HAS_MINESHAFT_MESA))
                && contains(UNDERGROUND_STRUCTURE_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_ANCIENT_CITY) && contains(ANCIENT_CITY_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_TRIAL_CHAMBERS) && contains(TRIAL_CHAMBER_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_PILLAGER_OUTPOST) && contains(PILLAGER_OUTPOST_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_WOODLAND_MANSION) && contains(WOODLAND_MANSION_BLOCKS, above, below)) {
            return true;
        }

        if ((biome.is(BiomeTags.HAS_SHIPWRECK) || biome.is(BiomeTags.HAS_SHIPWRECK_BEACHED))
                && contains(SHIPWRECK_BLOCKS, above, below)) {
            return true;
        }

        if ((biome.is(BiomeTags.HAS_VILLAGE_DESERT)
                        || biome.is(BiomeTags.HAS_VILLAGE_PLAINS)
                        || biome.is(BiomeTags.HAS_VILLAGE_SAVANNA)
                        || biome.is(BiomeTags.HAS_VILLAGE_SNOWY)
                        || biome.is(BiomeTags.HAS_VILLAGE_TAIGA))
                && contains(VILLAGE_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_SWAMP_HUT) && contains(SWAMP_HUT_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_IGLOO) && contains(IGLOO_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_BURIED_TREASURE) && contains(BURIED_TREASURE_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_TRAIL_RUINS) && contains(OVERWORLD_STRUCTURE_BLOCKS, above, below)) {
            return true;
        }

        if ((biome.is(BiomeTags.HAS_RUINED_PORTAL_DESERT)
                        || biome.is(BiomeTags.HAS_RUINED_PORTAL_JUNGLE)
                        || biome.is(BiomeTags.HAS_RUINED_PORTAL_OCEAN)
                        || biome.is(BiomeTags.HAS_RUINED_PORTAL_SWAMP)
                        || biome.is(BiomeTags.HAS_RUINED_PORTAL_MOUNTAIN)
                        || biome.is(BiomeTags.HAS_RUINED_PORTAL_STANDARD))
                && contains(Set.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN), above, below)) {
            return true;
        }

        return pos.getY() < 50 && contains(UNDERGROUND_STRUCTURE_BLOCKS, above, below);
    }

    private boolean isNetherStructureContainer(BlockPos pos, Block above, Block below) {
        net.minecraft.core.Holder<Biome> biome = mc.level.getBiome(pos);

        if (biome.is(BiomeTags.HAS_NETHER_FORTRESS) && contains(NETHER_FORTRESS_BLOCKS, above, below)) {
            return true;
        }

        if (biome.is(BiomeTags.HAS_BASTION_REMNANT) && contains(BASTION_BLOCKS, above, below)) {
            return true;
        }

        return biome.is(BiomeTags.HAS_RUINED_PORTAL_NETHER)
                && contains(NETHER_RUINED_PORTAL_BLOCKS, above, below);
    }

    private boolean isEndStructureContainer(BlockPos pos, Block above, Block below) {
        net.minecraft.core.Holder<Biome> biome = mc.level.getBiome(pos);

        return biome.is(BiomeTags.HAS_END_CITY) && contains(END_CITY_BLOCKS, above, below);
    }

    private boolean contains(Set<Block> blocks, Block above, Block below) {
        return blocks.contains(above) || blocks.contains(below);
    }

    private static final Set<EntityType<?>> SPECIAL_NATURAL_GEN_TYPES = Set.of();

    private List<Entity> filterStructureSpecialEntities(List<Entity> entities) {
        return entities;
    }

    @With
    public record ChunkRecord(
            Optional<Block> typeBlock, Optional<Item> typeItem, Optional<EntityType<?>> typeEntities, boolean reached) {
        public static final ChunkRecord EMPTY =
                new ChunkRecord(Optional.empty(), Optional.empty(), Optional.empty(), false);
        public static final Codec<ChunkRecord> CODEC = RecordCodecBuilder.create(oinstance -> oinstance
                .group(
                        BuiltInRegistries.BLOCK
                                .byNameCodec()
                                .optionalFieldOf("type-block")
                                .forGetter(ChunkRecord::typeBlock),
                        BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("item-type").forGetter(ChunkRecord::typeItem),
                        BuiltInRegistries.ENTITY_TYPE
                                .byNameCodec()
                                .optionalFieldOf("entity-type")
                                .forGetter(ChunkRecord::typeEntities),
                        Codec.BOOL.optionalFieldOf("reached", false).forGetter(ChunkRecord::reached))
                .apply(oinstance, ChunkRecord::new));

        public boolean isEmpty() {
            return typeBlock.isEmpty() && typeItem.isEmpty() && typeEntities.isEmpty();
        }
    }
}
