package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Set;
import me.matl114.hacks.modules.extra.BadPacketsFix;
import me.matl114.hacks.modules.move.AutoResync;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(
            method = "handlePlayerInfoUpdate",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
                            shift = At.Shift.BEFORE,
                            remap = false),
            cancellable = true)
    public void onInvalidPlayerEntry(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
        if (BadPacketsFix.INSTANCE.fixInvalidPlayerEntryUpdate.get()) {
            ci.cancel();
        }
    }

    @WrapOperation(
            method = "handleMovePlayer",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/ClientPacketListener;setValuesFromPositionPacket(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;Lnet/minecraft/world/entity/Entity;Z)Z"))
    private boolean wrapSetPositionLook(
            PositionMoveRotation pos, Set<Relative> flags, Entity entity, boolean bl, Operation<Boolean> original) {
        if (AutoResync.INSTANCE.noVelocitySetback.get()) {
            Vec3 currentVelocity = entity.getDeltaMovement();
            boolean re = original.call(pos, flags, entity, bl);
            entity.setDeltaMovement(currentVelocity);
            return re;
        } else {
            return original.call(pos, flags, entity, bl);
        }
    }
}
