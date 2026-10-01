package me.matl114.hacks.modules.interact;

import com.google.common.base.Preconditions;
import com.google.common.collect.Streams;
import java.util.*;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.*;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.combat.CombatExtra;
import me.matl114.hacks.modules.combat.PositionPredict;
import me.matl114.hacks.modules.combat.TargetSelector;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.*;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.hacks.utils.enums.LegalTargetingMode;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.collections.FlagEntry;
import me.matl114.utils.entity.PlayerInputUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public class Interact extends BaseModule {
    public static Interact INSTANCE;

    public Interact() {
        super("Interact");
        bindFlag(enable);
        INSTANCE = this;
    }

    public final ModulePath root = makePath(Configs.INTERACT_CONFIG, "interact-arua.interact");

    public final EnumRef<LegalTargetingMode> entityMode = builder(root.add("entity-mode"), LegalTargetingMode.class)
            .defaultValue(LegalTargetingMode.NONE)
            .build();

    public final EnumRef<LegalInteractMode> blockMode = builder(
                    root.add("block-mode"), LegalInteractMode.class)
            .defaultValue(LegalInteractMode.NONE)
            .build();

    public final FlagRef blockAirPlace = builder(root.add("block-air-place"), Boolean.class)
            .defaultValue(false)
            .build();

    public final NBTRef<OptionalPrimitive<Double>> tpInteract = builder(
                    root.add("tp-interact-range"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.DOUBLE_TYPE, 10.0D))
            .build();

    public final FlagRef swingHandBlock =
            builder(root.add("swing-hand"), Boolean.class).defaultValue(true).build();
    public final FlagRef swingHandEntity =
            builder(root.add("swing-hand"), Boolean.class).defaultValue(true).build();

    public final ModulePath base = root.add("interact-redirect");
    public final FlagRef enable = flagBuilder(base.addEnable()).build();
    public final KeyBindRef hotkey =
            moduleEntry(base.addHotkey(), new MultiKeyBind(), base.addEnable()).build();

    public final FlagRef enableEntity =
            builder(base.add("enable-entity"), Boolean.class).defaultValue(true).build();

    public final FlagRef enableBlock =
            builder(base.add("enable-block"), Boolean.class).defaultValue(false).build();

    public final NBTRef<EntityTypeRegex> interactWhiteList = builder(
                    base.add("entity-whitelist"), EntityTypeRegex.class)
            .defaultValue(new EntityTypeRegex(new Regex("^(villager|chest_minecart)$")))
            .build();

    public final FlagRef ignoreBlockPlace =
            flagBuilder(base.add("ignore-block-place")).build();

    public final FlagRef ignoreUseItem =
            flagBuilder(base.add("ignore-use-item")).build();

    public final NBTRef<EntrySet<Item>> useItemBlackList = builder(
                    base.add("use-item-black-list"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(new Regex("^()$"), BuiltInRegistries.ITEM))
            .build();

    public final FlagRef entityPriority = builder(base.add("entity-priority"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef entityOnlyInteractable = builder(base.add("only-interact-interactable-entity"), Boolean.class)
            .defaultValue(true)
            .build();

    public final NBTRef<EntrySet<Block>> blockWhiteList = builder(
                    base.add("block-whitelist"), EntrySet.<Block>parameter())
            .defaultValue(new EntrySet<>(new Regex("^(.*chest|shulker.*)$"), BuiltInRegistries.BLOCK))
            .build();

    public final FlagRef blockOnlyHandNotPlace = builder(
                    base.add("interact-block-only-when-hand-not-block"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef blockOnlyInteractable = builder(base.add("only-interact-interactable-block"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef renderAttackTarget =
            flagBuilder(base.add("render-target")).build();

    public final NBTRef<WrapColor> renderAttackColor = builder(base.add("render-target-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.RED)))
            .build();

    HitResult currentInteractTarget = null;

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreGameTick(), this::onPreTick);
        registerListener(RenderListener.getRender3DEvent(), this::onRender3D);
        registerListener(Listener.getItemUseAction(), this::onInteract);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
    }

    public boolean canUseTp() {
        return tpInteract.get().test(s -> s > 1E-6);
    }

    public boolean canInteract(Entity entity, double range) {
        if (!TargetSelector.INSTANCE.isTargetInRange(entity, range, 0)) {
            return false;
        }
        if (entity.isAlive() && !entity.isSpectator() && interactWhiteList.get().test(entity.getType())) {
            if (entityOnlyInteractable.get()
                    && !InteractUtils.isInteractAcceptable(
                            mc.level, mc.player, entity, mc.player.getItemInHand(InteractionHand.MAIN_HAND))) {
                return false;
            }
            return true;
        } else return false;
    }

    public Entity searchInteractableEntity() {
        double search = CombatExtra.INSTANCE.getAttackRange();
        if (canUseTp()) {
            search += tpInteract.get().getValue();
        }
        if (mc.hitResult.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) mc.hitResult).getEntity();
            if (canInteract(entity, search)) {
                return entity;
            }
        }

        final double searchRange = search;
        return TargetSelector.INSTANCE.searchAttack(search, true, 0, (entity) -> {
            return canInteract(entity, searchRange);
        });
    }

    public boolean canInteract(BlockPos bp, double range) {
        BlockState state = mc.level.getBlockState(bp);
        if (!state.isAir() && !state.liquid() && blockWhiteList.get().test(state.getBlock())) {
            if (!InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), bp, range)) {
                return false;
            }
            if (blockOnlyInteractable.get()
                    && !InteractUtils.isInteractAcceptable(
                            mc.level, mc.player, bp, state, mc.player.getItemInHand(InteractionHand.MAIN_HAND))) {
                return false;
            }
            if (blockOnlyHandNotPlace.get()
                    && mc.player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof BlockItem) {
                return false;
            }
            return true;
        }
        return false;
    }

    public BlockPos searchInteractableBlock() {
        double search = InteractExtra.INSTANCE.getBlockReachDistance();
        if (canUseTp()) {
            search += tpInteract.get().getValue();
        }
        Vec3 playerEye = mc.player.getEyePosition();
        Vec3 rot = mc.player.getLookAngle();
        Vec3 endPos = playerEye.add(rot.normalize().scale(search));
        for (var bp : RaycastUtils.createRaycastBlockPoses(playerEye, endPos)) {
            if (canInteract(bp, search)) {
                return bp;
            }
        }
        BlockPos playerPos = mc.player.getOnPos().offset(0, 1, 0);
        for (var bd : InteractExtra.INSTANCE.getBlocksAround()) {
            BlockPos testPos = playerPos.offset(bd);
            if (canInteract(testPos, search)) {
                return testPos;
            }
        }
        return null;
    }

    public void onPreTick(Event<LocalPlayer> event) {
        currentInteractTarget = null;
        if (enable.get()) {
            HitResult currentCrosshairTarget = mc.hitResult;
            // hold use
            if (!ignoreUseItem.get()) {
                ItemStack stack = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
                if (!useItemBlackList.get().test(stack.getItem())
                        && InteractUtils.isInteractAcceptable(mc.level, mc.player, stack)) {
                    currentInteractTarget = null;
                    return;
                }
            }
            if (currentCrosshairTarget.getType() == HitResult.Type.BLOCK) {
                if (!ignoreBlockPlace.get()
                        && mc.player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof BlockItem bl) {
                    currentInteractTarget = currentCrosshairTarget;
                } else {
                    BlockPos pos = ((BlockHitResult) currentCrosshairTarget).getBlockPos();
                    BlockState state = mc.level.getBlockState(pos);
                    if (InteractUtils.isInteractAcceptable(
                            mc.level, mc.player, pos, state, mc.player.getItemInHand(InteractionHand.MAIN_HAND))) {
                        currentInteractTarget = currentCrosshairTarget;
                    }
                }
            } else if (currentCrosshairTarget.getType() == HitResult.Type.ENTITY) {
                Entity target = ((EntityHitResult) currentCrosshairTarget).getEntity();
                if (InteractUtils.isInteractAcceptable(
                        mc.level, mc.player, target, mc.player.getItemInHand(InteractionHand.MAIN_HAND))) {
                    currentInteractTarget = currentCrosshairTarget;
                }
            }
            if (currentInteractTarget != null) {
                return;
            }
            if (entityPriority.get()) {
                Entity targetEntity = enableEntity.get() ? searchInteractableEntity() : null;
                if (targetEntity != null) {
                    currentInteractTarget = new EntityHitResult(targetEntity);
                } else {
                    BlockPos pos = enableBlock.get() ? searchInteractableBlock() : null;
                    if (pos != null) {
                        currentInteractTarget = InteractionTasks.createHitResult(pos, mc.player.position());
                    }
                }
            } else {
                BlockPos pos = enableBlock.get() ? searchInteractableBlock() : null;
                if (pos != null) {
                    currentInteractTarget = InteractionTasks.createHitResult(pos, mc.player.position());
                } else {
                    Entity targetEntity = enableEntity.get() ? searchInteractableEntity() : null;
                    if (targetEntity != null) {
                        currentInteractTarget = new EntityHitResult(targetEntity);
                    }
                }
            }
        }
    }

    public void onInteract(Event<HitResult> hitResult) {
        if (hitResult.isCancelled()) return;
        if (enable.get()) {
            InteractionHand hand = hitResult.getArgs(0);
            if (hand == InteractionHand.MAIN_HAND) {
                Player player = mc.player;
                if (player != null && mc.level != null) {
                    if (currentInteractTarget != null && currentInteractTarget != mc.hitResult) {
                        if (currentInteractTarget instanceof EntityHitResult entity
                                && entity.getType() == HitResult.Type.ENTITY
                                && interactEntity(entity.getEntity())) {
                            hitResult.cancel();
                        } else if (currentInteractTarget instanceof BlockHitResult hit
                                && hit.getType() == HitResult.Type.BLOCK
                                && interactBlock(hit)) {
                            hitResult.cancel();
                        }
                    }
                }
            }
        }
    }

    public boolean interactEntity(Entity target) {
        if (entityMode.get().isLegal()) {
            return processLegalInteract(target);
        } else {
            return processIllegalInteract(target);
        }
    }

    private boolean processLegalInteract(Entity target) {
        var player = mc.player;
        if (player == null) return false;
        // todo: pitch yaw fix;
        // do not add mace or tp attack in legal mode

        // mace attack, use item attack, need, delay
        return switch (entityMode.get()) {
            case DELAY_MOVEMENT -> processDelayMovementInteract(target);
            case LEGACY_SLIENT_ROT -> processLegacySnapInteract(target);
            case NONE -> {
                InteractionTasks.interactEntity(mc.player, target, InteractionHand.MAIN_HAND, swingHandEntity.get());
                yield true;
            }
        };
    }

    private final Random attackOffsetRand = new Random();

    private boolean processDelayMovementInteract(Entity target) {
        final double attackRange = CombatExtra.INSTANCE.getAttackAtTargetRange(target);
        // remove crosshairTarget judge, use
        boolean canDirectlyHit = RaycastUtils.canRaycastHit(
                mc.player,
                PlayerStateManager.INSTANCE.lastPitch,
                PlayerStateManager.INSTANCE.lastYaw,
                target,
                attackRange);
        if (canDirectlyHit) {
            // already actioned in caller
            // may not actioned in caller, fix it
            InteractionTasks.interactEntity(mc.player, target, InteractionHand.MAIN_HAND, swingHandEntity.get());
            return true;
        } else {
            // 提前转向 下个tick就有正确的velocity了
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

                            // step back our position
                            velocity = args.getDeltaMovement();
                            Vec3 predictedEyePos = TargetSelector.INSTANCE.getBestAttackEyePos(
                                    mc.player.position(), target.getBoundingBox()); // mc.player.getEyePos();
                            // revert shit
                            if (args.isFallFlying()) {
                                // fix targeting in big velocity
                                predictedEyePos = predictedEyePos.add(
                                        mc.player.getDeltaMovement()); // predictedEyePos.add(mc.player.getVelocity());
                            }
                            Vec3 vec3d = args.position();
                            if (tpInteract.get().positive()
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
                            if (lookVec != null && !args.isFallFlying()) {
                                // rewrite input to fit lookVec
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
                                postInteract(args, target);
                                if (posDelta != Vec3.ZERO) {
                                    Vec3 trueDelta = args.position().subtract(posDelta2); // .subtract(0, 0.2, 0);// =
                                    args.setPos(posDelta);
                                    // args.move(MovementType.PLAYER, posDelta.subtract(args.getPos()));
                                    args.move(MoverType.PLAYER, trueDelta);
                                    posDelta = posDelta2 = Vec3.ZERO;
                                }
                            }
                            // return do not kept
                            return false;
                        }
                    });
            return true;
        }
    }

    private void postInteract(Player player, Entity target) {
        // consider post
        InteractionTasks.interactEntity(mc.player, target, InteractionHand.MAIN_HAND, swingHandEntity.get());
    }

    private boolean processLegacySnapInteract(Entity target) {

        double attackRange = CombatTasks.getCombatExtra().getAttackAtTargetRange(target);
        boolean canDirectlyHit = RaycastUtils.canRaycastHit(
                mc.player,
                PlayerStateManager.INSTANCE.lastPitch,
                PlayerStateManager.INSTANCE.lastYaw,
                target,
                attackRange);
        if (canDirectlyHit) {
            // already actioned in caller
            // may not actioned in caller, fix it
            InteractionTasks.interactEntity(mc.player, target, InteractionHand.MAIN_HAND, swingHandEntity.get());
            return true;
        }
        Vec3 vec3d = mc.player.position();
        Vec3 predictedEyePos = TargetSelector.INSTANCE.getBestAttackEyePos(vec3d, target.getBoundingBox());
        boolean distancePassAttack =
                TargetSelector.INSTANCE.isWithinAttackRange(vec3d, mc.player.getBoundingBox(), attackRange);
        if (tpInteract.get().positive() && !distancePassAttack) {

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

            if (mc.player.isUsingItem()) {
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
            // mace
            LegacySnapRotManager.INSTANCE.snapAt(cacheDirection, false);
            InteractionTasks.interactEntity(mc.player, target, InteractionHand.MAIN_HAND, swingHandEntity.get());
        }
        return true;
    }
    // shit mountains copied from Attack
    private boolean processIllegalInteract(Entity target) {
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
        boolean useTp = canUseTp();
        boolean useExactAttack = useTp && (!alreadyInRange);
        boolean currentSuccessful = true;
        boolean vanillaSuccessful = false;
        Vec3 top = movementStack.peekLast().vec3d();
        if (alreadyAtTarget) {
            vanillaSuccessful = true;
        } else if (target.getBoundingBox().distanceToSqr(top.add(0, mc.player.getEyeHeight(), 0))
                <= MathUtils.s2(CombatTasks.getCombatExtra().getAttackRange())) {
            vanillaSuccessful = true;
        }
        // exact attack
        if (useExactAttack) {
            if (processExactInteract(player, target, movementStack, shouldMoveBackStack, vanillaSuccessful, useTp)) {
                currentSuccessful = true;
            } else {
                currentSuccessful = false;
            }
        } else {
            currentSuccessful = vanillaSuccessful;
        }

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
            InteractionTasks.interactEntity(player, target, InteractionHand.MAIN_HAND, swingHandEntity.get());
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

            // force resync position to origin
            if (useTp && (!shouldMoveBackStack.isEmpty() || !movementStack.isEmpty())) {
                mc.player.setPos(currentStartPos);
                // feature
                MovTasks.setupAutoResync();
            }
            // check fall damage
            List<MovTasks.MovInfo> movementList = Streams.concat(movementStack.stream(), shouldMoveBackStack.stream())
                    .toList();

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
            return true;
        }
        // next, can continue
        return false;
    }

    private boolean processExactInteract(
            Player player,
            Entity target,
            Deque<MovTasks.MovInfo> movementStack,
            Deque<MovTasks.MovInfo> shouldMoveBackStack,
            boolean vanillaSuccess,
            boolean useTp) {
        // how to manage exact attack and mace hack
        // fixed : can not tp to shulker insidef
        // should teleport the player to the pos of target entity
        PositionPredict positionPredict = CombatTasks.getPositionPredict();
        if (vanillaSuccess) {
            if (!positionPredict.considerAntiShield(target)) {
                return true;
            }
        }
        if (!useTp) {
            return false;
        }
        double range = CombatExtra.INSTANCE.getAttackAtTargetRange(player)
                + tpInteract.get().getValue();
        Vec3 current = player.position();
        // feat : teleporting position should met the need of antishield
        Vec3 targetPos = positionPredict.getExactAttackPosition(target);

        if (targetPos != null) {
            // common atttack?
            List<Vec3> tpSequence = MovTasks.generateTpSequence(current, targetPos, false, 1.5 * range, true);
            List<Vec3> tpSequenceBack = MovTasks.generateTpSequence(targetPos, current, false, 1.5 * range, true);
            if ((tpSequence.size() == 2 || tpSequence.size() == 4)
                    && (tpSequenceBack.size() == 2 || tpSequenceBack.size() == 4)) {
                // correct tp sequence
                // try compact mace hack

                if (tpSequence.size() == 2) {
                    // can directly tp
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(1)));

                } else {
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(1)));
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(2)));
                    movementStack.addLast(MovTasks.MovInfo.createNotOnGround(tpSequence.get(3)));
                }
                //  Debug.info(movementStack);
                int size = tpSequenceBack.size();

                for (int i = size - 2; i >= 0; --i) {
                    shouldMoveBackStack.addFirst(MovTasks.MovInfo.create(tpSequenceBack.get(i)));
                }
                return true;
            }
        }
        return vanillaSuccess;
    }

    @ApiMethod
    public boolean placeBlock(BlockPos pos) {
        FlagEntry<BlockHitResult> hitResult = InteractionTasks.getPlaceSupportingResult(
                pos, blockAirPlace.get(), !blockMode.get().isLegal());
        if (InteractUtils.canInteractAndPlace(mc.player, hitResult)) {
            return interactBlock(hitResult.val());
        } else return false;
    }

    @ApiMethod
    public boolean placeBlockStrict(BlockPos pos, BlockState state) {
        FlagEntry<BlockHitResult> hitResult = InteractionTasks.getPlaceSupportingResult(
                pos, blockAirPlace.get(), !blockMode.get().isLegal());
        if (InteractUtils.canInteractAndPlace(mc.player, hitResult)) {
            BlockRotate.INSTANCE.addTempStateSchematic(pos, state);
            return interactBlock(hitResult.val());
        } else return false;
    }

    @ApiMethod
    public FlagEntry<BlockHitResult> getPlaceSupportingResult(
            Vec3 playerPos, BlockPos blockPos, Direction preferredDirection) {
        return InteractionTasks.getPlaceSupportingResult(
                playerPos,
                blockPos,
                preferredDirection,
                blockAirPlace.get(),
                !blockMode.get().isLegal());
    }

    @ApiMethod
    public List<FlagEntry<BlockHitResult>> getAllPlaceSupportingResult(
            Vec3 playerPos, BlockPos blockPos, Direction preferredDirection) {
        return InteractionTasks.getAllPlaceSupportingResult(
                playerPos,
                blockPos,
                preferredDirection,
                blockAirPlace.get(),
                !blockMode.get().isLegal());
    }

    @ApiMethod
    public boolean interactBlock(BlockPos pos) {
        return interactBlock(InteractionTasks.createHitResult(pos, mc.player.position()));
    }

    @ApiMethod
    public boolean interactBlock(BlockHitResult hitResult) {
        double reach = InteractExtra.INSTANCE.getBlockReachDistance();
        boolean isWithinDistance =
                InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), hitResult.getBlockPos(), reach);
        boolean canDirectlyHit = RaycastUtils.canRaycastHit(
                mc.player,
                PlayerStateManager.INSTANCE.lastPitch,
                PlayerStateManager.INSTANCE.lastYaw,
                hitResult.getBlockPos(),
                reach);
        if (canDirectlyHit) {
            InteractionTasks.interactBlock(InteractionHand.MAIN_HAND, hitResult, swingHandBlock.get());
            return true;
        }
        switch (blockMode.get()) {
            case NONE -> {
                if (canUseTp() && !isWithinDistance) {
                    return TpInteract.INSTANCE.tpAndInteractBlock(hitResult, InteractionHand.MAIN_HAND, swingHandBlock.get());
                }
                InteractionTasks.interactBlock(InteractionHand.MAIN_HAND, hitResult, swingHandBlock.get());
                return true;
            }
            default -> {
                if (isWithinDistance) {
                    InteractionTasks.handlePlaceMode(blockMode.get(), hitResult, InteractionHand.MAIN_HAND, swingHandBlock.get());
                    return true;
                }
                return false;
            }
        }
    }

    public void onRender3D(Event<Render3D> event) {
        if (renderAttackTarget.get() && currentInteractTarget != null) {
            float tickDelta = event.context.partialTicks();
            AABB currentBox;
            if (currentInteractTarget instanceof BlockHitResult hitResult
                    && hitResult.getType() == HitResult.Type.BLOCK) {
                BlockPos hitPos = hitResult.getBlockPos();
                BlockState state = mc.level.getBlockState(hitPos);
                VoxelShape shape = state.getShape(mc.level, hitPos);
                if (shape.isEmpty()) {
                    return;
                }
                currentBox = shape.bounds().move(hitPos);
            } else if (currentInteractTarget instanceof EntityHitResult hitResult
                    && hitResult.getType() == HitResult.Type.ENTITY) {
                currentBox = RenderUtils.getLerpedBox(hitResult.getEntity(), tickDelta);
            } else {
                return;
            }
            RenderUtils.startDrawVirtual(event.context.stack());
            try {
                float dist = (float) currentBox
                        .getCenter()
                        .subtract(mc.player.getEyePosition())
                        .length();
                float opacity = Math.min(0.6F, 0.20F + dist * 0.02F);
                RenderUtils.drawSolidBox(
                        event.context.stack(),
                        currentBox.getMinPosition(),
                        currentBox.getMaxPosition(),
                        ColorUtils.withAlpha(renderAttackColor.get().color(), opacity));
            } finally {
                RenderUtils.stopDrawVirtual(event.context.stack());
            }
        }
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        entityMode.set(LegalTargetingMode.getFromPreset(event.context.getValue()));
        blockMode.set(LegalInteractMode.getFromPreset(event.context.getValue()));
    }
}
