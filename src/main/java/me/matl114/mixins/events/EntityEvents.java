package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.matl114.accessors.events.EntityAccess;
import me.matl114.accessors.events.TrackedPositionAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(Entity.class)
public abstract class EntityEvents<T extends Entity> implements EntityAccess<T> {
    @Shadow
    @Final
    private VecDeltaCodec packetPositionCodec;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void trackedPositionMarkOwner(EntityType<?> type, Level world, CallbackInfo ci) {
        ((TrackedPositionAccess) this.packetPositionCodec).setOwner((Entity) (Object) this);
    }

    @ModifyExpressionValue(
            method = "moveRelative",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/Entity;getInputVector(Lnet/minecraft/world/phys/Vec3;FF)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 onModifyVelocity(Vec3 original) {
        if (!checkClientPlayer()) return original;
        Event<Vec3> vec3d = new Event<>(original, true, true);
        Listener.getPlayerVelocityTick().handleValue(vec3d);
        if (vec3d.isCancelled()) {
            return Vec3.ZERO;
        } else {
            return vec3d.context();
        }
    }

    @Shadow
    protected abstract void setSharedFlag(int index, boolean value);

    @Shadow
    protected abstract boolean getSharedFlag(int index);

    @Shadow
    protected abstract void checkFallDamage(
            double heightDifference, boolean onGround, BlockState state, BlockPos landedPosition);

    @Shadow
    public abstract InteractionResult interact(Player player, InteractionHand hand, Vec3 location);

    @Unique
    public void setDataFlag(int index, boolean val) {
        this.setSharedFlag(index, val);
    }

    @Unique
    public boolean getDataFlag(int index) {
        return getSharedFlag(index);
    }

    @Inject(method = "setRemoved", at = @At("RETURN"))
    public void onEntityRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        // 本 mixin 不继承 Entity，取 level() 只能经 (Entity) (Object) this 转换
        if (((Entity) (Object) this).level().isClientSide()) {
            Listener.getEntityRemoveListener().broadcast((Entity) (Object) this, reason);
        }
    }

    // TODO(26.2): 流体推动速度事件暂未适配。
    // 26.2 把 updateFluidHeightAndDoFluidPushing(TagKey<Fluid>) 拆成了 Entity.updateFluidInteraction()
    // -> EntityFluidInteraction.applyCurrentTo(TagKey, Entity, double)
    // -> EntityFluidInteraction$Tracker.applyCurrentTo(Entity, double) -> Entity.addDeltaMovement(Vec3)。
    // 最终的速度叠加由 Tracker 完成，而 Tracker 自身没有保存 TagKey<Fluid>，
    // 无法直接还原 "带流体类型的速度事件" 语义，需要重新设计（例如改为按 Tracker 实例区分）。
    //     @WrapOperation(
    //             method = "updateFluidInteraction",
    //             at =
    //                     @At(
    //                             value = "INVOKE",
    //                             target =
    //
    // "Lnet/minecraft/world/phys/Vec3;add(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
    //                             ordinal = 1))
    //     public Vec3 onEntityUpdateVelocity(
    //             Vec3 instance, Vec3 vec, Operation<Vec3> original, @Local(argsOnly = true) TagKey<Fluid> tagKey) {
    //         if (checkClientPlayer()) {
    //             Event<Vec3> eventVec3d = new Event<>(vec, true, true, tagKey);
    //             Listener.getPlayerFluidVelocityPoint().handleValue(eventVec3d);
    //             if (eventVec3d.isCancelled()) {
    //                 return instance;
    //             }
    //             return original.call(instance, eventVec3d.context);
    //         } else {
    //             return original.call(instance, vec);
    //         }
    //    }
}
