package me.zuogeren.slimefunhelperplus.module.custommodel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import me.matl114.managers.config.StringRef;
import me.matl114.utils.config.BaseAttrKeyValue;
import me.matl114.utils.config.kv.EnumAttrKeyValue;

/**
 * 带候选值的字符串配置：宿主 ClickGui 会把它画成下拉而不是文本框。
 *
 * <p>宿主对 {@code StringRef} 一律走文本框（{@code AttrKeyValue.str}），
 * 而 {@code EnumAttrKeyValue} 走 {@code generateSwitchingButton} 那套下拉。
 * 这里覆写 {@link #_createKeyValue0} 返回后者，就能在不改宿主代码的前提下拿到下拉。
 *
 * <p>候选是惰性取的（{@link Supplier}）：模型目录扫描完才有一份完整列表，
 * 构造时定死会拿到空表。
 */
public class StringChoiceRef extends StringRef {

    private final Supplier<List<String>> choices;

    public StringChoiceRef(String value, Supplier<List<String>> choices) {
        super(value);
        this.choices = choices;
    }

    /**
     * 只认自己：{@code StringRef.isSameTypeWith} 对任何 {@code StringRef} 都返回 true，
     * 那样 {@code Config.getOrCreate} 会复用配置里已有的普通 {@code StringRef}，
     * 我们的下拉行为就丢了。这里收紧成「同类才复用」。
     */
    @Override
    public <W> boolean isSameTypeWith(me.matl114.managers.config.Ref<W> ref) {
        return ref instanceof StringChoiceRef;
    }

    @Override
    public BaseAttrKeyValue<String> _createKeyValue0(String key) {
        // 每次建 keyValue 都重新取一次候选：目录可能在两次打开界面之间变过
        Map<String, String> map = new LinkedHashMap<>();
        for (String choice : choices.get()) {
            map.put(choice, choice);
        }
        if (map.isEmpty()) {
            // 一个候选都没有时退回文本框，否则下拉是空的、点开什么也选不了
            return super._createKeyValue0(key);
        }
        return new EnumAttrKeyValue<>(key, get(), String.class, map);
    }
}
