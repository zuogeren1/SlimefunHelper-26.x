package me.zuogeren.slimefunhelperplus.module.newclickgui.ui;

import me.matl114.managers.config.Config;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.Ref;
import me.matl114.managers.config.StringRef;

/**
 * 新界面的自有设置，存在插件自己的配置里（{@code config/slimefunhelper-plus/config.yml}）。
 *
 * <p>刻意不走宿主的 {@code Configs.MISC_CONFIG}：那份配置只属于 ClickGui，
 * 新界面不该在宿主的面板里长出一格。开关键在模块的 {@code openKey} 里，同样存这份文件。
 */
public final class EndfieldSettings {
    private static final String ROOT = "new-click-gui";
    private static final String LIGHT = "light-theme";
    private static final String LAST_GROUP = "last-group";

    private final Config config;

    public EndfieldSettings(Config config) {
        this.config = config;
    }

    private Ref<Boolean> lightRef() {
        return config.getOrCreate(new FlagRef(false), ROOT, LIGHT);
    }

    public boolean light() {
        return Boolean.TRUE.equals(lightRef().getValue());
    }

    public void light(boolean value) {
        lightRef().setValue(value);
        config.markForSave();
    }

    private Ref<String> lastGroupRef() {
        return config.getOrCreate(new StringRef(""), ROOT, LAST_GROUP);
    }

    /** 上次停留的分组键；没有记录或分组已不存在时由界面回退到第一个分组。 */
    public String lastGroup() {
        String value = lastGroupRef().getValue();
        return value == null ? "" : value;
    }

    public void lastGroup(String value) {
        lastGroupRef().setValue(value);
        config.markForSave();
    }
}
