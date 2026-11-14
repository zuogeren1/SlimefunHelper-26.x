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
    /** 第一人称手臂（{@code mainEntity.armModel}）；模型没有手部几何时是 null。 */
    private static volatile ModelRuntime armRuntime;
    private static volatile String loadedFile = "";
    /** 轮盘选择：服装预设与额外动作各记一份；空串 = 没选（新模型自动用默认服装）。 */
    private static volatile String selectedVariant = "";
    private static volatile String selectedExtra = "";

    private static final String NO_EXTRA = "__none__";

    private CustomModelManager() {}

    public static ModelRuntime runtime() {
        return runtime;
    }

    public static ModelRuntime armRuntime() {
        return armRuntime;
    }

    public static String loadedFile() {
        return loadedFile;
    }

    public static String selectedExtra() {
        return selectedExtra;
    }

    /** 轮盘点一条：同一项再点一次取消；服装与动作各自独立。 */
    public static void selectExtra(String key) {
        ModelRuntime current = runtime;
        if (current == null) {
            return;
        }
        if (key == null || key.isEmpty() || NO_EXTRA.equals(key)) {
            current.clearAll();
        } else {
            current.toggle(key);
        }
        selectedVariant = current.activeVariantKey();
        selectedExtra = current.activeExtraKey();
        Debug.info("[SFH+] 轮盘选择: 服装=" + (selectedVariant.isEmpty() ? "默认" : selectedVariant)
                + " 动作=" + (selectedExtra.isEmpty() ? "无" : selectedExtra));
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
        armRuntime = null;
        loadedFile = "";
    }

    /**
     * 配置里的模型路径可能被用户写成带引号的绝对路径，直接喂给 Path 会抛 InvalidPathException。
     * 这里统一去掉首尾空白与成对引号。
     */
    private static String normalizeModelPath(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                text = text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }

    private static void reload(String rawFileName, int version) {
        final String fileName = normalizeModelPath(rawFileName);
        if (fileName.isBlank()) {
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
                armRuntime = null;
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
        armRuntime = textureId == null ? null : ModelRuntime.buildArm(model, textureId);
        loadedFile = loaded == null ? "" : fileName;
        if (loaded == null) {
            Debug.info("[SFH+] 模型加载失败（无可用贴图或几何）: " + fileName);
        } else {
            applyExtraSelection(loaded);
            Debug.info("[SFH+] 模型已加载 " + fileName + " bones=" + loaded.boneCount()
                    + " arm=" + (armRuntime == null ? "-" : armRuntime.boneCount())
                    + " variant=" + (loaded.activeVariantKey().isEmpty() ? "-" : loaded.activeVariantKey())
                    + " extra=" + (loaded.activeExtraKey().isEmpty() ? "-" : loaded.activeExtraKey())
                    + " texture=" + textureId);
        }
    }

    /** 模型换了以后把选择接上：服装预设不存在就退回默认服装；动作不存在就丢掉。 */
    private static void applyExtraSelection(ModelRuntime target) {
        if (!selectedVariant.isEmpty()) {
            target.setVariant(selectedVariant);
        }
        if (selectedVariant.isEmpty() || !selectedVariant.equals(target.activeVariantKey())) {
            target.setVariant(target.defaultVariantKey());
        }
        selectedVariant = target.activeVariantKey();
        if (!selectedExtra.isEmpty()) {
            target.setExtra(selectedExtra);
            if (!selectedExtra.equals(target.activeExtraKey())) {
                selectedExtra = "";
            }
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
