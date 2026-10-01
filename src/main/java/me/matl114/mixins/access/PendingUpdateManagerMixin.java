package me.matl114.mixins.access;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Optional;
import me.matl114.accessors.access.PendingUpdateManagerAccess;
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

    @Unique
    public Optional<BlockState> getPendingBlockState(int sequenceNumber, BlockPos pos) {
        var pending = serverVerifiedStates.get(pos.asLong());
        return (pending == null || pending.sequence < sequenceNumber)
                ? Optional.empty()
                : Optional.of(pending.blockState);
    }
}
