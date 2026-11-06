package me.matl114.hacks.modules.survival;

import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.utils.ClientUtils;

import com.mojang.datafixers.util.Pair;
import java.util.*;
import java.util.function.Consumer;
import me.matl114.accessors.access.MerchantScreenAccess;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.gui.basic.ButtonAction;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.combat.TargetSelector;
import me.matl114.hacks.modules.interact.Interact;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.utils.config.*;
import me.matl114.hacks.utils.move.AdjustmentSchedular;
import me.matl114.hacks.utils.move.PathingSchedular;
import me.matl114.hacks.utils.move.goal.*;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import me.matl114.hacks.utils.EntityUtils;

public class AutoLibrarian extends BaseModule {

    public AutoLibrarian() {
        super("AutoLibrarian");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "survival-mine-utils.auto-librarian");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final NBTRef<WeakEntryPrimitiveMap<Enchantment, Integer>> enchantments = builder(
                    root.add("enchantments"), WeakEntryPrimitiveMap.<Enchantment, Integer>parameter())
            .defaultValue(new WeakEntryPrimitiveMap<>(
                    Registries.ENCHANTMENT, NBTTypes.INT_TYPE, Map.of(Enchantments.MENDING.identifier(), 1)))
            .build();

    public final FlagRef onlyMaxLeve =
            builder(root.add("only-max-leve"), Boolean.class).defaultValue(true).build();

    public final FlagRef log = flagBuilder(root.add("log")).build();

    public final NBTRef<EntrySet<Block>> workstationPredicate = builder(
                    root.add("work-station-down-block"), EntrySet.<Block>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.BLOCK, List.of(Blocks.MAGMA_BLOCK, Blocks.OAK_FENCE)))
            .build();

    public final FlagRef autoLockTrade =
            flagBuilder(root.add("auto-lock-trade")).build();

    public final FlagRef autoRemoval = flagBuilder(root.add("auto-removal")).build();

    public final FlagRef baritoneControl =
            flagBuilder(root.add("baritone-control")).build();

    public final FlagRef adjustmentControl =
            flagBuilder(root.add("adjustment-control")).build();

    public final FlagRef render = flagBuilder(root.add("render")).build();

    public final NBTRef<WrapColor> renderColor = builder(root.add("render-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.GREEN)))
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onPreInputEvent);
        registerListener(Listener.getPostGameTick(), this::updateRenderCollector);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
    }

    final PathingSchedular pathingSchedular = new PathingSchedular();
    final AdjustmentSchedular adjustmentSchedular = new AdjustmentSchedular();

    @Override
    public void addCustomWidgets(
            Consumer<DrawableWidget> acceptor,
            int dx,
            int dy,
            int dblank,
            me.matl114.gui.interfaces.ElementFactory factory) {
        super.addCustomWidgets(acceptor, dx, dy, dblank, factory);
        acceptor.accept(factory.createTitle("widget.interact.interact-all.use-argument", 0, dblank, dx, dy));
        if (mc.getConnection() != null) {
            acceptor.accept(factory.createExecuteButton(
                    "widget.auto-librarian.set-min-price",
                    ButtonAction.run(this::setLowestPriceForAllEnchantments),
                    0,
                    dblank,
                    dx,
                    dy));
            acceptor.accept(factory.createExecuteButton(
                    "widget.auto-librarian.fill-all-enchantment",
                    ButtonAction.run(this::setAllEnchantments),
                    0,
                    dblank,
                    dx,
                    dy));
        }
    }

    public void setLowestPriceForAllEnchantments() {
        var handler = mc.getConnection();
        if (handler == null) return;
        Map<Identifier, Integer> map = enchantments.get().idMap();
        Map<Identifier, Integer> map2 = new LinkedHashMap<>();
        for (var re : map.entrySet()) {
            if (Objects.equals(WeakHolder.DEFAULT_KEY, re.getKey())) {
                map2.put(re.getKey(), re.getValue());
            } else {
                ResourceKey<Enchantment> registryKey = ResourceKey.create(Registries.ENCHANTMENT, re.getKey());
                Holder<Enchantment> entry = RegistryUtils.getRegistryEntry(handler.registryAccess(), registryKey);
                if (entry == null) {
                    map2.put(re.getKey(), re.getValue());
                } else {
                    Enchantment ench = entry.value();
                    int minLevel = 2 + 3 * ench.getMaxLevel();
                    if (entry.is(EnchantmentTags.DOUBLE_TRADE_PRICE)) {
                        minLevel *= 2;
                    }
                    map2.put(re.getKey(), minLevel);
                }
            }
        }
        enchantments.set(new WeakEntryPrimitiveMap<>(Registries.ENCHANTMENT, NBTTypes.INT_TYPE, map2));
    }

    public void setAllEnchantments() {
        var handler = mc.getConnection();
        if (handler == null) return;
        Map<Identifier, Integer> map = enchantments.get().idMap();
        Map<Identifier, Integer> map2 = new LinkedHashMap<>(map);
        Registry<Enchantment> enchantment = RegistryUtils.getRegistry(handler.registryAccess(), Registries.ENCHANTMENT);
        for (var re : enchantment.entrySet()) {
            Identifier id = re.getKey().identifier();
            if (!map2.containsKey(id)) {
                var ench = re.getValue();
                int minLevel = 2 + 3 * ench.getMaxLevel();
                if (enchantment.wrapAsHolder(ench).is(EnchantmentTags.DOUBLE_TRADE_PRICE)) {
                    minLevel *= 2;
                }
                map2.put(id, minLevel);
            }
        }
        enchantments.set(new WeakEntryPrimitiveMap<>(Registries.ENCHANTMENT, NBTTypes.INT_TYPE, map2));
    }

    Villager targetVillager;
    BlockPos targetWorkStationBase;
    private final RenderCollector<Entity> renderCollector =
            RenderCollectors.createEntityBoxCollector(true, false, false);
    private final RenderCollector<AABB> boxCollector = RenderCollectors.createBoxCollector(true, false, false);

    private void updateRenderCollector() {
        renderCollector.clear();
        boxCollector.clear();
        if (checkNull() || !enable.get() || !render.get() || targetVillager == null || targetWorkStationBase == null) {
            return;
        }
        renderCollector.submit(targetVillager, renderColor.get().withAlpha(255));
        boxCollector.submit(
                new AABB(targetWorkStationBase.offset(0, 1, 0)), renderColor.get().withAlpha(255));
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        clearTarget();
    }

    private void clearTarget() {
        targetVillager = null;
        targetWorkStationBase = null;
        hasOpened = false;
        lastMerchantScreenSyncId = -1;
        pathingSchedular.disable();
    }

    private boolean isLowLevelOrNoProfessionVillager(Villager villager) {
        VillagerData villagerData = villager.getVillagerData();
        if (villagerData != null) {
            var profession = villagerData.profession().unwrapKey().orElse(null);
            if (Objects.equals(profession, VillagerProfession.NONE)) {
                return true;
            }
            if (Objects.equals(profession, VillagerProfession.LIBRARIAN)) {
                return villagerData.level() <= 1 && !WorldManager.INSTANCE.isVillagerTradeLock(villager);
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    public boolean isRefreshTradeVillager(Villager villagerEntity) {
        // check on ground
        return EntityUtils.isEntityValid(villagerEntity)
                && villagerEntity.onGround()
                && !villagerEntity.isInWater()
                && isLowLevelOrNoProfessionVillager(villagerEntity)
                && locateWorkStation(villagerEntity) != null;
    }

    private BlockPos locateWorkStation(Villager villager) {
        BlockPos pos = villager.getOnPos();
        for (var re : MathUtils.HORIZONTALS) {
            BlockPos pos2 = pos.relative(re);
            BlockState state = mc.level.getBlockState(pos2);
            if (workstationPredicate.get().test(state.getBlock())) {
                return pos2;
            }
        }
        return null;
    }

    public void refreshTarget() {
        if (targetVillager != null) {
            targetWorkStationBase = locateWorkStation(targetVillager);
            return;
        }
        List<Villager> allVillagersInWorkSpace = mc.level.getEntities(
                EntityTypes.VILLAGER, mc.player.getBoundingBox().inflate(100, 100, 100), this::isRefreshTradeVillager);
        if (allVillagersInWorkSpace.isEmpty()) {
            clearTarget();
            return;
        }
        targetVillager = allVillagersInWorkSpace.stream()
                .min(Comparator.comparingDouble(s -> s.position().distanceToSqr(mc.player.position())))
                .orElseThrow();
        targetWorkStationBase = locateWorkStation(targetVillager);
        return;
    }

    public void onPreInputEvent() {
        if (checkNull()) return;
        if (enable.get()) {
            refreshTarget();
            if (targetVillager != null && targetWorkStationBase != null) {
                tickRefreshEnchantment();
            }
            pathingSchedular.tickPathing(mc.player);
            adjustmentSchedular.tickAdjustment(mc.player);
        }
    }

    public void onRender(Event<Render3D> event) {
        if (enable.get() && render.get()) {
            if (targetVillager != null && targetWorkStationBase != null) {
                RenderUtils.startDrawVirtual(event.context.stack());
                try {
                    renderCollector.render3D(event.context.stack(), event.context.partialTicks());
                    boxCollector.render3D(event.context.stack(), event.context.partialTicks());
                } finally {
                    RenderUtils.stopDrawVirtual(event.context.stack());
                }
                if (baritoneControl.get()) {
                    pathingSchedular.renderPathing(event);
                }
                if (adjustmentControl.get()) {
                    adjustmentSchedular.renderAdjustment(event);
                }
            }
        }
    }

    private int lastInteractTick = 0;
    private boolean noLecternNotify = false;
    private int lastMerchantScreenSyncId = -1;
    private boolean currentAccepted = false;
    private boolean hasOpened = false;

    public void tickRefreshEnchantment() {
        if (!(isRefreshTradeVillager(targetVillager))) {
            clearTarget();
            // end
            if (ClientUtils.getScreen(mc) instanceof MerchantScreen merchant) {
                merchant.onClose();
            }
            return;
        }
        if (!TargetSelector.INSTANCE.isWithinAttackRange(mc.player.position(), targetVillager)) {
            return;
        }
        if (pathingSchedular.isPathing()) {
            return;
        }
        PlayerInteractionAccess access = PlayerInteractionAccess.of(mc.gameMode);
        BlockPos targetWorkspace = targetWorkStationBase.offset(0, 1, 0);
        BlockState currentState = mc.level.getBlockState(targetWorkspace);
        VillagerData data = targetVillager.getVillagerData();
        ResourceKey<VillagerProfession> professionRegistryKey =
                data.profession().unwrapKey().orElse(null);
        if (currentState.isAir() || currentState.liquid() || currentState.canBeReplaced()) {
            hasOpened = false;
            if (Objects.equals(professionRegistryKey, VillagerProfession.NONE)) {
                IndexEntry<ItemStack> findStack = InventoryUtils.findPlayerItem(s -> s.is(Items.LECTERN), true, false);
                if (findStack != null) {
                    noLecternNotify = false;
                    Runnable callback =
                            InvExtra.INSTANCE.swapItemToHand(findStack.index(), false, GhostHandMode.INV_SWAP);
                    if (callback != null) {
                        Direction direction = MathUtils.getHorizontalFacing(
                                Vec3.atCenterOf(targetWorkspace).subtract(targetVillager.position()));
                        Interact.INSTANCE.placeBlockStrict(
                                targetWorkspace,
                                Blocks.LECTERN
                                        .defaultBlockState()
                                        .setValue(HorizontalDirectionalBlock.FACING, direction));
                        callback.run();
                        return;
                    }
                } else {
                    if (!noLecternNotify && log.get()) {
                        noLecternNotify = true;
                        logI18N("message.module.auto-librarian.no-lectern");
                    }
                    return;
                }
            } else {
                // wait till its profession disappear
                return;
            }
            return;
        }

        // refresh a trade
        if (Objects.equals(professionRegistryKey, VillagerProfession.LIBRARIAN)) {
            // we pretend that this is the screen
            if (ClientUtils.getScreen(mc) instanceof MerchantScreen merchantScreen) {
                MerchantMenu handler = merchantScreen.getMenu();
                if (lastMerchantScreenSyncId != handler.containerId) {
                    lastMerchantScreenSyncId = handler.containerId;
                    hasOpened = true;
                    onMerchantScreenUpdate(merchantScreen);
                }
                if (!currentAccepted) {
                    if (!Objects.equals(access.getCurrentMiningPos(), targetWorkspace)) {
                        access.sendStartBreakPacket(targetWorkspace);
                    }
                    if (access.predictCurrentMiningProgressWithTool(ItemStack.EMPTY) < 0.7) {
                        return;
                    }
                    ClientUtils.swing(mc.player, InteractionHand.MAIN_HAND);
                    access.sendBreakPacket(true);
                } else if (!WorldManager.canVillagerResetTrade(merchantScreen.getMenu())) {
                    WorldManager.INSTANCE.setVillagerTradeLock(targetVillager, true);
                }
            } else if (ClientUtils.getScreen(mc) == null || ClientUtils.getScreen(mc) instanceof AbstractContainerScreen<?>) {
                if (!hasOpened && lastInteractTick + 5 < Tasks.getTick()) {
                    Interact.INSTANCE.interactEntity(targetVillager);
                    lastInteractTick = Tasks.getTick();
                }
            }
        }
    }

    private void setAccepted(Holder<Enchantment> remove, int level, int price) {
        if (autoRemoval.get()) {
            Map<Identifier, Integer> map =
                    new LinkedHashMap<>(enchantments.get().idMap());
            Integer val = map.remove(remove.unwrapKey().get().identifier());
            if (val != null && val >= price) {
                if (!onlyMaxLeve.get() && level >= remove.value().getMaxLevel()) {
                    map.remove(remove.unwrapKey().get().identifier());
                    if (log.get()) {
                        logI18N(
                                "message.module.auto-librarian.enchantment-auto-remove",
                                remove.value().description());
                    }
                    enchantments.set(new WeakEntryPrimitiveMap<>(Registries.ENCHANTMENT, NBTTypes.INT_TYPE, map));
                }
            }
        }
    }

    public void onMerchantScreenUpdate(MerchantScreen screen) {
        MerchantMenu handler = screen.getMenu();
        if (WorldManager.canVillagerResetTrade(handler)) {

            IndexEntry<Pair<Integer, Holder<Enchantment>>> findIndex = checkTradingIndex(handler);
            if (findIndex == null) {
                currentAccepted = false;
                return;
            }

            Integer currentLimit =
                    enchantments.get().getOrDefault(findIndex.val().getSecond());
            if (currentLimit == null) {
                currentAccepted = false;
                if (log.get()) {
                    logI18N(
                            "message.module.auto-librarian.enchantment-not-whitelisted",
                            findIndex.val().getSecond().value().description());
                }
                return;
            }
            int price = getPriceAt(handler, findIndex.index());
            if (price > currentLimit) {
                currentAccepted = false;
                if (log.get()) {
                    logI18N(
                            "message.module.auto-librarian.enchantment-price-too-high",
                            findIndex.val().getSecond().value().description(),
                            price,
                            currentLimit);
                }
                return;
            }
            int level = findIndex.val().getFirst();
            int maxLevel = findIndex.val().getSecond().value().getMaxLevel();
            if (onlyMaxLeve.get() && level < maxLevel) {
                currentAccepted = false;
                if (log.get()) {
                    logI18N(
                            "message.module.auto-librarian.enchantment-level-too-low",
                            findIndex.val().getSecond().value().description(),
                            level,
                            maxLevel);
                }
                return;
            }
            if (log.get()) {
                logI18N(
                        "message.module.auto-librarian.enchantment-success",
                        findIndex.val().getSecond().value().description(),
                        price);
            }
            currentAccepted = true;
            if (autoLockTrade.get()) {
                if (InventoryUtils.findPlayerItem(s -> s.is(Items.BOOK), true, false) != null) {
                    if (InventoryUtils.computePlayerInventory(Items.EMERALD) >= price) {
                        var access = MerchantScreenAccess.of(screen);
                        access.setSelectedIndex(access.getSelectedIndex());
                        mc.gameMode.handleContainerInput(handler.containerId, 2, 1, ContainerInput.PICKUP, mc.player);
                    } else {
                        if (log.get()) {
                            logI18N(
                                    "message.module.auto-librarian.auto-lock.no-item",
                                    Items.EMERALD.getName(new ItemStack(Items.EMERALD)));
                        }
                    }
                } else {
                    if (log.get()) {
                        logI18N(
                                "message.module.auto-librarian.auto-lock.no-item",
                                Items.BOOK.getName(new ItemStack(Items.BOOK)));
                    }
                }
            }
        } else {
            WorldManager.INSTANCE.setVillagerTradeLock(targetVillager, true);
            currentAccepted = true;
            IndexEntry<Pair<Integer, Holder<Enchantment>>> findIndex = checkTradingIndex(handler);
            if (findIndex != null) {
                setAccepted(
                        findIndex.val().getSecond(),
                        findIndex.val().getFirst(),
                        getPriceAt(handler, findIndex.index()));
            }
        }
    }

    private int getPriceAt(MerchantMenu handler, int idx) {
        var re = handler.getOffers().get(idx);
        return Math.max(
                re.getItemCostA().count(),
                re.getItemCostB().map(ItemCost::count).orElse(0));
    }

    public IndexEntry<Pair<Integer, Holder<Enchantment>>> checkTradingIndex(MerchantMenu handler) {
        var offers = handler.getOffers();
        int idx = 0;
        for (var re : offers) {
            ItemStack stack1 = re.getResult();
            // if(stack1)
            if (stack1.is(Items.ENCHANTED_BOOK) && stack1.has(DataComponents.STORED_ENCHANTMENTS)) {
                // check price and enchantments
                var firstEnch = stack1.get(DataComponents.STORED_ENCHANTMENTS).entrySet().stream()
                        .findFirst()
                        .orElse(null);
                if (firstEnch != null) {
                    return new IndexEntry<>(idx, new Pair<>(firstEnch.getIntValue(), firstEnch.getKey()));
                }
            }
            idx += 1;
        }
        return null;
    }

    boolean pickupLecterns = false;

    public IPathGoal processGoal() {
        IndexEntry<ItemStack> findLectern = InventoryUtils.findItem(mc.player.getInventory(), Items.LECTERN);
        if (findLectern == null) {
            pickupLecterns = true;
        }
        if (pickupLecterns) {
            BlockPos doNotIntersect = targetVillager.blockPosition();
            AABB doNotIntersectBox = new AABB(doNotIntersect).inflate(0, 1, 0);
            List<ItemEntity> nearbyLecterns = mc.level.getEntities(
                    EntityTypes.ITEM,
                    mc.player.getBoundingBox().inflate(6, 2, 6),
                    (item) -> !doNotIntersectBox.intersects(item.getBoundingBox())
                            && (item).getItem().is(Items.LECTERN));
            ItemEntity nearest = nearbyLecterns.stream()
                    .min(Comparator.comparingDouble(s -> s.position().distanceToSqr(mc.player.position())))
                    .orElse(null);
            if (nearest == null) {
                pickupLecterns = false;
                return null;
            } else {
                return new GoalNearBlockPos(nearest.blockPosition());
            }
        } else {
            Direction lastDirection = MathUtils.getHorizontalFacing(
                    Vec3.atCenterOf(targetWorkStationBase).subtract(targetVillager.position()));
            Direction clockWise = lastDirection.getClockWise(Direction.Axis.Y);
            BlockPos testPos = targetWorkStationBase.offset(0, 1, 0).relative(clockWise);
            BlockState testState = mc.level.getBlockState(testPos);
            if (!testState
                    .getCollisionShape(mc.level, testPos, CollisionContext.of(mc.player))
                    .isEmpty()) {
                testPos = targetWorkStationBase.offset(0, 1, 0).relative(clockWise.getOpposite());
            }
            return new GoalBlockPos(testPos);
        }
    }

    {
        pathingSchedular.active(this::isEnablePathing).processGoal(this::processGoal);
    }

    public boolean isEnablePathing() {
        return baritoneControl.get() && targetVillager != null && targetWorkStationBase != null;
    }

    {
        adjustmentSchedular.adjustRange(1.5);
        adjustmentSchedular.center(this::adjustmentGoal);
    }

    public Vec3 adjustmentGoal() {
        if (adjustmentControl.get() && targetVillager != null && targetWorkStationBase != null) {
            BlockPos targetWorkSpace = targetWorkStationBase.offset(0, 1, 0);
            BlockPos playerPos = mc.player.blockPosition();
            int manDistance = MathUtils.getManhattanDistance(targetWorkSpace, playerPos);
            if (manDistance <= 1) {
                Direction lastDirection = MathUtils.getHorizontalFacing(
                        Vec3.atCenterOf(targetWorkStationBase).subtract(targetVillager.position()));
                Direction leftPos = lastDirection.getClockWise();
                Direction rightPos = lastDirection.getCounterClockWise();
                BlockPos leftBp = targetWorkSpace.relative(leftPos);
                BlockPos rightBp = targetWorkSpace.relative(rightPos);
                if (Objects.equals(playerPos, leftBp)) {
                    Vec3 corner = Vec3.atBottomCenterOf(playerPos)
                            .relative(lastDirection.getOpposite(), 0.2)
                            .relative(rightPos, 0.15);
                    if (MathUtils.isInBox(corner, mc.player.position(), 0.05)) {
                        return null;
                    }
                    return corner.relative(lastDirection.getOpposite(), 0.5);

                } else if (Objects.equals(playerPos, rightBp)) {
                    Vec3 corner = Vec3.atBottomCenterOf(playerPos)
                            .relative(lastDirection.getOpposite(), 0.23)
                            .relative(leftPos, 0.15);
                    if (MathUtils.isInBox(corner, mc.player.position(), 0.05)) {
                        return null;
                    }
                    return corner.relative(lastDirection.getOpposite(), 0.5);
                } else if (Objects.equals(playerPos, targetWorkSpace)) {
                    if (MathUtils.isInBox(Vec3.atCenterOf(leftBp), mc.player.position(), 1)) {
                        return Vec3.atBottomCenterOf(leftBp);
                    } else {
                        return Vec3.atBottomCenterOf(rightBp);
                    }
                }
            } else if (manDistance == 2) {
                return targetVillager.position();
            }
        }
        return null;
    }
}
