package me.matl114.hacks.utils.gui.slimefun;

import me.matl114.gui.PageSwitchSubScreen;
import me.matl114.gui.basic.*;
import net.minecraft.network.chat.Component;

public abstract class SlimefunPageScreen extends SlimefunScreen {

    protected final PageSwitchSubScreen pageSwitcher;
    protected ContentDelegateWidget<PageSwitchSubScreen> pageDelegate;

    protected abstract void resetPage();

    protected static final int PAGE_LABEL_HEIGHT = 12;

    protected abstract int getPageContentHeight();

    public SlimefunPageScreen(Component title) {
        super(title);
        this.pageSwitcher = new PageSwitchSubScreen(
                0,
                TITLE_OCCUPIED,
                this.backgroundWidth,
                PAGE_LABEL_HEIGHT,
                getPageContentHeight(),
                i -> this.resetPage());
    }

    protected void initPageButton() {
        this.pageDelegate = new ContentDelegateWidget<>(this.x, this.y, 0, 0)
                .setContentDelegate(this.pageSwitcher)
                .addTo(this);
        ;
    }
}
