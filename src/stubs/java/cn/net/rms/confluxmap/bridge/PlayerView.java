package cn.net.rms.confluxmap.bridge;

import cn.net.rms.confluxmap.core.model.DimensionId;

/**
 * 小地图 / 全屏地图那一帧的“观察者”视图（编译期桩，不参与打包）。
 *
 * <p>真实 jar 中为 {@code public record PlayerView(double x, double y, double z, double eyeY,
 * float yawDegrees, DimensionId dimension)}（javap 实测，0.1.7-26.2 / 0.1.9-26.2 / 0.1.9-26.1.2 一致）。
 * 小地图上它就是<b>视口中心的世界坐标</b>：{@code drawTiles} 用
 * {@code screenX = (key.originBlockX() - player.x()) * pxPerBlock}，
 * 路径点 / 自定义标记 / 传送门标记也都以 {@code player.x() / player.z()} 为原点 ——
 * 我们的区块边界覆盖层同样以它为投影中心（见 {@code ConfluxMapOverlay#minimapViewport}）。
 */
public record PlayerView(double x, double y, double z, double eyeY, float yawDegrees, DimensionId dimension) {}
