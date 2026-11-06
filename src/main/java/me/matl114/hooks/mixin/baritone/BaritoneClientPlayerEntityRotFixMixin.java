package me.matl114.hooks.mixin.baritone;

import com.mojang.authlib.GameProfile;
import java.util.Objects;
import me.matl114.accessors.events.ClientPlayerEntityAccess;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hooks.BaritoneHooks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(LocalPlayer.class)
public abstract class BaritoneClientPlayerEntityRotFixMixin extends AbstractClientPlayer
        implements ClientPlayerEntityAccess {
    //#if MC >= 260300
    //$$ // 26.3 起 LocalPlayer 不再覆写 getViewXRot（在 Entity 上）；@Shadow 只解析目标类自身声明的成员
    //#else
    @Shadow
    public abstract float getViewXRot(float tickProgress);
    //#endif

    @Shadow
    public abstract float getViewYRot(float tickProgress);

    @Unique
    Vec2 storedPreBaritonePitchYaw;

    public BaritoneClientPlayerEntityRotFixMixin(ClientLevel world, GameProfile profile) {
        super(world, profile);
    }

    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V",
                            shift = At.Shift.AFTER),
            order = 999)
    private void onPreBaritonePlayerUpdateEvent(CallbackInfo ci) {
        if (checkClientPlayer()
                && BaritoneHooks.getInstance().isBaritoneAPISupported()
                && (BaritoneHooks.getInstance().isBaritonePathing()
                        || BaritoneHooks.getInstance().isBaritoneElytraProcessing())) {
            LegalMovementManager manager = getLegalMovementManager();
            Vec2 rotModify = BaritoneHooks.getInstance().getBaritoneCurrentMoveRot(Minecraft.getInstance().player);
            Vec2 currentPY = new Vec2(getXRot(), getYRot());
            if (rotModify != null && !Objects.equals(rotModify, currentPY)) {
                if (manager.isResetRot()) {
                    storedPreBaritonePitchYaw = currentPY;
                    manager.playerStatus.restoreRotation();
                }
            }
        }
    }

    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V",
                            shift = At.Shift.AFTER),
            order = 1111)
    private void onPostBaritonePlayerUpdateEvent(CallbackInfo ci) {
        if (storedPreBaritonePitchYaw != null) {
            float pitch = getXRot();
            float yaw = getYRot();
            LegalMovementManager manager = getLegalMovementManager();
            if (pitch != manager.playerStatus.pitch || yaw != manager.playerStatus.yaw) {
                // mark that Baritone change the rotation
            } else {
                setXRot(storedPreBaritonePitchYaw.x);
                setYRot(storedPreBaritonePitchYaw.y);
            }
            storedPreBaritonePitchYaw = null;
        }
    }
}
