package me.zuogeren.slimefunhelperplus.module.custommodel.audio;

import java.util.HashMap;
import java.util.Map;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;

/**
 * 一个模型实例的音效游标表：层 + 片段名 → {@link ModelSoundCursor}。
 *
 * <p>键与 {@code ModelRuntime} 的 timeline 游标一致（{@code CursorKey(layer, clipName)}）：
 * 同一个片段可能同时被主层和控制器层播放，各层的时间基准不同，共用一条游标会让其中一层
 * 永远不触发音效。
 *
 * <p>本类只提供「按层推进」的入口，不持有任何渲染状态 —— 由 {@code ModelRuntime} 在它推进
 * timeline 的同一处调用，保证音效与动画用同一个片段内时间。
 */
public final class ModelSoundCursorTable {

    private final ModelSound sound;

    private final Map<CursorKey, ModelSoundCursor> cursors = new HashMap<>();

    private final ModelSoundCursor.SoundSink sink;

    public ModelSoundCursorTable(ModelSound sound) {
        this.sound = sound;
        this.sink = sound == null ? null : effectName -> sound.play(effectName, 1.0F);
    }

    /**
     * {@code sound} 为 null（没有模型可挂）时返回 null；否则一律建表。
     *
     * <p>不能按「有没有自带 ogg」来判空：音效关键帧里还有 {@code minecraft:} 开头的原版事件，
     * 没有 ogg 的模型同样要靠这张表把它们放出去。
     */
    public static ModelSoundCursorTable build(ModelSound sound) {
        return sound == null ? null : new ModelSoundCursorTable(sound);
    }

    public ModelSound sound() {
        return sound;
    }

    /**
     * 把 {@code clip} 在 {@code layer} 这一层的音效推进到片段内时间 {@code time}。
     *
     * <p>{@code time} 必须与 {@code ModelRuntime#runTimeline} 收到的那个值相同 —— 两者用同一份
     * 片段内时间，音效才会和动作对齐。
     */
    public void advance(String layer, RawYsmModel.RawAnimation clip, float time) {
        if (sound == null || !sound.isActive() || clip == null) {
            return;
        }
        if (clip.soundEffects == null || clip.soundEffects.isEmpty()) {
            return;
        }
        ModelSoundCursor cursor = cursors.computeIfAbsent(new CursorKey(layer, clipName(clip)),
                key -> new ModelSoundCursor(clip.soundEffects));
        cursor.advance(time, sink);
    }

    /** 模型卸载：停掉还在解码的播放并释放字节引用。 */
    public void release() {
        cursors.clear();
        if (sound != null) {
            sound.release();
        }
    }

    private static String clipName(RawYsmModel.RawAnimation clip) {
        return clip == null || clip.name == null ? "none" : clip.name;
    }

    /** 时间轴游标的键：层标识 + 片段名。 */
    private record CursorKey(String layer, String clip) {}
}
