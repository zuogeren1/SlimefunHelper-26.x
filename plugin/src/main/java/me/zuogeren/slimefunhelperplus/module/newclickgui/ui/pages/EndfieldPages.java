package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;
import net.minecraft.client.gui.screens.Screen;

/**
 * 顶部分类 → 新界面页面。
 *
 * <p>模块页是本界面自己，其余分类各有一页；还没做的分类返回 {@code null}，
 * 由调用方退回宿主的旧页面，所以这里加页不会让某个分类突然点不开。
 */
public final class EndfieldPages {
    private EndfieldPages() {}

    public static Screen create(String key, EndfieldSettings settings) {
        return switch (key) {
            case "Friends" -> new FriendsPage(settings);
            case "CmdMacros" -> new MacrosPage(settings);
            case "Hotkeys" -> new HotkeysPage(settings);
            case "BaseSettings" -> new BaseSettingsPage(settings);
            case "GuiSettings" -> new GuiSettingsPage(settings);
            case "Font" -> new FontPage(settings);
            case "Config" -> new ConfigPage(settings);
            case "Baritone" -> new BaritonePage(settings);
            default -> null;
        };
    }
}
