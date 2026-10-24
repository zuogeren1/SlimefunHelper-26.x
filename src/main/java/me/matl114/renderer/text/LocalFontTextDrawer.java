package me.matl114.renderer.text;

import java.io.IOException;
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
import java.util.stream.Stream;
import me.matl114.plugins.internal.MainPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * 本地 ttf 字体。
 *
 * <p>26.x 把字体内部管线重写过：{@code FontStorage} 变成 {@code FontSet}，字形图集走
 * {@code GlyphStitcher}，装载一个 ttf 还要模拟整套 {@code PackResources}/{@code ResourceManager}。
 * 这条链路需要单独按 26.2 源码逐层核对，暂不在本批次落地——本类先按资源字体
 * {@link #FONT_LOCATION} 走常规字体解析，扫描/查找本地字体的能力（纯 java）保持可用。
 */
public final class LocalFontTextDrawer extends ResourceFontTextDrawer implements AutoCloseable {
    private static final Identifier FONT_LOCATION = MainPlugin.identifier("local-font.ttf");
    public static final float DEFAULT_FONT_SIZE = 11.0F;

    private final Path path;
    private final float fontSize;

    public LocalFontTextDrawer(Path path) {
        this(path, DEFAULT_FONT_SIZE);
    }

    public LocalFontTextDrawer(Path path, float fontSize) {
        super(Minecraft.getInstance().font, FONT_LOCATION);
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Font file does not exist: " + path);
        }
        this.path = path;
        this.fontSize = fontSize;
    }

    public Path path() {
        return path;
    }

    public float fontSize() {
        return fontSize;
    }

    @Override
    public float getTextHeight() {
        // 装载链没落地时本门面实际画的是原版字体，行高必须跟着原版走，否则排版会错位。
        return isAvailable() ? fontSize : super.getTextHeight();
    }

    @Override
    public void close() {}

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
