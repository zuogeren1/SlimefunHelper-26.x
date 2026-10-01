package me.matl114.events.impl;

import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.ItemStack;

@Data
@AllArgsConstructor
@Getter
@Accessors(fluent = true, chain = true)
public class UseItemOnBlock implements SequencedAction {
    @Setter
    BlockHitResult hitResult;

    @Setter
    InteractionResult actionResult;

    Optional<BlockPos> placingBlockPos;

    final InteractionHand hand;

    ItemStack handItem;

    public boolean blockPlace() {
        return placingBlockPos.isPresent();
    }
}
