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
 * <p>{@link NativeImage} 只认 PNG/JPEG/BMP；WebP/AVIF 没有现成解码器（支持它们要引入第三方依赖），
 * 所以解不出来的贴图一律退回占位图 —— 贴图缺失不该让整个模型连几何都建不出来。
 */
public final class ModelTexture {

    private static final Map<String, Identifier> CACHE = new ConcurrentHashMap<>();

    /** 占位图边长：够看出「这张贴图没解出来」，又不占显存。 */
    private static final int PLACEHOLDER_SIZE = 16;

    private static volatile Identifier placeholder;

    private ModelTexture() {}

    public static Identifier register(RawYsmModel.RawTexture texture) {
        if (texture == null) {
            return null;
        }
        String key = texture.hash;
        if (key == null || key.isBlank()) {
            key = Integer.toHexString(texture.data == null ? 0 : Arrays.hashCode(texture.data));
        }
        final String cacheKey = key;
        return CACHE.computeIfAbsent(cacheKey, ignored -> load(texture, cacheKey));
    }

    /** 模型一张贴图都没有时用的占位图；同一个 Identifier 复用，避免每次加载都注册新纹理。 */
    public static Identifier placeholder() {
        Identifier cached = placeholder;
        if (cached != null) {
            return cached;
        }
        synchronized (ModelTexture.class) {
            if (placeholder == null) {
                // 固定 key 而不是走 hash 缓存：占位图与任何真实贴图都不该共用 Identifier
                placeholder = registerImage(placeholderImage(), "placeholder");
            }
            return placeholder;
        }
    }

    private static Identifier load(RawYsmModel.RawTexture texture, String key) {
        return registerImage(decode(texture, key), key);
    }

    private static Identifier registerImage(NativeImage image, String key) {
        try {
            DynamicTexture dynamic = new DynamicTexture(() -> "sfh-model-" + key, image);
            Identifier id = Identifier.fromNamespaceAndPath("slimefunhelperplus", "custommodel/" + key);
            Minecraft.getInstance().getTextureManager().register(id, dynamic);
            return id;
        } catch (Throwable throwable) {
            Debug.info("[SFH+] 贴图注册失败 key=" + key + ": " + throwable);
            return null;
        }
    }

    /**
     * YSM 二进制里的贴图有两种存法：{@code imageFormat == -1} 是裸 RGBA 像素，
     * 其余是编码字节（PNG/JPEG/BMP 由 {@link NativeImage#read(byte[])} 按文件头认）。
     *
     * <p>解不出来时返回占位图而不是 null：调用方拿到 null 会连几何一起放弃。
     */
    private static NativeImage decode(RawYsmModel.RawTexture texture, String key) {
        if (texture.data == null || texture.data.length == 0) {
            Debug.info("[SFH+] 贴图格式不支持，已用占位: format=" + texture.imageFormat
                    + " size=" + texture.width + "x" + texture.height + " magic=empty key=" + key);
            return placeholderImage();
        }
        if (texture.imageFormat == -1) {
            NativeImage raw = rawRgba(texture);
            if (raw != null) {
                return raw;
            }
        }
        // 声明的 imageFormat 可能与真实字节不符（SPM 也是按文件头重判），所以先认 magic：
        // 认得出是 WebP/AVIF 就直接放弃，认成 PNG/JPEG/BMP 就交给 NativeImage。
        String magic = magicName(texture.data);
        if (!"webp".equals(magic) && !"avif".equals(magic)) {
            try {
                return NativeImage.read(texture.data);
            } catch (Throwable encodedFailure) {
                NativeImage raw = rawRgba(texture);
                if (raw != null) {
                    return raw;
                }
            }
        }
        Debug.info("[SFH+] 贴图格式不支持，已用占位: format=" + texture.imageFormat
                + " size=" + texture.width + "x" + texture.height
                + " magic=" + magic + " key=" + key);
        return placeholderImage();
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

    /** 洋红/黑棋盘：一眼能认出是占位，又不像全透明那样让模型凭空消失。 */
    private static NativeImage placeholderImage() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, PLACEHOLDER_SIZE, PLACEHOLDER_SIZE, false);
        for (int y = 0; y < PLACEHOLDER_SIZE; y++) {
            for (int x = 0; x < PLACEHOLDER_SIZE; x++) {
                boolean magenta = (((x >> 2) + (y >> 2)) & 1) == 0;
                image.setPixelABGR(x, y, magenta ? 0xFFFF00FF : 0xFF000000);
            }
        }
        return image;
    }

    /** 按文件头认格式：声明的 imageFormat 可能与真实字节不符，日志里要能看出到底是哪种。 */
    private static String magicName(byte[] data) {
        if (data == null || data.length < 12) {
            return "unknown";
        }
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "png";
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return "jpeg";
        }
        if (data[0] == 'B' && data[1] == 'M') {
            return "bmp";
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        if (data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') {
            return "avif";
        }
        return "unknown";
    }
}
