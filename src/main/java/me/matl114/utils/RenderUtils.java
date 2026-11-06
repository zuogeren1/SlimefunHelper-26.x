package me.matl114.utils;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.awt.*;
import java.util.List;
import java.util.function.Function;
import me.matl114.utils.render.ColorQuad;
import me.matl114.utils.render.Quad;
import me.matl114.utils.world.RegionPos;
import me.matl114.versioned.api.VRender;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.*;

public class RenderUtils {
    private static final Minecraft mc = Minecraft.getInstance();
    // 说明：
    // LINES 两点绘制一个线段
    // LINE_STRIP 折线
    // TRIANGLES 三角型
    // TRIANGLE_STRIP 每个三角行和前一个三角行共享两个顶点
    // TRIANGLE_FAN 三角行扇
    // QUADS 四边形

    // VertexFormats要和shader匹配以及和vertex的参数匹配
    // 比如PositionColor就要bufferbuilder.vertex.color

    // vertex似乎是用来画线和面的

    // vertexBuffer可以缓存buffer的行为，可以在不同的变换矩阵下重复使用， 使用bind();draw(viewMatrix, projMatrix, shader);unbind();
    // projMatrix从RenderSystem.getProjectionMatrix();获取, shader从RenderSystem.getShader();获取,
    // viewMatrix是正常传参中的玩家位置matrixStack.position
    @ApiMethod
    public static Vec3 getCameraPos() {
        var d = mc.gameRenderer.mainCamera();
        return d == null ? Vec3.ZERO : d.position();
    }

    @ApiMethod
    public static Vec3 getCameraEntityPos() {
        var d = mc.gameRenderer.mainCamera();
        if (d == null) return Vec3.ZERO;
        Entity entity = d.entity();
        if (entity == null) {
            return d.position();
        } else {
            return entity.position();
        }
    }

    @ApiMethod
    public static BlockPos getCameraBlockPos() {
        Camera camera = mc.gameRenderer.mainCamera();
        if (camera == null) return BlockPos.ZERO;

        return camera.blockPosition();
    }

    @ApiMethod
    public static Vec3 getCameraLookVec(float partialTicks) {
        Camera camera = mc.gameRenderer.mainCamera();
        Vector3fc vector3f = camera.forwardVector();
        return new Vec3(vector3f.x(), vector3f.y(), vector3f.z());
    }

    @ApiMethod
    public static Vec3 getTracerOrigin(float partialTicks) {
        // if (mc.options.getPerspective() == Perspective.THIRD_PERSON_FRONT) start = start.negate();
        return getCameraLookVec(partialTicks).scale(10);
    }

    @ApiMethod
    public static RegionPos getCameraRegion() {
        return RegionPos.of(getCameraBlockPos());
    }

    @ApiMethod
    public static void applyRegionalRenderOffset(PoseStack matrixStack, RegionPos region) {
        Vec3 offset = region.toVec3d().subtract(getCameraPos());
        matrixStack.translate(offset.x, offset.y, offset.z);
    }
    /**
     * note: start mush be pair with stop!
     * @param matrixStack
     */
    private static boolean drawVirtual;

    public static boolean startDrawVirtual(PoseStack matrixStack) {
        if (!drawVirtual) {
            drawVirtual = true;
            matrixStack.pushPose();
            return true;
        } else {
            return false;
        }

        //        GL11.glEnable(GL11.GL_BLEND);
        //        //remove this
        ////        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        //        GL11.glDisable(GL11.GL_DEPTH_TEST);
        //        GL11.glDepthMask(false);
    }

    public static boolean stopDrawVirtual(PoseStack matrixStack) {
        if (drawVirtual) {
            drawVirtual = false;
            resetCurrentShaderColor();
            //        GL11.glDisable(GL11.GL_BLEND);
            //        GL11.glEnable(GL11.GL_DEPTH_TEST);
            //        GL11.glDepthMask(true);
            matrixStack.popPose();
            return true;
        } else {
            return false;
        }
    }
    // in world coord
    public static void drawStripLineVirtual(PoseStack matrixStack, List<Vec3> path, Color color) {
        if (path.size() < 2) return;
        Vec3 vec3d = getCameraPos();
        VRender.getInstance()
                .drawStripLineVirtualCameraCoord(
                        matrixStack, path.stream().map(v -> v.subtract(vec3d)).toList(), color);
    }

    public static void drawStripLineVirtualCameraCoord(PoseStack matrixStack, List<Vec3> path, Color color) {
        VRender.getInstance().drawStripLineVirtualCameraCoord(matrixStack, path, color);
    }

    // in world coord
    public static void drawLineVirtual(PoseStack matrixStack, Vec3 from, Vec3 to, Color color) {
        drawLineVirtual(matrixStack, List.of(from, to), color);
    }

    public static void drawLineVirtualCameraCoord(PoseStack matrixStack, Vec3 from, Vec3 to, Color color) {
        drawLineVirtualCameraCoord(matrixStack, List.of(from, to), color);
    }
    // in world coord
    public static void drawLineVirtual(PoseStack matrixStack, List<Vec3> pairs, Color color) {
        if (pairs.size() < 2) return;
        Vec3 vec3d = getCameraPos();
        VRender.getInstance()
                .drawLineVirtualCameraCoord(
                        matrixStack, pairs.stream().map(v -> v.subtract(vec3d)).toList(), color);
    }

    public static void drawLineVirtualCameraCoord(PoseStack matrixStack, List<Vec3> pairs, Color color) {
        VRender.getInstance().drawLineVirtualCameraCoord(matrixStack, pairs, color);
    }

    public static void drawOutlinedBox(PoseStack matrix, Vec3 from, Vec3 to, Color color) {
        Vec3 vec3d = getCameraPos();
        drawOutlinedBoxCameraCoord(matrix, from.subtract(vec3d), to.subtract(vec3d), color);
    }

    public static void drawOutlinedBoxCameraCoord(PoseStack matrix, Vec3 from, Vec3 to, Color color) {
        VRender.getInstance().drawOutlinedBoxCameraCoord(matrix, from, to, color);
    }

    public static void drawSolidBox(PoseStack matrix, Vec3 from, Vec3 to, Color color) {
        Vec3 vec3d = getCameraPos();
        VRender.getInstance().drawSolidBoxCameraCoord(matrix, from.subtract(vec3d), to.subtract(vec3d), color);
    }

    public static void drawQuadCameraCoord(PoseStack matrix4f, Vec3 a, Vec3 b, Vec3 c, Vec3 d, Color color) {
        VRender.getInstance().drawQuadCameraCoord(matrix4f, new Quad(a, b, c, d), ColorQuad.of(color.getRGB()));
    }

    public static void drawQuad(PoseStack matrix4f, Vec3 a, Vec3 b, Vec3 c, Vec3 d, Color color) {
        Vec3 vec3d = getCameraPos();
        drawQuadCameraCoord(
                matrix4f, a.subtract(vec3d), b.subtract(vec3d), c.subtract(vec3d), d.subtract(vec3d), color);
    }

    @Deprecated
    public static void resetCurrentShaderColor() {}

    @ApiMethod
    public static AABB getLerpedBox(Entity e, float partialTicks) {
        // When an entity is removed, it stops moving and its lastRenderX/Y/Z
        // values are no longer updated.
        if (e.isRemoved()) return e.getBoundingBox();

        Vec3 offset = getLerpedPos(e, partialTicks).subtract(e.position());
        return e.getBoundingBox().move(offset);
    }

    @ApiMethod
    public static Vec3 getLerpedPos(Entity e, float partialTicks) {
        // When an entity is removed, it stops moving and its lastRenderX/Y/Z
        // values are no longer updated.
        if (e.isRemoved()) return e.position();

        double x = Mth.lerp(partialTicks, e.xOld, e.getX());
        double y = Mth.lerp(partialTicks, e.yOld, e.getY());
        double z = Mth.lerp(partialTicks, e.zOld, e.getZ());
        return new Vec3(x, y, z);
    }

    @ApiMethod
    public static Vec3 getLerpedDelta(Entity e, float partialTicks) {
        return getLerpedPos(e, partialTicks).subtract(e.position());
    }

    @ApiMethod
    public static Quaternionf getBillboardRotation(Display.BillboardConstraints renderState, float pitch, float yaw) {
        Quaternionf rotation = new Quaternionf();
        Camera camera = mc.gameRenderer.mainCamera();
        Quaternionf var10000;
        switch (renderState) {
            case FIXED -> var10000 = rotation.rotationYXZ(-0.017453292F * yaw, 0.017453292F * pitch, 0.0F);
            case HORIZONTAL ->
                var10000 =
                        rotation.rotationYXZ(-0.017453292F * yaw, 0.017453292F * getNegatedPitch(camera.xRot()), 0.0F);
            case VERTICAL ->
                var10000 = rotation.rotationYXZ(
                        -0.017453292F * getBackwardsYaw(camera.yRot()), 0.017453292F * pitch, 0.0F);
            case CENTER ->
                var10000 = rotation.rotationYXZ(
                        -0.017453292F * getBackwardsYaw(camera.yRot()),
                        0.017453292F * getNegatedPitch(camera.xRot()),
                        0.0F);
            default -> throw new MatchException((String) null, (Throwable) null);
        }

        return var10000;
    }

    @ApiMethod
    private static float getBackwardsYaw(float yaw) {
        return yaw - 180.0F;
    }

    @ApiMethod
    private static float getNegatedPitch(float pitch) {
        return -pitch;
    }

    @ApiMethod
    public static VertexConsumer getSpriteVertexConsumer(VertexConsumer vertexConsumer, TextureAtlasSprite sprite) {
        return new SpriteTexturedVertexConsumer(vertexConsumer, sprite);
    }

    public static class SpriteTexturedVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final TextureAtlasSprite sprite;

        public SpriteTexturedVertexConsumer(VertexConsumer delegate, TextureAtlasSprite sprite) {
            this.delegate = delegate;
            this.sprite = sprite;
        }

        public VertexConsumer addVertex(float x, float y, float z) {
            this.delegate.addVertex(x, y, z);
            return this;
        }

        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.delegate.setColor(red, green, blue, alpha);
            return this;
        }

        public VertexConsumer setColor(int argb) {
            this.delegate.setColor(argb);
            return this;
        }

        public VertexConsumer setUv(float u, float v) {
            this.delegate.setUv(this.sprite.getU(u), this.sprite.getV(v));
            return this;
        }

        public VertexConsumer setUv1(int u, int v) {
            this.delegate.setUv1(u, v);
            return this;
        }

        public VertexConsumer setUv2(int u, int v) {
            this.delegate.setUv2(u, v);
            return this;
        }

//#if MC >= 260300
//$$         // 26.3 的第三组 uv 是贴片/闪光用的 decal uv，不在 sprite 坐标系里，原样转发
//$$         public VertexConsumer setUv3(float u, float v) {
//$$             this.delegate.setUv3(u, v);
//$$             return this;
//$$         }
//#endif

        public VertexConsumer setNormal(float x, float y, float z) {
            this.delegate.setNormal(x, y, z);
            return this;
        }

        public VertexConsumer setLineWidth(float width) {
            this.delegate.setLineWidth(width);
            return this;
        }

        public void addVertex(
                float x,
                float y,
                float z,
                int color,
                float u,
                float v,
                int overlay,
                int light,
                float normalX,
                float normalY,
                float normalZ) {
            this.delegate.addVertex(
                    x,
                    y,
                    z,
                    color,
                    this.sprite.getU(u),
                    this.sprite.getV(v),
                    overlay,
                    light,
                    normalX,
                    normalY,
                    normalZ);
        }
    }

    public static Vector2d translate3DTo2D(
            Matrix4f cameraMatrix, Matrix4f projectionMatrix, Vec3 camera, Vec3 pos, boolean checkInScreen) {
        Vector4f vec =
                new Vector4f((float) (pos.x - camera.x), (float) (pos.y - camera.y), (float) (pos.z - camera.z), 1.0f);
        vec.mul(cameraMatrix);
        vec.mul(projectionMatrix);
        if (checkInScreen && vec.w <= 0) {
            return null; // 在屏幕后面
        }
        if (vec.w < 0) {
            vec.w = -vec.w;
        }
        // 透视除法
        float ndcX = vec.x / vec.w;
        float ndcY = vec.y / vec.w;

        double windowWidth = mc.getWindow().getScreenWidth();
        double windowHeight = mc.getWindow().getScreenHeight();
        double screenX = (ndcX * 0.5 + 0.5) * windowWidth;
        double screenY = (1.0 - (ndcY * 0.5 + 0.5)) * windowHeight; // Y翻转

        double windowScale = mc.getWindow().getGuiScale();
        double guiX = screenX / windowScale;
        double guiY = screenY / windowScale; // 由于 screenY 已经是向下，直接除以缩放即可？

        // 检查是否在屏幕外（可选）
        if (Double.isInfinite(guiX) || Double.isInfinite(guiY)) return null;

        return new Vector2d(guiX, guiY);
    }

    public static Function<Vec3, Vector2d> createProjector(Matrix4f cam, Matrix4f proj) {
        return createProjector(cam, proj, true);
    }

    public static Function<Vec3, Vector2d> createProjector(Matrix4f cam, Matrix4f proj, boolean checkInScreen) {
        Vec3 cameraPos = getCameraPos();
        return (v) -> translate3DTo2D(cam, proj, cameraPos, v, checkInScreen);
    }

    public static Vector2d translate2D(Vec3 pos, float tickProgress) {
        Quaternionf rotation = mc.gameRenderer.mainCamera().rotation().conjugate(new Quaternionf());
        Matrix4f modelView = new Matrix4f().rotation(rotation);
        // 26.2: GameRenderer 不再提供 getProjectionMatrix(float)，
        // 投影矩阵从 gameRenderState 的 cameraRenderState 取。
        Matrix4f projView =
                new Matrix4f(mc.gameRenderer.gameRenderState.levelRenderState.cameraRenderState.projectionMatrix);
        Vec3 camera = getCameraPos();
        return translate3DTo2D(modelView, projView, camera, pos, true);
    }

    public static Vector2d getScreenSize() {
        int sizeX = mc.getWindow().getGuiScaledWidth();
        int sizeY = mc.getWindow().getGuiScaledHeight();
        return new Vector2d(sizeX, sizeY);
    }
}
