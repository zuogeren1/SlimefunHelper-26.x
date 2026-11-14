package me.zuogeren.slimefunhelperplus.module.custommodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    /** 当前模型里可切换的贴图（声明顺序）；切换时按需注册，不把每张都传上显存。 */
    private static volatile Map<String, RawYsmModel.RawTexture> textureSources = Map.of();
    private static volatile String activeTexture = "";
    /** 换贴图要重建 runtime，这里留住重建所需的模型与构建参数。 */
    private static volatile RawYsmModel loadedModel;
    /** 轮盘分组：额外动作键 → 分类名（{@code properties.extraAnimationClassifies}）；没有分类的模型为空表。 */
    private static volatile Map<String, String> extraCategories = Map.of();
    private static volatile boolean loadedHideProps;

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

    /** 轮盘分组表（动作键 → 分类名）；空表表示这个模型没有分类，轮盘按平铺显示。 */
    public static Map<String, String> extraCategories() {
        return extraCategories;
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
        // 贴图是 GPU 资源，Identifier 被丢弃不会让 TextureManager 回收它
        ModelTexture.releaseAll();
        runtime = null;
        armRuntime = null;
        loadedFile = "";
        selectedVariant = "";
        selectedExtra = "";
        textureSources = Map.of();
        extraCategories = Map.of();
        activeTexture = "";
        loadedModel = null;
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
                textureSources = Map.of();
                extraCategories = Map.of();
                activeTexture = "";
                loadedModel = null;
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
            // 显示名只有解析之后才知道：扫描阶段只有文件名，这里回填给界面用
            ModelCatalog.putDisplayName(fileName, model.metadata == null ? null : model.metadata.name);
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
                textureSources = Map.of();
                extraCategories = Map.of();
                activeTexture = "";
                loadedModel = null;
            }
            Debug.info("[SFH+] 模型加载异常 " + fileName + ": " + throwable);
        }
    }

    private static void apply(RawYsmModel model, String fileName, int version) {
        if (VERSION.get() != version) {
            return;
        }
        Map<String, String> categories = mergeClassifiedExtras(model);
        Map<String, RawYsmModel.RawTexture> textures = collectTextures(model);
        String defaultName = pickDefaultTextureName(model, textures);
        // 先释放旧模型的贴图再注册新的：反过来的话 releaseAll 会把刚注册的那张一起释放掉，
        // 渲染时拿到的是已释放的 Identifier，整模型变成 missing texture。
        ModelTexture.releaseAll();
        Identifier textureId = ModelTexture.register(textures.get(defaultName));
        if (textureId == null) {
            // 贴图注册失败（GPU 未就绪等）不该连几何一起丢：占位图至少让模型能画出来
            textureId = ModelTexture.placeholder();
        }
        CustomModelModule module = CustomModelModule.INSTANCE;
        boolean hideUnreferenced = module == null || !module.showProps.get();
        ModelRuntime loaded = textureId == null ? null : ModelRuntime.build(model, textureId, hideUnreferenced);
        if (VERSION.get() != version) {
            return;
        }
        // 换模型时先释放旧实例的音效（ogg 字节最大 64MB/模型），再换引用
        ModelRuntime previous = runtime;
        ModelRuntime previousArm = armRuntime;
        runtime = loaded;
        armRuntime = textureId == null ? null : ModelRuntime.buildArm(model, textureId);
        if (previous != null) {
            previous.release();
        }
        if (previousArm != null) {
            previousArm.release();
        }
        loadedFile = loaded == null ? "" : fileName;
        loadedModel = loaded == null ? null : model;
        loadedHideProps = hideUnreferenced;
        textureSources = loaded == null ? Map.of() : textures;
        extraCategories = loaded == null ? Map.of() : categories;
        activeTexture = loaded == null ? "" : defaultName;
        if (loaded == null) {
            Debug.info("[SFH+] 模型加载失败（几何为空或贴图不可用）: " + fileName);
        } else {
            applyExtraSelection(loaded, fileName);
            Debug.info("[SFH+] 模型已加载 " + fileName + " bones=" + loaded.boneCount()
                    + " arm=" + (armRuntime == null ? "-" : armRuntime.boneCount())
                    + " variant=" + (loaded.activeVariantKey().isEmpty() ? "-" : loaded.activeVariantKey())
                    + " extra=" + (loaded.activeExtraKey().isEmpty() ? "-" : loaded.activeExtraKey())
                    + " texture=" + activeTexture + "@" + textureId);
        }
    }

    /**
     * 把 {@code properties.extraAnimationClassifies} 里的动作并进 {@code extraAnimations}，返回「动作 → 分类名」。
     *
     * <p>必须并进去：轮盘只列 {@code extraAnimations} 里有的动作（{@code ModelRuntime#collectExtras}），
     * 而作者常把分类动作只写在分类里、根菜单只留一个 {@code #分类} 标记 —— 不并进来这些动作永远进不了轮盘。
     * 动作插在它自己的 {@code #分类} 标记后面，作者写的菜单顺序就是轮盘顺序；标记本身以 {@code #} 开头，
     * 轮盘照旧不列它，分类名改由轮盘画在组内第一格下方。
     *
     * <p>只在解析后做一次（{@link #apply} 里），轮盘每帧读的是 {@link ModelRuntime} 整理好的列表和这张表。
     */
    private static Map<String, String> mergeClassifiedExtras(RawYsmModel model) {
        if (model.properties == null) {
            return Map.of();
        }
        Map<String, String> extras = model.properties.extraAnimations;
        if (extras == null) {
            extras = new LinkedHashMap<>();
        }
        // 轮盘直接画 label，作者留空的会画成一格空白：先补成可读名，键序不变
        Map<String, String> labeled = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : extras.entrySet()) {
            labeled.put(entry.getKey(), labelOf(entry.getKey(), entry.getValue()));
        }
        if (labeled.isEmpty()) {
            // 没有菜单的模型，轮盘会把 animType==3 的动画全列出来；先补上可读名，
            // 否则那一页是一排 extra0~extra7
            fillUnnamedExtras(model, labeled);
        }
        model.properties.extraAnimations = labeled;
        if (model.properties.extraAnimationClassifies == null
                || model.properties.extraAnimationClassifies.isEmpty()) {
            return Map.of();
        }
        Map<String, RawYsmModel.ExtraAnimationClassify> byId = new LinkedHashMap<>();
        for (RawYsmModel.ExtraAnimationClassify classify : model.properties.extraAnimationClassifies) {
            if (classify == null || classify.extras == null || classify.extras.isEmpty()) {
                continue;
            }
            String id = classify.id == null ? "" : classify.id.trim();
            if (!id.isEmpty()) {
                byId.putIfAbsent(id, classify);
            }
        }
        if (byId.isEmpty()) {
            return Map.of();
        }
        Map<String, String> categories = new LinkedHashMap<>();
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : labeled.entrySet()) {
            merged.put(entry.getKey(), entry.getValue());
            String key = entry.getKey();
            if (key == null || !key.startsWith("#")) {
                continue;
            }
            RawYsmModel.ExtraAnimationClassify classify = byId.get(key.substring(1));
            if (classify != null) {
                appendClassified(merged, categories, classify, groupNameOf(entry.getValue(), key.substring(1)));
            }
        }
        // 作者只写了分类、根菜单里没有 #分类 标记的模型：整组追加到末尾，顺序照分类声明
        for (Map.Entry<String, RawYsmModel.ExtraAnimationClassify> entry : byId.entrySet()) {
            String id = entry.getKey();
            appendClassified(merged, categories, entry.getValue(), groupNameOf(labeled.get("#" + id), id));
        }
        model.properties.extraAnimations = merged;
        return Map.copyOf(categories);
    }

    /**
     * 没有 {@code extraAnimations} 菜单时，轮盘按 {@code ModelRuntime#collectExtras} 的兜底列出全部
     * {@code animType==3} 动画；这里按同一顺序把它们的 key 收进表里，只为让标签走 {@link #labelOf}。
     *
     * <p>键序与兜底遍历一致，所以轮盘顺序不变；没有骨骼通道的动画由 {@code collectExtras} 自己跳过。
     */
    private static void fillUnnamedExtras(RawYsmModel model, Map<String, String> out) {
        if (model.mainEntity == null || model.mainEntity.animationFiles == null) {
            return;
        }
        for (RawYsmModel.RawAnimationFile file : model.mainEntity.animationFiles.values()) {
            if (file == null || file.animations == null || file.animType != 3) {
                continue;
            }
            for (String name : file.animations.keySet()) {
                out.putIfAbsent(name, labelOf(name, ""));
            }
        }
    }

    /**
     * 轮盘标签：作者留空的（{@code extra0}~{@code extra7} 这类只当配置动画用的占位）退回可读的「动作 N」，
     * 否则轮盘上是一格空白，玩家只能看到原始 key。
     *
     * <p>只改显示，不改 key —— 选中、持久化、片段查找都按 key 走。
     */
    /**
     * 轮盘标签：作者给了名字就用名字，没给就显示原始 key。
     *
     * <p>不替作者编「动作 N」这类名字 —— YSM 自己也是直接显示 key（实机对照：默认动作的子轮盘里
     * 就是 {@code extra0}~{@code extra7}），编出来的名字反而和作者菜单对不上。
     */
    private static String labelOf(String key, String label) {
        if (label != null && !label.isBlank()) {
            return label;
        }
        return key == null ? "" : key;
    }

    private static void appendClassified(Map<String, String> merged, Map<String, String> categories,
            RawYsmModel.ExtraAnimationClassify classify, String group) {
        // 分类成员同样可能没名字（作者只把它当配置动画），标签规则与根菜单一致
        for (Map.Entry<String, String> member : classify.extras.entrySet()) {
            String key = member.getKey();
            if (key == null || key.isEmpty() || key.startsWith("#")) {
                continue;
            }
            merged.putIfAbsent(key, labelOf(key, member.getValue()));
            // 同一个动作出现在多个分类里时以先声明的为准：作者按菜单顺序写，后面的多半是复制残留
            categories.putIfAbsent(key, group);
        }
    }

    /**
     * 分类的显示名：优先用根菜单里 {@code #分类} 标记的标签（作者给玩家看的名字），
     * 标记不存在或标签本身还是个 {@code #} 标记时退回分类 id。
     */
    private static String groupNameOf(String markerLabel, String id) {
        if (markerLabel != null) {
            String text = ModelCatalog.stripFormatting(markerLabel).trim();
            if (!text.isEmpty() && !text.startsWith("#")) {
                return text;
            }
        }
        return id;
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

    /** 模型里所有带数据的贴图，保持声明顺序（切换列表与默认值都按这个顺序）。 */
    private static Map<String, RawYsmModel.RawTexture> collectTextures(RawYsmModel model) {
        Map<String, RawYsmModel.RawTexture> textures = new LinkedHashMap<>();
        if (model.mainEntity == null || model.mainEntity.textures == null) {
            return textures;
        }
        for (Map.Entry<String, RawYsmModel.RawTexture> entry : model.mainEntity.textures.entrySet()) {
            RawYsmModel.RawTexture texture = entry.getValue();
            if (texture == null || texture.data == null || texture.data.length == 0) {
                continue;
            }
            textures.put(entry.getKey(), texture);
        }
        return textures;
    }

    /**
     * 默认贴图取 {@code properties.defaultTexture}（作者在模型里指定的那张），
     * 它不存在或没数据时才退回第一张 —— 与 SPM 的 ModelAssemblyFactory 同一条规则。
     */
    private static String pickDefaultTextureName(RawYsmModel model, Map<String, RawYsmModel.RawTexture> textures) {
        if (textures.isEmpty()) {
            return "";
        }
        String declared = model.properties == null ? null : model.properties.defaultTexture;
        if (declared != null && textures.containsKey(declared)) {
            return declared;
        }
        return textures.keySet().iterator().next();
    }

    /** 可切换的贴图名（声明顺序）；没有模型时为空。 */
    public static List<String> textureNames() {
        return List.copyOf(textureSources.keySet());
    }

    public static String activeTexture() {
        return activeTexture;
    }

    /**
     * 换一张贴图：重建 runtime 并替换主模型与手臂模型。
     *
     * <p>贴图是 {@link ModelRuntime} 的构造参数，换图只能重建；几何与动画都来自同一份
     * {@link RawYsmModel}，重建代价与首次加载相同。名字不存在时保持原样。
     *
     * <p>必须在渲染线程调用（注册纹理与上传像素都要求渲染线程）。
     */
    public static void selectTexture(String name) {
        RawYsmModel model = loadedModel;
        Map<String, RawYsmModel.RawTexture> textures = textureSources;
        if (model == null || name == null || name.equals(activeTexture) || !textures.containsKey(name)) {
            return;
        }
        Identifier textureId = ModelTexture.register(textures.get(name));
        if (textureId == null) {
            Debug.info("[SFH+] 贴图切换失败（无法注册）: " + name);
            return;
        }
        ModelRuntime rebuilt = ModelRuntime.build(model, textureId, loadedHideProps);
        if (rebuilt == null) {
            Debug.info("[SFH+] 贴图切换失败（几何为空）: " + name);
            return;
        }
        // 旧 runtime 持有音效句柄，换图后要显式释放；旧贴图同理（新贴图已注册好，此时释放不会误伤）
        ModelRuntime previous = runtime;
        ModelRuntime previousArm = armRuntime;
        ModelTexture.release(activeTexture);
        runtime = rebuilt;
        armRuntime = ModelRuntime.buildArm(model, textureId);
        if (previous != null) {
            previous.release();
        }
        if (previousArm != null) {
            previousArm.release();
        }
        activeTexture = name;
        // 重建出来的 runtime 是全新的，服装/动作选择要按这个模型重新接上
        applyExtraSelection(rebuilt, loadedFile);
        Debug.info("[SFH+] 贴图已切换: " + name + " texture=" + textureId);
    }
}
