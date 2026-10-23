package me.matl114.hacks.modules.mine;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.managers.Tasks;
import me.matl114.utils.ItemStackUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import java.util.Comparator;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.utils.WorldUtils;
import me.matl114.utils.inventory.ItemStackSample;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public class MiningProgressManager extends BaseModule {
    public static MiningProgressManager INSTANCE;

    public MiningProgressManager() {
        super("MiningProgressManager");
        INSTANCE = this;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getEntityRemoveListener().getChannel(EntityTypes.PLAYER), this::onEntityRemove);
        registerListener(Listener.getWorldSwitchPoint(), this::onWorldChange);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundBlockDestructionPacket.class),
                this::onBlockProgressUpdate);
        registerListener(Listener.getPreGameTick(), this::onUpdate);
    }

    final Map<Integer, BlockBreakTracker> trackedMap = new HashMap<>();

    public Map<Integer, BlockBreakTracker> getBreakingMap() {
        return trackedMap;
    }

    public void onEntityRemove(Event<Entity> event) {
        trackedMap.remove(event.context.getId());
    }

    public void onWorldChange() {
        trackedMap.clear();
    }

    public void onBlockProgressUpdate(Event<ClientboundBlockDestructionPacket> eventProgress) {
        int eid = eventProgress.context.getId();
        BlockPos ps = eventProgress.context.getPos();
        if (mc.level.getEntity(eid) instanceof Player pl) {
            int progress = eventProgress.context.getProgress();
            if (progress == 255) {
                // 傻逼吧。
                progress = -1;
            }
            BlockBreakTracker tracker = trackedMap.computeIfAbsent(eid, (v) -> new BlockBreakTracker(pl));
            tracker.pushBreakingProgress(ps, progress);
        }
    }

    public void onUpdate() {
        if (checkNull()) return;
        for (var re : trackedMap.values()) {
            re.tickWorld(mc.level);
        }
    }

    @Getter
    public static class BlockBreakTracker {
        public BlockBreakTracker(Player player) {
            this.player = player;
        }

        public Player player;
        public BlockPos blockPos;
        public int breakingStartTick = 0;
        public int breakingProgress = -1;
        public BlockPos potentialDoubleBreak;
        public int doubleBreakProgress = -1;
        public int potentialDoubleBreakStartTick = 0;

        public void pushBreakingProgress(BlockPos pos, int breakingProgress) {
            if (Objects.equals(blockPos, pos)) {
                this.breakingProgress = breakingProgress;
                updateB(pos);
            } else if (Objects.equals(potentialDoubleBreak, pos)) {
                this.doubleBreakProgress = breakingProgress;
                updateD(pos);
            } else {
                if (blockPos == null) {
                    this.breakingProgress = breakingProgress;
                    updateB(pos);
                } else {
                    if (this.breakingProgress != 0 && this.doubleBreakProgress <= 0) {
                        this.doubleBreakProgress = this.breakingProgress;
                        updateD(this.blockPos);
                    }
                    this.breakingProgress = breakingProgress;
                    updateB(pos);
                }
            }
        }

        public boolean canMine() {
            return canMine(1.0);
        }

        public boolean canMine(double extra) {
            return InteractExtra.INSTANCE.isWithinInteractRange(
                    this.player.position(), this.blockPos, InteractExtra.INSTANCE.getBlockReachDistance() + extra);
        }

        public float predictBreakingProgress() {
            return predictGhostHandBreakSpeed(this.player, this.blockPos) * (Tasks.getTick() - breakingStartTick + 1);
        }

        public float predictDoubleBreakProgress() {
            return predictGhostHandBreakSpeed(this.player, this.potentialDoubleBreak)
                    * (Tasks.getTick() - potentialDoubleBreakStartTick + 1);
        }

        private static float predictGhostHandBreakSpeed(Player player, BlockPos pos) {
            PlayerStateManager.PlayerStatus status = PlayerStateManager.INSTANCE.getPlayerStatus(player);
            BlockState blockState = mc.level.getBlockState(pos);
            ItemStack bestTool = status.trackedInventoryItems.stream()
                    .max(Comparator.comparingDouble(s -> {
                        return WorldUtils.getPlayerBlockBreakingSpeedWithCanMineMultiply(
                                player, blockState, s.sample());
                    }))
                    .map(ItemStackSample::sample)
                    .orElse(ItemStack.EMPTY);
            float speed = WorldUtils.getPlayerBlockBreakingSpeedWithCanMineMultiply(player, blockState, bestTool);
            return WorldUtils.calcBlockBreakingDelta(blockState, mc.level, pos, speed);
        }

        private void updateB(BlockPos pos) {
            if (!Objects.equals(blockPos, pos)) {
                blockPos = pos;
                breakingStartTick = Tasks.getTick();
            }
            if (breakingProgress >= 10) {
                breakingProgress = -1;
            }
        }

        private void updateD(BlockPos pos) {
            if (!Objects.equals(potentialDoubleBreak, pos)) {
                potentialDoubleBreak = pos;
                potentialDoubleBreakStartTick = Tasks.getTick();
            }
            if (doubleBreakProgress >= 10) {
                potentialDoubleBreak = null;
                doubleBreakProgress = -1;
            }
        }

        public void tickWorld(Level world) {
            if (potentialDoubleBreak != null
                    && world.getBlockState(potentialDoubleBreak).isAir()) {
                potentialDoubleBreak = null;
                doubleBreakProgress = -1;
            }
        }
    }
}
