package cn.net.rms.confluxmap.mc.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;

/**
 * conflux-map 的绘制包装（编译期桩，不参与打包）。
 * 仅声明本模块真正用到的成员，签名以 javap 真实 jar 为准：
 *   public void fill(int, int, int, int, int);
 *   public int drawTextWithShadow(net.minecraft.client.gui.Font, java.lang.String, float, float, int);
 */
public class GuiDraw {
    public GuiDraw() {}

    public PoseStack matrices() {
        return null;
    }

    public void fill(int x1, int y1, int x2, int y2, int color) {}

    public int drawTextWithShadow(Font font, String text, float x, float y, int color) {
        return 0;
    }
}
