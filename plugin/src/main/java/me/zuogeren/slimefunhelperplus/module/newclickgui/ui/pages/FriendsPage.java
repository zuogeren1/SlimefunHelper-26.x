package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.List;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.modules.combat.TargetSelector;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;

/**
 * 「好友」页：宿主把好友/名单相关的配置挂在 {@link TargetSelector} 上，这一页就是把它们排成新界面的表单。
 *
 * <p>不是模块页，所以命令栏不挂启用块——这些开关本身就在表单里。
 */
public class FriendsPage extends EndfieldModuleConfigScreen {
    private static List<WrapperConfigRef<?>> refs() {
        return TargetSelector.INSTANCE.getEditableConfig();
    }

    public FriendsPage(EndfieldSettings settings) {
        super("FRIENDS", "好友", null, FriendsPage::refs, settings, "Friends");
    }

    /**
     * 名单人数来自 {@code playerList}（随 friends.nbt 单独存盘，不在配置项里），
     * 好友是否可攻击来自 {@code attack.att-friend}。两项都不碰玩家实体，主菜单里也报得出真实值。
     */
    @Override
    protected String extraStats() {
        TargetSelector selector = TargetSelector.INSTANCE;
        if (selector == null) {
            return "";
        }
        int count = selector.playerList == null ? 0 : selector.playerList.friends().size();
        return "名单 " + count + " 人 │ " + (selector.attackFriend.get() ? "好友可攻击" : "好友受保护");
    }
}
