package me.zuogeren.slimefunhelperplus.conflux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.matl114.utils.Debug;

/**
 * 一次性迁移：把宿主 {@code config/sfhelper-configs/survival.yml} 里的 {@code conflux-map-extra}
 * 整段搬到插件自己的 {@code config/slimefunhelper-plus/conflux.yml}。
 *
 * <p>模块从宿主迁到插件以后配置换了文件，旧值不会自己跟过来（新文件全是默认值）。
 * 这里只做<b>文本搬运</b>、不解析 YAML：两边是同一套 Ref 序列化出来的，
 * 原样复制就能被 ConfigImpl 读回；解析再重新序列化反而可能改掉
 * {@code nbt:primitivelist:{...}} 这类字符串里的转义。
 *
 * <p>用 {@code .conflux-migrated} 标记文件保证只搬一次 —— 「新文件是不是还是默认值」判断不可靠：
 * 配置系统第一次保存后所有键都会被写出来，之后就和用户手改过的文件没有区别了。
 */
final class ConfluxConfigMigration {

    private static final String SECTION = "conflux-map-extra";
    private static final String TARGET_FILE = "slimefunhelper-plus/conflux.yml";
    private static final String LEGACY_FILE = "sfhelper-configs/survival.yml";
    private static final String MARKER_FILE = "slimefunhelper-plus/.conflux-migrated";

    private ConfluxConfigMigration() {}

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
                List<String> section = extractSection(readLines(legacy));
                if (section != null && !section.isEmpty()) {
                    List<String> merged = replaceSection(readLines(target), section);
                    Files.writeString(target, String.join("\n", merged) + "\n", StandardCharsets.UTF_8);
                    Debug.info("[SFH+] 已迁移 conflux 配置: " + LEGACY_FILE + " -> " + TARGET_FILE + " ("
                            + section.size() + " 行)");
                }
            }
            Files.writeString(marker, "1", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Debug.info("[SFH+] conflux 配置迁移失败，沿用默认值: " + e.getMessage());
        }
    }

    private static List<String> readLines(Path file) throws IOException {
        return Files.exists(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<>();
    }

    /** 取顶层 {@code conflux-map-extra:} 整段（含缩进的子行）；没有就返回 null。 */
    private static List<String> extractSection(List<String> lines) {
        int start = indexOfHeader(lines);
        if (start < 0) {
            return null;
        }
        int end = start + 1;
        while (end < lines.size() && isIndented(lines.get(end))) {
            ++end;
        }
        return new ArrayList<>(lines.subList(start, end));
    }

    /** 写进目标文件：已有同名顶层段就整段替换，否则追加到末尾。 */
    private static List<String> replaceSection(List<String> target, List<String> section) {
        int start = indexOfHeader(target);
        if (start < 0) {
            List<String> result = new ArrayList<>(target);
            if (!result.isEmpty() && !result.get(result.size() - 1).isEmpty()) {
                result.add("");
            }
            result.addAll(section);
            return result;
        }
        int end = start + 1;
        while (end < target.size() && isIndented(target.get(end))) {
            ++end;
        }
        List<String> result = new ArrayList<>(target.subList(0, start));
        result.addAll(section);
        result.addAll(target.subList(end, target.size()));
        return result;
    }

    private static int indexOfHeader(List<String> lines) {
        for (int i = 0; i < lines.size(); ++i) {
            if (lines.get(i).equals(SECTION + ":")) {
                return i;
            }
        }
        return -1;
    }

    /** 属于上一行那一段的缩进行；空行与顶格的注释都算段尾。 */
    private static boolean isIndented(String line) {
        return !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
    }
}
