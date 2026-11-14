package me.zuogeren.slimefunhelperplus.module.custommodel.ui;

import java.util.ArrayList;
import java.util.List;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.GenericScreen;
import me.matl114.utils.ClientUtils;
import me.matl114.utils.Debug;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelManager;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ModelRuntime;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.Ef;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EfWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 动画轮盘：8 个选项按圆环排开，中间留空好让角色露出来，滚轮与两侧箭头翻页。
 *
 * <p>版式与交互照 YSM 客户端轮盘移植（槽位夹角、2° 缝隙、悬停时外沿鼓出一圈、
 * 文字块贴着扇区中心），绘制只用本仓库的 {@link Ef} / {@link VDrawContext} 原语：
 * 扇区几何来自 {@link RouletteGeometry}，逐行光栅化与命中判定共用同一块多边形。
 *
 * <p>数据走 {@link ModelRuntime#extras()}：服装预设与额外动作各占一层，点一下切换、再点取消；
 * 结果交给 {@link CustomModelManager#selectExtra(String)}。界面不暂停游戏、不糊背景，
 * 所以选动作时能直接看见角色的变化。
 */
public class ModelRouletteScreen extends GenericScreen {

    /** 顶部两行：眉标与模型名。 */
    private static final int HEADER_H = 34;
    /** 底部一行操作提示。 */
    private static final int FOOTER_H = 18;
    private static final int PAD = 10;

    /** 关掉再打开还停在上次的页；数据变了由 {@link #pageCount()} 夹回来。 */
    private static int rememberedPage;

    private final List<ModelRuntime.ExtraOption> options = new ArrayList<>();
    /** 每个槽位的扇区多边形（屏幕坐标），命中判定用。 */
    private final double[][][] sectors = new double[RouletteTheme.SLOTS][][];

    private int page;
    private float scale = 1.0F;
    private int centerX;
    private int centerY;
    private int hoveredSlot = -1;
    private boolean hoverPrev;
    private boolean hoverNext;
    /** 扇区光栅化结果：每行一段，{slot, y, x0, x1}；窗口尺寸不变就不重算。 */
    private List<int[]> slices = List.of();
    /** 悬停时鼓出来的那一圈，只画当前悬停的格。 */
    private List<int[]> lips = List.of();

    public ModelRouletteScreen() {
        super(Component.literal("模型动画"), 0, 0);
        ModelRuntime runtime = CustomModelManager.runtime();
        if (runtime != null) {
            this.options.addAll(runtime.extras());
        }
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        if (ClientUtils.getScreen() instanceof ModelRouletteScreen) {
            return;
        }
        ScreenAccess.of(new ModelRouletteScreen()).openFromCurrent();
    }

    /**
     * 世界留在背后：轮盘要在选动作时看得见角色。
     * 默认的模糊 + 菜单底会把模型糊掉，所以这一层整块不画，只留 {@link RouletteTheme#VEIL} 的压暗。
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {}

    @Override
    protected void init0() {
        super.init0();
        this.x = 0;
        this.y = 0;
        this.centerX = this.width / 2;
        this.centerY = HEADER_H + (this.height - HEADER_H - FOOTER_H) / 2;
        float byWidth = (this.width - PAD * 2.0F) / RouletteTheme.DESIGN_WIDTH;
        float byHeight = (this.height - HEADER_H - FOOTER_H) / (float) RouletteTheme.DESIGN_HEIGHT;
        this.scale = Math.max(RouletteTheme.MIN_SCALE, Math.min(1.0F, Math.min(byWidth, byHeight)));
        this.page = Math.max(0, Math.min(rememberedPage, pageCount() - 1));
        buildSectors();
        buildSlices();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        Debug.info("[SFH+] 轮盘界面 " + this.width + "x" + this.height + " 项=" + options.size()
                + " scale=" + scale);
        EfWidget.of(0, 0, this.width, this.height, this::paint)
                .click(this::click)
                .scroll(this::wheel)
                .addTo(this);
    }

    private int pageCount() {
        return Math.max(1, (options.size() + RouletteTheme.SLOTS - 1) / RouletteTheme.SLOTS);
    }

    // ------------------------------------------------------------------ 几何

    private void buildSectors() {
        for (int slot = 0; slot < RouletteTheme.SLOTS; slot++) {
            double center = RouletteTheme.FIRST_ANGLE + slot * RouletteTheme.SLOT_ANGLE;
            double start = center - RouletteTheme.SLOT_ANGLE / 2.0D + RouletteTheme.SLOT_GAP;
            double end = center + RouletteTheme.SLOT_ANGLE / 2.0D - RouletteTheme.SLOT_GAP;
            // 命中范围比看得见的环多一圈，鼠标停在鼓出来的那一圈上不会闪
            this.sectors[slot] = RouletteGeometry.sector(centerX, centerY,
                    RouletteTheme.WHEEL_INNER_R * scale, RouletteTheme.WHEEL_HOVER_R * scale, start, end);
        }
    }

    private void buildSlices() {
        List<int[]> base = new ArrayList<>();
        List<int[]> lip = new ArrayList<>();
        for (int slot = 0; slot < RouletteTheme.SLOTS; slot++) {
            double center = RouletteTheme.FIRST_ANGLE + slot * RouletteTheme.SLOT_ANGLE;
            double start = center - RouletteTheme.SLOT_ANGLE / 2.0D + RouletteTheme.SLOT_GAP;
            double end = center + RouletteTheme.SLOT_ANGLE / 2.0D - RouletteTheme.SLOT_GAP;
            rasterize(base, slot, RouletteGeometry.sector(centerX, centerY,
                    RouletteTheme.WHEEL_INNER_R * scale, RouletteTheme.WHEEL_OUTER_R * scale, start, end));
            rasterize(lip, slot, RouletteGeometry.sector(centerX, centerY,
                    RouletteTheme.WHEEL_OUTER_R * scale, RouletteTheme.WHEEL_HOVER_R * scale, start, end));
        }
        this.slices = base;
        this.lips = lip;
    }

    private void rasterize(List<int[]> out, int slot, double[][] polygon) {
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (double[] point : polygon) {
            top = Math.min(top, (int) Math.floor(point[1]));
            bottom = Math.max(bottom, (int) Math.ceil(point[1]));
        }
        for (int y = top; y <= bottom; y++) {
            int[] span = RouletteGeometry.span(polygon, y);
            if (span != null && span[1] > span[0]) {
                out.add(new int[]{slot, y, span[0], span[1]});
            }
        }
    }

    // ------------------------------------------------------------------ 绘制

    private void paint(EfWidget self, VDrawContext ctx, int mouseX, int mouseY) {
        Ef.fill(ctx, 0, 0, this.width, this.height, RouletteTheme.VEIL);
        // 顶部与底部各压一道淡渐变：眉标和操作提示压在世界上也读得清
        ctx.fillGuiGradient(0, 0, this.width, HEADER_H + 6, 0x90000000, 0x00000000, 0);
        ctx.fillGuiGradient(0, this.height - FOOTER_H - 6, this.width, this.height, 0x00000000, 0x90000000, 0);
        updateHover(mouseX, mouseY);
        ModelRuntime runtime = CustomModelManager.runtime();
        paintSlices(ctx, runtime);
        paintLabels(ctx, runtime);
        paintArrows(ctx);
        paintPageText(ctx);
        paintHeader(ctx, runtime);
        paintFooter(ctx);
        if (options.isEmpty()) {
            paintEmpty(ctx);
        }
    }

    private void paintSlices(VDrawContext ctx, ModelRuntime runtime) {
        int base = page * RouletteTheme.SLOTS;
        for (int[] slice : slices) {
            int index = base + slice[0];
            if (index >= options.size()) {
                continue;
            }
            boolean active = isActive(runtime, index);
            boolean hot = slice[0] == hoveredSlot;
            int color = RouletteTheme.SLICE;
            if (active) {
                color = hot ? RouletteTheme.SLICE_ACTIVE_HOVER : RouletteTheme.SLICE_ACTIVE;
            }
            ctx.fill(slice[2], slice[1], slice[3], slice[1] + 1, color);
        }
        if (hoveredSlot < 0) {
            return;
        }
        // 悬停是擦在格底上的一层：格底已经画过，这里只叠颜色，凸出来的一圈直接盖在世界上面
        if (!isActive(runtime, base + hoveredSlot)) {
            for (int[] slice : slices) {
                if (slice[0] == hoveredSlot) {
                    ctx.fill(slice[2], slice[1], slice[3], slice[1] + 1, RouletteTheme.SLICE_HOVER);
                }
            }
        }
        for (int[] lip : lips) {
            if (lip[0] == hoveredSlot) {
                ctx.fill(lip[2], lip[1], lip[3], lip[1] + 1, RouletteTheme.SLICE_LIP);
            }
        }
    }

    private void paintLabels(VDrawContext ctx, ModelRuntime runtime) {
        int rowH = Ef.lineH();
        int maxWidth = Math.max(28, Math.round(2.0F * RouletteTheme.LABEL_R * scale
                * (float) Math.sin(RouletteTheme.SLOT_ANGLE / 2.0D)));
        int base = page * RouletteTheme.SLOTS;
        for (int slot = 0; slot < RouletteTheme.SLOTS; slot++) {
            int index = base + slot;
            if (index >= options.size()) {
                continue;
            }
            double angle = RouletteTheme.FIRST_ANGLE + slot * RouletteTheme.SLOT_ANGLE;
            int labelX = centerX + (int) Math.round(RouletteTheme.LABEL_R * scale * Math.cos(angle));
            int labelY = centerY + (int) Math.round(RouletteTheme.LABEL_R * scale * Math.sin(angle));
            int ink = RouletteTheme.labelInk(isActive(runtime, index));
            List<String> lines = wrapLabel(options.get(index).label(), maxWidth);
            int top = labelY - lines.size() * rowH / 2;
            for (String line : lines) {
                Ef.text(ctx, line, labelX - Ef.textWidth(line) / 2, top, ink);
                top += rowH;
            }
        }
    }

    /** 两行以内按扇区宽度折行，超出就省略：长名字不会爬到隔壁扇区上去。 */
    private List<String> wrapLabel(String label, int maxWidth) {
        List<String> lines = Ef.wrap(label, maxWidth);
        if (lines.size() <= 2) {
            return lines;
        }
        StringBuilder rest = new StringBuilder();
        for (int i = 1; i < lines.size(); i++) {
            rest.append(lines.get(i));
        }
        List<String> capped = new ArrayList<>(2);
        capped.add(lines.get(0));
        capped.add(Ef.ellipsis(rest.toString(), maxWidth));
        return capped;
    }

    private void paintArrows(VDrawContext ctx) {
        paintArrow(ctx, -1, "<", hoverPrev, page > 0);
        paintArrow(ctx, 1, ">", hoverNext, page < pageCount() - 1);
    }

    private void paintArrow(VDrawContext ctx, int direction, String glyph, boolean hot, boolean enabled) {
        int size = RouletteTheme.ARROW_SIZE;
        int x = arrowX(direction) - size / 2;
        int y = centerY - size / 2;
        Ef.Corner corner = direction < 0 ? Ef.Corner.TL : Ef.Corner.BR;
        if (enabled && hot) {
            Ef.cutFill(ctx, x, y, size, size, RouletteTheme.ARROW_HOVER, Ef.CUT_WIDE, corner);
        } else if (enabled) {
            Ef.cutFill(ctx, x, y, size, size, RouletteTheme.ARROW_FILL, Ef.CUT_WIDE, corner);
        }
        Ef.cutOutline(ctx, x, y, size, size,
                enabled ? RouletteTheme.ARROW_EDGE : RouletteTheme.ARROW_EDGE_DEAD, Ef.CUT_WIDE, corner);
        int ink = RouletteTheme.ARROW_INK;
        if (!enabled) {
            ink = RouletteTheme.ARROW_INK_DEAD;
        } else if (hot) {
            ink = RouletteTheme.TEXT_ACTIVE;
        }
        Ef.text(ctx, glyph, x + (size - Ef.textWidth(glyph)) / 2 + 1, y + (size - Ef.lineH()) / 2 + 1, ink);
    }

    private void paintPageText(VDrawContext ctx) {
        String text = (page + 1) + " / " + pageCount();
        int width = Ef.textWidth(text) + 12;
        int x = centerX - width / 2;
        // 窗口太矮时页码会压到底部提示上，先往上收，宁可贴着轮盘下沿
        int y = Math.min(centerY + Math.round(RouletteTheme.WHEEL_OUTER_R * scale) + 12,
                this.height - FOOTER_H - 12);
        Ef.fill(ctx, x, y, width, 12, RouletteTheme.PAGE_BG);
        Ef.text(ctx, text, x + 6, y + 2, RouletteTheme.PAGE_INK);
    }

    private void paintHeader(VDrawContext ctx, ModelRuntime runtime) {
        Ef.tracked(ctx, "// ANIMATIONS", PAD, 8, RouletteTheme.TEXT_DIM, 1);
        String model = CustomModelManager.loadedFile();
        int columnWidth = Math.max(60, this.width - PAD * 2);
        Ef.text(ctx, Ef.ellipsis(model.isEmpty() ? "未加载模型" : model, columnWidth), PAD, 20, RouletteTheme.TEXT);

        // 右对齐遇上中文会量宽不准、压到屏幕外，改成左对齐第三行
        Ef.text(ctx, Ef.ellipsis("当前：" + currentLabel(runtime), columnWidth), PAD, 32,
                RouletteTheme.TEXT_ACTIVE);
    }

    private void paintFooter(VDrawContext ctx) {
        String hint = Ef.ellipsis("滚轮翻页 · 左键切换 · 再点取消 · 右键关闭", this.width - PAD * 2);
        Ef.text(ctx, hint, (this.width - Ef.textWidth(hint)) / 2, this.height - 14, RouletteTheme.TEXT_DIM);
    }

    private void paintEmpty(VDrawContext ctx) {
        String text = Ef.ellipsis("这个模型没有可选的动作或服装", this.width - PAD * 2);
        Ef.text(ctx, text, (this.width - Ef.textWidth(text)) / 2, centerY - Ef.lineH() / 2,
                RouletteTheme.TEXT_DIM);
    }

    // ------------------------------------------------------------------ 状态

    private boolean isActive(ModelRuntime runtime, int index) {
        if (runtime == null) {
            return false;
        }
        ModelRuntime.ExtraOption option = options.get(index);
        return option.variant()
                ? option.key().equals(runtime.activeVariantKey())
                : option.key().equals(runtime.activeExtraKey());
    }

    private String currentLabel(ModelRuntime runtime) {
        if (runtime == null) {
            return "无";
        }
        String variant = labelOf(runtime.activeVariantKey());
        if (variant.isEmpty()) {
            variant = "默认";
        }
        String extra = labelOf(runtime.activeExtraKey());
        return extra.isEmpty() ? variant : variant + " · " + extra;
    }

    private String labelOf(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        for (ModelRuntime.ExtraOption option : options) {
            if (option.key().equals(key)) {
                return option.label();
            }
        }
        return key;
    }

    // ------------------------------------------------------------------ 交互

    private int arrowX(int direction) {
        return centerX + direction * Math.round(RouletteTheme.WHEEL_OUTER_R * scale
                + RouletteTheme.ARROW_GAP + RouletteTheme.ARROW_SIZE / 2.0F);
    }

    private boolean hitArrow(double mouseX, double mouseY, int direction) {
        int half = RouletteTheme.ARROW_SIZE / 2;
        int x = arrowX(direction);
        return mouseX >= x - half && mouseX <= x + half
                && mouseY >= centerY - half && mouseY <= centerY + half;
    }

    private void updateHover(double mouseX, double mouseY) {
        this.hoveredSlot = -1;
        int base = page * RouletteTheme.SLOTS;
        for (int slot = 0; slot < RouletteTheme.SLOTS && base + slot < options.size(); slot++) {
            if (RouletteGeometry.contains(sectors[slot], mouseX, mouseY)) {
                this.hoveredSlot = slot;
                break;
            }
        }
        this.hoverPrev = hitArrow(mouseX, mouseY, -1) && page > 0;
        this.hoverNext = hitArrow(mouseX, mouseY, 1) && page < pageCount() - 1;
    }

    private boolean click(EfWidget self, double mouseX, double mouseY, int button) {
        if (button == 1) {
            onClose();
            return true;
        }
        if (button != 0) {
            return false;
        }
        updateHover(mouseX, mouseY);
        if (hitArrow(mouseX, mouseY, -1) && page > 0) {
            turnPage(-1);
            return true;
        }
        if (hitArrow(mouseX, mouseY, 1) && page < pageCount() - 1) {
            turnPage(1);
            return true;
        }
        if (hoveredSlot >= 0) {
            CustomModelManager.selectExtra(options.get(page * RouletteTheme.SLOTS + hoveredSlot).key());
            return true;
        }
        return false;
    }

    private boolean wheel(EfWidget self, double mouseX, double mouseY, double amount) {
        if (pageCount() <= 1 || amount == 0.0D) {
            return false;
        }
        turnPage(amount > 0.0D ? -1 : 1);
        return true;
    }

    private void turnPage(int step) {
        this.page = Math.max(0, Math.min(pageCount() - 1, this.page + step));
        rememberedPage = this.page;
    }
}