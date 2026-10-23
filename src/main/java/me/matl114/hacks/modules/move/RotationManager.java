package me.matl114.hacks.modules.move;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Setter;
import lombok.experimental.Accessors;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.events.packets.PacketStorage;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.modules.combat.TargetSelector;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.task.MovementManager;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.RayCastCalculator;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.managers.Tasks;
import me.matl114.utils.ApiMethod;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.Nullable;

public class RotationManager extends BaseModule implements LegalMovementManager.MovementModifier {
    public static RotationManager INSTANCE;

    private final List<RotationRequest> currentTickRequests = new ArrayList<>();
    RotationRequest currentTickRequest;
    private int queueTick = 0;
    private boolean currentInQueue = false;
    static LegalMovementManager.DelegateMovementModifier instance;

    public RotationManager() {
        super("RotationManager");
        INSTANCE = this;
        MovementManager.registerMovementPipeline(this);
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getServerLeavePoint(), this::clearRequests);
        registerListener(PacketManager.getPacketQueueOutEvent(), this::onQueuePacket);
    }

    @Override
    public int priority() {
        // Resolve queued interaction rotations after movement modifiers. This keeps
        // flight and combat modifiers from taking ownership of a queued request.
        return PRIORITY_HIGHEST;
    }

    @ApiMethod
    public boolean request(RotationRequest request) {
        Objects.requireNonNull(request, "request");
        if (isOccupied()) {
            return false;
        }
        currentTickRequests.add(request);
        return true;
    }

    public boolean isOccupied() {
        return currentTickRequests.stream().anyMatch(request -> request.priority() == Integer.MIN_VALUE);
    }

    private void onQueuePacket(Event<PacketStorage.Send> event) {
        if (queueTick < Tasks.getTick() - 5) {
            currentInQueue = false;
        }
        if (currentInQueue) {
            event.cancel();
        }
    }

    public void startQueuePacket() {
        queueTick = Tasks.getTick();
        PacketManager.schedule(() -> {
            queueTick = Tasks.getTick();
            currentInQueue = true;
        });
    }

    @Override
    public void applyPreTickModify(Event<LegalMovementManager> event) {

        LegalMovementManager manager = event.context();
        LocalPlayer player = manager.playerStatus.entity;

        currentTickRequest = currentTickRequests.stream()
                .min(Comparator.comparingInt(RotationRequest::priority))
                .orElse(null);
        currentTickRequests.clear();
        if (ElytraExtra.INSTANCE.hasFireworkVelocityOverrides()) {
            return;
        }
        if (currentTickRequest == null) {
            return;
        }
        // Resolve the initial rotation against the position used by this tick's movement.

        Vec2 rotation = currentTickRequest.tickRotationPre(player.position(), player);
        if (rotation == null) return;
        manager.pushImportantRotation(true, true);
        EntityUtils.setEntityPitchSafe(player, rotation.x);
        PlayerStateManager.setPlayerYawSafe(player, rotation.y);
        if (currentTickRequest.applyMoveFix()) manager.tryMarkForMoveFix();
        manager.markForResetRot();
    }

    @Override
    public void applyBeforeMovementPacketModify(Event<LegalMovementManager> event) {
        if (currentTickRequest == null) return;
        LocalPlayer player = event.context().playerStatus.entity;
        if (ElytraExtra.INSTANCE != null && ElytraExtra.INSTANCE.hasFireworkVelocityOverrides()) return;
        Vec2 rotation = currentTickRequest.tickRotationPreMovementPacket(player.position(), player);
        if (rotation == null) return;
        if (player.isFallFlying() || player.isSwimming()) return;
        EntityUtils.setEntityPitchSafe(player, rotation.x);
    }

    @Override
    public void applyBeforeInputPacketModify(Event<LegalMovementManager> event) {
        if (currentTickRequest == null) return;
        LocalPlayer player = event.context().playerStatus.entity;
        Vec2 rotation = currentTickRequest.tickRotationPreMovementPacket(player.position(), player);
        if (rotation == null) return;
        EntityUtils.setEntityPitchSafe(player, rotation.x);
    }

    @Override
    public boolean postModify(Event<LegalMovementManager> event, boolean enabledThisTick) {
        RotationRequest request = currentTickRequest;
        currentTickRequest = null;
        if (request != null) {
            request.tickPost();
        }
        if (queueTick != 0 || currentInQueue) {
            PacketManager.flushOutBound(() -> {
                queueTick = 0;
                currentInQueue = false;
            });
        }
        return true;
    }

    private void clearRequests() {
        currentTickRequests.clear();
        currentTickRequest = null;
    }

    public interface RotationRequest {
        default int priority() {
            return Integer.MIN_VALUE;
        }

        @Nullable
        public Vec2 tickRotationPre(Vec3 pos, LocalPlayer player);

        @Nullable
        default Vec2 tickRotationPreMovementPacket(Vec3 pos, LocalPlayer player) {
            return null;
        }

        default void tickPost() {}

        default boolean applyMoveFix() {
            return true;
        }
    }

    public static final RotationRequest EMPTY = (pos, player) -> null;

    @Setter
    @Accessors(fluent = true, chain = true)
    public abstract static class RotationRequestImpl<T> implements RotationRequest {
        private Runnable preTick = () -> {};
        private Runnable postTick = () -> {};
        private boolean preMovementAdjust;
        private boolean applyMoveFix = true;
        private int priority = Integer.MIN_VALUE;

        @Override
        public int priority() {
            return priority;
        }

        public boolean preMovementAdjust() {
            return preMovementAdjust;
        }

        @Override
        public boolean applyMoveFix() {
            return applyMoveFix;
        }

        @MustBeInvokedByOverriders
        @Override
        public void tickPost() {
            if (postTick != null) postTick.run();
        }

        @MustBeInvokedByOverriders
        @Override
        public @Nullable Vec2 tickRotationPre(Vec3 pos, LocalPlayer player) {
            if (preTick != null) preTick.run();
            return calculateRotationPre(pos, player);
        }

        protected abstract @Nullable Vec2 calculateRotationPre(Vec3 pos, LocalPlayer player);
    }

    @Setter
    @Accessors(fluent = true, chain = true)
    public static class BoxRaycastRequest<T> extends RotationRequestImpl<T> {
        private final RayCastCalculator<T> calculator;
        private final Supplier<T> targetSupplier;

        public BoxRaycastRequest(RayCastCalculator<T> calculator, Supplier<T> targetSupplier) {
            this.calculator = Objects.requireNonNull(calculator, "calculator");
            this.targetSupplier = Objects.requireNonNull(targetSupplier, "targetSupplier");
        }

        public BoxRaycastRequest(
                RayCastCalculator<T> calculator, Supplier<T> targetSupplier, boolean preMovementAdjust) {
            this(calculator, targetSupplier);
            preMovementAdjust(preMovementAdjust);
        }

        @MustBeInvokedByOverriders
        @Override
        protected @Nullable Vec2 calculateRotationPre(Vec3 pos, LocalPlayer player) {
            T target = targetSupplier.get();
            if (target == null) return null;
            Vec3 direction = calculator.getRaycast(player.position(), target);
            return direction == null ? null : EntityUtils.rotationToPitchYaw(direction.normalize());
        }

        @Override
        public @Nullable Vec2 tickRotationPreMovementPacket(Vec3 pos, LocalPlayer player) {
            if (!preMovementAdjust() || player.isSwimming() || player.isInWater() || player.isFallFlying())
                return null;
            T target = targetSupplier.get();
            if (target == null) return null;
            AABB effectiveBox = calculator.getEffectiveBox(target);
            if (effectiveBox == null) return null;

            float fixedYaw = player.getYRot();
            float currentPitch = player.getXRot();
            if (calculator.canRayCast(pos, target, currentPitch, fixedYaw)) {
                return new Vec2(currentPitch, fixedYaw);
            }

            Vec3 eyePos = pos.add(0, player.dimensions.eyeHeight(), 0);
            float centerPitch = EntityUtils.rotationToPitch(
                    effectiveBox.getCenter().subtract(eyePos).normalize());
            if (calculator.canRayCast(pos, target, centerPitch, fixedYaw)) {
                return new Vec2(centerPitch, fixedYaw);
            }
            AABB projectileBox = effectiveBox.setMinY(-1.0D).setMaxY(1.0D);
            Vec3 projectileYaw = EntityUtils.pitchYawToRotation(0, fixedYaw);
            var hitResult = projectileBox.clip(
                    pos.with(Direction.Axis.Y, 0), projectileYaw.scale(calculator.getReachDistance(target)));
            if (hitResult.isPresent()) {
                Vec3 hitPoint = hitResult.get();
                var hitRay = calculator
                        .getPotentialEyeHeights(pos)
                        .map(s -> {
                            Vec3 magPoint = hitPoint.with(
                                    Direction.Axis.Y, Math.clamp(s.y(), effectiveBox.minY, effectiveBox.maxY));
                            double distance = magPoint.distanceTo(s);
                            return distance <= calculator.getReachDistance(target) ? magPoint.subtract(s) : null;
                        })
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
                if (hitRay != null) {
                    float pitch = EntityUtils.rotationToPitch(hitRay.normalize());
                    if (calculator.canRayCast(pos, target, pitch, fixedYaw)) {
                        return new Vec2(pitch, fixedYaw);
                    }
                }
            }
            return null;
        }
    }

    private static final RayCastCalculator<AABB> BOX_CALCULATOR = new RayCastCalculator<>() {
        @Override
        public AABB getEffectiveBox(AABB target) {
            return target;
        }

        @Override
        public double getReachDistance(AABB val) {
            return 100.0D;
        }
    };

    public static BoxRaycastRequest<AABB> ofBox(Supplier<AABB> boxSupplier) {
        return new BoxRaycastRequest<>(BOX_CALCULATOR, boxSupplier);
    }

    public static LookDirectionRequest ofDirection(Supplier<Vec2> rotationSupplier) {
        return new LookDirectionRequest(rotationSupplier);
    }

    public static LookDirectionRequest ofDirection(Optional<Float> pitch, Optional<Float> yaw) {
        return new LookDirectionRequest(pitch, yaw);
    }

    public static LookDirectionRequest ofDirection(float pitch, float yaw) {
        return new LookDirectionRequest(Optional.of(pitch), Optional.of(yaw));
    }

    public static LookDirectionRequest ofLookDirection(Optional<Float> pitch, Optional<Float> yaw) {
        return new LookDirectionRequest(pitch, yaw);
    }

    public static LookDirectionRequest ofLookDirection(float pitch, float yaw) {
        return new LookDirectionRequest(Optional.of(pitch), Optional.of(yaw));
    }

    public static final class LookDirectionRequest extends RotationRequestImpl<Vec2> {
        private final Function<LocalPlayer, Vec2> rotationSupplier;

        public LookDirectionRequest() {
            this(Optional.empty(), Optional.empty());
        }

        public LookDirectionRequest(Supplier<Vec2> rotationSupplier) {
            this.rotationSupplier = (pl) -> rotationSupplier.get();
        }

        public LookDirectionRequest(Optional<Float> pitch, Optional<Float> yaw) {
            this.rotationSupplier = (pl) -> {
                if (pitch.isEmpty() && yaw.isEmpty()) return null;
                return new Vec2(pitch.orElseGet(pl::getXRot), yaw.orElseGet(pl::getYRot));
            };
        }

        @Override
        protected @Nullable Vec2 calculateRotationPre(Vec3 pos, LocalPlayer player) {
            return rotationSupplier == null ? null : rotationSupplier.apply(player);
        }
    }

    public static BoxRaycastRequest<BlockPos> ofBlock(Supplier<BlockPos> posSupplier) {
        return new BoxRaycastRequest<>(InteractExtra.INSTANCE, posSupplier) {
            @Override
            protected @Nullable Vec2 calculateRotationPre(Vec3 pos, LocalPlayer player) {
                Vec2 vec2f = super.calculateRotationPre(pos, player);
                if (vec2f == null) return null;
                return fluctuateBlockRaycast(posSupplier.get(), pos, vec2f, InteractExtra.INSTANCE);
            }
        };
    }

    public static BoxRaycastRequest<Entity> ofEntity(Supplier<Entity> entitySupplier) {
        BoxRaycastRequest<Entity> request = new BoxRaycastRequest<>(TargetSelector.INSTANCE, entitySupplier);
        request.preMovementAdjust(true);
        return request;
    }

    private static boolean lastAdd;

    private static Vec2 fluctuateBlockRaycast(
            BlockPos pos, Vec3 feet, Vec2 direct, RayCastCalculator<BlockPos> posRaycast) {
        if (pos == null) return direct;
        float pitch = quantize(direct.x);
        float yaw = quantize(direct.y);
        Vec2 candidate = new Vec2(pitch, yaw);
        if (!posRaycast.canRayCast(feet, pos, candidate.x, candidate.y)) candidate = direct;
        yaw = candidate.y;
        int sign = lastAdd ? 1 : -1;
        lastAdd = !lastAdd;
        for (int i = 0; i < 8; i++) {
            Vec2 adjusted = new Vec2(pitch, quantize(yaw + sign * 0.1F));
            if (posRaycast.canRayCast(feet, pos, adjusted.x, adjusted.y)) {
                candidate = adjusted;
                yaw = adjusted.y;
            }
        }
        return candidate;
    }

    private static float quantize(float angle) {
        return Math.round(angle * 10.0F) / 10.0F;
    }
}
