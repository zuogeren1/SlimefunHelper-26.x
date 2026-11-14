package me.zuogeren.slimefunhelperplus.module.custommodel.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.GenericScreen;
import me.matl114.utils.ClientUtils;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelManager;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import me.zuogeren.slimefunhelperplus.module.custommodel.ModelCatalog;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ModelRuntime;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.Ef;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EfWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/**
 * 模型选择页：左栏一块模型展示，右栏 5×2 张卡片，点卡片即切换模型。
 *
 * <p>版式照用户确认过的可交互预览（{@code .notes/refs/image/ysm/preview.html}）：面板 420×235、
 * 左栏 135、右栏 282、卡片 52×90、每页 10 个。整块按设计尺寸居中、不做等比缩放 ——
 * 3D 预览走原版 PiP 提交，它按屏幕绝对坐标出图，跟着 2D 矩阵缩会错位。
 *
 * <p>3D 只画<b>一块</b>：左栏展示位。卡片里不画 3D —— {@link ModelRuntime} 是单实例，
 * 每次 {@code render} 都会推进物理与控制器状态，同一帧画十份会互相踩状态（动作会跳帧、道具显隐乱掉）。
 *
 * <p>点卡片 = 选中并切换模型（写 {@code custom.model}），不跳页；动作预览从展示位右上角的齿轮进。
 */
public class ModelPickerScreen extends GenericScreen {

    private static final int PANEL_W = 420;
    private static final int PANEL_H = 235;
    private static final int LEFT_W = 135;
    private static final int RIGHT_X = 138;
    private static final int RIGHT_W = 282;

    private static final int SLOT_X = 5;
    private static final int SLOT_Y = 5;
    private static final int SLOT_W = 125;
    private static final int SLOT_H = 171;
    /**
     * 3D 预览只占展示位的中段。
     *
     * <p>原版实体预览走 PiP：它先渲染到离屏纹理、再作为一张图贴回 GUI，贴回的位置由
     * {@code entity(...)} 的矩形决定，且盖在所有 2D 之上。所以齿轮必须整块落在矩形之外，
     * 否则会被那张图盖住、点不到。
     */
    private static final int STAGE_TOP = 30;
    private static final int STAGE_H = 130;

    private static final int GEAR_X = 106;
    private static final int GEAR_Y = 6;
    private static final int GEAR_SIZE = 20;

    private static final int TEX_X = 28;
    private static final int TEX_Y = 5;
    private static final int TEX_W = 79;
    private static final int TEX_H = 20;

    private static final int SEARCH_X = 144;
    private static final int SEARCH_Y = 6;
    private static final int SEARCH_W = 140;
    private static final int SEARCH_H = 16;

    private static final int CAT_SIZE = 18;
    private static final int CAT_Y = 5;
    private static final int[] CAT_X = {288, 308, 328};

    private static final int CARD_W = 52;
    private static final int CARD_H = 90;
    private static final int CARD_GAP_X = 3;
    private static final int CARD_GAP_Y = 3;
    private static final int CARD_COLS = 5;
    private static final int CARD_ROWS = 2;
    private static final int PER_PAGE = CARD_COLS * CARD_ROWS;
    private static final int GRID_X = 143;
    private static final int GRID_Y = 28;

    private static final int PG_W = 52;
    private static final int PG_H = 14;
    private static final int PG_Y = 215;
    private static final int PREV_X = 198;
    private static final int NEXT_X = 308;
    private static final int PAGE_NUM_X = 244;
    private static final int PAGE_NUM_W = 70;

    /** 悬停擦入的时长，与 {@link Ef#mech(float)} 配套。 */
    private static final float WIPE_MS = 260.0F;

    /** 面板之外的压暗：世界还看得见，但不会和面板抢注意力。 */
    private static final int VEIL = 0x40000000;

    /** 悬停目标编号：0 = 没有。擦入动画按「目标变了就重来」驱动，不逐件存时间戳。 */
    private static final int HOVER_NONE = 0;
    private static final int HOVER_GEAR = 1;
    private static final int HOVER_TEX = 2;
    private static final int HOVER_CAT = 3;
    private static final int HOVER_PREV = 6;
    private static final int HOVER_NEXT = 7;
    private static final int HOVER_CARD = 10;

    private final Ef.Palette pal = Ef.DARK;
    private final List<String> view = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int page;
    private String query = "";
    private EditBox searchBox;
    private int hovered = HOVER_NONE;
    private long hoverStart = System.currentTimeMillis();

    public ModelPickerScreen() {
        super(Component.literal("模型选择"), 0, 0);
        rebuildView();
    }

    /**
     * 不要求玩家实体存在：从主菜单的 ClickGui 也能进来，那时只是没有 3D 预览，
     * 列表与切换照常可用。
     */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (ClientUtils.getScreen() instanceof ModelPickerScreen) {
            return;
        }
        ScreenAccess.of(new ModelPickerScreen()).openFromCurrent();
    }

    @Override
    protected void init0() {
        super.init0();
        this.x = 0;
        this.y = 0;
        this.panelX = Math.max(0, (this.width - PANEL_W) / 2);
        this.panelY = Math.max(0, (this.height - PANEL_H) / 2);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 世界留在背后：选模型时能看见角色本身，比糊一层菜单底更有用。
     * 压暗交给 {@link #paint} 里那层 VEIL，这里整块不画。
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {}

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        EfWidget.of(0, 0, this.width, this.height, this::paint)
                .click(this::click)
                .scroll(this::wheel)
                .addTo(this);
        if (this.searchBox != null) {
            this.removeWidget(this.searchBox);
        }
        this.searchBox = new EditBox(
                Minecraft.getInstance().font,
                panelX + SEARCH_X + 4,
                panelY + SEARCH_Y + 4,
                SEARCH_W - 8,
                9,
                Component.empty());
        this.searchBox.setBordered(false);
        this.searchBox.setMaxLength(48);
        this.searchBox.setHint(Component.literal("搜索模型 / 作者 / 目录"));
        this.searchBox.setTextColor(pal.ink());
        this.searchBox.setValue(query);
        this.searchBox.setResponder(this::applyQuery);
        this.addRenderableWidget(this.searchBox);
    }

    /** 搜索词变了就重算列表并回到第一页：留在旧页码上会看到一张空表。 */
    private void applyQuery(String text) {
        this.query = text == null ? "" : text;
        this.page = 0;
        rebuildView();
    }

    /**
     * 按搜索词过滤模型目录。
     *
     * <p>匹配显示名与相对路径两处：作者常把分类写在文件夹名里，只比显示名会搜不到。
     */
    private void rebuildView() {
        List<String> all = ModelCatalog.names();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        view.clear();
        for (String file : all) {
            if (needle.isEmpty()
                    || ModelCatalog.displayName(file).toLowerCase(Locale.ROOT).contains(needle)
                    || file.toLowerCase(Locale.ROOT).contains(needle)) {
                view.add(file);
            }
        }
        int pages = pageCount();
        if (page > pages - 1) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }
    }

    private int pageCount() {
        return Math.max(1, (view.size() + PER_PAGE - 1) / PER_PAGE);
    }

    private String selectedFile() {
        CustomModelModule module = CustomModelModule.INSTANCE;
        return module == null ? "" : module.model.get();
    }

    // ------------------------------------------------------------------ 绘制

    private void paint(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        Ef.fill(ctx, 0, 0, this.width, this.height, VEIL);
        updateHover(mouseX, mouseY);
        int px = panelX;
        int py = panelY;
        // 面板与右栏都切同一个角：只切外层的话，右栏那块矩形会把切角重新填满
        Ef.cutFill(ctx, px, py, PANEL_W, PANEL_H, pal.paper(), Ef.CUT_PANEL, Ef.Corner.BR);
        Ef.fill(ctx, px, py, LEFT_W, PANEL_H, pal.panel());
        Ef.cutFill(ctx, px + RIGHT_X, py, RIGHT_W, PANEL_H, pal.panel(), Ef.CUT_PANEL, Ef.Corner.BR);
        paintPreview(ctx, px, py);
        paintToolbar(ctx, px, py);
        paintCards(ctx, px, py);
        paintPager(ctx, px, py);
        paintFooter(ctx, px, py);
    }

    private void paintPreview(VDrawContext ctx, int px, int py) {
        int sx = px + SLOT_X;
        int sy = py + SLOT_Y;
        Ef.fill(ctx, sx, sy, SLOT_W, SLOT_H, pal.chip());
        hatch(ctx, sx, sy, SLOT_W, SLOT_H, 0x0CFFFFFF);
        // 四角只画两个对角短线：预览位不是可点件，用直角框会和按钮的切角语言打架
        cornerMark(ctx, sx + 3, sy + 3, 7, pal.rule(), true);
        cornerMark(ctx, sx + SLOT_W - 10, sy + SLOT_H - 10, 7, pal.rule(), false);

        paintModel(ctx, sx, sy + STAGE_TOP, SLOT_W, STAGE_H);

        boolean hot = hovered == HOVER_GEAR;
        float wipe = hot ? Ef.mech(Ef.progress(hoverStart, WIPE_MS)) : 0.0F;
        int gx = px + GEAR_X;
        int gy = py + GEAR_Y;
        Ef.wipeCut(ctx, gx, gy, GEAR_SIZE, GEAR_SIZE, pal.chip(), Ef.SIGNAL, wipe, Ef.CUT, Ef.Corner.BR);
        gearGlyph(ctx, gx, gy, GEAR_SIZE, hot ? Ef.ON_SIGNAL : pal.ink2());

        String file = selectedFile();
        String name = file.isEmpty() ? "未选择模型" : ModelCatalog.displayName(file);
        List<String> lines = Ef.wrap(name, LEFT_W - 8);
        int nameY = py + 194;
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            String line = lines.get(i);
            Ef.text(ctx, line, px + (LEFT_W - Ef.textWidth(line)) / 2, nameY, pal.ink());
            nameY += Ef.lineH();
        }
        String field = fieldLine(file);
        Ef.text(ctx, field, px + (LEFT_W - Ef.textWidth(field)) / 2, py + 209, pal.ink2());
    }

    /**
     * 展示位下方的字段行：编号 + 骨骼数 + 动作数。
     *
     * <p>骨骼与动作只有解析过的模型才知道，所以只对「当前已加载的那个」给数字，其余写加载中 ——
     * 编一个数字出来比留空更糟。
     */
    private String fieldLine(String file) {
        int index = view.indexOf(file);
        String no = "No." + (index < 0 ? "--" : String.format(Locale.ROOT, "%02d", index + 1));
        ModelRuntime runtime = CustomModelManager.runtime();
        if (runtime == null || !file.equals(CustomModelManager.loadedFile())) {
            return no + " │ 加载中";
        }
        return no + " │ " + runtime.boneCount() + " 骨 │ " + runtime.extras().size() + " 动作";
    }

    private void paintToolbar(VDrawContext ctx, int px, int py) {
        boolean hotTex = hovered == HOVER_TEX;
        float wipeTex = hotTex ? Ef.mech(Ef.progress(hoverStart, WIPE_MS)) : 0.0F;
        int tx = px + TEX_X;
        int ty = py + TEX_Y;
        Ef.wipeCut(ctx, tx, ty, TEX_W, TEX_H, pal.chip(), Ef.SIGNAL, wipeTex, Ef.CUT, Ef.Corner.BR);
        int ink = hotTex ? Ef.ON_SIGNAL : pal.ink2();
        Ef.fill(ctx, tx + 6, ty + 6, 8, 8, hotTex ? Ef.ON_SIGNAL : Ef.SIGNAL);
        Ef.text(ctx, "贴图", tx + 18, ty + (TEX_H - Ef.lineH()) / 2, ink);

        int sx = px + SEARCH_X;
        int sy = py + SEARCH_Y;
        // 框体自己画，字交给 EditBox：占位提示也由它画，这里不再补一遍
        Ef.cutFill(ctx, sx, sy, SEARCH_W, SEARCH_H, pal.chip(), Ef.CUT, Ef.Corner.BR);

        // 单字标签：18px 的方块放不下两个字，图标资源又没有现成的，用汉字最稳
        String[] labels = {"收", "权", "全"};
        for (int i = 0; i < CAT_X.length; i++) {
            int cx = px + CAT_X[i];
            int cy = py + CAT_Y;
            boolean on = i == 2;
            boolean hot = hovered == HOVER_CAT + i;
            float wipe = hot ? Ef.mech(Ef.progress(hoverStart, WIPE_MS)) : 0.0F;
            int base = on ? Ef.SIGNAL : pal.chip();
            if (hot && !on) {
                Ef.wipeCut(ctx, cx, cy, CAT_SIZE, CAT_SIZE, base, Ef.SIGNAL, wipe, Ef.CUT, Ef.Corner.BR);
            } else {
                Ef.cutFill(ctx, cx, cy, CAT_SIZE, CAT_SIZE, base, Ef.CUT, Ef.Corner.BR);
            }
            int catInk = on || hot ? Ef.ON_SIGNAL : 0xFF5C5C5C;
            Ef.text(ctx, labels[i], cx + (CAT_SIZE - Ef.textWidth(labels[i])) / 2,
                    cy + (CAT_SIZE - Ef.lineH()) / 2, catInk);
        }
    }

    private void paintCards(VDrawContext ctx, int px, int py) {
        if (view.isEmpty()) {
            String empty = "没有匹配的模型，换个关键词试试。";
            Ef.text(ctx, empty, px + RIGHT_X + (RIGHT_W - Ef.textWidth(empty)) / 2, py + 100, pal.ink2());
            return;
        }
        String selected = selectedFile();
        int base = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int index = base + i;
            if (index >= view.size()) {
                break;
            }
            String file = view.get(index);
            int cx = px + GRID_X + (i % CARD_COLS) * (CARD_W + CARD_GAP_X);
            int cy = py + GRID_Y + (i / CARD_COLS) * (CARD_H + CARD_GAP_Y);
            boolean isSelected = file.equals(selected);
            boolean hot = hovered == HOVER_CARD + i;
            float wipe = hot ? Ef.mech(Ef.progress(hoverStart, WIPE_MS)) : 0.0F;
            int cardBg = isSelected || hot ? 0xFF333333 : pal.chip();
            if (hot && !isSelected) {
                Ef.wipeCut(ctx, cx, cy, CARD_W, CARD_H, cardBg, Ef.alpha(Ef.SIGNAL, 0.16F), wipe,
                        Ef.CUT, Ef.Corner.BR);
            } else {
                Ef.cutFill(ctx, cx, cy, CARD_W, CARD_H, cardBg, Ef.CUT, Ef.Corner.BR);
            }
            // 编号标签：选中时整块强调色，一眼看出当前模型
            String no = String.format(Locale.ROOT, "%02d", index + 1);
            int tagW = Ef.textWidth(no) + 4;
            Ef.cutFill(ctx, cx, cy, tagW, 11, isSelected ? Ef.SIGNAL : pal.tagBg(), 2, Ef.Corner.BR);
            Ef.text(ctx, no, cx + 2, cy + 1, isSelected ? Ef.ON_SIGNAL : pal.tagFg());

            int slotY = cy + 11;
            int slotH = CARD_H - 11 - 20;
            Ef.fill(ctx, cx, slotY, CARD_W, slotH, isSelected ? 0xFF3A3A3A : 0xFF2A2A2A);
            hatch(ctx, cx, slotY, CARD_W, slotH, 0x0AFFFFFF);

            Ef.rule(ctx, cx, cy + CARD_H - 20, CARD_W, pal.rule());
            List<String> lines = Ef.wrap(ModelCatalog.displayName(file), CARD_W - 6);
            int nameY = cy + CARD_H - 18;
            for (int line = 0; line < 2 && line < lines.size(); line++) {
                String text = lines.get(line);
                if (line == 1 && lines.size() > 2) {
                    text = Ef.ellipsis(text + lines.get(2), CARD_W - 6);
                }
                Ef.text(ctx, text, cx + 3, nameY, pal.ink());
                nameY += Ef.lineH();
            }
            if (isSelected) {
                Ef.fill(ctx, cx, cy, 2, CARD_H, Ef.SIGNAL);
            }
        }
    }

    private void paintPager(VDrawContext ctx, int px, int py) {
        int pages = pageCount();
        // 箭头用 ASCII：界面字体不一定带 ◀ ▶ 这类符号，缺字会画成方块
        paintPagerButton(ctx, px + PREV_X, py + PG_Y, "< 上一页", page > 0, hovered == HOVER_PREV);
        paintPagerButton(ctx, px + NEXT_X, py + PG_Y, "下一页 >", page < pages - 1, hovered == HOVER_NEXT);
        String text = (page + 1) + " / " + pages;
        Ef.text(ctx, text, px + PAGE_NUM_X + (PAGE_NUM_W - Ef.textWidth(text)) / 2,
                py + PG_Y + (PG_H - Ef.lineH()) / 2, pal.ink2());
    }

    private void paintPagerButton(VDrawContext ctx, int x, int y, String label, boolean enabled, boolean hot) {
        float wipe = hot && enabled ? Ef.mech(Ef.progress(hoverStart, WIPE_MS)) : 0.0F;
        if (enabled && hot) {
            Ef.wipeCut(ctx, x, y, PG_W, PG_H, pal.chip(), Ef.SIGNAL, wipe, Ef.CUT, Ef.Corner.BR);
        } else {
            Ef.cutFill(ctx, x, y, PG_W, PG_H, pal.chip(), Ef.CUT, Ef.Corner.BR);
        }
        int ink = !enabled ? 0xFF5C5C5C : (hot ? Ef.ON_SIGNAL : pal.ink());
        Ef.text(ctx, label, x + (PG_W - Ef.textWidth(label)) / 2, y + (PG_H - Ef.lineH()) / 2, ink);
    }

    private void paintFooter(VDrawContext ctx, int px, int py) {
        String count = view.size() + " 个模型";
        Ef.text(ctx, count, px + 2, py + PANEL_H - 9, 0xFF5C5C5C);
    }

    // ------------------------------------------------------------------ 3D 预览

    /**
     * 用原版背包界面的实体预览画当前模型。
     *
     * <p>鼠标坐标传矩形中心，角度就恒为 0，模型正面朝前、不跟鼠标转 —— 选择页要的是「看清这个模型」，
     * 不是「转着玩」。这条路径走 {@code GuiGraphicsExtractor#entity}，最终由
     * {@code EntityRenderDispatcher#submit} 出图，自定义模型的 mixin 正好挂在那里，所以画出来就是
     * 当前加载的模型本身，不需要另写一套渲染。
     */
    private void paintModel(VDrawContext ctx, int x, int y, int w, int h) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        GuiGraphicsExtractor graphics = extractor(ctx);
        if (graphics == null) {
            return;
        }
        int size = Math.max(8, Math.round(h * 0.45F));
        int centerX = x + w / 2;
        int centerY = y + h / 2;
        InventoryScreen.extractEntityInInventoryFollowsMouse(
                graphics, x, y, x + w, y + h, size, 0.0F, centerX, centerY, minecraft.player);
    }

    /**
     * 取底层 {@link GuiGraphicsExtractor}：{@link VDrawContext} 没有暴露实例的入口，
     * 而 3D 预览只能走它。push 之后立刻 pop，矩阵栈净变化为零。
     */
    private static GuiGraphicsExtractor extractor(VDrawContext ctx) {
        GuiGraphicsExtractor graphics = ctx.pushMatrix();
        ctx.popMatrix();
        return graphics;
    }

    // ------------------------------------------------------------------ 装饰

    /**
     * 空槽位的底纹：每 6px 一道极淡的横线。
     *
     * <p>预览里是 45° 斜线，这里换成横线 —— 斜线要么逐像素 fill（一块 125×171 的槽位要几千次），
     * 要么走 {@code lineGui} 的调试线管线（26.2 上实测画不出像素），横线用普通 fill 就够，
     * 观感上同样是「这里没有内容」。
     */
    private static void hatch(VDrawContext ctx, int x, int y, int w, int h, int color) {
        for (int row = y + 3; row < y + h; row += 6) {
            Ef.fill(ctx, x, row, w, 1, color);
        }
    }

    /** 预览位的对角角标：只画两条边，像取景框而不是卡片边框。 */
    private static void cornerMark(VDrawContext ctx, int x, int y, int size, int color, boolean topLeft) {
        if (topLeft) {
            Ef.fill(ctx, x, y, size, 1, color);
            Ef.fill(ctx, x, y, 1, size, color);
        } else {
            Ef.fill(ctx, x, y + size - 1, size, 1, color);
            Ef.fill(ctx, x + size - 1, y, 1, size, color);
        }
    }

    /** 齿轮：中心圆 + 八根辐条，纯 fill 拼出来，不依赖图标资源。 */
    private static void gearGlyph(VDrawContext ctx, int x, int y, int size, int color) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        Ef.fill(ctx, cx - 2, cy - 2, 4, 4, color);
        Ef.fill(ctx, cx - 1, cy - 5, 2, 3, color);
        Ef.fill(ctx, cx - 1, cy + 2, 2, 3, color);
        Ef.fill(ctx, cx - 5, cy - 1, 3, 2, color);
        Ef.fill(ctx, cx + 2, cy - 1, 3, 2, color);
        Ef.fill(ctx, cx - 4, cy - 4, 2, 2, color);
        Ef.fill(ctx, cx + 2, cy - 4, 2, 2, color);
        Ef.fill(ctx, cx - 4, cy + 2, 2, 2, color);
        Ef.fill(ctx, cx + 2, cy + 2, 2, 2, color);
    }

    // ------------------------------------------------------------------ 交互

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private int cardAt(int mx, int my) {
        int base = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int index = base + i;
            if (index >= view.size()) {
                break;
            }
            int x = GRID_X + (i % CARD_COLS) * (CARD_W + CARD_GAP_X);
            int y = GRID_Y + (i / CARD_COLS) * (CARD_H + CARD_GAP_Y);
            if (inside(mx, my, x, y, CARD_W, CARD_H)) {
                return index;
            }
        }
        return -1;
    }

    private void updateHover(double mouseX, double mouseY) {
        int mx = (int) Math.floor(mouseX) - panelX;
        int my = (int) Math.floor(mouseY) - panelY;
        int next = HOVER_NONE;
        if (mx >= 0 && my >= 0 && mx < PANEL_W && my < PANEL_H) {
            if (inside(mx, my, GEAR_X, GEAR_Y, GEAR_SIZE, GEAR_SIZE)) {
                next = HOVER_GEAR;
            } else if (inside(mx, my, TEX_X, TEX_Y, TEX_W, TEX_H)) {
                next = HOVER_TEX;
            } else if (inside(mx, my, PREV_X, PG_Y, PG_W, PG_H)) {
                next = HOVER_PREV;
            } else if (inside(mx, my, NEXT_X, PG_Y, PG_W, PG_H)) {
                next = HOVER_NEXT;
            } else {
                for (int i = 0; i < CAT_X.length; i++) {
                    if (inside(mx, my, CAT_X[i], CAT_Y, CAT_SIZE, CAT_SIZE)) {
                        next = HOVER_CAT + i;
                        break;
                    }
                }
                if (next == HOVER_NONE) {
                    int card = cardAt(mx, my);
                    if (card >= 0) {
                        next = HOVER_CARD + (card - page * PER_PAGE);
                    }
                }
            }
        }
        if (next != hovered) {
            this.hovered = next;
            this.hoverStart = System.currentTimeMillis();
        }
    }

    private boolean click(EfWidget self, double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        int mx = (int) Math.floor(mouseX) - panelX;
        int my = (int) Math.floor(mouseY) - panelY;
        if (mx < 0 || my < 0 || mx >= PANEL_W || my >= PANEL_H) {
            return false;
        }
        // 搜索框是原版控件、排在铺满屏幕的这块之后：这里必须放行，否则它永远收不到点击
        if (inside(mx, my, SEARCH_X, SEARCH_Y, SEARCH_W, SEARCH_H)) {
            return false;
        }
        if (inside(mx, my, GEAR_X, GEAR_Y, GEAR_SIZE, GEAR_SIZE)) {
            ActionPreviewScreen.open();
            return true;
        }
        if (inside(mx, my, TEX_X, TEX_Y, TEX_W, TEX_H)) {
            cycleTexture();
            return true;
        }
        if (inside(mx, my, PREV_X, PG_Y, PG_W, PG_H)) {
            turnPage(-1);
            return true;
        }
        if (inside(mx, my, NEXT_X, PG_Y, PG_W, PG_H)) {
            turnPage(1);
            return true;
        }
        int card = cardAt(mx, my);
        if (card >= 0) {
            select(view.get(card));
            return true;
        }
        return false;
    }

    /** 点卡片：只切模型，不跳页。写配置会触发异步重载，展示位跟着换成新模型。 */
    private void select(String file) {
        CustomModelModule module = CustomModelModule.INSTANCE;
        if (module == null || file == null || file.isEmpty() || file.equals(module.model.get())) {
            return;
        }
        module.model.set(file);
    }

    /** 贴图按钮：在模型自带的贴图之间循环；只有一个贴图时什么都不做。 */
    private void cycleTexture() {
        List<String> names = CustomModelManager.textureNames();
        if (names.size() <= 1) {
            return;
        }
        String active = CustomModelManager.activeTexture();
        int index = names.indexOf(active);
        CustomModelManager.selectTexture(names.get((index + 1) % names.size()));
    }

    private void turnPage(int step) {
        int pages = pageCount();
        this.page = Math.max(0, Math.min(pages - 1, this.page + step));
    }

    private boolean wheel(EfWidget self, double mouseX, double mouseY, double amount) {
        if (pageCount() <= 1 || amount == 0.0D) {
            return false;
        }
        turnPage(amount > 0.0D ? -1 : 1);
        return true;
    }

}
