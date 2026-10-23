package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.datafixers.util.Pair;
import java.util.Objects;
import javax.annotation.Nullable;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.hacks.CombatTasks;
import me.matl114.hacks.modules.task.DisablerManager;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.mine.MineExtra;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.managers.Tasks;
import me.matl114.utils.AttributeUtils;
import me.matl114.utils.ItemStackUtils;
import me.matl114.utils.WorldUtils;
import me.matl114.utils.collections.IndexEntry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Environment(EnvType.CLIENT)
@Mixin(MultiPlayerGameMode.class)
public abstract class ClientPlayerInteractionManagerMixin implements PlayerInteractionAccess {
    @Shadow
    private float destroyProgress;

    @Shadow
    private boolean isDestroying;

    @Shadow
    private ItemStack destroyingItem;

    @Shadow
    protected abstract void startPrediction(ClientLevel world, PredictiveAction packetCreator);

    @Shadow
    public abstract boolean destroyBlock(BlockPos pos);

    @Shadow
    private int destroyDelay;

    @Shadow
    private float destroyTicks;

    @Shadow
    private BlockPos destroyBlockPos;

    /**
     * doubleBreak / failMine 使用的备用挖掘槽位。
     *
     * <p>主挖掘位置切走后，旧位置如果仍值得继续复用，就暂存在这里，等待后续 stop 或自动完成逻辑消费。
     */
    @Nullable
    @Unique
    private BlockPos currentFailBreakPos = null;

    /**
     * failBreak 槽位建立时对应的 start tick。
     *
     * <p>它和 {@link #currentFailBreakPos} 一起构成“备用挖掘会话”的最小状态，用于按服务端 start/stop
     * 状态机推导理论进度，而不是依赖客户端原版破坏动画。
     */
    @Unique
    private int failBreakStartTick;

    /**
     * 返回当前主挖掘槽位绑定的位置。
     *
     * <p>这是服务端后续 stop 包默认要对应的位置，也是 optimizeOneBlock 复用的主状态位。
     */
    @Override
    public BlockPos getCurrentMiningPos() {
        return destroyBlockPos;
    }

    /**
     * 本地清空当前主挖掘位。
     *
     * <p>这里只处理客户端会话态，不主动补发 stop。调用方通常在确定该上下文已经无效、或者需要显式重建
     * start 上下文时使用它。
     */
    @Override
    public void resetCurrentMiningPos() {
        destroyBlockPos = new BlockPos(-1, -1, -1);
        resetLocalMiningProgress();
    }

    /**
     * 读取当前 failBreak 槽位。
     *
     * <p>这里直接暴露当前备用槽位本身，不再由 doubleBreak 开关决定可见性；是否允许写入或消费该槽位，
     * 由具体调用路径自行判断。
     */
    @Override
    @Nullable
    public BlockPos getCurrentFailBreakPos() {
        return currentFailBreakPos;
    }

    /**
     * 判断 failBreak 槽位当前是否空闲。
     *
     * <p>这是对外暴露的稳定语义，调用方不需要再依赖 null 细节自行拼装状态判断。
     */
    @Override
    @Unique
    public boolean isFailBreakEmpty() {
        return currentFailBreakPos == null;
    }

    @Override
    @Unique
    public int getCurrentMiningTicks() {
        return Tasks.getTick() - MineExtra.INSTANCE.lastStartMineBreakingProgressResetTick;
    }

    @Override
    @Unique
    public int getFailBreakMiningTicks() {
        return Tasks.getTick() - failBreakStartTick;
    }

    @Override
    @Unique
    public int getMiningCooldown() {
        return destroyDelay;
    }

    @Override
    @Unique
    public void setMiningCooldown(int val) {
        destroyDelay = val;
    }
    /**
     * 读取当前主挖掘位进度。
     *
     * <p>传入 null 时，优先返回原版仍然有效的本地缓存进度；只有在 stop 判定显式传入工具时，才按指定工具速度回退到
     * start tick 推导值。
     */
    @Override
    public float getCurrentMiningProgress(@Nullable ItemStack tool) {
        BlockState block = Minecraft.getInstance().level.getBlockState(destroyBlockPos);
        if (block.isAir()) {
            return -1.0F;
        }
        // force return 0 if not mining
        if (!isDestroying && !MineExtra.INSTANCE.optimizeOneBlock.get()) {
            return -1.0F;
        }
        if (tool == null && (isDestroying && sameDestroyTarget(destroyBlockPos))) {
            return this.destroyProgress == 0.0F ? -1.0F : this.destroyProgress;
        }

        ItemStack usedTool = tool == null ? this.minecraft.player.getMainHandItem() : tool;
        return predictCurrentMiningProgressWithTool(usedTool);
    }

    /**
     * 显式建立一个 failBreak 槽位。
     *
     * <p>成功时会同时：
     * <ul>
     *     <li>登记备用位置</li>
     *     <li>把当前主挖掘位切到该位置，便于后续 stop 复用同一套位置语义</li>
     *     <li>记录该会话对应的 start tick</li>
     * </ul>
     *
     * <p>如果槽位已被占用，则拒绝覆盖，避免多个未完成的备用会话互相踩状态。
     */
    @Unique
    public boolean beginFailBreak(BlockPos pos) {
        if (currentFailBreakPos == null) {
            currentFailBreakPos = pos;
            destroyBlockPos = pos;
            failBreakStartTick = MineExtra.INSTANCE.lastStartMineBreakingProgressResetTick;
            return true;
        }
        return false;
    }

    /**
     * 尝试把当前主挖掘位整体迁入 failBreak 槽位。
     *
     * <p>这是 doubleBreak / 切块续挖最常用的入口，用于在开始处理新方块前，先保留旧方块的服务端挖掘上下文。
     */
    @Unique
    public boolean moveCurrentMiningToFailBreak() {
        return beginFailBreak(destroyBlockPos);
    }

    /**
     * 清空 failBreak 槽位和它的时间基线。
     *
     * <p>一旦调用，表示这段备用挖掘上下文已经失效、完成或不再值得继续复用。
     */
    @Unique
    public void clearFailBreak() {
        currentFailBreakPos = null;
        failBreakStartTick = 0;
    }

    /**
     * 只复位本地缓存的破坏进度。
     *
     * <p>这是一个纯本地 helper，不做位置切换，也不修改发包状态，用于把多个 stop/start 分支里的进度清理收口。
     */
    @Unique
    private void resetLocalMiningProgress() {
        destroyProgress = 0.0F;
    }

    /**
     * 清理“原版仍在持续挖掘”的本地标记。
     *
     * <p>很多 bypass 分支在提前 stop 时，都需要先把原版 breaking 标志降下来，避免后续 tick 继续按普通挖掘流推进。
     */
    @Unique
    private void clearBreakingState() {
        this.isDestroying = false;
    }

    /**
     * 执行一次 stop 之后的本地统一收尾。
     *
     * <p>它集中维护三类状态：
     * <ul>
     *     <li>是否清空本地进度缓存</li>
     *     <li>声音冷却归零</li>
     *     <li>交互冷却按 MineExtra 策略重置</li>
     * </ul>
     *
     * <p>这样不同 stop 路径就不需要再各自散写相同字段。
     */
    @Unique
    private void applyPostStopState(boolean resetProgress) {
        if (resetProgress) {
            resetLocalMiningProgress();
        }
        this.destroyTicks = 0.0F;
        this.destroyDelay =
                MineExtra.INSTANCE.cooldownOverride.get() ? MineExtra.INSTANCE.getMiningPacketCooldown(0) : 5;
    }

    @Override
    @Unique
    public void sendBreakPacket(BlockPos pos, Direction direction, boolean silent) {
        this.startPrediction(Minecraft.getInstance().level, (sequence -> {
            if (!silent) {
                destroyBlock(pos);
            }
            return new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
        }));
    }

    @Unique
    private void continueSameBlockMining(BlockPos pos, Direction direction) {
        this.destroyBlockPos = pos;
        this.destroyProgress = getCurrentMiningProgress(null);
        this.destroyDelay = 0;
        this.isDestroying = true;
        this.destroyingItem = this.minecraft.player.getMainHandItem();
        this.minecraft.level.destroyBlockProgress(
                this.minecraft.player.getId(), this.destroyBlockPos, this.getDestroyStage());
        this.continueDestroyBlock(pos, direction);
    }

    @Unique
    private boolean tryAbortCurrentMiningIntoFailBreak() {
        if (!MineExtra.INSTANCE.doubleBreak.get() || !isFailBreakEmpty()) {
            return false;
        }
        LocalPlayer playerEntity = this.minecraft.player;
        if (!playerEntity.isWithinBlockInteractionRange(this.destroyBlockPos, 1.0D)) {
            return false;
        }
        BlockState state = this.minecraft.level.getBlockState(this.destroyBlockPos);
        if (state.isAir() || state.liquid()) {
            return false;
        }
        AttributeUtils.updateAttribute(this.minecraft.player);
        float speed = WorldUtils.calcBlockBreakingDelta(state, this.minecraft.level, destroyBlockPos);
        if (speed <= 0) {
            return false;
        }
        moveCurrentMiningToFailBreak();
        MineExtra.INSTANCE.onPostStopMiningFastBreak(destroyBlockPos, speed, destroyProgress);
        return true;
    }

    /**
     * 判断当前 failBreak 槽位是否已经不值得继续保留。
     *
     * <p>清理条件包括：
     * <ul>
     *     <li>玩家或模式已经不再允许继续按生存挖掘处理</li>
     *     <li>槽位方块已空气化或液体化</li>
     *     <li>按 start tick 推导已经理论完成，不再需要继续挂起</li>
     *     <li>玩家与该位置距离过远，继续复用失去意义</li>
     * </ul>
     */
    @Unique
    private boolean shouldClearFailBreakBecauseInvalidState() {
        if (this.minecraft.level == null) {
            return true;
        }
        BlockState state = this.minecraft.level.getBlockState(currentFailBreakPos);
        if (minecraft.player == null || localPlayerMode != GameType.SURVIVAL) {
            return true;
        }
        if (state == null || state.isAir() || state.liquid()) {
            return true;
        }
        AttributeUtils.updateAttribute(this.minecraft.player);
        float speed = WorldUtils.calcBlockBreakingDelta(state, this.minecraft.level, currentFailBreakPos);
        // in the case of server lag
        if (speed > 0.0F && ((Tasks.getTick() - failBreakStartTick - 1) * speed > 1.0F)) {
            return true;
        }
        return minecraft.player != null
                && minecraft.player.position().distanceToSqr(Vec3.atCenterOf(destroyBlockPos)) > 225;
    }

    @Shadow
    private GameType localPlayerMode;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private ClientPacketListener connection;

    @Unique
    public boolean calculateInstantBlockBreakingDeltaWithGhostHand(BlockState instance, BlockPos pos) {
        if (MineExtra.INSTANCE.ghostHandMine.get()) {
            var bestTool = MineExtra.INSTANCE.getGhostHandMiningTool(instance);
            if (MineExtra.INSTANCE.ghostHandSwapWhenStart.get()
                    || WorldUtils.calcBlockBreakingDelta(
                                    instance,
                                    minecraft.level,
                                    pos,
                                    WorldUtils.getPlayerBlockBreakingSpeedWithCanMineMultiply(
                                            minecraft.player, instance, bestTool.val()))
                            > 1.01) {
                MineExtra.INSTANCE.instaBreakGhostHand =
                        Pair.of(InvExtra.INSTANCE.swapItemToHand(bestTool.index(), false, GhostHandMode.INV_SWAP), pos);
                return true;
            }
        }
        return false;
    }
    /**
     * 对外暴露的 start 语义入口。
     *
     * <p>调用时会复位本地进度，并在非 instant break 情况下切换主挖掘位置。这样外部模块就不需要再知道
     * “什么时候改 currentBreakingPos、什么时候只发 start 包” 这类内部细节。
     */
    @Override
    @Unique
    public void startMiningBlock(BlockPos pos, Direction direction) {
        this.startPrediction(Minecraft.getInstance().level, (sequence -> {
            BlockState state = minecraft.level.getBlockState(pos);
            DisablerManager.INSTANCE.flushACPlaceBreakQueue();
            if (this.minecraft.player.getAbilities().instabuild
                    || (!state.isAir()
                            && (calculateInstantBlockBreakingDeltaWithGhostHand(state, pos)
                                    || state.getDestroyProgress(minecraft.player, minecraft.level, pos) > 1.0))) {
                this.destroyBlock(pos);
                // insta break do not change current breaking pos
            } else {
                resetLocalMiningProgress();
                destroyBlockPos = pos;
            }
            return new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction, sequence);
        }));
    }

    public void abortBreak(Direction direction) {
        this.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, this.destroyBlockPos, direction));
    }

    @Override
    @Unique
    public void syncSelectedHotbar(int x) {
        minecraft.player.getInventory().setSelectedSlot(x);
        this.ensureHasSentCarriedItem();
    }
    //    public void autoSendStopPacket(){
    //        if(currentBreakingPos != null){
    //            sendStopBreakPacket(currentBreakingPos, Direction.UP);
    //        }
    //    }

    @Unique
    public boolean breakIfComplete() {
        BlockState state = this.minecraft.level.getBlockState(destroyBlockPos);
        if (state.isAir() || state.liquid()) {
            return true;
        }
        Vec3 shouldFacing = Vec3.atCenterOf(destroyBlockPos)
                .subtract(Minecraft.getInstance().player.getEyePosition());
        Direction direction = Direction.getApproximateNearest(shouldFacing).getOpposite();
        return breakIfComplete(destroyBlockPos, state, direction);
    }

    @Unique
    public boolean breakIfComplete(BlockPos pos, BlockState blockState, Direction direction) {
        if (blockState.isAir() || blockState.liquid()) {
            return true;
        }
        MineExtra mineExtra = MineExtra.INSTANCE;
        IndexEntry<ItemStack> tool = MineExtra.INSTANCE.getGhostHandMiningTool(blockState);
        float progress =
                predictCurrentMiningProgressWithTool(tool.val(), MineExtra.INSTANCE.breakSpeedExtraTicks.get());
        if (mineExtra.shouldExecuteFastBreak(progress)) {
            this.destroyProgress = progress;
            DisablerManager.INSTANCE.flushACPlaceBreakQueue();
            clearBreakingState();
            Runnable fastBreakGhostHand = InvExtra.INSTANCE.swapItemToHand(tool.index(), false, GhostHandMode.INV_SWAP);
            AttributeUtils.updateAttribute(this.minecraft.player);
            float speed = blockState.getDestroyProgress(Minecraft.getInstance().player, this.minecraft.level, pos);
            this.startPrediction(Minecraft.getInstance().level, (sequence) -> {
                this.destroyBlock(pos);
                return new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
            });
            if (fastBreakGhostHand != null) {
                fastBreakGhostHand.run();
            }
            mineExtra.onPostStopMiningFastBreak(pos, speed, this.destroyProgress);
            applyPostStopState(!mineExtra.optimizeOneBlock.get());
            return true;
        }
        return false;
    }

    // speed up with early packet when progress>0.7
    @Inject(
            method = "continueDestroyBlock",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/tutorial/Tutorial;onDestroyBlock(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;F)V",
                            ordinal = 1,
                            shift = At.Shift.AFTER),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILSOFT)
    public void fastbreak(
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir,
            net.minecraft.world.level.block.state.BlockState blockState) {
        if (breakIfComplete(pos, blockState, direction)) {
            cir.setReturnValue(true);
        }
    }

    //
    // fixme: fix
    @Inject(
            method = "startDestroyBlock",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V",
                            ordinal = 1,
                            shift = At.Shift.BEFORE),
            locals = LocalCapture.CAPTURE_FAILHARD,
            cancellable = true)
    public void samePositionOptimize(
            BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir, BlockState blockState) {
        // remove the flag, can work even if fastbreak off
        MineExtra mineExtra = MineExtra.INSTANCE;
        if (mineExtra.optimizeOneBlock.get()) {

            if (Objects.equals(pos, destroyBlockPos)) {

                if (!mineExtra.shouldExecuteOptimizeOneBlock()) {
                    return;
                }
                continueSameBlockMining(pos, direction);
                cir.setReturnValue(true);
            } else {
                float predictedProgress = getCurrentMiningProgress(null);
                if (mineExtra.shouldTryDoubleBreak(predictedProgress)) {
                    sendFailBreakCurrentPos(direction);
                }
            }
        }
    }

    @WrapOperation(
            method = "stopDestroyBlock",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void onDoubleBreak(ClientPacketListener instance, Packet packet, Operation<Void> original) {

        if (!MineExtra.INSTANCE.optimizeOneBlock.get()) {
            // we make optimizeOneBlockMine delay its destroy packet to changing the currentPosition in method
            // sameBlockOptimize
            if (sendFailBreakCurrentPos(null)) {
                return;
            }
        }
        original.call(instance, packet);
    }

    @Unique
    @Override
    public boolean sendFailBreakCurrentPos(@Nullable Direction direction) {
        if (tryAbortCurrentMiningIntoFailBreak()) {
            if (direction == null) {
                Vec3 shouldFacing = Vec3.atCenterOf(destroyBlockPos)
                        .subtract(Minecraft.getInstance().player.getEyePosition());
                direction = Direction.getApproximateNearest(shouldFacing).getOpposite();
            }
            sendBreakPacket(destroyBlockPos, direction, true);
            // ... add cooldown here
            if (MineExtra.INSTANCE.cooldownOverride.get()) {
                this.destroyDelay = MineExtra.INSTANCE.getMiningPacketCooldown(0);
            } else {
                this.destroyDelay = 5;
            }
            return true;
        }
        return false;
    }

    @WrapOperation(
            method = "startDestroyBlock",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void onDoubleBreak2(
            ClientPacketListener instance,
            Packet packet,
            Operation<Void> original,
            @Local(argsOnly = true) Direction direction) {
        // conflict with optimizeOneBlock

        if (!MineExtra.INSTANCE.optimizeOneBlock.get()) {
            // we make optimizeOneBlockMine delay its destroy packet to check onDoubleBreakAbort() and  changing the
            // currentPosition in method sameBlockOptimize
            if (sendFailBreakCurrentPos(direction)) {
                return;
            }
        }
        original.call(instance, packet);
    }

    @Shadow
    protected abstract int getDestroyStage();

    @Shadow
    public abstract boolean continueDestroyBlock(BlockPos pos, Direction direction);

    @Shadow
    protected abstract boolean sameDestroyTarget(BlockPos pos);

    @Shadow
    protected abstract void ensureHasSentCarriedItem();

    @Shadow
    protected abstract InteractionResult performUseItemOn(
            LocalPlayer player, InteractionHand hand, BlockHitResult hitResult);

    @Inject(
            method = "startDestroyBlock",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V",
                            ordinal = 0,
                            shift = At.Shift.AFTER),
            locals = LocalCapture.CAPTURE_FAILSOFT)
    public void instaBreakPacket(
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir,
            net.minecraft.world.level.block.state.BlockState blockState) {
        MineExtra.INSTANCE.onStartingMine(pos, Float.MAX_VALUE, true);
    }

    @Inject(
            method = "startDestroyBlock",
            at =
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;destroyDelay:I",
                            shift = At.Shift.BEFORE),
            locals = LocalCapture.CAPTURE_FAILSOFT,
            cancellable = true)
    public void fastBreakCreative(
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir,
            net.minecraft.world.level.block.state.BlockState blockState) {
        applyPostStopState(false);
        if (MineExtra.INSTANCE.quickMine.get()) {
            cir.setReturnValue(true);
        }
    }

    @WrapOperation(
            // 26.2: 中间名已不存在，对应 lambda$startDestroyBlock$1(BlockState, BlockPos, Direction, int)
            method = "lambda$startDestroyBlock$1",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/state/BlockState;getDestroyProgress(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
    public float fastBreakGhostHand(
            BlockState instance, Player player, BlockGetter blockView, BlockPos blockPos, Operation<Float> original) {
        if (calculateInstantBlockBreakingDeltaWithGhostHand(instance, blockPos)) {
            AttributeUtils.updateAttribute(player);
        }
        return original.call(instance, player, blockView, blockPos);
    }

    @Inject(
            method = "startDestroyBlock",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V",
                            ordinal = 1,
                            shift = At.Shift.AFTER),
            locals = LocalCapture.CAPTURE_FAILSOFT)
    public void earlyBreakPacket(
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir,
            net.minecraft.world.level.block.state.BlockState blockState) {
        float speed;
        var usingTool = MineExtra.INSTANCE.getGhostHandMiningTool(blockState);
        float playerSpeed = WorldUtils.getPlayerBlockBreakingSpeedWithCanMineMultiply(
                this.minecraft.player, blockState, usingTool.val());
        speed = WorldUtils.calcBlockBreakingDelta(blockState, this.minecraft.level, pos, playerSpeed);

        MineExtra mineExtra = MineExtra.INSTANCE;
        mineExtra.onStartingMine(pos, speed, false);
        if (!mineExtra.shouldUseQuickMine() || blockState.isAir()) {
            return;
        }
        if (mineExtra.shouldTriggerEarlyStop(speed)) {
            DisablerManager.INSTANCE.flushACPlaceBreakQueue();
            Runnable fastbreakCallback =
                    InvExtra.INSTANCE.swapItemToHand(usingTool.index(), false, GhostHandMode.INV_SWAP);
            AttributeUtils.updateAttribute(this.minecraft.player);
            clearBreakingState();
            this.startPrediction(Minecraft.getInstance().level, (sequence) -> {
                this.destroyBlock(pos);
                return new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
            });
            if (fastbreakCallback != null) {
                fastbreakCallback.run();
            }
            mineExtra.onPostStopMiningFastBreak(pos, speed, this.destroyProgress);
            applyPostStopState(!mineExtra.optimizeOneBlock.get());
        }
    }

    @Inject(
            method = "continueDestroyBlock",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V",
                            ordinal = 0,
                            shift = At.Shift.AFTER),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILSOFT)
    public void instaBreakPacketWhenUpdate(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        MineExtra.INSTANCE.onStartingMine(pos, Float.MAX_VALUE, true);
        applyPostStopState(false);
    }

    @Inject(
            method = "continueDestroyBlock",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V",
                            ordinal = 1,
                            shift = At.Shift.AFTER))
    private void onCommonBlockBreak(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        MineExtra.INSTANCE.onPostStopMiningLegally(pos);
    }

    //    @Inject(method = "getReachDistance",at = @At(value = "HEAD"),cancellable = true)
    //    public void widerReachDistance(CallbackInfoReturnable<Float> cir){
    //
    //    }
    @Inject(method = "hasMissTime", at = @At(value = "HEAD"), cancellable = true)
    public void cancelAttackSpeedLimit(CallbackInfoReturnable<Boolean> cir) {
        if (CombatTasks.getCombatExtra().noCooldown.get()) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    private int lastBreakCooldown = 0;

    @Inject(method = "tick", at = @At("RETURN"))
    public void onTick(CallbackInfo ci) {
        // tick cooldown when not pressing
        if (MineExtra.INSTANCE.fasterVanillaBreak.get()) {
            if (lastBreakCooldown != destroyDelay) {
                lastBreakCooldown = destroyDelay;
            } else if (destroyDelay > 0) {
                destroyDelay--;
                lastBreakCooldown = destroyDelay;
            }
        }
        if (!isFailBreakEmpty() && shouldClearFailBreakBecauseInvalidState()) {
            clearFailBreak();
        }
    }

    @ModifyExpressionValue(
            method = "handleContainerInput",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/world/entity/player/Player;containerMenu:Lnet/minecraft/world/inventory/AbstractContainerMenu;"))
    public AbstractContainerMenu onClickSlot(AbstractContainerMenu original, @Local(argsOnly = true) Player player) {
        return player instanceof ClientPlayerAccess clientPlayer ? clientPlayer.getServerScreenHandler() : original;
    }

    @Inject(method = "sameDestroyTarget", at = @At("HEAD"), cancellable = true)
    public void onCurrentlyBreaking(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        // completely ignore the damage change
        cir.setReturnValue(Objects.equals(pos, destroyBlockPos)
                && ItemStackUtils.matchItemMiningAbility(this.minecraft.player.getMainHandItem(), this.destroyingItem));
    }

    @Inject(
            method = "useItem",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;ensureHasSentCarriedItem()V",
                            shift = At.Shift.AFTER),
            order = -114514)
    private void onInteractPreSend(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        LegacySnapRotManager.INSTANCE.betweenViaPacket = true;
    }

    @Inject(
            method = "useItem",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lorg/apache/commons/lang3/mutable/MutableObject;<init>()V",
                            remap = false),
            order = 114514)
    private void onInteractPostSend(
            Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        LegacySnapRotManager.INSTANCE.betweenViaPacket = false;
    }

    @Override
    @Unique
    public InteractionResult simulateInteractBlock(InteractionHand hand, BlockHitResult hitResult) {
        return performUseItemOn(this.minecraft.player, hand, hitResult);
    }

    @Override
    @Unique
    public InteractionResult simulateInteractItem(InteractionHand hand) {
        var player = this.minecraft.player;
        ItemStack itemStack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(itemStack)) {
            return InteractionResult.PASS;
        } else {
            InteractionResult actionResult = itemStack.use(this.minecraft.level, player, hand);
            // restore
            player.setItemInHand(hand, itemStack);
            return actionResult;
        }
    }
}
