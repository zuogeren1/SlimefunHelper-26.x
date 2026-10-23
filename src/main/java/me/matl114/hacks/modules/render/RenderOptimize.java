package me.matl114.hacks.modules.render;

import java.util.concurrent.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import me.matl114.accessors.access.ChunkAccess;
import me.matl114.accessors.interfaces.MetadataHolder;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.containers.MetaData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.function.BooleanConsumer;

public class RenderOptimize extends BaseModule {
    public final ModulePath renderOptimize = makePath(Configs.RENDER_CONFIG, "render-optimize");

    public RenderOptimize() {
        super("Optimize");
    }

    public final FlagRef enableItemTickOpt =
            flagBuilder(renderOptimize.add("optimize-item-tick")).build();

    public final DoubleRef cullingDistanceItem = builder(renderOptimize.add("item-culling-distance"), DoubleRef.TYPE)
            .defaultValue(40.0D)
            .validator(Configs.doubleRange(0.0D, 360.0D))
            .build();

    public final FlagRef enableParticleTickOpt =
            flagBuilder(renderOptimize.add("optimize-particle-tick")).build();

    public final FlagRef enableArmorStandTickOpt =
            flagBuilder(renderOptimize.add("optimize-armor-stand-tick")).build();

    public final FlagRef enableLabelRenderOpt =
            flagBuilder(renderOptimize.add("optimize-entity-label-render")).build();

    public final DoubleRef cullingDistanceEntityLabel = builder(
                    renderOptimize.add("entity-label-render-culling-distance"), DoubleRef.TYPE)
            .defaultValue(64.0D)
            .validator(Configs.doubleRange(0.0D, 360.0D))
            .build();

    public final FlagRef enableBlockLabelRenderOpt =
            flagBuilder(renderOptimize.add("optimize-block-label-render")).build();

    public final DoubleRef cullingDistanceBlockLabel = builder(
                    renderOptimize.add("block-label-render-culling-distance"), DoubleRef.TYPE)
            .defaultValue(20.0D)
            .validator(Configs.doubleRange(0.0D, 360.0D))
            .build();

    public final FlagRef cullingEnable =
            flagBuilder(renderOptimize.add("optimize-culling-enable")).build();

    public final KeyBindRef keyBindRef = toggleHotkey(
                    renderOptimize.add("optimize-culling-enable-hotkey"),
                    new MultiKeyBind(),
                    renderOptimize.add("optimize-culling-enable"))
            .build();

    public final NBTRef<EntrySet<EntityType<?>>> cullingTypes = builder(
                    renderOptimize.add("optimize-culling-entity-types"), EntrySet.<EntityType<?>>parameter())
            .defaultValue(new EntrySet<>(new Regex("^(item.*)$"), BuiltInRegistries.ENTITY_TYPE))
            .build();

    public final NBTRef<EntrySet<BlockEntityType<?>>> cullingTypes2 = builder(
                    renderOptimize.add("optimize-culling-block-entity-types"), EntrySet.<BlockEntityType<?>>parameter())
            .defaultValue(new EntrySet<>(
                    new Regex("^((.*sign)|barrel|skull|(.*chest)|enchanting_table)$"),
                    BuiltInRegistries.BLOCK_ENTITY_TYPE))
            .build();

    public final NBTRef<EntrySet<ParticleType<?>>> cullingTypes3 = builder(
                    renderOptimize.add("optimize-culling-block-entity-types"), EntrySet.<ParticleType<?>>parameter())
            .defaultValue(new EntrySet<>(new Regex("^(.*)$"), BuiltInRegistries.PARTICLE_TYPE))
            .build();

    public final DoubleRef cullingRadius = builder(renderOptimize.add("optimize-culling-radius"), DoubleRef.TYPE)
            .defaultValue(64.0D)
            .validator(Configs.doubleRange(0.0D, 360.0D))
            .build();

    public final FlagRef cullingUseRaycast =
            flagBuilder(renderOptimize.add("optimize-culling-use-raycast")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getEntityPreTickListener(), this::onEntityTick);
        registerListener(Listener.getEntityPreTickListener(), this::onEntityCullingTick);
        registerListener(Listener.getEntityPreTickListener(), this::onEntityLabelShowTick);
        registerListener(Listener.getBlockEntityTickListener(), this::onBlockEntityTick);
        registerListener(Listener.getPostGameTick(), this::onCacheClean);
        registerListener(Listener.getPreGameTick(), this::onBlockEntityCullingTick);

        registerListener(RenderListener.getEntityRenderListener(), this::onEntityRender);
        registerListener(RenderListener.getBlockEntityRenderListener(), this::onBlockEntityRender);
    }

    ExecutorService parallelRaycastExecutor;

    @Override
    public void onCreate() {
        super.onCreate();
        parallelRaycastExecutor = Executors.newFixedThreadPool(4, ThreadUtils.daemonThreadFactory("sfh-raycast"));
    }

    @Override
    public void onRemove() {
        super.onRemove();
        if (parallelRaycastExecutor != null && !parallelRaycastExecutor.isShutdown()) {
            parallelRaycastExecutor.shutdown();
        }
    }

    public void onEntityTick(Event<Entity> event) {
        Entity entity = event.context();
        if (entity instanceof ItemEntity item && enableItemTickOpt.get()) {
            boolean itemInFluid = item.isInLiquid();
            boolean itemFalling = !item.onGround() && item.getGravity() > 0;
            if (!itemInFluid && !itemFalling) {
                event.cancel();
                item.setOldPosAndRot();
                return;
            }
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null
                    && player.position().distanceToSqr(item.position()) > MathUtils.s2(cullingDistanceItem.get())) {
                event.cancel();
                item.setOldPosAndRot();
                return;
            }
        } else if (entity instanceof ArmorStand armorStand && enableArmorStandTickOpt.get()) {
            event.cancel();
            return;
        }
    }

    public void onEntityCullingTick(Event<Entity> event) {
        Entity entity = event.context();
        if (entity instanceof MetadataHolder holder) {
            MetaData metaData;
            RenderController controller;
            Vec3 pos = RenderUtils.getCameraPos();
            EntityType<?> types = entity.getType();
            if (cullingEnable.get()) {
                if (this.cullingTypes.get().test(types)) {
                    metaData = holder.getMetadata();
                    controller = metaData.getOrPut(this, KEY_RENDER_CONTROL, RenderController::new);

                    // do not hide nearby entity
                    AABB box = entity.getBoundingBox();
                    if (box.distanceToSqr(pos) < 16) {
                        controller.hideAll = false;
                    } else if (box.distanceToSqr(pos) > MathUtils.s2(cullingRadius.get())) {
                        controller.hideAll = true;
                    } else {
                        Vec3 playerTo = pos.subtract(entity.position());
                        Vec3 playerLook = RenderUtils.getCameraLookVec(0.0F);
                        if (playerLook.dot(playerTo) > 0) {
                            controller.hideAll = true;
                        } else {
                            if (cullingUseRaycast.get()) {
                                delayScheduleRaycast(box, controller, (val) -> {
                                    controller.hideAll = val;
                                });
                            } else {
                                controller.hideAll = false;
                            }
                        }
                    }

                } else {
                    if (!holder.isMetaEmpty()) {
                        metaData = holder.getMetadata();
                        controller = metaData.get(this, KEY_RENDER_CONTROL);
                        if (controller != null) {
                            controller.hideAll = false;
                        }
                    }
                }
            }
        }
    }

    public void onEntityLabelShowTick(Event<Entity> event) {
        if (enableLabelRenderOpt.get()) {
            Entity entity = event.context();
            // do not hide player nametags
            if (entity instanceof Player) {
                return;
            }
            if (entity.hasCustomName() && entity instanceof MetadataHolder holder) {
                MetaData metaData = holder.getMetadata();
                RenderController controller = metaData.getOrPut(this, KEY_RENDER_CONTROL, RenderController::new);
                Vec3 pos = RenderUtils.getCameraPos();
                if (entity.position().distanceToSqr(pos) > MathUtils.s2(cullingDistanceEntityLabel.get())) {
                    controller.hideLabelFront = true;
                } else {
                    Vec3 toPlayer = pos.subtract(entity.position());
                    if (toPlayer.dot(RenderUtils.getCameraLookVec(0.0F)) > 0) {
                        controller.hideLabelFront = true;
                    } else {
                        controller.hideLabelFront = false;
                    }
                    // do not cull label
                }
            }
        }
    }

    public void onEntityRender(Event<Entity> event) {
        if (event.isCancelled() || !cullingEnable.get()) return;
        Entity entity = event.context();
        if (entity instanceof MetadataHolder holder
                && !holder.isMetaEmpty()
                && holder.getMetadata().get(this, KEY_RENDER_CONTROL) instanceof RenderController controller
                && controller.hideAll) {
            event.cancel();
        }
    }

    public static final String KEY_RENDER_CONTROL = "slimefunhelper:render_optimize/render_controller";

    public void onBlockEntityTick(Event<TickingBlockEntity> event) {
        TickingBlockEntity entity = event.context();
        BlockPos blockPos = entity.getPos();
        BlockEntity blockEntity = mc.level.getBlockEntity(blockPos);
        if (blockEntity instanceof MetadataHolder holder) {
            // more choice
            if (blockEntity instanceof SignBlockEntity) {
                MetaData metaData = holder.getMetadata();
                RenderController controller = metaData.getOrPut(this, KEY_RENDER_CONTROL, RenderController::new);
                BlockState blockState = mc.level.getBlockState(entity.getPos());
                if (enableBlockLabelRenderOpt.get()) {
                    if (blockState.getBlock() instanceof SignBlock signBlock) {
                        // sign logic
                        Vec3 cameraPos = RenderUtils.getCameraPos();
                        if (blockPos.distToCenterSqr(cameraPos) > MathUtils.s2(cullingDistanceBlockLabel.get())) {
                            controller.hideLabelBack = controller.hideLabelFront = true;
                        } else {
                            float degree = signBlock.getYRotationDegrees(blockState);
                            float yawRad = degree * Mth.DEG_TO_RAD;
                            double frontX = -Mth.sin(yawRad);
                            double frontZ = Mth.cos(yawRad);
                            Vec3 frontNormal = new Vec3(frontX, 0, frontZ).normalize();
                            Vec3 signCenter = Vec3.atLowerCornerOf(blockPos)
                                    .add(signBlock.getSignHitboxCenterPosition(blockState));
                            Vec3 toPlayer = cameraPos.subtract(signCenter);
                            Vec3 playerLook = RenderUtils.getCameraLookVec(0.0f);
                            boolean showFront = true;
                            boolean showBack = true;
                            // culling back entities
                            if (playerLook.dot(toPlayer) > 0) {
                                showFront = false;
                                showBack = false;
                            } else {
                                if (toPlayer.dot(frontNormal) > 0) {
                                    showBack = false;
                                } else {
                                    showFront = false;
                                }
                            }
                            if ((showBack || showFront) && cullingUseRaycast.get()) {
                                // delay update
                                final boolean showBack0 = showBack;
                                final boolean showFront0 = showFront;
                                AABB box = AABB.unitCubeFromLowerCorner(Vec3.atLowerCornerOf(blockPos));
                                delayScheduleRaycast(box, controller, (val) -> {
                                    if (!val) {
                                        controller.hideLabelBack = !showBack0;
                                        controller.hideLabelFront = !showFront0;
                                    } else {
                                        controller.hideLabelFront = true;
                                        controller.hideLabelBack = true;
                                    }
                                });

                            } else {
                                controller.hideLabelBack = !showBack;
                                controller.hideLabelFront = !showFront;
                            }
                        }
                    } else {
                        controller.hideLabelBack = false;
                        controller.hideLabelFront = false;
                    }
                } else {
                    controller.hideLabelFront = false;
                    controller.hideLabelBack = false;
                }
            }
        }
    }

    public void canChunkBeSeen(int chunkX, int chunkZ, Vec3 cameraPos, Vec3 cameraLook) {}

    public void onBlockEntityCullingTick() {

        if (mc.level != null && cullingEnable.get()) {
            double maxDistance = cullingRadius.get();
            int maxChunkDistance = (int) ((cullingRadius.get() + 1) / 16 + 1);
            Vec3 pos = RenderUtils.getCameraPos();
            BlockPos cameraBlock = BlockPos.containing(pos);
            int chunkX = cameraBlock.getX() >> 4;
            int chunkZ = cameraBlock.getZ() >> 4;
            for (var chunk : CommonUtils.chunks(false)) {
                ChunkPos cpos = chunk.getPos();
                if (Math.abs(cpos.x - chunkX) <= maxChunkDistance && Math.abs(cpos.z - chunkZ) <= maxChunkDistance) {
                    for (var entry : ChunkAccess.of(chunk).blockEntityEntries()) {
                        BlockPos blockPos = entry.getKey();
                        AABB box = AABB.unitCubeFromLowerCorner(Vec3.atLowerCornerOf(blockPos));
                        if (box.distanceToSqr(pos) <= MathUtils.s2(maxDistance)) {
                            BlockEntity blockEntity = entry.getValue();
                            if (blockEntity instanceof MetadataHolder holder) {
                                MetaData metaData;
                                RenderController controller;
                                BlockEntityType<?> types = blockEntity.getType();
                                if (cullingTypes2.get().test(types)) {
                                    metaData = holder.getMetadata();
                                    controller = metaData.getOrPut(this, KEY_RENDER_CONTROL, RenderController::new);

                                    // do not hide nearby entity
                                    if (box.distanceToSqr(pos) < 16) {
                                        controller.hideAll = false;
                                    } else {
                                        Vec3 playerTo = pos.subtract(Vec3.atCenterOf(blockPos));
                                        Vec3 playerLook = RenderUtils.getCameraLookVec(0.0F);
                                        if (playerLook.dot(playerTo) > 0) {
                                            controller.hideAll = true;
                                        } else {
                                            if (cullingUseRaycast.get()) {
                                                delayScheduleRaycast(box, controller, (val) -> {
                                                    controller.hideAll = val;
                                                });
                                            } else {
                                                controller.hideAll = false;
                                            }
                                        }
                                    }
                                } else {
                                    if (!holder.isMetaEmpty()) {
                                        metaData = holder.getMetadata();
                                        controller = metaData.get(this, KEY_RENDER_CONTROL);
                                        if (controller != null) {
                                            controller.hideAll = false;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void onBlockEntityRender(Event<BlockEntity> event) {
        if (event.isCancelled() || !cullingEnable.get()) return;
        BlockEntity entity = event.context();
        BlockEntityType<?> type = entity.getType();
        if (cullingTypes2.get().test(type)) {
            AABB box = AABB.unitCubeFromLowerCorner(Vec3.atLowerCornerOf(entity.getBlockPos()));
            double sq = box.distanceToSqr(RenderUtils.getCameraPos());
            // use distance first
            if (sq < 16) {
                return;
            } else if (sq > MathUtils.s2(cullingRadius.get())) {
                event.cancel();
                return;
                // then calculate
                // may contains old data, but will refresh next tick
            } else if (entity instanceof MetadataHolder holder
                    && !holder.isMetaEmpty()
                    && holder.getMetadata().get(this, KEY_RENDER_CONTROL) instanceof RenderController controller
                    && controller.hideAll) {
                event.cancel();
            }
        }
    }

    public boolean shouldCancelShowDisplayName(Entity entity) {
        if (enableLabelRenderOpt.get()
                && entity instanceof MetadataHolder holder
                && !holder.isMetaEmpty()
                && holder.getMetadata().get(this, KEY_RENDER_CONTROL) instanceof RenderController controller) {
            return controller.hideLabelFront;
        }
        return false;
    }

    public void delayScheduleRaycast(AABB box, RenderController controller, BooleanConsumer consumer) {
        if ((parallelRaycastExecutor == null || parallelRaycastExecutor.isShutdown())) {
            consumer.accept(false);
        } else if (mc.getCameraEntity() != null && mc.getCameraEntity().isSpectator()) {
            // support spectator mode
            consumer.accept(false);
        } else if (controller.lastUpdateRaycastTick + 2 > Tasks.getTick()) {
            //
            consumer.accept(controller.raycastResult);
            return;
        } else {
            Vec3 camera = RenderUtils.getCameraPos();
            controller.lastUpdateRaycastTick = Tasks.getTick();
            CompletableFuture.runAsync(
                    () -> {
                        controller.raycastResult = raycastFullBlockAsync(box, camera, controller);
                        controller.lastUpdateRaycastTick = Tasks.getTick();
                        consumer.accept(controller.raycastResult);
                        // again update
                    },
                    parallelRaycastExecutor);
        }
    }

    public volatile ConcurrentHashMap<Long, Boolean> cache = new ConcurrentHashMap<>();
    private int tickCounter = 0;

    public void onCacheClean() {
        if (cullingUseRaycast.get()) {
            tickCounter += 1;
            if (tickCounter > 1) {
                tickCounter = 0;
                cache = new ConcurrentHashMap<>();
            }
        }
    }

    public boolean raycastFullBlockAsync(AABB box, Vec3 cameraPos, RenderController controller) {
        ClientLevel world = mc.level;
        if (world == null) return false;

        Vec3 center = box.getCenter();
        double xSize = box.maxX - box.minX;
        double ySize = box.maxY - box.minY;
        double zSize = box.maxZ - box.minZ;
        Vec3[] samples;
        if (xSize <= ySize && xSize <= zSize) {
            double x = center.x;
            samples = new Vec3[] {
                new Vec3(x, box.minY, box.minZ),
                new Vec3(x, box.minY, box.maxZ),
                new Vec3(x, box.maxY, box.minZ),
                new Vec3(x, box.maxY, box.maxZ),
                center
            };
        } else if (ySize <= zSize) {
            double y = center.y;
            samples = new Vec3[] {
                new Vec3(box.minX, y, box.minZ),
                new Vec3(box.minX, y, box.maxZ),
                new Vec3(box.maxX, y, box.minZ),
                new Vec3(box.maxX, y, box.maxZ),
                center
            };
        } else {
            double z = center.z;
            samples = new Vec3[] {
                new Vec3(box.minX, box.minY, z),
                new Vec3(box.minX, box.maxY, z),
                new Vec3(box.maxX, box.minY, z),
                new Vec3(box.maxX, box.maxY, z),
                center
            };
        }

        for (Vec3 sample : samples) {
            var hit = world.clip(new ClipContext(
                    cameraPos,
                    sample,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    mc.player));
            if (hit.getType() == HitResult.Type.MISS) {
                return false;
            }
        }
        return true;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Accessors(fluent = true)
    public static class RenderController {
        boolean hideLabelFront = false;
        boolean hideLabelBack = false;
        boolean hideAll = false;
        boolean raycastResult = false;
        int lastUpdateRaycastTick = 0;
    }
}
