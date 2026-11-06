package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.matl114.events.Event;
import me.matl114.events.FluidTagContext;
import me.matl114.events.Listener;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 流体推动速度事件（内层）：真正派发 {@code Event<Vec3>}。
 *
 * <p>26.3 把"内层"从 {@code Tracker#applyCurrentTo} 挪到了
 * {@code CurrentAccumulator#applyTo}，所以 {@code @Mixin} 目标与 {@code method} 都按版本分叉；
 * 两版的注入点都只有一处 {@code Entity#addDeltaMovement(Vec3)}。
 *
 * <p>只对**客户端玩家**触发 —— 与原实现里的 {@code checkClientPlayer()} 一致，
 * 否则服务端 tick 的其它实体（怪物等）也会进到这里。
 */
//#if MC >= 260300
//$$ @Mixin(targets = "net.minecraft.world.entity.EntityFluidInteraction$CurrentAccumulator")
//#else
@Mixin(targets = "net.minecraft.world.entity.EntityFluidInteraction$Tracker")
//#endif
public abstract class FluidTrackerMixin {

    @WrapOperation(
            //#if MC >= 260300
            //$$ method = "applyTo",
            //#else
            method = "applyCurrentTo",
            //#endif
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/Entity;addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void onFluidPush(Entity instance, Vec3 delta, Operation<Void> original) {
        if (instance != Minecraft.getInstance().player) {
            original.call(instance, delta);
            return;
        }
        Event<Vec3> event = new Event<>(delta, true, true, FluidTagContext.CURRENT.get());
        Listener.getPlayerFluidVelocityPoint().handleValue(event);
        if (event.isCancelled()) {
            return;
        }
        original.call(instance, event.context());
    }
}
