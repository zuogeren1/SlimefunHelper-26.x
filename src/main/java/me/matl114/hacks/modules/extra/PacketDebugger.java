package me.matl114.hacks.modules.extra;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import me.matl114.SlimefunHelper;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.events.impl.SlotClickAction;
import me.matl114.events.packets.PacketStorage;
import me.matl114.events.packets.PacketStorageImpl;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.StringRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.Debug;
import me.matl114.utils.entity.PlayerInputUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public class PacketDebugger extends BaseModule {
    public final ModulePath packetDebugger = makePath(Configs.EXTRA_CONFIG, "packet-debugger");

    public PacketDebugger() {
        super("PacketDebug");
        bindFlag(enable);
    }

    private Set<PacketType<?>> typesDebug = new HashSet<>();

    private Set<PacketType<?>> typesIntercept = new HashSet<>();

    private Set<PacketType<?>> getDebugTypes(String regex) {
        Set<PacketType<?>> types = new HashSet<>();
        for (var entry : Listener.getRegisteredPacketTypes().keySet()) {
            if (Pattern.matches(regex, entry.id().getPath())) {
                types.add(entry);
            }
        }
        return types;
    }

    public final FlagRef enable = flagBuilder(packetDebugger.addEnable()).build();

    public final KeyBindRef enablePressShow = moduleEntry(
                    packetDebugger.addHotkey(), new MultiKeyBind(), packetDebugger.addEnable())
            .build();

    public final FlagRef debugIn =
            flagBuilder(packetDebugger.add("debug-packet-in")).build();

    public final FlagRef debugOut =
            flagBuilder(packetDebugger.add("debug-packet-out")).build();

    public final FlagRef debugClicks =
            flagBuilder(packetDebugger.add("debug-click-actions")).build();

    public final FlagRef debugViaPackets =
            flagBuilder(packetDebugger.add("debug-via-packet")).build();

    public final StringRef debugPacketType = builder(packetDebugger.add("debug-packet-type"), StringRef.TYPE)
            .defaultValue("^(move_player_.*)$")
            .validator(Configs.REGEX_VALIDATOR)
            .updateListener((v) -> typesDebug = getDebugTypes(v))
            .build();

    public final FlagRef debugTime =
            flagBuilder(packetDebugger.add("debug-time")).build();

    public final FlagRef stopDebugInChat = flagBuilder(packetDebugger.add("stop-debug-in-chat"))
            .show(() -> SlimefunHelper.DEV_ENV)
            .build();

    public final FlagRef interceptPacket =
            flagBuilder(packetDebugger.add("intercept-packet")).build();

    public final StringRef interceptPacketType = builder(packetDebugger.add("intercept-packet-type"), StringRef.TYPE)
            .defaultValue("^()$")
            .validator(Configs.REGEX_VALIDATOR)
            .updateListener((v) -> typesIntercept = getDebugTypes(v))
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPacketPreHandlePoint(), this::onPacketHandle, Integer.MIN_VALUE);
        registerListener(Listener.getPacketPostScheduleSendPoint(), this::onPacketSend, Integer.MIN_VALUE);
        registerListener(PacketManager.getPacketQueueOutEvent(), this::onViaSend, Integer.MIN_VALUE);
        registerListener(Listener.getPacketPoint(), this::onPacket);
        registerListener(Listener.getPreClickSlot(), this::onClick);
    }

    public static String simplifyId(Identifier id) {
        if (Objects.equals("minecraft", id.getNamespace())) {
            return "mc:" + id.getPath();
        } else return id.toString();
    }

    public void onPacketHandle(Event<Packet<?>> packetEvent) {
        if (packetEvent.isCancelled()) return;
        if (enable.get() && debugIn.get()) {
            Packet<?> type = packetEvent.context();
            if (typesDebug.contains(type.type())) {
                String timeStr = debugTime.get() ? (", Tick: " + Tasks.getTick()) : "";
                if (type instanceof ClientboundPlayerPositionPacket positionLookS2CPacket) {
                    Vec3 vec3d = positionLookS2CPacket.change().position();
                    debug(
                            "Accept",
                            simplifyId(type.type().id()),
                            vec3d.x,
                            vec3d.y,
                            vec3d.z,
                            ", Pitch:",
                            positionLookS2CPacket.change().xRot(),
                            ", Yaw:",
                            positionLookS2CPacket.change().yRot(),
                            ", Id:",
                            positionLookS2CPacket.id(),
                            timeStr);
                } else if (type instanceof ClientboundPingPacket ping) {
                    debug("Send", simplifyId(type.type().id()), ", Id:", ping.getId(), timeStr);
                } else {
                    debug("Accept", simplifyId(type.type().id()), timeStr);
                }
            }
        }
    }

    public void onPacketSend(Event<Packet<?>> packetEvent) {
        if (packetEvent.isCancelled()) return;
        if (enable.get() && debugOut.get()) {
            Packet<?> type = packetEvent.context();
            if (typesDebug.contains(type.type())) {
                String timeStr = debugTime.get() ? (", Tick: " + Tasks.getTick()) : "";
                if (type instanceof ServerboundMovePlayerPacket moveC2SPacket) {
                    debug(
                            "Send",
                            simplifyId(type.type().id()),
                            moveC2SPacket.getX(0.0),
                            moveC2SPacket.getY(0.0),
                            moveC2SPacket.getZ(0.0),
                            ", Pitch:",
                            moveC2SPacket.getXRot(0.0F),
                            ", Yaw:",
                            moveC2SPacket.getYRot(0.0F),
                            ", onGround:",
                            moveC2SPacket.isOnGround(),
                            timeStr);
                } else if (type instanceof ServerboundPlayerInputPacket playerInputC2SPacket) {
                    PlayerInputUtils.Input input = PlayerInputUtils.of(playerInputC2SPacket);
                    debug("Send", simplifyId(type.type().id()), input, timeStr);
                } else if (type instanceof ServerboundPlayerActionPacket actionC2SPacket) {
                    debug(
                            "Send",
                            simplifyId(type.type().id()),
                            actionC2SPacket.getAction().name(),
                            actionC2SPacket.getPos(),
                            actionC2SPacket.getSequence(),
                            timeStr);
                } else if (type instanceof ServerboundAttackPacket attack) {
                    // 26.2 把攻击拆成了独立包，旧版是 PlayerInteractEntityC2SPacket 的 ATTACK 类型
                    debug("Send", simplifyId(type.type().id()), "ATTACK", attack.entityId(), timeStr);
                } else if (type instanceof ServerboundInteractPacket interact) {
                    // usingSecondaryAction 是客户端传的"是否潜行"，不是交互类型
                    // （旧版 INTERACT_AT 是带命中点的交互，26.2 该语义合并进 ServerboundInteractPacket）
                    debug(
                            "Send",
                            simplifyId(type.type().id()),
                            interact.usingSecondaryAction() ? "INTERACT(sneaking)" : "INTERACT",
                            interact.entityId,
                            timeStr);
                } else if (type instanceof ServerboundPlayerCommandPacket ccmd) {
                    debug("Send", simplifyId(type.type().id()), ccmd.getAction().name(), timeStr);
                } else if (type instanceof ServerboundAcceptTeleportationPacket confirm) {
                    debug("Send", simplifyId(type.type().id()), ", Id:", confirm.getId(), timeStr);
                } else if (type instanceof ServerboundPongPacket pong) {
                    debug("Send", simplifyId(type.type().id()), ", Id:", pong.getId(), timeStr);
                } else {
                    debug("Send", simplifyId(type.type().id()), timeStr);
                }
            }
        }
    }

    public void onClick(Event<SlotClickAction> eventAction) {
        if (enable.get() && debugClicks.get()) {
            String timeStr = debugTime.get() ? (", Tick: " + Tasks.getTick()) : "";
            debug(
                    "Click: button:",
                    eventAction.context.button(),
                    ",slot:",
                    eventAction.context.slotId(),
                    ",type:",
                    eventAction.context.actionType().name(),
                    timeStr);
        }
    }

    public void onPacket(Event<Packet<?>> packetEvent) {
        if (packetEvent.isCancelled()) return;
        if (enable.get() && interceptPacket.get()) {
            Packet<?> type = packetEvent.context();
            if (typesIntercept.contains(type.type())) {
                packetEvent.cancel();
            }
        }
    }

    public void onViaSend(Event<PacketStorage> eventPacketStorage) {
        if (!(eventPacketStorage.context instanceof PacketStorageImpl)
                && enable.get()
                && debugViaPackets.get()) {
            // via packets
            PacketType<?> type = eventPacketStorage.context.packetType();
            if (type != null && typesDebug.contains(type)) {
                String timeStr = debugTime.get() ? (", Tick: " + Tasks.getTick()) : "";
                debug("Send by via:", simplifyId(type.id()), timeStr);
            }
        }
    }

    public void debug(Object... val) {
        if (!stopDebugInChat.get()) {
            Debug.chat(val);
        } else {
            Debug.info(val);
        }
    }
}
