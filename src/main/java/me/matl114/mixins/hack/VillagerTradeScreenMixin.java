package me.matl114.mixins.hack;

import me.matl114.accessors.access.MerchantScreenAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MerchantScreen.class)
@Environment(EnvType.CLIENT)
public abstract class VillagerTradeScreenMixin extends AbstractContainerScreen<MerchantMenu>
        implements MerchantScreenAccess {
    public VillagerTradeScreenMixin(MerchantMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Shadow
    private int shopItem;

    @Shadow
    protected abstract void postButtonClick();

    @Shadow
    private int scrollOff;

    @Accessor("shopItem")
    public abstract int getSelectedIndex();

    /**
     * 只改本地选中的交易项，不向服务端发同步包。
     *
     * <p>批量挑选交易时每换一项都发一次同步包会被服务端限流，
     * 因此先本地翻页，最后再用 {@link #setSelectedIndex(int)} 同步一次。
     */
    @Unique
    @Override
    public void selectIndexLocally(int index) {
        this.shopItem = index;
        if (index < this.scrollOff || index >= this.scrollOff + 7) {
            this.scrollOff = Math.max(0, index - 6);
        }
    }

    @Unique
    @Override
    public void setSelectedIndex(int index) {
        this.shopItem = index;
        postButtonClick();
    }
}
