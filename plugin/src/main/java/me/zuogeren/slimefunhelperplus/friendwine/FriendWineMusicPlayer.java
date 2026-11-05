package me.zuogeren.slimefunhelperplus.friendwine;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import me.matl114.utils.Debug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundSource;

/**
 * 「朋友的酒」的背景乐播放器：直接读**音频文件**，不走声音事件 / sounds.json。
 *
 * <p>取值语义与 {@code SkyRender} 的背景图一致：默认是包内资源路径
 * （{@code slimefunhelperplus:music/friend_wine.ogg}），也可以写本地文件路径
 * （{@code D:/music/friend_wine.ogg}）——先按资源 id 找，找不到再按文件系统打开。
 *
 * <p>解码用原版自带的 {@link JOrbisAudioStream}（jorbis，纯 Java），解码出的 PCM 交给
 * {@link SourceDataLine} 播放：它不经过 {@code SoundEngine}，所以不需要注册声音事件，
 * 也不会因为 {@code SoundManager} 没加载好而静默失败。循环由本类自己重开文件实现。
 *
 * <p>音量跟随游戏的主音量（{@code SoundSource.MASTER}），因此玩家把主音量拖到 0 时
 * 这里也会静音；音乐音量条不影响它（避免"音乐条 0 → 完全没声音"这种坑）。
 */
public final class FriendWineMusicPlayer {
    private static final int CHUNK_BYTES = 8192;

    private Thread worker;
    private volatile boolean running;
    private volatile long startNanos;
    private volatile long bytesWritten;

    /** 是否正在放（线程还活着且已经打开了输出）。 */
    public boolean isPlaying() {
        return running;
    }

    /** 真正出声那一刻的 {@code System.nanoTime()}，视觉时钟拿它对齐节拍。 */
    public long startNanos() {
        return startNanos;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    public synchronized void play(String rawPath) {
        stop();
        this.running = true;
        this.bytesWritten = 0L;
        Thread thread = new Thread(() -> runLoop(rawPath), "sfh-friend-wine-music");
        thread.setDaemon(true);
        this.worker = thread;
        thread.start();
    }

    public synchronized void stop() {
        this.running = false;
        Thread thread = this.worker;
        this.worker = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop(String rawPath) {
        while (running) {
            SourceDataLine line = null;
            try (InputStream input = openStream(rawPath); JOrbisAudioStream stream = new JOrbisAudioStream(input)) {
                AudioFormat format = stream.getFormat();
                line = AudioSystem.getSourceDataLine(format);
                line.open(format, Math.max(CHUNK_BYTES * 2, (int) format.getSampleRate() / 5));
                line.start();
                this.startNanos = System.nanoTime();
                while (running) {
                    ByteBuffer buffer = stream.read(CHUNK_BYTES);
                    if (!buffer.hasRemaining()) {
                        break;
                    }
                    byte[] pcm = new byte[buffer.remaining()];
                    buffer.get(pcm);
                    applyMasterVolume(pcm);
                    line.write(pcm, 0, pcm.length);
                    this.bytesWritten += pcm.length;
                }
                line.flush();
            } catch (Throwable error) {
                Debug.info("[SFH+] friends-wine: 音乐播放失败（" + rawPath + "）：" + error);
                return;
            } finally {
                if (line != null) {
                    line.stop();
                    line.close();
                }
            }
        }
    }

    /** 先按资源 id 打开（包内音频），拿不到再当本地文件路径处理。 */
    private InputStream openStream(String rawPath) throws IOException {
        String value = rawPath == null ? "" : rawPath.trim();
        if (value.isEmpty()) {
            throw new IOException("音乐路径为空");
        }
        Identifier resourceId = Identifier.tryParse(value);
        if (resourceId != null) {
            var resource = Minecraft.getInstance().getResourceManager().getResource(resourceId);
            if (resource.isPresent()) {
                return new BufferedInputStream(resource.get().open());
            }
        }
        Path file = Path.of(value);
        if (Files.isReadable(file)) {
            return new BufferedInputStream(Files.newInputStream(file));
        }
        throw new IOException("既不是有效的资源 id，也打不开这个文件");
    }

    /** PCM 是小端 16 位有符号，按主音量整体缩放。 */
    private static void applyMasterVolume(byte[] pcm) {
        float volume = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER);
        if (volume >= 0.999F) {
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
