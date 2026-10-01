package me.matl114.events;

import com.google.common.collect.ImmutableSet;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.*;
import lombok.Getter;
import me.matl114.accessors.events.ClientConnectionAccess;
import me.matl114.events.annotations.*;
import me.matl114.events.catchers.AbstractTypedPacketCatcher;
import me.matl114.events.catchers.PacketCatcher;
import me.matl114.events.channels.EventChannel;
import me.matl114.events.impl.MetadataUpdate;
import me.matl114.events.channels.EventChannelDispatcher;
import me.matl114.events.channels.PacketEventChannel;
import me.matl114.events.impl.*;
import me.matl114.managers.Tasks;
import me.matl114.managers.input.IHotKey;
import me.matl114.managers.input.IInputManager;
import me.matl114.utils.collections.FPoint;
import me.matl114.utils.collections.Point;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.locale.Language;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.configuration.ClientboundResetChatPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ConfigurationPacketTypes;
import net.minecraft.network.protocol.cookie.ClientCookiePacketListener;
import net.minecraft.network.protocol.cookie.CookiePacketTypes;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.handshake.HandshakePacketTypes;
import net.minecraft.network.protocol.login.LoginPacketTypes;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.PingPacketTypes;
import net.minecraft.network.protocol.status.StatusPacketTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.tags.TagKey;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Unique;

public class Listener {
    public static void init() {}

    @Getter
    private static final Map<PacketType<?>, Class<? extends Packet<?>>> registeredPacketTypes = new LinkedHashMap<>();

    private static final Map<Identifier, PacketType<?>> c2sPacketTypes = new HashMap<>();
    private static final Map<Identifier, PacketType<?>> s2cPacketTypes = new HashMap<>();

    private static void registerPacketTypesInternal(Class<?> clazz) {
        for (var field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && PacketType.class.isAssignableFrom(field.getType())) {
                try {
                    PacketType<?> typeInstance = (PacketType<?>) field.get(null);
                    if (field.getGenericType() instanceof ParameterizedType parameterizedType) {
                        Class<? extends Packet<?>> packetClass =
                                (Class<? extends Packet<?>>) parameterizedType.getActualTypeArguments()[0];
                        registeredPacketTypes.put((PacketType<?>) typeInstance, packetClass);
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    static {
        // register PlayPackets
        registerPacketTypesInternal(CommonPacketTypes.class);
        registerPacketTypesInternal(GamePacketTypes.class);
        registerPacketTypesInternal(LoginPacketTypes.class);
        registerPacketTypesInternal(PingPacketTypes.class);
        registerPacketTypesInternal(StatusPacketTypes.class);
        registerPacketTypesInternal(HandshakePacketTypes.class);
        registerPacketTypesInternal(ConfigurationPacketTypes.class);
        registerPacketTypesInternal(CookiePacketTypes.class);
        for (var packetType : registeredPacketTypes.keySet()) {
            if (packetType.flow() == PacketFlow.SERVERBOUND) {
                c2sPacketTypes.put(packetType.id(), packetType);
            } else {
                s2cPacketTypes.put(packetType.id(), packetType);
            }
        }
    }

    public static Class<? extends Packet<?>> getPacketClassById(Identifier id, boolean s2c) {
        return registeredPacketTypes.entrySet().stream()
                .filter(type -> Objects.equals(type.getKey().id(), id)
                        && type.getKey().flow() == (s2c ? PacketFlow.CLIENTBOUND : PacketFlow.SERVERBOUND))
                .findAny()
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    public static PacketType<?> getPacketTypeById(Identifier id, boolean s2c) {
        return (s2c ? s2cPacketTypes : c2sPacketTypes).get(id);
    }

    public static <T extends Packet<?>> EventChannel<T> getPacketListenerPoint(Class<T> clazz) {
        return packetPoint.getChannel(clazz);
    }
    //    private static final Map<Class<?>, CatcherPoint<Event<Packet<?>>>> packetCatcher = new ConcurrentHashMap<>();
    private static final Map<Class<? extends Packet<?>>, Class<? extends Packet<?>>> mappedPacketClass =
            new ConcurrentHashMap<>();

    public static <T extends Packet<?>, W extends Packet<?>> Class<W> getMappedPacketClass(Class<T> packet) {
        return (Class<W>)
                mappedPacketClass.computeIfAbsent((Class<? extends Packet<?>>) packet, Listener::getPacketClass);
    }

    private static <T extends Packet<?>, W extends Packet<?>> Class<W> getPacketClass(Class<T> packet) {
        Class<?> clazz1 = packet;
        while (Packet.class.isAssignableFrom(clazz1.getSuperclass())) {
            clazz1 = clazz1.getSuperclass();
        }
        return (Class<W>) clazz1;
    }

    protected static <T extends Packet<?>> Consumer<Event<T>> wrapListener(Predicate<T> w) {
        return (packetEvent -> {
            if (packetEvent.isCancelled()) {
                return;
            }
            boolean how = w.test(packetEvent.context());
            if (!how) {
                packetEvent.cancel();
            }
        });
    }

    protected static <T extends Packet<?>> Consumer<Event<T>> wrapListener(BiPredicate<Connection, T> w) {
        return (packetEvent -> {
            if (packetEvent.isCancelled()) {
                return;
            }
            boolean how = w.test(packetEvent.getArgs(0), packetEvent.context());
            if (!how) {
                packetEvent.cancel();
            }
        });
    }

    protected static <T> Consumer<Event<T>> wrapListener(Consumer<T> w) {
        return (packetEvent -> {
            if (packetEvent.isCancelled()) {
                return;
            }
            w.accept(packetEvent.context());
        });
    }

    public static void registerPacketListener(Consumer<Packet<?>> packetListener, boolean isS2C) {
        if (isS2C) {
            getPacketAcceptPoint().registerHandler(wrapListener(packetListener));
        } else {
            getPacketSendPoint().registerHandler(wrapListener(packetListener));
        }
    }

    public static void registerPacketListener(Predicate<Packet<?>> packetListener, boolean isS2C) {
        if (isS2C) {
            getPacketAcceptPoint().registerHandler(wrapListener(packetListener));
        } else {
            getPacketSendPoint().registerHandler(wrapListener(packetListener));
        }
    }

    public static void registerPacketListener(BiPredicate<Connection, Packet<?>> packetListener, boolean isS2C) {
        if (isS2C) {
            getPacketAcceptPoint().registerHandler(wrapListener(packetListener));
        } else {
            getPacketSendPoint().registerHandler(wrapListener(packetListener));
        }
    }

    public static <T extends Packet<?>> void registerSinglePacketListener(Class<T> clazz, Consumer<T> predicate) {
        getPacketListenerPoint(clazz).registerHandler(wrapListener(predicate));
    }

    public static <T extends Packet<?>> void registerSinglePacketListener(Class<T> clazz, Predicate<T> predicate) {
        getPacketListenerPoint(clazz).registerHandler(wrapListener(predicate));
    }

    public static <T extends Packet<?>> void registerSinglePacketListener(
            Class<T> clazz, BiPredicate<Connection, T> predicate) {
        getPacketListenerPoint(clazz).registerHandler(wrapListener(predicate));
    }

    @Getter
    public static Connection clientConnection;

    public static ClientConnectionAccess getConnectionAccess() {
        return ClientConnectionAccess.of(clientConnection);
    }

    public static Packet<?> acceptS2CPacket(Connection connection, Packet<?> packet) {

        return unpackMultiPacket(connection, packet, true);
    }

    public static Packet<?> sendC2SPacket(Connection connection, Packet<?> packet) {
        return unpackMultiPacket(connection, packet, false);
    }

    @Unique
    private static Packet<?> onSinglePacketListen(Connection connection, Packet<?> packet, boolean s2c) {
        Event<Packet<?>> packetEvent = new Event<>(packet, true, true, connection);
        // already handled in PacketEventChannel
        //        if (s2c) {
        //            getPacketAcceptPoint().handleValue(packetEvent);
        //        } else {
        //            getPacketSendPoint().handleValue(packetEvent);
        //        }
        getPacketPoint().handleValue(packetEvent);
        if (packetEvent.isCancelled()) {
            return null;
        } else {
            return packetEvent.context();
        }
    }

    @Unique
    private static Packet<?> unpackMultiPacket(Connection connection, Packet<?> packet, boolean isS2C) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            var iter = bundle.subPackets();
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            boolean recreate = false;
            for (var pkt : iter) {
                Packet<? super ClientGamePacketListener> p =
                        (Packet<? super ClientGamePacketListener>) unpackMultiPacket(connection, pkt, isS2C);
                if (p != null) {
                    packets.add(p);
                    if (p != pkt) {
                        recreate = true;
                    }
                } else {
                    recreate = true;
                }
            }
            if (recreate) {
                return packets.isEmpty() ? null : new ClientboundBundlePacket(packets);
            } else {
                return packet;
            }
        } else {
            return onSinglePacketListen(connection, packet, isS2C);
        }
    }

    private static final Function<Class<? extends Screen>, Class<? extends Screen>> screenClassIdentifierMapper =
            Util.memoize((clz -> {
                Class<?> clzz = clz;
                while (clzz != Screen.class && clzz.getSuperclass() != Screen.class) {
                    clzz = clzz.getSuperclass();
                }
                return (Class<? extends Screen>) clzz;
            }));

    // basic events
    // configurations
    @Getter
    @Broadcast
    private static final EventChannel<LocalPlayer> gameJoinPoint = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<Level> worldSwitchPoint = new EventChannel<>();
    // disconnect or enter reconfiguration
    @Getter
    @Broadcast
    @ExtraArgs(
            value = {boolean.class},
            names = "isRealDisconnect") // whether disconnect or not
    private static final EventChannel<Void> serverLeavePoint = new EventChannel<>();
    // disconnect from server
    @Getter
    @Broadcast
    @ExtraArgs(
            value = {boolean.class},
            names = "isTransferring") // whether transferring
    private static final EventChannel<Void> serverDisconnectPoint = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    @ExtraArgs({ServerData.class})
    private static final EventChannel<ServerAddress> serverPreConnectPoint = new EventChannel<>();

    @Getter
    @Modifiable
    @ExtraArgs({ResourceKey.class})
    @Dispatch(by = "RegistryKey")
    private static final EventChannelDispatcher<Map<TagKey<?>, List<Holder<?>>>> registryTagKeyReload =
            new EventChannelDispatcher<>((mapEvent -> mapEvent.getArgs(0)), true);

    // play

    @Getter // cancelable
    @Broadcast
    private static final EventChannel<Void> preTick = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<Void> postTick = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<LocalPlayer> preGameTick = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<LocalPlayer> postGameTick = new EventChannel<>();

    @Getter // arguments RenderTickCounter, tick , cancelable
    @Cancelable
    @ExtraArgs({DeltaTracker.class, boolean.class})
    private static final EventChannel<GameRenderer> gameRender = new EventChannel<>();

    @Getter
    @ApiStatus.Experimental
    @Broadcast
    private static final EventChannel<Language> languageReload = new EventChannel<>();

    @Getter
    @Cancelable(optional = true)
    @ExtraArgs(CrashReport.class)
    private static final EventChannel<Minecraft> clientMainExit = new EventChannel<>();

    // chat events
    @Getter // cancelable, modifiable
    @Cancelable
    @Modifiable
    private static final EventChannel<String> chatSend = new EventChannel<>();

    @Getter // cancelable, modifiable
    @Cancelable
    @Modifiable
    @ExtraArgs({MessageSignature.class, GuiMessageTag.class})
    private static final EventChannel<Component> messageAddToHud = new EventChannel<>();

    @Getter // cancelable, modifiable
    @Cancelable
    @Modifiable
    private static final EventChannel<GuiMessage> messageAddToVisible = new EventChannel<>();

    @Getter // cancelable, modifiable
    @Cancelable
    @Modifiable
    private static final EventChannel<String> chatScreenSendMessage = new EventChannel<>();

    @Getter
    @Cancelable
    private static final EventChannel<ChatRecv> chatMessageReceive = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    private static final EventChannel<Component> actionBarMessageReceive = new EventChannel<>();

    // screen events
    @Getter
    @Broadcast
    private static final EventChannelDispatcher<Screen> postCloseScreen = new EventChannelDispatcher<>(
            screen -> screen == null ? Screen.class : screenClassIdentifierMapper.apply(screen.getClass()));

    @Getter
    @Cancelable
    @Modifiable
    private static final EventChannelDispatcher<Screen> preSetScreen = new EventChannelDispatcher<>(
            screen -> screen == null ? Screen.class : screenClassIdentifierMapper.apply(screen.getClass()));

    @Getter
    @Cancelable // note: this cancels post operations of setting a screen , like cursor lock, render refresh and title
    // update
    private static final EventChannelDispatcher<Screen> midSetScreen = new EventChannelDispatcher<>(
            screen -> screen == null ? Screen.class : screenClassIdentifierMapper.apply(screen.getClass()));

    @Getter
    @Broadcast
    // update
    private static final EventChannelDispatcher<Screen> postSetScreen = new EventChannelDispatcher<>(
            screen -> screen == null ? Screen.class : screenClassIdentifierMapper.apply(screen.getClass()));

    @Getter
    @Broadcast
    private static final EventChannel<AbstractContainerScreen<?>> postOpenHandledScreen = new EventChannel<>();

    @Getter
    @Broadcast //  note: this is called when a screen open for the first time, or change its size. most screen clear
    // their children after change, but not all of them
    private static final EventChannelDispatcher<Screen> postInitializeScreen = new EventChannelDispatcher<>(
            screen -> screen == null ? Screen.class : screenClassIdentifierMapper.apply(screen.getClass()));

    @Getter // stores argument of the RecipeBook
    @Broadcast
    private static final EventChannel<RecipeBookToggle> postToggleRecipeBook = new EventChannel<>();

    @Getter
    @Cancelable
    private static final EventChannelDispatcher<SlotClickAction> preClickSlot =
            new EventChannelDispatcher<>(Function.identity());

    @Getter
    @Broadcast
    private static final EventChannelDispatcher<SlotClickAction> postClickSlot =
            new EventChannelDispatcher<>(Function.identity());

    @Getter
    @Broadcast
    private static final EventChannel<RecipeDisplayId> clickCraftingRecipe = new EventChannel<>();

    // packet events
    @Cancelable
    @ExtraArgs({Connection.class})
    public static EventChannel<Packet<?>> getPacketAcceptPoint() {
        return packetPoint.getPacketReceiveChannel();
    }

    @Cancelable
    @ExtraArgs({Connection.class})
    public static EventChannel<Packet<?>> getPacketSendPoint() {
        return packetPoint.getPacketSendChannel();
    }

    @Getter
    @ExtraArgs({Connection.class})
    public static final PacketEventChannel packetPostScheduleSendPoint = new PacketEventChannel();

    @Getter
    @ExtraArgs({Connection.class})
    private static final PacketEventChannel packetPostSendPoint = new PacketEventChannel();

    @Getter // packet accept or send
    @Cancelable
    @Modifiable
    @ExtraArgs({Connection.class})
    @Dispatch(by = "type and side")
    private static final PacketEventChannel packetPoint = new PacketEventChannel();

    @Getter // packet being handled on MainThread
    @Cancelable
    @ExtraArgs({PacketListener.class})
    @Dispatch(by = "type")
    private static final PacketEventChannel packetPreHandlePoint = new PacketEventChannel();

    @Getter
    @Broadcast
    @ExtraArgs({PacketListener.class})
    @Dispatch(by = "type")
    private static final PacketEventChannel packetPostHandlePoint = new PacketEventChannel();

    //    @Getter // network exception
    //    @Cancelable
    //    @ExtraArgs({PacketListener.class, Exception.class})
    //    private static final EventChannel<Packet<?>> packetListenerException = new EventChannel<>();

    // client player behaviours
    @Getter
    @Cancelable
    private static final EventChannel<LocalPlayer> clientPlayerSendMovementPoint = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<LocalPlayer> clientPlayerPostSendMovementPoint = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    @ApiStatus.Experimental
    @Dispatch(by = "Entity.getType")
    private static final EventChannelDispatcher<MetadataUpdate> entityTrackDataUpdate =
            new EventChannelDispatcher<>(e -> e.context.entity().getType(), true);

    @Getter
    @Broadcast
    private static final EventChannel<LocalPlayer> playerRespawnPoint = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    // jump not because of toggle creative flight
    private static final EventChannel<Integer> playerNotFlyJumpPoint = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<LocalPlayer> playerLandingPoint = new EventChannel<>();

    @Getter
    @Broadcast
    @ExtraArgs({BlockPos.class})
    private static final EventChannel<Vec3> playerWebSlowPoint = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    @ExtraArgs(TagKey.class)
    private static final EventChannel<Vec3> playerFluidVelocityPoint = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<Teleportation> teleportationConfirm = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    private static final EventChannel<Integer> useItemCooldownReset = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    private static final EventChannel<Vec3> playerVelocityTick = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<ClientInput> playerKeyboardInputTick = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<LocalPlayer> playerInitConfiguration = new EventChannel<>();

    @Getter
    @Modifiable
    @Cancelable
    private static final EventChannel<Integer> playerFallFlyingTick = new EventChannel<>();

    @Getter
    @Modifiable
    @Cancelable // records whether a Elytra flying should be started, every condition is considered, you can use this
    // event to also stop fallFlying
    @ExtraArgs(
            value = {Boolean.class},
            names = {"currentFallFlying"})
    private static final EventChannel<Boolean> playerSwitchFallFlying = new EventChannel<>();

    @Getter
    @Cancelable
    private static final EventChannel<Vec3> playerTravelingTick = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    private static final EventChannel<FPoint> playerChangeLook = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    private static final EventChannel<Integer> playerScrollHotBar = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    private static final EventChannel<Vec3> playerExplosionVelocity = new EventChannel<>();

    // entities
    @Getter
    @Broadcast
    private static final EventChannel<PlayerInfo> otherPlayerJoinPoint = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<PlayerInfo> otherPlayerExitPoint = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<PlayerInfo> otherPlayerEntryUpdate = new EventChannel<>();

    @Getter // vc update
    @Cancelable
    @Modifiable
    @ExtraArgs({Entity.class})
    private static final EventChannelDispatcher<Vec3> entityClientVelocityUpdate =
            new EventChannelDispatcher<>(event -> event.<Entity>getArgs(0).getType(), true);

    @Getter
    @Cancelable
    private static final EventChannelDispatcher<Entity> entityPreTickListener =
            new EventChannelDispatcher<>(Entity::getType);

    @Getter
    @Broadcast
    private static final EventChannelDispatcher<Entity> entityMidTickListener =
            new EventChannelDispatcher<>(Entity::getType);

    @Getter
    @Broadcast
    private static final EventChannelDispatcher<Entity> entityPostTickListener =
            new EventChannelDispatcher<>(Entity::getType);


    @Getter
    @Cancelable
    @Modifiable
    @ExtraArgs(value = {EntityType.class})
    private static final EventChannelDispatcher<Entity> entityCreateListener =
            new EventChannelDispatcher<>((event -> event.getArgs(0)), true);

    @Getter
    @Cancelable
    private static final EventChannelDispatcher<Entity> serverEntitySpawnListener =
            new EventChannelDispatcher<>(Entity::getType);

    @Getter
    @Broadcast
    @ExtraArgs(value = {Entity.RemovalReason.class})
    private static final EventChannelDispatcher<Entity> entityRemoveListener =
            new EventChannelDispatcher<>(Entity::getType);

    // world events

    @Getter
    @Cancelable
    private static final EventChannel<TickingBlockEntity> blockEntityTickListener = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannelDispatcher<BlockUpdate> blockUpdateListener =
            new EventChannelDispatcher<>(s -> s.newState().getBlock());

    @Getter
    @Broadcast
    private static final EventChannel<ChunkPos> chunkUpdateListener = new EventChannel<>();

    @Getter
    @Modifiable
    private static final EventChannel<Boolean> preWorldScannListener = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<Void> resetWorldScannListener = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<List<BiPredicate<BlockPos, BlockState>>> worldScannChunkBlockFilterList =
            new EventChannel<>();

    @Getter
    @Broadcast
    @ExtraArgs({BlockPos.class, ChunkPos.class})
    private static final EventChannelDispatcher<BlockState> worldScannBlockResult =
            new EventChannelDispatcher<>(BlockBehaviour.BlockStateBase::getBlock);

    @Getter
    @Broadcast
    @ExtraArgs({ChunkPos.class})
    private static final EventChannel<Map<BlockPos, BlockState>> worldScannChunkResult = new EventChannel<>();

    // client interactions and attacks
    @Getter // handle player uses and attacks
    @Cancelable
    private static final EventChannel<Void> preHandleInputEvents = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<Void> postHandleInputEvents = new EventChannel<>();

    @Getter
    @Cancelable
    private static final EventChannel<Boolean> playerDropSelectedItem = new EventChannel<>();

    @Getter
    @Cancelable
    private static final EventChannel<Void> playerCloseHandledScreen = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    private static final EventChannel<UseItem> prePlayerUseItem = new EventChannel<>();

    @Getter
    @Modifiable
    private static final EventChannel<UseItem> postPlayerUseItem = new EventChannel<>();

    @Getter // player interact at block
    @Cancelable
    @Modifiable
    private static final EventChannel<UseItemOnBlock> prePlayerUseItemAtBlock = new EventChannel<>();

    @Getter
    @Broadcast
    private static final EventChannel<UseItemOnBlock> postPlayerUseItemOnBlock = new EventChannel<>();

    @Getter // player attack at block
    @Cancelable
    @Modifiable
    @ExtraArgs(
            value = {boolean.class},
            names = {"isAttack"})
    private static final EventChannel<HitResult> mineBlockAction = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    private static final EventChannel<HitResult> attackAction = new EventChannel<>();

    @Getter
    @Cancelable
    @Modifiable
    @ExtraArgs(value = {InteractionHand.class})
    private static final EventChannel<HitResult> itemUseAction = new EventChannel<>();

    // client behaviours with the computer
    @Getter // the window size change
    @Broadcast
    private static final EventChannel<Point> resolutionChange = new EventChannel<>();

    @Getter // glfw events
    @Cancelable
    private static final EventChannel<KeyboardAction> KeyboardInput = new EventChannel<>();

    @Getter
    @Cancelable
    private static final EventChannel<MouseClickAction> mouseButton = new EventChannel<>();

    @Getter
    @Cancelable
    private static final EventChannel<MouseScrollAction> mouseScroll = new EventChannel<>();

    @Getter
    @Cancelable
    private static final EventChannel<MouseMoveAction> mouseMove = new EventChannel<>();

    @Getter
    @Cancelable
    private static final EventChannel<MouseDragAction> mouseDrag = new EventChannel<>();

    @Getter
    @Cancelable
    private static final EventChannel<CharTypedAction> charTyped = new EventChannel<>();

    @Getter // multiKeybind driven by glfw
    @Cancelable
    @ExtraArgs({IInputManager.class})
    private static final EventChannel<IHotKey> hotKeyTriggeredListener = new EventChannel<>();

    // exceptions
    @Getter
    @Cancelable
    @Dispatch(by = "WrapperException.type")
    private static final EventChannelDispatcher<WrapperException> exceptionListener =
            new EventChannelDispatcher<>(WrapperException::type);

    // custom event channel, where you can place all sort of things here
    @Getter
    @Cancelable(optional = true)
    @Modifiable(optional = true)
    @Dispatch(by = "EventContainer.getType")
    private static final EventChannelDispatcher<EventContainer<?>> customListener =
            new EventChannelDispatcher<>(EventContainer::getType);

    // network event

    @Getter
    @Broadcast
    @ExtraArgs({PacketFlow.class, Boolean.class})
    private static final EventChannel<ChannelPipeline> connectionChannelInitialize = new EventChannel<>();

    @Getter
    @Broadcast
    @ExtraArgs({PacketFlow.class, PacketListener.class})
    private static final EventChannel<Connection> connectionEstablish = new EventChannel<>();

    // misc

    @Getter
    @Cancelable
    @ExtraArgs({ParticleOptions.class})
    private static final EventChannelDispatcher<Particle> particleCreateListener =
            new EventChannelDispatcher<>(eve -> eve.<ParticleOptions>getArgs(0).getType(), true);

    @Getter
    @Cancelable
    @Modifiable
    private static final EventChannelDispatcher<SoundInstance> soundPlayEvent =
            new EventChannelDispatcher<>(SoundInstance::getIdentifier);

    @Getter
    @Cancelable
    private static final EventChannelDispatcher<SoundInstance> soundAddToHudEvent =
            new EventChannelDispatcher<>(SoundInstance::getIdentifier);

    private static final Set<Class<?>> asyncPackets = ImmutableSet.<Class<?>>builder()
            .add(ClientboundCustomPayloadPacket.class)
            .add(ClientboundChunkBatchStartPacket.class)
            .add(ClientboundChunkBatchFinishedPacket.class)
            .add(ClientboundPongResponsePacket.class)
            .add(ClientboundDisconnectPacket.class)
            .add(ClientboundResetChatPacket.class)
            .add(ClientboundUpdateEnabledFeaturesPacket.class)
            .build();

    public static boolean isAsyncImportantPacket(Packet<?> packet) {
        return asyncPackets.contains(packet.getClass());
    }

    public static void callPacketHandleEvent(
            Packet<?> instance, PacketListener t, BiConsumer<Packet<?>, PacketListener> callback) {
        if (!Listener.prepacketListenerApplyPoint(instance, t)) {
            try {
                callback.accept(instance, t);
            } catch (RunningOnDifferentThreadException e) {
                // off thread, maybe a mistake
            } catch (RejectedExecutionException | ClassCastException e) {
                throw e;
            } catch (Throwable e) {
                if (e instanceof ReportedException crashException
                        && crashException.getCause() instanceof OutOfMemoryError) {
                    throw e;
                }
                if (handleException(e, ExceptionType.PACKET_HANDLE_EXCEPTION, instance, t)) {
                    throw e;
                }
            } finally {
                Listener.postPacketListenerApplyPoint(instance, t);
            }
        }
    }

    public static boolean prepacketListenerApplyPoint(Packet<?> packet, PacketListener listener) {
        // most handle are on Thread, some are not
        if (!Minecraft.getInstance().isSameThread()) {
            return false;
        }
        Event<Packet<?>> packetEvent = new Event<>(packet, true, false, listener);
        packetPreHandlePoint.handleValue(packetEvent);
        return packetEvent.isCancelled();
    }

    public static void postPacketListenerApplyPoint(Packet<?> packet, PacketListener listener) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        Event<Packet<?>> packetEvent = new Event<>(packet, false, false, listener);
        packetPostHandlePoint.handleValue(packetEvent);
    }

    private static final Map<Class<?>, ArrayDeque<PacketCatcher>> preCatchers = new ConcurrentHashMap<>();
    private static final Map<Class<?>, ArrayDeque<PacketCatcher>> postCatchers = new ConcurrentHashMap<>();

    public static <T extends Packet<?>> void addPrePacketCatcher(PacketCatcher packet) {
        Class<?> dequeCls =
                packet instanceof AbstractTypedPacketCatcher abstractType ? abstractType.packetClass : Packet.class;
        var re = preCatchers.computeIfAbsent(dequeCls, k -> new ArrayDeque<>());
        synchronized (re) {
            re.addLast(packet);
        }
    }

    public static <T extends Packet<?>> void addPostPacketCatcher(PacketCatcher packet) {
        Class<?> dequeCls =
                packet instanceof AbstractTypedPacketCatcher abstractType ? abstractType.packetClass : Packet.class;
        var re = postCatchers.computeIfAbsent(dequeCls, k -> new ArrayDeque<>());
        synchronized (re) {
            re.addLast(packet);
        }
    }

    public static void onPacketEventCatch(
            Map<Class<?>, ArrayDeque<PacketCatcher>> packetCatchers, Event<? extends Packet<?>> packet) {
        Packet<?> pkt = packet.context();
        ArrayDeque<PacketCatcher> re = packetCatchers.get(Packet.class);
        if (re != null) {
            onPacketCatcherArrayWalk(re, packet);
        }
        if (packet.isCancelled()) return;
        ArrayDeque<PacketCatcher> re2 = packetCatchers.get(Listener.getMappedPacketClass(pkt.getClass()));
        if (re2 != null) {
            onPacketCatcherArrayWalk(re2, packet);
        }
    }

    private static void onPacketCatcherArrayWalk(ArrayDeque<PacketCatcher> re, Event<? extends Packet<?>> packet) {
        if (packet.isCancelled()) return;
        synchronized (re) {
            var iter = re.iterator();
            while (iter.hasNext()) {
                var handler = iter.next();
                boolean removal = handler.catchEvent(packet);
                if (removal) {
                    iter.remove();
                }
                if (packet.isCancelled()) {
                    return;
                }
            }
        }
    }

    public static void sendPacketNoEvents(Packet<?> packet) {
        var re = Minecraft.getInstance().getConnection();
        if (re != null) {
            sendPacketNoEvents(re.getConnection(), packet);
        }
    }

    // make a method to send packet without event
    public static void sendPacketNoEvents(Connection connection, Packet<?> packet) {
        connection.runOnceConnected((con) -> {
            Channel channel = con.channel;
            if (channel.eventLoop().inEventLoop()) {
                sendInternal(channel, packet);
            } else {
                channel.eventLoop().execute(() -> {
                    sendInternal(channel, packet);
                });
            }
        });
    }

    private static void sendInternal(Channel channel, Packet<?> packet) {
        ChannelFuture channelFuture = channel.writeAndFlush(packet);
        channelFuture.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    public static void onClientConnectionEstablish(Event<Connection> event) {
        if (event.getArgs(0) == PacketFlow.CLIENTBOUND && event.getArgs(1) instanceof ClientCookiePacketListener) {
            clientConnection = event.context;
            Tasks.scheduleRepeated(
                    () -> {
                        // after the connection
                        if (clientConnection != null
                                && clientConnection.isConnecting()
                                && !clientConnection.isConnected()) {
                            clientConnection = null;
                            return true;
                        }
                        return false;
                    },
                    20,
                    20);
        }
    }

    static {
        Listener.getPacketPreHandlePoint()
                .registerHandler(
                        (Consumer<Event<Packet<?>>>) ev -> onPacketEventCatch(preCatchers, ev), Integer.MIN_VALUE);
        Listener.getPacketSendPoint()
                .registerHandler(
                        (Consumer<Event<Packet<?>>>) ev -> onPacketEventCatch(preCatchers, ev), Integer.MIN_VALUE);

        Listener.getPacketPostHandlePoint()
                .registerHandler(
                        (Consumer<Event<Packet<?>>>) ev -> onPacketEventCatch(postCatchers, ev), Integer.MIN_VALUE);
        Listener.getPacketPostSendPoint()
                .registerHandler(
                        (Consumer<Event<Packet<?>>>) ev -> onPacketEventCatch(postCatchers, ev), Integer.MIN_VALUE);
        Listener.getConnectionEstablish()
                .registerHandler((Consumer<Event<Connection>>) Listener::onClientConnectionEstablish);
    }

    public static boolean handleException(Throwable e, ExceptionType type, Object... objects) {
        Event<WrapperException> event = new Event<>(new WrapperException(type, e), true, false, objects);
        getExceptionListener().handleValue(event);
        if (event.isCancelled()) {
            return false;
        } else {
            return true;
        }
    }

    public static record WrapperException(ExceptionType type, Throwable exception) {}

    public static enum ExceptionType {
        PACKET_HANDLE_EXCEPTION,
        PACKET_DECODE_EXCEPTION,
        UNKNOWN_CHANNEL_EXCEPTION,
        CLIENT_CRASH,
        ENTITY_TICK,
        BLOCK_ENTITY_TICK,
        UNKNOWN;
    }
}
