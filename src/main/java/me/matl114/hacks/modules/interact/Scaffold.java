package me.matl114.hacks.modules.interact;

import java.util.*;
import java.util.List;
import me.matl114.accessors.moonrise.MoonriseBlockStateBaseAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.ac.DisablerManager;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.managers.Configs;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.CollisionUtil;
import me.matl114.utils.InteractUtils;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.collections.FlagEntry;
import me.matl114.utils.collections.IndexEntry;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class Scaffold extends BaseModule {
    public Scaffold() {
        super("Scaffold");
        bindFlag(enable);
    }

    private static final Direction[] PATH_DIRECTIONS = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    final ModulePath scaffold = makePath(Configs.INTERACT_CONFIG, "interact-scaffold");

    public final FlagRef enable = flagBuilder(scaffold.addEnable()).build();

    public final KeyBindRef keyBind = moduleEntry(scaffold.addHotkey(), new MultiKeyBind(), scaffold.addEnable())
            .build();

    public final EnumRef<LegalInteractMode> legalMode = builder(
                    scaffold.add("legal-targeting"), LegalInteractMode.class)
            .defaultValue(LegalInteractMode.USEITEM_PACKET)
            .build();

    public final FlagRef airplace = flagBuilder(scaffold.add("air-place")).build();

    public final IntRef delay = intBuilder(scaffold.add("delay"))
            .defaultValue(1)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final IntRef mul = intBuilder(scaffold.add("mul"))
            .defaultValue(1)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final FlagRef offhand = flagBuilder(scaffold.add("offhand-enable")).build();

    public final FlagRef keepY = flagBuilder(scaffold.add("keep-y")).build();

    public final IntRef expandYDepth = builder(scaffold.add("expand-interact-y-depth"), IntRef.TYPE)
            .defaultValue(0)
            .validator(Configs.intRange(0, 3))
            .build();

    public final IntRef expandInteractRange = builder(scaffold.add("expand-interact-range"), IntRef.TYPE)
            .defaultValue(1)
            .validator(Configs.intRange(0, 4))
            .build();

    public final EnumRef<GhostHandMode> ghostHand = builder(scaffold.add("ghost-hand-mode"), GhostHandMode.class)
            .defaultValue(GhostHandMode.INV_SWAP)
            .build();

    public final FlagRef swingHand = builder(scaffold.add("swing-hand"), Boolean.class)
            .defaultValue(true)
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onPreInput);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onPresetReload);
    }

    private int delayTick;
    private int lastOnGroundY;

    private boolean placeBlock(int hand, BlockHitResult result) {
        boolean offhandOk = offhand.get() || hand == 40;
        Runnable callback = InvExtra.INSTANCE.swapItemToHand(hand, offhandOk, ghostHand.get());
        if (callback == null) {
            return false;
        }
        try {
            InteractionTasks.handlePlaceMode(
                    legalMode.get(), result, offhandOk ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, swingHand.get());
        } finally {
            callback.run();
        }
        return true;
    }

    private Set<Item> availableItemBlocks;

    public int supplyBlock() {
        if (availableItemBlocks == null) {
            availableItemBlocks = new HashSet<>();
            for (var item : BuiltInRegistries.ITEM) {
                if (item instanceof BlockItem blockItem
                        && !blockItem.getBlock().defaultBlockState().isAir()
                        && blockItem
                                .getBlock()
                                .defaultBlockState()
                                .isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                    availableItemBlocks.add(blockItem);
                }
            }
        }
        IndexEntry<ItemStack> stackEntry = InventoryUtils.findPlayerItem(
                item -> availableItemBlocks.contains(item.getItem()),
                ghostHand.get().getSearchSize(offhand.get()),
                true,
                false,
                true,
                true);
        return stackEntry == null ? -1 : stackEntry.index();
    }

    public void onPreInput(Event<Void> event) {
        if (checkNull()) {
            return;
        }
        if (mc.player.onGround()) {
            lastOnGroundY = mc.player.getBlockY();
        }
        if (!enable.get()) {
            delayTick = 0;
            return;
        }
        if (++delayTick >= delay.get()) {
            if (tickPlace()) {
                delayTick = 0;
            }
            return;
        }
    }

    private boolean tickPlace() {
        int hand = supplyBlock();
        if (hand < 0) {
            return false;
        }

        Vec3 playerPos = mc.player.position();
        AABB boundingBox = mc.player.getBoundingBox();
        AABB checkBox = new AABB(
                playerPos.x - 0.0001,
                boundingBox.minY - 0.5,
                playerPos.z - 0.0001,
                playerPos.x + 0.0001,
                boundingBox.maxY,
                playerPos.z + 0.0001);
        if (CollisionUtil.isBoxCollided(mc.level, mc.player, checkBox)) {
            return false;
        }
        BlockPos current = BlockPos.containing(playerPos);
        BlockPos target = BlockPos.containing(playerPos.subtract(0, 0.5F, 0));
        if (keepY.get() && target.getY() == lastOnGroundY) {
            target = target.atY(lastOnGroundY - 1);
        }
        if (Objects.equals(current, target)) {
            return false;
        }
        BlockState targetState = mc.level.getBlockState(target);
        if (!targetState.canBeReplaced()
                || (!targetState.isAir()
                        && !targetState.getCollisionShape(mc.level, target).isEmpty())) {
            return false;
        }
        List<BlockPos> path = findPlacementPath(playerPos, target);
        if (path == null || path.isEmpty()) {
            return false;
        }
        int cnt = DisablerManager.INSTANCE.isMultiRotPlaceCheckDisabled(
                        legalMode.get().canMultiRotPlace())
                ? mul.get()
                : 1;
        int ccc = 0;
        for (var i = 0; i < cnt; ++i) {
            BlockPos next = path.get(i);
            FlagEntry<BlockHitResult> hitResult = getPlacementResult(playerPos, next);
            if (!InteractUtils.canInteractAndPlace(mc.player, hitResult)) {
                return false;
            }
            BlockHitResult hit = hitResult.val();
            if (hit != null) {
                placeBlock(hand, hit);
                ccc++;
            }
        }
        return ccc > 0;
    }

    private List<BlockPos> findPlacementPath(Vec3 playerPos, BlockPos target) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos start = target.immutable();
        queue.add(start);
        visited.add(start);
        boolean positionPlaceCheck = legalMode.get().isLegal();
        while (!queue.isEmpty()) {
            BlockPos current = queue.remove();
            FlagEntry<BlockHitResult> hitResult = getPlacementResult(playerPos, current);
            if (InteractUtils.canInteractAndPlace(mc.player, hitResult)) {
                List<BlockPos> path = new ArrayList<>();
                for (BlockPos step = current; step != null; step = previous.get(step)) {
                    path.add(step.immutable());
                }
                return path;
            }

            for (Direction direction : PATH_DIRECTIONS) {
                BlockPos next = current.relative(direction);
                if (!isWithinSearchBounds(next, start) || visited.contains(next) || !isPlaceableTarget(next)) {
                    continue;
                }
                if (positionPlaceCheck) {
                    // we tend to interact nextBlock with face direction.opposite
                    Direction interactDirection = direction.getOpposite();
                    if (!InteractionTasks.checkInHead(next, mc.player.position())
                            && !InteractionTasks.checkPositionPlace(next, interactDirection, mc.player.position())) {
                        continue;
                    }
                }
                visited.add(next);
                previous.put(next, current);
                queue.add(next);
            }
        }
        return null;
    }

    private FlagEntry<BlockHitResult> getPlacementResult(Vec3 playerPos, BlockPos target) {
        if (!isPlaceableTarget(target)) {
            return null;
        }
        return InteractionTasks.getPlaceSupportingResult(
                playerPos,
                target,
                mc.player.getNearestViewDirection(),
                airplace.get(),
                !legalMode.get().isLegal());
    }

    private boolean isPlaceableTarget(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return (state.isAir() || state.liquid() || state.canBeReplaced())
                && InteractUtils.canCubePlace(mc.player, pos);
    }

    private boolean isWithinSearchBounds(BlockPos pos, BlockPos target) {
        int offsetX = pos.getX() - target.getX();
        int offsetY = pos.getY() - target.getY();
        int offsetZ = pos.getZ() - target.getZ();
        int range = expandInteractRange.get() + 1;
        return Math.abs(offsetX) + Math.abs(offsetZ) <= range && offsetY <= 0 && offsetY >= -expandYDepth.get();
    }

    public void onPresetReload(Event<EventContainer<ModulePreset>> event) {
        legalMode.set(LegalInteractMode.getFromPreset(event.context.getValue()));
        airplace.set(!event.context.getValue().hasAC());
    }
}
