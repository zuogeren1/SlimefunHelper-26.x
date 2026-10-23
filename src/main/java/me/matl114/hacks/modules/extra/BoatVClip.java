package me.matl114.hacks.modules.extra;

import java.util.*;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.combat.CombatExtra;
import me.matl114.hacks.modules.mine.FakeBlockManager;
import me.matl114.hacks.modules.task.MovementManager;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.CollisionUtil;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.InteractUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

public class BoatVClip extends BaseModule implements LegalMovementManager.MovementModifier {
    private static LegalMovementManager.DelegateMovementModifier instance;

    public BoatVClip() {
        super("BoatVClip");
        MovementManager.registerMovementPipeline(this);
        bindFlag(enable);
    }

    public final ModulePath boatVClip = makePath(Configs.EXTRA_CONFIG, "other.boat-vclip");

    public final FlagRef enable = flagBuilder(boatVClip.addEnable()).build();
    public final KeyBindRef hotkey = moduleEntry(boatVClip.addHotkey(), new MultiKeyBind(), boatVClip.addEnable())
            .build();

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        minedBlockPos.clear();
        blockStateMap.clear();
    }

    Map<BlockPos, BlockState> blockStateMap = new HashMap<>();
    Set<BlockPos> minedBlockPos = new HashSet<>();

    Entity lastVehicle;
    int cd = 0;
    Vec3 vec3d = Vec3.ZERO;

    @Override
    public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {
        if (enable.get()) {
            if (!EntityUtils.isEntityValid(lastVehicle)
                    || lastVehicle.getBoundingBox().distanceToSqr(mc.player.getEyePosition())
                            > CombatExtra.INSTANCE.getAttackRange()) {
                lastVehicle = null;
            }
            if (lastVehicle == null) {
                lastVehicle =
                        mc
                                .level
                                .getEntities(
                                        mc.player, mc.player.getBoundingBox().inflate(1.5, 1.5, 1.5))
                                .stream()
                                .filter(s -> s instanceof VehicleEntity)
                                .findAny()
                                .orElse(null);
            }
            if (lastVehicle != null) {
                if (!mc.player.isPassenger()) {
                    if (vec3d == null || vec3d.distanceToSqr(lastVehicle.position()) > 0.25) {
                        vec3d = lastVehicle.position();
                        blockStateMap.clear();
                        ;
                        AABB box = lastVehicle.getBoundingBox();
                        AABB expand = box.setMinY(box.minY - 0.5).setMaxY(box.minY + 0.5);
                        List<BlockPos> boxes = CollisionUtil.getIntersectingBlockPositions(mc.level, expand, false);
                        for (BlockPos boxPos : boxes) {
                            BlockState state = mc.level.getBlockState(boxPos);
                            if (!state.isAir() && !state.liquid()) {
                                blockStateMap.put(boxPos, state);
                            }
                        }
                        for (var re : blockStateMap.entrySet()) {
                            BlockPos pos = re.getKey();
                            //                            Listener.sendPacketNoEvents(new PlayerActionC2SPacket(
                            //                                    PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                            //                                    pos,
                            //                                    Direction.UP,
                            //                                    NetworkUtils.generateNextSequence()));
                            FakeBlockManager.INSTANCE.addFakeCompensateState(pos);
                            cd = 0;
                        }
                    }

                    if (cd > 4) {
                        //
                        cd = 0;
                        AABB boxxx = lastVehicle.getBoundingBox();
                        var re = new EntityHitResult(
                                lastVehicle, boxxx.getCenter().add(0, boxxx.getYsize() / 2, 0));
                        InteractUtils.simulateInteract(re);
                    } else {
                        cd += 1;
                    }
                } else {
                    vec3d = null;
                }
                for (var re : blockStateMap.entrySet()) {
                    BlockPos pos = re.getKey();
                    mc.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    @Override
    public boolean postModify(Event<LegalMovementManager> movementManagerEvent, boolean enabledThisTick) {
        if (enable.get()) {
            //            for(var pos : blockStateMap.entrySet()){
            //                mc.level.setBlockState(pos.getKey(), pos.getValue());
            //            }
            //            blockStateMap.clear();
        }
        return true;
    }
}
