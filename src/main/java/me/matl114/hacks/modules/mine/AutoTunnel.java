package me.matl114.hacks.modules.mine;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.RaycastUtils;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AutoTunnel extends BaseModule {
    public AutoTunnel() {
        super("AutoTunnel");
        bindFlag(enable);
    }

    private final ModulePath tunnel = makePath(Configs.MINE_CONFIG, "mine-tools.tunnel");
    private final Set<BlockPos> queuedTargets = new HashSet<>();

    public final FlagRef enable = flagBuilder(tunnel.addEnable()).build();

    public final KeyBindRef hotkey = toggleHotkey(tunnel.addHotkey(), new MultiKeyBind(), tunnel.addEnable())
            .build();
    public final DoubleRef expandWidth = doubleBuilder(tunnel.add("expand-width"))
            .defaultValue(1.0D)
            .validator(Configs.doubleRange(0.0D, 32.0D))
            .build();
    public final DoubleRef expandHeight = doubleBuilder(tunnel.add("expand-height"))
            .defaultValue(1.0D)
            .validator(Configs.doubleRange(0.0D, 32.0D))
            .build();
    public final DoubleRef distance = doubleBuilder(tunnel.add("distance"))
            .defaultValue(3.0D)
            .validator(Configs.doubleRange(0.0D, 64.0D))
            .build();
    public final FlagRef axisLock = flagBuilder(tunnel.add("axis-lock")).build();
    public final FlagRef lockGround = flagBuilder(tunnel.add("lock-ground")).build();
    public final IntRef groundLockDistance = intBuilder(tunnel.add("ground-lock-distance"))
            .defaultValue(3)
            .validator(Configs.intRange(1, 64))
            .show(lockGround::get)
            .build();
    public final RenderCollector<List<Vec3>> lineCollector = RenderCollectors.createLinesCollector();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreGameTick(), this::onAutoTunnelTick);
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        queuedTargets.clear();
    }

    private void onAutoTunnelTick() {
        if (!isActive() || mc.player == null || mc.level == null || QueueMine.INSTANCE == null) {
            return;
        }

        Set<BlockPos> targets = getTunnelTargets();
        removeTargetsOutsideTunnel(targets);

        int availableQueueSlots =
                Math.max(0, QueueMine.INSTANCE.queueSize.get() - QueueMine.INSTANCE.breakRequest.size());
        for (BlockPos target : targets) {
            if (availableQueueSlots <= 0) {
                break;
            }
            if (QueueMine.INSTANCE.sumitMine(target)) {
                queuedTargets.add(target);
                availableQueueSlots--;
            }
        }
    }

    private Set<BlockPos> getTunnelTargets() {
        AABB playerBox = mc.player.getBoundingBox();
        double width = expandWidth.get();
        AABB expandedBox = new AABB(
                playerBox.minX - width,
                playerBox.minY,
                playerBox.minZ - width,
                playerBox.maxX + width,
                playerBox.maxY + expandHeight.get(),
                playerBox.maxZ + width);
        if (lockGround.get()) {
            var groundY = findGroundY(playerBox);
            var down = Math.max(0, expandedBox.minY - groundY);
            expandedBox = expandedBox.move(0, -down, 0);
        }

        float yaw = axisLock.get() ? alignToAxis(mc.player.getYRot()) : mc.player.getYRot();
        Vec3 movement = EntityUtils.pitchYawToRotation(0.0F, yaw)
                .with(Direction.Axis.Y, 0.0D)
                .normalize()
                .scale(distance.get());
        Set<BlockPos> targets = new LinkedHashSet<>();
        Iterator<BlockPos> blockIterator = RaycastUtils.createRaycastBlockPosIterator(expandedBox, movement, false);
        while (blockIterator.hasNext()) {
            BlockPos target = blockIterator.next();
            if (!InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), target)) {
                continue;
            }
            if (isMineable(mc.level.getBlockState(target))) {
                targets.add(target.immutable());
            }
        }
        return targets;
    }

    private double findGroundY(AABB playerBox) {
        double searchMinY = playerBox.minY - groundLockDistance.get();
        double searchMaxY = playerBox.minY + 1.0E-7D;
        AABB searchBox = new AABB(playerBox.minX, searchMinY, playerBox.minZ, playerBox.maxX, searchMaxY, playerBox.maxZ);
        double groundY = Double.NEGATIVE_INFINITY;
        for (var collisionShape : mc.level.getBlockCollisions(mc.player, searchBox)) {
            for (AABB collisionBox : collisionShape.toAabbs()) {
                double topY = collisionBox.maxY;
                if (topY > searchMinY && topY <= searchMaxY) {
                    groundY = Math.max(groundY, topY);
                }
            }
        }
        return Double.isFinite(groundY) ? groundY : playerBox.minY;
    }

    private void removeTargetsOutsideTunnel(Set<BlockPos> targets) {
        Iterator<BlockPos> iterator = queuedTargets.iterator();
        while (iterator.hasNext()) {
            BlockPos target = iterator.next();
            if (!QueueMine.INSTANCE.breakRequest.contains(target)) {
                iterator.remove();
            } else if (!targets.contains(target)) {
                QueueMine.INSTANCE.breakRequest.remove(target);
                iterator.remove();
            }
        }
    }

    private float alignToAxis(float currentYaw) {
        float yaw = EntityUtils.normalizeYaw(currentYaw);
        return EntityUtils.normalizeYaw(Math.round(yaw / 45.0F) * 45.0F);
    }

    private boolean isMineable(BlockState state) {
        if (state.isAir() || state.liquid() || state.getBlock().defaultDestroyTime() < 0.0F) {
            return false;
        }
        return PacketMine.INSTANCE == null || PacketMine.INSTANCE.isMineable(state);
    }
}
