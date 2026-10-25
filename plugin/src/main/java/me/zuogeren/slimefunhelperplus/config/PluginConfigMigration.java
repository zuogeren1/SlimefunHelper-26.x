package me.zuogeren.slimefunhelperplus.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.matl114.managers.config.ConfigLoader;
import me.matl114.utils.Debug;

/**
 * 把插件配置收拢成**一份** {@code config/slimefunhelper-plus/config.yml}，只搬一次。
 *
 * <p>来源按优先级：插件自己拆分时期的 {@code conflux.yml} / {@code locator.yml}（用户已经跑过旧版本的
 * 情况）→ 宿主 {@code sfhelper-configs/survival.yml} 的 {@code conflux-map-extra} 段与
 * {@code render.yml} 的 {@code player-info.name-list.locator-*}。
 *
 * <p>conflux 那几个键的值是 {@code nbt:primitivelist:{...}} 这类字符串，**原样复制文本**才不会破坏转义；
 * 定位栏的 5 个键全是布尔/整数/小数，源文件里又是嵌套在 {@code player-info.name-list} 下的，
 * 所以用宿主现成的 YAML 解析读值、再按标量拼一遍。
 */
final class PluginConfigMigration {

    static final String TARGET_FILE = "slimefunhelper-plus/config.yml";

    private static final String MARKER_FILE = "slimefunhelper-plus/.config-migrated";
    private static final String CONFLUX_SECTION = "conflux-map-extra";
    private static final String LOCATOR_SECTION = "locator-bar";

    private static final String OLD_CONFLUX = "slimefunhelper-plus/conflux.yml";
    private static final String OLD_LOCATOR = "slimefunhelper-plus/locator.yml";
    private static final String LEGACY_SURVIVAL = "sfhelper-configs/survival.yml";
    private static final String LEGACY_RENDER = "sfhelper-configs/render.yml";

    private static final String[] LOCATOR_PARENT = {"player-info", "name-list"};
    private static final String[] LOCATOR_KEYS = {
        "locator-track",
        "locator-track-min-span",
        "locator-track-min-baseline",
        "locator-track-max-age",
        "locator-track-debug"
    };

    private PluginConfigMigration() {}

    static void run(Path configDir) {
        Path marker = configDir.resolve(MARKER_FILE);
        if (Files.exists(marker)) {
            return;
        }
        Path target = configDir.resolve(TARGET_FILE);
        try {
            Files.createDirectories(target.getParent());
            List<String> merged = new ArrayList<>();
            List<String> conflux = confluxSection(configDir);
            if (conflux != null && !conflux.isEmpty()) {
                merged.addAll(conflux);
            }
            List<String> locator = locatorSection(configDir);
            if (locator != null && !locator.isEmpty()) {
                if (!merged.isEmpty()) {
                    merged.add("");
                }
                merged.addAll(locator);
            }
            if (!merged.isEmpty()) {
                Files.writeString(target, String.join("\n", merged) + "\n", StandardCharsets.UTF_8);
                Debug.info("[SFH+] 已生成插件配置 " + TARGET_FILE + "，旧 conflux / 定位栏设置已合并");
            }
            Files.writeString(marker, "1", StandardCharsets.UTF_8);
        } catch (Throwable e) {
            Debug.info("[SFH+] 插件配置迁移失败，沿用默认值: " + e.getMessage());
        }
    }

    /** conflux 段：拆分时期的整份文件 → 宿主 survival.yml 里的同名段。 */
    private static List<String> confluxSection(Path configDir) throws IOException {
        Path old = configDir.resolve(OLD_CONFLUX);
        if (Files.exists(old)) {
            return trimTrailing(readLines(old));
        }
        Path legacy = configDir.resolve(LEGACY_SURVIVAL);
        return Files.exists(legacy) ? extractSection(readLines(legacy), CONFLUX_SECTION) : null;
    }

    /** 定位栏段：拆分时期的 locator.yml → 宿主 render.yml 的 player-info.name-list.locator-*。 */
    private static List<String> locatorSection(Path configDir) throws IOException {
        Path old = configDir.resolve(OLD_LOCATOR);
        if (Files.exists(old)) {
            List<String> section = extractSection(readLines(old), LOCATOR_SECTION);
            if (section != null && !section.isEmpty()) {
                return section;
            }
        }
        Path legacy = configDir.resolve(LEGACY_RENDER);
        return Files.exists(legacy) ? locatorFromLegacy(legacy) : null;
    }

    private static List<String> locatorFromLegacy(Path legacy) {
        Map<String, Object> values = readLegacyLocator(legacy);
        if (values == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add(LOCATOR_SECTION + ":");
        for (String key : LOCATOR_KEYS) {
            Object value = values.get(key);
            if (value != null) {
                lines.add("  " + key + ": " + format(value));
            }
        }
        return lines.size() > 1 ? lines : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readLegacyLocator(Path legacy) {
        try {
            Map<String, Object> root = ConfigLoader.loadYamlConfig(legacy.toFile());
            Object node = root;
            for (String part : LOCATOR_PARENT) {
                if (!(node instanceof Map)) {
                    return null;
                }
                node = ((Map<String, Object>) node).get(part);
            }
            return node instanceof Map ? (Map<String, Object>) node : null;
        } catch (Throwable e) {
            return null;
        }
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

    private static List<String> readLines(Path file) throws IOException {
        return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /** 取顶层 {@code section:} 整段（含缩进的子行）；没有就返回 null。 */
    private static List<String> extractSection(List<String> lines, String section) {
        int start = -1;
        for (int i = 0; i < lines.size(); ++i) {
            if (lines.get(i).equals(section + ":")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = start + 1;
        while (end < lines.size() && isIndented(lines.get(end))) {
            ++end;
        }
        return new ArrayList<>(lines.subList(start, end));
    }

    private static boolean isIndented(String line) {
        return !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
    }

    private static List<String> trimTrailing(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isBlank()) {
            --end;
        }
        return new ArrayList<>(lines.subList(0, end));
    }
}
