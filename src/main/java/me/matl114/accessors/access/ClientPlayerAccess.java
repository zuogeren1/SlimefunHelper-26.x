package me.matl114.accessors.access;

import me.matl114.hacks.utils.ClientUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import me.matl114.accessors.events.ClientPlayerEntityAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

public interface ClientPlayerAccess extends ClientPlayerEntityAccess {

    @Nullable
    public AbstractContainerScreen getKeepedInv();

    @Nullable
    public AbstractContainerMenu getKeepedInvHandler();

    public void clearKeepedInventory(boolean closeInv);

    @Nonnull
    public static ClientPlayerAccess of(@Nonnull LocalPlayer player) {
        return (ClientPlayerAccess) player;
    }
    // get the Screen object which handler related to the server(should)
    default AbstractContainerScreen getServerOpeningScreen() {
        if (getKeepedInv() != null) return getKeepedInv();
        else return ClientUtils.getScreen() instanceof AbstractContainerScreen<?> han ? han : null;
    }

    @Nonnull
    default AbstractContainerMenu getServerScreenHandler() {
        if (getKeepedInvHandler() != null) return getKeepedInvHandler();
        else return ((LocalPlayer) this).containerMenu;
    }

    public boolean isForceNoFall();

    public void setForceNoFall(boolean fall);
}
