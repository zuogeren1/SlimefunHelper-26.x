package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.ArrayList;
import java.util.List;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.modules.task.BindCommand;
import me.matl114.hacks.modules.task.EventCommand;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;

/** 「宏」页：绑定命令与事件命令两个模块的配置合在一起，按各自的配置路径分节。 */
public class MacrosPage extends EndfieldModuleConfigScreen {
    private static List<WrapperConfigRef<?>> refs() {
        List<WrapperConfigRef<?>> out = new ArrayList<>();
        out.addAll(BindCommand.INSTANCE.getEditableConfig());
        out.addAll(EventCommand.INSTANCE.getEditableConfig());
        return out;
    }

    public MacrosPage(EndfieldSettings settings) {
        super("MACROS", "宏", null, MacrosPage::refs, settings);
    }
}
