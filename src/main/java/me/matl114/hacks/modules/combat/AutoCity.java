package me.matl114.hacks.modules.combat;

import java.util.*;
import java.util.function.Predicate;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.BlockBreak;
import me.matl114.hacks.CombatTasks;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.interact.AutoSurround;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.mine.MineExtra;
import me.matl114.hacks.modules.mine.PacketMine;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.MathUtils;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AutoCity extends BaseModule {
    public static AutoCity INSTANCE;
    public final ModulePath combatUtils = makePath(Configs.COMBAT_CONFIG, "combat-utils");
    public final ModulePath autoCity = combatUtils.add("auto-city");

    public AutoCity() {
        super("AutoCity");
        INSTANCE = this;
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(autoCity.add("enable")).build();

    public final KeyBindRef hotkey = moduleEntry(autoCity.add("hotkey"), new MultiKeyBind(), autoCity.add("enable"))
            .build();

    // 是否优先考虑目标脚下/卡脚位置的黑曜石。
    public final FlagRef burrow = builder(autoCity.add("burrow-first"), Boolean.class)
            .defaultValue(true)
            .build();

    // 是否考虑目标脸侧可直接打开缺口的位置。
    public final FlagRef head = flagBuilder(autoCity.add("head-target")).build();

    // 是否允许向下扩展搜索目标下方可挖位置。
    public final FlagRef down = flagBuilder(autoCity.add("down")).build();

    // 是否启用周围一圈的 surround 位置搜索。
    public final FlagRef surround =
            builder(autoCity.add("surround"), Boolean.class).defaultValue(true).build();
    public final FlagRef doubleMineAll =
            flagBuilder(autoCity.add("double-mine-all")).build();

    public final FlagRef doubleMineFaceOnly =
            flagBuilder(autoCity.add("double-mine-face")).build();

    public final FlagRef eatingAbort = builder(autoCity.add("using-item-abort"), Boolean.class)
            .defaultValue(false)
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        // add prio so that it works before place modules and crystal modules
        registerListener(Listener.getPreHandleInputEvents(), this::onInputEvent, -33550336);
        registerListener(PacketMine.getPacketMineAction().getChannel(BlockBreak.Stage.PRE), this::onPrePacketMine);
    }

    Player targetEntity;
    BlockPos targetPos;
    boolean pendingSwitchPos;

    public void refreshTarget() {
        double range = InteractExtra.INSTANCE.getBlockReachDistance() + 2;
        if (!EntityUtils.isEntityValid(targetEntity)
                || targetEntity.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > MathUtils.s2(range)) {
            targetEntity = null;
            targetPos = null;
        }
        if (targetEntity == null) {
            Entity en = CombatTasks.getTargetSelector().searchAttackEntity(range, true, (pl) -> pl instanceof Player);
            if (en instanceof Player pl && pl != mc.player) {
                targetEntity = pl;
            }
        }
    }

    public void onInputEvent(Event<Void> event) {
        if (enable.get()) {
            pendingSwitchPos = false;
            refreshTarget();
            if (eatingAbort.get() && mc.player.isUsingItem()) {
                return;
            }
            if (targetEntity != null) {
                // consider cooldown
                onMine();
            }
        }
    }

    private void onPrePacketMine(Event<BlockBreak> event) {
        if (enable.get()
                && !event.isCancelled()
                && event.canCancel()
                && event.context().stage() == BlockBreak.Stage.PRE
                && pendingSwitchPos) {
            event.cancel();
        }
    }

    private void onMine() {
        AABB box = targetEntity.getBoundingBox();
        Set<BlockPos> outerPoses = new LinkedHashSet<>();
        Set<BlockPos> selfPoses = new LinkedHashSet<>();
        Vec3 pos = mc.player.getEyePosition();
        BlockPos targetEntityPos = targetEntity.blockPosition();
        Predicate<BlockPos> filter = (np) -> InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), np);
        selfPoses.addAll(MathUtils.getOccupiedBlockPositions(box).stream()
                .sorted(Comparator.comparingInt(Vec3i::getY))
                .toList());

        Comparator<BlockPos> blockPosComparator = Comparator.comparingInt((BlockPos v) -> v.getY())
                .thenComparingDouble((BlockPos v) -> v.distSqr(targetEntityPos))
                .thenComparingDouble(v -> MathUtils.getBlockBox(v).distanceToSqr(pos));

        AABB heightTest = box;
        if (head.get()) {
            heightTest = box.expandTowards(0, 0.75, 0);
            MathUtils.getOccupiedBlockPositions(heightTest).stream()
                    .filter(filter)
                    .sorted(blockPosComparator)
                    .forEach(outerPoses::add);
        }
        if (down.get()) {
            heightTest = box.expandTowards(0, -0.75, 0);
            MathUtils.getOccupiedBlockPositions(heightTest).stream()
                    .filter(filter)
                    .sorted(blockPosComparator)
                    .forEach(outerPoses::add);
        }
        if (surround.get()) {
            // check burrow, if target burrow in bedrock then don't waste time mining their feet
            AABB box1 = box.inflate(0.99, 0, 0);
            AABB box2 = box.inflate(0, 0, 0.99);
            BlockPos targetBlockPos = targetEntity.blockPosition();
            if (PacketMine.INSTANCE.isMineable(mc.level.getBlockState(targetBlockPos))) {
                // only mine feet
                box1 = box1.setMaxY(box.minY + 0.5);
                box2 = box2.setMaxY(box.minY + 0.5);
            } else if (box.maxY > targetBlockPos.getY() + 1
                    && PacketMine.INSTANCE.isMineable(mc.level.getBlockState(targetBlockPos.above()))) {
                // mine eye because they burrow themselves in bedrock
                box1 = box1.setMinY(box.minY + 1.0);
                box2 = box2.setMinY(box.minY + 1.0);
            } else {
                // mine whatever. shit
            }
            Set<BlockPos> surround = new HashSet<>();
            surround.addAll(MathUtils.getOccupiedBlockPositions(box1));
            surround.addAll(MathUtils.getOccupiedBlockPositions(box2));
            surround.stream().filter(filter).sorted(blockPosComparator).forEach(outerPoses::add);
        }

        outerPoses.removeAll(selfPoses);
        PlayerInteractionAccess access = PlayerInteractionAccess.of(mc.gameMode);
        BlockPos currentPos = access.getCurrentMiningPos();
        BlockPos nowCurrentFailMinePos = access.getCurrentFailBreakPos();
        // not mining
        boolean working = false;
        boolean switchPosition;
        if (burrow.get()) {
            if (MovTasks.isCollidingWithEnvironment(targetEntity)) {
                switchPosition = (!selfPoses.contains(currentPos)
                                && (nowCurrentFailMinePos == null || !selfPoses.contains(nowCurrentFailMinePos)))
                        || !outerPoses.contains(currentPos);
            } else {
                switchPosition = !outerPoses.contains(currentPos);
            }
        } else {
            switchPosition = !outerPoses.contains(currentPos);
        }
        if (switchPosition) {
            if (MineExtra.INSTANCE.isVanillaMineCooldownComplete(0)) {
                pendingSwitchPos = false;
                Set<BlockPos> surroundPos = new HashSet<>();
                if (AutoSurround.INSTANCE.enable.get()) {
                    surroundPos.addAll(AutoSurround.INSTANCE.getTargetingPos());
                }
                outerPoses.removeAll(surroundPos);
                List<BlockPos> selfPosList = selfPoses.stream().toList();
                List<BlockPos> outerPosList = outerPoses.stream().toList();
                BlockPos currentMinePos = null;
                BlockPos currentFailMinePos = null;
                boolean canFailMine = access.isFailBreakEmpty();
                find_mine_schedule:
                {
                    // process selfPos first
                    for (var bp : selfPosList) {
                        BlockState bs = mc.level.getBlockState(bp);
                        // 只挖硬的 软的可以炸掉
                        if (!bs.isAir()
                                && !bs.liquid()
                                && PacketMine.INSTANCE.isMineable(bs)
                                && bs.getBlock().getExplosionResistance() > 600) {
                            if (Objects.equals(bp, nowCurrentFailMinePos)) {
                                continue;
                            }
                            if (currentFailMinePos == null
                                    && canFailMine
                                    && doubleMineAll.get()
                                    && doubleMineFaceOnly.get()) {
                                currentFailMinePos = bp;
                                continue;
                            }
                            currentMinePos = bp;
                            break find_mine_schedule;
                        }
                    }
                    for (var bp : outerPosList) {
                        BlockState bs = mc.level.getBlockState(bp);
                        if (!bs.isAir() && !bs.liquid() && PacketMine.INSTANCE.isMineable(bs)) {
                            if (Objects.equals(bp, nowCurrentFailMinePos)) {
                                continue;
                            }
                            if (currentFailMinePos == null
                                    && canFailMine
                                    && doubleMineAll.get()
                                    && !doubleMineFaceOnly.get()) {
                                currentFailMinePos = bp;
                                continue;
                            }
                            currentMinePos = bp;
                            break find_mine_schedule;
                        }
                    }
                }
                if (currentFailMinePos != null) {
                    // abort current
                    if (currentMinePos != null) {
                        if (!Objects.equals(currentPos, currentFailMinePos)) {
                            access.sendStartBreakPacket(currentFailMinePos);
                        }
                        access.sendFailBreakCurrentPos(null);
                    } else {
                        currentMinePos = currentFailMinePos;
                        currentFailMinePos = null;
                    }
                }
                if (currentMinePos != null) {
                    working = true;
                    if (!Objects.equals(currentPos, currentMinePos)) {
                        access.sendStartBreakPacket(currentMinePos);
                        // to avoid instant break not changing mining pos
                        if (Objects.equals(access.getCurrentMiningPos(), currentMinePos)) {
                            access.sendAbortBreakPacket();
                        }
                    }
                }
            } else {
                pendingSwitchPos = true;
            }
        } else {
            pendingSwitchPos = false;
            working = true;
        }
        if (working) {
            PacketMine.INSTANCE.tickMine();
        }
    }
}
