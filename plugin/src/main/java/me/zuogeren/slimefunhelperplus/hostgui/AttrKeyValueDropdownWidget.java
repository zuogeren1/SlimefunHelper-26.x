package me.zuogeren.slimefunhelperplus.hostgui;

import java.util.List;
import me.matl114.gui.basic.ButtonAction;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.gui.basic.DynamicContentWidget;
import me.matl114.gui.basic.ExecutableWidget;
import me.matl114.gui.basic.InputHandler;
import me.matl114.gui.basic.RenderHandler;
import me.matl114.gui.basic.SubScreenWidget;
import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.renderer.TextRenderers;
import me.matl114.renderer.text.TextDrawer;
import me.matl114.utils.config.AttrKeyValue;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * 宿主配置页的「可编辑值框 + 候选箭头」。
 *
 * <p>照 {@code EnumAttrKeyValue#generateSwitchingButton} 那套搭：主体是输入框（直接读写
 * {@link AttrKeyValue}），右端一个箭头按钮切换候选浮层，浮层用 {@code DynamicContentWidget}
 * 挂在 {@code setPriority(1)} 的 {@code SubScreenWidget} 上 —— 宿主里唯一能盖住相邻行的做法。
 *
 * <p>候选行**不用** {@code createExecuteButton}：那个走 {@code drawScaledText0}，文本超宽时
 * 会等比缩小，一列名字长短不一时字号就参差不齐。这里自己画：字号恒定、超宽裁省略号、
 * 全名走悬停提示、滚轮翻页、点击选中。
 *
 * <p>浮层受列表的 scissor 裁剪（{@code ScrollableListWidget}），超出视口的部分看不见也点不到。
 * 这是宿主既有弹层的共同限制，不是这里引入的；行数按窗口高度自适应，尽量少触发。
 */
public final class AttrKeyValueDropdownWidget {

    /** 浮层最多占窗口高度的比例：留出上下边距，别顶到屏幕外。 */
    private static final double MAX_HEIGHT_RATIO = 0.5D;

    private AttrKeyValueDropdownWidget() {}

    /**
     * 建一个下拉值控件；宽度不足以放下箭头时返回 {@code null}，调用方退回原文本框。
     */
    public static DrawableWidget create(ElementFactory factory, AttrKeyValue<?> kv, List<String> choices,
            int x, int y, int dx, int dy) {
        if (choices.isEmpty() || dx <= dy * 2 || dy <= 0) {
            return null;
        }
        int arrow = dy;
        int mainDx = dx - arrow;
        SubScreenWidget root = new SubScreenWidget(x, y, dx, dy);
        // 主体：宿主自带的输入框，直接读写这个 AttrKeyValue，手填与下拉写的是同一份值
        root.addDrawableChild(factory.createInput(kv, 0, 0, mainDx, dy));
        MutableBoolean show = new MutableBoolean(false);
        root.addDrawableChild(factory.createExecuteButton(
                () -> Component.literal(show.booleanValue() ? "\u25B4" : "\u25BE"),
                List::of,
                ButtonAction.run(() -> show.setValue(!show.booleanValue())),
                mainDx, 0, arrow, dy));

        // 行数按窗口高度自适应。窗口大小会变（全屏/窗口化），所以每帧重算，
        // 不能在这里算一次就固定 —— 那样切到全屏后还是小窗口时的行数。
        // 浮层高度按「最多能列几行」给足，实际画几行由渲染期决定
        int popupH = visibleRows(choices.size(), dy) * dy;
        MutableInt scroll = new MutableInt(0);
        // 渲染期记下鼠标压着哪一条：tooltip 的 provider 只拿得到元素，拿不到鼠标坐标
        MutableInt hovered = new MutableInt(-1);
        SubScreenWidget popup = new SubScreenWidget(0, 0, mainDx, popupH).setPriority(1);
        // 一行候选：绘制 + 悬停提示 + 点击选中 + 滚轮翻页，全在这一个元素上
        DrawableWidget rowWidget = factory.createElement(
                (RenderHandler) (element, context, mouseX, mouseY, delta, alpha, highlight) -> {
                    // 每帧重算可见行数：窗口大小会变，算一次固定住的话切全屏后还是旧行数
                    int rows = visibleRows(choices.size(), dy);
                    paintChoices(context, element, choices, scroll.getValue(), rows, mainDx, dy, mouseX, mouseY, hovered);
                },
                null,
                ButtonAction.run(() -> {}),
                0, 0, mainDx, popupH);
        // 点击选中与滚轮翻页挂在同一行元素上：另起一个元素会和它抢点击与悬停。
        // 注意宿主分发的是 onAction（ExecutableWidget 调它），不是 onClick。
        if (rowWidget instanceof ExecutableWidget exec) {
            exec.setInputHandler(new InputHandler.ScrollerHandler() {
                @Override
                public boolean onAction(ExecutableWidget widget, double mouseX, double mouseY, int button, Type type) {
                    if (type != Type.MOUSE_CLICK || button != 0) {
                        return false;
                    }
                    // 坐标已经由 SubScreenWidget 平移过，这里直接按行高换算
                    int rows = visibleRows(choices.size(), dy);
                    int row = (int) (mouseY / dy);
                    int index = scroll.getValue() + row;
                    if (row < 0 || row >= rows || index < 0 || index >= choices.size()) {
                        return false;
                    }
                    kv.setInput(choices.get(index));
                    show.setValue(false);
                    return true;
                }

                @Override
                public boolean scroll(ExecutableWidget widget, double amount) {
                    int rows = visibleRows(choices.size(), dy);
                    int max = Math.max(0, choices.size() - rows);
                    int next = Math.max(0, Math.min(max, scroll.getValue() - (int) Math.signum(amount)));
                    if (next == scroll.getValue()) {
                        return false;
                    }
                    scroll.setValue(next);
                    return true;
                }
            });
        }
        popup.addDrawableChild(rowWidget);

        root.addDrawableChild(new DynamicContentWidget<>(() -> show.booleanValue() ? popup : null, 2, dy));
        return root;
    }

    /** 说明框的层级：比浮层的 priority 1 再高一层。 */
    private static final int TOOLTIP_LAYER = 2;

    /** 浮层能列几行：不超过候选数，也不超过窗口高度的一半。 */
    private static int visibleRows(int total, int rowH) {
        Minecraft mc = Minecraft.getInstance();
        int windowH = mc == null || mc.getWindow() == null ? 240 : mc.getWindow().getGuiScaledHeight();
        int byWindow = Math.max(1, (int) (windowH * MAX_HEIGHT_RATIO) / Math.max(1, rowH));
        return Math.max(1, Math.min(total, byWindow));
    }

    /**
     * 画候选行：字号恒定，超宽裁成省略号。
     *
     * <p>不用 {@code drawScaledText0} —— 它按可用宽度等比缩放，长短不一的候选会一行一个字号。
     */
    private static void paintChoices(VDrawContext context, DrawableWidget element, List<String> choices,
            int scroll, int rows, int width, int rowH, int mouseX, int mouseY, MutableInt hovered) {
        TextDrawer drawer = context.getTextDrawer();
        int textH = (int) drawer.getTextHeight();
        hovered.setValue(-1);
        for (int i = 0; i < rows; i++) {
            int index = scroll + i;
            if (index >= choices.size()) {
                break;
            }
            int rowY = i * rowH;
            // 鼠标坐标是相对这个元素的（renderAtCentered 已经把矩阵挪到元素原点）
            boolean hot = mouseY >= rowY && mouseY < rowY + rowH && mouseX >= 0 && mouseX < width;
            if (hot) {
                hovered.setValue(index);
            }
            context.fill(0, rowY, width, rowY + rowH, hot ? 0x60FFFFFF : 0x40000000);
            String shown = ellipsis(drawer, choices.get(index), width - 8);
            context.drawText(drawer, shown, 4, rowY + (rowH - textH) / 2, 0xFFFFFFFF, false);
        }
        // 说明框走原版渲染。宿主的 {@code drawTooltip} 把绘制登记成延迟回调，回调执行时层级栈已清空，
        // 说明框会落进 depth 0 的新层、被 priority 1 的浮层盖住；这里同步调用原版 tooltip，
        // 抬层与绘制在同一段代码里，层级才真的生效。
        int index = hovered.getValue();
        if (index >= 0 && index < choices.size()) {
            List<ClientTooltipComponent> lines = List.of(
                    ClientTooltipComponent.create(Component.literal(choices.get(index)).getVisualOrderText()));
            GuiGraphicsExtractor graphics = extractor(context);
            // 原版 tooltip 按屏幕坐标定位（贴边时翻到另一侧），而这里拿到的是元素内坐标；
            // 先把矩阵平移量取出来换算成屏幕坐标，绘制期间再把矩阵挪回原点，两边才对得上。
            Matrix4f pose = context.getMatrices().peek3D();
            float originX = pose.m30();
            float originY = pose.m31();
            Vector4f point = new Vector4f(mouseX, mouseY, 0, 1).mul(pose);
            context.pushLayer(TOOLTIP_LAYER);
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(-originX, -originY);
            try {
//#if MC >= 260300
//$$                 // 26.3 的 tooltip 多一个「首行后额外留空」开关，26.2 的排版等价于 false
//$$                 graphics.tooltip(tooltipFont(), lines, (int) point.x, (int) point.y, DefaultTooltipPositioner.INSTANCE, null, false);
//#else
                graphics.tooltip(tooltipFont(), lines, (int) point.x, (int) point.y, DefaultTooltipPositioner.INSTANCE, null);
//#endif
            } finally {
                context.getMatrices().popMatrix();
                context.popLayer();
            }
        }
    }

    /** 取底层 {@link GuiGraphicsExtractor}：{@link VDrawContext} 没有暴露实例的入口，push 后立刻 pop 保持栈平衡。 */
    private static GuiGraphicsExtractor extractor(VDrawContext context) {
        GuiGraphicsExtractor graphics = context.pushMatrix();
        context.popMatrix();
        return graphics;
    }

    /** 说明框的字体：跟随当前文字绘制器；自造字体（画在自己贴图上的）退回原版字体。 */
    private static Font tooltipFont() {
        Font font = TextRenderers.get().unwrap();
        return font != null ? font : Minecraft.getInstance().font;
    }

    /** 按像素宽度裁成省略号；放得下就原样返回。 */
    private static String ellipsis(TextDrawer drawer, String text, int maxWidth) {
        if (text == null || text.isEmpty() || drawer.getWidth(text) <= maxWidth) {
            return text;
        }
        String dots = "...";
        int limit = maxWidth - drawer.getWidth(dots);
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            int w = drawer.getWidth(String.valueOf(text.charAt(i)));
            if (used + w > limit) {
                break;
            }
            out.append(text.charAt(i));
            used += w;
        }
        return out + dots;
    }
}
