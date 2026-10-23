package me.matl114.hacks.modules.extra;

import java.util.*;
import me.matl114.accessors.access.PlayerMoveC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.entity.PlayerInputUtils;
import me.matl114.versioned.SupportVersion;
import me.matl114.versioned.api.VPacket;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public class BadPacketsFix extends BaseModule {
    public static BadPacketsFix INSTANCE;
    public final ModulePath badPackets = makePath(Configs.EXTRA_CONFIG, "bad-packets");

    public BadPacketsFix() {
        super("BadPackets");
        INSTANCE = this;
    }

    public final FlagRef enableSprint = builder(badPackets.add("fix-dup-sprint"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef enableSneak = builder(badPackets.add("fix-dup-sneak"), Boolean.class)
            .defaultValue(true)
            .build();
    public final FlagRef enableInput = builder(badPackets.add("fix-dup-input"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef exemptDupRot = builder(badPackets.add("exempt-dup-rot"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef filterDupRot = builder(badPackets.add("filter-dup-rot"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef enableFly = builder(badPackets.add("fix-fly-packets"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef enableRot = builder(badPackets.add("fix-dup-rot"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef enableFullRot = builder(badPackets.add("fix-full-dup-rot"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef enableViewDistance = builder(badPackets.add("fix-illegal-server-view-distance"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef fixIncorrectTools = builder(badPackets.add("fix-bad-block-tags"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef cancelLitematicaTransmit = builder(badPackets.add("fix-litematica-transmit"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef fixInvalidPlayerEntryUpdate =
            flagBuilder(badPackets.add("fix-invalid-player-entry-update")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPlayerRespawnPoint(), this::onPlayerInitialize);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerCommandPacket.class),
                this::onSendSprint,
                Integer.MIN_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerInputPacket.class), this::onSendInput, Integer.MIN_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundPlayerAbilitiesPacket.class),
                this::onServerAbility,
                Integer.MIN_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerAbilitiesPacket.class),
                this::onAbilityUpdate,
                Integer.MIN_VALUE);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundAcceptTeleportationPacket.class), this::onTeleportConfirm);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundMovePlayerPacket.class),
                this::onPlayerRotation,
                Integer.MIN_VALUE);
        //        registerListener(Listener.getPreHandleInputEvents(), this::onPreInputEvent);
        //        registerListener(Listener.getPostHandleInputEvents(), this::onPostInputEvent);
        registerListener(Listener.getWorldSwitchPoint(), this::onWorldChange);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundSetChunkCacheRadiusPacket.class),
                this::onRepackViewDistance,
                Integer.MIN_VALUE);
        registerListener(
                Listener.getRegistryTagKeyReload().getChannel(BuiltInRegistries.BLOCK.key()), this::fixTagsBadPackets);
    }

    boolean serverSprint = false;
    // removed due to protocol change
    // boolean serverSneak = false;
    boolean serverCanFly = false;
    PlayerInputUtils.Input serverInput = PlayerInputUtils.EMPTY;
    float serverPitch;
    float serverYaw;
    //    boolean handlingInputs = false;

    //    public void onPreInputEvent(Event<Void> eventVoid) {
    //        handlingInputs = true;
    //    }
    //
    //    public void onPostInputEvent(Event<Void> eventVoid) {
    //        handlingInputs = false;
    //    }

    public void onPlayerInitialize(Event<LocalPlayer> event) {
        LocalPlayer entity = event.context();
        serverSprint = entity.isSprinting();
        // serverSneak = entity.isSneaking();
        serverInput = PlayerInputUtils.EMPTY;
        serverCanFly = entity.getAbilities().mayfly;
        serverPitch = entity.getXRot();
        serverYaw = entity.getYRot();
    }

    public void onSendSprint(Event<ServerboundPlayerCommandPacket> event) {
        if (event.context().getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING
                || event.context().getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) {
            boolean isStartingSprint =
                    (event.context().getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING);
            if (serverSprint == isStartingSprint) {
                if (enableSprint.get()) {
                    event.cancel();
                }
            } else {
                serverSprint = isStartingSprint;
            }
        }
    }

    public boolean shouldConsiderInputPacket = SupportVersion.CURRENT.isHigherOrEqualTo(21, 2);

    public void onSendInput(Event<ServerboundPlayerInputPacket> inputC2SPacketEvent) {
        PlayerInputUtils.Input input = PlayerInputUtils.of(inputC2SPacketEvent.context());
        if (Objects.equals(input, serverInput)) {
            if (!mc.player.isPassenger() && shouldConsiderInputPacket && enableInput.get()) {
                inputC2SPacketEvent.cancel();
            }
        } else {
            serverInput = input;
        }
    }

    public void onServerAbility(Event<ClientboundPlayerAbilitiesPacket> event) {
        serverCanFly = event.context.canFly();
    }

    public void onAbilityUpdate(Event<ServerboundPlayerAbilitiesPacket> event) {
        if (!event.isCancelled()
                && enableFly.get()
                && !serverCanFly
                && event.context().isFlying()) {
            event.cancel();
        }
    }

    boolean exempt = false;

    public void onTeleportConfirm(Event<ServerboundAcceptTeleportationPacket> packetEvent) {
        exempt = true;
    }

    public void onPlayerRotation(Event<ServerboundMovePlayerPacket> packetEvent) {
        ServerboundMovePlayerPacket packet = packetEvent.context();
        float serverPitch = packet.getXRot(this.serverPitch);
        float serverYaw = packet.getYRot(this.serverYaw);
        if (packet instanceof PlayerMoveC2SPacketAccess access) {
            PlayerMoveC2SPacketAccess.Cause cause = access.getCause();
            if (cause != null) {
                switch (cause) {
                    case SET_BACK, LEGACY_SNAP -> {
                        exempt = true;
                    }
                }
            }
        }
        // fix lower than 1.20.6 interactItem protocol
        if (exemptDupRot.get() && ViaFabricPlusHooks.isSupportDupRot()) {
            if (packet instanceof ServerboundMovePlayerPacket.PosRot fullPacket) {
                // 懒得核验了，直接过吧
                exempt = true;
            }
        }
        if (filterDupRot.get() && ViaFabricPlusHooks.isSupportDupRot()) {
            if (packet instanceof ServerboundMovePlayerPacket.PosRot fullPacket
                    && LegacySnapRotManager.INSTANCE.betweenViaPacket
                    && fullPacket instanceof PlayerMoveC2SPacketAccess access
                    && access.getCause() == PlayerMoveC2SPacketAccess.Cause.LEGACY_SNAP) {
                if (!EntityUtils.isRotationDifferent(serverPitch, this.serverPitch, serverYaw, this.serverYaw)) {
                    this.serverPitch = serverPitch;
                    this.serverYaw = serverYaw;
                    exempt = false;
                    packetEvent.cancel();
                    return;
                }
            }
        }
        if (exempt) {
            this.serverPitch = serverPitch;
            this.serverYaw = serverYaw;
            exempt = false;
            return;
        }

        if (serverPitch == this.serverPitch && serverYaw == this.serverYaw) {
            if (packet.hasRotation() && enableRot.get()) {
                // do not handle full packet
                if (enableFullRot.get() && packet instanceof ServerboundMovePlayerPacket.PosRot full) {
                    packetEvent.context(PlayerMoveC2SPacketAccess.setCauseFrom(
                            VPacket.newPositionAndOnGround(
                                    packet.getX(mc.player.getX()),
                                    packet.getY(mc.player.getY()),
                                    packet.getZ(mc.player.getX()),
                                    packet.isOnGround(),
                                    VPacket.getCollisionFlag(full)),
                            full));
                } else if (packet instanceof ServerboundMovePlayerPacket.Rot lookAndOnGround) {
                    if (!mc.player.isPassenger()) {
                        packetEvent.context(PlayerMoveC2SPacketAccess.setCauseFrom(
                                VPacket.newOnGroundOnly(
                                        lookAndOnGround.isOnGround(), VPacket.getCollisionFlag(lookAndOnGround)),
                                lookAndOnGround));
                    }
                }
            }
        } else {
            this.serverPitch = serverPitch;
            this.serverYaw = serverYaw;
            return;
        }
    }

    public void onWorldChange(Event<Level> eventWorldChange) {
        if (enableViewDistance.get() && eventWorldChange.context != null) {
            if (eventWorldChange.context instanceof ClientLevel client) {
                if (client.getChunkSource().storage.chunkRadius > 35) {
                    client.getChunkSource().updateViewRadius(32);
                }
            }
        }
    }

    public void onRepackViewDistance(Event<ClientboundSetChunkCacheRadiusPacket> eventChunkLoad) {
        if (enableViewDistance.get() && eventChunkLoad.context != null) {
            ClientboundSetChunkCacheRadiusPacket packet = eventChunkLoad.context();
            if (packet.getRadius() > 32) {
                eventChunkLoad.context(new ClientboundSetChunkCacheRadiusPacket(32));
            }
        }
    }

    public void fixTagsBadPackets(Event<Map<TagKey<?>, List<Holder<?>>>> event) {
        var registryKey = event.getArgs(0);
        if (fixIncorrectTools.get() && Objects.equals(registryKey, BuiltInRegistries.BLOCK.key())) {
            var original = event.context;

            Map<TagKey<?>, List<Holder<?>>> recreateMap = null;
            var lst = original.get(BlockTags.MINEABLE_WITH_PICKAXE);
            if (lst != null) {
                int idx = lst.indexOf(Blocks.CHEST.builtInRegistryHolder());
                if (idx != -1) {
                    if (recreateMap == null) {
                        recreateMap = new HashMap<>(original);
                    }
                    var lstCopy = new ArrayList<>(lst);
                    lstCopy.remove(idx);
                    recreateMap.put(BlockTags.MINEABLE_WITH_PICKAXE, lstCopy);
                }
            }
            if (recreateMap != null) {
                event.context(recreateMap);
            }
        }
    }
}
