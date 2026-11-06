package me.matl114.hacks.utils.render;

import me.matl114.utils.render.EntityBoundaryCollector;
import me.matl114.utils.render.EntityBoxCollector;
import me.matl114.utils.render.EntityFaceCollector;
import me.matl114.utils.render.EntityFrameCollector;
import me.matl114.utils.render.EntityOutlineCollector;
import net.minecraft.world.entity.Entity;
import static me.matl114.utils.RenderUtils.*;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.val;
import me.matl114.events.RenderListener;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.render.ColorQuad;
import me.matl114.utils.render.RenderCollector;
import me.matl114.versioned.api.VDrawContext;
import me.matl114.versioned.api.VRender;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector2d;

public class RenderCollectors {
    public static final Minecraft mc = Minecraft.getInstance();
    public static double HEIGHT = 9.0d;

    public static RenderCollector<AABB> createBoxCollector(
            boolean drawOutline, boolean drawSolid, boolean drawTraceLine) {
        return new RenderCollector.Impl<AABB>() {
            @Override
            public void render3D(PoseStack matrices, float partialTick) {
                if (entries.isEmpty()) return;
                Vec3 cameraPos = getCameraPos().reverse();
                if (drawSolid) {
                    VRender.getInstance()
                            .createQuadsLayer(
                                    (operation, vertexConsumer) -> {
                                        if (!entries.isEmpty()) {
                                            for (IndexEntry<AABB> boxEntry : entries) {
                                                var box = boxEntry.val().move(cameraPos);
                                                operation.drawSolidBoxQuad(
                                                        matrices,
                                                        vertexConsumer,
                                                        box.getMinPosition(),
                                                        box.getMaxPosition(),
                                                        boxEntry.index());
                                            }
                                        }
                                    },
                                    true);
                }
                if (drawOutline || drawTraceLine) {
                    VRender.getInstance().createLinesLayer((op, vtx) -> {
                        if (drawOutline) {
                            for (var re : entries) {
                                var box = re.val().move(cameraPos);
                                op.drawOutlinedBox(
                                        matrices, vtx, box.getMinPosition(), box.getMaxPosition(), re.index());
                            }
                        }
                        if (drawTraceLine) {
                            Vec3 traceOrigin = RenderUtils.getTracerOrigin(partialTick);
                            for (var re : entries) {
                                var box = re.val().getCenter().add(cameraPos);
                                op.drawLine(matrices, vtx, traceOrigin, box, re.index());
                            }
                        }
                    });
                }
            }

            @Override
            public void render2D(VDrawContext vdraw, float partialTick) {
                if (entries.isEmpty()) return;
                Matrix4f camMatrix = RenderListener.getWorldModelViewMatrix();
                Matrix4f projMatrix = RenderListener.getWorldBasicProjectionMatrix();
                if (drawOutline || drawSolid) {

                    Function<Vec3, Vector2d> projector = RenderUtils.createProjector(camMatrix, projMatrix, true);

                    for (IndexEntry<AABB> entry : entries) {
                        AABB box = entry.val();
                        Vec3 min = box.getMinPosition();
                        Vec3 max = box.getMaxPosition();

                        // 8 个顶点的局部偏移（在 Box 坐标系中）
                        double[] xs = {min.x, max.x};
                        double[] ys = {min.y, max.y};
                        double[] zs = {min.z, max.z};

                        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                        boolean anyNoVisible = false;

                        // 遍历 8 个顶点，投影并取极值
                        forEach:
                        for (double x : xs) {
                            for (double y : ys) {
                                for (double z : zs) {
                                    Vec3 worldPos = new Vec3(x, y, z);
                                    Vector2d screen = projector.apply(worldPos);
                                    if (screen != null) {
                                        if (screen.x < minX) minX = screen.x;
                                        if (screen.x > maxX) maxX = screen.x;
                                        if (screen.y < minY) minY = screen.y;
                                        if (screen.y > maxY) maxY = screen.y;
                                    } else {
                                        anyNoVisible = true;
                                        break forEach;
                                    }
                                }
                            }
                        }

                        if (anyNoVisible) continue; // 完全在相机后方或不可见

                        int color = entry.index(); // 颜色（含 alpha）
                        int x1 = (int) Math.round(minX);
                        int y1 = (int) Math.round(minY);
                        int x2 = (int) Math.round(maxX);
                        int y2 = (int) Math.round(maxY);

                        if (drawSolid) {
                            vdraw.fill(x1, y1, x2, y2, color);
                        }

                        // 绘制边框（如果启用）
                        if (drawOutline) {
                            vdraw.lineGui(x1, y1, x2, y1, color, 0);
                            vdraw.lineGui(x2, y1, x2, y2, color, 0);
                            vdraw.lineGui(x2, y2, x1, y2, color, 0);
                            vdraw.lineGui(x1, y2, x1, y1, color, 0);
                        }
                    }
                }
                if (drawTraceLine) {
                    Function<Vec3, Vector2d> projector = RenderUtils.createProjector(camMatrix, projMatrix, false);
                    Vector2d screenCenter = RenderUtils.getScreenSize().mul(0.5D, 0.5D);
                    vdraw.getMatrices().pushMatrix();
                    try {
                        vdraw.getMatrices().translate((float) screenCenter.x, (float) screenCenter.y);
                        for (var entry : entries) {
                            Vec3 pos = entry.val().getCenter();
                            int color = entry.index();
                            Vector2d screenPos = projector.apply(pos);
                            if (screenPos != null) {
                                vdraw.lineGuiGradient(
                                        (int) (screenPos.x - screenCenter.x),
                                        (int) (screenPos.y - screenCenter.y),
                                        0,
                                        0,
                                        color,
                                        color,
                                        0);
                            }
                        }
                    } finally {
                        vdraw.getMatrices().popMatrix();
                    }
                }
            }
        };
    }

    public static RenderCollector<AABB> createOutlineCollector() {
        return new RenderCollector<AABB>() {
            private final Map<RenderElements.Line, Integer> lines = new LinkedHashMap<>();

            private void addInternal(RenderElements.Line line, int color) {
                if (lines.containsKey(line)) {
                    lines.remove(line);
                } else {
                    lines.put(line, color);
                }
            }

            @Override
            public void submit(AABB val, int color) {
                for (RenderElements.Line line : RenderElements.boxOutline(val)) {
                    addInternal(line, color);
                }
            }

            @Override
            public void clear() {
                lines.clear();
            }

            @Override
            public void render3D(PoseStack matrices, float partialTick) {
                if (lines.isEmpty()) return;
                Vec3 cameraPos = getCameraPos().reverse();
                VRender.getInstance().createLinesLayer((op, vtx) -> {
                    for (var entry : lines.entrySet()) {
                        var line = entry.getKey().offset(cameraPos);
                        op.drawLine(
                                matrices,
                                vtx,
                                new Vec3(line.x0(), line.y0(), line.z0()),
                                new Vec3(line.x1(), line.y1(), line.z1()),
                                entry.getValue());
                    }
                });
            }

            @Override
            public void render2D(VDrawContext vDrawContext, float partialTick) {}
        };
    }

    public static RenderCollector<AABB> createFaceCollector() {
        return new RenderCollector<AABB>() {
            private final Map<RenderElements.Quad, Integer> quads = new LinkedHashMap<>();

            private void addInternal(RenderElements.Quad quad, int color) {
                if (quads.containsKey(quad)) {
                    quads.remove(quad);
                } else {
                    quads.put(quad, color);
                }
            }

            @Override
            public void submit(AABB val, int color) {
                for (RenderElements.Quad quad : RenderElements.boxFaces(val)) {
                    addInternal(quad, color);
                }
            }

            @Override
            public void clear() {
                quads.clear();
            }

            @Override
            public void render3D(PoseStack matrices, float partialTick) {
                if (quads.isEmpty()) return;
                Vec3 cameraPos = getCameraPos().reverse();
                VRender.getInstance()
                        .createQuadsLayer(
                                (op, vtx) -> {
                                    for (var entry : quads.entrySet()) {
                                        var quad =
                                                entry.getKey().offset(cameraPos).toRenderQuad();
                                        op.drawQuad(matrices, vtx, quad, ColorQuad.of(entry.getValue()));
                                    }
                                },
                                false);
            }

            @Override
            public void render2D(VDrawContext vDrawContext, float partialTick) {}
        };
    }

    public static RenderCollector<Entity> createEntityOutlineCollector() {
        return new EntityOutlineCollector();
    }

    public static RenderCollector<Entity> createEntityFaceCollector() {
        return new EntityFaceCollector();
    }

    public static RenderCollector<Entity> createEntityBoundaryCollector() {
        return new EntityBoundaryCollector();
    }

    public static RenderCollector<Entity> createEntityFrameCollector() {
        return new EntityFrameCollector();
    }

    public static RenderCollector<Entity> createEntityBoxCollector(
            boolean drawOutline, boolean drawSolid, boolean drawTraceLine) {
        return new EntityBoxCollector(drawOutline, drawSolid, drawTraceLine);
    }

    public static RenderCollector<Vec3> createTracerCollector() {
        return new RenderCollector.Impl<Vec3>() {
            @Override
            public void render3D(PoseStack matrices, float partialTick) {
                if (entries.isEmpty()) return;
                Vec3 cameraPos = getCameraPos().reverse();
                Vec3 traceOrigin = RenderUtils.getTracerOrigin(partialTick);
                VRender.getInstance().createLinesLayer((op, vtx) -> {
                    for (var re : entries) {
                        var box = re.val().add(cameraPos);
                        op.drawLine(matrices, vtx, traceOrigin, box, re.index());
                    }
                });
            }

            @Override
            public void render2D(VDrawContext vdraw, float partialTick) {
                if (entries.isEmpty()) return;
                Matrix4f cam = RenderListener.getWorldModelViewMatrix();
                Matrix4f proj = RenderListener.getWorldBasicProjectionMatrix();
                Function<Vec3, Vector2d> projector = RenderUtils.createProjector(cam, proj, false);
                Vector2d screenCenter = RenderUtils.getScreenSize().mul(0.5D, 0.5D);
                vdraw.getMatrices().pushMatrix();
                try {
                    vdraw.getMatrices().translate((float) screenCenter.x, (float) screenCenter.y);
                    for (var entry : entries) {
                        Vec3 pos = entry.val();
                        int color = entry.index();
                        Vector2d screenPos = projector.apply(pos);
                        if (screenPos != null) {
                            vdraw.lineGuiGradient(
                                    (int) (screenPos.x - screenCenter.x),
                                    (int) (screenPos.y - screenCenter.y),
                                    0,
                                    0,
                                    color,
                                    color,
                                    0);
                        }
                    }
                } finally {
                    vdraw.getMatrices().popMatrix();
                }
            }
        };
    }

    public static RenderCollector<Entity> createEntityTracerCollector() {
        return new RenderCollector.Impl<Entity>() {
            private final RenderCollector<Vec3> tracerCollector = createTracerCollector();

            @Override
            public void render3D(PoseStack matrices, float partialTick) {
                if (entries.isEmpty()) return;
                for (var entry : entries) {
                    tracerCollector.submit(
                            RenderUtils.getLerpedBox(entry.val(), partialTick).getCenter(), entry.index());
                }
                tracerCollector.render3D(matrices, partialTick);
                tracerCollector.clear();
            }

            @Override
            public void render2D(VDrawContext vdraw, float partialTick) {
                if (entries.isEmpty()) return;
                for (var entry : entries) {
                    tracerCollector.submit(
                            RenderUtils.getLerpedBox(entry.val(), partialTick).getCenter(), entry.index());
                }
                tracerCollector.render2D(vdraw, partialTick);
                tracerCollector.clear();
            }
        };
    }

    public static RenderCollector<List<Vec3>> createLinesCollector() {
        return new RenderCollector.Impl<List<Vec3>>() {

            @Override
            public void render3D(PoseStack matrices, float partialTick) {
                if (entries.isEmpty()) return;
                Vec3 cameraPos = getCameraPos().reverse();
                VRender.getInstance().createLinesLayer((op, vtx) -> {
                    for (var re : entries) {
                        var lines = re.val().stream().map(s -> s.add(cameraPos)).toList();
                        op.drawLines(matrices, vtx, lines, re.index());
                    }
                });
            }

            @Override
            public void render2D(VDrawContext vDrawContext, float partialTick) {
                // not implemented yet
            }
        };
    }

    public static RenderCollector<RenderElements.Text> createTextCollector() {
        return new RenderCollector.Impl<>() {
            @Override
            public void render3D(PoseStack stack, float partialTick) {
                if (entries.isEmpty()) return;
                Vec3 cameraNeg = getCameraPos().reverse();
                double textHeight = HEIGHT;
                for (IndexEntry<RenderElements.Text> entry : entries) {
                    var col = entry.index();
                    var vec3d = entry.val().position();
                    var text = entry.val().text();
                    var delta = vec3d.add(cameraNeg);
                    float scale = entry.val().scale();
                    stack.pushPose();
                    stack.translate(delta.x, delta.y, delta.z);
//#if MC >= 260300
//$$                     stack.rotate(RenderUtils.getBillboardRotation(Display.BillboardConstraints.CENTER, 0, 0));
//#else
                    stack.mulPose(RenderUtils.getBillboardRotation(Display.BillboardConstraints.CENTER, 0, 0));
//#endif
                    stack.scale(0.03125F * scale, 0.03125F * scale, 1);
                    List<Component> listText = ChatUtils.splitToMultiLineText(text, Integer.MAX_VALUE);
                    int index = 0;
                    for (var re : listText) {
                        VRender.getInstance()
                                .drawTextCameraCoord(
                                        re.getVisualOrderText(),
                                        stack,
                                        Vec3.ZERO.add(0, -index * textHeight, 0),
                                        entry.val().offSetFlag(),
                                        new Color(col),
                                        VRender.DEFAULT_TEXT);
                        index += 1;
                    }
                    stack.popPose();
                }
            }

            @Override
            public void render2D(VDrawContext vdraw, float partialTick) {
                if (entries.isEmpty()) return;
                Matrix4f cam = RenderListener.getWorldModelViewMatrix();
                Matrix4f proj = RenderListener.getWorldBasicProjectionMatrix();
                Function<Vec3, Vector2d> projector = RenderUtils.createProjector(cam, proj);
                for (var entry : entries) {
                    var pair = entry.val();
                    Vec3 pos = pair.position();
                    int color = entry.index();
                    Vector2d screenPos = projector.apply(pos);
                    if (screenPos != null) {
                        vdraw.pushMatrix();
                        try {
                            vdraw.getMatrices().translate((float) screenPos.x, (float) screenPos.y);
                            float scale = entry.val().scale();
                            vdraw.getMatrices().scale(scale, scale);
                            Component text = pair.text();
                            int offsetFlag = pair.offSetFlag();
                            double dd = mc.font.getSplitter().stringWidth(text);
                            int xAlign = offsetFlag % 3;
                            int yAlign = offsetFlag / 3;
                            vdraw.getMatrices().translate((float) (-((dd * xAlign) / 2.0D)), (float)
                                    (-((HEIGHT * yAlign) / 2.0D)));
                            vdraw.drawText(mc.font, text.getVisualOrderText(), 0, 0, color, true);
                        } finally {
                            vdraw.popMatrix();
                        }
                    }
                }
            }
        };
    }
}
