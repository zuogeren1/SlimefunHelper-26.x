package me.matl114.events;

import com.google.common.collect.Queues;
import it.unimi.dsi.fastutil.objects.ReferenceArraySet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Getter;
import me.matl114.events.annotations.Broadcast;
import me.matl114.events.annotations.Cancelable;
import me.matl114.events.annotations.ExtraArgs;
import me.matl114.events.channels.EventChannel;
import me.matl114.events.channels.ListenerPoint;
import me.matl114.events.packets.PacketStorage;
import me.matl114.events.packets.PacketStorageImpl;
import me.matl114.managers.ScheduleService;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.level.Level;

public class PacketManager {
    // this queue should be accessed only in event loop
    public static ConcurrentLinkedQueue<PacketStorage> packetQueueIn = Queues.newConcurrentLinkedQueue();
    // this queue can be accessed async
    public static final ConcurrentLinkedQueue<PacketStorage> packetQueueOut = Queues.newConcurrentLinkedQueue();

    public static final WeakHashMap<Packet<?>, List<Consumer<Event<Packet<?>>>>> postSendQueue = new WeakHashMap<>();

    public static final WeakHashMap<Packet<?>, List<Consumer<Event<Packet<?>>>>> postScheduleSendQueue =
            new WeakHashMap<>();

    public static void schedulePostSendPacket(Packet<?> post, Packet<?> packet) {
        if (packet == null) return;
        schedulePostCallback(post, (ev) -> {
            Connection conn = ev.getArgs(0);
            conn.send(packet);
        });
    }

    public static <T extends Packet<?>> void schedulePostCallback(T post, Runnable packet) {
        if (packet == null) return;
        schedulePostCallback(post, (ev) -> {
            packet.run();
        });
    }

    public static <T extends Packet<?>> void schedulePostCallback(T post, Consumer<Event<T>> packet) {
        if (packet == null) return;
        postSendQueue.computeIfAbsent(post, (kv) -> new ArrayList<>()).add((Consumer) packet);
    }

    public static <T extends Packet<?>> void schedulePostScheduleCallback(T post, Runnable packet) {
        if (packet == null) return;
        schedulePostScheduleCallback(post, (ev) -> {
            packet.run();
        });
    }

    public static <T extends Packet<?>> void schedulePostScheduleCallback(T post, Consumer<Event<T>> packet) {
        if (packet == null) return;
        postScheduleSendQueue.computeIfAbsent(post, (kv) -> new ArrayList<>()).add((Consumer) packet);
    }

    public static void onPostPacketSend(Event<Packet<?>> packet) {
        var lst = postSendQueue.remove(packet.context);
        if (lst != null && !lst.isEmpty()) {
            for (var pkt : lst) {
                pkt.accept(packet);
            }
        }
    }

    public static void onPostPacketScheduleSend(Event<Packet<?>> packet) {
        var lst = postScheduleSendQueue.remove(packet.context);
        if (lst != null && !lst.isEmpty()) {
            for (var pkt : lst) {
                pkt.accept(packet);
            }
        }
    }

    static {
        Listener.getPacketPostSendPoint().registerHandler(PacketManager::onPostPacketSend);
        Listener.getPacketPostScheduleSendPoint().registerHandler(PacketManager::onPostPacketScheduleSend);
    }
    // must visit in eventLoop
    public static boolean startFlushIn = false;
    public static boolean startFlushOut = false;
    private static final Minecraft mc = Minecraft.getInstance();

    public static boolean handleQueueInPacket(Packet<?> packet, Connection connection) {
        // do not handle flushing packets
        if (startFlushIn) {
            return false;
        }
        checkImmediatelyFlush();
        // todo: what about BundlePacket
        if (connection.getPacketListener() instanceof ClientGamePacketListener play) {
            if (packet instanceof ClientboundDisconnectPacket
                    || (packet instanceof ClientboundSetHealthPacket hl && hl.getHealth() <= 0.0)
                    || packet instanceof ClientboundRespawnPacket
                    || packet instanceof ClientboundStartConfigurationPacket) {
                // clear all
                clearAndShutdown();
            } else {
                Event<PacketStorage> queueEvent = new Event<>(
                        new PacketStorageImpl(packet, System.currentTimeMillis(), connection),
                        true,
                        false,
                        connection,
                        true);
                packetQueueInEvent.handleValue(queueEvent);
                if (queueEvent.isCancelled()) {
                    handleQueueIn(queueEvent.context());
                    return true;
                }
            }
        }
        return false;
    }

    public static void clearAndShutdown() {
        queueShutdownEvent.broadcast(null);
        flushOutBound();
    }

    static boolean immediatelyFlush;

    public static void scheduleImmediateFlush() {
        immediatelyFlush = true;
    }

    public static void scheduleInInBoundThread(Runnable runnable) {
        if (mc.getConnection() != null) {
            if (mc.getConnection().getConnection().channel.eventLoop().inEventLoop(Thread.currentThread())) {
                runnable.run();
            } else {
                mc.getConnection().getConnection().channel.eventLoop().execute(runnable);
            }
        } else {
            runnable.run();
        }
    }

    private static void checkImmediatelyFlush() {
        if (immediatelyFlush) {
            immediatelyFlush = false;
            flushInBoundInternal(false);
        }
    }

    public static boolean handleQueueOutPacket(Packet<?> packet, Connection connection) {
        if (startFlushOut) {
            return false;
        }
        if (connection.getPacketListener() instanceof ClientGamePacketListener play) {
            if (packet instanceof ServerboundConfigurationAcknowledgedPacket) {
                clearAndShutdown();
            } else {
                Event<PacketStorage> queueEvent = new Event<>(
                        new PacketStorageImpl(packet, System.currentTimeMillis(), connection), true, false, connection);
                packetQueueOutEvent.handleValue(queueEvent);
                if (queueEvent.isCancelled()) {
                    handleQueueOut(queueEvent.context());
                    return true;
                }
            }
        }
        return false;
    }

    private static void flushInBoundInternal(boolean escapePipeline) {
        if (mc.getConnection() != null) {
            Runnable task = () -> {
                try {
                    if (startFlushIn) {
                        return;
                    }
                    if (mc.getConnection() != null
                            && mc.getConnection().getConnection().isConnected()) {
                        // flush
                        // do not trigger recursive call
                        startFlushIn = true;
                        immediatelyFlush = false;
                        try {
                            var oldQueue = packetQueueIn;
                            packetQueueIn = new ConcurrentLinkedQueue<>();
                            for (var packet : oldQueue) {
                                // abort if need a immediate flush
                                if (immediatelyFlush) {
                                    packetQueueIn.add(packet);
                                    continue;
                                }
                                if (!escapePipeline) {
                                    Event<PacketStorage> queueEvent = new Event<>(
                                            packet,
                                            true,
                                            false,
                                            mc.getConnection().getConnection(),
                                            false);
                                    packetQueueInEvent.handleValue(queueEvent);
                                    if (queueEvent.isCancelled()) {
                                        packetQueueIn.add(packet);
                                        continue;
                                    }
                                }
                                packet.handle();
                            }
                        } finally {
                            startFlushIn = false;
                            // clear async
                        }
                    } else {
                        packetQueueIn.clear();
                    }
                } catch (Throwable e) {
                    packetQueueIn.clear();
                }
            };
            if (mc.getConnection().getConnection().channel.eventLoop().inEventLoop(Thread.currentThread())) {
                task.run();
            } else {
                mc.getConnection().getConnection().channel.eventLoop().execute(task);
            }
        } else {
            packetQueueIn.clear();
        }
    }

    public static void flushOutBound() {
        try {
            if (mc.getConnection() != null && mc.getConnection().getConnection().isConnected()) {
                // flush
                startFlushOut = true;
                try {
                    for (var packet : packetQueueOut) {
                        packet.send();
                    }
                } finally {
                    startFlushOut = false;
                }
            }
        } finally {
            packetQueueOut.clear();
        }
    }

    public static void flushOutBound(Function<PacketStorage, FlushAction> pdd) {
        if (mc.getConnection() != null && mc.getConnection().getConnection().isConnected()) {
            // flush
            startFlushOut = true;
            var iter = packetQueueOut.iterator();
            try {
                while (iter.hasNext()) {
                    var packet = iter.next();
                    switch (pdd.apply(packet)) {
                        case FLUSH -> {
                            packet.send();
                            iter.remove();
                        }
                        case DROP -> {
                            iter.remove();
                        }
                    }
                }
            } finally {
                startFlushOut = false;
            }
        } else {
            packetQueueOut.removeIf((v) -> pdd.apply(v) != FlushAction.QUEUE);
        }
    }

    public static boolean isAsyncOrNotTransactionC2SPacket(Packet<?> pkt) {
        if (pkt instanceof ServerboundKeepAlivePacket
                || pkt instanceof ServerboundChatCommandSignedPacket
                || pkt instanceof ServerboundChatPacket
                || pkt instanceof ServerboundChatCommandPacket
                || pkt instanceof ServerboundCommandSuggestionPacket) return true;
        return false;
    }

    private static final ReferenceSet<PacketType<?>> packetSet1 = new ReferenceArraySet<>();

    static {
        packetSet1.add(CommonPacketTypes.SERVERBOUND_KEEP_ALIVE);
        packetSet1.add(GamePacketTypes.SERVERBOUND_CHAT_COMMAND_SIGNED);
        packetSet1.add(GamePacketTypes.SERVERBOUND_CHAT_COMMAND);
        packetSet1.add(GamePacketTypes.SERVERBOUND_CHAT);
        packetSet1.add(GamePacketTypes.SERVERBOUND_COMMAND_SUGGESTION);
    }

    public static boolean isAsyncOrNotTransactionC2SPacket(PacketType<?> pkt) {
        return pkt != null && packetSet1.contains(pkt);
    }

    private static final ReferenceSet<PacketType<?>> packetSet2 = new ReferenceArraySet<>();

    static {
        packetSet2.add(CommonPacketTypes.CLIENTBOUND_KEEP_ALIVE);
        packetSet2.add(GamePacketTypes.CLIENTBOUND_PLAYER_CHAT);
        packetSet2.add(GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT);
        packetSet2.add(GamePacketTypes.CLIENTBOUND_CONTAINER_CLOSE);
    }

    public static boolean isInventoryPacket(Packet<?> pkt) {
        return pkt instanceof ServerboundContainerClickPacket || pkt instanceof ServerboundContainerClosePacket;
    }

    public static boolean isInventoryPacket(PacketType<?> pkt) {
        return pkt == GamePacketTypes.SERVERBOUND_CONTAINER_CLICK || pkt == GamePacketTypes.SERVERBOUND_CONTAINER_CLOSE;
    }

    public static boolean isAsyncOrNotTransactionS2CPacket(Packet<?> pkt) {
        if (pkt instanceof ClientboundKeepAlivePacket
                || pkt instanceof ClientboundPlayerChatPacket
                || pkt instanceof ClientboundSystemChatPacket
                || pkt instanceof ClientboundContainerClosePacket
                || pkt instanceof ClientboundLevelChunkWithLightPacket) return true;
        return false;
    }

    public static boolean isAsyncOrNotTransactionS2CPacket(PacketType<?> pkt) {
        return pkt != null && packetSet2.contains(pkt);
    }

    public static void handleQueueIn(PacketStorage packet) {
        packetQueueIn.add(packet);
    }

    public static void handleQueueOut(PacketStorage packet) {
        packetQueueOut.add(packet);
    }

    @Getter
    @Cancelable
    @ExtraArgs({Connection.class, boolean.class})
    public static EventChannel<PacketStorage> packetQueueInEvent = new EventChannel<>();

    @Getter
    @Cancelable
    @ExtraArgs({Connection.class})
    public static EventChannel<PacketStorage> packetQueueOutEvent = new EventChannel<>();

    @Getter
    @Broadcast
    public static EventChannel<Void> queueShutdownEvent = new EventChannel<>();

    public static void onDisconnect(Event<Void> disconnect) {
        clearAndShutdown();
    }

    public static void onWorldSwitch(Event<Level> event) {
        clearAndShutdown();
    }

    protected static <W> void registerListener(ListenerPoint<W> listener, Consumer<W> handler) {
        listener.registerHandler(handler);
    }

    private static void flushInEveryMs() {
        if (mc.player == null || mc.level == null) return;
        if (packetQueueIn.isEmpty()) return;
        flushInBoundInternal(false);
    }

    static {
        registerListener(Listener.getServerLeavePoint(), PacketManager::onDisconnect);
        registerListener(Listener.getWorldSwitchPoint(), PacketManager::onWorldSwitch);
        ScheduleService.launchAsyncRepeatTask(PacketManager::flushInEveryMs, 1, 1);
    }

    public static enum FlushAction {
        DROP,
        FLUSH,
        QUEUE;
    }
}
