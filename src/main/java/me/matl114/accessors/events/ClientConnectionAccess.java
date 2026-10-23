package me.matl114.accessors.events;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;

public interface ClientConnectionAccess {
    public void handlePacket(Packet<?> packet);

    public void applyPacket(Packet<?> packet, PacketListener listener);

    public ProtocolInfo<?> getOutboundState();

    public ProtocolInfo<?> getInboundState();

    public void sendByteBuf(ByteBuf buf);

    public static ClientConnectionAccess of(Connection connection) {
        return (ClientConnectionAccess) connection;
    }
}
