package me.matl114.managers.config;

import com.mojang.serialization.Lifecycle;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import me.matl114.managers.ScheduleService;
import me.matl114.plugins.MainPlugin;
import me.matl114.plugins.SlimefunHelperPlugin;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.resources.ResourceKey;

public interface Config extends RefMap {
    static final Logger logger = Logger.getLogger(MainPlugin.ID);

    default Config registerAutoSave() {
        Holder.autoSaveConfigs.add(this);
        return this;
    }

    default void registerGlobal(SlimefunHelperPlugin plugin) {
        configs.add(this);
        ResourceKey<Config> reKey = this.getRegistryKey();
        // 上游是 SimpleRegistry#add；26.2 只有 MappedRegistry#register(key, value, RegistrationInfo)，
        // 且 loadConfigs 可被重复调用（重载资源），重复注册要先拦住
        if (reKey != null && !REGISTRY.containsKey(reKey)) {
            REGISTRY.register(reKey, this, RegistrationInfo.BUILT_IN);
        }
    }

    /**
     * 我方 MainTasks / ClickGui 仍用 Config.getConfigs() 取全局配置表，上游已改成遍历 Config.REGISTRY。
     * 这些调用点不在本批白名单内，先保留兼容集合，等它们迁移后再删。
     */
    Set<Config> configs = new LinkedHashSet<>();

    public static Set<Config> getConfigs() {
        return configs;
    }
    // names and keys
    @Nullable
    public ResourceKey<Config> getRegistryKey();

    public String getTranslationKey();

    public String getConfigName();

    // dirty flag
    public Config markForSave();

    public boolean isMarkForSave();
    // save operation
    public void save();
    // reload operation
    public void reload();

    // get the definition order
    public Set<String> getVisiblePaths();
    // pre define a path
    public void visitPath(String path);

    // get or create a node,
    public <T> Ref<T> getOrCreate(Ref<T> defaultValue, @Nonnull String... path);

    public <T> boolean setValue(T value, @Nonnull String... path);

    // getters

    @Nullable
    default StringRef getString(@Nonnull String... path) {
        Object node = get(path);
        if (node instanceof StringRef ref) {
            return ref;
        } else {

            return null;
        }
    }

    default KeyBindRef getKeyBind(String... path) {
        Object node = get(path);
        if (node instanceof KeyBindRef ref) {
            return ref;
        } else {

            return null;
        }
    }

    default ListRef getList(String... path) {
        Object node = get(path);
        if (node instanceof ListRef ref) {
            return ref;
        } else {
            return null;
        }
    }

    default <T extends ConfigEnum> EnumRef<T> getEnum(@Nonnull String... path) {
        Object node = get(path);
        if (node instanceof EnumRef<?> enumRef) {
            return (EnumRef<T>) enumRef;
        } else {
            return null;
        }
    }

    default IntRef getInt(@Nonnull String... path) {
        Object node = get(path);
        if (node instanceof IntRef ref) {
            return ref;
        } else {
            return null;
        }
    }

    default DoubleRef getDouble(String... path) {
        Object node = get(path);
        if (node instanceof DoubleRef ref) {
            return ref;
        } else {
            return null;
        }
    }

    default FlagRef getBoolean(@Nonnull String... path) {
        Object node = get(path);
        if (node instanceof FlagRef ref) {
            return ref;
        } else {
            return null;
        }
    }

    public static final MappedRegistry<Config> REGISTRY = new MappedRegistry<>(
            ResourceKey.createRegistryKey(MainPlugin.identifier("configs")), Lifecycle.stable());

    public static void launchSaveTasks() {
        ScheduleService.launchAsyncDelayedTask(Config.Holder::configSaveTasks, 1000);
    }

    public static void reloadAll() {
        Holder.reloadAll();
    }

    public static String[] cutToPath(String rawPath) {
        return rawPath.split("\\.");
    }

    static class Holder {
        @Getter
        private static final Set<Config> autoSaveConfigs = new LinkedHashSet<>();

        private static final long SAVE_TIME = 1000 * 15;

        static {
            ScheduleService.launchAsyncRepeatTask(Config.Holder::configSaveTasks, SAVE_TIME, SAVE_TIME);
            Runtime.getRuntime().addShutdownHook(new Thread(Config.Holder::configSaveTasks, "Config-Shutdown-Save"));
        }

        static void configSaveTasks() {
            for (var config : autoSaveConfigs) {
                config.save();
            }
        }

        static void reloadAll() {
            autoSaveConfigs.forEach(v -> {
                // only reload the not dirty configs
                // and now trigger save
                if (v.isMarkForSave()) {
                    v.save();
                } else {
                    // may be ...
                    v.reload();
                }
            });
        }
    }
}
