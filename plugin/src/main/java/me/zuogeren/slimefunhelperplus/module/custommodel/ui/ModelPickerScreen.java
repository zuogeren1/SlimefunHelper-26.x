package me.zuogeren.slimefunhelperplus.module.custommodel.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.GenericScreen;
import me.matl114.utils.ClientUtils;
import me.zuogeren.slimefunhelperplus.hostgui.HostControls;
import me.matl114.versioned.api.VDrawContext;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelManager;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import me.zuogeren.slimefunhelperplus.module.custommodel.ModelCatalog;
import me.zuogeren.slimefunhelperplus.module.custommodel.model.ModelRuntime;
import me.zuogeren.slimefunhelperplus.module.custommodel.ui.Ef;
import me.zuogeren.slimefunhelperplus.module.custommodel.ui.EfWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
    private static final int PANEL_H = 243;
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

    /**
     * 卡片尺寸与 YSM / ModernYSM 完全一致（{@code ModelButton:95} 的 {@code super(x, y, 52, 90, ...)}）。
     *
     * <p>编号标签与名字**叠在**卡片上，不额外占高度 —— 这是 YSM 的做法
     * （{@code CatalogModelButton:138-139} 的 {@code renderName}/{@code renderState} 都画在卡片矩形内）。
     * 把名字堆在卡片下方会让卡片变成 52×121，比例与 YSM 差 40%，模型取景也就对不上。
     */
    private static final int CARD_W = 52;
    private static final int CARD_H = 90;
    /** 编号标签的高度（叠在卡片左上角）。 */
    private static final int TAG_H = 11;
    /** 名字区的高度（叠在卡片底部）。 */
    private static final int NAME_H = 20;
    /**
     * 模型可见区 = 整张卡片。
     *
     * <p>3D 预览走 PiP：{@code GuiGraphicsExtractor#entity} 只登记一个 {@code GuiEntityRenderState}，
     * 真正的出图在 {@code GuiRenderer#preparePictureInPicture} 里，它的 blit 追加到当前层元素表末尾，
     * 因此**盖在同层所有 2D 之上**。
     *
     * <p>名字与编号靠 {@code pushLayer} 抬到更高层来压住它（{@code GuiRenderStateMixin.setLayerToDepth}
     * 按 depth 插入 strata），而不是把模型区缩小让位 —— 后者会让模型整体小 20% 以上。
     */
    private static final int CARD_SLOT_H = CARD_H;
    /** 卡片 2D 装饰（编号/名字）所在的层：比模型高一层，PiP 的 blit 就盖不住。 */
    private static final int CHROME_LAYER = 1;
    private static final int CARD_GAP_X = 3;
    private static final int CARD_GAP_Y = 3;
    private static final int CARD_COLS = 5;
    private static final int CARD_ROWS = 2;
    private static final int PER_PAGE = CARD_COLS * CARD_ROWS;
    private static final int GRID_X = 143;
    private static final int GRID_Y = 28;

    private static final int PG_W = 52;
    private static final int PG_H = 14;
    private static final int PG_Y = 217;
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

    /**
     * 卡片 3D 预览的渲染状态：文件 → 状态。
     *
     * <p>每个文件一份而不是共用一份：{@code GuiGraphicsExtractor#entity} 只记下状态引用，
     * 真正的出图在 {@code GuiRenderer} 里晚一步发生，共用一份会让十张卡片都画成最后一张。
     */
    private final Map<String, AvatarRenderState> cardStates = new HashMap<>();
    /** 本帧要画的卡片槽位 {x, y, h, hot, selected} 与对应文件；3D 统一排在 2D 之后画。 */
    private final List<int[]> cardSlots = new ArrayList<>();
    private final List<String> cardFiles = new ArrayList<>();
    /** 当前页的文件名，复用同一份避免每帧新建（{@link #ensurePageCards()} 每帧都要用）。 */
    private final List<String> pageFiles = new ArrayList<>();
    /** 文件 → 上一次应用的卡片动画名。动画换了要立刻重采一次，别等下一个采样窗口。 */
    private final Map<String, String> cardAnimations = new HashMap<>();
    /**
     * 卡片的重采样间隔（毫秒）。
     *
     * <p>卡片只有 52×90，20Hz 看不出卡顿；而一页十份逐帧全量采样会把主线程吃满。
     * 节拍是**整页**的：到点的那一帧把本页所有卡片各推进一次，其余帧只出图不采样。
     */
    private static final long CARD_SAMPLE_MS = 50L;
    private long cardSampleAt;
    /** 本帧是否已经推进过卡片采样：一帧只推进一轮，十张卡片在这一轮里各采一次。 */
    private boolean cardSampledThisFrame;
    /**
     * 卡片动画的时钟起点。
     *
     * <p>不能用 {@code System.currentTimeMillis()} 直接当秒数：它取模后每 100 秒回卷一次，
     * 回卷那一帧片段内时间会跳回 0，动作会闪一下。这里只记页面打开的时刻，差值单调递增。
     */
    private final long cardClockStart = System.currentTimeMillis();
    /** 卡片进场动画的起点：翻页/换搜索词时重置，卡片按序号错开滑入。 */
    private long cardEnterStart = System.currentTimeMillis();
    /** 进场动画时长与逐卡错开间隔（毫秒）：短、硬、机械，一个入场序列不超过 1 秒。 */
    private static final float CARD_ENTER_MS = 260.0F;
    private static final long CARD_ENTER_STAGGER_MS = 38L;
    /** 进场时卡片从下方滑入的距离（GUI 像素）。 */
    private static final int CARD_ENTER_RISE = 8;
    /** 上一帧的悬停卡片下标，用来认出「刚移出」并播 {@code hover_fadeout}。 */
    private int lastHoverCard = -1;
    /** 正在播淡出的卡片下标与它的起止时刻；{@code -1} 表示没有卡片在淡出。 */
    private int cardFadeIndex = -1;
    private long cardFadeStart = System.currentTimeMillis();
    private float cardFadeSeconds;

    public ModelPickerScreen() {
        super(Component.translatable("custom-model.picker.title"), 0, 0);
        rebuildView();
        retainPageCards();
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

    /** 关页时把卡片实例还回去：一页十个模型的几何不小，留着只是白占内存。 */
    @Override
    public void removed() {
        CustomModelManager.dropCards();
        cardStates.clear();
        cardAnimations.clear();
        super.removed();
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
        this.searchBox = HostControls.editBoxText(
                panelX + SEARCH_X + 4,
                panelY + SEARCH_Y + 4,
                SEARCH_W - 8,
                48,
                Component.translatable("custom-model.picker.search"));
        this.searchBox.setTextColor(pal.ink());
        HostControls.setValueQuietly(this.searchBox, query, this::applyQuery);
        this.addRenderableWidget(this.searchBox);
    }

    /** 搜索词变了就重算列表并回到第一页：留在旧页码上会看到一张空表。 */
    private void applyQuery(String text) {
        this.query = text == null ? "" : text;
        this.page = 0;
        rebuildView();
        retainPageCards();
        this.cardEnterStart = System.currentTimeMillis();
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
        // 卡片实例按帧预算逐个建：一页十个模型一次全建会把打开页面卡住好几秒。
        // 必须在 updateHover 之前重置 —— 悬停切换要查片段长度，那也要建实例。
        // 换模型/换贴图会把卡片实例整批丢掉（贴图已释放）：按文件缓存的渲染状态要一起清，
        // 新实例才会从干净状态重新采样。
        if (CustomModelManager.consumeCardsDropped()) {
            cardStates.clear();
            cardAnimations.clear();
        }
        // 每帧补一次预建。{@code prebuildCards} 自己按「已建 / 已备 / 解析中 / 失败」去重，
        // 全部就绪时只是一次十项查表；少了这一步，卡片被丢掉之后没人再发起预建，
        // 会一直空到玩家翻页或改搜索词。
        ensurePageCards();
        CustomModelManager.beginCardFrame();
        cardSampledThisFrame = false;
        updateHover(mouseX, mouseY);
        int px = panelX;
        int py = panelY;
        // 面板与右栏都切同一个角：只切外层的话，右栏那块矩形会把切角重新填满
        Ef.cutFill(ctx, px, py, PANEL_W, PANEL_H, pal.paper(), pal.cutPanel(), Ef.Corner.BR);
        Ef.fill(ctx, px, py, LEFT_W, PANEL_H, pal.panel());
        Ef.cutFill(ctx, px + RIGHT_X, py, RIGHT_W, PANEL_H, pal.panel(), pal.cutPanel(), Ef.Corner.BR);
        paintPreview(ctx, px, py);
        paintToolbar(ctx, px, py);
        paintCards(ctx, px, py);
        paintPager(ctx, px, py);
        paintFooter(ctx, px, py);
        paintCardTooltip(ctx, mouseX, mouseY);
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
        Ef.wipeCut(ctx, gx, gy, GEAR_SIZE, GEAR_SIZE, pal.chip(), pal.signal(), wipe, pal.cut(), Ef.Corner.BR);
        gearGlyph(ctx, gx, gy, GEAR_SIZE, hot ? pal.onSignal() : pal.ink2());

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
        Ef.wipeCut(ctx, tx, ty, TEX_W, TEX_H, pal.chip(), pal.signal(), wipeTex, pal.cut(), Ef.Corner.BR);
        int ink = hotTex ? pal.onSignal() : pal.ink2();
        Ef.fill(ctx, tx + 6, ty + 6, 8, 8, hotTex ? pal.onSignal() : pal.signal());
        Ef.text(ctx, Component.translatable("custom-model.picker.texture").getString(),
                tx + 18, ty + (TEX_H - Ef.lineH()) / 2, ink);

        int sx = px + SEARCH_X;
        int sy = py + SEARCH_Y;
        // 框体自己画，字交给 EditBox：占位提示也由它画，这里不再补一遍
        Ef.cutFill(ctx, sx, sy, SEARCH_W, SEARCH_H, pal.chip(), pal.cut(), Ef.Corner.BR);
    }

    private void paintCards(VDrawContext ctx, int px, int py) {
        if (view.isEmpty()) {
            // 两种空要分开说：目录里一个模型都没有时，提示「换个关键词」只会让人白试
            String empty = ModelCatalog.names().isEmpty()
                    ? Component.translatable("custom-model.picker.empty-folder").getString()
                    : Component.translatable("custom-model.picker.empty-search").getString();
            Ef.text(ctx, empty, px + RIGHT_X + (RIGHT_W - Ef.textWidth(empty)) / 2, py + 100, pal.ink2());
            return;
        }
        String selected = selectedFile();
        int base = page * PER_PAGE;
        cardSlots.clear();
        cardFiles.clear();
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
                Ef.wipeCut(ctx, cx, cy, CARD_W, CARD_H, cardBg, Ef.alpha(pal.signal(), 0.16F), wipe,
                        pal.cut(), Ef.Corner.BR);
            } else {
                Ef.cutFill(ctx, cx, cy, CARD_W, CARD_H, cardBg, pal.cut(), Ef.Corner.BR);
            }
            int slotY = cy;
            int slotH = CARD_SLOT_H;
            // 模型区的底色与底纹先铺：作者底图要盖在它们之上，否则底图会被这块底色吃掉。
            Ef.fill(ctx, cx, slotY, CARD_W, slotH, isSelected ? 0xFF3A3A3A : 0xFF2A2A2A);
            hatch(ctx, cx, slotY, CARD_W, slotH, 0x0AFFFFFF);
            // 作者底图铺满**整张卡片**，且必须在编号标签/名字/分隔线之前画 ——
            // YSM 的 renderImage 也不在 scissor 内（CatalogModelButton:195 用整卡矩形）。
            // 放在槽位里会被裁掉上下沿，放在 2D 元素之后会盖住它们。
            ModelRuntime cardRuntime = CustomModelManager.cardRuntime(CustomModelManager.cardStateId(file));
            if (cardRuntime != null) {
                Ef.image(ctx, cardRuntime.guiBackground(), cx, cy, CARD_W, CARD_H);
            }
            // 3D 排在所有 2D 之后统一画：PiP 贴回时盖在 2D 之上，先画会被卡片自己的底纹盖掉
            cardSlots.add(new int[]{cx, slotY, slotH, hot ? 1 : 0, isSelected ? 1 : 0});
            cardFiles.add(file);

        }
        for (int i = 0; i < cardSlots.size(); i++) {
            int[] slot = cardSlots.get(i);
            // 进场：按序号错开，从下方滑到位。PiP 的贴回位置就是矩形，挪矩形即可，
            // 不需要给离屏纹理做 alpha —— 卡片尺寸下位移比淡入更「机械」，也更省。
            // 裁剪框留在原位，滑入过程才会被槽位下沿切掉，而不是整块跟着往下跑。
            String cardFile = cardFiles.get(i);
            paintCardModel(ctx, cardFile, slot[0], slot[1], slot[2],
                    slot[3] != 0, slot[4] != 0, enterRise(i));
            // 前景图画在模型之上、scissor 之外：作者用它做卡片边框/遮罩，
            // 裁到槽位会把边框切掉（YSM 的 renderImage 也不在 scissor 内）。
            ModelRuntime fgRuntime = CustomModelManager.cardRuntime(CustomModelManager.cardStateId(cardFile));
            if (fgRuntime != null) {
                Ef.image(ctx, fgRuntime.guiForeground(), slot[0], slot[1], CARD_W, CARD_H);
            }
            // 编号与名字排在模型之后：PiP 贴回时盖在所有 2D 之上，
            // 先画会被模型压住（截图里名字直接压在模型上就是这个）。
            paintCardChrome(ctx, cardFile, slot[0], slot[1], slot[4] != 0);
        }
    }

    /**
     * 卡片的 2D 装饰：编号标签、名字遮罩、名字、选中条。
     *
     * <p>整块抬到 {@link #CHROME_LAYER}：PiP 的 blit 追加在当前层末尾，同层永远压不住它；
     * 抬一层之后编号底色与名字遮罩才不会被模型盖掉。
     *
     * <p>名字区带一层半透明遮罩：模型脚部会画到卡片下沿，没有遮罩时名字直接压在模型上，
     * 深色模型上几乎看不清（YSM 的 {@code renderName} 也是先铺一层底再写字）。
     */
    private void paintCardChrome(VDrawContext ctx, String file, int cx, int cy, boolean isSelected) {
        ctx.pushLayer(CHROME_LAYER);
        try {
            paintCardChromeInner(ctx, file, cx, cy, isSelected);
        } finally {
            ctx.popLayer();
        }
    }

    private void paintCardChromeInner(VDrawContext ctx, String file, int cx, int cy, boolean isSelected) {
        // 编号标签：选中时整块强调色，一眼看出当前模型
        String no = String.format(Locale.ROOT, "%02d", view.indexOf(file) + 1);
        int tagW = Ef.textWidth(no) + 4;
        Ef.cutFill(ctx, cx, cy, tagW, TAG_H, isSelected ? pal.signal() : pal.tagBg(), 2, Ef.Corner.BR);
        Ef.text(ctx, no, cx + 2, cy + 1, isSelected ? pal.onSignal() : pal.tagFg());

        // 名字遮罩：从卡片底部往上 20px，向下渐隐，让名字在任何模型上都读得清
        int scrimTop = cy + CARD_H - NAME_H;
        Ef.fill(ctx, cx, scrimTop, CARD_W, NAME_H, 0xC0101010);
        Ef.rule(ctx, cx, scrimTop, CARD_W, pal.rule());

        List<String> lines = Ef.wrap(ModelCatalog.displayName(file), CARD_W - 6);
        int nameY = scrimTop + 2;
        for (int line = 0; line < 2 && line < lines.size(); line++) {
            String text = lines.get(line);
            if (line == 1 && lines.size() > 2) {
                text = Ef.ellipsis(text + lines.get(2), CARD_W - 6);
            }
            Ef.text(ctx, text, cx + 3, nameY, pal.ink());
            nameY += Ef.lineH();
        }
        if (isSelected) {
            Ef.fill(ctx, cx, cy, 2, CARD_H, pal.signal());
        }
    }

    /**
     * 第 {@code index} 张卡片本帧的进场位移（GUI 像素，0 = 已到位）。
     *
     * <p>错开间隔只有 38ms：十张卡片全进场也不到 400ms，符合「一个入场序列不超过 1 秒」。
     * 关掉动效时 {@link Ef#progress} 直接返回 1，位移恒为 0。
     */
    private int enterRise(int index) {
        float progress = Ef.snap(Ef.progress(
                cardEnterStart + index * CARD_ENTER_STAGGER_MS, CARD_ENTER_MS));
        return Math.round(CARD_ENTER_RISE * (1.0F - progress));
    }

    // ------------------------------------------------------------------ 卡片 3D

    /**
     * 卡片里的模型：走原版 PiP 提交，每张卡片一个渲染状态。
     *
     * <p>PiP 是「先渲到离屏纹理、再贴回 GUI」，贴回的位置由矩形决定，所以卡片之间互不干扰 ——
     * 这也是唯一能同帧画十份的路子：直接往 GUI 的顶点缓冲里写自定义几何没有入口
     * （{@code GuiGraphicsExtractor} 只暴露 fill/blit/text/item 与 PiP 提交）。
     *
     * <p>姿态由 {@link #sampleCard} 按节奏采样，出图走 {@code renderSnapshot}：
     * 十份模型逐帧全量采样会把主线程吃满，而卡片尺寸下 20Hz 与 60Hz 看不出差别。
     */
    private void paintCardModel(VDrawContext ctx, String file, int cx, int slotY, int slotH,
            boolean hot, boolean isSelected, int rise) {
        GuiGraphicsExtractor graphics = extractor(ctx);
        if (graphics == null) {
            return;
        }
        int stateId = CustomModelManager.cardStateId(file);
        ModelRuntime runtime = CustomModelManager.cardRuntime(stateId);
        if (runtime == null) {
            return;
        }
        AvatarRenderState state = cardStates.computeIfAbsent(file, key -> cardState(stateId));
        state.id = stateId;
        // 卡片是静态展示：不跟鼠标转，正面朝前，只留一点俯角让立体感出来。
        // bodyRot 必须是 0：applyPose 里转的是 (180 - bodyRot)，填 180 会把它抵消成 0，
        // 模型朝 -Z 背对镜头；再叠一次 rotateY(π) 就是转两圈等于没转。
        state.bodyRot = 0.0F;
        state.yRot = 0.0F;
        state.xRot = 0.0F;
        state.pose = Pose.STANDING;
        state.isCrouching = false;
        state.isUsingItem = false;
        state.isPassenger = false;
        state.isFallFlying = false;
        state.isVisuallySwimming = false;
        state.walkAnimationSpeed = 0.0F;
        state.walkAnimationPos = 0.0F;
        state.ageInTicks = (System.currentTimeMillis() - cardClockStart) / 50.0F;
        state.boundingBoxWidth = 0.6F;
        state.boundingBoxHeight = 1.8F;
        state.scale = 1.0F;
        int cardIndex = view.indexOf(file) - page * PER_PAGE;
        boolean fading = cardIndex >= 0 && cardIndex == cardFadeIndex
                && System.currentTimeMillis() - cardFadeStart < cardFadeSeconds * 1000.0F;
        // 卡片固定播 idle：不跟世界玩家的潜行/游泳/骑乘走，否则十张卡片摆同一个姿势
        runtime.setCardMode(true);
        // 先定动画再采样：采样要按本帧的覆盖层算姿态，反过来的话动画永远慢一帧
        String animation = applyCardAnimation(runtime, hot, isSelected, fading);
        // 动画换了立刻重采一次，别等下一个节拍（否则新动画要晚 50ms 才出现）
        boolean changed = !animation.equals(cardAnimations.put(file, animation));
        sampleCard(runtime, state, changed);
        // 裁剪到模型区（卡片扣掉名字条）：PiP 的 blit 盖在所有 2D 之上，
        // 名字条只要与模型矩形重叠就一定会被压住，所以两者必须不重叠。
        graphics.enableScissor(cx, slotY, cx + CARD_W, slotY + slotH);
        try {
            // 只转 Y 不转 Z：几何在反序列化阶段已经烘成「Y 向上、脚在原点」，
            // 原版那套 rotZ(180) 是给 Y 向下的原版模型用的，套上来会把模型倒过来。
            // 模型原生朝 -Z，转 180° 才正对镜头；再压一点俯角，立体感来自这一下。
            //
            // 取景**完全照抄 YSM**：固定 scale 30、固定锚点，不做任何自适应。
            //
            // YSM 的 {@code PreviewRenderHost:72} 是 {@code renderModelInGui(26, 65, 30.0F, ...)}，
            // 视口 52×90（{@code WIDTH/GUI_SCALE} × {@code HEIGHT/GUI_SCALE}）。
            // 作者就是按这套固定取景在 {@code preview_animation} 里调位置和缩放的 ——
            // 有的模型故意放大到只看得见躯干（Trissy 的 {@code Root scale=1.8}、
            // 亚托莉/爱恩的 {@code scale=2.0}），那是作者的意图，不该被我们「修正」成完整可见。
            //
            // 按模型大小自适应会让这些模型被强行缩小，与 YSM 观感不符。
            float cardScale = 30.0F;
            // 锚点：**模型空间原点**落在视口 y=65 处（{@code HEIGHT/GUI_SCALE/2 + 20}）。
            //
            // YSM 的 {@code RenderUtil.renderModelInGuiStateful:349} 是
            // {@code viewStack.translate(posX, posY, 1050)}，作用在模型空间原点上 ——
            // y=65 锚的是原点，不是脚底。几何原点不在 Y=0 的模型（Trissy -0.40、fuxuan -1.62）
            // 在 YSM 里同样会偏，那是作者自己的摆位，不补偿。
            //
            // PiP 把模型原点画在纹理正中（slotH/2），{@code guiY = slotH/2 + cardScale × t.y}
            // （ortho 是 invertY，+Y 朝下），令 guiY = 65 → t.y = (65 - slotH/2) / 30。
            float anchorY = (65.0F - slotH * 0.5F) / cardScale;
            Vector3f translation = new Vector3f(0.0F, anchorY, 0.0F);
            // GuiEntityRenderer 内部已经做过 overrideCameraAngle.conjugate() 再 rotateY(180°)，
            // 这里再转一次 Y 就是转两圈等于没转（模型背对）。只补俯角。
            // 作者声明 disable_preview_rotation 时连俯角也不要：那是「这个模型必须正对着看」的意思。
            Quaternionf rotation = runtime.disablePreviewRotation()
                    ? new Quaternionf()
                    : new Quaternionf().rotateX(-10.0F * 0.017453292F);
            // PiP 矩形就是模型区：纹理尺寸决定坐标空间，锚点按它算。
            // 矩形与名字条不重叠，名字才不会被 PiP 的 blit 盖住。
            graphics.entity(state, cardScale, translation, rotation, null,
                    cx, slotY + rise, cx + CARD_W, slotY + slotH + rise);
        } finally {
            graphics.disableScissor();
        }
    }

    /** 卡片用的渲染状态：只填出图需要的字段，其余保持默认。 */
    private static AvatarRenderState cardState(int stateId) {
        AvatarRenderState state = new AvatarRenderState();
        state.id = stateId;
        state.lightCoords = 15728880;
        return state;
    }

    /**
     * 采样卡片姿态：所有卡片共用 {@link #CARD_SAMPLE_MS} 的节拍持续推进。
     *
     * <p>卡片不是静态截图 —— 待机动画（呼吸、头发、裙摆）本来就在播，只采一次会让整页卡片
     * 冻在打开页面那一帧。节拍是全局的：一帧最多推进一轮，十张卡片同帧各推一次，
     * 20Hz 下与逐帧采样的观感没有差别，主线程开销却只有三分之一。
     *
     * <p>{@code force} 为真时无视节拍立刻采一次（动画刚换、刚翻页），否则新动画要等一个节拍才出现。
     */
    private void sampleCard(ModelRuntime runtime, AvatarRenderState state, boolean force) {
        if (!force) {
            // 节拍只决定「这一帧要不要推进」，不决定「哪张卡片推进」：
            // 到点的那一帧本页所有卡片各采一次，其余帧只出图。
            long now = System.currentTimeMillis();
            if (!cardSampledThisFrame && now - cardSampleAt >= CARD_SAMPLE_MS) {
                cardSampleAt = now;
                cardSampledThisFrame = true;
            }
            if (!cardSampledThisFrame) {
                return;
            }
        }
        // 传真实玩家：卡片固定播 idle（runtime 的 cardMode），但 idle 里的查询量
        // （q.ground_speed / q.is_sneaking / ysm.food_level …）要有值，否则整条表达式落 NaN。
        runtime.sampleFrame(state, Minecraft.getInstance().player, state.ageInTicks / 20.0F);
    }

    /**
     * 卡片动画：照 OpenYSM 的 {@code ModelButton} 那套状态机，两个槽各管各的。
     *
     * <p>YSM 的 {@code ModelButton:172-184} 把 hover/hover_fadeout 写在 {@code previous} 槽、
     * focus 写在 {@code queued} 槽，**两个槽可以同时非空** —— 悬停中的选中卡片本来就该同时播这两条。
     * 所以这里也拆成两个槽：{@link ModelRuntime#setCardAnimation} 管 hover 系，
     * {@link ModelRuntime#setCardFocus} 管 focus，互不顶掉。
     *
     * <p>片段是作者写在模型里的，不是每个模型都有；缺片段时收到空名，等于不播。
     */
    private String applyCardAnimation(ModelRuntime runtime, boolean hot, boolean isSelected, boolean fading) {
        String name = "";
        boolean once = false;
        if (hot) {
            name = runtime.hasClip("hover") ? "hover" : "";
        } else if (fading) {
            // 刚移出的那一张：播一次淡出，播完停在末帧
            name = runtime.hasClip("hover_fadeout") ? "hover_fadeout" : "";
            once = true;
        }
        // preview_animation 不在这里：它是**主体态**（替换 idle），由 ModelRuntime#pickClip 在
        // cardMode 下直接选中。当覆盖层叠在 idle 上会与 idle 抢同一批骨骼通道，
        // 作者写在里面的位置/缩放（官方 default 的 gui 片段就是 MAllBody.position=[0,-22,0]）也就失效了。
        runtime.setCardAnimation(name, once);
        // focus 是独立槽：卡片没有键盘焦点，这里表示「就是当前选中的那个模型」
        runtime.setCardFocus(isSelected && runtime.hasClip("focus") ? "focus" : "");
        return name;
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
            Ef.wipeCut(ctx, x, y, PG_W, PG_H, pal.chip(), pal.signal(), wipe, pal.cut(), Ef.Corner.BR);
        } else {
            Ef.cutFill(ctx, x, y, PG_W, PG_H, pal.chip(), pal.cut(), Ef.Corner.BR);
        }
        int ink = !enabled ? 0xFF5C5C5C : (hot ? pal.onSignal() : pal.ink());
        Ef.text(ctx, label, x + (PG_W - Ef.textWidth(label)) / 2, y + (PG_H - Ef.lineH()) / 2, ink);
    }

    private void paintFooter(VDrawContext ctx, int px, int py) {
        String count = view.size() + " 个模型";
        Ef.text(ctx, count, px + 2, py + PANEL_H - 9, 0xFF5C5C5C);
    }

    /**
     * 卡片悬停说明：模型名、作者、作者写的 tips。
     *
     * <p>作者与 tips 只有解析过的模型才有（元信息在反序列化之后才存在），没解析过的只显示名字 ——
     * 为了一个 tooltip 去解析整页模型不划算。
     */
    private void paintCardTooltip(VDrawContext ctx, int mouseX, int mouseY) {
        if (hovered < HOVER_CARD) {
            return;
        }
        int index = page * PER_PAGE + (hovered - HOVER_CARD);
        if (index < 0 || index >= view.size()) {
            return;
        }
        String file = view.get(index);
        List<String> lines = new ArrayList<>();
        lines.add(ModelCatalog.displayName(file));
        String[] meta = CustomModelManager.metadataOf(file);
        if (meta != null) {
            if (!meta[0].isEmpty()) {
                lines.add("作者 " + meta[0]);
            }
            if (!meta[1].isEmpty()) {
                lines.addAll(Ef.wrap(meta[1], 180));
            }
        }
        Ef.tooltip(ctx, pal, lines, mouseX, mouseY, this.width, this.height);
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
                int card = cardAt(mx, my);
                if (card >= 0) {
                    next = HOVER_CARD + (card - page * PER_PAGE);
                }
            }
        }
        if (next != hovered) {
            this.hovered = next;
            this.hoverStart = System.currentTimeMillis();
            trackCardHover(next);
        }
    }

    /**
     * 卡片悬停变化：移出时记下淡出起点与时长，让 {@code hover_fadeout} 播完再回 idle。
     *
     * <p>时长取片段自己的长度 —— 作者写多长就播多长，写死一个数会让长尾动画被截断。
     */
    private void trackCardHover(int next) {
        int card = next >= HOVER_CARD ? next - HOVER_CARD : -1;
        if (card == lastHoverCard) {
            return;
        }
        if (lastHoverCard >= 0) {
            int index = page * PER_PAGE + lastHoverCard;
            if (index < view.size()) {
                ModelRuntime runtime = CustomModelManager.cardRuntime(
                        CustomModelManager.cardStateId(view.get(index)));
                this.cardFadeSeconds = runtime == null ? 0.0F : runtime.clipLength("hover_fadeout");
                this.cardFadeStart = System.currentTimeMillis();
                this.cardFadeIndex = this.cardFadeSeconds > 0.0F ? lastHoverCard : -1;
            }
        }
        this.lastHoverCard = card;
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
        int next = Math.max(0, Math.min(pages - 1, this.page + step));
        if (next == this.page) {
            return;
        }
        this.page = next;
        // 翻页后上一页的实例没人用了：留着白占内存，而重建代价与首次加载相同
        retainPageCards();
        this.cardEnterStart = System.currentTimeMillis();
    }

    /**
     * 把当前页的文件名交给后台预建。
     *
     * <p>每帧调用：{@code prebuildCards} 对已建好、已备好、正在解析、已失败的文件都会跳过，
     * 所以全部就绪时它只是一次十项查表，不会重复起线程。
     */
    private void ensurePageCards() {
        pageFiles.clear();
        int base = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && base + i < view.size(); i++) {
            pageFiles.add(view.get(base + i));
        }
        CustomModelManager.prebuildCards(pageFiles);
    }

    /** 只保留当前页的卡片实例；顺带清掉它们的渲染状态，编号变了要重建。 */
    private void retainPageCards() {
        Set<String> keep = new HashSet<>();
        List<String> pageFiles = new ArrayList<>();
        int base = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && base + i < view.size(); i++) {
            String file = view.get(base + i);
            keep.add(file);
            pageFiles.add(file);
        }
        // 先把这一页的模型丢给后台线程解析：翻页那一帧就能出图，不用等十帧逐张补齐
        CustomModelManager.prebuildCards(pageFiles);
        CustomModelManager.retainCards(keep);
        cardStates.keySet().retainAll(keep);
        cardAnimations.keySet().retainAll(keep);
        this.lastHoverCard = -1;
        this.cardFadeIndex = -1;
        this.cardFadeSeconds = 0.0F;
    }

    private boolean wheel(EfWidget self, double mouseX, double mouseY, double amount) {
        if (pageCount() <= 1 || amount == 0.0D) {
            return false;
        }
        turnPage(amount > 0.0D ? -1 : 1);
        return true;
    }

}
