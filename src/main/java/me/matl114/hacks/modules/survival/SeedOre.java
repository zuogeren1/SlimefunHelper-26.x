package me.matl114.hacks.modules.survival;

import com.google.common.collect.ImmutableMap;
import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JavaOps;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import me.matl114.managers.command.MainCommand;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.RenderTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.FileManager;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.StringRef;
import me.matl114.managers.file.FileStorage;
import me.matl114.utils.*;
import me.matl114.utils.commands.commandGroup.CommandContext;
import me.matl114.utils.commands.commandGroup.SubCommand;
import me.matl114.utils.commands.commandGroup.TreeSubCommand;
import me.matl114.utils.commands.params.ArgumentInputStream;
import me.matl114.utils.commands.params.SimpleCommandArgs;
import me.matl114.utils.config.AttrKeyValue;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.*;
import net.minecraft.util.Mth;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.Vec3;

public class SeedOre extends BaseModule {
    public static SeedOre INSTANCE;
    public static final String[] SEED_MAP = new String[] {"seed", "seed-cache"};
    public final Object2LongMap<String> seedMap = new Object2LongOpenHashMap<>();
    // how to do cache: chunkUnload
    private final Map<Long, Map<Ore, Set<Vec3>>> chunkSeedCache = new ConcurrentHashMap<>();
    private final Map<Long, Map<BlockPos, BlockState>> fakeOres = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public void saveSeedMap() {
        seedMapSave.write((Map<String, Long>) seedMap, JavaOps.INSTANCE);
    }

    public SeedOre() {
        super("SeedOre");
        bindFlag(enable);
        INSTANCE = this;
    }

    public final ModulePath seed = makePath(Configs.SURVIVAL_CONFIG, "survival-mine-utils.aaxray-seed-ore");

    {
        portConfigs(makePath(Configs.MINE_CONFIG, "aaxray.seed-ore"), seed);
    }

    public final FlagRef enable = flagBuilder(seed.addEnable()).build();

    public final IntRef chunkRadius = builder(seed.add("chunk-radius"), IntRef.TYPE)
            .defaultValue(6)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final FlagRef enableRender = flagBuilder(seed.add("render-ore")).build();

    boolean fakeOre = false;
    public final FlagRef enableFakeOres = flagBuilder(seed.add("enable-fake-ore"))
            .defaultValue(false)
            .updateListener(this::onFakeOreToggle)
            .build();

    public final StringRef oreWhiteList = builder(seed.add("ore-white-list"), String.class)
            .defaultValue("^(diamond)$")
            .validator(Configs.REGEX_VALIDATOR)
            .updateListener(Ore::reloadOreSettings)
            .build();

    public final FileStorage seedMapSave = FileManager.getInstance().getInternalStorage("seed-storage.nbt");

    {
        CompoundTag nbt = seedMapSave.asReadOnly(NbtOps.INSTANCE);
        seedMap.clear();
        for (var entry : nbt.entrySet()) {
            String key = entry.getKey();
            Tag value = entry.getValue();
            if (value instanceof LongTag ll) {
                seedMap.put(key, ll.value());
            }
        }
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getWorldSwitchPoint(), this::onDimensionChange);
        // this needs run on main thread to ensure the chunk is accessible
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundLevelChunkWithLightPacket.class),
                this::onChunkUpdate);
        registerListener(Listener.getPacketPoint().getChannel(ClientboundBlockUpdatePacket.class), this::onBlockUpdate);
        registerListener(RenderListener.getRender3DEvent(), this::onRenderOreSimulation);
        registerCommandBootstrap(this::registerCommandBootstrap);
    }

    @Override
    public void onEnableModule() {
        super.onEnableModule();
        if (mc.level != null) {
            onReloadSeedOre();
        }
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        oreConfig = null;
        if (mc.player != null && mc.level != null) {
            Debug.chat(Component.literal("[种子矿透] 禁用该功能").withStyle(ChatFormatting.RED));
        }
        onRemoveFakeOreVisibleChunks();
    }

    public void onReloadSeedOre() {
        try {
            onClearCachedResults();
            if (mc.level != null) {
                // remove Fake ores existing
                onRemoveFakeOreVisibleChunks();
            }
            oreConfig = Ore.getRegistry();
            if (mc.player != null && mc.level != null) {
                Debug.chat(Component.literal("[种子矿透] 启用该功能, 范围 %d".formatted(chunkRadius.get()))
                        .withStyle(ChatFormatting.GREEN));
                onLoadCurrentVisibleChunks();
            }
            // load fake ores are in onLoadCurrentVisibleChunks

        } catch (Throwable e) {
            Debug.info(e);
            if (mc.player != null) Debug.chat(Component.literal("[种子矿透] 启用时出现报错, 已关闭..."));
            enable.set(false);
        }
    }

    public void onFakeOreToggle(boolean va) {
        if (va != fakeOre) {
            fakeOre = va;
            if (enable.get()) {
                if (fakeOre) {
                    onReloadFakeOre();
                } else {
                    onRemoveFakeOreVisibleChunks();
                }
            }
        }
    }

    public void onReloadFakeOre() {
        if (mc.level != null) {
            onRemoveFakeOreVisibleChunks();
            onReloadFakeOreVisibleChunks();
        }
    }

    // -26225.23 67.00 -23482.37
    public void onDimensionChange(Event<Level> v) {
        onClearCachedResults();
        if (isActive()) {
            // reload config
            onReloadSeedOre();
        }
    }

    public void putSeed(String key, long seed) {
        if (seedMap.containsKey(key)) {
            long old = seedMap.getLong(key);
            if (old != seed) {
                seedMap.put(key, seed);
                saveSeedMap();
                onSeedChange(key);
            }
        } else {
            seedMap.put(key, seed);
            saveSeedMap();
            onSeedChange(key);
        }
    }

    public void removeSeed(String key) {
        if (seedMap.containsKey(key)) {
            seedMap.removeLong(key);
            onSeedChange(key);
        }
    }

    public void onSeedChange(String key) {
        if (!checkCurrentSeedExistence()) return;
        if (Objects.equals(CommonUtils.getWorldName(), key) && enable.get()) {
            Debug.chat("[种子矿透] 重载Seed Ore Simulation功能");
            onReloadSeedOre();
        }
    }

    public boolean hasCurrentSeed() {
        return seedMap.containsKey(CommonUtils.getWorldName());
    }

    public long getCurrentSeed() {
        return seedMap.getLong(CommonUtils.getWorldName());
    }

    @ApiMethod
    public void setWorldSeed(long seed) {
        putSeed(CommonUtils.getWorldName(), seed);
    }

    @ApiMethod
    public void removeWorldSeed() {
        removeSeed(CommonUtils.getWorldName());
    }

    public boolean checkCurrentSeedExistence() {
        if (hasCurrentSeed()) {
            return true;
        } else {
            Debug.chat(Component.literal("[世界种子] 暂时没有设置 %s 世界的种子".formatted(CommonUtils.getWorldName()))
                    .withStyle(ChatFormatting.RED));
            enable.set(false);
            return false;
        }
    }

    public static boolean isSeedValid(long seed) {
        long hashed = mc.level.getBiomeManager().biomeZoomSeed;
        return BiomeManager.obfuscateSeed(seed) == hashed;
    }

    public void validateCurrentSeed() {
        if (!checkCurrentSeedExistence()) return;
        long value = seedMap.getLong(CommonUtils.getWorldName());
        Debug.chat(Component.literal("[世界种子] 核验当前世界种子中:").withStyle(ChatFormatting.GREEN));
        Debug.chat(Component.literal("[世界种子] 输入的种子: ")
                .withStyle(ChatFormatting.GREEN)
                .append(ChatUtils.getDisplayedLong(value)));
        long hashed = mc.level.getBiomeManager().biomeZoomSeed;
        Debug.chat(Component.literal("[世界种子] 服务器加密种子: ").append(ChatUtils.getDisplayedLong(hashed)));
        if (isSeedValid(value)) {
            Debug.chat(Component.literal("[世界种子] 验证通过").withStyle(ChatFormatting.GREEN));
        } else {
            Debug.chat(Component.literal("[世界种子] 验证失败").withStyle(ChatFormatting.RED));
        }
    }

    // 重载可视区块的种子计算 包含了假矿计算
    public void onLoadCurrentVisibleChunks() {
        if (mc.level == null) return;
        for (ChunkAccess chunk : CommonUtils.chunks(false)) {
            updateChunk(chunk);
        }
    }
    // 重载假矿
    public void onReloadFakeOreVisibleChunks() {
        if (mc.level == null) return;
        for (ChunkAccess chunk : CommonUtils.chunks(false)) {
            long key = chunk.getPos().pack();
            var map = chunkSeedCache.get(key);
            if (map != null && !map.isEmpty()) {
                updateOreClientSide(key, map);
            }
        }
    }
    // 移除假矿
    public void onRemoveFakeOreVisibleChunks() {
        if (mc.level == null) return;
        for (ChunkAccess chunk : CommonUtils.chunks(false)) {
            long key = chunk.getPos().pack();
            var map = fakeOres.remove(key);
            if (map != null && !map.isEmpty()) {
                removeChunkFakeOres(key, map);
            }
        }
    }
    // 移除内部实现
    private void removeChunkFakeOres(long key, Map<BlockPos, BlockState> originDatas) {
        for (var re : originDatas.entrySet()) {
            BlockPos pos0 = re.getKey();
            BlockState state0 = re.getValue();
            Tasks.scheduleDelayed(
                    () -> {
                        if (mc.level != null) {
                            mc.level.setBlockAndUpdate(pos0, state0);
                        }
                    },
                    1);
        }
    }

    private void onClearCachedResults() {
        chunkSeedCache.clear();
        fakeOres.clear();
    }

    // events that updates the chunk
    public void onChunkUpdate(Event<ClientboundLevelChunkWithLightPacket> packet) {
        // update data as scheduled after the handle
        Packet<?> packet1 = packet.context;
        if (enable.get()) {
            var dataS2CPacket = packet.context;
            int x = dataS2CPacket.getX();
            int z = dataS2CPacket.getZ();
            Tasks.scheduleDelayed(
                    () -> {
                        updateChunk(mc.level.getChunk(x, z));
                    },
                    2);
        }
    }

    public void onBlockUpdate(Event<ClientboundBlockUpdatePacket> event) {
        // remove cache whenever
        // remove async
        if (!chunkSeedCache.isEmpty() || !fakeOres.isEmpty()) {
            var packet = event.context;
            long chunkKey = ChunkPos.pack((packet).getPos());
            var map = chunkSeedCache.get(chunkKey);
            Vec3 pos = Vec3.atLowerCornerOf(packet.getPos());
            if (map != null && !map.isEmpty()) {
                for (var ore : map.values()) {
                    ore.remove(pos);
                }
            }
            var map2 = fakeOres.get(chunkKey);
            if (map2 != null && !map2.isEmpty()) {
                map2.remove(packet.getPos());
            }
        }
    }
    // render issues

    public void onRenderOreSimulation(Event<Render3D> event) {
        var stack = event.context.stack();
        if (mc.player == null || oreConfig == null) return;
        if (!enable.get()) return;
        if (!enableRender.get()) return;
        if (!checkCurrentSeedExistence()) return;
        RenderUtils.startDrawVirtual(stack);
        try {
            int chunkX = mc.player.chunkPosition().x;
            int chunkZ = mc.player.chunkPosition().z;

            int rangeVal = chunkRadius.get();
            for (int range = 0; range <= rangeVal; range++) {
                for (int x = -range + chunkX; x <= range + chunkX; x++) {
                    renderChunk(x, chunkZ + range - rangeVal, stack);
                }
                for (int x = (-range) + 1 + chunkX; x < range + chunkX; x++) {
                    renderChunk(x, chunkZ - range + rangeVal + 1, stack);
                }
            }
        } finally {
            RenderUtils.stopDrawVirtual(stack);
        }
    }

    private void renderChunk(int x, int z, PoseStack event) {
        long chunkKey = ChunkPos.pack(x, z);

        if (chunkSeedCache.containsKey(chunkKey)) {
            Map<Ore, Set<Vec3>> chunk = chunkSeedCache.get(chunkKey);

            for (Map.Entry<Ore, Set<Vec3>> oreRenders : chunk.entrySet()) {
                if (oreRenders.getKey().active.get() == Boolean.TRUE) {
                    Color color = oreRenders.getKey().color;
                    for (Vec3 pos : oreRenders.getValue()) {
                        Vec3 centerPos = Vec3.atCenterOf(BlockPos.containing(pos));

                        // event.renderer.boxLines(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1,
                        // oreRenders.getKey().color, 0);
                        RenderUtils.drawOutlinedBox(
                                event, centerPos.add(RenderTasks.FROM), centerPos.add(RenderTasks.TO), color);
                    }
                }
            }
        }
    }

    public Map<String, Set<Vec3>> getSeedOres(int x, int z) {
        Map<String, Set<Vec3>> map = new HashMap<>();
        for (var entry :
                chunkSeedCache.getOrDefault(ChunkPos.pack(x, z), Map.of()).entrySet()) {
            map.put(entry.getKey().active.getKeyName(), entry.getValue());
        }
        return map;
    }

    private void updateChunk(ChunkAccess chunk) {
        if (!enable.get()) return;
        if (!checkCurrentSeedExistence()) {
            return;
        }
        var chunkPos = chunk.getPos();
        long chunkKey = chunkPos.pack();
        ClientLevel world = mc.level;
        // clear cache when switching world
        Map<Ore, Set<Vec3>> h;

        if (chunkSeedCache.containsKey(chunkKey) || world == null || oreConfig == null) {
            h = chunkSeedCache.get(chunkKey);
        } else {
            Set<ResourceKey<Biome>> biomes = new HashSet<>();
            ChunkPos.rangeClosed(chunkPos, 1).forEach(chunkPosx -> {
                ChunkAccess chunkxx = world.getChunk(chunkPosx.x, chunkPosx.z, ChunkStatus.BIOMES, false);
                if (chunkxx == null) return;

                for (LevelChunkSection chunkSection : chunkxx.getSections()) {
                    chunkSection
                            .getBiomes()
                            .getAll(entry -> biomes.add(entry.unwrapKey().get()));
                }
            });
            Set<Ore> oreSet =
                    biomes.stream().flatMap(b -> getDefaultOres(b).stream()).collect(Collectors.toSet());

            int chunkX = chunkPos.x << 4;
            int chunkZ = chunkPos.z << 4;
            WorldgenRandom random = new WorldgenRandom(WorldgenRandom.Algorithm.XOROSHIRO.newInstance(0));

            long populationSeed = random.setDecorationSeed(getCurrentSeed(), chunkX, chunkZ);
            h = new ConcurrentHashMap<>();
            for (Ore ore : oreSet) {

                Set<Vec3> ores = ConcurrentHashMap.newKeySet();

                random.setFeatureSeed(populationSeed, ore.index, ore.step);

                int repeat = ore.count.sample(random);

                for (int i = 0; i < repeat; i++) {

                    if (ore.rarity != 1F && random.nextFloat() >= 1 / ore.rarity) {
                        continue;
                    }

                    int x = random.nextInt(16) + chunkX;
                    int z = random.nextInt(16) + chunkZ;
                    int y = ore.heightProvider.sample(random, ore.heightContext);
                    BlockPos origin = new BlockPos(x, y, z);

                    ResourceKey<Biome> biome =
                            chunk.getNoiseBiome(x, y, z).unwrapKey().get();

                    if (!getDefaultOres(biome).contains(ore)) {
                        continue;
                    }

                    if (ore.scattered) {
                        ores.addAll(generateHidden(world, random, origin, ore.size));
                    } else {
                        ores.addAll(generateNormal(world, random, origin, ore.size, ore.discardOnAirChance));
                    }
                }
                if (!ores.isEmpty()) {
                    h.put(ore, ores);
                }
            }
        }
        if (h != null && !h.isEmpty()) {
            // fake ore do not load automatically
            chunkSeedCache.put(chunkKey, h);
            if (enableFakeOres.get()) {
                updateOreClientSide(chunkKey, h);
            }
        }
    }
    // for fake ores
    private void updateOreClientSide(long chunkey, Map<Ore, Set<Vec3>> ores) {
        if (mc.level == null) return;
        if (!enableFakeOres.get()) return;

        // return blockstates already cached
        var map0 = fakeOres.remove(chunkey);
        if (map0 != null && !map0.isEmpty()) {
            removeChunkFakeOres(chunkey, map0);
        }

        Map<BlockPos, BlockState> newFakeOres = new ConcurrentHashMap<>();
        int minY = mc.level.getMinY();
        for (var ore0 : ores.entrySet()) {
            Ore oreType = ore0.getKey();
            var sample = oreType.sampleBlock;
            var sampleDeepslate = oreType.sampleDeepslateBlock;
            loop:
            for (var pos : ore0.getValue()) {
                if (pos.y < minY + 4) {
                    // mostly bedrock , escape
                    continue;
                }
                var block = pos.y > 0 ? sample : sampleDeepslate;
                var blockState = block.defaultBlockState();
                var blockPos = BlockPos.containing(pos);
                BlockState state = mc.level.getBlockState(blockPos);
                // todo: air can be faked!!! that's allshit,
                // fuck you 3c3u
                if (!state.isAir() && state.getBlock() != sample && state.getBlock() != sampleDeepslate) {
                    // not naked and not the same
                    for (Direction direction : Direction.values()) {
                        BlockPos testPos = blockPos.relative(direction);
                        if (mc.level.getBlockState(testPos).isAir()) {
                            continue loop;
                        }
                    }
                    newFakeOres.put(blockPos, state);
                    // run sync
                    Tasks.scheduleDelayed(
                            () -> {
                                if (mc.level != null) mc.level.setBlockAndUpdate(blockPos, blockState);
                            },
                            2);
                }
            }
        }
        fakeOres.put(chunkey, newFakeOres);
    }

    private Map<ResourceKey<Biome>, List<Ore>> oreConfig;

    private List<Ore> getDefaultOres(ResourceKey<Biome> biomeRegistryKey) {
        if (oreConfig.containsKey(biomeRegistryKey)) {
            return oreConfig.get(biomeRegistryKey);
        } else {
            return oreConfig.values().stream().findAny().get();
        }
    }

    public void registerCommandBootstrap(MainCommand command) {
        TreeSubCommand main = command.subMainBuilder().name("seedore").build();
        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("toggle")
                    .helper("message.command.seedore.toggle.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("toggle")
                            .bool()
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onSeedOre)))
                    .complete();
        }
        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("render")
                    .helper("message.command.seedore.render.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("toggle")
                            .bool()
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onOreRender)))
                    .complete();
        }
        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("fakeore")
                    .helper("message.command.seedore.fakeore.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("operation")
                            .select(List.of("on", "off", "reload"))
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onFakeOre)))
                    .complete();
        }
        TreeSubCommand seed = command.subMainBuilder().name("seed").build();
        {
            seed.subBuilder(SubCommand.taskBuilder())
                    .name("set")
                    .helper("message.command.seed.set.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("seed")
                            .intValue()
                            .tabSupplier(() -> seedMap.keySet().stream())
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onSeedSet)))
                    .complete()
                    .subBuilder(SubCommand.taskBuilder())
                    .name("remove")
                    .helper("message.command.seed.remove.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("world")
                            .tabSupplier(() -> seedMap.keySet().stream())
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onSeedRemove)))
                    .complete()
                    .subBuilder(SubCommand.taskBuilder())
                    .name("list")
                    .helper("message.command.seed.list.help")
                    .post(e -> e.executor(CommandContext.run(this::onSeedList)))
                    .complete()
                    .subBuilder(SubCommand.taskBuilder())
                    .name("validate")
                    .helper("message.command.seed.validate.help")
                    .post(e -> e.executor(CommandContext.run(this::validateCurrentSeed)))
                    .complete();
        }
    }

    @Override
    public void addCustomWidgets(Consumer<DrawableWidget> acceptor, int dx, int dy, int dblank) {
        super.addCustomWidgets(acceptor, dx, dy, dblank);
        acceptor.accept(createTitle("widget.seed-ore.command", 0, dblank, dx, dy));
    }

    public void onSeedOre(ArgumentInputStream re) {
        boolean var = re.nextBoolean();

        if (var) {
            if (!isActive()) {
                enable.set(true);
            }
        } else {
            if (isActive()) {
                enable.set(false);
            }
        }
    }

    public void onSeedSet(ArgumentInputStream re) {
        String na = re.nextNonnull();
        long val;
        if (seedMap.containsKey(na)) {
            val = seedMap.getLong(na);
        } else {
            val = Long.parseLong(na);
        }
        setWorldSeed(val);
        me.matl114.utils.Debug.chat("[世界种子] 设置", CommonUtils.getWorldName(), "的种子为", val);
    }

    public void onSeedRemove(ArgumentInputStream re) {
        String key = re.nextNonnull();
        removeSeed(key);
    }

    public void onSeedList() {
        Debug.chat("[世界种子] 列表");
        for (var entry : seedMap.object2LongEntrySet()) {
            Debug.chat(entry.getKey(), ":", ChatUtils.getDisplayedLong(entry.getLongValue()));
        }
    }

    public void onOreRender(ArgumentInputStream re) {
        boolean val = re.nextBoolean();
        enableRender.set(val);
        Debug.chat("[种子矿透] 切换渲染:", val);
    }

    public void onFakeOre(ArgumentInputStream re) {
        String val = re.nextNonnull();
        switch (val) {
            case "on" -> {
                Debug.chat("[种子矿透] 切换假矿: true");
                enableFakeOres.set(true);
            }
            case "off" -> {
                Debug.chat("[种子矿透] 切换假矿: false");
                enableFakeOres.set(false);
            }
            case "reload" -> {
                Debug.chat("[种子矿透] 重载可视距离内的假矿");
                onReloadFakeOre();
            }
        }
    }

    static {
        Ore.init();
    }

    // ====================================
    // Mojang code
    // ====================================

    private static ArrayList<Vec3> generateNormal(
            ClientLevel world, WorldgenRandom random, BlockPos blockPos, int veinSize, float discardOnAir) {
        float f = random.nextFloat() * 3.1415927F;
        float g = (float) veinSize / 8.0F;
        int i = Mth.ceil(((float) veinSize / 16.0F * 2.0F + 1.0F) / 2.0F);
        double d = (double) blockPos.getX() + Math.sin(f) * (double) g;
        double e = (double) blockPos.getX() - Math.sin(f) * (double) g;
        double h = (double) blockPos.getZ() + Math.cos(f) * (double) g;
        double j = (double) blockPos.getZ() - Math.cos(f) * (double) g;
        double l = (blockPos.getY() + random.nextInt(3) - 2);
        double m = (blockPos.getY() + random.nextInt(3) - 2);
        int n = blockPos.getX() - Mth.ceil(g) - i;
        int o = blockPos.getY() - 2 - i;
        int p = blockPos.getZ() - Mth.ceil(g) - i;
        int q = 2 * (Mth.ceil(g) + i);
        int r = 2 * (2 + i);

        for (int s = n; s <= n + q; ++s) {
            for (int t = p; t <= p + q; ++t) {
                if (o <= world.getHeight(Heightmap.Types.MOTION_BLOCKING, s, t)) {
                    return generateVeinPart(world, random, veinSize, d, e, h, j, l, m, n, o, p, q, r, discardOnAir);
                }
            }
        }

        return new ArrayList<>();
    }

    private static ArrayList<Vec3> generateVeinPart(
            ClientLevel world,
            WorldgenRandom random,
            int veinSize,
            double startX,
            double endX,
            double startZ,
            double endZ,
            double startY,
            double endY,
            int x,
            int y,
            int z,
            int size,
            int i,
            float discardOnAir) {

        BitSet bitSet = new BitSet(size * i * size);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        double[] ds = new double[veinSize * 4];

        ArrayList<Vec3> poses = new ArrayList<>();

        int n;
        double p;
        double q;
        double r;
        double s;
        for (n = 0; n < veinSize; ++n) {
            float f = (float) n / (float) veinSize;
            p = Mth.lerp(f, startX, endX);
            q = Mth.lerp(f, startY, endY);
            r = Mth.lerp(f, startZ, endZ);
            s = random.nextDouble() * (double) veinSize / 16.0D;
            double m = ((double) (Mth.sin(3.1415927F * f) + 1.0F) * s + 1.0D) / 2.0D;
            ds[n * 4] = p;
            ds[n * 4 + 1] = q;
            ds[n * 4 + 2] = r;
            ds[n * 4 + 3] = m;
        }

        for (n = 0; n < veinSize - 1; ++n) {
            if (!(ds[n * 4 + 3] <= 0.0D)) {
                for (int o = n + 1; o < veinSize; ++o) {
                    if (!(ds[o * 4 + 3] <= 0.0D)) {
                        p = ds[n * 4] - ds[o * 4];
                        q = ds[n * 4 + 1] - ds[o * 4 + 1];
                        r = ds[n * 4 + 2] - ds[o * 4 + 2];
                        s = ds[n * 4 + 3] - ds[o * 4 + 3];
                        if (s * s > p * p + q * q + r * r) {
                            if (s > 0.0D) {
                                ds[o * 4 + 3] = -1.0D;
                            } else {
                                ds[n * 4 + 3] = -1.0D;
                            }
                        }
                    }
                }
            }
        }

        for (n = 0; n < veinSize; ++n) {
            double u = ds[n * 4 + 3];
            if (!(u < 0.0D)) {
                double v = ds[n * 4];
                double w = ds[n * 4 + 1];
                double aa = ds[n * 4 + 2];
                int ab = Math.max(Mth.floor(v - u), x);
                int ac = Math.max(Mth.floor(w - u), y);
                int ad = Math.max(Mth.floor(aa - u), z);
                int ae = Math.max(Mth.floor(v + u), ab);
                int af = Math.max(Mth.floor(w + u), ac);
                int ag = Math.max(Mth.floor(aa + u), ad);

                for (int ah = ab; ah <= ae; ++ah) {
                    double ai = ((double) ah + 0.5D - v) / u;
                    if (ai * ai < 1.0D) {
                        for (int aj = ac; aj <= af; ++aj) {
                            double ak = ((double) aj + 0.5D - w) / u;
                            if (ai * ai + ak * ak < 1.0D) {
                                for (int al = ad; al <= ag; ++al) {
                                    double am = ((double) al + 0.5D - aa) / u;
                                    if (ai * ai + ak * ak + am * am < 1.0D) {
                                        int an = ah - x + (aj - y) * size + (al - z) * size * i;
                                        if (!bitSet.get(an)) {
                                            bitSet.set(an);
                                            mutable.set(ah, aj, al);
                                            if (aj >= -64
                                                    && aj < 320
                                                    && (world.getBlockState(mutable)
                                                            .canOcclude())) {
                                                if (shouldPlace(world, mutable, discardOnAir, random)) {
                                                    poses.add(new Vec3(ah, aj, al));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return poses;
    }

    private static boolean shouldPlace(ClientLevel world, BlockPos orePos, float discardOnAir, WorldgenRandom random) {
        if (discardOnAir == 0F || (discardOnAir != 1F && random.nextFloat() >= discardOnAir)) {
            return true;
        }

        for (Direction direction : Direction.values()) {
            if (!world.getBlockState(orePos.offset(direction.getUnitVec3i())).canOcclude() && discardOnAir != 1F) {
                return false;
            }
        }
        return true;
    }

    private static ArrayList<Vec3> generateHidden(
            ClientLevel world, WorldgenRandom random, BlockPos blockPos, int size) {

        ArrayList<Vec3> poses = new ArrayList<>();

        int i = random.nextInt(size + 1);

        for (int j = 0; j < i; ++j) {
            size = Math.min(j, 7);
            int x = randomCoord(random, size) + blockPos.getX();
            int y = randomCoord(random, size) + blockPos.getY();
            int z = randomCoord(random, size) + blockPos.getZ();
            if (world.getBlockState(new BlockPos(x, y, z)).canOcclude()) {
                if (shouldPlace(world, new BlockPos(x, y, z), 1F, random)) {
                    poses.add(new Vec3(x, y, z));
                }
            }
        }

        return poses;
    }

    private static int randomCoord(WorldgenRandom random, int size) {
        return Math.round((random.nextFloat() - random.nextFloat()) * (float) size);
    }

    public static class Ore {
        private static final AttrKeyValue<Boolean> coal = AttrKeyValue.bool("Coal");
        private static final AttrKeyValue<Boolean> iron = AttrKeyValue.bool("Iron");
        private static final AttrKeyValue<Boolean> gold = AttrKeyValue.bool("Gold");
        private static final AttrKeyValue<Boolean> redstone = AttrKeyValue.bool("Redstone");
        private static final AttrKeyValue<Boolean> diamond = AttrKeyValue.bool("Diamond");
        private static final AttrKeyValue<Boolean> lapis = AttrKeyValue.bool("Lapis");
        private static final AttrKeyValue<Boolean> copper = AttrKeyValue.bool("Copper");
        private static final AttrKeyValue<Boolean> emerald = AttrKeyValue.bool("Emerald");
        private static final AttrKeyValue<Boolean> quartz = AttrKeyValue.bool("Quartz");
        private static final AttrKeyValue<Boolean> debris = AttrKeyValue.bool("Ancient Debris");
        private static final Map<String, Pair<Block, Block>> oreMapping =
                ImmutableMap.<String, Pair<Block, Block>>builder()
                        .put("Coal", Pair.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE))
                        .put("Iron", Pair.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE))
                        .put("Gold", Pair.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE))
                        .put("Redstone", Pair.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE))
                        .put("Diamond", Pair.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE))
                        .put("Lapis", Pair.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE))
                        .put("Copper", Pair.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE))
                        .put("Emerald", Pair.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE))
                        .put("Quartz", Pair.of(Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_QUARTZ_ORE))
                        .put("Ancient Debris", Pair.of(Blocks.ANCIENT_DEBRIS, Blocks.ANCIENT_DEBRIS))
                        .build();
        public static final List<AttrKeyValue<Boolean>> oreSettings = new ArrayList<>(
                Arrays.asList(coal, iron, gold, redstone, diamond, lapis, copper, emerald, quartz, debris));

        public static void reloadOreSettings(String value) {
            try {
                var regex = Pattern.compile(value).asMatchPredicate();
                for (var ore : oreSettings) {
                    if (regex.test(ore.getKeyName().toLowerCase(Locale.ROOT))) {
                        ore.setInput("true");
                    } else {
                        ore.setInput("false");
                    }
                }
            } catch (Throwable e) {
                // Debug.chat("");
            }
        }

        public static void init() {}

        public static Map<ResourceKey<Biome>, List<Ore>> getRegistry() {

            HolderLookup.Provider registry = VanillaRegistries.createLookup();
            HolderLookup.RegistryLookup<PlacedFeature> features = registry.lookupOrThrow(Registries.PLACED_FEATURE);
            var reg = registry.lookupOrThrow(Registries.WORLD_PRESET)
                    .getOrThrow(WorldPresets.NORMAL)
                    .value()
                    .createWorldDimensions()
                    .dimensions();
            ResourceKey<LevelStem> options = CommonUtils.getCurrentDimensionOption();
            var dim = reg.get(options);

            var biomes = dim.generator().getBiomeSource().possibleBiomes();
            var biomes1 = biomes.stream().toList();

            List<FeatureSorter.StepFeatureData> indexer = FeatureSorter.buildFeaturesPerStep(
                    biomes1,
                    biomeEntry -> biomeEntry.value().getGenerationSettings().features(),
                    true);

            Map<PlacedFeature, Ore> featureToOre = new HashMap<>();
            registerOre(featureToOre, indexer, features, OrePlacements.ORE_COAL_LOWER, 6, coal, new Color(47, 44, 54));
            registerOre(featureToOre, indexer, features, OrePlacements.ORE_COAL_UPPER, 6, coal, new Color(47, 44, 54));
            registerOre(
                    featureToOre, indexer, features, OrePlacements.ORE_IRON_MIDDLE, 6, iron, new Color(236, 173, 119));
            registerOre(
                    featureToOre, indexer, features, OrePlacements.ORE_IRON_SMALL, 6, iron, new Color(236, 173, 119));
            registerOre(
                    featureToOre, indexer, features, OrePlacements.ORE_IRON_UPPER, 6, iron, new Color(236, 173, 119));
            registerOre(featureToOre, indexer, features, OrePlacements.ORE_GOLD, 6, gold, new Color(247, 229, 30));
            registerOre(
                    featureToOre, indexer, features, OrePlacements.ORE_GOLD_LOWER, 6, gold, new Color(247, 229, 30));
            registerOre(
                    featureToOre, indexer, features, OrePlacements.ORE_GOLD_EXTRA, 6, gold, new Color(247, 229, 30));
            registerOre(
                    featureToOre, indexer, features, OrePlacements.ORE_GOLD_NETHER, 7, gold, new Color(247, 229, 30));
            registerOre(
                    featureToOre, indexer, features, OrePlacements.ORE_GOLD_DELTAS, 7, gold, new Color(247, 229, 30));
            registerOre(
                    featureToOre, indexer, features, OrePlacements.ORE_REDSTONE, 6, redstone, new Color(245, 7, 23));
            registerOre(
                    featureToOre,
                    indexer,
                    features,
                    OrePlacements.ORE_REDSTONE_LOWER,
                    6,
                    redstone,
                    new Color(245, 7, 23));
            registerOre(
                    featureToOre, indexer, features, OrePlacements.ORE_DIAMOND, 6, diamond, new Color(33, 244, 255));
            registerOre(
                    featureToOre,
                    indexer,
                    features,
                    OrePlacements.ORE_DIAMOND_BURIED,
                    6,
                    diamond,
                    new Color(33, 244, 255));
            registerOre(
                    featureToOre,
                    indexer,
                    features,
                    OrePlacements.ORE_DIAMOND_LARGE,
                    6,
                    diamond,
                    new Color(33, 244, 255));
            registerOre(
                    featureToOre,
                    indexer,
                    features,
                    OrePlacements.ORE_DIAMOND_MEDIUM,
                    6,
                    diamond,
                    new Color(33, 244, 255));
            registerOre(featureToOre, indexer, features, OrePlacements.ORE_LAPIS, 6, lapis, new Color(8, 26, 189));
            registerOre(
                    featureToOre, indexer, features, OrePlacements.ORE_LAPIS_BURIED, 6, lapis, new Color(8, 26, 189));
            registerOre(featureToOre, indexer, features, OrePlacements.ORE_COPPER, 6, copper, new Color(239, 151, 0));
            registerOre(
                    featureToOre, indexer, features, OrePlacements.ORE_COPPER_LARGE, 6, copper, new Color(239, 151, 0));
            registerOre(featureToOre, indexer, features, OrePlacements.ORE_EMERALD, 6, emerald, new Color(27, 209, 45));
            registerOre(
                    featureToOre,
                    indexer,
                    features,
                    OrePlacements.ORE_QUARTZ_NETHER,
                    7,
                    quartz,
                    new Color(205, 205, 205));
            registerOre(
                    featureToOre,
                    indexer,
                    features,
                    OrePlacements.ORE_QUARTZ_DELTAS,
                    7,
                    quartz,
                    new Color(205, 205, 205));
            registerOre(
                    featureToOre,
                    indexer,
                    features,
                    OrePlacements.ORE_ANCIENT_DEBRIS_SMALL,
                    7,
                    debris,
                    new Color(209, 27, 245));
            registerOre(
                    featureToOre,
                    indexer,
                    features,
                    OrePlacements.ORE_ANCIENT_DEBRIS_LARGE,
                    7,
                    debris,
                    new Color(209, 27, 245));

            Map<ResourceKey<Biome>, List<Ore>> biomeOreMap = new HashMap<>();

            biomes1.forEach(biome -> {
                biomeOreMap.put(biome.unwrapKey().get(), new ArrayList<>());
                biome.value().getGenerationSettings().features().stream()
                        .flatMap(HolderSet::stream)
                        .map(Holder::value)
                        .filter(featureToOre::containsKey)
                        .forEach(feature -> {
                            biomeOreMap.get(biome.unwrapKey().get()).add(featureToOre.get(feature));
                        });
            });
            return biomeOreMap;
        }

        private static void registerOre(
                Map<PlacedFeature, Ore> map,
                List<FeatureSorter.StepFeatureData> indexer,
                HolderLookup.RegistryLookup<PlacedFeature> oreRegistry,
                ResourceKey<PlacedFeature> oreKey,
                int genStep,
                AttrKeyValue<Boolean> active,
                Color color) {
            var orePlacement = oreRegistry.getOrThrow(oreKey).value();

            int index = indexer.get(genStep).indexMapping().applyAsInt(orePlacement);
            var pair = oreMapping.getOrDefault(active.getKeyName(), Pair.of(Blocks.IRON_ORE, Blocks.IRON_ORE));
            Ore ore = new Ore(orePlacement, pair.getFirst(), pair.getSecond(), genStep, index, active, color);

            map.put(orePlacement, ore);
        }

        public int step;
        public int index;
        public Block sampleBlock;
        public Block sampleDeepslateBlock;
        public AttrKeyValue<Boolean> active;
        public IntProvider count = ConstantInt.of(1);
        public HeightProvider heightProvider;
        public WorldGenerationContext heightContext;
        public float rarity = 1;
        public float discardOnAirChance;
        public int size;
        public Color color;
        public boolean scattered;

        private Ore(
                PlacedFeature feature,
                Block block,
                Block deepslate,
                int step,
                int index,
                AttrKeyValue<Boolean> active,
                Color color) {
            this.step = step;
            this.index = index;
            this.active = active;
            this.color = color;
            this.sampleBlock = block;
            this.sampleDeepslateBlock = deepslate;
            int bottom = Minecraft.getInstance().level.getMinY();
            int height = Minecraft.getInstance().level.dimensionType().logicalHeight();
            this.heightContext = new WorldGenerationContext(null, LevelHeightAccessor.create(bottom, height));

            for (PlacementModifier modifier : feature.placement()) {
                if (modifier instanceof CountPlacement count) {
                    this.count = count.count;

                } else if (modifier instanceof HeightRangePlacement height0) {
                    this.heightProvider = height0.height;

                } else if (modifier instanceof RarityFilter rare) {
                    this.rarity = rare.chance;
                }
            }

            FeatureConfiguration featureConfig = feature.feature().value().config();

            if (featureConfig instanceof OreConfiguration oreFeatureConfig) {
                this.discardOnAirChance = oreFeatureConfig.discardChanceOnAirExposure;
                this.size = oreFeatureConfig.size;
            } else {
                throw new IllegalStateException("config for " + feature + "is not OreFeatureConfig.class");
            }

            if (feature.feature().value().feature() instanceof ScatteredOreFeature) {
                this.scattered = true;
            }
        }
    }
}
