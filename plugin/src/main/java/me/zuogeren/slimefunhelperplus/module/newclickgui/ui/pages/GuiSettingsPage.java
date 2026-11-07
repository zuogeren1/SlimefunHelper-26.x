package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.List;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.modules.task.ClickGui;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;

/**
 * 「GUI」页：宿主 ClickGui 自己的设置（风格、按键大小、编辑器样式、开关菜单的键位……）。
 *
 * <p>尺寸项只在对应风格下出现（{@code widgetSize} 只对 SubScreen、{@code sliderWidgetSize}
 * 只对 Slider），所以页脚同时报「合计」：和上面「N 项设置」一比，就知道被当前风格藏掉了哪几项。
 */
public class GuiSettingsPage extends EndfieldModuleConfigScreen {
    private static List<WrapperConfigRef<?>> refs() {
        return ClickGui.INSTANCE.getEditableConfig();
    }

    public GuiSettingsPage(EndfieldSettings settings) {
        super("GUI SETTINGS", "GUI 设置", null, GuiSettingsPage::refs, settings, "GuiSettings");
    }

    /** 页脚左侧统计：合计是全部可编辑项（含被风格藏起来的），改过按 {@code Ref#isValueDifferent()} 数。 */
    @Override
    protected String extraStats() {
        List<WrapperConfigRef<?>> all = refs();
        int changed = 0;
        for (WrapperConfigRef<?> ref : all) {
            if (ref.ref().isValueDifferent()) {
                changed++;
            }
        }
        return "合计 " + all.size() + " 项 │ 改过 " + changed + " 项";
    }

    /** 风格与按键大小是宿主旧界面的排版参数，本界面按自己的版式画，不跟着变。 */
    @Override
    protected String hint() {
        return "风格与按键大小只影响旧界面";
    }
}
