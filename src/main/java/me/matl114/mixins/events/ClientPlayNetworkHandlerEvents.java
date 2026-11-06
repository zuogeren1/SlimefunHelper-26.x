package me.matl114.mixins.events;

import me.matl114.utils.ClientUtils;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import me.matl114.accessors.access.PlayerMoveC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.ChatRecv;
import me.matl114.events.impl.PosUpdate;
import me.matl114.events.impl.Teleportation;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerEvents {
    @Inject(method = "handleOpenScreen", at = @At("RETURN"))
    private void onPostInventoryOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (ClientUtils.getScreen() instanceof AbstractContainerScreen<?> screen) {
            Listener.getPostOpenHandledScreen().broadcast(screen);
        }
    }

    @Unique
    boolean escapeSendEvent = false;

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void onChat0(String command, CallbackInfo ci, @Local(argsOnly = true) LocalRef<String> commandRef) {
        if (escapeSendEvent) {
            escapeSendEvent = false;
            return;
        }
        String chatContent = "/" + command;
        Event<String> value = new Event<>(chatContent, true, true);
        Listener.getChatSend().handleValue(value);
        if (value.isCancelled()) {
            ci.cancel();
            return;
        }
        String valueChange = value.context();
        if (valueChange == null || valueChange.isEmpty()) {
            ci.cancel();
            return;
        }
        if (!Objects.equals(chatContent, valueChange)) {
            if (valueChange.startsWith("/")) {
                commandRef.set(valueChange.substring(1));
            } else {
                // change a command to a chat message
                ci.cancel();
                escapeSendEvent = true;
                sendChat(valueChange);
            }
        }
    }

    @Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
    private void onClickRunCommand(
            String command,
            Screen afterActionScreen,
            CallbackInfo ci,
            @Local(argsOnly = true) LocalRef<String> commandRef) {
        if (escapeSendEvent) {
            escapeSendEvent = false;
            return;
        }
        String chatContent = "/" + command;
        Event<String> value = new Event<>(chatContent, true, true);
        Listener.getChatSend().handleValue(value);
        if (value.isCancelled()) {
            ci.cancel();
            return;
        }
        String valueChange = value.context();
        if (valueChange == null || valueChange.isEmpty()) {
            ci.cancel();
            return;
        }
        if (!Objects.equals(chatContent, valueChange)) {
            if (valueChange.startsWith("/")) {
                commandRef.set(valueChange.substring(1));
            } else {
                // Change the clicked command to a chat message.
                ci.cancel();
                escapeSendEvent = true;
                sendChat(valueChange);
            }
        }
    }

    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void onChat2(String content, CallbackInfo ci, @Local(argsOnly = true) LocalRef<String> contentRef) {
        if (escapeSendEvent) {
            escapeSendEvent = false;
            return;
        }
        Event<String> value = new Event<>(content, true, true);
        Listener.getChatSend().handleValue(value);
        if (value.isCancelled()) {
            // set null string to trigger ret
            ci.cancel();
            return;
        }
        if (value.context() == null) {
            ci.cancel();
            return;
        }
        String valueChange = value.context();
        if (!Objects.equals(content, valueChange)) {
            if (!valueChange.startsWith("/")) {
                contentRef.set(valueChange);
            } else {
                ci.cancel();
                escapeSendEvent = true;
                sendCommand(valueChange.substring(1));
            }
        }
    }

    @Shadow
    private ClientLevel level;

    @Unique
    private boolean playerRecreateOnJoin = false;

    @Inject(
            method = "handleLogin",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    //#if MC >= 260300
                                    //$$ "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;createPlayer(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/stats/StatsCounter;Lnet/minecraft/client/ClientRecipeBook;Lnet/minecraft/client/player/ItemActivation;)Lnet/minecraft/client/player/LocalPlayer;",
                                    //#else
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;createPlayer(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/stats/StatsCounter;Lnet/minecraft/client/ClientRecipeBook;)Lnet/minecraft/client/player/LocalPlayer;",
                                    //#endif
                            shift = At.Shift.AFTER))
    private void onGameJoinCreatePlayer0(ClientboundLoginPacket packet, CallbackInfo ci) {
        playerRecreateOnJoin = true;
    }

    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void onGameJoinEntryPoint(ClientboundLoginPacket packet, CallbackInfo ci) {
        Listener.getGameJoinPoint().broadcast(Minecraft.getInstance().player);
        Listener.getWorldSwitchPoint().broadcast(this.level);
        if (playerRecreateOnJoin) {
            playerRecreateOnJoin = false;
            Listener.getPlayerRespawnPoint().broadcast(Minecraft.getInstance().player);
        }
    }

    @Unique
    private boolean worldChangeOnRespawn = false;

    @Inject(
            method = "handleRespawn",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    //#if MC >= 26.2
                                    "Lnet/minecraft/client/multiplayer/ClientLevel;<init>(Lnet/minecraft/client/multiplayer/ClientPacketListener;Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/Holder;IILnet/minecraft/client/renderer/extract/LevelExtractor;ZJI)V",
                                    //#else
                                    //$$ "Lnet/minecraft/client/multiplayer/ClientLevel;<init>(Lnet/minecraft/client/multiplayer/ClientPacketListener;Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/Holder;IILnet/minecraft/client/renderer/LevelRenderer;ZJI)V",
                                    //#endif
                            shift = At.Shift.AFTER))
    private void onPlayerSwitchDimension0(ClientboundRespawnPacket packet, CallbackInfo ci) {
        worldChangeOnRespawn = true;
    }

    @Inject(method = "handleRespawn", at = @At("RETURN"))
    private void onPlayerSwitchDimension(ClientboundRespawnPacket packet, CallbackInfo ci) {
        if (worldChangeOnRespawn) {
            worldChangeOnRespawn = false;
            Listener.getWorldSwitchPoint().broadcast(this.level);
        }
        Listener.getPlayerRespawnPoint().broadcast(Minecraft.getInstance().player);
    }

    @Shadow
    public abstract Connection getConnection();

    @Shadow
    public abstract void sendChat(String content);

    @Shadow
    public abstract void sendCommand(String command);

    @Shadow
    public abstract PlayerInfo getPlayerInfo(UUID player);

    @WrapOperation(
            method = "handleMovePlayer",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V",
                            //#if MC >= 260300
                            //$$ ordinal = 0))
                            //#else
                            ordinal = 1))
                            //#endif
    private void onTeleportConfirmResponse(
            Connection instance,
            Packet<?> packet,
            Operation<Void> original,
            @Local(argsOnly = true) ClientboundPlayerPositionPacket posLook) {
        //#if MC >= 260300
        //$$ if (packet instanceof ServerboundAcceptTeleportationPacket acceptPacket) {
        //$$     Listener.getTeleportationConfirm()
        //$$             .broadcast(new Teleportation(
        //$$                     acceptPacket.id(),
        //$$                     acceptPacket.x(),
        //$$                     acceptPacket.y(),
        //$$                     acceptPacket.z(),
        //$$                     acceptPacket.xRot(),
        //$$                     acceptPacket.yRot()));
        //$$ }
        //#else
        if (packet instanceof ServerboundMovePlayerPacket.PosRot fullPacket) {
            packet = PlayerMoveC2SPacketAccess.setCause(fullPacket, PlayerMoveC2SPacketAccess.Cause.SET_BACK);
            Listener.getTeleportationConfirm()
                    .broadcast(new Teleportation(
                            posLook.id(),
                            fullPacket.getX(0.0D),
                            fullPacket.getY(0.0D),
                            fullPacket.getZ(0.0D),
                            fullPacket.getXRot(0.0F),
                            fullPacket.getYRot(0.0F)));
        }
        //#endif
        original.call(instance, packet);
    }

    // 玩家进/出事件只从调用点取实参。@Local 依赖目标方法的局部变量表：handlePlayerInfoUpdate 里
    // playerInfo 的条目只到调用点之后一格（区间 [64,94)、addPlayer 在 91），第三方 mixin 改写局部变量
    // 时这张表还会被剥掉条目 —— 部分环境因此报
    // "Found 0 candidate variables but exactly 1 is required"，整个 ClientPacketListener 加载失败。
    // @WrapOperation 只认调用点描述符，与局部变量表无关。
    @WrapOperation(
            method = "handlePlayerInfoUpdate",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/screens/social/PlayerSocialManager;addPlayer(Lnet/minecraft/client/multiplayer/PlayerInfo;)V"))
    private void onOtherPlayerJoin(
            PlayerSocialManager instance, PlayerInfo playerListEntry, Operation<Void> original) {
        original.call(instance, playerListEntry);
        Listener.getOtherPlayerJoinPoint().broadcast(playerListEntry);
    }

    @WrapOperation(
            method = "handlePlayerInfoRemove",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;remove(Ljava/lang/Object;)Z"))
    private boolean onOtherPlayerExit(Set<PlayerInfo> instance, Object value, Operation<Boolean> original) {
        boolean removed = original.call(instance, value);
        if (value instanceof PlayerInfo playerListEntry) {
            Listener.getOtherPlayerExitPoint().broadcast(playerListEntry);
        }
        return removed;
    }

    @Inject(method = "applyPlayerInfoUpdate", at = @At("RETURN"))
    private void onPlayerListUpdate(
            ClientboundPlayerInfoUpdatePacket.Action action,
            ClientboundPlayerInfoUpdatePacket.Entry receivedEntry,
            PlayerInfo currentEntry,
            CallbackInfo ci) {
        Listener.getOtherPlayerEntryUpdate().broadcast(currentEntry, action);
    }

    @WrapOperation(
            method = "handleSetEntityMotion",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/Entity;lerpMotion(Lnet/minecraft/world/phys/Vec3;)V"))
    private void onEntityVelocityUpdate(Entity instance, Vec3 clientVelocity, Operation<Void> original) {
        if (!Listener.getEntityClientVelocityUpdate().isEmpty()) {
            Event<Vec3> vcUpdate = new Event<>(clientVelocity, true, true, instance);
            Listener.getEntityClientVelocityUpdate().handleValue(vcUpdate);
            if (vcUpdate.isCancelled()) {
                return;
            } else {
                Vec3 vec3d1 = vcUpdate.context();
                original.call(instance, vec3d1);
            }
        } else {
            original.call(instance, clientVelocity);
        }
    }

    @WrapOperation(
            method = "handleExplosion",
            at = @At(value = "INVOKE", target = "Ljava/util/Optional;ifPresent(Ljava/util/function/Consumer;)V"))
    private void onExplosionVelocityUpdate(Optional instance, Consumer<? super Vec3> action, Operation<Void> original) {
        if (instance.isPresent()) {
            Vec3 vec3d = (Vec3) instance.get();
            Event<Vec3> updateDeltaEvent = new Event<>(vec3d, true, true);
            Listener.getPlayerExplosionVelocity().handleValue(updateDeltaEvent);
            if (!updateDeltaEvent.isCancelled()) {
                original.call(
                        (Optional)
                                (vec3d == updateDeltaEvent.context()
                                        ? instance
                                        : Optional.ofNullable(updateDeltaEvent.context())),
                        action);
            }
        }
    }

    @Inject(
            method = "handleAddEntity",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/ClientLevel;addEntity(Lnet/minecraft/world/entity/Entity;)V",
                            shift = At.Shift.BEFORE),
            cancellable = true)
    private void onEntitySpawn(ClientboundAddEntityPacket packet, CallbackInfo ci, @Local Entity playerEntity) {
        if (!Listener.getServerEntitySpawnListener().isEmpty()) {
            Event<Entity> entityAdd = new Event<>(playerEntity, true, false);
            Listener.getServerEntitySpawnListener().handleValue(entityAdd);
            if (entityAdd.isCancelled()) {
                ci.cancel();
            }
        }
    }

    @WrapOperation(
            method = "handleBundlePacket",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private void wrapBundledPacket(Packet instance, PacketListener t, Operation<Void> original) {
        // do not handle serverbound packet
        if (t.flow() == PacketFlow.SERVERBOUND) {
            original.call(instance, t);
            return;
        }
        Listener.callPacketHandleEvent(instance, t, original::call);
    }

    @ModifyExpressionValue(
            method = "updateTags",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/tags/TagNetworkSerialization$NetworkPayload;resolve(Lnet/minecraft/core/Registry;)Lnet/minecraft/tags/TagLoader$LoadResult;"))
    private <T> TagLoader.LoadResult<T> onRegistryTagReload(
            TagLoader.LoadResult<T> original,
            @Local(argsOnly = true) ResourceKey<? extends Registry<? extends T>> registryKey) {
        Map<TagKey<T>, List<Holder<T>>> tagMap = original.tags();
        Event<Map<TagKey<T>, List<Holder<T>>>> event = new Event<>(tagMap, false, true, original.key());
        Listener.getRegistryTagKeyReload().handleValue((Event) event);
        if (event.context != tagMap) {
            return new TagLoader.LoadResult<>(original.key(), event.context);
        }
        return original;
    }

    @Inject(
            method = "handleLevelChunkWithLight",
            //#if MC >= 260300
            //$$ at =
            //$$         @At(
            //$$                 value = "INVOKE",
            //$$                 target =
            //$$                         "Lnet/minecraft/client/multiplayer/ClientChunkCache;replaceWithPacketData(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)Lnet/minecraft/world/level/chunk/LevelChunk;",
            //$$                 shift = At.Shift.AFTER))
            //#else
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/ClientPacketListener;updateLevelChunk(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)V",
                            shift = At.Shift.AFTER))
            //#endif
    private void onLoadChunkPost(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        //#if MC >= 260300
        //$$ ChunkPos pos = new ChunkPos(packet.x(), packet.z());
        //#else
        ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
        //#endif
        Listener.getChunkUpdateListener().broadcast(pos);
    }

    @WrapOperation(
            method = "handleSystemChat",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/chat/ChatListener;handleOverlay(Lnet/minecraft/network/chat/Component;)V"))
    private void onOverlayMessage(ChatListener instance, Component message, Operation<Void> original) {
        Event<Component> actionBarEvent = new Event<>(message, true, true);
        Listener.getActionBarMessageReceive().handleValue(actionBarEvent);
        if (actionBarEvent.isCancelled()) {
            return;
        }
        original.call(instance, actionBarEvent.context());
    }

    @WrapOperation(
            method = "handleSystemChat",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/chat/ChatListener;handleSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void onGameMessage(
            ChatListener instance, Component message, boolean remote, Operation<Void> original) {
        ChatRecv chatRecv = ChatRecv.parseSystemMessage(message);
        Event<ChatRecv> chatRecvEvent = new Event<>(chatRecv, true, false);
        Listener.getChatMessageReceive().handleValue(chatRecvEvent);
        if (chatRecvEvent.isCancelled()) {
            return;
        }
        original.call(instance, chatRecvEvent.context.text(), remote);
    }

    @Unique
    private final Map<Integer, Vec3> sfh$trackedPosBefore = new HashMap<>();

    @Inject(method = "handleMoveEntity", at = @At("HEAD"))
    private void sfh$captureMovedEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        if (!packet.hasPosition()) return;
        Entity entity = packet.getEntity(Minecraft.getInstance().level);
        if (entity != null) {
            sfh$trackedPosBefore.put(entity.getId(), entity.position());
        }
    }

    @Inject(method = "handleMoveEntity", at = @At("RETURN"))
    private void sfh$broadcastMovedEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        if (!packet.hasPosition()) return;
        Entity entity = packet.getEntity(Minecraft.getInstance().level);
        if (entity != null) {
            sfh$publishTrackedPosition(entity);
        }
    }

    @Inject(method = "handleEntityPositionSync", at = @At("HEAD"))
    private void sfh$captureSyncedEntity(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        Entity entity = sfh$entityById(packet.id());
        if (entity != null) {
            sfh$trackedPosBefore.put(entity.getId(), entity.position());
        }
    }

    @Inject(method = "handleEntityPositionSync", at = @At("RETURN"))
    private void sfh$broadcastSyncedEntity(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        Entity entity = sfh$entityById(packet.id());
        if (entity != null) {
            sfh$publishTrackedPosition(entity);
        }
    }

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"))
    private void sfh$captureTeleportedEntity(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        Entity entity = sfh$entityById(packet.id());
        if (entity != null) {
            sfh$trackedPosBefore.put(entity.getId(), entity.position());
        }
    }

    @Inject(method = "handleTeleportEntity", at = @At("RETURN"))
    private void sfh$broadcastTeleportedEntity(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        Entity entity = sfh$entityById(packet.id());
        if (entity != null) {
            sfh$publishTrackedPosition(entity);
        }
    }

    @Unique
    private Entity sfh$entityById(int entityId) {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : level.getEntity(entityId);
    }

    @Unique
    private void sfh$publishTrackedPosition(Entity entity) {
        Vec3 before = sfh$trackedPosBefore.remove(entity.getId());
        if (before != null && !before.equals(entity.position())) {
            Listener.getEntityTrackedPositionUpdate().broadcast(new PosUpdate(before, entity.position(), entity));
        }
    }
}
