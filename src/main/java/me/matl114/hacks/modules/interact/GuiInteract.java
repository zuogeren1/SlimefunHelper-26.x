package me.matl114.hacks.modules.interact;

import me.matl114.hacks.utils.ClientUtils;

import com.google.common.base.Suppliers;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import me.matl114.accessors.access.KeyBindAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.InvTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

public class GuiInteract extends BaseModule {
    public final ModulePath other = makePath(Configs.INTERACT_CONFIG, "interact-fix.gui-interact");

    public GuiInteract() {
        super("GuiInteract");
        bindFlag(enable);
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreSetScreen(), this::onStoreKeyBindState);
        registerListener(Listener.getPostSetScreen(), this::onResetKeyBind);
        registerListener(Listener.getPreHandleInputEvents(), this::onInput, Integer.MIN_VALUE);
    }

    public final Set<KeyMapping> sets = new HashSet<>();
    public final Supplier<KeyMapping[]> sticks = Suppliers.memoize(() -> {
        return new KeyMapping[] {
            mc.options.keyUse, mc.options.keyAttack, mc.options.keySprint,
        };
    });

    public final FlagRef enable =
            builder(other.addEnable(), Boolean.class).defaultValue(false).build();
    public final KeyBindRef hotkey = moduleEntry(other.addHotkey(), new MultiKeyBind(), other.addEnable())
            .build();

    public final FlagRef onlyWhenNoServerScreen =
            flagBuilder(other.add("stop-when-server-screen"), true).build();

    public final FlagRef stopWhenOpenPlayerInv =
            flagBuilder(other.add("stop-when-player-inv"), false).build();

    public final FlagRef useWhenScreenOpen = builder(other.add("use-tick-when-screen-open"), Boolean.class)
            .defaultValue(true)
            .build();

    public void onStoreKeyBindState(Event<Screen> eventPre) {
        sets.clear();
        if (checkNull()) return;
        if (shouldRun() && !eventPre.isCancelled()) {
            for (var re : sticks.get()) {
                if (re.isDown()) {
                    sets.add(re);
                }
            }
        }
    }

    public void onResetKeyBind(Event<Screen> eventPost) {
        if (checkNull()) {
            sets.clear();
            return;
        }
        if (shouldRun()) {
            if (eventPost.context != null) {
                for (var re : sticks.get()) {
                    if (sets.contains(re)) {
                        re.setDown(true);
                    }
                }
            } else {
                for (var re : sticks.get()) {
                    KeyBindAccess.of(re).resetKeyState();
                }
            }
        }
        sets.clear();
    }

    Map<KeyMapping, Boolean> pressingState = new HashMap<>();

    public void onInput(Event<Void> event) {
        if (checkNull()) return;
        if (shouldRun() && ClientUtils.getScreen(mc) != null) {
            if (useWhenScreenOpen.get()) {
                if (event.isCancelled()) {
                    event.cancel(false);
                }
            }

            for (var re : sticks.get()) {
                KeyBindAccess.of(re).resetKeyState();
                // 开界面时按键状态被重置，vanilla 统计不到点击次数；
                // 这里补记按下沿，让界面内交互仍算一次有效点击
                boolean lastState = pressingState.getOrDefault(re, false);
                if (!lastState && re.isDown()) {
                    re.clickCount += 1;
                }
                pressingState.put(re, re.isDown());
            }
        } else {
            for (var re : sticks.get()) {
                pressingState.put(re, re.isDown());
            }
        }
    }

    public boolean shouldRun() {
        if (enable.get()) {
            if (onlyWhenNoServerScreen.get()
                    && InvTasks.getCurrentServerScreenHandler(mc.player).containerId
                            != mc.player.inventoryMenu.containerId) {
                return false;
            }
            if (stopWhenOpenPlayerInv.get()
                    && (ClientUtils.getScreen(mc) instanceof InventoryScreen
                            || ClientUtils.getScreen(mc) instanceof CreativeModeInventoryScreen)) {
                return false;
            }
            return true;
        }
        return false;
    }
}
