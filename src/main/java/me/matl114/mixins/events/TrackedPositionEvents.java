package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.matl114.accessors.events.TrackedPositionAccess;
import me.matl114.events.Listener;
import me.matl114.events.impl.PosUpdate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Environment(EnvType.CLIENT)
@Mixin(VecDeltaCodec.class)
public abstract class TrackedPositionEvents implements TrackedPositionAccess {
    @Shadow
    private Vec3 base;

    @Unique
    public Entity owner;

    public void setOwner(Entity entity) {
        this.owner = entity;
    }

    @WrapOperation(
            method = "setBase",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/network/protocol/game/VecDeltaCodec;base:Lnet/minecraft/world/phys/Vec3;"))
    private void onSetPos(VecDeltaCodec instance, Vec3 value, Operation<Void> original) {
        Vec3 oldValue = this.base;
        original.call(instance, value);
        // do not listen local serverSide
        if (this.owner != null && this.owner.level().isClientSide()) {
            Listener.getEntityTrackedPositionUpdate().broadcast(new PosUpdate(oldValue, this.base, this.owner));
        }
    }
}
