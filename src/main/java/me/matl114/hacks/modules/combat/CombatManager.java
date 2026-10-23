package me.matl114.hacks.modules.combat;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.annotation.Nonnull;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import me.matl114.accessors.access.PlayerInteractEntityC2SPacketAccess;
import me.matl114.accessors.interfaces.MetadataHolder;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.annotations.Broadcast;
import me.matl114.events.channels.EventChannel;
import me.matl114.events.impl.BlockUpdate;
import me.matl114.events.impl.UseItemOnBlock;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.interact.SequencedActionManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.utils.DamageUtils;
import me.matl114.utils.ExplosionUtils;
import me.matl114.utils.algorithms.SerialExecutor;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.containers.MetaData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class CombatManager extends BaseModule {
    public final ModulePath combat = makePath(Configs.COMBAT_CONFIG, "attack");
    public static CombatManager INSTANCE;

    public static final int SECTION_RADIUS = 1;
    private static final float BLAST_RESISTANCE_THRESHOLD = 600.0F;
    private static final int DAMAGE_ESTIMATE_TTL = 10;
    private static final float UNKNOWN_DAMAGE_ESTIMATE = 1.0F;
    public static final String KEY_DAMAGE_TRACKER = "slimefunhelper:combat/damage_tracker";
    private static final Set<Block> MINEABLE_BLAST_RESISTANT_BLOCKS;
    private static final Set<Block> UNBREAKABLE_BLAST_RESISTANT_BLOCKS;

    static {
        Set<Block> blocks = new LinkedHashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.getExplosionResistance() >= BLAST_RESISTANCE_THRESHOLD && block.defaultDestroyTime() >= 0.0F) {
                blocks.add(block);
            }
        }
        MINEABLE_BLAST_RESISTANT_BLOCKS = Set.copyOf(blocks);
    }

    private enum TrackedDamageCategory {
        PLAYER_ATTACK(DamageTypes.PLAYER_ATTACK),
        SMASH(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.withDefaultNamespace("mace_smash"))),
        SPEAR(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.withDefaultNamespace("spear"))),
        BAD_RESPAWN_POINT(DamageTypes.BAD_RESPAWN_POINT),
        PLAYER_EXPLOSION(DamageTypes.PLAYER_EXPLOSION),
        EXPLOSION(DamageTypes.EXPLOSION);

        TrackedDamageCategory(ResourceKey<DamageType> world) {
            this.damageTypeKey = world;
        }

        ResourceKey<DamageType> damageTypeKey;
    }

    static {
        Set<Block> blocks = new LinkedHashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.getExplosionResistance() >= BLAST_RESISTANCE_THRESHOLD && block.defaultDestroyTime() < 0.0F) {
                blocks.add(block);
            }
        }
        UNBREAKABLE_BLAST_RESISTANT_BLOCKS = Set.copyOf(blocks);
    }

    public CombatManager() {
        super("CombatManager");
        INSTANCE = this;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreGameTick(), this::onPreTick);
        registerListener(Listener.getPlayerRespawnPoint(), this::clearWorldState);
        registerListener(Listener.getServerLeavePoint(), this::clearWorldState);
        registerListener(Listener.getBlockUpdateListener(), this::onBlockUpdate);
        registerListener(Listener.getChunkUpdateListener(), this::onChunkData);
        registerListener(
                SequencedActionManager.getSequencedActionResponse().getChannel(UseItemOnBlock.class),
                this::onUseOnBlockAck);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundAttackPacket.class),
                this::onPlayerAttackCrystal,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundAttackPacket.class),
                this::onTrackPlayerAttack,
                Integer.MAX_VALUE - 1);
        registerListener(
                Listener.getPacketPreHandlePoint().getChannel(ClientboundDamageEventPacket.class), this::onDamagePacket);
        registerListener(
                Listener.getPacketPreHandlePoint().getChannel(ClientboundExplodePacket.class), this::onExplosionPacket);
        registerListener(Listener.getEntityRemoveListener().getChannel(EntityTypes.END_CRYSTAL), this::onEntityRemoval);
        registerListener(Listener.getPostPlayerUseItemOnBlock(), this::onPlayerPlaceCrystalOnBlock);
    }

    @Getter
    @Broadcast
    private static final EventChannel<Service> requestEnableEvent = new EventChannel<>();

    private final Executor executor = new SerialExecutor(CompletableFuture::runAsync);
    private Map<SectionPos, SectionSnapshot> sectionSnapshots = new ConcurrentHashMap<>();

    public volatile Map<BlockPos, BlockState> trackedObsidianLike = new ConcurrentHashMap<>();
    public volatile Map<BlockPos, BlockState> trackedBedrockLike = new ConcurrentHashMap<>();
    public volatile Map<BlockPos, BlockState> trackedExplosives = new ConcurrentHashMap<>();
    public volatile Set<BlockPos> trackedHoles = ConcurrentHashMap.newKeySet();
    private Set<SectionPos> dirtySections = new HashSet<>();
    private SectionPos lastSectionPos = SectionPos.of(0, 0, 0);
    public final Set<EndCrystal> trackedEndCrystals = new HashSet<>();

    private void clearTrackedCaches() {
        trackedObsidianLike.clear();
        trackedExplosives.clear();
        trackedEndCrystals.clear();
        trackedBedrockLike.clear();
        trackedHoles.clear();
    }

    private void clearCaches() {
        clearTrackedCaches();
        sectionSnapshots = new ConcurrentHashMap<>();
    }

    private volatile Service currentService = new Service();

    public final Map<Entity, Integer> pendingCrystalRemovals = new HashMap<>();
    public final Set<IndexEntry<BlockPos>> pendingCrystalSummons = new HashSet<>();

    private void clearCombatState() {
        clearPendingCrystalOperations();
        clearNearbyEntityCache();
    }

    public void clearPendingCrystalOperations() {
        pendingCrystalRemovals.clear();
        pendingCrystalSummons.clear();
    }

    public void onUseOnBlockAck(Event<IndexEntry<UseItemOnBlock>> event) {
        UseItemOnBlock action = event.context().val();
        pendingCrystalSummons.removeIf(
                entry -> Objects.equals(entry.val(), action.hitResult().getBlockPos()));
    }

    private void onPlayerAttackCrystal(Event<ServerboundAttackPacket> event) {
        if (checkNull()) return;
        if (event.isCancelled()) return;
        // 26.2 起攻击是独立的 ServerboundAttackPacket（record，entityId() 为公开访问器），
        // 不再需要 PlayerInteractEntityC2SPacketAccess 判定 isAttack（后者在 26.2 恒为 false）。
        if (mc.level.getEntity(event.context.entityId()) instanceof EndCrystal crystal) {
            pendingCrystalRemovals.put(crystal, Tasks.getTick());
        }
    }

    private void onPlayerPlaceCrystalOnBlock(Event<UseItemOnBlock> eventUse) {
        if (checkNull()) return;
        if (eventUse.context().actionResult().consumesAction()
                && eventUse.context().handItem().getItem() instanceof EndCrystalItem
                && !eventUse.context().blockPlace()) {
            BlockPos hitResult = eventUse.context.hitResult().getBlockPos();
            BlockState state = mc.level.getBlockState(hitResult);
            if ((state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK)) && mc.level.getBlockState(hitResult.above()).isAir()) {
                pendingCrystalSummons.add(new IndexEntry<>(Tasks.getTick(), hitResult));
            }
        }
    }

    private void onEntityRemoval(Event<Entity> entityEvent) {
        if (entityEvent.context instanceof EndCrystal end) {
            pendingCrystalRemovals.remove(end);
        }
    }

    public boolean attackCrystal(Entity entity) {
        if (!Attack.INSTANCE.attackEntity(entity)) {
            pendingCrystalRemovals.put(entity, Tasks.getTick());
            return true;
        }
        return false;
    }

    public void markCrystalPlace(BlockPos pos) {
        pendingCrystalSummons.add(new IndexEntry<>(Tasks.getTick(), pos));
    }

    public boolean isPendingCrystalRemoval(Entity entity) {
        return pendingCrystalRemovals.containsKey(entity);
    }

    public boolean isPendingCrystalSummon(BlockPos pos) {
        return pendingCrystalSummons.stream().anyMatch(entry -> entry.val().equals(pos));
    }

    public boolean hasPendingCrystalSummon() {
        return !pendingCrystalSummons.isEmpty();
    }

    private List<Entity> currentTickCache = List.of();
    private AABB currentTickCacheBox = null;

    public List<Entity> getNearbyEntities(AABB queryBox) {
        if (contains(currentTickCacheBox, queryBox)) {
            return currentTickCache.stream()
                    .filter(s -> s.getBoundingBox().intersects(queryBox))
                    .filter(this::isNotPendingRemove)
                    .toList();
        }
        return mc.level.getEntities((net.minecraft.world.entity.Entity) null, queryBox, this::isNotPendingRemove);
    }

    private boolean isNotPendingRemove(Entity s) {
        if (s instanceof EndCrystal) {
            return !pendingCrystalRemovals.containsKey(s);
        } else {
            return true;
        }
    }

    public boolean canCubePlace(Player player, BlockPos pos) {
        return canBlockPlace(player, pos, Blocks.OBSIDIAN.defaultBlockState());
    }

    public boolean canBlockPlace(Player player, BlockPos pos, BlockState state) {
        Level world = player.level();
        if (!state.canSurvive(world, pos)) {
            return false;
        }
        CollisionContext context = CollisionContext.placementContext(player);
        VoxelShape shape = state.getCollisionShape(world, pos, context);
        if (shape.isEmpty()) {
            return true;
        }
        VoxelShape worldShape = shape.move(pos.getX(), pos.getY(), pos.getZ());
        return doesNotIntersectPendingCrystals(worldShape) && doesNotIntersectCachedEntities(worldShape);
    }

    private boolean doesNotIntersectCachedEntities(VoxelShape shape) {
        if (shape.isEmpty()) return true;
        AABB box = shape.bounds();
        if (contains(currentTickCacheBox, box)) {
            return doesNotIntersectEntities(currentTickCache, shape);
        }
        return mc.level.noCollision((net.minecraft.world.entity.Entity) null, shape.bounds());
    }

    private boolean doesNotIntersectEntities(List<Entity> entities, VoxelShape shape) {
        if (shape.isEmpty()) {
            return true;
        }
        AABB shapeBox = shape.bounds();
        for (Entity entity : entities) {
            if (entity.isRemoved()
                    || entity.isSpectator()
                    || !entity.isPickable()
                    || !entity.getBoundingBox().intersects(shapeBox)
                    || isPendingCrystalRemoval(entity)) {
                continue;
            }
            if (Shapes.joinIsNotEmpty(
                    shape, Shapes.create(entity.getBoundingBox()), BooleanOp.AND)) {
                return false;
            }
        }

        return true;
    }

    private boolean doesNotIntersectPendingCrystals(VoxelShape shape) {
        if (shape.isEmpty()) return true;
        AABB shapeBox = shape.bounds();
        for (var pending : pendingCrystalSummons) {
            Vec3 crystalBottom = Vec3.atBottomCenterOf(pending.val()).add(0, 1, 0);
            AABB expectingBox = new AABB(
                    crystalBottom.x - 1,
                    crystalBottom.y,
                    crystalBottom.z - 1,
                    crystalBottom.x + 1,
                    crystalBottom.y + 2,
                    crystalBottom.z + 1);
            if (!expectingBox.intersects(shapeBox)) {
                continue;
            }
            if (Shapes.joinIsNotEmpty(shape, Shapes.create(expectingBox), BooleanOp.AND)) {
                return false;
            }
        }
        return true;
    }

    @Nonnull
    public CrystalResult isCrystalConditionedBlockedByEntity(BlockPos crystalPos) {
        return isCrystalConditionedBlockedByEntity(crystalPos, null);
    }

    @Nonnull
    public CrystalResult isCrystalConditionedBlockedByEntity(
            BlockPos crystalPos, @Nullable Function<CrystalResult, Boolean> acceptingCondition) {
        return isCrystalBlockedByEntity(crystalPos, (result) -> {
            if (acceptingCondition != null) {
                var result2 = acceptingCondition.apply(result);
                if (result2 != null) {
                    return result2;
                }
            }
            if (result instanceof PendingItemDrop drop
                    && drop.dropState().getBlock().getExplosionResistance() < 600) {
                // only obsidian like, other block will be destroyed by explosion and we dont have to consider about
                // dropping
                return false;
            } else {
                return true;
            }
        });
    }

    @Nonnull
    public CrystalResult isCrystalBlockedByEntity(
            BlockPos crystalPos, @Nonnull Predicate<CrystalResult> acceptingCondition) {
        AABB crystalEntityBox = new AABB(crystalPos).expandTowards(0, 1, 0);
        var result = getNearbyEntities(crystalEntityBox).stream()
                .filter(s -> EntityUtils.isEntityValid(s) && !isPendingCrystalRemoval(s))
                .map(EntityBlock::new)
                .filter(acceptingCondition)
                .findFirst();
        if (result.isPresent()) {
            return result.get();
        }
        for (var re : pendingCrystalSummons) {
            Vec3 center = Vec3.atBottomCenterOf(re.val()).add(0, 1, 0);
            AABB summonBox = new AABB(center.x - 1, center.y, center.z - 1, center.x + 1, center.y + 2, center.z + 1);
            if (summonBox.intersects(crystalEntityBox)) {
                CrystalResult result2 = new PendingCrystal(re.val());
                if (acceptingCondition.test(result2)) {
                    return result2;
                }
            }
        }
        BlockPos currentPos = crystalPos;
        BlockPos upPos = crystalPos.above();
        var lastBreak = SequencedActionManager.INSTANCE.getBeforeBreakPredictionState(currentPos);
        if (lastBreak.isPresent()) {
            BlockState state = lastBreak.get();
            CrystalResult result3 = new PendingItemDrop(currentPos, state);
            if (acceptingCondition.test(result3)) {
                return result3;
            }
        }
        lastBreak = SequencedActionManager.INSTANCE.getBeforeBreakPredictionState(upPos);
        if (lastBreak.isPresent()) {
            BlockState state = lastBreak.get();
            CrystalResult result4 = new PendingItemDrop(currentPos, state);
            if (acceptingCondition.test(result4)) {
                return result4;
            }
        }
        return new Success();
    }

    private void clearNearbyEntityCache() {
        currentTickCache = List.of();
        currentTickCacheBox = null;
    }

    private static boolean contains(AABB outer, AABB inner) {
        return outer != null
                && outer.minX <= inner.minX
                && outer.minY <= inner.minY
                && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX
                && outer.maxY >= inner.maxY
                && outer.maxZ >= inner.maxZ;
    }

    private void clearUnusedTrackedCaches(Service service) {
        if (!service.enableBlockSearch()) {
            trackedObsidianLike.clear();
            trackedBedrockLike.clear();
        }
        if (!service.enableExplosiveSearch()) {
            trackedExplosives.clear();
            trackedEndCrystals.clear();
        }
        if (!service.enableHoleSearch()) {
            trackedHoles.clear();
        }
    }

    private synchronized void updateTrackedMaps(
            Map<SectionPos, SectionSnapshot> updateMap, boolean trust, Service service) {
        Map<BlockPos, BlockState> obsidianLike = new ConcurrentHashMap<>();
        Map<BlockPos, BlockState> explosives = new ConcurrentHashMap<>();
        Map<BlockPos, BlockState> bedrockLike = new ConcurrentHashMap<>();
        Set<BlockPos> holes = ConcurrentHashMap.newKeySet();
        for (var re : updateMap.values()) {
            if (service.enableBlockSearch()) {
                for (var pos : re.mineableBlastResistantPositions) {
                    BlockState state = mc.level.getBlockState(pos);
                    if (trust || MINEABLE_BLAST_RESISTANT_BLOCKS.contains(state.getBlock())) {
                        obsidianLike.put(pos, state);
                    }
                }
                for (var pos : re.unbreakableBlastResistantPositions) {
                    BlockState state = mc.level.getBlockState(pos);
                    if (trust || UNBREAKABLE_BLAST_RESISTANT_BLOCKS.contains(state.getBlock())) {
                        bedrockLike.put(pos, state);
                    }
                }
            }
            if (service.enableExplosiveSearch()) {
                for (var pos : re.respawnAnchorPositions) {
                    BlockState state = mc.level.getBlockState(pos);
                    if (trust || state.getBlock() instanceof RespawnAnchorBlock) {
                        explosives.put(pos, state);
                    }
                }
            }
            if (service.enableHoleSearch()) {
                for (var pos : re.holesPositions) {
                    if (trust || isHole(mc.level, pos)) {
                        holes.add(pos);
                    }
                }
            }
        }
        trackedObsidianLike = obsidianLike;
        trackedBedrockLike = bedrockLike;
        trackedExplosives = explosives;
        trackedHoles = holes;
    }

    private void clearWorldState() {
        clearCaches();
        clearCombatState();
    }

    public void onPreTick() {
        if (checkNull()) {
            return;
        }
        // for most ping < 50, 3 ticks are ok for responses
        pendingCrystalRemovals
                .entrySet()
                .removeIf(
                        entry -> !EntityUtils.isEntityValid(entry.getKey()) || Tasks.getTick() >= entry.getValue() + 3);
        pendingCrystalSummons.removeIf(entry -> Tasks.getTick() >= entry.index() + 3);
        currentTickCacheBox = mc.player.getBoundingBox().inflate(16, 16, 16);
        currentTickCache = mc.level.getEntities(null, currentTickCacheBox);
        Service lastService = currentService;
        currentService = new Service();
        requestEnableEvent.broadcast(currentService);
        if (!Objects.equals(currentService, lastService)) {
            dirtySections.addAll(sectionSnapshots.keySet());
        }
        if (currentService.isDisabled()) {
            clearCaches();
            return;
        }

        onUpdatePlayerPosition();
        Service service = currentService;
        clearUnusedTrackedCaches(service);
        updateTrackedMaps(sectionSnapshots, false, service);
        Set<SectionPos> sections = dirtySections;
        dirtySections = new HashSet<>();
        ClientLevel world = mc.level;
        Map<SectionPos, SectionSnapshot> sectionRef = new ConcurrentHashMap<>();
        executor.execute(() -> {
            for (var re : sections) {
                sectionRef.put(re, scanSection(world, re, service));
            }
            sectionSnapshots.putAll(sectionRef);
            if (Objects.equals(currentService, service)) {
                updateTrackedMaps(sectionSnapshots, true, service);
            }
        });
        if (service.enableExplosiveSearch()) {
            updateTrackedEntities();
        } else {
            trackedEndCrystals.clear();
        }
    }

    private void onTrackPlayerAttack(Event<ServerboundAttackPacket> event) {
        if (event.isCancelled() || checkNull()) {
            return;
        }
        Entity target = mc.level.getEntity(event.context.entityId());
        if (!(target instanceof Player player)) return;
        ItemStack weapon = mc.player.getMainHandItem();
        cacheDamageEstimate(
                player, TrackedDamageCategory.PLAYER_ATTACK, getMaximumWeaponDamage(mc.player, player, weapon));
        String weaponPath = BuiltInRegistries.ITEM.getKey(weapon.getItem()).getPath();
        if (weapon.getItem() instanceof MaceItem) {
            cacheDamageEstimate(player, TrackedDamageCategory.SMASH, getMaximumWeaponDamage(mc.player, player, weapon));
        }
        if (weaponPath.contains("spear")) {
            cacheDamageEstimate(player, TrackedDamageCategory.SPEAR, getMaximumWeaponDamage(mc.player, player, weapon));
        }
    }

    private void onExplosionPacket(Event<ClientboundExplodePacket> event) {
        if (checkNull()) return;
        BlockPos explosionBlock = BlockPos.containing(event.context.center());
        BlockState explosionState = mc.level.getBlockState(explosionBlock);
        boolean centeredExplosion = Objects.equals(event.context.center(), Vec3.atCenterOf(explosionBlock));
        boolean respawnAnchor = centeredExplosion && explosionState.getBlock() instanceof RespawnAnchorBlock;
        boolean bed = centeredExplosion && explosionState.getBlock() instanceof BedBlock;
        float radius = event.context.radius();
        if (radius <= 0.0F) {
            radius = respawnAnchor
                    ? ExplosionUtils.RESPAWN_ANCHOR_POWER
                    : bed ? ExplosionUtils.BED_POWER : ExplosionUtils.END_CRYSTAL_POWER;
        }
        ExplosionUtils.BlockStateAccess blockAccess =
                ExplosionUtils.fromWorldWithAutomaticOverrides(mc.level, event.context.center());
        for (Player player : mc.level.players()) {
            AABB targetBox = player.getBoundingBox();
            if (player != mc.player) {
                Vec3 syncedPos = PositionPredict.INSTANCE.getPredictor(player).getCurrentSyncedPos();
                if (syncedPos != null) {
                    targetBox = targetBox.move(syncedPos.subtract(player.position()));
                }
            }
            float rawDamage = ExplosionUtils.calculateExplosionRawDamage(
                    radius, event.context.center(), targetBox, blockAccess, ExplosionUtils.ALL_TERRAIN);
            float damage = DamageUtils.getMultipliedDamageByDifficulty(mc.level, rawDamage);
            DamageTracker tracker = getDamageTracker(player);
            if (tracker != null) {
                tracker.onExplosionDamage(damage);
            }
        }
    }

    private void onDamagePacket(Event<ClientboundDamageEventPacket> event) {
        if (checkNull()) return;
        Entity entity = mc.level.getEntity(event.context.entityId());
        if (!(entity instanceof Player player)) return;

        DamageTracker tracker = getOrCreateDamageTracker(player);
        ResourceKey<DamageType> damageType = event.context.sourceType().unwrapKey().orElse(null);
        Entity source = mc.level.getEntity(event.context.sourceCauseId());
        TrackedDamageCategory category = getDamageCategory(damageType);
        float damage = UNKNOWN_DAMAGE_ESTIMATE;
        if (isExplosionDamageCategory(category)) {
            damage = UNKNOWN_DAMAGE_ESTIMATE;
        } else if (source instanceof Player attacker) {
            damage = tracker.takeEstimate(category, Tasks.getTick());
            if (damage <= 0.0F) {
                ItemStack weapon = attacker.getMainHandItem();
                damage = getMaximumWeaponDamage(attacker, player, weapon);
            }
        }
        if (damage <= 0.0F) damage = UNKNOWN_DAMAGE_ESTIMATE;
        tracker.onDamage(damage, Tasks.getTick(), 0);
    }

    public void cacheDamageEstimate(Player player, ResourceKey<DamageType> damageType, float damage) {
        if (damageType == null || !Float.isFinite(damage) || damage < 0.0F) return;
        cacheDamageEstimate(player, getDamageCategory(damageType), damage);
    }

    private void cacheDamageEstimate(Player player, TrackedDamageCategory category, float damage) {
        if (!Float.isFinite(damage) || damage < 0.0F) return;
        DamageTracker tracker = getOrCreateDamageTracker(player);
        tracker.cacheEstimate(category, damage, Tasks.getTick());
        tracker.considerEstimatedDamage(damage);
    }

    private float getMaximumWeaponDamage(Player attacker, Player target, ItemStack weapon) {
        double baseDamage = DamageUtils.getMaxRealAttackDamage(
                attacker,
                target,
                weapon,
                attacker == mc.player ? PlayerStateManager.INSTANCE.fallDistance : attacker.fallDistance,
                true);
        return (float) (baseDamage * 1.5D);
    }

    private static TrackedDamageCategory getDamageCategory(ResourceKey<DamageType> type) {
        if (DamageUtils.isType(type, "mace_smash")) return TrackedDamageCategory.SMASH;
        if (DamageUtils.isType(type, "spear")) return TrackedDamageCategory.SPEAR;
        if (DamageUtils.isType(type, "bad_respawn_point")) return TrackedDamageCategory.BAD_RESPAWN_POINT;
        if (DamageUtils.isType(type, "player_explosion")) return TrackedDamageCategory.PLAYER_EXPLOSION;
        if (DamageUtils.isType(type, "explosion")) return TrackedDamageCategory.EXPLOSION;
        return TrackedDamageCategory.PLAYER_ATTACK;
    }

    private static boolean isExplosionDamageCategory(TrackedDamageCategory category) {
        return category == TrackedDamageCategory.BAD_RESPAWN_POINT
                || category == TrackedDamageCategory.PLAYER_EXPLOSION
                || category == TrackedDamageCategory.EXPLOSION;
    }

    @Nullable
    public DamageTracker getDamageTracker(Player player) {
        if (!(player instanceof MetadataHolder holder) || holder.isMetaEmpty()) return null;
        return holder.getMetadata().get(this, KEY_DAMAGE_TRACKER);
    }

    private DamageTracker getOrCreateDamageTracker(Player player) {
        MetaData metadata = ((MetadataHolder) player).getMetadata();
        return metadata.getOrPut(this, KEY_DAMAGE_TRACKER, DamageTracker::new);
    }

    public static class DamageTracker {
        private final Map<TrackedDamageCategory, DamageEstimate> estimates = new EnumMap<>(TrackedDamageCategory.class);
        public float lastDamage;
        public float damageThreshold;
        public int lastDamageTick;
        private int invulnerabilityEndTick;

        public int getInvulnerabilityTicks() {
            return Math.max(invulnerabilityEndTick - Tasks.getTick(), 0);
        }

        private void cacheEstimate(TrackedDamageCategory category, float damage, int tick) {
            DamageEstimate estimate = new DamageEstimate(damage, tick + DAMAGE_ESTIMATE_TTL);
            estimates.put(category, estimate);
        }

        private void considerEstimatedDamage(float damage) {
            if (getInvulnerabilityTicks() <= 0 || damage <= 0.0F) return;
            lastDamage = Math.max(lastDamage, damage);
            damageThreshold = lastDamage;
        }

        private float takeEstimate(TrackedDamageCategory category, int tick) {
            estimates.entrySet().removeIf(entry -> entry.getValue().expiresAt < tick);
            DamageEstimate estimate = estimates.remove(category);
            if (estimate == null && category != TrackedDamageCategory.PLAYER_ATTACK) {
                estimate = estimates.remove(TrackedDamageCategory.PLAYER_ATTACK);
            }
            return estimate == null ? 0.0F : estimate.damage;
        }

        private void onExplosionDamage(float damage) {
            if (getInvulnerabilityTicks() <= 0 || damage <= 0.0F) return;
            lastDamage = Math.max(lastDamage, damage);
            damageThreshold = lastDamage;
        }

        private void onDamage(float damage, int tick, int networkDelay) {
            lastDamage = damage;
            damageThreshold = lastDamage;
            int invulnerabilityTicks = Math.max(10 - networkDelay, 0);
            lastDamageTick = tick;
            invulnerabilityEndTick = tick + invulnerabilityTicks;
        }

        public boolean canTakeDamage() {
            return getInvulnerabilityTicks() == 0;
        }

        public float getDamageAboveThreshold(float incomingDamage) {
            if (incomingDamage <= 0.0F) return 0.0F;
            if (canTakeDamage()) return incomingDamage;
            return Math.max(incomingDamage - damageThreshold, 0.0F);
        }
    }

    private record DamageEstimate(float damage, int expiresAt) {}

    public void onUpdatePlayerPosition() {
        SectionPos currentPos = SectionPos.of(mc.player);
        sectionSnapshots
                .entrySet()
                .removeIf(re -> Math.abs(re.getKey().getX() - currentPos.getX()) > SECTION_RADIUS
                        || Math.abs(re.getKey().getZ() - currentPos.getZ()) > SECTION_RADIUS
                        || Math.abs(re.getKey().getY() - currentPos.getY()) > SECTION_RADIUS);
        dirtySections.removeIf(re -> Math.abs(re.getX() - currentPos.getX()) > SECTION_RADIUS
                || Math.abs(re.getZ() - currentPos.getZ()) > SECTION_RADIUS
                || Math.abs(re.getY() - currentPos.getY()) > SECTION_RADIUS);
        for (var i = -SECTION_RADIUS; i <= SECTION_RADIUS; ++i) {
            for (var j = -SECTION_RADIUS; j <= SECTION_RADIUS; ++j) {
                for (var k = -SECTION_RADIUS; k <= SECTION_RADIUS; ++k) {
                    SectionPos pos = SectionPos.of(currentPos.getX() + i, currentPos.getY() + j, currentPos.getZ() + k);
                    if (!sectionSnapshots.containsKey(pos)) {
                        dirtySections.add(pos);
                    }
                }
            }
        }

        lastSectionPos = currentPos;
    }

    public void updateTrackedEntities() {
        BlockPos minPos = lastSectionPos.origin();
        AABB currentTrackedBox = new AABB(
                minPos.getX() - 16,
                minPos.getY() - 16,
                minPos.getZ() - 16,
                minPos.getX() + 32,
                minPos.getY() + 32,
                minPos.getZ() + 32);
        trackedEndCrystals.clear();
        trackedEndCrystals.addAll(
                mc.level.getEntities(EntityTypes.END_CRYSTAL, currentTrackedBox, Predicates.alwaysTrue()));
    }

    public void onChunkData(Event<ChunkPos> event) {
        if (checkNull()) {
            return;
        }
        ChunkPos packet = event.context();
        scheduleDirtyChunks(packet.x, packet.z);
    }

    public void onBlockUpdate(Event<BlockUpdate> event) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        onPosUpdate(event.context.pos(), event.context.newState());
    }

    public void onPosUpdate(BlockPos pos, BlockState state) {
        SectionPos sectionPos = SectionPos.of(pos);
        if (isTrackedSection(sectionPos)) {
            SectionSnapshot snapshot = sectionSnapshots.get(sectionPos);
            if (snapshot != null) {
                Block type = state.getBlock();
                if (currentService.enableBlockSearch() && MINEABLE_BLAST_RESISTANT_BLOCKS.contains(type)) {
                    snapshot.mineableBlastResistantPositions.add(pos);
                } else {
                    snapshot.mineableBlastResistantPositions.remove(pos);
                }
                if (currentService.enableBlockSearch() && UNBREAKABLE_BLAST_RESISTANT_BLOCKS.contains(type)) {
                    snapshot.unbreakableBlastResistantPositions.add(pos);
                } else {
                    snapshot.unbreakableBlastResistantPositions.remove(pos);
                }
                if (currentService.enableExplosiveSearch() && type instanceof RespawnAnchorBlock) {
                    snapshot.respawnAnchorPositions.add(pos);
                } else {
                    snapshot.respawnAnchorPositions.remove(pos);
                }
            } else {
                dirtySections.add(sectionPos);
            }
        }
        if (currentService.enableHoleSearch()) {
            updateHoleCandidates(pos);
        }
    }

    private void scheduleDirtyChunks(int chunkX, int chunkZ) {
        if (Math.abs(chunkX - lastSectionPos.getX()) <= 1 && Math.abs(chunkZ - lastSectionPos.getZ()) <= 1) {
            for (var i = -1; i <= 1; ++i) {
                dirtySections.add(SectionPos.of(chunkX, lastSectionPos.getY() + i, chunkZ));
            }
        }
    }

    private SectionSnapshot scanSection(ClientLevel world, SectionPos key, Service service) {
        LevelChunk chunk = world.getChunkSource().getChunkNow(key.getX(), key.getZ());
        if (chunk == null) {
            return SectionSnapshot.empty();
        }

        int sectionIndex = key.getY() - world.getMinSectionY();
        LevelChunkSection[] sections = chunk.getSections();
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return SectionSnapshot.empty();
        }
        LevelChunkSection section = sections[sectionIndex];
        if (section == null || section.hasOnlyAir()) {
            return SectionSnapshot.empty();
        }

        PalettedContainer<BlockState> states = section.getStates();
        Set<BlockPos> mineableBlastResistantPositions = ConcurrentHashMap.newKeySet();
        Set<BlockPos> unbreakableBlastResistantPositions = ConcurrentHashMap.newKeySet();
        Set<BlockPos> respawnAnchorPositions = ConcurrentHashMap.newKeySet();
        Set<BlockPos> holesPositions = ConcurrentHashMap.newKeySet();
        int baseX = key.getX() << 4;
        int baseY = key.getY() << 4;
        int baseZ = key.getZ() << 4;
        for (int y = 0; y < 16; ++y) {
            for (int z = 0; z < 16; ++z) {
                for (int x = 0; x < 16; ++x) {
                    int localIndex = x | (z << 4) | (y << 8);
                    BlockState state = states.get(localIndex);
                    BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                    Block block = state.getBlock();
                    if (service.enableBlockSearch() && MINEABLE_BLAST_RESISTANT_BLOCKS.contains(block)) {
                        mineableBlastResistantPositions.add(pos);
                    }
                    if (service.enableBlockSearch() && UNBREAKABLE_BLAST_RESISTANT_BLOCKS.contains(block)) {
                        unbreakableBlastResistantPositions.add(pos);
                    }
                    if (service.enableExplosiveSearch() && block instanceof RespawnAnchorBlock) {
                        respawnAnchorPositions.add(pos);
                    }
                    if (service.enableHoleSearch() && isHole(world, pos)) {
                        holesPositions.add(pos);
                    }
                }
            }
        }

        return new SectionSnapshot(
                mineableBlastResistantPositions,
                unbreakableBlastResistantPositions,
                respawnAnchorPositions,
                holesPositions);
    }

    private boolean isTrackedSection(SectionPos sectionPos) {
        return Math.abs(sectionPos.getX() - lastSectionPos.getX()) <= SECTION_RADIUS
                && Math.abs(sectionPos.getZ() - lastSectionPos.getZ()) <= SECTION_RADIUS
                && Math.abs(sectionPos.getY() - lastSectionPos.getY()) <= SECTION_RADIUS;
    }

    private void updateHoleCandidates(BlockPos pos) {
        updateHoleState(pos);
        updateHoleState(pos.north());
        updateHoleState(pos.south());
        updateHoleState(pos.west());
        updateHoleState(pos.east());
    }

    private void updateHoleState(BlockPos pos) {
        SectionPos sectionPos = SectionPos.of(pos);
        if (!isTrackedSection(sectionPos)) {
            return;
        }
        SectionSnapshot snapshot = sectionSnapshots.get(sectionPos);
        if (snapshot == null) {
            dirtySections.add(sectionPos);
            return;
        }
        if (isHole(mc.level, pos)) {
            snapshot.holesPositions.add(pos);
        } else {
            snapshot.holesPositions.remove(pos);
        }
    }

    private boolean isHole(ClientLevel world, BlockPos pos) {
        if (!world.getBlockState(pos).isAir()) {
            return false;
        }
        return !world.getBlockState(pos.north()).isAir()
                && !world.getBlockState(pos.south()).isAir()
                && !world.getBlockState(pos.west()).isAir()
                && !world.getBlockState(pos.east()).isAir();
    }

    private record SectionSnapshot(
            Set<BlockPos> mineableBlastResistantPositions,
            Set<BlockPos> unbreakableBlastResistantPositions,
            Set<BlockPos> respawnAnchorPositions,
            Set<BlockPos> holesPositions) {
        public static SectionSnapshot empty() {
            return new SectionSnapshot(
                    ConcurrentHashMap.newKeySet(),
                    ConcurrentHashMap.newKeySet(),
                    ConcurrentHashMap.newKeySet(),
                    ConcurrentHashMap.newKeySet());
        }
    }

    public record ExplosiveContext(BlockState state, Map<Player, Double> damageCache) {}

    @Data
    @Getter
    @Setter
    @Accessors(fluent = true, chain = true)
    public static class Service {
        boolean enableBlockSearch;
        boolean enableExplosiveSearch;
        boolean enableHoleSearch;

        public boolean isDisabled() {
            return !enableBlockSearch && !enableExplosiveSearch && !enableHoleSearch;
        }
    }

    public interface CrystalResult {
        boolean isAccepted();
    }

    public static record Success() implements CrystalResult {

        @Override
        public boolean isAccepted() {
            return true;
        }
    }

    public static record EntityBlock(Entity entity) implements CrystalResult {

        @Override
        public boolean isAccepted() {
            return false;
        }
    }

    public static record PendingCrystal(BlockPos basePos) implements CrystalResult {

        @Override
        public boolean isAccepted() {
            return false;
        }
    }

    public static record PendingItemDrop(BlockPos dropPos, BlockState dropState) implements CrystalResult {

        @Override
        public boolean isAccepted() {
            return false;
        }
    }
}
