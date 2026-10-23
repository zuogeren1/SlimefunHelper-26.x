package me.matl114.hacks.modules.combat;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.CombatTasks;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.modules.move.RotationManager;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.EnumRef;
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
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class BowEnhance extends BaseModule {
    public final ModulePath bowAtt = makePath(Configs.COMBAT_CONFIG, "bow-att");

    public BowEnhance() {
        super("BowEnhance");
        bindFlag(enable);
    }

    public FlagRef enable = flagBuilder(bowAtt.add("bow-enhance")).build();

    public KeyBindRef hotkey = moduleEntry(
                    bowAtt.add("bow-enhance-hotkey"), new MultiKeyBind(), bowAtt.add("bow-enhance"))
            .build();

    public FlagRef enableAim = flagBuilder(bowAtt.add("aim-enable")).build();

    public FlagRef enableTp = flagBuilder(bowAtt.add("tp-enable")).build();

    public EnumRef<LegalInteractMode> mode = builder(
                    bowAtt.add("targeting-mode"), LegalInteractMode.class)
            .defaultValue(LegalInteractMode.USEITEM_PACKET)
            .build();

    public DoubleRef tpDistance = builder(bowAtt.add("tp-accelerate"), DoubleRef.TYPE)
            .defaultValue(150.0D)
            .validator(Configs.doubleRange(-200.0D, 200.0D))
            .show(enableTp::get)
            .build();

    public FlagRef enhanceTp = flagBuilder(bowAtt.add("tp-accelerate-exact-tp"))
            .show(enableTp::get)
            .build();
    // todo: use onGround Packets to reduce low version problems
    public FlagRef lowVersion =
            flagBuilder(bowAtt.add("version-lower-than-121")).build();

    public FlagRef renderTarget = flagBuilder(bowAtt.add("render-target")).build();

    public boolean canTp() {
        return enableTp.get() && tpDistance.get() > 1E-7;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class), this::onBowAction, 999);
        registerListener(Listener.getPostGameTick(), this::updateRenderCollector);
        registerListener(RenderListener.getRender3DEvent(), this::onRenderAimTarget);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
    }

    public void onBowAction(Event<ServerboundPlayerActionPacket> actionEvent) {
        if (actionEvent.isCancelled()) return;
        if (!enable.get()) return;
        var actionPacket = actionEvent.context();
        if (actionPacket.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM && mc.player != null) {
            // delay tp do not run BowAction logic and let it go
            // may not using item anymore

            // ret
            if (!mc.player.isUsingItem()) {
                return;
            }
            // run main logic
            ItemStack stack = mc.player.getUseItem();
            // only consider BowItem
            if (stack.isEmpty()) return;

            if ((stack.getItem() instanceof BowItem) || (stack.getItem() instanceof TridentItem)) {
                float velocity;
                boolean searchEntity = enableAim.get();
                Entity targetEntity;
                if (searchEntity) {
                    Entity entity = TargetSelector.INSTANCE.searchAimableEntity(stack.getItem() instanceof BowItem);
                    if (entity != null) {
                        Debug.chat(Component.literal("[Bow Aim] Aim at %s"
                                        .formatted(entity instanceof Player player ? "player " : "entity "))
                                .append(EntityUtils.getEntityDisplayable(entity))
                                .withStyle(ChatFormatting.GREEN));
                        // calculate lerp by speed
                        targetEntity = entity;
                    } else {

                        targetEntity = null;
                    }
                } else {
                    targetEntity = null;
                }
                // goes accelerate with bowTP
                if (stack.getItem() instanceof BowItem) {
                    velocity = (72000 - mc.player.getUseItemRemainingTicks()) / 20F;
                    velocity = (velocity * velocity + velocity * 2) / 3;
                    if (velocity > 1) velocity = 1;
                    velocity = (velocity * 3.0F);
                } else if (stack.getItem() instanceof TridentItem) {
                    velocity = 2.5F;
                } else {
                    // whatever
                    velocity = 3.0F;
                }
                switch (mode.get()) {
                    case LEGACY_SLIENT_ROT -> bowActionMovement(actionEvent, targetEntity, velocity);
                    case DELAY_MOVEMENT, MOVEMENT_POST -> bowActionDelayMovement(actionEvent, targetEntity, velocity);
                    case USEITEM_PACKET -> bowActionInteractItem(actionEvent, targetEntity, velocity);
                    default -> bowActionInteractItem(actionEvent, targetEntity, velocity);
                }
            }
        }
    }

    private void updateRenderCollector() {
        targetCollector.clear();
        if (!enable.get() || !enableAim.get() || !renderTarget.get() || mc.player == null || !mc.player.isUsingItem()) {
            return;
        }
        ItemStack itemInUse = mc.player.getActiveItem();
        if (itemInUse.isEmpty()
                || (!(itemInUse.getItem() instanceof ProjectileWeaponItem)
                        && !(itemInUse.getItem() instanceof TridentItem))) {
            return;
        }
        Entity entity = CombatTasks.getTargetSelector().searchAimableEntity(itemInUse.getItem() instanceof BowItem);
        if (entity != null) {
            targetCollector.submit(
                    entity, ColorUtils.withAlpha(Color.GREEN, 0.25F).getRGB());
        }
    }

    public void onRenderAimTarget(Event<Render3D> stackE) {
        if (!enable.get() || !enableAim.get() || !renderTarget.get() || mc.player == null) return;
        RenderUtils.startDrawVirtual(stackE.context.stack());
        try {
            targetCollector.render3D(stackE.context.stack(), stackE.context.partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(stackE.context.stack());
        }
    }

    private final RenderCollector<Entity> targetCollector =
            RenderCollectors.createEntityBoxCollector(false, true, false);

    public void bowActionMovement(Event<ServerboundPlayerActionPacket> event, @Nullable Entity entity, float initialVelocity) {
        Vec2 playerPitchYaw = new Vec2(mc.player.getXRot(), mc.player.getYRot());
        var facing = entity == null
                ? mc.player.getLookAngle().normalize()
                : CombatTasks.getPositionPredict()
                        .predictAimPositionForEntity(entity, 3600000)
                        .subtract(mc.player.getEyePosition());
        Entity nowMePointingTheEntity = (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY)
                ? ((EntityHitResult) mc.hitResult).getEntity()
                : null;
        if (nowMePointingTheEntity != null
                && nowMePointingTheEntity.position().distanceToSqr(mc.player.getEyePosition()) > 50) {
            nowMePointingTheEntity = null;
        }
        boolean makeAim = enableAim.get();
        float finalVelocity = initialVelocity;
        make_movements:
        {
            if (canTp()) {
                // add movements to accelerate the projectile
                boolean exactTp = enhanceTp.get();
                double range = tpDistance.get();
                Vec3 facingNorm = facing.normalize();
                Vec3 oppositeFacing = Vec3.ZERO.subtract(facingNorm);
                Vec3 finalMove = Vec3.ZERO;
                Vec3 currentPlayerPos = mc.player.position();
                boolean shouldResetRotation = true;
                test_tp_position:
                {
                    // optimize the collision check by caching List of Boxes
                    MovTasks.CollisionContext context = new MovTasks.CollisionCache(
                            mc.player,
                            currentPlayerPos,
                            currentPlayerPos.add(oppositeFacing.scale(range + 1.0d)),
                            true);
                    double test = range;
                    for (; test > 10.0D; test -= 1.0D) {
                        if (exactTp) {
                            Vec3 oppositeMultiply = oppositeFacing.scale(test);
                            if (MovTasks.validMoveTo(
                                    context,
                                    currentPlayerPos.add(oppositeMultiply),
                                    Vec3.ZERO.subtract(oppositeMultiply))) {
                                finalMove = oppositeMultiply;
                                break test_tp_position;
                            }
                        } else {
                            if (MovTasks.validMoveToAndBack(context, currentPlayerPos, oppositeFacing.scale(test))) {
                                finalMove = oppositeFacing.scale(test);
                                break test_tp_position;
                            }
                        }
                    }
                    // t < 10
                    // check again
                    test = 10.0D;
                    for (; test > 0.0D; test -= 0.5D) {
                        Vec3 oppositeMultiply = oppositeFacing.scale(test);
                        if (exactTp) {
                            if (MovTasks.validMoveTo(
                                    context,
                                    currentPlayerPos.add(oppositeMultiply),
                                    Vec3.ZERO.subtract(oppositeMultiply))) {
                                finalMove = oppositeMultiply;
                                break test_tp_position;
                            }
                        } else {
                            if (MovTasks.validMoveToAndBack(context, currentPlayerPos, oppositeMultiply)) {
                                finalMove = oppositeMultiply;
                                break test_tp_position;
                            }
                        }
                        Vec3 oppoHorizontal = new Vec3(oppositeMultiply.x, 0.0d, oppositeMultiply.z);
                        Vec3 simulateMove = context.simulateMovement(mc.player, currentPlayerPos, oppoHorizontal);
                        if (MovTasks.validMovementAsServer(oppoHorizontal, simulateMove)) {
                            Vec3 simulateDownMove = context.simulateMovement(
                                    mc.player, currentPlayerPos.add(simulateMove), new Vec3(0, oppositeMultiply.y, 0));
                            Vec3 wholeMovement = simulateMove.add(simulateDownMove);
                            // y does not matter , xz matters
                            if (MovTasks.validMoveTo(
                                    context, currentPlayerPos.add(wholeMovement), wholeMovement.scale(-1))) {
                                finalMove = wholeMovement;
                                break test_tp_position;
                            }
                        }
                    }
                    // should strengthen move when test < 10,
                }
                if (finalMove.lengthSqr() > 1E-4) {
                    // 随便写的阈值 速度太快不需要转向
                    double velocity = finalMove.length();
                    finalVelocity += velocity;
                    shouldResetRotation = velocity < 10d;
                    java.util.List<Vec3> tpSequence = MovTasks.generateTpSequence(
                            currentPlayerPos, currentPlayerPos.add(finalMove), false, 161, true);
                    if (!tpSequence.isEmpty()) {
                        Vec2 redirectTarget = null;
                        Debug.chat(Component.literal(
                                        "[Bow TP] Projectile Velocity Simulate %.2f".formatted(finalMove.length()))
                                .withStyle(ChatFormatting.GREEN));
                        List<MovTasks.MovInfo> movements = new ArrayList<>();
                        int size = tpSequence.size();
                        for (int i = 0; i < size; ++i) {
                            movements.add(
                                    i == 0
                                            ? MovTasks.MovInfo.createNotOnGround(tpSequence.get(i))
                                            : MovTasks.MovInfo.create(tpSequence.get(i)));
                        }
                        if (shouldResetRotation) {
                            // need test
                            redirectTarget = CombatTasks.calculatePitchYawPredict(finalVelocity, finalMove, facing);
                            if (Float.isNaN(redirectTarget.x) || Float.isInfinite(redirectTarget.x)) {
                                // unreachable target via aim
                                Debug.chat("[Bow Aim] Arrow failed to reach the target");
                                shouldResetRotation = false;
                            }
                        }
                        movements.add(
                                shouldResetRotation
                                        ? new MovTasks.MovInfo(
                                                currentPlayerPos.add(0, 9E-8, 0), null, true, redirectTarget)
                                        : MovTasks.MovInfo.create(currentPlayerPos.add(0, 9E-8, 0)));

                        MovTasks.scheduleFarawayMoveInternal(
                                movements, false, MovTasks.MovingContext.create(currentPlayerPos), true);

                        MovTasks.setupAutoResync();
                        // disable later autoAim because we have sent the pitchYaw
                        makeAim = false;
                        break make_movements;
                    }

                    // send packets to simulate movements
                }
                Debug.chat(Component.literal("[Bow TP] Projectile Velocity fail to simulate"));
            }
        }
        if (makeAim && entity != null && entity != nowMePointingTheEntity) {

            // add use item feature

            Vec2 red = CombatTasks.calculatePitchYawPredict(finalVelocity, Vec3.ZERO, facing);
            if (Float.isNaN(red.x) || Float.isInfinite(red.x) || Float.isNaN(red.y) || Float.isInfinite(red.y)) {
                Debug.chat("[Bow Aim] Arrow failed to reach the target");
            } else {
                // todo: may reset speed
                LegacySnapRotManager.INSTANCE.snapAt(red.x, red.y, false);
            }
        }

        mc.player.setXRot(playerPitchYaw.x);
        mc.player.setYRot(playerPitchYaw.y);
    }

    public void bowActionDelayMovement(
            Event<ServerboundPlayerActionPacket> event, @Nullable Entity entity, float initialVelocity) {
        // it is from a delayed packet, or, I can fire it without event
        if (canTp()) {
            Debug.chat("[BowEh] Arrow Velocity Simulate not enabled in Legal Mode");
        }
        if (entity == null) return;
        event.cancel();
        ServerboundPlayerActionPacket delayedPacket = event.context();
        RotationManager.INSTANCE.request(RotationManager.ofDirection(() -> {
                    LocalPlayer player = mc.player;
                    Vec3 targetAt =
                            CombatTasks.getPositionPredict().predictAimPositionForEntity(entity, initialVelocity);
                    Vec3 targetAtFacing = targetAt.subtract(player.getEyePosition());
                    Vec2 pitchYaw =
                            CombatTasks.calculatePitchYawPredict(initialVelocity, player.getDeltaMovement(), targetAtFacing);
                    if (Float.isNaN(pitchYaw.x)
                            || Float.isInfinite(pitchYaw.x)
                            || Float.isNaN(pitchYaw.y)
                            || Float.isInfinite(pitchYaw.y)) {
                        Debug.chat("[Bow Aim] Arrow failed to reach the target");
                        return null;
                    }
                    return pitchYaw;
                })
                .postTick(() -> Tasks.scheduleDelayedPre(() -> Listener.sendPacketNoEvents(delayedPacket), 0)));
    }

    public void bowActionInteractItem(
            Event<ServerboundPlayerActionPacket> event, @Nullable Entity entity, float initialVelocity) {
        Vec2 playerPitchYaw = new Vec2(mc.player.getXRot(), mc.player.getYRot());
        var facing = entity == null
                ? mc.player.getLookAngle().normalize()
                : CombatTasks.getPositionPredict()
                        .predictAimPositionForEntity(entity, 3600000)
                        .subtract(mc.player.getEyePosition());
        Entity nowMePointingTheEntity = (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY)
                ? ((EntityHitResult) mc.hitResult).getEntity()
                : null;
        if (nowMePointingTheEntity != null
                && nowMePointingTheEntity.position().distanceToSqr(mc.player.getEyePosition()) > 50) {
            nowMePointingTheEntity = null;
        }
        boolean makeAim = enableAim.get();
        float finalVelocity = initialVelocity;
        make_movements:
        {
            if (canTp()) {
                // add movements to accelerate the projectile
                boolean exactTp = enhanceTp.get();
                double range = tpDistance.get();
                Vec3 facingNorm = facing.normalize();
                Vec3 oppositeFacing = Vec3.ZERO.subtract(facingNorm);
                Vec3 finalMove = Vec3.ZERO;
                Vec3 currentPlayerPos = mc.player.position();
                boolean shouldResetRotation = true;
                test_tp_position:
                {
                    // optimize the collision check by caching List of Boxes
                    MovTasks.CollisionContext context = new MovTasks.CollisionCache(
                            mc.player,
                            currentPlayerPos,
                            currentPlayerPos.add(oppositeFacing.scale(range + 1.0d)),
                            true);
                    double test = range;
                    for (; test > 10.0D; test -= 1.0D) {
                        if (exactTp) {
                            Vec3 oppositeMultiply = oppositeFacing.scale(test);
                            if (MovTasks.validMoveTo(
                                    context,
                                    currentPlayerPos.add(oppositeMultiply),
                                    Vec3.ZERO.subtract(oppositeMultiply))) {
                                finalMove = oppositeMultiply;
                                break test_tp_position;
                            }
                        } else {
                            if (MovTasks.validMoveToAndBack(context, currentPlayerPos, oppositeFacing.scale(test))) {
                                finalMove = oppositeFacing.scale(test);
                                break test_tp_position;
                            }
                        }
                    }
                    // t < 10
                    // check again
                    test = 10.0D;
                    for (; test > 0.0D; test -= 0.5D) {
                        Vec3 oppositeMultiply = oppositeFacing.scale(test);
                        if (exactTp) {
                            if (MovTasks.validMoveTo(
                                    context,
                                    currentPlayerPos.add(oppositeMultiply),
                                    Vec3.ZERO.subtract(oppositeMultiply))) {
                                finalMove = oppositeMultiply;
                                break test_tp_position;
                            }
                        } else {
                            if (MovTasks.validMoveToAndBack(context, currentPlayerPos, oppositeMultiply)) {
                                finalMove = oppositeMultiply;
                                break test_tp_position;
                            }
                        }
                        Vec3 oppoHorizontal = new Vec3(oppositeMultiply.x, 0.0d, oppositeMultiply.z);
                        Vec3 simulateMove = context.simulateMovement(mc.player, currentPlayerPos, oppoHorizontal);
                        if (MovTasks.validMovementAsServer(oppoHorizontal, simulateMove)) {
                            Vec3 simulateDownMove = context.simulateMovement(
                                    mc.player, currentPlayerPos.add(simulateMove), new Vec3(0, oppositeMultiply.y, 0));
                            Vec3 wholeMovement = simulateMove.add(simulateDownMove);
                            // y does not matter , xz matters
                            if (MovTasks.validMoveTo(
                                    context, currentPlayerPos.add(wholeMovement), wholeMovement.scale(-1))) {
                                finalMove = wholeMovement;
                                break test_tp_position;
                            }
                        }
                    }
                    // should strengthen move when test < 10,
                }
                if (finalMove.lengthSqr() > 1E-4) {
                    // 随便写的阈值 速度太快不需要转向
                    double velocity = finalMove.length();
                    finalVelocity += velocity;
                    java.util.List<Vec3> tpSequence = MovTasks.generateTpSequence(
                            currentPlayerPos, currentPlayerPos.add(finalMove), false, 161, true);
                    if (!tpSequence.isEmpty()) {
                        Debug.chat(Component.literal(
                                        "[Bow TP] Projectile Velocity Simulate %.2f".formatted(finalMove.length()))
                                .withStyle(ChatFormatting.GREEN));
                        List<MovTasks.MovInfo> movements = new ArrayList<>();
                        int size = tpSequence.size();
                        for (int i = 0; i < size; ++i) {
                            movements.add(
                                    i == 0
                                            ? MovTasks.MovInfo.createNotOnGround(tpSequence.get(i))
                                            : MovTasks.MovInfo.create(tpSequence.get(i)));
                        }

                        movements.add(MovTasks.MovInfo.create(currentPlayerPos.add(0, 9E-8, 0)));

                        MovTasks.scheduleFarawayMoveInternal(
                                movements, false, MovTasks.MovingContext.create(currentPlayerPos), true);

                        MovTasks.setupAutoResync();
                        // disable later autoAim because we have sent the pitchYaw
                        break make_movements;
                    }

                    // send packets to simulate movements
                }
                Debug.chat(Component.literal("[Bow TP] Projectile Velocity fail to simulate"));
            }
        }
        if (makeAim && entity != null && entity != nowMePointingTheEntity) {
            Vec2 red = CombatTasks.calculatePitchYawPredict(finalVelocity, mc.player.getDeltaMovement(), facing);
            if (Float.isNaN(red.x) || Float.isInfinite(red.x) || Float.isNaN(red.y) || Float.isInfinite(red.y)) {
                Debug.chat("[Bow Aim] Arrow failed to reach the target");
            } else {
                mc.gameMode.startPrediction(mc.level, (s) -> {
                    return new ServerboundUseItemPacket(mc.player.getUsedItemHand(), s, red.y, red.x);
                });
            }
        }

        mc.player.setXRot(playerPitchYaw.x);
        mc.player.setYRot(playerPitchYaw.y);
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        if (event.context().getValue().isOptionPreset()) return;
        ModulePreset preset = event.context().getValue();
        switch (preset) {
            case HACKING, VANILLA -> {
                if (tpDistance.get() < 0) {
                    tpDistance.set(-tpDistance.get());
                }
                if (mode.get() == LegalInteractMode.DELAY_MOVEMENT) {
                    mode.set(LegalInteractMode.LEGACY_SLIENT_ROT);
                }
            }
            default -> {
                if (tpDistance.get() > 0) {
                    tpDistance.set(-tpDistance.get());
                }
                if (mode.get() == LegalInteractMode.LEGACY_SLIENT_ROT) {
                    mode.set(LegalInteractMode.DELAY_MOVEMENT);
                }
            }
        }
        switch (preset) {
            case HACKING, VANILLA -> {
                mode.set(LegalInteractMode.NONE);
            }
            case AC_GRIM_LEGACY -> {
                mode.set(LegalInteractMode.LEGACY_SLIENT_ROT);
            }
            default -> {
                mode.set(LegalInteractMode.USEITEM_PACKET);
            }
        }
    }
}
