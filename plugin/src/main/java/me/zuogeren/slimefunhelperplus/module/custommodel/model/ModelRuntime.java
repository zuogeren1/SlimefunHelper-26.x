package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import com.mojang.blaze3d.platform.NativeImage;
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
import me.zuogeren.slimefunhelperplus.module.custommodel.audio.ModelSound;
import me.zuogeren.slimefunhelperplus.module.custommodel.audio.ModelSoundCursorTable;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Pose;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
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

    /** 发光骨的骨名前缀：YSM 的 {@code GeoBone:6,50} 与 SPM 的 {@code GeometryBaker:60} 都用它。 */
    private static final String GLOW_BONE_PREFIX = "ysmGlow";

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
    /** 挥动：主手前缀是 {@code swing}（没有下划线），副手才是 {@code swing_offhand}（YSM ConditionalSwing）。 */
    private static final String[] SWING_MAIN = {"swing_hand", "swing"};
    private static final String[] SWING_OFF = {"swing_offhand"};
    private static final String[] JUMP = {"jump"};
    private static final String[] FP_ARM = {"fp_arm", "arm", "idle", "empty"};

    /** 只播一次的状态；其余主状态按 YSM 的 {@code playLoopAnimation} 语义一律循环。 */
    private static final Set<String> ONE_SHOT = Set.of("death", "attacked");

    /**
     * 控制器槽名（去掉 {@code player.} 前缀），按 YSM {@code PlayerControllerCollection:33-74} 的注册顺序排列。
     *
     * <p>顺序即合成顺序：越靠后越晚叠上去。{@code pre_*} 在对应主槽之前、{@code post_*} 之后，
     * {@code parallel} 排在 {@code use} 之上。
     */
    private static final List<String> CONTROLLER_LAYERS = List.of(
            "pre_parallel",
            "pre_main", "main", "post_main",
            "pre_hold", "hold_offhand", "hold_mainhand", "post_hold",
            "pre_swing", "swing", "post_swing",
            "pre_use", "use", "post_use",
            "parallel");

    /**
     * 允许多实例的槽：YSM 用 {@code multi(...)} 注册，槽名是 {@code player.<槽>_<后缀>}。
     *
     * <p>其余槽（{@code main} / {@code hold_*} / {@code swing} / {@code use}）是 {@code simple(...)} 单实例，
     * 带后缀的名字不是 YSM 的槽，归到自定义桶，否则会落进一个没人采样的桶里。
     */
    private static final List<String> MULTI_SLOTS = List.of(
            "pre_parallel", "pre_main", "post_main",
            "pre_hold", "post_hold", "pre_swing", "post_swing", "pre_use", "post_use",
            "parallel");

    /**
     * YSM 的定位组名（{@code PlayerLocator:13-25}）：按「去掉结尾数字后全等」匹配骨骼名，
     * 一组可以有多根骨（{@code GeoModelState#visitLocatorGroup} 逐根回调）。
     *
     * <p>只列渲染层真正会挂东西的几个：装备/鞘翅/鹦鹉/头顶物/背包。手部两组走
     * {@link #RIGHT_HAND_CHAIN} 的候选链，不在这里。
     */
    private static final String[] LOCATOR_NAMES = {
            "Head", "ElytraLocator", "BackpackLocator",
            "LeftShoulderLocator", "RightShoulderLocator",
            "LeftWaistLocator", "RightWaistLocator",
            "BladeLocator", "SheathLocator",
            "PistolLocator", "RifleLocator"
    };

    /** 参与「基础动作」的动画名：被这些动画驱动的骨骼不算舞台道具。 */
    private static final Set<String> MAIN_STATES = Set.of(
            "idle", "walk", "run", "sneak", "sneaking", "fly", "elytra_fly", "swim", "swim_stand",
            "ride", "boat", "jump", "attacked", "death", "riptide", "sleep", "climb", "climbing",
            "ladder_up", "ladder_down", "ladder_stillness", "use_mainhand", "use_offhand",
            "swing_hand", "swing_offhand", "sit");

    private static final float MIN_MOVING = 0.05F;
    /** 道具骨骼的最小尺寸（格）：比这个小的大多是耳饰、表情、衣角这类身体附件。 */
    private static final float PROP_MIN_SIZE = 0.75F;
    /** 明显不属于角色本体的部件（背景板、载具）。 */
    private static final float OVERSIZED = 4.0F;
    /** 身体部位词根：子树里出现这些名字就不按「道具」整棵隐藏。 */
    private static final String[] BODY_WORDS = {
            "head", "body", "arm", "hand", "leg", "foot", "hair", "eye", "finger", "neck", "waist", "chest"
    };

    /**
     * 手持物挂点候选链，按优先级短路取第一根存在的骨骼。
     *
     * <p>YSM 只认 {@code RightHandLocator}/{@code LeftHandLocator}，没有这两根骨的模型在原版里就不画手持物；
     * 而原版手持物渲染已被 cancel（{@code PlayerModelSubmitMixin}），这里找不到骨就等于物品完全不出现，
     * 所以按 SPM {@code GeometryBaker#buildPathFirst} 的顺序回退：先专用定位组，再退到手臂链。
     *
     * <p>主链（{@code *HandLocator → *Item → *Hand → *Palm → *Wrist → *ForeArm → *LowerArm → *Arm}）
     * 的相对顺序与 SPM 一致；{@code *HandLocator2..8} 与 {@code *Sword} 是 YSM 2.4.0 起的额外定位组，
     * 插在专用定位组之后、手臂链之前 —— 它们和 {@code *HandLocator} 一样落在手上，而手臂骨的 pivot
     * 在肩关节（内置 27 个模型里 RightArm 的 pivot Y 比 RightHandLocator 高 7~14 像素），
     * 拿手臂骨当挂点会把物品画到肩上；若把它们排到手臂链之后，有手臂骨的模型就永远走不到。
     */
    private static final String[] RIGHT_HAND_CHAIN = {
            "righthandlocator", "rightitem", "righthand",
            "righthandlocator2", "righthandlocator3", "righthandlocator4", "righthandlocator5",
            "righthandlocator6", "righthandlocator7", "righthandlocator8", "rightsword",
            "rightpalm", "rightwrist", "rightforearm", "rightlowerarm", "rightarm"
    };
    private static final String[] LEFT_HAND_CHAIN = {
            "lefthandlocator", "leftitem", "lefthand",
            "lefthandlocator2", "lefthandlocator3", "lefthandlocator4", "lefthandlocator5",
            "lefthandlocator6", "lefthandlocator7", "lefthandlocator8", "leftsword",
            "leftpalm", "leftwrist", "leftforearm", "leftlowerarm", "leftarm"
    };

    private final List<RawYsmModel.RawBone> bones;
    private final Map<String, Integer> boneIndex;
    private final Map<String, RawYsmModel.RawAnimation> clips;
    /** 模型缺失片段时的兜底表（默认模型的 main + arm）；只读，且不参与模型自身的统计。 */
    private final Map<String, RawYsmModel.RawAnimation> defaultClips;
    private final int[] parents;
    private final Matrix4f[] restMatrices;
    private final Matrix3f[] restNormals;
    private final Matrix4f[] frameMatrices;
    private final Matrix3f[] frameNormals;
    private final byte[] frameState;
    private final float[] poses;
    /**
     * 上一帧结束时的姿态快照（布局同 {@link #poses}）。
     *
     * <p>{@code ysm.bone_rot} 这类查询量在采样过程中求值，此时本帧的 {@link #poses} 只填了一部分，
     * 读到哪根骨骼取决于层序；按上一帧的完整姿态回答才是确定的，也与 SPM 的 PhysicsManager 一致。
     */
    private float[] lastFramePoses;
    private final boolean[] hiddenDefault;
    private final boolean[] hiddenCurrent;
    private final int[] propRoot;
    private final Map<Integer, List<Integer>> children;
    private final Map<String, RawYsmModel.RawAnimation> extraClips;
    private final List<ExtraOption> extraOptions;
    private final String defaultExtraKey;
    private final Identifier texture;
    /**
     * 左右手各自的 {@code *Sword} 锚点子树里有没有几何。
     *
     * <p>作者把剑的模型直接烘在 {@code RightSwordLocator} 下面时，手上再画一把原版剑就是双剑
     * （SPM {@code SwordReplacementHelper:20-77} 同样按这个判据抑制原版物品）。
     */
    private final boolean[] swordGeometry = new boolean[2];

    /** 定位组名 → 该组的骨骼下标（构建期算好，渲染期只查表）。 */
    private final Map<String, int[]> locatorGroups;
    private final int rightHandBone;
    private final int leftHandBone;
    private RawYsmModel.RawAnimation lastClip;
    private float lastClipStart;
    private float lastHurtTime;
    /** 三条手部层的重播起点：YSM 靠显式信号从头播，而不是靠「片段名变了」。 */
    private float handStartMain = Float.NaN;
    private float handStartOff = Float.NaN;
    private float swingStart = Float.NaN;
    private float useStart = Float.NaN;
    /** 上一次看到的手持物 / 挥动 tick / 使用 tick，用来认出「换物品」「新一次挥动」「新一次使用」。 */
    private ItemStack lastHoldMain = ItemStack.EMPTY;
    private ItemStack lastHoldOff = ItemStack.EMPTY;
    /** 基础姿态过渡：上一帧的快照与起始时刻。 */
    private float[] lastBasePoses;
    private float blendStart = Float.NaN;
    /** 过渡时长（秒），对齐 SPM 主控制器的 0.1f（TicksInterpolator 内部乘 20，即 2 tick）。 */
    private static final float BLEND_SECONDS = 0.1F;
    /** 播完后的退场淡出时长（秒）：SPM 的 {@code defaultTransitionTick} 是 3 tick。 */
    private static final float ENDING_SECONDS = 3.0F / 20.0F;
    /** 骨骼失去驱动后回到中性的时长（秒）：SPM 的 {@code AnimationData.resetSpeed} 默认 3 tick。 */
    private static final float DECAY_SECONDS = 3.0F / 20.0F;
    /** 本帧被任何层写过的骨骼；没被写过的走逐骨衰减。 */
    private final boolean[] poseDriven;
    /** 骨骼最后一次被驱动的时刻；{@code NaN} 表示当前没有在衰减。 */
    private final float[] decayStart;
    /** 骨骼最后一次被驱动时的姿态（布局同 {@link #poses}），衰减从这里出发。 */
    private final float[] decayPoses;
    /** 上一帧的时间戳：{@link #markDriven} 记的是「最后一次被驱动」的 tick，衰减进度按它算。 */
    private float lastFrameAge = Float.NaN;
    /**
     * 本帧 {@code blend_weight} 的单条缓存：同一片段在逐骨循环里被问很多次，
     * 而 Molang 求值不便宜。每帧开头清空，跨帧不保留（{@code query.life_time} 会变）。
     */
    private RawYsmModel.RawAnimation weightClip;
    private float weightTime = Float.NaN;
    private float weightValue = 1.0F;
    /** 通道组位掩码：{@link #blendChannels} 用。 */
    private static final byte ROTATION = 1;
    private static final byte POSITION = 2;
    private static final byte SCALE = 4;

    /** 同一控制器状态里多条动画的合成暂存（布局同 {@link #poses}）：旋转/位移加权和，缩放连乘。 */
    private final float[] blendPose;
    /**
     * {@link #blendPose} 里本帧真的被写过的通道组，按位记：{@code 1}=旋转、{@code 2}=位移、{@code 4}=缩放。
     *
     * <p>YSM 的 {@code pollRotationPoint} / {@code pollPositionPoint} / {@code pollScalePoint} 是三个独立
     * 通道，动画没写的那一组返回空、快照保持原值。所以覆盖也只能按组来 —— 整骨覆盖会把
     * {@code pre_parallel} 藏道具用的 {@code scale=0} 一起抹成 1。
     */
    private final byte[] blendChannels;
    /** 控制器状态切换时的过渡暂存：切换那一帧的姿态快照 + 起点 + 时长。 */
    private final Map<ModelController, ControllerTransition> transitions = new HashMap<>();
    /** 旋转过渡用的四元数暂存（SPM 的 {@code nlerpEulerAngles} 同形）。 */
    private final Quaternionf blendQuatA = new Quaternionf();
    private final Quaternionf blendQuatB = new Quaternionf();
    private final Vector3f blendEuler = new Vector3f();

    /** 一个控制器最近一次状态切换的过渡窗口；{@code from == null} 表示当前没有过渡。 */
    private static final class ControllerTransition {
        private float[] from;
        private ModelController.Blend blend = ModelController.Blend.NONE;
        private float start;
        private float lastStateStart = Float.NaN;
    }

    /** {@link #bonePivotAbs} 的累乘暂存，避免每次查询都新建矩阵。 */
    private final Matrix4f pivotAbsScratch = new Matrix4f();

    /** 26.3 上用来识别「新一次挥动」的 {@code SwingDescription} 实例。 */
    private Object lastSwingMarker;
    private float lastSwingTick = -1.0F;
    private float lastUseTick = -1.0F;
    private boolean swinging;
    private boolean using;
    /** 已锁存的挥动/使用片段：起手时解析一次，播完才释放。 */
    private RawYsmModel.RawAnimation swingClip;
    private RawYsmModel.RawAnimation useClip;
    /** 正在做退场过渡的挥动片段与它的起点：播完之后按 {@link #ENDING_SECONDS} 淡回中性。 */
    private RawYsmModel.RawAnimation swingEndClip;
    private float swingEndStart = Float.NaN;
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
    /**
     * 发光骨掩膜：骨名前缀 {@code ysmGlow} 的骨骼（YSM 的 {@code GeoBone:6,50} 判据）。
     *
     * <p>这些骨要单独用 emissive 渲染类型再画一遍，否则眼睛/灯/魔法件在夜里是全黑的。
     * 掩膜在构建期算好，出图时按它分两趟（见 {@link #renderGlow}）。
     */
    private boolean[] glowMask;
    /** 贴图半透明像素的扫描结果；{@code null} 表示还没扫过（首次出图时才扫，避免加载期卡顿）。 */
    private Boolean translucentPixels;
    private List<ModelController> controllers = List.of();
    /**
     * 控制器按 YSM 的注册顺序分桶：键是槽名前缀（{@code pre_main} 这类），值是这一层要采样的控制器。
     *
     * <p>YSM 的 {@code PlayerControllerCollection:33-74} 按固定顺序注册控制器，后注册的覆盖先注册的。
     * 全塞进一个桶压在主层之上，{@code pre_parallel} 就会盖掉主动画的 {@code scale=1}（道具不露），
     * {@code parallel} 又会被 swing/use 盖住。
     */
    private Map<String, List<ModelController>> controllerBuckets = Map.of();
    /** 模型属性里的渲染缩放（YSM 默认 0.7）；作者可以用它整体调大小。 */
    private float widthScale = 1.0F;
    private float heightScale = 1.0F;
    /** 无条件循环的默认层：模型里存在的 pre_parallel0..7。道具的自动隐藏写在这些片段里。 */
    private List<String> preParallelBase = List.of();
    /**
     * 无条件循环的第二层：模型里存在的 parallel0..7。YSM 的 {@code ParallelPredicate} 对
     * {@code pre_parallel*} 与 {@code parallel*} 一视同仁（都无条件循环），差别只在叠加顺序上。
     * 弹簧物理就写在这几个片段的时间轴语句里。
     */
    private List<String> parallelBase = List.of();
    /** 时间轴语句写入的 {@code v.*} 变量表：物理跨帧的状态全存在这里，按模型实例一份。 */
    private final Map<String, Float> variables = new HashMap<>();
    /** 层 + 片段名 → 时间轴执行游标。 */
    private final Map<CursorKey, TimelineCursor> timelines = new HashMap<>();

    /**
     * 层 + 片段名 → 音效游标。键与 {@link #timelines} 一致：音效和动作必须吃同一份片段内时间，
     * 否则循环回卷时两者对不上。模型没有自带音频时为 {@code null}，整条音效路径跳过。
     */
    private ModelSoundCursorTable soundCursors;

    /**
     * 本实例的物理状态表：{@code ysm.second_order} / {@code ysm.first_order} 的模拟器都挂在这里。
     * 主模型与第一人称手臂各一份 —— 弹簧状态属于渲染上下文，共用一个会让两处互相拖动。
     */
    private final ModelPhysics physics = new ModelPhysics();

    /** 轮盘里的一条：{@code key} 是动画名，{@code variant} 标记「服装预设」而不是额外动作。 */
    public record ExtraOption(String key, String label, boolean variant) {}

    private static String clipName(RawYsmModel.RawAnimation clip) {
        return clip == null || clip.name == null ? "none" : clip.name;
    }

    private ModelRuntime(List<RawYsmModel.RawBone> bones, Map<String, Integer> boneIndex,
            Map<String, RawYsmModel.RawAnimation> clips, int[] parents, Matrix4f[] restMatrices,
            Matrix3f[] restNormals, boolean[] hidden, int[] propRoot, Map<Integer, List<Integer>> children,
            Map<String, RawYsmModel.RawAnimation> extraClips, List<ExtraOption> extraOptions, String defaultExtraKey,
            Identifier texture, int rightHandBone, int leftHandBone, Map<String, int[]> locatorGroups,
            Map<String, RawYsmModel.RawAnimation> defaultClips, boolean[] glow) {
        this.bones = bones;
        this.boneIndex = boneIndex;
        this.clips = clips;
        this.defaultClips = defaultClips;
        this.parents = parents;
        this.restMatrices = restMatrices;
        this.restNormals = restNormals;
        this.frameMatrices = new Matrix4f[bones.size()];
        this.frameNormals = new Matrix3f[bones.size()];
        this.frameState = new byte[bones.size()];
        this.poses = new float[bones.size() * 9];
        this.lastFramePoses = new float[bones.size() * 9];
        this.poseDriven = new boolean[bones.size()];
        this.decayStart = new float[bones.size()];
        this.decayPoses = new float[bones.size() * 9];
        this.blendPose = new float[bones.size() * 9];
        this.blendChannels = new byte[bones.size()];
        java.util.Arrays.fill(this.decayStart, Float.NaN);
        // 缩放的「中性」是 1 不是 0：首帧快照若留 0，衰减会从 0 长回 1，模型第一帧是扁的
        for (int i = 0; i < bones.size(); i++) {
            this.decayPoses[i * 9 + 6] = 1.0F;
            this.decayPoses[i * 9 + 7] = 1.0F;
            this.decayPoses[i * 9 + 8] = 1.0F;
            this.lastFramePoses[i * 9 + 6] = 1.0F;
            this.lastFramePoses[i * 9 + 7] = 1.0F;
            this.lastFramePoses[i * 9 + 8] = 1.0F;
        }
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
        this.locatorGroups = locatorGroups;
        this.glowMask = glow;
    }

    public static ModelRuntime build(RawYsmModel model, Identifier texture, boolean hideProps) {
        return build(model, texture, hideProps, DefaultAnimations.clips());
    }

    /**
     * {@code defaultClips} 是缺失片段的兜底表：主模型接默认模型的 main + arm，第一人称手臂传空表
     * —— 默认动画按渲染目标分域，main/arm 那套不属于手臂模型，套上去只会让手臂摆出身体姿势。
     */
    private static ModelRuntime build(RawYsmModel model, Identifier texture, boolean hideProps,
            Map<String, RawYsmModel.RawAnimation> defaultClips) {
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
                if (file == null || !isBodyAnimationFile(file.animType) || file.animations == null) {
                    continue;
                }
                for (Map.Entry<String, RawYsmModel.RawAnimation> entry : file.animations.entrySet()) {
                    String name = entry.getKey();
                    RawYsmModel.RawAnimation animation = entry.getValue();
                    if (animation == null) {
                        continue;
                    }
                    animation.name = name;
                    // 后到的覆盖先到的：YSM 把 main/arm/extra 塞进同一个命名空间，同名时以加载顺序靠后的为准
                    clips.put(name, animation);
                    // 道具启发式只认主动画文件：手部动画（arm）动的是身体骨骼，算进来会把手臂当道具藏掉
                    boolean mainState = file.animType == 1 && MAIN_STATES.contains(name);
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
        // 但「被控制器槽引用」的骨骼不能藏：作者用 pre_parallel 把它缩到 0 当默认隐藏，
        // 再由 parallel_6 这类控制器槽把 scale 写回 1 放出来（丛雨的 cyw 就是剑）。
        // 藏掉之后控制器写多少都画不出来，所以这里先排除掉控制器引用集。
        Set<String> controllerDriven = controllerReferencedBones(model);
        for (int i = 0; i < count; i++) {
            String name = bones.get(i).name;
            if (name != null && zeroScaled.contains(name) && !mainAnimated.contains(name)
                    && !controllerDriven.contains(name) && extent[i] >= PROP_MIN_SIZE) {
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
        int rightHand = findHand(bones, RIGHT_HAND_CHAIN);
        int leftHand = findHand(bones, LEFT_HAND_CHAIN);
        // 发光骨：YSM 按骨名前缀判（SPM 的 GeometryBaker:60 是 name.startsWith("ysmGlow")）
        boolean[] glow = new boolean[count];
        int glowCount = 0;
        for (int i = 0; i < count; i++) {
            String name = bones.get(i).name;
            if (name != null && name.startsWith(GLOW_BONE_PREFIX)) {
                glow[i] = true;
                glowCount++;
            }
        }
        Debug.info("[SFH+] geo bones=" + count + " roots=" + roots + " unresolvedParents=" + unresolved
                + " clips=" + clips.size() + " hidden=" + hiddenCount + " extras=" + extraOptions.size()
                + " hands=" + rightHand + "/" + leftHand + " glow=" + glowCount);
        ModelRuntime runtime = new ModelRuntime(bones, byName, clips, parents, matrices, normals, hidden, propRoot,
                children, extraClips, extraOptions, defaultExtraKey, texture, rightHand, leftHand,
                collectLocatorGroups(bones), defaultClips, glow);
        runtime.scanSwordGeometry(bones, parents);
        runtime.soundCursors = ModelSoundCursorTable.build(ModelSound.build(model));
        runtime.controllers = parseControllers(model);
        runtime.controllerBuckets = bucketControllers(runtime.controllers);
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
        // 控制器槽名与片段名差一个下划线（player.parallel_6 驱动 parallel6），先换算成片段名
        Set<String> driven = new HashSet<>();
        for (ModelController controller : runtime.controllers) {
            driven.add(clipNameOfSlot(controller.slot()));
        }
        List<String> base = new ArrayList<>();
        List<String> parallel = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            // 有模型控制器的槽让位给状态机：YSM 里一个槽只有一个控制器，模型的定义会顶掉
            // 「无条件循环 parallelN」这个默认实现，两边都跑就是同一段动画叠两遍。
            String baseName = "pre_parallel" + index;
            if (clips.containsKey(baseName) && !driven.contains(baseName)) {
                base.add(baseName);
            }
            String parallelName = "parallel" + index;
            if (clips.containsKey(parallelName) && !driven.contains(parallelName)) {
                parallel.add(parallelName);
            }
        }
        // 默认的 player.pre_parallel_0 控制器最先播的是模型的 hair_physics
        // （YSM 的 builtin/misc/4_default_controllers/controller/main_controllers.json），
        // 名字不在 pre_parallelN 这一族里，不单独挑出来就整条不播。
        String hair = findClipIgnoreCase(clips, "hair_physics");
        if (hair != null && !driven.contains("pre_parallel0")) {
            base.add(0, hair);
        }
        runtime.preParallelBase = base;
        runtime.parallelBase = parallel;
        if (model.properties != null) {
            runtime.widthScale = model.properties.widthScale > 0.0F ? model.properties.widthScale : 1.0F;
            runtime.heightScale = model.properties.heightScale > 0.0F ? model.properties.heightScale : 1.0F;
        }
        int timelineCount = 0;
        for (RawYsmModel.RawAnimation clip : clips.values()) {
            if (clip != null && clip.timelineEvents != null) {
                timelineCount += clip.timelineEvents.size();
            }
        }
        Debug.info("[SFH+] parallelBase=" + runtime.preParallelBaseN() + " parallelOnly="
                + runtime.parallelOnlyN() + " timelineEvents=" + timelineCount);
        Debug.info("[SFH+] controllers=" + runtime.controllers.size() + "[" + slots.toString().trim()
                + "] parallelClips=" + parallelCount + "[" + parallelClips.toString().trim() + "]");
        int timelineClips = 0;
        for (RawYsmModel.RawAnimation animation : clips.values()) {
            if (animation.timelineEvents != null && !animation.timelineEvents.isEmpty()) {
                timelineClips++;
            }
        }
        Debug.info("[SFH+] layers pre=" + base.size() + " parallel=" + parallel.size()
                + " timelineClips=" + timelineClips);
        return runtime;
    }

    /**
     * 控制器按槽名前缀分桶，键是 YSM 的注册顺序里那一层的名字。
     *
     * <p>{@code PlayerControllerCollection:33-74} 的注册顺序就是合成顺序：{@code pre_parallel} 最底，
     * 然后 {@code pre_main} / {@code main} / {@code post_main}、{@code pre_hold} / {@code hold_*} /
     * {@code post_hold}、{@code pre_swing} / {@code swing} / {@code post_swing}、
     * {@code pre_use} / {@code use} / {@code post_use}，{@code parallel} 在 {@code use} 之上。
     * 槽名是 {@code player.<槽>}，多实例槽再带 {@code _<后缀>}（{@code MultiControllerDiscovery:22}），
     * 所以按「最长匹配的已知槽名」归类，{@code parallel_6} 归 {@code parallel} 而不是 {@code pre_parallel}。
     */
    private static Map<String, List<ModelController>> bucketControllers(List<ModelController> controllers) {
        if (controllers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ModelController>> buckets = new LinkedHashMap<>();
        for (ModelController controller : controllers) {
            String slot = controller.slot();
            String bucket = null;
            for (String known : CONTROLLER_LAYERS) {
                boolean matched = slot.equals(known)
                        || (MULTI_SLOTS.contains(known) && slot.startsWith(known + "_"));
                if (matched && (bucket == null || known.length() > bucket.length())) {
                    bucket = known;
                }
            }
            // 槽名不在注册表里的（模型自定义槽）归到空串桶，保持它们原来的位置
            buckets.computeIfAbsent(bucket == null ? "" : bucket, key -> new ArrayList<>()).add(controller);
        }
        return buckets;
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
                // 主体态与手部三层由代码侧判定（ItemClassifier 那套优先级）；模型把默认状态机
                // 整份抄进 controller.json 的情况很常见，再求值一次等于同一段动画叠两遍。
                if (machine != null && !machine.slot().isEmpty() && !ModelController.isCodeDriven(machine.slot())) {
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
        // 音效表挂在模型根上而不是 mainEntity 上：不搬过来，第一人称手臂那层的音效游标恒为空
        proxy.soundFiles = model.soundFiles;
        proxy.mainEntity = main;
        ModelRuntime runtime = build(proxy, texture, false, Map.of());
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

    /**
     * 参与「身体动画」的动画文件类型。YSM 把 main(1) / arm(2) / extra(3) / 控制器等放进同一个命名空间，
     * 手部条件动画（{@code hold_*} / {@code swing:*} / {@code use_*:*}）全在 arm 文件里，
     * 不放开这一层就永远选不到它们。排除 5（箭矢）与 11（第一人称手臂，走独立 runtime）。
     */
    private static boolean isBodyAnimationFile(int animType) {
        return animType == 1 || animType == 2 || animType == 3 || animType == 4
                || animType == 6 || animType == 7 || animType == 8 || animType == 9
                || animType == 10 || animType == 12 || animType == 13;
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

    /**
     * 被模型自带控制器槽引用到的骨骼名（小写）。
     *
     * <p>道具启发式靠「pre_parallel 里 scale=0」判断默认隐藏，但作者也会用同一手法做
     * 「默认藏、控制器放」：丛雨的 {@code cyw}（剑）在 {@code pre_parallel0} 里 scale=0，
     * 由 {@code player.parallel_6} 的 {@code 手持剑} 状态写回 1。这类骨骼一旦被藏掉，
     * 控制器写多少都画不出来，所以构建期先收集控制器引用集，把它们排除出隐藏名单。
     *
     * <p>只收控制器状态里真正会播的动画名（{@code animations} 的 key），不看转移条件 ——
     * 条件里出现的是状态名，不是骨骼。
     */
    private static Set<String> controllerReferencedBones(RawYsmModel model) {
        if (model.mainEntity == null || model.mainEntity.animationControllerFiles == null) {
            return Set.of();
        }
        Set<String> animationNames = new HashSet<>();
        for (RawYsmModel.RawAnimationControllerFile file : model.mainEntity.animationControllerFiles) {
            if (file == null || file.controllers == null) {
                continue;
            }
            for (RawYsmModel.RawAnimationController controller : file.controllers.values()) {
                if (controller == null || controller.states == null) {
                    continue;
                }
                for (RawYsmModel.RawControllerState state : controller.states) {
                    if (state == null || state.animations == null) {
                        continue;
                    }
                    for (Map.Entry<String, String> entry : state.animations) {
                        if (entry.getKey() != null && !entry.getKey().isEmpty()) {
                            animationNames.add(entry.getKey());
                        }
                    }
                }
            }
        }
        if (animationNames.isEmpty()) {
            return Set.of();
        }
        Set<String> bones = new HashSet<>();
        if (model.mainEntity.animationFiles == null) {
            return Set.of();
        }
        for (RawYsmModel.RawAnimationFile file : model.mainEntity.animationFiles.values()) {
            if (file == null || file.animations == null) {
                continue;
            }
            for (Map.Entry<String, RawYsmModel.RawAnimation> entry : file.animations.entrySet()) {
                if (!animationNames.contains(entry.getKey())) {
                    continue;
                }
                RawYsmModel.RawAnimation animation = entry.getValue();
                if (animation == null || animation.boneAnimations == null) {
                    continue;
                }
                for (RawYsmModel.RawBoneAnimation boneAnimation : animation.boneAnimations) {
                    if (boneAnimation.boneName != null) {
                        bones.add(boneAnimation.boneName.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return bones;
    }

    /**
     * 按 YSM 的定位组规则收集骨骼下标：去掉结尾数字后与组名全等（{@code GeoLocatorType#getByBoneName}）。
     *
     * <p>一组可以有多根骨 —— {@code GeoModelState#visitLocatorGroup} 对组内每根骨各回调一次，
     * 所以 {@code Head1}/{@code Head2} 会各挂一份头顶物。没有该组的模型不进表。
     */
    private static Map<String, int[]> collectLocatorGroups(List<RawYsmModel.RawBone> bones) {
        Map<String, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            String name = bones.get(i).name;
            if (name == null || name.isEmpty()) {
                continue;
            }
            int end = name.length();
            while (end > 0 && Character.isDigit(name.charAt(end - 1))) {
                end--;
            }
            String stem = name.substring(0, end);
            for (String locator : LOCATOR_NAMES) {
                if (locator.equalsIgnoreCase(stem)) {
                    groups.computeIfAbsent(locator, key -> new ArrayList<>()).add(i);
                    break;
                }
            }
        }
        if (groups.isEmpty()) {
            return Map.of();
        }
        Map<String, int[]> result = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
            int[] indices = new int[entry.getValue().size()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = entry.getValue().get(i);
            }
            result.put(entry.getKey(), indices);
        }
        return result;
    }

    /**
     * 扫描 {@code *Sword} 锚点子树里有没有几何，结果写进 {@link #swordGeometry}。
     *
     * <p>判据照 SPM {@code SwordReplacementHelper:36-77}：骨名去掉非字母数字后等于
     * {@code leftsword}/{@code rightsword}（或带纯数字后缀），且该骨或其后代有面片。
     * 有几何就说明作者已经画了剑，手上不该再叠一把原版物品。
     */
    private void scanSwordGeometry(List<RawYsmModel.RawBone> bones, int[] parents) {
        for (int i = 0; i < bones.size(); i++) {
            if (faceCount(bones.get(i)) <= 0) {
                continue;
            }
            int current = i;
            for (int depth = 0; current >= 0 && depth < bones.size(); depth++) {
                String name = normalizeBoneName(bones.get(current).name);
                if (isSwordAnchor(name, "leftsword")) {
                    swordGeometry[0] = true;
                    break;
                }
                if (isSwordAnchor(name, "rightsword")) {
                    swordGeometry[1] = true;
                    break;
                }
                current = parents[current];
            }
        }
    }

    /** 骨名归一化：只留字母数字并转小写（SPM {@code SwordReplacementHelper:90-102}）。 */
    private static String normalizeBoneName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char character = Character.toLowerCase(name.charAt(i));
            if (Character.isLetterOrDigit(character)) {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    /** 归一化后的名字是不是 {@code anchor} 本身或带纯数字后缀（{@code rightsword} / {@code rightsword2}）。 */
    private static boolean isSwordAnchor(String normalized, String anchor) {
        if (normalized.equals(anchor)) {
            return true;
        }
        if (!normalized.startsWith(anchor)) {
            return false;
        }
        String suffix = normalized.substring(anchor.length());
        if (suffix.isEmpty()) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按候选链顺序找第一根存在的骨骼，全部未命中返回 -1。
     *
     * <p>候选名一律小写，与骨骼名 {@code toLowerCase} 后全等比较；-1 是合法结果，
     * 调用方据此不挂手持物，不抛异常。
     */
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
     * <p>叠加语义——只有动画写到的通道才参与叠加，没写到的保持走路/待机；
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

    /**
     * 模型卸载：停掉还在解码的音效播放并释放 ogg 字节引用。
     *
     * <p>由持有本实例的一方在换模型时调用 —— 本类自己不知道「什么时候不再渲染」。
     * 不能挂在 {@link #clearAll()} 上：那是轮盘取消选择，模型还在用，释放后音效就再也响不了。
     */
    public void release() {
        if (soundCursors != null) {
            soundCursors.release();
            soundCursors = null;
        }
    }

    private ExtraOption findOption(String key) {
        for (ExtraOption option : extraOptions) {
            if (option.key().equals(key)) {
                return option;
            }
        }
        return null;
    }

    /**
     * 重算本帧的隐藏表：默认隐藏的道具，只要「当前有东西在驱动它」就放出来。
     *
     * <p>三种驱动源：轮盘的服装预设、轮盘的额外动作、以及**正在播的控制器状态**。
     * 第三种是 YSM 的常规做法 —— 作者在 {@code pre_parallel} 里把道具缩到 0 当默认隐藏，
     * 再由 {@code parallel_6} 这类控制器槽把 scale 写回 1 放出来（丛雨的剑就是）。
     * 只认轮盘的话，控制器放出来的道具仍然是全透明的。
     */
    private void recomputeHidden() {
        System.arraycopy(hiddenDefault, 0, hiddenCurrent, 0, hiddenCurrent.length);
        Set<Integer> roots = new HashSet<>();
        collectPropRoots(activeVariant, roots);
        collectPropRoots(activeExtra, roots);
        for (RawYsmModel.RawAnimation playing : controllerPlayingClips) {
            collectPropRoots(playing, roots);
        }
        for (int root : roots) {
            unhideSubtree(root);
        }
    }

    /** 本帧控制器状态机选中的片段（{@link #sampleControllerLayers} 每帧重填）。 */
    private final List<RawYsmModel.RawAnimation> controllerPlayingClips = new ArrayList<>();

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

    /**
     * 片段内时间：按 {@code loopMode} 决定循环还是停在末帧。
     *
     * <p>与 SPM {@code AnimationMapper:48-50} 一致：0=PLAY_ONCE、1=LOOP、3=HOLD_ON_LAST_FRAME。
     * 只有 LOOP 取模，其余一律 {@code min(elapsed, length)} —— 取模会让单次动作的收尾帧永远播不到，
     * 而物理片段若被当成单次，弹簧每轮只积分一次就冻住。
     */
    private static float layerTime(RawYsmModel.RawAnimation clip, float ageSeconds) {
        float length = lengthOf(clip);
        if (length <= 0.0F) {
            return 0.0F;
        }
        float elapsed = Math.max(0.0F, ageSeconds);
        return clip.loopMode == 1 ? elapsed % length : Math.min(elapsed, length);
    }

    private float overlayTime(RawYsmModel.RawAnimation clip, float ageSeconds) {
        float length = lengthOf(clip);
        if (length <= 0.0F) {
            return 0.0F;
        }
        float elapsed = Math.max(0.0F, ageSeconds - activeExtraStart);
        return elapsed % length;
    }

    /**
     * 一层「自动循环」片段：先推进它自己的 timeline 语句（弹簧积分写 {@code v.*}），再把骨骼通道叠上去。
     *
     * <p>顺序不能反过来 —— 同一片段里先写变量、后读变量，与 YSM 的
     * {@code AnimationControllerInstance#process}（先 {@code executeTimelineEvents} 再
     * {@code processRunningAnimation}）一致。
     *
     * <p>{@code override} 是这一层并进当前姿态的方式：{@code true} 时写到的通道整体取代原值，
     * {@code false} 时加到原值上。YSM 里只有高并行控制器是 additive —— {@code AnimationProcessor}
     * 对 {@code blendRotation} 的控制器把旋转 {@code add} 进快照，其余走 {@code AnimationVec3#applyRotation}
     * 的 {@code dst.set(this)}（无退场过渡时）。{@code PlayerControllerCollection} 装配 pre_parallel 时
     * 没传 {@code blendRotation}，只有 parallel 传了，所以前者覆盖、后者相加。
     * 相加会让同通道被多个 pre_parallel 片段写入时成倍放大。
     */
    private void sampleLayers(List<String> names, float ageSeconds, boolean override) {
        for (String name : names) {
            RawYsmModel.RawAnimation layer = clip(name);
            if (layer == null) {
                continue;
            }
            float time = layerTime(layer, ageSeconds);
            runTimeline(layer, time, ageSeconds, name);
            advanceSound(name, layer, time);
            for (int i = 0; i < bones.size(); i++) {
                if (override) {
                    sampleOverride(layer, i, time, ageSeconds);
                } else {
                    sampleOverlay(layer, i, time, ageSeconds);
                }
            }
        }
    }

    /**
     * 采样一个控制器桶：状态机选出本帧该播的动画，按覆盖层叠上去。
     *
     * <p>桶的键是 YSM 注册顺序里的槽名（见 {@link #CONTROLLER_LAYERS}），空串是模型自定义槽。
     * 同一状态里的动画共用一个起点，各自按自己的 {@code loopMode} 取时间 —— 单次动作播完停在最后一帧，
     * 取模会让收尾帧永远播不到（连击链就是靠「播完 → all_animations_finished」往下走的）。
     *
     * <p>同一状态里的多条动画先按 {@code blend_weight} 合成再一次性叠上去：SPM 的
     * {@code BoneBlendState} 对同一根骨骼的多个来源做 {@code fma(blendWeight)}（旋转/位移）与
     * {@code result.mul}（缩放），逐条覆盖会让先写的动画完全消失 —— 默认控制器的
     * {@code pre_parallel_0/default} 一次就挂 8 条片段。
     */
    private void sampleControllerLayers(String bucket, float ageSeconds) {
        List<ModelController> layer = controllerBuckets.get(bucket);
        if (layer == null || layer.isEmpty()) {
            return;
        }
        for (ModelController controller : layer) {
            List<String> overlayNames = controller.tick(ageSeconds, this::lengthOfClip);
            if (!overlayNames.isEmpty()) {
                for (String overlayName : overlayNames) {
                    RawYsmModel.RawAnimation playing = clip(overlayName);
                    if (playing != null) {
                        controllerPlayingClips.add(playing);
                    }
                }
                // 状态切换那一帧把切换前的姿态存下来，之后按目标状态的 blend_transition 淡过去。
                // 放在这里而不是 tick 之后：空状态不产生姿态，没有「旧姿态」可混。
                ControllerTransition transition = trackTransition(controller, ageSeconds);
                float elapsed = Math.max(0.0F, ageSeconds - controller.stateStart());
                java.util.Arrays.fill(blendChannels, (byte) 0);
                boolean blended = false;
                for (String overlayName : overlayNames) {
                    RawYsmModel.RawAnimation overlayClip = clip(overlayName);
                    if (overlayClip == null || overlayClip.boneAnimations == null) {
                        continue;
                    }
                    float overlayLength = lengthOf(overlayClip);
                    float overlayTime = overlayLength <= 0.0F ? 0.0F
                            : overlayClip.loopMode == 1 ? elapsed % overlayLength : Math.min(elapsed, overlayLength);
                    runTimeline(overlayClip, overlayTime, ageSeconds, controller.slot());
                    advanceSound(controller.slot(), overlayClip, overlayTime);
                    // 单条动画也要过 blend_weight：SPM 的 processRunningAnimation 对每条队列都设权重，
                    // 酒狐的 pre_parallel2 就是靠单条片段的 blend_weight 做尾巴摆动的
                    blended = true;
                    accumulateBlend(overlayClip, overlayTime, ageSeconds);
                }
                if (blended) {
                    applyBlend(transition, ageSeconds);
                }
            }
            controller.finishFrame();
        }
    }

    /**
     * 把一条动画按 {@code blend_weight} 累进 {@link #blendPose}：旋转与位移加权求和，缩放连乘。
     *
     * <p>与 SPM {@code BoneBlendState:565-573}（{@code fma(blendWeight, lerpPoint)}）和
     * {@code :694-711}（{@code result.mul}）同形。缩放用连乘而不是加权和：缩放的中性值是 1，
     * 加权和会让两条各写 1.0 的动画合成 2.0，而作者的本意是「两条都生效」。
     */
    private void accumulateBlend(RawYsmModel.RawAnimation clip, float time, float ageSeconds) {
        float weight = blendWeightOf(clip, time, ageSeconds);
        if (weight <= 0.0F) {
            return;
        }
        for (int i = 0; i < bones.size(); i++) {
            String boneName = bones.get(i).name;
            if (boneName == null) {
                continue;
            }
            for (RawYsmModel.RawBoneAnimation animation : clip.boneAnimations) {
                if (!boneName.equals(animation.boneName)) {
                    continue;
                }
                int offset = i * 9;
                float[] rotation = sampleChannel(animation.rotation, time, ageSeconds);
                if (rotation != null) {
                    if ((blendChannels[i] & ROTATION) == 0) {
                        blendChannels[i] |= ROTATION;
                        blendPose[offset] = 0.0F;
                        blendPose[offset + 1] = 0.0F;
                        blendPose[offset + 2] = 0.0F;
                    }
                    blendPose[offset] += weight * (float) -Math.toRadians(finite(rotation[0], 0.0F));
                    blendPose[offset + 1] += weight * (float) -Math.toRadians(finite(rotation[1], 0.0F));
                    blendPose[offset + 2] += weight * (float) Math.toRadians(finite(rotation[2], 0.0F));
                }
                float[] position = sampleChannel(animation.position, time, ageSeconds);
                if (position != null) {
                    if ((blendChannels[i] & POSITION) == 0) {
                        blendChannels[i] |= POSITION;
                        blendPose[offset + 3] = 0.0F;
                        blendPose[offset + 4] = 0.0F;
                        blendPose[offset + 5] = 0.0F;
                    }
                    blendPose[offset + 3] += weight * finite(position[0], 0.0F);
                    blendPose[offset + 4] += weight * finite(position[1], 0.0F);
                    blendPose[offset + 5] += weight * finite(position[2], 0.0F);
                }
                float[] scale = sampleChannel(animation.scale, time, ageSeconds);
                if (scale != null) {
                    if ((blendChannels[i] & SCALE) == 0) {
                        blendChannels[i] |= SCALE;
                        blendPose[offset + 6] = 1.0F;
                        blendPose[offset + 7] = 1.0F;
                        blendPose[offset + 8] = 1.0F;
                    }
                    blendPose[offset + 6] *= finite(scale[0], 1.0F);
                    blendPose[offset + 7] *= finite(scale[1], 1.0F);
                    blendPose[offset + 8] *= finite(scale[2], 1.0F);
                }
                break;
            }
        }
    }

    /**
     * 把 {@link #blendPose} 里被写过的通道组**覆盖**到当前姿态上。
     *
     * <p>模型自带的控制器在 YSM 里是 {@code HybridAnimationController}，一旦
     * {@code definitions.apply(name)} 命中就切到 {@code BedrockAnimationController}，
     * 而它的 {@code blendRotation()} 是 {@code IAnimationController} 的默认实现 {@code false} ——
     * 于是 {@code AnimationProcessor:121-128} 走 {@code AnimationVec3#applyRotation} 的
     * {@code dst.set(this)}，整体取代。位移与缩放同理（{@code AnimationVec3#apply}）。
     *
     * <p>这一点对丛雨是决定性的：它的 {@code parallel_6/手持剑} 把
     * {@code RightArm/RightForeArm/RightHand2} 写成 {@code [0,0,0]}，语义是「这三根骨回到静止姿态」。
     * 按相加处理等于什么都没做，待机动画的手臂角度会一直留在身上。
     *
     * <p>内置的 {@code parallelN} 默认片段不走这里：它们是
     * {@code PlayerControllerCollection:73} 那个带 {@code blendRotation=true} 的控制器，
     * 走 {@link #sampleLayers} 的 {@code override=false}（相加）。
     */
    private void applyBlend(ControllerTransition transition, float ageSeconds) {
        float weight = 1.0F;
        if (transition != null) {
            weight = transition.blend.weight(Math.max(0.0F, ageSeconds - transition.start));
        }
        for (int i = 0; i < bones.size(); i++) {
            byte channels = blendChannels[i];
            if (channels == 0) {
                continue;
            }
            int offset = i * 9;
            if ((channels & ROTATION) != 0) {
                if (transition == null) {
                    poses[offset] = blendPose[offset];
                    poses[offset + 1] = blendPose[offset + 1];
                    poses[offset + 2] = blendPose[offset + 2];
                } else {
                    blendRotation(weight, transition.from, blendPose, offset, bones.get(i).rotation);
                }
            }
            if ((channels & POSITION) != 0) {
                if (transition == null) {
                    poses[offset + 3] = blendPose[offset + 3];
                    poses[offset + 4] = blendPose[offset + 4];
                    poses[offset + 5] = blendPose[offset + 5];
                } else {
                    poses[offset + 3] = lerp(weight, transition.from[offset + 3], blendPose[offset + 3]);
                    poses[offset + 4] = lerp(weight, transition.from[offset + 4], blendPose[offset + 4]);
                    poses[offset + 5] = lerp(weight, transition.from[offset + 5], blendPose[offset + 5]);
                }
            }
            if ((channels & SCALE) != 0) {
                if (transition == null) {
                    poses[offset + 6] = blendPose[offset + 6];
                    poses[offset + 7] = blendPose[offset + 7];
                    poses[offset + 8] = blendPose[offset + 8];
                } else {
                    poses[offset + 6] = lerp(weight, transition.from[offset + 6], blendPose[offset + 6]);
                    poses[offset + 7] = lerp(weight, transition.from[offset + 7], blendPose[offset + 7]);
                    poses[offset + 8] = lerp(weight, transition.from[offset + 8], blendPose[offset + 8]);
                }
            }
            markDriven(i);
        }
    }

    /**
     * 记下控制器最近一次状态切换的过渡窗口。
     *
     * <p>过渡挂在**目标状态**上（SPM {@code AnimationControllerRuntime:326} 用
     * {@code nextState.getBlendTransition()} 覆盖槽位插值器），所以起点姿态取切换那一帧、
     * 本控制器还没叠上去之前的 {@link #poses} —— 那正是「旧姿态」。
     *
     * <p>首帧进入不算切换（没有「从哪来」），{@code ModelController#lastBlend} 那时也返回
     * {@link ModelController.Blend#NONE}；未声明过渡或时长为 0 时同样硬切。
     */
    private ControllerTransition trackTransition(ModelController controller, float ageSeconds) {
        ControllerTransition transition = transitions.get(controller);
        if (transition == null) {
            transition = new ControllerTransition();
            transitions.put(controller, transition);
        }
        float stateStart = controller.stateStart();
        if (transition.lastStateStart != stateStart) {
            boolean first = !Float.isFinite(transition.lastStateStart);
            transition.lastStateStart = stateStart;
            ModelController.Blend blend = controller.lastBlend();
            if (first || !blend.declared() || blend.seconds() <= 0.0F) {
                transition.from = null;
            } else {
                if (transition.from == null || transition.from.length != poses.length) {
                    transition.from = new float[poses.length];
                }
                System.arraycopy(poses, 0, transition.from, 0, poses.length);
                transition.blend = blend;
                transition.start = ageSeconds;
            }
        }
        if (transition.from == null) {
            return null;
        }
        if (ageSeconds - transition.start >= transition.blend.seconds()) {
            transition.from = null;
            return null;
        }
        return transition;
    }

    /**
     * 旋转按四元数最短弧插值：欧拉角直接线性插值会在两轴同时转时走出一条外扩的弧。
     *
     * <p>与 SPM {@code MathUtil.nlerpEulerAngles} 同形 —— 先把 rest 旋转加回去换成四元数，
     * 插完再减掉，因为 {@link #poses} 存的是动画空间的偏移量。
     */
    private void blendRotation(float weight, float[] from, float[] to, int offset, float[] initial) {
        if (weight >= 1.0F) {
            poses[offset] = to[offset];
            poses[offset + 1] = to[offset + 1];
            poses[offset + 2] = to[offset + 2];
            return;
        }
        if (weight <= 0.0F) {
            poses[offset] = from[offset];
            poses[offset + 1] = from[offset + 1];
            poses[offset + 2] = from[offset + 2];
            return;
        }
        blendQuatA.identity().rotateZYX(from[offset + 2] + initial[2], from[offset + 1] + initial[1],
                from[offset] + initial[0]);
        blendQuatB.identity().rotateZYX(to[offset + 2] + initial[2], to[offset + 1] + initial[1],
                to[offset] + initial[0]);
        blendQuatA.nlerp(blendQuatB, weight);
        eulerZYX(blendQuatA, blendEuler);
        poses[offset] = blendEuler.x - initial[0];
        poses[offset + 1] = blendEuler.y - initial[1];
        poses[offset + 2] = blendEuler.z - initial[2];
    }

    /** 四元数 → ZYX 欧拉角（joml 自带的同名方法有 bug，SPM 也是自己写的）。 */
    private static void eulerZYX(Quaternionf q, Vector3f out) {
        out.x = (float) Math.atan2(q.y * q.z + q.w * q.x, 0.5F - q.x * q.x - q.y * q.y);
        out.y = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, -2.0F * (q.x * q.z - q.w * q.y))));
        out.z = (float) Math.atan2(q.x * q.y + q.w * q.z, 0.5F - q.y * q.y - q.z * q.z);
    }

    private static float lerp(float weight, float from, float to) {
        return from + (to - from) * weight;
    }

    /**
     * 片段的 {@code blend_weight}：缺省 1.0，数字直接用，字符串按 Molang 求值（SPM
     * {@code AnimationControllerInstance:284/307/329} 三处都是 {@code evalAsFloat}）。
     *
     * <p>结果按 {@code BoneAnimationQueue#setBlendWeight} 夹到 {@code >= 0}：负权重在 SPM 里被吞成 0。
     */
    private float blendWeightOf(RawYsmModel.RawAnimation clip, float time, float ageSeconds) {
        Object raw = clip.blendWeight;
        if (raw == null) {
            return 1.0F;
        }
        if (clip == weightClip && time == weightTime) {
            return weightValue;
        }
        float value;
        if (raw instanceof Number number) {
            value = number.floatValue();
        } else {
            value = MolangExpression.channel(String.valueOf(raw), time, ageSeconds);
        }
        value = Float.isFinite(value) ? Math.max(0.0F, value) : 1.0F;
        weightClip = clip;
        weightTime = time;
        weightValue = value;
        return value;
    }

    /**
     * 执行片段里「片段内时间已经越过」的 timeline 语句（YSM 的 {@code InstructionKeyFrameExecutor}）。
     *
     * <p>语句是逐帧增量执行的：只跑游标之后、时间戳 {@code <= time} 的那几条；片段循环回卷时先把上一轮
     * 剩下的补完再把游标归零。模型的二阶弹簧正是靠这个次序「回写(commit) → 再积分」各跑一次 ——
     * 顺序颠倒会把积分结果当场覆盖掉，看上去就是「物理不动」。
     */
    private void runTimeline(RawYsmModel.RawAnimation clip, float time, float lifeTime, String layer) {
        List<RawYsmModel.RawTimelineEvent> events = clip.timelineEvents;
        if (events == null || events.isEmpty()) {
            return;
        }
        TimelineCursor cursor = timelines.computeIfAbsent(new CursorKey(layer, clipName(clip)),
                key -> new TimelineCursor());
        if (Float.isNaN(cursor.time)) {
            // 首帧没有上一轮可补，直接从片段开头算
            cursor.next = 0;
        } else if (time < cursor.time) {
            for (int i = cursor.next; i < events.size(); i++) {
                runStatements(events.get(i), time, lifeTime);
            }
            cursor.next = 0;
        }
        while (cursor.next < events.size() && events.get(cursor.next).timestamp <= time) {
            runStatements(events.get(cursor.next), time, lifeTime);
            cursor.next++;
        }
        cursor.time = time;
    }

    /**
     * 把音效游标推进到与同一次 {@link #runTimeline} 相同的片段内时间。
     *
     * <p>两者必须同源：音效关键帧的时间戳和 timeline 语句一样是片段内秒数，用别的时间推进会让
     * 音效与动作错位；循环回卷（{@code time} 变小）由 {@link ModelSoundCursorTable} 自己识别。
     * 模型没有自带音频时 {@code soundCursors} 为 {@code null}，整条路径跳过。
     */
    private void advanceSound(String layer, RawYsmModel.RawAnimation clip, float time) {
        if (soundCursors != null) {
            soundCursors.advance(layer, clip, time);
        }
    }

    private static void runStatements(RawYsmModel.RawTimelineEvent event, float time, float lifeTime) {
        if (event == null || event.events == null) {
            return;
        }
        for (String statement : event.events) {
            for (MolangExpression.Statement compiled : MolangExpression.statements(statement)) {
                compiled.run(time, lifeTime);
            }
        }
    }

    /** 一条片段的 timeline 执行进度：下一个待执行事件、上一帧的片段内时间（用来认出循环回卷）。 */
    private static final class TimelineCursor {
        private int next;
        private float time = Float.NaN;
    }

    /** 时间轴游标的键：层标识 + 片段名。 */
    private record CursorKey(String layer, String clip) {}

    /**
     * 覆盖层：写到的通道**赋值**而不是相加，没写到的骨骼完全不动。
     *
     * <p>YSM 里跨控制器一律是 {@code set} 覆盖：{@code AnimationProcessor#updateModel} 对每个控制器的
     * {@code pollRotationPoint} 结果调 {@code AnimationVec3#applyRotation}，运行中的动画
     * {@code endingTransitionPercentProgress == 0} 走 {@code dst.set(this)}；只有单个控制器内部的多条动画
     * 才按 {@code blend_weight} 做 {@code fma}。{@code PlayerControllerCollection} 里只有 {@code parallel}
     * 传了 {@code blendRotation}，{@code pre_parallel}、{@code main} 与手部三层都是覆盖。
     * 用相加会让同一通道被两层各写一次时叠成两倍变形。
     */
    private void sampleOverride(RawYsmModel.RawAnimation clip, int boneIndex, float time, float lifeTime) {
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
            markDriven(boneIndex);
            return;
        }
    }

    /**
     * 手部条件动画层：按 YSM 的控制器顺序 {@code hold_offhand → hold_mainhand → swing → use} 依次覆盖。
     *
     * <p>片段名由 {@link ItemClassifier#candidates} 给出的后缀候选拼成（{@code hold_mainhand:sword}、
     * {@code swing$minecraft:mace} 这类），取第一个存在的。挥动与使用是单次播放，持有是循环。
     */
    /**
     * 基础姿态的短时交叉淡入：切片段时把上一帧的姿态按比例混进来，避免「跑动忽然站定」那种硬切。
     *
     * <p>只作用于基础层（pre_parallel → 主动画 → 轮盘/额外/控制器），手部动作、parallel 与头部朝向
     * 都在它之后应用，不受影响 —— 那些层要么是瞬时动作，要么本身就带自己的时间轴。
     */
    private void blendBasePose(float ageSeconds) {
        int needed = poses.length;
        if (lastBasePoses == null || lastBasePoses.length != needed) {
            lastBasePoses = new float[needed];
            System.arraycopy(poses, 0, lastBasePoses, 0, needed);
            blendStart = Float.NaN;
            return;
        }
        if (Float.isFinite(blendStart)) {
            float progress = (ageSeconds - blendStart) / BLEND_SECONDS;
            if (progress >= 1.0F) {
                blendStart = Float.NaN;
            } else {
                // 平滑一下：线性混合在中段会有明显的「同时存在两个姿态」感
                float t = Math.max(0.0F, progress);
                t = t * t * (3.0F - 2.0F * t);
                for (int i = 0; i < needed; i++) {
                    poses[i] = lastBasePoses[i] + (poses[i] - lastBasePoses[i]) * t;
                }
            }
        }
        System.arraycopy(poses, 0, lastBasePoses, 0, needed);
    }

    /**
     * 持有层：换物品时从头播一次，其余时间保持。
     *
     * <p>它排在主动画**之后**、{@code pre_swing} 之前，与 YSM 的控制器注册顺序一致
     * （{@code PlayerControllerCollection:44-52}：main → pre_hold → hold_offhand → hold_mainhand →
     * post_hold → pre_swing）。持有姿势必须压过待机/走路的手臂通道，否则作者写的持物动作会被
     * 主体态的呼吸摆动盖掉；反过来挥动/使用要压过持有，所以它们排在更后面。
     */
    private void sampleHoldLayers(LocalPlayer player, float ageSeconds) {
        if (player == null) {
            return;
        }
        // 换物品 → 持有动画从头播一次（YSM 的 MainhandPredicate/OffhandPredicate#indicateReload）
        ItemStack main = player.getMainHandItem();
        if (!ItemStack.matches(main, lastHoldMain)) {
            lastHoldMain = main.copy();
            handStartMain = ageSeconds;
        }
        ItemStack off = player.getOffhandItem();
        if (!ItemStack.matches(off, lastHoldOff)) {
            lastHoldOff = off.copy();
            handStartOff = ageSeconds;
        }
        applyHandLayer(player, InteractionHand.OFF_HAND, "hold_offhand", ageSeconds, handStartOff);
        applyHandLayer(player, InteractionHand.MAIN_HAND, "hold_mainhand", ageSeconds, handStartMain);
    }

    /**
     * 动作层：挥动与使用。这两层排在主动画与轮盘层**之后**，因为它们是一次性的动作，
     * 本来就该盖住待机/走路的手臂姿态。
     *
     * <p>拆成两次采样是为了让 {@code pre_swing} / {@code post_swing} / {@code pre_use} / {@code post_use}
     * 这四个控制器槽能夹在对应动作的前后（{@code PlayerControllerCollection:54-60} 的注册顺序）。
     */
    private void sampleSwingLayer(AvatarRenderState avatar, LocalPlayer player, float ageSeconds) {
        if (player == null) {
            return;
        }
        // 挥动与使用是「起手时解析一次、播完才释放」：Wiki 明确说挥动动画
        // 「即使玩家停止挥动也不会中断，直至播放完毕」—— 边播边跟着 swinging 走会把收尾那段切掉，
        // 重锤这类长尾动画就会在攻击动作结束时直接弹回待机。
        boolean swingingNow = isSwinging(avatar, player);
        if (swingingNow) {
            InteractionHand hand = swingHand(avatar, player);
            float swingTick = swingProgress(avatar, player);
            // 26.2 及以前 swingTime 每 tick 递增，回到 0 或变小才是新的一次（YSM 的 SwingPredicate:52-56）；
            // 26.3 只有恒定的 durationTicks，改看 SwingDescription 的实例身份。
            if (!swinging || isNewSwing(avatar, swingTick)) {
                boolean offHandSide = hand == InteractionHand.OFF_HAND;
                // 主手分类前缀是 swing（无下划线），副手是 swing_offhand；都对不上就退到 swing_hand
                swingClip = resolveHandClip(player, hand, offHandSide ? "swing_offhand" : "swing",
                        offHandSide ? "swing_offhand" : "swing_hand");
                swingStart = ageSeconds;
            }
            swinging = true;
            lastSwingTick = swingTick;
        } else {
            swinging = false;
            lastSwingTick = -1.0F;
        }
        swingClip = playLatched(swingClip, swingStart, ageSeconds);
    }

    /** 使用层：排在挥动之后，同样是一次性动作，盖住待机/走路的手臂姿态。 */
    private void sampleUseLayer(AvatarRenderState avatar, LocalPlayer player, float ageSeconds) {
        if (player == null) {
            return;
        }
        boolean usingNow = avatar != null && avatar.isUsingItem;
        if (usingNow) {
            InteractionHand hand = avatar.useItemHand;
            float useTick = avatar.ticksUsingItem;
            if (!using || useTick < lastUseTick) {
                boolean offHandSide = hand == InteractionHand.OFF_HAND;
                useClip = resolveHandClip(player, hand, offHandSide ? "use_offhand" : "use_mainhand",
                        offHandSide ? "use_offhand" : "use_mainhand");
                useStart = ageSeconds;
            }
            using = true;
            lastUseTick = useTick;
        } else {
            using = false;
            lastUseTick = -1.0F;
            // 松手就丢掉：使用层不像挥动那样需要播完收尾（弓没拉满就松开时，动画必须立刻让位给待机）。
            useClip = null;
        }
        // 使用动画是循环播放（YSM UsePredicate 用 LoopType.LOOP），和挥动的单次播放不同。
        // 只在「确实还在使用」时应用：不加这层判断会让松手后的姿态一直停在最后一帧。
        if (usingNow && useClip != null) {
            float length = lengthOf(useClip);
            float elapsed = Float.isFinite(useStart) ? Math.max(0.0F, ageSeconds - useStart) : 0.0F;
            float time = length <= 0.0F ? 0.0F : elapsed % length;
            runTimeline(useClip, time, ageSeconds, "use");
            advanceSound("use", useClip, time);
            for (int i = 0; i < bones.size(); i++) {
                sampleOverride(useClip, i, time, ageSeconds);
            }
        }
    }

    /**
     * 播一个「锁存」的手部片段：从 {@code start} 起单次播放，播完返回 {@code null} 表示可以释放。
     *
     * <p>时间用 {@code min(elapsed, length)} 而不是取模 —— 取模是循环，收尾帧永远播不到。
     *
     * <p>播完不是立刻撒手：SPM 的 {@code AnimationControllerInstance:147-152} 在 PLAY_ONCE 播完时
     * 先进 {@code ENDING_TRANSITION}，把收尾帧的姿态按 {@code defaultTransitionTick}（3 tick）淡回中性
     * （{@code :254-280} 存快照、{@code :328-342} 用 {@code ConstantPoint} 逐帧降权）。直接返回
     * {@code null} 会让下一帧的 {@code resetPose} 把手臂硬拽回待机，重锤这类长尾动作看起来就是「蹦一下」。
     */
    private RawYsmModel.RawAnimation playLatched(RawYsmModel.RawAnimation clip, float start, float ageSeconds) {
        if (clip == null) {
            return null;
        }
        float length = lengthOf(clip);
        float elapsed = Float.isFinite(start) ? Math.max(0.0F, ageSeconds - start) : 0.0F;
        if (length > 0.0F && elapsed >= length) {
            // 收尾帧的姿态在「刚播完」那一帧存下来，之后按 ENDING_SECONDS 线性降权
            if (swingEndClip != clip) {
                swingEndClip = clip;
                swingEndStart = ageSeconds;
            }
            float fade = (ageSeconds - swingEndStart) / ENDING_SECONDS;
            if (fade >= 1.0F) {
                swingEndClip = null;
                swingEndStart = Float.NaN;
                return null;
            }
            float weight = 1.0F - Math.max(0.0F, fade);
            float endTime = length <= 0.0F ? 0.0F : length;
            for (int i = 0; i < bones.size(); i++) {
                sampleOverrideWeighted(clip, i, endTime, ageSeconds, weight);
            }
            return clip;
        }
        swingEndClip = null;
        swingEndStart = Float.NaN;
        float time = length <= 0.0F ? 0.0F : elapsed;
        runTimeline(clip, time, ageSeconds, "swing");
        advanceSound("swing", clip, time);
        for (int i = 0; i < bones.size(); i++) {
            sampleOverride(clip, i, time, ageSeconds);
        }
        return clip;
    }

    /**
     * 覆盖层按权重写入：{@code weight=1} 等价于 {@link #sampleOverride}，{@code weight=0} 完全不写。
     *
     * <p>退场过渡期间不能整条丢掉（那正是硬蹦的来源），也不能按 1.0 写（那就永远停在收尾帧）。
     * 与 SPM 的 {@code ConstantPoint} 一样，权重作用在「相对中性值」的偏移量上。
     */
    private void sampleOverrideWeighted(RawYsmModel.RawAnimation clip, int boneIndex, float time, float lifeTime,
            float weight) {
        if (weight <= 0.0F) {
            // 权重归零等于没写：不能标成「被驱动」，否则这根骨骼永远等不到逐骨衰减
            return;
        }
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
                poses[offset] += weight * (float) -Math.toRadians(finite(rotation[0], 0.0F));
                poses[offset + 1] += weight * (float) -Math.toRadians(finite(rotation[1], 0.0F));
                poses[offset + 2] += weight * (float) Math.toRadians(finite(rotation[2], 0.0F));
            }
            float[] position = sampleChannel(animation.position, time, lifeTime);
            if (position != null) {
                poses[offset + 3] += weight * finite(position[0], 0.0F);
                poses[offset + 4] += weight * finite(position[1], 0.0F);
                poses[offset + 5] += weight * finite(position[2], 0.0F);
            }
            float[] scale = sampleChannel(animation.scale, time, lifeTime);
            if (scale != null) {
                // 缩放的中性值是 1：按权重从 1 往采样值靠，weight=0 时保持 1
                poses[offset + 6] = 1.0F + weight * (finite(scale[0], 1.0F) - 1.0F);
                poses[offset + 7] = 1.0F + weight * (finite(scale[1], 1.0F) - 1.0F);
                poses[offset + 8] = 1.0F + weight * (finite(scale[2], 1.0F) - 1.0F);
            }
            markDriven(boneIndex);
            return;
        }
    }

    /**
     * 持有层：从 {@code start} 起单次播放，播完**停在最后一帧**并一直保持 —— 换物品时会重置起点，
     * 于是切换武器时重播一次（YSM 的 {@code indicateReload}）。和挥动不同，它不释放。
     */
    private void applyHandLayer(LocalPlayer player, InteractionHand hand, String prefix, float ageSeconds,
            float start) {
        RawYsmModel.RawAnimation clip = resolveHandClip(player, hand, prefix, null);
        if (clip == null) {
            return;
        }
        float length = lengthOf(clip);
        float elapsed = Float.isFinite(start) ? Math.max(0.0F, ageSeconds - start) : 0.0F;
        float time = length <= 0.0F ? 0.0F : Math.min(elapsed, length);
        runTimeline(clip, time, ageSeconds, prefix);
        advanceSound(prefix, clip, time);
        for (int i = 0; i < bones.size(); i++) {
            sampleOverride(clip, i, time, ageSeconds);
        }
    }

    /**
     * 按「前缀 + 分类后缀」找一个手部片段；找不到返回 {@code null}（保持主动画姿态）。
     *
     * <p>命中判据是「模型里有这个名字」，**空片段也算命中**：YSM 的 {@code AnimationStore#resolve}
     * 先查本地表（{@code containsLocal}），本地有就返回本地那条，哪怕它一根骨都没写；
     * SPM 合并默认动作预设用的是 {@code computeIfAbsent}，同理。作者留一条 0 骨的同名片段
     * 就是在说「这个分类我不要额外姿势」，此时不该拿默认模型的姿势去补 —— 那套姿势是按默认
     * 骨架的静止角写的，套到别的骨架上会扭。
     */
    private RawYsmModel.RawAnimation resolveHandClip(LocalPlayer player, InteractionHand hand, String prefix,
            String fallback) {
        for (String suffix : ItemClassifier.candidates(player, hand)) {
            RawYsmModel.RawAnimation resolved = clip(prefix + suffix);
            if (resolved != null && resolved.boneAnimations != null) {
                return resolved;
            }
        }
        // 分类对不上时的兜底：YSM 的 SwingPredicate/UsePredicate 都会退到不带分类的名字，
        // 所以拿镐子挖矿这类「模型没有专属动作」的情况仍然有动作可播。持有层没有兜底（源码返回 STOP）。
        if (fallback != null) {
            RawYsmModel.RawAnimation resolved = clip(fallback);
            if (resolved != null && resolved.boneAnimations != null) {
                return resolved;
            }
        }
        return null;
    }


    /** 叠加层只动它写到的通道：旋转/位移相加，缩放整体替换；没写到的通道保持基础姿态。 */
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
                poses[offset] += (float) -Math.toRadians(finite(rotation[0], 0.0F));
                poses[offset + 1] += (float) -Math.toRadians(finite(rotation[1], 0.0F));
                poses[offset + 2] += (float) Math.toRadians(finite(rotation[2], 0.0F));
            }
            float[] position = sampleChannel(animation.position, time, lifeTime);
            if (position != null) {
                poses[offset + 3] += finite(position[0], 0.0F);
                poses[offset + 4] += finite(position[1], 0.0F);
                poses[offset + 5] += finite(position[2], 0.0F);
            }
            float[] scale = sampleChannel(animation.scale, time, lifeTime);
            if (scale != null) {
                poses[offset + 6] = finite(scale[0], 1.0F);
                poses[offset + 7] = finite(scale[1], 1.0F);
                poses[offset + 8] = finite(scale[2], 1.0F);
            }
            markDriven(boneIndex);
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

    int preParallelBaseN() {
        return preParallelBase == null ? -1 : preParallelBase.size();
    }

    int parallelOnlyN() {
        return parallelBase == null ? -1 : parallelBase.size();
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
     *
     * <p>挂点骨骼在构建期由候选链解析（见 {@link #RIGHT_HAND_CHAIN}），未命中时返回 {@code null}。
     */
    public Matrix4f handItemMatrix(boolean rightHand) {
        return handItemMatrix(rightHand, ItemClassifier.Grip.DEFAULT);
    }

    /**
     * 手持物品的挂点，按握持类别微调。
     *
     * <p>三叉戟/长矛/锤的重心与剑不同，用同一套偏移会插进手里或悬空
     * （SPM {@code CustomPlayerItemInHandLayer:213-242} 的 {@code applyAuthoredHandTransform}）。
     * 偏移量照抄 SPM 的 YSM 自定义骨骼那一套（不是原版装备那一套）。
     */
    public Matrix4f handItemMatrix(boolean rightHand, ItemClassifier.Grip grip) {
        int index = rightHand ? rightHandBone : leftHandBone;
        if (index < 0) {
            return null;
        }
        Matrix4f matrix = new Matrix4f(frameValid ? frameMatrices[index] : restMatrices[index]);
        float[] pivot = bones.get(index).pivot;
        matrix.translate(pivot[0] / 16.0F, pivot[1] / 16.0F, pivot[2] / 16.0F);
        matrix.translate(0.0F, -0.0625F, -0.1F);
        matrix.rotateX((float) Math.toRadians(-90.0F));
        switch (grip) {
            case TRIDENT -> {
                // 三叉戟的柄比剑长，往手心方向再收一点
                matrix.translate(0.0F, 0.0F, -0.0125F);
            }
            case SPEAR, LANCE -> matrix.translate(0.0F, -0.01875F, -0.025F);
            case MACE -> matrix.translate(0.0F, -0.0125F, 0.01875F);
            default -> {
            }
        }
        return matrix;
    }

    /**
     * 定位组里第 {@code index} 根骨的挂点矩阵：{@code 骨骼矩阵 · translate(pivot)}。
     *
     * <p>与 YSM {@code GeoModelState#visitLocatorGroup:154-162} 同形 —— 先乘骨骼的世界矩阵，
     * 再平移到该骨的 pivot（模型空间单位是 1/16 格）。组不存在或下标越界返回 {@code null}。
     */
    public Matrix4f locatorMatrix(String locator, int index) {
        int[] group = locatorGroups.get(locator);
        if (group == null || index < 0 || index >= group.length) {
            return null;
        }
        int bone = group[index];
        Matrix4f matrix = new Matrix4f(frameValid ? frameMatrices[bone] : restMatrices[bone]);
        float[] pivot = bones.get(bone).pivot;
        matrix.translate(pivot[0] / 16.0F, pivot[1] / 16.0F, pivot[2] / 16.0F);
        return matrix;
    }

    /**
     * 这只手的 {@code *Sword} 锚点子树里有没有几何 —— 有就说明模型自己画了剑，
     * 手上不该再叠一把原版物品（SPM {@code SwordReplacementHelper:20-30}）。
     */
    public boolean hasSwordGeometry(boolean rightHand) {
        return swordGeometry[rightHand ? 1 : 0];
    }

    /** 定位组的骨骼数量（YSM 的 {@code locatorGroupSize}）；没有该组返回 0。 */
    public int locatorGroupSize(String locator) {
        int[] group = locatorGroups.get(locator);
        return group == null ? 0 : group.length;
    }

    /** 骨骼名 → 下标；找不到返回 -1。 */
    private int boneIndexOf(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        Integer index = boneIndex.get(name);
        return index == null ? -1 : index;
    }

    /**
     * {@code ysm.bone_rot}：骨骼上一帧姿态的欧拉角，**度**，X/Y 取负、Z 取正（SPM {@code BoneRotation:22-34}）。
     *
     * <p>加上骨骼自己的 rest 旋转：SPM 的 {@code getRotationX} 读的是「动画偏移 + 初始旋转」的合成值
     * （{@code BoneTopLevelSnapshot#reset} 把 {@code rotation + initialRotation} 写回骨骼）。
     * 骨骼不存在返回 0。
     */
    public float boneRotation(int axis, String name) {
        int index = boneIndexOf(name);
        if (index < 0 || axis < 0 || axis > 2) {
            return 0.0F;
        }
        float radians = lastFramePoses[index * 9 + axis] + bones.get(index).rotation[axis];
        return axis == 2 ? (float) Math.toDegrees(radians) : (float) -Math.toDegrees(radians);
    }

    /** {@code ysm.bone_scale}：骨骼上一帧的缩放（SPM {@code BoneScale:21-34}）。骨骼不存在返回 0。 */
    public float boneScale(int axis, String name) {
        int index = boneIndexOf(name);
        if (index < 0 || axis < 0 || axis > 2) {
            return 0.0F;
        }
        return lastFramePoses[index * 9 + 6 + axis];
    }

    /**
     * {@code ysm.bone_pivot_abs}：骨骼 pivot 的绝对位置（模型像素），X 取负（SPM {@code GpuRenderPath:906-910}）。
     *
     * <p>只累乘到父骨骼为止：目标骨骼自己的 {@code -pivot} 回移不算进绝对 pivot，SPM 在
     * {@code computeOnePivotAbs} 里也是写完 {@code stateBuffer} 就返回、不再回移。
     * 骨骼不存在返回 0。
     */
    public float bonePivotAbs(int axis, String name) {
        int index = boneIndexOf(name);
        if (index < 0 || axis < 0 || axis > 2) {
            return 0.0F;
        }
        Matrix4f matrix = pivotAbsScratch.identity();
        accumulateAncestor(index, matrix);
        int offset = index * 9;
        float[] pivot = bones.get(index).pivot;
        matrix.translate((pivot[0] - lastFramePoses[offset + 3]) / 16.0F,
                (pivot[1] + lastFramePoses[offset + 4]) / 16.0F,
                (pivot[2] + lastFramePoses[offset + 5]) / 16.0F);
        return switch (axis) {
            case 0 -> -matrix.m30() * 16.0F;
            case 1 -> matrix.m31() * 16.0F;
            default -> matrix.m32() * 16.0F;
        };
    }

    /** 从根往下累乘到 {@code index} 的父骨骼为止；目标骨骼自己的 -pivot 回移不算进绝对 pivot。 */
    private void accumulateAncestor(int index, Matrix4f matrix) {
        int parent = parents[index];
        if (parent < 0) {
            return;
        }
        accumulateAncestor(parent, matrix);
        matrix.mul(localOf(bones.get(parent), lastFramePoses, parent * 9));
    }

    public void render(PoseStack.Pose pose, VertexConsumer buffer, int light) {
        frameValid = false;
        emit(pose, buffer, light, restMatrices, restNormals);
    }

    /**
     * 这张贴图有没有半透明像素。cutout 会把 alpha 直接切掉，软边头发/纱/翅膀会被硬切；
     * 有半透明像素时主趟要换成 {@code entityTranslucent} 才能按 alpha 混合。
     *
     * <p>判据取「存在 0 < alpha < 255 的像素」，与 SPM 的 {@code TextureAlphaAnalyzer} 同口径
     * （它按面 UV 区间扫，这里按整张贴图扫：RenderType 是整趟的，逐面区分不了）。
     * 贴图读不到（占位图/已释放）时返回 {@code false}，退回 cutout。
     */
    public boolean hasTranslucentPixels() {
        Boolean cached = translucentPixels;
        if (cached != null) {
            return cached;
        }
        boolean result = scanTranslucent(texture);
        translucentPixels = result;
        return result;
    }

    private static boolean scanTranslucent(Identifier texture) {
        if (texture == null) {
            return false;
        }
        try {
            if (!(Minecraft.getInstance().getTextureManager().getTexture(texture) instanceof DynamicTexture dynamic)) {
                return false;
            }
            NativeImage image = dynamic.getPixels();
            if (image == null || image.isClosed()) {
                return false;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int alpha = image.getPixel(x, y) >>> 24;
                    if (alpha != 0 && alpha != 255) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException failure) {
            Debug.info("[SFH+] 贴图 alpha 扫描失败，按不透明处理: " + texture + " " + failure);
        }
        return false;
    }

    /** 这个模型有没有发光骨（骨名前缀 {@code ysmGlow}）；没有就不必提交第二趟。 */
    public boolean hasGlowBones() {
        if (glowMask == null) {
            return false;
        }
        for (boolean glow : glowMask) {
            if (glow) {
                return true;
            }
        }
        return false;
    }

    /**
     * 发光骨的第二趟出图：只出 {@code ysmGlow} 骨，用 emissive 渲染类型提交。
     *
     * <p>必须紧跟在主趟 {@link #render} 之后调用同一帧：姿态矩阵是那一趟算出来的，
     * 单独再算一遍会让发光骨与身体错开一帧。主趟没有跑过（{@code frameValid} 为假）时退回静止姿态。
     */
    public void renderGlow(PoseStack.Pose pose, VertexConsumer buffer, int light) {
        if (glowMask == null) {
            return;
        }
        if (frameValid) {
            emit(pose, buffer, light, frameMatrices, frameNormals, glowMask);
        } else {
            emit(pose, buffer, light, restMatrices, restNormals, glowMask);
        }
    }

    public void render(PoseStack.Pose pose, VertexConsumer buffer, int light,
            AvatarRenderState avatar, LocalPlayer player, float ageSeconds) {
        RawYsmModel.RawAnimation clip = pickClip(avatar, player);
        if (clip == null) {
            render(pose, buffer, light);
            return;
        }
        MolangVariables.bind(player, avatar, variables);
        MolangVariables.bindRuntime(this);
        // 控制器条件里写的 ctrl.idle / ctrl.run 就是这套主体态判定
        MolangVariables.bindControl(selectControlName(avatar, player));
        MolangVariables.clearControllerState();
        ModelPhysics.bind(physics);
        physics.advance(ageSeconds);
        int hurtTime = player == null ? 0 : player.hurtTime;
        boolean restart = clip != lastClip;
        if (ONE_SHOT.contains(clipName(clip)) && hurtTime > lastHurtTime) {
            restart = true;
        }
        lastHurtTime = hurtTime;
        if (restart) {
            lastClip = clip;
            lastClipStart = ageSeconds;
            blendStart = ageSeconds;
        }
        float time = clipTime(clip, ageSeconds);
        // 过渡窗口内把新动画的时间钉在 0：SPM 的 BEGINNING_TRANSITION 就是这样做的
        // （过渡期间 animTime 恒为 0，走完才把 tick 减去过渡长度继续推进）。
        // 只混姿态不让时间停住的话，动作本身已经在跑，观感仍是「跳一下」。
        if (Float.isFinite(blendStart) && ageSeconds - blendStart < BLEND_SECONDS) {
            time = 0.0F;
        }
        java.util.Arrays.fill(frameState, (byte) 0);
        weightClip = null;
        weightTime = Float.NaN;
        lastFrameAge = ageSeconds;
        // 控制器本帧会播哪些片段还不知道，先清空；采样完再按实际播的重算一次隐藏表
        controllerPlayingClips.clear();
        for (int i = 0; i < bones.size(); i++) {
            resetPose(i);
        }
        // 默认层 pre_parallel0..7 / parallel0..7：由代码侧控制器无条件循环播放，道具「默认不显示」就是靠它们
        // 把骨骼缩放成 0；物理（弹簧）写在这几个片段的 timeline 语句里，每帧推进一次。
        // 缩放在层间是整体替换而不是相乘：作者在 boat/sleep 这类主动画里用 scale=1 把 pre_parallel 藏起来的
        // 道具重新显示出来，相乘会把这个 1 乘成 0、道具永远出不来。
        // pre_parallel 是非 additive 控制器：同通道以最后一个写它的片段为准，不能相加。
        sampleLayers(preParallelBase, ageSeconds, true);
        // 控制器按 YSM 的注册顺序分桶插到对应层位置，而不是整桶压在主层之上 ——
        // 否则 pre_parallel 会盖掉主动画的 scale=1（道具不露），parallel 又会被 swing/use 盖住。
        sampleControllerLayers("pre_parallel", ageSeconds);
        sampleControllerLayers("pre_main", ageSeconds);
        runTimeline(clip, time, ageSeconds, "main");
        advanceSound("main", clip, time);
        for (int i = 0; i < bones.size(); i++) {
            sampleOverride(clip, i, time, ageSeconds);
        }
        // 服装变体是静态预设（只有缩放/位移），动作是循环播放；两层叠加，动作最后生效
        if (activeVariant != null) {
            runTimeline(activeVariant, 0.0F, ageSeconds, "variant");
            advanceSound("variant", activeVariant, 0.0F);
            for (int i = 0; i < bones.size(); i++) {
                sampleOverlay(activeVariant, i, 0.0F, ageSeconds);
            }
        }
        if (activeExtra != null) {
            if (!Float.isFinite(activeExtraStart)) {
                activeExtraStart = ageSeconds;
            }
            float extraTime = overlayTime(activeExtra, ageSeconds);
            runTimeline(activeExtra, extraTime, ageSeconds, "extra");
            advanceSound("extra", activeExtra, extraTime);
            for (int i = 0; i < bones.size(); i++) {
                sampleOverlay(activeExtra, i, extraTime, ageSeconds);
            }
        }
        // 控制器槽（parallel*/pre_parallel*/模型自定义槽）：道具显隐、换装、连击链都写在这些片段里，
        // 由状态机按 Molang 条件选出来当覆盖层。
        sampleControllerLayers("post_main", ageSeconds);
        // 持有层排在主动画之后（YSM 的注册顺序：main → post_main → pre_hold → hold_* → post_hold）：
        // 它是**覆盖**语义，只改自己写到的骨骼，所以持物姿势能盖住 idle 的手臂，而没写到的身体骨
        // 仍保持主动画的呼吸与迈腿。放在主动画之前会被 idle 的手臂通道盖掉，表现成「拿着剑但手臂还是待机」。
        sampleControllerLayers("pre_hold", ageSeconds);
        sampleHoldLayers(player, ageSeconds);
        sampleControllerLayers("post_hold", ageSeconds);
        // 槽名不在 YSM 注册表里的控制器（模型自定义槽）保持原来的位置：主动画之后、手部动作之前。
        sampleControllerLayers("", ageSeconds);
        // 手部条件动画：YSM 的控制器顺序是 pre_hold → hold_offhand → hold_mainhand → pre_swing → swing
        // → pre_use → use，全部排在主动画之后、parallel 之前，且是**覆盖**（绝对姿态）而不是相加。
        // 主副手各一层、互不覆盖（不同手臂），万一同骨骼则 mainhand 赢（顺序在后）。
        blendBasePose(ageSeconds);
        sampleControllerLayers("pre_swing", ageSeconds);
        sampleSwingLayer(avatar, player, ageSeconds);
        sampleControllerLayers("post_swing", ageSeconds);
        sampleControllerLayers("pre_use", ageSeconds);
        sampleUseLayer(avatar, player, ageSeconds);
        sampleControllerLayers("post_use", ageSeconds);
        // parallel 槽在 YSM 里排在 main 之后，所以它既能把通道加到主姿态上，也能在缩放上盖掉前面几层。
        // 它是 YSM 里唯一带 blendRotation 的控制器，合成方式就是相加。
        sampleLayers(parallelBase, ageSeconds, false);
        sampleControllerLayers("parallel", ageSeconds);
        // 本帧控制器播的片段都收集完了，按它们重算一次隐藏表：作者用控制器把道具放出来时，
        // 默认隐藏的那棵子树要跟着解除，否则 scale 写回 1 也画不出来。
        recomputeHidden();
        // 头部朝向：YSM 在动画求值之后把视线角度加到 Head 组骨骼上（CustomHumanoidEntity#codeAnimation），
        // 加的是「头相对身体」的分量 —— 身体朝向由实体变换那一层（applyEntityTransform）负责，这里只管头怎么偏。
        // YSM / SPM 在同一个（X/Y 已取负的）骨骼空间里加 headPitch = -xRot、netHeadYaw = -clamp(±85)，
        // 两个值原样搬过来即可，再翻一次符号等于在镜像空间里多反了一次。
        // avatar.yRot 本身就是 wrapDegrees(头朝向 - 身体朝向)（LivingEntityRenderer#extractRenderState），
        // 再减 bodyRot 会把它当成绝对角用，转过头时被 clamp 顶死，看起来就是「只有身体在转」。
        if (avatar != null) {
            float headX = (float) Math.toRadians(-avatar.xRot);
            float headY = (float) Math.toRadians(-net.minecraft.util.Mth.clamp(
                    net.minecraft.util.Mth.wrapDegrees(avatar.yRot), -85.0F, 85.0F));
            for (int i = 0; i < bones.size(); i++) {
                if (!isHeadBone(bones.get(i).name)) {
                    continue;
                }
                int offset = i * 9;
                poses[offset] += headX;
                poses[offset + 1] += headY;
                // 头部朝向每帧都写，算「被驱动」：否则它会被逐骨衰减当成没人管的骨骼拽回中性
                markDriven(i);
            }
        }
        // 所有层都跑完之后再收尾：本帧没被任何层写到的骨骼从最后一次被驱动的姿态衰减回中性
        // （SPM AnimationProcessor:169-226 也是在控制器全部跑完之后做这一步）
        settleDecay(ageSeconds);
        for (int i = 0; i < bones.size(); i++) {
            computeFrame(i);
            frameNormals[i] = normalOf(frameMatrices[i]);
        }
        frameValid = true;
        // 每帧只拷一次：表达式在采样过程中求值，读的是上一帧的完整姿态（见 lastFramePoses）
        System.arraycopy(poses, 0, lastFramePoses, 0, poses.length);
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
        MolangVariables.bind(Minecraft.getInstance().player, null, variables);
        MolangVariables.bindRuntime(this);
        // 第一人称没有渲染状态，所有 ctrl.* 一律按未绑定（条件为假）
        MolangVariables.bindControl("");
        MolangVariables.clearControllerState();
        ModelPhysics.bind(physics);
        physics.advance(ageSeconds);
        float time = clipTime(clip, ageSeconds);
        runTimeline(clip, time, ageSeconds, "fp_arm");
        advanceSound("fp_arm", clip, time);
        java.util.Arrays.fill(frameState, (byte) 0);
        weightClip = null;
        weightTime = Float.NaN;
        lastFrameAge = ageSeconds;
        for (int i = 0; i < bones.size(); i++) {
            resetPose(i);
            sampleOverride(clip, i, time, ageSeconds);
        }
        settleDecay(ageSeconds);
        for (int i = 0; i < bones.size(); i++) {
            computeFrame(i);
            frameNormals[i] = normalOf(frameMatrices[i]);
        }
        frameValid = true;
        System.arraycopy(poses, 0, lastFramePoses, 0, poses.length);
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
    /**
     * 把一根骨骼的姿态复位成静止值；分层叠加靠它划清每帧的起点。
     *
     * <p>复位本身仍是硬清零：本帧有没有层写它要等所有层跑完才知道，所以「不再被驱动」的收尾
     * 放在 {@link #settleDecay} 里做（SPM 也是在控制器全部跑完之后才按 {@code resetSpeed} 衰减）。
     */
    private void resetPose(int boneIndex) {
        int offset = boneIndex * 9;
        poseDriven[boneIndex] = false;
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
     * 记下这根骨骼本帧被驱动过：它当前没有在衰减，姿态由本帧的层决定。
     *
     * <p>「被驱动」的判据是「本帧有层真的写了它的通道」—— 见各采样方法里的调用点。
     * 只被复位、没被任何层写到的骨骼不算，否则衰减永远从复位值出发，等于没有衰减。
     */
    private void markDriven(int boneIndex) {
        poseDriven[boneIndex] = true;
        // 记下「最后一次被驱动时的姿态与时刻」：下一帧若不再被驱动，衰减就从这里出发
        System.arraycopy(poses, boneIndex * 9, decayPoses, boneIndex * 9, 9);
        decayStart[boneIndex] = lastFrameAge;
    }

    /**
     * 帧末收尾：本帧没有任何层写到的骨骼，从它最后一次被驱动时的姿态按 {@link #DECAY_SECONDS} 衰减回中性。
     *
     * <p>「不再被驱动」的判据是 {@link #poseDriven}：本帧所有采样方法（默认层、控制器层、主动画、
     * 服装变体、轮盘额外动作、手部 hold/swing/use、头部朝向）都没写这根骨骼的通道。只被
     * {@link #resetPose} 复位过不算驱动 —— 否则衰减永远从复位值出发，等于没有衰减。
     *
     * <p>与 SPM {@code AnimationProcessor:169-226} 同形：那里也是在所有控制器跑完之后，对
     * {@code isCurrentlyRunning*Animation == false} 的骨骼按 {@code (seekTime - mostRecentResetTick) / resetSpeed}
     * 做 lerp。{@link #decayStart} 记的是「最后一次被驱动的 tick」，起点姿态在那一帧由
     * {@link #markDriven} 抓一次 —— 每帧重抓会把进度重置回 0，衰减永远走不完。
     */
    private void settleDecay(float ageSeconds) {
        for (int i = 0; i < bones.size(); i++) {
            if (poseDriven[i] || !Float.isFinite(decayStart[i])) {
                continue;
            }
            // 起点是「最后一次被驱动的 tick」：SPM 的 mostRecentResetTick 也是驱动那一帧的 seekTime，
            // 所以骨骼停驱动的第一帧就已经走掉 1/3 的进度，而不是从 0 开始
            float progress = (ageSeconds - decayStart[i]) / DECAY_SECONDS;
            if (progress >= 1.0F) {
                decayStart[i] = Float.NaN;
                continue;
            }
            float t = Math.max(0.0F, progress);
            int offset = i * 9;
            for (int channel = 0; channel < 9; channel++) {
                float neutral = channel >= 6 ? 1.0F : 0.0F;
                poses[offset + channel] = decayPoses[offset + channel]
                        + (neutral - decayPoses[offset + channel]) * t;
            }
        }
    }

    /** Molang 的未知查询会算出 NaN；落到通道上时取中性值（旋转/位移 0，缩放 1）。 */
    private static float finite(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    /**
     * 采样一条通道在 {@code time} 处的三元值；通道为空返回 {@code null}。
     *
     * <p>关键帧有 pre / post 两个值：**段内插值的目标是下一帧的 pre**，到帧点才跳到它的 post
     * （SPM {@code EasingType#buildLinearKeyFrame} 用 {@code end.preValue()} 当终点、
     * {@code postValue()} 当帧点值）。作者用 pre≠post 表达「到这里瞬间换姿势」，
     * 只读 post 会把这段跳变摊成一条多余的过渡。
     */
    private static float[] sampleChannel(List<RawYsmModel.RawKeyframe> frames, float time, float lifeTime) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        int last = frames.size() - 1;
        if (time < frames.get(0).timestamp) {
            // 首帧之前是常量：SPM 的 TransitionKeyFrame 取首帧的 pre
            return valuesOf(preOf(frames.get(0)), time, lifeTime);
        }
        if (time >= frames.get(last).timestamp) {
            return valuesOf(frames.get(last).postData, time, lifeTime);
        }
        for (int i = 0; i < last; i++) {
            RawYsmModel.RawKeyframe current = frames.get(i);
            RawYsmModel.RawKeyframe next = frames.get(i + 1);
            if (time < current.timestamp || time >= next.timestamp) {
                continue;
            }
            float[] a = valuesOf(current.postData, time, lifeTime);
            if (a == null) {
                return null;
            }
            if (current.interpolationMode == RawYsmModel.RawKeyframe.INTERPOLATION_STEP) {
                return a;
            }
            float[] b = valuesOf(preOf(next), time, lifeTime);
            if (b == null) {
                return a;
            }
            float span = next.timestamp - current.timestamp;
            float t = span <= 0.0F ? 0.0F : (time - current.timestamp) / span;
            if (current.interpolationMode == RawYsmModel.RawKeyframe.INTERPOLATION_CATMULLROM) {
                // 段两端各多取一个控制点；越界时用端点自身（SPM 的 Math.max(0, i-2) / Math.min(size-1, i+1)）
                float[] left = valuesOf(postOf(frames.get(Math.max(0, i - 1))), time, lifeTime);
                float[] right = valuesOf(preOf(frames.get(Math.min(last, i + 2))), time, lifeTime);
                if (left != null && right != null) {
                    return new float[]{
                            catmullRom(t, left[0], a[0], b[0], right[0]),
                            catmullRom(t, left[1], a[1], b[1], right[1]),
                            catmullRom(t, left[2], a[2], b[2], right[2])};
                }
            }
            return new float[]{
                    a[0] + (b[0] - a[0]) * t,
                    a[1] + (b[1] - a[1]) * t,
                    a[2] + (b[2] - a[2]) * t};
        }
        return valuesOf(frames.get(last).postData, time, lifeTime);
    }

    /** 关键帧的「段起点值」：没有 preData 时与 post 相同（SPM 的 {@code contiguous}）。 */
    private static Object[] preOf(RawYsmModel.RawKeyframe frame) {
        return frame.hasPreData ? frame.preData : frame.postData;
    }

    /** 关键帧的「帧点值」：到帧点后跳到 post。 */
    private static Object[] postOf(RawYsmModel.RawKeyframe frame) {
        return frame.postData;
    }

    /**
     * Catmull-Rom 插值（SPM {@code MathUtil.catmullRom} 同形）。
     *
     * <p>用左右两个邻居控制切线，比线性插值多一次导数连续 —— 走跑、裙摆、头发这类
     * 关键帧密集的通道，线性插值会在每个帧点留下折角。
     */
    private static float catmullRom(float t, float left, float begin, float end, float right) {
        float v0 = (end - left) * 0.5F;
        float v1 = (right - begin) * 0.5F;
        float t2 = t * t;
        float t3 = t2 * t;
        return (2.0F * begin - 2.0F * end + v0 + v1) * t3
                + (-3.0F * begin + 3.0F * end - 2.0F * v0 - v1) * t2
                + v0 * t + begin;
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
                result[i] = MolangExpression.channel(text, time, lifeTime);
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

    /**
     * 控制器槽名 → 它驱动的片段名：YSM 的槽是 {@code parallel_6} / {@code pre_parallel_0}，
     * 片段却是 {@code parallel6} / {@code pre_parallel0}，少一个下划线。
     */
    private static String clipNameOfSlot(String slot) {
        int underscore = slot.lastIndexOf('_');
        if (underscore > 0 && underscore + 2 == slot.length() && Character.isDigit(slot.charAt(underscore + 1))) {
            return slot.substring(0, underscore) + slot.charAt(underscore + 1);
        }
        return slot;
    }

    /** 按名字取片段长度，给控制器判断「本状态的动画播完没有」。 */
    private float lengthOfClip(String name) {
        RawYsmModel.RawAnimation clip = clip(name);
        return clip == null ? 0.0F : lengthOf(clip);
    }

    /** 片段名大小写不敏感查找：YSM 默认控制器里写的是 {@code Hair_Physics}，模型里却常写成小写。 */
    private static String findClipIgnoreCase(Map<String, RawYsmModel.RawAnimation> clips, String wanted) {
        for (String name : clips.keySet()) {
            if (name.equalsIgnoreCase(wanted)) {
                return name;
            }
        }
        return null;
    }

    /**
     * 片段时间轴长度（秒）。{@code length} 是 {@code Infinity} 的片段要原样留着 —— 作者的用意是
     * 「不循环、{@code query.anim_time} 一直长下去」（pre_parallel 里那几条无限缠绕的发丝就是靠它算相位）；
     * 折成关键帧最大时间会让 {@code ageSeconds % length} 恒为 0，动画和相位一起冻在开头。
     */
    private static float lengthOf(RawYsmModel.RawAnimation clip) {
        float length = clip.length;
        if (!(length > 0.0F)) {
            length = maxKeyframeTime(clip);
        }
        return length > 0.0F ? length : 0.0F;
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

    /**
     * 是否正在挥动。26.3 把实体上的 {@code swinging} 收进了 {@code ArmedEntityRenderState.currentSwing}
     * （{@code LivingEntity.SwingDescription}），26.2 及以前仍是实体字段。
     */
    private static boolean isSwinging(AvatarRenderState avatar, LocalPlayer player) {
        //#if MC >= 260300
        //$$ return avatar != null && avatar.currentSwing != null;
        //#else
        return player != null && player.swinging;
        //#endif
    }

    /** 正在挥动的那只手；26.3 从 {@code currentSwing.hand()} 取，之前从实体字段取。 */
    private static InteractionHand swingHand(AvatarRenderState avatar, LocalPlayer player) {
        //#if MC >= 260300
        //$$ return avatar != null && avatar.currentSwing != null ? avatar.currentSwing.hand() : InteractionHand.MAIN_HAND;
        //#else
        return player != null ? player.swingingArm : InteractionHand.MAIN_HAND;
        //#endif
    }

    /**
     * 是否是新一次挥动。
     *
     * <p>26.3 的 {@code SwingDescription.durationTicks} 是<b>挥动总时长（常量）</b>，不随时间变化；
     * 快连击时 {@code currentSwing} 又一直非空，递减判据永远不成立 —— 只能靠实例身份：
     * 每次 {@code swing()} 都会新建一个 {@code SwingDescription} 记录。
     */
    private boolean isNewSwing(AvatarRenderState avatar, float swingTick) {
        //#if MC >= 260300
        //$$ Object current = avatar == null ? null : avatar.currentSwing;
        //$$ boolean fresh = current != lastSwingMarker;
        //$$ lastSwingMarker = current;
        //$$ return fresh;
        //#else
        return swingTick < lastSwingTick;
        //#endif
    }

    /** 挥动进度（tick）。26.3 只有 {@code currentSwing.durationTicks()}，26.2 及以前是实体字段。 */
    private static float swingProgress(AvatarRenderState avatar, LocalPlayer player) {
        //#if MC >= 260300
        //$$ return avatar == null || avatar.currentSwing == null ? 0.0F : avatar.currentSwing.durationTicks();
        //#else
        return player == null ? 0.0F : player.swingTime;
        //#endif
    }

    /**
     * 取片段：模型自带的优先，缺失时退回默认模型的同名动画。
     *
     * <p>兜底只在读取侧生效，绝不写进 {@code clips} —— {@code references}、道具启发式与
     * {@code pre_parallel*} 的统计都按模型自己的骨骼名算，混进默认模型的骨骼会把它们污染掉。
     */
    private RawYsmModel.RawAnimation clip(String name) {
        RawYsmModel.RawAnimation own = clips.get(name);
        return own != null ? own : defaultClips.get(name);
    }

    /**
     * 只在模型自带的片段里找，**不**回落默认模型。
     *
     * <p>主体态必须用这个：默认模型的 {@code swing_hand} 只写双臂，一旦被选成主体态，边挖边走时腿就停了。
     * 挥动/使用的默认兜底交给手部层（它只覆盖手臂骨骼），主体态继续走 walk/idle。
     */
    private RawYsmModel.RawAnimation firstOwnOf(String[] candidates) {
        for (String name : candidates) {
            RawYsmModel.RawAnimation clip = clips.get(name);
            if (clip != null) {
                return clip;
            }
        }
        return null;
    }

    private RawYsmModel.RawAnimation firstOf(String[] candidates) {
        for (String name : candidates) {
            RawYsmModel.RawAnimation resolved = clip(name);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    /**
     * 主体态名字，供控制器的 {@code ctrl.idle} / {@code ctrl.run} 这类谓词读。
     *
     * <p>顺序照 YSM {@code ControlReadBuiltins#selectedControl}：死亡 → 激流 → 睡觉 → 游泳 →
     * 游泳姿态（动/静）→ 攀爬 → 飞行 → 鞘翅 → 水中悬停 → 受击 → 跳跃 → 潜行（动/静）→ 疾跑 → 走 → 待机。
     * 骑乘时 YSM 直接给空串（载具自己有控制器），所以 {@code ctrl.*} 在车上全为假。
     *
     * <p>和 {@link #pickClip} 分开算：那边要按「模型里有没有这条片段」逐级回退，这里要的是
     * 「玩家当前处在哪个状态」这个纯判定，缺片段不该影响 {@code ctrl.*}。
     */
    private static String selectControlName(AvatarRenderState avatar, LocalPlayer player) {
        if (player == null) {
            return "";
        }
        if (player.isPassenger()) {
            return "";
        }
        if (player.isDeadOrDying()) {
            return "death";
        }
        if (player.isAutoSpinAttack()) {
            return "riptide";
        }
        if (player.getPose() == Pose.SLEEPING) {
            return "sleep";
        }
        if (player.isSwimming()) {
            return "swim";
        }
        boolean moving = avatar != null && avatar.walkAnimationSpeed > MIN_MOVING;
        if (player.getPose() == Pose.SWIMMING) {
            return moving ? "climb" : "climbing";
        }
        if (player.onClimbable()) {
            double vertical = player.getDeltaMovement().y;
            return vertical > 1.0E-4 ? "ladder_up" : vertical < -1.0E-4 ? "ladder_down" : "ladder_stillness";
        }
        if (player.getAbilities().flying) {
            return "fly";
        }
        if (player.isFallFlying()) {
            return "elytra_fly";
        }
        if (player.isInWater() && !player.onGround()) {
            return "swim_stand";
        }
        if (player.hurtTime > 0) {
            return "attacked";
        }
        if (!player.onGround() && !player.isInWater()) {
            return "jump";
        }
        if (player.onGround() && player.getPose() == Pose.CROUCHING) {
            return moving ? "sneak" : "sneaking";
        }
        if (player.onGround() && player.isSprinting()) {
            return "run";
        }
        if (player.onGround() && moving) {
            return "walk";
        }
        return "idle";
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
            // 挥动优先于使用（YSM 控制器顺序 pre_swing → swing → pre_use → use）。
            // 挥动/使用的「武器专属」版本由手部层单独覆盖（只看手臂骨骼），主动画仍走走路/待机，
            // 这样挖掘或攻击时腿照样动。只有模型自己真的提供了 swing_hand / use_* 时才换主体态。
            if (isSwinging(avatar, player)) {
                RawYsmModel.RawAnimation swing = firstOwnOf(
                        swingHand(avatar, player) == InteractionHand.OFF_HAND ? SWING_OFF : SWING_MAIN);
                if (swing != null) {
                    return swing;
                }
            }
            if (avatar.isUsingItem && avatar.walkAnimationSpeed <= MIN_MOVING) {
                RawYsmModel.RawAnimation use = firstOwnOf(
                        avatar.useItemHand == InteractionHand.MAIN_HAND ? USE_MAIN : USE_OFF);
                if (use != null) {
                    return use;
                }
            }
            if (player.onGround() && player.isSprinting()) {
                return orIdle(firstOf(RUN));
            }
            if (player.onGround() && avatar.walkAnimationSpeed > MIN_MOVING) {
                return orIdle(firstOf(WALK));
            }
        }
        if (isSwinging(avatar, player)) {
            RawYsmModel.RawAnimation swing = firstOwnOf(
                    swingHand(avatar, player) == InteractionHand.OFF_HAND ? SWING_OFF : SWING_MAIN);
            if (swing != null) {
                return swing;
            }
        }
        if (avatar.isUsingItem) {
            RawYsmModel.RawAnimation use = firstOwnOf(
                    avatar.useItemHand == InteractionHand.MAIN_HAND ? USE_MAIN : USE_OFF);
            if (use != null) {
                return use;
            }
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
        return emit(pose, buffer, light, matrices, normals, null);
    }

    /**
     * 出图，{@code onlyGlow} 非空时只出掩膜为真的骨骼（发光骨第二趟），
     * 为 {@code null} 时出全部（主趟）。
     */
    private int emit(PoseStack.Pose pose, VertexConsumer buffer, int light,
            Matrix4f[] matrices, Matrix3f[] normals, boolean[] onlyGlow) {
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
            if (onlyGlow != null && !onlyGlow[i]) {
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