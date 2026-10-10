package me.matl114.hooks.impl.confluxmap;

import java.lang.reflect.Constructor;

/**
 * conflux-map 位置菜单里的一条按钮描述（{@code FullscreenMapLocationMenu$ButtonSpec}）的构造桥。
 *
 * <p>为什么必须走反射：真类里的 {@code ButtonSpec} 是<b>包级私有</b>的 record，而本模块的类在
 * {@code me.matl114.hooks.*} 包下，javac 连「引用这个类型」这一步都不允许（实测：
 * 用真 jar 的 class 做 classpath，{@code new ButtonSpec(...)} 直接报「ButtonSpec 在 ... 中不是公共的」）。
 * Mixin 只能把代码合并进目标类，不能替我们新建一个包级私有的嵌套类型，所以唯一可行路径是
 * 运行期反射构造。
 *
 * <p>这个类因此只依赖 {@code Class} / {@code Object}，完全不出现 conflux-map 的类型，
 * 也就不会把包级私有访问带出 mixin。
 */
public final class ConfluxMenuButtonSpec {
    /** ButtonSpec 的规范构造器：{@code (Action, String labelKey, String tooltipKey, boolean active)} */
    private static Constructor<?> cached;
    private static boolean unavailable;

    private ConfluxMenuButtonSpec() {}

    /**
     * 造一条 {@code ButtonSpec}。
     *
     * @param specClass 运行期从目标类上取到的 {@code ButtonSpec} 类型
     * @param action    必须是一个<b>已存在</b>的 {@code Action} 常量（枚举是闭集，不能新增值）。
     *                  它不会被执行：列表里的这一行由我们自己的鼠标 / 按键注入先手接管，
     *                  原生按钮只会被画出来。
     * @param labelKey  翻译 key（{@code Texts.translatable(labelKey)} 渲染成按钮文字）
     * @param tooltipKey 悬浮提示的翻译 key，可以为 {@code null}
     * @param active    是否激活（与原生条目同一语义）
     * @return ButtonSpec 实例；反射失败返回 {@code null}（调用方保持原列表不变）
     */
    public static Object create(
            Class<?> specClass, Object action, String labelKey, String tooltipKey, boolean active) {
        if (specClass == null || action == null || unavailable) {
            return null;
        }
        try {
            Constructor<?> constructor = cached;
            if (constructor == null || constructor.getDeclaringClass() != specClass) {
                constructor = specClass.getDeclaredConstructor(
                        action.getClass(), String.class, String.class, boolean.class);
                constructor.setAccessible(true);
                cached = constructor;
            }
            return constructor.newInstance(action, labelKey, tooltipKey, active);
        } catch (Throwable e) {
            // 只可能发生在 conflux-map 改了 ButtonSpec 形状时；标记不可用，避免每帧重试刷屏
            unavailable = true;
            return null;
        }
    }
}
