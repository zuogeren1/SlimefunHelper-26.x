package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.List;
import java.util.Locale;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.hacks.MainTasks;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.modules.task.TextFont;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.Ef;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;

/** 「字体」页：宿主文字模块（{@code MainTasks#getTextFont}）的字体相关设置。 */
public class FontPage extends EndfieldModuleConfigScreen {
    private static List<WrapperConfigRef<?>> refs() {
        return MainTasks.getTextFont().getEditableConfig();
    }

    public FontPage(EndfieldSettings settings) {
        super("FONT", "字体", null, FontPage::refs, settings, "Font");
    }

    /**
     * 当前生效的字体：只读三份配置，不走 {@code getAvailableFonts()}——
     * 那个方法要遍历系统字体目录并逐个读 ttf 头，放进每帧都画的页脚会把界面卡住。
     */
    @Override
    protected String extraStats() {
        TextFont font = MainTasks.getTextFont();
        if (font == null) {
            return "";
        }
        return switch (font.textStyle.get()) {
            case VANILLA -> "当前字体 原版";
            case SYSTEM -> "当前字体 " + Ef.ellipsis(localFontName(font.textFontType.get()), 120)
                    + " │ 字号 " + Math.round(font.fontSize.get());
            case FONT -> "当前字体 " + Ef.ellipsis(font.resourceFontType.get(), 120);
        };
    }

    /**
     * 系统字体清单不是配置项（要遍历目录才拿得到），做成一行动作去专门的页面挑；
     * 这一行右侧写当前状态，切到别的样式时写明为什么选了不会生效。
     */
    @Override
    protected List<Action> actions() {
        TextFont font = MainTasks.getTextFont();
        if (font == null) {
            return List.of();
        }
        String detail = switch (font.textStyle.get()) {
            case SYSTEM -> localFontName(font.textFontType.get());
            case FONT -> "当前用资源字体";
            case VANILLA -> "当前用原版字体";
        };
        return List.of(new Action(
                "选择系统字体",
                detail,
                "选择",
                "列出本机字体目录里的 ttf；选中的字体只在「系统字体」样式下生效。",
                () -> ScreenAccess.of(new FontPickerPage(settings)).openFromCurrent()));
    }

    /** 配置里可能是字体显示名，也可能是 ttf 的绝对路径；页脚只留辨识度最高的那一段。 */
    private static String localFontName(String configured) {
        if (configured == null || configured.isBlank()) {
            return "未选择";
        }
        String value = configured.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0 && slash < value.length() - 1) {
            value = value.substring(slash + 1);
        }
        if (value.toLowerCase(Locale.ROOT).endsWith(".ttf")) {
            value = value.substring(0, value.length() - 4);
        }
        return value;
    }
}
