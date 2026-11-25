package me.matl114.accessors.access;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

public interface PlayerInteractBlockC2SPacketAccess {
    void setHand(InteractionHand hand);

    void setBlockHitResult(BlockHitResult blockHitResult);

    void setSequence(int sequence);

    UseContext getUseContext();

    default boolean hasUseContext() {
        return getUseContext() != null;
    }

    void setUseContext(UseContext stack);

    public static record UseContext(ItemStack stack, InteractionResult actionResult, Optional<BlockPos> placePos) {
        public boolean isEmpty() {
            return stack.isEmpty() || !(stack.getItem() instanceof BlockItem);
        }

        public boolean blockPlace() {
            return placePos.isPresent();
        }

        public boolean isAccepted() {
            return actionResult.consumesAction();
        }
    }

    static PlayerInteractBlockC2SPacketAccess of(ServerboundUseItemOnPacket packet) {
        //#if MC >= 260300
        //$$ return (PlayerInteractBlockC2SPacketAccess) (Object) packet;
        //#else
        return (PlayerInteractBlockC2SPacketAccess) packet;
        //#endif
    }
}
