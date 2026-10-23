package me.matl114.hacks;

import java.util.List;
import lombok.Getter;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.hacks.api.ModuleManager;
import me.matl114.hacks.modules.HackModules;
import me.matl114.hacks.modules.combat.*;
import me.matl114.utils.*;
import me.matl114.versioned.api.VItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import me.matl114.hacks.utils.EntityUtils;

public class CombatTasks {
    public static void init() {}

    private static final Minecraft mc = Minecraft.getInstance();

    public static boolean isHoldingWeapon(LocalPlayer player) {
        ItemStack itemInHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        return itemInHand != null && isWeaponForMCPlayer(itemInHand);
    }

    public static boolean isWeaponForMCPlayer(ItemStack itemStack) {

        var attr = itemStack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (attr != null && !attr.modifiers().isEmpty()) return true;
        var ench = itemStack.get(DataComponents.ENCHANTMENTS);

        if (ench != null
                && ((ItemStackUtils.getEnchantmentLevel(ench, Enchantments.SHARPNESS) > 0)
                        || (ItemStackUtils.getEnchantmentLevel(ench, Enchantments.SMITE) > 0))) {
            return true;
        }
        return false;
    }

    public static boolean notSuitableForAttack(ItemStack item) {
        return item.isEmpty()
                || (!ItemStackUtils.hasInPatch(item, DataComponents.ATTRIBUTE_MODIFIERS)
                        && (!VItem.getInstance().isWeapon(item)))
                || (VItem.getInstance().isNotAttackingTool(item));
    }

    @ApiMethod
    public static List<Entity> getBowAimableEntitiesForPlayer() {
        return getTargetSelector().getAimableEntities(true);
    }

    @ApiMethod
    public static boolean attackEntity(Player player, Entity target) {
        return attack.attackEntity(target);
    }

    // todo add Auto crystal
    // return whether the attack will execute delay
    // todo: add attack target render, render the attackTarget if attack is on, refresh every two ticks

    // todo fixfixfixfixfix
    public static Vec2 calculatePitchYawPredict(float velocity, Vec3 extraVector, Vec3 targetVec) {
        double extraVectorLen = extraVector.length();
        final float g = 0.05f;
        if (extraVectorLen > 10 || velocity > 10) {
            // tpBow case
            return EntityUtils.rotationToPitchYaw(targetVec.normalize());
        }
        // ordinary case
        double hDistance0 = targetVec.horizontalDistance();
        double hDistanceSq = hDistance0 * hDistance0;
        float velocitySq = velocity * velocity;
        float velocityPow4 = velocitySq * velocitySq;
        // fix: hDistance
        // 调整目标高度：y_adjusted = y - (h * deltaY / velocity)
        double adjustedY = targetVec.y - (hDistance0 * extraVector.y / velocity);
        Vec3 vecNorm = targetVec.normalize();
        // 代入修正后的y计算仰角
        Vec2 safeSolution = new Vec2(
                (float) -Math.toDegrees(Math.atan(
                        (velocitySq - Math.sqrt(velocityPow4 - g * (g * hDistanceSq + 2 * adjustedY * velocitySq)))
                                / (g * hDistance0))),
                (float) Math.toDegrees(Math.atan2(-vecNorm.x, vecNorm.z)));
        if (extraVectorLen < 1e-4) {
            return safeSolution;
        }
        final double tolerance = 1e-4;
        final int maxIter = 30;

        // 计算目标水平距离和方向
        double hDistance = Math.sqrt(targetVec.x * targetVec.x + targetVec.z * targetVec.z);
        Vec3 hDir = (hDistance > 1e-4)
                ? new Vec3(targetVec.x / hDistance, 0, targetVec.z / hDistance)
                : new Vec3(1, 0, 0); // 避免除零

        if (hDistance < 1e-4) {
            // 垂直射击情况
            return safeSolution;
        }

        // 初始化迭代变量
        double pitchRad = 0; // 初始俯仰角（弧度）
        double yawRad = 0; // 偏航角（弧度）
        double ex = extraVector.x;
        double ez = extraVector.z;
        double ey = extraVector.y;
        double hx = hDir.x;
        double hz = hDir.z;

        boolean converged = false;

        // 迭代求解
        for (int i = 0; i < maxIter; i++) {
            double cosPitch = Math.cos(pitchRad);
            double sinPitch = Math.sin(pitchRad);

            // 检查水平速度是否有效
            if (Math.abs(velocity * cosPitch) < 1e-5) {
                break; // 垂直发射情况
            }

            // 求解 λ (水平速度大小)
            double B = ex * hx + ez * hz;
            double C = (ex * ex + ez * ez) - (velocity * cosPitch) * (velocity * cosPitch);
            double discriminant = B * B - C;

            if (discriminant < 0) {
                break; // 无实数解
            }

            double lambda = B + Math.sqrt(discriminant); // 取正根
            if (lambda <= 1e-5) {
                break; // 无效解
            }

            // 求解偏航角 θ_y
            double cosYaw = (lambda * hx - ex) / (velocity * cosPitch);
            double sinYaw = (lambda * hz - ez) / (velocity * cosPitch);

            // 归一化处理
            double norm = Math.sqrt(cosYaw * cosYaw + sinYaw * sinYaw);
            if (norm < 1e-5) {
                break;
            }
            cosYaw /= norm;
            sinYaw /= norm;
            yawRad = Math.atan2(sinYaw, cosYaw);

            // 求解俯仰角 θ_p
            double t = hDistance / lambda;
            double u = (targetVec.y + 0.5 * g * t * t) * lambda / hDistance;
            u = (u - ey) / velocity;

            if (Math.abs(u) > 1.0) {
                break; // 超出可行域
            }

            double newPitchRad = Math.asin(u);

            // 检查收敛
            if (Math.abs(newPitchRad - pitchRad) < tolerance) {
                converged = true;
                pitchRad = newPitchRad;
                break;
            }
            pitchRad = newPitchRad;
        }

        // 返回有效解或安全解
        if (converged) {
            return new Vec2(
                    (float) -Math.toDegrees(pitchRad), // 转 Minecraft 俯仰角
                    (float) (Math.toDegrees(yawRad) - 90f) // 转 Minecraft 偏航角
                    );
        } else {
            return safeSolution;
        }
    }
    // todo: fix alllll of them

    ;
    // 道具锁人 使用弓箭相同的配置

    // todo: 自动搭路
    @ApiMethod
    @Getter
    public static final ModuleGroup moduleManager = new ModuleGroup("Combat");

    @Getter
    private static CombatExtra combatExtra;

    @Getter
    private static CombatManager combatManager;

    @Getter
    private static TargetSelector targetSelector;

    @Getter
    private static PositionPredict positionPredict;

    @Getter
    private static Attack attack;

    @Getter
    private static AttackAura attackAura;

    @Getter
    private static Criticals criticals;

    @Getter
    private static BowEnhance bowEnhance;

    @Getter
    private static ProjectileEnhance projectileEnhance;

    @Getter
    private static BowTp bowTp;

    @Getter
    private static CombatLog combatLog;

    @Getter
    private static AutoTotem autoTotem;

    @Getter
    private static TotemLog totemLog;

    @Getter
    private static SpearEnhance spearEnhance;

    @Getter
    private static SpearAttack spearAttack;

    @Getter
    private static Blink blink;

    @Getter
    private static BackTrack backTrack;

    @Getter
    private static AutoThrow autoThrow;

    @Getter
    private static PearlFly pearlFly;

    @Getter
    private static AutoCity autoCity;

    @Getter
    private static CrystalAura crystalAura;

    @Getter
    private static AutoWeb autoWeb;

    private static AutoTrap autoTrap;

    @Getter
    private static AnchorAura anchorAura;

    @Getter
    private static AntiReplenish antiReplenish;

    @Getter
    private static AntiCrystal antiCrystal;

    @Getter
    private static ElytraBot elytraBot;

    @ApiStatus.Experimental
    @Getter
    private static TransactionBlocker transactionBlocker;
    // todo: 带矛冲锋

    // todo antikb

    // todo
    // todo: tpBot
    // todo: Miss
    // todo: AntiMiss how
    //

    // todo: crystal
    private static void initModules(ModuleManager m) {
        combatExtra = new CombatExtra().register(m);
        combatManager = new CombatManager().register(m);
        targetSelector = new TargetSelector().register(m);
        positionPredict = new PositionPredict().register(m);
        attack = new Attack().register(m);
        attackAura = new AttackAura().register(m);
        criticals = new Criticals().register(m);
        bowEnhance = new BowEnhance().register(m);
        bowTp = new BowTp().register(m);
        projectileEnhance = new ProjectileEnhance().register(m);
        combatLog = new CombatLog().register(m);
        autoTotem = new AutoTotem().register(m);
        totemLog = new TotemLog().register(m);

        spearEnhance = new SpearEnhance().register(m);
        spearAttack = new SpearAttack().register(m);
        blink = new Blink().register(m);
        backTrack = new BackTrack().register(m);
        autoThrow = new AutoThrow().register(m);
        pearlFly = new PearlFly().register(m);
        autoCity = new AutoCity().register(m);
        crystalAura = new CrystalAura().register(m);
        autoWeb = new AutoWeb().register(m);
        autoTrap = new AutoTrap().register(m);
        anchorAura = new AnchorAura().register(m);
        antiReplenish = new AntiReplenish().register(m);
        antiCrystal = new AntiCrystal().register(m);
        elytraBot = new ElytraBot().register(m);
        // transactionBlocker = new TransactionBlocker().register(m);
    }

    static {
        moduleManager.registerFactories(CombatTasks::initModules);
        HackModules.registerModuleGroup(moduleManager);
    }
}
