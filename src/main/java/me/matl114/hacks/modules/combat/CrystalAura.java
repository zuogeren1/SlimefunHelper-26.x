package me.matl114.hacks.modules.combat;

import com.mojang.datafixers.util.Pair;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.BlockBreak;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.task.DisablerManager;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.interact.SequencedActionManager;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.mine.PacketMine;
import me.matl114.hacks.modules.task.DisablerManager;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.hacks.utils.interact.InteractRequest;
import me.matl114.hacks.utils.interact.InteractRequestImpl;
import me.matl114.hacks.utils.interact.InteractionQueue;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hacks.utils.tasks.TimerExecutor;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.collections.FlagEntry;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import me.matl114.hacks.utils.EntityUtils;

public class CrystalAura extends BaseModule {
    private static final int CRYSTAL_SEARCH_RADIUS = 5;

    public CrystalAura() {
        super("CrystalAura");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.COMBAT_CONFIG, "combat-utils.crystal-aura");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final EnumRef<LegalInteractMode> mode = builder(root.add("mode"), LegalInteractMode.class)
            .defaultValue(LegalInteractMode.NONE)
            .build();

    public final IntRef range = intBuilder(root.add("range"))
            .defaultValue(10)
            .validator(Configs.INT_NONNEGATIVE)
            .build();
    private List<Vec3i> interactRangeBlocks = new ArrayList<>();
    public final DoubleRef interactRange = doubleBuilder(root.add("interact-range"))
            .defaultValue(4.5)
            .validator(Configs.doubleRange(0.0D, 100.0D))
            .updateListener(s -> interactRangeBlocks = MathUtils.create3DPointListAroundPlayer(s))
            .build();

    public final IntRef delay = intBuilder(root.add("delay"))
            .defaultValue(3)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final IntRef attackDelay = intBuilder(root.add("attack-delay"))
            .defaultValue(2)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final FlagRef placeAfterAttack = builder(root.add("place-after-attack"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef eatingAbort = builder(root.add("using-item-abort"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef offhand =
            builder(root.add("offhand"), Boolean.class).defaultValue(false).build();

    public final FlagRef airplaceBase =
            builder(root.add("air-place"), Boolean.class).defaultValue(false).build();

    public final DoubleRef selfFinalDamageThreshold = doubleBuilder(root.add("self-final-damage-threshold"))
            .defaultValue(4.0D)
            .validator(Configs.doubleRange(0.0D, 1000.0D))
            .build();

    public final DoubleRef targetDamageThreshold = doubleBuilder(root.add("target-damage-threshold"))
            .defaultValue(16.0D)
            .validator(Configs.doubleRange(0.0D, 1000.0D))
            .build();

    public final ModulePath invulnerability = root.add("invulnerability-frame");

    public final FlagRef considerInvulnerability = builder(invulnerability.add("consider"), Boolean.class)
            .defaultValue(false)
            .build();

    public final DoubleRef invulnerabilityDamageMultiplier = doubleBuilder(invulnerability.add("damage-multiplier"))
            .defaultValue(1.0D)
            .validator(Configs.doubleRange(0.0D, 100.0D))
            .build();

    public final IntRef invulnerabilityConsiderTicks = intBuilder(invulnerability.add("consider-ticks"))
            .defaultValue(7)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final FlagRef usePredictor = builder(root.add("damage-use-predictor"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef considerExistingDamage = builder(
                    invulnerability.add("consider-existing-damage"), Boolean.class)
            .defaultValue(true)
            .build();

    public final EnumRef<GhostHandMode> ghostHand = builder(root.add("ghost-hand-mode"), GhostHandMode.class)
            .defaultValue(GhostHandMode.INV_SWAP)
            .build();

    public final FlagRef notifySupply =
            builder(root.add("notify-supply"), Boolean.class).defaultValue(true).build();

    public final FlagRef swingHand =
            builder(root.add("swing-hand"), Boolean.class).defaultValue(true).build();

    public final ModulePath base = root.add("obsidian-base");

    public final FlagRef autoBase =
            builder(base.addEnable(), Boolean.class).defaultValue(true).build();

    public final FlagRef zeroTickBase = builder(base.add("zero-tick-base"), Boolean.class)
            .defaultValue(false)
            .build();

    public final DoubleRef baseReduce = builder(base.add("base-value-reduce"), DoubleRef.TYPE)
            .defaultValue(0.9D)
            .build();

    public final ModulePath fill = root.add("auto-fill");

    public final FlagRef autoFill =
            builder(fill.addEnable(), Boolean.class).defaultValue(false).build();

    public final KeyBindRef hotkeyFill =
            toggleHotkey(fill.addHotkey(), new MultiKeyBind(), fill.addEnable()).build();

    public final NBTRef<EntrySet<Item>> fillWhiteList = builder(fill.add("white-list"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.ITEM, List.of(Items.GLASS, Items.OAK_LEAVES)))
            .build();

    public final FlagRef fillIgnoreDamage = builder(fill.add("fill-ignore-damage"), Boolean.class)
            .defaultValue(false)
            .build();

    public final IntRef fillMinWaitTime = intBuilder(fill.add("fill-min-wait-time"))
            .defaultValue(2)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef fillMaxWaitTime = intBuilder(fill.add("fill-max-wait-time"))
            .defaultValue(4)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final ModulePath damageBlock = root.add("auto-block");

    public final FlagRef autoBlock =
            builder(damageBlock.addEnable(), Boolean.class).defaultValue(false).build();

    public final NBTRef<EntrySet<Item>> blockingBlock = builder(
                    damageBlock.add("blocking-block"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.ITEM, List.of(Items.OBSIDIAN)))
            .build();

    public final DoubleRef blockReduce = builder(damageBlock.add("block-reduce"), DoubleRef.TYPE)
            .defaultValue(0.9D)
            .build();

    public final FlagRef zeroTickBlock = builder(damageBlock.add("zero-tick"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef scaffoldBlock = builder(damageBlock.add("scaffold-block"), Boolean.class)
            .defaultValue(false)
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPlayerRespawnPoint(), this::onWorldSwitch);
        registerListener(Listener.getPostHandleInputEvents(), this::onPostInputEvent);
        registerListener(PacketMine.getPacketMineAction().getChannel(BlockBreak.Stage.PRE), this::onPrePacketMine);
        registerListener(PacketMine.getPacketMineAction(), this::onPostPacketMine);
        registerListener(CombatManager.getRequestEnableEvent(), this::onRequestCombatService);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onPreset);
        registerListener(RenderListener.getRender3DEvent(), this::onRender3D);
    }

    TimerExecutor supplyEndCrystalExecutor = new TimerExecutor();
    TimerExecutor supplyBaseExecutor = new TimerExecutor();
    TimerExecutor supplyFillBlockExecutor = new TimerExecutor();
    TimerExecutor tickAttackExecutor = new TimerExecutor();
    List<Player> currentTarget = List.of();
    List<CrystalPlan> pendingBases = new ArrayList<>();
    private InteractionQueue blockingQueue;
    private CrystalPlan blockingPlan;
    private boolean placeCrystalAfterBlocking;
    Map<BlockPos, Integer> trackedGlasses = new ConcurrentHashMap<>();
    int timer = 0;
    RenderCollector<AABB> debugRender = RenderCollectors.createBoxCollector(true, false, false);
    boolean nextHitSkipDamageTest;
    double maxExistingDamageValue = Double.NEGATIVE_INFINITY;

    public void onWorldSwitch() {
        currentTarget = List.of();
        trackedGlasses.clear();
        timer = 0;
        pendingBases.clear();
        clearBlockingQueue();
    }

    boolean fillWorkingTick = false;
    Pair<BlockPos, BlockState> lastMiningTarget = null;

    public void onPostInputEvent() {
        if (checkNull()) {
            return;
        }
        debugRender.clear();
        if (enable.get()) {
            boolean shouldWorkFill = false;
            if (fillWorkingTick) {
                shouldWorkFill = true;
                fillWorkingTick = false;
            }
            var access = PlayerInteractionAccess.of(mc.gameMode);
            BlockPos pos = access.getCurrentMiningPos();
            Pair<BlockPos, BlockState> currentPair = Pair.of(pos, mc.level.getBlockState(pos));
            if (!Objects.equals(currentPair, lastMiningTarget)) {
                lastMiningTarget = currentPair;
                if (SequencedActionManager.INSTANCE.isWaitingStopBreakResponse(pos)) {
                    shouldWorkFill = true;
                }
            }
            refreshTargets();
            if (currentTarget.isEmpty()) return;
            if (eatingAbort.get() && mc.player.isUsingItem()) {
                return;
            }
            boolean blockingWasPending = blockingQueue != null;
            boolean alreadyHasPlace = tickTrackedGlasses();
            if (alreadyHasPlace) {
                timer = 0;
            }
            // constantly attack, only delay when place
            boolean attack = tickAttack();
            boolean queuedPlaces =
                    blockingWasPending ? tickBlockingQueue() : blockingQueue == null && tickPendingBase();
            if (queuedPlaces) {
                timer = 0;
            }
            ++timer;
            if (!blockingWasPending
                    && blockingQueue == null
                    && (timer >= delay.get() || (placeAfterAttack.get() && attack) || fillWorkingTick)
                    && !currentTarget.isEmpty()) {
                if (checkSupplies()) {
                    if (tickPlace()) {
                        timer = 0;
                    }
                }
            }
            if (shouldWorkFill && autoFill.get()) {
                tickFill(pos);
            }
        } else {
            currentTarget = List.of();
            trackedGlasses.clear();
            timer = 0;
            pendingBases.clear();
            clearBlockingQueue();
        }
    }

    private void tickFill(BlockPos pos) {
        if (CombatManager.INSTANCE.hasPendingCrystalSummon()) {
            return;
        }
        if (supplyCrystalItem() == null) {
            return;
        }
        if (!InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), pos)) return;
        BlockState currentState = mc.level.getBlockState(pos);
        if (!currentState.isAir()) return;
        if (!canFillPos(pos)) {
            return;
        }
        // within range check, do not check crystal range, because human can move
        if (!InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), pos.below())) {
            return;
        }
        if (!checkFillPlace(pos)) {
            return;
        }
        // help fix PacketMine fakeAirMine
        if (supplyFillBlock() == null) {
            supplyFillBlockExecutor.run(100, () -> {
                if (notifySupply.get()) {
                    logI18N("message.module.crystal-aura.no-item.fill-block");
                }
            });
            return;
        }
        Map<BlockPos, BlockState> overrideStates = new HashMap<>();
        overrideStates.put(pos, Blocks.AIR.defaultBlockState());
        BlockPos failBreak = PlayerInteractionAccess.of(mc.gameMode).getCurrentFailBreakPos();
        if (failBreak != null) {
            overrideStates.put(failBreak, Blocks.AIR.defaultBlockState());
        }
        if (!fillIgnoreDamage.get()) {
            Map<Player, Double> damageMap = calculateCrystalDamage(Vec3.atBottomCenterOf(pos), overrideStates);
            if (!isSuitableAttackExplodePos(damageMap)) {
                return;
            }
        }
        FlagEntry<BlockHitResult> hitResult = InteractionTasks.getPlaceSupportingResult(
                pos, airplaceBase.get(), !mode.get().isLegal());
        if (InteractUtils.canInteractAndPlace(mc.player, hitResult)) {
            placeFill(pos, hitResult.val());
        }
    }

    public void onRender3D(Event<Render3D> event) {
        if (enable.get()) {
            RenderUtils.startDrawVirtual(event.context.stack());
            try {
                debugRender.render3D(event.context.stack(), event.context.partialTicks());
            } finally {
                RenderUtils.stopDrawVirtual(event.context.stack());
            }
        }
    }

    private boolean checkSupplies() {
        var endCrystal = supplyCrystalItem() != null;
        if (!endCrystal) {
            supplyEndCrystalExecutor.run(100, () -> {
                if (notifySupply.get()) {
                    logI18N("message.module.crystal-aura.no-item.crystal");
                }
            });
            return false;
        }
        return true;
    }

    private List<EndCrystal> findAttackableCrystals() {
        double attackRange = CombatExtra.INSTANCE.getAttackRange();
        return CombatManager.INSTANCE.trackedEndCrystals.stream()
                .filter(EntityUtils::isEntityValid)
                .filter(crystal -> !CombatManager.INSTANCE.isPendingCrystalRemoval(crystal))
                .filter(crystal -> TargetSelector.INSTANCE.isTargetInRange(crystal, attackRange, 0))
                .toList();
    }

    public boolean tickAttack() {
        maxExistingDamageValue = Double.NEGATIVE_INFINITY;
        boolean skipTick = nextHitSkipDamageTest;
        nextHitSkipDamageTest = false;
        if (currentTarget.isEmpty()) {
            return false;
        }
        boolean attack = false;

        List<Map.Entry<EndCrystal, Map<Player, Double>>> candidates = new ArrayList<>();
        for (EndCrystal crystal : findAttackableCrystals()) {
            if (CombatManager.INSTANCE.isPendingCrystalRemoval(crystal)) {
                continue;
            }
            if (!EntityUtils.isEntityValid(crystal)) {
                continue;
            }
            Map<Player, Double> damageMap = calculateCrystalDamage(crystal.position());
            Map<Player, Double> attackDamageMap =
                    considerInvulnerability.get() ? calculateInvulnerabilityDamageMap(damageMap) : damageMap;
            if (isSuitableExplodePos(damageMap) && (skipTick || isSuitableAttackExplodePos(attackDamageMap))) {
                candidates.add(Map.entry(crystal, attackDamageMap));
            } else {
                // use raw damage
                maxExistingDamageValue = Math.max(maxExistingDamageValue, getBestEnemyDamage(damageMap));
            }
        }
        if (tickAttackExecutor.canRun(attackDelay.get())) {
            candidates.sort(Map.Entry.comparingByValue(this::compareCrystalEntries));
            for (var crystalEntry : candidates) {
                EndCrystal crystal = crystalEntry.getKey();
                debugRender.submit(crystal.getBoundingBox(), ColorUtils.withAlphaInt(Color.MAGENTA, 255));
                tickAttackExecutor.mark();
                if (!CombatManager.INSTANCE.attackCrystal(crystal)) {
                    break;
                }
                attack = true;
            }
        }
        return attack;
    }

    public void refreshTargets() {
        if (TargetSelector.INSTANCE == null) {
            currentTarget = List.of();
            return;
        }
        currentTarget = TargetSelector.INSTANCE.getAttackableEntities(range.get()).stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .filter(EntityUtils::isEntityValid)
                .filter(player -> player != mc.player)
                .toList();
    }

    public boolean tickPendingBase() {
        var iter = pendingBases.iterator();
        while (iter.hasNext()) {
            var plan = iter.next();
            iter.remove();
            BlockPos basePos = plan.option().basePos();
            if (InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), basePos)
                    && isBasePlacedCrystalInAttackRange(basePos)
                    && canPlaceCrystalAtBase(basePos)) {
                placeBlockingAndCrystal(plan);
                return true;
            }
        }
        return false;
    }

    public boolean tickPlace() {
        if (currentTarget.isEmpty()) {
            return false;
        }
        boolean legal = mode.get().isLegal();

        CrystalPlan bestPlan = null;
        double bestEnemyDamage = considerInvulnerability.get() && considerExistingDamage.get()
                ? Math.max(maxExistingDamageValue, Double.NEGATIVE_INFINITY)
                : Double.NEGATIVE_INFINITY;
        double bestSelfDamage = Double.POSITIVE_INFINITY;
        Map<Player, Double> bestDamageMap = Map.of();
        Set<EndCrystal> currentPreRemovals = Set.of();
        boolean canAutoBase = autoBase.get();
        if (canAutoBase) {
            if (supplyItem(Items.OBSIDIAN) == null) {
                supplyBaseExecutor.run(100, () -> {
                    if (notifySupply.get()) {
                        logI18N("message.module.crystal-aura.no-item.base");
                    }
                });
                canAutoBase = false;
            }
        }
        List<EndCrystal> currentInAttackRange = findAttackableCrystals();
        BlockPos currentPlayerPos = mc.player.blockPosition();
        double collisionDamage = -1.0D;
        List<BlockPos> searchBlockPos = interactRangeBlocks.stream()
                .map(currentPlayerPos::offset)
                .filter(s -> InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), s))
                .filter(this::isBasePlacedCrystalInAttackRange)
                .filter(s -> canPlaceCrystal(s.above()))
                .sorted(Comparator.comparingDouble(s -> currentTarget.stream()
                        .map(v -> new AABB(s).distanceToSqr(v.position()))
                        .min(Double::compare)
                        .orElse(Double.POSITIVE_INFINITY)))
                .toList();
        // already has a targeting, just consider about invulerability
        boolean shouldConsiderExisting = bestEnemyDamage >= targetDamageThreshold.get();
        for (BlockPos pos : searchBlockPos) {
            BlockPos crystalPos = pos.above();
            boolean canPlaceCrystal = isCrystalHasBase(crystalPos);
            EndCrystalOption option;
            // filter the can-not-base positions first
            if (canPlaceCrystal) {
                option = new DirectPlace(pos);
            } else if (canAutoBase && isSuitableBasePos(pos)) {
                FlagEntry<BlockHitResult> hitResultEntry =
                        InteractionTasks.getPlaceSupportingResult(pos, airplaceBase.get(), !legal);
                if (InteractUtils.canInteractAndPlace(mc.player, hitResultEntry)
                        && InteractExtra.INSTANCE.isWithinInteractRange(
                                mc.player.position(), hitResultEntry.val().getBlockPos())) {
                    option = new AutoBase(pos, hitResultEntry.val());
                } else {
                    continue;
                }
            } else {
                continue;
            }
            // entity blocking
            Set<EndCrystal> entitySet = new HashSet<>();
            Set<Entity> entitySet2 = new HashSet<>();
            var result = CombatManager.INSTANCE.isCrystalConditionedBlockedByEntity(pos.above(), (check) -> {
                // let entity test pass explosion-removing entities
                if (check instanceof CombatManager.EntityBlock entity) {
                    if (entity.entity() instanceof EndCrystal end && new AABB(pos.above()).contains(end.position())) {
                        // crystal at here
                        return true;
                    }
                    if (entity.entity() instanceof EndCrystal end
                            && TargetSelector.INSTANCE.isWithinAttackRange(mc.player.position(), end)
                            && isSuitableExplodePos(calculateCrystalDamage(end.position()))) {
                        entitySet.add(end);
                        return false;
                    } else if (canExplodeRemoveEntity(entity.entity())) {
                        entitySet2.add(entity.entity());
                        return false;
                    }
                }
                // do not block
                if (check instanceof CombatManager.PendingCrystal pendingCrystal
                        && Objects.equals(pos, pendingCrystal.basePos())) {
                    return true;
                }
                // let the dropping test pass glass
                if (check instanceof CombatManager.PendingItemDrop drop && isTrackedGlassState(drop.dropState())) {
                    return false;
                }
                return null;
            });
            if (!result.isAccepted()) {
                continue;
            }

            CrystalPlan plan = new CrystalPlan(option, Optional.empty());
            Vec3 explosionPos = getCrystalExplosionPos(pos);
            var maxDamageMap = estimateCrystalMaxDamage(explosionPos);
            if (getMaxedPlanValue(getBestEnemyDamage(maxDamageMap)) < bestEnemyDamage) {
                // fast skip, do not waste time on useless point
                continue;
            }
            Map<Player, Double> damageMap = calculateCrystalDamage(explosionPos, getDamageOverrides(plan));
            double enemyDamage = getBestEnemyDamage(damageMap);
            if (getMaxedPlanValue(enemyDamage) < bestEnemyDamage) {
                // fast skip
                continue;
            }
            // block only when it is a attack crystal, avoid duplicated calculation
            if (!isSuitableExplodePos(damageMap) && isSuitableAttackExplodePos(damageMap)) {
                if (!autoBlock.get()) {
                    continue;
                }
                plan = findBlockingPlan(option);
                if (plan == null) {
                    continue;
                }
                damageMap = calculateCrystalDamage(getCrystalExplosionPos(pos), getDamageOverrides(plan));
                if (!isSuitableExplodePos(damageMap)) {
                    continue;
                }
            }

            double selfDamage = damageMap.getOrDefault(mc.player, Double.POSITIVE_INFINITY);
            double damageValue = getPlanValue(plan, enemyDamage);
            if (damageValue > bestEnemyDamage || (damageValue == bestEnemyDamage && selfDamage < bestSelfDamage)) {
                if (!entitySet2.isEmpty()) {
                    collisionDamage = Math.max(damageValue, collisionDamage);
                } else {
                    bestPlan = plan;
                    bestEnemyDamage = damageValue;
                    bestSelfDamage = selfDamage;
                    bestDamageMap = damageMap;
                    currentPreRemovals = entitySet;
                }
            }
        }

        if (bestPlan == null) {
            return false;
        }
        if (!isSuitableAttackExplodePos(bestDamageMap)) {
            if (collisionDamage > bestEnemyDamage) {
                if (!currentPreRemovals.isEmpty()) {
                    for (var re : currentPreRemovals) {
                        if (!CombatManager.INSTANCE.attackCrystal(re)) {
                            return false;
                        }
                    }
                    return false;
                }
                if (!currentInAttackRange.isEmpty()) {
                    for (var re : currentInAttackRange) {
                        if (!CombatManager.INSTANCE.attackCrystal(re)) {
                            return false;
                        }
                    }
                    return false;
                }
                nextHitSkipDamageTest = true;
            } else {
                return false;
            }
        } else if (shouldConsiderExisting) {
            // do not attack if player are during,
            var invulnerable = calculateInvulnerabilityDamageMap(bestDamageMap);
            if (!isSuitableAttackExplodePos(invulnerable)) {
                return false;
            }
        }

        debugRender.submit(new AABB(bestPlan.option().basePos()), ColorUtils.withAlphaInt(Color.MAGENTA, 255));
        if (!currentPreRemovals.isEmpty()) {
            for (var re : currentPreRemovals) {
                if (!CombatManager.INSTANCE.attackCrystal(re)) {
                    return false;
                }
            }
        }
        if (bestPlan.option().needBase()) {
            placeBase(bestPlan);
        } else {
            placeBlockingAndCrystal(bestPlan);
        }
        return true;
    }

    private Map<BlockPos, BlockState> getDamageOverrides(CrystalPlan plan) {
        Map<BlockPos, BlockState> overrides = new HashMap<>();
        if (plan.option().needBase()) {
            overrides.put(plan.option().basePos(), Blocks.OBSIDIAN.defaultBlockState());
        }
        plan.blockingPos().ifPresent(pos -> overrides.put(pos, Blocks.OBSIDIAN.defaultBlockState()));
        return overrides;
    }

    private double getPlanValue(CrystalPlan plan, double enemyDamage) {
        double value = enemyDamage;
        if (plan.option().needBase()) {
            value *= baseReduce.get();
        }
        if (plan.blockingPos().isPresent()) {
            value *= blockReduce.get();
        }
        return value;
    }

    private double getMaxedPlanValue(double enemyDamage) {
        double value = Math.max(enemyDamage, enemyDamage * baseReduce.get());
        return Math.max(value, value * blockReduce.get());
    }

    private CrystalPlan findBlockingPlan(EndCrystalOption option) {
        IndexEntry<ItemStack> blockingItem = supplyBlockingBlock();
        if (blockingItem == null) {
            return null;
        }

        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
        BlockPos crystalPos = option.basePos().above();
        CrystalPlan bestHorizontalPlan = null;
        CrystalPlan bestHorizontalScaffoldPlan = null;
        CrystalPlan bestTopPlan = null;
        double bestHorizontalValue = Double.NEGATIVE_INFINITY;
        double bestHorizontalScaffoldValue = Double.NEGATIVE_INFINITY;
        double bestTopValue = Double.NEGATIVE_INFINITY;
        for (Direction direction : directions) {
            BlockPos blockingPos = crystalPos.relative(direction);
            BlockState blockingState = mc.level.getBlockState(blockingPos);
            if (!(blockingState.isAir() || blockingState.liquid() || blockingState.canBeReplaced())) {
                continue;
            }
            if (InteractionTasks.checkInHead(blockingPos, mc.player.position())
                    || !InteractionTasks.checkPositionPlace(crystalPos, direction, mc.player.position())) {
                continue;
            }
            // check entity and blocking
            if (!CombatManager.INSTANCE.canCubePlace(mc.player, blockingPos)) {
                continue;
            }

            CrystalPlan plan = new CrystalPlan(option, Optional.of(blockingPos));
            boolean directPlace = InteractUtils.canInteractAndPlace(mc.player, getBlockingPlaceResult(plan));
            boolean scaffoldPlace = !directPlace && scaffoldBlock.get() && direction.getAxis() != Direction.Axis.Y;
            if (!directPlace && !scaffoldPlace) {
                continue;
            }

            Map<Player, Double> damageMap =
                    calculateCrystalDamage(getCrystalExplosionPos(option.basePos()), getDamageOverrides(plan));
            if (!isSuitableExplodePos(damageMap)) {
                continue;
            }
            double value = getPlanValue(plan, getBestEnemyDamage(damageMap));
            if (directPlace) {
                if (direction.getAxis() == Direction.Axis.Y) {
                    if (value > bestTopValue) {
                        bestTopPlan = plan;
                        bestTopValue = value;
                    }
                } else if (value > bestHorizontalValue) {
                    bestHorizontalPlan = plan;
                    bestHorizontalValue = value;
                }
            } else if (value > bestHorizontalScaffoldValue) {
                bestHorizontalScaffoldPlan = plan;
                bestHorizontalScaffoldValue = value;
            }
        }
        if (bestHorizontalPlan != null) {
            return bestHorizontalPlan;
        }
        if (bestHorizontalScaffoldPlan != null) {
            return bestHorizontalScaffoldPlan;
        }
        return bestTopPlan;
    }

    private CrystalPlan findGlassBlockingPlan(BlockPos pos) {
        IndexEntry<ItemStack> blockingItem = supplyBlockingBlock();
        if (blockingItem == null) {
            return null;
        }
        if (!InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), pos)) {
            return null;
        }

        CrystalPlan bestOption = null;
        double bestOptionValue = Double.NEGATIVE_INFINITY;
        for (Direction direction : Direction.values()) {
            BlockPos blockingPos = pos.relative(direction);
            // when it is in head, it's too late
            if (InteractionTasks.checkInHead(pos, mc.player.position())
                    || !InteractionTasks.checkPositionPlace(pos, direction, mc.player.position())) {
                continue;
            }

            BlockState blockingState = mc.level.getBlockState(blockingPos);
            if (!blockingState.isAir() && !blockingState.liquid() && !blockingState.canBeReplaced()) {
                continue;
            }
            if (!CombatManager.INSTANCE.canCubePlace(mc.player, blockingPos)) {
                continue;
            }
            CrystalPlan option = new CrystalPlan(new DirectPlace(pos.below()), Optional.of(blockingPos));
            if (!InteractUtils.canInteractAndPlace(mc.player, getBlockingPlaceResult(option))) {
                continue;
            }
            var newDamageMap = calculateCrystalDamage(
                    getCrystalExplosionPos(pos.below()),
                    Map.of(blockingPos, Blocks.OBSIDIAN.defaultBlockState(), pos, Blocks.AIR.defaultBlockState()));
            if (!isSuitableExplodePos(newDamageMap)) {
                continue;
            }

            double optionValue = getBestEnemyDamage(newDamageMap);
            if (optionValue > bestOptionValue) {
                bestOption = option;
                bestOptionValue = optionValue;
            }
        }
        return bestOption;
    }

    private Map<Player, Double> calculateCrystalDamage(Vec3 explosionPos) {
        return calculateCrystalDamage(explosionPos, Map.of());
    }

    private Map<Player, Double> estimateCrystalMaxDamage(Vec3 explosionPos) {
        Map<Player, Double> damageMap = new LinkedHashMap<>();
        if (mc.level == null || mc.player == null) {
            return damageMap;
        }
        for (Player target : currentTarget) {
            if (!EntityUtils.isEntityValid(target) || target == mc.player) {
                continue;
            }
            Vec3 realMovement = getTargetDamageMovement(target);
            damageMap.put(target, (double) ExplosionUtils.calculateExplosionMaxDamage(
                    ExplosionUtils.END_CRYSTAL_POWER, target.getBoundingBox().move(realMovement), explosionPos));
        }
        return damageMap;
    }

    private Map<Player, Double> calculateCrystalDamage(Vec3 explosionPos, Map<BlockPos, BlockState> overrides) {
        Map<Player, Double> damageMap = new LinkedHashMap<>();
        if (mc.level == null || mc.player == null) {
            return damageMap;
        }

        Map<BlockPos, BlockState> selfAccess = new LinkedHashMap<>(overrides);
        var interact = PlayerInteractionAccess.of(mc.gameMode);
        if (interact.getCurrentMiningProgress(null) >= 1.0) {
            selfAccess.put(interact.getCurrentMiningPos(), Blocks.AIR.defaultBlockState());
        }

        damageMap.put(mc.player, (double) ExplosionUtils.calculateExplosionRawDamage(
                ExplosionUtils.END_CRYSTAL_POWER,
                explosionPos,
                mc.player.getBoundingBox(),
                ExplosionUtils.fromWorldWithOverrides(mc.level, selfAccess),
                ExplosionUtils.ALL_TERRAIN));
        ExplosionUtils.BlockStateAccess access = overrides.isEmpty()
                ? ExplosionUtils.fromWorld(mc.level)
                : ExplosionUtils.fromWorldWithOverrides(mc.level, overrides);
        for (Player target : currentTarget) {
            if (!EntityUtils.isEntityValid(target) || target == mc.player) {
                continue;
            }
            Vec3 realMovement = getTargetDamageMovement(target);

            damageMap.put(target, (double) ExplosionUtils.calculateExplosionRawDamage(
                    ExplosionUtils.END_CRYSTAL_POWER,
                    explosionPos,
                    target.getBoundingBox().move(realMovement),
                    access,
                    ExplosionUtils.EXPLOSION_RESISTENCE));
        }
        return damageMap;
    }

    private Vec3 getTargetDamageMovement(Player target) {
        Vec3 targetPos = target.position();
        Vec3 syncedPos = PositionPredict.INSTANCE.getPredictor(target).getCurrentSyncedPos();
        if (syncedPos == null) {
            return Vec3.ZERO;
        }

        Vec3 syncMovement = syncedPos.subtract(targetPos);
        if (!usePredictor.get()) {
            return syncMovement;
        }

        Vec3 predictionPos =
                PositionPredict.INSTANCE.attackPredictArgument.get().predict(target);
        if (predictionPos == null || predictionPos.distanceToSqr(syncedPos) <= MathUtils.s2(0.5)) {
            return syncMovement;
        }

        Vec3 predictedMovement =
                MovTasks.simulateMovement(target, syncedPos, predictionPos.subtract(syncedPos), false);
        return syncMovement.add(predictedMovement);
    }

    private Vec3 getCrystalExplosionPos(BlockPos basePos) {
        return Vec3.atBottomCenterOf(basePos.above());
    }

    private boolean isSuitableAttackExplodePos(Map<Player, Double> damageMap) {
        if (damageMap == null || damageMap.isEmpty()) {
            return false;
        }
        return getBestEnemyDamage(damageMap) >= targetDamageThreshold.get();
    }

    private boolean isSuitableExplodePos(Map<Player, Double> damageMap) {
        if (damageMap == null || damageMap.isEmpty()) {
            return false;
        }
        double selfDamage = damageMap.getOrDefault(mc.player, Double.POSITIVE_INFINITY);
        float finalDamage = DamageUtils.getFinalDamage(
                mc.player,
                (float) selfDamage,
                DamageUtils.createDamageSource(DamageTypes.PLAYER_EXPLOSION, null, mc.player));
        if (finalDamage > selfFinalDamageThreshold.get()) {
            return false;
        }
        return true;
    }

    private double getBestEnemyDamage(Map<Player, Double> damageMap) {
        double best = Double.NEGATIVE_INFINITY;
        for (Player target : currentTarget) {
            if (!EntityUtils.isEntityValid(target) || target == mc.player) {
                continue;
            }
            Double damage = damageMap.get(target);
            if (damage != null && damage > best) {
                best = damage;
            }
        }
        return best;
    }

    private Map<Player, Double> calculateInvulnerabilityDamageMap(Map<Player, Double> damageMap) {
        if (!considerInvulnerability.get()) return damageMap;
        Map<Player, Double> adjustedDamageMap = new LinkedHashMap<>(damageMap);
        for (var entry : adjustedDamageMap.entrySet()) {
            CombatManager.DamageTracker tracker = CombatManager.INSTANCE.getDamageTracker(entry.getKey());
            if (tracker == null
                    || tracker.getInvulnerabilityTicks() <= 0
                    || tracker.getInvulnerabilityTicks() <= 10 - invulnerabilityConsiderTicks.get()) {
                continue;
            }
            Double damage = entry.getValue();
            if (damage == null) continue;
            double adjustedDamage = Math.min(
                    Math.min(
                            (damage.floatValue() - DamageUtils.getRawDamageByDifficulty(mc.level, tracker.lastDamage))
                                    * invulnerabilityDamageMultiplier.get(),
                            damage),
                    0);
            entry.setValue(adjustedDamage);
        }
        return adjustedDamageMap;
    }

    private boolean isBasePlacedCrystalInAttackRange(BlockPos basePos) {
        Vec3 place = Vec3.atBottomCenterOf(basePos.above());
        AABB estimatedEndCrystalBox =
                new AABB(place.x - 1, place.y, place.z - 1, place.x + 1, place.y + 2, place.z + 1);
        return estimatedEndCrystalBox.distanceToSqr(mc.player.getEyePosition())
                <= MathUtils.s2(CombatExtra.INSTANCE.getAttackRange());
    }

    private boolean isCrystalBase(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private boolean canExplodeRemoveEntity(Entity entity) {
        return entity instanceof EndCrystal
                || entity instanceof BlockAttachedEntity
                || entity instanceof ExperienceOrb
                || entity instanceof ArmorStand;
    }

    private boolean canPlaceCrystal(BlockPos crystalPos) {
        return canPlaceCrystal(crystalPos, Map.of());
    }

    private boolean canPlaceCrystal(BlockPos crystalPos, Map<BlockPos, BlockState> overrides) {
        return overrides
                .getOrDefault(crystalPos, mc.level.getBlockState(crystalPos))
                .isAir();
    }

    private boolean isCrystalHasBase(BlockPos crystalPos) {
        return isCrystalHasBase(crystalPos, Map.of());
    }

    private boolean isCrystalHasBase(BlockPos crystalPos, Map<BlockPos, BlockState> overrides) {
        return isCrystalBase(overrides.getOrDefault(crystalPos.below(), mc.level.getBlockState(crystalPos.below())));
    }

    private boolean canPlaceCrystalAtPos(BlockPos crystalPos, Map<BlockPos, BlockState> overrides) {
        return canPlaceCrystal(crystalPos, overrides)
                && isCrystalHasBase(crystalPos, overrides)
                && CombatManager.INSTANCE
                        .isCrystalConditionedBlockedByEntity(crystalPos, (v) -> {
                            if (v instanceof CombatManager.PendingItemDrop drop
                                    && isTrackedGlassState(drop.dropState())) {
                                return false;
                            }
                            return null;
                        })
                        .isAccepted();
    }

    private boolean canPlaceCrystalAtBase(BlockPos basePos) {
        return canPlaceCrystalAtPos(basePos.above(), Map.of());
    }

    private boolean isSuitableBasePos(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return (state.isAir() || state.liquid() || state.canBeReplaced())
                && CombatManager.INSTANCE.canBlockPlace(mc.player, pos, Blocks.OBSIDIAN.defaultBlockState())
                && canPlaceCrystalAtPos(pos.above(), Map.of(pos, Blocks.OBSIDIAN.defaultBlockState()));
    }

    private IndexEntry<ItemStack> supplyItem(Item item) {
        return InventoryUtils.findPlayerItem(
                stack -> stack.is(item), ghostHand.get().getSearchSize(offhand.get()), true, false);
    }

    private boolean isFillBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || !fillWhiteList.get().test(stack.getItem())) {
            return false;
        }
        return true;
    }

    private IndexEntry<ItemStack> supplyFillBlock() {
        return InventoryUtils.findPlayerItem(
                this::isFillBlock, ghostHand.get().getSearchSize(offhand.get()), true, false);
    }

    private IndexEntry<ItemStack> supplyCrystalItem() {
        return InventoryUtils.findPlayerItem(
                stack -> stack.is(Items.END_CRYSTAL), ghostHand.get().getSearchSize(offhand.get()), true, false);
    }

    private IndexEntry<ItemStack> supplyBlockingBlock() {
        return InventoryUtils.findPlayerItem(
                this::isBlockingBlock, ghostHand.get().getSearchSize(false), true, false);
    }

    private boolean isBlockingBlock(ItemStack stack) {
        EntrySet<Item> allowedBlocks = blockingBlock.get();
        return allowedBlocks != null && stack.getItem() instanceof BlockItem && allowedBlocks.test(stack.getItem());
    }

    private boolean isTrackedGlassState(BlockState state) {
        if (state == null || state.isAir() || state.liquid()) {
            return false;
        }
        Item item = state.getBlock().asItem();
        return item != Items.AIR && fillWhiteList.get().test(item);
    }

    private boolean tickTrackedGlasses() {
        if (!enable.get()) {
            trackedGlasses.clear();
            return false;
        }
        if (trackedGlasses.isEmpty()) {
            return false;
        }
        if (currentTarget.isEmpty()) {
            trackedGlasses.clear();
            return false;
        }
        trackedGlasses.keySet().removeIf(s -> s.distToCenterSqr(mc.player.position()) > MathUtils.s2(8));
        var iter = trackedGlasses.entrySet().iterator();
        boolean removal = false;
        while (iter.hasNext()) {
            var entry = iter.next();
            BlockPos pos = entry.getKey();
            BlockState state = mc.level.getBlockState(pos);

            if (isTrackedGlassState(state)) {
                Map<BlockPos, BlockState> overrides =
                        Map.of(pos, Blocks.AIR.defaultBlockState(), pos.below(), Blocks.OBSIDIAN.defaultBlockState());
                Map<Player, Double> damageMap = calculateCrystalDamage(Vec3.atBottomCenterOf(pos), overrides);
                if (fillIgnoreDamage.get() || isSuitableAttackExplodePos(damageMap)) {
                    if (!isSuitableExplodePos(damageMap) && autoBlock.get()) {
                        var blocking = findGlassBlockingPlan(pos);
                        if (blocking != null) {
                            placeGlassBlocking(blocking);
                        }
                    }
                    continue;
                }
            }
            iter.remove();
            if (state.isAir()) {
                if (autoFill.get()
                        && canFillPos(pos)
                        && InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), pos.below())) {
                    if (tryPlaceCrystalAtGlass(pos, Map.of())) {
                        removal = true;
                    }
                }
            }
        }
        return removal;
    }

    private boolean tryPlaceCrystal(BlockPos basePos) {
        if (!canPlaceCrystalAtBase(basePos)) {
            return false;
        }
        IndexEntry<ItemStack> entry = supplyCrystalItem();
        if (entry == null) {
            return false;
        }
        BlockHitResult hitResult = InteractionTasks.createHitResult(basePos, mc.player.position());
        if (!InteractionTasks.canHandleInteract(mode.get(), hitResult)) {
            return false;
        }
        Runnable callback = InvExtra.INSTANCE.swapItemToHand(entry.index(), offhand.get(), ghostHand.get());
        if (callback == null) {
            return false;
        }
        boolean completed = InteractionTasks.handlePlaceMode(
                mode.get(), hitResult, offhand.get() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, swingHand.get());
        if (!completed) {
            InteractionTasks.finishInteractGhostHand(mode.get(), callback);
            return false;
        }
        CombatManager.INSTANCE.markCrystalPlace(basePos);
        InteractionTasks.finishInteractGhostHand(mode.get(), callback);
        return true;
    }

    private boolean placeBase(CrystalPlan plan) {
        if (!(plan.option() instanceof AutoBase autoBase)) {
            return false;
        }
        IndexEntry<ItemStack> entry = supplyItem(Items.OBSIDIAN);
        if (entry == null) {
            return false;
        }
        if (!InteractionTasks.canHandleInteract(mode.get(), autoBase.basePlaceResult())) {
            return false;
        }
        Runnable callback = InvExtra.INSTANCE.swapItemToHand(entry.index(), offhand.get(), ghostHand.get());
        if (callback == null) {
            return false;
        }
        boolean completed = InteractionTasks.handlePlaceMode(
                mode.get(),
                autoBase.basePlaceResult(),
                offhand.get() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                swingHand.get());
        InteractionTasks.finishInteractGhostHand(mode.get(), callback);
        if (completed
                && zeroTickBase.get()
                && DisablerManager.INSTANCE.isMultiRotPlaceCheckDisabled(
                        mode.get().canMultiRotPlace())) {
            placeBlockingAndCrystal(plan);
            return true;
        }
        pendingBases.add(plan);
        return true;
    }

    private FlagEntry<BlockHitResult> getBlockingPlaceResult(CrystalPlan plan) {
        Optional<BlockPos> blockingPos = plan.blockingPos();
        if (blockingPos.isEmpty()) {
            return null;
        }
        return InteractionTasks.getPlaceSupportingResult(
                blockingPos.get(), airplaceBase.get(), !mode.get().isLegal());
    }

    private boolean canPlaceBlockingBlockAtHit(CrystalPlan plan, ItemStack stack, BlockHitResult hitResult) {
        BlockPos blockingPos = plan.blockingPos().orElse(null);
        if (blockingPos == null
                || !(stack.getItem() instanceof BlockItem)
                || InteractionTasks.checkInHead(blockingPos, mc.player.position())
                || !InteractUtils.canInteractAndPlace(mc.player, new FlagEntry<>(false, hitResult))) {
            return false;
        }
        BlockState currentState = mc.level.getBlockState(blockingPos);
        if (!(currentState.isAir() || currentState.liquid() || currentState.canBeReplaced())) {
            return false;
        }
        BlockState placementState = InteractUtils.getBlockPlacement(mc.player, InteractionHand.MAIN_HAND, stack, hitResult);
        return placementState != null && CombatManager.INSTANCE.canBlockPlace(mc.player, blockingPos, placementState);
    }

    private boolean placeBlockingAndCrystal(CrystalPlan plan) {
        if (plan.blockingPos().isEmpty()) {
            return tryPlaceCrystal(plan.option().basePos());
        }
        if (blockingQueue != null || supplyBlockingBlock() == null || !canContinuePlaceBlockingBlock(plan)) {
            return false;
        }

        BlockPos basePos = plan.option().basePos();
        BlockPos targetPos = plan.blockingPos().orElseThrow();
        boolean scaffold = !InteractUtils.canInteractAndPlace(mc.player, getBlockingPlaceResult(plan));
        BlockPos scaffoldPos = targetPos.below();
        if (scaffold
                && (!scaffoldBlock.get()
                        || scaffoldPos.getY() != basePos.getY()
                        || basePos.distSqr(scaffoldPos) != 1
                        || !CombatManager.INSTANCE.canCubePlace(mc.player, scaffoldPos)
                        || !InteractionTasks.canHandleInteract(mode.get(), getScaffoldPlaceResult(plan)))) {
            return false;
        }

        var placeMode = mode.get();
        List<InteractRequest> requests = new ArrayList<>(scaffold ? 2 : 1);
        if (scaffold) {
            requests.add(new InteractRequestImpl(this::isBlockingBlock, (interactionQueue, itemMatched) -> {
                if (!itemMatched
                        || !canContinuePlaceBlockingBlock(plan)
                        || !CombatManager.INSTANCE.canCubePlace(mc.player, scaffoldPos)) {
                    return interactionQueue.abort();
                }
                if (InteractUtils.canInteractAndPlace(mc.player, getBlockingPlaceResult(plan))) {
                    return interactionQueue.success();
                }
                BlockHitResult hitResult = getScaffoldPlaceResult(plan);
                return InteractionTasks.canHandleInteract(placeMode, hitResult)
                        ? interactionQueue.success(hitResult)
                        : interactionQueue.abort();
            }));
        }
        requests.add(blockingPlaceRequest(plan));
        return enqueueBlockingRequests(plan, requests, true);
    }

    private boolean placeGlassBlocking(CrystalPlan plan) {
        if (blockingQueue != null
                || supplyBlockingBlock() == null
                || !canContinuePlaceBlockingBlock(plan)
                || !InteractUtils.canInteractAndPlace(mc.player, getBlockingPlaceResult(plan))) {
            return false;
        }
        return enqueueBlockingRequests(plan, List.of(blockingPlaceRequest(plan)), false);
    }

    private InteractRequest blockingPlaceRequest(CrystalPlan plan) {
        return new InteractRequestImpl(
                stack -> {
                    if (!isBlockingBlock(stack)) return false;
                    FlagEntry<BlockHitResult> hitResult = getBlockingPlaceResult(plan);
                    return InteractUtils.canInteractAndPlace(mc.player, hitResult)
                            && canPlaceBlockingBlockAtHit(plan, stack, hitResult.val());
                },
                (interactionQueue, itemMatched) -> {
                    if (!itemMatched || !canContinuePlaceBlockingBlock(plan)) {
                        return interactionQueue.abort();
                    }
                    FlagEntry<BlockHitResult> hitResult = getBlockingPlaceResult(plan);
                    return InteractUtils.canInteractAndPlace(mc.player, hitResult)
                            ? interactionQueue.success(hitResult.val())
                            : interactionQueue.abort();
                });
    }

    private boolean enqueueBlockingRequests(CrystalPlan plan, List<InteractRequest> requests, boolean placeCrystal) {
        var placeMode = mode.get();
        boolean zeroTick = zeroTickBlock.get()
                && DisablerManager.INSTANCE.isMultiRotPlaceCheckDisabled(placeMode.canMultiRotPlace());
        InteractionQueue queue = new InteractionQueue(
                zeroTick ? 64 : 1,
                InteractionHand.MAIN_HAND,
                ghostHand.get(),
                airplaceBase.get(),
                placeMode,
                swingHand.get());
        queue.enqueueAll(requests);
        blockingQueue = queue;
        blockingPlan = plan;
        placeCrystalAfterBlocking = placeCrystal;
        if (zeroTick) {
            queue.tick();
            return queue.isEmpty() ? finishBlockingQueue() : !queue.isTerminate();
        }
        return true;
    }

    private boolean tickBlockingQueue() {
        blockingQueue.tick();
        return blockingQueue.isEmpty() && finishBlockingQueue();
    }

    private boolean finishBlockingQueue() {
        if (!blockingQueue.isEmpty()) return false;
        CrystalPlan plan = blockingPlan;
        boolean placeCrystal = placeCrystalAfterBlocking;
        boolean success = blockingQueue.isSuccess();
        blockingQueue = null;
        blockingPlan = null;
        placeCrystalAfterBlocking = false;
        return success && (!placeCrystal || tryPlaceCrystal(plan.option().basePos()));
    }

    private void clearBlockingQueue() {
        if (blockingQueue == null) return;
        blockingQueue.clear();
        if (blockingQueue.isEmpty()) {
            blockingQueue = null;
            blockingPlan = null;
            placeCrystalAfterBlocking = false;
        }
    }

    private boolean canContinuePlaceBlockingBlock(CrystalPlan plan) {
        BlockState stateBase = mc.level.getBlockState(plan.option().basePos());
        if (!isCrystalBase(stateBase)) {
            return false;
        }
        if (plan.blockingPos().isEmpty()) return false;
        if (!CombatManager.INSTANCE.canCubePlace(mc.player, plan.blockingPos().get())) {
            return false;
        }
        BlockState targetState = mc.level.getBlockState(plan.blockingPos().get());
        if (!targetState.isAir() && !targetState.liquid() && !targetState.canBeReplaced()) {
            return false;
        }
        return true;
    }

    private Direction getBlockingDirection(CrystalPlan plan) {
        BlockPos blockingPos = plan.blockingPos().orElse(null);
        if (blockingPos == null) {
            return null;
        }
        BlockPos crystalPos = plan.option().basePos().above();
        for (Direction direction : Direction.values()) {
            if (crystalPos.relative(direction).equals(blockingPos)) {
                return direction;
            }
        }
        return null;
    }

    private BlockHitResult getScaffoldPlaceResult(CrystalPlan plan) {
        Direction direction = getBlockingDirection(plan);
        if (direction == null || direction.getAxis() == Direction.Axis.Y) {
            return null;
        }
        BlockPos basePos = plan.option().basePos();
        BlockPos blockingPos = plan.blockingPos().orElse(null);
        if (blockingPos == null || !blockingPos.below().equals(basePos.relative(direction))) {
            return null;
        }
        return new BlockHitResult(Vec3.atCenterOf(basePos).relative(direction, 0.5D), direction, basePos, false);
    }

    private boolean placeFill(BlockPos pos, BlockHitResult hitResult) {
        IndexEntry<ItemStack> entry = supplyFillBlock();
        if (entry == null) {
            return false;
        }
        if (!InteractionTasks.canHandleInteract(mode.get(), hitResult)) {
            return false;
        }
        Runnable callback = InvExtra.INSTANCE.swapItemToHand(entry.index(), offhand.get(), ghostHand.get());
        if (callback == null) {
            return false;
        }
        mc.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        boolean completed = InteractionTasks.handlePlaceMode(
                mode.get(), hitResult, offhand.get() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, swingHand.get());
        InteractionTasks.finishInteractGhostHand(mode.get(), callback);
        if (completed) {
            trackedGlasses.put(pos, Tasks.getTick());
        }
        return completed;
    }

    private boolean canFillPos(BlockPos crystalPos) {
        BlockState baseState = mc.level.getBlockState(crystalPos.below());
        boolean availableBase = baseState.is(Blocks.OBSIDIAN) || baseState.is(Blocks.BEDROCK);
        if (!availableBase && autoBase.get()) {
            if (baseState.isAir()) {
                var re = InteractionTasks.getPlaceSupportingResult(
                        crystalPos.below(), airplaceBase.get(), !mode.get().isLegal());
                if (InteractUtils.canInteractAndPlace(mc.player, re)
                        && CombatManager.INSTANCE.canBlockPlace(
                                mc.player, crystalPos.below(), Blocks.OBSIDIAN.defaultBlockState())) {
                    availableBase = true;
                }
            }
        }
        return availableBase
                && !mc.level.getBlockState(crystalPos).liquid()
                && CombatManager.INSTANCE.canCubePlace(mc.player, crystalPos);
    }

    public void onPrePacketMine(Event<BlockBreak> eventPreMine) {
        if (!enable.get()
                || eventPreMine.isCancelled()
                || !eventPreMine.canCancel()
                || eventPreMine.context().stage() != BlockBreak.Stage.PRE) {
            return;
        }
        BlockPos pos = eventPreMine.context().blockPos();
        Integer placeTick = trackedGlasses.get(pos);
        if (placeTick == null) {
            return;
        }
        if (supplyCrystalItem() == null) {
            eventPreMine.cancel();
            return;
        }
        // tracked glass
        if (SequencedActionManager.INSTANCE
                .getCurrentPendingBlockPlace()
                .anyMatch(s -> s.placingBlockPos().isPresent()
                        && Objects.equals(pos, s.placingBlockPos().get()))) {
            eventPreMine.cancel();
            return;
        }
        BlockPos basePos = pos.below();
        boolean olderThanTwoTick = Tasks.getTick() - placeTick >= fillMinWaitTime.get();
        Map<BlockPos, BlockState> overrides = Map.of(pos, Blocks.AIR.defaultBlockState());
        Map<Player, Double> damageMap;
        if (!olderThanTwoTick || !InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), basePos)) {
            eventPreMine.cancel();
            return;
        }
        boolean afterDropTicks = Tasks.getTick() - placeTick >= fillMaxWaitTime.get();
        boolean canPlaceCrystal = isCrystalHasBase(pos, overrides)
                && CombatManager.INSTANCE
                        .isCrystalConditionedBlockedByEntity(pos, (check) -> {
                            if (!afterDropTicks) {
                                return null;
                            }
                            if (check instanceof CombatManager.PendingItemDrop) {
                                return false;
                            }
                            if (check instanceof CombatManager.EntityBlock entity
                                    && entity.entity() instanceof ItemEntity item) {
                                return false;
                            }
                            return null;
                        })
                        .isAccepted();
        if (!canPlaceCrystal) {
            eventPreMine.cancel();
            return;
        }
        if (blockingQueue != null) {
            eventPreMine.cancel();
            return;
        }
        if (!isSuitableExplodePos(damageMap = calculateCrystalDamage(Vec3.atBottomCenterOf(pos), overrides))) {
            if (isSuitableAttackExplodePos(damageMap) && autoBlock.get()) {
                // try figure out the blocking now
                var blocking = findGlassBlockingPlan(pos);
                if (blocking == null || !placeGlassBlocking(blocking) || blockingQueue != null) {
                    eventPreMine.cancel();
                    return;
                }
            } else {
                eventPreMine.cancel();
                return;
            }
        }
    }

    private boolean checkFillPlace(BlockPos pos) {
        for (var dir : MathUtils.HORIZONTALS) {
            BlockPos nearBox = pos.relative(dir);
            AABB nearBb = new AABB(nearBox);
            if (mc.level.getBlockState(nearBox).getBlock().getExplosionResistance() < 600
                    && currentTarget.stream().anyMatch(s -> s.getBoundingBox().intersects(nearBb))) {
                return true;
            }
        }
        return false;
    }

    public void onPostPacketMine(Event<BlockBreak> eventPostMine) {
        if (!enable.get() || eventPostMine.context().stage() != BlockBreak.Stage.POST) {
            return;
        }
        BlockPos pos = eventPostMine.context().blockPos();
        float progress = eventPostMine.context().progress();
        if (progress <= 0.7F) {
            return;
        }
        if (currentTarget.isEmpty()) {
            return;
        }
        // current available Crystals
        if (!autoFill.get()) {
            return;
        }
        if (eatingAbort.get() && mc.player.isUsingItem()) {
            return;
        }
        if (!canFillPos(pos)) {
            return;
        }
        // within range check, do not check crystal range, because human can move
        if (!InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), pos.below())) {
            return;
        }
        pos = pos.immutable();
        // do not write shit into prediction
        Map<BlockPos, BlockState> overrides = new HashMap<>();
        overrides.put(pos, Blocks.AIR.defaultBlockState());
        // help fix PacketMine fakeAirMine
        mc.level.setServerVerifiedBlockState(pos, Blocks.AIR.defaultBlockState(), 3);

        if (trackedGlasses.containsKey(pos)) {
            if (tryPlaceCrystalAtGlass(pos, overrides)) {
                trackedGlasses.remove(pos);
                return;
            }
        }
        fillWorkingTick = true;
    }

    private boolean tryPlaceCrystalAtGlass(BlockPos pos, Map<BlockPos, BlockState> overrides) {
        if (canPlaceCrystalAtPos(pos, overrides)) {
            if (supplyCrystalItem() != null) {
                BlockPos checkBaseAndEntity = pos.below();
                tryPlaceCrystal(checkBaseAndEntity);
                return true;
            }
        }
        return false;
    }

    private int compareCrystalEntries(Map<Player, Double> first, Map<Player, Double> second) {
        double firstEnemy = getBestEnemyDamage(first);
        double secondEnemy = getBestEnemyDamage(second);
        int enemyCompare = Double.compare(secondEnemy, firstEnemy);
        if (enemyCompare != 0) {
            return enemyCompare;
        }
        double firstSelf = first.getOrDefault(mc.player, Double.POSITIVE_INFINITY);
        double secondSelf = second.getOrDefault(mc.player, Double.POSITIVE_INFINITY);
        return Double.compare(firstSelf, secondSelf);
    }

    private void onRequestCombatService(Event<CombatManager.Service> event) {
        if (enable.get()) {
            event.context().enableExplosiveSearch(true);
        }
    }

    public void onPreset(Event<EventContainer<ModulePreset>> event) {
        ModulePreset preset = event.context.getValue();
        if (preset.isAirplacePreset()) airplaceBase.set(preset.getAirplace(airplaceBase.get()));
        if (preset.isSwingPlacePreset()) swingHand.set(preset.getSwingPlace(swingHand.get()));
        if (preset.isMultitaskPreset()) eatingAbort.set(preset.getEatingAbort(eatingAbort.get()));
        if (!preset.isOptionPreset()) {
            mode.set(LegalInteractMode.getFromPreset(preset));
            airplaceBase.set(!preset.hasAC());
        }
    }

    public record CrystalPlan(EndCrystalOption option, Optional<BlockPos> blockingPos) {}

    public static sealed interface EndCrystalOption permits AutoBase, DirectPlace {
        public BlockPos basePos();

        public boolean needBase();
    }

    public static record AutoBase(BlockPos basePos, BlockHitResult basePlaceResult) implements EndCrystalOption {

        @Override
        public boolean needBase() {
            return true;
        }
    }

    public static record DirectPlace(BlockPos basePos) implements EndCrystalOption {

        @Override
        public boolean needBase() {
            return false;
        }
    }
}
