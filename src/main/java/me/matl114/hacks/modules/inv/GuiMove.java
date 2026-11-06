package me.matl114.hacks.modules.inv;

import com.mojang.blaze3d.platform.InputConstants;
import me.matl114.utils.ClientUtils;

import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.KeyboardAction;
import me.matl114.gui.WidgetUtils;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.managers.input.SimpleInputManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;

public class GuiMove extends BaseModule {
    public GuiMove() {
        super("GuiMove");
        bindFlag(enable);
    }

    public ModulePath path = makePath(Configs.INV_CONFIG, "inventory.gui-move");

    public final FlagRef enable = flagBuilder(path.addEnable()).build();

    public final KeyBindRef hotkey =
            moduleEntry(path.addHotkey(), new MultiKeyBind(), path.addEnable()).build();

    public final FlagRef allGui = flagBuilder(path.add("all-gui-move")).build();

    public final FlagRef noShiftInChest = builder(path.add("no-shift-in-chest"), FlagRef.TYPE)
            .defaultValue(true)
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getKeyboardInput(), this::onKeyInput);
        registerListener(Listener.getPostSetScreen(), this::onPostSetScreen);
    }

    public KeyMapping[] inputBindings;
    public KeyMapping[] inputBindingsNoSneak;

    private void initBinding() {
        if (inputBindings == null || inputBindingsNoSneak == null) {
            inputBindings = new KeyMapping[] {
                mc.options.keyUp,
                mc.options.keyDown,
                mc.options.keyLeft,
                mc.options.keyRight,
                mc.options.keyJump,
                mc.options.keyShift,
                mc.options.keySprint
            };
            inputBindingsNoSneak = new KeyMapping[] {
                mc.options.keyUp,
                mc.options.keyDown,
                mc.options.keyLeft,
                mc.options.keyRight,
                mc.options.keyJump,
                mc.options.keySprint
            };
        }
    }

    public KeyMapping[] getBindings() {
        initBinding();
        return noShiftInChest.get() && ClientUtils.getScreen(mc) instanceof AbstractContainerScreen<?>
                ? inputBindingsNoSneak
                : inputBindings;
    }

    public void onKeyInput(Event<KeyboardAction> eventInput) {
        if (checkNull()) return;
        if (enable.get()) {
            if (skip()) return;
            int keyCode = eventInput.context.keyCode();
            int action = eventInput.context.action();
            for (var re : getBindings()) {
                if (handle(re, keyCode, action)) {}
            }
        }
    }

    public boolean handle(KeyMapping keyBinding, int keyCode, int action) {
        if (keyBinding.key.getValue() != keyCode) {
            return false;
        }
        if (action == InputConstants.PRESS) {
            keyBinding.setDown(true);
            return true;
        } else if (action == InputConstants.RELEASE) {
            keyBinding.setDown(false);
            return true;
        }
        return false;
    }

    public void onPostSetScreen(Event<Screen> event) {
        if (checkNull()) return;
        if (enable.get() && event.context != null) {
            initBinding();
            for (var re : getBindings()) {
                re.setDown(SimpleInputManager.getInstance().isKeyPressed(re.key.getValue()));
            }
        }
    }

    public boolean skip() {
        if (ClientUtils.getScreen(mc) == null
                || ClientUtils.getScreen(mc) instanceof CreativeModeInventoryScreen
                || ClientUtils.getScreen(mc) instanceof ChatScreen
                || ClientUtils.getScreen(mc) instanceof SignEditScreen
                || ClientUtils.getScreen(mc) instanceof AnvilScreen
                || ClientUtils.getScreen(mc) instanceof CommandBlockEditScreen
                || ClientUtils.getScreen(mc) instanceof StructureBlockEditScreen
                || ClientUtils.getScreen(mc).getFocused() instanceof EditBox
                || (ClientUtils.getScreen(mc).getFocused() instanceof DrawableWidget widget && checkCustomWidget(widget)))
            return true;
        if (allGui.get()) return false;
        return !(ClientUtils.getScreen(mc) instanceof AbstractContainerScreen<?>);
    }

    public boolean checkCustomWidget(DrawableWidget drawableWidget) {
        DrawableWidget drawable = WidgetUtils.getFocusedWidget(drawableWidget);
        return drawable != null && WidgetUtils.isInputWidget(drawable);
    }
}
