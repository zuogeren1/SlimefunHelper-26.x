package me.zuogeren.slimefunhelperplus.module.custommodel.audio;

import java.util.List;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;

/**
 * 一条片段的音效游标：按片段内时间把「已经越过」的音效关键帧放出去。
 *
 * <p>与 {@code ModelRuntime} 的 timeline 游标同构（{@code runTimeline}）：逐帧增量推进，
 * 只触发游标之后、时间戳 {@code <= time} 的那几条；片段循环回卷（{@code time} 比上一帧小）时
 * 先把上一轮剩下的补完再把游标归零。差别只在「触发」这一步 —— timeline 跑 Molang 语句，
 * 这里放声音。
 *
 * <p>首帧没有上一轮可补，直接从片段开头算（{@code time} 为 NaN 的分支），否则从中间接上的
 * 片段会把开头那几条音效一次性全放出来。
 *
 * <p>游标按「层 + 片段名」各持一份，由 {@link ModelSoundCursorTable} 管理；本类只管一条。
 */
public final class ModelSoundCursor {

    private final List<RawYsmModel.RawSoundEffect> keyframes;

    private int next;

    private float time = Float.NaN;

    public ModelSoundCursor(List<RawYsmModel.RawSoundEffect> keyframes) {
        this.keyframes = keyframes;
    }

    public boolean isEmpty() {
        return keyframes == null || keyframes.isEmpty();
    }

    /**
     * 推进到片段内时间 {@code time}，把越过的关键帧交给 {@code sink}。
     *
     * <p>{@code sink} 为 null 时只推进游标不出声 —— 用于「模型没在渲染但时间还在走」的场合，
     * 避免切回视野时把积压的音效一次性放出来。
     */
    public void advance(float time, SoundSink sink) {
        if (isEmpty()) {
            return;
        }
        if (Float.isNaN(this.time)) {
            // 首帧没有上一轮可补，直接从片段开头算
            this.next = 0;
        } else if (time < this.time) {
            // 循环回卷：先把上一轮剩下的补完，再把游标归零
            if (sink != null) {
                for (int i = this.next; i < keyframes.size(); i++) {
                    fire(keyframes.get(i), sink);
                }
            }
            this.next = 0;
        }
        while (this.next < keyframes.size() && keyframes.get(this.next).timestamp <= time) {
            if (sink != null) {
                fire(keyframes.get(this.next), sink);
            }
            this.next++;
        }
        this.time = time;
    }

    /** 片段重播（换片段、重进状态）时归零，让下一帧从开头重新触发。 */
    public void reset() {
        this.next = 0;
        this.time = Float.NaN;
    }

    private static void fire(RawYsmModel.RawSoundEffect keyframe, SoundSink sink) {
        if (keyframe == null || keyframe.effectName == null) {
            return;
        }
        sink.play(keyframe.effectName);
    }

    /** 音效出口：把名字交给 {@link ModelSound} 解析成自带 ogg 或原版事件。 */
    @FunctionalInterface
    public interface SoundSink {
        void play(String effectName);
    }
}
