package me.matl114.hacks.utils.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.Optional;
import lombok.With;
import me.matl114.managers.config.NBTParsable;
import me.matl114.managers.config.NBTType;
import me.matl114.utils.ItemStackUtils;
import me.matl114.utils.config.WrapperFactory;
import me.matl114.utils.config.kv.RegistryAttrKeyValue;
import me.matl114.utils.config.kv.TypeConvertAttrKeyValue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantments;

@With
public record WeakHolder<T>(Identifier registry, Identifier location) implements NBTParsable<WeakHolder<T>> {
    public static final Identifier DEFAULT_KEY = new Identifier("minecraft", "default");

    public static <W> Class<WeakHolder<W>> parameter() {
        return (Class) WeakHolder.class;
    }

    public WeakHolder(ResourceKey<T> registryKey) {
        this(registryKey.registry(), registryKey.identifier());
    }

    private static final Minecraft mc = Minecraft.getInstance();
    public static NBTType<WeakHolder> TYPE = new NBTType<>(
            "weakholder",
            Codec.STRING.comapFlatMap(WeakHolder::parse, WeakHolder::asString),
            (factory, w, x, y, dx, dy) -> {
                Identifier registry = w.get().registry();
                var handler = mc.getConnection();
                Optional<Registry<Object>> optionalLookup;
                if (handler != null && handler.registryAccess() != null) {
                    var registryLookup = handler.registryAccess();
                    optionalLookup = registryLookup.lookup(ResourceKey.createRegistryKey(registry));
                } else {
                    optionalLookup = Optional.empty();
                }
                var wrapper = new TypeConvertAttrKeyValue<>(
                        w,
                        WrapperFactory.of(s -> new WeakHolder<>(registry, s), WeakHolder::location),
                        NBTTypes.IDENTIFIER_TYPE);
                if (optionalLookup.isPresent()) {
                    Registry<Object> lookupValue = optionalLookup.get();
                    return RegistryAttrKeyValue.generateTextInputWithRegistrySearch(
                            factory, lookupValue, wrapper, x, y, dx, dy);
                } else {
                    return wrapper.generateValueWidget(factory, x, y, dx, dy);
                }
            },
            new WeakHolder(Enchantments.AQUA_AFFINITY));

    public static <T> DataResult<WeakHolder<T>> parse(String s) {
        String[] split = s.split("\\|");
        if (split.length == 2) {
            Identifier identifier = Identifier.tryParse(split[0]);
            Identifier location = Identifier.tryParse(split[1]);
            if (identifier != null && location != null) {
                return DataResult.success(new WeakHolder<>(identifier, location));
            } else {
                return DataResult.error(() -> "Invalid format");
            }
        } else {
            return DataResult.error(() -> "Invalid format");
        }
    }

    public String asString() {
        return registry + "|" + location;
    }

    @Override
    public NBTType<WeakHolder<T>> type() {
        return TYPE.cast();
    }

    public ResourceKey<T> toRegistryKey() {
        return ResourceKey.create(ResourceKey.createRegistryKey(registry), location);
    }

    public Optional<Holder<T>> getEntry() {
        return ItemStackUtils.registry().get(toRegistryKey()).map(s -> s);
    }
}
