package me.matl114.renderer.text;

import com.mojang.blaze3d.font.GlyphProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;
import me.matl114.plugins.internal.MainPlugin;
import me.matl114.utils.Debug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import net.minecraft.util.Util;

/**
 * 本地 ttf 字体。
 *
 * <p>26.x 的一份字体就是一个 {@code FontSet}（字形图集交给 {@code GlyphStitcher}），而 ttf 装载器
 * {@code TrueTypeGlyphProviderDefinition} 只吃 {@code ResourceManager}。这里按 {@code FontManager}
 * 造 FontSet 的三步等价写法自己拼一套：先用只提供本地 ttf 的 {@code ResourceManager}/{@code PackResources}
 * 喂给装载器，再把得到的 {@code GlyphProvider} 塞进自建 {@code FontSet}，最后用「本类 fontId 查这份
 * FontSet、其余转交 {@code FontManager}」的 {@code Font.Provider} 造一个 {@code Font} 交给父类当 delegate
 * ——父类会把每段样式的字体改写成构造时传进去的那个 id，于是整段文本都走本地字体；本地字体缺的码点
 * 再由本类按码点放回原样式，回落到原版字体。
 *
 * <p>{@code FontManager} 对不认识的字体 id 会静默回落到 missingFontSet（全空心方块），所以装载失败
 * 必须由本类自己判定：失败时退回原版字体并把 {@link #isAvailable()} 置 false。
 */
public final class LocalFontTextDrawer extends ResourceFontTextDrawer implements AutoCloseable {
    private static final Identifier FONT_LOCATION = MainPlugin.identifier("local-font.ttf");
    private static final float OVERSAMPLE = 4.0F;
    private static final TrueTypeGlyphProviderDefinition.Shift SHIFT =
            new TrueTypeGlyphProviderDefinition.Shift(0.0F, 1.0F);
    private static final AtomicLong FONT_INSTANCES = new AtomicLong();
    public static final float DEFAULT_FONT_SIZE = 11.0F;

    private final Path path;
    private final float fontSize;
    private final LoadedFont loaded;
    private final FontDescription.Resource localFont;
    /** 逐码点缓存「本地字体有没有这个字形」；结论与 FontSet 同寿命，所以只放实例字段。 */
    private final Map<Integer, Boolean> localGlyphs = new ConcurrentHashMap<>();

    public LocalFontTextDrawer(Path path) {
        this(path, DEFAULT_FONT_SIZE);
    }

    public LocalFontTextDrawer(Path path, float fontSize) {
        this(path, fontSize, load(path, fontSize));
    }

    private LocalFontTextDrawer(Path path, float fontSize, LoadedFont loaded) {
        super(loaded.font(), loaded.fontId(), loaded.fontSet() != null);
        this.path = path;
        this.fontSize = fontSize;
        this.loaded = loaded;
        this.localFont = new FontDescription.Resource(loaded.fontId());
    }

    public Path path() {
        return path;
    }

    public float fontSize() {
        return fontSize;
    }

    /**
     * 把本地 ttf 装成一份独立的 {@code FontSet}；任何一环失败都退回原版字体。
     *
     * <p>{@code FontManager#createFontSet} 是私有的，这里按它的三步写法复刻：建 {@code GlyphStitcher} →
     * 建 {@code FontSet} → {@code reload} 一批自带过滤条件的 {@code GlyphProvider}。{@code fontId} 每个实例
     * 唯一，否则 {@code GlyphStitcher} 注册的字形图集名会互相顶掉。
     */
    private static LoadedFont load(Path path, float fontSize) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Font file does not exist: " + path);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            throw new IllegalStateException("Local font cannot be loaded before Minecraft is initialized");
        }
        Identifier fontId = MainPlugin.identifier("local-font/" + FONT_INSTANCES.getAndIncrement());
        GlyphProvider provider = null;
        FontSet fontSet = null;
        try {
            TrueTypeGlyphProviderDefinition definition =
                    new TrueTypeGlyphProviderDefinition(FONT_LOCATION, fontSize, OVERSAMPLE, SHIFT, "");
            GlyphProviderDefinition.Loader loader = definition.unpack()
                    .left()
                    .orElseThrow(() -> new IllegalStateException("TTF definition has no loader"));
            provider = loader.load(new LocalFontResourceManager(minecraft.getResourceManager(), FONT_LOCATION, path));
            fontSet = new FontSet(new GlyphStitcher(minecraft.getTextureManager(), fontId));
            fontSet.reload(
                    List.of(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)), Set.of());
            return new LoadedFont(new Font(new LocalFontProvider(fontSet, fontId)), fontId, fontSet, provider);
        } catch (Exception exception) {
            if (fontSet != null) fontSet.close();
            if (provider != null) provider.close();
            Debug.info("Failed to load local font " + path + ", falling back to the vanilla font: " + exception);
            return LoadedFont.fallback();
        }
    }

    @Override
    public float getTextHeight() {
        // 装载失败时本门面实际画的是原版字体，行高必须跟着原版走，否则排版会错位。
        return isAvailable() ? fontSize : super.getTextHeight();
    }

    /**
     * 逐码点决定这个字符用哪份字体：本地字体有这个字形就换成 {@code fontId}，没有就保持原样式不动。
     *
     * <p>父类把整段文本的字体一律改写成 {@code fontId}，本地 ttf 缺字（CJK、符号）时那些字符只能落到
     * {@code FontSet} 的 missing 字形（不可见）；这里改成按码点分流，缺字的样式不带本地 fontId，
     * {@code Font#prepareText} 取字形时就落回原版字体。
     */
    @Override
    public FormattedCharSequence withFont(FormattedCharSequence text) {
        if (!isAvailable()) return text;
        return sink -> text.accept(
                (index, style, codePoint) -> sink.accept(index, withLocalFont(codePoint, style), codePoint));
    }

    @Override
    public FormattedCharSequence formattedString(String text, boolean mirror) {
        if (!isAvailable()) return super.formattedString(text, mirror);
        String renderedText = mirror && delegate.isBidirectional() ? delegate.bidirectionalShaping(text) : text;
        List<FontCharacter> characters = new ArrayList<>();
        StringDecomposer.iterateFormatted(renderedText, Style.EMPTY, (index, style, codePoint) -> {
            characters.add(new FontCharacter(index, withLocalFont(codePoint, style), codePoint));
            return true;
        });
        return sink -> {
            for (FontCharacter character : characters) {
                if (!sink.accept(character.index(), character.style(), character.codePoint())) {
                    return false;
                }
            }
            return true;
        };
    }

    /** 缺字时不改样式，交给原版字体；其余字符仍然整段改用本地字体。 */
    private Style withLocalFont(int codePoint, Style style) {
        return hasLocalGlyph(codePoint) ? style.withFont(localFont) : style;
    }

    /**
     * 本地 ttf 是否真有这个码点的字形。
     *
     * <p>不能问 {@code FontSet#source}：它查不到字形时返回的是 missing 字形而不是 null。装载器
     * （{@code TrueTypeGlyphProvider}）对 cmap 之外的码点返回 null，所以直接问 provider。
     * 空格与控制字符不走字形判断，直接算「本地有」，它们的宽度归本地字体管。
     */
    private boolean hasLocalGlyph(int codePoint) {
        if (codePoint <= 32) return true;
        Boolean cached = localGlyphs.get(codePoint);
        if (cached != null) return cached;
        boolean present = loaded.provider().getGlyph(codePoint) != null;
        localGlyphs.put(codePoint, present);
        return present;
    }

    /** 预先把字符与样式算好；{@code FormattedCharSequence} 会被消费多次，别在 sink 里重算。 */
    private record FontCharacter(int index, Style style, int codePoint) {}

    @Override
    public void close() {
        if (loaded.fontSet() != null) loaded.fontSet().close();
        if (loaded.provider() != null) loaded.provider().close();
    }

    public static List<FontFile> scanFonts() {
        Set<Path> roots = new HashSet<>();
        String userHome = System.getProperty("user.home", "");
        String javaHome = System.getProperty("java.home", "");
        String systemRoot = System.getenv("SystemRoot");

        addRoot(roots, Path.of(javaHome, "lib", "fonts"));
        switch (Util.getPlatform()) {
            case WINDOWS -> {
                addRoot(roots, Path.of(userHome, "AppData", "Local", "Microsoft", "Windows", "Fonts"));
                if (systemRoot != null) addRoot(roots, Path.of(systemRoot, "Fonts"));
            }
            case OSX -> {
                addRoot(roots, Path.of(userHome, "Library", "Fonts"));
                addRoot(roots, Path.of("/System/Library/Fonts"));
            }
            default -> {
                addRoot(roots, Path.of(userHome, ".local", "share", "fonts"));
                addRoot(roots, Path.of(userHome, ".fonts"));
                addRoot(roots, Path.of("/usr/share/fonts"));
            }
        }

        Map<String, FontFile> fonts = new HashMap<>();
        Set<Path> visited = new HashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(LocalFontTextDrawer::isTtf)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .filter(visited::add)
                        .map(LocalFontTextDrawer::inspect)
                        .filter(Objects::nonNull)
                        .forEach(font -> fonts.putIfAbsent(font.displayName().toLowerCase(java.util.Locale.ROOT), font));
            } catch (IOException ignored) {
            }
        }
        List<FontFile> result = new ArrayList<>(fonts.values());
        result.sort(Comparator.comparing(FontFile::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(font -> font.path().toString(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public static Optional<FontFile> findFont(String selection) {
        if (selection == null || selection.isBlank()) return Optional.empty();
        String normalized = selection.trim();
        try {
            Path path = Path.of(normalized).toAbsolutePath().normalize();
            if (Files.isRegularFile(path) && isTtf(path)) {
                return Optional.ofNullable(inspect(path));
            }
        } catch (InvalidPathException ignored) {
        }
        List<FontFile> fonts = scanFonts();
        Optional<FontFile> exactName = fonts.stream()
                .filter(font -> font.displayName().equalsIgnoreCase(normalized))
                .findFirst();
        if (exactName.isPresent()) return exactName;
        return fonts.stream()
                .filter(font -> font.path().getFileName().toString().equalsIgnoreCase(normalized)
                        || font.family().equalsIgnoreCase(normalized))
                .findFirst();
    }

    private static FontFile inspect(Path path) {
        try {
            java.awt.Font font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, path.toFile());
            String family = font.getFamily();
            int fontStyle = font.getStyle();
            String style = fontStyle == (java.awt.Font.BOLD | java.awt.Font.ITALIC)
                    ? "Bold Italic"
                    : fontStyle == java.awt.Font.BOLD
                            ? "Bold"
                            : fontStyle == java.awt.Font.ITALIC ? "Italic" : "Regular";
            return new FontFile(family, style, path);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isTtf(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".ttf");
    }

    private static void addRoot(Set<Path> roots, Path path) {
        if (path != null) roots.add(path.toAbsolutePath().normalize());
    }

    /** 一次装载的结果；失败时 {@code fontSet}/{@code provider} 为 null，{@code font} 就是原版字体。 */
    private record LoadedFont(Font font, Identifier fontId, FontSet fontSet, GlyphProvider provider) {
        private static LoadedFont fallback() {
            return new LoadedFont(Minecraft.getInstance().font, FONT_LOCATION, null, null);
        }
    }

    /**
     * 本地字体 + 原版字体的 {@code Font.Provider}。
     *
     * <p>样式里写着本类的 fontId 就返回本地字形；其余（缺字回落后残留的原样式、{@code minecraft:default}
     * 以及别的资源字体）一律转交 {@code FontManager}——否则缺字会再次拿到本地 FontSet 的 missing 字形，
     * 回落等于没做。
     */
    private static final class LocalFontProvider implements Font.Provider {
        private final FontSet fontSet;
        private final GlyphSource source;
        private final Identifier fontId;

        private LocalFontProvider(FontSet fontSet, Identifier fontId) {
            this.fontSet = fontSet;
            this.source = fontSet.source(false);
            this.fontId = fontId;
        }

        @Override
        public GlyphSource glyphs(FontDescription font) {
            if (font instanceof FontDescription.Resource resource && !fontId.equals(resource.id())) {
                return Minecraft.getInstance().fontManager.getFontSetRaw(resource.id()).source(false);
            }
            return source;
        }

        @Override
        public EffectGlyph effect() {
            return fontSet.whiteGlyph();
        }
    }

    /**
     * 只多出一个 {@code <ns>:font/xxx} 的 {@code ResourceManager}，其余全部转交给真正的实现。
     * ttf 装载器只用到 {@code #open}，但接口方法必须齐。
     */
    private static final class LocalFontResourceManager implements ResourceManager {
        private final ResourceManager delegate;
        private final Identifier resourceId;
        private final Resource resource;

        private LocalFontResourceManager(ResourceManager delegate, Identifier location, Path path) {
            this.delegate = delegate;
            this.resourceId = location.withPrefix("font/");
            this.resource = new Resource(new LocalFontResourcePack(this.resourceId, path), IoSupplier.create(path));
        }

        @Override
        public Optional<Resource> getResource(Identifier location) {
            if (this.resourceId.equals(location)) {
                return Optional.of(this.resource);
            }
            return this.delegate.getResource(location);
        }

        @Override
        public Set<String> getNamespaces() {
            Set<String> namespaces = new HashSet<>(this.delegate.getNamespaces());
            namespaces.add(this.resourceId.getNamespace());
            return namespaces;
        }

        @Override
        public List<Resource> getResourceStack(Identifier location) {
            if (this.resourceId.equals(location)) {
                List<Resource> stack = new ArrayList<>(this.delegate.getResourceStack(location));
                stack.add(this.resource);
                return stack;
            }
            return this.delegate.getResourceStack(location);
        }

        @Override
        public Map<Identifier, Resource> listResources(String directory, Predicate<Identifier> filter) {
            Map<Identifier, Resource> resources = new HashMap<>(this.delegate.listResources(directory, filter));
            if (this.resourceId.getPath().startsWith(directory) && filter.test(this.resourceId)) {
                resources.put(this.resourceId, this.resource);
            }
            return resources;
        }

        @Override
        public Map<Identifier, List<Resource>> listResourceStacks(String directory, Predicate<Identifier> filter) {
            Map<Identifier, List<Resource>> stacks = new HashMap<>(this.delegate.listResourceStacks(directory, filter));
            if (this.resourceId.getPath().startsWith(directory) && filter.test(this.resourceId)) {
                List<Resource> matches = new ArrayList<>(stacks.getOrDefault(this.resourceId, List.of()));
                matches.add(this.resource);
                stacks.put(this.resourceId, matches);
            }
            return stacks;
        }

        @Override
        public Stream<PackResources> listPacks() {
            return this.delegate.listPacks();
        }
    }

    /** 只暴露 {@code <ns>:font/xxx} 这一个资源的资源包，用来给 {@link Resource} 当来源。 */
    private static final class LocalFontResourcePack implements PackResources {
        private static final PackLocationInfo LOCATION = new PackLocationInfo(
                "slimefunhelper-local-font",
                Component.literal("SlimefunHelper local font"),
                PackSource.DEFAULT,
                Optional.empty());

        private final Identifier resourceId;
        private final Path path;

        private LocalFontResourcePack(Identifier resourceId, Path path) {
            this.resourceId = resourceId;
            this.path = path;
        }

        @Override
        public IoSupplier<InputStream> getRootResource(String... path) {
            return null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
            if (type == PackType.CLIENT_RESOURCES && this.resourceId.equals(location)) {
                return IoSupplier.create(this.path);
            }
            return null;
        }

        @Override
        public void listResources(
                PackType type, String namespace, String directory, PackResources.ResourceOutput output) {
            if (type == PackType.CLIENT_RESOURCES
                    && this.resourceId.getNamespace().equals(namespace)
                    && this.resourceId.getPath().startsWith(directory)) {
                output.accept(this.resourceId, IoSupplier.create(this.path));
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return type == PackType.CLIENT_RESOURCES ? Set.of(this.resourceId.getNamespace()) : Set.of();
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionType<T> metadataSerializer) {
            return null;
        }

        @Override
        public PackLocationInfo location() {
            return LOCATION;
        }

        @Override
        public void close() {}
    }

    public record FontFile(String family, String style, Path path) {
        public String displayName() {
            return family + " " + style;
        }

        @Override
        public String toString() {
            return displayName() + " (" + path + ")";
        }
    }
}
