package me.matl114.versioned.api;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.awt.*;
import java.util.List;
import lombok.With;
import me.matl114.utils.render.ColorQuad;
import me.matl114.utils.render.Quad;
import me.matl114.utils.render.UV;
import me.matl114.versioned.impl.Render_v1_21_11;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public interface VRender {
    public static final VRender INSTANCE = new Render_v1_21_11();

    public static VRender getInstance() {
        return INSTANCE;
    }

    // *********************************** layers *****************************
    @LimitOperation(layer = "Lines", format = "PositionColorNormalLineWidth")
    public void createLinesLayer(RenderCallback callback);

    @LimitOperation(layer = "LineStrip", format = "PositionColorNormalLineWidth")
    public void createLineStripLayer(RenderCallback callback);

    @LimitOperation(layer = "Quad", format = "PositionColor")
    public void createQuadsLayer(RenderCallback callback, boolean hasCulling);

    @LimitOperation(layer = "Rect", format = "PositionColor")
    public void createTrianglesLayer(RenderCallback callback, boolean hasCulling);

    @LimitOperation(layer = "Rect", format = "PositionColor")
    public void createTriangleStripLayer(RenderCallback callback, boolean hasCulling);

    @LimitOperation(layer = "TexturedGui", format = "PositionTextureColor")
    public void createGuiTexturedLayer(Identifier path, RenderCallback callback);

    @LimitOperation(layer = "TexturedGui", format = "PositionTextureColor")
    public void createSpriteTexturedLayer(TextureAtlasSprite sprite, RenderCallback callback);

    /** 用实体自身贴图重画它的面（实体高亮/ESP 单面绘制）。 */
    @LimitOperation(layer = "EntityHighlight", format = "PositionTextureColor")
    public void createEntityHighlightLayer(Identifier path, RenderCallback callback);

    @LimitOperation(layer = "Gui", format = "PositionColor")
    public void createGuiLayer(RenderCallback callback);

    // ********************************** defaults **************************************

    default void drawStripLineVirtualCameraCoord(PoseStack matrixStack, List<Vec3> path, Color color) {
        createLinesLayer((op, bf) -> {
            op.drawLines(matrixStack, bf, path, color.getRGB());
        });
    }

    default void drawLineVirtualCameraCoord(PoseStack matrixStack, List<Vec3> pairs, Color color) {
        createLinesLayer((op, bf) -> {
            int size = pairs.size();
            for (int i = 1; i < size; i += 2) {
                op.drawLine(matrixStack, bf, pairs.get(i - 1), pairs.get(i), color.getRGB());
            }
        });
    }

    default void drawOutlinedBoxCameraCoord(PoseStack matrix, Vec3 from, Vec3 to, Color color) {
        createLinesLayer((op, bf) -> {
            op.drawOutlinedBox(matrix, bf, from, to, color.getRGB());
        });
    }

    default void drawSolidBoxCameraCoord(PoseStack matrix, Vec3 from, Vec3 to, Color color) {
        createQuadsLayer(
                (op, bf) -> {
                    op.drawSolidBoxQuad(matrix, bf, from, to, color.getRGB());
                },
                true);
    }

    default void drawQuadCameraCoord(PoseStack matrix4f, Quad quad, ColorQuad color) {
        createQuadsLayer(
                (op, bf) -> {
                    op.drawQuad(matrix4f, bf, quad, color);
                },
                false);
    }

    // 九宫格， -1 0 1  x +
    //      -1 0 1 2
    //      0  3 4 5
    //      1  6 7 8
    //      y +
    public static int createTextPositionFlag(int xAlign, int yAlign) {
        int flag0 = xAlign < 0 ? 0 : (xAlign > 0 ? 2 : 1);
        int flag1 = yAlign < 0 ? 0 : (yAlign > 0 ? 2 : 1);
        return 3 * flag1 + flag0;
    }

    /**
     * draw a texture in 3D,
     * looks normal when in Z+
     * @param path
     * @param stack
     * @param quad
     * @param uv
     * @param color
     */
    default void drawTexturedQuadCameraCoord(Identifier path, PoseStack stack, Quad quad, UV uv, ColorQuad color) {
        createGuiTexturedLayer(path, (op, bf) -> {
            op.drawTexturedQuad(stack, bf, quad, uv, color);
        });
    }

    /**
     * draw a sprite texture in 3D
     * looks normal when in Z+
     * @param sprite
     * @param stack
     * @param quad
     * @param uv
     * @param color
     */
    default void drawSpriteQuadCameraCoord(
            TextureAtlasSprite sprite, PoseStack stack, Quad quad, UV uv, ColorQuad color) {
        createSpriteTexturedLayer(sprite, (op, bf) -> {
            op.drawTexturedQuad(stack, bf, quad, uv, color);
        });
    }

    /**
     * draw a sprite texture in 3D
     * looks normal when in Z+
     * @param path
     * @param stack
     * @param quad
     * @param uv
     * @param color
     */
    default void drawGuiSpriteQuadCameraCoord(Identifier path, PoseStack stack, Quad quad, UV uv, ColorQuad color) {
        TextureAtlas spriteAtlasTexture =
                Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.GUI);
        TextureAtlasSprite sprite = spriteAtlasTexture.getSprite(path);
        drawSpriteQuadCameraCoord(sprite, stack, quad, uv, color);
    }

    /**
     * draw a colored quad in 3D
     * using GUI Pipeline
     * @param stack
     * @param quad
     * @param color
     */
    default void drawGuiQuadCameraCoord(PoseStack stack, Quad quad, ColorQuad color) {
        createGuiLayer((operation, vertexConsumer) -> {
            operation.drawQuad(stack, vertexConsumer, quad, color);
        });
    }
    // ************************** Specials **********************************

    /**
     * pass the coordinate of the "center"
     * draw a text related to it
     * the text should looks normal when in Z+
     * use the displayPositionFlag to control the relative position
     * @param orderedText
     * @param stack
     * @param center
     * @param displayPositionFlag
     * @param color
     * @param displayInfo
     */
    public void drawTextCameraCoord(
            FormattedCharSequence orderedText,
            PoseStack stack,
            Vec3 center,
            int displayPositionFlag,
            Color color,
            TextDisplay displayInfo);

    public void drawItemCameraCoord(
            ItemStack itemStack, PoseStack stack, Vec3 vec3d, ItemDisplayContext context, ItemDisplay displayInfo);

    @With
    public record TextDisplay(boolean shadow, Font.DisplayMode layerType, int backgroundColor, int light) {}

    public static TextDisplay DEFAULT_TEXT = new TextDisplay(false, Font.DisplayMode.SEE_THROUGH, 0, 0);

    public record ItemDisplay(int light, int overlay, int outlineColor) {}

    public static ItemDisplay DEFAULT_ITEM = new ItemDisplay(0XFF00FF, OverlayTexture.NO_OVERLAY, 0);

    public static interface RenderCallback {
        public void draw(WrapRenderOperation operation, VertexConsumer vertexConsumer);
    }

    public static interface WrapRenderOperation {
        @LimitOperation(format = "PositionColorNormalLineWidth", layer = "Lines")
        public void drawOutlinedBox(
                PoseStack matrix4f, VertexConsumer bufferBuilder, Vec3 from, Vec3 to, int cachedRenderColor);

        //        @LimitOperation(format = "PositionColorNormalLineWidth", layer = "LineStrip")
        //        public void drawOutlinedBoxStrip(
        //            MatrixStack matrix4f, VertexConsumer bufferBuilder, Vec3d from, Vec3d to, int cachedRenderColor);
        @LimitOperation(format = "PositionColor", layer = "Quad")
        public void drawSolidBoxQuad(
                PoseStack matrixStack, VertexConsumer bufferBuilder, Vec3 from, Vec3 to, int cachedRenderColor);

        //        @LimitOperation(format = "PositionColor", layer = "Rect")
        //        public void drawSolidBoxTriangle(
        //                MatrixStack matrixStack, VertexConsumer bufferBuilder, Vec3d from, Vec3d to, int
        // cachedRenderColor);

        @LimitOperation(format = "PositionColor")
        public void drawQuad(PoseStack matrixStack, VertexConsumer bufferBuilder, Quad uv, ColorQuad color);

        @LimitOperation(format = "PositionColorNormalLineWidth")
        public void drawLines(PoseStack matrixStack, VertexConsumer consumer, List<Vec3> points, int color);

        @LimitOperation(format = "PositionColorNormalLineWidth")
        public void drawLine(PoseStack matrixStack, VertexConsumer consumer, Vec3 prevV, Vec3 nextV, int color);

        @LimitOperation(format = "PositionTextureColor", layer = "TexturedGui")
        public void drawTexturedQuad(PoseStack stack, VertexConsumer vertex, Quad quad, UV uv, ColorQuad colorQuad);
    }

    public @interface LimitOperation {
        String format() default "";

        String layer() default "";
    }
}
