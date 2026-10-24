package me.matl114.hacks.modules.slimefun;

import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.utils.ClientUtils;

import com.mojang.datafixers.util.Pair;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import me.matl114.accessors.access.ClientAccess;
import me.matl114.accessors.access.HandledScreenAccess;
import me.matl114.accessors.interfaces.TileInventory;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.events.impl.UseItemOnBlock;
import me.matl114.gui.basic.ContentDelegateWidget;
import me.matl114.gui.complex.slimefun.SlimefunDispensorSuggestBookWidget;
import me.matl114.hacks.SlimefunTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.RotationManager;
import me.matl114.hacks.utils.multiblock.BlockMatcher;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.utils.Debug;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.RaycastUtils;
import me.matl114.utils.containers.MetaData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class MultiBlockHelper extends BaseModule {
    public MultiBlockHelper() {
        super("MultiBlockHelper");
        bindFlag(enableClicker);
    }

    public final ModulePath multiblock = makePath(Configs.SLIMEFUN_CONFIG, "multi-block-clicker");

    public final FlagRef enableClicker = flagBuilder(multiblock.addEnable()).build();

    public final FlagRef enableCrafterGui =
            flagBuilder(multiblock.add("enable-crafter-gui")).build();

    public final IntRef rate = builder(multiblock.add("rate"), IntRef.TYPE)
            .defaultValue(9)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final EnumRef<LegalInteractMode> legalMode = builder(
                    multiblock.add("bypass-targeting-mode"), LegalInteractMode.class)
            .defaultValue(LegalInteractMode.USEITEM_PACKET)
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostPlayerUseItemOnBlock(), this::onBlockClick);
        registerListener(Listener.getPreGameTick(), this::onTick);
        registerListener(Listener.getServerLeavePoint(), this::onExit);
        registerListener(Listener.getPostInitializeScreen().getChannel(AbstractContainerScreen.class), this::onScreenInit);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        if (event.context().getValue().isOptionPreset()) return;
        legalMode.set(LegalInteractMode.getFromPreset(event.context.getValue()));
    }

    private int lastChatTimestamp = 0;
    private int lastInteractTimestamp = 0;

    public void onBlockClick(Event<UseItemOnBlock> result) {
        if (enableClicker.get()) {
            onClickBlockExecute(result.context.hitResult(), false, true);
        }
    }

    private long lastAutoTick;

    public void onTick(Event<LocalPlayer> player) {
        if (!screens.isEmpty()) {
            long currentMs = System.currentTimeMillis();
            if (currentMs > (lastAutoTick + (null == ClientUtils.getScreen(mc) ? 2 : 1) * 300)) {
                if (mc.player != null && mc.player.isShiftKeyDown()) {
                    Debug.chat(Component.literal("[自动多方块] 检测到长按下蹲,清除全部的执行中多方块"));
                    clearMultiBlockExecuteTasks();
                } else {
                    lastAutoTick = currentMs;

                    int cursorIndex = (++executeCursor) % screens.size();
                    var executeData = screens.get(cursorIndex);
                    if (executeData != null && executeData.getSecond() instanceof TileInventory holder) {
                        if (!holder.isVirtual() && holder.getBlockType() == Blocks.DISPENSER) {
                            // 当玩家关闭界面但并没有取消的时候,以低速运行
                            onMultiBlockExecute(holder.castHandled(), true, false);
                        }
                    } else {
                        Debug.chat(Component.literal("[自动多方块] 当前执行的界面并没有位置记录,已自动移除"));
                        screens.remove(cursorIndex);
                        executeCursor -= 1;
                    }
                }
            }
        }
    }

    public void onExit() {
        screens.clear();
        executeCursor = 0;
    }

    private final Random interactOffsetRand = new Random();
    private SlimefunDispensorSuggestBookWidget suggestBook;
    private static final String KEY_BOOK_WIDGET = "slimefunhelper:multiblock_suggestion_book_widget";
    private static final int[] AVAILABLE_SLOTS = new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8};

    private void onScreenInit(Event<AbstractContainerScreen<?>> event) {
        if (enableCrafterGui.get()
                && event.context() instanceof TileInventory screen
                && event.context() instanceof DispenserScreen containerScreen) {
            HandledScreenAccess screenAccess = HandledScreenAccess.of(containerScreen);
            MetaData holder = screenAccess.getMetadata();
            SlimefunDispensorSuggestBookWidget dispensorWidget = holder.get(this, KEY_BOOK_WIDGET);
            // init while lately
            if (dispensorWidget == null) {
                Collection<String> co = screen.isVirtual()
                        ? null
                        : SlimefunTasks.getOptionalMultiBlockTypes(screen.getWorld(), screen.getPos()).stream()
                                .map(SlimefunTasks::getOptionalCraftingType)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .map(RecipeDatabase.CraftingType::id)
                                .collect(Collectors.toSet());
                dispensorWidget = new SlimefunDispensorSuggestBookWidget(
                        screen,
                        3,
                        3,
                        co,
                        (bol, entry) -> SlimefunTasks.moveSlimefunRecipePatternToContainer(
                                entry, containerScreen.getMenu(), bol, true, AVAILABLE_SLOTS));
                holder.put(this, KEY_BOOK_WIDGET, dispensorWidget);
            }
            dispensorWidget.refreshActiveState();
            new ContentDelegateWidget<>(screenAccess.getScreenX(), screenAccess.getScreenY(), 0, 0)
                    .setContentDelegate(dispensorWidget)
                    .addTo(containerScreen);
        }
    }

    private void onClickBlockExecute(BlockHitResult result, boolean delayClick, boolean clickMany) {
        if (result == null) return;
        BlockPos pos = result.getBlockPos();
        Block block = mc.level.getBlockState(pos).getBlock();
        // do not speed up when opening crafting dispensor
        if (block == Blocks.DISPENSER || block == Blocks.DROPPER) {
            return;
        }
        var potentials = SlimefunTasks.getRecipeDatabase().getPotentialMultiBlocks(block);
        if (potentials == null || potentials.isEmpty()) return;
        Optional<RecipeDatabase.MultiBlockEntry> first = potentials.stream()
                .filter(m -> anyMatchMiddle(m, mc.level, pos))
                .findFirst();
        if (first.isEmpty()) return;
        if (lastChatTimestamp + 5 * 20 < Tasks.getTick()) {
            Debug.chat(
                    Component.literal("[MBHelper] Interacting with multiblock: ")
                            .withStyle(ChatFormatting.RED),
                    first.get().id());
            lastChatTimestamp = Tasks.getTick();
        }
        int rateLimit = (clickMany ? rate.get() : 1);
        boolean currentLookingAt = false;
        if (mc.hitResult != null
                && mc.hitResult.getType() == HitResult.Type.BLOCK
                && Objects.equals(((BlockHitResult) mc.hitResult).getBlockPos(), result.getBlockPos())) {
            currentLookingAt = true;
        }
        // illegal click, with legal mode, have to redirect
        if (!currentLookingAt && legalMode.get().isLegal()) {
            // the 300ms limit or the legalMode
            if (!clickMany || lastInteractTimestamp + (5) < Tasks.getTick()) {
                lastInteractTimestamp = Tasks.getTick();
                Vec3 interactTarget = Vec3.atCenterOf(result.getBlockPos());
                Vec3 interactLook = interactTarget.add(
                        interactOffsetRand.nextDouble(-0.05d, 0.05d),
                        interactOffsetRand.nextDouble(-0.05d, 0.05d),
                        interactOffsetRand.nextDouble(-0.05d, 0.05d));
                Vec3 cacheDirection =
                        interactLook.subtract(mc.player.getEyePosition()).normalize();
                Vec2 pitchYaw = EntityUtils.rotationToPitchYaw(cacheDirection);
                switch (legalMode.get()) {
                    case USEITEM_PACKET -> clickUsePacket(result, pitchYaw, rateLimit);
                    case LEGACY_SLIENT_ROT -> clickSnap(result, pitchYaw, rateLimit);
                    case DELAY_MOVEMENT, MOVEMENT_POST -> clickDelayMovement(result, pitchYaw, rateLimit);
                }
            } else {
                Debug.chat(Component.literal("[AC] 你点的太快了,可能无法通过反作弊"));
            }
        } else {
            for (int i = 0; i < rateLimit; ++i) {
                mc.gameMode.startPrediction(
                        mc.level,
                        (sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, result, sequence)));
            }
            if (delayClick && clickMany) {
                AtomicInteger count = new AtomicInteger(2);
                Tasks.scheduleRepeated(
                        () -> {
                            for (int i = 0; i < rateLimit; ++i) {
                                mc.gameMode.startPrediction(
                                        mc.level,
                                        (sequence -> new ServerboundUseItemOnPacket(
                                                InteractionHand.MAIN_HAND, result, sequence)));
                            }
                            return count.decrementAndGet() <= 0;
                        },
                        3,
                        4);
            }
            ClientAccess.of(mc).setItemUseCooldown(0);
        }
    }

    public void clickDelayMovement(BlockHitResult result, Vec2 pitchYaw, int clickRate) {
        RotationManager.INSTANCE.request(
                RotationManager.ofDirection(() -> pitchYaw).applyMoveFix(false).postTick(() -> {
                    for (int i = 0; i < clickRate; ++i) {
                        mc.gameMode.startPrediction(
                                mc.level,
                                (sequence -> new ServerboundUseItemOnPacket(
                                        InteractionHand.MAIN_HAND, result, sequence)));
                    }
                    ClientAccess.of(mc).setItemUseCooldown(0);
                }));
    }

    public void clickSnap(BlockHitResult result, Vec2 pitchYaw, int clickRate) {
        LegacySnapRotManager.INSTANCE.snapAt(pitchYaw.x, pitchYaw.y, false);
        for (int i = 0; i < clickRate; ++i) {
            mc.gameMode.startPrediction(
                    mc.level,
                    (sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, result, sequence)));
        }
    }

    public void clickUsePacket(BlockHitResult result, Vec2 pitchYaw, int clickRate) {

        // find a hand which contains a item
        // do not pass grimac
        // will consume packet-limit, shit
        InteractionHand hand;
        if (!mc.player.getMainHandItem().isEmpty()) {
            hand = InteractionHand.MAIN_HAND;
        } else if (!mc.player.getOffhandItem().isEmpty()) {
            hand = InteractionHand.OFF_HAND;
        } else {
            // try
            hand = null;
        }
        if (hand != null) {

            for (int i = 0; i < clickRate; ++i) {
                mc.gameMode.startPrediction(
                        mc.level, (z) -> new ServerboundUseItemPacket(hand, z, pitchYaw.y, pitchYaw.x));
                mc.gameMode.startPrediction(
                        mc.level,
                        (sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, result, sequence)));
            }
            ClientAccess.of(mc).setItemUseCooldown(0);
        } else {
            clickSnap(result, pitchYaw, clickRate);
        }
    }

    private List<Pair<BlockPos, TileInventory>> screens = new ArrayList<>();
    private int executeCursor = 0;

    public void onMultiBlockExecute(Screen executingScreen, boolean clickMany, boolean clickDouble) {
        if (mc.player == null
                || !(executingScreen instanceof TileInventory tile)
                || tile.isVirtual()
                || tile.getWorld() != mc.level) {
            return;
        }
        BlockPos pos = tile.getPos();
        Block block = tile.getBlockType();
        if (Vec3.atCenterOf(pos).distanceToSqr(mc.player.position()) > 50) {
            Debug.chat(Component.literal("[多方块执行] 你离着自动执行的多方块太远了,已关闭自动执行"));
            toggleMultiBlockAutoExecuteState(tile, false);
            return;
        }
        // opening current Executing
        if (ClientUtils.getScreen(mc) instanceof TileInventory tileExecute && Objects.equals(pos, tileExecute.getPos())) {
            boolean hasItem = false;
            for (var slot : tileExecute.castHandled().getMenu().slots) {
                if (slot.container instanceof Inventory) {
                    break;
                } else if (!slot.getItem().isEmpty()) {
                    hasItem = true;
                    break;
                }
            }
            // return if there is no item in the screen
            if (!hasItem) return;
        }

        boolean find = false;
        if (block == Blocks.DISPENSER || block == Blocks.DROPPER) {
            for (var multiblock :
                    SlimefunTasks.getRecipeDatabase().getMultiBlockRegistry().values()) {
                var optional = getOptionalActionFromDispenser(multiblock, mc.level, pos);
                if (optional.isEmpty()) continue;
                find = true;
                for (var bp : optional) {
                    BlockHitResult result = RaycastUtils.createRealHitResult(bp);
                    onClickBlockExecute(result, clickDouble, clickMany);
                }
            }
        }
        if (!find) {
            Debug.chat(Component.literal("[多方块执行] 多方块结构与已记录的多方块无法匹配").withStyle(ChatFormatting.RED));
            toggleMultiBlockAutoExecuteState(tile, false);
        }
    }

    public void clearMultiBlockExecuteTasks() {
        Debug.chat(Component.literal("[自动多方块] 已清除 %d 个执行中多方块".formatted(screens.size()))
                .withStyle(ChatFormatting.GREEN));
        screens.clear();
        executeCursor = 0;
    }
    //    private static boolean AUTO_EXECUTE = false;
    public boolean isMultiBlockExecuting(TileInventory screen) {
        return screens.stream().anyMatch(i -> Objects.equals(screen.getPos(), i.getFirst()));
    }

    public void toggleMultiBlockAutoExecuteState(TileInventory screen, boolean val) {
        if (screen.isVirtual()) {
            Debug.chat(Component.literal("[自动多方块] 找不到该屏幕对应的方块位置"));
        } else {
            BlockPos pos = screen.getPos();
            screens.removeIf(i -> Objects.equals(i.getFirst(), pos));
            if (val) {
                screens.add(Pair.of(pos, screen));
            }
            Debug.chat(Component.literal("[自动多方块] 已切换该屏幕的自动执行状态,目前有 %d 个自动执行中(长按下蹲以全部关闭)".formatted(screens.size()))
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    public static record MultiblockOffset(int dy, Direction direction) {}

    @AllArgsConstructor
    public static class MultiBlockWithLocation {
        public RecipeDatabase.MultiBlockEntry multiBlockEntry;
        public MultiBlockLocation location;
    }

    @AllArgsConstructor
    public static class MultiBlockLocation {
        BlockPos leftDown;
        Vec3i left2Right;

        public BlockPos getComponentBlock(int x, int y) {
            return leftDown.offset(left2Right.getX() * x, y, left2Right.getZ() * x);
        }
    }

    public static List<BlockPos> getPositionByDirection(
            RecipeDatabase.MultiBlockEntry entry, BlockPos blockPos, MultiblockOffset direcion) {
        blockPos.offset(0, -direcion.dy, 0);
        int dy = direcion.dy;
        Direction dir = direcion.direction;
        var blockTypes = entry.blockTypes();
        List<BlockPos> pos = new ArrayList<>(9);
        BlockMatcher[] types = entry.blockTypes();
        for (int i = -1; i <= 1; ++i) {
            for (int j = 0; j <= 2; ++j) {
                // only match existing block

                if (blockTypes[3 * j + (i + 1)] != BlockMatcher.ANY_MATCH) {
                    pos.add(blockPos.offset(i * dir.getStepX(), j - dy, i * dir.getStepZ()));
                }
            }
        }
        return pos;
    }

    public static Collection<BlockPos> getOptionalActionFromDispenser(
            RecipeDatabase.MultiBlockEntry entry, ClientLevel world, BlockPos pos) {
        var lookup = entry.lookup();
        var optionalActionBlock = entry.optionalActionBlock();
        Collection<MultiBlockLocation> locations = lookup.lookup(world, pos);
        if (locations != null && !locations.isEmpty()) {
            Set<BlockPos> poseSet = new HashSet<>();
            for (var ml : locations) {
                for (var pt : optionalActionBlock) {
                    poseSet.add(ml.getComponentBlock(pt.x, pt.y));
                }
            }
            return poseSet;
        }
        return Set.of();
    }

    static Direction[] DIR_CONSIDER =
            new Direction[] {Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST};
    static Direction[] DIR_SYMM = new Direction[] {Direction.NORTH, Direction.WEST};

    public static MultiblockOffset matchDirection(
            RecipeDatabase.MultiBlockEntry entry, Level world, BlockPos blockPos) {
        var blockTypes = entry.blockTypes();
        var symm = entry.symm();
        position:
        for (int i = 0; i <= 2; ++i) {
            // match middle first
            BlockPos.MutableBlockPos middleBotton = blockPos.mutable().move(0, -i, 0);
            for (int s = 0; s <= 2; ++s) {
                Block block = world.getBlockState(middleBotton).getBlock();
                if (!blockTypes[1 + 3 * s].match(block)) {
                    continue position;
                }
                middleBotton.move(0, 1, 0);
            }
            // middle match
            // then consider symm
            Direction currentDir = null;
            directionMatch:
            for (Direction dir : symm ? DIR_SYMM : DIR_CONSIDER) {

                BlockPos.MutableBlockPos leftBotton =
                        blockPos.mutable().move(0, -i, 0).move(dir);
                for (int s = 0; s <= 2; ++s) {

                    Block block = world.getBlockState(leftBotton).getBlock();
                    if (!blockTypes[3 * s].match(block)) {
                        continue directionMatch;
                    }
                    leftBotton.move(0, 1, 0);
                }
                currentDir = dir;
                if (!symm) {
                    BlockPos.MutableBlockPos rightBotton =
                            blockPos.mutable().move(0, -i, 0).move(currentDir, -1);
                    // if not symm, still need check
                    for (int s = 0; s <= 2; ++s) {
                        Block block = world.getBlockState(rightBotton).getBlock();
                        if (!blockTypes[3 * s + 2].match(block)) {
                            continue directionMatch;
                        }
                        rightBotton.move(0, 1, 0);
                    }
                }
                return new MultiblockOffset(i, currentDir);
            }
        }
        return null;
    }

    public static boolean anyMatchMiddle(RecipeDatabase.MultiBlockEntry entry, Level world, BlockPos blockPos) {
        return matchDirection(entry, world, blockPos) != null;
    }
}
