package me.matl114.mixins.hack;

import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;

//#if MC >= 260200
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import me.matl114.hacks.modules.render.SkyRender;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2 版：天空渲染已迁到 SkyRenderer（原 SkyRendering），状态对象为 state.level.SkyRenderState，
// 顶点构建改用 ByteBufferBuilder/BufferBuilder + PrimitiveTopology，末地天空用 GpuBufferSlice 绑定。
@Environment(EnvType.CLIENT)
@Mixin(SkyRenderer.class)
public abstract class SkyRenderingMixin {
    @Shadow
    public abstract void renderEndSky();

    @Unique
    private static final int skyRender$FACE_COUNT = 6;

    @Unique
    private GpuBuffer skyRender$backgroundVertices;

    @Unique
    private AbstractTexture skyRender$loadedBackgroundTexture;

    @Unique
    private int skyRender$backgroundWidth;

    @Unique
    private int skyRender$backgroundHeight;

    @ModifyExpressionValue(
            method = "renderEndSky",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/client/renderer/SkyRenderer;endSkyTexture:Lnet/minecraft/client/renderer/texture/AbstractTexture;"))
    private AbstractTexture skyRender$backgroundTexture(AbstractTexture original) {
        return skyRender$loadedBackgroundTexture != null ? skyRender$loadedBackgroundTexture : original;
    }

    @ModifyArg(
            method = "renderEndSky",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/blaze3d/systems/RenderPass;setVertexBuffer(ILcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"),
            index = 1)
    private GpuBufferSlice skyRender$selectBackgroundVertices(GpuBufferSlice original) {
        return skyRender$loadedBackgroundTexture != null && skyRender$backgroundVertices != null
                ? skyRender$backgroundVertices.slice()
                : original;
    }

    // 26.2 的 renderEndSky 只调 1 参 writeTransform（ColorModulator 恒为白），自定义颜色没地方传；
    // 改调 2 参重载把颜色写进 uniform，语义与 26.1.2 / 上游的「贴图 × 顶点色 × ColorModulator」一致。
    @WrapOperation(
            method = "renderEndSky",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private static GpuBufferSlice skyRender$tintEndSky(
            DynamicUniforms instance, Matrix4f modelView, Operation<GpuBufferSlice> original) {
        var module = SkyRender.INSTANCE;
        if (module != null && module.hasCustomColor()) {
            return instance.writeTransform(modelView, ARGB.vector4fFromARGB32(module.color.get().withAlpha(255)));
        }
        return original.call(instance, modelView);
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void updateCustomSky(
            ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, CallbackInfo ci) {
        var module = SkyRender.INSTANCE;
        var background = module != null ? module.getBackground() : null;
        skyRender$loadedBackgroundTexture = background != null
                ? Minecraft.getInstance().getTextureManager().getTexture(background)
                : null;
        if (skyRender$loadedBackgroundTexture != null) {
            GpuTexture texture = null;
            try {
                texture = skyRender$loadedBackgroundTexture.getTexture();
            } catch (Throwable ignored) {
                // 纹理尚未上传，等下一帧再建顶点
            }
            if (texture != null) {
                int width = texture.getWidth(0);
                int height = texture.getHeight(0);
                if (width > 0
                        && height > 0
                        && (skyRender$backgroundVertices == null
                                || width != skyRender$backgroundWidth
                                || height != skyRender$backgroundHeight)) {
                    if (skyRender$backgroundVertices != null) {
                        skyRender$backgroundVertices.close();
                    }
                    skyRender$backgroundVertices = skyRender$createBackgroundVertices(width, height);
                    skyRender$backgroundWidth = width;
                    skyRender$backgroundHeight = height;
                }
            }
        }
        if (module == null) return;
        if (module.hasCustomColor()) {
            state.skyColor = module.color.get().withAlpha(255);
        }
        if (module.hasCustomColor() || skyRender$loadedBackgroundTexture != null) {
            state.sunriseAndSunsetColor = 0;
            state.shouldRenderDarkDisc = false;
        }
    }

    @Inject(method = "renderSkyDisc", at = @At("TAIL"))
    private void renderCustomBackground(int color, CallbackInfo ci) {
        if (skyRender$loadedBackgroundTexture != null) {
            renderEndSky();
        }
    }

    @Unique
    private GpuBuffer skyRender$createBackgroundVertices(int textureWidth, int textureHeight) {
        boolean sixTileAtlas = textureWidth % 3 == 0 && textureHeight % 2 == 0 && textureWidth / 3 == textureHeight / 2;
        // 顶点色恢复 -1（与上游一致），末地染色统一交给 ColorModulator（见 skyRender$tintEndSky）
        try (ByteBufferBuilder allocator = ByteBufferBuilder.exactlySized(
                skyRender$FACE_COUNT * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder builder =
                    new BufferBuilder(allocator, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (int face = 0; face < skyRender$FACE_COUNT; face++) {
                int tile =
                        switch (face) {
                            case 0 -> 4; // North
                            case 1 -> 5; // West
                            case 2 -> 2; // South
                            case 3 -> 3; // East
                            case 4 -> 1; // Top
                            case 5 -> 0; // Bottom
                            default -> throw new IllegalStateException("Unexpected sky face: " + face);
                        };
                float minU = sixTileAtlas ? (tile % 3) / 3.0f : 0.0f;
                float maxU = sixTileAtlas ? minU + 1.0f / 3.0f : 1.0f;
                float minV = sixTileAtlas ? (tile / 3) / 2.0f : 0.0f;
                float maxV = sixTileAtlas ? minV + 0.5f : 1.0f;
                Matrix4f matrix = new Matrix4f();
                switch (face) {
                    case 1 -> matrix.rotationY((float) (Math.PI / 2));
                    case 2 -> matrix.rotationY((float) Math.PI);
                    case 3 -> matrix.rotationY((float) (-Math.PI / 2));
                    case 4 -> matrix.rotationX((float) (Math.PI / 2));
                    case 5 -> matrix.rotationX((float) (-Math.PI / 2));
                }
                builder.addVertex(matrix, -100, 100, -100).setUv(minU, minV).setColor(-1);
                builder.addVertex(matrix, -100, -100, -100).setUv(minU, maxV).setColor(-1);
                builder.addVertex(matrix, 100, -100, -100).setUv(maxU, maxV).setColor(-1);
                builder.addVertex(matrix, 100, 100, -100).setUv(maxU, minV).setColor(-1);
            }
            try (MeshData built = builder.buildOrThrow()) {
                return RenderSystem.getDevice().createBuffer(() -> "SkyRender background", 32, built.vertexBuffer());
            }
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void closeCustomBackground(CallbackInfo ci) {
        skyRender$loadedBackgroundTexture = null;
        if (skyRender$backgroundVertices != null) {
            skyRender$backgroundVertices.close();
            skyRender$backgroundVertices = null;
        }
        skyRender$backgroundWidth = 0;
        skyRender$backgroundHeight = 0;
    }
}
//#else
//$$ import me.matl114.hacks.modules.render.SkyRender;
//$$ import net.minecraft.util.ARGB;
//$$ import org.joml.Vector4fc;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.ModifyArg;
//$$
//$$ @Mixin(SkyRenderer.class)
//$$ public abstract class SkyRenderingMixin {
//$$     // 26.1.2 的 renderEndSky 直接调 4 参 writeTransform，第 2 参就是 ColorModulator（与上游同款改法）
//$$     @ModifyArg(
//$$             method = "renderEndSky",
//$$             at =
//$$                     @At(
//$$                             value = "INVOKE",
//$$                             target =
//$$                                     "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4fc;Lorg/joml/Vector4fc;Lorg/joml/Vector3fc;Lorg/joml/Matrix4fc;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
//$$             index = 1)
//$$     private Vector4fc skyRender$tintEndSky(Vector4fc original) {
//$$         var module = SkyRender.INSTANCE;
//$$         return module != null && module.hasCustomColor()
//$$                 ? ARGB.vector4fFromARGB32(module.color.get().withAlpha(255))
//$$                 : original;
//$$     }
//$$ }
//#endif
