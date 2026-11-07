package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.List;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.modules.task.ModuleSettings;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;

/**
 * 「基础」页：宿主的模块公共设置（{@link ModuleSettings}）表单。
 *
 * <p>这一页管的是所有模块共用的行为，自己没有启用状态，所以命令栏不挂启停块。
 * 页脚补两列统计：改过几项、几项带默认值能恢复。
 */
public class BaseSettingsPage extends EndfieldModuleConfigScreen {
    private static List<WrapperConfigRef<?>> refs() {
        return ModuleSettings.INSTANCE.getEditableConfig();
    }

    public BaseSettingsPage(EndfieldSettings settings) {
        super("BASE SETTINGS", "基础设置", null, BaseSettingsPage::refs, settings, "BaseSettings");
    }

    /**
     * 页脚左侧统计。
     *
     * <p>{@code isValueDifferent()} 就是宿主对「有默认值且当前值不同」的定义
     * （{@code hasDefaultValue() && !Objects.equals(getDefaultValue(), getValue())}），
     * 直接用它，页脚统计的与行尾重置按钮判的才是同一件事。
     */
    @Override
    protected String extraStats() {
        int changed = 0;
        int restorable = 0;
        for (WrapperConfigRef<?> ref : refs()) {
            if (ref.ref().hasDefaultValue()) {
                restorable++;
            }
            if (ref.ref().isValueDifferent()) {
                changed++;
            }
        }
        return "改过 " + changed + " 项 │ 可恢复 " + restorable + " 项";
    }

    /** 这页只有开关、下拉与文本项，没有滑杆，默认那套「拖滑杆」的说明在这儿对不上。 */
    @Override
    protected String hint() {
        return "改完立即生效 │ 点行尾图标恢复默认";
    }
}
