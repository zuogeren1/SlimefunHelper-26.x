package me.matl114.jsApi;

import me.matl114.hacks.utils.ClientUtils;

import java.util.List;
import javax.annotation.Nonnull;
import me.matl114.hacks.InvTasks;
import me.matl114.utils.ApiMethod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.CartographyTableScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.HorseInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.gui.screens.inventory.SmithingScreen;
import net.minecraft.client.gui.screens.inventory.SmokerScreen;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

@ApiMethod
public class ScreenHelper {
    private static final Minecraft mc = Minecraft.getInstance();

    public static boolean isServerScreenOpen() {
        return mc.player.containerMenu != mc.player.inventoryMenu;
    }

    public static boolean isScreenOpen() {
        return ClientUtils.getScreen(mc) instanceof AbstractContainerScreen<?>;
    }

    @Nonnull
    public static Object createInventoryView(AbstractContainerScreen s) {
        return JsMacrosBridge.getInstance().wrap(s);
    }

    @Nonnull
    public static Object createServerInventoryView() {
        LocalPlayer player = mc.player;
        AbstractContainerScreen<?> handledScreen = InvTasks.getCurrentServerOpeningScreen(player);
        // create backpack inventory if null
        return handledScreen != null
                ? createInventoryView(handledScreen)
                : JsMacrosBridge.getInstance().createInventory();
    }

    public static AbstractContainerMenu getScreenHandler(Object handled) {
        return unwrapHandler(handled);
    }

    private static AbstractContainerMenu unwrapHandler(Object obj) {
        if (obj instanceof AbstractContainerMenu sh) {
            return sh;
        } else {
            return JsHelper.unwrap(obj, AbstractContainerScreen.class).getMenu();
        }
    }

    public static int getSyncId(Object screen) {
        return unwrapHandler(screen).containerId;
    }

    public static boolean isInPlayerInventory(Object handled, int slotIndex) {
        return unwrapHandler(handled).slots.get(slotIndex).container instanceof Inventory;
    }

    public static boolean isInContainerInventory(Object handled, int slotIndex) {
        return !isInPlayerInventory(handled, slotIndex);
    }

    public static boolean canPlaceInSlot(Object handled, int slotIndex, Object itemStack) {
        Slot slot = unwrapHandler(handled).slots.get(slotIndex);
        ItemStack stack = JsHelper.unwrap(itemStack, ItemStack.class);
        return slot.mayPlace(stack);
    }

    public static boolean canTakeFromSlot(Object handled, int slotIndex) {
        Slot slot = unwrapHandler(handled).slots.get(slotIndex);
        return slot.mayPickup(mc.player);
    }

    public static List<Slot> getScreenSlots(Object handled) {
        return unwrapHandler(handled).slots;
    }

    public static Slot getScreenSlot(Object handled, int index) {
        return unwrapHandler(handled).slots.get(index);
    }

    public static ItemStack getScreenStack(Object handled, int index) {
        return unwrapHandler(handled).slots.get(index).getItem();
    }

    public static void setScreenStack(Object handled, int index, ItemStack stack) {
        unwrapHandler(handled).slots.get(index).setByPlayer(stack == null ? ItemStack.EMPTY : stack);
    }

    public static void setSlotItem(Slot slot, ItemStack stack) {
        slot.setByPlayer(stack == null ? ItemStack.EMPTY : stack);
    }

    public static ItemStack getSlotItem(Slot slot) {
        return slot.getItem();
    }

    public static String getScreenName(Screen s) {
        if (s == null) {
            return null;
        } else if (s instanceof AbstractContainerScreen) {
            if (s instanceof ContainerScreen) {
                return String.format("%d Row Chest", ((ChestMenu) ((ContainerScreen) s).getMenu()).getRowCount());
            } else if (s instanceof DispenserScreen) {
                return "3x3 Container";
            } else if (s instanceof AnvilScreen) {
                return "Anvil";
            } else if (s instanceof BeaconScreen) {
                return "Beacon";
            } else if (s instanceof BlastFurnaceScreen) {
                return "Blast Furnace";
            } else if (s instanceof BrewingStandScreen) {
                return "Brewing Stand";
            } else if (s instanceof CraftingScreen) {
                return "Crafting Table";
            } else if (s instanceof EnchantmentScreen) {
                return "Enchanting Table";
            } else if (s instanceof FurnaceScreen) {
                return "Furnace";
            } else if (s instanceof GrindstoneScreen) {
                return "Grindstone";
            } else if (s instanceof HopperScreen) {
                return "Hopper";
            } else if (s instanceof LoomScreen) {
                return "Loom";
            } else if (s instanceof MerchantScreen) {
                return "Villager";
            } else if (s instanceof ShulkerBoxScreen) {
                return "Shulker Box";
            } else if (s instanceof SmithingScreen) {
                return "Smithing Table";
            } else if (s instanceof SmokerScreen) {
                return "Smoker";
            } else if (s instanceof CartographyTableScreen) {
                return "Cartography Table";
            } else if (s instanceof StonecutterScreen) {
                return "Stonecutter";
            } else if (s instanceof InventoryScreen) {
                return "Survival Inventory";
            } else if (s instanceof HorseInventoryScreen) {
                return "Horse";
            } else {
                return s instanceof CreativeModeInventoryScreen
                        ? "Creative Inventory"
                        : s.getClass().getName();
            }
        } else if (s instanceof ChatScreen) {
            return "Chat";
        } else {
            Component t = s.getTitle();
            String ret = "";
            if (t != null) {
                ret = t.getString();
            }

            if (ret.equals("")) {
                ret = "unknown";
            }

            return ret;
        }
    }
}
