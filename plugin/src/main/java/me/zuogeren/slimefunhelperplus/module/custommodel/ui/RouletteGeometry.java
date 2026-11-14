// Source: https://github.com/sdf123098/Sparkle-Morpher (common/src/main/java/com/micaftic/morpher/core/gui/RouletteGeometry.java)
// Branch/commit: fa26.2 @ 816235f2
// Copyright (c) 2026 Marciken / MIT
package me.zuogeren.slimefunhelperplus.module.custommodel.ui;

/**
 * 轮盘扇区几何：一块由四条直线边围成的扇环（梯形），光栅化与命中判定共用同一份多边形。
 *
 * <p>角度以 +X 为 0、顺时针增长（屏幕 y 向下，与 {@link Math#atan2} 的符号一致）；
 * 圆心与内外半径都由调用方给，所以命中判定用屏幕坐标算出的多边形，
 * 与逐行光栅化出来的形状严格是同一块——不会出现「看得见却点不中」。
 */
public final class RouletteGeometry {

    private RouletteGeometry() {}

    /** 圆心、内外半径与起止角给出的扇环，顶点顺序：内起、外起、外止、内止。 */
    public static double[][] sector(
            double centerX, double centerY, double inner, double outer, double startAngle, double endAngle) {
        double cosStart = Math.cos(startAngle);
        double sinStart = Math.sin(startAngle);
        double cosEnd = Math.cos(endAngle);
        double sinEnd = Math.sin(endAngle);
        return new double[][]{
                {centerX + inner * cosStart, centerY + inner * sinStart},
                {centerX + outer * cosStart, centerY + outer * sinStart},
                {centerX + outer * cosEnd, centerY + outer * sinEnd},
                {centerX + inner * cosEnd, centerY + inner * sinEnd}};
    }

    /** 点是否落在多边形里；扇区是凸的，求出该行的左右边界就够。 */
    public static boolean contains(double[][] polygon, double x, double y) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i];
            double[] b = polygon[(i + 1) % polygon.length];
            if ((a[1] <= y && b[1] > y) || (b[1] <= y && a[1] > y)) {
                double edge = a[0] + (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1]);
                lo = Math.min(lo, edge);
                hi = Math.max(hi, edge);
            }
        }
        return Double.isFinite(lo) && x >= lo && x < hi;
    }

    /** 某一行（{@code y+0.5} 采样）的水平跨度，返回 {@code [x0, x1)}；没有跨度返回 null。 */
    public static int[] span(double[][] polygon, int y) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        double scan = y + 0.5D;
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i];
            double[] b = polygon[(i + 1) % polygon.length];
            if ((a[1] <= scan && b[1] > scan) || (b[1] <= scan && a[1] > scan)) {
                double x = a[0] + (scan - a[1]) * (b[0] - a[0]) / (b[1] - a[1]);
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
            }
        }
        return Double.isFinite(lo) ? new int[]{(int) Math.ceil(lo - 0.5D), (int) Math.ceil(hi - 0.5D)} : null;
    }
}