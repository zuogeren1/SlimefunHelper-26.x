package me.matl114.hacks.modules.combat;

import com.google.common.base.Preconditions;
import com.google.common.collect.Streams;
import java.util.*;
import lombok.NonNull;
import lombok.With;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.hacks.EntityInternalAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.*;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.move.ElytraExtra;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.OptionalPrimitive;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.enums.LegalTargetingMode;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.entity.PlayerInputUtils;
import me.matl114.versioned.api.VDataFlag;
import me.matl114.versioned.api.VItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
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
import org.jetbrains.annotations.ApiStatus;
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
            .defaultValue(LegalTargetingMode.DELAY_MOVEMENT)
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

    public final FlagRef targetPredict = flagBuilder(attack.add("use-delay-movement-pos-predict"))
            .show(() -> !legalTargetingMode.get().isLegal()
                    && legalTargetingMode.get().isIn(LegalTargetingMode.DELAY_MOVEMENT))
            .build();

    public final FlagRef postFix = builder(attack.add("attack-post-fix"), Boolean.class)
            .defaultValue(true)
            .show(() -> !legalTargetingMode.get().isLegal()
                    && legalTargetingMode.get().isIn(LegalTargetingMode.DELAY_MOVEMENT))
            .build();

    public final FlagRef autoAntiShield =
            flagBuilder(attack.add("auto-anti-shield")).build();

    public final FlagRef autoSwap = flagBuilder(attack.add("attack-inv-swap")).build();

    public final FlagRef autoSelect =
            flagBuilder(attack.add("attack-select-best-weapon")).build();
    //
    public final FlagRef autoRelease =
            flagBuilder(attack.add("auto-handle-use-when-attack")).build();

    @ApiStatus.Experimental
    public final NBTRef<OptionalPrimitive<Double>> autoMaceSwap = builder(
                    attack.add("mace-swap"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.DOUBLE_TYPE, 6.0D))
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

    private final Random attackOffsetRand = new Random();

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
        registerListener(RenderListener.getRender3DEvent(), this::onRenderTarget);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
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
        if (targetPredict.get() && legalTargetingMode.get().isLegal()) {
            switch (legalTargetingMode.get()) {
                case DELAY_MOVEMENT: {
                    if (mc.player.isFallFlying()
                            && willUseMaceAttack()
                            && ElytraExtra.INSTANCE.shouldUseDelayMovementAttackMaceFix()) {
                        return 2;
                    }
                    return 1;
                }
                case LEGACY_SLIENT_ROT:
                    return 0;
                default:
                    return 0;
            }
        } else {
            return 0;
        }
    }

    private Entity lastTickTarget;
    private int lastTick;

    public void onRenderTarget(Event<Render3D> stackE) {
        var stack = stackE.context;
        if (enable.get() && mc.player != null && renderAttackTarget.get()) {
            float tickDelta = stack.partialTicks();
            if (mc.player.isUsingItem()) {
                // filter bow, but keep shield
                if (mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                    return;
                }
                if (mc.player.getUseItem().getItem() instanceof ProjectileWeaponItem bow) {
                    return;
                }
            }
            // only render when holding weapon,
            if (CombatTasks.notSuitableForAttack(mc.player.getMainHandItem())) {
                return;
            }
            if (lastTick != Tasks.getTick()) {
                lastTick = Tasks.getTick();
                lastTickTarget = CombatTasks.getTargetSelector().searchAttackEntity(getTpSelectRange(), true);
            }
            if (!EntityUtils.isEntityValid(lastTickTarget)) {
                lastTickTarget = null;
                return;
            }
            RenderUtils.startDrawVirtual(stack.stack());
            try {
                Entity entity = lastTickTarget;
                if (entity != null) {
                    float dist = entity.distanceTo(mc.player);
                    float opacity = Math.min(0.6F, 0.10F + dist * 0.02F);
                    AABB box = RenderUtils.getLerpedBox(entity, tickDelta);
                    RenderUtils.drawSolidBox(
                            stack.stack(),
                            box.getMinPosition(),
                            box.getMaxPosition(),
                            ColorUtils.withAlpha(renderAttackColor.get().color(), opacity));
                }
            } finally {
                RenderUtils.stopDrawVirtual(stack.stack());
            }
        }
    }

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
        boolean invSwap = autoSwap.get();
        boolean selectWeapon = autoSelect.get();
        boolean antiShield = autoAntiShield.get();
        boolean useAttack = autoRelease.get() && mc.player.isUsingItem();
        boolean elytraSwitch = ElytraExtra.INSTANCE.shouldUseMaceFix()
                && ElytraExtra.INSTANCE.shouldUseDelayMovementAttackMaceFix()
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

    @NonNull
    public static IndexEntry<ItemStack> selectBestWeapon(AttackSettings attackSettings, @Nullable Entity target) {
        IndexEntry<ItemStack> invResult;
        if (attackSettings.antiShieldSwap()
                && shouldUseAntiShield(target)
                && (invResult = findAntiShieldWeapon(attackSettings)) != null) {
            return invResult;
        } else if (attackSettings.invSwap()
                && !VItem.getInstance().isWeapon(mc.player.getItemInHand(InteractionHand.MAIN_HAND))
                && target instanceof LivingEntity lv
                && (invResult = InventoryUtils.findBestPlayerItem(
                                (ex) -> {
                                    if (VItem.getInstance().isSpear(ex)) return null;
                                    if (VItem.getInstance().isWeapon(ex)) {
                                        Integer damageCost = VItem.getInstance().getAttackDurabilityCost(ex);
                                        return damageCost == null
                                                ? null
                                                : -((double) damageCost * 1E8)
                                                        + DamageUtils.getAttackDamage(mc.player, lv, ex)
                                                                * DamageUtils.getAttackSpeed(mc.player, ex);
                                    }
                                    return null;
                                },
                                attackSettings.ghostHandMode().getSearchSize(false),
                                false,
                                false))
                        != null) {
            return invResult;
        } else if (attackSettings.maceSwap()
                && target instanceof LivingEntity lv
                && (invResult = InventoryUtils.findBestPlayerItem(
                                (ex) -> {
                                    if (ex.getItem() == Items.MACE) {
                                        return DamageUtils.getAttackDamage(lv, ex);
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
                && target instanceof LivingEntity
                && (invResult = InventoryUtils.findBestPlayerItem(
                                (ex) -> {
                                    if (VItem.getInstance().isSpear(ex)) return null;
                                    if (ex.is(mc.player
                                            .getItemInHand(InteractionHand.MAIN_HAND)
                                            .getItem())) {
                                        return DamageUtils.getAttackDamage(mc.player, target, ex)
                                                * DamageUtils.getAttackSpeed(mc.player, ex);
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
        if (callback != null) {
            callback.run();
        }
        if (input != null) {
            input.sendPlayerInputAsRiding();
        }
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
            case DELAY_MOVEMENT -> processDelayMovementAttack(target, settings);
            case LEGACY_SLIENT_ROT -> processLegacySnapAttack(target, settings);
            case NONE -> {
                attackWithSettings(mc.player, target, settings);
                yield false;
            }
        };
    }

    private boolean processDelayMovementAttack(Entity target, AttackSettings settings) {
        ElytraExtra elytraExtra = MovTasks.getElytraExtra();
        final double attackRange = CombatExtra.INSTANCE.getAttackAtTargetRange(target);
        boolean useMaceAttack =
                elytraExtra.shouldUseDelayMovementAttackMaceFix() && willUseMaceAttack(settings.maceSwap());
        // remove crosshairTarget judge, use
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
        } else {
            // 提前转向 下个tick就有正确的velocity了
            // mace not enable in legal mode
            // use Item packet should trigger by a non-empty item

            // add movement prediction position targeting option
            // check if it can pass grimac in real situation

            // TODO: fix this bug: can not pass matrix ac when on ground , check numbers and positions,
            int swapElytraSlot = -1;
            boolean armorFly = elytraExtra.isCurrentArmorGliding();
            if (useMaceAttack) {
                // do here
                if (!armorFly) {
                    // common elytra fly not supported yet
                    swapElytraSlot = elytraExtra.findEmptySlotForElytra();
                    if (swapElytraSlot != -1) {
                        elytraExtra.switchSlotToArmor(swapElytraSlot);
                    }
                }
            }
            final int elytraSlot = swapElytraSlot;
            // testing failed,
            // see Grim' s Reach
            if (settings.useAttack()) {
                MovTasks.getNoSlowDown().setPreAttackUseTick();
            }
            boolean preAttack = false; // legalTargetingMode.get().isPreAttack();
            //                if(!useMaceAttack && preAttack) {
            //                    // pieces of shit... may not bypass shit grim after one REACH flag, I dont know
            // why??
            //                    attackWithCritic(player, target, criticSprint);
            //                }
            delayAttacking = true;
            ClientPlayerAccess.of(mc.player)
                    .getLegalMovementManager()
                    .addMovementModifier(new LegalMovementManager.MovementModifier() {
                        Vec3 posDelta = Vec3.ZERO;
                        Vec3 posDelta2 = Vec3.ZERO;
                        Vec3 velocity;
                        Vec3 lookVec;
                        boolean distancePassAttack = true;
                        boolean runThisTick = true;
                        int max = 10;

                        @Override
                        public int priority() {
                            return PRIORITY_LOW;
                        }

                        @Override
                        public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {
                            runThisTick = true;
                            LocalPlayer args = movementManagerEvent.context.playerStatus.entity;
                            if (useMaceAttack) {
                                if (args.isFallFlying()) {
                                    max--;
                                    runThisTick = false;
                                    return;
                                }
                            }

                            // step back our position
                            velocity = args.getDeltaMovement();
                            Vec3 predictedEyePos = TargetSelector.INSTANCE.getBestAttackEyePos(
                                    mc.player.position(), target.getBoundingBox()); // mc.player.getEyePos();
                            // revert shit
                            if (useMaceAttack || args.isFallFlying()) {
                                // fix targeting in big velocity
                                predictedEyePos = predictedEyePos.add(
                                        mc.player.getDeltaMovement()); // predictedEyePos.add(mc.player.getVelocity());
                            }
                            Vec3 vec3d = args.position();
                            if (tpRange.get().positive()
                                    && target.getBoundingBox().distanceToSqr(predictedEyePos)
                                            > MathUtils.s2(attackRange)) {
                                // need tp attack
                                // how?
                                // 平面突袭？

                                Vec3 vec3d1 =
                                        MovTasks.tpAttackSearch(vec3d, target.getBoundingBox(), attackRange, 9.9, 1)
                                                .stream()
                                                .findFirst()
                                                .orElse(null);
                                // calculateBestReachPos(vec3d, target.getBoundingBox());
                                if (vec3d1 != null && vec3d1.distanceToSqr(vec3d) > 1E-7) {
                                    posDelta = vec3d; // vec3d1.subtract(vec3d);
                                    posDelta2 = vec3d1;
                                    args.setPos(vec3d1.add(0, 9E-8, 0));
                                    predictedEyePos = TargetSelector.INSTANCE.getBestAttackEyePos(
                                            args.position(), target.getBoundingBox());
                                }
                                // backoff
                                if (!TargetSelector.INSTANCE.isWithinAttackRange(
                                        args.position(), target.getBoundingBox(), attackRange)) {
                                    // Debug.chat("Distance to large , disable atack");
                                    distancePassAttack = false;
                                    movementManagerEvent.context.playerStatus.restoreRotation();
                                    args.setPos(vec3d);
                                    // skip attack
                                }
                            }
                            // after move player, do target
                            if (distancePassAttack) {
                                Vec3 eyePos = target.getEyePosition();
                                Vec3 targetPos = target.position();
                                double percentage = attackOffsetRand.nextDouble(0.8d, 1.00d);
                                Vec3 attackOffsetted =
                                        targetPos.add(eyePos.subtract(targetPos).scale(percentage));
                                attackOffsetted.add(
                                        attackOffsetRand.nextDouble(-0.05d, 0.05d),
                                        attackOffsetRand.nextDouble(-0.05d, 0.05d),
                                        attackOffsetRand.nextDouble(-0.05d, 0.05d));
                                Vec3 cacheDirection = attackOffsetted
                                        .subtract(predictedEyePos)
                                        .normalize();
                                // only fix silent entities, because they will not move
                                if (!(target instanceof LivingEntity)) {
                                    cacheDirection = fixRayCastBigBox(
                                            predictedEyePos, target.getBoundingBox(), cacheDirection, attackRange);
                                }
                                movementManagerEvent.context.pushImportantRotation(true, true);
                                PlayerStateManager.setPlayerRotationSafe(args, cacheDirection);
                                if (RenderTasks.DEBUG_RENDER_COMBAT) {
                                    RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
                                            RenderTasks.DEBUG_TICK,
                                            new RenderTasks.LineObject(predictedEyePos, cacheDirection)));
                                }
                                lookVec = cacheDirection;

                                movementManagerEvent.context.markForResetRot();
                            }

                            // restore velocity after collide
                            args.setDeltaMovement(velocity);
                        }

                        @Override
                        public void applyAfterInputTick(Event<LegalMovementManager> movementManagerEvent) {
                            if (!runThisTick) return;
                            LocalPlayer args = movementManagerEvent.context.playerStatus.entity;
                            // there is no need for fall flying player to correct this
                            if (!args.isFallFlying()) {
                                PlayerInputUtils.of(mc.player).sprint(false).applyInput(mc.player);
                                movementManagerEvent.context.markForMoveFix();
                            }
                        }

                        @Override
                        public boolean postModify(
                                Event<LegalMovementManager> movementManagerEvent, boolean enabledThisTick) {
                            if (!runThisTick) {
                                return max >= 0;
                            }
                            LocalPlayer args = movementManagerEvent.context.playerStatus.entity;
                            if (distancePassAttack) {
                                if (!preAttack) {
                                    applyPostAttack(target, settings);
                                }
                                if (posDelta != Vec3.ZERO) {
                                    Vec3 trueDelta = args.position().subtract(posDelta2); // .subtract(0, 0.2, 0);// =
                                    args.setPos(posDelta);
                                    // args.move(MoverType.PLAYER, posDelta.subtract(args.getPos()));
                                    args.move(MoverType.PLAYER, trueDelta);
                                    posDelta = posDelta2 = Vec3.ZERO;
                                }
                            }
                            if (useMaceAttack && !preAttack) {
                                if (armorFly) {
                                    ACTasks.addPostTransactionAction((ch) -> {
                                        if (elytraExtra.onSwitchItemArmorFallFlying()) {
                                            mc.getConnection()
                                                    .send(new ServerboundPlayerCommandPacket(
                                                            mc.player,
                                                            ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                                            elytraExtra.switchSlotToArmor(elytraExtra.armorGlideTransactionSlot);
                                            elytraExtra.postTickSwapWithChestSlot = -1;
                                            EntityInternalAccess.of(mc.player)
                                                    .setDataFlag(VDataFlag.FALL_FLYING_FLAG_INDEX, true);
                                        }
                                    });
                                } else {
                                    if (elytraSlot != -1) {
                                        ACTasks.addPostTransactionAction((ch) -> {
                                            elytraExtra.switchSlotToArmor(elytraSlot);
                                            if (!mc.player.isFallFlying())
                                                ch.send(new ServerboundPlayerCommandPacket(
                                                        mc.player,
                                                        ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                                            //
                                            // EntityInternalAccess.of(mc.player).setDataFlag(VDataFlag.FALL_FLYING_FLAG_INDEX, true);
                                        });
                                    }
                                }
                            }
                            Tasks.scheduleDelayedPre(
                                    () -> {
                                        delayAttacking = false;
                                    },
                                    0);
                            // return do not kept
                            return false;
                        }
                    });

            // can not try, they control the packets movement
            //  mc.level.tickEntity(mc.player);
            return true;
        }
    }

    private void applyPostAttack(Entity target, AttackSettings settings) {
        if (postFix.get()) {
            ACTasks.addPostTransactionAction((ch) -> {
                attackWithSettings(mc.player, target, settings);
            });
        } else {
            attackWithSettings(mc.player, target, settings);
        }
    }

    private boolean processLegacySnapAttack(Entity target, AttackSettings settings) {
        //        ElytraExtra elytraExtra = MovTasks.getElytraExtra();
        boolean sprintFlag = mc.player.isSprinting();
        //        boolean useMaceAttack =
        //                false && elytraExtra.shouldUseDelayMovementAttackMaceFix() &&
        // willUseMaceAttack(settings.maceSwap());
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
        Vec3 predictedEyePos = TargetSelector.INSTANCE.getBestAttackEyePos(vec3d, target.getBoundingBox());
        boolean distancePassAttack =
                TargetSelector.INSTANCE.isWithinAttackRange(vec3d, mc.player.getBoundingBox(), attackRange);
        if (tpRange.get().positive() && !distancePassAttack) {

            Vec3 vec3d1 = MovTasks.tpAttackSearch(vec3d, target.getBoundingBox(), attackRange, 9.9, 1).stream()
                    .findFirst()
                    .orElse(null);
            // calculateBestReachPos(vec3d, target.getBoundingBox());
            if (vec3d1 != null && vec3d1.distanceToSqr(vec3d) > 1E-7) {
                mc.player.setPos(vec3d1.add(0, 9E-8, 0));
                predictedEyePos = TargetSelector.INSTANCE.getBestAttackEyePos(vec3d, target.getBoundingBox());
            }
            // backoff
            if (!TargetSelector.INSTANCE.isWithinAttackRange(vec3d, mc.player.getBoundingBox(), attackRange)) {
                distancePassAttack = false;
                mc.player.setPos(vec3d);
                // skip attack
            }
        }
        // after move player, do target
        if (distancePassAttack) {
            boolean useItem = false;
            if (settings.useAttack()) {
                useItem = true;
                mc.player.releaseUsingItem();
                mc.getConnection()
                        .send(new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
            }
            Vec3 eyePos = target.getEyePosition();
            Vec3 targetPos = target.position();
            double percentage = attackOffsetRand.nextDouble(0.8d, 1.00d);
            Vec3 attackOffsetted = targetPos.add(eyePos.subtract(targetPos).scale(percentage));
            attackOffsetted.add(
                    attackOffsetRand.nextDouble(-0.05d, 0.05d),
                    attackOffsetRand.nextDouble(-0.05d, 0.05d),
                    attackOffsetRand.nextDouble(-0.05d, 0.05d));
            Vec3 cacheDirection = attackOffsetted.subtract(predictedEyePos).normalize();
            cacheDirection = fixRayCastBigBox(predictedEyePos, target.getBoundingBox(), cacheDirection, attackRange);
            // mace
            LegacySnapRotManager.INSTANCE.snapAt(cacheDirection, false);
            attackWithSettings(mc.player, target, settings);
        }

        mc.player.setSprinting(sprintFlag);
        return false;
    }

    private Vec3 fixRayCastBigBox(Vec3 usingEyePos, AABB targetBox, Vec3 currentRayCast, double currentAttackRange) {
        Vec3 rayCastTest = currentRayCast.normalize().scale(currentAttackRange - 0.009178);
        var ray = targetBox.clip(usingEyePos, usingEyePos.add(rayCastTest));
        if (ray.isPresent()) {
            return currentRayCast;
        } else {
            // ?
            AABB shrinkedBox = targetBox.inflate(-1E-7, -1E-7, -1E-7);

            Vec3 targetingPos = MathUtils.magnitudePoint(shrinkedBox, usingEyePos);
            return targetingPos.subtract(usingEyePos).normalize();
        }
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
                        target.getBoundingBox(),
                        attackRange); // target.getBoundingBox().squaredMagnitude(player.getEyePos()) <
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

    public static boolean passCriticalPredicate(Player player) {
        boolean bl3 = player.getAttackStrengthScale(0.5f) > 0.9f
                && !player.onGround()
                && !player.onClimbable()
                && !player.isInWater()
                && !player.hasEffect(MobEffects.BLINDNESS)
                && !player.isPassenger();
        bl3 = bl3 && !player.isSprinting();
        return bl3;
    }

    @Deprecated
    private static Vec3 calculateBestReachPos(Vec3 from, AABB target) {
        return from;
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
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
    }

    @With
    public static record AttackSettings(
            boolean useTp,
            boolean maceSwap,
            boolean invSwap,
            boolean selectWeapon,
            boolean antiShieldSwap,
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
    }
}
