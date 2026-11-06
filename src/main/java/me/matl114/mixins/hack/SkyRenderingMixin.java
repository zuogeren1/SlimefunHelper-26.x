package me.matl114.mixins.hack;

//#if MC >= 260300
//$$ import com.mojang.renderpearl.api.buffers.GpuBuffer;
//#else
import com.mojang.blaze3d.buffers.GpuBuffer;
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
//#if MC >= 260300
//$$ import com.mojang.renderpearl.api.textures.GpuTexture;
//#else
import com.mojang.blaze3d.textures.GpuTexture;
//#endif
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
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;
//#if MC >= 260300
//$$ import org.joml.Vector3fc;
//$$ import org.joml.Vector4f;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if MC >= 260300
//$$ import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
//$$ import com.mojang.renderpearl.api.commands.RenderPass;
//$$ import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
//$$ import net.minecraft.client.renderer.DynamicGpuData;
//#else
//#if MC >= 260200
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.DynamicUniforms;
//#else
//$$ import com.mojang.blaze3d.textures.GpuSampler;
//$$ import com.mojang.blaze3d.textures.GpuTextureView;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import org.joml.Vector4fc;
//#endif
//#endif

// 自定义天空贴图分两半接入：一半把贴图与顶点缓冲接到天空 pass 上（版本差异全集中在这里），
// 一半负责从配置装载纹理、构建顶点缓冲（三版共用同一份）。
// 三版都是 SkyRenderer + state.level.SkyRenderState；差别只在渲染后端的接法：
// 26.3 只在 render(GpuBufferSlice, SkyRenderState) 里建一张 sky pass，renderEndSky 等子方法转成 private 并接收它；
// 26.2 用 PrimitiveTopology 建顶点、GpuBufferSlice 绑定顶点缓冲，贴图靠改 endSkyTexture 的字段读；
// 26.1.2 用 VertexFormat.Mode 建顶点、GpuBuffer 直接绑定，贴图视图/采样器/顶点各走一处改参。
@Environment(EnvType.CLIENT)
@Mixin(SkyRenderer.class)
public abstract class SkyRenderingMixin {
    //#if MC >= 260300
//$$     @Shadow
//$$     private void renderEndSky(RenderPass renderPass) {
//$$         throw new AssertionError();
//$$     }
    //#else
    @Shadow
    public abstract void renderEndSky();
    //#endif

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

    // ==================== 绘制：把自定义贴图/顶点接进天空 pass ====================
    //#if MC >= 260300
//$$     @ModifyExpressionValue(
//$$             method = "renderEndSky(Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
//$$             at =
//$$                     @At(
//$$                             value = "FIELD",
//$$                             target =
//$$                                     "Lnet/minecraft/client/renderer/SkyRenderer;endSkyTexture:Lnet/minecraft/client/renderer/texture/AbstractTexture;"))
//$$     private AbstractTexture skyRender$backgroundTexture(AbstractTexture original) {
//$$         return skyRender$loadedBackgroundTexture != null ? skyRender$loadedBackgroundTexture : original;
//$$     }
//$$
//$$     @ModifyArg(
//$$             method = "renderEndSky(Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
//$$             at =
//$$                     @At(
//$$                             value = "INVOKE",
//$$                             target =
//$$                                     "Lcom/mojang/renderpearl/api/commands/RenderPass;setVertexBuffer(ILcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V"),
//$$             index = 1)
//$$     private GpuBufferSlice skyRender$selectBackgroundVertices(GpuBufferSlice original) {
//$$         return skyRender$loadedBackgroundTexture != null && skyRender$backgroundVertices != null
//$$                 ? skyRender$backgroundVertices.slice()
//$$                 : original;
//$$     }
//$$
//$$     // 26.3 的 renderEndSky 只调 1 参 writeTransform（ColorModulator 恒为白），自定义颜色没地方传；
//$$     // 改调 2 参重载把颜色写进 uniform，语义与上游的「贴图 × 顶点色 × ColorModulator」一致。
//$$     @WrapOperation(
//$$             method = "renderEndSky(Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
//$$             at =
//$$                     @At(
//$$                             value = "INVOKE",
//$$                             target =
//$$                                     "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
//$$     private static GpuBufferSlice skyRender$tintEndSky(
//$$             DynamicGpuData instance, Matrix4f modelView, Operation<GpuBufferSlice> original) {
//$$         var module = SkyRender.INSTANCE;
//$$         if (module != null && module.hasCustomColor()) {
//$$             return instance.writeTransform(modelView, ARGB.vector4fFromARGB32(module.color.get().withAlpha(255)));
//$$         }
//$$         return original.call(instance, modelView);
//$$     }
    //#else
    //#if MC >= 260200
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
    // 改调 2 参重载把颜色写进 uniform，语义与上游的「贴图 × 顶点色 × ColorModulator」一致。
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
    //#else
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
//$$
//$$     // 26.1.2 把贴图视图、采样器与顶点缓冲分三处交给 RenderPass，三处都要换成自定义贴图。
//$$     // 顶点缓冲非空即代表贴图已可读（只在能读到 GpuTexture 时才建），拿它当就绪信号，
//$$     // 免得在纹理视图还没生成时取 getTextureView() 抛异常。
//$$     @ModifyArg(
//$$             method = "renderEndSky",
//$$             at =
//$$                     @At(
//$$                             value = "INVOKE",
//$$                             target =
//$$                                     "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V"),
//$$             index = 1)
//$$     private GpuTextureView skyRender$selectBackgroundTextureView(GpuTextureView original) {
//$$         return skyRender$loadedBackgroundTexture != null && skyRender$backgroundVertices != null
//$$                 ? skyRender$loadedBackgroundTexture.getTextureView()
//$$                 : original;
//$$     }
//$$
//$$     @ModifyArg(
//$$             method = "renderEndSky",
//$$             at =
//$$                     @At(
//$$                             value = "INVOKE",
//$$                             target =
//$$                                     "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V"),
//$$             index = 2)
//$$     private GpuSampler skyRender$selectBackgroundSampler(GpuSampler original) {
//$$         return skyRender$loadedBackgroundTexture != null && skyRender$backgroundVertices != null
//$$                 ? skyRender$loadedBackgroundTexture.getSampler()
//$$                 : original;
//$$     }
//$$
//$$     @ModifyArg(
//$$             method = "renderEndSky",
//$$             at =
//$$                     @At(
//$$                             value = "INVOKE",
//$$                             target =
//$$                                     "Lcom/mojang/blaze3d/systems/RenderPass;setVertexBuffer(ILcom/mojang/blaze3d/buffers/GpuBuffer;)V"),
//$$             index = 1)
//$$     private GpuBuffer skyRender$selectBackgroundVertices(GpuBuffer original) {
//$$         return skyRender$loadedBackgroundTexture != null && skyRender$backgroundVertices != null
//$$                 ? skyRender$backgroundVertices
//$$                 : original;
//$$     }
    //#endif
    //#endif

    // renderSkyDisc 只画主世界天穹，自定义贴图要在它之后整块重画；renderEndSky 是唯一能复用
    // END_SKY 管线的出口，上面的钩子在那条路径上把贴图与顶点换掉。
    // 顶点缓冲就绪才重画：没有它就只能拿 vanilla 的 16 格 UV 配自定义贴图，盖出来是一片黑。
    //#if MC >= 260300
//$$     @Inject(
//$$             method = "renderSkyDisc(Lcom/mojang/renderpearl/api/commands/RenderPass;Lorg/joml/Vector3fc;)V",
//$$             at = @At("TAIL"))
//$$     private void renderCustomBackground(RenderPass renderPass, Vector3fc skyColor, CallbackInfo ci) {
//$$         if (skyRender$loadedBackgroundTexture != null && skyRender$backgroundVertices != null) {
//$$             renderEndSky(renderPass);
//$$         }
//$$     }
    //#else
    @Inject(method = "renderSkyDisc", at = @At("TAIL"))
    private void renderCustomBackground(int color, CallbackInfo ci) {
        if (skyRender$loadedBackgroundTexture != null && skyRender$backgroundVertices != null) {
            renderEndSky();
        }
    }
    //#endif

    // ==================== 装载：配置 → 纹理 + 顶点缓冲 ====================
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
            //#if MC >= 260300
//$$             state.skyColor = ARGB.vector3fFromRGB24(module.color.get().withAlpha(255));
            //#else
            state.skyColor = module.color.get().withAlpha(255);
            //#endif
        }
        if (module.hasCustomColor() || skyRender$loadedBackgroundTexture != null) {
            //#if MC >= 260300
//$$             state.sunriseAndSunsetColor = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
            //#else
            state.sunriseAndSunsetColor = 0;
            //#endif
            state.shouldRenderDarkDisc = false;
        }
    }

    @Unique
    private GpuBuffer skyRender$createBackgroundVertices(int textureWidth, int textureHeight) {
        boolean sixTileAtlas = textureWidth % 3 == 0 && textureHeight % 2 == 0 && textureWidth / 3 == textureHeight / 2;
        // 顶点色恢复 -1（与上游一致），末地染色统一交给 ColorModulator（见 skyRender$tintEndSky）
        try (ByteBufferBuilder allocator = ByteBufferBuilder.exactlySized(
                skyRender$FACE_COUNT * 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(
                    allocator,
                    //#if MC >= 260200
                    PrimitiveTopology.QUADS,
                    //#else
//$$                     VertexFormat.Mode.QUADS,
                    //#endif
                    DefaultVertexFormat.POSITION_TEX_COLOR);
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
