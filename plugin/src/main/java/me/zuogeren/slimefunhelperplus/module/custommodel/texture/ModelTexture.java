package me.zuogeren.slimefunhelperplus.module.custommodel.texture;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * 把 YSM 模型里内嵌的贴图字节注册成游戏纹理。
 *
 * <p>只处理 {@link NativeImage} 能直接读的格式（PNG/JPEG/BMP）；WebP/AVIF 先返回 null，
 * 由调用方决定回退（P3 不引入额外解码器）。
 */
public final class ModelTexture {

    private static final Map<String, Identifier> CACHE = new ConcurrentHashMap<>();

    private ModelTexture() {}

    public static Identifier register(RawYsmModel.RawTexture texture) {
        if (texture == null || texture.data == null || texture.data.length == 0) {
            return null;
        }
        String key = texture.hash;
        if (key == null || key.isBlank()) {
            key = Integer.toHexString(Arrays.hashCode(texture.data));
        }
        final String cacheKey = key;
        return CACHE.computeIfAbsent(cacheKey, ignored -> load(texture, cacheKey));
    }

    private static Identifier load(RawYsmModel.RawTexture texture, String key) {
        try {
            NativeImage image = decode(texture);
            if (image == null) {
                return null;
            }
            DynamicTexture dynamic = new DynamicTexture(() -> "sfh-model-" + key, image);
            Identifier id = Identifier.fromNamespaceAndPath("slimefunhelperplus", "custommodel/" + key);
            Minecraft.getInstance().getTextureManager().register(id, dynamic);
            return id;
        } catch (Throwable throwable) {
            Debug.info("[SFH+] 贴图解码失败 format=" + texture.imageFormat + " size="
                    + texture.width + "x" + texture.height + ": " + throwable);
            return null;
        }
    }

    /**
     * YSM 二进制里的贴图有两种存法：{@code imageFormat == -1} 是裸 RGBA 像素，
     * 其余是 PNG/JPEG/BMP 编码字节；WebP/AVIF 暂不支持。
     */
    private static NativeImage decode(RawYsmModel.RawTexture texture) {
        if (texture.imageFormat == -1) {
            return rawRgba(texture);
        }
        try {
            return NativeImage.read(texture.data);
        } catch (Throwable encodedFailure) {
            NativeImage raw = rawRgba(texture);
            if (raw != null) {
                return raw;
            }
            throw encodedFailure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(encodedFailure);
        }
    }

    private static NativeImage rawRgba(RawYsmModel.RawTexture texture) {
        int width = texture.width;
        int height = texture.height;
        if (width <= 0 || height <= 0 || texture.data.length < (long) width * height * 4L) {
            return null;
        }
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int r = texture.data[offset] & 0xFF;
                int g = texture.data[offset + 1] & 0xFF;
                int b = texture.data[offset + 2] & 0xFF;
                int a = texture.data[offset + 3] & 0xFF;
                image.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return image;
    }
}
