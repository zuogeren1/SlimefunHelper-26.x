package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.YsmAnimationParsing;

/**
 * 内置默认模型的动画表：自定义模型缺失某个片段时，由 {@code ModelRuntime#clip(String)} 退回这里。
 *
 * <p>取材是 YSM 自带 default 模型的 {@code main} + {@code arm} 两份 Bedrock 动画 JSON
 * （{@code assets/slimefunhelperplus/custommodel/default/}）—— YSM 的约定就是「模型没有动画文件，
 * 或只缺一部分动画时，用默认模型的同名动画补齐」。两份文件合成一个命名空间，
 * 后加载的覆盖先加载的，与 YSM 把 main/arm 塞进同一个命名空间的做法一致。
 *
 * <p>只读、只加载一次：模型重载会重建 {@link ModelRuntime}，但这份表是纯数据，没必要跟着重解析。
 * 读不到资源不算错误 —— 没有兜底也只是回到「模型自己有什么播什么」。
 */
final class DefaultAnimations {

    private static final String ROOT = "/assets/slimefunhelperplus/custommodel/default/";
    private static final String[] FILES = {"main.animation.json", "arm.animation.json"};

    private static Map<String, RawYsmModel.RawAnimation> cache;

    private DefaultAnimations() {
    }

    static synchronized Map<String, RawYsmModel.RawAnimation> clips() {
        if (cache != null) {
            return cache;
        }
        Map<String, RawYsmModel.RawAnimation> loaded = new LinkedHashMap<>();
        int parsed = 0;
        for (String file : FILES) {
            byte[] data = read(ROOT + file);
            if (data == null) {
                continue;
            }
            try {
                RawYsmModel.RawAnimationFile animationFile = YsmAnimationParsing.parseAnimationFile(data);
                parsed++;
                for (Map.Entry<String, RawYsmModel.RawAnimation> entry : animationFile.animations.entrySet()) {
                    entry.getValue().name = entry.getKey();
                    loaded.put(entry.getKey(), entry.getValue());
                }
            } catch (RuntimeException error) {
                CustomModelModule.logDebug("[SFH+] 默认动画解析失败 " + file + ": " + error);
            }
        }
        cache = Collections.unmodifiableMap(loaded);
        // 一次性：实机只靠这一行确认兜底表到底有没有进包
        CustomModelModule.logDebug("[SFH+] defaultClips=" + cache.size() + " files=" + parsed);
        return cache;
    }

    private static byte[] read(String path) {
        try (InputStream stream = DefaultAnimations.class.getResourceAsStream(path)) {
            if (stream == null) {
                CustomModelModule.logDebug("[SFH+] 默认动画资源缺失 " + path);
                return null;
            }
            return stream.readAllBytes();
        } catch (IOException error) {
            CustomModelModule.logDebug("[SFH+] 默认动画读取失败 " + path + ": " + error);
            return null;
        }
    }
}
