package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.List;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.modules.task.ModuleSettings;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;

/** 「基础」页：宿主的模块公共设置（{@link ModuleSettings}）表单。 */
public class BaseSettingsPage extends EndfieldModuleConfigScreen {
    private static List<WrapperConfigRef<?>> refs() {
        return ModuleSettings.INSTANCE.getEditableConfig();
    }

    public BaseSettingsPage(EndfieldSettings settings) {
        super("BASE SETTINGS", "基础设置", null, BaseSettingsPage::refs, settings);
    }
}
