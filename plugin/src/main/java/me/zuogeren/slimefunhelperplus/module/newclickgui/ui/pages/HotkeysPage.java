package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.ArrayList;
import java.util.List;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.hacks.modules.HackModules;
import me.matl114.managers.config.KeyBindRef;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;

/**
 * 「快捷键」页：把所有模块配置里的键位项汇总成一页。
 *
 * <p>宿主原来的做法是遍历模块表筛 {@link KeyBindRef}，这里同源；
 * 条目按各自模块的配置路径分节，所以看得出是哪个模块的键位。
 */
public class HotkeysPage extends EndfieldModuleConfigScreen {
    private static List<WrapperConfigRef<?>> refs() {
        List<WrapperConfigRef<?>> out = new ArrayList<>();
        for (ModuleGroup group : HackModules.main.getModuleGroups().values()) {
            for (BaseModule module : group.getModules()) {
                for (WrapperConfigRef<?> ref : module.getEditableConfig()) {
                    if (ref.ref() instanceof KeyBindRef) {
                        out.add(ref);
                    }
                }
            }
        }
        return out;
    }

    public HotkeysPage(EndfieldSettings settings) {
        super("HOTKEYS", "快捷键", null, HotkeysPage::refs, settings);
    }
}
