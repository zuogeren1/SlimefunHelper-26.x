package me.matl114.hacks.modules.interact;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;
import me.matl114.accessors.access.PlayerInteractBlockC2SPacketAccess;
import me.matl114.accessors.access.PlayerInteractItemC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.UseItem;
import me.matl114.events.impl.UseItemOnBlock;
import me.matl114.hacks.api.BaseModule;
import me.matl114.managers.Tasks;
import me.matl114.utils.collections.IndexEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class SequencedActionManager extends BaseModule {
    public static SequencedActionManager INSTANCE;

    public SequencedActionManager() {
        super("SequencedActionManager");
        INSTANCE = this;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getWorldSwitchPoint(), this::onWorldSwitch);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemOnPacket.class),
                this::onInteractBlock,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemPacket.class),
                this::onInteractItem,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPreHandlePoint().getChannel(ClientboundBlockChangedAckPacket.class), this::onAck);
    }

    int maxSeq = 0;
    Deque<IndexEntry<UseItemOnBlock>> blockPlaceSequences = new ArrayDeque<>(8);
    Deque<IndexEntry<UseItem>> itemUsageSequences = new ArrayDeque<>(8);
    public IndexEntry<UseItemOnBlock> lastBlockPlace;
    public int lastBlockPlaceTick;
    public IndexEntry<UseItem> lastItemUse;
    public int lastItemUseTick;

    private void onWorldSwitch(Event<Level> event) {
        maxSeq = 0;
        blockPlaceSequences.clear();
        itemUsageSequences.clear();
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
                                Optional.empty(),
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

    private <W> void updateACK(Deque<IndexEntry<W>> ackList, int ack) {
        IndexEntry<W> val;
        while ((val = ackList.peek()) != null && val.index() <= ack) {
            ackList.pollFirst();
        }
        if (!ackList.isEmpty()) {
            ackList.removeIf(s -> s.index() <= ack);
        }
    }

    public boolean isWaitingResponse(BlockPos bp) {
        return blockPlaceSequences.stream()
                .anyMatch(s -> Objects.equals(s.val().hitResult().getBlockPos(), bp));
    }

    public boolean isWaitingResponse(BlockPos bp, Predicate<ItemStack> placeBlock) {
        return blockPlaceSequences.stream()
                .anyMatch(s -> Objects.equals(s.val().hitResult().getBlockPos(), bp)
                        && placeBlock.test(s.val().handItem()));
    }

    public boolean isWaitingResponse(Predicate<ItemStack> stack) {
        return itemUsageSequences.stream().anyMatch(s -> stack.test(s.val().handItem()));
    }

    public Stream<UseItemOnBlock> getCurrentPendingBlockPlace() {
        return blockPlaceSequences.stream().map(IndexEntry::val);
    }

    public void onAck(Event<ClientboundBlockChangedAckPacket> event) {
        int response = event.context.sequence();
        updateACK(itemUsageSequences, response);
        updateACK(blockPlaceSequences, response);
    }
}
