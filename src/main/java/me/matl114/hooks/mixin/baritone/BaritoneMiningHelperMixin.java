package me.matl114.hooks.mixin.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.utils.BlockBreakHelper;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.matl114.hacks.modules.mine.MineExtra;
import me.matl114.hacks.modules.survival.BaritoneFix;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
//#if MC >= 260300
//$$ import net.minecraft.world.item.component.SwingAnimation;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Environment(EnvType.CLIENT)
@Mixin(BlockBreakHelper.class)
public abstract class BaritoneMiningHelperMixin {
    @WrapOperation(
            method = "Lbaritone/utils/BlockBreakHelper;tick(Z)V",
            at = @At(value = "FIELD", target = "Lbaritone/api/Settings$Setting;value:Ljava/lang/Object;"),
            require = 0,
            expect = 0,
            remap = false)
    private Object onOverrideMiningCooldownBaritone(Settings.Setting instance, Operation<Object> original) {
        if (instance == BaritoneAPI.getSettings().blockBreakSpeed) {
            if (BaritoneFix.INSTANCE.enableMiningCooldown.get()) {
                return (Integer) MineExtra.INSTANCE.getMiningPacketCooldown(1);
            }
        }
        return original.call(instance);
    }

    @WrapWithCondition(
            method = "Lbaritone/utils/BlockBreakHelper;tick(Z)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    //#if MC >= 260300
                                    //$$ "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z",
                                    //#else
                                    "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;)V",
                                    //#endif
                            ordinal = 0),
            require = 0,
            expect = 0,
            remap = false)
    //#if MC >= 260300
    //$$ private boolean onStopSwingHand1(LocalPlayer instance, InteractionHand hand, SwingAnimation animation, boolean sendToSwingingEntity) {
    //$$     if (BaritoneFix.INSTANCE.applyMineSettingsToBaritone.get() && MineExtra.INSTANCE.noSwing.get()) {
    //$$         return false;
    //$$     }
    //$$     return true;
    //$$ }
    //#else
    private boolean onStopSwingHand1(LocalPlayer instance, InteractionHand hand) {
        if (BaritoneFix.INSTANCE.applyMineSettingsToBaritone.get() && MineExtra.INSTANCE.noSwing.get()) {
            return false;
        }
        return true;
    }
    //#endif

    @WrapWithCondition(
            method = "Lbaritone/utils/BlockBreakHelper;tick(Z)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    //#if MC >= 260300
                                    //$$ "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z",
                                    //#else
                                    "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;)V",
                                    //#endif
                            ordinal = 1),
            require = 0,
            expect = 0,
            remap = false)
    //#if MC >= 260300
    //$$ private boolean onStopSwingHand2(LocalPlayer instance, InteractionHand hand, SwingAnimation animation, boolean sendToSwingingEntity) {
    //$$     if (BaritoneFix.INSTANCE.applyMineSettingsToBaritone.get() && MineExtra.INSTANCE.noSwing.get()) {
    //$$         return false;
    //$$     }
    //$$     return true;
    //$$ }
    //#else
    private boolean onStopSwingHand2(LocalPlayer instance, InteractionHand hand) {
        if (BaritoneFix.INSTANCE.applyMineSettingsToBaritone.get() && MineExtra.INSTANCE.noSwing.get()) {
            return false;
        }
        return true;
    }
    //#endif
}
