package me.zuogeren.slimefunhelperplus.module.custommodel.audio;

import java.util.LinkedHashMap;
import java.util.Map;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;
import net.minecraft.resources.Identifier;

/**
 * 一个模型自带的音效表：{@code soundFiles} 里的 ogg 字节 + 名字解析。
 *
 * <p>数据形态（离线核对 37 个语料模型）：{@code soundFiles} 是「名字 → ogg 字节」，
 * 433 条全部是 Ogg Vorbis（{@code OggS} + {@code vorbis} 头），不是 hash 引用 ——
 * {@code RawDataFile#hash} 只是内容摘要，字节本身就在包里。所以播放不需要再回读文件。
 *
 * <p>音效关键帧的 {@code effectName} 有两种写法：命中 {@code soundFiles} 的键就是模型自带音频；
 * 带命名空间冒号的（{@code minecraft:entity.player.attack.knockback}）是原版声音事件。
 * 两者都不命中的（语料里 15 个，多为作者漏打包）静默丢弃。
 *
 * <p>字节数组直接引用 {@link RawYsmModel} 里的那一份，不复制：语料里单个模型最多 64MB，
 * 复制一份等于把模型常驻内存翻倍。
 */
public final class ModelSound {

    /** 名字 → ogg 字节；空表表示这个模型没有自带音频。整体替换而不是原地清空，渲染线程可能正在查表。 */
    private volatile Map<String, byte[]> files;

    /** 模型是否还在用：{@link #release()} 之后正在跑的播放会在下一个 chunk 边界退出。 */
    private volatile boolean active = true;

    private ModelSound(Map<String, byte[]> files) {
        this.files = files;
    }

    /**
     * 从反序列化结果里取音效表。
     *
     * <p>没有自带 ogg 的模型也必须返回实例：音效关键帧里还有 {@code minecraft:} 开头的原版事件，
     * 返回 null 会让调用方把整条音效路径（含原版事件）一起跳过。只有 {@code model} 为 null
     * （没有模型可挂）时才返回 null。
     */
    public static ModelSound build(RawYsmModel model) {
        if (model == null) {
            return null;
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (model.soundFiles != null) {
            for (Map.Entry<String, RawYsmModel.RawDataFile> entry : model.soundFiles.entrySet()) {
                RawYsmModel.RawDataFile file = entry.getValue();
                if (entry.getKey() == null || file == null || file.data == null || file.data.length == 0) {
                    continue;
                }
                files.put(entry.getKey(), file.data);
            }
        }
        return new ModelSound(files);
    }

    public boolean isActive() {
        return active;
    }

    /** 模型卸载时调用：让还在解码的播放尽快收尾，并释放字节引用。 */
    public void release() {
        active = false;
        files = Map.of();
    }

    public int size() {
        return files.size();
    }

    /**
     * 播一条音效关键帧。
     *
     * <p>{@code volume} 是作者侧的音量（当前恒为 1.0，YSM 的音效关键帧不带音量字段）；
     * 玩家音量在 {@link ModelSoundPlayer} 里乘。
     */
    public void play(String effectName, float volume) {
        if (!active || effectName == null || effectName.isEmpty()) {
            return;
        }
        byte[] ogg = files.get(effectName);
        if (ogg != null) {
            ModelSoundPlayer.get().playCustom(this, ogg, volume);
            return;
        }
        // 带冒号的才是资源 id；不带冒号的未命中名字是作者漏打包，按原版事件解析只会白打一条警告
        if (effectName.indexOf(':') < 0) {
            return;
        }
        Identifier location = Identifier.tryParse(effectName);
        if (location != null) {
            ModelSoundPlayer.get().playVanilla(location, volume);
        }
    }
}
