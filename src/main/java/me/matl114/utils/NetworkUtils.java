package me.matl114.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.InteractionHand;

@ApiMethod
public class NetworkUtils {
    private static final Minecraft mc = Minecraft.getInstance();

    public static ByteBuf createBytebuf() {
        return Unpooled.buffer();
    }

    public static int generateNextSequence() {
        var re = mc.level.getBlockStatePredictionHandler().startPredicting();
        int seq = re.currentSequenceNr;
        re.close();
        return seq;
    }

    public static void restoreSequence(int sequenceRestore) {
        var re = mc.level.getBlockStatePredictionHandler();
        if (re.currentSequenceNr == sequenceRestore) {
            --re.currentSequenceNr;
        }
    }

    /**
     * 挥手包。26.3 删掉了带 hand 的 ServerboundSwingPacket，客户端只发无参的 punch，
     * 服务端固定按主手处理（handlePunch）；offhand 挥手在协议层无法表达。
     */
    public static void sendSwing(ClientPacketListener connection) {
        //#if MC >= 260300
        //$$ connection.send(net.minecraft.network.protocol.game.ServerboundPunchPacket.INSTANCE);
        //#else
        connection.send(new net.minecraft.network.protocol.game.ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        //#endif
    }
}
