package me.matl114.versioned.impl;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.*;
import me.matl114.renderer.TextRenderers;
import me.matl114.renderer.text.ResourceFontTextDrawer;
import me.matl114.renderer.text.TextDrawer;
import me.matl114.renderer.text.VanillaTextDrawer;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.accessors.GuiRendererStateAccess;
import me.matl114.versioned.api.MatrixStack;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class DrawContext_v1_21_11 implements VDrawContext {
    private final GuiGraphicsExtractor drawContext;
    private final MatrixStack matrixStack;

    public DrawContext_v1_21_11(GuiGraphicsExtractor context) {
        this.drawContext = context;
        this.matrixStack = MatrixStack.of(context);
    }

    @Override
    public GuiGraphicsExtractor pushMatrix() {
        getMatrices().pushMatrix();
        return this.drawContext;
    }

    @Override
    public GuiGraphicsExtractor popMatrix() {
        getMatrices().popMatrix();
        return this.drawContext;
    }

    @Override
    public MatrixStack getMatrices() {
        return this.matrixStack;
    }

    public void setShaderColor(int rgba) {
        cachedShaderColor[0] = ARGB.red(rgba);
        cachedShaderColor[1] = ARGB.green(rgba);
        cachedShaderColor[2] = ARGB.blue(rgba);
        cachedShaderColor[3] = ARGB.alpha(rgba);
    }

    @Override
    public void setShaderColor(float red, float green, float blue, float alpha) {
        cachedShaderColor[0] = ARGB.as8BitChannel(red);
        cachedShaderColor[1] = ARGB.as8BitChannel(green);
        cachedShaderColor[2] = ARGB.as8BitChannel(blue);
        cachedShaderColor[3] = ARGB.as8BitChannel(alpha);
    }

    public void setShaderAlpha(float alpha) {
        cachedShaderColor[3] = ARGB.as8BitChannel(alpha);
    }

    // r g  b a
    private static final int[] cachedShaderColor = new int[4];

    static {
        Arrays.fill(cachedShaderColor, 255);
    }

    public static int getShaderRGB() {
        return (cachedShaderColor[3] << 24)
                | (cachedShaderColor[0] << 16)
                | (cachedShaderColor[1] << 8)
                | cachedShaderColor[2];
    }

    public static int getShaderRGB(int a) {
        return ARGB.multiply(getShaderRGB(), a);
    }

    public static int getCurrentDepthLevel() {
        return depthDeque.isEmpty() ? 0 : depthDeque.peekLast().index();
    }

    private static final ArrayDeque<IndexEntry<LayerSnapshot>> depthDeque = new ArrayDeque<>(4);

    private record LayerSnapshot(
            GuiRenderState.Node layer, @Nullable ScreenRectangle bounds) {}

    public void pushLayer(int depth) {
        int level = getCurrentDepthLevel();
        LayerSnapshot snapshot =
                new LayerSnapshot(drawContext.guiRenderState.current, drawContext.guiRenderState.lastElementBounds);
        depthDeque.addLast(new IndexEntry<>(depth + level, snapshot));
        GuiRendererStateAccess.of(drawContext.guiRenderState).setLayerToDepth();
        drawContext.guiRenderState.lastElementBounds = null;
    }

    public void popLayer() {
        var idx = depthDeque.removeLast();
        drawContext.guiRenderState.current = idx.val().layer();
        drawContext.guiRenderState.lastElementBounds = idx.val().bounds();
    }

    @Override
    public void drawGuiTexture(Identifier texture, int x, int y, int z, int width, int height) {
        if (z != 0) {
            pushLayer(z);
        }
        try {
            this.drawContext.blitSprite(RenderPipelines.GUI_TEXTURED, texture, x, y, width, height, getShaderRGB());
        } finally {
            if (z != 0) {
                popLayer();
            }
        }
    }

    @Override
    public void drawGuiTexture(
            Identifier texture, int i, int j, int k, int l, int x, int y, int z, int width, int height) {
        if (z != 0) {
            pushLayer(z);
        }
        try {
            this.drawContext.blitSprite(
                    RenderPipelines.GUI_TEXTURED, texture, i, j, k, l, x, y, width, height, getShaderRGB());
        } finally {
            if (z != 0) {
                popLayer();
            }
        }
    }

    public void drawGuiTextureQuad(
            Identifier texture, int x1, int x2, int y1, int y2, int z, float u1, float u2, float v1, float v2) {
        TextureAtlasSprite sprite = getGuiSprite(texture);
        float sMinU = sprite.getU0();
        float sMaxU = sprite.getU1();
        float sMinV = sprite.getV0();
        float sMaxV = sprite.getV1();
        // 映射：u 从 [0,1] 映射到 [sMinU, sMaxU]，v 同理
        float finalU1 = sMinU + u1 * (sMaxU - sMinU);
        float finalU2 = sMinU + u2 * (sMaxU - sMinU);
        float finalV1 = sMinV + v1 * (sMaxV - sMinV);
        float finalV2 = sMinV + v2 * (sMaxV - sMinV);
        this.drawTexturedQuad(sprite.atlasLocation(), x1, x2, y1, y2, z, finalU1, finalU2, finalV1, finalV2);
    }

    @Override
    public TextureAtlasSprite getGuiSprite(Identifier id) {
        return this.drawContext.guiSprites.getSprite(id);
    }

    @Override
    public void drawTexturedQuad(
            Identifier texture, int x1, int x2, int y1, int y2, int z, float u1, float u2, float v1, float v2) {
        if (z != 0) {
            pushLayer(z);
        }
        try {
            this.drawContext.innerBlit(
                    RenderPipelines.GUI_TEXTURED, texture, x1, x2, y1, y2, u1, u2, v1, v2, getShaderRGB());
        } finally {
            if (z != 0) {
                popLayer();
            }
        }
    }

    @Override
    public void drawText(TextDrawer textDrawer, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        TextDrawer current = TextRenderers.resolve(textDrawer);
        if (current.getClass() == VanillaTextDrawer.class) {
            drawContext.text(((VanillaTextDrawer) current).unwrap(), text, x, y, getShaderRGB(color), shadow);
        } else if (current instanceof ResourceFontTextDrawer resourceFontTextDrawer
                && resourceFontTextDrawer.isAvailable()) {
            addTextState(
                    resourceFontTextDrawer.unwrap(),
                    resourceFontTextDrawer.withFont(text),
                    x,
                    y,
                    getShaderRGB(color),
                    shadow,
                    resourceFontTextDrawer.getTextYOffset());
        } else {
            drawContext.text(fontOf(current), text, x, y, getShaderRGB(color), shadow);
        }
    }

    @Override
    public void drawText(TextDrawer textDrawer, @Nullable String text, int x, int y, int color, boolean shadow) {
        if (text == null) {
            return;
        }
        TextDrawer current = TextRenderers.resolve(textDrawer);
        if (current.getClass() == VanillaTextDrawer.class) {
            drawContext.text(((VanillaTextDrawer) current).unwrap(), text, x, y, getShaderRGB(color), shadow);
        } else if (current instanceof ResourceFontTextDrawer resourceFontTextDrawer
                && resourceFontTextDrawer.isAvailable()) {
            addTextState(
                    resourceFontTextDrawer.unwrap(),
                    resourceFontTextDrawer.formattedString(text, true),
                    x,
                    y,
                    getShaderRGB(color),
                    shadow,
                    resourceFontTextDrawer.getTextYOffset());
        } else {
            drawContext.text(fontOf(current), text, x, y, getShaderRGB(color), shadow);
        }
    }

    private static Font fontOf(TextDrawer textDrawer) {
        Font font = textDrawer.unwrap();
        return font != null ? font : Minecraft.getInstance().font;
    }

    /**
     * 自定义字体没法走原版 {@code text} 提交，只能自己塞一条文字渲染状态；
     * {@code yOffset} 用来把非 9px 字体的行高校正回原版基线。
     */
    private void addTextState(
            Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, float yOffset) {
        if (ARGB.alpha(color) == 0) {
            return;
        }
        drawContext.guiRenderState.addText(new GuiTextRenderState(
                font,
                text,
                new Matrix3x2f(this.drawContext.pose()).translate(0.0F, yOffset),
                x,
                y,
                color,
                0,
                shadow,
                false,
                this.drawContext.scissorStack.peek()));
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        this.drawContext.enableScissor(x, y, width, height);
    }

    @Override
    public void disableScissor() {
        this.drawContext.disableScissor();
    }

    @Override
    public void tryDraw() {}

    @Override
    public void fillGuiGradient(int x1, int y1, int x2, int y2, int color1, int color2, int depth) {
        if (depth != 0) {
            pushLayer(depth);
        }
        try {
            this.drawContext.fillGradient(x1, y1, x2, y2, getShaderRGB(color1), getShaderRGB(color2));
        } finally {
            if (depth != 0) {
                popLayer();
            }
        }
    }

    @Override
    public void fillGuiGradient(
            int x1, int y1, int x2, int y2, int color1, int color2, int color3, int color4, int depth) {
        if (depth != 0) {
            pushLayer(depth);
        }
        try {
            this.drawContext.guiRenderState.addGuiElement(new ColoredQuad2DGuiElementRenderState(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    new Matrix3x2f(this.drawContext.pose()),
                    x1,
                    y1,
                    x2,
                    y2,
                    color1,
                    color2,
                    color3,
                    color4,
                    this.drawContext.scissorStack.peek()));
        } finally {
            if (depth != 0) {
                popLayer();
            }
        }
    }

    public static record ColoredQuad2DGuiElementRenderState(
            RenderPipeline pipeline,
            TextureSetup textureSetup,
            Matrix3x2fc pose,
            int x0,
            int y0,
            int x1,
            int y1,
            int col1,
            int col2,
            int col3,
            int col4,
            @Nullable ScreenRectangle scissorArea,
            @Nullable ScreenRectangle bounds)
            implements GuiElementRenderState {
        public ColoredQuad2DGuiElementRenderState(
                RenderPipeline pipeline,
                TextureSetup textureSetup,
                Matrix3x2fc pose,
                int x0,
                int y0,
                int x1,
                int y1,
                int col1,
                int col2,
                int col3,
                int col4,
                @Nullable ScreenRectangle scissorArea) {
            this(
                    pipeline,
                    textureSetup,
                    pose,
                    x0,
                    y0,
                    x1,
                    y1,
                    col1,
                    col2,
                    col3,
                    col4,
                    scissorArea,
                    createBounds(x0, y0, x1, y1, pose, scissorArea));
        }

        @Override
        public void buildVertices(VertexConsumer vertices) {
            vertices.addVertexWith2DPose(this.pose(), (float) this.x0(), (float) this.y0())
                    .setColor(this.col1());
            vertices.addVertexWith2DPose(this.pose(), (float) this.x0(), (float) this.y1())
                    .setColor(this.col2());
            vertices.addVertexWith2DPose(this.pose(), (float) this.x1(), (float) this.y1())
                    .setColor(this.col3());
            vertices.addVertexWith2DPose(this.pose(), (float) this.x1(), (float) this.y0())
                    .setColor(this.col4());
        }
    }

    public static record ColoredLine2DGuiElementRenderState(
            RenderPipeline pipeline,
            TextureSetup textureSetup,
            Matrix3x2fc pose,
            int x1,
            int x2,
            int y1,
            int y2,
            int color1,
            int color2,
            @Nullable ScreenRectangle scissorArea,
            @Nullable ScreenRectangle bounds)
            implements GuiElementRenderState {

        public ColoredLine2DGuiElementRenderState(
                RenderPipeline pipeline,
                TextureSetup textureSetup,
                Matrix3x2fc pose,
                int x1,
                int x2,
                int y1,
                int y2,
                int color1,
                int color2,
                @Nullable ScreenRectangle scissorArea) {
            this(
                    pipeline,
                    textureSetup,
                    pose,
                    x1,
                    x2,
                    y1,
                    y2,
                    color1,
                    color2,
                    scissorArea,
                    createBounds(x1, y1, x2, y2, pose, scissorArea));
        }

        @Override
        public void buildVertices(VertexConsumer vertices) {
            Vector3f normal = new Vector3f(x2() - x1(), y2() - y1(), 0).normalize();
            vertices.addVertexWith2DPose(this.pose(), (float) this.x1(), (float) this.y1())
                    .setColor(this.color1())
                    .setNormal(normal.x, normal.y, normal.z)
                    .setLineWidth(2);
            vertices.addVertexWith2DPose(this.pose(), (float) this.x2(), (float) this.y2())
                    .setColor(this.color2())
                    .setNormal(normal.x, normal.y, normal.z)
                    .setLineWidth(2);
        }
    }

    private static @Nullable ScreenRectangle createBounds(
            int x0, int y0, int x1, int y1, Matrix3x2fc pose, @Nullable ScreenRectangle scissorArea) {
        ScreenRectangle screenRect = (new ScreenRectangle(x0, y0, x1 - x0, y1 - y0)).transformMaxBounds(pose);
        return scissorArea != null ? scissorArea.intersection(screenRect) : screenRect;
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int z, int color) {
        if (z != 0) {
            pushLayer(z);
        }
        try {
            this.drawContext.fill(x1, y1, x2, y2, getShaderRGB(color));
        } finally {
            if (z != 0) {
                popLayer();
            }
        }
    }

    public void lineGuiGradient(int x1, int y1, int x2, int y2, int color1, int color2, int z) {
        if (z != 0) {
            pushLayer(z);
        }
        try {
            this.drawContext.guiRenderState.addGuiElement(new ColoredLine2DGuiElementRenderState(
                    Render_v1_21_11.DEBUG_LINES,
                    TextureSetup.noTexture(),
                    new Matrix3x2f(this.drawContext.pose()),
                    x1,
                    x2,
                    y1,
                    y2,
                    color1,
                    color2,
                    this.drawContext.scissorStack.peek()));
        } finally {
            if (z != 0) {
                popLayer();
            }
        }
    }

    private void addInternal(Runnable runnable) {
        if (this.drawContext.deferredTooltip != null) {
            final Runnable prev = this.drawContext.deferredTooltip;
            this.drawContext.deferredTooltip = () -> {
                prev.run();
                runnable.run();
            };
        } else {
            this.drawContext.deferredTooltip = runnable;
        }
    }

    @Override
    public void drawTooltip(List<Component> text, Optional<TooltipComponent> data, int x, int y) {
        var trans = matrixStack.peek3D();
        var point1 = new Vector4f(x, y, 0, 1).mul(trans);
        // Tooltips are draw in delay callback, so transfer before the call
        List<ClientTooltipComponent> list = (List) text.stream()
                .map(Component::getVisualOrderText)
                .map(ClientTooltipComponent::create)
                .collect(Util.toMutableList());
        data.ifPresent((datax) -> {
            list.add(list.isEmpty() ? 0 : 1, ClientTooltipComponent.create(datax));
        });
        if (!list.isEmpty()) {
            addInternal(() -> {
                drawContext.tooltip(
                        fontOf(TextRenderers.get()),
                        list,
                        (int) point1.x,
                        (int) point1.y,
                        DefaultTooltipPositioner.INSTANCE,
                        null);
            });
        }
    }

    @Override
    public void drawItem(ItemStack stack, int x, int y, int seed, int z) {
        if (z != 0) {
            pushLayer(z);
        }
        try {
            this.drawContext.item(stack, x, y, seed);
        } finally {
            if (z != 0) {
                popLayer();
            }
        }
    }

    @Override
    public void drawItemInSlot(TextDrawer textDrawer, ItemStack stack, int x, int y, @Nullable String countOverride) {
        this.drawContext.itemDecorations(fontOf(textDrawer), stack, x, y, countOverride);
    }
}
