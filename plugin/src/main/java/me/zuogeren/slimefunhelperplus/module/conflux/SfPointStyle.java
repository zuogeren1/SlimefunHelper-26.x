package me.zuogeren.slimefunhelperplus.module.conflux;

import me.zuogeren.slimefunhelperplus.module.conflux.probe.WaypointCaptureProbe.CapturedPoint;

/**
 * 捕获点 → conflux 视觉/坐标的共用转换。
 *
 * <p>标记（{@code MarkerApi}）与路径点（{@code WaypointApi}）两条路都要同一套规则 ——
 * 名字压平、颜色序号转 ARGB、预乘的坐标除回去。放在一个地方，避免两边各写一份之后慢慢漂移。
 */
public final class SfPointStyle {

    /** 全屏地图会把 label 原样单行画出来，太长会横穿屏幕 */
    private static final int MAX_LABEL_LENGTH = 24;

    /** conflux 对空 label 画的是问号；我们也用同一个兜底 */
    private static final String UNKNOWN_LABEL = "?";

    /**
     * Xaero {@code WaypointColor} 的十六进制值，按枚举序号排列。
     *
     * <p>顺序即宿主传入的 {@code color} 语义（{@code WaypointColor.ordinal()}），
     * 16 个值取自 xaerominimap 26.5.1 的 {@code WaypointColor#<clinit>}（javap 逐条核过，
     * 含 {@code sipush} 与 {@code iconst_m1} 的那几个）。
     */
    private static final int[] XAERO_COLORS = {
        -16777216, -16777046, -16733696, -16733526,
        -5636096, -5635926, -22016, -5592406,
        -11184811, -11184641, -11141291, -11141121,
        -65536, -43521, -171, -1
    };

    /** 颜色序号越界时的兜底（白色） */
    private static final int FALLBACK_COLOR = -1;

    private SfPointStyle() {}

    /**
     * 把宿主的点名字压成一条能直接画出来的标签。
     *
     * <p>SearchLabel 的名字是多行的（{@code Label:\nBlock:…}），而 conflux 的方框只取首字符、
     * 全屏又把整条单行画出来 —— 不压平的话方框里会出现换行符那个怪字形。
     */
    public static String label(CapturedPoint point) {
        String raw = point.name();
        if (raw == null || raw.isEmpty()) {
            return UNKNOWN_LABEL;
        }
        StringBuilder text = new StringBuilder(Math.min(raw.length(), MAX_LABEL_LENGTH));
        for (int i = 0; i < raw.length() && text.length() < MAX_LABEL_LENGTH; ++i) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                text.append(' ');
            } else if (c >= 0x20 && c != 0x7f && c != '\u00a7') {
                text.append(c);
            }
        }
        String label = text.toString().trim();
        return label.isEmpty() ? UNKNOWN_LABEL : label;
    }

    /** Xaero 颜色序号 → ARGB（conflux 只用 RGB，alpha 会被强制不透明） */
    public static int color(int index) {
        return index >= 0 && index < XAERO_COLORS.length ? XAERO_COLORS[index] : FALLBACK_COLOR;
    }

    /**
     * 宿主口径 → conflux 口径的除数。
     *
     * <p>conflux 存的是「该维度的原始方块坐标」。宿主里 StructureLabel / SearchLabel / Warps
     * 在提交前乘过 {@code coordinateScale}（下界 ×8），EntityLog 与旅行点没乘 ——
     * 所以只对乘过的那批除回去。
     */
    public static double coordinateScale(CapturedPoint point) {
        return point.preScaled() && point.coordinateScale() > 0 ? point.coordinateScale() : 1.0;
    }
}
