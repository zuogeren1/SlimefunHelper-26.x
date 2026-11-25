package me.matl114.hacks.modules.mine;

import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.MineTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.managers.*;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.Debug;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class MineArua extends BaseModule {
    public MineArua() {
        super("MineArua");
        bindFlag(enable);
    }

    private BlockPos cachePosition;
    private int lastRefreshTick;
    public final ModulePath mineArua = makePath(Configs.MINE_CONFIG, "mine-tools.mine-arua");

    public final FlagRef enable = flagBuilder(mineArua.addEnable()).build();
    public KeyBindRef keyBind = toggleHotkey(mineArua.addHotkey(), new MultiKeyBind(), mineArua.addEnable())
            .build();

    public NBTRef<EntrySet<Block>> whiteListRegex = builder(
                    mineArua.add("block-whitelist"), EntrySet.<Block>parameter())
            .defaultValue(new EntrySet<>(new Regex("^(.*bed)$"), BuiltInRegistries.BLOCK))
            .build();

    public FlagRef autoBreak = flagBuilder(mineArua.add("auto-break")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getMineBlockAction(), this::onMineBlockAction);
        // todo handle doAttackAction redirect, fix bug
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        this.cachePosition = null;
    }

    public void onMineBlockAction(Event<HitResult> event) {
        // todo: sb, rewrite pls sb sb sb sb
        if (false && isActive()) {
            BlockPos pos = refreshMineAruaTarget();
            if (pos != this.cachePosition) {
                // mc.gameMode.sendSequencedPacket(mc.level, );
            }
        }
        if (mc.player != null && isActive()) {
            BlockPos pos = refreshMineAruaTarget();
            if (pos != this.cachePosition) {
                if (pos != null) {
                    Debug.chat(Component.literal("[Mine Arua] Redirect mine target ")
                            .append(ChatUtils.getDisplayedLocation(Vec3.atLowerCornerOf(pos)))
                            .withStyle(ChatFormatting.GREEN));
                }
                this.cachePosition = pos;
                lastRefreshTick = Tasks.getTick();
            }

            if (this.cachePosition != null) {
                Direction dir = Direction.getApproximateNearest(
                                Vec3.atCenterOf(this.cachePosition).subtract(mc.player.getEyePosition()))
                        .getOpposite();
                HitResult hitResult =
                        new BlockHitResult(Vec3.atLowerCornerOf(this.cachePosition), dir, this.cachePosition, false);
                event.context(hitResult);
            }
        }
    }

    // where to place it
    // todo: minearua conflict with optimize

    private boolean isMineAruaTarget(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state != null && !state.isAir() && !state.liquid()) {
            Block block = state.getBlock();
            if (block.defaultDestroyTime() >= 0.0F && whiteListRegex.get().test(block)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos refreshMineAruaTarget() {
        if (mc.player != null && mc.level != null) {
            // every time check if current cache is here
            Vec3 eyepos = mc.player.getEyePosition();
            if (this.cachePosition != null
                    && isMineAruaTarget(mc.level, this.cachePosition)
                    && !MineTasks.distanceOutOfReach(this.cachePosition, eyepos)) {
                return this.cachePosition;
            }
            // refresh only 4 ticks once
            if (Tasks.getTick() >= lastRefreshTick + 4) {
                BlockPos currentBlockPos = mc.player.blockPosition();
                for (var vec : InteractExtra.INSTANCE.getBlocksAround()) {
                    BlockPos pos = currentBlockPos.offset(vec);
                    if (isMineAruaTarget(mc.level, pos) && !MineTasks.distanceOutOfReach(pos, eyepos)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }
}
