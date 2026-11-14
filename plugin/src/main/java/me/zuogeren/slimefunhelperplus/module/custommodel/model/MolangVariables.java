package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.boat.ChestRaft;
import net.minecraft.world.entity.vehicle.boat.Raft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import org.lwjgl.stb.STBPerlin;

/**
 * YSM 的 ysm.* / query.* 查询量。
 *
 * <p>名字与语义对齐 SPM 的 YSMBinding / QueryBinding（MIT，同版本目标）；那两张表本身是 YSM 官方行为的
 * 忠实移植，比从 wiki 反推可靠。取值靠每帧绑定的上下文（{@link #bind}）。
 *
 * <p>没有实现的量返回 NaN：未知量按 NaN 走比较为假、通道落中性值，不会把条件误判成真。
 * {@code v.*} 局部变量是例外 —— 官方资源把它当加数到处用（{@code +v.hv}），NaN 会毒掉整条表达式，
 * 所以按 Molang 语义把未赋值的读成 0；写入侧是 {@link MolangExpression#statements} 的时间轴语句，
 * 变量表按模型实例绑定（{@link #bind}）。武器的动作子状态来自 SPM 的 WeaponActionBridge 子系统，
 * 尚未移植，保持 NaN。
 */
public final class MolangVariables {

    private static volatile LocalPlayer player;
    private static volatile AvatarRenderState avatar;
    /** 上一次算 yaw 速度时的 tick 与 yaw：YSM 的速度是逐 tick 差分，同一 tick 里重复出图不能重复分摊。 */
    private static long lastYawTick = Long.MIN_VALUE;
    private static float lastYaw;
    private static float lastYawSpeed;
    /**
     * 时间轴语句写入的 {@code v.*} 变量表；每个模型实例一份，渲染前绑定。
     *
     * <p>{@code t.*} / {@code temp.*} 也存在这张表里（键带 {@code t.} 前缀，与 {@code v.*} 的键不重叠）：
     * SPM 的 temp 表挂在模型实例的 VariableStorage 上（{@code TempVariableRegistry}），与 {@code v.*}
     * 同生命周期 —— 换模型实例即清空、同一实例内跨帧保留，共用一张表正好是这个语义。
     */
    private static volatile Map<String, Float> variables;
    /**
     * 当前正在出图的模型实例：{@code ysm.bone_rot} 这类骨骼查询量要问它要姿态。
     *
     * <p>与 {@link #variables} 同时绑定、同生命周期 —— 骨骼姿态属于模型实例，不属于实体。
     * {@code null} 表示不在渲染上下文里，骨骼查询一律按「找不到骨骼」返回 0。
     */
    private static volatile ModelRuntime runtime;
    /**
     * 主体态名字（YSM {@code ControlReadBuiltins#selectedControl} 的那串判定），给 {@code ctrl.idle}
     * 这类谓词用。空串表示当前不在渲染上下文里，所有 {@code ctrl.*} 按 NaN（条件为假）。
     */
    private static volatile String control = "";
    /** 控制器求值时绑定的「本状态动画是否都播完」；不在控制器上下文里按 NaN，避免污染别处的条件。 */
    private static volatile float controllerAllFinished = Float.NaN;
    private static volatile float controllerAnyFinished = Float.NaN;
    /** 上一帧的插值位置与它所属的帧键：{@code query.position_delta} 同一帧只算一次，见 {@link #framePositionDelta()}。 */
    private static long lastPositionKey = Long.MIN_VALUE;
    private static double lastPositionX;
    private static double lastPositionY;
    private static double lastPositionZ;
    private static final float[] lastPositionDelta = new float[3];

    private MolangVariables() {}

    public static void bind(LocalPlayer localPlayer, AvatarRenderState state) {
        bind(localPlayer, state, null);
    }

    /** {@code table} 是该模型实例的 {@code v.*} / {@code t.*} 变量表；没有表时局部变量一律读 0。 */
    public static void bind(LocalPlayer localPlayer, AvatarRenderState state, Map<String, Float> table) {
        variables = table;
        avatar = state;
        player = localPlayer;
    }

    /**
     * 绑定本帧出图的模型实例，供 {@code ysm.bone_rot} / {@code bone_pivot_abs} / {@code bone_scale} 查姿态。
     *
     * <p>与 {@link #bind} 分开：骨骼查询只在模型出图时有意义，而 {@code bind} 也被不涉及骨骼的
     * 上下文（第一人称手臂、纸娃娃）调用。不绑定即按「找不到骨骼」返回 0。
     */
    public static void bindRuntime(ModelRuntime model) {
        runtime = model;
    }

    /** 本帧的主体态；{@code null} 或空串表示按未绑定处理。 */
    public static void bindControl(String name) {
        control = name == null ? "" : name;
    }

    /**
     * 控制器求值前绑定「本状态里的动画是不是都播完了」，供 {@code query.all_animations_finished} 读。
     *
     * <p>YSM 的语义（{@code BedrockAnimationController#updateState}）：状态里没有生效动画时 all/any 都为真，
     * 否则 all 要求每条都播完、any 只要有一条播完。
     */
    public static void bindControllerState(boolean allFinished, boolean anyFinished) {
        controllerAllFinished = allFinished ? 1.0F : 0.0F;
        controllerAnyFinished = anyFinished ? 1.0F : 0.0F;
    }

    /** 控制器之外的条件（主体态、手部层、物理）一律按 NaN：它们是另一套上下文，不该借到上一个控制器的结果。 */
    public static void clearControllerState() {
        controllerAllFinished = Float.NaN;
        controllerAnyFinished = Float.NaN;
    }

    /** {@code ctrl.hold(slot, filter)} / {@code ctrl.swing} / {@code ctrl.use} 的三档操作。 */
    public static final int CHECK_HOLD = 0;
    public static final int CHECK_SWING = 1;
    public static final int CHECK_USE = 2;

    /**
     * YSM {@code ControlBuiltins#itemCheck} 的移植：先按操作类型卡「正在挥 / 正在用」，再按
     * {@code empty} / {@code $id} / {@code #tag} / {@code :分类} 匹配手持物。
     *
     * <p>与 {@link ItemClassifier} 的差别是语义：那里给的是「按优先级排好的候选后缀」，这里问的是
     * 「这个选择器命中吗」，所以用单值分类而不是候选表。{@code :} 同时认内部分类与使用动画名
     * （YSM 的原式就是两者取或）。
     */
    public static float itemCheck(String slotName, String filter, int operation) {
        LocalPlayer p = player;
        if (p == null || slotName == null || filter == null || filter.isEmpty()) {
            return 0.0F;
        }
        EquipmentSlot slot = slotOf(slotName);
        if (slot == null) {
            return Float.NaN;
        }
        if (operation == CHECK_SWING && !isSwingingNow(p)) {
            return 0.0F;
        }
        if (operation == CHECK_USE && !p.isUsingItem()) {
            return 0.0F;
        }
        ItemStack stack = p.getItemBySlot(slot);
        if (stack.isEmpty()) {
            return "empty".equals(filter) ? 1.0F : 0.0F;
        }
        if (filter.length() < 2) {
            return 0.0F;
        }
        String value = filter.substring(1);
        return switch (filter.charAt(0)) {
            case '$' -> {
                Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                yield id != null && value.equals(id.toString()) ? 1.0F : 0.0F;
            }
            case '#' -> {
                try {
                    yield stack.is(TagKey.create(Registries.ITEM, Identifier.parse(value))) ? 1.0F : 0.0F;
                } catch (RuntimeException malformed) {
                    yield Float.NaN;
                }
            }
            case ':' -> {
                String classify = ItemClassifier.classify(stack);
                boolean match = value.equals(classify)
                        || value.equals(stack.getUseAnimation().name().toLowerCase(java.util.Locale.ROOT));
                yield match ? 1.0F : 0.0F;
            }
            default -> 0.0F;
        };
    }

    /** {@code query.equipped_item_any_tag(slot, tag...)}：任一标签命中即真。 */
    public static float equippedItemAnyTag(String slotName, List<String> tags) {
        return equippedItemTags(slotName, tags, false);
    }

    /** {@code query.equipped_item_all_tags(slot, tag...)}：全部标签都命中才真（SPM {@code EquippedItemAllTags:26-35}）。 */
    public static float equippedItemAllTags(String slotName, List<String> tags) {
        return equippedItemTags(slotName, tags, true);
    }

    /**
     * any / all 两种标签判定的共同实现：空槽一律假、槽名不认识返回 NaN、标签写错返回 NaN
     * （SPM 的 {@code getResourceLocation} 解析失败给 null，落到 Molang 就是「没有值」）。
     */
    private static float equippedItemTags(String slotName, List<String> tags, boolean all) {
        LocalPlayer p = player;
        if (p == null || tags == null || tags.isEmpty()) {
            return 0.0F;
        }
        EquipmentSlot slot = slotOf(slotName);
        if (slot == null) {
            return Float.NaN;
        }
        ItemStack stack = p.getItemBySlot(slot);
        if (stack.isEmpty()) {
            return 0.0F;
        }
        for (String tag : tags) {
            if (tag == null || tag.isEmpty()) {
                continue;
            }
            boolean match;
            try {
                match = stack.is(TagKey.create(Registries.ITEM, Identifier.parse(tag)));
            } catch (RuntimeException malformed) {
                return Float.NaN;
            }
            if (all && !match) {
                return 0.0F;
            }
            if (!all && match) {
                return 1.0F;
            }
        }
        return all ? 1.0F : 0.0F;
    }

    /**
     * {@code query.is_item_name_any(slot, name...)}：槽位里的物品 id 命中任一名字即真
     * （SPM {@code IsItemNameAny:17-37}、YSM {@code ControlBuiltins:116-136}）。
     *
     * <p>空槽返回假；名字解析不出来返回 NaN 而不是「不匹配」—— 写错 id 的模型该整条失效，
     * 静默判假会让作者以为条件写对了。
     */
    public static float isItemNameAny(String slotName, List<String> names) {
        LocalPlayer p = player;
        if (p == null || names == null || names.isEmpty()) {
            return 0.0F;
        }
        EquipmentSlot slot = slotOf(slotName);
        if (slot == null) {
            return Float.NaN;
        }
        ItemStack stack = p.getItemBySlot(slot);
        if (stack.isEmpty()) {
            return 0.0F;
        }
        Identifier actual = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (actual == null) {
            return 0.0F;
        }
        for (String name : names) {
            Identifier expected = Identifier.tryParse(name);
            if (expected == null) {
                return Float.NaN;
            }
            if (actual.equals(expected)) {
                return 1.0F;
            }
        }
        return 0.0F;
    }

    /**
     * {@code ysm.effect_level(id...)}：这些状态效果各自等级之和，没有的按 0 计（SPM {@code EffectLevel:24-59}）。
     *
     * <p>等级是 {@code amplifier + 1}（力量 I 的 amplifier 是 0）。SPM 对每个实参累加，
     * 解析不出来的 id 直接跳过而不是整条作废；只有「非生物实体」才返回 null，而这里只有玩家上下文。
     */
    public static float effectLevel(List<String> ids) {
        LocalPlayer p = player;
        if (p == null || ids == null || ids.isEmpty()) {
            return 0.0F;
        }
        float total = 0.0F;
        for (String id : ids) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            Identifier key = Identifier.tryParse(id);
            if (key == null) {
                continue;
            }
            MobEffectInstance instance = BuiltInRegistries.MOB_EFFECT.get(key).map(p::getEffect).orElse(null);
            if (instance != null) {
                total += instance.getAmplifier() + 1;
            }
        }
        return total;
    }

    /**
     * {@code ysm.perlin_noise(seed, x[, y[, z]])}：STB 的 3D Perlin 噪声，约 −1..1
     * （SPM {@code PerlinNoise:11-24} 直接调 {@code stb_perlin_noise3_seed(x, y, z, 0, 0, 0, seed)}）。
     *
     * <p>缺省的 y/z 取 0，与 SPM 一致；种子按 {@code (int)} 截断（SPM 的 {@code getAsInt} 同样是截断）。
     * 用 STB 而不是自己写一份：语料里的模型是按 STB 的输出调参的，换实现波形就对不上。
     */
    public static float perlinNoise(float seed, float x, float y, float z) {
        return STBPerlin.stb_perlin_noise3_seed(x, y, z, 0, 0, 0, (int) seed);
    }

    private static EquipmentSlot slotOf(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "head", "helmet" -> EquipmentSlot.HEAD;
            case "chest", "chestplate" -> EquipmentSlot.CHEST;
            case "legs", "leggings" -> EquipmentSlot.LEGS;
            case "feet", "boots" -> EquipmentSlot.FEET;
            case "mainhand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            default -> null;
        };
    }

    /** 正在挥动；26.3 只认渲染状态里的 {@code currentSwing}。 */
    private static boolean isSwingingNow(LocalPlayer p) {
        //#if MC >= 260300
        //$$ return avatar != null && avatar.currentSwing != null;
        //#else
        return p.swinging;
        //#endif
    }

    /**
     * 时间轴语句的写入侧。只认 {@code v.*} / {@code t.*} / {@code temp.*}，非有限值不落表 —— 一次 NaN
     * 会永久污染弹簧状态，而模型里 {@code v.skirt_vertical_P0dot = (input - P0)/v.time} 这类算式一旦
     * 拿到 NaN 就再也回不来。
     */
    public static void set(String name, float value) {
        Map<String, Float> table = variables;
        if (table == null || !Float.isFinite(value)) {
            return;
        }
        String key = variableKey(name);
        if (key != null) {
            table.put(key, value);
        }
    }

    /**
     * 竖直速度（格/秒）。站在地上时 {@code getDeltaMovement().y} 并不为 0（实测约 -0.078），
     * 直接乘 20 会得到一个恒定的负速度，把依赖它的弹簧（发丝、裙摆）永久推偏。
     * 所以照 SPM 的 {@code MovementQuery#getVerticalSpeed} 加一道 0.1 的门槛，低于它按 0 处理。
     */
    private static float verticalSpeed(LocalPlayer p) {
        double dy = p.getDeltaMovement().y;
        if (Math.abs(dy) > 0.1) {
            return (float) (dy * 20.0);
        }
        return (float) ((p.getY() - p.yo) * 20.0);
    }

    /** {@code query.position_delta(axis)}：相邻两帧之间的位移（格），轴越界按 Molang 未定义返回 NaN。 */
    public static float positionDelta(float axis) {
        int component = Math.round(axis);
        if (component < 0 || component > 2) {
            return Float.NaN;
        }
        float[] delta = framePositionDelta();
        return delta == null ? Float.NaN : delta[component];
    }

    /**
     * {@code query.position(axis)}：实体插值后的世界坐标（格），轴越界按 Molang 未定义返回 NaN
     * （SPM {@code Position:15-24} 的 default 分支返回 null）。
     *
     * <p>与 {@link #positionDelta} 的帧间差分不同，这是绝对坐标：模型拿它做「世界锚定」的骨骼
     * （语料里是无人机跟随物，{@code query.position(0) - ysm.second_order('DroneX', ...)}）。
     * 插值端点用 {@code xo/yo/zo}，与 SPM 一致。
     */
    public static float position(float axis) {
        LocalPlayer p = player;
        if (p == null) {
            return Float.NaN;
        }
        int component = Math.round(axis);
        if (component < 0 || component > 2) {
            return Float.NaN;
        }
        float partial = boatPartialTick();
        return switch (component) {
            case 0 -> (float) Mth.lerp(partial, p.xo, p.getX());
            case 1 -> (float) Mth.lerp(partial, p.yo, p.getY());
            default -> (float) Mth.lerp(partial, p.zo, p.getZ());
        };
    }

    /**
     * 查询量的**文本值**，给 {@code ==} / {@code !=} 的字符串字面量比较用（SPM 的 EQ/NEQ 按值类型分流）。
     *
     * <p>只有 SPM 里绑成字符串的查询才有文本值。返回 {@code null} 表示「这个量没有文本值」，调用方按
     * SPM 的 null 分支处理（EQ 假、NEQ 真）。数值量一律返回 null —— 它们该走数值比较。
     */
    public static String text(String name) {
        String query = name.startsWith("q.") ? "query." + name.substring(2) : name;
        return switch (query) {
            // compat 查询：SPM 在对应 mod 未安装时注册成空串（ParcoolCompatImpl:39、SlashBladeCompatImpl:34），
            // 语料里 {@code ctrl.parcool_state==''} 就是「没在跑酷」，按空串答才对得上
            case "ctrl.parcool_state", "ctrl.slashblade_animation" -> "";
            default -> null;
        };
    }

    public static float get(String name) {
        String key = variableKey(name);
        if (key != null) {
            Map<String, Float> table = variables;
            Float value = table == null ? null : table.get(key);
            return value == null ? 0.0F : value;
        }
        LocalPlayer p = player;
        if (p == null) {
            return Float.NaN;
        }
        // ctrl.idle / ctrl.run … 是「当前主体态是不是它」，同一个名字走一遍选中链即可
        if (name.startsWith("ctrl.")) {
            String state = control;
            if (state.isEmpty()) {
                return Float.NaN;
            }
            return state.equals(name.substring(5)) ? 1.0F : 0.0F;
        }
        // 官方资源大量写短名（q.vertical_speed / q.yaw_speed / q.ground_speed），与 query.* 同一个量
        String query = name.startsWith("q.") ? "query." + name.substring(2) : name;
        try {
            return switch (query) {
                case "ysm.has_helmet" -> has(p, EquipmentSlot.HEAD);
                case "ysm.has_chest_plate" -> has(p, EquipmentSlot.CHEST);
                case "ysm.has_leggings" -> has(p, EquipmentSlot.LEGS);
                case "ysm.has_boots" -> has(p, EquipmentSlot.FEET);
                case "ysm.has_mainhand" -> has(p, EquipmentSlot.MAINHAND);
                case "ysm.has_offhand" -> has(p, EquipmentSlot.OFFHAND);
                case "ysm.has_elytra" -> p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) ? 1.0F : 0.0F;
                case "ysm.armor_value" -> p.getArmorValue();
                case "ysm.food_level" -> p.getFoodData().getFoodLevel();
                case "ysm.hurt_time" -> p.hurtTime;
                case "ysm.air_supply" -> p.getAirSupply();
                case "ysm.frozen_ticks" -> p.getTicksFrozen();
                case "ysm.on_ladder" -> p.onClimbable() ? 1.0F : 0.0F;
                case "ysm.is_riptide" -> p.isAutoSpinAttack() ? 1.0F : 0.0F;
                case "ysm.is_sleep" -> p.getPose() == Pose.SLEEPING ? 1.0F : 0.0F;
                case "ysm.is_sneak" -> p.onGround() && p.getPose() == Pose.CROUCHING ? 1.0F : 0.0F;
                case "ysm.is_passenger" -> p.isPassenger() ? 1.0F : 0.0F;
                case "ysm.is_player" -> 1.0F;
                case "ysm.is_maid" -> 0.0F;
                case "ysm.eye_in_water" -> p.isUnderWater() ? 1.0F : 0.0F;
                case "ysm.arrow_count" -> p.getArrowCount();
                case "ysm.stinger_count" -> p.getStingerCount();
                //#if MC >= 260300
                //$$ // 26.3 把 swinging/swingTime/attackAnim 收进了 ArmedEntityRenderState：
                //$$ // currentSwing（hand + durationTicks）与 swingAnimation（0..1 进度）。
                //$$ // attackAnim 直接对应 swingAnimation；swingTime（已过 tick）用「进度 × 总时长」还原。
                //$$ case "ysm.swinging" -> avatar != null && avatar.currentSwing != null ? 1.0F : 0.0F;
                //$$ case "ysm.swing_time" -> avatar == null || avatar.currentSwing == null ? 0.0F
                //$$         : avatar.swingAnimation * avatar.currentSwing.durationTicks();
                //$$ case "ysm.attack_time" -> avatar == null ? 0.0F : avatar.swingAnimation;
                //#else
                case "ysm.swinging" -> p.swinging ? 1.0F : 0.0F;
                case "ysm.swing_time" -> p.swingTime;
                case "ysm.attack_time" -> p.attackAnim;
                //#endif
                case "ysm.mainhand_charged_crossbow" -> p.getMainHandItem().is(Items.CROSSBOW) ? 1.0F : 0.0F;
                case "ysm.offhand_charged_crossbow" -> p.getOffhandItem().is(Items.CROSSBOW) ? 1.0F : 0.0F;
                // 挥动的那只手（0=主手，1=副手）。原来读的是「正在使用的手」，语义不对。
                //#if MC >= 260300
                //$$ case "ysm.swinging_arm" -> avatar != null && avatar.currentSwing != null
                //$$         && avatar.currentSwing.hand() == InteractionHand.OFF_HAND ? 1.0F : 0.0F;
                //#else
                case "ysm.swinging_arm" -> p.swingingArm == InteractionHand.OFF_HAND ? 1.0F : 0.0F;
                //#endif
                // 视线角度进表达式前先取负（YSM/SPM/OSM 的 AnimatableEntity 都是
                // headPitch = -rawHeadPitch、netHeadYaw = -clamp(wrapDegrees(...), ±85)）：
                // 模型按「负 = 俯视」写单侧夹取（math.max(ysm.head_pitch,-45) 之类），
                // 少了这层负号俯视会一路顶上去而不被夹住。净头偏角同样夹在 ±85°。
                // AvatarRenderState 的 xRot/yRot 正是这两条公式的输入（xRot = 俯视为正的俯仰角，
                // yRot = wrapDegrees(头朝向 - 身体朝向)），与 {@link ModelRuntime} 的头部跟随层同一个量。
                // query.head_x_rotation / head_y_rotation 是同一对量的别名（SPM QueryBinding:59-60 与
                // YSMBinding:97-98 读的都是 netHeadYaw / headPitch）：x 轴旋转是偏航、y 轴旋转是俯仰，
                // 别按字面把 x 当俯仰。
                case "ysm.head_yaw", "query.head_x_rotation" -> avatar == null ? Float.NaN
                        : -Mth.clamp(Mth.wrapDegrees(avatar.yRot), -85.0F, 85.0F);
                case "ysm.head_pitch", "query.head_y_rotation" -> avatar == null ? Float.NaN : -avatar.xRot;
                case "ysm.block_light" -> light(p, LightLayer.BLOCK);
                case "ysm.sky_light" -> light(p, LightLayer.SKY);
                case "ysm.is_open_air" -> p.level().canSeeSky(p.blockPosition()) ? 1.0F : 0.0F;
                case "ysm.rendering_in_paperdoll", "ysm.rendering_in_inventory" -> 0.0F;
                case "ysm.person_view" -> Minecraft.getInstance().options.getCameraType().isFirstPerson() ? 0.0F : 1.0F;
                // 第一人称下要播「把身体藏起来」的动作（语料里是 ysm.first_person_mod_hide?2 这种位移）。
                // SPM FirstPersonModHide:15 判的是相机类型而不是渲染通道标志：全身第一人称模型也走
                // 玩家渲染器，只有相机在第一人称才需要藏。
                case "ysm.first_person_mod_hide" ->
                        Minecraft.getInstance().options.getCameraType().isFirstPerson() ? 1.0F : 0.0F;
                case "ysm.elytra_rot_z" -> elytraRotZ(p);

                // 控制器状态机里最常用的判定：本状态里那几条动画播完了没有
                case "query.all_animations_finished" -> controllerAllFinished;
                case "query.any_animation_finished" -> controllerAnyFinished;
                // 移动输入相对朝向的分解（YSM PositionComponent#relativeMoveInput）：竖直是前后、水平是左右
                case "ysm.input_vertical" -> moveInput(p, true);
                case "ysm.input_horizontal" -> moveInput(p, false);
                case "ysm.ground_speed2" -> horizontalSpeed(p);
                case "ysm.is_fishing" -> p.fishing != null && !p.fishing.isRemoved() ? 1.0F : 0.0F;

                case "query.ground_speed" -> horizontalSpeed(p);
                case "query.vertical_speed" -> verticalSpeed(p);
                case "query.yaw_speed" -> yawSpeed(p);
                case "query.health" -> p.getHealth();
                case "query.max_health" -> p.getMaxHealth();
                case "query.player_level" -> p.experienceLevel;
                case "query.time_of_day" -> p.level().getGameTime() % 24000L;
                case "query.is_on_fire" -> p.isOnFire() ? 1.0F : 0.0F;
                case "query.is_in_water" -> p.isInWater() ? 1.0F : 0.0F;
                case "query.is_in_water_or_rain" -> (p.isInWater() || p.level().isRainingAt(p.blockPosition())) ? 1.0F : 0.0F;
                case "query.is_on_ground" -> p.onGround() ? 1.0F : 0.0F;
                // 整个腾空期间都算「跳跃」，不区分上升/下降（YSM EntityBuiltins:125-132、
                // SPM QueryBinding:108 都是这个判据）。要求「正在上升」会让下落段的条件动画全部失效 ——
                // 默认模型的 swing:sword 用 v.jump 选跳跃挥剑姿势，只判上升就只剩起跳那一下。
                case "query.is_jumping" -> !p.getAbilities().flying && !p.isPassenger()
                        && !p.onGround() && !p.isInWater() ? 1.0F : 0.0F;
                case "query.is_sprinting" -> p.isSprinting() ? 1.0F : 0.0F;
                case "query.is_sneaking" -> p.isCrouching() ? 1.0F : 0.0F;
                case "query.is_swimming" -> p.isSwimming() ? 1.0F : 0.0F;
                case "query.is_sleeping" -> p.isSleeping() ? 1.0F : 0.0F;
                case "query.is_riding" -> p.isPassenger() ? 1.0F : 0.0F;
                case "query.is_using_item" -> p.isUsingItem() ? 1.0F : 0.0F;
                //#if MC >= 260300
                //$$ case "query.is_swinging" -> avatar != null && avatar.currentSwing != null ? 1.0F : 0.0F;
                //#else
                case "query.is_swinging" -> p.swinging ? 1.0F : 0.0F;
                //#endif
                case "query.is_first_person" -> Minecraft.getInstance().options.getCameraType().isFirstPerson() ? 1.0F : 0.0F;
                // 身体朝向（度）：x 是俯仰（正 = 低头）、y 是身体偏航。
                // 两条都照 SPM QueryBinding:87-88 的写法：x 走 getXRot(partial)（内部就是
                // Mth.lerp(partial, xRotO, getXRot())），y 走 Mth.lerp 后 wrapDegrees。
                case "query.body_x_rotation" -> p.getXRot(boatPartialTick());
                case "query.body_y_rotation" -> Mth.wrapDegrees(Mth.lerp(boatPartialTick(), p.yBodyRotO, p.yBodyRot));
                // 视线目标角（度）：x 俯仰、y 偏航，取的是头部朝向而不是身体朝向
                // （SPM QueryBinding:69-70、YSM EntityBuiltins:83-86 都读 getViewXRot/getViewYRot）
                case "query.eye_target_x_rotation" -> p.getViewXRot(boatPartialTick());
                case "query.eye_target_y_rotation" -> p.getViewYRot(boatPartialTick());
                // 世界时钟的绝对 tick（SPM QueryBinding:63 读 getDefaultClockTime）。
                // 26.2 没有 getDayTime，getDefaultClockTime 就是它的替代（维度默认时钟的总 tick）。
                case "query.time_stamp" -> (float) p.level().getDefaultClockTime();
                case "query.cape_flap_amount" -> capeFlapAmount(p);
                // 武器族：只看「手里是什么 + 玩家在做什么」，不碰 trident/lance/mace 的动作子状态
                // （那部分在 SPM 的 WeaponActionBridgeImpl 里是完整子系统，尚未移植，保持 NaN）
                case "ysm.weapon_type" -> weaponType(p);
                case "ysm.weapon_is_trident" -> p.getMainHandItem().is(Items.TRIDENT) ? 1.0F : 0.0F;
                case "ysm.weapon_is_mace" -> p.getMainHandItem().is(Items.MACE) ? 1.0F : 0.0F;
                case "ysm.weapon_is_spear", "ysm.weapon_is_lance" -> isSpear(p.getMainHandItem()) ? 1.0F : 0.0F;
                case "ysm.weapon_using" -> p.isUsingItem() ? 1.0F : 0.0F;
                case "ysm.weapon_riding" -> p.isPassenger() ? 1.0F : 0.0F;
                case "ysm.weapon_fall_flying" -> p.isFallFlying() ? 1.0F : 0.0F;
                case "ysm.weapon_speed" -> horizontalSpeed(p);
                case "ysm.trident_holding" -> p.getMainHandItem().is(Items.TRIDENT) ? 1.0F : 0.0F;
                case "ysm.trident_using" -> p.getMainHandItem().is(Items.TRIDENT) && p.isUsingItem() ? 1.0F : 0.0F;
                case "ysm.trident_throwing" -> p.getMainHandItem().is(Items.TRIDENT) && p.isUsingItem() ? 1.0F : 0.0F;
                case "ysm.trident_riptide" -> p.isAutoSpinAttack() ? 1.0F : 0.0F;
                case "ysm.lance_holding" -> isSpear(p.getMainHandItem()) ? 1.0F : 0.0F;
                case "ysm.lance_using" -> isSpear(p.getMainHandItem()) && p.isUsingItem() ? 1.0F : 0.0F;
                case "ysm.lance_riding" -> p.isPassenger() ? 1.0F : 0.0F;
                case "ysm.lance_fall_flying" -> p.isFallFlying() ? 1.0F : 0.0F;
                case "ysm.lance_speed" -> horizontalSpeed(p);
                case "ysm.mace_holding" -> p.getMainHandItem().is(Items.MACE) ? 1.0F : 0.0F;
                case "ysm.mace_fall_flying" -> p.isFallFlying() ? 1.0F : 0.0F;
                case "ysm.mace_riding" -> p.isPassenger() ? 1.0F : 0.0F;
                case "ysm.mace_vertical_speed" -> (float) (p.getDeltaMovement().y * 20.0);
                // 武器动作子状态：语义照 SPM 的 WeaponActionBridgeImpl（MIT），阈值常量一并沿用
                case "ysm.weapon_attacking" -> attackingMain(p) ? 1.0F : 0.0F;
                case "ysm.weapon_attack_ticks" -> attackingMain(p) ? swingTicks(p) : 0.0F;
                case "ysm.weapon_use_ticks" -> usingMain(p) ? p.getTicksUsingItem() : 0.0F;
                case "ysm.trident_attack" -> isTrident(p) && attackingMain(p) ? 1.0F : 0.0F;
                case "ysm.trident_use_ticks" -> isTrident(p) && usingMain(p) ? p.getTicksUsingItem() : 0.0F;
                case "ysm.trident_attack_ticks" -> isTrident(p) && attackingMain(p) ? swingTicks(p) : 0.0F;
                case "ysm.lance_charging" -> isSpear(p.getMainHandItem()) && usingMain(p) ? 1.0F : 0.0F;
                case "ysm.lance_jabbing" -> isSpear(p.getMainHandItem()) && attackingMain(p) && !lunging(p) ? 1.0F : 0.0F;
                case "ysm.lance_lunging" -> isSpear(p.getMainHandItem()) && lunging(p) ? 1.0F : 0.0F;
                case "ysm.lance_riding_charge" -> p.isPassenger() && usingMain(p) ? 1.0F : 0.0F;
                case "ysm.lance_use_ticks" -> isSpear(p.getMainHandItem()) && usingMain(p) ? p.getTicksUsingItem() : 0.0F;
                case "ysm.lance_attack_ticks" -> isSpear(p.getMainHandItem()) && attackingMain(p) ? swingTicks(p) : 0.0F;
                case "ysm.lance_charge_progress" -> chargeProgress(p);
                case "ysm.mace_falling" -> maceFalling(p) ? 1.0F : 0.0F;
                case "ysm.mace_can_smash" -> maceCanSmash(p) ? 1.0F : 0.0F;
                case "ysm.mace_smashing" -> maceCanSmash(p) && attackingMain(p) ? 1.0F : 0.0F;
                case "ysm.mace_wind_bursting" -> p.isIgnoringFallDamageFromCurrentImpulse() ? 1.0F : 0.0F;
                case "ysm.mace_attacking" -> isMace(p) && attackingMain(p) ? 1.0F : 0.0F;
                case "ysm.mace_fall_distance" -> (float) p.fallDistance;
                case "ysm.mace_attack_ticks" -> isMace(p) && attackingMain(p) ? swingTicks(p) : 0.0F;
                case "ysm.mace_smash_progress" -> maceCanSmash(p) ? Math.min(1.0F, (float) p.fallDistance / 3.0F) : 0.0F;
                // 船族：只取玩家骑的船；不在船上落 NaN，由通道走中性值
                case "ysm.boat_left_paddle" -> boatPaddle(p, AbstractBoat.PADDLE_LEFT);
                case "ysm.boat_right_paddle" -> boatPaddle(p, AbstractBoat.PADDLE_RIGHT);
                case "ysm.boat_left_rowing_time" -> boatRowingTime(p, AbstractBoat.PADDLE_LEFT);
                case "ysm.boat_right_rowing_time" -> boatRowingTime(p, AbstractBoat.PADDLE_RIGHT);
                case "ysm.boat_is_chest" -> boatIsChest(p);
                case "ysm.boat_is_raft" -> boatIsRaft(p);
                case "ysm.boat_body_offset_y" -> boatBodyOffsetY(p);
                case "ysm.boat_body_offset_z" -> boatBodyOffsetZ(p);
                case "ysm.boat_paddle_scale" -> boatPaddleScale(p);
                case "ysm.boat_chest_passenger_offset" -> -boatBodyOffsetZ(p);
                case "query.has_cape" -> p.isModelPartShown(PlayerModelPart.CAPE) ? 1.0F : 0.0F;
                default -> Float.NaN;
            };
        } catch (RuntimeException error) {
            return Float.NaN;
        }
    }

    /**
     * {@code ysm.bone_rot(name)}：骨骼姿态的欧拉角，**度**，X/Y 取负、Z 取正。
     *
     * <p>符号与 SPM 的 {@code BoneRotation:22-34} 一致 —— 骨骼空间本身是 X/Y 取负的（见
     * {@code ModelRuntime#localOf} 的采样），这里再翻一次正好回到作者写动画时用的角度。
     *
     * <p>取的是**上一帧**的完整姿态：表达式在采样过程中求值，本帧 {@code poses} 只填了一部分，
     * 读到哪根骨骼取决于层序；上一帧快照才是确定的，也与 SPM 的 PhysicsManager 一致。
     * 骨骼不存在（或不在渲染上下文里）返回 0，照 SPM {@code BoneParamFunction:24-26} 的 null 分支。
     */
    public static float boneRotation(int axis, String name) {
        ModelRuntime model = runtime;
        return model == null ? 0.0F : model.boneRotation(axis, name);
    }

    /** {@code ysm.bone_pivot_abs(name)}：骨骼 pivot 的绝对位置（模型像素），X 取负（SPM GpuRenderPath:906-910）。 */
    public static float bonePivotAbs(int axis, String name) {
        ModelRuntime model = runtime;
        return model == null ? 0.0F : model.bonePivotAbs(axis, name);
    }

    /** {@code ysm.bone_scale(name)}：骨骼当前缩放（SPM {@code BoneScale:21-34}）。 */
    public static float boneScale(int axis, String name) {
        ModelRuntime model = runtime;
        return model == null ? 0.0F : model.boneScale(axis, name);
    }

    /** 0=空手 1=三叉戟 2=矛/骑枪 3=重锤；编码由本仓库自定，YSM 原版语义未公开。 */
    private static float weaponType(LocalPlayer p) {
        ItemStack stack = p.getMainHandItem();
        if (stack.isEmpty()) {
            return 0.0F;
        }
        if (stack.is(Items.TRIDENT)) {
            return 1.0F;
        }
        if (isSpear(stack)) {
            return 2.0F;
        }
        return stack.is(Items.MACE) ? 3.0F : 0.0F;
    }

    private static boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && stack.is(net.minecraft.tags.ItemTags.SPEARS);
    }

    private static boolean isTrident(LocalPlayer p) {
        return p.getMainHandItem().is(Items.TRIDENT);
    }

    private static boolean isMace(LocalPlayer p) {
        return p.getMainHandItem().is(Items.MACE);
    }

    private static boolean usingMain(LocalPlayer p) {
        return p.isUsingItem() && p.getUsedItemHand() == InteractionHand.MAIN_HAND;
    }

    private static boolean attackingMain(LocalPlayer p) {
        //#if MC >= 260300
        //$$ return avatar != null && avatar.currentSwing != null
        //$$         && avatar.currentSwing.hand() == InteractionHand.MAIN_HAND;
        //#else
        return p.swinging && p.swingingArm == InteractionHand.MAIN_HAND;
        //#endif
    }

    private static float swingTicks(LocalPlayer p) {
        //#if MC >= 260300
        //$$ return avatar == null || avatar.currentSwing == null ? 0.0F
        //$$         : avatar.swingAnimation * avatar.currentSwing.durationTicks();
        //#else
        return p.swingTime;
        //#endif
    }

    /** 骑枪冲刺：速度够快且正在用/攻击/骑乘/滑翔（阈值沿用 SPM）。 */
    private static boolean lunging(LocalPlayer p) {
        float speed = horizontalSpeed(p) / 20.0F;
        return speed >= 0.35F
                && (usingMain(p) || attackingMain(p) || p.isPassenger() || p.isFallFlying());
    }

    private static float chargeProgress(LocalPlayer p) {
        if (!usingMain(p)) {
            return 0.0F;
        }
        int duration = p.getMainHandItem().getUseDuration(p);
        if (duration <= 0) {
            return 0.0F;
        }
        return Math.min(1.0F, p.getTicksUsingItem() / duration);
    }

    private static boolean maceFalling(LocalPlayer p) {
        return !p.onGround() && p.getDeltaMovement().y < -0.08 && p.fallDistance > 0.0F;
    }

    private static boolean maceCanSmash(LocalPlayer p) {
        return isMace(p) && maceFalling(p) && p.fallDistance >= 1.5F;
    }

    /** 胸船乘客整体后移 0.15 格；筏的座位高 0.8888889 倍、桨低 1 单位，偏移量照 SPM 的 YSMBinding（MIT）。 */
    private static final float BOAT_CHEST_BODY_Z_OFFSET = -0.15F * 16.0F;
    private static final float BOAT_RAFT_BODY_Y_OFFSET = -((0.8888889F - 1.0F / 3.0F) * 0.5625F * 16.0F + 1.0F);

    private static AbstractBoat boatOf(LocalPlayer p) {
        return p.getVehicle() instanceof AbstractBoat boat ? boat : null;
    }

    private static float boatPaddle(LocalPlayer p, int side) {
        AbstractBoat boat = boatOf(p);
        if (boat == null) {
            return Float.NaN;
        }
        return boat.getPaddleState(side) ? 1.0F : 0.0F;
    }

    private static float boatRowingTime(LocalPlayer p, int side) {
        AbstractBoat boat = boatOf(p);
        if (boat == null) {
            return Float.NaN;
        }
        return -boat.getRowingTime(side, boatPartialTick());
    }

    private static float boatIsChest(LocalPlayer p) {
        AbstractBoat boat = boatOf(p);
        if (boat == null) {
            return Float.NaN;
        }
        return boat instanceof AbstractChestBoat ? 1.0F : 0.0F;
    }

    private static float boatIsRaft(LocalPlayer p) {
        AbstractBoat boat = boatOf(p);
        if (boat == null) {
            return Float.NaN;
        }
        return boat instanceof Raft || boat instanceof ChestRaft ? 1.0F : 0.0F;
    }

    private static float boatBodyOffsetY(LocalPlayer p) {
        AbstractBoat boat = boatOf(p);
        if (boat == null) {
            return Float.NaN;
        }
        return boat instanceof Raft || boat instanceof ChestRaft ? BOAT_RAFT_BODY_Y_OFFSET : 0.0F;
    }

    private static float boatBodyOffsetZ(LocalPlayer p) {
        AbstractBoat boat = boatOf(p);
        if (boat == null) {
            return Float.NaN;
        }
        return boat instanceof AbstractChestBoat ? BOAT_CHEST_BODY_Z_OFFSET : 0.0F;
    }

    private static float boatPaddleScale(LocalPlayer p) {
        // SPM 里筏与船的桨缩放同为 1.0，仍需区分「在船上」，否则会落中性值 1.0 掩盖缺上下文
        return boatOf(p) == null ? Float.NaN : 1.0F;
    }

    /** 划桨插值按帧内进度取样，否则 20tps 下桨角只有每 tick 一跳。 */
    private static float boatPartialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    /**
     * {@code query.cape_flap_amount}：披风下摆角度归一化到 0..1（SPM {@code QueryBinding:238-257}）。
     *
     * <p>SPM 的算式是「原版披风 X 旋转 / 108 再夹到 0..1」：{@code (6 + capeLean/2 + capeFlap) / 108}，
     * 其中 capeLean 是披风相对身体的水平位移在身体朝向法线上的投影、capeFlap 是竖直位移，
     * 原版 {@code AvatarRenderer#extractCapeState} 就是这么算的。SPM 在 26.x 拿不到披风位置字段，
     * 把披风位置写死成 0，于是「位移」退化成实体绝对坐标（{@code 0 - lerp(xo, x)}）——
     * 离原点超过 1.5 格的玩家会直接顶到上限 1。26.2 的 {@code ClientAvatarState#getInterpolatedCloakX/Y/Z}
     * 仍然提供插值披风位置，所以这里按原版算式取真实位移，只保留 SPM 的归一化与夹取。
     *
     * <p>走路摆动项（{@code sin(walkDist * 6) * 32 * bob}）照 SPM 保留：站着不动时它恒为 0，
     * 走起来才让披风跟着步子抖。原版还会乘 {@code 1 - fallFlyingScale()}（滑翔时披风让位给鞘翅），
     * SPM 没做，这里同样不做 —— 模型问的是「披风在飘多少」，滑翔时正是最大。
     * 蹲下时竖直项 +25 是 SPM 保留的原版行为，照抄。
     */
    private static float capeFlapAmount(LocalPlayer p) {
        float partial = boatPartialTick();
        ClientAvatarState state = p.avatarState();
        double dx = state.getInterpolatedCloakX(partial) - Mth.lerp(partial, p.xo, p.getX());
        double dy = state.getInterpolatedCloakY(partial) - Mth.lerp(partial, p.yo, p.getY());
        double dz = state.getInterpolatedCloakZ(partial) - Mth.lerp(partial, p.zo, p.getZ());
        float bodyRot = Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot);
        float sin = Mth.sin(bodyRot * 0.017453292F);
        float cos = -Mth.cos(bodyRot * 0.017453292F);
        float flap = Mth.clamp((float) dy * 10.0F, -6.0F, 32.0F);
        float lean = Mth.clamp((float) (dx * sin + dz * cos) * 100.0F, 0.0F, 150.0F);
        // 走路摆动：SPM 的 fSin2 是「竖直位移 + 步幅正弦 × 上下颠簸幅度」
        flap += Mth.sin(state.getInterpolatedWalkDistance(partial) * 6.0F) * 32.0F
                * state.getInterpolatedBob(partial);
        if (p.isCrouching()) {
            flap += 25.0F;
        }
        return Mth.clamp((6.0F + lean / 2.0F + flap) / 108.0F, 0.0F, 1.0F);
    }

    /** {@code ysm.elytra_rot_z}：鞘翅下摆的 Z 旋转，**度**（SPM {@code YSMBinding:374-376} 对 {@code getRotZ} 取 toDegrees）。 */
    private static float elytraRotZ(LocalPlayer p) {
        return (float) Math.toDegrees(p.elytraAnimationState.getRotZ(boatPartialTick()));
    }

    private static float has(LivingEntity entity, EquipmentSlot slot) {
        return entity.getItemBySlot(slot).isEmpty() ? 0.0F : 1.0F;
    }

    private static float light(LocalPlayer entity, LightLayer layer) {
        BlockPos pos = entity.blockPosition();
        return entity.level().getBrightness(layer, pos);
    }

    private static boolean isLocalVariable(String name) {
        return name.startsWith("v.");
    }

    /**
     * 语句能写、表达式能读的变量名归一成表键；不是变量返回 {@code null}。
     *
     * <p>{@code v.*} 与 {@code t.*} / {@code temp.*} 在 SPM 里绑到同一个模型实例存储
     * （{@code PrimaryBinding:40-45}：{@code v}/{@code variable} 是 ScopedVariableBinding，
     * {@code t}/{@code temp} 是 TempVariableRegistry），生命周期一致，所以共用一张表、只按前缀区分名字。
     * {@code t.} 与 {@code temp.} 是同一个 registry 的两个拼写，归一到 {@code t.} 才不会各存一份。
     */
    private static String variableKey(String name) {
        // 先按前缀分流再小写：查询量（q.* / ysm.*）每帧要读上千次，不该为它们各造一个字符串
        if (isLocalVariable(name) || name.startsWith("t.")) {
            return name.toLowerCase(java.util.Locale.ROOT);
        }
        if (name.startsWith("temp.")) {
            return "t." + name.substring(5).toLowerCase(java.util.Locale.ROOT);
        }
        return null;
    }

    /**
     * {@code v.*} 是否已经被写过。Molang 的 {@code ??}（空值合并）靠它区分「未赋值」与「值为 0」：
     * 模型里 {@code v.player_size??1} 这类写法要求未赋值时取默认值，而直接读默认给 0 就永远拿不到 1。
     */
    public static boolean isDefined(String name) {
        String key = variableKey(name);
        if (key == null) {
            return true;
        }
        Map<String, Float> table = variables;
        return table != null && table.containsKey(key);
    }

    /**
     * 转向角速度（度/秒）：**身体**朝向的逐 tick 差分 ×20。
     *
     * <p>量的是 body yaw（YSM {@code PlayerStateTracker:151-153}、SPM {@code PlayerEntityFrameState:91-97}
     * 都是 {@code getYRot()}），不是渲染状态里的净头偏角 —— 后者是「头相对身体」的角度，站着不动只转视角
     * 也会剧烈变化，喂进 {@code v.L3}/{@code v.L4} 这两条高增益链（乘完再乘 0.1）会让发丝和尾巴一直甩。
     */
    private static float yawSpeed(LocalPlayer entity) {
        long tick = entity.level().getGameTime();
        if (tick == lastYawTick) {
            return lastYawSpeed;
        }
        float yaw = entity.getYRot();
        if (lastYawTick != Long.MIN_VALUE) {
            long elapsed = tick - lastYawTick;
            lastYawSpeed = elapsed > 0L
                    ? 20.0F * Mth.wrapDegrees(yaw - lastYaw) / elapsed
                    : 0.0F;
        }
        lastYaw = yaw;
        lastYawTick = tick;
        return lastYawSpeed;
    }

    /**
     * 帧间位移：SPM 的 {@code EntityFrameStateTracker#updatePosition} 取「当前帧插值位置 − 上一帧插值位置」，
     * 所以这里按 {@code (tick, partialTick)} 记帧键，同一帧里的多次取值复用同一份结果 —— 否则第二次起
     * 会算成 0，弹簧输入直接掉没。
     */
    private static float[] framePositionDelta() {
        LocalPlayer p = player;
        if (p == null) {
            return null;
        }
        double partial = boatPartialTick();
        long tick = p.level().getGameTime();
        long key = tick * 4096L + Math.round(partial * 4096.0F);
        if (key == lastPositionKey) {
            return lastPositionDelta;
        }
        double x = Mth.lerp(partial, p.xOld, p.getX());
        double y = Mth.lerp(partial, p.yOld, p.getY());
        double z = Mth.lerp(partial, p.zOld, p.getZ());
        if (lastPositionKey == Long.MIN_VALUE) {
            // 首帧没有上一帧可减，给 0：给整段位移会把弹簧一次性顶飞
            lastPositionDelta[0] = 0.0F;
            lastPositionDelta[1] = 0.0F;
            lastPositionDelta[2] = 0.0F;
        } else {
            lastPositionDelta[0] = (float) (x - lastPositionX);
            lastPositionDelta[1] = (float) (y - lastPositionY);
            lastPositionDelta[2] = (float) (z - lastPositionZ);
        }
        lastPositionX = x;
        lastPositionY = y;
        lastPositionZ = z;
        lastPositionKey = key;
        return lastPositionDelta;
    }

    /**
     * 移动输入相对朝向的分解（YSM {@code PositionComponent#relativeMoveInput}）：把「本 tick 走的位移方向」
     * 减去视线朝向，取余弦（前后）或正弦（左右），落在 −1..1。
     *
     * <p>用位移而不是按键状态：模型拿它判断「在往前走还是在倒退」，跳起、被推、坐船时都该跟着实际运动走。
     */
    private static float moveInput(LocalPlayer entity, boolean vertical) {
        double dx = entity.getX() - entity.xOld;
        double dz = entity.getZ() - entity.zOld;
        if (Math.sqrt(dx * dx + dz * dz) < 1.0E-4) {
            return 0.0F;
        }
        double moveAngle = Math.toDegrees(Math.atan2(dz, dx));
        double entityYaw = 90.0 - Mth.wrapDegrees(-entity.getYRot());
        double relative = Mth.wrapDegrees(moveAngle - entityYaw);
        return (float) (vertical ? Math.cos(Math.toRadians(relative)) : Math.sin(Math.toRadians(relative)));
    }

    private static float horizontalSpeed(LocalPlayer entity) {
        double dx = entity.getX() - entity.xOld;
        double dz = entity.getZ() - entity.zOld;
        return (float) Math.sqrt(dx * dx + dz * dz) * 20.0F;
    }
}
