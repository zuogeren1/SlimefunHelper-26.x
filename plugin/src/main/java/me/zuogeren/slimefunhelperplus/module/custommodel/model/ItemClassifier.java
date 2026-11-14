package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ThrowablePotionItem;

/**
 * 把「手上拿着什么」翻译成 YSM 手部条件动画的后缀，调用方拼上 {@code hold_mainhand} / {@code swing} /
 * {@code use_mainhand} 后按返回顺序取第一个在模型里存在的片段。
 *
 * <p><b>优先级</b>（以 YSM 源码为准，wiki 与源码一致）：
 * <ol>
 *   <li>{@code :charged_crossbow} —— 主副手都有，{@code MainhandPredicate:43-45} /
 *       {@code OffhandPredicate:32-34}，条件是 {@code Items.CROSSBOW} 且 {@code CrossbowItem.isCharged}；</li>
 *   <li>{@code :fishing} —— <b>仅主手</b>，{@code MainhandPredicate:46-49}；</li>
 *   <li>{@code :empty} —— 空手，{@code ConditionalHold:73-75}，命中后不再往下走；</li>
 *   <li>{@code $<物品 id>} —— {@code ConditionalHold:76-84} 分派到 {@code :87-100}；</li>
 *   <li>{@code #<标签 id>} —— {@code ConditionalHold:102-113}；</li>
 *   <li>{@code :<内部分类>} —— {@code ConditionalHold:115-128} 先试 {@code InnerClassify}（铲子另有历史别名 {@code spade}，排在正名之后）；</li>
 *   <li>{@code :<使用动画名>} —— 同一段代码在内部分类之后才试 {@code ItemStack#getUseAnimation}。</li>
 * </ol>
 * 挥动与使用走的是同一套分派（{@code ConditionalSwing:69-82} / {@code ConditionalUse:69-82}），
 * 只是空手时返回空串而不是 {@code :empty}，所以 {@code :empty} 只对 hold 有意义。
 *
 * <p><b>26.2 的 API 现实</b>：{@code ItemUseAnimation} 的枚举值是 NONE / EAT / DRINK / BLOCK / BOW /
 * TRIDENT / CROSSBOW / SPYGLASS / TOOT_HORN / BRUSH / BUNDLE / SPEAR —— <b>没有 LANCE</b>，三叉戟归
 * {@code spear}；26.2 新增的原版长矛（{@code minecraft:*_spear}）走 {@code ItemTags.SPEARS} 归 {@code lance}，
 * 与 SPM 的 26.x 适配一致（{@code InnerClassify:46-57,72-90}）。物品标签用 {@code ItemStack#tags()}
 * （{@code TypedInstance} 的默认方法，转发到 {@code Holder#tags()}）。
 *
 * <p><b>照抄自 SPM</b>：内部分类的判定顺序与「一律用标签、不用 {@code instanceof}」来自
 * {@code InnerClassify.java:100-139}；{@code :charged_crossbow} 与 {@code :fishing} 的位置来自
 * {@code MainHandHoldPredicate.java:64-70}；抛竿判据（必须手持鱼竿）来自同文件 {@code :43-46}。
 * 26.2 已经没有 {@code SwordItem} / {@code PickaxeItem}（物品改成纯 {@code Item} + 组件），
 * 所以 YSM 那套 {@code instanceof} 分支在这里无法照搬。
 */
public final class ItemClassifier {

    /** 空手时 hold 的固定后缀；swing/use 没有这个分类，调用方查不到会自然回落到默认片段。 */
    private static final String EMPTY = ":empty";
    private static final String CHARGED_CROSSBOW = ":charged_crossbow";
    private static final String FISHING = ":fishing";

    private ItemClassifier() {
    }

    /**
     * 按 YSM 的优先级给出候选后缀，调用方拿它去拼 {@code hold_mainhand<后缀>} / {@code swing<后缀>} /
     * {@code use_mainhand<后缀>}。纯函数，不缓存、不写任何状态。
     */
    public static List<String> candidates(LocalPlayer player, InteractionHand hand) {
        List<String> out = new ArrayList<>(8);
        if (player == null) {
            return out;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            out.add(EMPTY);
            return out;
        }
        if (stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) {
            out.add(CHARGED_CROSSBOW);
        }
        if (hand == InteractionHand.MAIN_HAND && isFishing(player, stack)) {
            out.add(FISHING);
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null) {
            out.add("$" + id);
        }
        collectTags(stack, out);
        String inner = classify(stack);
        if (!inner.isEmpty()) {
            out.add(":" + inner);
            String legacy = legacyAlias(inner);
            if (!legacy.isEmpty()) {
                out.add(":" + legacy);
            }
        }
        ItemUseAnimation anim = stack.getUseAnimation();
        // 弓与十字弓的 use-anim 名和内部分类同名（bow / crossbow），不去重就会给出两个一样的后缀。
        if (anim != ItemUseAnimation.NONE && !anim.name().equalsIgnoreCase(inner)) {
            out.add(":" + anim.name().toLowerCase(Locale.US));
        }
        return out;
    }

    /**
     * 内部分类，顺序照 YSM {@code InnerClassify:21-62}。26.2 的物品不再按子类区分剑/镐，
     * 只能靠标签，所以这里全部走 {@code ItemTags}（同 SPM {@code InnerClassify:100-139}）。
     */
    public static String classify(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        if (stack.is(ItemTags.SWORDS)) {
            return "sword";
        }
        if (stack.is(ItemTags.AXES)) {
            return "axe";
        }
        if (stack.is(ItemTags.PICKAXES)) {
            return "pickaxe";
        }
        if (stack.is(ItemTags.SHOVELS)) {
            return "shovel";
        }
        if (stack.is(ItemTags.HOES)) {
            return "hoe";
        }
        if (stack.is(Items.SHIELD)) {
            return "shield";
        }
        if (stack.is(Items.CROSSBOW)) {
            return "crossbow";
        }
        if (stack.is(Items.BOW)) {
            return "bow";
        }
        if (stack.is(Items.FISHING_ROD)) {
            return "fishing_rod";
        }
        if (stack.is(Items.TRIDENT)) {
            return "spear";
        }
        if (stack.is(ItemTags.SPEARS)) {
            return "lance";
        }
        if (stack.getUseAnimation() == ItemUseAnimation.SPEAR) {
            return "lance";
        }
        if (stack.getItem() instanceof ThrowablePotionItem) {
            return "throwable_potion";
        }
        return "";
    }

    /**
     * 内部分类的历史别名：老模型把铲子写成 {@code spade}，片段名还留在
     * {@code hold_mainhand:spade} 这类位置上（SPM {@code InnerClassify#getLegacyAlias} 同样保留）。
     * 别名排在正名之后 —— 正名是现行写法，别名只用来兜住老模型。
     */
    private static String legacyAlias(String inner) {
        return "shovel".equals(inner) ? "spade" : "";
    }

    /**
     * 手持物的握持类别，决定挂点上的微调偏移（SPM {@code InnerClassify#getWeaponKind:72-90}）。
     *
     * <p>与 {@link #classify} 分开：那个是给动画片段名用的（三叉戟归 {@code spear}、原版长矛归
     * {@code lance}），这里是给挂点矩阵用的 —— 三叉戟/长矛/锤的模型重心与剑不同，
     * 用同一套偏移会让它们插进手里或者悬空。
     */
    public enum Grip {
        /** 剑、斧、镐、工具、空手：默认偏移。 */
        DEFAULT,
        TRIDENT,
        /** 原版长矛（{@code minecraft:*_spear}，使用动画为 SPEAR）。 */
        SPEAR,
        /** 模组长矛（{@code ItemTags.SPEARS}）。 */
        LANCE,
        MACE
    }

    /** 握持类别；空手与普通物品都是 {@link Grip#DEFAULT}。 */
    public static Grip grip(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Grip.DEFAULT;
        }
        if (stack.is(Items.TRIDENT)) {
            return Grip.TRIDENT;
        }
        if (isVanillaSpear(stack)) {
            return Grip.SPEAR;
        }
        if (stack.is(ItemTags.SPEARS)) {
            return Grip.LANCE;
        }
        if (stack.is(Items.MACE)) {
            return Grip.MACE;
        }
        return Grip.DEFAULT;
    }

    /**
     * 原版长矛：使用动画是 SPEAR，且 id 是 {@code minecraft:*_spear}
     * （SPM {@code InnerClassify:92-98}）。只判使用动画会把模组长矛也算进来。
     */
    private static boolean isVanillaSpear(ItemStack stack) {
        if (stack.getUseAnimation() != ItemUseAnimation.SPEAR) {
            return false;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && "minecraft".equals(id.getNamespace()) && id.getPath().endsWith("_spear");
    }

    private static boolean isFishing(LocalPlayer player, ItemStack stack) {
        // YSM 只判 player.fishing != null（MainhandPredicate:46），不看手上是不是鱼竿；
        // 照 SPM 收紧到鱼竿（MainHandHoldPredicate:43-46），否则切到别的物品也会播抛竿姿势。
        return player.fishing != null && !player.fishing.isRemoved() && stack.getItem() instanceof FishingRodItem;
    }

    private static void collectTags(ItemStack stack, List<String> out) {
        List<String> tags = new ArrayList<>(4);
        try {
            stack.tags().forEach(tag -> tags.add("#" + tag.location()));
        } catch (IllegalStateException unbound) {
            // Holder 的标签集在 BuiltInRegistries 冻结前是 null，tags() 会抛 "Tags not bound"；
            // 分类器跑在渲染路径上，少给候选可以，把异常抛给调用方不行。
            return;
        }
        // YSM 的标签优先级是模型里的声明顺序（ConditionalHold:111 按 tagTest 顺序 findFirst），
        // 这里拿不到模型上下文，只能按 id 排序，保证同一物品每次给出同一顺序。
        tags.sort(null);
        out.addAll(tags);
    }
}
