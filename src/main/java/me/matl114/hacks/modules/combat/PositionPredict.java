package me.matl114.hacks.modules.combat;

import com.google.common.hash.Hashing;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import lombok.With;
import me.matl114.accessors.hacks.EntityInternalAccess;
import me.matl114.events.*;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.RenderListener;
import me.matl114.events.packets.PacketStorage;
import me.matl114.events.packets.PacketStorageImpl;
import me.matl114.gui.basic.*;
import me.matl114.gui.elements.ButtonElement;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.entity.EntityMovementStatus;
import me.matl114.hacks.utils.entity.LocalEntityPredictor;
import me.matl114.hacks.utils.entity.Predictor;
import me.matl114.hacks.utils.entity.PredictorImpl;
import me.matl114.hacks.utils.enums.PredictionMode;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.CodecUtils;
import me.matl114.utils.ColorUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.config.WrapperFactory;
import me.matl114.utils.config.kv.EnumAttrKeyValue;
import me.matl114.utils.config.kv.TypeConvertAttrKeyValue;
import me.matl114.utils.entity.PlayerInputUtils;
import me.matl114.versioned.api.VRender;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class PositionPredict extends BaseModule {
    public static PositionPredict INSTANCE;
    public final ModulePath attack = makePath(Configs.COMBAT_CONFIG, "attack");
    public final ModulePath attBot = makePath(Configs.COMBAT_CONFIG, "att-bot");

    public PositionPredict() {
        super("PositionPredict");
        INSTANCE = this;
    }
    //
    //    public final FlagRef render = flagBuilder(attack.add("render-predict-pos"))
    //        .build();

    public final NBTRef<PredictArgument> attackPredictArgument = builder(
                    attack.add("attack-predict-argument"), PredictArgument.class)
            .defaultValue(new PredictArgument(2, 5, PredictionMode.NO_PREDICT))
            .build();

    public final NBTRef<PredictArgument> flyPredictArgument = builder(
                    attack.add("fly-predict-argument"), PredictArgument.class)
            .defaultValue(new PredictArgument(2, 5, PredictionMode.PREDICTOR_NV))
            .build();

    public final NBTRef<PredictArgument> spearPredictArgument = builder(
                    attack.add("spear-predict-argument"), PredictArgument.class)
            .defaultValue(new PredictArgument(2, 5, PredictionMode.PREDICTOR_NV))
            .build();

    public final FlagRef enableNoShield = builder(attBot.add("exact-tp-anti-shield"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef playerSyncPositionPeek =
            flagBuilder(attack.add("player-sync-position-peek")).build();

    public final NBTRef<WrapColor> playerSyncPosColor = builder(
                    attack.add("player-sync-position-render-color"), WrapColor.class)
            .defaultValue(new WrapColor(Color.GREEN))
            .build();

    public final FlagRef debugRender =
            flagBuilder(attack.add("debug-render-prediction")).build();
    Int2ObjectArrayMap<List<Vec3>> recordedPoints = new Int2ObjectArrayMap<>();

    public final FlagRef placeRecorder = flagBuilder(attBot.add("place-recorder"))
            .updateListener(s -> this.recordedPoints.clear())
            .build();

    public final KeyBindRef placeRecorderHotkey = toggleHotkey(
                    attBot.add("place-recorder-hotkey"), new MultiKeyBind(), attBot.add("place-recorder"))
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
    }

    static {
        PacketManager.getPacketQueueInEvent().registerHandler(PositionPredict::onInBoundPacket, Integer.MIN_VALUE);
    }

    static final Int2ObjectMap<PredictorImpl> asyncLoadedPlayerPositionTrackers = new Int2ObjectOpenHashMap<>();

    public static void onInBoundPacket(Event<PacketStorage> event) {
        if (event.<Boolean>getArgs(1) && event.context instanceof PacketStorageImpl impl) {
            onPacket(impl.packet());
        }
    }

    public static Predictor getPlayerPredictor(Entity id) {
        synchronized (asyncLoadedPlayerPositionTrackers) {
            var re = asyncLoadedPlayerPositionTrackers.get(id.getId());
            return re != null ? re : new LocalEntityPredictor(id);
        }
    }

    private static void onPacket(Packet<?> ev) {
        if (ev instanceof ClientboundBundlePacket bundle) {
            for (var re : bundle.subPackets()) {
                onPacket(re);
            }
            return;
        }
        if (ev instanceof ClientboundLoginPacket joinWorld || ev instanceof ClientboundRespawnPacket) {
            synchronized (asyncLoadedPlayerPositionTrackers) {
                asyncLoadedPlayerPositionTrackers.clear();
            }
            return;
        }
        if (ev instanceof ClientboundAddEntityPacket packet && packet.getType() == EntityTypes.PLAYER) {
            PredictorImpl newPredictor = new PredictorImpl();
            newPredictor.initializeTrackedPosition(
                    packet.getId(),
                    packet.getX(),
                    packet.getY(),
                    packet.getZ(),
                    packet.getXRot(),
                    packet.getYRot());
            synchronized (asyncLoadedPlayerPositionTrackers) {
                asyncLoadedPlayerPositionTrackers.put(packet.getId(), newPredictor);
            }
            return;
        }
        if (ev instanceof ClientboundRemoveEntitiesPacket destroy) {
            synchronized (asyncLoadedPlayerPositionTrackers) {
                destroy.getEntityIds().forEach(asyncLoadedPlayerPositionTrackers::remove);
            }
        }
        PredictorImpl impl;
        if (ev instanceof ClientboundMoveEntityPacket packet) {
            synchronized (asyncLoadedPlayerPositionTrackers) {
                impl = asyncLoadedPlayerPositionTrackers.get(packet.getEntity(mc.level).getId());
            }

            if (impl != null) {
                impl.onEntityPositionMove(packet);
            }
            return;
        }
        if (ev instanceof ClientboundTeleportEntityPacket packet) {
            synchronized (asyncLoadedPlayerPositionTrackers) {
                impl = asyncLoadedPlayerPositionTrackers.get(packet.id());
            }

            if (impl != null) {
                impl.onEntityPositionPost(packet);
            }
            return;
        }
        if (ev instanceof ClientboundEntityPositionSyncPacket sync) {
            synchronized (asyncLoadedPlayerPositionTrackers) {
                impl = asyncLoadedPlayerPositionTrackers.get(sync.id());
            }

            if (impl != null) {
                impl.onEntityPositionSyncPost(sync);
            }
            return;
        }
    }

    public void onRender(Event<Render3D> event) {
        if (playerSyncPositionPeek.get()) {
            List<AABB> boxes = new ArrayList<>();
            for (var re : mc.level.players()) {
                Vec3 trackedPos = re.trackingPosition();
                Vec3 currentPos = getPredictor(re).getCurrentPos();
                if (currentPos.distanceToSqr(trackedPos) > 1E-2) {
                    boxes.add(re.dimensions.makeBoundingBox(currentPos));
                }
            }
            if (!boxes.isEmpty()) {
                RenderUtils.startDrawVirtual(event.context.stack());
                try {
                    VRender.getInstance().createLinesLayer(((operation, vertexConsumer) -> {
                        for (AABB box : boxes) {
                            operation.drawOutlinedBox(
                                    event.context.stack(),
                                    vertexConsumer,
                                    box.getMinPosition(),
                                    box.getMaxPosition(),
                                    playerSyncPosColor.get().withAlpha(255));
                        }
                    }));
                } finally {
                    RenderUtils.stopDrawVirtual(event.context.stack());
                }
            }
        }
        if (debugRender.get()) {
            RenderUtils.startDrawVirtual(event.context.stack());
            try {
                List<AABB> boxes = new ArrayList<>();
                Vec3 camera = RenderUtils.getCameraPos().reverse();
                for (var re : mc.level.players()) {
                    if (re != mc.getCameraEntity()) {
                        Vec3 pos = flyPredictArgument
                                .get()
                                .predict(re); // predictFlyingPosition(re, 2, renderUseArgument1.get());
                        boxes.add(mc.player.dimensions.makeBoundingBox(pos).move(camera));
                    }
                }
                VRender.getInstance().createLinesLayer(((operation, vertexConsumer) -> {
                    for (AABB box : boxes) {
                        operation.drawOutlinedBox(
                                event.context.stack(),
                                vertexConsumer,
                                box.getMinPosition(),
                                box.getMaxPosition(),
                                Color.MAGENTA.getRGB());
                    }
                }));
                for (var re : recordedPoints.int2ObjectEntrySet()) {
                    var lst = re.getValue();
                    int hash = ColorUtils.withAlphaInt(
                            Hashing.sha256().hashInt(re.getIntKey()).hashCode(), 255);
                    VRender.getInstance().createLinesLayer(((operation, vertexConsumer) -> {
                        for (Vec3 box : lst) {
                            box = box.add(camera);
                            operation.drawOutlinedBox(
                                    event.context.stack(),
                                    vertexConsumer,
                                    box.add(-0.2, -0.2, -0.2),
                                    box.add(0.2, 0.2, 0.2),
                                    hash);
                        }
                    }));
                    VRender.getInstance().createLineStripLayer(((operation, vertexConsumer) -> {
                        operation.drawLines(
                                event.context.stack(),
                                vertexConsumer,
                                lst.stream().map(s -> s.add(camera)).toList(),
                                hash);
                    }));
                }
            } finally {
                RenderUtils.stopDrawVirtual(event.context.stack());
            }
        }
    }

    public Predictor getPredictor(Entity entity) {
        return EntityInternalAccess.of(entity).getPositionPredictor();
    }

    public Vec3 predictKnownMovement(Entity entity) {
        return EntityInternalAccess.of(entity).getPositionPredictor().getKnownDeltaMovement();
    }

    public Vec3 getExactAttackPosition(Entity target) {
        if (mc.player == null) return null;
        if (target instanceof Shulker) {
            // consider wtf shit , this entity collides with player
            // consider all collisions use bounding box not directions
            Vec3 vec3 = target.position();
            //            BlockPos posAt = BlockPos.ofFloored(vec3);
            AABB boundingBox = target.getBoundingBox();
            for (Direction dir : Direction.values()) {

                Vec3 testPos =
                        switch (dir) {
                            case UP -> vec3.with(Direction.Axis.Y, boundingBox.maxY + 0.1);
                            case DOWN -> vec3.with(Direction.Axis.Y, boundingBox.minY - 2);
                            case NORTH -> vec3.with(Direction.Axis.Z, boundingBox.minZ - 0.5);
                            case SOUTH -> vec3.with(Direction.Axis.Z, boundingBox.maxZ + 0.5);
                            case EAST -> vec3.with(Direction.Axis.X, boundingBox.maxX + 0.5);
                            case WEST -> vec3.with(Direction.Axis.X, boundingBox.minX - 0.5);
                        };

                if (!MovTasks.ENGIN.checkEnvironmentCollision(mc.player, testPos, true)) {
                    return testPos;
                }
            }
            return null;
        } else {
            boolean considerAntiShield = considerAntiShield(target);
            Vec3 deltaMovments;
            if (considerAntiShield) {
                deltaMovments = target.getLookAngle().normalize().scale(-0.2);
            } else if (target instanceof Player playerEntity) {
                var re = attackPredictArgument.get();

                Vec3 predictedPosition = re.predict(
                        playerEntity); /// predictAttackPosition(playerEntity, re.ticksLater(), re.ticksHistory(),
                // re.mode());
                deltaMovments = predictedPosition.subtract(target.position());
            } else {
                Vec3 targetFacing = mc.player.position().subtract(target.position());
                Vec3 targetFacingHorizontal = new Vec3(targetFacing.x, 0.0d, targetFacing.z);
                double multiply = 0.5;
                deltaMovments = targetFacingHorizontal.normalize().scale(multiply);
            }

            Vec3 targetPos = target.position();
            Vec3 actualMove = MovTasks.ENGIN.simulateMovement(mc.player, targetPos, deltaMovments);
            return targetPos.add(actualMove);
        }
    }

    public Vec3 predictAimPositionForEntity(Entity entity, float finalVelocity) {
        Vec3 estimatedDelta = entity.position().subtract(mc.player.position());
        double estimateSpeed = estimatedDelta.length() / (finalVelocity);
        int estimateTick;
        if (estimateSpeed < 2.0) {
            estimateTick = 0;
        } else if (estimateSpeed > 20.0) {
            estimateTick = 20;
        } else {
            estimateTick = (int) (estimateSpeed - 2.0D);
        }

        return entity.getEyePosition()
                .subtract(entity.position())
                .scale(0.75)
                .add(flyPredictArgument.get().predictWithExtraTicks(entity, estimateTick));
    }

    public boolean considerAntiShield(Entity target) {
        return enableNoShield.get()
                && target instanceof LivingEntity livingEntity
                && livingEntity.isUsingItem()
                && livingEntity.getUseItem().getItem() instanceof ShieldItem;
    }

    public Vec3 predictPlayerMove(PlayerInputUtils.Input input) {
        EntityMovementStatus<Entity> entityMovementStatus = new EntityMovementStatus<>(mc.player);
        if (mc.player.isFallFlying()) {
            return mc.player.getDeltaMovement();
        } else if (mc.player.isInLiquid()) {
            return mc.player.getDeltaMovement();
        } else {
            return entityMovementStatus.calculateLastMoveVelocity(input.forwardSpeed(), input.sidewaysSpeed());
        }
    }

    @With
    public static record PredictArgument(double ticksLater, int ticksHistory, PredictionMode mode)
            implements NBTParsable<PredictArgument> {
        public static NBTType<PredictArgument> TYPE = new NBTType<>(
                "predictargument",
                RecordCodecBuilder.<PredictArgument>create(s -> s.group(
                                Codec.withAlternative(Codec.DOUBLE, Codec.INT.xmap(t -> (double) (int) t, t ->
                                                (int) (double) t))
                                        .fieldOf("ticks")
                                        .forGetter(PredictArgument::ticksLater),
                                Codec.INT.fieldOf("history").forGetter(PredictArgument::ticksHistory),
                                CodecUtils.enumCodec(PredictionMode.class)
                                        .fieldOf("mode")
                                        .forGetter(PredictArgument::mode))
                        .apply(s, PredictArgument::new)),
                (s, x, y, dx, dy) -> {
                    SubScreenWidget subScreenWidget = SubScreenWidget.instance(x, y, dx, dy);
                    int half = dx / 4;
                    WrapperFactory<Double, PredictArgument> firstWrapper =
                            WrapperFactory.of((d) -> s.get().withTicksLater(d), PredictArgument::ticksLater);
                    WrapperFactory<Integer, PredictArgument> secondWrapper = WrapperFactory.of(
                            (d) -> s.get().withTicksHistory(d), PredictArgument::ticksHistory);
                    WrapperFactory<PredictionMode, PredictArgument> thirdWrapper =
                            WrapperFactory.of((d) -> s.get().withMode(d), PredictArgument::mode);

                    return subScreenWidget
                            .addDrawableChild(DisplayWidget.instance(0, 0, dy, dy)
                                    .setRenderHandler(new ButtonElement(
                                                    TextProvider.of(Component.translatableWithFallback(
                                                            "widget.nbt-parsable.predict-argument.ticks", "F:")),
                                                    ButtonAction.empty())
                                            .withTooltips(TooltipHandler.of(ChatUtils.parseTooltipsTranslation(
                                                    "widget.nbt-parsable.predict-argument.ticks.tooltips", "")))))
                            .addDrawableChild(new TypeConvertAttrKeyValue<>(s, firstWrapper, NBTTypes.DOUBLE_TYPE)
                                    .generateValueWidget(dy, 0, half - dy, dy))
                            .addDrawableChild(DisplayWidget.instance(half, 0, dy, dy)
                                    .setRenderHandler(new ButtonElement(
                                                    TextProvider.of(Component.translatableWithFallback(
                                                            "widget.nbt-parsable.predict-argument.history", "H:")),
                                                    ButtonAction.empty())
                                            .withTooltips(TooltipHandler.of(ChatUtils.parseTooltipsTranslation(
                                                    "widget.nbt-parsable.predict-argument.history.tooltips", "")))))
                            .addDrawableChild(new TypeConvertAttrKeyValue<>(s, secondWrapper, NBTTypes.INT_TYPE)
                                    .generateValueWidget(half + dy, 0, half - dy, dy))
                            .addDrawableChild(DisplayWidget.instance(2 * half, 0, dy, dy)
                                    .setRenderHandler(new ButtonElement(
                                                    TextProvider.of(Component.translatableWithFallback(
                                                            "widget.nbt-parsable.predict-argument.mode", "M:")),
                                                    ButtonAction.empty())
                                            .withTooltips(TooltipHandler.of(ChatUtils.parseTooltipsTranslation(
                                                    "widget.nbt-parsable.predict-argument.mode.tooltips", "")))))
                            .addDrawableChild(new TypeConvertAttrKeyValue<>(
                                            s,
                                            thirdWrapper,
                                            EnumAttrKeyValue.createEnumWidgetGenerator(PredictionMode.class),
                                            WrapperFactory.of(PredictionMode::valueOf, PredictionMode::name))
                                    .generateValueWidget(2 * half + dy, 0, 2 * half - dy, dy));
                },
                new PredictArgument(2, 5, PredictionMode.NO_PREDICT));

        @Override
        public NBTType<PredictArgument> type() {
            return TYPE;
        }

        public Vec3 predict(Entity entity) {
            return predict0(entity, ticksLater);
        }

        public Vec3 predict0(Entity entity, double ticksLater) {
            int floor = (int) Math.floor(ticksLater);
            Vec3 floorPos =
                    EntityInternalAccess.of(entity).getPositionPredictor().predict(floor, mode.ordinal(), ticksHistory);
            if (Math.abs(floor - ticksLater) < 1E-2) {
                return floorPos;
            }
            Vec3 roofPos = EntityInternalAccess.of(entity)
                    .getPositionPredictor()
                    .predict(floor + 1, mode.ordinal(), ticksHistory);
            return floorPos.scale(floor + 1 - ticksLater).add(roofPos.scale(ticksLater - floor));
        }

        public Vec3 predictWithExtraTicks(Entity entity, double ticks) {
            return predict0(entity, ticksLater + ticks);
        }
    }
}
