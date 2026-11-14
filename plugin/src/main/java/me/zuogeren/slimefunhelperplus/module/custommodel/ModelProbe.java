package me.zuogeren.slimefunhelperplus.module.custommodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.crypto.YsmCrypt;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.YSMBinaryDeserializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * P2 的临时探针：扫描模型目录，把每个 {@code .ysm} 解密并反序列化，日志输出结构统计。
 *
 * <p>只用于验证格式层移植是否与真实语料一致；P3 接入渲染后由模型管理器取代。
 */
public final class ModelProbe {

    private ModelProbe() {}

    public static void scanAsync() {
        Thread thread = new Thread(ModelProbe::scan, "SFH+ model probe");
        thread.setDaemon(true);
        thread.start();
    }

    private static void scan() {
        Path dir = FabricLoader.getInstance().getConfigDir()
                .resolve("slimefunhelper-plus")
                .resolve("models");
        if (!Files.isDirectory(dir)) {
            Debug.info("[SFH+] 模型目录不存在: " + dir);
            return;
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(dir, 2)) {
            stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .forEach(files::add);
        } catch (Exception exception) {
            Debug.info("[SFH+] 扫描模型目录失败: " + exception);
            return;
        }
        Collections.sort(files);
        int ok = 0;
        int fail = 0;
        long start = System.nanoTime();
        for (Path file : files) {
            try {
                byte[] clear = YsmCrypt.decryptYsmFile(Files.readAllBytes(file));
                RawYsmModel model = new YSMBinaryDeserializer(clear).deserialize();
                RawYsmModel.RawGeometry geometry = model.mainEntity == null ? null : model.mainEntity.mainModel;
                int bones = geometry == null || geometry.bones == null ? 0 : geometry.bones.size();
                int cubes = 0;
                if (geometry != null && geometry.bones != null) {
                    for (RawYsmModel.RawBone bone : geometry.bones) {
                        cubes += bone.cubes == null ? 0 : bone.cubes.size();
                    }
                }
                int textures = model.mainEntity == null || model.mainEntity.textures == null ? 0 : model.mainEntity.textures.size();
                int animations = model.mainEntity == null || model.mainEntity.animationFiles == null ? 0 : model.mainEntity.animationFiles.size();
                Debug.info("[SFH+] model " + file.getFileName() + " fmt=" + model.formatVersion
                        + " bones=" + bones + " cubes=" + cubes + " textures=" + textures + " anims=" + animations);
                ok++;
            } catch (Throwable throwable) {
                fail++;
                Debug.info("[SFH+] 模型解析失败 " + file.getFileName() + ": " + throwable);
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        Debug.info("[SFH+] 模型探测完成: ok=" + ok + " fail=" + fail + " 用时=" + millis + "ms");
    }
}
