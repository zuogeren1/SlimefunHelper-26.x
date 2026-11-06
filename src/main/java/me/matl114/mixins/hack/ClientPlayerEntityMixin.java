package me.matl114.mixins.hack;

import me.matl114.utils.ClientUtils;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import java.util.Objects;
import lombok.Getter;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.events.Event;
import me.matl114.hacks.*;
import me.matl114.hacks.modules.extra.BadPacketsFix;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.move.MoveTimer;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.modules.move.Sprint;
import me.matl114.hacks.modules.render.NoRender;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityMixin extends AbstractClientPlayer implements ClientPlayerAccess {
    @Unique
    private boolean forceNoFall;

    public boolean isForceNoFall() {
        return forceNoFall;
    }

    public void setForceNoFall(boolean fall) {
        this.forceNoFall = fall;
    }

    public ClientPlayerEntityMixin(ClientLevel world, GameProfile profile) {
        super(world, profile);
    }

    //    @Unique
    //    private ScreenHandler keepedInventoryHandler=null;
    @Shadow
    public abstract void clientSideCloseContainer();

    @Shadow
    @Final
    protected Minecraft minecraft;

    @Shadow
    public abstract void tick();

    @Shadow
    public abstract void move(MoverType movementType, Vec3 movement);

    @Shadow
    protected abstract void sendPosition();

    @Shadow
    public ClientInput input;

    @Shadow
    private boolean wasSprinting;

    @Shadow
    public abstract boolean isShiftKeyDown();

    @Shadow
    public abstract boolean isUsingItem();

    @Shadow
    private boolean startedUsingItem;

    @Shadow
    public abstract void resetPos();

    @Shadow
    private boolean crouching;

    @Getter
    @Unique
    public AbstractContainerScreen keepedInv = null;

    @Getter
    @Unique
    public AbstractContainerMenu keepedInvHandler = null;

    @Unique
    boolean forceCloseInv = false;

    @Unique
    public void clearKeepedInventory(boolean closeInv) {
        // todo closeInv log
        keepedInv = null;
        AbstractContainerMenu handler = keepedInvHandler;
        keepedInvHandler = null;
        if (closeInv) {
            forceCloseInv = true;
            try {
                ((LocalPlayer) (Object) this).closeContainer();
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                forceCloseInv = false;
            }
        }
    }

    @Override
    public float getEffectBlendFactor(Holder<MobEffect> effect, float tickProgress) {
        if (NoRender.INSTANCE.noNausea() && Objects.equals(effect, MobEffects.NAUSEA)) {
            return 0.0F;
        }
        if ((NoRender.INSTANCE.noDarkNess() && Objects.equals(effect, MobEffects.DARKNESS))
                || (NoRender.INSTANCE.noBlindness() && Objects.equals(effect, MobEffects.BLINDNESS))) {
            return 0.0F;
        }
        return super.getEffectBlendFactor(effect, tickProgress);
    }

    @Inject(method = "closeContainer", at = @At(value = "HEAD"), cancellable = true)
    public void closeHandledScreen(CallbackInfo ci) {
        if (!this.forceCloseInv && InvExtra.INSTANCE.enableKeepInv.get()) {
            // do not keep the inventory handler because we can get accessed to it any time
            if (ClientUtils.getScreen(minecraft) instanceof AbstractContainerScreen handled
                    && !(handled.getMenu() instanceof InventoryMenu)
                    && !(handled.getMenu() instanceof CreativeModeInventoryScreen.ItemPickerMenu)) {
                keepedInv = handled;
                this.keepedInvHandler = ((LocalPlayer) (Object) this).containerMenu;
                this.clientSideCloseContainer();
                ci.cancel();
            }
        }
    }

    @Unique
    public double getAttributeValue(Holder<Attribute> attribute) {
        if (attribute == Attributes.MOVEMENT_SPEED
                && MovTasks.getFlight().overrideWalkSpeed.get()) {
            return MovTasks.getFlight().getOverridingWalkSpeed();
        }
        return super.getAttributeValue(attribute);
    }

    @ModifyExpressionValue(
            method = "modifyInput",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
    private boolean noSlowUsingItem(boolean original) {
        if (MovTasks.getNoSlowDown().workNoSlowItemThisTick) {
            return false;
        }
        return original;
    }

    @ModifyExpressionValue(
            method = "isSlowDueToUsingItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
    private boolean noSlowUsingItemDoNotBlockSprint(boolean original) {
        if (MovTasks.getNoSlowDown().workNoSlowItemThisTick) {
            return false;
        }
        return original;
    }

    @WrapOperation(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;canStartSprinting()Z"))
    private boolean noSlowUsingItemDoNotBlockSprint1(LocalPlayer instance, Operation<Boolean> original) {
        // fix viafabric
        if (MovTasks.getNoSlowDown().workNoSlowItemThisTick) {
            boolean v = startedUsingItem;
            startedUsingItem = false;
            try {
                return original.call(instance);
            } finally {
                startedUsingItem = v;
            }
        }
        return original.call(instance);
    }

    @WrapOperation(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;shouldStopRunSprinting()Z"))
    private boolean noSlowUsingItemDoNotBlockSprint2(LocalPlayer instance, Operation<Boolean> original) {
        // fix viafabric
        if (MovTasks.getNoSlowDown().workNoSlowItemThisTick) {
            boolean v = startedUsingItem;
            startedUsingItem = false;
            try {
                return original.call(instance);
            } finally {
                startedUsingItem = v;
            }
        }
        return original.call(instance);
    }

    @WrapOperation(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;shouldStopSwimSprinting()Z"))
    private boolean nnoSlowUsingItemDoNotBlockSprint3(LocalPlayer instance, Operation<Boolean> original) {
        // fix viafabric
        if (MovTasks.getNoSlowDown().workNoSlowItemThisTick) {
            boolean v = startedUsingItem;
            startedUsingItem = false;
            try {
                return original.call(instance);
            } finally {
                startedUsingItem = v;
            }
        }
        return original.call(instance);
    }

    @ModifyExpressionValue(
            method = "modifyInput",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isMovingSlowly()Z"))
    private boolean noSlowSneak(boolean original) {
        if (MovTasks.getNoSlowDown().shouldNoSlowSneak()) {
            return false;
        }
        return original;
    }

    @Override
    protected float getBlockSpeedFactor() {
        if (MovTasks.getNoSlowDown().blockSlow.get()) {
            return 1.0f;
        }
        return super.getBlockSpeedFactor();
    }

    @Inject(method = "permissions", at = @At("HEAD"), cancellable = true)
    protected void grantAllClientPermissions(CallbackInfoReturnable<PermissionSet> cir) {
        cir.setReturnValue(PermissionSet.ALL_PERMISSIONS);
    }

    //
    //    @Override
    //    public double getEntityInteractionRange() {
    //
    //        if(HotKeys.getHotkeyToggleManager().getState(HotKeys.REACH)){
    //            return super.getEntityInteractionRange() + 1.0;
    //        }
    //        return super.getEntityInteractionRange();
    //    }

    //    @Unique
    //    public void syncLocationPackets(){
    //        double d = this.getX() - this.lastX;
    //        double e = this.getY() - this.lastBaseY;
    //        double f = this.getZ() - this.lastZ;
    //        double g = (double)(this.getYaw() - this.lastYaw);
    //        double h = (double)(this.getPitch() - this.lastPitch);
    //
    //        boolean bl2 = MathHelper.squaredMagnitude(d, e, f) > MathHelper.square(2.0E-4) ||
    // this.ticksSinceLastPositionPacketSent > 20;
    //        boolean bl3 = g != 0.0 || h != 0.0;
    //        if (bl2 && bl3) {
    //            this.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(this.getX(), this.getY(), this.getZ(),
    // this.getYaw(), this.getPitch(), this.isOnGround()));
    //        } else if (bl2) {
    //            this.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(this.getX(), this.getY(),
    // this.getZ(), this.isOnGround()));
    //        } else if (bl3) {
    //            this.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(this.getYaw(), this.getPitch(),
    // this.isOnGround()));
    //        } else if (this.lastOnGround != this.isOnGround()) {
    //            this.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(this.isOnGround()));
    //        }
    //
    //        if (bl2) {
    //            this.lastX = this.getX();
    //            this.lastBaseY = this.getY();
    //            this.lastZ = this.getZ();
    //            this.ticksSinceLastPositionPacketSent = 0;
    //        }
    //
    //        if (bl3) {
    //            this.lastYaw = this.getYaw();
    //            this.lastPitch = this.getPitch();
    //        }
    //        this.lastOnGround = this.isOnGround();
    //    }

    @WrapOperation(
            method = "aiStep",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/LocalPlayer;jumpFromGround()V",
                            ordinal = 0))
    public void onCancelJumpAfterToggle(LocalPlayer instance, Operation<Void> original) {}

    // multiply movements timer
    // todo: speeding up with more packets, not big speed (timer speedup

    @Override
    public void travel(Vec3 movementInput) {
        super.travel(movementInput);
        MoveTimer timer = MovTasks.getMoveTimer();
        if (timer.isActive()) {
            for (int i = 0; i < timer.timer.get(); ++i) {
                this.sendPosition();
                super.travel(movementInput);
            }
        }
    }

    @Unique
    private boolean shouldDirectionalSprint() {
        Sprint sprintModule = MovTasks.getSprint();
        return sprintModule.directionalSprint.get()
                && (input.keyPresses.backward() && !input.keyPresses.forward())
                && sprintModule.enableSprintDirectionalThisTick;
    }

    @ModifyExpressionValue(
            method = "shouldStopRunSprinting",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;hasForwardImpulse()Z"))
    private boolean allDirectionSprint(boolean original) {
        if (shouldDirectionalSprint()) {
            return true;
        }
        return original;
    }

    @ModifyExpressionValue(
            method = "canStartSprinting",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;hasForwardImpulse()Z"))
    private boolean allDirectionSprint2(boolean original) {
        if (shouldDirectionalSprint()) {
            return true;
        }
        return original;
    }

    @ModifyExpressionValue(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;hasForwardImpulse()Z"))
    private boolean allDirectionSprint3(boolean original) {
        if (shouldDirectionalSprint()) {
            return true;
        }
        return original;
    }

    @ModifyExpressionValue(
            method = "shouldStopSwimSprinting",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;hasForwardImpulse()Z"))
    private boolean allDirectionSprint4(boolean original) {
        if (shouldDirectionalSprint()) {
            return true;
        }
        return original;
    }

    @ModifyExpressionValue(
            method = "handlePortalTransitionEffect",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;isAllowedInPortal()Z"))
    private boolean onPortalGui(boolean original) {
        if (ExtraTasks.getClientExtra().portalGui.get()) return true;
        return original;
    }

    // redirection conflict with viafabricplus
    //    @Redirect(method = "tickMovement", at = @At(value = "INVOKE", target =
    // "Lnet/minecraft/client/input/Input;hasForwardMovement()Z"))
    //    private boolean allDirectionSprint(Input instance){
    //        if(legalDirectional.get()){
    //            return true;
    //        }
    //        return instance.hasForwardMovement();
    //    }

    @Unique
    @Override
    public ItemEntity drop(ItemStack stack, boolean throwRandomly, boolean retainOwnership) {
        if (!stack.isEmpty()
                && this.level().isClientSide()
                && InvTasks.SUPPRESS_DROPITEM_SPAWN.get()
                && !Minecraft.getInstance().isSameThread()) {
            ClientUtils.swing((LocalPlayer) (Object) this, InteractionHand.MAIN_HAND);
            return null;
        } else {
            return super.drop(stack, throwRandomly, retainOwnership);
        }
    }

    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
    public void onBlockVelocity(double x, double z, CallbackInfo ci) {
        if (MovTasks.getVelocity().noBlock.get()) {
            ci.cancel();
        }
    }

    @WrapOperation(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V",
                            ordinal = 0))
    private void captureInputPacketSendToAvoidIdiotViaFabricPlus(
            ClientPacketListener instance, Packet packet, Operation<Void> original) {
        original.call(instance, packet);
        if (packet instanceof ServerboundPlayerInputPacket inputShit) {
            Event<ServerboundPlayerInputPacket> fakeEvent = new Event<>(inputShit, true, true);
            PlayerStateManager.INSTANCE.onPlayerInput(fakeEvent);
            BadPacketsFix.INSTANCE.onSendInput(fakeEvent);
        }
    }
}
