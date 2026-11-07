package me.matl114.managers.input;

import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nullable;
import me.matl114.utils.Debug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;

public class KeyCode {
    private static final Int2ObjectOpenHashMap<String> MAP_KEY_TO_NAME = new Int2ObjectOpenHashMap<>();
    private static final Object2IntOpenHashMap<String> MAP_NAME_TO_KEY = new Object2IntOpenHashMap<>();

    public static final int KEY_NONE = InputConstants.UNKNOWN.getValue();

    public static final int KEY_SPACE = InputConstants.KEY_SPACE;
    public static final int KEY_APOSTROPHE = InputConstants.KEY_APOSTROPHE;
    public static final int KEY_COMMA = InputConstants.KEY_COMMA;
    public static final int KEY_MINUS = InputConstants.KEY_MINUS;
    public static final int KEY_PERIOD = InputConstants.KEY_PERIOD;
    public static final int KEY_SLASH = InputConstants.KEY_SLASH;
    public static final int KEY_0 = InputConstants.KEY_0;
    public static final int KEY_1 = InputConstants.KEY_1;
    public static final int KEY_2 = InputConstants.KEY_2;
    public static final int KEY_3 = InputConstants.KEY_3;
    public static final int KEY_4 = InputConstants.KEY_4;
    public static final int KEY_5 = InputConstants.KEY_5;
    public static final int KEY_6 = InputConstants.KEY_6;
    public static final int KEY_7 = InputConstants.KEY_7;
    public static final int KEY_8 = InputConstants.KEY_8;
    public static final int KEY_9 = InputConstants.KEY_9;
    public static final int KEY_SEMICOLON = InputConstants.KEY_SEMICOLON;
    public static final int KEY_EQUAL = InputConstants.KEY_EQUALS;
    public static final int KEY_A = InputConstants.KEY_A;
    public static final int KEY_B = InputConstants.KEY_B;
    public static final int KEY_C = InputConstants.KEY_C;
    public static final int KEY_D = InputConstants.KEY_D;
    public static final int KEY_E = InputConstants.KEY_E;
    public static final int KEY_F = InputConstants.KEY_F;
    public static final int KEY_G = InputConstants.KEY_G;
    public static final int KEY_H = InputConstants.KEY_H;
    public static final int KEY_I = InputConstants.KEY_I;
    public static final int KEY_J = InputConstants.KEY_J;
    public static final int KEY_K = InputConstants.KEY_K;
    public static final int KEY_L = InputConstants.KEY_L;
    public static final int KEY_M = InputConstants.KEY_M;
    public static final int KEY_N = InputConstants.KEY_N;
    public static final int KEY_O = InputConstants.KEY_O;
    public static final int KEY_P = InputConstants.KEY_P;
    public static final int KEY_Q = InputConstants.KEY_Q;
    public static final int KEY_R = InputConstants.KEY_R;
    public static final int KEY_S = InputConstants.KEY_S;
    public static final int KEY_T = InputConstants.KEY_T;
    public static final int KEY_U = InputConstants.KEY_U;
    public static final int KEY_V = InputConstants.KEY_V;
    public static final int KEY_W = InputConstants.KEY_W;
    public static final int KEY_X = InputConstants.KEY_X;
    public static final int KEY_Y = InputConstants.KEY_Y;
    public static final int KEY_Z = InputConstants.KEY_Z;
    public static final int KEY_LEFT_BRACKET = InputConstants.KEY_LBRACKET;
    public static final int KEY_BACKSLASH = InputConstants.KEY_BACKSLASH;
    public static final int KEY_RIGHT_BRACKET = InputConstants.KEY_RBRACKET;
    public static final int KEY_GRAVE_ACCENT = InputConstants.KEY_GRAVE;
    public static final int KEY_WORLD_1 = InputConstants.getKey("key.keyboard.world.1").getValue();
    public static final int KEY_WORLD_2 = InputConstants.getKey("key.keyboard.world.2").getValue();
    public static final int KEY_ESCAPE = InputConstants.KEY_ESCAPE;
    public static final int KEY_ENTER = InputConstants.KEY_RETURN;
    public static final int KEY_TAB = InputConstants.KEY_TAB;
    public static final int KEY_BACKSPACE = InputConstants.KEY_BACKSPACE;
    public static final int KEY_INSERT = InputConstants.KEY_INSERT;
    public static final int KEY_DELETE = InputConstants.KEY_DELETE;
    public static final int KEY_RIGHT = InputConstants.KEY_RIGHT;
    public static final int KEY_LEFT = InputConstants.KEY_LEFT;
    public static final int KEY_DOWN = InputConstants.KEY_DOWN;
    public static final int KEY_UP = InputConstants.KEY_UP;
    public static final int KEY_PAGE_UP = InputConstants.KEY_PAGEUP;
    public static final int KEY_PAGE_DOWN = InputConstants.KEY_PAGEDOWN;
    public static final int KEY_HOME = InputConstants.KEY_HOME;
    public static final int KEY_END = InputConstants.KEY_END;
    public static final int KEY_CAPS_LOCK = InputConstants.KEY_CAPSLOCK;
    public static final int KEY_SCROLL_LOCK = InputConstants.KEY_SCROLLLOCK;
    public static final int KEY_NUM_LOCK = InputConstants.KEY_NUMLOCK;
    public static final int KEY_PRINT_SCREEN = InputConstants.KEY_PRINTSCREEN;
    public static final int KEY_PAUSE = InputConstants.KEY_PAUSE;
    public static final int KEY_F1 = InputConstants.KEY_F1;
    public static final int KEY_F2 = InputConstants.KEY_F2;
    public static final int KEY_F3 = InputConstants.KEY_F3;
    public static final int KEY_F4 = InputConstants.KEY_F4;
    public static final int KEY_F5 = InputConstants.KEY_F5;
    public static final int KEY_F6 = InputConstants.KEY_F6;
    public static final int KEY_F7 = InputConstants.KEY_F7;
    public static final int KEY_F8 = InputConstants.KEY_F8;
    public static final int KEY_F9 = InputConstants.KEY_F9;
    public static final int KEY_F10 = InputConstants.KEY_F10;
    public static final int KEY_F11 = InputConstants.KEY_F11;
    public static final int KEY_F12 = InputConstants.KEY_F12;
    public static final int KEY_F13 = InputConstants.KEY_F13;
    public static final int KEY_F14 = InputConstants.KEY_F14;
    public static final int KEY_F15 = InputConstants.KEY_F15;
    public static final int KEY_F16 = InputConstants.KEY_F16;
    public static final int KEY_F17 = InputConstants.KEY_F17;
    public static final int KEY_F18 = InputConstants.KEY_F18;
    public static final int KEY_F19 = InputConstants.KEY_F19;
    public static final int KEY_F20 = InputConstants.KEY_F20;
    public static final int KEY_F21 = InputConstants.KEY_F21;
    public static final int KEY_F22 = InputConstants.KEY_F22;
    public static final int KEY_F23 = InputConstants.KEY_F23;
    public static final int KEY_F24 = InputConstants.KEY_F24;
    // 26.3 的键盘映射表没有 F25
    //#if MC >= 260300
    //$$ public static final int KEY_F25 = KEY_NONE;
    //#else
    public static final int KEY_F25 = InputConstants.KEY_F25;
    //#endif
    public static final int KEY_KP_0 = InputConstants.KEY_NUMPAD0;
    public static final int KEY_KP_1 = InputConstants.KEY_NUMPAD1;
    public static final int KEY_KP_2 = InputConstants.KEY_NUMPAD2;
    public static final int KEY_KP_3 = InputConstants.KEY_NUMPAD3;
    public static final int KEY_KP_4 = InputConstants.KEY_NUMPAD4;
    public static final int KEY_KP_5 = InputConstants.KEY_NUMPAD5;
    public static final int KEY_KP_6 = InputConstants.KEY_NUMPAD6;
    public static final int KEY_KP_7 = InputConstants.KEY_NUMPAD7;
    public static final int KEY_KP_8 = InputConstants.KEY_NUMPAD8;
    public static final int KEY_KP_9 = InputConstants.KEY_NUMPAD9;
    public static final int KEY_KP_DECIMAL = InputConstants.KEY_NUMPADCOMMA;
    public static final int KEY_KP_DIVIDE = InputConstants.getKey("key.keyboard.keypad.divide").getValue();
    public static final int KEY_KP_MULTIPLY = InputConstants.KEY_MULTIPLY;
    public static final int KEY_KP_SUBTRACT = InputConstants.getKey("key.keyboard.keypad.subtract").getValue();
    public static final int KEY_KP_ADD = InputConstants.KEY_ADD;
    public static final int KEY_KP_ENTER = InputConstants.KEY_NUMPADENTER;
    public static final int KEY_KP_EQUAL = InputConstants.KEY_NUMPADEQUALS;
    public static final int KEY_LEFT_SHIFT = InputConstants.KEY_LSHIFT;
    public static final int KEY_LEFT_CONTROL = InputConstants.KEY_LCONTROL;
    public static final int KEY_LEFT_ALT = InputConstants.KEY_LALT;
    public static final int KEY_LEFT_SUPER = InputConstants.getKey("key.keyboard.left.win").getValue();
    public static final int KEY_RIGHT_SHIFT = InputConstants.KEY_RSHIFT;
    public static final int KEY_RIGHT_CONTROL = InputConstants.KEY_RCONTROL;
    public static final int KEY_RIGHT_ALT = InputConstants.KEY_RALT;
    public static final int KEY_RIGHT_SUPER = InputConstants.getKey("key.keyboard.right.win").getValue();
    public static final int KEY_MENU = InputConstants.getKey("key.keyboard.menu").getValue();

    public static final int MOUSE_BUTTON_1 = InputConstants.MOUSE_BUTTON_LEFT - 100;
    // 26.2 的鼠标键号是 0 基序号（1=左 2=右 3=中），26.3 的 SDL 是 1 基（1=左 2=中 3=右），按下标而不是按物理键对齐
    //#if MC >= 260300
    //$$ public static final int MOUSE_BUTTON_2 = InputConstants.MOUSE_BUTTON_MIDDLE - 100;
    //$$ public static final int MOUSE_BUTTON_3 = InputConstants.MOUSE_BUTTON_RIGHT - 100;
    //#else
    public static final int MOUSE_BUTTON_2 = InputConstants.MOUSE_BUTTON_RIGHT - 100;
    public static final int MOUSE_BUTTON_3 = InputConstants.MOUSE_BUTTON_MIDDLE - 100;
    //#endif
    public static final int MOUSE_BUTTON_4 = InputConstants.MOUSE_BUTTON_4 - 100;
    public static final int MOUSE_BUTTON_5 = InputConstants.MOUSE_BUTTON_5 - 100;
    public static final int MOUSE_BUTTON_6 = InputConstants.MOUSE_BUTTON_6 - 100;
    public static final int MOUSE_BUTTON_7 = InputConstants.MOUSE_BUTTON_7 - 100;
    public static final int MOUSE_BUTTON_8 = InputConstants.MOUSE_BUTTON_8 - 100;

    /**
     * 26.2 查键状态要连窗口句柄一起传，26.3 的键值本身就是 SDL scancode，直接查键盘状态表。
     */
    public static boolean isKeyDown(int keyCode) {
        //#if MC >= 260300
        //$$ return InputConstants.isKeyDown(keyCode);
        //#else
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), keyCode);
        //#endif
    }

    /**
     * 两版都没有轮询任意鼠标键的公开接口：1~3 号键读 MouseHandler 记的三个标准键状态，
     * 其余键只能用 MouseHandler 记录的“当前按下的那个键”兜底（同时按多个键时只认最后一个）。
     */
    public static boolean isMouseButtonDown(int button) {
        MouseHandler mouse = Minecraft.getInstance().mouseHandler;
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            return mouse.isLeftPressed();
        }
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            return mouse.isRightPressed();
        }
        if (button == InputConstants.MOUSE_BUTTON_MIDDLE) {
            return mouse.isMiddlePressed();
        }
        MouseButtonInfo active = mouse.activeButton;
        return active != null && active.button() == button;
    }

    /**
     * KeyEvent 的第二个数字字段：26.2 是扫描码，26.3 是键码。
     * 26.2 要拿扫描码只能反查窗口库的键值表，那套接口在 26.3 已被移除，这里给 0：
     * MC 只在键值为 UNKNOWN 或绑定的是 SCANCODE 类型时才读这个字段，正常按键用不到。
     */
    public static int getKeyEventSecondaryCode(int keyCode) {
        //#if MC >= 260300
        //$$ return org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode(keyCode, (short) 0, false);
        //#else
        return 0;
        //#endif
    }

    @Nullable
    public static String getNameForKey(int keyCode) {
        return MAP_KEY_TO_NAME.get(keyCode);
    }

    public static int getKeyCodeFromName(String name) {
        return MAP_NAME_TO_KEY.getInt(name);
    }

    /**
     * 26.3 的 {@code MouseButtonInfo.button()} 是 SDL 的 1 基序号（1=左 2=中 3=右），
     * 而 GUI 内部（{@code InputHandler}、拖拽、框选）一律按 GLFW 的 0 基比较，进出 GUI 时统一转换。
     *
     * <p>SDL 与 GLFW 的<b>中键/右键次序相反</b>（SDL 中=2、右=3；GLFW 右=1、中=2），
     * 不能整体减一：那样右键会变成 2（被当成中键），右键拖拽、右键菜单全部失效。
     */
    public static int toGlfwMouseButton(int button) {
        //#if MC >= 260300
        //$$ return switch (button) {
        //$$     case 1 -> 0;
        //$$     case 2 -> 2;
        //$$     case 3 -> 1;
        //$$     default -> button - 1;
        //$$ };
        //#else
        return button;
        //#endif
    }

    /**
     * 反向转换：把 GUI 内部的 GLFW 0 基键号还原成 26.3 的 SDL 1 基序号，
     * 供重新构造 {@link net.minecraft.client.input.MouseButtonInfo} 或键码（SDL-100）时使用。
     */
    public static int toSdlMouseButton(int button) {
        //#if MC >= 260300
        //$$ return switch (button) {
        //$$     case 0 -> 1;
        //$$     case 1 -> 3;
        //$$     case 2 -> 2;
        //$$     default -> button + 1;
        //$$ };
        //#else
        return button;
        //#endif
    }

    public static int getKeyCodeFromMouseAction(int button) {
        return button - 100;
    }

    public static void init() {}

    public static Map<String, Integer> getKeyMap() {
        return Collections.unmodifiableMap(MAP_NAME_TO_KEY);
    }

    static {
        MAP_NAME_TO_KEY.defaultReturnValue(KEY_NONE);

        for (Field field : KeyCode.class.getDeclaredFields()) {
            try {
                String name = field.getName();
                int keyCode = KEY_NONE;

                if (name.startsWith("KEY_")) {
                    name = name.substring(4);
                    keyCode = field.getInt(null);
                } else if (name.startsWith("MOUSE_")) {
                    name = name.substring(6);
                    keyCode = field.getInt(null);
                }

                if (keyCode != KEY_NONE) {
                    MAP_KEY_TO_NAME.put(keyCode, name);
                    MAP_NAME_TO_KEY.put(name, keyCode);
                }
            } catch (Exception e) {
                Debug.info("Failed to initialize the key name lookup!", e);
            }
        }
    }
}
