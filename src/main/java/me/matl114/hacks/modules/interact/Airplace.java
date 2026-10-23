package me.matl114.hacks.modules.interact;

import java.awt.*;
import java.util.function.Predicate;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.events.RenderListener;
import me.matl114.events.catchers.PacketCatcherImpl;
import me.matl114.events.impl.Render3D;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.RenderTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.task.DisablerManager;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.Debug;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.MathUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.algorithms.StateMachine;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class Airplace extends BaseModule {
    public final ModulePath interactionTweaks = makePath(Configs.INTERACT_CONFIG, "interaction-tweaks");
    public final ModulePath airPlace = interactionTweaks.add("air-place");

    public Airplace() {
        super("Airplace");
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(airPlace.add("enable")).build();

    public final KeyBindRef hotkey = moduleEntry(airPlace.add("hotkey"), new MultiKeyBind(), airPlace.add("enable"))
            .build();

    public final DoubleRef range = builder(airPlace.add("range"), DoubleRef.TYPE)
            .defaultValue(5.0D)
            .validator(Configs.doubleRange(0, 10000))
            .build();

    public final FlagRef onlyBlocks = builder(airPlace.add("only-blocks"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef render = flagBuilder(airPlace.add("render")).build();
    // todo: add to switch mode
    public final EnumRef<Mode> enableAirWall = builder(airPlace.add("mode"), Mode.class)
            .defaultValue(Mode.VANILLA)
            .updateListener(s -> {
                onSwitch();
            })
            .build();

    public final IntRef maxBatch = intBuilder(airPlace.add("max-batch-place"))
            .defaultValue(64)
            .validator(Configs.INT_POSITIVE)
            .show(() -> enableAirWall.get().isIn(Mode.GRIM_FAST_GHOST_BLOCK_WALL))
            .build();

    public final IntRef invSleepTick = intBuilder(airPlace.add("inv-sleep-tick"))
            .defaultValue(2)
            .validator(Configs.INT_POSITIVE)
            .show(() -> enableAirWall.get().isIn(Mode.GRIM_FAST_GHOST_BLOCK_WALL))
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getItemUseAction(), this::onInteract);
        registerListener(Listener.getPreHandleInputEvents(), this::onInput);
        registerListener(RenderListener.getRender3DEvent(), this::onRenderPos);
        registerListener(PacketManager.getQueueShutdownEvent(), this::clearCurrentAirWall);
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        clearCurrentAirWall();
    }

    public void onInteract(Event<HitResult> event) {
        if (!event.isCancelled() && enable.get()) {
            InteractionHand hand = event.getArgs(0);
            ItemStack stack = mc.player.getItemInHand(hand);
            if (!onlyBlocks.get() || (!stack.isEmpty() && stack.getItem() instanceof BlockItem)) {
                HitResult hitResult = event.context();
                if (hitResult.getType() == HitResult.Type.MISS) {
                    HitResult result = getCameraEntity().pick(range.get(), 0, false);
                    if (result.getType() == HitResult.Type.MISS && result instanceof BlockHitResult block) {
                        switch (enableAirWall.get()) {
                            case VANILLA -> {
                                BlockHitResult newResult = new BlockHitResult(
                                        block.getLocation(),
                                        block.getDirection(),
                                        block.getBlockPos(),
                                        block.isInside());
                                event.context(newResult);
                                return;
                            }
                            case GRIM_GHOST_BLOCK_WALL -> {
                                onGrimAirWall(block);
                                return;
                            }
                            case GRIM_FAST_GHOST_BLOCK_WALL -> {
                                onGrimFastWall(block, hand);
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    public void onSwitch() {
        clearCurrentAirWall();
    }

    public void clearCurrentAirWall() {
        targetPos = null;
    }

    public void onGrimAirWall(BlockHitResult hitResult) {
        clearCurrentAirWall();
        if (DisablerManager.INSTANCE.isGrimSelfCheckDisabled()) {
            targetPos = hitResult.getBlockPos();
        } else {
            Debug.chat("[AirWall] 当前暂未禁用GrimSelfCheck,无法执行");
        }
    }

    BlockPos targetPos = null;

    public void onInput() {
        onInputGrimWall();
        onInputFastWall();
    }

    public void onInputGrimWall() {
        if (enable.get()
                && targetPos != null
                && mc.player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof BlockItem block
                && block != Items.AIR
                && Vec3.atCenterOf(targetPos)
                                .subtract(mc.player.getEyePosition())
                                .horizontalDistanceSqr()
                        <= MathUtils.s2(mc.player.blockInteractionRange() + 1)) {
            for (var i = 1; i < 256; ++i) {
                BlockPos checkPos = targetPos.offset(0, -i, 0);
                BlockState state = mc.level.getBlockState(checkPos);
                if (!state.isAir() && !state.liquid()) {
                    if (i == 1) targetPos = null;
                    var ppp = checkPos;
                    RenderTasks.drawBox(AABB.of(new BoundingBox(ppp)), 50, Color.MAGENTA);
                    InteractionTasks.interactBlock(
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atBottomCenterOf(ppp).add(0, 1, 0), Direction.UP, ppp, false),
                            false);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    // work by magic
                    // work by placeAfterPlace bypass
                    DisablerManager.INSTANCE.flushACPlaceQueue();
                    return;
                }
            }
        } else {
            targetPos = null;
        }
    }

    FastPlaceTaskInfo currentTask;

    public static record FastPlaceTaskInfo(
            BlockPos.MutableBlockPos startPos,
            BlockPos targetPos,
            int itemCount,
            ItemStack item,
            int selectedSlot,
            InteractionHand hand,
            int way) {}

    int startWaitTick = 0;
    static final int FAST_STATE_NONE = 0;
    static final int FAST_STATE_PLACE = 1;
    static final int FAST_STATE_WAIT_SLOT_UPDATE = 2;
    static final int FAST_STATE_WAIT_300MS = 3;
    StateMachine stateMachine;
    // 状态机
    // place -> send swap -> wait response -> continue ->
    // if reach -> wait tick = 7 ~ 300ms -> place real
    public void clearFastWall() {
        currentTask = null;
        stateMachine = null;
    }

    public boolean isState() {
        return currentTask != null;
    }

    public void onGrimFastWall(BlockHitResult hitResult, InteractionHand hand) {
        clearFastWall();
        if (DisablerManager.INSTANCE.isGrimSelfCheckDisabled()) {
            if (!DisablerManager.INSTANCE.autoFlushPlaceQueue.get()) {
                Debug.chat(
                        "[AirWall] 请先在",
                        Component.translatable("config.index.disablers"),
                        "中启用配置项: ",
                        Component.translatable("disablers.auto-flush-multi-place-queue"));
                return;
            }
            ItemStack usingItem = mc.player.getItemInHand(hand);
            if (usingItem.isEmpty()) return;
            if (usingItem.getCount() < 2) {
                Debug.chat("[AirWall] 手上物品太少,无法执行,该模式下手上尽可能有足够多的方块");
                return;
            }
            int recommendCnt = Math.min(maxBatch.get(), 48);
            if (usingItem.getCount() < recommendCnt) {
                Debug.chat("[AirWall] 提示: 我们推荐该模式手上最好有足够多(>= %d)的方块,当前数量可能会导致放置较慢".formatted(recommendCnt));
            }
            BlockPos startPos = hitResult.getBlockPos();
            Vec3 centerPos = Vec3.atCenterOf(startPos);
            // under eye -> from down, else from up
            int way = centerPos.y < mc.player.getEyePosition().y ? -1 : 1;
            BlockPos fastStartPos = null;
            for (var i = 1; i < 256; ++i) {
                BlockPos checkPos = startPos.offset(0, way * i, 0);
                BlockState state = mc.level.getBlockState(checkPos);
                if (!state.isAir() && !state.liquid()) {
                    fastStartPos = checkPos;
                    break;
                }
            }
            if (fastStartPos != null) {
                currentTask = new FastPlaceTaskInfo(
                        fastStartPos.mutable(),
                        startPos,
                        usingItem.getCount(),
                        usingItem.copy(),
                        InventoryUtils.getSelectedSlot(),
                        hand,
                        way);
                stateMachine = createStateMachine();
            }
        } else {
            Debug.chat("[AirWall] 当前暂未禁用GrimSelfCheck,无法执行");
        }
    }

    public void onInputFastWall() {
        if (enable.get() && currentTask != null && stateMachine != null) {
            BlockPos targetPos = currentTask.targetPos;
            ItemStack stack = currentTask.item;
            InteractionHand hand = currentTask.hand;
            ItemStack stackInHand = mc.player.getItemInHand(hand);
            if (ItemStack.isSameItem(stackInHand, stack)) {
                if (Vec3.atCenterOf(targetPos)
                                .subtract(mc.player.getEyePosition())
                                .horizontalDistanceSqr()
                        <= MathUtils.s2(mc.player.blockInteractionRange() + 1)) {
                    stateMachine.step();
                } else {
                    Debug.chat("[AirWall] 你移动的位置太多了, 终止任务");
                    currentTask = null;
                    stateMachine = null;
                }
            } else {
                Debug.chat("[AirWall] 手上的物品被切换了，终止任务");
                currentTask = null;
                stateMachine = null;
            }
        }
    }

    public StateMachine createStateMachine() {
        return new StateMachine(
                FAST_STATE_PLACE,
                this::onUpdate,
                (state) -> FAST_STATE_NONE,
                this::onPlace,
                this::onWaitSlotUpdate,
                this::onWait300MS);
    }

    public int onUpdate(StateMachine machine, int t) {
        if (currentTask == null) {
            stateMachine = null;
            machine.markForEndState();
            return FAST_STATE_NONE;
        }
        return t;
    }

    public int onPlace(StateMachine machine) {
        BlockPos.MutableBlockPos mutable = currentTask.startPos;
        int endY = currentTask.targetPos.getY();
        int canPlaceCount = Math.min(maxBatch.get(), currentTask.itemCount - 1);
        int placeCnt = 0;
        for (; mutable.getY() != endY; ) {
            BlockPos pos = mutable.immutable();
            Direction dir = currentTask.way < 0 ? Direction.UP : Direction.DOWN;
            BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(pos).relative(dir, 0.5), dir, pos, false);
            mc.gameMode.startPrediction(
                    mc.level, (seq) -> new ServerboundUseItemOnPacket(currentTask.hand, hitResult, seq));
            mutable.move(0, -currentTask.way, 0);
            placeCnt += 1;
            if (placeCnt >= canPlaceCount) {
                startWaitTick = 0;
                return FAST_STATE_WAIT_SLOT_UPDATE;
            }
        }
        // mutable.getY() == endY
        startWaitTick = 0;
        machine.markForEndState();
        return FAST_STATE_WAIT_300MS;
    }

    public int onWaitSlotUpdate(StateMachine machine) {
        // magic sleep
        int sleepLimit = invSleepTick.get();
        if (startWaitTick == sleepLimit) {
            //
            ItemStack stackCopy = mc.player.getItemInHand(currentTask.hand).copy();
            // make desync inventory packets
            mc.player.setItemInHand(currentTask.hand, ItemStack.EMPTY);
            try {
                int hotbarIndex = mc.player
                        .containerMenu
                        .findSlot(mc.player.getInventory(), currentTask.selectedSlot)
                        .orElse(-1);
                mc.gameMode.handleContainerInput(
                        mc.player.containerMenu.containerId, hotbarIndex, 40, ContainerInput.SWAP, mc.player);
                mc.gameMode.handleContainerInput(
                        mc.player.containerMenu.containerId, hotbarIndex, 40, ContainerInput.SWAP, mc.player);

            } finally {
                mc.player.setItemInHand(currentTask.hand, stackCopy);
            }
            Predicate<Event<?>> packetPredicate = (event) -> {
                if (machine.getState() == FAST_STATE_WAIT_SLOT_UPDATE && startWaitTick < 20) {
                    machine.setState(FAST_STATE_PLACE);
                }
                return true;
            };
            Listener.addPostPacketCatcher(
                    new PacketCatcherImpl(ClientboundContainerSetSlotPacket.class, packetPredicate));
            Listener.addPostPacketCatcher(
                    new PacketCatcherImpl(ClientboundContainerSetContentPacket.class, packetPredicate));
        }
        startWaitTick++;
        machine.markForEndState();
        if (startWaitTick >= 20) {
            return FAST_STATE_PLACE;
        }
        return FAST_STATE_WAIT_SLOT_UPDATE;
    }

    public int onWait300MS(StateMachine machine) {
        machine.markForEndState();
        startWaitTick++;
        if (startWaitTick > 8) {
            // execute place
            stateMachine = null;
            BlockPos pos = currentTask.targetPos;
            Direction dir = currentTask.way < 0 ? Direction.UP : Direction.DOWN;
            BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(pos).relative(dir, 0.5), dir, pos, false);
            mc.gameMode.startPrediction(
                    mc.level, (seq) -> new ServerboundUseItemOnPacket(currentTask.hand, hitResult, seq));
            currentTask = null;
            Debug.chat("[AirWall] 任务完成");
            return FAST_STATE_NONE;
        }
        return FAST_STATE_WAIT_300MS;
    }

    public void onRenderPos(Event<Render3D> event) {
        if (enable.get()) {
            if (mc.player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()
                    && mc.player.getItemInHand(InteractionHand.OFF_HAND).isEmpty()) {
                return;
            }
            var stack = event.context();
            if (mc.hitResult.getType() == HitResult.Type.MISS) {
                HitResult result = getCameraEntity().pick(range.get(), 0, false);
                if (result.getType() == HitResult.Type.MISS && result instanceof BlockHitResult block) {
                    RenderUtils.startDrawVirtual(stack.stack());
                    try {
                        BlockPos pos = block.getBlockPos();
                        RenderUtils.drawOutlinedBox(
                                stack.stack(), Vec3.atLowerCornerOf(pos), Vec3.atLowerCornerOf(pos.offset(1, 1, 1)), Color.RED);
                    } finally {
                        RenderUtils.stopDrawVirtual(stack.stack());
                    }
                }
            }
        }
    }

    public Entity getCameraEntity() {
        if (mc.getCameraEntity() != null) {
            return mc.getCameraEntity();
        }
        return mc.player;
    }

    public static enum Mode implements ConfigEnum {
        VANILLA,
        GRIM_GHOST_BLOCK_WALL,
        GRIM_FAST_GHOST_BLOCK_WALL;

        @Override
        public String getConfigEnumType() {
            return "air_place_mode";
        }
    }
}
