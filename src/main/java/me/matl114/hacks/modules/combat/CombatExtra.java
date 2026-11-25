package me.matl114.hacks.modules.combat;

import me.matl114.accessors.access.PlayerMoveC2SPacketAccess;
import me.matl114.accessors.interfaces.MetadataHolder;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.events.impl.PosUpdate;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.modules.task.DisablerManager;
import me.matl114.hacks.modules.task.PostManager;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.utils.Debug;
import me.matl114.versioned.SupportVersion;
import me.matl114.versioned.api.VDataFlag;
import me.matl114.versioned.api.VItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class CombatExtra extends BaseModule {
    public static CombatExtra INSTANCE;

    public CombatExtra() {
        super("CombatExtra");
        INSTANCE = this;
    }

    public final ModulePath combat = makePath(Configs.COMBAT_CONFIG, "attack");

    public final DoubleRef range = doubleBuilder(combat.add("att-range"))
            .defaultValue(0.0D)
            .validator(Configs.doubleRange(0.0D, 64.0D))
            .build();

    public final DoubleRef boatAttackRange = doubleBuilder(combat.add("boat-reach-range"))
            .defaultValue(0.0D)
            .validator(Configs.doubleRange(0.0D, 64.0D))
            .build();

    public final FlagRef spearReach =
            flagBuilder(combat.add("grim-spear-reach")).build();

    public final FlagRef shieldPredict =
            flagBuilder(combat.add("shielding-setback-log")).build();

    public final FlagRef useAttack = flagBuilder(combat.add("shielding-attack")).build();

    public final FlagRef rideAttack = flagBuilder(combat.add("riding-attack")).build();

    public final FlagRef noCooldown = flagBuilder(combat.add("cancel-interval")).build();

    private boolean ridingBypass(Entity entity) {
        return entity.isPassenger();
    }

    public double getAttackAtTargetRange(Entity entity) {
        double d = ridingBypass(mc.player) || ridingBypass(entity) ? boatAttackRange.get() : range.get();
        return getAttackRangeAttribute() + d;
    }

    public double getAttackRange() {
        double d = ridingBypass(mc.player) ? boatAttackRange.get() : range.get();
        return getAttackRangeAttribute() + d;
    }

    public double getAttackRangeAttribute() {
        double reachValue = mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        if (spearReach.get()
                && ViaFabricPlusHooks.getInstance().getCurrentVersion().isHigherOrEqualTo(21, 11)) {
            ItemStack lastStack = PlayerStateManager.INSTANCE
                    .getOrCreatePlayerStatus(mc.player)
                    .getMainHandSnapshot();
            if (lastStack.has(DataComponents.ATTACK_RANGE)) {
                AttackRange range1 = lastStack.get(DataComponents.ATTACK_RANGE);
                double reach1 = range1.effectiveMaxRange(mc.player);
                reachValue = Math.max(reach1, reachValue);
            }
            ItemStack handStack = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
            if (handStack.has(DataComponents.ATTACK_RANGE)) {
                AttackRange range1 = handStack.get(DataComponents.ATTACK_RANGE);
                double reach1 = range1.effectiveMaxRange(mc.player);
                reachValue = Math.max(reach1, reachValue);
            }
        }
        return reachValue;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundSetEntityDataPacket.class), this::onShieldSetback);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundCooldownPacket.class), this::asyncUpdateShieldCooldown);
        registerListener(Listener.getAttackAction(), this::onUseAttackNoSlow);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundMovePlayerPacket.class), this::onMovementPacket);
        registerListener(Listener.getPostGameTick(), this::onClientTickEndPacket);
        registerListener(Listener.getEntityTrackedPositionUpdate(), this::onTrackedPosition);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundMoveEntityPacket.class), this::onEntityPacket);
    }

    public int shieldExceptionspam = 0;

    public void onUseAttackNoSlow() {
        //        if (shieldAttack.get() && mc.player.isUsingItem()) {
        //            MovTasks.getNoSlowDown().setPreAttackUseTick();
        //        }
    }

    public void onPreInputTick() {
        if (checkNull()) return;
    }

    public void onShieldSetback(Event<ClientboundSetEntityDataPacket> trackerUpdateS2CPacketEvent) {
        if (trackerUpdateS2CPacketEvent.isCancelled()) {
            return;
        }
        var trackerUpdateS2CPacket = trackerUpdateS2CPacketEvent.context();
        if (shieldPredict.get()
                && mc.player != null
                && trackerUpdateS2CPacket.id() == mc.player.getId()
                && mc.player.isUsingItem()
                && VItem.getInstance().isShield(mc.player.getUseItem())
                && !mc.player.getCooldowns().isOnCooldown(mc.player.getUseItem())) {
            // shield not in cooldown
            // block shield from
            for (var trackerUpdate : trackerUpdateS2CPacket.packedItems()) {
                // the ordinal  of LIVING FLAGS in LivingEntity, may vary with versionsl pls check
                if (trackerUpdate.id() == VDataFlag.ID_LIVING_FLAGS) {
                    byte byteValue = ((Number) trackerUpdate.value()).byteValue();
                    boolean bl = (byteValue & (1 << VDataFlag.USING_ITEM_FLAG_INDEX)) > 0;
                    InteractionHand hand = (byteValue & (1 << VDataFlag.OFFHAND_ACTIVE_FLAG_INDEX)) > 0
                            ? InteractionHand.OFF_HAND
                            : InteractionHand.MAIN_HAND;
                    // cooldown should be ok,
                    // the only position the server disable shield correctly should be cooldown
                    // so we kick it back
                    if (!bl && hand == mc.player.getUsedItemHand()) {
                        // using shield , but banned
                        if (shieldExceptionspam + 4 < Tasks.getTick()) {
                            shieldExceptionspam = Tasks.getTick();
                            Debug.chat(Component.literal("[AC] 阻挡异常盾牌禁用").withStyle(ChatFormatting.RED));
                        }
                        // trackerUpdateS2CPacketEvent.cancel();
                    }
                }
            }
        }
    }

    public void asyncUpdateShieldCooldown(Event<ClientboundCooldownPacket> packetEvent) {
        if (packetEvent.isCancelled()) {
            return;
        }
        ClientboundCooldownPacket packet = packetEvent.context();
        if (packet.duration() > 0) {
            try {
                synchronized (CombatExtra.class) {
                    // async update, synchronize to protect concurrent cooldown update,
                    mc.player.getCooldowns().addCooldown(packet.cooldownGroup(), packet.duration());
                    //                if(mc.player.isUsingItem() && mc.player.getActiveItem().getItem() == shield){
                    //
                    //                }
                    // consume packet
                    packetEvent.cancel();
                }

            } catch (Throwable e) {
                // any exception

            }
        }
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        if (event.context().getValue().isOptionPreset()) return;
        switch (event.context.getValue()) {
            case HACKING -> {
                range.set(3.0);
                boatAttackRange.set(3.0);
            }
            case AC_GRIM, AC_GRIM_LEGACY -> {
                range.set(0.0);
                boatAttackRange.set(3.0);
            }
            default -> {
                range.set(0.0);
                boatAttackRange.set(0.0);
            }
        }
    }

    public void onMovementPacket(Event<ServerboundMovePlayerPacket> packetEvent) {
        if (packetEvent.isCancelled()) {
            return;
        }
        var access = PlayerMoveC2SPacketAccess.of(packetEvent.context);
        if (access.getCause() != PlayerMoveC2SPacketAccess.Cause.SET_BACK
                && access.getCause() != PlayerMoveC2SPacketAccess.Cause.LEGACY_SNAP) {
            mc.level.entitiesForRendering().forEach(entity -> {
                if (entity instanceof LivingEntity) {
                    tickEntity(entity);
                }
            });
        }
    }

    private void tickEntity(Entity entity) {
        getTracker(entity).onMovement(maySkipInterpolationTick());
    }

    public boolean maySkipInterpolationTick() {
        boolean tickReliable = !DisablerManager.INSTANCE.isGrimSelfCheckDisabled()
                && (ViaFabricPlusHooks.isSupportEndTick() || mc.player.isPassenger());
        return !tickReliable;
    }

    public void onClientTickEndPacket() {
        if (ViaFabricPlusHooks.isSupportEndTick() && !PlayerStateManager.INSTANCE.lastTickHasMovement) {
            mc.level.entitiesForRendering().forEach(entity -> {
                if (entity instanceof LivingEntity) {
                    tickEntity(entity);
                }
            });
        }
    }

    public void onTrackedPosition(Event<PosUpdate> event) {
        if (event.context.entity() instanceof LivingEntity living) {
            getTracker(living)
                    .onEntityPos(
                            living,
                            event.context.newPos(),
                            !living.isPassenger() && !ViaFabricPlusHooks.isSupportEndTick());
        }
    }

    public void onEntityPacket(Event<ClientboundMoveEntityPacket> event) {
        ClientboundMoveEntityPacket packet = event.context;
        if (packet.hasPosition() || !packet.hasRotation() || mc.level == null) return;
        if (!(packet.getEntity(mc.level) instanceof LivingEntity living) || living.isLocalInstanceAuthoritative())
            return;
        getTracker(living).onRotationOnly(living, !living.isPassenger() && !ViaFabricPlusHooks.isSupportEndTick());
    }

    private static final String KEY_TRACKER = "slimefunhelper:combat_extra/position_tracker";

    public LivingPositionTracker getTracker(Entity entity) {
        return ((MetadataHolder) entity).getMetadata().getOrPut(this, KEY_TRACKER, LivingPositionTracker::new);
    }

    public static class LivingPositionTracker {
        private InterpolationData oldPacketLocation;
        private InterpolationData newPacketLocation;
        private Vec3 trackedPosition;

        private static boolean hasNoInterpolator(LivingEntity entity) {
            //#if MC >= 260300
            //$$ return entity.getInterpolation() == InterpolationHandler.NO_OP;
            //#else
            return entity.getInterpolation() == null;
            //#endif
        }

        public void onEntityPos(LivingEntity entity, Vec3 pos, boolean maySkipTick) {
            boolean instant = hasNoInterpolator(entity);
            int interpolationSteps = instant ? 1 : 3;
            if (instant) {
                trackedPosition = pos;
                oldPacketLocation = null;
                newPacketLocation = new InterpolationData(pos, interpolationSteps, false);
                return;
            }
            if (trackedPosition == null || newPacketLocation == null) {
                trackedPosition = pos;
                oldPacketLocation = null;
                newPacketLocation = new InterpolationData(this.trackedPosition, interpolationSteps, maySkipTick);
            } else {
                trackedPosition = pos;
                oldPacketLocation = newPacketLocation;
                newPacketLocation = new InterpolationData(
                        oldPacketLocation.possiblePositions(), trackedPosition, interpolationSteps, maySkipTick);
                PostManager.INSTANCE.addNextTransactionAction((ch) -> oldPacketLocation = null);
            }
        }

        public void onRotationOnly(LivingEntity entity, boolean maySkipTick) {
            if (hasNoInterpolator(entity) || trackedPosition == null || newPacketLocation == null) return;
            SupportVersion protocol = ViaFabricPlusHooks.getInstance().getCurrentVersion();
            oldPacketLocation = newPacketLocation;
            AABB possible = oldPacketLocation.possiblePositions();
            boolean freeze = (protocol.isHigherOrEqualTo(21, 5) && protocol.isLowerOrEqualTo(21, 8))
                    || (protocol.isHigherOrEqualTo(15, 1) && protocol.isLowerOrEqualTo(20, 1));
            newPacketLocation = freeze
                    ? InterpolationData.frozen(possible)
                    : new InterpolationData(possible, trackedPosition, 3, maySkipTick);
            PostManager.INSTANCE.addNextTransactionAction((ch) -> oldPacketLocation = null);
        }

        // 傻逼grim又发力了。依旧跳刻大法
        public void onMovement(boolean maySkipTick) {
            if (newPacketLocation == null) return;
            newPacketLocation.step(oldPacketLocation == null, maySkipTick);
            if (oldPacketLocation != null) {
                oldPacketLocation.step(true, maySkipTick);
                newPacketLocation.includePossibleStartingRange(oldPacketLocation.possiblePositions());
            }
        }

        public AABB getInteractBox(Entity entity) {
            if (newPacketLocation == null) return entity.getBoundingBox();
            // 傻逼grim为什么会想到能用这种纯比操作来检测reach。不检测interpolate反而将所有可行box直接取轴线交集吗
            if (oldPacketLocation == null) {
                return expand(newPacketLocation.possiblePositions(), entity).minmax(entity.getBoundingBox());
            } else {
                return expand(oldPacketLocation.possiblePositions(), entity)
                        .minmax(expand(newPacketLocation.possiblePositions(), entity))
                        .minmax(entity.getBoundingBox());
            }
        }

        public AABB getNextInterpolatedBox(Entity entity, int ticksLater, boolean maySkipTick) {
            if (ticksLater <= 0) return getInteractBox(entity);
            if (newPacketLocation == null) return entity.getBoundingBox();
            return predictInterpolation(entity, ticksLater, maySkipTick);
        }

        public Vec3 getNextInterpolatedPosition(Entity entity, int ticksLater, boolean maySkipTick) {
            if (ticksLater <= 0) return entity.position();
            if (newPacketLocation == null) {
                Vec3 tracked = entity.trackingPosition();
                return tracked.equals(Vec3.ZERO) ? entity.position() : tracked;
            }
            return getNextInterpolatedBox(entity, ticksLater, maySkipTick)
                    .getCenter()
                    .subtract(0.0, entity.dimensions.height() / 2.0, 0.0);
        }

        private AABB predictInterpolation(Entity entity, int ticksLater, boolean maySkipTick) {
            InterpolationData predictedOld = oldPacketLocation == null ? null : oldPacketLocation.copy();
            InterpolationData predictedNew = newPacketLocation.copy();
            for (int i = 0; i < ticksLater; i++) {
                predictedNew.step(predictedOld == null, maySkipTick);
                if (predictedOld != null) {
                    predictedOld.step(true, maySkipTick);
                    predictedNew.includePossibleStartingRange(predictedOld.possiblePositions());
                }
            }

            AABB predictedBox = expand(predictedNew.possiblePositions(), entity).minmax(entity.getBoundingBox());
            if (predictedOld != null) {
                predictedBox = predictedBox.minmax(expand(predictedOld.possiblePositions(), entity));
            }
            return predictedBox;
        }

        private AABB expand(AABB box, Entity entity) {
            return box.inflate(entity.dimensions.width() / 2, 0, entity.dimensions.width() / 2)
                    .expandTowards(0, entity.dimensions.height(), 0);
        }
    }

    public static final class InterpolationData {
        private final int steps;
        private final Vec3 target;
        private AABB startRange;
        private boolean frozen;
        private int lowStep;
        private int highStep;

        public InterpolationData(Vec3 stable, int steps, boolean maySkipTicks) {
            this.steps = steps;
            this.target = stable;
            this.startRange = new AABB(stable, stable);
            this.lowStep = 0;
            this.highStep = maySkipTicks ? steps : 0;
        }

        public InterpolationData(AABB startRange, Vec3 target, int steps, boolean maySkipTicks) {
            this.startRange = startRange;
            this.target = target;
            this.steps = steps;
            this.lowStep = 0;
            this.highStep = maySkipTicks ? steps : 0;
        }

        public static InterpolationData frozen(AABB location) {
            InterpolationData data = new InterpolationData(location, location.getCenter(), 1, false);
            data.frozen = true;
            return data;
        }

        public AABB possiblePositions() {
            return interpolate(lowStep).minmax(interpolate(highStep));
        }

        private AABB interpolate(int step) {
            double t = (double) step / steps;
            return new AABB(
                    lerp(startRange.minX, target.x, t),
                    lerp(startRange.minY, target.y, t),
                    lerp(startRange.minZ, target.z, t),
                    lerp(startRange.maxX, target.x, t),
                    lerp(startRange.maxY, target.y, t),
                    lerp(startRange.maxZ, target.z, t));
        }

        public void step(boolean increase, boolean maySkipTick) {
            if (frozen) return;
            if (maySkipTick) this.highStep = steps;
            if (increase) {
                lowStep = Math.min(lowStep + 1, steps);
            }
            this.highStep = Math.min(highStep + 1, steps);
        }

        public void includePossibleStartingRange(AABB previousRange) {
            startRange = startRange.minmax(previousRange);
        }

        private InterpolationData copy() {
            InterpolationData copy = new InterpolationData(startRange, target, steps, false);
            copy.frozen = frozen;
            copy.lowStep = lowStep;
            copy.highStep = highStep;
            return copy;
        }

        private static double lerp(double from, double to, double t) {
            return from + (to - from) * t;
        }
    }
}
