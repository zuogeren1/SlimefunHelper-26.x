package me.matl114.hacks.modules.interact;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.Getter;
import me.matl114.accessors.access.PlayerInteractBlockC2SPacketAccess;
import me.matl114.accessors.access.PlayerInteractItemC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.annotations.Broadcast;
import me.matl114.events.channels.EventChannelDispatcher;
import me.matl114.events.impl.BlockBreak;
import me.matl114.events.impl.SequencedAction;
import me.matl114.events.impl.UseItem;
import me.matl114.events.impl.UseItemOnBlock;
import me.matl114.hacks.api.BaseModule;
import me.matl114.managers.Tasks;
import me.matl114.utils.collections.IndexEntry;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public class SequencedActionManager extends BaseModule {
    public static SequencedActionManager INSTANCE;

    public SequencedActionManager() {
        super("SequencedActionManager");
        INSTANCE = this;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPlayerRespawnPoint(), this::onPlayerRespawn);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemOnPacket.class),
                this::onInteractBlock,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemPacket.class),
                this::onInteractItem,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class),
                this::onBlockBreak,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPreHandlePoint().getChannel(ClientboundBlockChangedAckPacket.class), this::onAck);
    }

    int maxSeq = 0;
    Deque<IndexEntry<UseItemOnBlock>> blockPlaceSequences = new ArrayDeque<>(8);
    Deque<IndexEntry<UseItem>> itemUsageSequences = new ArrayDeque<>(8);
    Deque<IndexEntry<BlockBreak>> blockBreakSequences = new ArrayDeque<>(8);
    public IndexEntry<UseItemOnBlock> lastBlockPlace;
    public int lastBlockPlaceTick;
    public IndexEntry<UseItem> lastItemUse;
    public int lastItemUseTick;

    @Broadcast
    @Getter
    public static EventChannelDispatcher<SequencedAction> sequencedActionResponse =
            new EventChannelDispatcher<>(SequencedAction::getClass);

    // Mirrors the pre-break block states that vanilla keeps in the (private) BlockStatePredictionHandler map:
    // upstream reads them back through its own PendingUpdateManagerAccess, which we do not have, and the access
    // widener does not expose that map, so we record the states we predict ourselves.
    private final Map<BlockPos, BlockState> beforeBreakPredictionStates = new HashMap<>();

    private void onPlayerRespawn(Event<LocalPlayer> playerEntityEvent) {
        maxSeq = 0;
        blockPlaceSequences.forEach(s -> sequencedActionResponse.broadcast(s.val()));
        itemUsageSequences.forEach(s -> sequencedActionResponse.broadcast(s.val()));
        blockBreakSequences.forEach(s -> sequencedActionResponse.broadcast(s.val()));
        blockPlaceSequences.clear();
        itemUsageSequences.clear();
        blockBreakSequences.clear();
        beforeBreakPredictionStates.clear();
        lastBlockPlace = null;
        lastItemUse = null;
    }

    private void onInteractBlock(Event<ServerboundUseItemOnPacket> event) {
        if (event.isCancelled()) return;
        // ignore fake sequence
        if (event.context.getSequence() > mc.level.getBlockStatePredictionHandler().currentSequenceNr + 20) {
            return;
        }
        maxSeq = Math.max(maxSeq, event.context.getSequence());
        var context = PlayerInteractBlockC2SPacketAccess.of(event.context).getUseContext();
        var stack = context != null && context.stack() != null
                ? context.stack()
                : mc.player.getItemInHand(event.context.getHand());
        blockPlaceSequences.addLast(
                lastBlockPlace = new IndexEntry<>(
                        event.context.getSequence(),
                        new UseItemOnBlock(
                                event.context.getHitResult(),
                                InteractionResult.SUCCESS,
                                context != null ? context.placePos() : Optional.empty(),
                                event.context.getHand(),
                                stack)));
        lastBlockPlaceTick = Tasks.getTick();
    }

    private void onInteractItem(Event<ServerboundUseItemPacket> event) {
        if (event.isCancelled()) return;
        // ignore fake sequence
        if (event.context.getSequence() > mc.level.getBlockStatePredictionHandler().currentSequenceNr + 20) {
            return;
        }
        maxSeq = Math.max(maxSeq, event.context.getSequence());
        ItemStack stack = PlayerInteractItemC2SPacketAccess.of(event.context).getItemStack();
        itemUsageSequences.add(
                lastItemUse = new IndexEntry<>(
                        event.context.getSequence(),
                        new UseItem(InteractionResult.SUCCESS, event.context.getHand(), stack)));
        lastItemUseTick = Tasks.getTick();
    }

    private void onBlockBreak(Event<ServerboundPlayerActionPacket> event) {
        if (event.isCancelled()) return;
        if (event.context.getSequence() > mc.level.getBlockStatePredictionHandler().currentSequenceNr + 20) {
            return;
        }
        // not sequenced packet
        if (event.context.getSequence() == 0) return;
        if (event.context.getAction() != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && event.context.getAction() != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
            return;
        }
        maxSeq = Math.max(maxSeq, event.context.getSequence());
        ItemStack stack = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        blockBreakSequences.add(new IndexEntry<>(
                event.context.getSequence(),
                new BlockBreak(
                        event.context.getPos(),
                        event.context.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                                ? BlockBreak.Stage.PRE
                                : BlockBreak.Stage.POST,
                        stack.copy(),
                        1.0F)));
    }

    private <W extends SequencedAction> void updateACK(Deque<IndexEntry<W>> ackList, int ack) {
        IndexEntry<W> val;
        while ((val = ackList.peek()) != null && val.index() <= ack) {
            var pollOut = ackList.pollFirst();
            if (pollOut != null) {
                onActionAcked(pollOut.val());
            }
        }
        if (!ackList.isEmpty()) {
            var iter = ackList.iterator();
            while (iter.hasNext()) {
                var s = iter.next();
                if (s.index() <= ack) {
                    iter.remove();
                    onActionAcked(s.val());
                }
            }
        }
    }

    private void onActionAcked(SequencedAction action) {
        // drop the mirrored pre-break state together with the action, like endPredictionsUpTo does for vanilla
        if (action instanceof BlockBreak blockBreak) {
            beforeBreakPredictionStates.remove(blockBreak.blockPos());
        }
        sequencedActionResponse.broadcast(action);
    }

    public boolean isWaitingBlockResponse(BlockPos bp) {
        return blockPlaceSequences.stream()
                .anyMatch(s -> Objects.equals(s.val().hitResult().getBlockPos(), bp));
    }

    public boolean isWaitingBlockResponse(BlockPos bp, Predicate<ItemStack> placeBlock) {
        return blockPlaceSequences.stream()
                .anyMatch(s -> Objects.equals(s.val().hitResult().getBlockPos(), bp)
                        && placeBlock.test(s.val().handItem()));
    }

    public boolean isWaitingBlockUseOnResponse(Predicate<UseItemOnBlock> bp) {
        return blockPlaceSequences.stream().anyMatch(s -> bp.test(s.val()));
    }

    public boolean isWaitingItemResponse(Predicate<ItemStack> stack) {
        return itemUsageSequences.stream().anyMatch(s -> stack.test(s.val().handItem()));
    }

    public boolean isWaitingItemUseResponse(Predicate<UseItem> bp) {
        return itemUsageSequences.stream().anyMatch(s -> bp.test(s.val()));
    }

    public boolean isWaitingBreakResponse(Predicate<BlockPos> bp) {
        return blockBreakSequences.stream().anyMatch(s -> bp.test(s.val().blockPos()));
    }

    public boolean isWaitingBlockBreakResponse(Predicate<BlockBreak> bp) {
        return blockBreakSequences.stream().anyMatch(s -> bp.test(s.val()));
    }

    // ---- legacy names, kept as thin delegates so existing call sites keep exactly the same semantics ----

    public boolean isWaitingResponse(BlockPos bp) {
        return isWaitingBlockResponse(bp);
    }

    public boolean isWaitingResponse(BlockPos bp, Predicate<ItemStack> placeBlock) {
        return isWaitingBlockResponse(bp, placeBlock);
    }

    public boolean isWaitingResponse(Predicate<ItemStack> stack) {
        return isWaitingItemResponse(stack);
    }

    public Stream<UseItemOnBlock> getCurrentPendingBlockPlace() {
        return blockPlaceSequences.stream().map(IndexEntry::val);
    }

    public void onAck(Event<ClientboundBlockChangedAckPacket> event) {
        int response = event.context.sequence();
        updateACK(itemUsageSequences, response);
        updateACK(blockPlaceSequences, response);
        updateACK(blockBreakSequences, response);
    }

    public boolean appendBlockBreakPrediction(BlockPos pos, BlockState state) {
        var predictionHandler = mc.level.getBlockStatePredictionHandler();
        if (predictionHandler.isPredicting()) {
            // ClientLevel.setBlock already retains the known server state while predicting
            BlockState oldState = mc.level.getBlockState(pos);
            boolean success = mc.level.setBlock(pos, state, 11, 512);
            if (success) {
                beforeBreakPredictionStates.put(pos, oldState);
            }
            return success;
        } else {
            BlockState oldState = mc.level.getBlockState(pos);
            boolean success = mc.level.setBlock(pos, state, 11, 512);
            if (success) {
                predictionHandler.retainKnownServerState(pos, oldState, mc.player);
                beforeBreakPredictionStates.put(pos, oldState);
            }
            return success;
        }
    }

    public Optional<BlockState> getBeforeBreakPredictionState(BlockPos pos) {
        var latestAction = blockBreakSequences.stream()
                .filter(s -> Objects.equals(s.val().blockPos(), pos))
                .max(Comparator.comparingInt(IndexEntry::index))
                .orElse(null);
        if (latestAction == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(beforeBreakPredictionStates.get(pos));
    }
}
