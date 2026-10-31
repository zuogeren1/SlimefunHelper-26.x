package me.matl114.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.RenderListener;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.hacks.api.ModuleManager;
import me.matl114.hacks.modules.HackModules;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.modules.render.*;
import me.matl114.managers.Tasks;
import me.matl114.utils.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class RenderTasks {
    public static void init() {}

    private static Minecraft mc = Minecraft.getInstance();

    public static Color STATIC_DEBUG_COLOR = null;
    public static int DEBUG_TICK = 16;
    public static boolean DEBUG_RENDER_STANDING = false;
    public static boolean DEBUG_RENDER_COLLISION = false;
    public static boolean DEBUG_RENDER_COMBAT = false;
    public static boolean DEBUG_RENDER_COLLISION_RENDERING = false;
    public static boolean DEBUG_RENDER_BOWAIM = false;
    public static boolean DEBUG_RENDER_SPEAR = false;
    public static boolean DEBUG_RENDER_INTERACTION = false;

    public static void debugBoxMov(AABB box, Vec3 move) {
        if (DEBUG_RENDER_COLLISION_RENDERING && DEBUG_RENDER_COLLISION) {
            RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
                    DEBUG_TICK, new BoxMoveTarget(box, move, STATIC_DEBUG_COLOR, Color.RED)));
        }
    }

    public static void debugBox(AABB box) {
        if (DEBUG_RENDER_COLLISION_RENDERING && DEBUG_RENDER_COLLISION) {
            RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
                    DEBUG_TICK, new BoxObject(box.getMinPosition(), box.getMaxPosition(), STATIC_DEBUG_COLOR)));
        }
    }

    public static void debugBlockHitResult(BlockHitResult packetHitResult) {
        if (DEBUG_RENDER_INTERACTION) {
            RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
                    RenderTasks.DEBUG_TICK,
                    new RenderTasks.BoxObject(
                            Vec3.atLowerCornerOf(packetHitResult.getBlockPos()),
                            Vec3.atLowerCornerOf(packetHitResult.getBlockPos()).add(1, 1, 1),
                            Color.WHITE)));
            RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
                    RenderTasks.DEBUG_TICK,
                    new RenderTasks.BoxObject(
                            packetHitResult.getLocation().add(-0.1, -0.1, -0.1),
                            packetHitResult.getLocation().add(0.1, 0.1, 0.1),
                            Color.RED)));
        }
    }

    public static void onDebugRenderTick(Event<Render3D> event) {
        if (RenderTasks.DEBUG_RENDER_STANDING) {
            if (mc.player != null) {
                try {
                    RenderUtils.startDrawVirtual(event.context().stack());
                    BlockPos pos = PlayerStateManager.INSTANCE.lastVelocityAffectingPos;
                    RenderUtils.drawOutlinedBox(
                            event.context().stack(),
                            Vec3.atCenterOf(pos).add(RenderTasks.FROM),
                            Vec3.atCenterOf(pos).add(RenderTasks.TO),
                            Color.GREEN);

                    Vec3 vec3d = mc.player.position();
                    Vec3 vec3dSupportingBlock = vec3d.subtract(0, 0.500001F, 0);
                    BlockPos underBlock = BlockPos.containing(vec3dSupportingBlock);
                    RenderUtils.drawOutlinedBox(
                            event.context().stack(),
                            Vec3.atCenterOf(underBlock).add(RenderTasks.FROM),
                            Vec3.atCenterOf(underBlock).add(RenderTasks.TO),
                            Color.MAGENTA);
                } finally {
                    RenderUtils.stopDrawVirtual(event.context().stack());
                }
            }
        }
    }

    public static void drawBox(AABB box, int timeTick, Color color) {
        RenderTasks.registerVirtualRenderTask(
                new RenderTasks.RenderTask(timeTick, new BoxObject(box.getMinPosition(), box.getMaxPosition(), color)));
    }

    public static void drawBoxMov(AABB box, Vec3 move, int tick, Color color) {
        RenderTasks.registerVirtualRenderTask(
                new RenderTasks.RenderTask(tick, new BoxMoveTarget(box, move, color, color)));
    }

    public static void drawLine(Vec3 from, Vec3 deltaMove, int timeTick, Color color) {
        RenderTasks.registerVirtualRenderTask(new RenderTask(timeTick, new LineObject(from, deltaMove).color(color)));
    }

    // the visit to renderBlocks need synchronized for thread safety, as they involved for-loop and remove
    private static final Set<VirtualRenderTask> renderBlocks = new LinkedHashSet<>();

    public static void registerVirtualRenderTask(VirtualRenderTask task) {
        synchronized (renderBlocks) {
            task.startRender();
            renderBlocks.add(task);
        }
    }

    public static final Vec3 FROM = new Vec3(-0.5, -0.5, -0.5);
    public static final Vec3 SMALL_FROM = new Vec3(-0.2, -0.2, -0.2);
    public static final Vec3 TO = new Vec3(0.5, 0.5, 0.5);
    public static final Vec3 SMALL_TO = new Vec3(0.2, 0.2, 0.2);

    private static void onRenderVirtualTasks(Event<Render3D> stackE) {
        if (renderBlocks.isEmpty()) return;
        synchronized (renderBlocks) {
            var stack = stackE.context.stack();
            float ticksDelta = stackE.context.partialTicks();
            RenderUtils.startDrawVirtual(stack);
            try {
                Iterator<VirtualRenderTask> tasks = renderBlocks.iterator();
                while (tasks.hasNext()) {
                    VirtualRenderTask renderTask = tasks.next();
                    if (renderTask.stillRender()) {
                        renderTask.renderVirtual(stack, ticksDelta);
                    } else {
                        renderTask.stopRender();
                        tasks.remove();
                    }
                }
            } finally {
                RenderUtils.stopDrawVirtual(stack);
            }
        }
    }
    //    public static interface StaticRenderTask {
    //        boolean stillRender();
    //        VertexBuffer getRenderAction();
    //    }

    public static class TaskBuilder {
        int tickLeft = -1;
        BooleanSupplier autoStop;
        Runnable stopFuture;
        List<RenderObject> renderObjects = new ArrayList<>();

        public TaskBuilder time(int tickLeft) {
            this.tickLeft = tickLeft;
            return this;
        }

        public TaskBuilder add(RenderObject renderObject) {
            renderObjects.add(renderObject);
            return this;
        }

        public TaskBuilder autoStop(BooleanSupplier autoStop) {
            this.autoStop = autoStop;
            return this;
        }

        public TaskBuilder stopFuture(Runnable runnable) {
            this.stopFuture = runnable;
            return this;
        }

        public RenderTask build() {
            RenderTask renderTask;
            if (tickLeft <= 0) {
                renderTask = new RenderTask(renderObjects.toArray(RenderObject[]::new));
            } else {
                renderTask = new RenderTask(tickLeft, renderObjects.toArray(RenderObject[]::new));
            }
            if (autoStop != null) {
                renderTask.setAutoStop(autoStop);
            }
            if (stopFuture != null) {
                renderTask.setStopFuture(stopFuture);
            }
            return renderTask;
        }
    }

    public static TaskBuilder builder() {
        return new TaskBuilder();
    }

    public static interface VirtualRenderTask {
        void renderVirtual(PoseStack stack, float partialTicks);

        public void startRender();

        public void stopRender();

        boolean stillRender();
    }

    public static class RenderTask implements VirtualRenderTask {
        int endTick;
        RenderObject[] renderObjects;
        boolean registered = false;
        BooleanSupplier autoStopPredicate = null;
        Runnable stopFuture = null;

        public RenderTask(int tick, RenderObject... renderObjects) {
            this.endTick = tick + Tasks.getTick();
            this.renderObjects = renderObjects;
        }

        public RenderTask(RenderObject... renderObjects) {
            this.endTick = Integer.MAX_VALUE;
            this.renderObjects = renderObjects;
        }

        public RenderTask refreshTimer(int val) {
            this.endTick = val + Tasks.getTick();
            return this;
        }

        public void stopRender() {
            this.registered = false;
            this.endTick = -1;
            if (stopFuture != null) {
                stopFuture.run();
            }
        }

        public RenderTask cancelTimer() {
            this.endTick = Integer.MAX_VALUE;
            return this;
        }

        public RenderTask setAutoStop(BooleanSupplier autoStopPredicate) {
            this.autoStopPredicate = autoStopPredicate;
            return this;
        }

        public RenderTask setStopFuture(Runnable stopFuture) {
            this.stopFuture = stopFuture;
            return this;
        }

        @Override
        public void renderVirtual(PoseStack stack, float partialTicks) {
            for (RenderObject renderObject : renderObjects) {
                renderObject.render(stack, partialTicks);
            }
        }

        @Override
        public boolean stillRender() {
            return registered
                    && Tasks.getTick() <= this.endTick
                    && (autoStopPredicate == null || !autoStopPredicate.getAsBoolean());
        }

        public void startRender() {
            if (!registered) {
                registered = true;
                RenderTasks.registerVirtualRenderTask(this);
            }
        }
    }

    public static interface RenderObject {
        public void render(PoseStack stack, float partialTicks);
    }

    @Getter
    @Setter
    @Accessors(chain = true, fluent = true)
    public static class LineObject implements RenderObject {
        Vec3 start;
        Vec3 movement;
        Color color = Color.GREEN;

        public LineObject(Vec3 start, Vec3 movement) {
            this.start = start;
            this.movement = movement;
        }

        @Override
        public void render(PoseStack stack, float partialTicks) {
            RenderUtils.drawLineVirtual(stack, start, start.add(movement), color);
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true, fluent = true)
    public static class BoxObject implements RenderObject {

        Vec3 startVec;
        Vec3 endVec;
        Color color;
        float opacity = 0.25F;

        public BoxObject(AABB box, Color color) {
            this(box.getMinPosition(), box.getMaxPosition(), color);
        }

        public BoxObject(Vec3 start, Vec3 end, Color color) {
            this.startVec = start;
            this.endVec = end;
            this.color = color;
        }

        @Override
        public void render(PoseStack stack, float partialTicks) {
            RenderUtils.drawSolidBox(stack, startVec, endVec, ColorUtils.withAlpha(color, opacity));
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true, fluent = true)
    @AllArgsConstructor
    public static class BoxOutlineObject implements RenderObject {
        Vec3 startVec;
        Vec3 endVec;
        Color color;

        @Override
        public void render(PoseStack stack, float partialTicks) {
            RenderUtils.drawOutlinedBox(stack, startVec, endVec, color);
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true, fluent = true)
    @AllArgsConstructor
    public static class BoxMoveTarget implements RenderObject {
        AABB startBox;
        Vec3 delta;
        Color color1;
        Color color2;

        public BoxMoveTarget(AABB startBox, Vec3 delta) {
            this(startBox, delta, Color.GREEN, Color.RED);
        }

        @Override
        public void render(PoseStack stack, float partialTicks) {
            Color color = ColorUtils.withAlpha(color1, 0.25F);
            RenderUtils.drawSolidBox(stack, startBox.getMinPosition(), startBox.getMaxPosition(), color);
            RenderUtils.drawSolidBox(
                    stack,
                    startBox.getMinPosition().add(delta),
                    startBox.getMaxPosition().add(delta),
                    color);
            for (var ver : CollisionUtil.getBoxVertices(startBox))
                RenderUtils.drawLineVirtual(stack, ver, ver.add(delta), color2);
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true, fluent = true)
    public static class QuadObject implements RenderObject {
        Vec3[] abcd;
        Color color;

        public QuadObject(Vec3 abcd, Vec3 b, Vec3 c, Vec3 d, Color color) {
            this.abcd = new Vec3[] {abcd, b, c, d};
            this.color = color;
        }

        @Override
        public void render(PoseStack stack, float partialTicks) {
            RenderUtils.drawQuad(
                    stack,
                    abcd[0],
                    abcd[1],
                    abcd[2],
                    abcd[3],
                    new Color(color.getRed(), color.getGreen(), color.getBlue(), 64));
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true, fluent = true)
    @AllArgsConstructor
    public static class MultiLineObject implements RenderObject {
        List<Vec3> multiLine;
        Color color;

        @Override
        public void render(PoseStack stack, float partialTicks) {
            RenderUtils.drawLineVirtual(stack, multiLine, color);
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true, fluent = true)
    @AllArgsConstructor
    public static class LineToTargetObject implements RenderObject {
        Vec3 vec3d;
        Color color;

        @Override
        public void render(PoseStack stack, float partialTicks) {
            Vec3 camera = RenderUtils.getCameraPos();
            Vec3 camerToBlock = this.vec3d.subtract(camera);
            Vec3 cursorPos = RenderUtils.getTracerOrigin(1.0f);
            RenderUtils.drawLineVirtualCameraCoord(stack, cursorPos, camerToBlock, color);
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true, fluent = true)
    @AllArgsConstructor
    public static class EntityBoxObject implements RenderObject {
        Entity entity;

        Color color;

        @Override
        public void render(PoseStack stack, float partialTicks) {
            RenderUtils.drawSolidBox(
                    stack,
                    entity.getBoundingBox().getMinPosition(),
                    entity.getBoundingBox().getMaxPosition(),
                    color);
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true, fluent = true)
    @AllArgsConstructor
    public static class EntityBoxOutlineObject implements RenderObject {
        Entity entity;

        Color color;

        @Override
        public void render(PoseStack stack, float partialTicks) {
            RenderUtils.drawOutlinedBox(
                    stack,
                    entity.getBoundingBox().getMinPosition(),
                    entity.getBoundingBox().getMaxPosition(),
                    ColorUtils.withAlpha(color, 0.25F));
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true, fluent = true)
    @AllArgsConstructor
    public static class LineToEntityObject implements RenderObject {
        Entity entity;
        Color color;

        @Override
        public void render(PoseStack stack, float partialTicks) {
            Vec3 camera = RenderUtils.getCameraPos();
            Vec3 camerToBlock = this.entity.getBoundingBox().getCenter().subtract(camera);
            Vec3 cursorPos = RenderUtils.getTracerOrigin(1.0f);
            RenderUtils.drawLineVirtualCameraCoord(stack, cursorPos, camerToBlock, color);
        }
    }

    @Getter
    public static final ModuleGroup moduleManager = new ModuleGroup("Render");

    @Getter
    private static RenderExtra renderExtra;

    @Getter
    private static NoRender noRender;

    @Getter
    private static EntityLog entityLog;

    @Getter
    private static EntityESP entityESP;

    @Getter
    private static ExplosiveESP explosiveESP;

    @Getter
    private static NameTag nameTag;

    @Getter
    private static NameList nameList;

    @Getter
    private static ItemESP itemESP;

    @Getter
    private static ItemList itemList;

    @Getter
    private static ChestESP chestESP;

    @Getter
    private static WorldScanner worldScanner;

    @Getter
    private static MineESP mineESP;

    @Getter
    private static ProjectileESP projectileESP;

    @Getter
    private static Zoom zoom;

    @Getter
    private static SleepMode sleepMode;

    @Getter
    private static CustomOverlay customOverlay;

    @Getter
    private static Freecam freecam;

    @Getter
    private static RenderOptimize renderOptimize;

    @Getter
    private static Hud hud;

    @Getter
    private static ModuleListHud moduleListHud;

    @Getter
    private static InvHud invHud;

    @Getter
    private static PlayerStatistic playerStatistic;

    @Getter
    private static EquipmentHud equipmentHud;

    @Getter
    private static ItemDisplay itemDisplay;

    @Getter
    private static ShulkerDisplay shulkerDisplay;

    @Getter
    private static SkyRender skyRender;

    private static void initModules(ModuleManager m) {
        renderExtra = new RenderExtra().register(m);
        noRender = new NoRender().register(m);
        entityLog = new EntityLog().register(m);
        entityESP = new EntityESP().register(m);
        explosiveESP = new ExplosiveESP().register(m);

        nameTag = new NameTag().register(m);
        nameList = new NameList().register(m);
        chestESP = new ChestESP().register(m);
        itemESP = new ItemESP().register(m);
        itemList = new ItemList().register(m);
        worldScanner = new WorldScanner().register(m);
        mineESP = new MineESP().register(m);

        projectileESP = new ProjectileESP().register(m);
        zoom = new Zoom().register(m);
        sleepMode = new SleepMode().register(m);
        customOverlay = new CustomOverlay().register(m);
        freecam = new Freecam().register(m);
        renderOptimize = new RenderOptimize().register(m);
        hud = new Hud().register(m);
        moduleListHud = new ModuleListHud().register(m);
        invHud = new InvHud().register(m);
        playerStatistic = new PlayerStatistic().register(m);
        equipmentHud = new EquipmentHud().register(m);
        itemDisplay = new ItemDisplay().register(m);
        shulkerDisplay = new ShulkerDisplay().register(m);
        skyRender = new SkyRender().register(m);
    }

    static {
        RenderListener.getRender3DEvent().registerHandler(RenderTasks::onRenderVirtualTasks);
        RenderListener.getRender3DEvent().registerHandler(RenderTasks::onDebugRenderTick);
        moduleManager.registerFactories(RenderTasks::initModules);
        HackModules.registerModuleGroup(moduleManager);
    }
}
