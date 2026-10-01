package me.matl114.hacks.modules.inv;

import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.access.LivingEntityAccess;
import me.matl114.accessors.access.PlayerInteractItemC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.InteractUtils;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class InvDesyncFix extends BaseModule {
    public InvDesyncFix() {
        super("InvDesyncFix");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.INV_CONFIG, "inventory-desync-fix");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            toggleHotkey(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final FlagRef useItemFix = flagBuilder(root.add("use-item")).build();

    public final FlagRef finishUseFix = flagBuilder(root.add("finish-use")).build();

    public final FlagRef totemFix = flagBuilder(root.add("totem-fix")).build();

    public final FlagRef fastSync = flagBuilder(root.add("fast-sync")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemPacket.class),
                this::onUseItem,
                Integer.MAX_VALUE - 10);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundContainerSetSlotPacket.class),
                this::fastAsyncUpdateRevision);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundContainerSetContentPacket.class), this::fastAsyncUpdateRevision2);
        registerListener(Listener.getPacketPoint().getChannel(ClientboundEntityEventPacket.class), this::onStatusConsumed);
    }

    private void nextRevision() {
        var serverHandler = ClientPlayerAccess.of(mc.player).getServerScreenHandler();
        serverHandler.incrementStateId();
    }

    public void onUseItem(Event<ServerboundUseItemPacket> event) {
        if (checkNull()) return;
        if (event.isCancelled()) return;
        if (enable.get()
                && useItemFix.get()
                && !LivingEntityAccess.of(mc.player).isTrackedUsingItem()) {
            InteractionHand hand = event.context.getHand();
            ItemStack stack =
                    PlayerInteractItemC2SPacketAccess.of(event.context).getItemStack();
            ItemStack currentStack = mc.player.getItemInHand(hand);
            if (!ItemStack.isSameItemSameComponents(stack, currentStack)
                    || InteractUtils.isInteractAcceptable(mc.level, mc.player, stack)) {
                PacketManager.schedulePostScheduleCallback(event.context, this::nextRevision);
            }
        }
    }

    private void onStatusConsumed(Event<ClientboundEntityEventPacket> event) {
        if (checkNull()) return;
        if (enable.get() && event.context.getEntity(mc.level) == mc.player) {
            if (event.context.getEventId() == EntityEvent.USE_ITEM_COMPLETE && finishUseFix.get()) {
                // abort revision, force desync
                // fix grim shit.
                nextRevision();
                nextRevision();
            }
            if (event.context.getEventId() == EntityEvent.PROTECTED_FROM_DEATH && totemFix.get()) {
                nextRevision();
                nextRevision();
            }
        }
    }

    public void fastAsyncUpdateRevision(Event<ClientboundContainerSetSlotPacket> eventUpdate) {
        if (checkNull()) return;
        int syncId = eventUpdate.context.getContainerId();
        if (enable.get()
                && fastSync.get()
                && mc.gameMode.getPlayerMode().isSurvival()) {
            if (syncId == 0) {
                syncPlayerInventoryRevision(eventUpdate.context.getStateId());
            } else {
                AbstractContainerMenu handler = ClientPlayerAccess.of(mc.player).getServerScreenHandler();
                if (handler.containerId == syncId) {
                    handler.stateId = eventUpdate.context.getStateId();
                }
            }
        }
    }

    public void fastAsyncUpdateRevision2(Event<ClientboundContainerSetContentPacket> eventUpdate) {
        if (checkNull()) return;
        int syncId = eventUpdate.context.containerId();
        if (enable.get()
                && fastSync.get()
                && mc.gameMode.getPlayerMode().isSurvival()) {
            if (syncId == 0) {
                syncPlayerInventoryRevision(eventUpdate.context.stateId());
            } else {
                AbstractContainerMenu handler = ClientPlayerAccess.of(mc.player).getServerScreenHandler();
                if (handler.containerId == syncId) {
                    handler.stateId = eventUpdate.context.stateId();
                }
            }
        }
    }

    public int playerInventoryRevisionManage = 0;

    public void syncPlayerInventoryRevision(int revision) {
        if (playerInventoryRevisionManage < 0) {
            playerInventoryRevisionManage = revision;
        } else {
            int abs = Math.abs(playerInventoryRevisionManage - revision);
            if (abs > 20) {
                playerInventoryRevisionManage = revision;
            } else {
                if (playerInventoryRevisionManage < revision) {
                    playerInventoryRevisionManage = revision;
                }
            }
        }
    }
}
