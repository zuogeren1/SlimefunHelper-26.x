package me.zuogeren.slimefunhelperplus.module.custommodel.ui;

import me.zuogeren.slimefunhelperplus.module.custommodel.ui.Ef;

/**
 * 轮盘的版式与配色：一页 8 个槽位围成一圈，槽与槽之间留 2° 空隙，悬停那一格向外鼓出一圈。
 *
 * <p>比例照 YSM 客户端轮盘取（内圈 25 / 外圈 105 / 文字半径 65）：一圈窄梯形贴着中心，
 * 中间留空，角色不会被挡；缝隙比描边便宜，也是原版最好认的地方。整块按设计尺寸等比缩到当前窗口，
 * 但字号不跟着缩——屏幕再小标签也还读得出来，只是一行能放的字变少。
 */
public final class RouletteTheme {

    private RouletteTheme() {}

    /** 一页的槽位数。 */
    public static final int SLOTS = 8;
    /** 相邻两格的夹角。 */
    public static final double SLOT_ANGLE = Math.PI * 2.0D / SLOTS;
    /** 每格两侧各让出的角度。 */
    public static final double SLOT_GAP = Math.toRadians(2.0D);
    /** 第 0 格的中心方向：+X 再转半格，第一格落在右下方。 */
    public static final double FIRST_ANGLE = SLOT_ANGLE / 2.0D;

    public static final float WHEEL_INNER_R = 26.0F;
    public static final float WHEEL_OUTER_R = 106.0F;
    /** 悬停时外沿多鼓出来的一圈。 */
    public static final float WHEEL_HOVER_R = 116.0F;
    /** 文字与圆心之间的距离：落在扇区中部。 */
    public static final float LABEL_R = 66.0F;

    /** 轮盘、两侧箭头、页码都落在这个设计框里，窗口比它小时整体缩。 */
    public static final int DESIGN_WIDTH = 300;
    public static final int DESIGN_HEIGHT = 280;
    public static final float MIN_SCALE = 0.5F;

    /** 翻页箭头：边长固定，缩到很小的窗口上也不至于点不中。 */
    public static final int ARROW_SIZE = 20;
    /** 箭头与轮盘外沿之间的空档。 */
    public static final int ARROW_GAP = 12;

    /** 背景只压暗一点：选动作时要看得见角色。 */
    public static final int VEIL = 0x40000000;

    /** 普通格底：半透明深色，世界还在底下透出来。 */
    public static final int SLICE = 0x90141414;
    /** 悬停时擦在格底上的一层强调色；叠在格底上，所以任何背景下浓淡都一样。 */
    public static int sliceHover(Ef.Palette p) {
        return Ef.alpha(p.signal(), 0.5F);
    }
    /** 已选中格：整块强调色，再叠上悬停时提亮。 */
    public static int sliceActive(Ef.Palette p) {
        return p.signal();
    }
    public static int sliceActiveHover(Ef.Palette p) {
        return Ef.mix(p.signal(), 0xFFFFFFFF, 0.35F);
    }
    /** 悬停格向外鼓出来的那一圈。 */
    public static int sliceLip(Ef.Palette p) {
        return Ef.alpha(p.signal(), 0.62F);
    }

    public static final int TEXT = 0xFFEDEDED;
    public static final int TEXT_DIM = 0xFFA3A3A3;
    /** 强调色底上的字用墨色，浅绿上白字看不清。 */
    public static int textActive(Ef.Palette p) {
        return p.onSignal();
    }

    /** 翻页箭头：底色 / 悬停底色 / 已经到头。 */
    public static final int ARROW_FILL = 0x90141414;
    public static int arrowHover(Ef.Palette p) {
        return p.signal();
    }
    public static final int ARROW_EDGE = 0xFFA3A3A3;
    public static final int ARROW_EDGE_DEAD = 0x30FFFFFF;
    public static final int ARROW_INK = 0xFFEDEDED;
    public static final int ARROW_INK_DEAD = 0x60FFFFFF;

    /** 页码条。 */
    public static final int PAGE_BG = 0xB0000000;
    public static final int PAGE_INK = 0xFFEDEDED;

    public static int labelInk(Ef.Palette p, boolean active) {
        return active ? textActive(p) : TEXT;
    }
}
