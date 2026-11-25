package me.matl114.hacks.modules.mine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class ChainMine extends BaseModule {
    public ChainMine() {
        super("ChainMine");
        bindFlag(enable);
    }

    public final ModulePath chainMining = makePath(Configs.MINE_CONFIG, "mine-tools.chain-mining");

    public final FlagRef enable = flagBuilder(chainMining.addEnable()).build();
    public final KeyBindRef hotkey = toggleHotkey(chainMining.addHotkey(), new MultiKeyBind(), chainMining.addEnable())
            .build();
    public final IntRef chainDepth = intBuilder(chainMining.add("chain-depth"))
            .defaultValue(8)
            .validator(Configs.intRange(0, 1000))
            .build();
    public final FlagRef whitelistEnabled =
            flagBuilder(chainMining.add("enable-whitelist")).build();
    public final NBTRef<EntrySet<Block>> whitelist = builder(chainMining.add("whitelist"), EntrySet.<Block>parameter())
            .defaultValue(new EntrySet<>(new Regex(".*log|.*ore"), BuiltInRegistries.BLOCK))
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getMineBlockAction(), this::onMineBlockAction);
    }

    private void onMineBlockAction(Event<HitResult> event) {
        if (event.context instanceof BlockHitResult hitResult) {
            submitChainMiningTargets(hitResult.getBlockPos());
        }
    }

    private void submitChainMiningTargets(BlockPos origin) {
        if (!isActive() || mc.player == null || mc.level == null || QueueMine.INSTANCE == null) {
            return;
        }

        BlockState originState = mc.level.getBlockState(origin);
        Block originBlock = originState.getBlock();
        if (!isMineableBlock(originState)
                || (whitelistEnabled.get() && !whitelist.get().test(originBlock))) {
            return;
        }

        Queue<BlockPos> frontier = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> targets = new ArrayList<>();
        frontier.add(origin.immutable());
        visited.add(origin.immutable());
        targets.add(origin.immutable());

        int maxTargets = chainDepth.get();
        while (targets.size() < maxTargets && !frontier.isEmpty()) {
            BlockPos current = frontier.remove();
            for (Direction direction : Direction.values()) {
                if (targets.size() >= maxTargets) {
                    break;
                }

                BlockPos next = current.relative(direction).immutable();
                if (!visited.add(next)) {
                    continue;
                }

                BlockState state = mc.level.getBlockState(next);
                if (state.getBlock() != originBlock
                        || !isMineableBlock(state)
                        || !InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), next)) {
                    continue;
                }

                frontier.add(next);
                targets.add(next);
            }
        }

        for (BlockPos target : targets) {
            QueueMine.INSTANCE.sumitMine(target);
        }
    }

    private boolean isMineableBlock(BlockState state) {
        if (state.isAir() || state.liquid() || state.getBlock().defaultDestroyTime() < 0.0F) {
            return false;
        }
        return true;
    }
}
