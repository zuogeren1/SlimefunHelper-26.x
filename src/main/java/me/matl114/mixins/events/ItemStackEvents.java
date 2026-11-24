package me.matl114.mixins.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.matl114.events.Event;
import me.matl114.events.RenderListener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
// to avoid clash with other
@Mixin(value = ItemStack.class, priority = 10000)
public abstract class ItemStackEvents {
    @Inject(method = "getTooltipLines", at = @At(value = "RETURN"), cancellable = true)
    public void onTooltip(
            Item.TooltipContext context,
            @Nullable Player player,
            TooltipFlag type,
            CallbackInfoReturnable<List<Component>> cir) {
        List<Component> tooltip = cir.getReturnValue();
        boolean rebuild = false;
        if (!(tooltip instanceof ArrayList)) {
            tooltip = new ArrayList<>(tooltip);
            rebuild = true;
        }
        Event<List<Component>> event =
                new Event<>(tooltip, false, false, (ItemStack) (Object) this, type.isAdvanced(), type.isCreative());
        RenderListener.getTooltipShow().handleValue(event);
        if (rebuild && !Objects.equals(cir.getReturnValue(), tooltip)) {
            cir.setReturnValue(tooltip);
        }
    }
}
