package me.zuogeren.slimefunhelperplus.module.custommodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    /** 选择记录最多保存几个模型（够用且不会让配置行无限长）。 */
    private static final int SELECTION_LIMIT = 8;
    /** 一条记录的三段分隔符；模型名与选择键里都不会出现这两个字符。 */
    private static final char FIELD_SEPARATOR = '|';
    private static final char ENTRY_SEPARATOR = ';';

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
        rememberSelection(loadedFile, selectedVariant, selectedExtra);
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
        selectedVariant = "";
        selectedExtra = "";
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
            applyExtraSelection(loaded, fileName);
            Debug.info("[SFH+] 模型已加载 " + fileName + " bones=" + loaded.boneCount()
                    + " arm=" + (armRuntime == null ? "-" : armRuntime.boneCount())
                    + " variant=" + (loaded.activeVariantKey().isEmpty() ? "-" : loaded.activeVariantKey())
                    + " extra=" + (loaded.activeExtraKey().isEmpty() ? "-" : loaded.activeExtraKey())
                    + " texture=" + textureId);
        }
    }

    /**
     * 模型换了以后把选择接上：先取这个模型上次落盘的选择，服装预设不存在就退回默认服装、动作不存在就丢掉。
     *
     * <p>按模型分别记：同一个轮盘键在不同模型里指向不同片段，混用会把别的模型的服装名套进来。
     */
    private static void applyExtraSelection(ModelRuntime target, String model) {
        String[] recalled = recallSelection(model);
        String variant = recalled[0];
        String extra = recalled[1];
        if (!variant.isEmpty()) {
            target.setVariant(variant);
        }
        if (variant.isEmpty() || !variant.equals(target.activeVariantKey())) {
            target.setVariant(target.defaultVariantKey());
        }
        selectedVariant = target.activeVariantKey();
        selectedExtra = "";
        if (!extra.isEmpty()) {
            target.setExtra(extra);
            if (extra.equals(target.activeExtraKey())) {
                selectedExtra = extra;
            }
        }
    }

    /** 读这个模型上次的选择；没有记录返回两个空串。同一个模型出现多条时以最后一条为准。 */
    private static String[] recallSelection(String model) {
        String variant = "";
        String extra = "";
        if (model == null || model.isEmpty()) {
            return new String[]{"", ""};
        }
        for (String entry : selectionEntries()) {
            int first = entry.indexOf(FIELD_SEPARATOR);
            int second = first < 0 ? -1 : entry.indexOf(FIELD_SEPARATOR, first + 1);
            if (first <= 0 || second < 0) {
                continue;
            }
            if (entry.substring(0, first).equals(model)) {
                variant = entry.substring(first + 1, second);
                extra = entry.substring(second + 1);
            }
        }
        return new String[]{variant, extra};
    }

    /**
     * 记下这个模型的选择：先删掉同模型的旧记录再追加，超出上限时丢最早的那条。
     *
     * <p>写回配置而不是另存文件 —— 配置本来就随模块一起落盘，且用户能看到当前记了哪些模型。
     */
    private static void rememberSelection(String model, String variant, String extra) {
        CustomModelModule module = CustomModelModule.INSTANCE;
        if (module == null || model == null || model.isEmpty()) {
            return;
        }
        List<String> entries = new ArrayList<>();
        for (String entry : selectionEntries()) {
            int first = entry.indexOf(FIELD_SEPARATOR);
            if (first > 0 && entry.substring(0, first).equals(model)) {
                continue;
            }
            entries.add(entry);
        }
        entries.add(model + FIELD_SEPARATOR + (variant == null ? "" : variant)
                + FIELD_SEPARATOR + (extra == null ? "" : extra));
        while (entries.size() > SELECTION_LIMIT) {
            entries.remove(0);
        }
        module.rouletteSelection.set(String.join(String.valueOf(ENTRY_SEPARATOR), entries));
    }

    private static List<String> selectionEntries() {
        CustomModelModule module = CustomModelModule.INSTANCE;
        List<String> entries = new ArrayList<>(2);
        if (module == null) {
            return entries;
        }
        String raw = module.rouletteSelection.get();
        if (raw == null || raw.isBlank()) {
            return entries;
        }
        for (String entry : raw.split(String.valueOf(ENTRY_SEPARATOR))) {
            if (!entry.isBlank()) {
                entries.add(entry.trim());
            }
        }
        return entries;
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
