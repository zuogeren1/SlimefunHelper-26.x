package me.matl114.hacks.modules.combat;

import com.google.common.base.Preconditions;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.CombatTasks;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.RenderTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.ColorUtils;
import me.matl114.utils.Debug;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class BowTp extends BaseModule {
    public final ModulePath bowAtt = makePath(Configs.COMBAT_CONFIG, "bow-att");

    public BowTp() {
        super("BowTp");
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(bowAtt.add("bowtp-enable")).build();
    public KeyBindRef keyBind = moduleEntry(
                    bowAtt.add("bowtp-enable-hotkey"), new MultiKeyBind(), bowAtt.add("bowtp-enable"))
            .build();

    public DoubleRef tpDistance = builder(bowAtt.add("bowtp-distance"), DoubleRef.TYPE)
            .defaultValue(80.0D)
            .validator(Configs.doubleRange(-200.0D, 200.0D))
            .build();

    public DoubleRef targetingDistance = builder(bowAtt.add("bowtp-target-distance"), DoubleRef.TYPE)
            .defaultValue(80.0D)
            .validator(Configs.doubleRange(-200.0D, 200.0D))
            .build();

    public FlagRef render = flagBuilder(bowAtt.add("bowtp-render-target")).build();

    public DoubleRef deltaY = builder(bowAtt.add("bowtp-start-delta-y"), DoubleRef.TYPE)
            .defaultValue(0.0D)
            .validator(Configs.doubleRange(-200, 200))
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class), this::onBowAction, -999);
        registerListener(Listener.getPostGameTick(), this::updateRenderCollector);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
    }

    public void onBowAction(Event<ServerboundPlayerActionPacket> packetEvent) {
        if (packetEvent.isCancelled()) return;
        var actionPacket = packetEvent.context();
        if (actionPacket.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
                && enable.get()
                && canTp()) {
            if (mc.player != null && mc.player.getUseItem().getItem() instanceof BowItem bowItem) {
                var targetSelector = CombatTasks.getTargetSelector();
                Entity entity = targetSelector.searchAttackEntity(
                        targetingDistance.get(), true, e -> targetSelector.checkWeapon(e, true));
                // rewrite tp system
                if (entity != null) {
                    Debug.chat(Component.literal("[Bow Attack] Aim at %s"
                                    .formatted(entity instanceof Player ? "player " : "entity "))
                            .append(EntityUtils.getEntityDisplayable(entity))
                            .withStyle(ChatFormatting.GREEN));
                    onBowTpAttack(packetEvent, entity);
                } else {
                    return;
                }
            }
        }
    }

    public void onBowTpAttack(Event<ServerboundPlayerActionPacket> packetEvent, Entity target) {
        var player = mc.player;
        Deque<MovTasks.MovInfo> movementStack = new ArrayDeque<>();
        Deque<MovTasks.MovInfo> shouldMoveBackStack = new ArrayDeque<>();
        Vec3 currentStartPos = mc.player.position();
        float pitch = mc.player.getXRot();
        float yaw = mc.player.getYRot();
        movementStack.addLast(MovTasks.MovInfo.createNoUpdate(mc.player.position()));
        shouldMoveBackStack.addFirst(MovTasks.MovInfo.createNoUpdate(mc.player.position()));
        // mace hack、
        if (!processExactBowTp(player, target, movementStack, shouldMoveBackStack)) {
            return;
        }
        if (!processBowVClip(player, target, movementStack, shouldMoveBackStack)) {
            return;
        }

        // attacking creative player with mace at same height will cause falldamage calculate(caused by the shit code
        // below: we should resetHeight even if backStack.size() = 1
        //            Vec3d lastlyPos = movementStack.peekLast().vec3d();
        // final pos lies in attack range
        // remove final pos check because already checked
        // start execute
        var iter = movementStack.iterator();
        Preconditions.checkArgument(iter.hasNext());
        Vec3 vec3d1 = iter.next().vec3d();
        MovTasks.MovingContext movingContext = MovTasks.MovingContext.create(vec3d1);
        List<MovTasks.MovInfo> moveInfos = new ArrayList<>();
        iter.forEachRemaining(moveInfos::add);
        shouldMoveBackStack.removeFirst();
        int movingToBundleCnt = moveInfos.size();
        moveInfos.addAll(shouldMoveBackStack);
        //                MovTasks.scheduleFarawayMoveInternal(moveInfos, false, movingContext, false);
        // attack
        List<MovTasks.StepActionBundle> actionBundles =
                MovTasks.createMovingPacketsForMovSequence(movingContext, moveInfos, true, false);
        for (int i = 0; i < movingToBundleCnt; ++i) {
            actionBundles.get(i).run();
        }
        // processDuplicateAttack(player, target, moveInfos, movingContext, maceAttack);
        Listener.sendPacketNoEvents(packetEvent.context());
        for (int i = movingToBundleCnt; i < actionBundles.size(); ++i) {
            if (actionBundles.get(i).success) {
                actionBundles.get(i).run();

            } else {
                List<MovTasks.MovInfo> leftTasks = moveInfos.subList(i, moveInfos.size());
                Tasks.scheduleDelayed(
                        () -> {
                            MovTasks.scheduleFarawayMoveInternal(leftTasks, false, movingContext.resetTick(), true);
                        },
                        1);
                break;
            }
        }
        // force resync position to origin
        mc.player.setPos(currentStartPos);
        mc.player.setXRot(pitch);
        mc.player.setYRot(yaw);
        // feature
        MovTasks.setupAutoResync();

        // check fall damage
        ClientPlayerAccess.of((LocalPlayer) player).setForceNoFall(true);
        // in case that resync packet cause OnGround falldamage
        player.setOnGround(false);
        packetEvent.cancel();
        // next, can continue
    }

    private boolean processExactBowTp(
            Player player,
            Entity target,
            Deque<MovTasks.MovInfo> movementStack,
            Deque<MovTasks.MovInfo> shouldMoveBackStack) {
        // how to manage exact attack and mace hack
        // fixed : can not tp to shulker inside
        // should teleport the player to the pos of target entity
        PositionPredict positionPredict = CombatTasks.getPositionPredict();
        double range = this.targetingDistance.get();
        Vec3 current = player.position();
        // feat : teleporting position should met the need of antishield
        Vec3 targetPos = positionPredict.getExactAttackPosition(target);

        if (targetPos != null) {
            // target pos should be higher
            targetPos = targetPos.add(0, getFireArrowPositionHeight(player, target), 0);
            // common atttack?
            if (RenderTasks.DEBUG_RENDER_COMBAT)
                RenderTasks.drawBox(player.dimensions.makeBoundingBox(targetPos), 150, Color.GREEN);
            List<Vec3> tpSequence = MovTasks.generateTpSequence(current, targetPos, false, 1.5 * range, true);
            List<Vec3> tpSequenceBack = MovTasks.generateTpSequence(targetPos, current, false, 1.5 * range, true);
            if ((tpSequence.size() == 2 || tpSequence.size() == 4)
                    && (tpSequenceBack.size() == 2 || tpSequenceBack.size() == 4)) {
                // correct tp sequence
                // try compact mace hack
                if (tpSequence.size() == 2) {
                    // can directly tp
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(1)));

                } else {
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(1)));
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(2)));
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(3)));
                }
                int size = tpSequenceBack.size();

                for (int i = size - 2; i >= 0; --i) {
                    shouldMoveBackStack.addFirst(MovTasks.MovInfo.create(tpSequenceBack.get(i)));
                }
                return true;
            } else {
                Debug.chat("[Bow Attack] Can not reach the target");
            }
        }
        return false;
    }

    private boolean processBowVClip(
            Player player,
            Entity target,
            Deque<MovTasks.MovInfo> movementStack,
            Deque<MovTasks.MovInfo> shouldMoveBackStack) {
        if (canTp()) {
            double maxMace = tpDistance.get();
            player.setOnGround(false);
            Vec3 playerPos = movementStack.peekLast().vec3d();
            double deltaY = target.getY() - playerPos.y;
            // error: down search returns negative value
            double height = MovTasks.searchFirstNoCollisionSpaceYHeight(
                    playerPos.add(0, maxMace, 0), 0, maxMace - 2 - deltaY, false);
            // +height
            double maceHeightMultiplier = maxMace + height;
            // the stack already contains the height.
            double minAvailableHeight = 0.0F;

            if (maceHeightMultiplier - minAvailableHeight > 0.0) {
                Debug.chat(Component.literal("[Bow Attack] Bow Attack Simulation: simulate height %.2f"
                                .formatted(maceHeightMultiplier))
                        .withStyle(ChatFormatting.GREEN));
                movementStack.addLast(MovTasks.MovInfo.createNoUpdate(playerPos.add(0, maceHeightMultiplier, 0)));
                // Debug.info("add", playerPos.add(0, maceHeightMultiplier,0));
                // create the movement with pitchYaw update
                movementStack.addLast(new MovTasks.MovInfo(
                        playerPos.add(0, minAvailableHeight, 0), null, false, new Vec2(89, mc.player.getYRot())));

                return true;
            }
        }
        return false;
    }

    private void updateRenderCollector() {
        targetCollector.clear();
        if (!enable.get() || mc.player == null || !render.get()) return;
        if (!mc.player.isUsingItem() || !(mc.player.getActiveItem().getItem() instanceof BowItem)) return;
        var targetSelector = CombatTasks.getTargetSelector();
        Entity entity = targetSelector.searchAttackEntity(
                targetingDistance.get(), true, e -> targetSelector.checkWeapon(e, true));
        if (entity != null) {
            float dist = entity.distanceTo(mc.player);
            float opacity = Math.min(0.6F, 0.10F + dist * 0.02F);
            targetCollector.submit(
                    entity, ColorUtils.withAlpha(Color.GREEN, opacity).getRGB());
        }
    }

    public void onRender(Event<Render3D> stackE) {
        if (!enable.get() || !render.get() || mc.player == null) return;
        RenderUtils.startDrawVirtual(stackE.context.stack());
        try {
            targetCollector.render3D(stackE.context.stack(), stackE.context.partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(stackE.context.stack());
        }
    }

    private final RenderCollector<Entity> targetCollector =
            RenderCollectors.createEntityBoxCollector(false, true, false);

    private double getFireArrowPositionHeight(Player player, Entity target) {
        // real height is owner.getEyeY() - 0.10000000149011612
        return target.getBoundingBox().getYsize() - player.getEyeHeight(player.getPose()) + 0.11 + deltaY.get();
    }

    private boolean canTp() {
        return tpDistance.get() > 0.0D;
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        if (event.context().getValue().isOptionPreset()) return;
        ModulePreset preset = event.context().getValue();
        switch (preset) {
            case HACKING, VANILLA -> {
                if (tpDistance.get() < 0.0D) {
                    tpDistance.set(-tpDistance.get());
                }
            }
            default -> {
                if (tpDistance.get() > 0.0D) {
                    tpDistance.set(-tpDistance.get());
                }
            }
        }
    }
}
