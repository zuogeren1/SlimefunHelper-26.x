package me.matl114.hacks.modules.combat;

import java.awt.*;
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
import me.matl114.utils.entity.PlayerInputUtils;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

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

    public final DoubleRef autoPearlActivateRange = doubleBuilder(pearlPhase.add("auto-activate-range"))
            .defaultValue(0.35)
            .validator(Configs.doubleRange(0.0D, 2.0D))
            .build();

    public final FlagRef useWASDControl =
            flagBuilder(pearlPhase.add("use-wasd-control")).build();

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
            Vec3 facingDirection = EntityUtils.pitchYawToRotation(0, mc.player.getYRot());
            Direction direction = mc.player.getDirection();
            Vec3 pos = Vec3.atCenterOf(mc.player.blockPosition());
            Vec3 ppos = mc.player.position();

            BlockPos searchPos;
            if (useWASDControl.get()) {
                var input = PlayerInputUtils.of(mc.options);
                Vec3 adAxis = new Vec3(facingDirection.z, 0, -facingDirection.x);
                Vec3 inputVec = facingDirection
                        .scale(input.forwardSpeed())
                        .add(adAxis.scale(input.sidewaysSpeed()))
                        .normalize();
                searchPos = BlockPos.containing(ppos.add(inputVec));
                if (enableJump.get() && input.upwardSpeed() > 0) {
                    searchPos = searchPos.relative(Direction.UP, input.upwardSpeed());
                }
                if (!Objects.equals(mc.player.blockPosition(), searchPos)
                        && doPearlUse(mc.player.blockPosition(), searchPos)) {
                    enablePearlPhase.set(false);
                    return;
                }
            } else {
                if (MathUtils.isInXZRange(pos, ppos, 0.5 - autoPearlActivateRange.get())) {
                    return;
                }
                Direction search = direction;
                Direction result = direction;
                double min = Double.MAX_VALUE;
                BlockPos pbpos = mc.player.blockPosition();
                do {
                    BlockPos test = pbpos.relative(search);

                    if (MathUtils.isInXZRange(ppos, Vec3.atCenterOf(test), 0.5 + autoPearlActivateRange.get())) {
                        double sqd = test.distToCenterSqr(ppos);
                        if (sqd < min) {
                            result = search;
                            min = sqd;
                        }
                    }

                    search = search.getClockWise();
                } while (search != direction);
                if (min == Double.MAX_VALUE) {
                    return;
                }
                search = result;
                searchPos = pbpos.relative(search, 1);

                BlockState state = mc.level.getBlockState(searchPos);
                if (!state.isAir() && !state.liquid()) {
                    if (doPearlUse(pbpos, searchPos)) {
                        enablePearlPhase.set(false);
                        return;
                    }
                }
                if (autoCrawl.get() && pose != Pose.SWIMMING) {
                    BlockState state2 = mc.level.getBlockState(searchPos.relative(Direction.UP));
                    if (!state2.isAir() && !state2.liquid()) {
                        if (doPearlUse(pbpos, searchPos)) {
                            enablePearlPhase.set(false);
                            return;
                        }
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

    public boolean doPearlUse(BlockPos originPos, BlockPos pos) {
        Pose pose = mc.player.getPose();
        Vec3 look;
        if (pose == Pose.SWIMMING) {
            look = Vec3.atCenterOf(pos).subtract(mc.player.getEyePosition());
        } else {
            look = Vec3.atCenterOf(pos)
                    .add(Vec3.atCenterOf(originPos))
                    .scale(0.5)
                    .subtract(mc.player.getEyePosition());
        }
        return usePearl(look);
    }

    public boolean usePearl(Vec3 look) {
        look = look.normalize();
        var re = InventoryUtils.findPlayerItem((ss) -> ss.getItem() == Items.ENDER_PEARL, true, false);
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
