package me.matl114.jsApi;

import com.mojang.blaze3d.platform.InputConstants;
import me.matl114.utils.ClientUtils;

import me.matl114.managers.input.KeyCode;
import me.matl114.managers.input.SimpleInputManager;
import me.matl114.utils.ApiMethod;
import me.matl114.utils.ScreenUtils;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

@ApiMethod
public class InputHelper {
    static final Minecraft mc = Minecraft.getInstance();

    public static KeyboardHandler getKeyboard() {
        return mc.keyboardHandler;
    }

    public static Class<?> INPUT_CONSTANTS = InputConstants.class;

    public static void keyAction(int key, int action) {
        keyAction(key, action, ScreenUtils.getCurrentModifiers());
    }

    public static void keyAction(int key, int action, int modifiers) {
        keyAction(key, KeyCode.getKeyEventSecondaryCode(key), action, modifiers);
    }
    /**
     * 处理键盘按键事件的方法。
     * @param key       按键的键值常量，如{@code InputConstants.KEY_A}、
     *                  {@code InputConstants.KEY_SPACE}等。这些常量定义了键盘上每个按键的唯一标识。
     * @param scancode  按键的次级标识，取值由{@code KeyCode#getKeyEventSecondaryCode}按版本给出：
     *                  26.2 是物理扫描码，由键盘硬件生成，与操作系统和键盘布局无关（QWERTY的"A"键和AZERTY的"Q"键物理位置相同，扫描码也相同）；
     *                  26.3 是键码，因为那边的键值本身就是扫描码。
     * @param action    按键动作类型。指示按键是被按下、释放还是重复触发。
     *                  可能的取值：
     *                  - {@code InputConstants.PRESS}   (1): 按键被按下
     *                  - {@code InputConstants.RELEASE} (0): 按键被释放
     *                  - {@code InputConstants.REPEAT}: 按键被按住并重复触发（两版取值不同，不要按字面量比较）
     *
     * @param modifiers 修饰键的状态标志位。表示事件发生时哪些修饰键被同时按住。
     *                  这是一个按位组合的标志，可以通过位运算检查各个修饰键的状态。
     *                  常用修饰键常量：
     *                  - {@code InputConstants.MOD_SHIFT}:     Shift键被按住
     *                  - {@code InputConstants.MOD_CONTROL}:   Ctrl键被按住
     *                  - {@code InputConstants.MOD_ALT}:       Alt/Option键被按住
     *                  - {@code InputConstants.MOD_SUPER}:     Windows/Command键被按住
     *                  - {@code InputConstants.MOD_CAPS_LOCK}: 大写锁定开启
     *                  - {@code InputConstants.MOD_NUM_LOCK}:  数字锁定开启
     *                  示例：检查Ctrl+Shift组合
     *                  {@code if ((modifiers & (InputConstants.MOD_CONTROL | InputConstants.MOD_SHIFT)) != 0)}
     *
     * @threadSafety 内部会自动切换到Minecraft主线程执行。
     */
    public static void keyAction(int key, int scancode, int action, int modifiers) {
        mc.execute(() -> {
            mc.keyboardHandler.keyPress(mc.getWindow().handle(), action, new KeyEvent(key, scancode, modifiers));
        });
    }

    public static void charAction(String str) {
        charAction(str);
    }

    public static void charAction(int str) {
        charAction(str, ScreenUtils.getCurrentModifiers());
    }

    public static void charAction(String chr, int modifiers) {
        charAction((int) chr.charAt(0), modifiers);
    }

    public static void charAction(int codePoint, int modifiers) {
        mc.execute(() -> {
            GuiEventListener element = ClientUtils.getScreen(mc);
            if (element != null && ClientUtils.getOverlay(mc) == null) {
                if (Character.charCount(codePoint) == 1) {
                    ScreenUtils.wrapScreenError(
                            () -> {
                                element.charTyped(new CharacterEvent(codePoint));
                            },
                            "charTyped event handler",
                            element.getClass().getCanonicalName());
                } else {
                    char[] var6 = Character.toChars(codePoint);
                    int var7 = var6.length;

                    for (int var8 = 0; var8 < var7; ++var8) {
                        char c = var6[var8];
                        ScreenUtils.wrapScreenError(
                                () -> {
                                    element.charTyped(new CharacterEvent(c));
                                },
                                "charTyped event handler",
                                element.getClass().getCanonicalName());
                    }
                }
            }
        });
    }

    public int getKeyCode(String keyName) {
        return KeyCode.getKeyCodeFromName("KEY_" + keyName.toUpperCase());
    }

    public int getMouseButtonCode(String mouseButtonName) {
        return KeyCode.getKeyCodeFromName("MOUSE_BUTTON_" + mouseButtonName.toUpperCase());
    }

    public int getCode(String name) {
        return KeyCode.getKeyCodeFromName(name);
    }

    public String getKeyName(int keyCode) {
        return KeyCode.getNameForKey(keyCode);
    }

    public static boolean hasShiftDown() {
        return SimpleInputManager.getInstance().isKeyPressed(KeyCode.KEY_LEFT_SHIFT)
                || SimpleInputManager.getInstance().isKeyPressed(KeyCode.KEY_RIGHT_SHIFT);
    }

    public static boolean hasCtrlDown() {
        return SimpleInputManager.getInstance().isKeyPressed(KeyCode.KEY_LEFT_CONTROL)
                || SimpleInputManager.getInstance().isKeyPressed(KeyCode.KEY_RIGHT_CONTROL);
    }

    public static boolean hasAltDown() {
        return SimpleInputManager.getInstance().isKeyPressed(KeyCode.KEY_LEFT_ALT)
                || SimpleInputManager.getInstance().isKeyPressed(KeyCode.KEY_RIGHT_ALT);
    }

    public static boolean hasEnterDown() {
        return SimpleInputManager.getInstance().isKeyPressed(KeyCode.KEY_ENTER)
                || SimpleInputManager.getInstance().isKeyPressed(355);
    }

    public static boolean hasKeyPressed(int keyCode) {
        return SimpleInputManager.getInstance().isKeyPressed(keyCode);
    }
}
