package me.matl114.utils;

import static net.minecraft.core.component.DataComponents.*;

import com.google.common.collect.ImmutableMap;
import com.google.gson.*;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import me.matl114.bukkit.BukkitItemStackUtils;
import me.matl114.versioned.api.VHideFlag;
import me.matl114.versioned.api.VItem;
import me.matl114.versioned.impl.TooltipHideFlag_v1_21_11;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.core.*;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ItemLike;
import org.jetbrains.annotations.Nullable;

@ApiMethod
public class ItemStackUtils {
    public static CustomItemStackBuilder builder() {
        return new CustomItemStackBuilder();
    }

    public static VHideFlag[] getHideFlags() {
        return TooltipHideFlag_v1_21_11.values();
    }

    public static Predicate<ItemStack> componentPredicate(DataComponentType<?> type) {
        return (stack) -> hasInPatch(stack, type);
    }

    public static <T> Predicate<ItemStack> componentPredicate(
            DataComponentType<T> type, Predicate<T> test, boolean nullDefault) {
        return (stack) -> {
            var val = stack.get(type);
            if (val != null) {
                return test.test(val);
            } else {
                return nullDefault;
            }
        };
    }

    public interface TooltipsToggle {
        public void apply(ItemStack stack, boolean showInTooltip);

        public static TooltipsToggle byComponent(DataComponentType<Unit> type) {
            return ((stack, showInTooltip) -> {
                if (showInTooltip) {
                    stack.remove(type);
                } else {
                    stack.set(type, Unit.INSTANCE);
                }
            });
        }

        public static <T> TooltipsToggle onComponent(DataComponentType<T> type, ComponentTooltipsToggle<T> toggle) {
            return ((stack, showInTooltips) -> {
                T val = stack.get(type);
                if (val != null) {
                    stack.set(type, toggle.toggle(val, showInTooltips));
                }
            });
        }
    }

    public interface ComponentTooltipsToggle<T> {
        T toggle(T val, boolean showInToolTips);
    }

    @SuppressWarnings("all")
    public static <T> T getInPatch(ItemStack stack, DataComponentType<T> type) {
        if (stack != null && !stack.isEmpty()) {
            var map = stack.components.patch;
            if (map == null) return null;
            var optional = map.get(type);
            return (T) (optional == null ? null : optional.orElse(null));
        }
        return null;
    }

    public static boolean hasInPatch(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            var map = stack.components.patch;
            // add compat to via item 1.20.4
            if (map == null) return false;
            if (map.isEmpty()) return false;
            if (map.size() >= 2) return true;
            if (map.containsKey(CUSTOM_DATA)) {
                // check protocol item
                CustomData customData = (CustomData) map.get(CUSTOM_DATA).orElse(null);
                if (customData == null || customData.isEmpty()) return false;
                var nbt = customData.tag;
                Set<String> keys = nbt.keySet();
                if (keys.size() > 2) return true;
                // we only support Damage , because most of these are from damage
                int val = nbt.get("Damage") instanceof IntTag nbtInt ? nbtInt.intValue() : 0;
                if (val > 0) return true;
                for (var key : keys) {
                    // viaversion items
                    if (Objects.equals("Damage", key) || key.contains("VV|Protocol")) {
                        continue;
                    }
                    return true;
                }
                return false;
            } else return true;
        }
        return false;
    }

    public static boolean hasInPatch(ItemStack stack, DataComponentType<?> type) {
        if (stack != null && !stack.isEmpty()) {
            var map = stack.components.patch;
            if (map == null) return false;
            return map.containsKey(type) && !Objects.equals(Optional.empty(), map.get(type));
        }
        return false;
    }

    public static <T> void setOrRemoveChange(ItemStack stack, DataComponentType<T> type, @Nullable T val) {
        if (stack != null && !stack.isEmpty()) {
            var cpmap = stack.components;
            var map = cpmap.patch;
            if (map == null) return;
            boolean shouldChange;
            if (val == null) {
                shouldChange = map.containsKey(type);
            } else {
                var op = map.get(type);
                if (op != null && Objects.equals(val, op.orElse(null))) {
                    shouldChange = false;
                } else shouldChange = true;
            }
            if (shouldChange) {
                // copy before write
                cpmap.ensureMapOwnership();
                // update the map after copy
                map = cpmap.patch;
                if (val == null) map.remove(type);
                else map.put(type, Optional.of(val));
            }
        }
    }

    public static <T> void markRemoveAsChange(ItemStack stack, DataComponentType<T> type) {
        if (stack != null && !stack.isEmpty()) {
            var cpmap = stack.components;
            var map = cpmap.patch;
            if (map != null) {
                // no need to modify
                if (map.containsKey(type) && map.get(type) == Optional.empty()) return;
                cpmap.ensureMapOwnership();
                ;
                cpmap.patch.put(type, Optional.empty());
            }
        }
    }

    private static final Minecraft mc = Minecraft.getInstance();
    private static RegistryAccess staticRegistry;

    public static <T> Identifier solveDynamic(Holder<T> entry) {
        return entry.unwrapKey().get().identifier();
    }

    public static class DelegateRegistryWrapperLookup implements HolderLookup.Provider {
        protected static final DelegateRegistryWrapperLookup INSTANCE = new DelegateRegistryWrapperLookup();

        @Override
        public Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys() {
            return registry().listRegistryKeys();
        }

        @Override
        public <T> Optional<? extends HolderLookup.RegistryLookup<T>> lookup(
                ResourceKey<? extends Registry<? extends T>> registryRef) {
            return registry().lookup(registryRef);
        }

        public <V> RegistryOps<V> createSerializationContext(DynamicOps<V> delegate) {
            return registry().createSerializationContext(delegate);
        }
    }

    public static HolderLookup.Provider delegate() {
        return DelegateRegistryWrapperLookup.INSTANCE;
    }

    private static RegistryAccess cachedRegistry;

    @Nonnull
    public static RegistryAccess registry() {
        if (mc.getConnection() != null) {
            return cachedRegistry = mc.getConnection().registryAccess();
        } else {
            // when asking registry() offline, just return the cache value
            if (cachedRegistry != null) {
                return cachedRegistry;
            }
            if (staticRegistry == null) {
                staticRegistry = ClientRegistryLayer.createRegistryAccess().compositeAccess();
            }
            return staticRegistry;
        }
    }

    /**
     * 物品数据组件是否已绑定。
     *
     * <p>26.2 起组件的最终值来自数据包：{@code DataComponentInitializers} 在加载数据包时统一
     * {@code bindComponents}，本次启动还没进过世界也没连过服时 {@code Holder.Reference#components()}
     * 仍是 null，此时任何 {@code new ItemStack(...)} 都会抛 {@code NullPointerException: Components not bound yet}。
     * 主菜单上的图标、注册表条目预览都处在这个阶段。
     */
    public static boolean itemComponentsBound() {
        return BuiltInRegistries.ITEM.wrapAsHolder(Items.AIR).areComponentsBound();
    }

    /** 组件未绑定时给空槽，绑定后照常构造 —— 图标类调用点用它替代裸 {@code new ItemStack(...)}。 */
    public static ItemStack safeItemStack(ItemLike item) {
        return itemComponentsBound() ? new ItemStack(item) : ItemStack.EMPTY;
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static Component jsonRawToText(String jsonRaw) {
        try {
            if (jsonRaw == null) return null;
            JsonElement jsonElement = JsonParser.parseString(jsonRaw);
            return jsonElement == null
                    ? null
                    : ComponentSerialization.CODEC
                            .parse(registry().createSerializationContext(JsonOps.INSTANCE), jsonElement)
                            .getOrThrow(JsonParseException::new);
        } catch (Throwable e) {
            return null;
        }
    }

    public static String textToJsonRaw(Component text) {
        if (text == null) return null;
        try {
            var re = ComponentSerialization.CODEC
                    .encodeStart(registry().createSerializationContext(JsonOps.INSTANCE), text)
                    .getOrThrow(JsonParseException::new);
            return GSON.toJson(re);
        } catch (Throwable e) {
            return null;
        }
    }

    @Nullable
    public static Component getCustomName(ItemStack stack) {
        var text = getInPatch(stack, CUSTOM_NAME);
        return text == null ? Component.empty() : text;
    }

    public static void setCustomName(ItemStack stack, Component text) {
        setOrRemoveChange(stack, CUSTOM_NAME, Objects.equals(text, Component.empty()) ? null : text);
    }

    public static void applyItemEnchant(ItemStack stack, ItemEnchantments ench) {
        setOrRemoveChange(stack, ENCHANTMENTS, Objects.equals(ench, ItemEnchantments.EMPTY) ? null : ench);
    }

    public static ItemEnchantments getItemEnchant(ItemStack stack) {
        var itemEnchant = getInPatch(stack, ENCHANTMENTS);
        return itemEnchant == null ? ItemEnchantments.EMPTY : itemEnchant;
    }

    private static final Map<String, EquipmentSlot> NAME_TO_SLOT = new HashMap<>();

    static {
        for (var re : EquipmentSlot.values()) {
            NAME_TO_SLOT.put(re.getName(), re);
        }
    }

    public static ItemAttributeModifiers getEntityModifier(ItemStack stack) {
        var attr = getInPatch(stack, ATTRIBUTE_MODIFIERS);
        return attr == null ? ItemAttributeModifiers.EMPTY : attr;
    }

    public static void applyEntityModifier(ItemStack stack, ItemAttributeModifiers data) {
        setOrRemoveChange(stack, ATTRIBUTE_MODIFIERS, Objects.equals(data, ItemAttributeModifiers.EMPTY) ? null : data);
    }

    public static boolean getIsUnbreakable(ItemStack stack) {
        return hasInPatch(stack, UNBREAKABLE);
    }

    public static void setUnbreakable(ItemStack stack, boolean ub) {
        Unit component = getInPatch(stack, UNBREAKABLE);
        if (component == null) {
            setOrRemoveChange(stack, UNBREAKABLE, ub ? Unit.INSTANCE : null);
        } else {
            if (!ub) {
                setOrRemoveChange(stack, UNBREAKABLE, null);
            }
        }
    }

    public static void setDamage(ItemStack stack, int damage) {
        if (stack == ItemStack.EMPTY) return;
        if (damage > 0) {
            stack.setDamageValue(damage);
        } else {
            setOrRemoveChange(stack, DAMAGE, null);
        }
    }

    private static final Style LORE_STYLE =
            Style.EMPTY.withColor(ChatFormatting.DARK_PURPLE).withItalic(true);

    public static List<Component> getLore(ItemStack stack) {
        var itemLore = getInPatch(stack, LORE);
        return itemLore == null ? new ArrayList<>() : new ArrayList<>(itemLore.lines());
    }

    public static List<Component> getLoreReadOnly(ItemStack stack) {
        var itemLore = getInPatch(stack, LORE);
        return itemLore == null ? List.of() : itemLore.lines();
    }

    public static void setLore(ItemStack itemStack, List<Component> lore) {
        if (lore != null && !lore.isEmpty()) {
            setOrRemoveChange(itemStack, LORE, new ItemLore(lore));
        } else {
            setOrRemoveChange(itemStack, LORE, null);
        }
    }

    public static List<String> getLoreString(ItemStack stack) {
        return getLoreReadOnly(stack).stream()
                .map(txt -> txt.getString().replace("§.", ""))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static ItemEnchantments getStoredEnchantment(ItemStack stack) {
        var ench = getInPatch(stack, STORED_ENCHANTMENTS);
        return ench == null ? ItemEnchantments.EMPTY : ench;
    }

    public static void setEnchantmentGlow(ItemStack stack) {
        setOrRemoveChange(stack, ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
    }

    public static void setEnchantment(ItemStack stack, ItemEnchantments enchantments) {
        setOrRemoveChange(
                stack, ENCHANTMENTS, Objects.equals(enchantments, ItemEnchantments.EMPTY) ? null : enchantments);
    }

    public static void setStoredEnchantment(ItemStack stack, ItemEnchantments enchantments) {
        setOrRemoveChange(
                stack, STORED_ENCHANTMENTS, Objects.equals(enchantments, ItemEnchantments.EMPTY) ? null : enchantments);
    }

    public static ItemStack getCleanedItem(ItemStack stack) {
        return getCleanedItem(stack, true);
    }

    public static ItemStack getCleanedItem(ItemStack stack, boolean keepDur) {
        return getCleanedItem(stack, keepDur, true);
    }

    public static ItemStack getCleanedItem(ItemStack stack, boolean keepDur, boolean keepEnchant) {
        return getCleanedItem(stack, true, keepDur, keepEnchant);
    }

    public static ItemStack getCleanedItem(ItemStack stack, boolean keepNBT, boolean keepDur, boolean keepEnchant) {
        return getCleanedItem(stack, -999, keepNBT, keepDur, keepEnchant);
    }

    public static ItemStack getCleanedItem(
            ItemStack stack, int setAmount, boolean keepNBT, boolean keepDur, boolean keepEnchant) {
        ItemStack cleaned = stack.getItem().getDefaultInstance();

        if (!keepNBT) {
            if (setAmount != -999) {
                cleaned.setCount(setAmount);
            }
            return cleaned;
        }
        ItemStack stackCopy = stack.copy();
        if (setAmount != -999) {
            stackCopy.setCount(setAmount);
        }
        if (!keepDur) {
            setOrRemoveChange(stackCopy, DAMAGE, null);
        }
        if (!keepEnchant) {
            setOrRemoveChange(stackCopy, ENCHANTMENTS, null);
            setOrRemoveChange(stackCopy, STORED_ENCHANTMENTS, null);
        }

        return stackCopy;
    }

    public static boolean matchItemWithout(
            ItemStack stack1, ItemStack stack2, boolean matchDur, boolean matchEnch, boolean matchLore) {

        if (stack1.isEmpty()) {
            return stack2.isEmpty();
        } else if (stack2.isEmpty()) {
            return false;
        } else if (!stack1.is(stack2.getItem())) {
            return false;
        }
        {
            // both not empty and with same item
            //            if(matchDur && matchEnch){
            //                return matchLore ? ItemStack.areItemsAndComponentsEqual(stack1, stack2) :
            // matchItemWithoutLore(stack1, stack2);
            //            }else{
            //                // optimize
            //                ItemStack clean1 = getCleanedItem(stack1, matchDur, matchEnch);
            //                ItemStack clean2 = getCleanedItem(stack2, matchDur, matchEnch);
            //                return matchLore ? ItemStack.areItemsAndComponentsEqual(clean1, clean2) :
            // matchItemWithoutLore(clean1, clean2);
            //            }
            if (matchDur && matchEnch && matchLore) {
                return ItemStack.isSameItemSameComponents(stack1, stack2);
            }
            var compound1 = stack1.components.patch;
            var compound2 = stack2.components.patch;
            if (compound1 == null || compound2 == null) {
                return compound1 == compound2;
            }

            Map<DataComponentType, Optional> map1 = new HashMap<>(compound1);
            Map<DataComponentType, Optional> map2 = new HashMap<>(compound2);
            Optional n1;
            Optional n2;
            if (!matchLore) {
                n1 = map1.remove(LORE);
                n2 = map2.remove(LORE);
                // both having or not having lore
                if (!((n1 == null) ? (n2 == null || n2 == Optional.empty()) : (n2 != null && n2.isPresent()))) {
                    return false;
                }
            }

            if (!matchEnch) {
                n1 = map1.remove(ENCHANTMENTS);
                n2 = map2.remove(ENCHANTMENTS);
                // both having or not having lore
                if (!((n1 == null) ? (n2 == null || n2 == Optional.empty()) : (n2 != null && n2.isPresent()))) {
                    return false;
                }
            }
            if (!matchDur) {
                map1.remove(DAMAGE);
                map2.remove(DAMAGE);
            }
            return map1.equals(map2);
        }
    }

    public static boolean matchItemWithoutLore(ItemStack stack1, ItemStack stack2) {
        if (!stack1.is(stack2.getItem())) {
            return false;
        }
        if (stack1.isEmpty()) {
            return stack2.isEmpty();
        } else if (stack2.isEmpty()) {
            return false;
        } else {
            var compound1 = stack1.components.patch;
            var compound2 = stack2.components.patch;
            if (compound1 == null || compound2 == null) {
                return compound1 == compound2;
            }
            Map<DataComponentType, Optional> map1 = new HashMap<>(compound1);
            Map<DataComponentType, Optional> map2 = new HashMap<>(compound2);
            var n1 = map1.remove(LORE);
            var n2 = map2.remove(LORE);
            // both having or not having lore
            return ((n1 == null) ? (n2 == null || n2 == Optional.empty()) : (n2 != null && n2.isPresent()))
                    && map1.equals(map2);
        }
    }

    public static boolean matchItemMiningAbility(ItemStack stack1, ItemStack stack2) {
        return Objects.equals(stack1.get(TOOL), stack2.get(TOOL)) && matchEfficiency(stack1, stack2);
    }

    public static boolean matchVersionedItem(ItemStack stack1, ItemStack stack2) {
        if (!stack1.is(stack2.getItem())) {
            return false;
        }
        if (stack1.isEmpty()) {
            return stack2.isEmpty();
        } else if (stack2.isEmpty()) {
            return false;
        } else {
            var compound1 = stack1.components.patch;
            var compound2 = stack2.components.patch;
            Map<DataComponentType, Optional> map1 = compound1 == null ? new HashMap<>() : new HashMap<>(compound1);
            Map<DataComponentType, Optional> map2 = compound2 == null ? new HashMap<>() : new HashMap<>(compound2);
            var n1 = map1.remove(CUSTOM_DATA);
            var n2 = map2.remove(CUSTOM_DATA);
            // both having or not having lore
            if (map1.equals(map2)) {
                Tag nbt1 = (n1 == null || n1.isEmpty()) ? null : ((CustomData) n1.get()).tag.get(BUKKIT_NAMESPACE);
                Tag nbt2 = (n2 == null || n2.isEmpty()) ? null : ((CustomData) n2.get()).tag.get(BUKKIT_NAMESPACE);
                return Objects.equals(nbt1, nbt2);
            } else {
                return false;
            }
        }
    }

    private static boolean matchEfficiency(ItemStack stack1, ItemStack stack2) {
        ItemEnchantments ench1 = stack1.get(ENCHANTMENTS);
        ItemEnchantments ench2 = stack2.get(ENCHANTMENTS);
        if (ench1 == null || ench2 == null) {
            return ench1 == ench2;
        } else {
            Holder<Enchantment> efficient = ItemStackUtils.registry().getOrThrow(Enchantments.EFFICIENCY);
            return ench1.getLevel(efficient) == ench2.getLevel(efficient);
        }
    }

    protected static String BUKKIT_NAMESPACE = "PublicBukkitValues";
    protected static String SLIMEFUN_ID_PATH = "slimefun:slimefun_item";
    protected static boolean isGrassOrShortGrass =
            BuiltInRegistries.ITEM.getValue(new Identifier("minecraft", "grass")) != Items.AIR;

    public static ItemStack newItem(String type, String id) {
        String[] typedString = type.split("[$]");
        String typedStr = typedString[0].toLowerCase(Locale.ROOT);
        if ("grass".equals(typedStr) || "short_grass".equals(typedStr)) {
            typedStr = isGrassOrShortGrass ? "grass" : "short_grass";
        }
        Item typed = BuiltInRegistries.ITEM.getValue(new Identifier("minecraft", typedStr));

        ItemStack stacked = new ItemStack(typed);
        if (typedString.length == 2) {
            if (typed == Items.PLAYER_HEAD) {
                setOrRemoveChange(
                        stacked, PROFILE, BukkitItemStackUtils.buildPlayerHeadProfileCSCoreLib(typedString[1]));
            }
        }
        if (id != null && !"null".equals(id)) {
            setSfId(stacked, id);
        }
        return stacked.isEmpty() ? null : stacked;
    }

    public static boolean hasCustomData(ItemStack itemStack) {
        CustomData customData = getInPatch(itemStack, CUSTOM_DATA);
        return customData != null && !customData.isEmpty();
    }

    private static final CompoundTag EMPTY = new CompoundTag(ImmutableMap.of());

    public static CompoundTag getCustomDataReadOnly(ItemStack itemStack) {
        CustomData customData = getInPatch(itemStack, CUSTOM_DATA);
        return customData == null ? EMPTY : customData.tag;
    }

    public static void mapCustomData(ItemStack itemStack, UnaryOperator<CompoundTag> updater) {
        CustomData customData = getInPatch(itemStack, CUSTOM_DATA);
        CompoundTag nbtCompound;
        if (customData == null) {
            nbtCompound = new CompoundTag();
        } else {
            nbtCompound = customData.copyTag();
        }
        nbtCompound = updater.apply(nbtCompound);
        if (nbtCompound == null || nbtCompound.isEmpty()) {
            setOrRemoveChange(itemStack, CUSTOM_DATA, null);
        } else {
            setOrRemoveChange(itemStack, CUSTOM_DATA, new CustomData(nbtCompound));
        }
    }

    public static void updateCustomData(ItemStack itemStack, Consumer<CompoundTag> updater) {
        CustomData customData = getInPatch(itemStack, CUSTOM_DATA);
        CompoundTag nbtCompound;
        if (customData == null) {
            nbtCompound = new CompoundTag();
        } else {
            nbtCompound = customData.copyTag();
        }
        updater.accept(nbtCompound);
        if (nbtCompound == null || nbtCompound.isEmpty()) {
            setOrRemoveChange(itemStack, CUSTOM_DATA, null);
        } else {
            setOrRemoveChange(itemStack, CUSTOM_DATA, new CustomData(nbtCompound));
        }
    }

    public static CompoundTag getBukkitValueReadOnly(ItemStack stack) {
        CompoundTag compound = getCustomDataReadOnly(stack);
        return getBukkitValue(compound);
    }

    public static CompoundTag getBukkitValue(@Nonnull CompoundTag nbt) {
        return nbt.get(BUKKIT_NAMESPACE) instanceof CompoundTag cpd ? cpd : null;
    }

    private static CompoundTag createBukkitValue(CompoundTag nbt) {
        CompoundTag nbt0;
        if (nbt.get(BUKKIT_NAMESPACE) instanceof CompoundTag nbt2) {
            return nbt2;
        }
        nbt0 = new CompoundTag();

        nbt.put(BUKKIT_NAMESPACE, nbt0);
        return nbt0;
    }

    public static String getSfIdFromBukkitValues(CompoundTag ntb) {
        return ntb == null
                ? null
                : (ntb.get(SLIMEFUN_ID_PATH) instanceof StringTag nbtString ? nbtString.value() : null);
    }

    public static String getSfId(CompoundTag nbt) {
        CompoundTag bukkitValues = getBukkitValue(nbt);
        if (bukkitValues == null) return null;
        return getSfIdFromBukkitValues(bukkitValues);
    }

    public static void setSfId(ItemStack stack, String id) {
        if (id == null || id.isEmpty()) {
            mapCustomData(stack, (nbt) -> {
                var nbt0 = getBukkitValue(nbt);
                if (nbt0 != null) {
                    nbt0.remove(SLIMEFUN_ID_PATH);
                    if (nbt0.isEmpty()) {
                        nbt.remove(BUKKIT_NAMESPACE);
                    }
                }
                return nbt;
            });
        } else {
            mapCustomData(stack, (nbt) -> {
                CompoundTag compound = createBukkitValue(nbt);
                compound.putString(SLIMEFUN_ID_PATH, id);
                return nbt;
            });
        }
    }

    public static String getSfId(ItemStack stack) {
        CompoundTag bukkitValues = getBukkitValueReadOnly(stack);
        if (bukkitValues == null) return null;
        return getSfIdFromBukkitValues(bukkitValues);
    }

    public static ItemStack withTypeChange(ItemStack itemStack, Item typeChange) {
        return itemStack.transmuteCopyIgnoreEmpty((ItemLike) typeChange, itemStack.getCount());
    }

    public static void setCustomModelData(ItemStack stack, int customModelData) {
        setOrRemoveChange(stack, CUSTOM_MODEL_DATA, VItem.getInstance().createModelData(customModelData));
    }

    public static int getEnchantmentLevel(ItemEnchantments component, ResourceKey<Enchantment> key) {
        if (component == null) return 0;
        var enchantmentRegistry = ItemStackUtils.registry().lookupOrThrow(Registries.ENCHANTMENT);
        return component.getLevel(enchantmentRegistry.getOrThrow(key));
    }

    //    public static double getAttributeValue(ItemStack stack)
}
