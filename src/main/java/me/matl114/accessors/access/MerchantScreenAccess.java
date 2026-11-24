package me.matl114.accessors.access;

import net.minecraft.client.gui.screens.inventory.MerchantScreen;

public interface MerchantScreenAccess {
    public int getSelectedIndex();

    public void setSelectedIndex(int k);

    public void selectIndexLocally(int index);

    public static MerchantScreenAccess of(MerchantScreen screen) {
        return (MerchantScreenAccess) screen;
    }
}
