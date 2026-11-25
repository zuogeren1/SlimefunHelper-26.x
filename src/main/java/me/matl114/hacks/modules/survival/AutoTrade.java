package me.matl114.hacks.modules.survival;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import me.matl114.accessors.access.MerchantScreenAccess;
import me.matl114.accessors.interfaces.EntityInventory;
import me.matl114.accessors.interfaces.MetadataHolder;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.SurvivalTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.combat.TargetSelector;
import me.matl114.hacks.modules.interact.Interact;
import me.matl114.hacks.modules.interact.SequencedActionManager;
import me.matl114.hacks.utils.ClientUtils;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.hacks.utils.move.AdjustmentSchedular;
import me.matl114.hacks.utils.move.PathingSchedular;
import me.matl114.hacks.utils.move.goal.GoalNearBlockPos;
import me.matl114.hacks.utils.move.goal.IPathGoal;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.MathUtils;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public class AutoTrade extends BaseModule {
    private static final String COOLDOWN_METADATA_KEY = "slimefunhelper:auto_trade/cooldown_tick";
    private static final int SCREEN_OPEN_TIMEOUT = 40;
    private static final double PATH_STOP_DISTANCE = 1.25D;

    public AutoTrade() {
        super("AutoTrade");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "survival-interact-utils.auto-trade");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final IntRef searchRadius = intBuilder(root.add("search-radius"))
            .defaultValue(32)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final IntRef cooldown = intBuilder(root.add("cooldown"))
            .defaultValue(20)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final FlagRef baritoneControl =
            flagBuilder(root.add("baritone-control")).build();

    public final FlagRef adjustmentControl =
            flagBuilder(root.add("adjustment-control")).build();

    public final NBTRef<EntrySet<VillagerProfession>> villagerTypes = builder(
                    root.add("villager-types"), EntrySet.<VillagerProfession>parameter())
            .defaultValue(new EntrySet<>(new Regex("^(?!none$|nitwit$).*"), BuiltInRegistries.VILLAGER_PROFESSION))
            .build();

    public final NBTRef<EntrySet<Item>> tradeItemTypes = builder(
                    root.add("trade-item-types"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.ITEM, List.of()))
            .build();

    private final PathingSchedular pathingSchedular = new PathingSchedular();
    private final AdjustmentSchedular adjustmentSchedular = new AdjustmentSchedular();

    private Villager pendingVillager;
    private TradeTarget pendingTarget;
    private TradeTarget currentTarget;
    private int pendingInteractionTick = Integer.MIN_VALUE;
    private int closeScreenTick = Integer.MIN_VALUE;

    /**
     * The line is deliberately kept in memory only. It is a route cache, not user/world data.
     */
    private TradeLine tradeLine;

    private int lineCursor;
    private int lineStep = 1;

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onPreInputEvent);
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        clearState();
    }

    private void clearState() {
        closePendingScreen();
        pendingVillager = null;
        pendingTarget = null;
        currentTarget = null;
        pendingInteractionTick = Integer.MIN_VALUE;
        closeScreenTick = Integer.MIN_VALUE;
        tradeLine = null;
        lineCursor = 0;
        lineStep = 1;
        pathingSchedular.disable();
    }

    private void onPreInputEvent(Event<Void> event) {
        if (checkNull() || !enable.get()) {
            return;
        }

        int tick = Tasks.getTick();
        if (closeScreenTick != Integer.MIN_VALUE) {
            if (tick >= closeScreenTick) {
                finishPendingInteraction();
            }
            tickSchedulers();
            return;
        }

        if (pendingVillager != null) {
            if (processPendingTrade(tick)) {
                tickSchedulers();
                return;
            }
            if (tick - pendingInteractionTick >= SCREEN_OPEN_TIMEOUT) {
                finishPendingInteraction();
            }
            tickSchedulers();
            return;
        }

        if (ClientUtils.getScreen(mc) != null) {
            tickSchedulers();
            return;
        }

        refreshTradeLine();
        currentTarget = findTarget();
        if (currentTarget != null
                && !pathingSchedular.isPathing()
                && isWithinInteractionRange(currentTarget.villager())
                && Interact.INSTANCE != null
                && !SequencedActionManager.INSTANCE
                        .getEntityInteractTracker()
                        .isWaitingResponse(currentTarget.villager())) {
            boolean completed = Interact.INSTANCE.interactEntity(currentTarget.villager());
            SequencedActionManager.INSTANCE.getEntityInteractTracker().postInteract(currentTarget.villager());
            if (completed) {
                markCooldown(currentTarget.villager(), tick);
                pendingVillager = currentTarget.villager();
                pendingTarget = currentTarget;
                pendingInteractionTick = tick;
            }
        }
        tickSchedulers();
    }

    private void tickSchedulers() {
        if (mc.player == null) {
            return;
        }
        pathingSchedular.tickPathing(mc.player);
        adjustmentSchedular.tickAdjustment(mc.player);
    }

    private boolean processPendingTrade(int tick) {
        if (!(ClientUtils.getScreen(mc) instanceof MerchantScreen screen) || !isPendingScreen(screen)) {
            return false;
        }

        MerchantMenu handler = screen.getMenu();
        MerchantOffers offers = handler.getOffers();
        if (offers.isEmpty()) {
            return false;
        }

        int offerIndex = findOffer(offers);
        if (offerIndex >= 0) {
            MerchantScreenAccess.of(screen).setSelectedIndex(offerIndex);
            mc.gameMode.handleContainerInput(handler.containerId, 2, 1, ContainerInput.PICKUP, mc.player);
        }
        closeScreenTick = tick + 1;
        return true;
    }

    private void finishPendingInteraction() {
        closePendingScreen();
        advanceLineAfter(pendingTarget);
        pendingVillager = null;
        pendingTarget = null;
        currentTarget = null;
        pendingInteractionTick = Integer.MIN_VALUE;
        closeScreenTick = Integer.MIN_VALUE;
    }

    private boolean isPendingScreen(MerchantScreen screen) {
        return screen instanceof EntityInventory<?> inventory
                && inventory.getOwner() instanceof Villager owner
                && owner == pendingVillager;
    }

    private void closePendingScreen() {
        if (pendingVillager != null
                && ClientUtils.getScreen(mc) instanceof MerchantScreen screen
                && isPendingScreen(screen)) {
            screen.onClose();
        }
    }

    private int findOffer(MerchantOffers offers) {
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            if (!offer.isOutOfStock()
                    && tradeItemTypes.get().test(offer.getResult().getItem())) {
                return i;
            }
        }
        return -1;
    }

    private void refreshTradeLine() {
        if (tradeLine != null && isTradeLineValid()) {
            return;
        }

        tradeLine = findTradeLine(collectTradeTargets());
        if (tradeLine == null) {
            lineCursor = 0;
            lineStep = 1;
            return;
        }

        int last = tradeLine.targets().size() - 1;
        if (distanceToPlayer(tradeLine.targets().get(last))
                < distanceToPlayer(tradeLine.targets().get(0))) {
            lineCursor = last;
            lineStep = -1;
        } else {
            lineCursor = 0;
            lineStep = 1;
        }
    }

    private List<TradeTarget> collectTradeTargets() {
        return mc.level
                .getEntities(
                        EntityTypes.VILLAGER,
                        mc.player.getBoundingBox().inflate(searchRadius.get()),
                        this::isTradeCandidate)
                .stream()
                .map(this::createTradeTarget)
                .toList();
    }

    private TradeTarget findTarget() {
        if (tradeLine != null) {
            return findLineTarget();
        }
        return collectTradeTargets().stream()
                .filter(target -> !isOnCooldown(target.villager()))
                .min(Comparator.comparingDouble(this::distanceToPlayer))
                .orElse(null);
    }

    private TradeTarget findLineTarget() {
        if (tradeLine.targets().isEmpty()) {
            return null;
        }
        for (int i = 0; i < tradeLine.targets().size(); i++) {
            TradeTarget target = tradeLine.targets().get(lineCursor);
            if (!isOnCooldown(target.villager()) && isTradeCandidate(target.villager())) {
                return target;
            }
            advanceLineCursor();
        }
        return null;
    }

    private boolean isTradeLineValid() {
        for (TradeTarget target : tradeLine.targets()) {
            if (!isTradeCandidate(target.villager())) {
                return false;
            }
            BlockPos workstation = findWorkStation(target.villager());
            BlockPos pathPos = workstation == null ? null : findWorkStationOuterPos(target.villager(), workstation);
            if (!Objects.equals(workstation, target.workstation()) || !Objects.equals(pathPos, target.pathPos())) {
                return false;
            }
        }
        return true;
    }

    private TradeTarget createTradeTarget(Villager villager) {
        BlockPos workstation = findWorkStation(villager);
        BlockPos pathPos = workstation == null ? null : findWorkStationOuterPos(villager, workstation);
        return new TradeTarget(villager, workstation, pathPos, -1);
    }

    private TradeLine findTradeLine(List<TradeTarget> candidates) {
        Map<LineKey, TreeMap<Integer, TradeTarget>> groups = new HashMap<>();
        for (TradeTarget target : candidates) {
            if (target.pathPos() == null) {
                continue;
            }
            BlockPos pos = target.pathPos();
            groups.computeIfAbsent(new LineKey(true, pos.getZ(), pos.getY()), key -> new TreeMap<>())
                    .putIfAbsent(pos.getX(), target);
            groups.computeIfAbsent(new LineKey(false, pos.getX(), pos.getY()), key -> new TreeMap<>())
                    .putIfAbsent(pos.getZ(), target);
        }

        List<TradeTarget> best = List.of();
        for (TreeMap<Integer, TradeTarget> group : groups.values()) {
            best = chooseBetterLine(best, new ArrayList<>(group.values()));
        }
        if (best.size() < 2) {
            return null;
        }

        List<TradeTarget> indexed = new ArrayList<>(best.size());
        for (int i = 0; i < best.size(); i++) {
            TradeTarget target = best.get(i);
            indexed.add(new TradeTarget(target.villager(), target.workstation(), target.pathPos(), i));
        }
        return new TradeLine(List.copyOf(indexed));
    }

    private List<TradeTarget> chooseBetterLine(List<TradeTarget> current, List<TradeTarget> candidate) {
        if (candidate.size() > current.size()) {
            return List.copyOf(candidate);
        }
        if (candidate.size() == current.size()
                && candidate.size() > 1
                && distanceToPlayer(candidate.get(0)) + distanceToPlayer(candidate.get(candidate.size() - 1))
                        < distanceToPlayer(current.get(0)) + distanceToPlayer(current.get(current.size() - 1))) {
            return List.copyOf(candidate);
        }
        return current;
    }

    private void advanceLineAfter(TradeTarget target) {
        if (target == null || tradeLine == null || target.lineIndex() < 0) {
            return;
        }
        lineCursor = target.lineIndex();
        advanceLineCursor();
    }

    private void advanceLineCursor() {
        int next = lineCursor + lineStep;
        if (next < 0 || next >= tradeLine.targets().size()) {
            lineStep = -lineStep;
            next = lineCursor + lineStep;
        }
        lineCursor = next;
    }

    private double distanceToPlayer(TradeTarget target) {
        return target.villager().position().distanceToSqr(mc.player.position());
    }

    private boolean isTradeCandidate(Villager villager) {
        if (!EntityUtils.isEntityValid(villager)) {
            return false;
        }
        ResourceKey<VillagerProfession> profession =
                villager.getVillagerData().profession().unwrapKey().orElse(null);
        return profession != null
                && villagerTypes.get().test(BuiltInRegistries.VILLAGER_PROFESSION.getValue(profession));
    }

    private boolean isWithinInteractionRange(Villager villager) {
        return TargetSelector.INSTANCE.isWithinAttackRange(mc.player.position(), villager);
    }

    private BlockPos findWorkStation(Villager villager) {
        AutoLibrarian autoLibrarian = SurvivalTasks.getAutoLibrarian();
        return autoLibrarian == null ? null : autoLibrarian.findWorkStation(villager);
    }

    private BlockPos findWorkStationOuterPos(Villager villager, BlockPos workstation) {
        Direction facing =
                MathUtils.getHorizontalFacing(Vec3.atCenterOf(workstation).subtract(villager.position()));
        Direction clockwise = facing.getClockWise(Direction.Axis.Y);
        BlockPos pathPos = workstation.above().relative(clockwise);
        BlockState state = mc.level.getBlockState(pathPos);
        if (!state.getCollisionShape(mc.level, pathPos, CollisionContext.of(mc.player))
                .isEmpty()) {
            pathPos = workstation.above().relative(clockwise.getOpposite());
        }
        return pathPos;
    }

    private IPathGoal processGoal() {
        if (pendingVillager != null || ClientUtils.getScreen(mc) != null || currentTarget == null) {
            return null;
        }
        if (currentTarget.pathPos() != null) {
            return new GoalNearBlockPos(currentTarget.pathPos());
        }
        return PathingSchedular.pathToOrNearStopGoal(
                currentTarget.villager().blockPosition(), PATH_STOP_DISTANCE);
    }

    private Vec3 adjustmentGoal() {
        if (!adjustmentControl.get() || pendingVillager != null || currentTarget == null) {
            return null;
        }
        if (currentTarget.workstation() == null) {
            return isWithinTwoBlocks(currentTarget.villager().blockPosition())
                    ? currentTarget.villager().position()
                    : null;
        }

        BlockPos workspace = currentTarget.workstation().above();
        BlockPos playerPos = mc.player.blockPosition();
        int manDistance = MathUtils.getManhattanDistance(workspace, playerPos);
        if (manDistance <= 1) {
            Direction facing = MathUtils.getHorizontalFacing(Vec3.atCenterOf(currentTarget.workstation())
                    .subtract(currentTarget.villager().position()));
            Direction left = facing.getClockWise();
            Direction right = facing.getCounterClockWise();
            BlockPos leftPos = workspace.relative(left);
            BlockPos rightPos = workspace.relative(right);
            if (Objects.equals(playerPos, leftPos)) {
                Vec3 corner = Vec3.atBottomCenterOf(playerPos)
                        .relative(facing.getOpposite(), 0.2)
                        .relative(right, 0.15);
                if (MathUtils.isInBox(corner, mc.player.position(), 0.05)) {
                    return null;
                }
                return corner.relative(facing.getOpposite(), 0.5);
            } else if (Objects.equals(playerPos, rightPos)) {
                Vec3 corner = Vec3.atBottomCenterOf(playerPos)
                        .relative(facing.getOpposite(), 0.23)
                        .relative(left, 0.15);
                if (MathUtils.isInBox(corner, mc.player.position(), 0.05)) {
                    return null;
                }
                return corner.relative(facing.getOpposite(), 0.5);
            } else if (Objects.equals(playerPos, workspace)) {
                return MathUtils.isInBox(Vec3.atCenterOf(leftPos), mc.player.position(), 1)
                        ? Vec3.atBottomCenterOf(leftPos)
                        : Vec3.atBottomCenterOf(rightPos);
            }
        } else if (manDistance == 2) {
            return currentTarget.villager().position();
        }
        return null;
    }

    private boolean isWithinTwoBlocks(BlockPos pos) {
        return MathUtils.getManhattanDistance(pos, mc.player.blockPosition()) <= 2;
    }

    {
        pathingSchedular
                .active(() -> enable.get()
                        && baritoneControl.get()
                        && currentTarget != null
                        && pendingVillager == null
                        && ClientUtils.getScreen(mc) == null)
                .processGoal(this::processGoal);
        adjustmentSchedular.adjustRange(1.5D).center(this::adjustmentGoal);
    }

    private boolean isOnCooldown(Villager villager) {
        if (!(villager instanceof MetadataHolder holder)) {
            return false;
        }
        Long lastInteraction = holder.getMetadata().get(this, COOLDOWN_METADATA_KEY);
        return lastInteraction != null && Tasks.getTick() - lastInteraction < cooldown.get();
    }

    private void markCooldown(Villager villager, int tick) {
        if (villager instanceof MetadataHolder holder) {
            holder.getMetadata().put(this, COOLDOWN_METADATA_KEY, (long) tick);
        }
    }

    private record LineKey(boolean alongX, int fixed, int y) {}

    private record TradeLine(List<TradeTarget> targets) {}

    private record TradeTarget(Villager villager, BlockPos workstation, BlockPos pathPos, int lineIndex) {}
}
