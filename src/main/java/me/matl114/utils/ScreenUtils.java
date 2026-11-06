package me.matl114.utils;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import me.matl114.accessors.access.HandledScreenAccess;
import me.matl114.events.Listener;
import me.matl114.events.catchers.PacketCatcherImpl;
import me.matl114.managers.input.KeyCode;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.collections.Point;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.InputType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.util.Util;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

@ApiMethod
public class ScreenUtils {
    public static Point getMouseCoord(Minecraft client) {
        return getMouseCoord(client, client.mouseHandler);
    }

    public static Point getMouseCoord(Minecraft client, MouseHandler mouse) {
        Window window = client.getWindow();
        int mouseX = (int) (mouse.xpos() * (double) window.getGuiScaledWidth() / (double) window.getScreenWidth());
        int mouseY = (int) (mouse.ypos() * (double) window.getGuiScaledHeight() / (double) window.getScreenHeight());
        return new Point(mouseX, mouseY);
    }

    private static final Minecraft mc = Minecraft.getInstance();

    public static Slot getSelectingOrHandSlot() {
        if (mc.player == null) return null;
        if (ClientUtils.getScreen(mc) instanceof AbstractContainerScreen<?> s) {
            Point mouseCoord = ScreenUtils.getMouseCoord(mc);
            Slot slot = HandledScreenAccess.of(s).reallyGetSlotAt(mouseCoord.x, mouseCoord.y);
            if (slot != null) {
                return slot;
            }
        } else {
            int selected = InventoryUtils.getSelectedSlot();
            OptionalInt optionalInt = mc.player.inventoryMenu.findSlot(mc.player.getInventory(), selected);
            if (optionalInt.isPresent()) {
                return mc.player.inventoryMenu.getSlot(optionalInt.getAsInt());
            }
        }
        return null;
    }

    public static ItemStack getSelectingOrHandItem() {
        if (mc.player == null) return null;
        if (ClientUtils.getScreen(mc) instanceof AbstractContainerScreen<?> s) {
            Point mouseCoord = ScreenUtils.getMouseCoord(mc);
            Slot slot = HandledScreenAccess.of(s).reallyGetSlotAt(mouseCoord.x, mouseCoord.y);
            if (slot != null) {
                return slot.getItem();
            }
        } else {
            return mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        }
        return null;
    }

    public static CompletableFuture<AbstractContainerScreen<?>> getOpenScreenFuture() {
        int currentSyncId = mc.player.containerMenu.containerId;
        CompletableFuture<AbstractContainerScreen<?>> cf = new CompletableFuture<>();
        Listener.addPostPacketCatcher(new PacketCatcherImpl<>(ClientboundOpenScreenPacket.class, (packetEvent) -> {
            var packet = packetEvent.context();
            int syncId = packet.getContainerId();
            if (currentSyncId != syncId && syncId != 0) {
                if (ClientUtils.getScreen(mc) instanceof AbstractContainerScreen<?> handled) {
                    Listener.addPostPacketCatcher(
                            new PacketCatcherImpl<>(ClientboundContainerSetContentPacket.class, (packet2Event) -> {
                                var packet2 = packet2Event.context();
                                if (packet2.containerId() == syncId) {
                                    // execute immediately after the update of menu
                                    cf.complete(handled);
                                    return true;
                                }
                                return false;
                            }));
                } else {
                    cf.complete(null);
                }
                return true;
            }
            return false;
        }));
        return cf;
    }

    public static IndexEntry<Slot> getSlot(AbstractContainerMenu handler, Container inventory, int index) {
        for (int i = 0; i < handler.slots.size(); ++i) {
            Slot slot = (Slot) handler.slots.get(i);
            if (slot.container == inventory && index == slot.getContainerSlot()) {
                return new IndexEntry<>(i, slot);
            }
        }

        return null;
    }

    public static boolean hasShiftDown() {
        return KeyCode.isKeyDown(KeyCode.KEY_LEFT_SHIFT) || KeyCode.isKeyDown(KeyCode.KEY_RIGHT_SHIFT);
    }

    public static boolean hasCtrlDown() {
        return KeyCode.isKeyDown(KeyCode.KEY_LEFT_CONTROL) || KeyCode.isKeyDown(KeyCode.KEY_RIGHT_CONTROL);
    }

    public static boolean hasAltDown() {
        return KeyCode.isKeyDown(KeyCode.KEY_LEFT_ALT) || KeyCode.isKeyDown(KeyCode.KEY_RIGHT_ALT);
    }

    public static boolean hasEnterDown() {
        return KeyCode.isKeyDown(KeyCode.KEY_ENTER) || KeyCode.isKeyDown(355);
    }

    public static boolean hasKeyPressed(int keyCode) {
        return KeyCode.isKeyDown(keyCode);
    }

    public static boolean isToggle(int keyCode) {
        return keyCode == KeyCode.KEY_ENTER || keyCode == KeyCode.KEY_SPACE || keyCode == KeyCode.KEY_KP_ENTER;
    }

    public static final Map<MenuType<?>, Integer> nonPlayerSlots = Map.ofEntries(
            Map.entry(MenuType.GENERIC_9x1, 9),
            Map.entry(MenuType.GENERIC_9x2, 18),
            Map.entry(MenuType.GENERIC_9x3, 27),
            Map.entry(MenuType.GENERIC_9x4, 36),
            Map.entry(MenuType.GENERIC_9x5, 45),
            Map.entry(MenuType.GENERIC_9x6, 54),
            Map.entry(MenuType.GENERIC_3x3, 9),
            Map.entry(MenuType.CRAFTER_3x3, 9),
            Map.entry(MenuType.ANVIL, 3),
            Map.entry(MenuType.BEACON, 1),
            Map.entry(MenuType.BLAST_FURNACE, 3),
            Map.entry(MenuType.BREWING_STAND, 5),
            Map.entry(MenuType.CRAFTING, 10),
            Map.entry(MenuType.ENCHANTMENT, 2),
            Map.entry(MenuType.FURNACE, 3),
            Map.entry(MenuType.GRINDSTONE, 3),
            Map.entry(MenuType.HOPPER, 5),
            Map.entry(MenuType.LOOM, 4),
            Map.entry(MenuType.MERCHANT, 3),
            Map.entry(MenuType.SHULKER_BOX, 27),
            Map.entry(MenuType.SMITHING, 4), // 1.20+ 锻造台
            Map.entry(MenuType.SMOKER, 3),
            Map.entry(MenuType.CARTOGRAPHY_TABLE, 3),
            Map.entry(MenuType.STONECUTTER, 2));

    public static Integer getTopInventorySize(MenuType<?> type) {
        return nonPlayerSlots.get(type);
    }

    public static MenuType<?> getGenericScreenType(int size) {
        return switch ((size - 1) / 9) {
            case 0 -> MenuType.GENERIC_9x1;
            case 1 -> MenuType.GENERIC_9x2;
            case 2 -> MenuType.GENERIC_9x3;
            case 3 -> MenuType.GENERIC_9x4;
            case 4 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    public static void openChatScreen(String originalText) {
        ChatComponent.ChatMethod method =
                originalText.startsWith("/") ? ChatComponent.ChatMethod.COMMAND : ChatComponent.ChatMethod.MESSAGE;
        ClientUtils.openChatScreen(mc, method);
        if (ClientUtils.getScreen(mc) instanceof ChatScreen chat) {
            chat.insertText(originalText, true);
        }
    }

    public static int getCurrentModifiers() {
        if (mc.getWindow().handle() == 0) {
            return 0;
        }

        int modifiers = 0;

        // 检查 Shift 键
        if (KeyCode.isKeyDown(KeyCode.KEY_LEFT_SHIFT) || KeyCode.isKeyDown(KeyCode.KEY_RIGHT_SHIFT)) {
            modifiers |= InputConstants.MOD_SHIFT;
        }

        // 检查 Control 键
        if (KeyCode.isKeyDown(KeyCode.KEY_LEFT_CONTROL) || KeyCode.isKeyDown(KeyCode.KEY_RIGHT_CONTROL)) {
            modifiers |= InputConstants.MOD_CONTROL;
        }

        // 检查 Alt 键
        if (KeyCode.isKeyDown(KeyCode.KEY_LEFT_ALT) || KeyCode.isKeyDown(KeyCode.KEY_RIGHT_ALT)) {
            modifiers |= InputConstants.MOD_ALT;
        }

        // 检查 Windows/Command 键
        if (KeyCode.isKeyDown(KeyCode.KEY_LEFT_SUPER) || KeyCode.isKeyDown(KeyCode.KEY_RIGHT_SUPER)) {
            modifiers |= InputConstants.MOD_SUPER;
        }

        // 检查 Caps Lock
        if (KeyCode.isKeyDown(KeyCode.KEY_CAPS_LOCK)) {
            modifiers |= InputConstants.MOD_CAPS_LOCK;
        }

        // 检查 Num Lock
        if (KeyCode.isKeyDown(KeyCode.KEY_NUM_LOCK)) {
            modifiers |= InputConstants.MOD_NUM_LOCK;
        }

        return modifiers;
    }

    // internal methods from MCClient

    public static void wrapScreenError(Runnable task, String errorTitle, String screenName) {
        try {
            task.run();
        } catch (Throwable var6) {
            Throwable throwable = var6;
            CrashReport crashReport = CrashReport.forThrowable(throwable, errorTitle);
            CrashReportCategory crashReportSection = crashReport.addCategory("Affected screen");
            crashReportSection.setDetail("Screen name", () -> {
                return screenName;
            });
            throw new ReportedException(crashReport);
        }
    }

    public static void simulateKeyAction(Screen screen, int key, int scancode, int action, int modifiers) {
        if (screen != null) {
            switch (key) {
                case KeyCode.KEY_TAB:
                    mc.setLastInputType(InputType.KEYBOARD_TAB);
                case KeyCode.KEY_BACKSPACE:
                case KeyCode.KEY_INSERT:
                case KeyCode.KEY_DELETE:
                default:
                    break;
                case KeyCode.KEY_RIGHT:
                case KeyCode.KEY_LEFT:
                case KeyCode.KEY_DOWN:
                case KeyCode.KEY_UP:
                    mc.setLastInputType(InputType.KEYBOARD_ARROW);
            }
        }
        KeyEvent keyInput = new KeyEvent(key, scancode, modifiers);
        if (action == InputConstants.PRESS
                && (!(screen instanceof KeyBindsScreen)
                        || ((KeyBindsScreen) screen).lastKeySelection <= Util.getMillis() - 20L)) {
            if (mc.options.keyFullscreen.matches(keyInput)) {
                mc.getWindow().toggleFullScreen();
                mc.options.fullscreen().set(mc.getWindow().isFullscreen());
                return;
            }
        }

        boolean bl3;

        if (screen != null) {
            boolean[] bls = new boolean[] {false};
            wrapScreenError(
                    () -> {
                        if (action != InputConstants.PRESS && action != InputConstants.REPEAT) {
                            if (action == InputConstants.RELEASE) {
                                bls[0] = screen.keyReleased(keyInput);
                            }
                        } else {
                            InputConstants.Key key2;
                            screen.afterKeyboardAction();
                            bls[0] = screen.keyPressed(keyInput);
                            if (bls[0]) {
                                if (ClientUtils.getScreen(mc) == null) {
                                    key2 = InputConstants.getKey(keyInput);
                                    KeyMapping.set(key2, false);
                                }
                            }
                        }
                    },
                    "keyPressed event handler",
                    screen.getClass().getCanonicalName());
            if (bls[0]) {

                return;
            }
        }

        InputConstants.Key key2;
        boolean var10000;
        label184:
        {
            key2 = InputConstants.getKey(keyInput);
            bl3 = screen == null;
            if (!bl3) {
                label180:
                {
                    Screen var13 = screen;
                    if (var13 instanceof PauseScreen) {
                        PauseScreen gameMenuScreen = (PauseScreen) var13;
                        if (!gameMenuScreen.showsPauseMenu()) {
                            break label180;
                        }
                    }

                    var10000 = false;
                    break label184;
                }
            }

            var10000 = true;
        }

        boolean bl4 = var10000;
        if (action == InputConstants.RELEASE) {
            KeyMapping.set(key2, false);

        } else {
            boolean bl5 = KeyCode.isKeyDown(KeyCode.KEY_F3);

            if (bl3) {
                if (bl5) {
                    KeyMapping.set(key2, false);
                } else {
                    KeyMapping.set(key2, true);
                    KeyMapping.click(key2);
                }
            }
        }
    }

    public static void simulateMouseButton(@Nonnull Screen screen, int button, int action, int mods) {
        if (screen != null) {
            mc.setLastInputType(InputType.MOUSE);
        }
        MouseButtonInfo mouseInput = new MouseButtonInfo(button, mods);
        boolean bl = action == InputConstants.PRESS;
        final MouseHandler mouse = mc.mouseHandler;
        //#if MC >= 260300
        //$$ MouseButtonInfo i = mouseInput;
        //#else
        MouseButtonInfo i = mouse.simulateRightClick(mouseInput, bl);
        //#endif
        if (bl) {

            mouse.activeButton = i;
        } else if (mouse.activeButton != null) {

            mouse.activeButton = null;
        }

        boolean[] bls = new boolean[] {false};
        if (ClientUtils.getOverlay(mc) == null) {
            double d = mouse.xpos()
                    * (double) mc.getWindow().getGuiScaledWidth()
                    / (double) mc.getWindow().getScreenWidth();
            double e = mouse.ypos()
                    * (double) mc.getWindow().getGuiScaledHeight()
                    / (double) mc.getWindow().getScreenHeight();
            MouseButtonEvent click = new MouseButtonEvent(d, e, mouseInput);
            if (bl) {
                screen.afterMouseAction();
                wrapScreenError(
                        () -> {
                            long l = Util.getMillis();
                            boolean bl2 = mouse.lastClick != null
                                    && l - mouse.lastClick.time() < 250L
                                    &&
                                    // remove screen check
                                    // mouse.lastMouseClick.screen() == screen &&
                                    mouse.lastClickButton == button;
                            bls[0] = screen.mouseClicked(click, bl2);
                            if (bls[0]) {
                                mouse.lastClick = new MouseHandler.LastClick(l, screen);
                                mouse.lastClickButton = button;
                            }
                        },
                        "mouseClicked event handler",
                        screen.getClass().getCanonicalName());
            } else {
                wrapScreenError(
                        () -> {
                            bls[0] = screen.mouseReleased(click);
                        },
                        "mouseReleased event handler",
                        screen.getClass().getCanonicalName());
            }
        }
    }

    public static void simulateMouseScroll(@Nonnull Screen screen, double horizontal, double vertical) {
        boolean bl = (Boolean) mc.options.discreteMouseScroll().get();
        double d = (Double) mc.options.mouseWheelSensitivity().get();
        double e = (bl ? Math.signum(horizontal) : horizontal) * d;
        double f = (bl ? Math.signum(vertical) : vertical) * d;
        if (ClientUtils.getOverlay(mc) == null) {
            if (screen != null) {
                double g = mc.mouseHandler.xpos()
                        * (double) mc.getWindow().getGuiScaledWidth()
                        / (double) mc.getWindow().getScreenWidth();
                double h = mc.mouseHandler.ypos()
                        * (double) mc.getWindow().getGuiScaledHeight()
                        / (double) mc.getWindow().getScreenHeight();
                screen.mouseScrolled(g, h, e, f);
                screen.afterMouseAction();
            } else if (mc.player != null) {
                // FUCK YOU , IT IS DEPRECATED , GET OUT OF MY WORLD, I DON'T WANT TO EAT SHIT
                //                if (mc.mouse.eventDeltaHorizontalWheel != 0.0 && Math.signum(e) !=
                // Math.signum(mc.mouse.eventDeltaHorizontalWheel)) {
                //                    mc.mouse.eventDeltaHorizontalWheel = 0.0;
                //                }
                //
                //                if (mc.mouse.eventDeltaVerticalWheel != 0.0 && Math.signum(f) !=
                // Math.signum(mc.mouse.eventDeltaVerticalWheel)) {
                //                    mc.mouse.eventDeltaVerticalWheel = 0.0;
                //                }
                //
                //                mc.mouse.eventDeltaHorizontalWheel += e;
                //                mc.mouse.eventDeltaVerticalWheel += f;
                //                int i = (int)mc.mouse.eventDeltaHorizontalWheel;
                //                int j = (int)mc.mouse.eventDeltaVerticalWheel;
                //                if (i == 0 && j == 0) {
                //                    return;
                //                }
                //
                //                mc.mouse.eventDeltaHorizontalWheel -= (double)i;
                //                mc.mouse.eventDeltaVerticalWheel -= (double)j;
                //                int k = j == 0 ? -i : j;
                //                if (mc.player.isSpectator()) {
                //                    if (mc.inGameHud.getSpectatorHud().isOpen()) {
                //                        mc.inGameHud.getSpectatorHud().cycleSlot(-k);
                //                    } else {
                //                        float l = MathHelper.clamp(mc.player.getAbilities().getFlySpeed() + (float)j *
                // 0.005F, 0.0F, 0.2F);
                //                        mc.player.getAbilities().setFlySpeed(l);
                //                    }
                //                } else {
                //                    mc.player.getInventory().scrollInHotbar((double)k);
                //                }
            }
        }
    }
}
