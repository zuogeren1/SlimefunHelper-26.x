package me.matl114.plugins.api;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.ConfigLoader;
import me.matl114.versioned.ModVersion;
import net.fabricmc.loader.api.ModContainer;

/** Entry point implemented by extensions loaded through the {@code slimefunhelper} Fabric entrypoint. */
public interface SlimefunHelperPlugin {
    /**
     * 默认最低宿主版本：取本 jar 构建时的 {@code mod_version}（由 {@code processResources} 写进
     * {@code slimefunhelper-plugin-api.txt}）。这样「插件要求的最低版本」与宿主版本永远同步，
     * 不会因为手工改常量而脱钩；读不到资源时退回硬编码值。
     */
    ModVersion DEFAULT_MINIMUM_VERSION = loadDefaultMinimumVersion();

    private static ModVersion loadDefaultMinimumVersion() {
        ModVersion fallback = ModVersion.parse("1.2.1.1");
        try (InputStream stream = SlimefunHelperPlugin.class.getResourceAsStream("/slimefunhelper-plugin-api.txt")) {
            if (stream == null) {
                return fallback;
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            // 形如 `1.2.1.3` 或 `1.2.1.3-fix1`：修版本号后缀不影响插件 API，截到第一个非「数字/点」字符。
            int end = 0;
            while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                end++;
            }
            return end > 0 ? ModVersion.parse(text.substring(0, end)) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** Called before core configuration is read. Register plugin-owned configs here. */
    public void onConfigBootstrap();

    /** Called after all global listener channels have been initialized. */
    public void onListenerBootstrap();

    /** Called after built-in modules initialize. Register plugin modules here. */
    public void onModuleBootstrap();

    /** Called after all regular bootstrap phases have completed. */
    public void postInitialize();

    /** Returns the mod id that owns this plugin. */
    public String getPluginId();

    /** Returns the minimum SlimefunHelper host version required by this plugin. */
    default ModVersion getMinimumVersion() {
        return DEFAULT_MINIMUM_VERSION;
    }

    default String getId() {
        return getPluginId();
    }

    public ModContainer getContainer();

    /** Registers a plugin module group in the global module registry. */
    default void registerModuleGroup(ModuleGroup group) {
        PluginHosts.context().modules().registerGroup(getPluginId(), group);
    }

    default <T extends Config> T loadRuntimeConfig(String fileName, String customName) {
        return (T) ConfigLoader.loadRuntimeConfig(fileName, customName).markForSave();
    }

    /** Makes a plugin config visible to the global config screen and reload lifecycle. */
    default <T extends Config> T registerConfig(T config) {
        return PluginHosts.context().config().register(config);
    }

    default void onDiscovered(ModContainer container) {}
}
