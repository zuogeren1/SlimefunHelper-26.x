package me.matl114.gui.presets.single;

import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import lombok.AllArgsConstructor;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.gui.basic.RenderHandler;
import me.matl114.gui.basic.TextAlign;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.renderer.TextRenderers;
import me.matl114.utils.ItemStackUtils;
import me.matl114.utils.RegistryUtils;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleResources;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.CommonColors;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

public class RegistryDisplays {

    // 26.2: ItemStack 必须在组件绑定之后才能构造，不能在 <clinit> 里 new。
    private static ItemStack defaultNullIcon = null;

    public static ItemStack defaultNullIcon() {
        if (defaultNullIcon == null && ItemStackUtils.itemComponentsBound()) {
            defaultNullIcon = new ItemStack(Items.BARRIER);
        }
        return defaultNullIcon == null ? ItemStack.EMPTY : defaultNullIcon;
    }

    public static ItemStack createEnchantmentIcon(Enchantment enchantment) {
        if (!ItemStackUtils.itemComponentsBound()) {
            return ItemStack.EMPTY;
        }
        ItemStack itemStack = new ItemStack(Items.ENCHANTED_BOOK);
        Holder<Enchantment> entry =
                RegistryUtils.getRegistryEntry(ItemStackUtils.registry(), Registries.ENCHANTMENT, enchantment);

        if (entry == null) {
            return itemStack;
        }
        var builder = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        builder.upgrade(entry, 1);
        ItemEnchantments component = builder.toImmutable();
        itemStack.set(DataComponents.STORED_ENCHANTMENTS, component);
        return itemStack;
    }

    public static <T> Component getDisplay(Registry<T> registry, @Nonnull T val) {
        Component re = guessTranslation(val);
        if (re != null) return re;
        Identifier id = registry.getKey(val);
        if (id != null) {
            return Component.translatable(registry.key().identifier().getPath() + ".minecraft." + id.getPath());
        }
        return Component.literal(val.toString());
    }

    public static <T> Component getDisplay(@Nonnull T val) {
        Component re = guessTranslation(val);
        if (re != null) return re;
        ResourceKey<? extends Registry<T>> registry = RegistryUtils.getRegistryTypeKey(val);
        if (registry != null) {
            Registry<T> re2 = BuiltInRegistries.REGISTRY.getValue((ResourceKey) registry);
            if (re2 != null) {
                Identifier id = re2.getKey(val);
                if (id != null) {
                    return Component.translatable(registry.identifier().getPath() + ".minecraft." + id.getPath());
                }
            }
        }
        return Component.literal(val.toString());
    }

    private static <T> Component guessTranslation(T val) {
        if (val instanceof MobEffect effect) {
            return effect.getDisplayName();
        } else if (val instanceof Attribute attribute) {
            return Component.translatable(attribute.getDescriptionId());
        } else if (val instanceof Enchantment enchantment) {
            return enchantment.description();
        } else if (val instanceof BlockEntityType<?> blockEntityType) {
            return Component.literal(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType).getPath());
        } else if (val instanceof EntityType<?> entityType) {
            return Component.translatable(entityType.getDescriptionId());
        } else if (val instanceof Item itemConvertible) {
            return ItemStackUtils.itemComponentsBound()
                    ? itemConvertible.getName(new ItemStack(itemConvertible))
                    : Component.translatable(itemConvertible.getDescriptionId());
        } else if (val instanceof Block itemStack) {
            return itemStack.getName();
        } else if (val instanceof SoundEvent soundEvent) {
            return Component.translatable("subtitles." + soundEvent.location().getPath());
        } else if (val instanceof Potion potionType) {
            return Component.translatable(Items.POTION.getDescriptionId() + ".effect." + potionType.name());
        }
        return null;
    }

    public static <T> RenderHandler of(Registry<T> registry, T value, Component name, Identifier identifier) {
        // Class<?> clazz = value.getClass();
        IIcon<T> icon = getIcon(registry); // (IIcon<T>) TYPE_TO_ICON_MAP.getOrDefault(clazz, IIcon.EMPTY);
        return new IEntry<>(name, identifier, icon, value);
    }

    // 26.2: ItemStack 必须在组件绑定之后才能构造（Holder.components() 会抛
    // "Components not bound yet"），因此不能在 <clinit> 里 new，改为按需创建。
    private static final RandomSource RAND = new SingleThreadedRandomSource(999);
    public static Map<Class<?>, IIcon<?>> TYPE_TO_ICON_MAP = ImmutableMap.<Class<?>, IIcon<?>>builder()
            .put(Item.class, IIcon.<ItemLike>renderItem(ItemStack::new))
            .put(Block.class, IIcon.<ItemLike>renderItem(ItemStack::new))
            .put(Attribute.class, IIcon.renderItem((v) -> new ItemStack(Items.ANVIL)))
            .put(Enchantment.class, IIcon.renderItem(RegistryDisplays::createEnchantmentIcon))
            .put(BlockEntityType.class, IIcon.<BlockEntityType<?>>renderItem(s -> {
                if (s.validBlocks.isEmpty()) return new ItemStack(Items.AIR);
                List<Block> blockList = s.validBlocks.stream().toList();
                int select = ((int) (System.currentTimeMillis() / 1000) % blockList.size());
                return new ItemStack(blockList.get(select));
            }))
            .put(EntityType.class, IIcon.<EntityType<?>>renderItem((v) -> {
                Item item = EntityUtils.entityToSpawnEgg(v);
                return new ItemStack(item == null ? Items.PIG_SPAWN_EGG : item);
            }))
            .put(MobEffect.class, IIcon.<MobEffect>renderSprite(effect -> {
                Holder<MobEffect> entry = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect);
                return getEffectTexture(entry);
            }))
            .put(Potion.class, IIcon.<Potion>renderItem((v) -> {
                return PotionContents.createItemStack(Items.POTION, BuiltInRegistries.POTION.wrapAsHolder(v));
            }))
            .put(ParticleType.class, IIcon.<ParticleType<?>>renderSprite((v) -> {
                Identifier id = BuiltInRegistries.PARTICLE_TYPE.getKey(v);
                ParticleResources manager = Minecraft.getInstance().particleEngine.resourceManager;
                var re = manager.spriteSets;
                var what = re.get(id);
                if (what != null) {
                    return what.get(RAND);
                } else {
                    return null;
                }
            }))
            .build();

    public static <T> IIcon<T> getIcon(Class<T> registryClass) {
        return (IIcon<T>) TYPE_TO_ICON_MAP.getOrDefault(registryClass, IIcon.EMPTY);
    }

    public static <T> IIcon<T> getIcon(Registry<T> registryClass) {
        return (IIcon<T>) TYPE_TO_ICON_MAP.getOrDefault(RegistryUtils.getRegistryType(registryClass), IIcon.EMPTY);
    }

    @AllArgsConstructor
    public static class IEntry<T> implements RenderHandler {
        Component name;
        Identifier identifier;
        IIcon<T> icon;
        T value;
        // this render should be 20 high
        @Override
        public void renderAtCentered(
                DrawableWidget element,
                VDrawContext context,
                int mouseX,
                int mouseY,
                float delta,
                float alpha,
                boolean shouldHighlight) {
            int startIndexX = (element.getTextureHeight() - 16) / 2;
            int startIndexY = startIndexX;
            icon.render(startIndexX, startIndexY, context, value);
            RenderHandler.drawScaledText0(
                    context, TextRenderers.get(), name, 20, 1, 200, 10, CommonColors.WHITE, TextAlign.LEFT);
            RenderHandler.drawScaledText0(
                    context,
                    TextRenderers.get(),
                    Component.literal(identifier.toString()),
                    20,
                    10,
                    200,
                    19,
                    CommonColors.WHITE,
                    TextAlign.LEFT);
        }
    }

    public static Identifier getEffectTexture(Holder<MobEffect> effect) {
        return (Identifier) effect.unwrapKey()
                .map(ResourceKey::identifier)
                .map((id) -> {
                    return id.withPrefix("mob_effect/");
                })
                .orElseGet(MissingTextureAtlasSprite::getLocation);
    }
}
