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
import me.matl114.managers.input.KeyCode;
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
import net.minecraft.client.player.ClientInput;

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
        registerListener(Listener.getPlayerKeyboardInputTick(), this::onInputTick);
    }

    /**
     * 界面打开时按物理键状态逐帧同步移动键。
     *
     * <p>只接管“有界面”的帧：没有界面时原版按键事件已经把这些映射维护好了，
     * 这里再写一遍会与它们打架；界面里若有输入框聚焦（{@link #skip()}），
     * 一律置为松开，避免字符输入时角色乱跑。
     */
    public void onInputTick(Event<ClientInput> event) {
        if (checkNull()) return;
        Screen screen = ClientUtils.getScreen(mc);
        if (screen == null) return;
        boolean allowed = enable.get() && !skip();
        initBinding();
        for (KeyMapping re : getBindings()) {
            re.setDown(allowed && KeyCode.isKeyDown(re.key.getValue()));
        }
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
