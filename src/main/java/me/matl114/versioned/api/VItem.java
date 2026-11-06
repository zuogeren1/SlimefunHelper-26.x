package me.matl114.versioned.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import me.matl114.versioned.impl.ItemUtils_v1_21_11;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import org.jetbrains.annotations.Nullable;

public interface VItem {
    public static final VItem INSTANCE = new ItemUtils_v1_21_11();

    public static VItem getInstance() {
        return INSTANCE;
    }

    public boolean canGlide(ItemStack stack);

    public boolean isSpear(ItemStack stack);

    public boolean isWeapon(ItemStack stack);

    public boolean isTool(ItemStack stack);

    public boolean isNotAttackingTool(ItemStack stack);

    public boolean isShield(ItemStack stack);

    public boolean isAxe(ItemStack stack);

    public boolean isEatable(ItemStack stack);

    public Integer getAttackDurabilityCost(ItemStack stack);

    // now we save DataVersion field
    public ItemStack fromNbt(CompoundTag tag, HolderLookup.Provider lookup);
    // now we save DataVersion field
    public CompoundTag toNbt(ItemStack tag, HolderLookup.Provider lookup);

    public MutableComponent getFormattedName(ItemStack stack);

    public CustomModelData createModelData(int cmd);

    public Map<DataComponentType<?>, Codec<?>> getVersionCompatCodecs();

    default Codec<ItemStack> getVersionedCodec() {
        return ITEM_STACK_CODEC;
    }

    public static record ComponentChangesType(@Nullable DataComponentType<?> type, boolean removed) {
        public static final Codec<ComponentChangesType> CODEC;

        @Nonnull
        public Codec<?> getValueCodec() {
            if (type == null) return Codec.EMPTY.codec();
            if (removed) return Codec.EMPTY.codec();
            else {
                var versioned = VItem.getInstance().getVersionCompatCodecs().get(type);
                return versioned == null ? type.codecOrThrow() : versioned;
            }
        }

        static {
            CODEC = Codec.STRING.flatXmap(
                    (id) -> {
                        boolean bl = id.startsWith("!");
                        if (bl) {
                            id = id.substring("!".length());
                        }

                        Identifier identifier = Identifier.tryParse(id);
                        DataComponentType<?> componentType = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(identifier);
                        if (componentType == null) {
                            // for version compat
                            return DataResult.success(new ComponentChangesType(null, false));
                        } else {
                            return componentType.isTransient()
                                    ? DataResult.error(() -> {
                                        return "'" + String.valueOf(identifier) + "' is not a persistent component";
                                    })
                                    : DataResult.success(new ComponentChangesType(componentType, bl));
                        }
                    },
                    (type) -> {
                        DataComponentType<?> componentType = type.type();
                        if (componentType == null) {
                            return DataResult.error(() -> "Null component type");
                        }
                        Identifier identifier = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(componentType);
                        return identifier == null
                                ? DataResult.error(() -> {
                                    return "Unregistered component: " + String.valueOf(componentType);
                                })
                                : DataResult.success(
                                        type.removed() ? "!" + String.valueOf(identifier) : identifier.toString());
                    });
        }
    }

    Codec<DataComponentPatch> COMPONENT_CHANGES_CODEC = Codec.dispatchedMap(
                    ComponentChangesType.CODEC, ComponentChangesType::getValueCodec)
            .xmap(
                    //#if MC >= 260300
                    //$$ (changes) -> {
                        //$$ if (changes.isEmpty()) {
                            //$$ return DataComponentPatch.EMPTY;
                        //$$ } else {
                            //$$ DataComponentPatch.Builder builder = DataComponentPatch.builder();
                            //$$ var var2 = changes.entrySet().iterator();
//$$
                            //$$ while (var2.hasNext()) {
                                //$$ Map.Entry<ComponentChangesType, ?> entry = var2.next();
                                //$$ ComponentChangesType type = entry.getKey();
                                //$$ // should be only used here
                                //$$ if (type.type() != null) {
                                    //$$ if (type.removed()) {
                                        //$$ builder.remove((DataComponentType) type.type());
                                    //$$ } else {
                                        //$$ builder.set((DataComponentType) type.type(), entry.getValue());
                                    //$$ }
                                //$$ }
                            //$$ }
//$$
                            //$$ return builder.build();
                        //$$ }
                    //$$ },
                    //#else
                    (changes) -> {
                        if (changes.isEmpty()) {
                            return DataComponentPatch.EMPTY;
                        } else {
                            Reference2ObjectMap<DataComponentType<?>, Optional<?>> reference2ObjectMap =
                                    new Reference2ObjectArrayMap<>(changes.size());
                            var var2 = changes.entrySet().iterator();

                            while (var2.hasNext()) {
                                Map.Entry<ComponentChangesType, ?> entry = var2.next();
                                ComponentChangesType type = entry.getKey();
                                // should be only used here
                                if (type.type() != null) {
                                    if (type.removed()) {
                                        reference2ObjectMap.put(type.type(), Optional.empty());
                                    } else {
                                        reference2ObjectMap.put(type.type(), Optional.of(entry.getValue()));
                                    }
                                }
                            }

                            return new DataComponentPatch(reference2ObjectMap);
                        }
                    },
                    //#endif
                    //#if MC >= 260300
                    //$$ (changes) -> {
                        //$$ Reference2ObjectMap<ComponentChangesType, ?> reference2ObjectMap =
                                //$$ new Reference2ObjectArrayMap<>(changes.size());
                        //$$ var split = changes.split();
                        //$$ for (var component : split.added()) {
                            //$$ DataComponentType<?> componentType = component.type();
                            //$$ if (!componentType.isTransient()) {
                                //$$ ((Map) reference2ObjectMap)
                                        //$$ .put(new ComponentChangesType(componentType, false), component.value());
                            //$$ }
                        //$$ }
                        //$$ for (DataComponentType<?> componentType : split.removed()) {
                            //$$ if (!componentType.isTransient()) {
                                //$$ ((Map) reference2ObjectMap)
                                        //$$ .put(new ComponentChangesType(componentType, true), Unit.INSTANCE);
                            //$$ }
                        //$$ }
//$$
                        //$$ return (Map) reference2ObjectMap;
                    //$$ });
                    //#else
                    (changes) -> {
                        Reference2ObjectMap<ComponentChangesType, ?> reference2ObjectMap =
                                new Reference2ObjectArrayMap<>(changes.size());
                        var var2 = changes.entrySet().iterator();

                        while (var2.hasNext()) {
                            Map.Entry<DataComponentType<?>, Optional<?>> entry = var2.next();
                            DataComponentType<?> componentType = entry.getKey();
                            if (!componentType.isTransient()) {
                                Optional<?> optional = entry.getValue();
                                if (optional.isPresent()) {
                                    ((Map) reference2ObjectMap)
                                            .put(new ComponentChangesType(componentType, false), optional.get());
                                } else {
                                    ((Map) reference2ObjectMap)
                                            .put(new ComponentChangesType(componentType, true), Unit.INSTANCE);
                                }
                            }
                        }

                        return (Map) reference2ObjectMap;
                    });
                    //#endif

    MapCodec<ItemStack> ITEM_STACK_MAP_CODEC = MapCodec.recursive("ItemStack", (codec) -> {
        return RecordCodecBuilder.mapCodec((instance) -> {
            return instance.group(
                            Item.CODEC.fieldOf("id").forGetter(ItemStack::typeHolder),
                            Codec.INT.fieldOf("count").orElse(1).forGetter(ItemStack::getCount),
                            VItem.COMPONENT_CHANGES_CODEC
                                    .optionalFieldOf("components", DataComponentPatch.EMPTY)
                                    .forGetter((stack) -> {
                                        return stack.components.asPatch();
                                    }))
                    .apply(instance, ItemStack::new);
        });
    });

    Codec<ItemStack> ITEM_STACK_CODEC = Codec.lazyInitialized(ITEM_STACK_MAP_CODEC::codec);
}
