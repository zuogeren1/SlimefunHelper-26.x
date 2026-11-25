package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Environment(EnvType.CLIENT)
@Mixin(EntityType.class)
public abstract class EntityTypeEvents {
    @WrapOperation(
            method =
                    //#if MC >= 26.2
                    "create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnRequest;)Lnet/minecraft/world/entity/Entity;",
                    //#else
                    //$$ "create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;",
                    //#endif
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/EntityType$EntityFactory;create(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;"))
    private <T extends Entity> T onCreate(
            EntityType.EntityFactory<T> instance, EntityType<T> tEntityType, Level world, Operation<T> original) {
        T val = original.call(instance, tEntityType, world);
        if (world.isClientSide()) {
            Event<Entity> event = new Event<Entity>(val, true, true, tEntityType);
            Listener.getEntityCreateListener().handleValue(event);
            if (event.isCancelled()) {
                return null;
            } else {
                return (T) event.context();
            }
        } else {
            return val;
        }
    }
}
