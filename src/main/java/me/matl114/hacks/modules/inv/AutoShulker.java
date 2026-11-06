package me.matl114.hacks.modules.inv;

import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.InvTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.task.PostManager;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.RaycastUtils;
import me.matl114.utils.ScreenUtils;
import me.matl114.utils.entity.PlayerInputUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class AutoShulker extends BaseModule {
    public final ModulePath autoInv = makePath(Configs.INV_CONFIG, "auto-inv");
    public final ModulePath autoShulkerPath = autoInv.add("auto-shulker");

    public AutoShulker() {
        super("AutoShulker");
        bindFlag(autoShulker);
    }

    // 自动潜影盒子功能开关
    public final FlagRef autoShulker =
            flagBuilder(autoShulkerPath.add("enable")).build();

    // 自动潜影盒切换快捷键
    public final KeyBindRef autoShulkerToggleKey = moduleEntry(
                    autoShulkerPath.add("toggle-key"),
                    new MultiKeyBind(), // 默认按键 H
                    autoShulkerPath.add("enable") // 关联自动潜影盒开关
                    )
            .build();
    // todo: add steal

    // 0 Tick 偷取开关（潜影盒专用）
    public final FlagRef autoShulker0TickSteal =
            flagBuilder(autoShulkerPath.add("0tick-steal")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPostScheduleSendPoint().getChannel(ServerboundUseItemOnPacket.class),
                this::onClickShulkerBoxOrPlaceShulkerBox);
    }

    public void onClickShulkerBoxOrPlaceShulkerBox(Event<ServerboundUseItemOnPacket> event) {
        if (event.isCancelled()) return;
        if (autoShulker.get()) {
            ServerboundUseItemOnPacket packet = event.context;
            //#if MC >= 260300
            //$$ BlockHitResult hitResult = packet.hitResult();
            //#else
            BlockHitResult hitResult = packet.getHitResult();
            //#endif
            boolean hasShift = mc.player.isShiftKeyDown();
            BlockState state = mc.level.getBlockState(hitResult.getBlockPos());
            if (state.getBlock() instanceof ShulkerBoxBlock
                    && !hasShift
                    && mc.level.getBlockEntity(hitResult.getBlockPos()) instanceof ShulkerBoxBlockEntity bl) {
                int size = InvTasks.predictOpenVanillaContainerSize(hitResult.getBlockPos());
                // can open
                if (size > 0) {
                    // interact shulker
                    if (autoShulker0TickSteal.get()) {
                        InvTasks.executePredictInventoryAction(bl, handler -> {
                            for (var i = 0; i < size; ++i) {
                                mc.gameMode.handleContainerInput(
                                        handler.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
                            }
                        });
                        int tick = Tasks.getTick();
                        // add timeout
                        ScreenUtils.getOpenScreenFuture()
                                .thenRunAsync(
                                        () -> {
                                            if (tick + 4 > Tasks.getTick()) {
                                                mc.player.closeContainer();
                                            }
                                        },
                                        mc);
                    } else {
                        int tick = Tasks.getTick();
                        ScreenUtils.getOpenScreenFuture().thenRun(() -> {
                            if (mc.player.containerMenu != mc.player.inventoryMenu) {
                                if (tick + 4 <= Tasks.getTick()) {
                                    return;
                                }
                                for (var i = 0; i < size; ++i) {
                                    mc.gameMode.handleContainerInput(
                                            mc.player.containerMenu.containerId,
                                            i,
                                            0,
                                            ContainerInput.QUICK_MOVE,
                                            mc.player);
                                }
                            }
                            mc.player.closeContainer();
                        });
                    }
                }
            } else {
                if (hasShift) {
                    mc.player.setShiftKeyDown(false);
                    PlayerInputUtils.of(mc.player).sneak(false).sendPlayerSneakUpdatePacket();
                    ClientPlayerAccess.of(mc.player).resyncSneak();
                }
                BlockPos placedBlock = hitResult.getBlockPos().relative(hitResult.getDirection());
                BlockState placedState = mc.level.getBlockState(placedBlock);

                if (placedState.getBlock() instanceof ShulkerBoxBlock) {
                    BlockHitResult hitResult1 = RaycastUtils.createRealHitResult(placedBlock);
                    // mc.gameMode.interactBlock(mc.player, InteractionHand.MAIN_HAND, hitResult1);
                    PostManager.INSTANCE.addNextTransactionAction((ch) -> {
                        InteractionTasks.interactBlock(InteractionHand.MAIN_HAND, hitResult1, true);
                    });
                }
            }
        }
    }
}
