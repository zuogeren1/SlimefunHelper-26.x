package me.matl114.hacks.modules.interact;

import java.util.*;
import java.util.stream.Collectors;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.BlockBreak;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.*;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.ac.DisablerManager;
import me.matl114.hacks.modules.combat.Attack;
import me.matl114.hacks.modules.combat.TargetSelector;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.mine.MiningProgressManager;
import me.matl114.hacks.modules.mine.PacketMine;
import me.matl114.hacks.modules.move.PlayerInputManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.*;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.entity.PlayerInputUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import me.matl114.hacks.utils.EntityUtils;

public class AutoSurround extends BaseModule implements LegalMovementManager.MovementModifier {
    static LegalMovementManager.DelegateMovementModifier instance;
    public static AutoSurround INSTANCE;

    public AutoSurround() {
        super("AutoSurround");
        if (instance == null) {
            instance = new LegalMovementManager.DelegateMovementModifier(this::cast);
            MovTasks.PLAYER_PIPELINE_0.addMovementModifierFactory(() -> instance);
        }
        instance.setDelegate(this::cast);
        INSTANCE = this;
        bindFlag(enable);
    }

    public final ModulePath autoSurround = makePath(Configs.INTERACT_CONFIG, "place-utils.auto-surround");

    @Override
    public int priority() {
        return PRIORITY_MONITOR;
    }

    public final FlagRef enable = flagBuilder(autoSurround.addEnable()).build();

    public final KeyBindRef hotkey = moduleEntry(autoSurround.addHotkey(), new MultiKeyBind(), autoSurround.addEnable())
            .build();

    public final FlagRef offhand =
            flagBuilder(autoSurround.add("offhand-enable")).build();

    public final IntRef delay = builder(autoSurround.add("delay"), IntRef.TYPE)
            .defaultValue(1)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final IntRef multiply = builder(autoSurround.add("multiply"), IntRef.TYPE)
            .defaultValue(1)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final EnumRef<LegalInteractMode> mode = builder(autoSurround.add("mode"), LegalInteractMode.class)
            .defaultValue(LegalInteractMode.DELAY_MOVEMENT)
            .build();

    public final FlagRef airplace = flagBuilder(autoSurround.add("air-place")).build();

    public final NBTRef<OptionalPrimitive<Double>> onlyPlayerNear = builder(
                    autoSurround.add("only-player-near"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.DOUBLE_TYPE, 10.0D))
            .build();

    public final FlagRef placeLower = flagBuilder(autoSurround.add("lower")).build();

    public final FlagRef autoAttackCrystals =
            flagBuilder(autoSurround.add("auto-attack-crystal")).build();

    public final FlagRef antiPacketMine =
            flagBuilder(autoSurround.add("anti-packet-mine")).build();

    public final FlagRef onlyGround =
            flagBuilder(autoSurround.add("only-ground")).build();

    public final FlagRef autoCenter =
            flagBuilder(autoSurround.add("auto-center")).build();
    public final FlagRef autoSneak = flagBuilder(autoSurround.add("auto-sneak")).build();

    public final FlagRef useWhiteList =
            flagBuilder(autoSurround.add("use-white-list")).build();

    public final NBTRef<EntrySet<Item>> whiteList = builder(autoSurround.add("white-list"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.ITEM, List.of(Items.OBSIDIAN)))
            .build();

    public final FlagRef onlyBlastResistance = builder(autoSurround.add("only-blast-resistance"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef eatingAbort = builder(autoSurround.add("using-item-abort"), Boolean.class)
            .defaultValue(false)
            .build();

    public final EnumRef<GhostHandMode> ghostHand = builder(autoSurround.add("ghost-hand-mode"), GhostHandMode.class)
            .defaultValue(GhostHandMode.INV_SWAP)
            .build();

    public final FlagRef swingHand = builder(autoSurround.add("swing-hand"), Boolean.class)
            .defaultValue(true)
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onInput);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
        registerListener(PacketMine.getPacketMineAction().getChannel(BlockBreak.Stage.PRE), this::onPrePacketMine);
    }

    @Override
    public void onEnableModule() {
        super.onEnableModule();
        triggerCenterFix = autoCenter.get() && mc.player != null && mc.player.getPose() != Pose.SWIMMING;
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        triggerCenterFix = false;
    }

    int delayTicks;
    boolean needSneak = false;

    public void onInput(Event<Void> inputEvent) {
        if (enable.get()) {
            boolean bl = mc.player.isShiftKeyDown();
            if (++delayTicks >= delay.get()) {
                if (!eatingAbort.get() || !mc.player.isUsingItem()) {
                    if (checkSurround()) {
                        if (autoCenter.get() && mc.player.getPose() != Pose.SWIMMING) {
                            triggerCenterFix = true;
                        }
                        delayTicks = 0;
                    } else {
                        triggerCenterFix = false;
                    }
                }
            }
            if (needSneak) {
                needSneak = false;
                if (autoSneak.get()) {
                    if (ViaFabricPlusHooks.isSupportInstaSneak()) {
                        if (mc.player.isShiftKeyDown() != bl) {
                            PlayerInputUtils.of(mc.player)
                                    .sneak(bl)
                                    .sendPlayerSneakUpdatePacket()
                                    .applyInput(mc.player);
                        }
                    } else {
                        PlayerInputManager.INSTANCE.addSneakModifier(0, true, Math.max(delay.get() - 1, 0), 1);
                    }
                }
            }
        }
    }

    public void onPrePacketMine(Event<BlockBreak> eventPre) {
        if (enable.get()
                && !eventPre.isCancelled()
                && eventPre.canCancel()
                && eventPre.context().stage() == BlockBreak.Stage.PRE) {
            BlockPos pos = eventPre.context().blockPos();
            if (getTargetingPos().contains(pos)) {
                eventPre.cancel();
            }
        }
    }

    int[] dx = {0, 0, -1, 1};

    int[] dz = {-1, 1, 0, 0};
    Direction[] dd = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN};
    BlockPos lastSurround;

    public Set<BlockPos> getTargetingPos() {
        BlockPos vcPos = PlayerStateManager.INSTANCE.lastVelocityAffectingPos;
        lastSurround = vcPos;
        AABB playerBox = mc.player.getBoundingBox();
        playerBox = playerBox.setMaxY(Math.max(
                playerBox.minY
                        + InteractExtra.INSTANCE.getPotentialEyeHeights().max().orElse(0),
                playerBox.maxY));
        var occupiedPoses = new LinkedHashSet<>(MathUtils.getOccupiedBlockPositions(playerBox));
        var occupiedBasePoses = new LinkedHashSet<BlockPos>();
        for (BlockPos occupiedPos : occupiedPoses) {
            occupiedBasePoses.add(new BlockPos(occupiedPos.getX(), vcPos.getY(), occupiedPos.getZ()));
        }
        int minY = ((int) playerBox.minY) - 1;
        int maxY = ((int) playerBox.maxY) + 1;
        Set<BlockPos> result = new LinkedHashSet<>();
        for (int direction = 0; direction < 4; ++direction) {
            Direction dir = dd[direction];
            var directionTestPoses = new LinkedHashSet<BlockPos>();
            for (BlockPos occupiedPos : occupiedBasePoses) {
                BlockPos expandedPos = occupiedPos.relative(dir);
                if (!occupiedBasePoses.contains(expandedPos)) {
                    directionTestPoses.add(expandedPos);
                }
            }
            // do not place under me
            int coordYMax = dir == Direction.DOWN ? maxY - 2 : maxY;
            for (BlockPos testPos : directionTestPoses) {
                for (int y = minY; y <= coordYMax; ++y) {
                    BlockPos test = testPos.atY(y);
                    if (occupiedPoses.contains(test)) {
                        continue;
                    }
                    result.add(test);
                }
            }
        }
        if (placeLower.get()) {
            if (!CollisionUtil.isBoxCollided(
                    mc.level,
                    mc.player,
                    playerBox.setMinY(playerBox.minY - 2.5).setMaxY(playerBox.minY))) {
                for (int y = minY; y < maxY - 2; ++y) {
                    for (var re : occupiedPoses) {
                        BlockPos test = re.atY(y);
                        if (occupiedPoses.contains(test)) {
                            continue;
                        }
                        result.add(test);
                    }
                }
            }
        }
        return result;
    }

    private boolean canCubePlace(LocalPlayer player, BlockPos pos, Set<EndCrystal> pendingRemove) {
        BlockState state = Blocks.OBSIDIAN.defaultBlockState();
        VoxelShape shape = state.getCollisionShape(mc.level, pos, CollisionContext.of(mc.player))
                .move(pos.getX(), pos.getY(), pos.getZ());

        return !CollisionUtil.hasAnyIntersects(
                mc.level, (entity) -> entity instanceof EndCrystal end && pendingRemove.contains(end), shape);
    }

    public boolean checkSurround() {
        if (onlyGround.get() && !mc.player.onGround() && !CollisionUtil.isEntitySupported(mc.player, 1.5D)) {
            return false;
        }
        if (onlyPlayerNear.get().isPresent()) {
            var emeries = TargetSelector.INSTANCE.getAttackableEntities(
                    onlyPlayerNear.get().getValue());
            if (emeries.isEmpty()) {
                return false;
            }
        }
        boolean legal = mode.get().isLegal();

        int mul = ((DisablerManager.INSTANCE.isMultiRotPlaceCheckDisabled(
                        mode.get().canMultiRotPlace())))
                ? multiply.get()
                : 1;

        int placeCnt = 0;
        Runnable invCallback = null;
        Set<EndCrystal> pendingRemoval = new HashSet<>();
        boolean offhandOk = offhand.get();
        var resultPoses = getTargetingPos();
        Set<BlockPos> bbs = Set.of();
        if (antiPacketMine.get()) {
            bbs = MiningProgressManager.INSTANCE.getBreakingMap().values().stream()
                    .filter(s -> TargetSelector.INSTANCE.canAttack(s.player))
                    .filter(MiningProgressManager.BlockBreakTracker::canMine)
                    .filter(s -> s.predictBreakingProgress() > 0.5)
                    .map(MiningProgressManager.BlockBreakTracker::getBlockPos)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }
        Set<BlockPos> pendingMine = new HashSet<>();
        for (var test : resultPoses) {
            BlockState state = mc.level.getBlockState(test);
            if ((state.isAir() || state.canBeReplaced())) {
                var hitResult = InteractionTasks.getPlaceSupportingResult(test, airplace.get(), !legal);
                if (hitResult != null && hitResult.flag()) {
                    if (!needSneak
                            && autoSneak.get()
                            && ViaFabricPlusHooks.isSupportInstaSneak()
                            && !mc.player.isShiftKeyDown()) {
                        PlayerInputUtils.of(mc.player)
                                .sneak(true)
                                .sendPlayerSneakUpdatePacket()
                                .applyInput(mc.player);
                    }
                    needSneak = true;
                }
                boolean canPlace = InteractUtils.canInteractAndPlace(mc.player, hitResult);
                if (canPlace) {
                    if (autoAttackCrystals.get()) {
                        mc.level
                                .getEntities((Entity) null, MathUtils.getBlockBox(test), (e) -> e instanceof EndCrystal)
                                .forEach(endCrystalEntity -> {
                                    if (endCrystalEntity instanceof EndCrystal endCrystal
                                            && !Attack.INSTANCE.attackEntity(endCrystalEntity)) {
                                        pendingRemoval.add(endCrystal);
                                    }
                                });
                    }
                    if (canCubePlace(mc.player, test, pendingRemoval)) {
                        if (placeCnt == 0) {
                            var supply = supplyBlocks();
                            if (supply == null) {
                                break;
                            }
                            mul = Math.min(mul, supply.val().getCount());
                            offhandOk |= supply.index() == 40;
                            invCallback = InvExtra.INSTANCE.swapItemToHand(supply.index(), offhandOk, ghostHand.get());
                        }
                        InteractionTasks.handlePlaceMode(
                                mode.get(),
                                hitResult.val(),
                                offhandOk ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                                swingHand.get());
                        placeCnt += 1;
                        if (placeCnt >= mul) {
                            break;
                        }
                    }
                }
            } else if (antiPacketMine.get()
                    && bbs.contains(test)
                    && mc.level
                            .getEntities(
                                    (Entity) null, new AABB(test), predicate -> !(predicate instanceof EndCrystal))
                            .isEmpty()) {
                pendingMine.add(test);
            }
        }
        if (placeCnt < mul && !mc.player.isUsingItem()) {
            for (var test : pendingMine) {
                BlockHitResult selfHitResult = InteractionTasks.createHitResult(test, mc.player.position());
                if (placeCnt == 0) {
                    var supply = supplyBlocks();
                    if (supply == null) {
                        break;
                    }
                    mul = Math.min(mul, supply.val().getCount());
                    offhandOk |= supply.index() == 40;
                    invCallback = InvExtra.INSTANCE.swapItemToHand(supply.index(), offhandOk, ghostHand.get());
                }
                InteractionTasks.handlePlaceMode(
                        mode.get(),
                        selfHitResult,
                        offhandOk ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                        swingHand.get());
                placeCnt += 1;
                if (placeCnt >= mul) {
                    break;
                }
            }
        }
        if (invCallback != null) {
            invCallback.run();
        }
        return placeCnt > 0;
    }

    public IndexEntry<ItemStack> supplyBlocks() {
        return InventoryUtils.findBestPlayerItem(
                item -> {
                    if (item.getItem() instanceof BlockItem blockItem) {
                        if (useWhiteList.get()) {
                            if (!whiteList.get().test(blockItem)) {
                                return null;
                            }
                        }
                        if (onlyBlastResistance.get() && blockItem.getBlock().getExplosionResistance() < 600) {
                            return null;
                        }
                        return (double) (blockItem.getBlock().getExplosionResistance())
                                + ((blockItem == Items.OBSIDIAN) ? 1E8 : 0)
                                + (blockItem.getBlock() instanceof BaseEntityBlock ? -1E8 : 0);
                    }
                    return null;
                },
                ghostHand.get().getSearchSize(offhand.get()),
                true,
                false);
    }

    boolean triggerCenterFix = false;
    boolean rotateSuccess = false;
    BlockPos lastCenter;

    @Override
    public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {

        if (triggerCenterFix && mc.player.onGround()) {
            if (lastCenter == null || !MathUtils.isInBox(Vec3.atCenterOf(lastCenter), mc.player.position(), 1.0)) {
                lastCenter = lastSurround == null ? PlayerStateManager.INSTANCE.lastVelocityAffectingPos : lastSurround;
            }
            var blockPos = lastCenter;
            boolean fixed = mc.player.getX() - blockPos.getX() - 0.5 <= 0.2
                    && mc.player.getX() - blockPos.getX() - 0.5 >= -0.2
                    && mc.player.getZ() - blockPos.getZ() - 0.5 <= 0.2
                    && mc.player.getZ() - 0.5 - blockPos.getZ() >= -0.2;
            if (!fixed) {
                PlayerInputUtils.Input inputUtils = PlayerInputUtils.of(mc.options);
                if (!inputUtils.hasMovement() && !movementManagerEvent.context.hasImportantRotation()) {
                    rotateSuccess = true;
                    Vec3 lookHorizontal = Vec3.atCenterOf(blockPos).subtract(mc.player.position());
                    PlayerStateManager.setPlayerYawSafe(
                            mc.player, EntityUtils.rotationToYaw(lookHorizontal.normalize()));
                    movementManagerEvent.context.markForResetRot();
                }
            } else {
                triggerCenterFix = false;
                lastCenter = null;
            }
        }
    }
    // todo: try check block position, sneak-related
    @Override
    public void applyAfterInputTick(Event<LegalMovementManager> movementManagerEvent) {
        if (triggerCenterFix && mc.player.onGround() && rotateSuccess) {
            rotateSuccess = false;
            var input = PlayerInputUtils.of(mc.player);
            if (!input.hasWASDMovement()) {
                input.forward(true).applyInput(mc.player);
            }
        }
    }

    @Override
    public boolean postModify(Event<LegalMovementManager> movementManagerEvent, boolean enabledThisTick) {
        return true;
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        this.mode.set(LegalInteractMode.getFromPreset(event.context.getValue()));
        this.airplace.set(!event.context.getValue().hasAC());
    }
}
