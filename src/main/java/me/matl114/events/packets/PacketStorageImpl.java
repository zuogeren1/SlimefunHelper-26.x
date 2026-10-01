package me.matl114.events.packets;

import me.matl114.accessors.events.ClientConnectionAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;

public record PacketStorageImpl(Packet<?> packet, long timestampMS, Connection connection)
        implements PacketStorage {
    @Override
    public PacketType<?> packetType() {
        return packet.type();
    }

    @Override
    public PacketFlow side() {
        return packet.type().flow();
    }

    @Override
    public void send() {
        try {
            connection.send(packet);
        } catch (Throwable throwable) {
        }
    }

    @Override
    public void handle() {
        try {
            ClientConnectionAccess.of(connection).handlePacket(packet);
        } catch (Throwable throwable) {
        }
    }
}
