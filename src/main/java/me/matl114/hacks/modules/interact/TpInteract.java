package me.matl114.hacks.modules.interact;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.*;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class TpInteract extends BaseModule {
    public final ModulePath tpInteract = makePath(Configs.INTERACT_CONFIG, "tp-interact");

    public static TpInteract INSTANCE;

    public TpInteract() {
        super("TpInteract");
        bindFlag(enable);
        INSTANCE = this;
    }

    public final FlagRef enable = flagBuilder(tpInteract.add("enable")).build();

    public final KeyBindRef keyBindRef = moduleEntry(
                    tpInteract.add("enable-hotkey"), new MultiKeyBind(), tpInteract.add("enable"))
            .build();

    public final FlagRef useFallMine =
            flagBuilder(tpInteract.add("mine-interact-use-fail-mine")).build();

    public final KeyBindRef tryTpSteal = hotkey(
                    Configs.INTERACT_CONFIG,
                    tpInteract.add("try-tp-steal-chest-key").toPath())
            .defaultValue(new MultiKeyBind())
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPacketPoint().getChannel(ServerboundUseItemOnPacket.class), this::onInteractBlock);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundInteractPacket.class), this::onInteractEntity);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class), this::onBlockMine);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
    }

    private final float ENABLE_NO_TP_DISTANCE = 1.14f;

    public void onInteractBlock(Event<ServerboundUseItemOnPacket> event) {
        if (event.isCancelled()) return;
        if (enable.get()) {
            var packetToSend = event.context;
            //#if MC >= 260300
            //$$ BlockHitResult hit = event.context.hitResult();
            //#else
            BlockHitResult hit = event.context.getHitResult();
            //#endif
            BlockPos blockPos = hit.getBlockPos();
            double distance = InteractExtra.INSTANCE.getBlockReachDistance() + ENABLE_NO_TP_DISTANCE;
            if (new AABB(blockPos).distanceToSqr(mc.player.getEyePosition()) > MathUtils.s2(distance)) {
                if (!mc.player.isShiftKeyDown() && tryTpSteal.get().isAllPressed()) {
                    int size = InvTasks.predictOpenVanillaContainerSize(blockPos);
                    if (size != 0) {

                        if (tpToBlock(
                                blockPos,
                                (sel) -> executeTp(sel, () -> {
                                    Debug.chat("[TpInteract] 尝试和物品栏交互");
                                    Listener.sendPacketNoEvents(packetToSend);
                                    InvTasks.executePredictInventoryAction(
                                            InventoryUtils.createInventory(Collections.nCopies(size, ItemStack.EMPTY)),
                                            (handler) -> {
                                                for (var i = 0; i < size; ++i) {
                                                    mc.gameMode.handleContainerInput(
                                                            handler.containerId,
                                                            i,
                                                            0,
                                                            ContainerInput.QUICK_MOVE,
                                                            mc.player);
                                                }
                                            });
                                }))) {
                            event.cancel();
                        }
                        return;
                    }
                }
                if (tpToBlock(blockPos, (sel) -> executeTp(sel, packetToSend))) {
                    event.cancel();
                }
            }
        }
    }


    public void onInteractEntity(Event<ServerboundInteractPacket> event) {
        if (event.isCancelled()) return;
        if (enable.get()) {
            var packet = event.context;
            // 26.2: ServerboundInteractPacket 只剩交互语义（攻击已拆为 ServerboundAttackPacket），
            // 故无需再过滤 ATTACK。
            {
                int entityId = packet.entityId;
                Entity entity = mc.level.getEntity(entityId);
                if (entity != null
                        && entity.getBoundingBox().distanceToSqr(mc.player.getEyePosition())
                                > MathUtils.s2(CombatTasks.getCombatExtra().getAttackRange() + ENABLE_NO_TP_DISTANCE)) {
                    if (tpToEntity(entity, packet)) {
                        event.cancel();
                    }
                }
            }
        }
    }

    public void onBlockMine(Event<ServerboundPlayerActionPacket> event) {
        if (event.isCancelled()) return;
        if (enable.get()) {
            var packet = event.context;
            switch (packet.getAction()) {
                case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                    BlockPos involvedBlock = packet.getPos();
                    // check y;
                    if (involvedBlock == null) return;
                    // filter "out of building height" shit
                    if (involvedBlock.getY() < (mc.level.getMinY() - 1)
                            || involvedBlock.getY() > (mc.level.getMinY() + mc.level.getHeight() + 1)) {
                        return;
                    }
                }
                default -> {
                    return;
                }
            }
            BlockPos blockPos = packet.getPos();
            double distance = InteractExtra.INSTANCE.getBlockReachDistance() + ENABLE_NO_TP_DISTANCE;
            if (new AABB(blockPos).distanceToSqr(mc.player.getEyePosition()) > MathUtils.s2(distance)) {
                ServerboundPlayerActionPacket packetToSend = event.context();
                if (tpToBlock(
                        blockPos,
                        useFallMine.get()
                                ? (selectedPos) -> executeTp(selectedPos, () -> {
                                    mc.getConnection().send(packetToSend);
                                    PlayerInteractionAccess access = PlayerInteractionAccess.of(mc.gameMode);
                                    if (packetToSend.getAction()
                                                    == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                                            && Objects.equals(access.getCurrentMiningPos(), packetToSend.getPos())
                                            && access.getCurrentFailBreakPos() == null) {
                                        access.sendFailBreakCurrentPos(null);
                                    }
                                })
                                : (sel) -> executeTp(sel, packetToSend))) {
                    event.cancel();
                }
            }
        }
    }

    public boolean tpAndInteractBlock(BlockHitResult hitResult, InteractionHand hand, boolean swing) {
        return tpToBlock(
                hitResult.getBlockPos(),
                (sel) -> executeTp(sel, () -> {
                    InteractionTasks.interactBlock(hand, hitResult, swing);
                }));
    }

    public boolean tpToBlock(BlockPos pos, Predicate<Vec3> callBack) {
        // compat Freecam
        Vec3 selectedPos = RenderUtils.getCameraEntityPos();
        double eyeHeight = mc.player.getEyeHeight(mc.player.getPose());
        if (MineTasks.distanceOutOfReach(pos, selectedPos.add(0, eyeHeight, 0))
                || MovTasks.ENGIN.checkEnvironmentCollision(mc.player, selectedPos, true)) {
            selectedPos = null;
            for (var deltaPos : InteractExtra.INSTANCE.getBlocksAround()) {
                Vec3 checkPos = Vec3.atBottomCenterOf(pos.offset(deltaPos)).add(0, 1E-4, 0);
                if (!MineTasks.distanceOutOfReach(pos, checkPos.add(0, eyeHeight, 0))
                        && !MovTasks.ENGIN.checkEnvironmentCollision(mc.player, checkPos, true)) {
                    selectedPos = checkPos;
                    break;
                }
            }
        }
        if (selectedPos == null) {
            logI18NSub("TpAct", "message.module.tp-interact.cannot-reach");
            return false;
        } else {
            return callBack.test(selectedPos);
        }
    }

    public boolean tpToEntity(Entity pos, Packet<?> packetToSend) {
        Vec3 selectedPos = RenderUtils.getCameraEntityPos();
        AABB entityBox = pos.getBoundingBox();
        double attackRange = CombatTasks.getCombatExtra().getAttackRange() + 1.0d;
        double eyeHeight = mc.player.getEyeHeight(mc.player.getPose());
        if (entityBox.distanceToSqr(selectedPos.add(0, eyeHeight, 0)) > MathUtils.s2(attackRange)) {
            selectedPos = null;
            // make an algorithm to
            BlockPos entityPos = pos.blockPosition();
            // todo: move this to CombatExtra or PositionPredictor or something
            for (var deltaPos : InteractExtra.INSTANCE.getBlocksAround()) {
                Vec3 checkPos =
                        Vec3.atBottomCenterOf(entityPos.offset(deltaPos)).add(0, 1E-4, 0);
                if (entityBox.distanceToSqr(checkPos.add(0, eyeHeight, 0)) < MathUtils.s2(attackRange)
                        && !MovTasks.ENGIN.checkEnvironmentCollision(mc.player, checkPos, true)) {
                    selectedPos = checkPos;
                    break;
                }
            }
        }
        if (selectedPos == null) {
            Debug.chat("[TpAct] Can not reach the target");
            return false;
        } else {
            return executeTp(selectedPos, packetToSend);
        }
    }

    public boolean executeTp(Vec3 pos, Runnable callback) {
        Vec3 current = mc.player.position();
        MovTasks.MovingContext context = MovTasks.createPlayerMovContext();
        List<Vec3> from = MovTasks.generateTpSequence(current, pos, false, 200, true);
        List<Vec3> to = MovTasks.generateTpSequence(pos, current, false, 200, true);
        if (from.isEmpty() || to.isEmpty()) {
            Debug.chat("[TpAct] Can not reach the target");
            return false;
        } else {
            if (RenderTasks.DEBUG_RENDER_INTERACTION) {
                RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
                        RenderTasks.DEBUG_TICK,
                        new RenderTasks.BoxObject(
                                mc.player.dimensions.makeBoundingBox(pos),
                                ColorUtils.withAlpha(Color.MAGENTA, 0.25F))));
            }
            List<MovTasks.MovInfo> moveInfo = new ArrayList<>();
            moveInfo.addAll(MovTasks.createMovInfoList(from));
            moveInfo.addAll(MovTasks.createMovInfoList(to));
            var actions = MovTasks.createMovingPacketsForMovSequence(context, moveInfo, false, true);
            for (var i = 0; i < from.size(); ++i) {
                actions.get(i).run();
            }
            callback.run();
            for (int i = from.size(); i < actions.size(); ++i) {
                if (actions.get(i).success) {
                    actions.get(i).run();

                } else {
                    List<MovTasks.MovInfo> leftTasks = moveInfo.subList(i, actions.size());
                    Tasks.scheduleDelayed(
                            () -> {
                                MovTasks.scheduleFarawayMoveInternal(leftTasks, false, context.resetTick(), true);
                            },
                            1);
                    break;
                }
            }
            MovTasks.setupAutoResync();
            ClientPlayerAccess.of(mc.player).setForceNoFall(true);
            return true;
        }
    }

    public boolean executeTp(Vec3 pos, Packet<?>... packetToSend) {
        return executeTp(pos, () -> {
            for (Packet<?> packet : packetToSend) {
                Listener.sendPacketNoEvents(packet);
            }
        });
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        if (event.context().getValue().isOptionPreset()) return;
        switch (event.context().getValue()) {
            case HACKING, VANILLA -> enable.set(true);
            default -> enable.set(false);
        }
    }
}
