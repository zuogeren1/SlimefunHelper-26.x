package me.matl114.hacks.utils.move;

import java.util.List;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.hacks.modules.move.PlayerInputManager;
import me.matl114.hacks.modules.survival.SchedularSettings;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.MathUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

@Accessors(fluent = true, chain = true)
public class AdjustmentSchedular {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final double EDGE_STEP = 1E-2;
    private static final double LINE_LENGTH = 0.8;

    @Setter
    @Getter
    double extraRange = 0;

    @Setter
    @Getter
    Supplier<Vec3> center;

    @Setter
    @Getter
    boolean opposite = false;

    @Setter
    @Getter
    double adjustRange = 1.5F;

    @Setter
    @Getter
    double availableRange = 0.05;

    LocalPlayer player;
    RenderCollector<List<Vec3>> renderCollector = RenderCollectors.createLinesCollector();

    public AdjustmentSchedular() {}

    public void tickAdjustment(LocalPlayer player) {
        renderCollector.clear();
        this.player = player;
        if (player == null || center == null) {
            return;
        }

        if (PathingSchedular.isCurrentPathing()) {
            return;
        }

        Vec3 target = center.get();
        if (target == null) {
            return;
        }

        Vec3 playerCenter = player.position();
        renderCollector.submit(
                List.of(player.position(), target),
                SchedularSettings.INSTANCE.colorLines.get().withAlpha(255));
        if (MathUtils.isInBox(playerCenter, target, availableRange)) {

            return;
        }

        double range = adjustRange + extraRange;
        if (!MathUtils.isInBox(playerCenter.subtract(target), range)) {
            return;
        }

        Vec3 direction = opposite ? playerCenter.subtract(target) : target.subtract(playerCenter);
        Vec3 flatDirection = new Vec3(direction.x, 0, direction.z);
        if (flatDirection.lengthSqr() < 1.0E-8) {
            return;
        }

        int collisionCount = countAdjacentXZCollisions(player, flatDirection);
        PlayerInputManager.Modifier modifier;
        Vec3 renderDirection;
        if (collisionCount >= 2) {
            modifier = null;
            renderDirection = Vec3.ZERO;
        } else if (collisionCount == 1) {
            Vec2 pitchYaw = EntityUtils.rotationToPitchYaw(target.subtract(player.position()));
            renderDirection = flatDirection.normalize();
            modifier = PlayerInputManager.Modifier.empty(0)
                    .forward(true)
                    .backward(false)
                    .left(false)
                    .right(false)
                    .pitch(pitchYaw.x)
                    .yaw(pitchYaw.y);
        } else {
            renderDirection = flatDirection.normalize();
            modifier = buildWasdModifier(player, renderDirection);
        }
        if (modifier != null) {
            PlayerInputManager.INSTANCE.addInputModifier(modifier, 0);
        }
    }

    private int countAdjacentXZCollisions(LocalPlayer player, Vec3 direction) {
        Vec3 step = direction.normalize().scale(EDGE_STEP);
        AABB movedBox = player.getBoundingBox().move(step);
        BlockPos basePos = player.blockPosition();
        int minY = (int) Math.floor(movedBox.minY);
        int maxY = (int) Math.ceil(movedBox.maxY) - 1;
        int collisions = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                boolean collided = false;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(basePos.getX() + dx, y, basePos.getZ() + dz);
                    VoxelShape shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
                    if (shape.isEmpty()) {
                        continue;
                    }

                    for (AABB box : shape.toAabbs()) {
                        if (movedBox.intersects(box.move(pos))) {
                            collided = true;
                            break;
                        }
                    }
                    if (collided) {
                        break;
                    }
                }

                if (collided) {
                    collisions++;
                    if (collisions >= 2) {
                        return collisions;
                    }
                }
            }
        }
        return collisions;
    }

    private PlayerInputManager.Modifier buildWasdModifier(LocalPlayer player, Vec3 direction) {
        double yawRad = Math.toRadians(player.getYRot());
        double forwardAmount = -direction.x * Math.sin(yawRad) + direction.z * Math.cos(yawRad);
        double sidewaysAmount = direction.x * Math.cos(yawRad) + direction.z * Math.sin(yawRad);

        PlayerInputManager.Modifier modifier = PlayerInputManager.Modifier.empty(0)
                .forward(forwardAmount > 1.0E-3)
                .backward(forwardAmount < -1.0E-3)
                .left(sidewaysAmount > 1.0E-3)
                .right(sidewaysAmount < -1.0E-3);

        if (!modifier.forward() && !modifier.backward() && !modifier.left() && !modifier.right()) {
            modifier = modifier.forward(true);
        }
        return modifier;
    }

    public void renderAdjustment(Event<Render3D> event) {
        if (!SchedularSettings.INSTANCE.enableRender.get()) {
            return;
        }
        RenderUtils.startDrawVirtual(event.context.stack());
        try {
            renderCollector.render3D(event.context.stack(), event.context.partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(event.context.stack());
        }
    }
}
