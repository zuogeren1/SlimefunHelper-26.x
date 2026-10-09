package me.matl114.hooks.impl.confluxmap;

import java.util.function.Consumer;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * conflux-map 右键位置菜单里我们自己面板上的一条条目。
 * label 在收集阶段就已经渲染成纯文本，运行期只需要画出来。
 */
@Getter
@Accessors(fluent = true)
public class ConfluxMenuContext {
    final String label;
    final Consumer<ConfluxMenuTarget> action;

    public ConfluxMenuContext(String label, Consumer<ConfluxMenuTarget> action) {
        this.label = label;
        this.action = action;
    }

    /** 与 Xaero 右键菜单一致：动作抛异常只当作没生效，不影响地图界面 */
    public void accept(ConfluxMenuTarget target) {
        try {
            action.accept(target);
        } catch (Throwable ignored) {
        }
    }
}
