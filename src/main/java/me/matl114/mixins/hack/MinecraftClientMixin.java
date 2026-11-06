package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import me.matl114.accessors.access.ClientAccess;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.modules.combat.CombatExtra;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.mine.MineExtra;
import me.matl114.hacks.modules.render.RenderExtra;
import me.matl114.utils.ClientUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
//#if MC >= 260300
//$$ import net.minecraft.world.item.component.SwingAnimation;
//#endif
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin implements Cloneable, ClientAccess {

    @Shadow
    @Nullable
    public LocalPlayer player;

    @Shadow
    @Nullable
    public MultiPlayerGameMode gameMode;

    @Shadow
    @Nullable
    public HitResult hitResult;

    @Shadow
    private int rightClickDelay;

    @Shadow
    static Minecraft instance;

    @Final
    @Shadow
    public Options options;

    @Unique
    public void setItemUseCooldown(int cooldown) {
        this.rightClickDelay = cooldown;
    }

    @Unique
    public int getItemUseCooldown() {
        return this.rightClickDelay;
    }

    @ModifyArg(
            method = "handleKeybinds",
            at =
                    @At(
                            value = "INVOKE",
                            target =
//#if MC >= 26.2
                                    "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V",
                            ordinal = 0))
//#else
//$$                                 "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V",
//$$                         ordinal = 1))
//#endif
    public Screen onRedirectInventoryKeyPress(Screen screen) {
        if (InvExtra.INSTANCE.enableKeepInv.get()) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null
                    && ClientPlayerAccess.of(player).getKeepedInvHandler() != null
                    && ClientPlayerAccess.of(player).getKeepedInv() != null) {
                AbstractContainerScreen screen1 = ClientPlayerAccess.of(player).getKeepedInv();
                player.containerMenu = ClientPlayerAccess.of(player).getKeepedInvHandler();
                ClientPlayerAccess.of(player).clearKeepedInventory(false);
                return screen1;
            }
        }
        return screen;
    }

    @ModifyExpressionValue(
            method = "startAttack",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isHandsBusy()Z"))
    public boolean onEnableRidingAttack(boolean original) {

        if (CombatExtra.INSTANCE.rideAttack.get()) {
            // always not riding
            return false;
        }
        return original;
    }

    @WrapOperation(
            method = "startAttack",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z"))
    private boolean onVanillaAttackBlockFixCooldown(
            MultiPlayerGameMode instance, BlockPos pos, Direction direction, Operation<Boolean> original) {
        if (MineExtra.INSTANCE.vanillaFastBreakFix.get() && MineExtra.INSTANCE.getMiningPacketCooldown(1) > 0) {
            return false;
        }
        return original.call(instance, pos, direction);
    }

    boolean lastUse = false;

    @WrapOperation(
            method = "handleKeybinds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal = 2))
    public boolean onHoldUse(KeyMapping instance, Operation<Boolean> original) {
        boolean pressed = original.call(instance);
        if (InteractionTasks.getInteractExtra().holdUse.get()) {
            // hold use logic
            boolean lastUseFlag = lastUse;
            lastUse = pressed;
            if (player.isUsingItem()) {
                if (lastUseFlag == pressed) {
                    return true;
                }
                // if toggle off in the first few ticks , it is seen as original
                if (player.getTicksUsingItem()
                        < InteractionTasks.getInteractExtra().holdUseStartTick.get()) {
                    return pressed;
                }

                return lastUseFlag;
            }
        }
        return pressed;
    }

    // for attack when using shield
    @WrapOperation(
            method = "handleKeybinds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z", ordinal = 0))
    public boolean onAllowingPlayerAttackWhenUseItem(LocalPlayer instance, Operation<Boolean> original) {
        boolean flag = original.call(instance);
        if (flag && CombatExtra.INSTANCE.useAttack.get()) {
            // do attack logic
            boolean bl3 = false;
            // still do attack first
            while (options.keyAttack.consumeClick()) {
                bl3 |= this.startAttack();
            }
            // escape pickItemKey
            while (options.keyPickItem.consumeClick()) {
                this.pickBlockOrEntity();
            }
        }
        return flag;
    }

    @WrapOperation(
            method = "continueAttack",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z", ordinal = 0))
    public boolean onAllowingPlayerBreakingWhenUseItem(LocalPlayer instance, Operation<Boolean> original) {
        if (CombatExtra.INSTANCE.useAttack.get()) {
            return false;
        } else {
            return original.call(instance);
        }
    }

    //    @Redirect(method = "doItemUse", at = @At(value = "FIELD", target =
    // "Lnet/minecraft/client/Minecraft;itemUseCooldown:I"))
    //    public void onRewriteItemCooldown1(Minecraft instance, int value){
    //
    //    }

    @WrapOperation(
            method = "startUseItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isHandsBusy()Z"))
    public boolean onAllowRidingUse(LocalPlayer instance, Operation<Boolean> original) {
        if (InteractExtra.INSTANCE.rideUse.get()) {
            return false;
        }
        return original.call(instance);
    }

    @Shadow
    protected abstract void continueAttack(boolean b);

    @Shadow
    protected abstract void pickBlockOrEntity();

    @Shadow
    protected abstract boolean startAttack();

    @Shadow
    public int missTime;

    @Unique
    public void setAttackCooldown(int cooldown) {
        missTime = cooldown;
    }

    @Unique
    public int getAttackCooldown() {
        return missTime;
    }

    @Shadow
    public abstract Window getWindow();

    @Shadow
    protected abstract void startUseItem();

    @Override
    public ClientAccess clone() {
        try {
            ClientAccess clone = (MinecraftClientMixin) super.clone();
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }

    @Inject(method = "showOnlyReducedInfo", at = @At("HEAD"), cancellable = true)
    private void onEnhanceDebug(CallbackInfoReturnable<Boolean> cir) {
        if (RenderExtra.INSTANCE.enhancedDebugHud.get()) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    public void simulateRightClick() {
        startUseItem();
    }

    @Unique
    public void simulateLeftClick() {
        startAttack();
    }

    @Unique
    public InteractionResult simulateUseItem(InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (!itemStack.isEmpty()) {
            InteractionResult actionResult3 = this.gameMode.useItem(this.player, hand);
            if (actionResult3 instanceof InteractionResult.Success) {
                InteractionResult.Success success3 = (InteractionResult.Success) actionResult3;
                //#if MC >= 260300
                //$$ if (success3.swingSource() == InteractionResult.SwingSource.PREDICTED) {
                //#else
                if (success3.swingSource() == InteractionResult.SwingSource.CLIENT) {
                //#endif
                    ClientUtils.swing(this.player, hand);
                }
            }
            return actionResult3;
        }
        return InteractionResult.FAIL;
    }

    @WrapWithCondition(
            method = "continueAttack",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    //#if MC >= 260300
                                    //$$ "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z"))
                                    //#else
                                    "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;)V"))
                                    //#endif
    //#if MC >= 260300
    //$$ private boolean handleBlockBreakingNoSwing(LocalPlayer instance, InteractionHand hand, SwingAnimation animation, boolean sendToSwingingEntity) {
    //$$     if (MineExtra.INSTANCE.noSwing.get()) {
    //$$         return false;
    //$$     }
    //$$     return true;
    //$$ }
    //#else
    private boolean handleBlockBreakingNoSwing(LocalPlayer instance, InteractionHand hand) {
        if (MineExtra.INSTANCE.noSwing.get()) {
            return false;
        }
        return true;
    }
    //#endif

    @Unique
    private boolean blockAttack;

    @Inject(method = "startAttack", at = @At("HEAD"))
    private void resetBlockAttack(CallbackInfoReturnable<Boolean> cir) {
        blockAttack = false;
    }

    @Inject(
            method = "startAttack",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z"))
    private void onCaptureAttackBlock(CallbackInfoReturnable<Boolean> cir) {
        blockAttack = true;
    }

    @WrapWithCondition(
            method = "startAttack",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    //#if MC >= 260300
                                    //$$ "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z"))
                                    //#else
                                    "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;)V"))
                                    //#endif
    //#if MC >= 260300
    //$$ private boolean handleBlockAttackNoSwing(LocalPlayer instance, InteractionHand hand, SwingAnimation animation, boolean sendToSwingingEntity) {
    //$$     if (blockAttack) {
    //$$         blockAttack = false;
    //$$         if (MineExtra.INSTANCE.noSwing.get()) {
    //$$             return false;
    //$$         }
    //$$     }
    //$$     return true;
    //$$ }
    //#else
    private boolean handleBlockAttackNoSwing(LocalPlayer instance, InteractionHand hand) {
        if (blockAttack) {
            blockAttack = false;
            if (MineExtra.INSTANCE.noSwing.get()) {
                return false;
            }
        }
        return true;
    }
    //#endif
}
