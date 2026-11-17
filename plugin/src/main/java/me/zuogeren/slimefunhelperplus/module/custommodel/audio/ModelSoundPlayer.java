package me.zuogeren.slimefunhelperplus.module.custommodel.audio;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * 模型音效的输出端：自定义 ogg 解成 PCM 直接写 {@link SourceDataLine}，原版声音事件走 {@code SoundManager}。
 *
 * <p>自定义 ogg 不走 {@code SoundManager} 的原因：26.2 的 {@code SoundInstance} 没有
 * {@code getStream} 钩子，{@code SoundEngine#play} 固定用
 * {@code SoundBufferLibrary#getStream(sound.getPath(), ...)} 从资源包读 ogg，而
 * {@code SoundBufferLibrary} 的 {@code ResourceProvider} 是 {@code SoundManager} 在 reload 时
 * 按 {@code sounds} 目录快照出来的私有 map —— 模型自带的 ogg 既不在资源包里，也没法在不加
 * mixin / 资源包的前提下塞进去。解码器仍用原版自带的 {@link JOrbisAudioStream}（jorbis，纯 Java），
 * 与 {@code FriendWineMusicPlayer} 同一条路。
 *
 * <p>音量跟随 {@code SoundSource.PLAYERS}：模型音效属于玩家音效，玩家把「玩家」音量拖到 0
 * 时这里也静音。不做距离衰减 —— 自定义模型只渲染本地玩家自己，声源与听者恒在同一位置。
 */
public final class ModelSoundPlayer {

    private static final ModelSoundPlayer INSTANCE = new ModelSoundPlayer();

    /** 一次读多少 PCM 字节；也是「模型卸载后多久静音」的上界（32kHz 单声道约 0.13 秒）。 */
    private static final int CHUNK_BYTES = 8192;

    /** 同时出声的条数上限：音效关键帧可能在循环回卷时成串触发，不设上限会一次开几十条线。 */
    private static final int MAX_CONCURRENT = 4;

    /** 排队上限：超过就丢弃，宁可少响一条也不让解码线程堆积。 */
    private static final int MAX_PENDING = 8;

    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT, runnable -> {
        Thread thread = new Thread(runnable, "sfh-model-sound");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicInteger pending = new AtomicInteger();

    private ModelSoundPlayer() {}

    public static ModelSoundPlayer get() {
        return INSTANCE;
    }

    /**
     * 播一条模型自带的 ogg。
     *
     * <p>{@code owner} 只用来判断「模型还在不在」：{@link ModelSound#release()} 之后
     * 正在跑的播放会在下一个 chunk 边界退出，不需要逐条持有播放句柄。
     */
    public void playCustom(ModelSound owner, byte[] ogg, float volume) {
        if (owner == null || ogg == null || ogg.length == 0 || volume <= 0.0F) {
            return;
        }
        if (pending.get() >= MAX_PENDING) {
            return;
        }
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    run(owner, ogg, volume);
                } catch (Throwable error) {
                    CustomModelModule.logDebug("[SFH+] model-sound: 播放失败：" + error);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (Throwable rejected) {
            pending.decrementAndGet();
        }
    }

    /** 播一条原版声音事件（语料里 {@code minecraft:entity.player.attack.knockback} 这类引用）。 */
    public void playVanilla(Identifier location, float volume) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || location == null || volume <= 0.0F) {
            return;
        }
        // 相对坐标 + 无衰减：声源就是听者自己，走线性衰减会按 0,0,0 到相机的距离算成听不见。
        SimpleSoundInstance instance = new SimpleSoundInstance(location, SoundSource.PLAYERS, volume, 1.0F,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
                0.0, 0.0, 0.0, true);
        // 调用点在渲染线程（submitCustomGeometry 回调）里：SoundEngine#play 会阻塞等声音线程，
        // 直接在渲染线程上等会卡帧，投递到主线程再播（SPM 的 AudioPlayerManager 也是这么做的）。
        if (minecraft.isSameThread()) {
            minecraft.getSoundManager().play(instance);
        } else {
            minecraft.execute(() -> minecraft.getSoundManager().play(instance));
        }
    }

    private void run(ModelSound owner, byte[] ogg, float volume) throws Exception {
        SourceDataLine line = null;
        try (InputStream input = new ByteArrayInputStream(ogg);
                JOrbisAudioStream stream = new JOrbisAudioStream(input)) {
            AudioFormat format = stream.getFormat();
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, Math.max(CHUNK_BYTES * 2, (int) format.getSampleRate() / 5));
            line.start();
            while (owner.isActive()) {
                ByteBuffer buffer = stream.read(CHUNK_BYTES);
                if (!buffer.hasRemaining()) {
                    break;
                }
                byte[] pcm = new byte[buffer.remaining()];
                buffer.get(pcm);
                applyVolume(pcm, volume * playersVolume());
                line.write(pcm, 0, pcm.length);
            }
            line.flush();
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
        }
    }

    private static float playersVolume() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return 1.0F;
        }
        // getFinalSoundSourceVolume 已经乘过 MASTER；用 getSoundSourceVolume 会让主音量条失效
        return minecraft.options.getFinalSoundSourceVolume(SoundSource.PLAYERS);
    }

    /** PCM 是小端 16 位有符号，按音量整体缩放。 */
    private static void applyVolume(byte[] pcm, float volume) {
        if (volume >= 0.999F) {
            return;
        }
        if (volume <= 0.0F) {
            Arrays.fill(pcm, (byte) 0);
            return;
        }
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] & 0xFF) << 8);
            sample = Math.round(sample * volume);
            pcm[i] = (byte) sample;
            pcm[i + 1] = (byte) (sample >> 8);
        }
    }
}