package me.matl114.hacks.modules.survival;

import java.util.List;
import java.util.function.Consumer;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.Render2D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.interact.Interact;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hacks.utils.render.RenderElements;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.render.RenderCollector;
import me.matl114.versioned.api.VItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class BlockFarm extends BaseModule {
    public BlockFarm() {
        super("BlockFarm");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "survival-mine-utils.block-farm");

    {
        portConfigs(makePath(Configs.MINE_CONFIG, "mine-utils.block-farm"), root);
    }

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final IntRef delay = intBuilder(root.add("delay"))
            .defaultValue(6)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef mul = intBuilder(root.add("multiply"))
            .defaultValue(9)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final FlagRef enableWhiteList =
            flagBuilder(root.add("white-list-enable")).build();

    public final NBTRef<EntrySet<Item>> whiteList = builder(root.add("white-list"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.ITEM, List.of(Items.ENDER_CHEST, Items.BOOKSHELF)))
            .build();

    public final FlagRef render = flagBuilder(root.add("render")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getMineBlockAction(), this::onPlayerMineAttackBlock);
        registerListener(Listener.getPreHandleInputEvents(), this::onPreInput);
        registerListener(RenderListener.getRender2DEvent(), this::onRender2D);
    }

    @Override
    public void addCustomWidgets(
            Consumer<DrawableWidget> acceptor,
            int dx,
            int dy,
            int dblank,
            me.matl114.gui.interfaces.ElementFactory factory) {
        super.addCustomWidgets(acceptor, dx, dy, dblank, factory);
        acceptor.accept(factory.createTitle("widget.interact.interact-block.use-argument", 0, dblank, dx, dy));
    }

    BlockItem currentPlacingItem;
    RenderCollector<RenderElements.Text> textRenderer = RenderCollectors.createTextCollector();

    public void onPlayerMineAttackBlock(Event<HitResult> hitResultEvent) {
        if (enable.get()) {
            currentPlacingItem = null;
            if (hitResultEvent.context instanceof BlockHitResult hitResult) {
                BlockPos placePos = hitResult.getBlockPos();
                BlockState currentState;
                if (placePos != null
                        && !(currentState = mc.level.getBlockState(placePos)).isAir()
                        && !currentState.liquid()) {
                    Item it = currentState.getBlock().asItem();
                    if (it instanceof BlockItem bl
                            && bl != Items.AIR
                            && (!enableWhiteList.get() || whiteList.get().test(it))) {
                        currentPlacingItem = bl;
                    }
                }
            }
        } else {
            currentPlacingItem = null;
        }
    }

    int timer;

    public void onPreInput() {
        if (checkNull()) return;
        textRenderer.clear();
        if (enable.get() && currentPlacingItem != null) {
            var access = PlayerInteractionAccess.of(mc.gameMode);
            BlockPos pos = access.getCurrentMiningPos();
            BlockState state = mc.level.getBlockState(pos);
            if (state.getBlock() == currentPlacingItem.getBlock()) {

                if (++timer >= delay.get() && InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), pos)) {
                    timer = 0;
                    tickMineAndPlace(currentPlacingItem);
                }

                textRenderer.submit(
                        new RenderElements.Text(
                                Component.literal("Farm: %s"
                                        .formatted(BuiltInRegistries.ITEM
                                                .getKey(currentPlacingItem)
                                                .getPath())),
                                Vec3.atCenterOf(pos).add(0, 0.6, 0),
                                0.66F),
                        -1);
            }
        }
    }

    public IndexEntry<ItemStack> supplyItems(BlockItem blockItem) {
        return InventoryUtils.findBestPlayerItem(
                s -> {
                    if (s.getItem() == blockItem) {
                        return -(double) s.getCount();
                    } else {
                        return null;
                    }
                },
                true,
                false);
    }

    public void tickMineAndPlace(BlockItem blockItem) {
        int multiply = mul.get();
        Runnable callback = null;
        ItemStack currentTool = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        boolean isTool = VItem.getInstance().isTool(currentTool);
        var access = PlayerInteractionAccess.of(mc.gameMode);

        for (var i = 0; i < multiply; ++i) {
            if (isTool && callback != null) {
                callback.run();
                callback = null;
            }
            if (access.breakIfComplete()) {
                if (mc.player.getItemInHand(InteractionHand.MAIN_HAND).getItem() != blockItem) {
                    if (callback != null) {
                        callback.run();
                        callback = null;
                    }
                    var entry = supplyItems(blockItem);
                    if (entry != null) {
                        callback = InvExtra.INSTANCE.swapItemToHand(entry.index(), false, GhostHandMode.INV_SWAP);
                    } else {
                        break;
                    }
                }
                BlockPos pos = access.getCurrentMiningPos();
                if (!Interact.INSTANCE.placeBlock(pos)) {
                    break;
                }

            } else {
                break;
            }
        }
        if (callback != null) {
            callback.run();
            callback = null;
        }
    }

    public void onRender2D(Event<Render2D> vdraw) {
        if (enable.get() && render.get()) {
            vdraw.context.drawContext().pushMatrix();
            try {
                textRenderer.render2D(vdraw.context.drawContext(), vdraw.context.partialTicks());
            } finally {
                vdraw.context.drawContext().popMatrix();
            }
        }
    }
}
