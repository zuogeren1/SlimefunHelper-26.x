package me.matl114.accessors.access;

import java.util.Map;
import java.util.Optional;
import me.matl114.utils.collections.IndexEntry;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public interface PendingUpdateManagerAccess {
    public static PendingUpdateManagerAccess of(BlockStatePredictionHandler manager) {
        return (PendingUpdateManagerAccess) manager;
    }

    public Optional<BlockState> getPendingBlockState(int sequenceNumber, BlockPos pos);

    public Map<BlockPos, IndexEntry<BlockState>> getPendingBlockStates(int sequenceNumber);

    public void restorePendingUpdate(int seq, Map<BlockPos, IndexEntry<BlockState>> states);

    public void restorePredictionSequence(int sequence, boolean pendingSequence);
}
