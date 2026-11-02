package me.matl114.events;

import com.google.common.collect.Queues;
import com.google.common.util.concurrent.Runnables;
import it.unimi.dsi.fastutil.objects.ReferenceArraySet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nonnull;
import lombok.Getter;
import me.matl114.events.annotations.Broadcast;
import me.matl114.events.annotations.Cancelable;
import me.matl114.events.annotations.ExtraArgs;
import me.matl114.events.channels.EventChannel;
import me.matl114.events.channels.ListenerPoint;
import me.matl114.events.packets.PacketStorage;
import me.matl114.events.packets.PacketStorageRecvImpl;
import me.matl114.events.packets.PacketStorageSendImpl;
import me.matl114.managers.ScheduleService;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
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
    public static ConcurrentLinkedQueue<PacketStorage.Recv> packetQueueIn = Queues.newConcurrentLinkedQueue();
    // this queue can be accessed async
    public static final ConcurrentLinkedQueue<PacketStorage.Send> packetQueueOut = Queues.newConcurrentLinkedQueue();

    public static final WeakHashMap<Packet<?>, List<Consumer<Event<Packet<?>>>>> postScheduleSendQueue =
            new WeakHashMap<>();

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

    public static void onPostPacketScheduleSend(Event<Packet<?>> packet) {
        var lst = postScheduleSendQueue.remove(packet.context);
        if (lst != null && !lst.isEmpty()) {
            for (var pkt : lst) {
                pkt.accept(packet);
            }
        }
    }

    static {
        Listener.getPacketPostScheduleSendPoint().registerHandler(PacketManager::onPostPacketScheduleSend);
    }
    // must visit in eventLoop
    public static boolean startFlushIn = false;
    public static boolean startFlushOut = false;
    private static final Minecraft mc = Minecraft.getInstance();
    private static Set<PacketType<?>> disconnectTypes =
            Set.of(CommonPacketTypes.CLIENTBOUND_DISCONNECT, GamePacketTypes.CLIENTBOUND_START_CONFIGURATION, GamePacketTypes.CLIENTBOUND_RESPAWN);

    public static boolean isDisconnectOrRespawn(PacketType<?> recv) {
        return disconnectTypes.contains(recv);
    }

    public static boolean handleQueueInPacket(Packet<?> packet, Connection connection) {
        // do not handle flushing packets
        if (startFlushIn) {
            return false;
        }
        checkImmediatelyFlush();
        // todo: what about BundlePacket
        if (connection.getPacketListener() instanceof ClientGamePacketListener play) {
            Event<PacketStorage.Recv> queueEvent = new Event<>(
                    new PacketStorageRecvImpl(packet, System.currentTimeMillis(), connection),
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
        return false;
    }

    public static void clearAndShutdown() {
        schedule(() -> {
            queueShutdownEvent.broadcast(null);
            flushOutBound(Runnables.doNothing());
        });
    }

    static boolean immediatelyFlush;

    public static void scheduleImmediateFlush() {
        immediatelyFlush = true;
    }

    public static void schedule(Runnable runnable) {
        if (mc.getConnection() == null) {
            runnable.run();
            return;
        }
        scheduleInEventLoop(mc.getConnection().getConnection(), runnable);
    }

    public static void scheduleInEventLoop(@Nonnull Connection connection, Runnable runnable) {
        if (connection.isConnected()) {
            if (connection.channel.eventLoop().inEventLoop(Thread.currentThread())) {
                runnable.run();
            } else {
                connection.channel.eventLoop().execute(runnable);
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

    public static boolean handleQueueOutPacket(
            Packet<?> packet, BiConsumer<Packet<?>, Boolean> callback, Connection connection) {
        if (startFlushOut) {
            return false;
        }
        // just come to PLAY stage, do not queue
        if (packet instanceof ServerboundFinishConfigurationPacket) {
            //
            return false;
        }
        if (connection.getPacketListener() instanceof ClientGamePacketListener play) {
            Event<PacketStorage.Send> queueEvent = new Event<>(
                    new PacketStorageSendImpl(packet, System.currentTimeMillis(), callback), true, false, connection);
            packetQueueOutEvent.handleValue(queueEvent);
            if (queueEvent.isCancelled()) {
                handleQueueOut(queueEvent.context());
                return true;
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
                                    Event<PacketStorage.Recv> queueEvent = new Event<>(
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

    public static void flushOutBound(Runnable postFlushTask) {

        if (mc.getConnection() != null
                && mc.getConnection().getConnection().isConnected()) {
            // flush
            Connection conn = mc.getConnection().getConnection();
            scheduleInEventLoop(conn, () -> {
                startFlushOut = true;
                try {
                    List<PacketStorage.Send> storages = new ArrayList<>(packetQueueOut);
                    if (!storages.isEmpty()) {
                        for (var re : storages) {
                            re.send(false);
                        }
                        mc.getConnection().getConnection().flushChannel();
                    }
                } finally {
                    startFlushOut = false;
                    packetQueueOut.clear();
                    postFlushTask.run();
                }
            });
        } else {
            packetQueueOut.clear();
            postFlushTask.run();
        }
    }

    public static void flushOutBound(Function<PacketStorage, FlushAction> pdd, Runnable postFlushTask) {
        if (mc.getConnection() != null
                && mc.getConnection().getConnection().isConnected()) {
            // flush
            Connection conn = mc.getConnection().getConnection();
            scheduleInEventLoop(conn, () -> {
                startFlushOut = true;
                var iter = packetQueueOut.iterator();
                try {
                    List<PacketStorage.Send> storages = new ArrayList<>();
                    while (iter.hasNext()) {
                        var packet = iter.next();
                        switch (pdd.apply(packet)) {
                            case FLUSH -> {
                                storages.add(packet);
                                iter.remove();
                            }
                            case DROP -> {
                                iter.remove();
                            }
                        }
                    }
                    if (!storages.isEmpty()) {
                        for (var re : storages) {
                            re.send(false);
                        }
                        mc.getConnection().getConnection().flushChannel();
                    }
                } finally {
                    startFlushOut = false;
                    postFlushTask.run();
                }
            });
        } else {
            packetQueueOut.removeIf((v) -> pdd.apply(v) != FlushAction.QUEUE);
            postFlushTask.run();
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

    public static void handleQueueIn(PacketStorage.Recv packet) {
        packetQueueIn.add(packet);
    }

    public static void handleQueueOut(PacketStorage.Send packet) {
        packetQueueOut.add(packet);
    }

    @Getter
    @Cancelable
    @ExtraArgs({Connection.class, boolean.class})
    public static EventChannel<PacketStorage.Recv> packetQueueInEvent = new EventChannel<>();

    @Getter
    @Cancelable
    @ExtraArgs({Connection.class})
    public static EventChannel<PacketStorage.Send> packetQueueOutEvent = new EventChannel<>();

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
