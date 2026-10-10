package me.matl114.hooks.impl.confluxmap;

import java.util.Arrays;
import me.matl114.hacks.modules.survival.ConfluxMapHelper;
import me.matl114.utils.Debug;

/**
 * conflux 小地图缩放（<b>每像素多少格</b>）的唯一事实来源。
 *
 * <h2>为什么要有这么一个类</h2>
 * conflux 自己的小地图缩放只有 4 个离散档位：
 * <pre>
 *   MinimapHudRenderer#BLOCKS_PER_PIXEL = {0.5F, 1.0F, 2.0F, 4.0F}   // 由 ConfluxConfig#minimapZoomIndex 选
 * </pre>
 * 用户要的是 0.1 ~ 8 的任意值。本类就是那个值的<b>唯一出口</b> —— 凡是需要“小地图缩放”的地方都必须经过它：
 * <ul>
 *   <li>{@code ConfluxMinimapHudMixin} 的 {@code @ModifyExpressionValue}：把 conflux 每帧读的那张
 *       {@code BLOCKS_PER_PIXEL} 表换成我们的副本，于是<b>他们的</b>瓦片、传送门区块高亮、轨迹、
 *       方位 / 路径点 / 自定义标记、雷达点、玩家箭头全都跟着缩放；</li>
 *   <li>{@code ConfluxMinimapHudMixin} 里<b>我们自己的</b>覆盖层（已加载区块边界 / 旅行目标标记）。</li>
 * </ul>
 * 两边只要有一边绕过它，覆盖层就会与他们的瓦片 / 标记错位，所以取值的逻辑只写在这一处。
 *
 * <h2>语义（配置项 {@code conflux-map-extra.conflux-map-helper.minimap-blocks-per-pixel}）</h2>
 * <ul>
 *   <li>{@code <= 0}（默认 0）= <b>不覆盖</b>，完全跟随 conflux 自己的档位 —— 老行为逐字节不变
 *       （{@link #substitute} 直接返回他们那张表的原引用，连一次数组拷贝都不做）；</li>
 *   <li>{@code > 0} = 夹紧到 {@code [0.1, 8]} 之后覆盖。<b>数值越大越“缩得远”</b>，与 conflux 的
 *       {@code BLOCKS_PER_PIXEL} 同一量纲（{@code drawTiles} 里
 *       {@code screenX = (blockX - player.x()) / blocksPerPixel}）：0.1 放到最大，8 缩到最远。</li>
 * </ul>
 * 超范围只夹紧、不报错：{@code 12 -> 8}、{@code 0.05 -> 0.1}、{@code <= 0 -> 跟随}。
 * 手改过配置文件 / 老配置里留了怪值的情况下也不会把他们的地图搞坏。
 *
 * <h2>软失败</h2>
 * 所有入口都 {@code try/catch(Throwable)}：模块还没建好、配置读不到时一律退化成“不覆盖”，
 * 异常绝不外抛到 conflux 的渲染路径里。
 *
 * <p>本类只在客户端渲染线程上被调用（conflux 的 {@code MinimapHudRenderer#render} 与我们的
 * {@code drawRadar} HEAD 注入），因此 {@link #substitute} 的缓存不加同步。
 */
public final class ConfluxMinimapZoom {
    /** 有效区间下界：0.1 = 放到最大 */
    public static final double MIN_BLOCKS_PER_PIXEL = 0.1D;

    /** 有效区间上界：8 = 缩到最远 */
    public static final double MAX_BLOCKS_PER_PIXEL = 8.0D;

    private ConfluxMinimapZoom() {}

    /**
     * 配置里的原始值；{@code <= 0} 表示不覆盖。模块还没建好 / 读不到时算 0（= 不覆盖）。
     *
     * <p>直接读 {@link ConfluxMapHelper#INSTANCE} 的 {@code DoubleRef}（一次字段读，很便宜），
     * 不缓存 —— 用户在配置界面里改一下，下一帧就得生效。
     */
    public static double configured() {
        try {
            ConfluxMapHelper module = ConfluxMapHelper.INSTANCE;
            return module == null ? 0.0D : module.minimapBlocksPerPixel.get();
        } catch (Throwable e) {
            return 0.0D;
        }
    }

    /**
     * 覆盖值（已夹紧到 {@code [0.1, 8]}）；<b>不覆盖时返回 {@code NaN}</b>（调用方用它当“没有覆盖”的哨兵，
     * 不走装箱、不用 nullable）。
     *
     * <p>{@code NaN} / {@code <= 0} 都是“不覆盖”；{@code +Inf} 会夹到 8。
     */
    public static float overrideOrNaN() {
        double value = configured();
        if (!(value > 0.0D)) {
            return Float.NaN;
        }
        return (float) Math.min(Math.max(value, MIN_BLOCKS_PER_PIXEL), MAX_BLOCKS_PER_PIXEL);
    }

    /**
     * conflux 自己算出来的值 → 真正生效的值（覆盖生效时用我们的）。
     *
     * <p>给<b>我们自己的覆盖层</b>用（{@code ConfluxMinimapHudMixin} 里反射读到的
     * {@code BLOCKS_PER_PIXEL[config.minimapZoomIndex]}）。
     *
     * <p>{@code confluxValue <= 0} 是那条链路的“算不出来 / 这帧不画”约定（反射拿不到成员时返回 0），
     * 这种情况下<b>绝不能</b>用我们的值把它顶掉 —— 那会画出一层没有任何依据的线，所以原样透传。
     */
    public static float resolve(float confluxValue) {
        float ours = overrideOrNaN();
        if (Float.isNaN(ours) || !(confluxValue > 0.0F)) {
            if (confluxValue > 0.0F) {
                report(confluxValue, false);
            }
            return confluxValue;
        }
        report(ours, true);
        return ours;
    }

    /**
     * conflux 的 {@code BLOCKS_PER_PIXEL} 表 → “每一档都是我们那个值”的副本；<b>不覆盖时原样返回他们的引用</b>。
     *
     * <p>这就是 {@code @ModifyExpressionValue} 的返回值。他们每一处读取都是
     * <pre>
     *   getstatic BLOCKS_PER_PIXEL:[F
     *   aload_0; getfield config; getfield minimapZoomIndex:I
     *   faload
     * </pre>
     * 所以只要把<b>整张表</b>填成我们的值，无论他们当前选的是哪一档，读出来都是我们的值 ——
     * 既不需要知道 {@code minimapZoomIndex}（少一次反射、也不会越界），也不需要逐个注入点去改。
     * 返回的数组只被他们 {@code faload} 读取，不会被写。
     *
     * <p>副本按 {@code (源数组, 生效值)} 缓存：配置不变时每帧 10 处读取共用同一个数组，零分配；
     * 值一变（或将来他们的表换了个实例）立刻重建。
     *
     * @param table        conflux 的 {@code BLOCKS_PER_PIXEL}（可能为 null，原样返回）
     * @param confluxValue 他们当前档位的值，<b>只用来打日志</b>；拿不到给 {@code NaN}
     */
    public static float[] substitute(float[] table, float confluxValue) {
        if (table == null) {
            return null;
        }
        float ours = overrideOrNaN();
        if (Float.isNaN(ours)) {
            report(confluxValue, false);
            return table;
        }
        float[] copy = cachedCopy;
        if (copy == null || cachedSource != table || cachedValue != ours || copy.length != table.length) {
            copy = table.clone();
            Arrays.fill(copy, ours);
            cachedSource = table;
            cachedValue = ours;
            cachedCopy = copy;
        }
        report(ours, true);
        return copy;
    }

    /** {@link #substitute} 的缓存（渲染线程单线程访问，见类注释） */
    private static float[] cachedSource;

    private static float[] cachedCopy;

    private static float cachedValue = Float.NaN;

    /** 上一次打过日志的状态；{@code effective = NaN} 表示还没打过任何一行 */
    private static float reportedValue = Float.NaN;

    private static boolean reportedOurs;

    /**
     * 只在<b>状态真的变了</b>的时候打一行，实机验收按这行看覆盖有没有生效：
     * <pre>
     *   [ConfluxMapHelper] minimap zoom: blocksPerPixel=2.5 source=ours
     *   [ConfluxMapHelper] minimap zoom: blocksPerPixel=1.0 source=conflux
     * </pre>
     *
     * <p>每帧会被调 10 次（他们每一处读取一次），所以必须廉价：一次比较就返回。
     * 拿不到有效值（{@code NaN}）时不打 —— 那种情况下什么都没变，打出来只会误导。
     */
    private static void report(float effective, boolean ours) {
        try {
            if (Float.isNaN(effective)) {
                return;
            }
            if (!Float.isNaN(reportedValue) && reportedOurs == ours && reportedValue == effective) {
                return;
            }
            reportedValue = effective;
            reportedOurs = ours;
            Debug.getLogger()
                    .info("[ConfluxMapHelper] minimap zoom: blocksPerPixel=" + effective + " source="
                            + (ours ? "ours" : "conflux"));
        } catch (Throwable ignored) {
            // 打日志本身绝不允许影响渲染
        }
    }
}
