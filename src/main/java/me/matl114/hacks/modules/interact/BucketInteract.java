package me.matl114.hacks.modules.interact;

import me.matl114.hacks.utils.EntityUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * 远程交互里「桶类物品对流体 / 方块」的目标感知（2026-10-11 加）。
 *
 * <p>为什么需要它：26.2 的 {@link BucketItem} <b>只实现 {@code use()}，从不覆写 {@code useOn()}</b> ——
 * 装 / 放流体完全靠服务端拿到包里的 yaw / pitch <b>自己重算一次 POV 射线</b>。
 * 而 {@code !!useitem pos} 给的朝向是「方块的<b>几何中心</b>」：水桶那次 clip 用 {@code Fluid.NONE} 会
 * <b>穿过流体</b>打到后面的方块，服务端算出的 {@code placePos = hitPos.relative(face)} 就落到别的格子上；
 * 空桶遇到流动水也会 MISS。
 *
 * <p>本类的做法：先在客户端按<b>与原版同参数</b>的 clip 试射，找出「真能命中目标格」的那个点，
 * 再把朝向改成它 —— 服务端用同一个朝向重算，就会得到同一个命中。
 * 细雪桶不同：{@link SolidBucketItem} 是 {@code BlockItem}，放置走 {@code useOn}，
 * 所以这里直接把构造好的 {@link BlockHitResult} 交给调用方去发 {@code useItemOn}。
 *
 * <p>落点一律取「面中心」（距方块中心恰好 0.5）：服务端 {@code handleUseItemOn} 会校验
 * {@code hitResult.location} 距方块中心各轴 {@code < 1.0000001}，超了直接丢包并打 warn。
 *
 * <p>注意：所有求解都<b>只回答「该朝哪看」</b>，不做发包；求解失败一律返回 {@code null}，
 * 由调用方退回原来的「转头 + useItem」行为，保证不回归。
 */
public final class BucketInteract {
    private BucketInteract() {}

    private static final Minecraft mc = Minecraft.getInstance();

    /** 细雪桶一类：{@code SolidBucketItem} 是 {@code BlockItem}，放置必须走 useItemOn。 */
    public static boolean isSolidBucket(ItemStack stack) {
        return stack.getItem() instanceof SolidBucketItem;
    }

    /** 空桶 / 水桶 / 岩浆桶 / 鱼桶 / 硫方块桶…（都靠 use() 让服务端自己重算射线）。 */
    public static boolean isFluidBucket(ItemStack stack) {
        return stack.getItem() instanceof BucketItem;
    }

    public static boolean shouldHandle(ItemStack stack) {
        return isFluidBucket(stack) || isSolidBucket(stack);
    }

    /**
     * 目标感知入口：目标是流体源且手上是空桶 → 按「舀」求解；否则按「把内容物放进这一格」求解。
     *
     * @return 命中结果；{@code null} = 看不见那个面 / 没有可用支撑面 → 调用方退回原行为
     */
    public static BlockHitResult resolve(Player player, ItemStack stack, BlockPos pos, double range) {
        if (mc.level == null) {
            return null;
        }
        if (isSolidBucket(stack)) {
            return findPlaceHit(player, pos, range);
        }
        if (isFluidBucket(stack) && stack.getItem() instanceof BucketItem bucket) {
            FluidState state = mc.level.getFluidState(pos);
            if (bucket.getContent() == Fluids.EMPTY && state.isSource()) {
                return findPickupHit(player, pos, range);
            }
            return findPlaceHit(player, pos, range);
        }
        return null;
    }

    /**
     * 「放」：服务端按 {@code placePos = hitPos.relative(face)} 决定落点，
     * 所以要让内容物落进 {@code pos}，必须命中 {@code pos.relative(face.getOpposite())} 这个邻块的、朝 {@code pos} 的那个面。
     * 六个方向逐一试射，取第一个「射线真的先打到它」的方向。
     */
    public static BlockHitResult findPlaceHit(Player player, BlockPos pos, double range) {
        if (mc.level == null) {
            return null;
        }
        for (Direction face : Direction.values()) {
            BlockPos support = pos.relative(face.getOpposite());
            if (mc.level.isEmptyBlock(support)) {
                continue; // 空块点不到
            }
            Vec3 point = Vec3.atCenterOf(support).relative(face, 0.5);
            BlockHitResult hit = clipTo(player, point, range, ClipContext.Fluid.NONE);
            if (hit != null && support.equals(hit.getBlockPos()) && hit.getDirection() == face) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 「舀」：原版 {@code BucketItem.use} 内部用的就是 {@code SOURCE_ONLY} 的 clip，这里同参数试射，
     * 并要求命中格就是目标那一格（流向不是源方块时原版本来就不给舀，不做放宽）。
     */
    public static BlockHitResult findPickupHit(Player player, BlockPos pos, double range) {
        if (mc.level == null) {
            return null;
        }
        BlockHitResult hit = clipTo(player, Vec3.atCenterOf(pos), range, ClipContext.Fluid.SOURCE_ONLY);
        if (hit != null && pos.equals(hit.getBlockPos())) {
            return hit;
        }
        for (Direction face : Direction.values()) {
            Vec3 point = Vec3.atCenterOf(pos).relative(face, 0.5);
            hit = clipTo(player, point, range, ClipContext.Fluid.SOURCE_ONLY);
            if (hit != null && pos.equals(hit.getBlockPos())) {
                return hit;
            }
        }
        return null;
    }

    /** 把「看向某点」换算成朝向（pitch / yaw）；点与眼睛重合时返回 null。 */
    public static Vec2 lookAt(Player player, Vec3 point) {
        Vec3 delta = point.subtract(player.getEyePosition());
        if (delta.lengthSqr() < 1.0E-8D) {
            return null;
        }
        return EntityUtils.rotationToPitchYaw(delta);
    }

    /** 从眼睛朝某点试射一次；返回 null = 没打到方块。 */
    private static BlockHitResult clipTo(Player player, Vec3 point, double range, ClipContext.Fluid fluid) {
        Vec3 eye = player.getEyePosition();
        Vec3 delta = point.subtract(eye);
        if (delta.lengthSqr() < 1.0E-8D) {
            return null;
        }
        Vec3 to = eye.add(delta.normalize().scale(Math.max(range, 1.0E-3D)));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, fluid, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }
}
