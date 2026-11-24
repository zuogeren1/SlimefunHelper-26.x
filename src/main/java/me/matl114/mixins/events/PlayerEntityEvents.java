package me.matl114.mixins.events;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.events.EntityAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(Player.class)
public abstract class PlayerEntityEvents implements EntityAccess<Player> {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void onPlayerTravel(Vec3 movementInput, CallbackInfo ci, @Local(argsOnly = true) LocalRef<Vec3> move) {
        if ((Object) this instanceof LocalPlayer clientPlayerEntity && checkClientPlayer()) {
            Event<Vec3> event = new Event<>(movementInput, true, true, clientPlayerEntity);
            Listener.getPlayerTravelingTick().handleValue(event);
            if (event.isCancelled()) {
                ci.cancel();
            } else {
                if (!ClientPlayerAccess.of(clientPlayerEntity)
                                .getLegalMovementManager()
                                .preTravelTick(clientPlayerEntity, event)
                        || event.isCancelled()) {
                    ci.cancel();
                    ClientPlayerAccess.of(clientPlayerEntity)
                            .getLegalMovementManager()
                            .postTravelTick(clientPlayerEntity, event);
                } else {
                    if (event.context != movementInput) {
                        move.set(event.context);
                    }
                }
            }
        }
    }

    @Inject(method = "travel", at = @At("RETURN"))
    private void onPlayerTravelReturn(Vec3 movementInput, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer clientPlayerEntity && checkClientPlayer()) {
            ClientPlayerAccess.of(clientPlayerEntity)
                    .getLegalMovementManager()
                    .postTravelTick(clientPlayerEntity, new Event<>(movementInput, false, false, clientPlayerEntity));
        }
    }
}
