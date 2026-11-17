package me.zuogeren.slimefunhelperplus.hostgui;

import java.util.function.Consumer;
import me.matl114.gui.Constants;
import me.matl114.gui.FilterService;
import me.matl114.managers.config.Ref;
import me.matl114.utils.config.AttrKeyValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 宿主控件的唯一入口：插件侧不直接 new 宿主的控件，一律走这里。
 *
 * <p>宿主升级时只改本类，不用在二十来个文件里找漏。这里只做「创建 + 统一配置」，
 * 不接管绘制与命中——外观仍归各风格自己画。
 */
public final class HostControls {
    private HostControls() {}

    /**
     * 原版输入框：只负责收字（输入法、退格、粘贴、光标），框体由调用方自绘。
     *
     * <p>无边框是这套界面的前提：边框、聚焦环、底色都在 {@code paintFieldFrame} 里画，
     * 留着原版边框会与自绘的框错开一像素。
     */
    public static EditBox editBox(int x, int y, int w, int maxLength, String hint) {
        EditBox box = new EditBox(Minecraft.getInstance().font, x, y, w, 9, Component.empty());
        box.setBordered(false);
        box.setMaxLength(maxLength);
        if (hint != null && !hint.isEmpty()) {
            box.setHint(Component.literal(hint));
        }
        return box;
    }

    /** 同上，但提示文案已经是 {@link Component}（走翻译键时用这个）。 */
    public static EditBox editBoxText(int x, int y, int w, int maxLength, Component hint) {
        EditBox box = new EditBox(Minecraft.getInstance().font, x, y, w, 9, Component.empty());
        box.setBordered(false);
        box.setMaxLength(maxLength);
        if (hint != null) {
            box.setHint(hint);
        }
        return box;
    }

    /**
     * 程序写值：{@code setValue} 会同步回调 responder，写回配置时会二次触发。
     *
     * <p>所以先摘 responder、写完再挂回去。这个顺序写错的表现是「改一格，别的格跟着跳」，
     * 而且只在特定输入下复现，很难查——统一收在这里，调用方不用每次记得。
     */
    public static void setValueQuietly(EditBox box, String text, Consumer<String> responder) {
        box.setResponder(null);
        box.setValue(text == null ? "" : text);
        if (responder != null) {
            box.setResponder(responder);
        }
    }

    /**
     * 拼音搜索：宿主那套支持全拼、首字母、带空格三种写法。
     *
     * <p>自己写一份会漏掉其中一种，而且用户已经习惯了那套匹配规则——搜索框的行为不该因为
     * 换了界面就变。
     */
    public static boolean nameMatch(String text, String needle) {
        return FilterService.nameMatch(text, needle);
    }

    /**
     * 配置项的「对象 ↔ 文本」转换工厂。
     *
     * <p>各 Ref 类型自己知道怎么 stringify，这里只负责取到那个工厂；
     * 插件侧不自己实现转换，否则配置写回会与宿主配置页产生两种语义。
     */
    public static AttrKeyValue<?> keyValue(Ref<?> ref, String keyName) {
        return ref.createKeyValue(keyName);
    }

    /** 行尾重置按钮的图标。 */
    public static Identifier resetIcon() {
        return Constants.RESET_BUTTON_SPRITE;
    }
}
