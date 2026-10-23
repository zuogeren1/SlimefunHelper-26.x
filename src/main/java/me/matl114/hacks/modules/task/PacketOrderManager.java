package me.matl114.hacks.modules.task;

import me.matl114.accessors.access.PlayerMoveC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hooks.ViaFabricPlusHooks;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

public class PacketOrderManager extends BaseModule {
    public boolean swapping;
    public boolean dropping;
    public boolean interacting;
    public boolean attacking;
    public boolean releasing;
    public boolean digging;
    public boolean sprinting;
    public boolean placing;
    public boolean using;
    public boolean startingToGlide;
    public static PacketOrderManager INSTANCE;

    public PacketOrderManager() {
        super("PacketOrderManager");
        INSTANCE = this;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundAttackPacket.class), this::onAttack, Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundInteractPacket.class),
                this::onInteract,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemOnPacket.class),
                this::onInteractBlock,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class),
                this::onPlayerAction,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerCommandPacket.class),
                this::onEntityAction,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundMovePlayerPacket.class),
                this::onPacketTick,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundClientTickEndPacket.class),
                this::onTickEnd,
                Integer.MAX_VALUE);
    }

    public void onAttack(Event<ServerboundAttackPacket> event) {
        // 26.2: 攻击语义由独立的 ServerboundAttackPacket 承载
        attacking = true;
    }

    public void onInteract(Event<ServerboundInteractPacket> event) {
        interacting = true;
    }

    public void onInteractBlock() {
        placing = true;
    }

    public void onPlayerAction(Event<ServerboundPlayerActionPacket> event) {
        switch (event.context.getAction()) {
            case SWAP_ITEM_WITH_OFFHAND -> swapping = true;
            case DROP_ITEM, DROP_ALL_ITEMS -> dropping = true;
            case RELEASE_USE_ITEM -> releasing = true;
            case STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, START_DESTROY_BLOCK -> digging = true;
        }
    }

    public void onEntityAction(Event<ServerboundPlayerCommandPacket> event) {
        switch (event.context.getAction()) {
            case START_SPRINTING, STOP_SPRINTING -> {
                if (!mc.player.isPassenger()) {
                    sprinting = true;
                }
            }
            case START_FALL_FLYING -> startingToGlide = true;
        }
    }

    boolean lastTickMove;

    public void onTick() {
        swapping = false;
        dropping = false;
        attacking = false;
        interacting = false;
        releasing = false;
        digging = false;
        placing = false;
        using = false;
        sprinting = false;
        startingToGlide = false;
    }

    public void onPacketTick(Event<ServerboundMovePlayerPacket> event) {
        lastTickMove = true;
        PlayerMoveC2SPacketAccess.Cause cause =
                PlayerMoveC2SPacketAccess.of(event.context).getCause();
        if (cause != PlayerMoveC2SPacketAccess.Cause.SET_BACK && cause != PlayerMoveC2SPacketAccess.Cause.LEGACY_SNAP) {
            onTick();
        }
    }

    public void onTickEnd() {
        if (lastTickMove) {
            lastTickMove = false;
        } else {
            if (ViaFabricPlusHooks.isSupportEndTick()) {
                onTick();
            }
        }
    }
}
