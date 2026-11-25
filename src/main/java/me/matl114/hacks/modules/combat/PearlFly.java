package me.matl114.hacks.modules.combat;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render3D;
import me.matl114.hacks.CombatTasks;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.RenderTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.render.ProjectileESP;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.hacks.utils.PlayerInputUtils;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import me.matl114.hacks.utils.RaycastUtils;
import me.matl114.hacks.utils.RenderUtils;

public class PearlFly extends BaseModule {
    public PearlFly() {
        super("PearlFly");
    }

    public final ModulePath combatUtils = makePath(Configs.COMBAT_CONFIG, "combat-utils");
    public final ModulePath pearl = combatUtils.add("pearl-fly");

    public final ModulePath pearlPhase = combatUtils.add("pearl-phase");
    public final FlagRef enablePearlPhase = flagBuilder(pearlPhase.addEnable()).build();

    public final KeyBindRef hotkey = moduleEntry(pearlPhase.addHotkey(), new MultiKeyBind(), pearlPhase.addEnable())
            .build();

    public final FlagRef enableCrawl =
            flagBuilder(pearlPhase.add("enable-crawl")).build();

    public final FlagRef enableStand =
            flagBuilder(pearlPhase.add("enable-stand")).build();

    public final FlagRef autoCrawl = flagBuilder(pearlPhase.add("auto-crawl")).build();
    public final FlagRef useWASDControl =
            flagBuilder(pearlPhase.add("use-wasd-control")).build();

    public final DoubleRef autoPearlActivateRange = doubleBuilder(pearlPhase.add("auto-activate-range"))
            .defaultValue(0.35)
            .validator(Configs.doubleRange(0.0D, 5.0D))
            .show(() -> !this.useWASDControl.get())
            .build();

    public final DoubleRef wasdPearlActiveRange = doubleBuilder(pearlPhase.add("wasd-activate-range"))
            .defaultValue(1.0)
            .validator(Configs.doubleRange(0.0D, 5.0D))
            .show(this.useWASDControl::get)
            .build();

    public final FlagRef enableJump = builder(pearlPhase.add("enable-jump-up"), Boolean.class)
            .defaultValue(true)
            .build();

    public final ModulePath pearlThrow = combatUtils.add("pearl-throw");

    public final KeyBindRef executePearlThrow = hotkey(pearlThrow.add("execute-throw"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler(this::doPearlThrow))
            .build();

    public final FlagRef pearlAimRender =
            flagBuilder(pearlThrow.add("pearl-trace-render")).build();

    public final KeyBindRef executePearlHelper = toggleHotkey(
                    pearlThrow.add("pearl-trace-render-hotkey"),
                    new MultiKeyBind(),
                    pearlThrow.add("pearl-trace-render"))
            .build();

    public final NBTRef<WrapColor> color = builder(pearlThrow.add("color"), WrapColor.class)
            .defaultValue(new WrapColor((Color.ORANGE)))
            .build();

    public final FlagRef exactDirection = flagBuilder(pearlThrow.add("exact")).build();

    public final EnumRef<GhostHandMode> ghostHand = builder(pearl.add("ghost-hand-mode"), GhostHandMode.class)
            .defaultValue(GhostHandMode.INV_SWAP)
            .build();

    public final FlagRef offhand = flagBuilder(pearl.add("offhand")).build();

    public final FlagRef swingHand =
            builder(pearl.add("swing-hand"), Boolean.class).defaultValue(true).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onInputEvent);
        registerListener(RenderListener.getRender3DEvent(), this::onRender3D);
    }

    Vec2 pearlThrowRotation = null;

    public void onInputEvent() {
        if (checkNull()) return;
        if (enablePearlPhase.get()) {
            var pose = mc.player.getPose();
            if (pose == Pose.SWIMMING) {
                if (!enableCrawl.get()) {
                    return;
                }
            } else {
                if (!enableStand.get()) {
                    return;
                }
            }
            if (mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))) {
                return;
            }
            if (useWASDControl.get()) {
                var input = PlayerInputUtils.of(mc.options);
                if (!input.hasMovementControl()) {
                    return;
                }
                Vec3 facingDirection = EntityUtils.pitchYawToRotation(0, mc.player.getYRot());
                Vec3 adAxis = new Vec3(facingDirection.z, 0, -facingDirection.x);
                Vec3 inputVec = facingDirection
                        .scale(input.forwardSpeed())
                        .add(adAxis.scale(input.sidewaysSpeed()))
                        .normalize()
                        .scale(wasdPearlActiveRange.get());
                if (enableJump.get() && input.upwardSpeed() > 0) {
                    inputVec = inputVec.add(0, input.upwardSpeed(), 0);
                }
                boolean anySolid = false;
                Vec3 targetPos = mc.player.position().add(inputVec);
                Vec3 targetEyePos = mc.player.getEyePosition().add(inputVec);
                for (var re : RaycastUtils.createRaycastBlockPoses(mc.player.position(), targetPos)) {
                    if (!mc.level
                            .getBlockState(re)
                            .getCollisionShape(mc.level, re)
                            .isEmpty()) {
                        anySolid = true;
                        break;
                    }
                }
                if (!anySolid) {
                    for (var re : RaycastUtils.createRaycastBlockPoses(mc.player.getEyePosition(), targetEyePos)) {
                        if (!mc.level
                                .getBlockState(re)
                                .getCollisionShape(mc.level, re)
                                .isEmpty()) {
                            anySolid = true;
                            break;
                        }
                    }
                }
                if (!anySolid) {
                    return;
                }
                BlockPos searchPos = BlockPos.containing(mc.player.position().add(inputVec));

                if (!Objects.equals(mc.player.blockPosition(), searchPos) && doPearlUse(searchPos)) {
                    enablePearlPhase.set(false);
                    return;
                }
            } else {
                AABB playerBox = mc.player.getBoundingBox();
                double extra = autoPearlActivateRange.get();
                double minX = playerBox.minX;
                double maxX = playerBox.maxX;
                double minY = playerBox.minY;
                double maxY = playerBox.maxY;
                double minZ = playerBox.minZ;
                double maxZ = playerBox.maxZ;
                List<AABB> verticalSearchBoxes;
                if (autoCrawl.get()) {
                    verticalSearchBoxes = new ArrayList<>();
                    for (int y = Mth.floor(minY); y < Math.ceil(maxY); y++) {
                        double sliceMinY = Math.max(minY, y);
                        double sliceMaxY = Math.min(maxY, y + 1.0);
                        verticalSearchBoxes.add(new AABB(minX, sliceMinY, minZ, maxX, sliceMaxY, maxZ));
                    }
                } else {
                    verticalSearchBoxes = List.of(playerBox);
                }
                List<AABB> cornerSearchBoxes = List.of(
                        new AABB(maxX, minY, maxZ, maxX + extra, maxY, maxZ + extra),
                        new AABB(minX - extra, minY, maxZ, minX, maxY, maxZ + extra),
                        new AABB(minX - extra, minY, minZ - extra, minX, maxY, minZ),
                        new AABB(maxX, minY, minZ - extra, maxX + extra, maxY, minZ));
                List<AABB> sideSearchBoxes = List.of(
                        new AABB(maxX, minY, minZ, maxX + extra, maxY, maxZ),
                        new AABB(minX - extra, minY, minZ, minX, maxY, maxZ),
                        new AABB(minX, minY, maxZ, maxX, maxY, maxZ + extra),
                        new AABB(minX, minY, minZ - extra, maxX, maxY, minZ));

                List<List<BlockPos>> collisionGroups = new ArrayList<>();
                for (List<AABB> searchBoxes : List.of(cornerSearchBoxes, sideSearchBoxes)) {
                    collisionGroups.add(searchBoxes.stream()
                            .flatMap(searchBox -> verticalSearchBoxes.stream()
                                    .map(verticalBox -> new AABB(
                                            searchBox.minX,
                                            verticalBox.minY,
                                            searchBox.minZ,
                                            searchBox.maxX,
                                            verticalBox.maxY,
                                            searchBox.maxZ)))
                            .flatMap(
                                    searchBox -> CollisionUtil.getBoxCollision(mc.level, mc.player, searchBox).stream())
                            .distinct()
                            .toList());
                }

                int blockY = mc.player.getBlockY();
                Vec3 eyePos = mc.player.getEyePosition();
                Comparator<BlockPos> horizontalDistance = Comparator.comparingDouble(pos ->
                        MathUtils.squareSum(pos.getX() + 0.5 - mc.player.getX(), pos.getZ() + 0.5 - mc.player.getZ()));
                if (autoCrawl.get() && pose != Pose.SWIMMING && pose != Pose.FALL_FLYING) {
                    BlockPos crawlPos = collisionGroups.stream()
                            .flatMap(List::stream)
                            .filter(pos -> pos.getY() == blockY + 1)
                            .filter(pos -> {
                                BlockPos crawlSpacePos = pos.below();
                                return mc.level
                                        .getBlockState(crawlSpacePos)
                                        .getCollisionShape(mc.level, crawlSpacePos)
                                        .isEmpty();
                            })
                            .filter(pos -> RaycastUtils.raycastOnlyBlockCollisions(
                                            eyePos, getPearlTargetPoint(pos, blockY + 0.01))
                                    == null)
                            .max(horizontalDistance)
                            .orElse(null);
                    if (crawlPos != null
                            && usePearl(
                                    getPearlTargetPoint(crawlPos, blockY + 0.01).subtract(eyePos))) {
                        enablePearlPhase.set(false);
                        return;
                    }
                }

                for (List<BlockPos> collisionPositions : collisionGroups) {
                    BlockPos targetPos = collisionPositions.stream()
                            .filter(pos -> pos.getY() == blockY)
                            .max(horizontalDistance)
                            .orElse(null);
                    if (targetPos != null && doPearlUse(targetPos)) {
                        enablePearlPhase.set(false);
                        return;
                    }
                }
            }
        }
        if (pearlThrowRotation != null
                && !mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))) {
            usePearl(EntityUtils.pitchYawToRotation(pearlThrowRotation.x, pearlThrowRotation.y));
            pearlThrowRotation = null;
        }
        lineCollector.clear();
        hitBoxCollector.clear();
        if (pearlAimRender.get()) {
            Vec2 direction;
            Vec3 eye = mc.player.getEyePosition();
            if (exactDirection.get()) {
                Vec3 rayCastStart = RenderUtils.getCameraPos();
                Vec3 look = RenderUtils.getCameraLookVec(0.0F);
                var hit = RaycastUtils.raycastOnlyBlockCollisions(rayCastStart, rayCastStart.add(look.scale(128)));
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    Vec3 hitPoint = hit.getLocation();
                    var red = CombatTasks.calculatePitchYawPredict(1.48F, Vec3.ZERO, hitPoint.subtract(eye));
                    if (Float.isNaN(red.x)
                            || Float.isInfinite(red.x)
                            || Float.isNaN(red.y)
                            || Float.isInfinite(red.y)) {
                        return;
                    } else {
                        direction = red;
                    }
                } else {
                    return;
                }
            } else {
                direction = new Vec2(mc.player.getXRot(), mc.player.getYRot());
            }
            Vec3 rot = EntityUtils.pitchYawToRotation(direction.x, direction.y);
            var predictor = new ProjectileESP.ArrowPredictor(
                    eye.subtract(0, 0.11, 0), rot.normalize().scale(1.48F), mc.player);
            var result = predictor.predictLineWithHitResult(400);
            if (result != null) {
                lineCollector.submit(result.getFirst(), color.get().withAlpha(255));
                HitResult hitPose = result.getSecond();
                if (hitPose != null) {
                    var hitPos = hitPose.getLocation();
                    hitBoxCollector.submit(
                            new AABB(hitPos.add(RenderTasks.SMALL_FROM), hitPos.add(RenderTasks.SMALL_TO)),
                            color.get().withAlpha(64));
                }
            }
        }
    }

    RenderCollector<List<Vec3>> lineCollector = RenderCollectors.createLinesCollector();
    RenderCollector<AABB> hitBoxCollector = RenderCollectors.createBoxCollector(true, true, false);

    public void doPearlThrow() {
        if (checkNull()) return;
        if (exactDirection.get()) {
            Vec3 rayCastStart = RenderUtils.getCameraPos();
            Vec3 look = RenderUtils.getCameraLookVec(0.0F);
            var hit = RaycastUtils.raycastOnlyBlockCollisions(rayCastStart, rayCastStart.add(look.scale(128)));
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                Vec3 hitPoint = hit.getLocation();
                var red = CombatTasks.calculatePitchYawPredict(
                        1.48F, Vec3.ZERO, hitPoint.subtract(mc.player.getEyePosition()));
                if (Float.isNaN(red.x) || Float.isInfinite(red.x) || Float.isNaN(red.y) || Float.isInfinite(red.y)) {
                    logI18NSub("Pearl", "message.module.pearl-fly.can-not-reach-target");
                } else {
                    pearlThrowRotation = red;
                }
            } else {
                logI18NSub("Pearl", "message.module.pearl-fly.no-target");
            }
        } else {
            pearlThrowRotation = new Vec2(mc.player.getXRot(), mc.player.getYRot());
        }
    }

    public boolean doPearlUse(BlockPos pos) {
        Vec3 eyePos = mc.player.getEyePosition();
        return usePearl(getPearlTargetPoint(pos, mc.player.getBlockY() + 0.5).subtract(eyePos));
    }

    private Vec3 getPearlTargetPoint(BlockPos pos, double y) {
        Vec3 eyePos = mc.player.getEyePosition();
        return MathUtils.magnitudePoint(new AABB(pos), eyePos).with(Direction.Axis.Y, y);
    }

    public boolean usePearl(Vec3 look) {
        look = look.normalize();
        var re = InventoryUtils.findPlayerItem((ss) -> ss.getItem() == Items.ENDER_PEARL, false, false);
        if (re == null) {
            logI18NSub("Pearl", "message.module.pearl-fly.no-pearl");
            return true;
        }

        boolean offHand = offhand.get() || re.index() == 40;
        Runnable runnable = InvExtra.INSTANCE.swapItemToHand(re.index(), offHand, ghostHand.get());
        if (runnable == null) return false;
        InteractionTasks.interactItem(offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, look, true, swingHand.get());
        runnable.run();
        return true;
    }

    public void onRender3D(Event<Render3D> event) {
        if (checkNull())
            ;
        if (pearlAimRender.get()) {
            RenderUtils.startDrawVirtual(event.context.stack());
            try {
                lineCollector.render3D(event.context.stack(), event.context.partialTicks());
                hitBoxCollector.render3D(event.context.stack(), event.context.partialTicks());
            } finally {
                RenderUtils.stopDrawVirtual(event.context.stack());
            }
        }
    }
}
