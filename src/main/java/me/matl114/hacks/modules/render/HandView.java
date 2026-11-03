package me.matl114.hacks.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.awt.Color;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.Vec3;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.managers.Configs;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.utils.render.HandFaceCollector;
import net.minecraft.world.InteractionHand;

/**
 * 第一人称手部：模型变换（位移 / 旋转 / 缩放）、自定义挥手速度，以及发光壳层 / 边框的配置。
 *
 * <p>变换由 {@code ItemInHandRendererMixin} 在每只手的 {@code submitArmWithItem}（26.1.2 为
 * {@code renderArmWithItem}）HEAD 里 push 并应用、RETURN 里 pop，包住 vanilla 的整段提交
 * （空手 / 地图 / 弩 / 吃东西 / 举镜），因此变换本身不介入任何渲染管线。
 *
 * <p>发光与边框（{@code glow / glowColor / glowAlpha / boundaryColor}）的配置也在本类，画法在
 * {@link HandFaceCollector}：{@code GameRenderMixin} 把这一帧的手部提交换成抓面 collector，提交结束后用
 * identity PoseStack 把壳层与边线重提交回同一个 collector。两个版本的手部几何都只经
 * {@code submitModelPart / submitItem / submitCustomGeometry} 三条出口，所以这条路径不随版本分叉，
 * 也不需要上游那套 mask 后处理。
 */
public class HandView extends BaseModule {
    public static HandView INSTANCE;

    public final ModulePath handView = makePath(Configs.RENDER_CONFIG, "hand-view");
    public final ModulePath mainHand = handView.add("main-hand");
    public final ModulePath mainHandEmpty = handView.add("main-hand-empty");
    public final ModulePath offHand = handView.add("off-hand");
    public final ModulePath handAnimation = handView.add("hand-animation");

    public final FlagRef enable = flagBuilder(handView.addEnable()).build();

    // 发光 / 边框：配置在模块上，抓面与重提交在 HandFaceCollector，两个版本都生效
    public final FlagRef glow = flagBuilder(handView.add("glow")).build();

    public final NBTRef<WrapColor> glowColor = builder(handView.add("glow-color"), WrapColor.class)
            .defaultValue(new WrapColor(Color.CYAN))
            .build();

    public final IntRef glowAlpha = intBuilder(handView.add("glow-alpha"))
            .defaultValue(32)
            .validator(Configs.intRange(0, 255))
            .build();

    public final NBTRef<WrapColor> boundaryColor = builder(handView.add("boundary-color"), WrapColor.class)
            .defaultValue(new WrapColor(Color.CYAN))
            .build();

    /** 每帧手部抓面的缓存；只有本模块用，写入与重提交都由 {@code GameRenderMixin} 驱动。 */
    private final HandFaceCollector handFaces = new HandFaceCollector();

    public final FlagRef swingSpeedEnabled =
            flagBuilder(handAnimation.addEnable()).build();

    // 挥手时长倍率：>1 加快挥手，<1 减慢；实际作用点见 LivingEntityMixin#getCurrentSwingDuration
    public final DoubleRef swingSpeed = doubleBuilder(handAnimation.add("swing-speed"))
            .defaultValue(1.0D)
            .validator(Configs.doubleRange(0.1D, 10.0D))
            .build();

    public final NBTRef<Vec3> mainHandScale = builder(mainHand.add("scale"), Vec3.class)
            .defaultValue(new Vec3(1, 1, 1))
            .build();

    public final NBTRef<Vec3> mainHandPosition = builder(mainHand.add("position"), Vec3.class)
            .defaultValue(new Vec3(0, 0, 0))
            .build();

    public final NBTRef<Vec3> mainHandRotation = builder(mainHand.add("rotation"), Vec3.class)
            .defaultValue(new Vec3(0, 0, 0))
            .build();

    public final NBTRef<Vec3> mainHandEmptyScale = builder(mainHandEmpty.add("scale"), Vec3.class)
            .defaultValue(new Vec3(1, 1, 1))
            .build();

    public final NBTRef<Vec3> mainHandEmptyPosition = builder(mainHandEmpty.add("position"), Vec3.class)
            .defaultValue(new Vec3(0, 0, 0))
            .build();

    public final NBTRef<Vec3> mainHandEmptyRotation = builder(mainHandEmpty.add("rotation"), Vec3.class)
            .defaultValue(new Vec3(0, 0, 0))
            .build();

    public final NBTRef<Vec3> offHandScale = builder(offHand.add("scale"), Vec3.class)
            .defaultValue(new Vec3(1, 1, 1))
            .build();

    public final NBTRef<Vec3> offHandPosition = builder(offHand.add("position"), Vec3.class)
            .defaultValue(new Vec3(0, 0, 0))
            .build();

    public final NBTRef<Vec3> offHandRotation = builder(offHand.add("rotation"), Vec3.class)
            .defaultValue(new Vec3(0, 0, 0))
            .build();

    public HandView() {
        super("HandView");
        bindFlag(enable);
        INSTANCE = this;
    }

    /** 是否需要画发光层：模块启用且开了发光开关。 */
    public boolean glowEnabled() {
        return isActive() && glow.get();
    }

    public HandFaceCollector getHandFaces() {
        return handFaces;
    }

    /** 壳层填充色：{@code glow-alpha} 只作用在这里，取 0 即壳层不可见、边线照画。 */
    public int glowShellColor() {
        return glowColor.get().withAlpha(glowAlpha.get());
    }

    public int glowBoundaryColor() {
        return boundaryColor.get().withAlpha(255);
    }

    /**
     * 按「哪只手 + 是否空手」选一组变换。空手（手臂）单独一套，避免裸手和持物互相牵连。
     */
    public void applyHandTransform(PoseStack matrices, InteractionHand hand, boolean handEmpty) {
        Vec3 rotation = hand == InteractionHand.MAIN_HAND
                ? (handEmpty ? mainHandEmptyRotation.get() : mainHandRotation.get())
                : offHandRotation.get();
        Vec3 scale = hand == InteractionHand.MAIN_HAND
                ? (handEmpty ? mainHandEmptyScale.get() : mainHandScale.get())
                : offHandScale.get();
        Vec3 position = hand == InteractionHand.MAIN_HAND
                ? (handEmpty ? mainHandEmptyPosition.get() : mainHandPosition.get())
                : offHandPosition.get();
        applyTransform(matrices, scale, position, rotation);
    }

    // 顺序与上游一致：先缩放、再平移、最后旋转（平移量因此处在缩放后的坐标系里）
    private static void applyTransform(PoseStack matrices, Vec3 scale, Vec3 position, Vec3 rotation) {
        matrices.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
        matrices.translate((float) position.x(), (float) position.y(), (float) position.z());
        matrices.mulPose(Axis.XP.rotationDegrees((float) rotation.x()));
        matrices.mulPose(Axis.YP.rotationDegrees((float) rotation.y()));
        matrices.mulPose(Axis.ZP.rotationDegrees((float) rotation.z()));
    }
}
