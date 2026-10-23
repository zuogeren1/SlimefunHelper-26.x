package me.matl114.mixins.fix;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.matl114.hacks.InvTasks;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Environment(EnvType.CLIENT)
@Mixin(AbstractContainerMenu.class)
public abstract class ScreenHandlerFixMixin {

    @WrapOperation(
            method = "clicked",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/inventory/AbstractContainerMenu;doClick(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V"))
    private void suppressDropItemSpawn(
            AbstractContainerMenu instance,
            int slotIndex,
            int button,
            ContainerInput actionType,
            Player player,
            Operation<Void> original) {
        if (!player.level().isClientSide()) {
            original.call(instance, slotIndex, button, actionType, player);
            return;
        }

        boolean previous = InvTasks.SUPPRESS_DROPITEM_SPAWN.get();
        InvTasks.SUPPRESS_DROPITEM_SPAWN.set(true);
        try {
            original.call(instance, slotIndex, button, actionType, player);
        } finally {
            InvTasks.SUPPRESS_DROPITEM_SPAWN.set(previous);
        }
    }
}
