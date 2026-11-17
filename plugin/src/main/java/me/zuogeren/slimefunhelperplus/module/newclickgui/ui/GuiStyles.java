package me.zuogeren.slimefunhelperplus.module.newclickgui.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield.Ef;
import me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield.EndfieldClickGuiScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield.pages.EndfieldPages;
import net.minecraft.resources.Identifier;

/**
 * 界面风格的注册表：id → 一套 {@link GuiStyle}。
 *
 * <p>风格是插件自己的，不往宿主的 {@code ClickGuiStyles} 里注册——宿主那套只负责「用哪个工厂开界面」，
 * 而本界面的控件外观全部是自绘的，挂过去反而要在宿主侧动手。
 *
 * <p>注册顺序就是配置下拉里的顺序，第一项是默认值。加一套风格 = 在这里 {@code register} 一条。
 */
public final class GuiStyles {
    /** 当前唯一的一套：终末地风味（深色为主，强调色 lime）。 */
    public static final Identifier ENDFIELD = Identifier.fromNamespaceAndPath("slimefunhelperplus", "endfield");

    private static final Map<Identifier, GuiStyle> STYLES = new LinkedHashMap<>();

    static {
        register(new GuiStyle(
                ENDFIELD,
                Ef.DARK,
                Ef.LIGHT,
                EndfieldClickGuiScreen::new,
                EndfieldPages::create,
                EndfieldModuleConfigScreen::new));
    }

    private GuiStyles() {}

    public static GuiStyle register(GuiStyle style) {
        STYLES.put(style.id(), style);
        return style;
    }

    public static boolean unregister(GuiStyle style) {
        return STYLES.remove(style.id(), style);
    }

    /** 按 id 取；没有注册过就退回默认那套，界面至少是完整的。 */
    public static GuiStyle get(Identifier id) {
        GuiStyle style = id == null ? null : STYLES.get(id);
        return style == null ? STYLES.get(ENDFIELD) : style;
    }

    /** 配置里存的是 id 字符串；解析不出来时同样退回默认。 */
    public static GuiStyle getOrDefault(String configValue) {
        if (configValue == null || configValue.isEmpty()) {
            return STYLES.get(ENDFIELD);
        }
        for (GuiStyle style : STYLES.values()) {
            if (style.id().toString().equals(configValue)) {
                return style;
            }
        }
        return STYLES.get(ENDFIELD);
    }

    public static List<GuiStyle> values() {
        return List.copyOf(STYLES.values());
    }

    /** 配置下拉的候选值：id 的字符串形式。 */
    public static List<String> configValues() {
        return STYLES.values().stream().map(style -> style.id().toString()).toList();
    }
}