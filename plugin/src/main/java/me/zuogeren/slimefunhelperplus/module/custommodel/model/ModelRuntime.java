package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.matl114.utils.Debug;
import net.minecraft.client.Minecraft;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 一个已烘焙好的 YSM 模型：骨骼矩阵 + 每个骨骼的面片，直接往 {@link VertexConsumer} 里写顶点。
 *
 * <p>几何在反序列化阶段已经烘焙成模型空间的面片（见 {@code YsmGeometryParsing}，坐标是方块单位），
 * 这里只负责按骨骼层级乘矩阵；根的朝向（{@code 180 - bodyRot}）由调用方在 PoseStack 上施加。
 *
 * <p>动画吃 {@code animation-main}（animType=1）：状态表与 YSM 的 {@code AnimationRegister} 对齐
 * （death/riptide/sleep/swim/climb/ladder/fly/elytra/swim_stand/attacked/jump/sneak/run/walk/idle），
 * 主状态一律循环，只有死亡与被击是一次性；关键帧的字符串通道走 {@link MolangExpression} 的数值子集。
 *
 * <p>舞台道具分两类识别：整棵只由非基础动画（{@code parallel*}/{@code pre_parallel*}/{@code stage}）
 * 引用的根（钢琴、背景板、车、鼓），以及挂在身体根下、在 {@code pre_parallel*} 里被缩放到 0 的骨骼
 * （吉他、手机、备用服装）。{@code show-props} 打开时全部显示。
 */
public final class ModelRuntime {

    private static final String[] IDLE = {"idle", "empty"};
    private static final String[] WALK = {"walk"};
    private static final String[] RUN = {"run"};
    private static final String[] SNEAK = {"sneak"};
    private static final String[] SNEAK_STILL = {"sneaking"};
    private static final String[] ELYTRA = {"elytra_fly"};
    private static final String[] FLY = {"fly"};
    private static final String[] SWIM = {"swim"};
    private static final String[] SWIM_STAND = {"swim_stand"};
    private static final String[] RIDE = {"ride", "boat"};
    private static final String[] RIDE_PIG = {"ride_pig", "ride"};
    private static final String[] RIDE_BOAT = {"boat", "ride"};
    private static final String[] SIT = {"sit", "ride"};
    private static final String[] DEATH = {"death"};
    private static final String[] ATTACKED = {"attacked"};
    private static final String[] RIPTIDE = {"riptide"};
    private static final String[] SLEEP = {"sleep"};
    private static final String[] CLIMB = {"climb"};
    private static final String[] CLIMBING = {"climbing"};
    private static final String[] LADDER_UP = {"ladder_up"};
    private static final String[] LADDER_DOWN = {"ladder_down"};
    private static final String[] LADDER_STILL = {"ladder_stillness"};
    private static final String[] USE_MAIN = {"use_mainhand"};
    private static final String[] USE_OFF = {"use_offhand"};
    private static final String[] JUMP = {"jump"};
    private static final String[] FP_ARM = {"fp_arm", "arm", "idle", "empty"};

    /** 只播一次的状态；其余主状态按 YSM 的 {@code playLoopAnimation} 语义一律循环。 */
    private static final Set<String> ONE_SHOT = Set.of("death", "attacked");

    /** 参与「基础动作」的动画名：被这些动画驱动的骨骼不算舞台道具。 */
    private static final Set<String> MAIN_STATES = Set.of(
            "idle", "walk", "run", "sneak", "sneaking", "fly", "elytra_fly", "swim", "swim_stand",
            "ride", "boat", "jump", "attacked", "death", "riptide", "sleep", "climb", "climbing",
            "ladder_up", "ladder_down", "ladder_stillness", "use_mainhand", "use_offhand", "sit");

    private static final float MIN_MOVING = 0.05F;
    /** 道具骨骼的最小尺寸（格）：比这个小的大多是耳饰、表情、衣角这类身体附件。 */
    private static final float PROP_MIN_SIZE = 0.75F;
    /** 明显不属于角色本体的部件（背景板、载具）。 */
    private static final float OVERSIZED = 4.0F;
    /** 身体部位词根：子树里出现这些名字就不按「道具」整棵隐藏。 */
    private static final String[] BODY_WORDS = {
            "head", "body", "arm", "hand", "leg", "foot", "hair", "eye", "finger", "neck", "waist", "chest"
    };

    private final List<RawYsmModel.RawBone> bones;
    private final Map<String, Integer> boneIndex;
    private final Map<String, RawYsmModel.RawAnimation> clips;
    private final int[] parents;
    private final Matrix4f[] restMatrices;
    private final Matrix3f[] restNormals;
    private final Matrix4f[] frameMatrices;
    private final Matrix3f[] frameNormals;
    private final byte[] frameState;
    private final float[] poses;
    private final boolean[] hiddenDefault;
    private final boolean[] hiddenCurrent;
    private final int[] propRoot;
    private final Map<Integer, List<Integer>> children;
    private final Map<String, RawYsmModel.RawAnimation> extraClips;
    private final List<ExtraOption> extraOptions;
    private final String defaultExtraKey;
    private final Identifier texture;
    private final int rightHandBone;
    private final int leftHandBone;
    private RawYsmModel.RawAnimation lastClip;
    private float lastClipStart;
    private float lastHurtTime;
    private RawYsmModel.RawAnimation activeVariant;
    private String activeVariantKey = "";
    private RawYsmModel.RawAnimation activeExtra;
    private String activeExtraKey = "";
    private float activeExtraStart = Float.NaN;
    private boolean frameValid;
    private boolean logged;
    /** 手臂模型专用：每根骨骼的根、根 pivot 的 X（用来分左右手）；主模型上是 null。 */
    private int[] armRootOf;
    private float[] armRootX;
    private boolean[] renderMask;
    private List<ModelController> controllers = List.of();
    /** 模型属性里的渲染缩放（YSM 默认 0.7）；作者可以用它整体调大小。 */
    private float widthScale = 1.0F;
    private float heightScale = 1.0F;
    /** 无条件循环的默认层：模型里存在的 pre_parallel0..7。道具的自动隐藏写在这些片段里。 */
    private List<String> preParallelBase = List.of();

    /** 轮盘里的一条：{@code key} 是动画名，{@code variant} 标记「服装预设」而不是额外动作。 */
    public record ExtraOption(String key, String label, boolean variant) {}

    private static String clipName(RawYsmModel.RawAnimation clip) {
        return clip == null || clip.name == null ? "none" : clip.name;
    }

    private ModelRuntime(List<RawYsmModel.RawBone> bones, Map<String, Integer> boneIndex,
            Map<String, RawYsmModel.RawAnimation> clips, int[] parents, Matrix4f[] restMatrices,
            Matrix3f[] restNormals, boolean[] hidden, int[] propRoot, Map<Integer, List<Integer>> children,
            Map<String, RawYsmModel.RawAnimation> extraClips, List<ExtraOption> extraOptions, String defaultExtraKey,
            Identifier texture, int rightHandBone, int leftHandBone) {
        this.bones = bones;
        this.boneIndex = boneIndex;
        this.clips = clips;
        this.parents = parents;
        this.restMatrices = restMatrices;
        this.restNormals = restNormals;
        this.frameMatrices = new Matrix4f[bones.size()];
        this.frameNormals = new Matrix3f[bones.size()];
        this.frameState = new byte[bones.size()];
        this.poses = new float[bones.size() * 9];
        this.hiddenDefault = hidden.clone();
        this.hiddenCurrent = hidden.clone();
        this.propRoot = propRoot;
        this.children = children;
        this.extraClips = extraClips;
        this.extraOptions = extraOptions;
        this.defaultExtraKey = defaultExtraKey;
        this.texture = texture;
        this.rightHandBone = rightHandBone;
        this.leftHandBone = leftHandBone;
    }

    public static ModelRuntime build(RawYsmModel model, Identifier texture, boolean hideProps) {
        RawYsmModel.RawGeometry geometry = model.mainEntity == null ? null : model.mainEntity.mainModel;
        if (geometry == null || geometry.bones == null || geometry.bones.isEmpty()) {
            return null;
        }
        List<RawYsmModel.RawBone> bones = geometry.bones;
        int count = bones.size();
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String name = bones.get(i).name;
            if (name != null && !name.isEmpty()) {
                byName.putIfAbsent(name, i);
            }
        }
        int[] parents = new int[count];
        for (int i = 0; i < count; i++) {
            parents[i] = -1;
            String parentName = bones.get(i).parentName;
            if (parentName != null && !parentName.isEmpty()) {
                Integer parent = byName.get(parentName);
                if (parent != null && parent != i) {
                    parents[i] = parent;
                }
            }
        }
        Matrix4f[] matrices = new Matrix4f[count];
        byte[] state = new byte[count];
        for (int i = 0; i < count; i++) {
            computeRest(i, bones, parents, matrices, state);
        }
        Matrix3f[] normals = new Matrix3f[count];
        for (int i = 0; i < count; i++) {
            normals[i] = normalOf(matrices[i]);
        }
        Map<String, RawYsmModel.RawAnimation> clips = new HashMap<>();
        Map<String, List<String>> references = new HashMap<>();
        Set<String> zeroScaled = new HashSet<>();
        Set<String> mainAnimated = new HashSet<>();
        if (model.mainEntity != null && model.mainEntity.animationFiles != null) {
            for (RawYsmModel.RawAnimationFile file : model.mainEntity.animationFiles.values()) {
                if (file == null || file.animType != 1 || file.animations == null) {
                    continue;
                }
                for (Map.Entry<String, RawYsmModel.RawAnimation> entry : file.animations.entrySet()) {
                    String name = entry.getKey();
                    RawYsmModel.RawAnimation animation = entry.getValue();
                    if (animation == null) {
                        continue;
                    }
                    animation.name = name;
                    clips.putIfAbsent(name, animation);
                    boolean mainState = MAIN_STATES.contains(name);
                    for (RawYsmModel.RawBoneAnimation boneAnimation : animation.boneAnimations) {
                        if (boneAnimation.boneName == null) {
                            continue;
                        }
                        references.computeIfAbsent(boneAnimation.boneName, key -> new java.util.ArrayList<>()).add(name);
                        if (mainState) {
                            mainAnimated.add(boneAnimation.boneName);
                        } else if (name.startsWith("pre_parallel") && hasZeroScale(boneAnimation)) {
                            zeroScaled.add(boneAnimation.boneName);
                        }
                    }
                }
            }
        }
        boolean[] hidden = new boolean[count];
        float[] extent = new float[count];
        int[] faces = new int[count];
        for (int i = 0; i < count; i++) {
            extent[i] = extentOf(bones.get(i));
            faces[i] = faceCount(bones.get(i));
        }
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (int i = 0; i < count; i++) {
            if (parents[i] >= 0) {
                children.computeIfAbsent(parents[i], key -> new java.util.ArrayList<>()).add(i);
            }
        }
        // 舞台道具/载具（钢琴、背景板、车、鼓）：整棵子树只被「非基础动画」引用，且子树里没有身体部件。
        // 这些根在 idle 下应该隐藏 —— YSM 靠 parallel*/pre_parallel* 控制器按需显示。
        for (int root = 0; root < count; root++) {
            if (parents[root] >= 0) {
                continue;
            }
            List<String> rootRefs = references.getOrDefault(bones.get(root).name, List.of());
            boolean mainReferenced = false;
            boolean propReferenced = false;
            for (String reference : rootRefs) {
                if (MAIN_STATES.contains(reference)) {
                    mainReferenced = true;
                } else {
                    propReferenced = true;
                }
            }
            if (mainReferenced || !propReferenced) {
                continue;
            }
            int subtreeFaces = 0;
            boolean body = isBodyName(bones.get(root).name);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                int current = queue.poll();
                subtreeFaces += faces[current];
                if (isBodyName(bones.get(current).name)) {
                    body = true;
                }
                for (int child : children.getOrDefault(current, List.of())) {
                    queue.add(child);
                }
            }
            if (subtreeFaces <= 0 || body) {
                continue;
            }
            queue.add(root);
            while (!queue.isEmpty()) {
                int current = queue.poll();
                hidden[current] = true;
                for (int child : children.getOrDefault(current, List.of())) {
                    queue.add(child);
                }
            }
        }
        // 挂在身体根下面的道具（吉他、手机、备用服装）：pre_parallel* 里缩放到 0、且基础动画不驱动。
        for (int i = 0; i < count; i++) {
            String name = bones.get(i).name;
            if (name != null && zeroScaled.contains(name) && !mainAnimated.contains(name)
                    && extent[i] >= PROP_MIN_SIZE) {
                hidden[i] = true;
            }
        }
        // 道具的子骨骼（琴弦、拨片、装饰）跟着父骨骼一起藏；基础动画驱动的不算。
        int[] depths = depthsOf(parents);
        for (int depth = 1; depth < count; depth++) {
            for (int i = 0; i < count; i++) {
                int parent = parents[i];
                if (depths[i] != depth || parent < 0 || !hidden[parent] || hidden[i]) {
                    continue;
                }
                String name = bones.get(i).name;
                if (name == null || !mainAnimated.contains(name)) {
                    hidden[i] = true;
                }
            }
        }
        int hiddenCount = 0;
        for (int i = 0; i < count; i++) {
            if (extent[i] > OVERSIZED) {
                hidden[i] = true;
            }
            if (hidden[i]) {
                hiddenCount++;
            }
        }
        if (!hideProps) {
            java.util.Arrays.fill(hidden, false);
            hiddenCount = 0;
        }
        // 每根骨骼「最近一个被隐藏的祖先」：轮盘选中额外动画时，把它引用的道具整棵放出来。
        int[] propRoot = new int[count];
        for (int i = 0; i < count; i++) {
            int root = -1;
            int cursor = i;
            while (cursor >= 0 && hidden[cursor]) {
                root = cursor;
                cursor = parents[cursor];
            }
            propRoot[i] = root;
        }
        Map<String, RawYsmModel.RawAnimation> extraClips = new LinkedHashMap<>();
        List<ExtraOption> extraOptions = new ArrayList<>();
        String defaultExtraKey = collectExtras(model, extraClips, extraOptions);
        int roots = 0;
        int unresolved = 0;
        for (int i = 0; i < count; i++) {
            if (parents[i] < 0) {
                roots++;
                String parentName = bones.get(i).parentName;
                if (parentName != null && !parentName.isEmpty()) {
                    unresolved++;
                }
            }
        }
        int rightHand = findHand(bones, "righthandlocator", "rightitem", "righthand");
        int leftHand = findHand(bones, "lefthandlocator", "leftitem", "lefthand");
        Debug.info("[SFH+] geo bones=" + count + " roots=" + roots + " unresolvedParents=" + unresolved
                + " clips=" + clips.size() + " hidden=" + hiddenCount + " extras=" + extraOptions.size()
                + " hands=" + rightHand + "/" + leftHand);
        ModelRuntime runtime = new ModelRuntime(bones, byName, clips, parents, matrices, normals, hidden, propRoot,
                children, extraClips, extraOptions, defaultExtraKey, texture, rightHand, leftHand);
        runtime.controllers = parseControllers(model);
        StringBuilder slots = new StringBuilder();
        for (ModelController controller : runtime.controllers) {
            slots.append(' ').append(controller.slot());
        }
        StringBuilder parallelClips = new StringBuilder();
        int parallelCount = 0;
        for (String name : clips.keySet()) {
            if (name.matches("^(pre_)?parallel[0-7]$")) {
                parallelClips.append(' ').append(name);
                parallelCount++;
            }
        }
        List<String> base = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String baseName = "pre_parallel" + index;
            if (clips.containsKey(baseName)) {
                base.add(baseName);
            }
        }
        runtime.preParallelBase = base;
        if (model.properties != null) {
            runtime.widthScale = model.properties.widthScale > 0.0F ? model.properties.widthScale : 1.0F;
            runtime.heightScale = model.properties.heightScale > 0.0F ? model.properties.heightScale : 1.0F;
        }
        // 一次性尺寸诊断：模型静止包围盒 vs vanilla 玩家模型（HumanoidModel 32px = 2.0 格）
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        org.joml.Vector3f probe = new org.joml.Vector3f();
        for (int i = 0; i < count; i++) {
            if (hidden[i]) {
                continue;
            }
            for (RawYsmModel.RawCube cube : bones.get(i).cubes) {
                if (cube.faces == null) {
                    continue;
                }
                for (RawYsmModel.RawFace face : cube.faces) {
                    if (face.positions == null) {
                        continue;
                    }
                    for (float[] raw : face.positions) {
                        probe.set(raw[0], raw[1], raw[2]);
                        matrices[i].transformPosition(probe);
                        lo = Math.min(lo, probe.y);
                        hi = Math.max(hi, probe.y);
                    }
                }
            }
        }
        Debug.info("[SFH+] size wScale=" + runtime.widthScale + " hScale=" + runtime.heightScale
                + " geoHeight=" + (hi - lo) + " vanillaModelHeight=2.0"
                + " -> ours=" + ((hi - lo) * runtime.heightScale) + " vanilla=" + (2.0F * 0.9375F));
        Debug.info("[SFH+] controllers=" + runtime.controllers.size() + "[" + slots.toString().trim()
                + "] parallelClips=" + parallelCount + "[" + parallelClips.toString().trim() + "]");
        return runtime;
    }

    /** 模型自带的控制器（{@code animationControllerFiles}）解析成状态机列表；没有就是空表。 */
    private static List<ModelController> parseControllers(RawYsmModel model) {
        if (model.mainEntity == null || model.mainEntity.animationControllerFiles == null) {
            return List.of();
        }
        List<ModelController> parsed = new ArrayList<>();
        for (RawYsmModel.RawAnimationControllerFile file : model.mainEntity.animationControllerFiles) {
            if (file == null || file.controllers == null) {
                continue;
            }
            for (RawYsmModel.RawAnimationController controller : file.controllers.values()) {
                ModelController machine = ModelController.parse(controller);
                if (machine != null && !machine.slot().isEmpty()) {
                    parsed.add(machine);
                }
            }
        }
        return parsed;
    }

    /**
     * 第一人称手臂：{@code mainEntity.armModel} 是独立的一套骨骼，动画在 {@code animType=2/11} 的两份文件里
     * （{@code animation-arm} / {@code animation-fp_arm}）。
     *
     * <p>做法是把它包成一个「只有手臂几何 + 手臂动画」的临时模型再走同一个 {@link #build}，
     * 矩阵、采样、UV 全部复用，不用给主模型开分支。动画文件在这里改标成 {@code animType=1}，
     * 因为 {@code build} 只收主模型那一类。
     * {@code hideProps} 固定 false —— 手臂动画里没有基础状态名，道具启发式会把整条手臂当成道具藏掉。
     */
    public static ModelRuntime buildArm(RawYsmModel model, Identifier texture) {
        if (model == null || model.mainEntity == null) {
            return null;
        }
        RawYsmModel.RawGeometry geometry = model.mainEntity.armModel;
        if (geometry == null || geometry.bones == null || geometry.bones.isEmpty()) {
            return null;
        }
        RawYsmModel.RawMainEntity main = new RawYsmModel.RawMainEntity();
        main.mainModel = geometry;
        main.textures = model.mainEntity.textures;
        main.animationFiles = new LinkedHashMap<>();
        for (Map.Entry<String, RawYsmModel.RawAnimationFile> entry : model.mainEntity.animationFiles.entrySet()) {
            RawYsmModel.RawAnimationFile file = entry.getValue();
            if (file == null || (file.animType != 2 && file.animType != 11)) {
                continue;
            }
            RawYsmModel.RawAnimationFile copy = new RawYsmModel.RawAnimationFile();
            copy.animType = 1;
            copy.fileHash = file.fileHash;
            copy.animations = file.animations;
            main.animationFiles.put(entry.getKey(), copy);
        }
        RawYsmModel proxy = new RawYsmModel();
        proxy.formatVersion = model.formatVersion;
        proxy.metadata = model.metadata;
        proxy.properties = model.properties;
        proxy.mainEntity = main;
        ModelRuntime runtime = build(proxy, texture, false);
        if (runtime != null) {
            runtime.initArmSides();
        }
        return runtime;
    }

    /**
     * 手臂模型里通常左右两条手臂各成一根，这里按根 pivot 的 X 正负把它们分开。
     *
     * <p>反序列化阶段已经把 YSM 的 X 取负。渲染时右手还有 {@code x' = -p.x - 0.25} 这一步，
     * 要落在屏幕右侧需要 {@code p.x > 0}，所以「X 为正」才是右手那一条。
     */
    private void initArmSides() {
        int count = bones.size();
        armRootOf = new int[count];
        armRootX = new float[count];
        renderMask = new boolean[count];
        for (int i = 0; i < count; i++) {
            int node = i;
            while (parents[node] >= 0) {
                node = parents[node];
            }
            armRootOf[i] = node;
            armRootX[i] = bones.get(node).pivot[0];
        }
    }

    private static boolean hasZeroScale(RawYsmModel.RawBoneAnimation boneAnimation) {
        if (boneAnimation.scale == null) {
            return false;
        }
        for (RawYsmModel.RawKeyframe keyframe : boneAnimation.scale) {
            Object[] data = keyframe.postData;
            if (data != null && data[0] instanceof Number number && Math.abs(number.doubleValue()) < 1.0E-6) {
                return true;
            }
        }
        return false;
    }

    private static int faceCount(RawYsmModel.RawBone bone) {
        int total = 0;
        if (bone.cubes == null) {
            return 0;
        }
        for (RawYsmModel.RawCube cube : bone.cubes) {
            if (cube.faces != null) {
                total += cube.faces.size();
            }
        }
        return total;
    }

    /** 骨骼名字里带身体部位词根：用来避免把「身体根」当成道具整棵藏掉。 */
    /** YSM 的 Head 组按「去掉结尾数字后等于 Head」匹配（PlayerLocator + GeoLocatorType 的规则）。 */
    private static boolean isHeadBone(String name) {
        if (name == null) {
            return false;
        }
        int end = name.length();
        while (end > 0 && Character.isDigit(name.charAt(end - 1))) {
            end--;
        }
        return end == 4 && name.regionMatches(true, 0, "head", 0, 4);
    }

    private static boolean isBodyName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        for (String word : BODY_WORDS) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 骨骼自身几何的最大边长（面片坐标是骨骼局部坐标，已除 16）。 */
    private static float extentOf(RawYsmModel.RawBone bone) {
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        if (bone.cubes == null) {
            return 0.0F;
        }
        for (RawYsmModel.RawCube cube : bone.cubes) {
            if (cube.faces == null) {
                continue;
            }
            for (RawYsmModel.RawFace face : cube.faces) {
                if (face.positions == null) {
                    continue;
                }
                for (float[] position : face.positions) {
                    any = true;
                    for (int axis = 0; axis < 3; axis++) {
                        min[axis] = Math.min(min[axis], position[axis]);
                        max[axis] = Math.max(max[axis], position[axis]);
                    }
                }
            }
        }
        if (!any) {
            return 0.0F;
        }
        return Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
    }

    private static int[] depthsOf(int[] parents) {
        int count = parents.length;
        int[] depths = new int[count];
        java.util.Arrays.fill(depths, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < count; i++) {
            if (parents[i] < 0) {
                depths[i] = 0;
                queue.add(i);
            }
        }
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (int i = 0; i < count; i++) {
            if (parents[i] >= 0) {
                children.computeIfAbsent(parents[i], key -> new java.util.ArrayList<>()).add(i);
            }
        }
        while (!queue.isEmpty()) {
            int parent = queue.poll();
            for (int child : children.getOrDefault(parent, List.of())) {
                if (depths[child] < 0) {
                    depths[child] = depths[parent] + 1;
                    queue.add(child);
                }
            }
        }
        for (int i = 0; i < count; i++) {
            if (depths[i] < 0) {
                depths[i] = 0;
            }
        }
        return depths;
    }

    private static int findHand(List<RawYsmModel.RawBone> bones, String... candidates) {
        for (String candidate : candidates) {
            for (int i = 0; i < bones.size(); i++) {
                String name = bones.get(i).name;
                if (name != null && name.toLowerCase(Locale.ROOT).equals(candidate)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 轮盘可选项：服装预设（主文件里的 {@code skirtN_original}，只含 scale/position，用来隐藏其它变体）
     * 在前，模型自带的额外动画（{@code animation-extra}，animType=3）在后。
     *
     * <p>没有骨骼通道的动画（只有音效/锁定播放的占位）不列出来。返回默认服装预设的 key，没有则为空串。
     */
    private static String collectExtras(RawYsmModel model, Map<String, RawYsmModel.RawAnimation> clips,
            List<ExtraOption> options) {
        Map<String, String> labels = model.properties == null || model.properties.extraAnimations == null
                ? Map.of()
                : model.properties.extraAnimations;
        if (model.mainEntity == null || model.mainEntity.animationFiles == null) {
            return "";
        }
        List<Map.Entry<String, RawYsmModel.RawAnimation>> variants = new ArrayList<>();
        List<ExtraOption> extras = new ArrayList<>();
        for (RawYsmModel.RawAnimationFile file : model.mainEntity.animationFiles.values()) {
            if (file == null || file.animations == null) {
                continue;
            }
            for (Map.Entry<String, RawYsmModel.RawAnimation> entry : file.animations.entrySet()) {
                RawYsmModel.RawAnimation animation = entry.getValue();
                if (animation == null || animation.boneAnimations == null || animation.boneAnimations.isEmpty()) {
                    continue;
                }
                String name = entry.getKey();
                if (isVariantName(name)) {
                    variants.add(entry);
                } else if (file.animType == 3) {
                    // 作者用 extraAnimations 给出菜单；没有出现在菜单里的（如 extra0 配置动画）不列
                    if (!labels.isEmpty() && !labels.containsKey(name)) {
                        continue;
                    }
                    String label = labels.getOrDefault(name, name);
                    // 以 # 开头的是作者的配置/子菜单标记（如 #extra_config），不进轮盘
                    if (label.startsWith("#")) {
                        continue;
                    }
                    extras.add(new ExtraOption(name, label, false));
                }
            }
        }
        variants.sort(Comparator.comparingInt(entry -> variantIndex(entry.getKey())));
        if (!labels.isEmpty()) {
            Map<String, Integer> order = new HashMap<>();
            int position = 0;
            for (String key : labels.keySet()) {
                order.put(key, position++);
            }
            extras.sort(Comparator.comparingInt(option -> order.getOrDefault(option.key(), Integer.MAX_VALUE)));
        }
        for (Map.Entry<String, RawYsmModel.RawAnimation> entry : variants) {
            RawYsmModel.RawAnimation animation = entry.getValue();
            animation.name = entry.getKey();
            clips.putIfAbsent(entry.getKey(), animation);
            options.add(new ExtraOption(entry.getKey(), "服装 " + variantIndex(entry.getKey()), true));
        }
        for (ExtraOption option : extras) {
            RawYsmModel.RawAnimation animation = findAnimation(model, option.key());
            if (animation == null) {
                continue;
            }
            animation.name = option.key();
            clips.putIfAbsent(option.key(), animation);
            options.add(option);
        }
        return variants.isEmpty() ? "" : variants.get(0).getKey();
    }

    private static RawYsmModel.RawAnimation findAnimation(RawYsmModel model, String name) {
        for (RawYsmModel.RawAnimationFile file : model.mainEntity.animationFiles.values()) {
            if (file != null && file.animations != null && file.animations.containsKey(name)) {
                return file.animations.get(name);
            }
        }
        return null;
    }

    private static boolean isVariantName(String name) {
        return name != null && name.startsWith("skirt") && name.endsWith("_original");
    }

    private static int variantIndex(String name) {
        try {
            return Integer.parseInt(name.substring("skirt".length(), name.length() - "_original".length()));
        } catch (RuntimeException ignored) {
            return 99;
        }
    }

    /** 轮盘列表（含服装预设）；每次选中都走 {@link #setExtra(String)}。 */
    public List<ExtraOption> extras() {
        return java.util.Collections.unmodifiableList(extraOptions);
    }

    public String activeExtraKey() {
        return activeExtraKey;
    }

    public String activeVariantKey() {
        return activeVariantKey;
    }

    /** 没有服装预设时为空串；有的话是索引最小的那个。 */
    public String defaultVariantKey() {
        return defaultExtraKey;
    }

    /** 服装预设：静态覆盖层，和额外动作互不挤占。 */
    public void setVariant(String key) {
        String wanted = key == null ? "" : key;
        RawYsmModel.RawAnimation clip = wanted.isEmpty() ? null : extraClips.get(wanted);
        activeVariant = clip;
        activeVariantKey = clip == null ? "" : wanted;
        recomputeHidden();
    }

    /**
     * 额外动作：循环叠加层；空串表示不播。
     *
     * <p>叠加语义——只有动画写到的通道才覆盖，没写到的保持走路/待机；
     * 同时把它引用到的、默认藏起来的道具整棵放出来（弹琴要看得见钢琴）。
     */
    public void setExtra(String key) {
        String wanted = key == null ? "" : key;
        RawYsmModel.RawAnimation clip = wanted.isEmpty() ? null : extraClips.get(wanted);
        if (clip == null && !wanted.isEmpty()) {
            return;
        }
        if (clip != activeExtra) {
            activeExtraStart = Float.NaN;
        }
        activeExtra = clip;
        activeExtraKey = clip == null ? "" : wanted;
        recomputeHidden();
    }

    /** 轮盘点击：同一条再点一次就取消；变体与动作各占一个槽，互不覆盖。 */
    public void toggle(String key) {
        ExtraOption option = findOption(key);
        if (option == null) {
            return;
        }
        if (option.variant()) {
            setVariant(option.key().equals(activeVariantKey) ? "" : option.key());
        } else {
            setExtra(option.key().equals(activeExtraKey) ? "" : option.key());
        }
    }

    public void clearAll() {
        activeVariant = null;
        activeVariantKey = "";
        activeExtra = null;
        activeExtraKey = "";
        activeExtraStart = Float.NaN;
        recomputeHidden();
    }

    private ExtraOption findOption(String key) {
        for (ExtraOption option : extraOptions) {
            if (option.key().equals(key)) {
                return option;
            }
        }
        return null;
    }

    private void recomputeHidden() {
        System.arraycopy(hiddenDefault, 0, hiddenCurrent, 0, hiddenCurrent.length);
        Set<Integer> roots = new HashSet<>();
        collectPropRoots(activeVariant, roots);
        collectPropRoots(activeExtra, roots);
        for (int root : roots) {
            unhideSubtree(root);
        }
    }

    private void collectPropRoots(RawYsmModel.RawAnimation clip, Set<Integer> roots) {
        if (clip == null) {
            return;
        }
        for (RawYsmModel.RawBoneAnimation animation : clip.boneAnimations) {
            if (animation.boneName == null) {
                continue;
            }
            Integer index = boneIndex.get(animation.boneName);
            if (index == null) {
                continue;
            }
            int root = propRoot[index];
            if (root >= 0) {
                roots.add(root);
            }
        }
    }

    private void unhideSubtree(int root) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            hiddenCurrent[current] = false;
            for (int child : children.getOrDefault(current, List.of())) {
                queue.add(child);
            }
        }
    }

    private float overlayTime(RawYsmModel.RawAnimation clip, float ageSeconds) {
        float length = lengthOf(clip);
        if (length <= 0.0F) {
            return 0.0F;
        }
        float elapsed = Math.max(0.0F, ageSeconds - activeExtraStart);
        return elapsed % length;
    }

    /** 额外动画只覆盖它写到的通道；没写的保持基础姿态。 */
    private void sampleOverlay(RawYsmModel.RawAnimation clip, int boneIndex, float time, float lifeTime) {
        String boneName = bones.get(boneIndex).name;
        if (boneName == null) {
            return;
        }
        for (RawYsmModel.RawBoneAnimation animation : clip.boneAnimations) {
            if (!boneName.equals(animation.boneName)) {
                continue;
            }
            int offset = boneIndex * 9;
            float[] rotation = sampleChannel(animation.rotation, time, lifeTime);
            if (rotation != null) {
                poses[offset] = (float) -Math.toRadians(finite(rotation[0], 0.0F));
                poses[offset + 1] = (float) -Math.toRadians(finite(rotation[1], 0.0F));
                poses[offset + 2] = (float) Math.toRadians(finite(rotation[2], 0.0F));
            }
            float[] position = sampleChannel(animation.position, time, lifeTime);
            if (position != null) {
                poses[offset + 3] = finite(position[0], 0.0F);
                poses[offset + 4] = finite(position[1], 0.0F);
                poses[offset + 5] = finite(position[2], 0.0F);
            }
            float[] scale = sampleChannel(animation.scale, time, lifeTime);
            if (scale != null) {
                poses[offset + 6] = finite(scale[0], 1.0F);
                poses[offset + 7] = finite(scale[1], 1.0F);
                poses[offset + 8] = finite(scale[2], 1.0F);
            }
            return;
        }
    }

    private static Matrix4f computeRest(int index, List<RawYsmModel.RawBone> bones, int[] parents,
            Matrix4f[] matrices, byte[] state) {
        if (state[index] == 2) {
            return matrices[index];
        }
        if (state[index] == 1) {
            return new Matrix4f();
        }
        state[index] = 1;
        Matrix4f local = localOf(bones.get(index), null, 0);
        int parent = parents[index];
        Matrix4f world = parent < 0
                ? local
                : new Matrix4f(computeRest(parent, bones, parents, matrices, state)).mul(local);
        matrices[index] = world;
        state[index] = 2;
        return world;
    }

    /**
     * 骨骼局部矩阵：{@code translate(pivot) · rotZ/rotY/rotX · scale · translate(-pivot)}；
     * 动画位移在第一步减/加进去（与 SPM 的 {@code calculateBoneMatrixLinear} 同形）。
     */
    private static Matrix4f localOf(RawYsmModel.RawBone bone, float[] pose, int offset) {
        Matrix4f matrix = new Matrix4f();
        float[] pivot = bone.pivot;
        float animX = pose == null ? 0.0F : pose[offset + 3];
        float animY = pose == null ? 0.0F : pose[offset + 4];
        float animZ = pose == null ? 0.0F : pose[offset + 5];
        float rotX = (pose == null ? 0.0F : pose[offset]) + bone.rotation[0];
        float rotY = (pose == null ? 0.0F : pose[offset + 1]) + bone.rotation[1];
        float rotZ = (pose == null ? 0.0F : pose[offset + 2]) + bone.rotation[2];
        float scaleX = pose == null ? 1.0F : pose[offset + 6];
        float scaleY = pose == null ? 1.0F : pose[offset + 7];
        float scaleZ = pose == null ? 1.0F : pose[offset + 8];
        matrix.translate((pivot[0] - animX) / 16.0F, (pivot[1] + animY) / 16.0F, (pivot[2] + animZ) / 16.0F);
        matrix.rotateZ(rotZ);
        matrix.rotateY(rotY);
        matrix.rotateX(rotX);
        matrix.scale(scaleX, scaleY, scaleZ);
        matrix.translate(-pivot[0] / 16.0F, -pivot[1] / 16.0F, -pivot[2] / 16.0F);
        return matrix;
    }

    private static Matrix3f normalOf(Matrix4f matrix) {
        try {
            return new Matrix3f(matrix).invert().transpose();
        } catch (RuntimeException singular) {
            return new Matrix3f();
        }
    }

    public Identifier texture() {
        return texture;
    }

    /**
     * 该模型是否自己承担鞘翅平飞时的实体俯仰（因此这一层要抵消掉原版的俯仰与朝向）。
     *
     * <p>判据取 YSM 官方渲染器：{@code GeoReplacedEntityRenderer#setupRotations} 直接 super，
     * 实体级从不补平飞俯仰；SPM 则是按模型判（{@code detectsFallFlyingRootRotation}）。我们跟着 YSM 走，
     * 缺省抵消；将来接模块开关时改这里即可，调用方不用动。
     */
    public boolean handlesElytraPitch() {
        return true;
    }

    public float widthScale() {
        return widthScale;
    }

    public float heightScale() {
        return heightScale;
    }

    public int boneCount() {
        return bones.size();
    }

    /**
     * 手持物品的挂点：{@code 手骨骼矩阵 · translate(pivot) · 默认握持偏移}。
     *
     * <p>与 YSM 的 {@code RenderUtils.prepMatrixForLocator} + {@code applyAuthoredDefaultHandTransform}
     * 同形；调用方把这个矩阵乘进实体姿态即可让物品跟手走。
     */
    public Matrix4f handItemMatrix(boolean rightHand) {
        int index = rightHand ? rightHandBone : leftHandBone;
        if (index < 0) {
            return null;
        }
        Matrix4f matrix = new Matrix4f(frameValid ? frameMatrices[index] : restMatrices[index]);
        float[] pivot = bones.get(index).pivot;
        matrix.translate(pivot[0] / 16.0F, pivot[1] / 16.0F, pivot[2] / 16.0F);
        matrix.translate(0.0F, -0.0625F, -0.1F);
        matrix.rotateX((float) Math.toRadians(-90.0F));
        return matrix;
    }

    public void render(PoseStack.Pose pose, VertexConsumer buffer, int light) {
        frameValid = false;
        emit(pose, buffer, light, restMatrices, restNormals);
    }

    public void render(PoseStack.Pose pose, VertexConsumer buffer, int light,
            AvatarRenderState avatar, LocalPlayer player, float ageSeconds) {
        RawYsmModel.RawAnimation clip = pickClip(avatar, player);
        if (clip == null) {
            render(pose, buffer, light);
            return;
        }
        MolangVariables.bind(player, avatar);
        int hurtTime = player == null ? 0 : player.hurtTime;
        boolean restart = clip != lastClip;
        if (ONE_SHOT.contains(clipName(clip)) && hurtTime > lastHurtTime) {
            restart = true;
        }
        lastHurtTime = hurtTime;
        if (restart) {
            lastClip = clip;
            lastClipStart = ageSeconds;
        }
        float time = clipTime(clip, ageSeconds);
        java.util.Arrays.fill(frameState, (byte) 0);
        for (int i = 0; i < bones.size(); i++) {
            resetPose(i);
        }
        // 默认层 pre_parallel0..7：由代码侧控制器无条件循环播放，道具「默认不显示」就是靠它们把骨骼缩放成 0。
        // 优先级低于主动画（YSM 里 pre_parallel 先注册、main 后注册），所以先叠它、再让主动画覆盖。
        for (String baseName : preParallelBase) {
            RawYsmModel.RawAnimation base = clips.get(baseName);
            if (base == null) {
                continue;
            }
            float baseLength = lengthOf(base);
            float baseTime = baseLength <= 0.0F ? 0.0F : ageSeconds % baseLength;
            for (int i = 0; i < bones.size(); i++) {
                sampleOverlay(base, i, baseTime, ageSeconds);
            }
        }
        for (int i = 0; i < bones.size(); i++) {
            samplePoseOver(clip, i, time, ageSeconds);
        }
        // 服装变体是静态预设（只有缩放/位移），动作是循环播放；两层叠加，动作最后生效
        if (activeVariant != null) {
            for (int i = 0; i < bones.size(); i++) {
                sampleOverlay(activeVariant, i, 0.0F, ageSeconds);
            }
        }
        if (activeExtra != null) {
            if (!Float.isFinite(activeExtraStart)) {
                activeExtraStart = ageSeconds;
            }
            float extraTime = overlayTime(activeExtra, ageSeconds);
            for (int i = 0; i < bones.size(); i++) {
                sampleOverlay(activeExtra, i, extraTime, ageSeconds);
            }
        }
        // 控制器槽（parallel*/pre_parallel*）：模型自带的道具显隐、换装都写在这些片段里，
        // 由状态机按 Molang 条件选一个当覆盖层，叠在基础姿态之上。
        for (ModelController controller : controllers) {
            String overlayName = controller.tick(time, ageSeconds);
            if (overlayName == null) {
                continue;
            }
            RawYsmModel.RawAnimation overlayClip = clips.get(overlayName);
            if (overlayClip == null || overlayClip.boneAnimations == null) {
                continue;
            }
            float overlayLength = lengthOf(overlayClip);
            float overlayTime = overlayLength <= 0.0F ? 0.0F : ageSeconds % overlayLength;
            for (int i = 0; i < bones.size(); i++) {
                sampleOverlay(overlayClip, i, overlayTime, ageSeconds);
            }
        }
        // 头部朝向：YSM 在**动画求值之后**把视线角度加到 Head 组骨骼上（CustomHumanoidEntity#applyHeadTracking）。
        // 不做这一层，模型的头就永远跟着身体走，转头转头都不动，看着很怪。
        if (avatar != null) {
            float headX = (float) Math.toRadians(-avatar.xRot);
            float headY = (float) Math.toRadians(-net.minecraft.util.Mth.clamp(
                    net.minecraft.util.Mth.wrapDegrees(avatar.yRot - avatar.bodyRot), -85.0F, 85.0F));
            for (int i = 0; i < bones.size(); i++) {
                if (!isHeadBone(bones.get(i).name)) {
                    continue;
                }
                int offset = i * 9;
                poses[offset] += headX;
                poses[offset + 1] += headY;
            }
        }
        for (int i = 0; i < bones.size(); i++) {
            computeFrame(i);
            frameNormals[i] = normalOf(frameMatrices[i]);
        }
        frameValid = true;
        int emitted = emit(pose, buffer, light, frameMatrices, frameNormals);
        if (!logged) {
            logged = true;
            Matrix4f first = frameMatrices.length == 0 ? null : frameMatrices[0];
            Debug.info("[SFH+] anim clip=" + clipName(clip) + " len=" + clip.length + " loop=" + clip.loopMode
                    + " t=" + time + " bones=" + bones.size() + " emitted=" + emitted
                    + " m0=" + (first == null ? "null" : first.m30() + "," + first.m31() + "," + first.m32()));
        }
    }

    /**
     * 第一人称手臂的每帧出图：只播 {@code fp_arm}（没有就退回 {@code arm}），循环。
     *
     * <p>不按实体状态选片段 —— 第一人称看不到身体，YSM 在这里也只播手臂动画。
     */
    public void renderFirstPerson(PoseStack.Pose pose, VertexConsumer buffer, int light, float ageSeconds,
            boolean rightArm) {
        if (armRootOf != null) {
            for (int i = 0; i < bones.size(); i++) {
                renderMask[i] = rightArm ? armRootX[i] >= 0.0F : armRootX[i] < 0.0F;
            }
        }
        RawYsmModel.RawAnimation clip = firstOf(FP_ARM);
        if (clip == null) {
            render(pose, buffer, light);
            return;
        }
        if (clip != lastClip) {
            lastClip = clip;
            lastClipStart = ageSeconds;
        }
        MolangVariables.bind(Minecraft.getInstance().player, null);
        float time = clipTime(clip, ageSeconds);
        java.util.Arrays.fill(frameState, (byte) 0);
        for (int i = 0; i < bones.size(); i++) {
            resetPose(i);
            samplePoseOver(clip, i, time, ageSeconds);
        }
        for (int i = 0; i < bones.size(); i++) {
            computeFrame(i);
            frameNormals[i] = normalOf(frameMatrices[i]);
        }
        frameValid = true;
        emit(pose, buffer, light, frameMatrices, frameNormals);
    }

    private Matrix4f computeFrame(int index) {
        if (frameState[index] == 2) {
            return frameMatrices[index];
        }
        if (frameState[index] == 1) {
            return new Matrix4f();
        }
        frameState[index] = 1;
        Matrix4f local = localOf(bones.get(index), poses, index * 9);
        int parent = parents[index];
        Matrix4f world = parent < 0 ? local : new Matrix4f(computeFrame(parent)).mul(local);
        frameMatrices[index] = world;
        frameState[index] = 2;
        return world;
    }

    /** 采样三个通道，写入该骨骼的 9 个 float（弧度/像素/倍率）；字符串值按 Molang 求值。 */
    /** 把一根骨骼的姿态复位成静止值；分层叠加靠它划清每帧的起点。 */
    private void resetPose(int boneIndex) {
        int offset = boneIndex * 9;
        poses[offset] = 0.0F;
        poses[offset + 1] = 0.0F;
        poses[offset + 2] = 0.0F;
        poses[offset + 3] = 0.0F;
        poses[offset + 4] = 0.0F;
        poses[offset + 5] = 0.0F;
        poses[offset + 6] = 1.0F;
        poses[offset + 7] = 1.0F;
        poses[offset + 8] = 1.0F;
    }

    /**
     * 把片段里该骨骼的通道叠到当前姿态上，**不复位**。
     *
     * <p>分层顺序：复位 → {@code pre_parallel0..7}（默认层，优先级最低）→ 主状态片段，
     * 后写的覆盖先写的。这与 YSM 的控制器注册顺序一致（pre_parallel 先注册、main 后注册）。
     */
    private void samplePoseOver(RawYsmModel.RawAnimation clip, int boneIndex, float time, float lifeTime) {
        int offset = boneIndex * 9;
        String boneName = bones.get(boneIndex).name;
        if (boneName == null) {
            return;
        }
        for (RawYsmModel.RawBoneAnimation animation : clip.boneAnimations) {
            if (!boneName.equals(animation.boneName)) {
                continue;
            }
            float[] rotation = sampleChannel(animation.rotation, time, lifeTime);
            if (rotation != null) {
                poses[offset] = (float) -Math.toRadians(finite(rotation[0], 0.0F));
                poses[offset + 1] = (float) -Math.toRadians(finite(rotation[1], 0.0F));
                poses[offset + 2] = (float) Math.toRadians(finite(rotation[2], 0.0F));
            }
            float[] position = sampleChannel(animation.position, time, lifeTime);
            if (position != null) {
                poses[offset + 3] = finite(position[0], 0.0F);
                poses[offset + 4] = finite(position[1], 0.0F);
                poses[offset + 5] = finite(position[2], 0.0F);
            }
            float[] scale = sampleChannel(animation.scale, time, lifeTime);
            if (scale != null) {
                poses[offset + 6] = finite(scale[0], 1.0F);
                poses[offset + 7] = finite(scale[1], 1.0F);
                poses[offset + 8] = finite(scale[2], 1.0F);
            }
            return;
        }
    }

    /** Molang 的未知查询会算出 NaN；落到通道上时取中性值（旋转/位移 0，缩放 1）。 */
    private static float finite(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    private static float[] sampleChannel(List<RawYsmModel.RawKeyframe> frames, float time, float lifeTime) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        int last = frames.size() - 1;
        if (time <= frames.get(0).timestamp) {
            return valuesOf(frames.get(0).postData, time, lifeTime);
        }
        if (time >= frames.get(last).timestamp) {
            return valuesOf(frames.get(last).postData, time, lifeTime);
        }
        for (int i = 0; i < last; i++) {
            RawYsmModel.RawKeyframe current = frames.get(i);
            RawYsmModel.RawKeyframe next = frames.get(i + 1);
            if (time < current.timestamp || time > next.timestamp) {
                continue;
            }
            float[] a = valuesOf(current.postData, time, lifeTime);
            if (a == null) {
                return null;
            }
            if (current.interpolationMode == RawYsmModel.RawKeyframe.INTERPOLATION_STEP) {
                return a;
            }
            float[] b = valuesOf(next.postData, time, lifeTime);
            if (b == null) {
                return a;
            }
            float span = next.timestamp - current.timestamp;
            float t = span <= 0.0F ? 0.0F : (time - current.timestamp) / span;
            return new float[]{
                    a[0] + (b[0] - a[0]) * t,
                    a[1] + (b[1] - a[1]) * t,
                    a[2] + (b[2] - a[2]) * t};
        }
        return valuesOf(frames.get(last).postData, time, lifeTime);
    }

    private static float[] valuesOf(Object[] values, float time, float lifeTime) {
        if (values == null || values.length < 3) {
            return null;
        }
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            Object value = values[i];
            if (value instanceof Number number) {
                result[i] = number.floatValue();
            } else if (value instanceof String text) {
                result[i] = MolangExpression.of(text).eval(time, lifeTime);
            } else {
                return null;
            }
        }
        return result;
    }

    private float clipTime(RawYsmModel.RawAnimation clip, float ageSeconds) {
        float length = lengthOf(clip);
        if (length <= 0.0F) {
            return 0.0F;
        }
        float elapsed = Math.max(0.0F, ageSeconds - lastClipStart);
        if (ONE_SHOT.contains(clipName(clip))) {
            return Math.min(elapsed, length);
        }
        return elapsed % length;
    }

    private static float lengthOf(RawYsmModel.RawAnimation clip) {
        float length = clip.length;
        if (!Float.isFinite(length) || length <= 0.0F) {
            length = maxKeyframeTime(clip);
        }
        return Float.isFinite(length) && length > 0.0F ? length : 0.0F;
    }

    private static float maxKeyframeTime(RawYsmModel.RawAnimation clip) {
        float max = 0.0F;
        for (RawYsmModel.RawBoneAnimation animation : clip.boneAnimations) {
            max = Math.max(max, lastTime(animation.rotation));
            max = Math.max(max, lastTime(animation.position));
            max = Math.max(max, lastTime(animation.scale));
        }
        return max;
    }

    private static float lastTime(List<RawYsmModel.RawKeyframe> frames) {
        return frames == null || frames.isEmpty() ? 0.0F : frames.get(frames.size() - 1).timestamp;
    }

    private RawYsmModel.RawAnimation firstOf(String[] candidates) {
        for (String name : candidates) {
            RawYsmModel.RawAnimation clip = clips.get(name);
            if (clip != null) {
                return clip;
            }
        }
        return null;
    }

    /** 状态表按 YSM 的 {@code AnimationRegister} 排序；缺失的动画退回 idle，再退回静止。 */
    private RawYsmModel.RawAnimation pickClip(AvatarRenderState avatar, LocalPlayer player) {
        if (avatar == null) {
            return firstOf(IDLE);
        }
        RawYsmModel.RawAnimation clip;
        if (player != null) {
            if (player.isDeadOrDying()) {
                return orIdle(firstOf(DEATH));
            }
            if (player.isAutoSpinAttack()) {
                return orIdle(firstOf(RIPTIDE));
            }
            if (player.getPose() == Pose.SLEEPING) {
                return orIdle(firstOf(SLEEP));
            }
            if (avatar.isPassenger) {
                return orIdle(rideClip(player, avatar));
            }
            if (player.isSwimming()) {
                return orIdle(firstOf(SWIM));
            }
            boolean swimmingPose = player.getPose() == Pose.SWIMMING;
            if (swimmingPose) {
                clip = firstOf(avatar.walkAnimationSpeed > MIN_MOVING ? CLIMB : CLIMBING);
                if (clip != null) {
                    return clip;
                }
            }
            if (player.onClimbable()) {
                double vertical = player.getDeltaMovement().y;
                clip = firstOf(vertical > 1.0E-4 ? LADDER_UP : vertical < -1.0E-4 ? LADDER_DOWN : LADDER_STILL);
                if (clip != null) {
                    return clip;
                }
            }
            if (player.getAbilities().flying) {
                return orIdle(firstOf(FLY));
            }
            if (player.isFallFlying()) {
                return orIdle(firstOf(ELYTRA));
            }
            if (player.isInWater() && !player.onGround()) {
                return orIdle(firstOf(SWIM_STAND));
            }
            if (player.hurtTime > 0) {
                RawYsmModel.RawAnimation attacked = firstOf(ATTACKED);
                if (attacked != null) {
                    return attacked;
                }
            }
            if (!player.onGround() && !player.isInWater()) {
                return orIdle(firstOf(JUMP));
            }
            if (player.onGround() && avatar.isCrouching) {
                return orIdle(firstOf(avatar.walkAnimationSpeed > MIN_MOVING ? SNEAK : SNEAK_STILL));
            }
            if (avatar.isUsingItem && avatar.walkAnimationSpeed <= MIN_MOVING) {
                return orIdle(firstOf(avatar.useItemHand == InteractionHand.MAIN_HAND ? USE_MAIN : USE_OFF));
            }
            if (player.onGround() && player.isSprinting()) {
                return orIdle(firstOf(RUN));
            }
            if (player.onGround() && avatar.walkAnimationSpeed > MIN_MOVING) {
                return orIdle(firstOf(WALK));
            }
        }
        if (avatar.isUsingItem) {
            return orIdle(firstOf(avatar.useItemHand == InteractionHand.MAIN_HAND ? USE_MAIN : USE_OFF));
        }
        if (avatar.isFallFlying) {
            return orIdle(firstOf(ELYTRA));
        }
        if (avatar.isVisuallySwimming) {
            return orIdle(firstOf(SWIM));
        }
        if (avatar.isPassenger) {
            return orIdle(rideClip(player, avatar));
        }
        if (avatar.isCrouching) {
            return orIdle(firstOf(avatar.walkAnimationSpeed > MIN_MOVING ? SNEAK : SNEAK_STILL));
        }
        if (avatar.walkAnimationSpeed > MIN_MOVING) {
            return orIdle(firstOf(WALK));
        }
        return firstOf(IDLE);
    }

    /**
     * 载具对应的动画，顺序照 YSM 的 {@code VehiclePredicate}：
     * 猪 → {@code ride_pig}，可骑乘（马/驴/骆驼…）→ {@code ride}，船 → {@code boat}，其余 → {@code sit}。
     *
     * <p>必须先判猪再判可骑乘：{@code Pig} 自己就实现了 {@code Saddleable}。
     */
    private RawYsmModel.RawAnimation rideClip(LocalPlayer player, AvatarRenderState avatar) {
        Entity vehicle = player == null ? null : player.getVehicle();
        if (vehicle instanceof net.minecraft.world.entity.animal.pig.Pig) {
            return firstOf(RIDE_PIG);
        }
        if (vehicle instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat) {
            return firstOf(RIDE_BOAT);
        }
        // 其余可骑乘（马/驴/骆驼/矿车…）走通用 ride；YSM 对「不认识」的载具才退 sit，
        // 我们没接模型自带载具条件，先按通用 ride 处理更稳。
        return firstOf(RIDE);
    }

    private RawYsmModel.RawAnimation orIdle(RawYsmModel.RawAnimation clip) {
        return clip != null ? clip : firstOf(IDLE);
    }

    private int emit(PoseStack.Pose pose, VertexConsumer buffer, int light,
            Matrix4f[] matrices, Matrix3f[] normals) {
        int emitted = 0;
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int i = 0; i < bones.size(); i++) {
            Matrix4f matrix = matrices[i];
            if (matrix == null) {
                continue;
            }
            if (hiddenCurrent[i]) {
                continue;
            }
            if (renderMask != null && !renderMask[i]) {
                continue;
            }
            Matrix3f normalMatrix = normals[i];
            for (RawYsmModel.RawCube cube : bones.get(i).cubes) {
                if (cube.faces == null) {
                    continue;
                }
                for (RawYsmModel.RawFace face : cube.faces) {
                    if (face.positions == null || face.u == null || face.v == null) {
                        continue;
                    }
                    float[] faceNormal = face.normal == null ? new float[]{0.0F, 1.0F, 0.0F} : face.normal;
                    normal.set(faceNormal[0], faceNormal[1], faceNormal[2]).mul(normalMatrix);
                    if (normal.lengthSquared() > 0.0F) {
                        normal.normalize();
                    }
                    for (int vertex = 0; vertex < 4; vertex++) {
                        float[] raw = face.positions[vertex];
                        position.set(raw[0], raw[1], raw[2]);
                        matrix.transformPosition(position);
                        buffer.addVertex(pose, position.x, position.y, position.z)
                                .setColor(255, 255, 255, 255)
                                .setUv(face.u[vertex], face.v[vertex])
                                .setOverlay(OverlayTexture.NO_OVERLAY)
                                .setLight(light)
                                .setNormal(pose, normal.x, normal.y, normal.z);
                        emitted++;
                    }
                }
            }
        }
        return emitted;
    }
}