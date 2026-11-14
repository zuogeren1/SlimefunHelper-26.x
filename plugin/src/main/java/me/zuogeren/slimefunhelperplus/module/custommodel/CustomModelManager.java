package me.zuogeren.slimefunhelperplus.module.custommodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.crypto.YsmCrypt;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.YSMBinaryDeserializer;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ModelRuntime;
import me.zuogeren.slimefunhelperplus.module.custommodel.texture.ModelTexture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * 本地模型的生命周期：按配置里的文件名读取、解密、反序列化、注册贴图、构建 {@link ModelRuntime}。
 *
 * <p>只保留一份「自己的模型」；重载用版本号丢弃过期结果，避免快速切换时旧线程覆盖新模型。
 */
public final class CustomModelManager {

    private static final AtomicInteger VERSION = new AtomicInteger();

    private static volatile ModelRuntime runtime;
    private static volatile String loadedFile = "";

    private CustomModelManager() {}

    public static ModelRuntime runtime() {
        return runtime;
    }

    public static String loadedFile() {
        return loadedFile;
    }

    public static void reloadAsync(String fileName) {
        int version = VERSION.incrementAndGet();
        Thread thread = new Thread(() -> reload(fileName, version), "SFH+ model load");
        thread.setDaemon(true);
        thread.start();
    }

    public static void clear() {
        VERSION.incrementAndGet();
        runtime = null;
        loadedFile = "";
    }

    private static void reload(String fileName, int version) {
        if (fileName == null || fileName.isBlank()) {
            if (VERSION.get() == version) {
                runtime = null;
                loadedFile = "";
            }
            return;
        }
        try {
            Path dir = FabricLoader.getInstance().getConfigDir()
                    .resolve("slimefunhelper-plus")
                    .resolve("models");
            Path file = dir.resolve(fileName);
            byte[] clear = YsmCrypt.decryptYsmFile(Files.readAllBytes(file));
            RawYsmModel model = new YSMBinaryDeserializer(clear).deserialize();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            // 贴图注册与 DynamicTexture 上传必须在渲染线程，且要等 GPU 设备初始化完成
            minecraft.execute(() -> apply(model, fileName, version));
        } catch (Throwable throwable) {
            if (VERSION.get() == version) {
                runtime = null;
                loadedFile = "";
            }
            Debug.info("[SFH+] 模型加载异常 " + fileName + ": " + throwable);
        }
    }

    private static void apply(RawYsmModel model, String fileName, int version) {
        if (VERSION.get() != version) {
            return;
        }
        RawYsmModel.RawTexture texture = pickTexture(model);
        Identifier textureId = ModelTexture.register(texture);
        CustomModelModule module = CustomModelModule.INSTANCE;
        boolean hideUnreferenced = module == null || !module.showProps.get();
        ModelRuntime loaded = textureId == null ? null : ModelRuntime.build(model, textureId, hideUnreferenced);
        if (VERSION.get() != version) {
            return;
        }
        runtime = loaded;
        loadedFile = loaded == null ? "" : fileName;
        if (loaded == null) {
            Debug.info("[SFH+] 模型加载失败（无可用贴图或几何）: " + fileName);
        } else {
            Debug.info("[SFH+] 模型已加载 " + fileName + " bones=" + loaded.boneCount() + " texture=" + textureId);
        }
    }

    private static RawYsmModel.RawTexture pickTexture(RawYsmModel model) {
        if (model.mainEntity == null || model.mainEntity.textures == null) {
            return null;
        }
        RawYsmModel.RawTexture fallback = null;
        for (Map.Entry<String, RawYsmModel.RawTexture> entry : model.mainEntity.textures.entrySet()) {
            RawYsmModel.RawTexture texture = entry.getValue();
            if (texture == null || texture.data == null || texture.data.length == 0) {
                continue;
            }
            if ("default".equals(entry.getKey())) {
                return texture;
            }
            if (fallback == null) {
                fallback = texture;
            }
        }
        return fallback;
    }
}
