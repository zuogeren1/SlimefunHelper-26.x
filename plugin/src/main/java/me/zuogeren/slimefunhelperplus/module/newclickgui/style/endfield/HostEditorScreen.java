package me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield;

import me.matl114.gui.GenericScreen;
import me.matl114.gui.basic.DrawableWidget;
import net.minecraft.network.chat.Component;

/**
 * 把一只宿主控件包成独立屏。
 *
 * <p>键值表这类配置的编辑器由 {@code NBTType} 自己生成，自绘一份等于重写它；
 * 但直接嵌进配置页会得到「一半自绘一半宿主」的割裂。折中是：入口仍是本界面的切角块，
 * 点开进这一屏——屏里只有那一个控件，关掉就回到配置页。
 */
public class HostEditorScreen extends GenericScreen {
    private final DrawableWidget editor;
    private final int editorW;
    private final int editorH;

    public HostEditorScreen(DrawableWidget editor, int editorW, int editorH) {
        super(Component.empty(), 0, 0);
        this.editor = editor;
        this.editorW = editorW;
        this.editorH = editorH;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        int x = (this.width - editorW) / 2;
        int y = (this.height - editorH) / 2;
        editor.setX(x);
        editor.setY(y);
        this.addRenderableWidget(editor);
    }
}
