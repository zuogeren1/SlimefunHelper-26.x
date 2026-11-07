package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.List;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.modules.task.ClickGui;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;

/** 「GUI」页：宿主 ClickGui 自己的设置（风格、面板尺寸、开关键位……）。 */
public class GuiSettingsPage extends EndfieldModuleConfigScreen {
    private static List<WrapperConfigRef<?>> refs() {
        return ClickGui.INSTANCE.getEditableConfig();
    }

    public GuiSettingsPage(EndfieldSettings settings) {
        super("GUI SETTINGS", "GUI 设置", null, GuiSettingsPage::refs, settings);
    }
}
