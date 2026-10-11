package me.matl114.hacks.modules.interact;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.*;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class TpInteract extends BaseModule {
    public final ModulePath tpInteract = makePath(Configs.INTERACT_CONFIG, "tp-interact");

    public static TpInteract INSTANCE;

    public TpInteract() {
        super("TpInteract");
        bindFlag(enable);
        INSTANCE = this;
    }

    public final FlagRef enable = flagBuilder(tpInteract.add("enable")).build();

    public final KeyBindRef keyBindRef = moduleEntry(
                    tpInteract.add("enable-hotkey"), new MultiKeyBind(), tpInteract.add("enable"))
            .build();

    public final FlagRef useFallMine =
            flagBuilder(tpInteract.add("mine-interact-use-fail-mine")).build();

    public final KeyBindRef tryTpSteal = hotkey(
                    Configs.INTERACT_CONFIG,
                    tpInteract.add("try-tp-steal-chest-key").toPath())
            .defaultValue(new MultiKeyBind())
            .build();

    /** 桶类远程交互的调试输出（默认开，问题定位完成后关掉）。 */
    public final FlagRef debugLog = flagBuilder(tpInteract.add("debug-log")).build();

    /** 找目标时用的射线长度：要覆盖 freecam 的观察距离（找到目标后我们会传送过去，不受交互距离限制）。 */
    private static final double BUCKET_TARGET_RANGE = 128.0D;

    /** 桶类传送的落点距离：服务端 {@code BucketItem.use} 的射线只有 {@code blockInteractionRange()}（默认 4.5），
     *  所以必须站得比它更近，否则传过去服务端照样 MISS。 */
    private static final double BUCKET_CLOSE_DISTANCE = 3.0D;

    /** 服务端 {@code BucketItem.use} 的重算射线长度（属性默认 4.5）—— 超过这个距离基本必 MISS，只用于报警。 */
    private static final double SERVER_SAFE_DISTANCE = 4.0D;

    /**
     * 本次点击的 useItem 已经由 {@link #onInteractBlock} 在**同一次传送**里补发过了（记录 tick）。
     *
     * <p>桶的一次右键会发 useItemOn + useItem 两条包，各自触发一次传送 —— 两次传送会互相打架
     * （服务端把第二趟当异常移动回弹掉），所以远距离时把两条包合并进同一次传送。按 tick 判定，
     * 天然自清理（原版这两条包一定在同一 tick 内发完）。
     */
    private long bucketMergedTick = Long.MIN_VALUE;

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPacketPoint().getChannel(ServerboundUseItemOnPacket.class), this::onInteractBlock);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundUseItemPacket.class), this::onUseItem);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundInteractPacket.class), this::onInteractEntity);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class), this::onBlockMine);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
    }

    private final float ENABLE_NO_TP_DISTANCE = 1.14f;

    public void onInteractBlock(Event<ServerboundUseItemOnPacket> event) {
        if (event.isCancelled()) return;
        if (enable.get()) {
            var packetToSend = event.context;
            BlockHitResult hit = event.context.getHitResult();
            BlockPos blockPos = hit.getBlockPos();
            double distance = InteractExtra.INSTANCE.getBlockReachDistance() + ENABLE_NO_TP_DISTANCE;
            if (new AABB(blockPos).distanceToSqr(mc.player.getEyePosition()) > MathUtils.s2(distance)) {
                if (!mc.player.isShiftKeyDown() && tryTpSteal.get().isAllPressed()) {
                    int size = InvTasks.predictOpenVanillaContainerSize(blockPos);
                    if (size != 0) {

                        if (tpToBlock(
                                blockPos,
                                (sel) -> executeTp(sel, () -> {
                                    Debug.chat("[TpInteract] 尝试和物品栏交互");
                                    Listener.sendPacketNoEvents(packetToSend);
                                    InvTasks.executePredictInventoryAction(
                                            InventoryUtils.createInventory(Collections.nCopies(size, ItemStack.EMPTY)),
                                            (handler) -> {
                                                for (var i = 0; i < size; ++i) {
                                                    mc.gameMode.handleContainerInput(
                                                            handler.containerId,
                                                            i,
                                                            0,
                                                            ContainerInput.QUICK_MOVE,
                                                            mc.player);
                                                }
                                            });
                                }))) {
                            event.cancel();
                        }
                        return;
                    }
                }
                InteractionHand mergeHand = mergeableBucketHand(packetToSend.getHand(), blockPos);
                if (mergeHand != null) {
                    // 桶类：useItemOn 之后原版还会跟一条 useItem（BucketItem 的 useOn 永远 PASS），
                    // 那条包同样要传送 + 正确朝向 —— 合并进这一次传送，避免两次传送互相打架。
                    BlockHitResult bucketTarget =
                            BucketInteract.povTarget(mc.player.getItemInHand(mergeHand), BUCKET_TARGET_RANGE);
                    if (bucketTarget != null
                            && tpCloseToBlock(blockPos, (sel) -> executeTp(sel, () -> {
                                Listener.sendPacketNoEvents(packetToSend);
                                if (fireBucketUse(mergeHand, bucketTarget)) {
                                    bucketMergedTick = Tasks.getTick();
                                    debugBucket("merged: useItemOn + useItem both sent from %s", sel.toString());
                                }
                            }))) {
                        event.cancel();
                        return;
                    }
                }
                if (tpToBlock(blockPos, (sel) -> executeTp(sel, packetToSend))) {
                    event.cancel();
                }
            }
        }
    }

    /**
     * 桶类物品的远程右键：真正干活的是 {@link ServerboundUseItemPacket}（{@code BucketItem} 只有 {@code use()}，
     * {@code useOn} 永远返回 PASS），而这条包以前没有钩子 —— 它是从**远处的玩家位置**发出去的，
     * 服务端拿包里的朝向自己重算 POV 射线必然 MISS：水桶放不出水、空桶舀不到水。
     *
     * <p>这里补上：传送过去 → 重新解一个「真能命中目标」的朝向 → 改包发出 → 传送回来。
     * 够得着（原版范围内）不干预；解不出命中也不吃原包，保持原行为。
     *
     * <p><b>状态：WIP</b>（2026-10-11）—— 能用但不好用，暂不发版；已知不足见 CHANGELOG 的「未发布（WIP）」段。
     *
     * <p><b>目标不能取 {@code mc.hitResult}</b>：准星那次 pick 是 {@code withLiquids = false}（26.2 的
     * {@code LiquidBlock.getShape()} 还返回空），瞄水面时它命中的是水后面 / 水下的方块；而 freecam 期间
     * 玩家朝向被 Freecam 冻结。所以目标一律用 {@link BucketInteract#povTarget} 按<b>相机实体</b>自己算。
     */
    public void onUseItem(Event<ServerboundUseItemPacket> event) {
        if (event.isCancelled() || !enable.get()) return;
        // 指令路径（!!useitem）的目标是指令给的坐标、不是准星，别按准星传送
        if (InteractManager.commandUsingItem) return;
        // 这一次点击的 useItem 已经由 onInteractBlock 在**同一次传送**里补发过了（合并传送，避免服务端位置回弹）
        if (bucketMergedTick == Tasks.getTick()) {
            bucketMergedTick = Long.MIN_VALUE;
            debugBucket("merged into the useItemOn teleport -> drop vanilla packet");
            event.cancel();
            return;
        }
        ServerboundUseItemPacket packet = event.context;
        ItemStack stack = mc.player.getItemInHand(packet.getHand());
        if (!BucketInteract.shouldHandle(stack)) return;
        // 目标自己按相机实体的 POV 算（空桶 SOURCE_ONLY）—— 见方法头注释：准星看不见水源，且 freecam 下玩家朝向是冻结的
        BlockHitResult target = BucketInteract.povTarget(stack, BUCKET_TARGET_RANGE);
        if (target == null) {
            debugBucket("no target: camera POV clip miss");
            return;
        }
        BlockPos targetPos = target.getBlockPos();
        // ⚠ 判定「原地能不能完成」必须用**服务端**的口径：服务端那次桶射线只有 blockInteractionRange()（默认 4.5），
        // 客户端的 reach 加成它不认。之前用客户端 reach 判定 → 4~10 格的目标被判成「够得着」，直接原地发不管用。
        double serverSafeReach = Math.min(InteractExtra.INSTANCE.getBlockReachDistance(), SERVER_SAFE_DISTANCE);
        double distanceSqr = new AABB(targetPos).distanceToSqr(mc.player.getEyePosition());
        if (distanceSqr <= MathUtils.s2(serverSafeReach)) {
            // 够得着**也不等于原版能用**：freecam 下玩家朝向被冻结，服务端会沿那个冻结朝向重算 → 打偏。
            // 所以这里只把「朝向」换成瞄向目标（不传送），包仍由我们自己发。
            if (mc.getCameraEntity() == mc.player) {
                debugBucket("target %s within reach, camera == player -> vanilla", targetPos.toShortString());
                return;
            }
            boolean fired = fireBucketUse(packet.getHand(), target);
            debugBucket("target %s within reach, freecam -> fix aim in place: fired=%s", targetPos.toShortString(), fired);
            if (fired) {
                event.cancel();
            }
            return;
        }
        boolean[] sent = {false};
        debugBucket("target=%s dist=%.2f > server-safe %.1f -> teleport", targetPos.toShortString(), Math.sqrt(distanceSqr), serverSafeReach);
        boolean tp = tpCloseToBlock(targetPos, (sel) -> executeTp(sel, () -> {
            sent[0] = fireBucketUse(packet.getHand(), target);
        }));
        debugBucket("target=%s tp=%s sent=%s", targetPos.toShortString(), tp, sent[0]);
        if (tp && sent[0]) {
            event.cancel();
        }
    }

    /**
     * 这条 useItemOn 后面是否还会跟一条同手的 useItem（= 值得合并）：只有**纯建筑方块**上的桶点击才算。
     * 会被方块自己吃掉的交互（箱子/熔炉/门/炼药锅…）之后原版不会发 useItem，合并会多发一条包。
     */
    private InteractionHand mergeableBucketHand(InteractionHand hand, BlockPos pos) {
        if (!BucketInteract.isFluidBucket(mc.player.getItemInHand(hand))) {
            return null;
        }
        BlockState state = mc.level.getBlockState(pos);
        boolean plainBlock = state.isCollisionShapeFullBlock(mc.level, pos)
                && state.getMenuProvider(mc.level, pos) == null
                && !(state.getBlock() instanceof LiquidBlockContainer);
        return plainBlock ? hand : null;
    }

    /** 桶类专用传送：优先挑一个「贴脸」的落点。
     *  {@link #tpToBlock} 的第一选择是相机位（freecam 时可能离目标 5 格开外），而服务端的
     *  {@code BucketItem.use} 射线只有 {@code blockInteractionRange()}（属性默认 4.5）—— 传过去也照样 MISS。 */
    private boolean tpCloseToBlock(BlockPos pos, Predicate<Vec3> callBack) {
        Vec3 spot = findCloseSpot(pos);
        if (spot != null) {
            return callBack.test(spot);
        }
        debugBucket("no close spot, fallback to tpToBlock");
        return tpToBlock(pos, callBack);
    }

    /** 在目标方块周围找一个「眼位距它不超过 {@link #BUCKET_CLOSE_DISTANCE}」且不撞环境的落脚点。 */
    private Vec3 findCloseSpot(BlockPos pos) {
        double eyeHeight = mc.player.getEyeHeight(mc.player.getPose());
        for (var deltaPos : InteractExtra.INSTANCE.getBlocksAround()) {
            Vec3 checkPos = Vec3.atBottomCenterOf(pos.offset(deltaPos)).add(0, 1E-4, 0);
            if (new AABB(pos).distanceToSqr(checkPos.add(0, eyeHeight, 0))
                            <= MathUtils.s2(BUCKET_CLOSE_DISTANCE)
                    && !MovTasks.ENGIN.checkEnvironmentCollision(mc.player, checkPos, true)) {
                return checkPos;
            }
        }
        return null;
    }

    private void debugBucket(String fmt, Object... args) {
        if (debugLog.get()) {
            Debug.chat("[TpBucket] " + String.format(fmt, args));
        }
    }

    /**
     * 解出朝向并**自己发**一条 {@code ServerboundUseItemPacket}；成功返回 true。
     *
     * <p>为什么不走 {@code mc.gameMode.useItem}：我们此刻正待在原版 {@code startPrediction} 的预测窗口里
     * （这条包就是那个窗口发的），再嵌一层 {@code startPrediction} 会破坏预测簿记。自己发包 + 由调用方吃掉原包更干净。
     */
    private boolean fireBucketUse(InteractionHand hand, BlockHitResult target) {
        ItemStack stack = mc.player.getItemInHand(hand);
        BlockHitResult solved = BucketInteract.resolveForClick(
                mc.player, stack, target, InteractExtra.INSTANCE.getBlockReachDistance());
        if (solved == null) return false;
        Vec2 look = BucketInteract.lookAt(mc.player, solved.getLocation());
        if (look == null) return false;
        double distance = solved.getLocation().distanceTo(mc.player.getEyePosition());
        if (distance > SERVER_SAFE_DISTANCE) {
            debugBucket("solved hit %.2f blocks away (> %.1f): server will likely MISS", distance, SERVER_SAFE_DISTANCE);
        }
        Listener.sendPacketNoEvents(
                new ServerboundUseItemPacket(hand, NetworkUtils.generateNextSequence(), look.y, look.x));
        return true;
    }

    public void onInteractEntity(Event<ServerboundInteractPacket> event) {
        if (event.isCancelled()) return;
        if (enable.get()) {
            var packet = event.context;
            // 26.2: ServerboundInteractPacket 只剩交互语义（攻击已拆为 ServerboundAttackPacket），
            // 故无需再过滤 ATTACK。
            {
                int entityId = packet.entityId;
                Entity entity = mc.level.getEntity(entityId);
                if (entity != null
                        && entity.getBoundingBox().distanceToSqr(mc.player.getEyePosition())
                                > MathUtils.s2(CombatTasks.getCombatExtra().getAttackRange() + ENABLE_NO_TP_DISTANCE)) {
                    if (tpToEntity(entity, packet)) {
                        event.cancel();
                    }
                }
            }
        }
    }

    public void onBlockMine(Event<ServerboundPlayerActionPacket> event) {
        if (event.isCancelled()) return;
        if (enable.get()) {
            var packet = event.context;
            switch (packet.getAction()) {
                case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                    BlockPos involvedBlock = packet.getPos();
                    // check y;
                    if (involvedBlock == null) return;
                    // filter "out of building height" shit
                    if (involvedBlock.getY() < (mc.level.getMinY() - 1)
                            || involvedBlock.getY() > (mc.level.getMinY() + mc.level.getHeight() + 1)) {
                        return;
                    }
                }
                default -> {
                    return;
                }
            }
            BlockPos blockPos = packet.getPos();
            double distance = InteractExtra.INSTANCE.getBlockReachDistance() + ENABLE_NO_TP_DISTANCE;
            if (new AABB(blockPos).distanceToSqr(mc.player.getEyePosition()) > MathUtils.s2(distance)) {
                ServerboundPlayerActionPacket packetToSend = event.context();
                if (tpToBlock(
                        blockPos,
                        useFallMine.get()
                                ? (selectedPos) -> executeTp(selectedPos, () -> {
                                    mc.getConnection().send(packetToSend);
                                    PlayerInteractionAccess access = PlayerInteractionAccess.of(mc.gameMode);
                                    if (packetToSend.getAction()
                                                    == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                                            && Objects.equals(access.getCurrentMiningPos(), packetToSend.getPos())
                                            && access.getCurrentFailBreakPos() == null) {
                                        access.sendFailBreakCurrentPos(null);
                                    }
                                })
                                : (sel) -> executeTp(sel, packetToSend))) {
                    event.cancel();
                }
            }
        }
    }

    public boolean tpAndInteractBlock(BlockHitResult hitResult, InteractionHand hand, boolean swing) {
        return tpToBlock(
                hitResult.getBlockPos(),
                (sel) -> executeTp(sel, () -> {
                    InteractionTasks.interactBlock(hand, hitResult, swing);
                }));
    }

    public boolean tpToBlock(BlockPos pos, Predicate<Vec3> callBack) {
        // compat Freecam
        Vec3 selectedPos = RenderUtils.getCameraEntityPos();
        double eyeHeight = mc.player.getEyeHeight(mc.player.getPose());
        if (MineTasks.distanceOutOfReach(pos, selectedPos.add(0, eyeHeight, 0))
                || MovTasks.ENGIN.checkEnvironmentCollision(mc.player, selectedPos, true)) {
            selectedPos = null;
            for (var deltaPos : InteractExtra.INSTANCE.getBlocksAround()) {
                Vec3 checkPos = Vec3.atBottomCenterOf(pos.offset(deltaPos)).add(0, 1E-4, 0);
                if (!MineTasks.distanceOutOfReach(pos, checkPos.add(0, eyeHeight, 0))
                        && !MovTasks.ENGIN.checkEnvironmentCollision(mc.player, checkPos, true)) {
                    selectedPos = checkPos;
                    break;
                }
            }
        }
        if (selectedPos == null) {
            logI18NSub("TpAct", "message.module.tp-interact.cannot-reach");
            return false;
        } else {
            return callBack.test(selectedPos);
        }
    }

    public boolean tpToEntity(Entity pos, Packet<?> packetToSend) {
        Vec3 selectedPos = RenderUtils.getCameraEntityPos();
        AABB entityBox = pos.getBoundingBox();
        double attackRange = CombatTasks.getCombatExtra().getAttackRange() + 1.0d;
        double eyeHeight = mc.player.getEyeHeight(mc.player.getPose());
        if (entityBox.distanceToSqr(selectedPos.add(0, eyeHeight, 0)) > MathUtils.s2(attackRange)) {
            selectedPos = null;
            // make an algorithm to
            BlockPos entityPos = pos.blockPosition();
            // todo: move this to CombatExtra or PositionPredictor or something
            for (var deltaPos : InteractExtra.INSTANCE.getBlocksAround()) {
                Vec3 checkPos =
                        Vec3.atBottomCenterOf(entityPos.offset(deltaPos)).add(0, 1E-4, 0);
                if (entityBox.distanceToSqr(checkPos.add(0, eyeHeight, 0)) < MathUtils.s2(attackRange)
                        && !MovTasks.ENGIN.checkEnvironmentCollision(mc.player, checkPos, true)) {
                    selectedPos = checkPos;
                    break;
                }
            }
        }
        if (selectedPos == null) {
            Debug.chat("[TpAct] Can not reach the target");
            return false;
        } else {
            return executeTp(selectedPos, packetToSend);
        }
    }

    public boolean executeTp(Vec3 pos, Runnable callback) {
        Vec3 current = mc.player.position();
        MovTasks.MovingContext context = MovTasks.createPlayerMovContext();
        List<Vec3> from = MovTasks.generateTpSequence(current, pos, false, 200, true);
        List<Vec3> to = MovTasks.generateTpSequence(pos, current, false, 200, true);
        if (from.isEmpty() || to.isEmpty()) {
            Debug.chat("[TpAct] Can not reach the target");
            return false;
        } else {
            if (RenderTasks.DEBUG_RENDER_INTERACTION) {
                RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
                        RenderTasks.DEBUG_TICK,
                        new RenderTasks.BoxObject(
                                mc.player.dimensions.makeBoundingBox(pos),
                                ColorUtils.withAlpha(Color.MAGENTA, 0.25F))));
            }
            List<MovTasks.MovInfo> moveInfo = new ArrayList<>();
            moveInfo.addAll(MovTasks.createMovInfoList(from));
            moveInfo.addAll(MovTasks.createMovInfoList(to));
            var actions = MovTasks.createMovingPacketsForMovSequence(context, moveInfo, false, true);
            for (var i = 0; i < from.size(); ++i) {
                actions.get(i).run();
            }
            callback.run();
            for (int i = from.size(); i < actions.size(); ++i) {
                if (actions.get(i).success) {
                    actions.get(i).run();

                } else {
                    List<MovTasks.MovInfo> leftTasks = moveInfo.subList(i, actions.size());
                    Tasks.scheduleDelayed(
                            () -> {
                                MovTasks.scheduleFarawayMoveInternal(leftTasks, false, context.resetTick(), true);
                            },
                            1);
                    break;
                }
            }
            MovTasks.setupAutoResync();
            ClientPlayerAccess.of(mc.player).setForceNoFall(true);
            return true;
        }
    }

    public boolean executeTp(Vec3 pos, Packet<?>... packetToSend) {
        return executeTp(pos, () -> {
            for (Packet<?> packet : packetToSend) {
                Listener.sendPacketNoEvents(packet);
            }
        });
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        switch (event.context().getValue()) {
            case HACKING, VANILLA -> enable.set(true);
            default -> enable.set(false);
        }
    }
}
