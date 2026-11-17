package me.zuogeren.slimefunhelperplus.module.custommodel.texture;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import rip.ysm.imagestream.avif.AvifDecoder;
import rip.ysm.imagestream.webp.WebpDecoder;

/**
 * 把 YSM 模型里内嵌的贴图字节注册成游戏纹理。
 *
 * <p>{@link NativeImage} 只认 PNG/JPEG/BMP；WebP/AVIF 没有现成解码器（支持它们要引入第三方依赖），
 * 所以解不出来的贴图一律退回占位图 —— 贴图缺失不该让整个模型连几何都建不出来。
 */
public final class ModelTexture {

    private static final Map<String, Identifier> CACHE = new ConcurrentHashMap<>();

    /** 后台线程解好的贴图：像素已就绪，只差在渲染线程建 GPU 纹理。 */
    public record Prepared(String key, NativeImage image) {}

    /**
     * 卡片预览的模型贴图单独缓存。
     *
     * <p>不跟世界模型的 {@link #CACHE} 混：换模型会 {@link #releaseAll()} 掉世界那张，
     * 卡片若共用同一份，每次切模型都得把一页十张重新解码上传 —— 那正是开页卡顿的来源。
     * 卡片贴图只在选择页关闭时由 {@link #releaseCards()} 统一释放。
     */
    private static final Map<String, Identifier> CARD_CACHE = new ConcurrentHashMap<>();

    /** 卡片贴图的 Identifier 前缀：与世界模型的 {@code custommodel/} 分开，两者才不会互相覆盖。 */
    private static final String CARD_PATH = "custommodel/card/";

    /** 卡片底图/前景图单独缓存：它们是卡片装饰，不随模型贴图的 {@link #releaseAll()} 释放。 */
    private static final Map<String, Identifier> GUI_CACHE = new ConcurrentHashMap<>();

    /** 占位图边长：够看出「这张贴图没解出来」，又不占显存。 */
    private static final int PLACEHOLDER_SIZE = 16;

    private static volatile Identifier placeholder;

    private ModelTexture() {}

    public static Identifier register(RawYsmModel.RawTexture texture) {
        if (texture == null) {
            return null;
        }
        String cacheKey = keyOf(texture);
        return CACHE.computeIfAbsent(cacheKey, ignored -> load(texture, cacheKey));
    }

    /** 贴图的缓存键：优先用模型里记的 hash，没有就按字节算。 */
    private static String keyOf(RawYsmModel.RawTexture texture) {
        String key = texture.hash;
        if (key == null || key.isBlank()) {
            key = Integer.toHexString(texture.data == null ? 0 : Arrays.hashCode(texture.data));
        }
        return key;
    }

    /**
     * 后台线程预解码：只解像素，不碰 GPU。
     *
     * <p>解码是纯 CPU 活（{@link NativeImage} 只是堆上的像素缓冲），可以离开渲染线程；
     * 建 {@link DynamicTexture} 才需要 GL。选择页一屏十张，把解码摊到后台能省掉开页那一下的卡顿。
     */
    public static Prepared prepare(RawYsmModel.RawTexture texture) {
        if (texture == null) {
            return null;
        }
        String key = keyOf(texture);
        NativeImage image = decode(texture, key);
        return image == null ? null : new Prepared(key, image);
    }

    /**
     * 渲染线程：把预解码的像素上传成 GPU 纹理。
     *
     * <p>同一个 key 已经注册过时直接复用并释放这份像素 —— 卡片之间常共用同一张贴图。
     */
    public static Identifier registerPrepared(Prepared prepared) {
        if (prepared == null || prepared.image() == null) {
            return null;
        }
        Identifier existing = CARD_CACHE.get(prepared.key());
        if (existing != null) {
            prepared.image().close();
            return existing;
        }
        Identifier id = registerImage(prepared.image(), prepared.key(), CARD_PATH);
        if (id == null) {
            prepared.image().close();
            return null;
        }
        CARD_CACHE.put(prepared.key(), id);
        return id;
    }

    /** 释放全部卡片贴图；选择页关闭时调用。 */
    public static void releaseCards() {
        for (String key : new java.util.ArrayList<>(CARD_CACHE.keySet())) {
            Identifier id = CARD_CACHE.remove(key);
            if (id == null) {
                continue;
            }
            try {
                Minecraft.getInstance().getTextureManager().release(id);
            } catch (Throwable throwable) {
                CustomModelModule.logDebug("[SFH+] 卡片贴图释放失败 key=" + key + ": " + throwable);
            }
        }
    }

    /**
     * 释放所有已注册的模型贴图。
     *
     * <p>{@link DynamicTexture} 持有 GPU 贴图，{@link Identifier} 被丢弃不会让
     * {@code TextureManager} 回收它 —— 换模型时必须显式释放，否则每换一次模型就多占一张显存。
     * 占位图是跨模型共享的常量，不随模型释放。
     *
     * <p>必须在渲染线程调用。
     */
    public static void releaseAll() {
        // 先拷一份 key：release 会从 CACHE 里删，边遍历边删会漏掉一部分贴图
        for (String key : new java.util.ArrayList<>(CACHE.keySet())) {
            release(key);
        }
    }

    /** 释放单张贴图；key 不在缓存里时什么也不做。 */
    public static void release(String key) {
        Identifier id = CACHE.remove(key);
        if (id == null) {
            return;
        }
        try {
            Minecraft.getInstance().getTextureManager().release(id);
        } catch (Throwable throwable) {
            CustomModelModule.logDebug("[SFH+] 贴图释放失败 key=" + key + ": " + throwable);
        }
    }

    /**
     * 把模型内嵌的 GUI 底图/前景图注册成纹理。
     *
     * <p>与模型贴图分开缓存：底图是卡片装饰，换模型时不该跟着 {@link #releaseAll()} 一起释放
     * （卡片翻页会反复用到同一批底图）。key 用「角色 + 名字」区分，避免底图与前景图撞车。
     */
    public static Identifier registerGuiImage(RawYsmModel.RawImage image, String role) {
        if (image == null || image.data == null || image.data.length == 0) {
            return null;
        }
        String key = "gui-" + role + "-" + (image.name == null ? "" : image.name)
                + "-" + Integer.toHexString(Arrays.hashCode(image.data));
        // 单独一张表：底图是卡片装饰，换模型时不该跟着 releaseAll 一起释放
        // （翻页会反复用到同一批底图，释放了就得重新解码）。
        return GUI_CACHE.computeIfAbsent(key, ignored -> {
            NativeImage decoded = decodeImageBytes(image.data, image.width, image.height, image.format, key);
            return decoded == null ? null : registerImage(decoded, key);
        });
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
        return registerImage(image, key, "custommodel/");
    }

    /**
     * 注册一张纹理，{@code pathPrefix} 决定它在 {@code TextureManager} 里的位置。
     *
     * <p>卡片与世界模型必须用不同的前缀：同一个模型文件算出的 key 相同，共用 Identifier 时
     * 后注册的会覆盖先注册的，而 {@link #releaseAll()} 又会把那个 Identifier 整个删掉 ——
     * 卡片手里的引用随之失效，画出来就是紫黑棋盘格。
     */
    private static Identifier registerImage(NativeImage image, String key, String pathPrefix) {
        try {
            DynamicTexture dynamic = new DynamicTexture(() -> "sfh-model-" + key, image);
            Identifier id = Identifier.fromNamespaceAndPath("slimefunhelperplus", pathPrefix + key);
            Minecraft.getInstance().getTextureManager().register(id, dynamic);
            return id;
        } catch (Throwable throwable) {
            CustomModelModule.logDebug("[SFH+] 贴图注册失败 key=" + key + ": " + throwable);
            return null;
        }
    }

    /**
     * YSM 二进制里的贴图有两种存法：{@code imageFormat == -1} 是裸 RGBA 像素，
     * 其余是编码字节（PNG/JPEG/BMP 由 {@link NativeImage#read(byte[])} 按文件头认）。
     *
     * <p>解不出来时返回占位图而不是 null：调用方拿到 null 会连几何一起放弃。
     */
    /**
     * 解一张裸字节图（{@link RawYsmModel.RawImage} 用）。
     *
     * <p>与 {@link #decode} 同一套判定：{@code format == -1} 是裸 RGBA，其余按文件头认。
     * 解不出来返回 {@code null}（底图缺失只是少一层装饰，不该用占位图糊上去）。
     */
    private static NativeImage decodeImageBytes(byte[] data, int width, int height, int format, String key) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (format == -1 && width > 0 && height > 0 && data.length >= width * height * 4) {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = (y * width + x) * 4;
                    int r = data[offset] & 0xFF;
                    int g = data[offset + 1] & 0xFF;
                    int b = data[offset + 2] & 0xFF;
                    int a = data[offset + 3] & 0xFF;
                    image.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            return image;
        }
        String magic = magicName(data);
        if ("webp".equals(magic) || "avif".equals(magic)) {
            return decodeWithImageStream(data, magic);
        }
        try {
            return NativeImage.read(data);
        } catch (Throwable throwable) {
            CustomModelModule.logDebug("[SFH+] GUI 图解码失败 key=" + key + " magic=" + magic + ": " + throwable);
            return null;
        }
    }

    private static NativeImage decode(RawYsmModel.RawTexture texture, String key) {
        if (texture.data == null || texture.data.length == 0) {
            CustomModelModule.logDebug("[SFH+] 贴图格式不支持，已用占位: format=" + texture.imageFormat
                    + " size=" + texture.width + "x" + texture.height + " magic=empty key=" + key);
            return placeholderImage();
        }
        if (texture.imageFormat == -1) {
            NativeImage raw = rawRgba(texture);
            if (raw != null) {
                return raw;
            }
        }
        // 声明的 imageFormat 可能与真实字节不符（SPM 也是按文件头重判），所以先认 magic。
        // WebP/AVIF 交给 ImageStream（纯 Java 解码器，与 SPM 同一个库）；
        // 其余交给 NativeImage，它只认 PNG/JPEG/BMP。
        String magic = magicName(texture.data);
        if ("webp".equals(magic) || "avif".equals(magic)) {
            NativeImage decoded = decodeWithImageStream(texture.data, magic);
            if (decoded != null) {
                return decoded;
            }
        } else {
            try {
                return NativeImage.read(texture.data);
            } catch (Throwable encodedFailure) {
                NativeImage raw = rawRgba(texture);
                if (raw != null) {
                    return raw;
                }
            }
        }
        CustomModelModule.logDebug("[SFH+] 贴图格式不支持，已用占位: format=" + texture.imageFormat
                + " size=" + texture.width + "x" + texture.height
                + " magic=" + magic + " key=" + key);
        return placeholderImage();
    }

    /**
     * WebP/AVIF 解码：{@link NativeImage} 只认 PNG/JPEG/BMP，这两种要靠 ImageStream。
     *
     * <p>解码器返回 {@link BufferedImage}，这里逐像素搬进 {@link NativeImage}。
     * 解不出来返回 {@code null}，由调用方退回占位图 —— 贴图缺失不该让整个模型建不出来。
     */
    private static NativeImage decodeWithImageStream(byte[] data, String magic) {
        try {
            BufferedImage image = "webp".equals(magic)
                    ? new WebpDecoder().read(data)
                    : new AvifDecoder().read(data);
            if (image == null) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            NativeImage result = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    result.setPixelABGR(x, y, image.getRGB(x, y));
                }
            }
            return result;
        } catch (Throwable throwable) {
            CustomModelModule.logDebug("[SFH+] " + magic + " 解码失败: " + throwable);
            return null;
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
