package me.matl114.hacks.modules.interact;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.IntStream;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.task.DisablerManager;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.collections.FlagEntry;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

public class AutoPlate extends BaseModule {
    public AutoPlate() {
        super("AutoPlate");
        bindFlag(enable);
    }

    public final ModulePath autoPlate = makePath(Configs.INTERACT_CONFIG, "place-utils.auto-plate");

    public final FlagRef enable = flagBuilder(autoPlate.addEnable()).build();

    public final KeyBindRef hotkey = moduleEntry(autoPlate.addHotkey(), new MultiKeyBind(), autoPlate.addEnable())
            .build();

    public List<Vec3i> blocksSeq = new ArrayList<>();

    public final EnumRef<LegalInteractMode> mode = builder(
                    autoPlate.add("mode"), LegalInteractMode.class)
            .defaultValue(LegalInteractMode.NONE)
            .build();

    public final FlagRef airplace = flagBuilder(autoPlate.add("air-place")).build();

    public final IntRef delay = intBuilder(autoPlate.add("delay"))
            .defaultValue(5)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef mul = intBuilder(autoPlate.add("multiply"))
            .defaultValue(1)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final DoubleRef expandRange = doubleBuilder(autoPlate.add("expand-range"))
            .defaultValue(2.0D)
            .validator(Configs.doubleRange(0.0d, 100.0d))
            .build();

    public final DoubleRef range = doubleBuilder(autoPlate.add("interact-range"))
            .defaultValue(5.0D)
            .validator(Configs.doubleRange(0.0D, 100.0D))
            .updateListener(s -> blocksSeq = MathUtils.create2DPointListInRange(s, 0))
            .build();

    public final IntRef depth = intBuilder(autoPlate.add("fill-depth"))
            .defaultValue(0)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final FlagRef copyState = builder(autoPlate.add("copy-state"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef useBlockRotate =
            flagBuilder(autoPlate.add("use-block-rotate")).build();

    public final FlagRef returnBlock =
            flagBuilder(autoPlate.add("ghost-hand-swap-back")).build();

    public final FlagRef swingHand = builder(autoPlate.add("swing-hand"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef render = flagBuilder(autoPlate.add("render")).build();

    public final NBTRef<WrapColor> color = builder(autoPlate.add("render-color"), WrapColor.class)
            .defaultValue(new WrapColor((Color.GREEN)))
            .build();

    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onInput);
        registerListener(RenderListener.getRender3DEvent(), this::onRender3D);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onPresetReload);
    }

    final List<BlockPos> placeList = new ArrayList<>();
    Optional<BlockState> placeState = Optional.empty();
    final RenderCollector<AABB> drawOutlines = RenderCollectors.createBoxCollector(true, false, false);

    public void refreshState() {
        drawOutlines.clear();
        placeList.clear();
        placeState = Optional.empty();
        AABB playerBox = mc.player.getBoundingBox().inflate(expandRange.get(), 0, expandRange.get());
        AABB checkBox = new AABB(
                playerBox.minX,
                playerBox.minY - expandRange.get(),
                playerBox.minZ,
                playerBox.maxX,
                playerBox.maxY,
                playerBox.maxZ);
        List<BlockPos> collisions = CollisionUtil.getIntersectingBlockPositions(mc.level, checkBox, false);
        if (collisions.isEmpty()) {
            return;
        }
        int maxY = collisions.stream().mapToInt(BlockPos::getY).max().getAsInt();

        placeList.addAll(blocksSeq.stream()
                .flatMap(s -> IntStream.range(0, depth.get() + 1)
                        .mapToObj(j -> new BlockPos(
                                s.getX() + mc.player.getBlockX(), maxY - j, s.getZ() + mc.player.getBlockZ())))
                .toList());
        placeList.forEach(s -> drawOutlines.submit(new AABB(s), color.get().withAlpha(255)));

        if (copyState.get()) {
            List<BlockPos> filteredPos =
                    collisions.stream().filter(s -> s.getY() == maxY).toList();
            placeState = filteredPos.stream()
                    .filter(s -> {
                        BlockState state = mc.level.getBlockState(s);
                        return !state.isAir() && !state.liquid();
                    })
                    .min(Comparator.comparingDouble(s -> s.distToCenterSqr(mc.player.position())))
                    .map(mc.level::getBlockState);
        }
    }

    int timer;

    public void onInput() {
        if (checkNull()) return;
        if (enable.get()) {
            if (++timer >= delay.get()) {
                timer = 0;
                refreshState();
                if (!placeList.isEmpty()) {
                    tickPlace();
                }
            }
        }
    }

    public int supplyBlocks(Block needBlock) {
        Item needItem = needBlock.asItem();
        if (needItem == Items.AIR) return -1;
        var entry = InventoryUtils.findPlayerItem((item) -> item.getItem() == needItem, true, false);
        return entry == null ? -1 : entry.index();
    }

    public IndexEntry<ItemStack> supplyAnyBlocks() {
        return InventoryUtils.findPlayerItem((item) -> item.getItem() instanceof BlockItem, true, false);
    }

    public void tickPlace() {
        int cnt = 0;
        int multiply = ((DisablerManager.INSTANCE.isMultiRotPlaceCheckDisabled(
                        mode.get().canMultiRotPlace())))
                ? mul.get()
                : 1;
        List<Runnable> stack = new ArrayList<>(multiply);

        for (var bp : placeList) {
            BlockState clientState = mc.level.getBlockState(bp);
            if (!clientState.isAir() && !clientState.liquid() && !clientState.canBeReplaced()) {
                continue;
            }
            int supplyBlock;
            BlockState state;
            if (placeState.isPresent()) {
                supplyBlock = supplyBlocks(placeState.get().getBlock());
                if (supplyBlock == -1) {
                    break;
                }
                state = placeState.get();
            } else {
                var entry = supplyAnyBlocks();
                if (entry == null) {
                    break;
                }
                if (entry.val().getItem() instanceof BlockItem bl) {
                    supplyBlock = entry.index();
                    state = bl.getBlock().defaultBlockState();
                } else {
                    break;
                }
            }
            if (supplyBlock == -1) break;
            FlagEntry<BlockHitResult> blockHitResult = InteractionTasks.createSpecificStateHitResult(
                    bp, state, airplace.get(), !mode.get().isLegal());
            if (InteractUtils.canInteractAndPlace(mc.player, blockHitResult)
                    && InteractExtra.INSTANCE.isWithinInteractRange(
                            mc.player.position(), blockHitResult.val().getBlockPos(), range.get())
                    && InteractUtils.getBlockPlacement(state.getBlock(), mc.player, mc.level, blockHitResult.val())
                            != null) {
                if (!InteractionTasks.canHandleInteract(mode.get(), blockHitResult.val())) break;
                Runnable runnable = InvExtra.INSTANCE.swapItemToHand(supplyBlock, false, GhostHandMode.INV_SWAP);
                if (runnable == null) break;
                stack.add(runnable);
                if (useBlockRotate.get()) {
                    BlockRotate.INSTANCE.addTempStateSchematic(bp, state);
                }
                boolean completed = InteractionTasks.handlePlaceMode(
                        mode.get(), blockHitResult.val(), InteractionHand.MAIN_HAND, swingHand.get());
                if (completed) {
                    mc.level.setBlock(bp, state, WorldUtils.UPDATE_BLOCK_NO_PHYSICS);
                }
                cnt += 1;
                if (!completed || cnt >= multiply) {
                    break;
                }
            }
        }
        if (returnBlock.get()) {
            int size = stack.size();
            for (var i = size - 1; i >= 0; i--) {
                InteractionTasks.finishInteractGhostHand(mode.get(), stack.get(i));
            }
        }
    }

    public void onRender3D(Event<Render3D> eventVDraw) {
        if (enable.get() && render.get()) {
            RenderUtils.startDrawVirtual(eventVDraw.context.stack());
            try {
                drawOutlines.render3D(eventVDraw.context.stack(), eventVDraw.context.partialTicks());
            } finally {
                RenderUtils.stopDrawVirtual(eventVDraw.context.stack());
            }
        }
    }

    public void onPresetReload(Event<EventContainer<ModulePreset>> event) {
        ModulePreset preset = event.context.getValue();
        if (preset.isAirplacePreset()) airplace.set(preset.getAirplace(airplace.get()));
        if (preset.isSwingPlacePreset()) swingHand.set(preset.getSwingPlace(swingHand.get()));
        if (!preset.isOptionPreset()) {
            mode.set(LegalInteractMode.getFromPreset(preset));
            airplace.set(!preset.hasAC());
        }
    }
}
