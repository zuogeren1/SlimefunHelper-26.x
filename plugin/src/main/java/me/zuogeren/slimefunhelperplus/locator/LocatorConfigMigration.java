package me.zuogeren.slimefunhelperplus.locator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.ConfigLoader;
import me.matl114.plugins.api.PluginHosts;
import me.matl114.utils.Debug;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 把定位栏反推的 5 个配置从宿主 {@code sfhelper-configs/render.yml} 搬到插件自己的
 * {@code config/slimefunhelper-plus/locator.yml}，只搬一次。
 *
 * <p>源文件里这些键埋在 {@code player-info.name-list.*} 下面（宿主 {@code NameList} 的配置段），
 * 目标文件里是独立的顶层段 {@code locator-bar}，所以不像 conflux 那样能整段复制文本 ——
 * 这里用宿主现成的 {@link ConfigLoader#loadYamlConfig} 读值，再按标量重新拼一段 YAML。
 * 5 个键全是布尔/整数/小数，不需要处理转义。
 */
final class LocatorConfigMigration {

    private static final String TARGET_FILE = "slimefunhelper-plus/locator.yml";
    private static final String LEGACY_FILE = "sfhelper-configs/render.yml";
    private static final String MARKER_FILE = "slimefunhelper-plus/.locator-migrated";
    private static final String SECTION = "locator-bar";

    private static final String[] LEGACY_PARENT = {"player-info", "name-list"};
    private static final String[] KEYS = {
        "locator-track",
        "locator-track-min-span",
        "locator-track-min-baseline",
        "locator-track-max-age",
        "locator-track-debug"
    };

    private LocatorConfigMigration() {}

    /** 迁移（必要时）后加载插件配置。 */
    static Config loadConfig() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        run(configDir);
        return PluginHosts.context().config().load(TARGET_FILE, "sfhelperplus-locator");
    }

    static void run(Path configDir) {
        Path marker = configDir.resolve(MARKER_FILE);
        if (Files.exists(marker)) {
            return;
        }
        Path target = configDir.resolve(TARGET_FILE);
        Path legacy = configDir.resolve(LEGACY_FILE);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(legacy)) {
                Map<String, Object> values = readLegacy(legacy);
                if (values != null) {
                    StringBuilder text = new StringBuilder(SECTION).append(":\n");
                    boolean any = false;
                    for (String key : KEYS) {
                        Object value = values.get(key);
                        if (value == null) {
                            continue;
                        }
                        text.append("  ").append(key).append(": ").append(format(value)).append('\n');
                        any = true;
                    }
                    if (any) {
                        Files.writeString(target, text.toString(), StandardCharsets.UTF_8);
                        Debug.info("[SFH+] 已迁移定位栏配置: " + LEGACY_FILE + " -> " + TARGET_FILE);
                    }
                }
            }
            Files.writeString(marker, "1", StandardCharsets.UTF_8);
        } catch (Throwable e) {
            Debug.info("[SFH+] 定位栏配置迁移失败，沿用默认值: " + e.getMessage());
        }
    }

    /** 读到 {@code player-info.name-list} 这一层；结构不对就当没有旧配置。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readLegacy(Path legacy) {
        Map<String, Object> root = ConfigLoader.loadYamlConfig(legacy.toFile());
        Object node = root;
        for (String part : LEGACY_PARENT) {
            if (!(node instanceof Map)) {
                return null;
            }
            node = ((Map<String, Object>) node).get(part);
        }
        return node instanceof Map ? (Map<String, Object>) node : null;
    }

    /** 只处理布尔/整数/小数；其它类型按字符串加引号兜底。 */
    private static String format(Object value) {
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Double || value instanceof Float) {
            return value.toString();
        }
        String text = String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + text + "\"";
    }
}
