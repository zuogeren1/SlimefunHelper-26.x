package me.matl114.hacks.modules.mine;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.utils.NetworkUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class FakeBlockManager extends BaseModule {
    public static FakeBlockManager INSTANCE;

    public FakeBlockManager() {
        super("FakeBlockManager");
        INSTANCE = this;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getWorldSwitchPoint(), this::onWorldSwitch);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundBlockChangedAckPacket.class), this::onBlockACK);
    }

    final Int2ObjectOpenHashMap<BlockPos> fakeMiningBlocks = new Int2ObjectOpenHashMap<>(4);
    final Set<BlockPos> permanentFakeMiningBlocks = new HashSet<>();

    public void onWorldSwitch(Event<Level> event) {
        fakeMiningBlocks.clear();
    }

    public void addFakeCompensateState(BlockPos pos) {
        addFakeCompensateState(pos, false);
    }

    public void addFakeCompensateState(BlockPos pos, boolean force) {
        if (!force && fakeMiningBlocks.containsValue(pos)) {
            return;
        }
        if (Objects.equals(PlayerInteractionAccess.of(mc.gameMode).getCurrentMiningPos(), new BlockPos(-1, -1, -1))) {
            // start to avoid wrong break
            PlayerInteractionAccess.of(mc.gameMode).sendStartBreakPacket(pos);
        }
        Direction direction =
                Direction.getApproximateNearest(mc.player.getEyePosition().subtract(Vec3.atCenterOf(pos)));
        int seq = NetworkUtils.generateNextSequence();
        mc.getConnection()
                .send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction, seq));
        BlockPos pos2 = fakeMiningBlocks.put(seq, pos);
        if (pos2 != null) {
            permanentFakeMiningBlocks.add(pos2);
        }
    }

    public boolean isCurrentlyFakeState(BlockPos pos) {
        return fakeMiningBlocks.containsValue(pos);
    }

    public void addPermanentFakeCompensateState(BlockPos pos) {
        // todo
        // howto: send two packet with same sequence, the first one will be permanent fake state
    }
    // 交互不能产生假方块， see ACK

    public void onBlockACK(Event<ClientboundBlockChangedAckPacket> event) {
        if (fakeMiningBlocks.isEmpty()) {
            return;
        }
        int prediction = event.context.sequence();
        for (Iterator<Int2ObjectMap.Entry<BlockPos>> it =
                        fakeMiningBlocks.int2ObjectEntrySet().iterator();
                it.hasNext(); ) {
            var iter = it.next();
            if (iter.getIntKey() <= prediction) {
                it.remove();
            }
        }
    }
}
