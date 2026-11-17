package me.zuogeren.slimefunhelperplus.module.custommodel.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.GenericScreen;
import me.matl114.utils.ClientUtils;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelManager;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import me.zuogeren.slimefunhelperplus.module.custommodel.ModelCatalog;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ModelRuntime;
import me.zuogeren.slimefunhelperplus.module.custommodel.ui.Ef;
import me.zuogeren.slimefunhelperplus.module.custommodel.ui.EfWidget;
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
 * 结果交给 {@link CustomModelManager#selectExtra(String)}。带分类的模型是两级轮盘：
 * 主轮盘上每个分类只占一格，点进去才是该组的动作，子轮盘末尾一格是返回。
 * 界面不暂停游戏、不糊背景，所以选动作时能直接看见角色的变化。
 */
public class ModelRouletteScreen extends GenericScreen {

    /** 顶部两行：眉标与模型名。 */
    private static final int HEADER_H = 34;
    /** 底部一行操作提示。 */
    private static final int FOOTER_H = 18;
    private static final int PAD = 10;

    /** 分类入口的标签后缀：一眼看出这一格是「进去」而不是「切换」。用 ASCII，自定义字体缺字也不会变方块。 */
    private static final String SUBMENU_MARK = " >";

    /** 主轮盘圆心显示的目录名。 */
    private static final String ROOT_LABEL = "根目录";

    /** 关掉再打开还停在上次的页；数据变了由 {@link #pageCount()} 夹回来。 */
    private static int rememberedPage;

    /**
     * 轮盘一格，三种：动作（{@code key} 交给 {@link CustomModelManager#selectExtra(String)}）、
     * 分类入口（{@code category} 非空，点进去）、返回（子轮盘末尾那一格）。
     *
     * <p>三种用字段区分而不是靠 key 里的魔法前缀：动作键来自模型文件，作者写什么都有可能。
     */
    private record Slot(String key, String label, boolean variant, String category, boolean back) {
        static Slot ofAction(String key, String label, boolean variant) {
            return new Slot(key, label, variant, "", false);
        }

        static Slot ofSubmenu(String category) {
            return new Slot("", category + SUBMENU_MARK, false, category, false);
        }

        static Slot ofBack() {
            return new Slot("", "返回", false, "", true);
        }
    }

    /** 轮盘固定用深色样式：它铺在世界画面上，浅色纸面会把角色挡掉。 */
    private final Ef.Palette pal = Ef.palette(false);

    private final List<ModelRuntime.ExtraOption> options = new ArrayList<>();
    /** 主轮盘：服装预设、没有分类的动作、每个分类一个入口。 */
    private List<Slot> rootSlots = List.of();
    /** 分类名 → 该组动作（末尾带返回格）。 */
    private Map<String, List<Slot>> categorySlots = Map.of();
    /** 每个槽位的扇区多边形（屏幕坐标），命中判定用。 */
    private final double[][][] sectors = new double[RouletteTheme.SLOTS][][];

    /** 当前层级：0 = 主轮盘，1 = 某个分类的子轮盘。 */
    private int level;
    /** 子轮盘对应的分类名；主轮盘为空串。 */
    private String openCategory = "";
    private int page;
    /** 主轮盘的页码：进子轮盘时先存下来，退回来还停在原来那一页。 */
    private int rootPage;
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
        super(Component.translatable("custom-model.roulette.title"), 0, 0);
        ModelRuntime runtime = CustomModelManager.runtime();
        if (runtime != null) {
            this.options.addAll(runtime.extras());
        }
        buildSlots();
    }

    /**
     * 把平铺的动作列表折成两级：分类动作收进各自的子轮盘，主轮盘只留一个分类入口。
     *
     * <p>顺序照作者菜单：分类入口落在它第一个成员原来的位置上（解析阶段分类动作已经排到
     * {@code #分类} 标记后面），所以主轮盘读起来还是作者写的顺序。没有分类的模型整列都是空串，
     * 主轮盘就是原来的平铺列表。
     *
     * <p>只在打开界面时算一次，轮盘每帧只读 {@link #rootSlots} / {@link #categorySlots}。
     */
    private void buildSlots() {
        Map<String, String> categories = CustomModelManager.extraCategories();
        // 根菜单里列过的动作留在根目录：SPM 的 extraAnimation 是根菜单、extraAnimationClassify 只是
        // 子菜单表（UnifiedRouletteScreen:122-137），分类只给「额外的」动作提供入口。
        // 按分类表无条件搬走会让根菜单里的动作凭空消失（樱羽艾玛的「名牌开关」）。
        Set<String> rootKeys = CustomModelManager.rootExtraKeys();
        List<Slot> root = new ArrayList<>();
        Map<String, List<Slot>> groups = new LinkedHashMap<>();
        for (ModelRuntime.ExtraOption option : options) {
            String group = option.variant() || rootKeys.contains(option.key())
                    ? ""
                    : categories.getOrDefault(option.key(), "");
            if (option.variant() || group.isEmpty()) {
                root.add(Slot.ofAction(option.key(), option.label(), option.variant()));
                continue;
            }
            List<Slot> members = groups.get(group);
            if (members == null) {
                members = new ArrayList<>();
                groups.put(group, members);
                root.add(Slot.ofSubmenu(group));
            }
            members.add(Slot.ofAction(option.key(), option.label(), false));
        }
        for (List<Slot> members : groups.values()) {
            members.add(Slot.ofBack());
        }
        this.rootSlots = root;
        this.categorySlots = groups;
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
        // 改窗口大小会重跑 init0：主轮盘回到记住的那一页，子轮盘只把页码夹回本层范围
        int wanted = level == 0 ? rememberedPage : this.page;
        this.page = Math.max(0, Math.min(wanted, pageCount() - 1));
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
        CustomModelModule.logDebug("[SFH+] 轮盘界面 " + this.width + "x" + this.height + " 项=" + options.size()
                + " scale=" + scale);
        EfWidget.of(0, 0, this.width, this.height, this::paint)
                .click(this::click)
                .scroll(this::wheel)
                .addTo(this);
    }

    /** 当前层级的格子。 */
    private List<Slot> entries() {
        return level == 0 ? rootSlots : categorySlots.getOrDefault(openCategory, List.of());
    }

    private int pageCount() {
        return Math.max(1, (entries().size() + RouletteTheme.SLOTS - 1) / RouletteTheme.SLOTS);
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

    /** 扇区里能放下的文字宽度（弦宽）。 */
    private int labelWidth() {
        return Math.max(28, Math.round(2.0F * RouletteTheme.LABEL_R * scale
                * (float) Math.sin(RouletteTheme.SLOT_ANGLE / 2.0D)));
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
        paintCenter(ctx);
        paintArrows(ctx);
        paintPageText(ctx);
        paintHeader(ctx, runtime);
        paintFooter(ctx);
        if (entries().isEmpty()) {
            paintEmpty(ctx);
        }
    }

    private void paintSlices(VDrawContext ctx, ModelRuntime runtime) {
        List<Slot> slots = entries();
        int base = page * RouletteTheme.SLOTS;
        for (int[] slice : slices) {
            int index = base + slice[0];
            if (index >= slots.size()) {
                continue;
            }
            boolean active = isActive(runtime, slots.get(index));
            boolean hot = slice[0] == hoveredSlot;
            int color = RouletteTheme.SLICE;
            if (active) {
                color = hot ? RouletteTheme.sliceActiveHover(pal) : RouletteTheme.sliceActive(pal);
            }
            ctx.fill(slice[2], slice[1], slice[3], slice[1] + 1, color);
        }
        if (hoveredSlot < 0) {
            return;
        }
        // 悬停是擦在格底上的一层：格底已经画过，这里只叠颜色，凸出来的一圈直接盖在世界上面
        if (!isActive(runtime, slots.get(base + hoveredSlot))) {
            for (int[] slice : slices) {
                if (slice[0] == hoveredSlot) {
                    ctx.fill(slice[2], slice[1], slice[3], slice[1] + 1, RouletteTheme.sliceHover(pal));
                }
            }
        }
        for (int[] lip : lips) {
            if (lip[0] == hoveredSlot) {
                ctx.fill(lip[2], lip[1], lip[3], lip[1] + 1, RouletteTheme.sliceLip(pal));
            }
        }
    }

    private void paintLabels(VDrawContext ctx, ModelRuntime runtime) {
        List<Slot> slots = entries();
        int rowH = Ef.lineH();
        int maxWidth = labelWidth();
        int base = page * RouletteTheme.SLOTS;
        for (int slot = 0; slot < RouletteTheme.SLOTS; slot++) {
            int index = base + slot;
            if (index >= slots.size()) {
                continue;
            }
            Slot entry = slots.get(index);
            double angle = RouletteTheme.FIRST_ANGLE + slot * RouletteTheme.SLOT_ANGLE;
            int labelX = centerX + (int) Math.round(RouletteTheme.LABEL_R * scale * Math.cos(angle));
            int labelY = centerY + (int) Math.round(RouletteTheme.LABEL_R * scale * Math.sin(angle));
            int ink = RouletteTheme.labelInk(pal, isActive(runtime, entry));
            List<String> lines = wrapLabel(entry.label(), maxWidth);
            int top = labelY - lines.size() * rowH / 2;
            for (String line : lines) {
                Ef.text(ctx, line, labelX - Ef.textWidth(line) / 2, top, ink);
                top += rowH;
            }
        }
    }

    /**
     * 子轮盘在圆心写分类名：轮盘中间本来就是空的，正好当面包屑，也提示这里能点回上一级。
     * 主轮盘不写 —— 那里要留给角色。
     */
    /**
     * 圆心显示**当前所在目录**，不是返回按钮：根目录写「根目录」，子轮盘写分类名。
     *
     * <p>返回走子轮盘末尾那一格（{@link Slot#ofBack()}），圆心只做指示。
     */
    private void paintCenter(VDrawContext ctx) {
        int maxWidth = Math.max(20, Math.round(RouletteTheme.WHEEL_INNER_R * 2.0F * scale) - 8);
        String text = Ef.ellipsis(level == 0 ? ROOT_LABEL : openCategory, maxWidth);
        Ef.text(ctx, text, centerX - Ef.textWidth(text) / 2, centerY - Ef.lineH() / 2, RouletteTheme.TEXT_DIM);
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
            Ef.cutFill(ctx, x, y, size, size, RouletteTheme.arrowHover(pal), pal.cutWide(), corner);
        } else if (enabled) {
            Ef.cutFill(ctx, x, y, size, size, RouletteTheme.ARROW_FILL, pal.cutWide(), corner);
        }
        Ef.cutOutline(ctx, x, y, size, size,
                enabled ? RouletteTheme.ARROW_EDGE : RouletteTheme.ARROW_EDGE_DEAD, pal.cutWide(), corner);
        int ink = RouletteTheme.ARROW_INK;
        if (!enabled) {
            ink = RouletteTheme.ARROW_INK_DEAD;
        } else if (hot) {
            ink = RouletteTheme.textActive(pal);
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
        String file = CustomModelManager.loadedFile();
        // 标题用模型自己写的名字（metadata.name），没写才退回文件名
        String model = file.isEmpty() ? "" : ModelCatalog.displayName(file);
        int columnWidth = Math.max(60, this.width - PAD * 2);
        String title = model.isEmpty()
                ? Component.translatable("custom-model.roulette.no-model").getString() : model;
        Ef.text(ctx, Ef.ellipsis(title, columnWidth), PAD, 20, RouletteTheme.TEXT);

        // 右对齐遇上中文会量宽不准、压到屏幕外，改成左对齐第三行
        String current = Component.translatable("custom-model.roulette.current", currentLabel(runtime)).getString();
        Ef.text(ctx, Ef.ellipsis(current, columnWidth), PAD, 32,
                RouletteTheme.textActive(pal));
    }

    private void paintFooter(VDrawContext ctx) {
        String hint = Component.translatable(level > 0
                ? "custom-model.roulette.hint-nested" : "custom-model.roulette.hint").getString();
        hint = Ef.ellipsis(hint, this.width - PAD * 2);
        Ef.text(ctx, hint, (this.width - Ef.textWidth(hint)) / 2, this.height - 14, RouletteTheme.TEXT_DIM);
    }

    private void paintEmpty(VDrawContext ctx) {
        String text = Ef.ellipsis(Component.translatable("custom-model.roulette.empty").getString(),
                this.width - PAD * 2);
        Ef.text(ctx, text, (this.width - Ef.textWidth(text)) / 2, centerY - Ef.lineH() / 2,
                RouletteTheme.TEXT_DIM);
    }

    // ------------------------------------------------------------------ 状态

    private boolean isActive(ModelRuntime runtime, Slot slot) {
        if (runtime == null) {
            return false;
        }
        if (!slot.category().isEmpty()) {
            // 分类入口：组里有动作在播就点亮，玩家一眼看出当前动作在哪一组
            for (Slot member : categorySlots.getOrDefault(slot.category(), List.of())) {
                if (isActive(runtime, member)) {
                    return true;
                }
            }
            return false;
        }
        if (slot.back()) {
            return false;
        }
        return slot.variant()
                ? slot.key().equals(runtime.activeVariantKey())
                : slot.key().equals(runtime.activeExtraKey());
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

    /** 轮盘中间的空心圆：子轮盘里点它退回主轮盘。 */
    private boolean insideCenter(double mouseX, double mouseY) {
        double dx = mouseX - centerX;
        double dy = mouseY - centerY;
        double radius = RouletteTheme.WHEEL_INNER_R * scale;
        return dx * dx + dy * dy <= radius * radius;
    }

    private void updateHover(double mouseX, double mouseY) {
        this.hoveredSlot = -1;
        List<Slot> slots = entries();
        int base = page * RouletteTheme.SLOTS;
        for (int slot = 0; slot < RouletteTheme.SLOTS && base + slot < slots.size(); slot++) {
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
            // 右键是「关掉整个轮盘」，子轮盘里也一样，所以不走会先退一级的 onClose
            super.onClose();
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
        if (level > 0 && insideCenter(mouseX, mouseY)) {
            back();
            return true;
        }
        if (hoveredSlot >= 0) {
            Slot entry = entries().get(page * RouletteTheme.SLOTS + hoveredSlot);
            if (!entry.category().isEmpty()) {
                enter(entry.category());
            } else if (entry.back()) {
                back();
            } else {
                CustomModelManager.selectExtra(entry.key());
            }
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
        if (level == 0) {
            rememberedPage = this.page;
        }
    }

    /** 进子轮盘：主轮盘的页码先存下来，退回来时还停在那儿。 */
    private void enter(String category) {
        this.rootPage = page;
        this.level = 1;
        this.openCategory = category;
        this.page = 0;
        this.hoveredSlot = -1;
    }

    private void back() {
        this.level = 0;
        this.openCategory = "";
        this.page = Math.max(0, Math.min(rootPage, pageCount() - 1));
        this.hoveredSlot = -1;
    }

    /**
     * Esc 在子轮盘里退回主轮盘，主轮盘才真的关屏。
     *
     * <p>宿主在把 Esc 派给子控件之前就调这里，所以「返回上一级」只能在这一层拦。
     */
    @Override
    public void onClose() {
        if (level > 0) {
            back();
            return;
        }
        super.onClose();
    }
}
