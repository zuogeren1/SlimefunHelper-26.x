package me.zuogeren.slimefunhelperplus.module.conflux.hooks;

import java.util.List;

/**
 * 全屏地图界面那一侧暴露给位置菜单控制器的「追加菜单行」入口。
 *
 * <h2>为什么需要这个接口</h2>
 * conflux 0.1.16 起，按钮是在 {@code MapLocationMenuController#addLocationMenuButtons()} 里按
 * <b>控制器自己的</b> {@code locationMenuButtonSpecs(...)} 返回值造的；屏幕上那个同名方法只是一句转发。
 * 所以追加必须发生在控制器那一次调用上，否则列表里没有我们的条目 —— 按钮根本不会创建
 * （点击仍然有效，因为命中判定在我们自己的注入里，于是会出现「点得到、看不见」）。
 *
 * <p>但标签宽度要读包级私有的 {@code FullscreenMapLocationMenu$Bounds}，那段代码只能留在屏幕的 mixin 里
 * （合并进 {@code FullscreenMapScreen} 之后才有同包访问权）。这个接口就是两边之间的通道：
 * 屏幕 mixin {@code implements} 它，控制器 mixin 用 {@code instanceof} 拿到实现再调用。
 * 调用点虽然在控制器的方法体里，实际执行的方法体属于屏幕的类，包级私有访问照样成立。
 */
public interface ConfluxMenuRowHost {
    /**
     * 把我们的条目就地追加进他们刚造好的按钮列表。
     *
     * @param specs       控制器返回的 {@code List<ButtonSpec>}（就是他们随后逐条造按钮的那个列表）
     * @param nativeCount 追加之前他们自己的条目数（面板几何、命中判定、日志都以它为准）
     */
    void slimefunhelper$appendMenuRows(List<?> specs, int nativeCount);
}
