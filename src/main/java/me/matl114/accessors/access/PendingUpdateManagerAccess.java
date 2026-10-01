package me.matl114.accessors.access;

import java.util.Optional;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public interface PendingUpdateManagerAccess {
    public static PendingUpdateManagerAccess of(BlockStatePredictionHandler manager) {
        return (PendingUpdateManagerAccess) manager;
    }

    public Optional<BlockState> getPendingBlockState(int sequenceNumber, BlockPos pos);
}
