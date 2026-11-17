package me.zuogeren.slimefunhelperplus.module.custommodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 模型目录的轻量索引：只记相对路径，扫描与排序都在动作里做。
 *
 * <p>路径统一用 {@code /}，写进配置后 {@code CustomModelManager} 直接 {@code resolve} 即可；
 * 只扫 {@code config/slimefunhelper-plus/models} 下四层，够覆盖按作者分文件夹的存放习惯。
 */
public final class ModelCatalog {

    private static volatile List<String> names = List.of();
    /** 解析后回填的显示名：模型文件 → metadata 里的名字。扫描阶段只有文件名，名字要等反序列化之后才有。 */
    private static volatile Map<String, String> displayNames = Map.of();

    private ModelCatalog() {}

    /** 上一次扫描的结果（只读）。 */
    public static List<String> names() {
        return names;
    }

    /**
     * 界面上的模型名：解析过的模型用 metadata 里的名字，没解析过或模型没写名字时退回文件名。
     *
     * <p>读的是回填表而不是扫描结果 —— 扫描只看文件名，名字要等解密反序列化之后才知道。
     */
    public static String displayName(String file) {
        if (file == null || file.isEmpty()) {
            return "";
        }
        String name = displayNames.get(file);
        return name == null || name.isEmpty() ? file : name;
    }

    /** 解析成功后回填显示名；空名字不记，界面自然退回文件名。 */
    public static void putDisplayName(String file, String name) {
        if (file == null || file.isEmpty() || name == null) {
            return;
        }
        String text = stripFormatting(name).trim();
        if (text.isEmpty()) {
            return;
        }
        Map<String, String> next = new HashMap<>(displayNames);
        next.put(file, text);
        displayNames = Map.copyOf(next);
    }

    /** 模型名里带 {@code §} 颜色码，界面自己定墨色，这里只留文字。 */
    static String stripFormatting(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    public static void rescan() {
        Path dir = FabricLoader.getInstance().getConfigDir()
                .resolve("slimefunhelper-plus")
                .resolve("models");
        List<String> found = new ArrayList<>();
        // 首次启动时目录还不存在：建出来再扫，不然每次扫描都会抛 NoSuchFileException 刷屏。
        try {
            Files.createDirectories(dir);
        } catch (Exception exception) {
            CustomModelModule.logDebug("[SFH+] 无法创建模型目录: " + exception);
        }
        try (var stream = Files.walk(dir, 4)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .forEach(path -> found.add(dir.relativize(path).toString().replace('\\', '/')));
        } catch (Exception exception) {
            CustomModelModule.logDebug("[SFH+] 扫描模型目录失败: " + exception);
        }
        found.sort(String.CASE_INSENSITIVE_ORDER);
        names = List.copyOf(found);
        CustomModelModule.logDebug("[SFH+] 模型目录扫描: " + names.size() + " 个 (" + dir + ")");
    }

    /** 按目录顺序取下一个；没有结果时先重扫一次。 */
    public static String cycle(String current) {
        List<String> list = names;
        if (list.isEmpty()) {
            rescan();
            list = names;
        }
        if (list.isEmpty()) {
            return current;
        }
        int index = list.indexOf(current);
        return list.get((index + 1) % list.size());
    }
}
