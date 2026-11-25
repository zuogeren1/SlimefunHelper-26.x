package me.matl114.mixins.access;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import me.matl114.accessors.access.PendingUpdateManagerAccess;
import me.matl114.utils.collections.IndexEntry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Environment(EnvType.CLIENT)
@Mixin(BlockStatePredictionHandler.class)
public abstract class PendingUpdateManagerMixin implements PendingUpdateManagerAccess {

    @Shadow
    @Final
    private Long2ObjectOpenHashMap<BlockStatePredictionHandler.ServerVerifiedState> serverVerifiedStates;

    @Shadow
    private int currentSequenceNr;

    @Shadow
    private boolean isPredicting;

    @Unique
    public Optional<BlockState> getPendingBlockState(int sequenceNumber, BlockPos pos) {
        var pending = serverVerifiedStates.get(pos.asLong());
        return (pending == null || pending.sequence < sequenceNumber)
                ? Optional.empty()
                : Optional.of(pending.blockState);
    }

    @Unique
    public Map<BlockPos, IndexEntry<BlockState>> getPendingBlockStates(int sequenceNumber) {
        Map<BlockPos, IndexEntry<BlockState>> states = new HashMap<>();
        for (var entry : serverVerifiedStates.long2ObjectEntrySet()) {
            var pending = entry.getValue();
            if (pending.sequence >= sequenceNumber) {
                states.put(
                        BlockPos.of(entry.getLongKey()), new IndexEntry<>(pending.sequence, pending.blockState));
            }
        }
        return states;
    }

    @Unique
    public void restorePendingUpdate(int seq, Map<BlockPos, IndexEntry<BlockState>> states) {
        var iterator = serverVerifiedStates.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var pending = entry.getValue();
            IndexEntry<BlockState> original = states.get(BlockPos.of(entry.getLongKey()));
            if (original != null) {
                pending.sequence = original.index();
                pending.blockState = original.val();
            } else if (pending.sequence >= seq) {
                iterator.remove();
            }
        }
    }

    @Unique
    public void restorePredictionSequence(int sequence, boolean pendingSequence) {
        this.currentSequenceNr = sequence;
        this.isPredicting = pendingSequence;
    }
}
