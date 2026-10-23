package me.matl114.hacks.modules.extra;

import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.CombatTasks;
import me.matl114.hacks.MainTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.InventoryUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Items;

public class AutoLogout extends BaseModule {
    public final ModulePath autoLogout = makePath(Configs.EXTRA_CONFIG, "auto-logout");

    public AutoLogout() {
        super("AutoLogout");
        bindFlag(enable);
    }

    // 总开关
    public final FlagRef enable = flagBuilder(autoLogout.add("enable")).build();

    public final KeyBindRef keyBindRef = moduleEntry(
                    autoLogout.add("hotkey"), new MultiKeyBind(), autoLogout.add("enable"))
            .build();

    // 1. 血量相关
    public final FlagRef healthEnable =
            flagBuilder(autoLogout.add("health").add("enable")).build();
    public final IntRef healthThreshold = builder(autoLogout.add("health").add("threshold"), IntRef.TYPE)
            .defaultValue(4)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    // 2. 不死图腾剩余数量相关
    public final FlagRef totemLeftEnable =
            flagBuilder(autoLogout.add("totem-left").add("enable")).build();
    public final IntRef totemLeftThreshold = builder(
                    autoLogout.add("totem-left").add("threshold"), IntRef.TYPE)
            .defaultValue(1)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    // 3. 图腾触发时自动退出
    public final FlagRef totemTriggerEnable =
            flagBuilder(autoLogout.add("totem-trigger").add("enable")).build();

    public final IntRef totemTriggerCheckLeftTotem = builder(
                    autoLogout.add("totem-trigger").add("threshold"), IntRef.TYPE)
            .defaultValue(99)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    // 4. 最低高度相关
    public final FlagRef minHeightEnable =
            flagBuilder(autoLogout.add("min-height").add("enable")).build();
    public final IntRef minHeightThreshold = builder(
                    autoLogout.add("min-height").add("threshold"), IntRef.TYPE)
            .defaultValue(256)
            .build();

    // 5. 陌生玩家出现
    public final FlagRef strangerPlayerEnable =
            flagBuilder(autoLogout.add("stranger-player").add("enable")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostGameTick(), this::onTick);
        registerListener(Listener.getPacketPoint().getChannel(ClientboundEntityEventPacket.class), this::onPacketTotem);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundAddEntityPacket.class), this::onPlayerSpawn);
    }

    public void onTick(Event<LocalPlayer> event) {
        if (enable.get()) {
            LocalPlayer player = event.context;
            if (healthEnable.get() && player.getHealth() <= healthThreshold.get()) {
                MainTasks.scheduleDisconnect();
                return;
            }
            if (totemLeftEnable.get()) {
                double cnt = InventoryUtils.computePlayerInventory(
                        (v) -> v.getItem() == Items.TOTEM_OF_UNDYING ? (double) v.getCount() : null, false);
                if (cnt <= totemLeftThreshold.get()) {
                    MainTasks.scheduleDisconnect();
                    return;
                }
            }
            if (minHeightEnable.get() && player.getY() < minHeightThreshold.get()) {
                minHeightEnable.set(false);
                MainTasks.scheduleDisconnect();
                return;
            }
        }
    }

    public void onPacketTotem(Event<ClientboundEntityEventPacket> event) {
        ClientboundEntityEventPacket statusS2CPacket = event.context();
        if (enable.get()
                && totemTriggerEnable.get()
                && statusS2CPacket.getEventId() == EntityEvent.PROTECTED_FROM_DEATH
                && mc.level != null
                && mc.player != null) {
            Entity entity = statusS2CPacket.getEntity(mc.level);
            if (entity != null && entity.getId() == mc.player.getId()) {
                int leftTotem = totemTriggerCheckLeftTotem.get();
                // minus one, because one is going to consume
                double cnt = InventoryUtils.computePlayerInventory(
                                (v) -> v.getItem() == Items.TOTEM_OF_UNDYING ? (double) v.getCount() : null, false)
                        - 1;
                if (cnt <= leftTotem) {
                    MainTasks.scheduleDisconnect();
                    return;
                }
            }
        }
    }

    public void onPlayerSpawn(Event<ClientboundAddEntityPacket> event) {
        if (enable.get() && strangerPlayerEnable.get() && mc.player != null) {
            ClientboundAddEntityPacket spawn = event.context();
            if (spawn.getType() == EntityTypes.PLAYER && spawn.getId() != mc.player.getId()) {
                Entity entity = mc.level.getEntity(spawn.getId());
                if (entity != null && CombatTasks.getTargetSelector().isNotFriend(entity)) {
                    MainTasks.scheduleDisconnect();
                    strangerPlayerEnable.set(false);
                }
            }
        }
    }
}
