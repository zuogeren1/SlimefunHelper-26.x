package me.matl114.events.packets;

import javax.annotation.Nullable;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;

public interface PacketStorage {
    long timestampMS();

    @Nullable
    PacketType<?> packetType();

    PacketFlow side();

    public interface Recv extends PacketStorage {
        default PacketFlow side() {
            return PacketFlow.CLIENTBOUND;
        }

        void handle();
    }

    public interface Send extends PacketStorage {
        default PacketFlow side() {
            return PacketFlow.SERVERBOUND;
        }

        void send(boolean forceFlush);
    }
}
