package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.List;
import me.matl114.hacks.MainTasks;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;

/** 「字体」页：宿主文字模块（{@code MainTasks#getTextFont}）的字体相关设置。 */
public class FontPage extends EndfieldModuleConfigScreen {
    private static List<WrapperConfigRef<?>> refs() {
        return MainTasks.getTextFont().getEditableConfig();
    }

    public FontPage(EndfieldSettings settings) {
        super("FONT", "字体", null, FontPage::refs, settings);
    }
}
