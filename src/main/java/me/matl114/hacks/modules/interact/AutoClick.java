package me.matl114.hacks.modules.interact;

import me.matl114.accessors.hacks.KeyBindAccess;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.tasks.TimerExecutor;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import net.minecraft.client.KeyMapping;

public class AutoClick extends BaseModule {
    public AutoClick() {
        super("AutoClick");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.INTERACT_CONFIG, "interaction-tweaks.auto-click");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final FlagRef onlyWhenKeyPause = builder(root.add("only-when-press"), Boolean.class)
            .defaultValue(true)
            .build();

    public final IntRef cd = intBuilder(root.add("cooldown"))
            .defaultValue(0)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final FlagRef enableLeft = flagBuilder(root.add("left")).build();

    public final FlagRef enableRight = flagBuilder(root.add("right")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onPreInputEvent);
        registerListener(Listener.getPostHandleInputEvents(), this::onPostInputEvent);
    }

    TimerExecutor cooldown = new TimerExecutor();

    boolean workLeft;
    boolean workRight;

    public void onPreInputEvent() {
        if (enable.get()) {
            if (cooldown.run(cd.get())) {
                if (onlyWhenKeyPause.get()) {
                    if (enableLeft.get() && mc.options.keyAttack.isDown()) {
                        workLeft = true;
                    }
                    if (enableRight.get() && mc.options.keyUse.isDown()) {
                        workRight = true;
                    }
                } else {
                    workLeft = enableLeft.get();
                    workRight = enableRight.get();
                }
                if (workLeft) {
                    ac(mc.options.keyAttack);
                }
                if (workRight) {
                    ac(mc.options.keyUse);
                }
            }
        }
    }

    private void ac(KeyMapping keyBinding) {
        keyBinding.setDown(true);
        if (keyBinding.clickCount <= 0) {
            keyBinding.clickCount = 1;
        }
    }

    public void onPostInputEvent() {
        if (workLeft) {
            KeyBindAccess.of(mc.options.keyAttack).resetKeyState();
            workLeft = false;
        }
        if (workRight) {
            KeyBindAccess.of(mc.options.keyUse).resetKeyState();
            workRight = false;
        }
    }
}
