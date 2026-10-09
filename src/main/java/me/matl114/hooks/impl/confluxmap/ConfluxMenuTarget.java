package me.matl114.hooks.impl.confluxmap;

/**
 * conflux-map 右键位置菜单的点击目标，已经脱去 conflux-map 自己的类型，
 * 只留下本模块需要的信息。
 *
 * @param dimensionId 完整维度 id，形如 {@code minecraft:overworld}
 * @param worldPath   维度 path，形如 {@code overworld}，对应模板占位符 world
 * @param x           方块 X
 * @param y           方块 Y（优先取 conflux 的地面高度 + 1，未知时退回玩家当前 Y）
 * @param z           方块 Z
 */
public record ConfluxMenuTarget(String dimensionId, String worldPath, int x, int y, int z) {}
