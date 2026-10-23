package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.DecoderException;
import javax.annotation.Nullable;
import me.matl114.accessors.events.ClientConnectionAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.ClientboundPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.ServerboundPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.handshake.ClientIntent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(Connection.class)
public abstract class ClientConnectionEvents extends SimpleChannelInboundHandler<Packet<?>>
        implements ClientConnectionAccess {
    @Shadow
    protected abstract void channelRead0(ChannelHandlerContext channelHandlerContext, Packet<?> packet);

    @Shadow
    public Channel channel;

    @Shadow
    @Final
    private PacketFlow receiving;

    @Shadow
    private volatile @Nullable PacketListener packetListener;

    @Shadow
    private boolean handlingFault;

    @Shadow
    private int sentPackets;

    @Shadow
    private static <T extends PacketListener> void genericsFtw(Packet<T> packet, PacketListener listener) {}

    @Unique
    ProtocolInfo<?> currentInBoundState;

    @Unique
    ProtocolInfo<?> currentOutBoundState;

    public ProtocolInfo<?> getOutboundState() {
        return currentOutBoundState;
    }

    public ProtocolInfo<?> getInboundState() {
        return currentInBoundState;
    }

    public void sendByteBuf(ByteBuf buf) {
        ++this.sentPackets;
        if (this.channel.eventLoop().inEventLoop()) {
            this.channel.writeAndFlush(buf);
        } else {
            this.channel.eventLoop().execute(() -> {
                this.channel.writeAndFlush(buf);
            });
        }
    }

    @Inject(method = "validateListener", at = @At("HEAD"))
    private void onSetPacketListener(ProtocolInfo<?> state, PacketListener listener, CallbackInfo ci) {
        currentInBoundState = state;
    }

    @Inject(method = "setupOutboundProtocol", at = @At("HEAD"))
    private void onTransitionOutbound(ProtocolInfo<?> newState, CallbackInfo ci) {
        currentOutBoundState = newState;
    }

    @Inject(
            method =
                    "initiateServerboundConnection(Ljava/lang/String;ILnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ClientboundPacketListener;Lnet/minecraft/network/protocol/handshake/ClientIntent;)V",
            at = @At("HEAD"))
    private void onConnection(
            String address,
            int port,
            ProtocolInfo outboundState,
            ProtocolInfo inboundState,
            ClientboundPacketListener prePlayStateListener,
            ClientIntent intent,
            CallbackInfo ci) {
        currentInBoundState = inboundState;
        currentOutBoundState = outboundState;
    }

    @Inject(
            method = "exceptionCaught",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/network/Connection;packetListener:Lnet/minecraft/network/PacketListener;",
                            shift = At.Shift.BEFORE),
            cancellable = true)
    private void onChannelException(ChannelHandlerContext context, Throwable ex, CallbackInfo ci) {
        if (ex instanceof DecoderException decodeExp && decodeExp.getMessage().contains("Failed to decode packet")) {
            if (!Listener.handleException(
                    ex, Listener.ExceptionType.PACKET_DECODE_EXCEPTION, this.packetListener, this)) {
                // cancel exception
                handlingFault = false;
                ci.cancel();
            }
        } else {
            if (!Listener.handleException(
                    ex, Listener.ExceptionType.UNKNOWN_CHANNEL_EXCEPTION, this.packetListener, this)) {
                handlingFault = false;
                ci.cancel();
            }
        }
    }
    // some sb mod inject at this point, we fix it by order = -999
    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"),
            cancellable = true,
            order = -999)
    private void acceptPacket(
            ChannelHandlerContext channelHandlerContext,
            Packet<?> packet,
            CallbackInfo ci,
            @Local(argsOnly = true) LocalRef<Packet<?>> packetLocalRef) {
        if (packet == null) {
            ci.cancel();
            return;
        }
        // do not handle serverbound packet
        if (this.receiving == PacketFlow.SERVERBOUND) {
            return;
        }
        if (PacketManager.handleQueueInPacket(packet, (Connection) (Object) this)) {
            ci.cancel();
            return;
        }
        Packet<?> packetToRecv = Listener.acceptS2CPacket((Connection) (Object) this, packet);
        if (packetToRecv != packet) {
            if (packetToRecv == null) {
                ci.cancel();
            } else {
                packetLocalRef.set(packetToRecv);
            }
        }
    }

    @Inject(
            method =
                    "initiateServerboundConnection(Ljava/lang/String;ILnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ClientboundPacketListener;Lnet/minecraft/network/protocol/handshake/ClientIntent;)V",
            at = @At("RETURN"))
    private <S extends ServerboundPacketListener, C extends ClientboundPacketListener> void onConnect(
            String address,
            int port,
            ProtocolInfo<S> outboundState,
            ProtocolInfo<C> inboundState,
            C prePlayStateListener,
            ClientIntent intent,
            CallbackInfo ci) {
        Listener.getConnectionEstablish()
                .handleValue(new Event<>(
                        (Connection) (Object) this, false, false, inboundState.flow(), prePlayStateListener));
    }

    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void sendPacket(
            Packet<?> packet,
            ChannelFutureListener listener,
            boolean flush,
            CallbackInfo ci,
            @Local(argsOnly = true) LocalRef<Packet<?>> packetLocalRef) {
        // fix: null values from cancelled send Events
        if (packet == null) {
            ci.cancel();
            return;
        }
        // do not handle serverbound packet
        if (this.receiving == PacketFlow.SERVERBOUND) {
            return;
        }
        Packet<?> packetToSend = Listener.sendC2SPacket((Connection) (Object) this, packet);
        if (packetToSend != packet) {
            if (packetToSend == null) {
                ci.cancel();
            } else {
                packetLocalRef.set(packetToSend);
            }
        }
    }

    @Inject(method = "sendPacket", at = @At("RETURN"))
    private void sendImmediately(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        // do not handle serverbound packet
        if (this.receiving == PacketFlow.SERVERBOUND) {
            return;
        }
        Listener.getPacketPostScheduleSendPoint().broadcast(packet, this);
    }

    @WrapMethod(method = "doSendPacket")
    private void sendPacketPost(
            Packet<?> packet, ChannelFutureListener listener, boolean flush, Operation<Void> original) {
        // do not handle serverbound packet
        if (this.receiving != PacketFlow.SERVERBOUND) {
            if (PacketManager.handleQueueOutPacket(
                    packet,
                    (packet2, flush2) -> {
                        original.call(packet2, listener, flush2);
                    },
                    (Connection) (Object) this)) {
                return;
            }
        }
        original.call(packet, listener, flush);
    }

    @WrapOperation(
            method = "genericsFtw",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private static void applyPacketMainThread(Packet instance, PacketListener t, Operation<Void> original) {
        // do not handle serverbound packet
        if (t.flow() == PacketFlow.SERVERBOUND) {
            original.call(instance, t);
            return;
        }
        if (!Minecraft.getInstance().isSameThread() && !Listener.isAsyncImportantPacket(instance)) {
            original.call(instance, t);
            return;
        } else {
            Listener.callPacketHandleEvent(instance, t, original::call);
        }
    }

    @Inject(method = "configureSerialization", at = @At("HEAD"))
    private static void proxyChannelIp(
            ChannelPipeline pipeline,
            PacketFlow side,
            boolean local,
            BandwidthDebugMonitor packetSizeLogger,
            CallbackInfo ci) {
        Listener.getConnectionChannelInitialize().broadcast(pipeline, side, local);
    }

    @Override
    public void applyPacket(Packet<?> packet, PacketListener listener) {
        genericsFtw(packet, listener);
    }

    @Override
    public void handlePacket(Packet<?> packet) {
        try {
            channelRead0(null, packet);
        } catch (NullPointerException e) {
            return;
        } catch (Throwable e) {
            throw e;
        }
    }
}
