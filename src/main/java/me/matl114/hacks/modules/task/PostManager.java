package me.matl114.hacks.modules.task;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.managers.Tasks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;

public class PostManager extends BaseModule {
    public static PostManager INSTANCE;

    public PostManager() {
        super("PostManager");
        INSTANCE = this;
    }

    private int peekPingRequest;
    private int lastPingTick;
    private final Deque<Consumer<ClientPacketListener>> postTickHandlers = new ArrayDeque<>(33);
    private final Deque<Consumer<ClientPacketListener>> queuePackets = new ArrayDeque<>(33);

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPacketPoint().getChannel(ClientboundPingPacket.class), this::peekPingPacketIn);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundPingPacket.class), this::postPongPacketOut);
        registerListener(Listener.getServerLeavePoint(), this::onDisconnectReset);
        registerListener(Listener.getPostGameTick(), this::onWatchPingLongTimeNoSent);
        registerListener(Listener.getPreTick(), this::onPreTick);
    }

    public void addNextTransactionAction(Consumer<ClientPacketListener> packet) {
        queuePackets.addLast(packet);
    }

    public void onPreTick() {
        runAllQueuePackets(mc.getConnection());
    }

    public void peekPingPacketIn() {
        peekPingRequest += 1;
        lastPingTick = Tasks.getTick();
        //        Debug.info("in", packetPing.getPacketId(), peekPingRequest);
    }

    private final Deque<Integer> transactionIds = new ArrayDeque<>(50);

    private boolean isTransactionId(int id) {
        if (id > 0 || id < -32767) return false;

        if (!transactionIds.isEmpty()) {
            int last = transactionIds.peekLast();
            int gap = ((-id) - (-last) + 32768) % 32768;

            if (gap == 0 || gap > 16) return false;
        }

        transactionIds.addLast(id);
        if (transactionIds.size() > 50) transactionIds.removeFirst();
        return true;
    }

    public void postPongPacketOut(Event<ClientboundPingPacket> event) {
        // we sent the Common Pong in Ping's handle

        // end transaction,
        // fresh queue
        peekPingRequest -= 1;
        //            Debug.info("out",pong.getPacketId(), peekPingRequest);

        // fix anything wrong wtf
        if (peekPingRequest < 0) peekPingRequest = 0;
        // anyway ,flush

        var id = event.context.getId();
        if (id == 0) {
            transactionIds.clear();
            transactionIds.addLast(id);
        } else if (id < 0 && id >= -64) {
            transactionIds.addLast(id);
        } else if (!isTransactionId(id)) { // we only use negative ids
            return;
        }
        runAllQueuePackets(mc.getConnection());
    }

    private void runAllPostTickPackets(ClientPacketListener handler) {
        runQueue(handler, postTickHandlers);
    }

    private void runQueue(ClientPacketListener handler, Deque<Consumer<ClientPacketListener>> postTickHandlers) {
        if (!postTickHandlers.isEmpty()) {

            if (handler != null) {
                var iter = postTickHandlers.iterator();
                while (iter.hasNext()) {
                    iter.next().accept(handler);
                    iter.remove();
                }
            } else {
                postTickHandlers.clear();
            }
        }
    }

    private void runAllQueuePackets(ClientPacketListener handler) {
        runQueue(handler, queuePackets);
    }

    private void onDisconnectReset(Event<Void> v) {
        peekPingRequest = 0;
        //        hasHandledPongPacket = false;
    }

    private void onWatchPingLongTimeNoSent() {
        if (peekPingRequest > 0 && lastPingTick + 20 < Tasks.getTick()) {
            peekPingRequest = 0;
            lastPingTick = Tasks.getTick();
            runAllQueuePackets(mc.getConnection());
        }
    }

    public void onDisconnect(Event<Void> v) {}
}
