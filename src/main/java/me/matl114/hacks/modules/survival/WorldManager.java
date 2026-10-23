package me.matl114.hacks.modules.survival;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import me.matl114.accessors.events.EntityAccess;
import me.matl114.accessors.interfaces.EntityInventory;
import me.matl114.events.Event;
import me.matl114.events.impl.MetadataUpdate;
import me.matl114.events.Listener;
import me.matl114.events.impl.BlockUpdate;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.interact.SequencedActionManager;
import me.matl114.hacks.modules.task.ServerStorage;
import me.matl114.hacks.utils.world.BlockStorage;
import me.matl114.hacks.utils.world.EntityStorage;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.utils.MathUtils;
import me.matl114.utils.NBTUtils;
import me.matl114.utils.algorithms.SerialExecutor;
import me.matl114.utils.world.BlockLocation;
import me.matl114.versioned.api.VDataFlag;
import me.matl114.versioned.api.VItem;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.block.state.BlockState;

public class WorldManager extends BaseModule {
    public static WorldManager INSTANCE;

    public WorldManager() {
        super("WorldManager");
        INSTANCE = this;
    }

    public ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "world-manager");

    public final FlagRef enableEntityPersistentStorage =
            builder(root.add("enable-entity"), Boolean.class).defaultValue(true).build();

    public final FlagRef enableBlockEntityPersistentStorage = builder(root.add("enable-block-entities"), Boolean.class)
            .defaultValue(true)
            .build();

    public final Map<UUID, EntityStatus> currentEntities = new ConcurrentHashMap<>();

    public final Map<BlockLocation, BlockStatus> currentBlocks = new ConcurrentHashMap<>();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(ServerStorage.getServerStorageLoad(), this::onLoad);
        registerListener(Listener.getPostGameTick(), this::onGameTick);
        registerListener(Listener.getEntityRemoveListener(), this::onEntityDeath);
        registerListener(
                Listener.getEntityTrackDataUpdate().getChannel(EntityTypes.VILLAGER), this::onVillagerProfessionUpdate);
        registerListener(
                Listener.getPacketPreHandlePoint().getChannel(ClientboundMerchantOffersPacket.class),
                this::onVillagerTradeUpdate);
        registerListener(
                Listener.getBlockUpdateListener().getChannel(Blocks.TRIAL_SPAWNER), this::onTrialSpawnerStateUpdate);
        registerListener(
                Listener.getPacketPostHandlePoint()
                        .getChannel(ClientboundBlockUpdatePacket.class),
                this::onVaultStateUpdate);

        registerListener(
                Listener.getServerEntitySpawnListener().getChannel(EntityTypes.ENDER_PEARL),
                this::onThrownOwnerDataUpdate);

        registerListener(ServerStorage.getServerStorageSave(), this::onSave);
        registerListener(ServerStorage.getServerStorageLoad(), this::onLoad);
    }

    private final Executor asyncExecutor = new SerialExecutor(CompletableFuture::runAsync);

    public static final String ENTITY_DATA_KEY = "slimefunhelper:world_manager/entity_data_storage";
    public static final String BLOCK_DATA_KEY = "slimefunhelper:world_manager/block_data_storage";

    public static final String KEY_VILLAGER_TRADE = "slimefunhelper:villager/trade_info";
    public static final String KEY_VILLAGER_TRADE_LOCK = "slimefunhelper:trade_lock";
    public static final String KEY_VILLAGER_TRADE_LIST = "slimefunhelper:trade_list";

    public void setVillagerTradeLock(Villager villager, boolean lock) {
        var status = getStatus(villager, true);
        CompoundTag nbtCompound = status.getDataContainer();
        CompoundTag sub = NBTUtils.ensurePath(nbtCompound, KEY_VILLAGER_TRADE);
        sub.putByte(KEY_VILLAGER_TRADE_LOCK, lock ? (byte) 1 : (byte) 0);
        status.markDirty();
    }

    public boolean isVillagerTradeLock(Villager villager) {
        var status = getStatus(villager, false);
        return status != null
                && NBTUtils.resolve(status.getDataContainer(), KEY_VILLAGER_TRADE, KEY_VILLAGER_TRADE_LOCK)
                        instanceof ByteTag nbtByte
                && nbtByte.byteValue() == (byte) 1;
    }

    public static boolean canVillagerResetTrade(MerchantMenu handler) {
        return handler.getTraderXp() == 0 && handler.getTraderLevel() <= 1;
    }

    public void setVillagerTradeList(Villager villager, List<TradeRecord> trades) {
        var status = getStatus(villager, true);
        CompoundTag nbtCompound = status.getDataContainer();
        CompoundTag sub = NBTUtils.ensurePath(nbtCompound, KEY_VILLAGER_TRADE);
        NBTUtils.putValue(
                sub,
                KEY_VILLAGER_TRADE_LIST,
                trades,
                Codec.list(TradeRecord.CODEC),
                mc.getConnection().registryAccess());
        status.markDirty();
    }

    @Nullable
    public List<TradeRecord> getVillagerTradeList(Villager villager) {
        var status = getStatus(villager, false);
        return status != null
                        && NBTUtils.resolve(status.getDataContainer(), KEY_VILLAGER_TRADE, KEY_VILLAGER_TRADE_LIST)
                                instanceof ListTag list
                ? NBTUtils.toValue(
                        list, Codec.list(TradeRecord.CODEC), mc.getConnection().registryAccess())
                : null;
    }

    public record TradeRecord(ItemStack result, ItemStack buy1, ItemStack buy2, int buyLimit) {
        public static final Codec<TradeRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        VItem.ITEM_STACK_CODEC.fieldOf("result").forGetter(TradeRecord::result),
                        VItem.ITEM_STACK_CODEC
                                .optionalFieldOf("buy1", ItemStack.EMPTY)
                                .forGetter(TradeRecord::buy1),
                        VItem.ITEM_STACK_CODEC
                                .optionalFieldOf("buy2", ItemStack.EMPTY)
                                .forGetter(TradeRecord::buy2),
                        Codec.INT.fieldOf("buy-limit").forGetter(TradeRecord::buyLimit))
                .apply(instance, TradeRecord::new));
    }

    public void onVillagerTradeUpdate(Event<ClientboundMerchantOffersPacket> eventSetTrade) {
        if (eventSetTrade.context.getContainerId() == mc.player.containerMenu.containerId) {
            if (mc.player.containerMenu instanceof EntityInventory<?> inventory
                    && inventory.getOwner() instanceof Villager villager) {
                asyncExecutor.execute(() -> {
                    var offers = eventSetTrade.context.getOffers();
                    var canRefresh =
                            eventSetTrade.context.getVillagerXp() == 0 && eventSetTrade.context.getVillagerLevel() <= 1;
                    setVillagerTradeLock(villager, !canRefresh);
                    List<TradeRecord> trades = offers.stream()
                            .map(offer -> {
                                return new TradeRecord(
                                        offer.getResult(),
                                        offer.getItemCostA().itemStack(),
                                        offer.getItemCostB()
                                                .map(ItemCost::itemStack)
                                                .orElse(ItemStack.EMPTY),
                                        offer.getMaxUses());
                            })
                            .toList();
                    setVillagerTradeList(villager, trades);
                });
            }
        }
    }

    public void onVillagerProfessionUpdate(Event<MetadataUpdate> eventDataUpdate) {
        if (eventDataUpdate.context().entity() instanceof Villager villager) {
            if (eventDataUpdate.context().metadata().id() == VDataFlag.ID_VILLAGER_PROFESSION_DATA
                    && eventDataUpdate.context().metadata().value() instanceof VillagerData data) {
                asyncExecutor.execute(() -> {
                    var profession = data.profession().unwrapKey().orElse(null);
                    if (Objects.equals(profession, VillagerProfession.NONE)
                            || Objects.equals(profession, VillagerProfession.NITWIT)) {
                        var status = getStatus(villager, false);
                        if (status != null) {
                            status.getDataContainer().remove(KEY_VILLAGER_TRADE);
                            status.markDirty();
                        }
                    } else {
                        if (data.level() > 1) {
                            setVillagerTradeLock(villager, true);
                        }
                    }
                });
            }
        }
    }

    public static final String KEY_TRIAL_INFO = "slimefunhelper:trial/trial_info";

    public static final String KEY_TRIAL_FINISH_GLOBAL_TIME = "slimefunhelper:trial_cooldown_global_time";

    public static final String KEY_TRIAL_ACTIVE_GLOBAL_TIME = "slimefunhelper:trial_active_global_time";

    public static final String KEY_VAULT_OPEN_PLAYERS = "slimefunhelper:vault_open_players";

    private static final Codec<List<UUID>> VAULT_OPEN_PLAYERS_CODEC = Codec.list(UUIDUtil.CODEC);

    public Set<UUID> getVaultOpenPlayers(VaultBlockEntity vault) {
        BlockStatus status = getStatus(vault, false);
        if (status == null) {
            return Set.of();
        }
        Tag element = NBTUtils.resolve(status.getDataContainer(), KEY_TRIAL_INFO, KEY_VAULT_OPEN_PLAYERS);
        List<UUID> players = element == null ? null : NBTUtils.toValue(element, VAULT_OPEN_PLAYERS_CODEC);
        return players == null ? Set.of() : Set.copyOf(players);
    }

    public boolean hasVaultBeenOpenedBy(VaultBlockEntity vault, UUID player) {
        return player != null && getVaultOpenPlayers(vault).contains(player);
    }

    public void recordVaultOpenedBy(VaultBlockEntity vault, Set<UUID> players) {
        BlockStatus status = getStatus(vault, true);
        CompoundTag trialInfo = NBTUtils.ensurePath(status.getDataContainer(), KEY_TRIAL_INFO);
        NBTUtils.putValue(trialInfo, KEY_VAULT_OPEN_PLAYERS, List.copyOf(players), VAULT_OPEN_PLAYERS_CODEC);
        status.markDirty();
    }

    public void recordVaultOpenedBy(VaultBlockEntity vault, UUID player) {
        if (player == null) {
            return;
        }
        BlockStatus status = getStatus(vault, true);
        CompoundTag trialInfo = NBTUtils.ensurePath(status.getDataContainer(), KEY_TRIAL_INFO);
        Set<UUID> players = new LinkedHashSet<>(getVaultOpenPlayers(vault));
        if (players.add(player)) {
            NBTUtils.putValue(trialInfo, KEY_VAULT_OPEN_PLAYERS, List.copyOf(players), VAULT_OPEN_PLAYERS_CODEC);
            status.markDirty();
        }
    }

    private void onVaultStateUpdate(Event<ClientboundBlockUpdatePacket> event) {
        if (checkNull()) {
            return;
        }
        ClientboundBlockUpdatePacket packet = event.context;
        BlockPos pos = packet.getPos();
        BlockState newState = packet.getBlockState();
        if (newState.getBlock() != Blocks.VAULT
                || newState.getValue(VaultBlock.STATE) != VaultState.UNLOCKING
                || !(mc.level.getBlockEntity(pos) instanceof VaultBlockEntity vault)
                || !SequencedActionManager.INSTANCE.isWaitingBlockResponse(
                        pos, stack -> stack.is(Items.TRIAL_KEY) || stack.is(Items.OMINOUS_TRIAL_KEY))) {
            return;
        }
        recordVaultOpenedBy(vault, mc.player.getUUID());
    }

    public void onTrialSpawnerStateUpdate(Event<BlockUpdate> event) {
        if (event.context.oldState().getBlock() == Blocks.TRIAL_SPAWNER
                && event.context.newState().getBlock() == Blocks.TRIAL_SPAWNER) {
            BlockState oldState = event.context.oldState();
            BlockState newState = event.context.newState();
            BlockPos pos = event.context.pos();
            if (mc.level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity be) {
                TrialSpawnerState oldAct = oldState.getValue(TrialSpawnerBlock.STATE);
                TrialSpawnerState newAct = newState.getValue(TrialSpawnerBlock.STATE);
                if (oldAct != newAct) {
                    if (newAct == TrialSpawnerState.COOLDOWN) {
                        var bc = getStatus(be, true);
                        var sub = NBTUtils.ensurePath(bc.getDataContainer(), KEY_TRIAL_INFO);
                        sub.putLong(KEY_TRIAL_FINISH_GLOBAL_TIME, System.currentTimeMillis());
                        bc.markDirty();
                    }
                    if (newAct == TrialSpawnerState.ACTIVE) {
                        var bc = getStatus(be, true);
                        var sub = NBTUtils.ensurePath(bc.getDataContainer(), KEY_TRIAL_INFO);
                        sub.putLong(KEY_TRIAL_ACTIVE_GLOBAL_TIME, System.currentTimeMillis());
                        bc.markDirty();
                    }
                }
            }
        }
    }

    public static final String KEY_PEARL_INFO = "slimefunhelper:thrown/owner_info";

    public static final String KEY_PEARL_NAME = "slimefunhelper:owner_info/name";

    public static final String KEY_PEARL_UUID = "slimefunhelper:owner_info/uid";

    public void onThrownOwnerDataUpdate(Event<Entity> data) {
        if (data.context instanceof Projectile thrown) {
            getStatus(thrown, true).setUpdateCallback((lv) -> {
                if (lv instanceof Projectile thrown2
                        && EntityAccess.of(thrown2).getLivingTicks() > 250
                        && thrown2.getOwner() instanceof Player pl) {
                    var bc = getStatus(lv, true);
                    var sub = NBTUtils.ensurePath(bc.getDataContainer(), KEY_PEARL_INFO);
                    sub.put(KEY_PEARL_NAME, StringTag.valueOf(pl.getScoreboardName()));
                    NBTUtils.putValue(sub, KEY_PEARL_UUID, pl.getUUID(), UUIDUtil.CODEC);
                    bc.markDirty();
                }
            });
        }
    }

    public UUID getThrownEntityOwner(Projectile thrown) {
        var status = getStatus(thrown, false);
        if (status != null) {
            var uuid = NBTUtils.resolve(status.getDataContainer(), KEY_PEARL_INFO, KEY_PEARL_UUID);
            if (uuid != null) {
                return NBTUtils.toValue(uuid, UUIDUtil.CODEC);
            }
        }
        return null;
    }

    public String getThrownEntityOwnerName(Projectile thrown) {
        var status = getStatus(thrown, false);
        return status != null
                        && NBTUtils.resolve(status.getDataContainer(), KEY_PEARL_INFO, KEY_PEARL_NAME)
                                instanceof StringTag str
                ? str.value()
                : null;
    }

    public OptionalLong getTrialSpawnerCooldownStartTime(BlockEntity be) {
        var container = getStatus(be, false);
        if (container == null) {
            return OptionalLong.empty();
        }
        var nbtLong = NBTUtils.resolve(container.getDataContainer(), KEY_TRIAL_INFO, KEY_TRIAL_FINISH_GLOBAL_TIME);
        return nbtLong instanceof LongTag longValue ? OptionalLong.of(longValue.longValue()) : OptionalLong.empty();
    }

    public OptionalLong getTrialSpawnerActiveStartTime(BlockEntity be) {
        var container = getStatus(be, false);
        if (container == null) {
            return OptionalLong.empty();
        }
        var nbtLong = NBTUtils.resolve(container.getDataContainer(), KEY_TRIAL_INFO, KEY_TRIAL_ACTIVE_GLOBAL_TIME);
        return nbtLong instanceof LongTag longValue ? OptionalLong.of(longValue.longValue()) : OptionalLong.empty();
    }

    public void onLoad(Event<ServerStorage.Meta> event) {
        ServerStorage.Meta meta = event.context;
        RegistryAccess manager = event.getArgs(1);
        currentEntities.clear();
        currentBlocks.clear();
        for (var storage : meta.allEntityStorages()) {
            if (storage.contains(ENTITY_DATA_KEY)) {
                EntityStatus status = storage.get(ENTITY_DATA_KEY, EntityStatus.CODEC, manager);
                if (status != null) {
                    currentEntities.put(status.getSelf(), status);
                }
            }
        }
        for (var re : meta.toBlockList()) {
            if (re.contains(BLOCK_DATA_KEY)) {
                BlockStatus status = re.get(BLOCK_DATA_KEY, BlockStatus.CODEC, manager);
                if (status != null) {
                    currentBlocks.put(BlockLocation.of(re.getDimension(), re.getPos()), status);
                }
            }
        }
    }

    int timer = 0;

    public void onGameTick() {
        if (checkNull()) return;
        if (++timer < 10) {
            return;
        }
        timer = 0;
        var iter = currentBlocks.entrySet().iterator();
        while (iter.hasNext()) {
            var r = iter.next();
            var re = r.getKey();
            if (re.isLocationLoaded(mc.level)) {
                BlockPos pos = re.getPos();
                BlockEntity be = mc.level.getBlockEntity(pos);
                if (be != null && be.getType() == r.getValue().getType()) {
                    r.getValue().update(pos, be);
                } else {
                    iter.remove();
                    onRemoveBlock(re);
                }
            }
        }
        var iter2 = currentEntities.entrySet().iterator();
        while (iter2.hasNext()) {
            var re = iter2.next();
            var entity = mc.level.getEntities().get(re.getKey());
            if (isAlive(entity)) {
                re.getValue().update(entity);
            }
        }
    }

    public EntityStatus getStatus(Entity entity, boolean create) {
        if (isAlive(entity)) {
            return create
                    ? currentEntities.computeIfAbsent(entity.getUUID(), EntityStatus::new)
                    : currentEntities.get(entity.getUUID());
        } else {
            return null;
        }
    }

    public BlockStatus getStatus(BlockEntity entity, boolean create) {
        BlockLocation bl = BlockLocation.of(entity.getLevel(), entity.getBlockPos());
        return currentBlocks.compute(bl, (k, v) -> {
            if (v == null) {
                return create ? new BlockStatus(entity.getType()) : null;
            } else {
                return v.getType() == entity.getType() ? v : null;
            }
        });
    }

    private boolean isAlive(Entity entity) {
        return (!(entity instanceof LivingEntity lv) || lv.getHealth() > 0.0);
    }

    public void onEntityDeath(Event<Entity> event) {
        if (checkNull()) return;
        Entity entity = event.context;
        if (entity instanceof LivingEntity lv) {
            if (lv.getHealth() <= 0) {
                onConfirmDeathEntities(entity);
            }
        } else {
            if (entity.position().distanceToSqr(mc.player.position()) < MathUtils.s2(60)) {
                onConfirmDeathEntities(entity);
            }
        }
    }

    private void onConfirmDeathEntities(Entity entity) {
        currentEntities.remove(entity.getUUID());
        var meta = ServerStorage.getStorage();
        if (meta != null) {
            EntityStorage storage = meta.getEntityStorage(entity.getUUID(), false);
            if (storage != null) {
                storage.put(ENTITY_DATA_KEY, null);
            }
        }
    }

    private void onRemoveBlock(BlockLocation location) {
        BlockStorage storage = ServerStorage.getStorage().getBlockStorage(location.world(), location.getPos(), false);
        if (storage != null) {
            storage.put(BLOCK_DATA_KEY, null);
            ServerStorage.update(storage, true);
        }
    }

    public void onSave(Event<ServerStorage.Meta> event) {
        RegistryAccess manager = event.getArgs(1);
        ServerStorage.Meta meta = event.context;
        if (enableEntityPersistentStorage.getValue()) {
            for (var entry : currentEntities.entrySet()) {
                EntityStatus status = entry.getValue();
                if (status.isDirty()) {

                    if (!status.isEmpty()) {
                        EntityStorage storage = meta.getEntityStorage(entry.getKey(), true);
                        storage.put(ENTITY_DATA_KEY, status, EntityStatus.CODEC, manager);
                    } else {
                        EntityStorage storage = meta.getEntityStorage(entry.getKey(), false);
                        if (storage != null) {
                            storage.put(ENTITY_DATA_KEY, null);
                        }
                    }
                    status.dirty = false;
                }
            }
        }
        if (enableBlockEntityPersistentStorage.get()) {
            for (var entry : currentBlocks.entrySet()) {
                BlockLocation location = entry.getKey();
                BlockStatus status = entry.getValue();
                if (status.isDirty()) {
                    if (!status.isEmpty()) {
                        BlockStorage storage = meta.getBlockStorage(location.world(), location.getPos(), true);
                        storage.put(BLOCK_DATA_KEY, status, BlockStatus.CODEC, manager);
                    } else {
                        BlockStorage storage = meta.getBlockStorage(location.world(), location.getPos(), false);
                        if (storage != null) {
                            storage.put(BLOCK_DATA_KEY, null);
                            ServerStorage.update(storage, true);
                        }
                    }
                    status.dirty = false;
                }
            }
        }
    }

    @Getter
    public static class EntityStatus {
        final UUID self;
        Optional<UUID> owner = Optional.empty();
        long lastUpdatedMs;
        CompoundTag dataContainer = new CompoundTag();
        boolean dirty = false;
        public static final Codec<EntityStatus> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        UUIDUtil.CODEC.fieldOf("uuid").forGetter(EntityStatus::getSelf),
                        UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(EntityStatus::getOwner),
                        Codec.LONG.optionalFieldOf("timestamp", 0L).forGetter(EntityStatus::getLastUpdatedMs),
                        CompoundTag.CODEC
                                .optionalFieldOf("data", new CompoundTag())
                                .forGetter(EntityStatus::getDataContainer))
                .apply(instance, EntityStatus::new));

        @Setter
        public Consumer<Entity> updateCallback;

        public EntityStatus(UUID self, Optional<UUID> owner, long lastUpdatedMs, CompoundTag dataContainer) {
            this.self = self;
            this.owner = owner;
            this.lastUpdatedMs = lastUpdatedMs;
            this.dataContainer = dataContainer.copy();
        }

        public EntityStatus(UUID uid) {
            this.self = uid;
            lastUpdatedMs = System.currentTimeMillis();
        }

        public void markDirty() {
            this.dirty = true;
        }

        public void updateTime() {
            lastUpdatedMs = System.currentTimeMillis();
        }

        public void update(Entity entity) {
            updateTime();
            if (updateCallback != null) {
                updateCallback.accept(entity);
            }
        }

        public boolean isEmpty() {
            return owner.isEmpty() && dataContainer.isEmpty();
        }
    }

    @Getter
    public static class BlockStatus {
        long lastUpdatedMs;
        final BlockEntityType<?> type;
        CompoundTag dataContainer = new CompoundTag();
        boolean dirty = false;

        @Setter
        BiConsumer<BlockPos, BlockEntity> updateCallback;

        public BlockStatus(BlockEntityType<?> type, long lastUpdatedMs, CompoundTag dataContainer) {
            this.type = type;
            this.lastUpdatedMs = lastUpdatedMs;
            this.dataContainer = dataContainer.copy();
        }

        public BlockStatus(BlockEntityType<?> type) {
            this.type = type;
            lastUpdatedMs = System.currentTimeMillis();
        }

        public void markDirty() {
            this.dirty = true;
        }

        public void updateTime() {
            lastUpdatedMs = System.currentTimeMillis();
        }

        public void update(BlockPos pos, BlockEntity blockEntity) {
            updateTime();
            if (updateCallback != null) {
                updateCallback.accept(pos, blockEntity);
            }
        }

        public boolean isEmpty() {
            return dataContainer.isEmpty();
        }

        public static final Codec<BlockStatus> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BuiltInRegistries.BLOCK_ENTITY_TYPE
                                .byNameCodec()
                                .fieldOf("block-type")
                                .forGetter(BlockStatus::getType),
                        Codec.LONG.fieldOf("timestamp").forGetter(BlockStatus::getLastUpdatedMs),
                        CompoundTag.CODEC
                                .optionalFieldOf("data", new CompoundTag())
                                .forGetter(BlockStatus::getDataContainer))
                .apply(instance, BlockStatus::new));
    }
}
