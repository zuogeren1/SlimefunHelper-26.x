package me.zuogeren.slimefunhelperplus.module.newclickgui.ui.pages;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.matl114.gui.FilterService;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.BaseModule.WrapperConfigRef;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.hacks.modules.HackModules;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.Ef;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldModuleConfigScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 「快捷键」页：把所有模块配置里的键位项汇总成一页。
 *
 * <p>收集顺序是「分组 → 模块 → 配置项」，同一模块的键位在清单里彼此相邻，
 * 配置路径前缀因此成为分节；顺序不能按字母重排，否则一个模块的键位会被拆到好几个同名分节里。
 *
 * <p>键位项是 {@link KeyBindRef}，所以行控件直接用基类那套：左键进监听、右键写空表清除。
 * 上面的工具条是搜索框，筛的是"键名/行名"两样，页脚同时报总数与已绑数。
 */
public class HotkeysPage extends EndfieldModuleConfigScreen {
    private static final int TOOL_H = 22;

    /** 全量键位清单：模块表在运行期不变，收集一次就够。 */
    private static List<WrapperConfigRef<?>> all;

    private String query = "";
    private EditBox searchBox;

    /** 命中结果按配置项缓存：行名要过一遍拼音转换，每帧对全部键位重算会拖慢界面。 */
    private String acceptQuery = "";
    private final Map<WrapperConfigRef<?>, Boolean> acceptCache = new IdentityHashMap<>();

    private static List<WrapperConfigRef<?>> all() {
        if (all == null) {
            List<WrapperConfigRef<?>> out = new ArrayList<>();
            for (ModuleGroup group : HackModules.getModuleGroups()) {
                for (BaseModule module : group.getModules()) {
                    for (WrapperConfigRef<?> ref : module.getEditableConfig()) {
                        if (ref.ref() instanceof KeyBindRef) {
                            out.add(ref);
                        }
                    }
                }
            }
            all = out;
        }
        return all;
    }

    public HotkeysPage(EndfieldSettings settings) {
        super("HOTKEYS", "快捷键", null, HotkeysPage::all, settings, "Hotkeys");
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        int boxW = searchBoxWidth();
        int prefixW = Ef.trackedWidth("//", 1) + 4;
        EditBox box = new EditBox(
                Minecraft.getInstance().font,
                contentLeftPx() + 4 + prefixW,
                bodyTopPx() + 7,
                Math.max(24, boxW - 8 - prefixW),
                9,
                Component.literal("搜索"));
        box.setBordered(false);
        box.setMaxLength(32);
        box.setHint(Component.literal("搜索键名"));
        box.setValue(query);
        box.setResponder(text -> query = text);
        this.searchBox = box;
        this.addRenderableWidget(box);
    }

    @Override
    protected int toolHeight() {
        return TOOL_H;
    }

    @Override
    protected void paintTool(
            VDrawContext ctx, Ef.Palette p, int x, int y, int width, int mouseX, int mouseY) {
        int boxW = searchBoxWidth();
        if (searchBox != null) {
            searchBox.setTextColor(p.ink());
        }
        Ef.fill(ctx, x, y + 4, boxW, 13, p.panel());
        Ef.cutOutline(ctx, x, y + 4, boxW, 13, p.rule(), Ef.CUT);
        Ef.tracked(ctx, "//", x + 4, y + 7, p.ink2(), 1);
        String shown = shownCount() + " / " + all().size() + " 个键位";
        Ef.text(ctx, shown, x + boxW + 12, y + 7, p.ink2());
    }

    private int searchBoxWidth() {
        return Math.max(120, Math.min(240, contentWidthPx() / 3));
    }

    /**
     * 搜索同时匹配配置键名与行名：键名按原样匹配，行名交给宿主的 {@link FilterService#nameMatch}，
     * 于是中文行名也能用全拼与首字母搜。
     */
    @Override
    protected boolean accept(WrapperConfigRef<?> ref) {
        String q = query.trim();
        if (q.isEmpty()) {
            return true;
        }
        if (!acceptQuery.equals(q)) {
            acceptCache.clear();
            acceptQuery = q;
        }
        Boolean cached = acceptCache.get(ref);
        if (cached != null) {
            return cached;
        }
        String needle = q.toLowerCase(Locale.ROOT);
        boolean hit = ref.keyName().toLowerCase(Locale.ROOT).contains(needle)
                || FilterService.nameMatch(rowLabel(ref), needle);
        acceptCache.put(ref, hit);
        return hit;
    }

    private int shownCount() {
        int shown = 0;
        for (WrapperConfigRef<?> ref : all()) {
            if (ref.shouldShow() && accept(ref)) {
                shown++;
            }
        }
        return shown;
    }

    /** 页脚统计：当前搜到几个、其中几个已经绑上键；筛选时再把总数带上。 */
    @Override
    protected String extraStats() {
        int bound = 0;
        int shown = 0;
        for (WrapperConfigRef<?> ref : all()) {
            if (!ref.shouldShow() || !accept(ref)) {
                continue;
            }
            shown++;
            if (ref.ref() instanceof KeyBindRef keyBind && isBound(keyBind)) {
                bound++;
            }
        }
        String text = "共 " + shown + " 个键位 │ 已绑 " + bound + " 个";
        return shown == all().size() ? text : text + " │ 全部 " + all().size();
    }

    /** 键位项用空表表示「未绑定」，空表与 null 是同一件事。 */
    private static boolean isBound(KeyBindRef ref) {
        MultiKeyBind bind = ref.get();
        return bind != null && !bind.isEmpty();
    }

    /** 本页没有滑杆，默认提示不适用；点行改键、右键清键是真实操作。 */
    @Override
    protected String hint() {
        return "左键改键 │ 右键清除";
    }
}
