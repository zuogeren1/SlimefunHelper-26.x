package me.zuogeren.slimefunhelperplus.module.custommodel.ui;

import java.util.List;
import java.util.Locale;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.GenericScreen;
import me.matl114.utils.ClientUtils;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelManager;
import me.zuogeren.slimefunhelperplus.module.custommodel.ModelCatalog;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ModelRuntime;
import me.zuogeren.slimefunhelperplus.module.custommodel.ui.Ef;
import me.zuogeren.slimefunhelperplus.module.custommodel.ui.EfWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/**
 * 动作预览页：左边一块大预览，右边一列动作，点动作即切换。
 *
 * <p>版式照用户确认过的可交互预览（{@code .notes/refs/image/ysm/preview.html}）：面板 420×235、
 * 左栏 262、右栏 155、动作 8 条一页、返回按钮 145×22。
 *
 * <p>从模型选择页的齿轮进来，返回（按钮或 Esc）回到模型选择页 —— 父屏由
 * {@link ScreenAccess#openFromCurrent()} 记下，所以这里只管调 {@code onClose()}。
 *
 * <p>动作列表直接取 {@link ModelRuntime#extras()}：服装预设与额外动作都在里面。动作点一下从头播一次、
 * 播完停在末帧（{@link ModelRuntime#playExtraOnce(String)}）；服装预设是静态覆盖层，点一下切换。
 *
 * <p>预览跑在 {@link CustomModelManager#beginPreview()} 建出来的独立实例上：世界里的玩家有自己的
 * 物理与控制器状态，预览里播动作不会推进它，关掉页面也不会留下选择。
 */
public class ActionPreviewScreen extends GenericScreen {

    private static final int PANEL_W = 420;
    private static final int PANEL_H = 235;
    private static final int LEFT_W = 262;
    private static final int RIGHT_X = 265;
    private static final int RIGHT_W = 155;

    private static final int STAGE_X = 5;
    private static final int STAGE_Y = 5;
    private static final int STAGE_W = 255;
    private static final int STAGE_H = 225;
    /** 3D 只占舞台中段：PiP 出图盖在所有 2D 之上，面包屑、标题、时间轴必须留在它外面。 */
    private static final int FIG_TOP = 26;
    private static final int FIG_H = 147;

    private static final int NAV_SIZE = 30;
    private static final int PREV_X = 265;
    private static final int NEXT_X = 380;
    private static final int NAV_Y = 8;
    private static final int PAGE_X = 297;
    private static final int PAGE_Y = 23;
    private static final int PAGE_W = 81;
    private static final int PAGE_H = 15;
    private static final int BACK_X = 265;
    private static final int BACK_Y = 40;
    private static final int BACK_W = 145;
    private static final int BACK_H = 22;
    /** 分类切换条：返回按钮下方一行三个标签。 */
    private static final int TAB_Y = 66;
    private static final int TAB_H = 14;
    private static final int TAB_W = 47;
    private static final int LIST_X = 265;
    private static final int LIST_Y = 84;
    private static final int LIST_W = 145;
    private static final int ROW_H = 18;
    private static final int ROW_STEP = 20;
    /** 每页动作数：分类切换条占掉一行后，面板只剩 151px，7 行刚好不溢出。 */
    private static final int ACT_PER_PAGE = 7;

    private static final float WIPE_MS = 260.0F;

    /** 面板之外的压暗：世界还看得见，但不会和面板抢注意力。 */
    private static final int VEIL = 0x40000000;

    private static final int HOVER_NONE = 0;
    private static final int HOVER_PREV = 1;
    private static final int HOVER_NEXT = 2;
    private static final int HOVER_BACK = 3;
    private static final int HOVER_TAB = 4;
    /** 分类切换条的顺序，与 {@link #paintTabs} 的标签一一对应。 */
    private static final ModelRuntime.Category[] TAB_CATEGORIES = {
            ModelRuntime.Category.MAIN, ModelRuntime.Category.EXTRA, ModelRuntime.Category.VARIANT
    };
    private static final int HOVER_ROW = 10;

    private final Ef.Palette pal = Ef.DARK;

    private int panelX;
    private int panelY;
    private int page;
    /** 当前分类筛选；{@code null} 表示全部。 */
    private ModelRuntime.Category category = ModelRuntime.Category.MAIN;
    private int hovered = HOVER_NONE;
    private long hoverStart = System.currentTimeMillis();

    public ActionPreviewScreen() {
        super(Component.translatable("custom-model.preview.title"), 0, 0);
    }

    /** 不要求玩家实体存在：没有它只是少了 3D 预览，动作列表照常可用。 */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (ClientUtils.getScreen() instanceof ActionPreviewScreen) {
            return;
        }
        ScreenAccess.of(new ActionPreviewScreen()).openFromCurrent();
    }

    @Override
    protected void init0() {
        super.init0();
        this.x = 0;
        this.y = 0;
        this.panelX = Math.max(0, (this.width - PANEL_W) / 2);
        this.panelY = Math.max(0, (this.height - PANEL_H) / 2);
        CustomModelManager.beginPreview();
    }

    /** 关页时丢掉预览实例：世界实例的状态自始至终没被推进过，不需要还原。 */
    @Override
    public void removed() {
        CustomModelManager.endPreview();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 世界留在背后：预览动作时要看得见角色本身，压暗交给 {@link #paint} 里那层 VEIL。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {}

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        EfWidget.of(0, 0, this.width, this.height, this::paint)
                .click(this::click)
                .scroll(this::wheel)
                .addTo(this);
    }

    /**
     * 当前分类下的动作列表。
     *
     * <p>主体态动作（idle/walk/run/death…）与轮盘额外动作分开列：语料实测每个模型有 22~26 条
     * 主体态动作，混在一起会把轮盘动作挤到后面几页。
     */
    private List<ModelRuntime.ExtraOption> options() {
        ModelRuntime runtime = CustomModelManager.runtime();
        if (runtime == null) {
            return List.of();
        }
        List<ModelRuntime.ExtraOption> all = runtime.extras();
        if (category == null) {
            return all;
        }
        List<ModelRuntime.ExtraOption> filtered = new java.util.ArrayList<>();
        for (ModelRuntime.ExtraOption option : all) {
            if (option.category() == category) {
                filtered.add(option);
            }
        }
        return filtered;
    }

    /** 切换分类；页码归零，否则会停在超出范围的页。 */
    private void setCategory(ModelRuntime.Category next) {
        if (category == next) {
            return;
        }
        category = next;
        page = 0;
    }

    private int pageCount() {
        return Math.max(1, (options().size() + ACT_PER_PAGE - 1) / ACT_PER_PAGE);
    }

    // ------------------------------------------------------------------ 绘制

    private void paint(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        // 模型可能在页面开着时被换掉（配置重载），预览实例跟着重建，否则预览会停在旧模型上
        if (!CustomModelManager.previewActive()) {
            CustomModelManager.beginPreview();
        }
        Ef.fill(ctx, 0, 0, this.width, this.height, VEIL);
        updateHover(mouseX, mouseY);
        int px = panelX;
        int py = panelY;
        Ef.cutFill(ctx, px, py, PANEL_W, PANEL_H, pal.paper(), pal.cutPanel(), Ef.Corner.BR);
        Ef.fill(ctx, px, py, LEFT_W, PANEL_H, pal.panel());
        Ef.cutFill(ctx, px + RIGHT_X, py, RIGHT_W, PANEL_H, pal.panel(), pal.cutPanel(), Ef.Corner.BR);
        paintStage(ctx, px, py);
        paintNav(ctx, px, py);
        paintTabs(ctx, px, py);
        paintList(ctx, px, py);
    }

    private void paintStage(VDrawContext ctx, int px, int py) {
        int sx = px + STAGE_X;
        int sy = py + STAGE_Y;
        Ef.fill(ctx, sx, sy, STAGE_W, STAGE_H, pal.chip());
        hatch(ctx, sx, sy, STAGE_W, STAGE_H, 0x0AFFFFFF);
        cornerMark(ctx, sx + 4, sy + 4, 8, pal.rule(), true);
        cornerMark(ctx, sx + STAGE_W - 12, sy + STAGE_H - 12, 8, pal.rule(), false);

        paintModel(ctx, sx, sy + FIG_TOP, STAGE_W, FIG_H);

        String file = CustomModelManager.loadedFile();
        String name = file.isEmpty() ? "未加载模型" : ModelCatalog.displayName(file);
        Ef.text(ctx, Ef.ellipsis(name, 200), sx + 8, sy + 7, pal.ink());

        ModelRuntime runtime = CustomModelManager.runtime();
        String badge = runtime == null ? "-" : "单次";
        Ef.fill(ctx, sx + STAGE_W - 8 - 3, sy + 10, 3, 3, pal.signal());
        Ef.text(ctx, badge, sx + STAGE_W - 8 - 3 - 4 - Ef.textWidth(badge), sy + 7, pal.ink2());

        String current = currentLabel(runtime);
        Ef.text(ctx, Ef.ellipsis(current, STAGE_W - 16), sx + 8, sy + STAGE_H - 26, pal.ink());
        String stats = runtime == null ? ""
                : Component.translatable("custom-model.preview.stats", runtime.boneCount(), runtime.extras().size()).getString();
        Ef.text(ctx, stats, sx + STAGE_W - 8 - Ef.textWidth(stats), sy + STAGE_H - 26, pal.ink2());
        Ef.fill(ctx, sx + 8, sy + STAGE_H - 14, STAGE_W - 16, 3, 0xFF3A3A3A);
    }

    private String currentLabel(ModelRuntime runtime) {
        if (runtime == null) {
            return "模型未加载";
        }
        String variant = labelOf(runtime, runtime.activeVariantKey());
        String extra = labelOf(runtime, runtime.activeExtraKey());
        String head = variant.isEmpty() ? "默认服装" : variant;
        return extra.isEmpty() ? head : head + " · " + extra;
    }

    private String labelOf(ModelRuntime runtime, String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        for (ModelRuntime.ExtraOption option : runtime.extras()) {
            if (option.key().equals(key)) {
                return option.label();
            }
        }
        return key;
    }

    private void paintNav(VDrawContext ctx, int px, int py) {
        int pages = pageCount();
        // 箭头用 ASCII：界面字体不一定带 ◀ ▶ 这类符号，缺字会画成方块
        paintNavButton(ctx, px + PREV_X, py + NAV_Y, "<", page > 0, hovered == HOVER_PREV);
        paintNavButton(ctx, px + NEXT_X, py + NAV_Y, ">", page < pages - 1, hovered == HOVER_NEXT);
        String text = (page + 1) + " / " + pages;
        Ef.fill(ctx, px + PAGE_X, py + PAGE_Y, PAGE_W, PAGE_H, 0x48000000);
        Ef.text(ctx, text, px + PAGE_X + (PAGE_W - Ef.textWidth(text)) / 2,
                py + PAGE_Y + (PAGE_H - Ef.lineH()) / 2, pal.ink2());

        boolean hot = hovered == HOVER_BACK;
        float wipe = hot ? Ef.mech(Ef.progress(hoverStart, WIPE_MS)) : 0.0F;
        Ef.wipeCut(ctx, px + BACK_X, py + BACK_Y, BACK_W, BACK_H, pal.chip(), pal.signal(), wipe,
                pal.cutWide(), Ef.Corner.BR);
        String label = "< 返回";
        Ef.text(ctx, label, px + BACK_X + (BACK_W - Ef.textWidth(label)) / 2,
                py + BACK_Y + (BACK_H - Ef.lineH()) / 2, hot ? pal.onSignal() : pal.ink());
    }

    private void paintNavButton(VDrawContext ctx, int x, int y, String glyph, boolean enabled, boolean hot) {
        float wipe = hot && enabled ? Ef.mech(Ef.progress(hoverStart, WIPE_MS)) : 0.0F;
        if (enabled && hot) {
            Ef.wipeCut(ctx, x, y, NAV_SIZE, NAV_SIZE, pal.chip(), pal.signal(), wipe, pal.cut(), Ef.Corner.BR);
        } else {
            Ef.cutFill(ctx, x, y, NAV_SIZE, NAV_SIZE, pal.chip(), pal.cut(), Ef.Corner.BR);
        }
        int ink = !enabled ? 0xFF5C5C5C : (hot ? pal.onSignal() : pal.ink());
        Ef.text(ctx, glyph, x + (NAV_SIZE - Ef.textWidth(glyph)) / 2,
                y + (NAV_SIZE - Ef.lineH()) / 2, ink);
    }

    /**
     * 分类切换：主体动作 / 轮盘动作 / 服装。
     *
     * <p>语料实测每个模型有 22~26 条主体态动作（idle/walk/run/death…），
     * 与轮盘动作混在一列会把后者挤到后面几页，所以分开列。
     */
    private void paintTabs(VDrawContext ctx, int px, int py) {
        ModelRuntime.Category[] cats = {
                ModelRuntime.Category.MAIN, ModelRuntime.Category.EXTRA, ModelRuntime.Category.VARIANT
        };
        String[] labels = {
                Component.translatable("custom-model.preview.tab.main").getString(),
                Component.translatable("custom-model.preview.tab.extra").getString(),
                Component.translatable("custom-model.preview.tab.variant").getString()
        };
        for (int i = 0; i < cats.length; i++) {
            int tx = px + LIST_X + i * (TAB_W + 2);
            int ty = py + TAB_Y;
            boolean active = category == cats[i];
            boolean hot = hovered == HOVER_TAB + i;
            if (active) {
                Ef.cutFill(ctx, tx, ty, TAB_W, TAB_H, pal.signal(), 2, Ef.Corner.BR);
            } else if (hot) {
                Ef.cutFill(ctx, tx, ty, TAB_W, TAB_H, pal.chip(), 2, Ef.Corner.BR);
            } else {
                Ef.cutFill(ctx, tx, ty, TAB_W, TAB_H, pal.panel(), 2, Ef.Corner.BR);
            }
            int ink = active ? pal.onSignal() : (hot ? pal.ink() : pal.ink2());
            Ef.text(ctx, labels[i], tx + (TAB_W - Ef.textWidth(labels[i])) / 2,
                    ty + (TAB_H - Ef.lineH()) / 2, ink);
        }
    }

    private void paintList(VDrawContext ctx, int px, int py) {
        List<ModelRuntime.ExtraOption> list = options();
        if (list.isEmpty()) {
            String empty = Component.translatable("custom-model.preview.empty").getString();
            Ef.text(ctx, empty, px + LIST_X + (LIST_W - Ef.textWidth(empty)) / 2, py + 120, pal.ink2());
            return;
        }
        ModelRuntime runtime = CustomModelManager.runtime();
        int base = page * ACT_PER_PAGE;
        for (int i = 0; i < ACT_PER_PAGE; i++) {
            int index = base + i;
            if (index >= list.size()) {
                break;
            }
            ModelRuntime.ExtraOption option = list.get(index);
            int rx = px + LIST_X;
            int ry = py + LIST_Y + i * ROW_STEP;
            boolean active = isActive(runtime, option);
            boolean hot = hovered == HOVER_ROW + i;
            float wipe = hot ? Ef.mech(Ef.progress(hoverStart, WIPE_MS)) : 0.0F;
            if (active) {
                Ef.cutFill(ctx, rx, ry, LIST_W, ROW_H, pal.signal(), pal.cut(), Ef.Corner.BR);
            } else if (hot) {
                Ef.wipeCut(ctx, rx, ry, LIST_W, ROW_H, pal.chip(), Ef.alpha(pal.signal(), 0.18F), wipe,
                        pal.cut(), Ef.Corner.BR);
            } else {
                Ef.cutFill(ctx, rx, ry, LIST_W, ROW_H, pal.chip(), pal.cut(), Ef.Corner.BR);
            }
            int ink = active ? pal.onSignal() : (hot ? pal.ink() : pal.ink2());
            String no = String.format(Locale.ROOT, "%02d", index + 1);
            Ef.text(ctx, no, rx + 5, ry + (ROW_H - Ef.lineH()) / 2, active ? 0x99161616 : 0xFF6A6A6A);
            String label = Ef.ellipsis(option.label(), LIST_W - 34);
            Ef.text(ctx, label, rx + 20, ry + (ROW_H - Ef.lineH()) / 2, ink);
            if (option.variant()) {
                // 服装预设是静态覆盖层，没有时长；用一个小方块和动作区分开
                Ef.fill(ctx, rx + LIST_W - 8, ry + ROW_H / 2 - 2, 3, 3,
                        active ? pal.onSignal() : pal.signal());
            }
        }
    }

    private boolean isActive(ModelRuntime runtime, ModelRuntime.ExtraOption option) {
        if (runtime == null) {
            return false;
        }
        return option.variant()
                ? option.key().equals(runtime.activeVariantKey())
                : option.key().equals(runtime.activeExtraKey());
    }

    // ------------------------------------------------------------------ 3D 预览

    /** 与模型选择页同一条路径：原版实体预览 + 自定义模型 mixin，画出来就是当前模型。 */
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

    /** 取底层 {@link GuiGraphicsExtractor}：{@link VDrawContext} 没暴露实例入口，push 后立刻 pop 保持栈平衡。 */
    private static GuiGraphicsExtractor extractor(VDrawContext ctx) {
        GuiGraphicsExtractor graphics = ctx.pushMatrix();
        ctx.popMatrix();
        return graphics;
    }

    // ------------------------------------------------------------------ 装饰

    /**
     * 空槽位的底纹：每 6px 一道极淡的横线。
     *
     * <p>预览里是 45° 斜线，这里换成横线 —— 斜线要么逐像素 fill（一块 255×225 的舞台要上万次），
     * 要么走 {@code lineGui} 的调试线管线（26.2 上实测画不出像素），横线用普通 fill 就够，
     * 观感上同样是「这里没有内容」。
     */
    private static void hatch(VDrawContext ctx, int x, int y, int w, int h, int color) {
        for (int row = y + 3; row < y + h; row += 6) {
            Ef.fill(ctx, x, row, w, 1, color);
        }
    }

    private static void cornerMark(VDrawContext ctx, int x, int y, int size, int color, boolean topLeft) {
        if (topLeft) {
            Ef.fill(ctx, x, y, size, 1, color);
            Ef.fill(ctx, x, y, 1, size, color);
        } else {
            Ef.fill(ctx, x, y + size - 1, size, 1, color);
            Ef.fill(ctx, x + size - 1, y, 1, size, color);
        }
    }

    // ------------------------------------------------------------------ 交互

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private int rowAt(int mx, int my) {
        int base = page * ACT_PER_PAGE;
        for (int i = 0; i < ACT_PER_PAGE; i++) {
            if (base + i >= options().size()) {
                break;
            }
            if (inside(mx, my, LIST_X, LIST_Y + i * ROW_STEP, LIST_W, ROW_H)) {
                return base + i;
            }
        }
        return -1;
    }

    private void updateHover(double mouseX, double mouseY) {
        int mx = (int) Math.floor(mouseX) - panelX;
        int my = (int) Math.floor(mouseY) - panelY;
        int next = HOVER_NONE;
        if (mx >= 0 && my >= 0 && mx < PANEL_W && my < PANEL_H) {
            if (inside(mx, my, PREV_X, NAV_Y, NAV_SIZE, NAV_SIZE)) {
                next = HOVER_PREV;
            } else if (inside(mx, my, NEXT_X, NAV_Y, NAV_SIZE, NAV_SIZE)) {
                next = HOVER_NEXT;
            } else if (inside(mx, my, BACK_X, BACK_Y, BACK_W, BACK_H)) {
                next = HOVER_BACK;
            } else if (tabAt(mx, my) >= 0) {
                next = HOVER_TAB + tabAt(mx, my);
            } else {
                int row = rowAt(mx, my);
                if (row >= 0) {
                    next = HOVER_ROW + (row - page * ACT_PER_PAGE);
                }
            }
        }
        if (next != hovered) {
            this.hovered = next;
            this.hoverStart = System.currentTimeMillis();
        }
    }

    /** 分类切换条的下标；不在条上返回 -1。 */
    private int tabAt(int mx, int my) {
        if (my < TAB_Y || my >= TAB_Y + TAB_H) {
            return -1;
        }
        for (int i = 0; i < TAB_CATEGORIES.length; i++) {
            int tx = LIST_X + i * (TAB_W + 2);
            if (mx >= tx && mx < tx + TAB_W) {
                return i;
            }
        }
        return -1;
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
        if (inside(mx, my, BACK_X, BACK_Y, BACK_W, BACK_H)) {
            super.onClose();
            return true;
        }
        if (inside(mx, my, PREV_X, NAV_Y, NAV_SIZE, NAV_SIZE)) {
            turnPage(-1);
            return true;
        }
        if (inside(mx, my, NEXT_X, NAV_Y, NAV_SIZE, NAV_SIZE)) {
            turnPage(1);
            return true;
        }
        int tab = tabAt(mx, my);
        if (tab >= 0) {
            setCategory(TAB_CATEGORIES[tab]);
            return true;
        }
        int row = rowAt(mx, my);
        if (row >= 0) {
            play(options().get(row));
            return true;
        }
        return false;
    }

    /**
     * 点一条动作：在预览实例上从头播一次。
     *
     * <p>预览实例没建起来（模型没加载）时什么都不做 —— 落到世界实例上就等于把预览的选择带进世界。
     */
    private void play(ModelRuntime.ExtraOption option) {
        ModelRuntime runtime = CustomModelManager.runtime();
        if (runtime == null || !CustomModelManager.previewActive()) {
            return;
        }
        if (option.variant()) {
            runtime.setVariant(option.key());
        } else {
            runtime.playExtraOnce(option.key());
        }
    }

    private void turnPage(int step) {
        this.page = Math.max(0, Math.min(pageCount() - 1, this.page + step));
    }

    private boolean wheel(EfWidget self, double mouseX, double mouseY, double amount) {
        if (pageCount() <= 1 || amount == 0.0D) {
            return false;
        }
        turnPage(amount > 0.0D ? -1 : 1);
        return true;
    }

}
