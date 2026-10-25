package me.matl114.managers.config;

import java.util.*;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import me.matl114.plugins.internal.MainPlugin;
import me.matl114.plugins.api.SlimefunHelperPlugin;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

public abstract class AbstractConfig implements Config {
    protected MapRef ref;
    protected LinkedHashSet<String> buildOrder = new LinkedHashSet<>();

    @Getter
    @Nullable
    ResourceKey<Config> registryKey;

    @Getter
    protected final String configName;

    public String getTranslationKey() {
        if (getRegistryKey() == null) {
            return "config.index." + configName.toLowerCase(Locale.ROOT).replace(" ", "_");
        }
        return "config.index." + this.registryKey.identifier().getPath();
    }

    public void registerGlobal(SlimefunHelperPlugin plugin) {
        if (registryKey == null) {
            // 插件自有配置经 PluginContext#config().load(...) 注册，拿不到宿主插件实例，命名空间回落到宿主 id
            String namespace = plugin == null ? MainPlugin.ID : plugin.getId();
            this.registryKey = ResourceKey.create(
                    REGISTRY.key(),
                    Identifier.fromNamespaceAndPath(
                            namespace, configName.toLowerCase(Locale.ROOT).replace(" ", "_")));
        }
        Config.super.registerGlobal(plugin);
    }

    public AbstractConfig(String name, Map<String, Object> rawMap) {
        this.configName = name;
        this.ref = Refs.transferConfig(rawMap);
        this.ref.setConfigReference(this);
    }

    public AbstractConfig(String name) {
        this.configName = name;
        this.ref = new MapRef();
        this.ref.setConfigReference(this);
    }

    public boolean setValue(Object value, @Nonnull String... path) {
        Ref refo = Refs.wrapInstance(value);
        boolean update = this.ref.setValue(refo, path);
        if (update) {
            markForSave();
        }
        return update;
    }

    public Ref<?> get(@Nonnull String... path) {
        var re = this.ref.get(path);
        if (re != null) {
            re.setConfigReference(this);
        }
        return re;
    }

    public <T> Config validator(Predicate<T> validator, String... path) {
        Ref<T> ref = (Ref<T>) get(path);
        ref.addValidator(validator);
        return this;
    }

    public Ref getOrCreate(Ref defaultValue, @Nonnull String... path) {
        Ref result = this.ref.getOrCreate(defaultValue, path);
        if (result != null) {
            result.setConfigReference(this);
        }
        if (result == defaultValue) {
            markForSave();
            return defaultValue;
        } else if (result == null) {
            throw new IllegalArgumentException(
                    "create fail ref validation: " + Arrays.stream(path).toList());
        }

        return result;
    }

    @Getter
    protected boolean markForSave = false;

    public Config markForSave() {
        markForSave = true;
        return this;
    }

    @Nonnull
    public Set<String> getKeys() {
        return ref.getKeys();
    }

    @Nonnull
    public Set<String> getKeys(@Nonnull String... path) {
        var subMap = ref.get(path);
        if (subMap instanceof MapRef mapRef) {
            return mapRef.getKeys();
        } else {
            return Set.of();
        }
    }

    public Set<String> getVisiblePaths() {
        return buildOrder;
    }

    @Override
    public void visitPath(String path) {
        buildOrder.add(path);
    }

    public MapRef asRef() {
        return this.ref;
    }
}
