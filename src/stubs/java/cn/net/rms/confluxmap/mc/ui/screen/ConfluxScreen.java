package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * conflux-map 的界面基类（编译期桩，不参与打包）。
 * 真实 jar 中为 public abstract，构造器 protected，renderContents 为 protected abstract。
 */
public abstract class ConfluxScreen extends Screen {
    protected ConfluxScreen(Component title) {
        super(title);
    }

    protected abstract void renderContents(GuiDraw draw, int mouseX, int mouseY, float tickDelta);
}
