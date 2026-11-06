package me.matl114.versioned.impl;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.matl114.utils.ItemStackUtils;
import me.matl114.versioned.DataVersion;
import me.matl114.versioned.api.VItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.item.AdventureModePredicate;
//#if MC >= 260300
//$$ // 26.3 工具组件化删除 AxeItem，isWeapon 改判 ItemTags.AXES
//#else
import net.minecraft.world.item.AxeItem;
//#endif
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public class ItemUtils_v1_21_11 implements VItem {
    @Override
    public boolean canGlide(ItemStack stack) {
        return stack.has(DataComponents.GLIDER);
    }

    @Override
    public boolean isSpear(ItemStack stack) {
        if (stack.has(DataComponents.KINETIC_WEAPON)) {
            return true;
        } else {
            // 1.21.11 Netherite Spear
            Item item = stack.getItem();
            if (item.builtInRegistryHolder().is(ItemTags.SWORDS)) {
                Integer viaId = getOptionalViaItemId(stack);
                // wooden spear id in 1.21.11 is 1296
                if (viaId != null && viaId >= 1296) {
                    return true;
                }
                Component name = stack.getCustomName();
                if (name != null) {
                    String str = name.getString();
                    if (str.contains("1.21.11") && str.contains("Spear")) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    @Override
    public boolean isWeapon(ItemStack stack) {
        if (stack.getItem() instanceof MaceItem) {
            return true;
        } else if (stack.has(DataComponents.TOOL)) {
            Item tool = stack.getItem();
            //#if MC >= 260300
            //$$ boolean axeTool = tool.builtInRegistryHolder().is(ItemTags.AXES);
            //#else
            boolean axeTool = tool instanceof AxeItem;
            //#endif
            if (axeTool) {
                return true;
            } else if (isMiningPurposeWeaponWTFTool(stack)) {
                return false;
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    private boolean isMiningPurposeWeaponWTFTool(ItemStack stack) {
        Weapon component = stack.get(DataComponents.WEAPON);
        return component != null && component.itemDamagePerAttack() > 1;
    }

    @Override
    public boolean isTool(ItemStack stack) {
        return stack.has(DataComponents.TOOL);
    }

    @Override
    public boolean isNotAttackingTool(ItemStack stack) {
        if (isTool(stack)) {
            if (stack.has(DataComponents.WEAPON)) {
                var weapon = stack.get(DataComponents.WEAPON);
                if (weapon.itemDamagePerAttack() > 1) {
                    // only axe
                    return !stack.getItem().builtInRegistryHolder().is(ItemTags.AXES);
                    // return !stack.getItem().toString().contains("_axe");
                }
                return false;
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean isShield(ItemStack stack) {
        return stack.has(DataComponents.BLOCKS_ATTACKS);
    }

    @Override
    public boolean isAxe(ItemStack stack) {
        return stack.getItem().builtInRegistryHolder().is(ItemTags.AXES);
    }

    @Override
    public boolean isEatable(ItemStack stack) {
        return stack.has(DataComponents.CONSUMABLE);
    }

    public ItemStack fromNbt(CompoundTag tag, HolderLookup.Provider lookup) {
        return tag.isEmpty()
                ? ItemStack.EMPTY
                : ItemStack.CODEC
                        .decode(lookup.createSerializationContext(NbtOps.INSTANCE), tag)
                        .getOrThrow()
                        .getFirst();
    }
    // now we save DataVersion field
    public CompoundTag toNbt(ItemStack tag, HolderLookup.Provider lookup) {
        CompoundTag tagCompound = toNbt0(tag, lookup);
        tagCompound.putInt(DataVersion.DATA_VERSION_FLAG, DataVersion.getDataVersion());
        return tagCompound;
    }

    @Override
    public MutableComponent getFormattedName(ItemStack stack) {
        MutableComponent mutableText = Component.empty()
                .append(stack.getHoverName())
                .withStyle(stack.getRarity().color());
        if (stack.has(DataComponents.CUSTOM_NAME)) {
            mutableText.withStyle(ChatFormatting.ITALIC);
        }

        return mutableText;
    }

    private CompoundTag toNbt0(ItemStack tag) {
        return tag.isEmpty()
                ? new CompoundTag()
                : (CompoundTag) ItemStack.CODEC
                        .encodeStart(ItemStackUtils.registry().createSerializationContext(NbtOps.INSTANCE), tag)
                        .getOrThrow();
    }

    private CompoundTag toNbt0(ItemStack tag, HolderLookup.Provider lookup) {
        return tag.isEmpty()
                ? new CompoundTag()
                : (CompoundTag) ItemStack.CODEC
                        .encodeStart(lookup.createSerializationContext(NbtOps.INSTANCE), tag)
                        .getOrThrow();
    }

    @Override
    public CustomModelData createModelData(int cmd) {
        return new CustomModelData(List.of((float) cmd), List.of(), List.of(), List.of());
    }

    public Integer getAttackDurabilityCost(ItemStack stack) {
        Weapon weapon = stack.get(DataComponents.WEAPON);
        return weapon != null ? weapon.itemDamagePerAttack() : null;
    }

    private final Map<DataComponentType<?>, Codec<?>> versionCompatCodecs;
    private final Codec<Component> TEXT_CODEC;

    public static Codec<Component> codec(int maxSerializedLength) {
        final Codec<String> codec = Codec.string(0, maxSerializedLength);
        return new Codec<Component>() {
            public <T> DataResult<Pair<Component, T>> decode(DynamicOps<T> ops, T input) {
                DynamicOps<JsonElement> dynamicOps = toJsonOps(ops);
                return codec.decode(ops, input).flatMap((pair) -> {
                    try {
                        JsonElement jsonElement = JsonParser.parseString((String) pair.getFirst());
                        return ComponentSerialization.CODEC
                                .parse(dynamicOps, jsonElement)
                                .map((text) -> {
                                    return Pair.of(text, pair.getSecond());
                                });
                    } catch (JsonParseException var3) {
                        JsonParseException jsonParseException = var3;
                        Objects.requireNonNull(jsonParseException);
                        return DataResult.error(jsonParseException::getMessage);
                    }
                });
            }

            public <T> DataResult<T> encode(Component text, DynamicOps<T> dynamicOps, T object) {
                DynamicOps<JsonElement> dynamicOps2 = toJsonOps(dynamicOps);
                return ComponentSerialization.CODEC
                        .encodeStart(dynamicOps2, text)
                        .flatMap((json) -> {
                            try {
                                return codec.encodeStart(dynamicOps, GsonHelper.toStableString(json));
                            } catch (IllegalArgumentException var4) {
                                IllegalArgumentException illegalArgumentException = var4;
                                Objects.requireNonNull(illegalArgumentException);
                                return DataResult.error(illegalArgumentException::getMessage);
                            }
                        });
            }

            private static <T> DynamicOps<JsonElement> toJsonOps(DynamicOps<T> ops) {
                if (ops instanceof RegistryOps<T> registryOps) {
                    return registryOps.withParent(JsonOps.INSTANCE);
                } else {
                    return JsonOps.INSTANCE;
                }
            }
        };
    }

    {
        Codec<Component> STRINGIFY_CODEC = codec(Integer.MAX_VALUE);

        TEXT_CODEC = Codec.of(
                ComponentSerialization.CODEC, Codec.withAlternative(STRINGIFY_CODEC, ComponentSerialization.CODEC));
    }

    {
        var builder = ImmutableMap.<DataComponentType<?>, Codec<?>>builder();
        builder.put(
                DataComponents.CUSTOM_MODEL_DATA,
                Codec.withAlternative(
                        CustomModelData.CODEC,
                        Codec.INT.xmap(
                                i -> new CustomModelData(List.of((float) i), List.of(), List.of(), List.of()),
                                v -> v.floats().stream()
                                        .findFirst()
                                        .map(Number::intValue)
                                        .orElse(0))));
        builder.put(DataComponents.CUSTOM_NAME, TEXT_CODEC);
        builder.put(DataComponents.ITEM_NAME, TEXT_CODEC);
        builder.put(DataComponents.LORE, TEXT_CODEC.sizeLimitedListOf(256).xmap(ItemLore::new, ItemLore::lines));
        builder.put(
                DataComponents.ENCHANTMENTS,
                Codec.withAlternative(
                        ItemEnchantments.CODEC,
                        ItemEnchantments.CODEC.fieldOf("levels").codec()));
        builder.put(
                DataComponents.STORED_ENCHANTMENTS,
                Codec.withAlternative(
                        ItemEnchantments.CODEC,
                        ItemEnchantments.CODEC.fieldOf("levels").codec()));
        builder.put(
                DataComponents.DYED_COLOR,
                Codec.withAlternative(
                        DyedItemColor.CODEC, DyedItemColor.CODEC.fieldOf("rgb").codec()));
        builder.put(
                DataComponents.CAN_BREAK,
                Codec.withAlternative(
                        AdventureModePredicate.CODEC,
                        AdventureModePredicate.CODEC.fieldOf("predicates").codec()));
        builder.put(
                DataComponents.CAN_PLACE_ON,
                Codec.withAlternative(
                        AdventureModePredicate.CODEC,
                        AdventureModePredicate.CODEC.fieldOf("predicates").codec()));
        var attributeCodec = ItemAttributeModifiers.Entry.CODEC
                .listOf()
                .xmap(ItemAttributeModifiers::new, ItemAttributeModifiers::modifiers);
        builder.put(
                DataComponents.ATTRIBUTE_MODIFIERS,
                Codec.withAlternative(
                        attributeCodec, attributeCodec.fieldOf("modifiers").codec()));
        versionCompatCodecs = builder.build();
    }

    public Integer getOptionalViaItemId(ItemStack stack) {
        CustomData component = stack.get(DataComponents.CUSTOM_DATA);
        if (component != null) {
            var nbt = component.tag;
            if (nbt != null
                    && nbt.get("VV|original_hashes") instanceof CompoundTag original
                    && original.get("id") instanceof IntTag intValue) {
                return intValue.intValue();
            } else if (nbt != null && nbt.get("VB|Protocol1_21_11To1_21_9|id") instanceof IntTag intVal) {
                return intVal.intValue();
            }
        }
        return null;
    }

    @Override
    public Map<DataComponentType<?>, Codec<?>> getVersionCompatCodecs() {
        return versionCompatCodecs;
    }
}
