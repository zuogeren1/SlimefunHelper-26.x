package me.matl114.events.packets;

import me.matl114.accessors.events.ClientConnectionAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;

public record PacketStorageRecvImpl(Packet<?> packet, long timestampMS, Connection connection)
        implements PacketStorage.Recv {
    @Override
    public PacketType<?> packetType() {
        return packet.type();
    }

    @Override
    public void handle() {
        try {
            ClientConnectionAccess.of(connection).handlePacket(packet);
        } catch (Throwable throwable) {
        }
    }
}
