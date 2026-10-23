package me.matl114.hacks.modules.combat;

import com.google.common.util.concurrent.Runnables;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.events.packets.PacketStorage;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.Debug;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;

public class TransactionBlocker extends BaseModule {
    public final ModulePath lagUtils = makePath(Configs.COMBAT_CONFIG, "lag-utils");
    public final ModulePath transactionBlocker = lagUtils.add("transaction-blocker");

    public TransactionBlocker() {
        super("TransactionBlocker");
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(transactionBlocker.add("enable")).build();

    public final KeyBindRef hotkey = moduleEntry(
                    transactionBlocker.addHotkey(), new MultiKeyBind(), transactionBlocker.addEnable())
            .build();

    public final FlagRef enableC =
            flagBuilder(transactionBlocker.add("bw-test-1")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(PacketManager.getPacketQueueOutEvent(), this::onPacketQueue);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundPlayerPositionPacket.class), this::onPlayerRespawnLook);
        registerListener(Listener.getPacketPoint().getChannel(ClientboundSetPassengersPacket.class), this::onDismount);
    }

    @Override
    public void onEnableModule() {
        super.onEnableModule();
    }

    public void onDisableModule() {
        super.onDisableModule();
        flush();
    }

    public void flush() {
        PacketManager.flushOutBound(
                (packet) -> {
                    if (isTransactionRelated(packet.packetType())) {
                        return PacketManager.FlushAction.DROP;
                    } else {
                        return PacketManager.FlushAction.QUEUE;
                    }
                },
                Runnables.doNothing());
    }

    public boolean isTransactionRelated(Packet<?> packet) {
        return packet instanceof ServerboundPongPacket || packet instanceof ClientboundPingPacket;
    }

    public boolean isTransactionRelated(PacketType<?> packet) {
        return packet == CommonPacketTypes.CLIENTBOUND_PING || packet == CommonPacketTypes.SERVERBOUND_PONG;
    }

    public void onPacketQueue(Event<PacketStorage.Send> packetEvent) {
        if (enable.get() && isTransactionRelated(packetEvent.context.packetType())) {
            packetEvent.cancel();
            Listener.sendPacketNoEvents(new ServerboundPongPacket(0));
        }
    }

    int rideId;

    public void onDismount(Event<ClientboundSetPassengersPacket> event) {
        var pkt = event.context;
        for (var re : pkt.getPassengers()) {
            if (re == mc.player.getId()) {
                rideId = event.context.getVehicle();
                return;
            }
        }
        if (rideId == event.context.getVehicle()) {
            enable.set(true);
        }
    }

    public void onPlayerRespawn(Event<ClientboundRespawnPacket> event) {
        if (enableC.get()) {
            enable.set(true);
        }
    }

    public void onPlayerRespawnLook() {
        if (enableC.get() && mc.player != null && mc.player.getAbilities().flying) {
            Debug.chat("Start");
            enable.set(true);
        }
    }
}
