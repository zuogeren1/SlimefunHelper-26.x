package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
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

/**
 * YSM 的 ysm.* / query.* 查询量。
 *
 * <p>名字与语义对齐 SPM 的 YSMBinding / QueryBinding（MIT，同版本目标）；那两张表本身是 YSM 官方行为的
 * 忠实移植，比从 wiki 反推可靠。取值靠每帧绑定的上下文（{@link #bind}）。
 *
 * <p>没有实现的量返回 NaN，与旧行为一致：未知量按 NaN 走比较为假、通道落中性值，
 * 不会把条件误判成真。武器的动作子状态来自 SPM 的 WeaponActionBridge 子系统，尚未移植，保持 NaN。
 */
public final class MolangVariables {

    private static volatile LocalPlayer player;
    private static volatile AvatarRenderState avatar;

    private MolangVariables() {}

    public static void bind(LocalPlayer localPlayer, AvatarRenderState state) {
        player = localPlayer;
        avatar = state;
    }

    public static float get(String name) {
        LocalPlayer p = player;
        if (p == null) {
            return Float.NaN;
        }
        try {
            return switch (name) {
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
                //$$ // 26.3 把实体上的 swinging/swingTime/attackAnim 收进了 ArmedEntityRenderState.currentSwing
                //$$ // （LivingEntity.SwingDescription(hand, animation, durationTicks)）：只有「在挥」能判，
                //$$ // 挥动进度/时长没有等价字段，仍然是 NaN。
                //$$ case "ysm.swinging" -> avatar != null && avatar.currentSwing != null ? 1.0F : 0.0F;
                //$$ case "ysm.swing_time" -> Float.NaN;
                //$$ case "ysm.attack_time" -> Float.NaN;
                //#else
                case "ysm.swinging" -> p.swinging ? 1.0F : 0.0F;
                case "ysm.swing_time" -> p.swingTime;
                case "ysm.attack_time" -> p.attackAnim;
                //#endif
                case "ysm.mainhand_charged_crossbow" -> p.getMainHandItem().is(Items.CROSSBOW) ? 1.0F : 0.0F;
                case "ysm.offhand_charged_crossbow" -> p.getOffhandItem().is(Items.CROSSBOW) ? 1.0F : 0.0F;
                case "ysm.swinging_arm" -> p.getUsedItemHand() == InteractionHand.MAIN_HAND ? 0.0F : 1.0F;
                case "ysm.head_yaw" -> avatar == null ? Float.NaN : avatar.yRot;
                case "ysm.head_pitch" -> avatar == null ? Float.NaN : avatar.xRot;
                case "ysm.block_light" -> light(p, LightLayer.BLOCK);
                case "ysm.sky_light" -> light(p, LightLayer.SKY);
                case "ysm.is_open_air" -> p.level().canSeeSky(p.blockPosition()) ? 1.0F : 0.0F;
                case "ysm.rendering_in_paperdoll", "ysm.rendering_in_inventory" -> 0.0F;
                case "ysm.person_view" -> Minecraft.getInstance().options.getCameraType().isFirstPerson() ? 0.0F : 1.0F;

                case "query.ground_speed" -> horizontalSpeed(p);
                case "query.vertical_speed" -> (float) (p.getDeltaMovement().y * 20.0);
                case "query.health" -> p.getHealth();
                case "query.max_health" -> p.getMaxHealth();
                case "query.player_level" -> p.experienceLevel;
                case "query.time_of_day" -> p.level().getGameTime() % 24000L;
                case "query.is_on_fire" -> p.isOnFire() ? 1.0F : 0.0F;
                case "query.is_in_water" -> p.isInWater() ? 1.0F : 0.0F;
                case "query.is_in_water_or_rain" -> (p.isInWater() || p.level().isRainingAt(p.blockPosition())) ? 1.0F : 0.0F;
                case "query.is_on_ground" -> p.onGround() ? 1.0F : 0.0F;
                case "query.is_jumping" -> !p.onGround() && p.getDeltaMovement().y > 0.0 ? 1.0F : 0.0F;
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
        //$$ return avatar == null || avatar.currentSwing == null ? 0.0F : avatar.currentSwing.durationTicks();
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

    private static float has(LivingEntity entity, EquipmentSlot slot) {
        return entity.getItemBySlot(slot).isEmpty() ? 0.0F : 1.0F;
    }

    private static float light(LocalPlayer entity, LightLayer layer) {
        BlockPos pos = entity.blockPosition();
        return entity.level().getBrightness(layer, pos);
    }

    private static float horizontalSpeed(LocalPlayer entity) {
        double dx = entity.getX() - entity.xOld;
        double dz = entity.getZ() - entity.zOld;
        return (float) Math.sqrt(dx * dx + dz * dz) * 20.0F;
    }
}
