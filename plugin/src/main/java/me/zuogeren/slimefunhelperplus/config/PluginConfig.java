package me.zuogeren.slimefunhelperplus.config;

import java.nio.file.Path;
import me.matl114.managers.config.Config;
import me.matl114.plugins.api.PluginHosts;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 插件的**唯一**配置 {@code config/slimefunhelper-plus/config.yml}。
 *
 * <p>Conflux 与定位栏共用这一份：宿主「全配置」页里只出现一个 {@code SlimeFunHelper+}，
 * 里面按顶层段分成「Conflux 地图扩展」和「定位栏反推」两块，不再拆成两个分类。
 * 配置名 {@code sfhelperplus} 必须是 ASCII（中文会让 {@code Identifier} 抛异常）。
 */
public final class PluginConfig {

    private static Config config;

    private PluginConfig() {}

    public static synchronized Config get() {
        if (config == null) {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            PluginConfigMigration.run(configDir);
            config = PluginHosts.context().config().load(PluginConfigMigration.TARGET_FILE, "sfhelperplus");
        }
        return config;
    }
}
