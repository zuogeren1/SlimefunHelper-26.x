package me.matl114.hacks.modules.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.awt.*;
import java.util.ArrayList;
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
import me.matl114.hacks.modules.task.MovementManager;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.*;
import me.matl114.utils.*;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.ChatFormatting;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import me.matl114.hacks.utils.EntityUtils;

public class SpearAttack extends BaseModule implements LegalMovementManager.MovementModifier {
    public final ModulePath spearModule = makePath(Configs.COMBAT_CONFIG, "spear-module");
    private static LegalMovementManager.DelegateMovementModifier INSTANCE;

    public SpearAttack() {
        super("SpearAttack");
        MovementManager.registerMovementPipeline(this);
        bindFlag(enable);
    }

    @Override
    public int priority() {
        return PRIORITY_LOW;
    }

    public final FlagRef enable = builder(spearModule.add("spear-attack-enable"), Boolean.class)
            .defaultValue(true)
            .build();

    public final KeyBindRef keyBind = hotkey(spearModule.add("spear-attack-hotkey"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler(this::onSpearAction))
            .build();

    public final DoubleRef spearDistance = builder(spearModule.add("spear-motion-simulation"), DoubleRef.TYPE)
            .defaultValue(50.0D)
            .validator(Configs.doubleRange(-200.0D, 200.0D))
            .build();

    public final DoubleRef spearMaxTp = builder(spearModule.add("spear-max-tp"), DoubleRef.TYPE)
            .defaultValue(100.0D)
            .validator(Configs.doubleRange(-200.0D, 200.0D))
            .build();

    public final FlagRef spearRender =
            flagBuilder(spearModule.add("render-target")).build();

    public final IntRef delay = intBuilder(spearModule.add("spear-server-tick-delay"))
            .defaultValue(2)
            .validator(Configs.INT_POSITIVE)
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostGameTick(), this::updateRenderCollector);
        registerListener(RenderListener.getRender3DEvent(), this::renderPlayerSpearTarget);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundClientTickEndPacket.class), this::onClientTickEnd);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
    }
    //
    public boolean onSpearAction() {
        if (enable.get() && canSpearAttack()) {
            if (currentWaitBackTick > 0) {
                return true;
            }
            currentWaitBackTick = 1;
            // Tasks.scheduleDelayed(this::spearAttack, 0);
            return spearAttack();
        }
        return false;
    }
    // todo: find out which flag determines the spear status
    public boolean canSpearAttack() {
        return SpearEnhance.canSpearKineticAttack();
    }

    public boolean spearAttack() {
        if (spearDistance.get() < 0) {
            currentWaitBackTick = 0;
            return false;
        }
        Entity target =
                CombatTasks.getTargetSelector().searchAttackEntity(spearDistance.get(), true, SpearAttack::isSpearable);
        if (target == null) {
            currentWaitBackTick = 0;
            return false;
        }
        if (spearMaxTp.get() < spearDistance.get()) {
            Debug.chat("[Spear] 参数错误, MaxTp不能小于Distance");
            currentWaitBackTick = 0;
            return true;
        }
        Vec3 playerPos = mc.player.position();
        Vec2 playerPy = new Vec2(mc.player.getXRot(), mc.player.getYRot());
        Vec3 targetPos = CombatTasks.getPositionPredict()
                .spearPredictArgument
                .get()
                .predict(target); // .predictAttackPosition(target);
        Vec3 direction =
                targetPos.add(0, target.getEyeHeight(target.getPose()), 0).subtract(mc.player.getEyePosition());
        double distance = direction.length();
        direction = direction.normalize();
        Vec3 tpDirection = direction.scale(-1);
        Vec3 horizontalLine = MathUtils.getVerticalWithSameXZ(direction);

        var re = findValidTpPosition(
                playerPos, tpDirection, horizontalLine, spearMaxTp.get(), spearDistance.get(), distance);
        // ensure the back tp is a direct tp
        if (re != null && re.getSecond().size() == 2) {
            var to = re.getFirst();
            var from = re.getSecond();
            List<MovTasks.MovInfo> toList = new ArrayList<>();
            Vec2 py = EntityUtils.rotationToPitchYaw(direction);
            for (var i = 0; i < to.size() - 1; i++) {
                toList.add(new MovTasks.MovInfo(to.get(i), false, false, null));
            }
            Vec3 targetTpPos = to.get(to.size() - 1);
            if (RenderTasks.DEBUG_RENDER_SPEAR) {
                RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
                        RenderTasks.DEBUG_TICK,
                        new RenderTasks.BoxObject(
                                targetTpPos.add(RenderTasks.FROM), targetTpPos.add(RenderTasks.TO), Color.MAGENTA)));
                RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
                        RenderTasks.DEBUG_TICK, new RenderTasks.LineToTargetObject(targetTpPos, Color.MAGENTA)));
            }
            toList.add(new MovTasks.MovInfo(targetTpPos, false, false, py));
            Vec3 backpos = re.getSecond().get(1);
            toList.add(new MovTasks.MovInfo(backpos, false, false, py));
            // DO NOT CONSIDER NOFALL, it may send extra packets
            MovTasks.scheduleMoveSequence(MovTasks.createPlayerMovContext(), toList, false, true);
            // DO NOT SEND PACKET HERE
            ClientPlayerAccess.of(mc.player).setForceNoFall(false);

            Debug.chat(Component.literal("[Spear] simulate delay %.2f"
                            .formatted(playerPos.subtract(targetTpPos).dot(direction)))
                    .withStyle(ChatFormatting.GREEN));
            // Debug.info("target", targetTpPos);
            currentWaitBackTick = delay.get() + 1;
            mc.player.setXRot(playerPy.x);
            mc.player.setYRot(playerPy.y);
            MovTasks.setupAutoResync();
            return true;
            // todo: check if we can do 1tick move
        } else {
            Debug.chat("[Spear] Too far to reach target");
            currentWaitBackTick = 0;
            return true;
        }
    }

    public Pair<List<Vec3>, List<Vec3>> isValidTpLocation(
            MovTasks.CollisionContext context, Vec3 playerLocation, Vec3 tpLocation, double maxDistance) {
        Vec3 currentSimulateMovement = playerLocation.subtract(tpLocation);
        List<Vec3> back;
        if (MovTasks.validMoveTo(context, tpLocation, currentSimulateMovement)) {
            back = List.of(tpLocation, playerLocation);
        } else return null;
        var listTo = context.generateTpSequence(playerLocation, tpLocation, false, 320, true);
        if (listTo.isEmpty()) return null;
        // back movement should be one

        //        var listBack = context.generateTpSequence(tpLocation, playerLocation, false, 320, true);
        return Pair.of(listTo, back);
    }

    //    public void onSpearAttackRender(Event<MatrixStack> event) {
    //        MatrixStack stack = event.context();
    //        {
    //            RenderUtils.startDrawVirtual(stack);
    //            try {
    //                for (var entity : mc.level.getEntities()) {
    //                    if (entity instanceof LivingEntity livingEntity
    //                            && livingEntity.isUsingItem()
    //                            && VItem.getInstance().isSpear(livingEntity.getActiveItem())) {
    //                        drawPlayerUseSpearTick(livingEntity, stack);
    //                    }
    //                }
    //            } finally {
    //                RenderUtils.stopDrawVirtual(stack);
    //            }
    //        }
    //    }
    // > 1 : no TickEnd, no move
    // == 1 : make noFall for next tick, can TickEnd, can not start next Spear
    // == 0 can move, can Start next Spear
    int currentWaitBackTick = 0;

    private void renderPlayerSpearTarget(Event<Render3D> event) {
        if (!enable.get() || !spearRender.get()) return;
        RenderUtils.startDrawVirtual(event.context().stack());
        try {
            targetCollector.render3D(event.context().stack(), event.context().partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(event.context().stack());
        }
    }

    private void updateRenderCollector() {
        targetCollector.clear();
        if (!enable.get() || !spearRender.get() || !canSpearAttack()) return;
        Entity spearEntity = CombatTasks.getTargetSelector()
                .searchAttackEntity(spearDistance.get(), false, SpearAttack::isSpearable);
        if (spearEntity != null) {
            float dist = spearEntity.distanceTo(mc.player);
            float opacity = Math.min(0.6F, 0.10F + dist * 0.02F);
            targetCollector.submit(
                    spearEntity, ColorUtils.withAlpha(Color.GREEN, opacity).getRGB());
        }
    }

    private final RenderCollector<Entity> targetCollector =
            RenderCollectors.createEntityBoxCollector(false, true, false);

    //    private void drawPlayerUseSpearTick(LivingEntity entity, PoseStack stack) {
    //        AttackRangeComponent attackRange = entity.getAttackRange();
    //        Vec3d startEye = entity.getEyePos();
    //        Vec3d direction = entity.getHeadRotationVector();
    //        double minRange = attackRange.getEffectiveMinRange(entity);
    //        double maxRange = attackRange.getEffectiveMaxRange(entity);
    //        double speedBonus = Math.max(0, entity.getMovement().dotProduct(direction));
    //        double finalMaxRange = maxRange + speedBonus;
    //
    //        Vec3d startPoint = startEye.add(direction.multiply(minRange));
    //        Vec3d endPoint = startEye.add(direction.multiply(finalMaxRange));
    //        float hitboxMargin = attackRange.hitboxMargin();
    //        Box box = Box.of(startPoint, (double) hitboxMargin, (double) hitboxMargin, (double) hitboxMargin)
    //                .stretch(endPoint.subtract(startPoint))
    //                .expand(1.0);
    //        stack.push();
    //        RenderUtils.drawOutlinedBox(stack, box.getMinPos(), box.getMaxPos(), Color.MAGENTA);
    //        RenderUtils.drawLineVirtual(stack, startPoint, endPoint, Color.MAGENTA);
    //        float max = Math.max(0, hitboxMargin);
    //        for (var e : mc.level.getOtherEntities(entity, box)) {
    //            if (e instanceof LivingEntity livingEntity) {
    //                if (livingEntity.getBoundingBox().raycast(startPoint, endPoint).isPresent()) {
    //                    RenderUtils.drawSolidBox(
    //                            stack,
    //                            livingEntity.getBoundingBox().getMinPos(),
    //                            livingEntity.getBoundingBox().getMaxPos(),
    //                            ColorUtils.withAlpha(Color.BLUE, 0.25F));
    //                } else if (max > 0) {
    //                    var box2 = livingEntity.getBoundingBox().expand(hitboxMargin);
    //                    var re = box2.raycast(startPoint, endPoint);
    //                    if (re.isPresent()) {
    //                        Vec3d vec3d = re.get();
    //                        Vec3d vec3d2 = box2.getCenter();
    //                        Optional<Vec3d> optional3 =
    //                                livingEntity.getBoundingBox().raycast(vec3d, vec3d2);
    //                        if (optional3.isPresent()) {
    //                            RenderUtils.drawSolidBox(
    //                                    stack,
    //                                    livingEntity.getBoundingBox().getMinPos(),
    //                                    livingEntity.getBoundingBox().getMaxPos(),
    //                                    ColorUtils.withAlpha(Color.BLUE, 0.25F));
    //                        }
    //                    }
    //                }
    //            }
    //        }
    //        stack.pop();
    //    }

    public static boolean isSpearable(Entity entity) {
        // Vec3d pos = mc.player.getEyePos();
        if (mc.player.getEyePosition().subtract(entity.getEyePosition()).lengthSqr()
                <= MathUtils.s2(mc.player
                        .getAttackRangeWith(mc.player.getMainHandItem())
                        .effectiveMinRange(mc.player))) {
            return false;
        }
        BlockHitResult blockHitResult = mc.level.clip(new ClipContext(
                mc.player.getEyePosition(),
                entity.getEyePosition(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mc.player));
        if (blockHitResult.getType() != HitResult.Type.MISS) {
            return false;
            //            Vec3d maxReach = blockHitResult.getPos();
            //            if (pos.squaredDistanceTo(maxReach)
            //                    < MathUtils.s2(mc.player.getAttackRange().getEffectiveMinRange(mc.player))) {
            //                return false;
            //            }
        }
        return true;
    }

    DoubleList searchOrder = new DoubleArrayList();

    {
        searchOrder.add(0);

        for (var i = 1; i < 10; ++i) {
            searchOrder.add(i);
            searchOrder.add(-i);
        }
    }

    public Pair<List<Vec3>, List<Vec3>> findValidTpPosition(
            Vec3 currentPlayerPos,
            Vec3 tpDirection,
            Vec3 expandDirection,
            double maxDistance,
            double distance,
            double minDistance) {
        MovTasks.CollisionContext context = MovTasks.ENGIN;
        //            new MovTasks.CollisionCache(
        //            mc.player,
        //            currentPlayerPos.add(- maxDistance , -maxDistance, -maxDistance),
        //            currentPlayerPos.add(maxDistance, maxDistance, maxDistance),
        //            true);
        Pair<List<Vec3>, List<Vec3>> result = null;

        for (double search = distance; search > minDistance; search -= 2.0D) {
            for (var i : searchOrder) {
                Vec3 searchTpPos =
                        currentPlayerPos.add(tpDirection.scale(search)).add(expandDirection.scale(i));
                if (searchTpPos.distanceToSqr(currentPlayerPos) > MathUtils.s2(maxDistance)) {
                    break;
                }
                if (!context.checkEnvironmentCollision(mc.player, searchTpPos, true)
                        && (result = isValidTpLocation(context, currentPlayerPos, searchTpPos, maxDistance)) != null) {
                    return result;
                }
            }
        }
        return null;
    }

    @Override
    public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {}

    @Override
    public void applyBeforeMovementPacketModify(Event<LegalMovementManager> movementManagerEvent) {
        if (currentWaitBackTick > 0) {
            currentWaitBackTick -= 1;
            if (currentWaitBackTick > delay.get()) {
                currentWaitBackTick = delay.get();
            }
            if (currentWaitBackTick > 0) {
                movementManagerEvent.cancel();
                // movementManagerEvent.context.playerStatus.restorePos();
                movementManagerEvent.context.playerStatus.entity.setOnGround(false);
            }

            if (currentWaitBackTick == 1) {
                if (RenderTasks.DEBUG_RENDER_SPEAR) {
                    Debug.chat("Delay finish");
                }
                ClientPlayerAccess.of(mc.player).setForceNoFall(true);
                // trigger pitch yaw resync and pos resync
                ClientPlayerAccess.of(mc.player).resyncPos();
                ClientPlayerAccess.of(mc.player).resyncRot();
            }
        }
    }

    @Override
    public void applyBeforeInputPacketModify(Event<LegalMovementManager> movementManagerEvent) {
        this.applyBeforeMovementPacketModify(movementManagerEvent);
    }

    public void onClientTickEnd(Event<ServerboundClientTickEndPacket> tickEndPacket) {
        if (currentWaitBackTick > 1) {
            tickEndPacket.cancel();
        }
    }

    @Override
    public boolean postModify(Event<LegalMovementManager> movementManagerEvent, boolean enabledThisTick) {
        return true;
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        if (event.context().getValue().isOptionPreset()) return;
        ModulePreset preset = event.context().getValue();
        switch (preset) {
            case HACKING, VANILLA -> {
                if (spearDistance.get() < 0.0D) {
                    spearDistance.set(-spearDistance.get());
                }
            }
            default -> {
                if (spearDistance.get() > 0.0D) {
                    spearDistance.set(-spearDistance.get());
                }
            }
        }
    }
}
