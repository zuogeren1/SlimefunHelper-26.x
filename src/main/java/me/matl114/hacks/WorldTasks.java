package me.matl114.hacks;

import com.mojang.serialization.JavaOps;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.channels.ListenerPoint;
import me.matl114.events.impl.BlockUpdate;
import me.matl114.hacks.modules.survival.SeedOre;
import me.matl114.managers.FileManager;
import me.matl114.managers.command.MainCommand;
import me.matl114.managers.file.FileStorage;
import me.matl114.utils.ApiMethod;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.CommonUtils;
import me.matl114.utils.Debug;
import me.matl114.utils.ThreadUtils;
import me.matl114.utils.WorldUtils;
import me.matl114.utils.commands.commandGroup.CommandContext;
import me.matl114.utils.commands.commandGroup.SubCommand;
import me.matl114.utils.commands.commandGroup.TreeSubCommand;
import me.matl114.utils.commands.params.ArgumentInputStream;
import me.matl114.utils.commands.params.SimpleCommandArgs;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public class WorldTasks {
    public static final Object2LongMap<String> seedMap = new Object2LongOpenHashMap<>();
    private static final FileStorage seedMapSave = FileManager.getInstance().getInternalStorage("seed-storage.nbt");

    public static void init() {
        MainCommand.registerCommandBootstrap(WorldTasks::registerSeedCommand);
    }

    private static void loadSeedMap() {
        CompoundTag nbt = seedMapSave.asReadOnly(NbtOps.INSTANCE);
        for (var entry : nbt.entrySet()) {
            if (entry.getValue() instanceof LongTag value) {
                seedMap.put(entry.getKey(), value.value());
            }
        }
    }

    private static void saveSeedMap() {
        seedMapSave.write((Map<String, Long>) seedMap, JavaOps.INSTANCE);
    }

    public static void putSeed(String key, long seed) {
        if (!seedMap.containsKey(key) || seedMap.getLong(key) != seed) {
            seedMap.put(key, seed);
            saveSeedMap();
            SeedOre.INSTANCE.onSeedChange(key);
        }
    }

    public static void removeSeed(String key) {
        if (seedMap.containsKey(key)) {
            seedMap.removeLong(key);
            saveSeedMap();
            SeedOre.INSTANCE.onSeedChange(key);
        }
    }

    @ApiMethod
    public static long getSeed() {
        return seedMap.getLong(CommonUtils.getWorldName());
    }

    @ApiMethod
    public static void setSeed(long seed) {
        putSeed(CommonUtils.getWorldName(), seed);
    }

    public static boolean hasSeed() {
        return seedMap.containsKey(CommonUtils.getWorldName());
    }

    private static void registerSeedCommand(MainCommand command) {
        TreeSubCommand seed = command.subMainBuilder().name("seed").build();
        seed.subBuilder(SubCommand.taskBuilder())
                .name("set")
                .helper("message.command.seed.set.help")
                .arg(SimpleCommandArgs.argumentBuilder()
                        .name("seed")
                        .intValue()
                        .tabSupplier(() -> seedMap.keySet().stream())
                        .build())
                .post(e -> e.executor(CommandContext.run(WorldTasks::onSeedSet)))
                .complete()
                .subBuilder(SubCommand.taskBuilder())
                .name("remove")
                .helper("message.command.seed.remove.help")
                .arg(SimpleCommandArgs.argumentBuilder()
                        .name("world")
                        .tabSupplier(() -> seedMap.keySet().stream())
                        .build())
                .post(e -> e.executor(CommandContext.run(WorldTasks::onSeedRemove)))
                .complete()
                .subBuilder(SubCommand.taskBuilder())
                .name("list")
                .helper("message.command.seed.list.help")
                .post(e -> e.executor(CommandContext.run(WorldTasks::onSeedList)))
                .complete()
                .subBuilder(SubCommand.taskBuilder())
                .name("validate")
                .helper("message.command.seed.validate.help")
                .post(e -> e.executor(CommandContext.run(WorldTasks::validateCurrentSeed)))
                .complete();
    }

    private static void onSeedSet(ArgumentInputStream args) {
        String value = args.nextNonnull();
        long seed = seedMap.containsKey(value) ? seedMap.getLong(value) : Long.parseLong(value);
        setSeed(seed);
        Debug.chat("[世界种子] 设置", CommonUtils.getWorldName(), "的种子为", seed);
    }

    private static void onSeedRemove(ArgumentInputStream args) {
        removeSeed(args.nextNonnull());
    }

    private static void onSeedList() {
        Debug.chat("[世界种子] 列表");
        for (var entry : seedMap.object2LongEntrySet()) {
            Debug.chat(entry.getKey(), ":", ChatUtils.getDisplayedLong(entry.getLongValue()));
        }
    }

    private static void validateCurrentSeed() {
        SeedOre seedOre = SeedOre.INSTANCE;
        if (!seedOre.checkCurrentSeedExistence()) return;
        long seed = getSeed();
        Debug.chat(Component.literal("[世界种子] 核验当前世界种子为").withStyle(ChatFormatting.GREEN));
        Debug.chat(Component.literal("[世界种子] 输入的种子 ").withStyle(ChatFormatting.GREEN).append(ChatUtils.getDisplayedLong(seed)));
        long hashed = mc.level.getBiomeManager().biomeZoomSeed;
        Debug.chat(Component.literal("[世界种子] 服务器加密种子 ").append(ChatUtils.getDisplayedLong(hashed)));
        if (SeedOre.isSeedValid(seed)) {
            Debug.chat(Component.literal("[世界种子] 验证通过").withStyle(ChatFormatting.GREEN));
        } else {
            Debug.chat(Component.literal("[世界种子] 验证失败").withStyle(ChatFormatting.RED));
        }
    }

    public static Map<ChunkPos, CompletableFuture<Void>> pendingUpdateTasks = new ConcurrentHashMap<>();
    private static final Minecraft mc = Minecraft.getInstance();
    // optimize, do not block main thread
    private static final ExecutorService scanExecutor = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() / 2,
            Runtime.getRuntime().availableProcessors() / 2,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2500),
            ThreadUtils.daemonThreadFactory("sfh-chunk-scan"),
            new ThreadPoolExecutor.CallerRunsPolicy());
    private static final Executor processExecutor =
            Executors.newSingleThreadExecutor(ThreadUtils.daemonThreadFactory("sfh-world-task"));
    static int tickCounter = 0;

    public static void onTick(Event<LocalPlayer> eventUpdate) {
        if (++tickCounter > 5) {
            tickCounter = 0;
            Set<ChunkPos> chunkPoses = pendingUpdateTasks.keySet();
            List<ChunkPos> removal = new ArrayList<>(32);
            for (var key : chunkPoses) {
                if (!mc.level.getChunkSource().hasChunk(key.x, key.z)) {
                    removal.add(key);
                }
            }
            for (ChunkPos chunkPos : removal) {
                cancelPendingChunkTask(chunkPos);
            }
        }
    }

    public static void cancelPendingChunkTask(ChunkPos chunkPos) {
        mc.execute(() -> pendingUpdateTasks.remove(chunkPos));
    }

    public static void cancelAllPendingChunkTasks() {
        mc.execute(() -> pendingUpdateTasks.clear());
    }

    public static void onWorldChange(Event<Level> event) {
        cancelAllPendingChunkTasks();
    }

    public static void onGameExit(Event<Void> event) {
        cancelAllPendingChunkTasks();
    }

    public static void scheduleChunkTask(ChunkPos pos, Runnable runnable, boolean async) {
        if (async) {
            pendingUpdateTasks.compute(pos, (v, t) -> {
                if (t == null) {
                    return CompletableFuture.runAsync(runnable, scanExecutor);
                } else {
                    return t.thenRunAsync(runnable, scanExecutor);
                }
            });
        } else {
            pendingUpdateTasks.compute(pos, (v, t) -> {
                if (t == null) {
                    return CompletableFuture.runAsync(runnable, mc);
                } else {
                    return t.thenRunAsync(runnable, mc);
                }
            });
        }
    }

    public static boolean shouldExecuteWorldScan() {
        if (!Listener.getPreWorldScannListener().isEmpty()) {
            Event<Boolean> requestEvent = new Event<>(false, false, true);
            Listener.getPreWorldScannListener().handleValue(requestEvent);
            return requestEvent.context == Boolean.TRUE;
        }
        return false;
    }

    public static void restartWorldScanner() {
        Listener.getResetWorldScannListener().broadcast(null);
        cancelAllPendingChunkTasks();
        if (mc.player == null || mc.level == null) {
            return;
        }
        refreshAllChunks();
    }

    public static void refreshAllChunks() {
        if (shouldExecuteWorldScan()) {
            for (ChunkAccess chunk : CommonUtils.chunks(false)) {
                ChunkPos chunkPos = chunk.getPos();
                scheduleChunkTask(chunkPos, () -> onChunkReScann(chunkPos), true);
            }
        }
    }

    private static void onChunkReScann(ChunkPos chunkPos) {
        if (mc.player == null || mc.level == null) return;
        if (mc.level.getChunkSource().hasChunk(chunkPos.x, chunkPos.z)) {
            ChunkAccess chunk = mc.level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk != null) {
                List<BiPredicate<BlockPos, BlockState>> statePredicates = new ArrayList<>();
                Listener.getWorldScannChunkBlockFilterList().broadcast(statePredicates);
                if (statePredicates.isEmpty()) {
                    return;
                }
                BiPredicate<BlockPos, BlockState> predicate =
                        (b, s) -> statePredicates.stream().anyMatch(s1 -> s1.test(b, s));
                Map<BlockPos, BlockState> stateMap = WorldUtils.scannChunk(chunk, predicate);
                processExecutor.execute(() -> {
                    Event<Map<BlockPos, BlockState>> chunkUpdate = new Event<>(stateMap, false, false, chunkPos);
                    Listener.getWorldScannChunkResult().handleValue(chunkUpdate);
                });
            }
        }
    }

    private static void onBlockStateUpdate(Event<BlockUpdate> event) {
        if (shouldExecuteWorldScan()) {
            BlockPos pos = event.context.pos().immutable();
            ChunkPos chunkPos = ChunkPos.containing(pos);
            scheduleChunkTask(chunkPos, () -> onSingleBlockValueChange(pos), true);
        }
    }

    private static void onSingleBlockValueChange(BlockPos pos) {
        // (checkNull()) return;
        ChunkPos chunkPos = CommonUtils.toChunk(pos);
        if (mc.level.getChunkSource().hasChunk(chunkPos.x, chunkPos.z)) {
            BlockState state = mc.level.getBlockState(pos);
            scanExecutor.execute(() -> {
                Event<BlockState> stateUpdate = new Event<>(state, false, false, pos, chunkPos);
                Listener.getWorldScannBlockResult().handleValue(stateUpdate);
            });
        }
    }

    public static void onChunkUpdate(Event<ChunkPos> chunkDataS2CPacketEvent) {
        if (mc.level == null || mc.player == null) return;
        if (shouldExecuteWorldScan()) {
            ChunkPos pos = chunkDataS2CPacketEvent.context;
            ChunkAccess updatedChunk = mc.level.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
            if (updatedChunk != null) {
                // because of chunk update, cancel all the last
                cancelPendingChunkTask(pos);
                scheduleChunkTask(pos, () -> onChunkReScann(pos), true);
            }
        }
    }

    private static <W> void registerListener(ListenerPoint<W> listener, Consumer<W> handler) {
        listener.registerHandler(handler);
    }

    static {
        loadSeedMap();
        registerListener(Listener.getBlockUpdateListener(), WorldTasks::onBlockStateUpdate);
        registerListener(Listener.getChunkUpdateListener(), WorldTasks::onChunkUpdate);
        registerListener(Listener.getWorldSwitchPoint(), WorldTasks::onWorldChange);
        registerListener(Listener.getServerLeavePoint(), WorldTasks::onGameExit);
        registerListener(Listener.getPostGameTick(), WorldTasks::onTick);
    }
}
