package me.zuogeren.slimefunhelperplus.module.friendwine;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.List;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.config.StringRef;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.config.PluginConfig;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 「朋友的酒」：让所有生物像果冻一样纵向伸缩并绕自身竖直轴旋转，同时循环播放处理过的曲子。
 *
 * <p>纯渲染期变换：只改 {@code LivingEntityRenderer#submit} 里的 {@link PoseStack}，
 * 不动模型骨骼、实体坐标、碰撞箱与 AI，服务端和存档都无感。变换由
 * {@code me.zuogeren.slimefunhelperplus.mixin.LivingEntityRendererMixin} 施加，
 * 只包住「模型 + 装备层」那一段，名字牌、拴绳、影子、冒火与第一人称手都不受影响。
 *
 * <p><b>卡点</b>：包内曲目经 atempo 从原曲 130 BPM 拉伸到 **128 BPM**、切成整数拍（405 拍）循环，
 * 一拍 = 一次弹动。视觉相位不以实体自己的 {@code ageTicks} 为准，而是以**音乐真正开始出声的时刻**为 0，
 * 这样所有生物同拍且与音乐对齐；音乐关掉时退回模块打开的时刻。
 *
 * <p><b>音乐</b>直接用 {@link FriendWineMusicPlayer} 读音频文件（包内资源路径或本地文件路径都可），
 * 不走声音事件；路径语义与 {@code SkyRender} 的背景图一致。
 */
public class FriendWine extends BaseModule {
    public static FriendWine INSTANCE;

    private static final Config CONFIG = PluginConfig.get();

    /** 包内曲目的速度：一拍一次弹动。 */
    private static final double BEATS_PER_SECOND = 128.0D / 60.0D;

    private static final String DEFAULT_MUSIC_PATH = "slimefunhelperplus:music/friend_wine.ogg";

    public final ModulePath root = makePath(CONFIG, "friend-wine");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    /** {@code false} = 黑名单（名单里的生物保持原样），{@code true} = 白名单（只有名单里的生物变形）。 */
    public final FlagRef entityWhiteList =
            flagBuilder(root.add("entity-white-list")).build();

    /** 生物名单：默认空集合，黑名单模式下等于「所有生物都变形」。 */
    public final NBTRef<EntrySet<EntityType<?>>> entityList = builder(
                    root.add("entity-list"), EntrySet.<EntityType<?>>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.ENTITY_TYPE, List.of()))
            .build();

    /** 每秒绕自身竖直轴转过的角度（度/秒）。 */
    public final DoubleRef spinSpeed = doubleBuilder(root.add("spin-speed"))
            .defaultValue(300.0D)
            .validator(Configs.doubleRange(0.0D, 1080.0D))
            .build();

    /** 纵向伸缩幅度：0.35 表示在 65% ~ 135% 之间来回。 */
    public final DoubleRef stretchAmplitude = doubleBuilder(root.add("stretch-amplitude"))
            .defaultValue(0.35D)
            .validator(Configs.doubleRange(0.0D, 0.9D))
            .build();

    public final FlagRef playMusic = flagBuilder(root.add("play-music"))
            .updateListener(this::onPlayMusicChanged)
            .build();

    /**
     * 音乐文件：包内资源路径（默认）或本地文件路径。
     *
     * <p>先按资源 id 找（{@code 命名空间:路径}，指向 {@code assets/<命名空间>/<路径>}），
     * 找不到再当文件系统路径打开，所以 {@code D:/music/xxx.ogg} 这种写法也能用。
     */
    public final StringRef musicPath = builder(root.add("music-path"), String.class)
            .defaultValue(DEFAULT_MUSIC_PATH)
            .updateListener(this::onMusicPathChanged)
            .build();

    private final FriendWineMusicPlayer musicPlayer = new FriendWineMusicPlayer();

    private long clockStartNanos = System.nanoTime();

    public FriendWine() {
        super("FriendWine");
        bindFlag(enable);
        INSTANCE = this;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        // 换世界/换维度时重新对齐：旧曲子的播放位置已经不属于这个场景
        registerListener(Listener.getWorldSwitchPoint(), this::onWorldSwitch);
    }

    @Override
    public void onEnableModule() {
        super.onEnableModule();
        restartClock();
        startMusic();
    }

    @Override
    public void onDisableModule() {
        stopMusic();
        super.onDisableModule();
    }

    /**
     * 这个生物这一帧要不要变形。
     *
     * <p>列表的 {@code test} 是「在集合里」，两种模式互为取反：
     * 白名单命中才变形，黑名单命中反而不变形。
     */
    public boolean shouldApply(LivingEntityRenderState state) {
        if (!isActive() || state == null) {
            return false;
        }
        boolean listed = entityList.get().test(state.entityType);
        return entityWhiteList.get() == listed;
    }

    /**
     * 把伸缩与旋转乘到渲染栈上。
     *
     * <p>调用点位于 {@code LivingEntityRenderer#submit} 的最外层，此时栈原点就是生物脚底，
     * 所以纵向缩放不会把脚压进地面，绕 Y 轴的旋转也正好是「原地转」。
     * 横向用 {@code 1 / sqrt(k)} 反向收缩，近似保体积，看起来才像果冻而不是整体放大。
     */
    public void applyTransform(PoseStack poseStack) {
        double seconds = elapsedSeconds();
        double wave = Math.sin(Math.PI * 2.0D * BEATS_PER_SECOND * seconds);
        double vertical = 1.0D + stretchAmplitude.get() * wave;
        float horizontal = (float) (1.0D / Math.sqrt(vertical));
        float spin = (float) ((seconds * spinSpeed.get()) % 360.0D);
        // 26.3 把 PoseStack.mulPose(Quaternionfc) 改名为 rotate；两者内部都是 last().rotate(by)，
        // 走 last().pose() 可以让插件三个版本共用一份源码，不必引入 //#if。
        poseStack.last().pose().rotate(Axis.YP.rotationDegrees(spin));
        poseStack.scale(horizontal, (float) vertical, horizontal);
    }

    /** 视觉相位：音乐在放就用「出声那一刻」当 0（卡点），否则用模块打开那一刻。 */
    private double elapsedSeconds() {
        long start = this.clockStartNanos;
        if (musicPlayer.isPlaying() && musicPlayer.startNanos() != 0L) {
            start = musicPlayer.startNanos();
        }
        return (System.nanoTime() - start) / 1_000_000_000.0D;
    }

    private void restartClock() {
        this.clockStartNanos = System.nanoTime();
    }

    private void onWorldSwitch(Event<Level> event) {
        if (isActive()) {
            startMusic();
        } else {
            stopMusic();
        }
    }

    private void onPlayMusicChanged(Boolean value) {
        if (isActive()) {
            startMusic();
        }
    }

    private void onMusicPathChanged(String value) {
        if (isActive()) {
            startMusic();
        }
    }

    /** 起播当前配置的曲子；已经在放就先停掉。视觉时钟跟着音乐重新对齐。 */
    private void startMusic() {
        stopMusic();
        if (!playMusic.get() || mc.level == null) {
            return;
        }
        String path = resolveMusicPath();
        restartClock();
        musicPlayer.play(path);
        Debug.info("[SFH+] friends-wine: 开始循环播放 " + path);
        Tasks.scheduleDelayed(
                () -> Debug.info(musicPlayer.isPlaying()
                        ? "[SFH+] friends-wine: 音乐正在播放（已写入 " + (musicPlayer.bytesWritten() / 1024L) + " KB）"
                        : "[SFH+] friends-wine: 音乐没有在放（检查路径或解码）"),
                40);
    }

    private void stopMusic() {
        musicPlayer.stop();
    }

    private String resolveMusicPath() {
        String raw = musicPath.get();
        return (raw == null || raw.isBlank()) ? DEFAULT_MUSIC_PATH : raw.trim();
    }
}
