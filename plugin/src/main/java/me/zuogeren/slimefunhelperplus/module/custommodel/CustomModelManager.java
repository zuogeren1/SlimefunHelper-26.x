package me.zuogeren.slimefunhelperplus.module.custommodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
    /**
     * 预览页专用的实例：与世界里那个分开，预览里播动作不会推进世界玩家的物理与控制器状态，
     * 也不会把选择写进配置。
     *
     * <p>预览期间由 {@link #runtime()} 让位给它，关闭时再换回来 —— 世界实例自始至终没被推进过。
     * 世界玩家的出图走 {@link #worldRuntime()}，两者由实体提交 mixin 按渲染上下文分开。
     */
    private static volatile ModelRuntime previewRuntime;
    /** 已解析模型的元信息：文件 → {作者, tips}；只有加载过的模型才有，卡片 tooltip 用。 */
    private static volatile Map<String, String[]> metadata = Map.of();
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

    /**
     * 根菜单里直接列出的动作键。
     *
     * <p>这些动作即使同时出现在某个分类里，也要留在根目录：SPM 的 {@code extraAnimation} 是根菜单、
     * {@code extraAnimationClassify} 只是子菜单表，分类只给「额外的」动作提供入口。
     */
    private static volatile Set<String> rootExtraKeys = Set.of();
    private static volatile boolean loadedHideProps;

    /**
     * 卡片预览实例：文件 → 独立 runtime。
     *
     * <p>选择页一屏十份，每份都要自己的物理与控制器状态（共用一份会互相踩），所以按文件各建一个。
     * 只保留当前页用到的那些：一个模型的几何 + 贴图不小，翻页时把不在页内的丢掉。
     */
    private static final Map<String, ModelRuntime> cardRuntimes = new LinkedHashMap<>();
    /** 文件 → 卡片预览的渲染状态编号；编号一旦分配就不再变，翻页丢掉实例也不回收。 */
    private static final Map<String, Integer> cardIds = new HashMap<>();
    /** 卡片预览的渲染状态编号 → runtime。出图入口（实体提交 mixin）只拿得到渲染状态，靠它认领实例。 */
    private static final Map<Integer, ModelRuntime> cardByStateId = new HashMap<>();
    /** 卡片预览用的合成实体编号：负数，不会与真实实体撞号。 */
    private static final int CARD_STATE_BASE = -100000;
    private static int cardIdSeq;
    /** 一帧最多建几个卡片实例：解析 + 建几何是重活，摊到多帧做，翻页时不会卡一下。 */
    private static final int CARD_BUILD_PER_FRAME = 1;
    private static int cardBuildBudget;

    private static final String NO_EXTRA = "__none__";
    /** 选择记录最多保存几个模型（够用且不会让配置行无限长）。 */
    private static final int SELECTION_LIMIT = 8;
    /** 一条记录的三段分隔符；模型名与选择键里都不会出现这两个字符。 */
    private static final char FIELD_SEPARATOR = '|';
    private static final char ENTRY_SEPARATOR = ';';

    private CustomModelManager() {}

    public static ModelRuntime runtime() {
        ModelRuntime preview = previewRuntime;
        return preview != null ? preview : runtime;
    }

    /**
     * 世界实例：预览页开着时 {@link #runtime()} 让位给预览实例，世界里的玩家必须继续用自己那份，
     * 否则面板背后的角色会跟着播预览动作。
     *
     * <p>「这次出图是不是预览」由调用方按渲染上下文判断 —— 出图入口拿得到 {@code CameraRenderState}，
     * 比在这里猜可靠。
     */
    public static ModelRuntime worldRuntime() {
        return runtime;
    }

    public static ModelRuntime armRuntime() {
        return armRuntime;
    }

    /** 预览实例是否就绪；没就绪时预览页不该把动作落到世界实例上。 */
    public static boolean previewActive() {
        return previewRuntime != null;
    }

    /**
     * 为预览页建一个独立实例。
     *
     * <p>几何与贴图都取自当前模型，所以预览看到的外观与世界一致；服装预设照搬，动作留空由预览页自己点。
     * 必须在渲染线程调用（{@link ModelRuntime#build} 会注册贴图）。
     */
    public static void beginPreview() {
        if (previewRuntime != null) {
            return;
        }
        RawYsmModel model = loadedModel;
        ModelRuntime world = runtime;
        if (model == null || world == null) {
            return;
        }
        ModelRuntime built = ModelRuntime.build(model, world.texture(), loadedHideProps);
        if (built == null) {
            return;
        }
        built.setVariant(world.activeVariantKey());
        if (built.activeVariantKey().isEmpty()) {
            built.setVariant(built.defaultVariantKey());
        }
        previewRuntime = built;
    }

    /** 预览页关闭：丢掉预览实例，世界实例的状态自始至终没被推进过。 */
    public static void endPreview() {
        ModelRuntime preview = previewRuntime;
        previewRuntime = null;
        if (preview != null) {
            preview.release();
        }
    }

    /**
     * 卡片预览的渲染状态编号；这个文件还没有实例时返回 {@link #CARD_STATE_BASE} 之前的占位值。
     *
     * <p>实例按需建、每帧最多一个（{@link #CARD_BUILD_PER_FRAME}）：一页十个模型，一次全建会把
     * 打开页面卡住好几秒。没建好之前卡片画占位底纹，建好自然出现。
     *
     * <p>必须在渲染线程调用（注册贴图要求渲染线程）。
     */
    public static int cardStateId(String file) {
        if (file == null || file.isEmpty()) {
            return CARD_STATE_BASE - 1;
        }
        if (cardRuntimes.containsKey(file)) {
            return cardIds.get(file);
        }
        if (cardBuildBudget <= 0) {
            return CARD_STATE_BASE - 1;
        }
        cardBuildBudget--;
        ModelRuntime built = buildCardRuntime(file);
        if (built == null) {
            return CARD_STATE_BASE - 1;
        }
        Integer known = cardIds.get(file);
        int id = known != null ? known : CARD_STATE_BASE - (cardIdSeq++);
        cardIds.put(file, id);
        cardRuntimes.put(file, built);
        cardByStateId.put(id, built);
        return id;
    }

    /** 出图入口按渲染状态编号认领卡片实例；不是卡片预览时返回 {@code null}。 */
    public static ModelRuntime cardRuntime(int stateId) {
        return cardByStateId.get(stateId);
    }

    /**
     * 每帧开头重置建实例的预算。
     *
     * <p>由选择页在画卡片之前调用：一页十个模型，一次全建会把打开页面卡住好几秒，
     * 摊到多帧做才不卡；预算按帧重置，翻页后新的一页自然逐帧补齐。
     */
    public static void beginCardFrame() {
        cardBuildBudget = CARD_BUILD_PER_FRAME;
    }

    /**
     * 丢掉全部卡片实例。
     *
     * <p>换模型会 {@code ModelTexture.releaseAll()}，卡片实例手里的 Identifier 随之失效；
     * 不丢掉的话卡片会一直画成 missing texture。丢掉之后选择页下一帧按需重建。
     *
     * <p>选择页关闭时也走这里：一页十个模型的几何不小，关掉页面就该还回去。
     */
    public static void dropCards() {
        for (ModelRuntime runtime : cardRuntimes.values()) {
            runtime.release();
        }
        cardRuntimes.clear();
        cardByStateId.clear();
    }

    /**
     * 只保留这些文件的卡片实例，其余丢掉。
     *
     * <p>翻页时调用：上一页的实例留着只会白占内存，而重建代价与首次加载相同。
     */
    public static void retainCards(Set<String> keep) {
        cardBuildBudget = CARD_BUILD_PER_FRAME;
        if (cardRuntimes.isEmpty()) {
            return;
        }
        List<String> stale = new ArrayList<>();
        for (String file : cardRuntimes.keySet()) {
            if (!keep.contains(file)) {
                stale.add(file);
            }
        }
        for (String file : stale) {
            ModelRuntime dropped = cardRuntimes.remove(file);
            if (dropped != null) {
                dropped.release();
            }
        }
        cardByStateId.clear();
        for (Map.Entry<String, ModelRuntime> entry : cardRuntimes.entrySet()) {
            cardByStateId.put(cardIds.get(entry.getKey()), entry.getValue());
        }
    }

    /**
     * 建一个卡片实例：解密、反序列化、注册贴图、建几何。
     *
     * <p>与 {@link #reload} 走同一套解析，但不碰世界实例的任何字段 —— 卡片是只读展示，
     * 换页不该影响玩家身上那个模型。
     */
    private static ModelRuntime buildCardRuntime(String file) {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir()
                    .resolve("slimefunhelper-plus")
                    .resolve("models");
            byte[] clear = YsmCrypt.decryptYsmFile(Files.readAllBytes(dir.resolve(file)));
            RawYsmModel model = new YSMBinaryDeserializer(clear).deserialize();
            ModelCatalog.putDisplayName(file, model.metadata == null ? null : model.metadata.name);
            rememberMetadata(file, model.metadata);
            Map<String, RawYsmModel.RawTexture> textures = collectTextures(model);
            Identifier textureId = ModelTexture.register(textures.get(pickDefaultTextureName(model, textures)));
            if (textureId == null) {
                textureId = ModelTexture.placeholder();
            }
            ModelRuntime built = ModelRuntime.buildCard(model, textureId, loadedHideProps);
            if (built != null) {
                built.setVariant(built.defaultVariantKey());
            }
            return built;
        } catch (Throwable throwable) {
            Debug.info("[SFH+] 卡片预览加载失败 " + file + ": " + throwable);
            return null;
        }
    }

    /** 已解析模型的 {作者, tips}；没加载过这个模型时返回 {@code null}。 */
    public static String[] metadataOf(String file) {
        return file == null ? null : metadata.get(file);
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

    /** 根菜单里直接列出的动作键；轮盘据此决定哪些动作留在根目录。 */
    public static Set<String> rootExtraKeys() {
        return rootExtraKeys;
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
        dropCards();
        runtime = null;
        armRuntime = null;
        ModelRuntime preview = previewRuntime;
        previewRuntime = null;
        if (preview != null) {
            preview.release();
        }
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
        // 卡片实例持有同一批贴图，释放后必须一起丢掉，否则卡片画成 missing texture
        dropCards();
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
        rememberMetadata(fileName, model.metadata);
        loadedHideProps = hideUnreferenced;
        textureSources = loaded == null ? Map.of() : textures;
        extraCategories = loaded == null ? Map.of() : categories;
        activeTexture = loaded == null ? "" : defaultName;
        // 换模型会释放旧贴图，预览实例持有的那张随之失效：丢掉它，预览页下一帧按新模型重建
        ModelRuntime stalePreview = previewRuntime;
        previewRuntime = null;
        if (stalePreview != null) {
            stalePreview.release();
        }
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
        // 根菜单成员要在并分类之前记下来：并完之后分不清哪些是作者直接列的
        Set<String> rootKeys = new LinkedHashSet<>();
        for (String key : labeled.keySet()) {
            if (key != null && !key.startsWith("#")) {
                rootKeys.add(key);
            }
        }
        rootExtraKeys = Set.copyOf(rootKeys);
        if (labeled.isEmpty()) {
            // 没有菜单的模型，轮盘会把 animType==3 的动画全列出来；先补上可读名，
            // 否则那一页是一排 extra0~extra7
            fillUnnamedExtras(model, labeled);
            // 兜底列出的全部算根菜单成员
            rootExtraKeys = Set.copyOf(labeled.keySet());
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
        // 分类只在根菜单写了对应的 #id 标记时才成立：SPM 的 extraAnimation 是根菜单、
        // extraAnimationClassify 只是子菜单表（UnifiedRouletteScreen:122-137），没有标记就没有入口，
        // 那个分类整组不出现。作者写了 id 却忘了在根菜单留标记时，补入口等于凭空多一个目录。
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

    /**
     * 记下模型的作者与 tips：卡片 tooltip 要显示它们，而元信息只有反序列化之后才有。
     *
     * <p>按文件缓存而不是只留当前模型：切走再切回来时不必重新解析就能显示。
     */
    private static void rememberMetadata(String file, RawYsmModel.RawMetadata meta) {
        if (file == null || file.isEmpty() || meta == null) {
            return;
        }
        StringBuilder authors = new StringBuilder();
        if (meta.authors != null) {
            for (RawYsmModel.RawMetadata.Author author : meta.authors) {
                if (author == null || author.name == null || author.name.isBlank()) {
                    continue;
                }
                if (authors.length() > 0) {
                    authors.append(" / ");
                }
                authors.append(ModelCatalog.stripFormatting(author.name).trim());
            }
        }
        String tips = meta.tips == null ? "" : ModelCatalog.stripFormatting(meta.tips).trim();
        Map<String, String[]> next = new HashMap<>(metadata);
        next.put(file, new String[]{authors.toString(), tips});
        metadata = Map.copyOf(next);
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
        // 卡片实例持有同一批贴图，释放后必须一起丢掉，否则卡片画成 missing texture
        dropCards();
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
