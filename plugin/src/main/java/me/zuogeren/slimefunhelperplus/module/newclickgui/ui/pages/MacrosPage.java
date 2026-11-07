package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.ArrayList;
import java.util.List;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.modules.task.BindCommand;
import me.matl114.hacks.modules.task.EventCommand;
import me.matl114.hacks.utils.config.PrimitivePairList;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.Ef;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;

/**
 * 「宏」页：绑定命令与事件命令两个模块的配置合在一起，按各自的配置路径分节。
 *
 * <p>两个模块各自只暴露三项可编辑设置：启用开关、触发键、一整张条目表。
 * 条目表在宿主里是一个带「点击编辑列表」按钮的控件，点开就进宿主的列表编辑界面，
 * 增删条目都在那里完成，所以这一页不自造增删按钮，只在正文上方把两张表的规模和
 * 各自的启用状态摆出来——模块停着时表里的宏不会触发，这一点值得先看见。
 *
 * <p>两个模块的配置前缀是 {@code bind-command} 与 {@code event-command}，不会互相串节；
 * 返回顺序按模块排，于是每张条目表都紧跟着它自己的开关和触发键。
 */
public class MacrosPage extends EndfieldModuleConfigScreen {
    /** 数据条高度：一行数据块 + 一条分区分隔线。 */
    private static final int STRIP_H = 22;

    /** 数字横向拉宽的比例：条数是这一页唯一要一眼读出的量。 */
    private static final float NUMBER_STRETCH = 1.3F;

    /** 启用状态块的高度。 */
    private static final int CHIP_H = 11;

    /** 两段数据之间至少留出的空白。 */
    private static final int BLOCK_GAP = 18;

    private static List<WrapperConfigRef<?>> refs() {
        List<WrapperConfigRef<?>> out = new ArrayList<>();
        out.addAll(BindCommand.INSTANCE.getEditableConfig());
        out.addAll(EventCommand.INSTANCE.getEditableConfig());
        return out;
    }

    public MacrosPage(EndfieldSettings settings) {
        super("MACROS", "宏", null, MacrosPage::refs, settings, "CmdMacros");
    }

    /** 绑定命令的条数：整张表就是 {@code bind-command.commands} 这一个配置项。 */
    private static int bindCount() {
        return pairCount(BindCommand.INSTANCE.commands.get());
    }

    /** 事件宏的条数：整张表就是 {@code event-command.event-map} 这一个配置项。 */
    private static int eventCount() {
        return pairCount(EventCommand.INSTANCE.eventMap.get());
    }

    private static int pairCount(PrimitivePairList<?, ?> pairs) {
        return pairs == null || pairs.list() == null ? 0 : pairs.list().size();
    }

    @Override
    protected int toolHeight() {
        return STRIP_H;
    }

    /** 两段数据并排：名称、条数、启用状态。数字是真的条数，状态是真的开关值。 */
    @Override
    protected void paintTool(VDrawContext ctx, Ef.Palette p, int x, int y, int width, int mouseX, int mouseY) {
        int textY = y + 7;
        String bindName = "绑定命令";
        String eventName = "事件宏";
        int bindValue = bindCount();
        int eventValue = eventCount();
        int bindWidth = blockWidth(bindName, bindValue);
        int eventWidth = blockWidth(eventName, eventValue);
        int eventX = x + Math.max(bindWidth + BLOCK_GAP, width / 2);
        if (eventX + eventWidth > x + width) {
            // 窗口窄到放不下两栏时退回顺序排列，数字不裁切
            eventX = x + bindWidth + BLOCK_GAP;
        }
        paintBlock(ctx, p, x, textY, bindName, bindValue, BindCommand.INSTANCE.enable.get());
        paintBlock(ctx, p, eventX, textY, eventName, eventValue, EventCommand.INSTANCE.enable.get());
        // 竖线把两段数据分开，横线把它和下面的条目分开：一套 1px 分区
        Ef.fill(ctx, eventX - 9, y + 3, 1, STRIP_H - 10, p.rule());
        Ef.rule(ctx, x, y + STRIP_H - 5, width, p.rule());
    }

    private static void paintBlock(
            VDrawContext ctx, Ef.Palette p, int x, int textY, String name, int count, boolean enabled) {
        Ef.text(ctx, name, x, textY, p.ink());

        int numberX = x + Ef.textWidth(name) + 10;
        String number = Integer.toString(count);
        int numberWidth = Ef.trackedWideWidth(number, 0, NUMBER_STRETCH);
        // 模块停着却还留着条目：这些宏不会触发，数字垫上强调色
        boolean idle = !enabled && count > 0;
        if (idle) {
            Ef.fill(ctx, numberX - 3, textY - 2, numberWidth + 6, 12, Ef.SIGNAL);
        }
        Ef.trackedWide(ctx, number, numberX, textY, idle ? Ef.ON_SIGNAL : p.ink(), 0, NUMBER_STRETCH);
        Ef.text(ctx, "条", numberX + numberWidth + 4, textY, p.ink2());

        String state = enabled ? "启用" : "停用";
        int stateWidth = Ef.textWidth(state) + 8;
        int stateX = x + blockWidth(name, count) - stateWidth;
        if (enabled) {
            Ef.fill(ctx, stateX, textY - 3, stateWidth, CHIP_H, p.ink());
            Ef.text(ctx, state, stateX + 4, textY - 2, p.paper());
        } else {
            Ef.dashedOutline(ctx, stateX, textY - 3, stateWidth, CHIP_H, p.ink2());
            Ef.text(ctx, state, stateX + 4, textY - 2, p.ink2());
        }
    }

    /** 一段数据的自然宽度：名称 + 条数 + 「条」+ 间隔 + 状态块。 */
    private static int blockWidth(String name, int count) {
        return Ef.textWidth(name)
                + 10
                + Ef.trackedWideWidth(Integer.toString(count), 0, NUMBER_STRETCH)
                + 4
                + Ef.textWidth("条")
                + 12
                + Ef.textWidth("启用")
                + 8;
    }

    /** 增删在宿主的列表编辑界面里完成，这一页只负责引导过去。 */
    @Override
    protected String hint() {
        return "点列表按钮增删条目";
    }

    @Override
    protected String extraStats() {
        return "绑定命令 " + bindCount() + " 条 │ 事件宏 " + eventCount() + " 条";
    }
}
