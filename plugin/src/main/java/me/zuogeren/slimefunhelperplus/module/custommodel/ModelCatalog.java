package me.zuogeren.slimefunhelperplus.module.custommodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.matl114.utils.Debug;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 模型目录的轻量索引：只记相对路径，扫描与排序都在动作里做。
 *
 * <p>路径统一用 {@code /}，写进配置后 {@code CustomModelManager} 直接 {@code resolve} 即可；
 * 只扫 {@code config/slimefunhelper-plus/models} 下四层，够覆盖按作者分文件夹的存放习惯。
 */
public final class ModelCatalog {

    private static volatile List<String> names = List.of();

    private ModelCatalog() {}

    /** 上一次扫描的结果（只读）。 */
    public static List<String> names() {
        return names;
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
            Debug.info("[SFH+] 无法创建模型目录: " + exception);
        }
        try (var stream = Files.walk(dir, 4)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .forEach(path -> found.add(dir.relativize(path).toString().replace('\\', '/')));
        } catch (Exception exception) {
            Debug.info("[SFH+] 扫描模型目录失败: " + exception);
        }
        found.sort(String.CASE_INSENSITIVE_ORDER);
        names = List.copyOf(found);
        Debug.info("[SFH+] 模型目录扫描: " + names.size() + " 个 (" + dir + ")");
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
