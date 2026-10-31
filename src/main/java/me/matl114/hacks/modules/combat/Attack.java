package me.matl114.hacks.modules.combat;

import com.google.common.base.Preconditions;
import com.google.common.collect.Streams;
import java.util.*;
import lombok.NonNull;
import lombok.With;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.PacketManager;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.*;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.move.ElytraExtra;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerInputManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.modules.move.RotationManager;
import me.matl114.hacks.modules.task.ServerFeature;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.OptionalPrimitive;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.config.WrapEnum;
import me.matl114.hacks.utils.enums.BypassMode;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.enums.LegalTargetingMode;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.entity.PlayerInputUtils;
import me.matl114.utils.render.RenderCollector;
import me.matl114.versioned.api.VItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import me.matl114.hacks.utils.EntityUtils;

public class Attack extends BaseModule {
    public static Attack INSTANCE;
    public final ModulePath attack = makePath(Configs.COMBAT_CONFIG, "att-bot");

    public Attack() {
        super("Attack");
        bindFlag(enable);
        INSTANCE = this;
    }

    public final FlagRef enable = flagBuilder(attack.add("always-att")).build();

    public final KeyBindRef hotkey = moduleEntry(
                    attack.add("always-att-hotkey"), new MultiKeyBind(), attack.add("always-att"))
            .build();

    //    public final FlagRef legalMode = flagBuilder(attack.add("legal-mode")).build();
    public final EnumRef<LegalTargetingMode> legalTargetingMode = builder(
                    attack.add("legal-targeting"), LegalTargetingMode.class)
            .defaultValue(LegalTargetingMode.MOVEMENT_POST)
            .build();

    public final NBTRef<OptionalPrimitive<Double>> tpRange = builder(
                    attack.add("tp-reach"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(true, NBTTypes.DOUBLE_TYPE, 0.0D))
            .build();

    public final NBTRef<OptionalPrimitive<Double>> maceHeight = builder(
                    attack.add("mace-height-multiply"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.DOUBLE_TYPE, 30.0d))
            .show(() -> !legalTargetingMode.get().isLegal())
            .build();

    public final FlagRef exactAttack = flagBuilder(attack.add("exact-tp"))
            .show(() -> !legalTargetingMode.get().isLegal() && tpRange.get().isPresent())
            .build();

    public final FlagRef postFix = builder(attack.add("attack-post-fix"), Boolean.class)
            .defaultValue(true)
            .show(() -> !legalTargetingMode.get().isLegal()
                    && legalTargetingMode.get().isIn(LegalTargetingMode.MOVEMENT_POST))
            .build();

    public final FlagRef autoAntiShield =
            flagBuilder(attack.add("auto-anti-shield")).build();

    public final NBTRef<OptionalPrimitive<Integer>> fastDestroyShield = builder(
                    attack.add("fast-destroy-shield"), OptionalPrimitive.INT_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.INT_TYPE, 1))
            .build();

    public final FlagRef autoSwap = flagBuilder(attack.add("attack-inv-swap")).build();

    public final FlagRef autoSelect =
            flagBuilder(attack.add("attack-select-best-weapon")).build();
    //
    public final FlagRef autoRelease =
            flagBuilder(attack.add("auto-handle-use-when-attack")).build();

    public final NBTRef<OptionalPrimitive<Double>> autoMaceSwap = builder(
                    attack.add("mace-swap"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.DOUBLE_TYPE, 6.0D))
            .build();

    public final NBTRef<OptionalPrimitive<WrapEnum<BypassMode>>> maceFixMode = builder(
                    attack.add("mace-hit-fix-mode"), OptionalPrimitive.configEnum(BypassMode.class))
            .defaultValue(new OptionalPrimitive<>(
                    false, NBTTypes.CONFIG_ENUM_TYPE.cast(), new WrapEnum<>(BypassMode.NO_BYPASS)))
            .build();

    // todo: ghosthand mace enchantment

    public final EnumRef<GhostHandMode> ghostHand = builder(attack.add("ghost-hand-mode"), GhostHandMode.class)
            .defaultValue(GhostHandMode.INV_SWAP)
            .build();

    public final FlagRef swingHand =
            builder(attack.add("swing-hand"), Boolean.class).defaultValue(true).build();

    public final FlagRef renderAttackTarget =
            flagBuilder(attack.add("render-target")).build();

    public final NBTRef<WrapColor> renderAttackColor = builder(attack.add("render-target-color"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.GREEN))
            .build();

    public boolean canUseTp() {
        return tpRange.get().positive();
    }

    public boolean canUseMaceTp() {
        return tpRange.get().positive();
    }

    @Override
    public void registerAll() {
        super.registerAll();
        // should be in high priority
        registerListener(Listener.getAttackAction(), this::onAttack, -999);
        registerListener(Listener.getPostGameTick(), this::updateRenderCollector);
        registerListener(RenderListener.getRender3DEvent(), this::onRenderTarget);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundAttackPacket.class), this::handleMaceAttack);
    }

    public boolean delayAttacking = false;

    public void onAttack(Event<HitResult> hitResult) {
        if (hitResult.isCancelled()) return;
        if (enable.get()) {
            Player player = mc.player;
            if (player != null && mc.level != null) {
                if (tryAttack(false)) {
                    mc.missTime = 1;
                    hitResult.cancel();
                } else if (hitResult.context().getType() == HitResult.Type.ENTITY) {
                    // if target at an Entity but we didn't attack it, then it should be cancelled
                    mc.missTime = 0;
                    hitResult.cancel();
                }
            }
        }
    }

    public int getModePredictTicks() {
        if (mc.player != null
                && mc.player.isFallFlying()
                && willUseMaceAttack()
                && shouldUseMaceFix()
                && shouldUseDelayMovementAttackMaceFix()) {
            return 1;
        }
        return 0;
    }

    private void updateRenderCollector() {
        targetCollector.clear();
        if (!enable.get() || mc.player == null || !renderAttackTarget.get()) return;
        if (mc.player.isUsingItem()) {
            if (mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) return;
            if (mc.player.getActiveItem().getItem() instanceof ProjectileWeaponItem) return;
        }
        if (mc.hitResult.getType() != HitResult.Type.MISS
                && CombatTasks.notSuitableForAttack(mc.player.getMainHandItem())) return;

        Entity lastTickTarget = CombatTasks.getTargetSelector().searchAttackEntity(getTpSelectRange(), true);

        if (!EntityUtils.isEntityValid(lastTickTarget)) {
            return;
        }
        float dist = lastTickTarget.distanceTo(mc.player);
        float opacity = Math.min(0.6F, 0.10F + dist * 0.02F);
        targetCollector.submit(
                lastTickTarget,
                ColorUtils.withAlpha(renderAttackColor.get().color(), opacity).getRGB());
    }

    public void onRenderTarget(Event<Render3D> stackE) {
        if (!enable.get() || !renderAttackTarget.get() || mc.player == null) return;
        RenderUtils.startDrawVirtual(stackE.context.stack());
        try {
            targetCollector.render3D(stackE.context.stack(), stackE.context.partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(stackE.context.stack());
        }
    }

    private final RenderCollector<Entity> targetCollector =
            RenderCollectors.createEntityBoxCollector(false, true, false);

    public List<Entity> getCurrentRangeEntities() {
        return CombatTasks.getTargetSelector().getAttackableEntities(getTpSelectRange());
    }

    public Entity getCurrentSelectTarget(boolean auto) {
        return CombatTasks.getTargetSelector().searchAttackEntity(getTpSelectRange(), auto, getModePredictTicks());
    }

    // the attack return value of whether it needs cooldown, for delayMovement attacking
    public boolean tryAttack(boolean auto) {
        if (mc.player == null) return false;
        Entity entity = getCurrentSelectTarget(auto);
        if (entity != null) {
            return attackEntity(entity, createAttackSettings());
        }
        return false;
    }

    public double getTpSelectRange() {
        return CombatTasks.getCombatExtra().getAttackRange()
                + (canUseTp() ? Math.max(0.0d, tpRange.get().getValue()) : 0.0D);
    }

    private static boolean canEntityUseShieldBlockMe(LivingEntity target, Player player) {
        return true; // player.getEyePos().subtract(target.getEyePos()).dotProduct(target.getRotationVector()) > 0;
    }

    private static IndexEntry<ItemStack> findAntiShieldWeapon(AttackSettings settings) {
        return InventoryUtils.findPlayerItem(
                (ex) -> VItem.getInstance().isAxe(ex), settings.ghostHandMode().getSearchSize(false), false, false);
    }

    public AttackSettings createAttackSettings() {
        boolean useTp = canUseTp();
        boolean maceSwap = autoMaceSwap.get().isPresent()
                && PlayerStateManager.INSTANCE.fallDistance
                        >= autoMaceSwap.get().getValue();
        boolean invSwap = autoSwap.get() && !ServerFeature.INSTANCE.attackStrengthUpdateWithHand.get();
        boolean selectWeapon = autoSelect.get();
        boolean antiShield = autoAntiShield.get();
        boolean useAttack = autoRelease.get() && mc.player.isUsingItem();
        boolean elytraSwitch = shouldUseMaceFix()
                && shouldUseDelayMovementAttackMaceFix()
                && PlayerStateManager.INSTANCE.fallDistance > 1.5
                && willUseMaceAttack(maceSwap);
        boolean legal = legalTargetingMode.get().isLegal();
        boolean criticalSprint = !legal && mc.player.isSprinting();
        boolean maceVClip = canUseMaceTp() && !legal;
        if (maceVClip
                && autoMaceSwap.get().isPresent()
                && maceHeight.get().getValue() >= autoMaceSwap.get().getValue()) {
            maceSwap = true;
        }
        return new AttackSettings(
                useTp,
                maceSwap,
                invSwap,
                selectWeapon,
                antiShield,
                fastDestroyShield.get().asOptional(),
                useAttack,
                elytraSwitch,
                criticalSprint,
                maceVClip,
                swingHand.get(),
                ghostHand.get());
    }

    public static boolean shouldUseAntiShield(Entity target) {
        return shouldUseAntiShield(target, INSTANCE.createAttackSettings());
    }

    private static boolean shouldUseAntiShield(Entity target, AttackSettings settings) {
        return target instanceof LivingEntity lv
                && lv.isUsingItem()
                && lv.getUseItem().getItem() instanceof ShieldItem sh
                && canEntityUseShieldBlockMe(lv, mc.player)
                && findAntiShieldWeapon(settings) != null;
    }

    private static double applyArmorReduceOn(ItemStack stack, double finalDamage, LivingEntity target) {
        var context = DamageUtils.createAssumeProtectionFourContext(target);
        return DamageUtils.getFinalAttackDamageAgainst(mc.player, stack, (float) finalDamage, context);
    }

    @NotNull
    public static IndexEntry<ItemStack> selectBestWeapon(AttackSettings attackSettings, @Nullable Entity target) {
        IndexEntry<ItemStack> invResult;
        if (attackSettings.antiShieldSwap()
                && shouldUseAntiShield(target)
                && (invResult = findAntiShieldWeapon(attackSettings)) != null) {
            return invResult;
        }
        if (attackSettings.fastBreakShieldSwap().isPresent()
                && !ServerFeature.INSTANCE.attributeSwap.get()
                && target instanceof LivingEntity livingTarget
                && livingTarget.isUsingItem()
                && livingTarget.getActiveItem().getItem() instanceof ShieldItem
                && isWithinShieldBlockingAngle(livingTarget, mc.player)
                && (invResult = InventoryUtils.findBestPlayerItem(
                                (stack) -> {
                                    if (!VItem.getInstance().isWeapon(stack)
                                            || VItem.getInstance().isAxe(stack)) {
                                        return null;
                                    }
                                    double baseDamage = AttributeUtils.getAttributeWith(
                                                    mc.player, Map.of(EquipmentSlot.MAINHAND, stack))
                                            .getValue(Attributes.ATTACK_DAMAGE);
                                    return baseDamage >= 15.0D ? baseDamage : null;
                                },
                                attackSettings.ghostHandMode().getSearchSize(false),
                                false,
                                false))
                        != null) {
            return invResult;
        }
        if (attackSettings.maceSwap()
                && target instanceof LivingEntity lv
                && (invResult = InventoryUtils.findBestPlayerItem(
                                (ex) -> {
                                    if (ex.getItem() == Items.MACE) {
                                        return applyArmorReduceOn(
                                                ex,
                                                DamageUtils.getRealAttackDamage(
                                                        mc.player,
                                                        lv,
                                                        ex,
                                                        PlayerStateManager.INSTANCE.fallDistance,
                                                        PlayerStateManager.INSTANCE.getTicksSinceLastAttack(),
                                                        ServerFeature.INSTANCE.attributeSwap.get()),
                                                lv);
                                    }
                                    return null;
                                },
                                attackSettings.ghostHandMode().getSearchSize(false),
                                false,
                                false))
                        != null) {
            return invResult;
        } else if (attackSettings.invSwap()
                && target instanceof LivingEntity lv
                && (invResult = InventoryUtils.findBestPlayerItem(
                                (ex) -> {
                                    if (VItem.getInstance().isSpear(ex)) return null;
                                    if (VItem.getInstance().isWeapon(ex)) {
                                        Integer damageCost = VItem.getInstance().getAttackDurabilityCost(ex);
                                        return damageCost == null
                                                ? null
                                                : -((double) damageCost * 1E8)
                                                        + applyArmorReduceOn(
                                                                ex,
                                                                DamageUtils.getRealAttackDamage(
                                                                        mc.player,
                                                                        lv,
                                                                        ex,
                                                                        PlayerStateManager.INSTANCE.fallDistance,
                                                                        PlayerStateManager.INSTANCE
                                                                                .getTicksSinceLastAttack(),
                                                                        ServerFeature.INSTANCE.attributeSwap.get()),
                                                                lv);
                                    }
                                    return null;
                                },
                                attackSettings.ghostHandMode().getSearchSize(false),
                                false,
                                false))
                        != null) {
            return invResult;
        } else if (attackSettings.selectWeapon()
                && !mc.player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()
                && target instanceof LivingEntity lv
                && (invResult = InventoryUtils.findBestPlayerItem(
                                (ex) -> {
                                    if (VItem.getInstance().isSpear(ex)) return null;
                                    if (ex.is(mc.player
                                            .getItemInHand(InteractionHand.MAIN_HAND)
                                            .getItem())) {
                                        return applyArmorReduceOn(
                                                ex,
                                                DamageUtils.getRealAttackDamage(
                                                        mc.player,
                                                        lv,
                                                        ex,
                                                        PlayerStateManager.INSTANCE.fallDistance,
                                                        PlayerStateManager.INSTANCE.getTicksSinceLastAttack(),
                                                        ServerFeature.INSTANCE.attributeSwap.get()),
                                                lv);
                                    }
                                    return null;
                                },
                                attackSettings.ghostHandMode().getSearchSize(false),
                                false,
                                false))
                        != null) {
            return invResult;
        }
        return InventoryUtils.getSelectedItem();
    }

    public static void attackWithSettings(Player player, Entity target, AttackSettings attackSettings) {
        PlayerInputUtils.Input input = null;
        if (player.isPassenger()) {
            input = PlayerInputUtils.of(mc.player);
            if (input.hasWASDMovement()) {
                var re =
                        input.clone().forward(false).backward(false).left(false).right(false);
                re.sendPlayerInputAsRiding();
            } else {
                input = null;
            }
        }
        IndexEntry<ItemStack> invResult = selectBestWeapon(attackSettings, target);
        Runnable callback = InvExtra.INSTANCE.swapItemToHand(invResult.index(), false, attackSettings.ghostHandMode());
        attackWithCritic(player, target, attackSettings.criticalSprint(), attackSettings.swingHand());
        Optional<Integer> fastDestroyShield = attackSettings.fastBreakShieldSwap();
        if (fastDestroyShield.isPresent()
                && player.getAttributeValue(Attributes.ATTACK_DAMAGE) >= 15.0D
                && !VItem.getInstance().isAxe(invResult.val())
                && target instanceof LivingEntity livingTarget
                && livingTarget.isUsingItem()
                && livingTarget.getActiveItem().getItem() instanceof ShieldItem
                && isWithinShieldBlockingAngle(livingTarget, player)) {
            for (int i = 0; i < fastDestroyShield.get(); i++) {
                attackWithCritic(player, target, attackSettings.criticalSprint(), attackSettings.swingHand());
            }
        }
        if (callback != null) {
            callback.run();
        }
        if (input != null) {
            input.sendPlayerInputAsRiding();
        }
    }

    private static boolean isWithinShieldBlockingAngle(LivingEntity target, Entity attacker) {
        // Vanilla shield blocking uses horizontal source direction and head yaw.
        // The shield's default blocking angle is 90 degrees.
        Vec3 directionToAttacker = attacker.position()
                .subtract(target.position())
                .multiply(1.0D, 0.0D, 1.0D)
                .normalize();
        Vec3 horizontalView = Vec3.directionFromRotation(0.0F, target.getYHeadRot());
        return horizontalView.dot(directionToAttacker) >= 0.0D;
    }

    @ApiMethod
    public static void attackWithCritic(Player player, Entity target, boolean criticSprint) {
        attackWithCritic(player, target, criticSprint, true);
    }

    @ApiMethod
    public static void attackWithCritic(Player player, Entity target, boolean criticSprint, boolean swing) {
        //        if (criticSprint) {
        //            mc.getConnection()
        //                    .sendPacket(new ClientCommandC2SPacket(player,
        // ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        //        }
        mc.gameMode.attack(mc.player, target);
        if (swing) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        // we use event to handle shield predict

    }
    // return if it is a delay attack
    public boolean attackEntity(Entity target) {
        return attackEntity(target, createAttackSettings());
    }

    public boolean attackEntity(Entity target, AttackSettings settings) {
        if (legalTargetingMode.get().isLegal()) {
            return processLegalAttack(target, settings);
        } else {
            return processIllegalAttack(target, settings);
        }
    }

    public boolean willUseMaceAttack() {
        return willUseMaceAttack(autoMaceSwap.get().isPresent()
                && autoMaceSwap.get().getValue() <= PlayerStateManager.INSTANCE.fallDistance);
    }

    public boolean willUseMaceAttack(boolean autoMace) {
        return mc.player.getMainHandItem().getItem() instanceof MaceItem mace
                || (autoMace
                        && InventoryUtils.findPlayerItem(
                                        (ex) -> ex.getItem() == Items.MACE,
                                        ghostHand.get().getSearchSize(false),
                                        false,
                                        false)
                                != null);
    }

    private boolean processLegalAttack(Entity target, AttackSettings settings) {
        var player = mc.player;
        if (player == null) return false;
        // todo: pitch yaw fix;
        // do not add mace or tp attack in legal mode

        // mace attack, use item attack, need, delay
        return switch (legalTargetingMode.get()) {
            case DELAY_MOVEMENT -> processDelayMovementAttack(target, settings, true);
            case MOVEMENT_POST -> processDelayMovementAttack(target, settings, false);
            case LEGACY_SLIENT_ROT -> processLegacySnapAttack(target, settings);
            case NONE -> {
                attackWithSettings(mc.player, target, settings);
                yield false;
            }
        };
    }

    private boolean processDelayMovementAttack(Entity target, AttackSettings settings, boolean attackBeforeMovement) {
        ElytraExtra elytraExtra = MovTasks.getElytraExtra();
        double attackRange = CombatExtra.INSTANCE.getAttackAtTargetRange(target);
        attackBeforeMovement = attackBeforeMovement && !settings.useAttack();

        boolean canDirectlyHit = RaycastUtils.canRaycastHit(
                mc.player,
                PlayerStateManager.INSTANCE.lastPitch,
                PlayerStateManager.INSTANCE.lastYaw,
                target,
                attackRange);
        if (settings.isNoDelay() && canDirectlyHit) {
            attackWithSettings(mc.player, target, settings);
            return false;
        }

        if (settings.elytraDelaySwitch()) {
            if (elytraExtra.isThisTickGlideMovementServerSideGlide()) {
                attackBeforeMovement = true;
            } else if (!elytraExtra.hasPendingFallFlyingReset()) {
                elytraExtra.requestManualArmorSwapAndResetFallFlying(20);
                attackBeforeMovement = false;
            }
        }

        Vec3 precomputedTeleportTarget = null;
        Vec3 playerPosition = mc.player.position();
        if (!TargetSelector.INSTANCE.isWithinAttackRange(playerPosition, target, attackRange)) {
            if (tpRange.get().positive()) {
                precomputedTeleportTarget =
                        MovTasks.tpAttackSearch(playerPosition, target.getBoundingBox(), attackRange, 9.9, 1).stream()
                                .findFirst()
                                .orElse(null);
            }
            if ((precomputedTeleportTarget == null
                            || !TargetSelector.INSTANCE.isWithinAttackRange(
                                    precomputedTeleportTarget, target, attackRange))
                    && (!settings.elytraDelaySwitch() || elytraExtra.delayQueue.isEmpty())) {
                return true;
            }
        }

        if (attackBeforeMovement) attackWithSettings(mc.player, target, settings);

        delayAttacking = true;
        DelayMovementAttackTask task = new DelayMovementAttackTask(
                target, settings, attackRange, settings.elytraDelaySwitch(), playerPosition, precomputedTeleportTarget);
        if (!task.submit()) {
            task.finish();
            return true;
        }
        if (settings.useAttack()) MovTasks.getNoSlowDown().setPreAttackUseTick();
        return true;
    }

    private final class DelayMovementAttackTask {
        private final Entity target;
        private final AttackSettings settings;
        private final double attackRange;
        private final boolean useMaceAttack;
        private final Vec3 precomputedFromPosition;
        private final Vec3 precomputedTeleportTarget;
        private Vec3 originalPosition;
        private Vec3 teleportedPosition;
        private Vec3 velocity;
        private boolean distancePassAttack = true;
        private boolean runThisTick = true;
        private boolean finished;

        private DelayMovementAttackTask(
                Entity target,
                AttackSettings settings,
                double attackRange,
                boolean useMaceAttack,
                Vec3 precomputedFromPosition,
                Vec3 precomputedTeleportTarget) {
            this.target = target;
            this.settings = settings;
            this.attackRange = attackRange;
            this.useMaceAttack = useMaceAttack;
            this.precomputedFromPosition = precomputedFromPosition;
            this.precomputedTeleportTarget = precomputedTeleportTarget;
        }

        private boolean submit() {
            runThisTick = true;
            distancePassAttack = true;
            return RotationManager.INSTANCE.request(
                    RotationManager.ofEntity(() -> runThisTick && distancePassAttack ? target : null)
                            .preTick(this::preTick)
                            .postTick(this::postTick));
        }

        private void preTick() {
            LocalPlayer player = mc.player;
            ElytraExtra elytraExtra = MovTasks.getElytraExtra();
            // Movement based attacks must wait until the server-side glide transition has passed.
            if (settings.elytraDelaySwitch() && elytraExtra.isThisTickGlideMovementServerSideGlide()) {
                runThisTick = false;
                return;
            }

            velocity = player.getDeltaMovement();
            originalPosition = player.position();
            Vec3 rangePosition = originalPosition;
            if (!TargetSelector.INSTANCE.isWithinAttackRange(rangePosition, target, attackRange)) {
                Vec3 teleportTarget = precomputedTeleportTarget;
                if (teleportTarget != null
                        && originalPosition.distanceToSqr(precomputedFromPosition) <= 1E-7
                        && teleportTarget.distanceToSqr(originalPosition) > 1E-7) {
                    teleportedPosition = teleportTarget;
                    player.setPos(teleportTarget.add(0, 9E-8, 0));
                }
                if (!TargetSelector.INSTANCE.isWithinAttackRange(player.position(), target, attackRange)) {
                    distancePassAttack = false;
                    ClientPlayerAccess.of(player)
                            .getLegalMovementManager()
                            .playerStatus
                            .restoreRotation();
                    player.setPos(originalPosition);
                    teleportedPosition = null;
                    if (settings.elytraDelaySwitch() && !elytraExtra.delayQueue.isEmpty()) {
                        runThisTick = false;
                    }
                }
            }

            player.setDeltaMovement(velocity);
            if (distancePassAttack && !player.isFallFlying() && PlayerInputManager.INSTANCE != null) {
                PlayerInputManager.INSTANCE.addSprintModifier(Integer.MAX_VALUE, false, 0);
                ClientPlayerAccess.of(player).getLegalMovementManager().markForMoveFix();
            }
        }

        private void postTick() {
            if (!runThisTick) {
                if (submit()) return;
                finish();
                return;
            }

            LocalPlayer player = mc.player;
            if (distancePassAttack) {
                applyPostAttack(target, settings);
                if (teleportedPosition != null) {
                    Vec3 movementDelta = player.position().subtract(teleportedPosition);
                    player.setPos(originalPosition);
                    player.move(MoverType.PLAYER, movementDelta);
                    teleportedPosition = null;
                }
            }
            finish();
        }

        private void finish() {
            if (finished) return;
            finished = true;
            Tasks.scheduleDelayedPre(() -> delayAttacking = false, 0);
        }
    }

    private void applyPostAttack(Entity target, AttackSettings settings) {
        if (postFix.get() || settings.elytraDelaySwitch()) {
            Tasks.scheduleDelayedPre(
                    () -> {
                        attackWithSettings(mc.player, target, settings);
                    },
                    0);
        } else {
            attackWithSettings(mc.player, target, settings);
        }
    }

    private boolean processLegacySnapAttack(Entity target, AttackSettings settings) {
        if (settings.elytraDelaySwitch()) {
            // Legacy snap may attack even on a server-side glide tick.
            ElytraExtra elytraExtra = MovTasks.getElytraExtra();
            if (!elytraExtra.isThisTickGlideMovementServerSideGlide() && !elytraExtra.hasPendingFallFlyingReset()) {
                elytraExtra.requestManualArmorSwapAndResetFallFlying(20);
                delayAttacking = true;
                Tasks.scheduleRepeatedPre(
                        () -> {
                            if (!elytraExtra.isThisTickGlideMovementServerSideGlide()
                                    && !elytraExtra.hasPendingFallFlyingReset()) {
                                delayAttacking = false;
                                return true;
                            }
                            if (mc.player == null || mc.level == null || target.isRemoved()) {
                                delayAttacking = false;
                                return true;
                            }
                            double attackRange = CombatTasks.getCombatExtra().getAttackAtTargetRange(target);
                            if (!canLegacySnapAttack(target, attackRange)) {
                                if (!elytraExtra.hasPendingFallFlyingReset()) return false;
                                delayAttacking = false;
                                return true;
                            }
                            try {
                                processLegacySnapAttackNow(target, settings);
                            } finally {
                                delayAttacking = false;
                            }
                            return true;
                        },
                        1,
                        5);
                return true;
            }
        }
        return processLegacySnapAttackNow(target, settings);
    }

    private boolean canLegacySnapAttack(Entity target, double attackRange) {
        Vec3 playerPosition = mc.player.position();
        if (TargetSelector.INSTANCE.isWithinAttackRange(playerPosition, target, attackRange)) return true;
        if (!tpRange.get().positive()) return false;

        Vec3 teleportTarget =
                MovTasks.tpAttackSearch(playerPosition, target.getBoundingBox(), attackRange, 9.9, 1).stream()
                        .findFirst()
                        .orElse(null);
        return teleportTarget != null
                && TargetSelector.INSTANCE.isWithinAttackRange(teleportTarget, target, attackRange);
    }

    private boolean processLegacySnapAttackNow(Entity target, AttackSettings settings) {
        boolean sprintFlag = mc.player.isSprinting();
        double attackRange = CombatTasks.getCombatExtra().getAttackAtTargetRange(target);
        boolean canDirectlyHit = RaycastUtils.canRaycastHit(
                mc.player,
                PlayerStateManager.INSTANCE.lastPitch,
                PlayerStateManager.INSTANCE.lastYaw,
                target,
                attackRange);
        if (settings.isNoDelay() && canDirectlyHit) {
            // already actioned in caller
            // may not actioned in caller, fix it
            attackWithSettings(mc.player, target, settings);
            return false;
        }
        Vec3 vec3d = mc.player.position();
        boolean distancePassAttack = TargetSelector.INSTANCE.isWithinAttackRange(vec3d, target, attackRange);
        if (tpRange.get().positive() && !distancePassAttack) {

            Vec3 vec3d1 = MovTasks.tpAttackSearch(vec3d, target.getBoundingBox(), attackRange, 9.9, 1).stream()
                    .findFirst()
                    .orElse(null);
            // calculateBestReachPos(vec3d, target.getBoundingBox());
            if (vec3d1 != null && vec3d1.distanceToSqr(vec3d) > 1E-7) {
                mc.player.setPos(vec3d1.add(0, 9E-8, 0));
            }
            // backoff
            distancePassAttack = TargetSelector.INSTANCE.isWithinAttackRange(mc.player.position(), target, attackRange);
            if (!distancePassAttack) {
                mc.player.setPos(vec3d);
                // skip attack
            }
        }
        // after move player, do target
        if (distancePassAttack) {
            if (settings.useAttack()) {
                mc.player.releaseUsingItem();
                mc.getConnection()
                        .send(new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
            }

            Vec3 cacheDirection = TargetSelector.INSTANCE.getEntityRayCast(mc.player.position(), target);
            LegacySnapRotManager.INSTANCE.snapAt(cacheDirection, false);
            attackWithSettings(mc.player, target, settings);
            if (tpRange.get().positive()) {
                mc.player.setPos(vec3d);
            }
        }

        mc.player.setSprinting(sprintFlag);
        return false;
    }

    private boolean processIllegalAttack(Entity target, AttackSettings settings) {
        var player = mc.player;
        if (player == null) return false;
        final double attackRange = CombatTasks.getCombatExtra().getAttackRange();
        boolean alreadyAtTarget = RaycastUtils.canRaycastHit(
                mc.player, PlayerStateManager.INSTANCE.lastPitch, PlayerStateManager.INSTANCE.lastYaw, target);
        // rewrite tp system
        Deque<MovTasks.MovInfo> movementStack = new ArrayDeque<>();
        Deque<MovTasks.MovInfo> shouldMoveBackStack = new ArrayDeque<>();
        Vec3 currentStartPos = mc.player.position();
        movementStack.addLast(MovTasks.MovInfo.createNoUpdate(mc.player.position()));
        shouldMoveBackStack.addFirst(MovTasks.MovInfo.createNoUpdate(mc.player.position()));
        boolean alreadyInRange = alreadyAtTarget
                || TargetSelector.INSTANCE.isWithinAttackRange(
                        player.position(),
                        target,
                        attackRange); // target.getBoundingBox().squaredMagnitude(player.getEyePosition()) <
        // MathUtils.s2(attackRange);
        // mace hack、
        boolean useExactAttack = settings.useTp()
                && exactAttack.get()
                && (!alreadyInRange || CombatTasks.getPositionPredict().considerAntiShield(target));
        boolean shouldResetFallDamage = false;
        boolean currentSuccessful = true;
        boolean vanillaSuccessful = false;
        boolean exactSuccessful = false;
        // todo: add special attack logic,  special attack logic should before any attack logic
        // todo: remake configuration, use CustomRef
        // todo: add hand swapping logic
        if (currentSuccessful) {
            vanillaSuccessful =
                    processVanillaAttack(player, target, movementStack, shouldMoveBackStack, alreadyAtTarget);
        }
        if (currentSuccessful) {
            // exact attack
            if (useExactAttack) {
                if (processExactAttack(
                        player, target, movementStack, shouldMoveBackStack, vanillaSuccessful, settings)) {
                    exactSuccessful = true;
                }
            }
        }
        if (currentSuccessful) {
            if (!exactSuccessful && !vanillaSuccessful) {
                currentSuccessful &= processCommonTpAttack(
                        player, target, movementStack, shouldMoveBackStack, alreadyAtTarget, settings);
            }
        }
        boolean maceAttack = false;
        if (currentSuccessful) {
            if (processMaceAttack(player, target, movementStack, shouldMoveBackStack, settings)) {
                maceAttack = true;
                int maceThreshold = (useExactAttack ? 100 : 140);
                if (maceHeight.get().getValue() > maceThreshold) {
                    Debug.chat(Component.literal("[Attack Bot] 当前参数中,不建议将MaceHack范围设置在%d以上!".formatted(maceThreshold)));
                }
            }
        }

        // attacking creative player with mace at same height will cause falldamage calculate(caused by the shit code
        // below: we should resetHeight even if backStack.size() = 1
        //            Vec3 lastlyPos = movementStack.peekLast().vec3d();
        // final pos lies in attack range
        // remove final pos check because already checked
        if (currentSuccessful) {
            // start execute
            var iter = movementStack.iterator();
            Preconditions.checkArgument(iter.hasNext());
            Vec3 vec3d1 = iter.next().vec3d();
            MovTasks.MovingContext movingContext = MovTasks.MovingContext.create(vec3d1);
            List<MovTasks.MovInfo> moveInfos = new ArrayList<>();
            iter.forEachRemaining(moveInfos::add);
            shouldMoveBackStack.removeFirst();
            int movingToBundleCnt = moveInfos.size();
            moveInfos.addAll(shouldMoveBackStack);
            //                MovTasks.scheduleFarawayMoveInternal(moveInfos, false, movingContext, false);
            // attack
            List<MovTasks.StepActionBundle> actionBundles =
                    MovTasks.createMovingPacketsForMovSequence(movingContext, moveInfos, true, false);
            for (int i = 0; i < movingToBundleCnt; ++i) {
                actionBundles.get(i).run();
            }
            // processDuplicateAttack(player, target, moveInfos, movingContext, maceAttack);
            attackWithSettings(player, target, settings);
            for (int i = movingToBundleCnt; i < actionBundles.size(); ++i) {
                if (actionBundles.get(i).success) {
                    actionBundles.get(i).run();

                } else {
                    List<MovTasks.MovInfo> leftTasks = moveInfos.subList(i, moveInfos.size());
                    Tasks.scheduleDelayed(
                            () -> {
                                MovTasks.scheduleFarawayMoveInternal(leftTasks, false, movingContext.resetTick(), true);
                            },
                            1);
                    break;
                }
            }
            // already at first, remove duplicate stack
            //                Debug.info(movementStack);
            //                Debug.info(shouldMoveBackStack);
            //                var inviter = shouldMoveBackStack.stream().toList();
            //                MovTasks.scheduleFarawayMoveInternal(inviter, true, movingContext,
            //                    //calculate nofall down there in this argument, no need to consider
            //                    false
            //                );

            // force resync position to origin
            if ((settings.useTp() || settings.maceVClip())
                    && (!shouldMoveBackStack.isEmpty() || !movementStack.isEmpty())) {
                mc.player.setPos(currentStartPos);
                // feature
                MovTasks.setupAutoResync();
            }
            // check fall damage
            List<MovTasks.MovInfo> movementList = Streams.concat(movementStack.stream(), shouldMoveBackStack.stream())
                    .toList();
            //                Debug.info(movementList);
            //                Debug.info(movementList.size());
            int size = movementList.size();

            if (size > 1) {
                double maxY = Integer.MIN_VALUE;
                double minY = Integer.MAX_VALUE;
                for (var i = 0; i < size - 1; ++i) {
                    maxY = Math.max(maxY, movementList.get(i).vec3d().y);
                    minY = Math.min(minY, movementList.get(i).vec3d().y);
                }
                // calculate max deltaY
                if (Math.abs(maxY - minY) > player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) - 1) {
                    ClientPlayerAccess.of((LocalPlayer) player).setForceNoFall(true);
                    // in case that resync packet cause OnGround falldamage
                    player.setOnGround(false);
                }
            }

            //  shouldResetFallDamage = size >= 2 && movementList.get(size - 1).vec3d().y < movementList.get(size -
            // 2).vec3d().y;
            // - mc.player.getAttributeValue(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE);
            // falldistance will sum up if movement is down,

            // if(shouldResetFallDamage){
            // let noFall functions make fall judgement
            //                //do not consume fall damage when resync if any custom tp is applied
            //                if(size > 1){
            //
            //                }
            // }
        }

        // next, can continue
        return false;
    }

    private static int shieldExceptionspam = 0;

    private boolean processMaceAttack(
            Player player,
            Entity target,
            Deque<MovTasks.MovInfo> movementStack,
            Deque<MovTasks.MovInfo> shouldMoveBackStack,
            AttackSettings attackSettings) {
        //        if(maceHack.get() > 0.0D && player.getMainHandItem().getItem() instanceof MaceItem mace){
        //            //dupe fall distance
        //            double maxMace = maceHack.get();
        //            player.setOnGround(false);
        //            double deltaY = Math.max(target.getY() - mc.player.getY(),0);
        //            //error: down search returns negative value
        //            double height = MovTasks.searchFirstNoCollisionSpaceYHeight(mc.player.position().add(0, maxMace, 0),
        // 0, maxMace - 2 - deltaY, false);
        //
        //            double maceHeightMultiplier = maxMace + height;
        //            //attack space
        //            double minAvailableHeight = MovTasks.searchFirstNoCollisionSpaceYHeight(mc.player.position(), deltaY
        // , maxMace, true);
        //
        //            if(maceHeightMultiplier - minAvailableHeight > 1.5){
        //                Debug.chat(Component.literal("Mace Attack Simulation: simulate height %.2f, target height:
        // %.2f".formatted(maceHeightMultiplier, minAvailableHeight)).withStyle(ChatFormatting.GREEN));
        //                Vec3 top = movementStack.peekLast().vec3d();
        //                movementStack.addLast(MovTasks.MovInfo.createNoUpdate( top.add(0, maceHeightMultiplier,0)));
        //                movementStack.addLast(MovTasks.MovInfo.createNoUpdate(top.add(0, minAvailableHeight,0)));
        //                shouldMoveBackStack.addFirst(MovTasks.MovInfo.createNoUpdate(top.add(0, minAvailableHeight,
        // 0)));
        //            }
        //
        //        }
        if (attackSettings.maceVClip() && willUseMaceAttack(attackSettings.maceSwap())) {
            double maxMace = maceHeight.get().getValue();
            player.setOnGround(false);
            Vec3 playerPos = movementStack.peekLast().vec3d();
            // do not mace attack into water, water will reset fall distance
            if (mc.level.getBlockState(BlockPos.containing(playerPos)).getBlock() == Blocks.WATER) {
                Debug.chat(Component.literal("[Attack Bot] 目标攻击位置位于水中,无法执行MaceAttack!"));
                return false;
            }
            double deltaY = target.getY() - playerPos.y;
            // error: down search returns negative value
            double height = MovTasks.searchFirstNoCollisionSpaceYHeight(
                    playerPos.add(0, maxMace, 0), 0, maxMace - 2 - deltaY, false);
            // +height
            double maceHeightMultiplier = maxMace + height;
            // attack space
            // we assume that target is in attack range centered playerPos
            // we don't need to search for the 'minAvailableHeight‘
            // the height is 0.0D
            double minAvailableHeight = 0.0D;
            // MovTasks.searchFirstNoCollisionSpaceYHeight(playerPos, deltaY , maxMace, true);
            // moving height towards enermy only cause the distance be smaller
            //  moving height towards enermy causes collision with shulker
            // maybe we should delete height redirect
            //
            if (maceHeightMultiplier - minAvailableHeight > 2.0) {
                Debug.chat(Component.literal("[Attack Bot] Mace Attack Simulation: simulate height %.2f"
                                .formatted(maceHeightMultiplier))
                        .withStyle(ChatFormatting.GREEN));
                movementStack.addLast(MovTasks.MovInfo.createNoUpdate(playerPos.add(0, maceHeightMultiplier, 0)));
                // Debug.info("add", playerPos.add(0, maceHeightMultiplier,0));
                movementStack.addLast(MovTasks.MovInfo.createNoUpdate(playerPos.add(0, minAvailableHeight, 0)));
                // Debug.info("add", playerPos);
                if (Math.abs(minAvailableHeight) > 1E-7) {
                    shouldMoveBackStack.addFirst(
                            MovTasks.MovInfo.createNoUpdate(playerPos.add(0, minAvailableHeight, 0)));
                }
                return true;
            }
        }
        return false;
    }

    private static boolean processVanillaAttack(
            Player player,
            Entity target,
            Deque<MovTasks.MovInfo> movementStack,
            Deque<MovTasks.MovInfo> shouldMoveBackStack,
            boolean alreadAtTarget) {
        Vec3 top = movementStack.peekLast().vec3d();
        if (alreadAtTarget) {
            return true;
        } else if (target.getBoundingBox().distanceToSqr(top.add(0, mc.player.getEyeHeight(), 0))
                <= MathUtils.s2(CombatTasks.getCombatExtra().getAttackRange())) {
            return true;
        } else return false;
    }

    private boolean processExactAttack(
            Player player,
            Entity target,
            Deque<MovTasks.MovInfo> movementStack,
            Deque<MovTasks.MovInfo> shouldMoveBackStack,
            boolean vanillaSuccess,
            AttackSettings settings) {
        // how to manage exact attack and mace hack
        // fixed : can not tp to shulker insidef
        // should teleport the player to the pos of target entity
        PositionPredict positionPredict = CombatTasks.getPositionPredict();
        if (vanillaSuccess) {
            if (!positionPredict.considerAntiShield(target)) {
                return true;
            }
        }
        if (!settings.useTp()) {
            return false;
        }
        double range = getTpSelectRange();
        Vec3 current = player.position();
        // feat : teleporting position should met the need of antishield
        Vec3 targetPos = positionPredict.getExactAttackPosition(target);
        // todo: add Environment check and fallback plans like positions around
        if (targetPos != null) {
            // common atttack?
            List<Vec3> tpSequence = MovTasks.generateTpSequence(current, targetPos, false, 1.5 * range, true);
            List<Vec3> tpSequenceBack = MovTasks.generateTpSequence(targetPos, current, false, 1.5 * range, true);
            if ((tpSequence.size() == 2 || tpSequence.size() == 4)
                    && (tpSequenceBack.size() == 2 || tpSequenceBack.size() == 4)) {
                // correct tp sequence
                // try compact mace hack
                Vec3 lastly;
                if (tpSequence.size() == 2) {
                    // can directly tp
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(1)));

                } else {
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(1)));
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(2)));
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(3)));
                    lastly = tpSequence.get(3);
                }
                //  Debug.info(movementStack);
                int size = tpSequenceBack.size();

                for (int i = size - 2; i >= 0; --i) {
                    shouldMoveBackStack.addFirst(MovTasks.MovInfo.create(tpSequenceBack.get(i)));
                }
                //                if(maceHack.get() > 80){
                //                    Debug.chat(Component.literal("[Attack Bot] 在精确攻击模式下,不建议将MaceHack设置在80以上!"));
                //                }

                // shouldMoveBackStack.addFirst(MovTasks.MovInfo.create(tpSequenceBack.get(0).add(0, 9E-8,0)));
                // Debug.info(shouldMoveBackStack);
                return true;
            } else {
                Debug.chat("[Attack Bot] Exact Attack failed, fall back to common mode");
            }
        }
        return vanillaSuccess;
    }

    private boolean processCommonTpAttack(
            Player player,
            Entity target,
            Deque<MovTasks.MovInfo> movementStack,
            Deque<MovTasks.MovInfo> shouldMoveBackStack,
            boolean alreadyAtTarget,
            AttackSettings settings) {
        final Vec3 vec3d = movementStack.peekLast().vec3d();
        double commonAttackRange = CombatTasks.getCombatExtra().getAttackRange();
        if (alreadyAtTarget) {
            // pass
            return true;
        }
        // todo: get this better

        else if (settings.useTp()
                && target.getBoundingBox().distanceToSqr(vec3d.add(0, mc.player.getEyeHeight(), 0))
                        > MathUtils.s2(commonAttackRange)) {

            List<Vec3> sequence =
                    MovTasks.tpAttackSearch(vec3d, target.getBoundingBox(), commonAttackRange - 0.25, 135, 1);
            //            if(!sequence.isEmpty() && RenderTasks.DEBUG_RENDER_COLLISION){
            //                Vec3 vec3d1 = sequence.get(sequence.size() -1);
            //                RenderTasks.registerVirtualRenderTask(new RenderTasks.BoxRenderingTask(vec3d1.add(new
            // Vec3(-0.5, 0, -0.5)), vec3d1.add(new Vec3(0.5, 2, 0.5)), 16));
            //            }

            if (!sequence.isEmpty()
                    && target.getBoundingBox().distanceToSqr(sequence.get(sequence.size() - 1))
                            < MathUtils.s2(commonAttackRange)) {
                for (var vec : sequence) {
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(vec));

                    shouldMoveBackStack.addFirst(MovTasks.MovInfo.createNotOnGround(vec));
                }
                //                if(tpAttackRange.get() >= 135){
                //                    Debug.chat(Component.literal("[Attack Bot] 不建议将tpAttack范围设置在135以上!"));
                //                }
                return true;
            }
            return false;
        } else {
            return false;
        }
    }

    public boolean shouldUseDelayMovementAttackMaceFix() {
        return maceFixMode.get().isPresent() && maceFixMode.get().getValue().get() == BypassMode.BYPASS_GRIM;
    }

    public void handleMaceAttack(Event<ServerboundAttackPacket> interactPacket) {
        if (interactPacket.isCancelled()) return;
        // 26.2 起攻击由独立的 ServerboundAttackPacket 承载，该通道本身就只收攻击包，无需再判 isAttack
        if (maceFixMode.get().isPresent()
                && mc.player.getMainHandItem().getItem() instanceof MaceItem
                && mc.player.isFallFlying()
                && !ElytraExtra.INSTANCE.hasPendingFallFlyingReset()
                && !ElytraExtra.INSTANCE.isThisTickGlideMovementServerSideGlide()
                && shouldUseMaceFix()
                && !shouldUseDelayMovementAttackMaceFix()) {
            mc.getConnection()
                    .send(new ServerboundPlayerCommandPacket(
                            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            PacketManager.schedulePostScheduleCallback(interactPacket.context, () -> {
                ElytraExtra.INSTANCE.requestManualResetFallFlying(20);
            });
        }
    }

    public boolean shouldUseMaceFix() {
        return maceFixMode.get().isPresent() && mc.player.isFallFlying();
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        if (event.context().getValue().isOptionPreset()) return;
        ModulePreset preset = event.context().getValue();
        legalTargetingMode.set(LegalTargetingMode.getFromPreset(preset));
        switch (preset) {
            case HACKING, VANILLA -> {
                if (tpRange.get().getValue() < 0) {
                    tpRange.set(tpRange.get().withValue(-tpRange.get().getValue()));
                }
            }
            default -> {
                if (tpRange.get().getValue() > 0) {
                    tpRange.set(tpRange.get().withValue(-tpRange.get().getValue()));
                }
            }
        }
        switch (preset) {
            case AC_GRIM, AC_GRIM_LEGACY -> {
                maceFixMode.set(maceFixMode.get().withValue(new WrapEnum<>(BypassMode.BYPASS_GRIM)));
            }
            default -> maceFixMode.set(maceFixMode.get().withValue(new WrapEnum<>(BypassMode.NO_BYPASS)));
        }
    }

    @With
    public static record AttackSettings(
            boolean useTp,
            boolean maceSwap,
            boolean invSwap,
            boolean selectWeapon,
            boolean antiShieldSwap,
            Optional<Integer> fastBreakShieldSwap,
            boolean useAttack,
            boolean elytraDelaySwitch,
            boolean criticalSprint,
            boolean maceVClip,
            boolean swingHand,
            GhostHandMode ghostHandMode) {
        public boolean isVanilla() {
            return !useTp && !elytraDelaySwitch && !maceVClip && !useAttack;
        }

        public boolean isNoDelay() {
            return !elytraDelaySwitch && !useAttack;
        }

        public AttackSettings withNoSwap() {
            return new AttackSettings(
                    useTp,
                    false,
                    false,
                    false,
                    false,
                    Optional.empty(),
                    useAttack,
                    elytraDelaySwitch,
                    criticalSprint,
                    maceVClip,
                    swingHand,
                    ghostHandMode);
        }
    }
}
