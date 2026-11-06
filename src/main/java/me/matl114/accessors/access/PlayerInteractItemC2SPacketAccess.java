package me.matl114.accessors.access;

import javax.annotation.Nonnull;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public interface PlayerInteractItemC2SPacketAccess {
    void setHand(InteractionHand hand);

    void setYaw(float yaw);

    void setPitch(float pitch);

    void setItemStack(ItemStack stack);

    @Nonnull
    ItemStack getItemStack();

    static PlayerInteractItemC2SPacketAccess of(ServerboundUseItemPacket packet) {
        return (PlayerInteractItemC2SPacketAccess) packet;
    }

    static ServerboundUseItemPacket setContext(LocalPlayer player, ServerboundUseItemPacket packet) {
        //#if MC >= 260300
        //$$ of(packet).setItemStack(player.getItemInHand(packet.hand()).copy());
        //#else
        of(packet).setItemStack(player.getItemInHand(packet.getHand()).copy());
        //#endif
        return packet;
    }
}
