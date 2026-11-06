package me.matl114.jsApi;

import com.mojang.serialization.JavaOps;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import me.matl114.utils.ApiMethod;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.ItemStackUtils;
import me.matl114.utils.inventory.MutableInventory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

@ApiMethod
public class ItemStackHelper {
    private static final Minecraft mc = Minecraft.getInstance();

    public static ItemStack createStack(Object object, int num) {
        if (object instanceof ItemStack stack) {
            return stack.copyWithCount(num);
        } else if (object instanceof ItemLike item) {
            return new ItemStack(item, num);
        } else if (object instanceof String str) {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(str));
            return new ItemStack(item, num);
        } else {
            throw new IllegalArgumentException(object + " is not a stack related argument");
        }
    }

    public static Map<String, Object> saveItemToMap(Object itemStack) {
        ItemStack itemStack1 = JsHelper.unwrap(itemStack, ItemStack.class);
        if (itemStack1.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) ItemStack.CODEC
                .encodeStart(ItemStackUtils.registry().createSerializationContext(JavaOps.INSTANCE), itemStack1)
                .getOrThrow();
    }

    public static ItemStack loadItemFromMap(Map<String, Object> itemStack) {
        if (itemStack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return ItemStack.CODEC
                .decode(ItemStackUtils.registry().createSerializationContext(JavaOps.INSTANCE), itemStack)
                .getOrThrow()
                .getFirst();
    }

    public static String getCustomName(Object what) {
        ItemStack itemStack = JsHelper.unwrap(what, ItemStack.class);
        if (itemStack.isEmpty()) {
            return null;
        }
        Component text = ItemStackUtils.getCustomName(itemStack);
        return Objects.equals(text, Component.empty()) ? null : ChatUtils.textToLegacyString(text);
    }

    public static void setCustomName(Object what, String name) {
        ItemStack itemStack = JsHelper.unwrap(what, ItemStack.class);
        if (itemStack.isEmpty()) {
            return;
        }
        ItemStackUtils.setCustomName(itemStack, name == null ? null : ChatUtils.textFromJsonString(name));
    }

    public static List<String> getLore(Object what) {
        ItemStack itemStack = JsHelper.unwrap(what, ItemStack.class);
        if (itemStack.isEmpty()) {
            return null;
        }
        List<Component> texts = ItemStackUtils.getLore(itemStack);
        return texts.isEmpty()
                ? null
                : texts.stream()
                        .map(ChatUtils::textToLegacyString)
                        .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    public static void setLore(Object what, List<String> lore) {
        ItemStack itemStack = JsHelper.unwrap(what, ItemStack.class);
        if (itemStack.isEmpty()) {
            return;
        }
        ItemStackUtils.setLore(
                itemStack,
                (lore == null || lore.isEmpty())
                        ? null
                        : lore.stream()
                                .map(ChatUtils::textFromLegacyString)
                                .map(Component.class::cast)
                                .toList());
    }

    public static DataComponentType<?> getComponentType(String name) {
        return RegistryHelper.getInRegistry(BuiltInRegistries.DATA_COMPONENT_TYPE, name);
    }

    public static Optional<?> getComponent(Object what, DataComponentType<?> type) {
        ItemStack itemStack = JsHelper.unwrap(what, ItemStack.class);
        if (itemStack.isEmpty()) {
            return null;
        }
        //#if MC >= 260300
        //$$ var components = itemStack.components;
        //$$ return components.hasNonDefault(type) ? Optional.ofNullable(components.get(type)) : null;
        //#else
        var map = itemStack.components.patch;
        return map == null ? null : map.get(type);
        //#endif
    }

    public static <T> void setComponent(Object what, DataComponentType<T> type, @Nullable Optional<T> value) {
        ItemStack itemStack = JsHelper.unwrap(what, ItemStack.class);
        if (itemStack.isEmpty()) {
            return;
        }
        if (value == null) {
            ItemStackUtils.setOrRemoveChange(itemStack, type, null);
        } else if (value.isPresent()) {
            ItemStackUtils.setOrRemoveChange(itemStack, type, value.get());
        } else {
            ItemStackUtils.markRemoveAsChange(itemStack, type);
        }
    }

    public static Object getHelperItem(ItemStack stack) {
        return JsMacrosBridge.getInstance().wrapItemStack(stack);
    }

    public static Container createInventory(List<?> list, int size) {
        return new MutableInventory(
                size,
                list.stream()
                        .map(s -> JsHelper.unwrap(s, ItemStack.class))
                        .collect(Collectors.toCollection(ArrayList::new)));
    }

    public static Container createMappingInventory(List<ItemStack> list, int size) {
        return new MutableInventory(size, list);
    }

    public static Container createJSMappingInventory(List<?> list, int size) {
        List helpers = list;
        if (helpers.size() < size) {
            helpers.add(JsMacrosBridge.getInstance().wrapItemStack(ItemStack.EMPTY));
        }
        return new Container() {
            @Override
            public int getContainerSize() {
                return size;
            }

            @Override
            public boolean isEmpty() {
                return helpers.stream().allMatch(JsMacrosBridge.getInstance()::isItemEmpty);
            }

            @Override
            public ItemStack getItem(int slot) {
                return JsMacrosBridge.getInstance().unwrapItemStack(helpers.get(slot));
            }

            @Override
            public ItemStack removeItem(int slot, int amount) {
                Object helper = helpers.get(slot);
                if (!JsMacrosBridge.getInstance().isItemEmpty(helper) && amount > 0) {
                    return JsMacrosBridge.getInstance().unwrapItemStack(helper).split(amount);
                } else {
                    return ItemStack.EMPTY;
                }
            }

            @Override
            public ItemStack removeItemNoUpdate(int slot) {
                ItemStack itemStack = JsMacrosBridge.getInstance().unwrapItemStack(helpers.get(slot));
                if (itemStack.isEmpty()) {
                    return ItemStack.EMPTY;
                } else {
                    helpers.set(slot, JsMacrosBridge.getInstance().wrapItemStack(ItemStack.EMPTY));
                    return itemStack;
                }
            }

            @Override
            public void setItem(int slot, ItemStack stack) {
                helpers.set(slot, JsMacrosBridge.getInstance().wrapItemStack(stack));
            }

            @Override
            public void setChanged() {}

            @Override
            public boolean stillValid(Player player) {
                return true;
            }

            @Override
            public void clearContent() {
                for (int i = 0; i < size; i++) {
                    setItem(i, ItemStack.EMPTY);
                }
            }
        };
    }
}
