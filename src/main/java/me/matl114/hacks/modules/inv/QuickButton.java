package me.matl114.hacks.modules.inv;

import com.google.common.util.concurrent.Runnables;
import java.util.*;
import me.matl114.accessors.access.HandledScreenAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.gui.basic.*;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.slimefun.SlimefunGuide;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.managers.Configs;
import me.matl114.managers.TaskManagers;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.ListRef;
import me.matl114.utils.config.ValueAccessor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;

public class QuickButton extends BaseModule {
    public final ModulePath quickButtons = makePath(Configs.INV_CONFIG, "quick-buttons");
    public final FlagRef enable =
            flagBuilder(quickButtons.add("enable-buttons")).build();

    public QuickButton() {
        super("QuickButton");
        bindFlag(enable);
    }

    public static int resizeCreativeYv(int y) {
        return y - 30;
    }

    public final ListRef taskList = builder(quickButtons.add("button-tasks"), ListRef.TYPE)
            .defaultValue(List.of(InvExtra.CLEAR_KEEP, FastInv.TAKE_ALL, FastInv.SAVE_ALL, SlimefunGuide.OPEN_GUIDE))
            .build();

    public final ListRef toggleList = builder(quickButtons.add("button-toggles"), ListRef.TYPE)
            .defaultValue(List.of("keep-inv", "fast-inv", "auto-store", "left-one"))
            .build();

    //

    private static final int buttonHeight = 12;

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPostInitializeScreen().getChannel(AbstractContainerScreen.class),
                this::onHandledScreenInitialized);
    }

    public void onHandledScreenInitialized(Event<AbstractContainerScreen<?>> event) {
        if (enable.get()) {
            initButton(event.context);
        }
    }

    public void initButton(AbstractContainerScreen<?> handledScreen) {
        HandledScreenAccess access = HandledScreenAccess.of(handledScreen);
        Map<String, Runnable> buttonTasks = new LinkedHashMap<>();
        for (var re : taskList.get()) {
            Runnable task = TaskManagers.getTaskManager().getTask(TaskManagers.PREFIX_BUTTON_TASKS + "." + re);
            buttonTasks.put(re, task == null ? Runnables.doNothing() : task);
        }
        Map<String, Optional<FlagRef>> buttonToggles = new LinkedHashMap<>();
        for (var re : toggleList.get()) {
            FlagRef flagRef = TaskManagers.getToggleManager().getFlag(TaskManagers.PREFIX_BUTTON_TOGGLE + "." + re);
            buttonToggles.put(re, Optional.ofNullable(flagRef));
        }

        int line = 0;
        int buttonWidth = (access.getScreenBackgroundX() / 4);
        int size1 = buttonTasks.size();
        line += ((size1 - 1) / 4) + 1;
        int size2 = buttonToggles.size();
        line += ((size2 - 1) / 4) + 1;
        int y0 = -(buttonHeight) * line - 6;
        int x0 = 0;
        DynamicSubScreenWidget widgetSet = new DynamicSubScreenWidget(
                ValueAccessor.ofIgnore(access::getScreenX),
                ValueAccessor.of(() -> handledScreen instanceof CreativeModeInventoryScreen
                        ? resizeCreativeYv(access.getScreenY())
                        : access.getScreenY()));
        widgetSet.addTo(handledScreen);
        for (Map.Entry<String, Optional<FlagRef>> entry : buttonToggles.entrySet()) {
            final String key = entry.getKey();
            final FlagRef flagRef = entry.getValue().orElse(null);
            String fullKey = TaskManagers.PREFIX_BUTTON_TOGGLE + "." + key;
            final Runnable stateChange =
                    flagRef != null ? HotKeyUtils.wrapFlagAsToggle(fullKey, flagRef) : Runnables.doNothing();
            ELEMENT_FACTORY
                    .createToggleButton(
                            () -> Component.literal(key),
                            List::of,
                            ValueAccessor.of(() -> flagRef == null || flagRef.get(), (bl) -> {
                                boolean current = flagRef == null || flagRef.get();
                                if (current != bl) {
                                    stateChange.run();
                                }
                            }),
                            x0 * (buttonWidth + 1),
                            y0,
                            buttonWidth,
                            buttonHeight)
                    .addToSub(widgetSet);
            x0 += 1;
            if (x0 == 4) {
                x0 = 0;
                y0 += (buttonHeight + 2);
            }
        }
        // 换行
        x0 = 0;
        y0 = buttonHeight + 2;
        for (Map.Entry<String, Runnable> entry : buttonTasks.entrySet()) {
            final Runnable task = entry.getValue();
            ELEMENT_FACTORY
                    .createExecuteButton(
                            () -> Component.literal(entry.getKey()),
                            List::of,
                            ButtonAction.run(task),
                            x0 * (buttonWidth + 1),
                            -y0,
                            buttonWidth,
                            buttonHeight)
                    .addToSub(widgetSet);
            x0 += 1;
            if (x0 == 4) {
                x0 = 0;
                y0 += (buttonHeight + 2);
            }
        }
    }
}
