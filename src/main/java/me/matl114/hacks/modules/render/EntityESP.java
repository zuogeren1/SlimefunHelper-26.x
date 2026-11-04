package me.matl114.hacks.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.matl114.accessors.hacks.EntityInternalAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render2D;
import me.matl114.events.impl.Render3D;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.EntryPrimitiveMap;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hacks.utils.render.RenderMode;
import me.matl114.managers.Configs;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.ColorUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import me.matl114.versioned.api.VDrawContext;
import me.matl114.versioned.impl.Render_v1_21_11;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;

/**
 * 实体 ESP。
 *
 * <p>画法分三条通道，各管一种观感：
 *
 * <ul>
 *   <li>线框盒与追踪线走 {@link Render_v1_21_11#ESP_TRACE_LINES}：没有深度测试，盒子与线都能穿过方块。
 *       线宽是屏幕像素（{@code rendertype_lines} 的顶点着色器按 {@code Normal} 展开），不随距离变化。</li>
 *   <li>高亮填充走 {@code EntityFaceCollector#render2D}：把实体模型面按贴图 alpha 镂空画进掩膜，
 *       再全屏合成一层很淡的同色罩（alpha {@value #FILL_ALPHA}）。实体本体照常可见，只有它被染了色。</li>
 *   <li>高亮轮廓走 {@code EntityBoundaryCollector#render2D}：同一份掩膜，只画剪影之外那圈固定 2.5 像素的描边。</li>
 * </ul>
 *
 * <p>高亮与 {@code render-mode} 无关：两条掩膜通道恒在 2D 阶段提交，所以 3D 模式下也有高亮；
 * {@code render-mode} 只决定盒子与追踪线画在 3D 还是 2D。
 */
public class EntityESP extends BaseModule {
    /** 元数据键：实体在本模块里的追踪级别，取值见 {@link EntityInternalAccess#RENDER_LEVEL_DISABLE}。 */
    private static final String KEY_RENDER_LEVEL = "render-tracked-level";

    /** 线宽是屏幕空间像素，不随距离变细；比原版碰撞箱描边略粗，才像 ESP 标记。 */
    private static final float LINE_WIDTH_SCALE = 0.6F;

    /** 追踪线起点在相机空间里的位置：视线前方 10 格，与上游 {@code getTracerOrigin} 一致。 */
    private static final double TRACE_FORWARD = 10.0D;

    /** 起点再往屏幕下方偏一点：线段与视线共线时顶点着色器的屏幕法向会退化成 NaN。 */
    private static final double TRACE_DOWN = 0.02D;

    /** 掩膜填充色的 alpha：淡到能看见实体本体，又能一眼看出被标了。 */
    private static final int FILL_ALPHA = 32;

    /** 8 个角点之间的 12 条棱。角点编号 bit0=x、bit1=y、bit2=z，每条棱的两端只差一个 bit。 */
    private static final int[] BOX_EDGES = {
        0, 1, 2, 3, 4, 5, 6, 7,
        0, 2, 1, 3, 4, 6, 5, 7,
        0, 4, 1, 5, 2, 6, 3, 7
    };

    public final ModulePath entityRoot = makePath(Configs.RENDER_CONFIG, "detect-entity");
    public final ModulePath entityEsp = entityRoot.add("entity-esp");

    public EntityESP() {
        super("EntityESP");
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(entityEsp.add("enable")).build();

    public final KeyBindRef hotkeyToggle = toggleHotkey(
                    entityEsp.add("hotkey"), new MultiKeyBind(), entityEsp.add("enable"))
            .build();

    public final EnumRef<RenderMode> renderMode = builder(entityEsp.add("render-mode"), RenderMode.class)
            .defaultValue(RenderMode.RENDER_3D)
            .build();

    public final NBTRef<EntrySet<EntityType<?>>> whiteList = builder(
                    entityEsp.add("whitelist"), EntrySet.<EntityType<?>>parameter())
            .defaultValue(new EntrySet<>(new Regex("player,wither"), BuiltInRegistries.ENTITY_TYPE))
            .build();

    public final NBTRef<EntryPrimitiveMap<EntityType<?>, TextColor>> renderColor = builder(
                    entityEsp.add("color"),
                    EntryPrimitiveMap.<EntityType<?>, TextColor>parameter())
            .defaultValue(new EntryPrimitiveMap<>(
                    BuiltInRegistries.ENTITY_TYPE,
                    NBTTypes.COLOR_TYPE,
                    Map.of(
                            EntityTypes.PLAYER, ColorUtils.color(ChatFormatting.YELLOW),
                            EntityTypes.ARMOR_STAND, ColorUtils.color(ChatFormatting.GREEN)),
                    ColorUtils.color(ChatFormatting.RED)))
            .build();

    public final NBTRef<EntryPrimitiveMap<EntityType<?>, Boolean>> boxOption = builder(
                    entityEsp.add("boxing-option"),
                    EntryPrimitiveMap.<EntityType<?>, Boolean>parameter())
            .defaultValue(new EntryPrimitiveMap<>(
                    BuiltInRegistries.ENTITY_TYPE,
                    NBTTypes.BOOLEAN_TYPE,
                    Map.of(
                            EntityTypes.PLAYER, true,
                            EntityTypes.END_CRYSTAL, true,
                            EntityTypes.WITHER, true),
                    false))
            .build();

    public final NBTRef<EntryPrimitiveMap<EntityType<?>, Boolean>> traceOption = builder(
                    entityEsp.add("trace-option"),
                    EntryPrimitiveMap.<EntityType<?>, Boolean>parameter())
            .defaultValue(new EntryPrimitiveMap<>(
                    BuiltInRegistries.ENTITY_TYPE,
                    NBTTypes.BOOLEAN_TYPE,
                    Map.of(
                            EntityTypes.PLAYER, true,
                            EntityTypes.END_CRYSTAL, false,
                            EntityTypes.WITHER, true),
                    false))
            .build();

    public final NBTRef<EntryPrimitiveMap<EntityType<?>, Boolean>> highlightOption = builder(
                    entityEsp.add("highlight-option"),
                    EntryPrimitiveMap.<EntityType<?>, Boolean>parameter())
            .defaultValue(new EntryPrimitiveMap<>(
                    BuiltInRegistries.ENTITY_TYPE,
                    NBTTypes.BOOLEAN_TYPE,
                    Map.of(
                            EntityTypes.PLAYER, true,
                            EntityTypes.END_CRYSTAL, true,
                            EntityTypes.WITHER, true),
                    true))
            .build();

    @Override
    public void registerAll() {
        super.registerAll();

        registerListener(RenderListener.getRender3DEvent(), this::onRender3D);
        registerListener(RenderListener.getRender2DEvent(), this::onRender2D);
        registerListener(Listener.getPostGameTick(), this::onTick);
    }

    /** 一帧内要画的实体：三个开关与颜色都在 tick 线程解析好，渲染线程不再读配置。 */
    private record Target(Entity entity, int color, boolean box, boolean trace) {}

    /** 世界坐标下的一段线。 */
    private record Segment(Vec3 from, Vec3 to, int color) {}

    /**
     * 整个 tick 换掉一份不可变快照，而不是原地改列表：渲染线程可能正好在遍历上一份，
     * 读到的旧表也是完整的，不会看到画到一半的内容。
     */
    private volatile List<Target> targets = List.of();

    /** 掩膜高亮：填充（淡罩）与轮廓各一个收集器，共用同一份模型面抓取。 */
    private final RenderCollector<Entity> faceHighlight = RenderCollectors.createEntityFaceCollector();

    private final RenderCollector<Entity> boundaryHighlight = RenderCollectors.createEntityBoundaryCollector();

    public void onTick() {
        faceHighlight.clear();
        boundaryHighlight.clear();
        if (checkNull()) {
            targets = List.of();
            return;
        }

        boolean enabled = enable.get();
        EntrySet<EntityType<?>> whitelist = whiteList.get();
        EntryPrimitiveMap<EntityType<?>, TextColor> colors = renderColor.get();
        EntryPrimitiveMap<EntityType<?>, Boolean> boxes = boxOption.get();
        EntryPrimitiveMap<EntityType<?>, Boolean> traces = traceOption.get();
        EntryPrimitiveMap<EntityType<?>, Boolean> glows = highlightOption.get();
        Entity focused = focusedEntity();

        List<Target> nextTargets = new ArrayList<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.isRemoved() || entity == focused) {
                continue;
            }
            byte level = resolveRenderLevel(entity, whitelist);
            boolean forced = level == EntityInternalAccess.RENDER_LEVEL_FORCE;
            boolean whitelisted = level == EntityInternalAccess.RENDER_LEVEL_WHITELIST;
            if (!forced && !(whitelisted && enabled)) {
                continue;
            }

            EntityType<?> type = entity.getType();
            TextColor color = colors.getEntryValue(type);
            if (color == null) {
                continue;
            }
            boolean box = boxes.getEntryValueOr(type, false);
            boolean trace = traces.getEntryValueOr(type, false);
            boolean glow = glows.getEntryValueOr(type, false);
            if (!box && !trace && !glow) {
                continue;
            }

            int argb = ColorUtils.withAlphaInt(color.getValue(), 255);
            nextTargets.add(new Target(entity, argb, box, trace));
            if (glow) {
                // 填充用淡色：alpha 压到 32 才看得见实体本体，压满会糊成一块色块
                faceHighlight.submit(entity, ColorUtils.withAlphaInt(argb, FILL_ALPHA));
                boundaryHighlight.submit(entity, argb);
            }
        }

        targets = nextTargets;
    }

    /**
     * 维护并回读实体上的 render-level：FORCE 由外部设置，表示不看白名单也不看总开关；
     * 其余情况按白名单重算。默认值 DISABLE 不落元数据 —— 每 tick 给世界里每个实体都写一次
     * 会白造一份 {@code MetaData}。
     */
    private byte resolveRenderLevel(Entity entity, EntrySet<EntityType<?>> whitelist) {
        EntityInternalAccess<Entity> access = EntityInternalAccess.of(entity);
        Byte stored = access.isMetaEmpty() ? null : access.getMetadata().get(this, KEY_RENDER_LEVEL);
        if (stored != null && stored == EntityInternalAccess.RENDER_LEVEL_FORCE) {
            return stored;
        }
        byte resolved = whitelist.test(entity.getType())
                ? EntityInternalAccess.RENDER_LEVEL_WHITELIST
                : EntityInternalAccess.RENDER_LEVEL_DISABLE;
        if (stored == null) {
            if (resolved != EntityInternalAccess.RENDER_LEVEL_DISABLE) {
                access.getMetadata().put(this, KEY_RENDER_LEVEL, resolved);
            }
        } else if (stored != resolved) {
            access.getMetadata().put(this, KEY_RENDER_LEVEL, resolved);
        }
        return resolved;
    }

    public void onRender3D(Event<Render3D> event) {
        if (checkNull()) return;
        if (!renderMode.get().isIn(RenderMode.RENDER_3D)) return;

        List<Target> snapshot = targets;
        if (snapshot.isEmpty()) return;
        SubmitNodeCollector collector = Render_v1_21_11.getCollector();
        if (collector == null) return;

        List<Segment> segments = collectSegments(snapshot, event.context().partialTicks());
        if (segments.isEmpty()) return;
        submitSegments(collector, event.context().stack(), segments, lineWidth());
    }

    public void onRender2D(Event<Render2D> event) {
        if (checkNull()) return;
        float tickDelta = event.context().partialTicks();

        // 高亮恒在 2D 阶段提交（与 render-mode 无关）：掩膜要的是一帧的世界相机矩阵，
        // 3D 与 2D 两种显示模式下这份矩阵都在。
        if (enable.get()) {
            VDrawContext draw = event.context().drawContext();
            faceHighlight.render2D(draw, tickDelta);
            boundaryHighlight.render2D(draw, tickDelta);
        }

        if (!renderMode.get().isIn(RenderMode.RENDER_2D)) return;

        List<Target> snapshot = targets;
        if (snapshot.isEmpty()) return;
        CameraRenderState camera = cameraState();
        if (!camera.initialized) return;

        VDrawContext draw = event.context().drawContext();
        Function<Vec3, Vector2d> projector =
                RenderUtils.createProjector(camera.viewRotationMatrix, camera.projectionMatrix, true);
        Vector2d screenCenter = RenderUtils.getScreenSize().mul(0.5D, 0.5D);

        for (Target target : snapshot) {
            AABB box = RenderUtils.getLerpedBox(target.entity(), tickDelta);
            if (target.box()) {
                drawBox2D(draw, projector, box, target.color());
            }
            if (target.trace()) {
                Vector2d center = projector.apply(box.getCenter());
                if (center != null) {
                    int fromX = (int) Math.round(screenCenter.x);
                    int fromY = (int) Math.round(screenCenter.y);
                    int toX = (int) Math.round(center.x);
                    int toY = (int) Math.round(center.y);
                    // GUI 线段按 (x1,y1,x2,y2) 算包围盒，坐标反着传会得到负宽高，可能被裁剪判定丢掉；
                    // 线段两端同色，交换两端等价，所以这里换成 x 递增，别无谓地被吃掉。
                    if (toX < fromX) {
                        int swap = fromX;
                        fromX = toX;
                        toX = swap;
                        swap = fromY;
                        fromY = toY;
                        toY = swap;
                    }
                    draw.lineGui(fromX, fromY, toX, toY, target.color(), 0);
                }
            }
        }
    }

    /** 把这一帧要画的线算成世界坐标线段：盒子边 + 到实体中心的追踪线。 */
    private List<Segment> collectSegments(List<Target> snapshot, float tickDelta) {
        Vec3 tracerOrigin = traceOrigin(tickDelta);
        List<Segment> segments = new ArrayList<>();
        for (Target target : snapshot) {
            AABB box = RenderUtils.getLerpedBox(target.entity(), tickDelta);
            if (target.box()) {
                addBoxSegments(segments, box, target.color());
            }
            if (target.trace()) {
                segments.add(new Segment(tracerOrigin, box.getCenter(), target.color()));
            }
        }
        return segments;
    }

    private static void addBoxSegments(List<Segment> out, AABB box, int color) {
        Vec3[] corners = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = new Vec3(
                    (i & 1) == 0 ? box.minX : box.maxX,
                    (i & 2) == 0 ? box.minY : box.maxY,
                    (i & 4) == 0 ? box.minZ : box.maxZ);
        }
        for (int i = 0; i < BOX_EDGES.length; i += 2) {
            out.add(new Segment(corners[BOX_EDGES[i]], corners[BOX_EDGES[i + 1]], color));
        }
    }

    /** 3D 线：走没有深度测试的线层，盒子与追踪线都穿墙（与上游一致）。 */
    private static void submitSegments(
            SubmitNodeCollector collector, PoseStack stack, List<Segment> segments, float lineWidth) {
        Vec3 cameraPos = RenderUtils.getCameraPos();
        collector.submitCustomGeometry(stack, Render_v1_21_11.ESP_TRACE_LINES, (pose, buffer) -> {
            for (Segment segment : segments) {
                emitSegment(buffer, pose, segment, cameraPos, lineWidth);
            }
        });
    }

    /**
     * 一段线写成 LINES 拓扑的两个顶点。
     *
     * <p>{@code Normal} 在这条管线里不是法线，而是「线段在屏幕上的方向」的取样点：
     * 顶点着色器用 {@code Position + Normal} 再投一次影，据此算出垂直于线段的屏幕位移
     * （见 {@code core/rendertype_lines.vsh}）。所以它必须与线段同向且非零，
     * 而 {@code LineWidth} 是屏幕像素宽 —— 漏写其中任何一个，这条线就是隐形的。
     */
    private static void emitSegment(
            VertexConsumer buffer, PoseStack.Pose pose, Segment segment, Vec3 cameraPos, float lineWidth) {
        float x0 = (float) (segment.from().x - cameraPos.x);
        float y0 = (float) (segment.from().y - cameraPos.y);
        float z0 = (float) (segment.from().z - cameraPos.z);
        float x1 = (float) (segment.to().x - cameraPos.x);
        float y1 = (float) (segment.to().y - cameraPos.y);
        float z1 = (float) (segment.to().z - cameraPos.z);

        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        // 退化线段没有方向，算不出屏幕法向；强行归一化会写出 NaN 顶点，把整批几何一起丢掉。
        if (length < 1.0E-4F) {
            return;
        }
        dx /= length;
        dy /= length;
        dz /= length;

        int color = segment.color();
        buffer.addVertex(pose, x0, y0, z0)
                .setColor(color)
                .setNormal(pose, dx, dy, dz)
                .setLineWidth(lineWidth);
        buffer.addVertex(pose, x1, y1, z1)
                .setColor(color)
                .setNormal(pose, dx, dy, dz)
                .setLineWidth(lineWidth);
    }

    /**
     * 2D 包围盒：八个角点全投影到屏幕后取包围矩形画边框。
     *
     * <p>只要有一个角在相机后面就整框不画 —— 透视除法会把它翻到屏幕另一侧，
     * 那样算出来的矩形位置是错的。
     */
    private static void drawBox2D(
            VDrawContext draw, Function<Vec3, Vector2d> projector, AABB box, int color) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3(
                    (i & 1) == 0 ? box.minX : box.maxX,
                    (i & 2) == 0 ? box.minY : box.maxY,
                    (i & 4) == 0 ? box.minZ : box.maxZ);
            Vector2d screen = projector.apply(corner);
            if (screen == null) {
                return;
            }
            minX = Math.min(minX, screen.x);
            minY = Math.min(minY, screen.y);
            maxX = Math.max(maxX, screen.x);
            maxY = Math.max(maxY, screen.y);
        }

        int x1 = (int) Math.round(minX);
        int y1 = (int) Math.round(minY);
        int x2 = (int) Math.round(maxX);
        int y2 = (int) Math.round(maxY);
        draw.lineGui(x1, y1, x2, y1, color, 0);
        draw.lineGui(x2, y1, x2, y2, color, 0);
        draw.lineGui(x2, y2, x1, y2, color, 0);
        draw.lineGui(x1, y2, x1, y1, color, 0);
    }

    private static CameraRenderState cameraState() {
        return mc.gameRenderer.gameRenderState.levelRenderState.cameraRenderState;
    }

    private static Entity focusedEntity() {
        return mc.gameRenderer.mainCamera().entity();
    }

    /**
     * 追踪线起点：相机前 {@link #TRACE_FORWARD} 格、再往屏幕下方偏 {@link #TRACE_DOWN} 格。
     *
     * <p>起点不能正好落在相机视线上。{@code rendertype_lines} 的顶点着色器按
     * {@code normalize((ndc(P + Normal) - ndc(P)) * ScreenSize)} 求线段在屏幕上的方向，而透视只缩放 w、
     * 不移动视线上的投影点：线段与视线共线时这个差值为零，{@code normalize} 得到 NaN，整条线段不出像素。
     * 线框盒的棱不会与视线共线，只有追踪线会，所以起点必须同时带前向与下向分量，视觉上就是从屏幕下方射向目标。
     */
    private static Vec3 traceOrigin(float tickDelta) {
        Vec3 look = RenderUtils.getCameraLookVec(tickDelta);
        Vec3 down = cameraDown(look);
        return RenderUtils.getCameraPos()
                .add(look.scale(TRACE_FORWARD))
                .add(down.x * TRACE_DOWN, down.y * TRACE_DOWN, down.z * TRACE_DOWN);
    }

    /**
     * 视线的「屏幕下方」：先叉出水平侧向，再叉一次视线得到下方。
     * 视线接近竖直时换一根参照轴，否则两次叉积都退化成零向量。
     */
    private static Vec3 cameraDown(Vec3 look) {
        Vec3 reference = Math.abs(look.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 side = look.cross(reference).normalize();
        return look.cross(side).normalize();
    }

    private static float lineWidth() {
        // 2D 用的是 1 像素，3D 这边按窗口线宽算，两边观感才一致。
        return Math.max(1.0F, mc.getWindow().getAppropriateLineWidth() * LINE_WIDTH_SCALE);
    }
}
