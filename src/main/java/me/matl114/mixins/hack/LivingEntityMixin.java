package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.matl114.accessors.access.LivingEntityAccess;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.move.ElytraExtra;
import me.matl114.hacks.modules.render.HandView;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.entity.PlayerInputUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
//#if MC >= 260300
//$$ import net.minecraft.world.item.component.SwingAnimation;
//#endif
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity implements LivingEntityAccess {

    @Shadow
    protected int fallFlyTicks;

    @Accessor("noJumpDelay")
    public abstract void setJumpingCooldown(int cooldown);

    @Shadow
    public abstract float getViewYRot(float tickDelta);

    public LivingEntityMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @Shadow
    protected abstract float getJumpPower(float st);

    @Shadow
    public abstract boolean isFallFlying();

    @Shadow
    public abstract void remove(RemovalReason reason);

    @Shadow
    public abstract void setItemSlot(EquipmentSlot slot, ItemStack stack);

    @Shadow
    protected abstract boolean shouldTravelInFluid(FluidState state);

    @Shadow
    protected ItemStack useItem;

    @Shadow
    protected int useItemRemaining;

    @Shadow
    public abstract boolean isUsingItem();

    @Shadow
    @Final
    public static EntityDataAccessor<Byte> DATA_LIVING_ENTITY_FLAGS;

    @Unique
    @Override
    public float getJumpUpwardSpeed(float strength) {
        return getJumpPower(1.0f);
    }

    //    @Inject(method = "tick", at = @At(value = "INVOKE", target =
    // "Lnet/minecraft/entity/LivingEntity;isFallFlying()Z", shift = At.Shift.BEFORE))
    //    private void onWriteFlyingTicks(CallbackInfo ci){
    //        if(elytraUnbreakable.get() && fallFlyingTicks > 18){
    //            if(MovTasks.runElytraUnbreakable(this)){
    //                fallFlyingTicks = 0;
    //            }
    //        }
    //    }

    @Inject(
            method = "jumpFromGround",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/LivingEntity;addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
                            shift = At.Shift.BEFORE),
            cancellable = true)
    private void fixJumpingWhileSprintingBackward(CallbackInfo ci, @Local Vec3 vec3d) {
        if (MovTasks.getSprint().directionalSprint.get()) {
            //            float g = this.getYaw() * 0.017453292F;
            Vec3 rot = EntityUtils.pitchYawToRotation(0.0F, this.getYRot());
            if (rot.x * vec3d.x + rot.z * vec3d.z < 0) {
                // inversed
                this.addDeltaMovement(rot.normalize().scale(-0.2));
                needsSync = true;
                ci.cancel();
            }
        }
    }

    @ModifyExpressionValue(
            method = "travelInAir",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
    private float onIgnoreSlipperiness(float original) {
        if (MovTasks.getNoSlowDown().blockFrac.get()) {
            return 0.6F;
        } else {
            return original;
        }
    }

    @Inject(
            method = "travel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/LivingEntity;travelInFluid(Lnet/minecraft/world/phys/Vec3;)V",
                            shift = At.Shift.BEFORE),
            cancellable = true)
    private void onWaterGlide(Vec3 movementInput, CallbackInfo ci) {
        if (checkClientPlayer()) {
            if (isFallFlying()
                    && ((ElytraExtra.INSTANCE.canFireworkControlMotion()))
                    && (ElytraExtra.INSTANCE.hasFireworkVelocityOverrides())) {
                ci.cancel();
                Vec3 overriding = ElytraExtra.INSTANCE.requestNextOverrideVelocity();
                Vec3 vec3d = this.getDeltaMovement();
                if (overriding != null) {
                    this.setDeltaMovement(overriding);
                } else {
                    this.setDeltaMovement(EntityUtils.calculateGlidingVelocity(
                            (LocalPlayer) (Entity) this, vec3d, this.getLookAngle(), !this.isNoGravity()));
                }
                ;
                this.move(MoverType.SELF, this.getDeltaMovement());
            }
        }
    }

    @WrapOperation(
            method = "travelFallFlying",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/LivingEntity;updateFallFlyingMovement(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 travelGliding(LivingEntity instance, Vec3 oldVelocity, Operation<Vec3> original) {
        if (checkClientPlayer()) {
            Vec3 velocity = ElytraExtra.INSTANCE.requestNextOverrideVelocity();
            if (velocity == null) {
                velocity = original.call(instance, oldVelocity);
            }
            velocity = ElytraExtra.INSTANCE.clampFireworkSpeedInWeb(velocity);
            return velocity;
        }
        return original.call(instance, oldVelocity);
    }

    @WrapOperation(
            method = "updatingUsingItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;stopUsingItem()V"))
    private void updatingUsingItem(LivingEntity instance, Operation<Void> original) {
        if (checkClientPlayer() && InteractExtra.INSTANCE.clientUsingStateFix.get()) {
            // do not reset LocalPlayer using flag here
            if (((Byte) this.entityData.get(DATA_LIVING_ENTITY_FLAGS) & 1) <= 0) {
                original.call(instance);
            } else {
                this.useItem = ItemStack.EMPTY;
                this.useItemRemaining = 0;
            }
        } else {
            original.call(instance);
        }
    }

    //#if MC >= 260300
    //$$ @Inject(method = "getModifiedSwingDuration", at = @At("RETURN"), cancellable = true)
    //$$ private void applyHandViewSwingSpeed(SwingAnimation animation, CallbackInfoReturnable<Integer> cir) {
    //#else
    @Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true)
    private void applyHandViewSwingSpeed(CallbackInfoReturnable<Integer> cir) {
    //#endif
        HandView handView = HandView.INSTANCE;
        if (checkClientPlayer()
                && handView != null
                && handView.isActive()
                && handView.swingSpeedEnabled.get()
                && handView.swingSpeed.get() != 1.0D) {
            // 保留 vanilla 的物品/药水修正，并把挥手时长下限压到 1 tick
            cir.setReturnValue(Math.max(1, (int) Math.round(cir.getReturnValue() / handView.swingSpeed.get())));
        }
    }

    // 与上游 1:1：只拦「贴墙 + 跳跃」的那一处 onClimbable；handleOnClimbable 的限速与
    // resetFallDistance 保持不动（拦掉会带出坠落伤害），按住跳跃键时照旧能爬（保留 !jump() 守卫）
    @WrapOperation(
            method = "handleRelativeFrictionAndCalculateMovement",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;onClimbable()Z"))
    private boolean onNoClimbing(LivingEntity instance, Operation<Boolean> original) {
        if (checkClientPlayer()
                && MovTasks.getVelocity().noClimbing.get()
                && !PlayerInputUtils.of(Minecraft.getInstance().player).jump()) {
            return false;
        }
        return original.call(instance);
    }
}
