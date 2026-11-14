package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;

/**
 * YSM 的动画控制器状态机：一个控制器管一个槽（{@code player.parallel_0}、{@code player.new} …）。
 *
 * <p>语义对齐 YSM 的 {@code ParallelControllerDiscovery} + {@code BedrockAnimationController}：
 * 从 {@code initialState} 开始，每帧按当前状态的 {@code transitions} 顺序找第一条成立的跳转
 * （跳到没有动画的空状态时继续往下试，防止中间态卡一帧），再按新状态的 {@code animations}
 * 顺序把所有成立的动画交给调用方当覆盖层播 —— <b>不是</b>取第一条，一个状态本来就允许同时播多条。
 *
 * <p>{@code onEntry} / {@code onExit} 里的语句照 YSM 在跳转瞬间执行，模型靠它维护
 * {@code v.swing_sword} 这类连击游标；{@code onExit} 拿到的是旧状态已经走了多久，
 * 与 YSM 的 {@code AnimationPlayer} 时间一致。
 *
 * <p>条件求值复用 {@link MolangExpression}，未知量按 NaN → 比较为假，不会误判成真。
 */
public final class ModelController {

    /** 一条「动画名 → 条件」；条件为空表示无条件成立。 */
    public record Choice(String animation, MolangExpression.Expr condition) {}

    /** 一条「目标状态 → 条件」。 */
    public record Transition(String target, MolangExpression.Expr condition) {}

    /**
     * 进入一个状态时的过渡混合数据，对应 YSM 的 {@code blend_transition} 与 {@code blend_via_shortest_path}。
     *
     * <p>SPM 把这两项挂在**目标状态**上：{@code AnimationControllerRuntime#transitionToEntry} 用
     * {@code nextState.getBlendTransition()} 设插值器、用 {@code nextState.isBlendViaShortestPath()}
     * 选旋转混合模式，所以这里也按目标状态存，与「从哪个状态来」无关。
     *
     * <p>{@code seconds} 是总时长（秒）：YSM 的 {@code blend_transition} 数值就是秒，SPM 的
     * {@code TicksInterpolator} 内部乘 20 换成 tick。{@code times}/{@code progress} 是分段线性的
     * 权重曲线，只有 {@code blend_transition} 写成对象时才有，空数组表示按 {@code seconds} 线性。
     * {@code progress} 是「新姿态占多少」：0 = 旧姿态、1 = 新姿态（SPM 的
     * {@code LinearKeyframeInterpolator} 存的是 1 - 该值）。
     *
     * <p>{@code declared} 表示模型确实给了过渡数据（曲线，或非 0 的标量）。标量 0 与「没写」在
     * 二进制里同形（{@code RawControllerState} 没保留「标量还是曲线」那个标志位），都算未声明，
     * 由调用方决定兜底时长。
     */
    public record Blend(float seconds, boolean shortestPath, float[] times, float[] progress,
            boolean declared) {

        static final float[] EMPTY = new float[0];

        /** 未声明过渡：硬切。 */
        public static final Blend NONE = new Blend(0.0F, false, EMPTY, EMPTY, false);

        /** 过渡进度 0..1；时长 0 时恒为 1（SPM 的 {@code TicksInterpolator(0)} 同样直接返回 1）。 */
        public float weight(float elapsedSeconds) {
            if (this.times.length == 0) {
                if (this.seconds <= 0.0F) {
                    return 1.0F;
                }
                return Math.min(1.0F, Math.max(0.0F, elapsedSeconds / this.seconds));
            }
            if (elapsedSeconds <= this.times[0]) {
                return this.progress[0];
            }
            for (int i = 1; i < this.times.length; i++) {
                if (elapsedSeconds <= this.times[i]) {
                    float span = this.times[i] - this.times[i - 1];
                    float ratio = span <= 0.0F ? 1.0F : (elapsedSeconds - this.times[i - 1]) / span;
                    return this.progress[i - 1] + (this.progress[i] - this.progress[i - 1]) * ratio;
                }
            }
            return this.progress[this.progress.length - 1];
        }
    }

    public record State(String name, List<Choice> animations, List<Transition> transitions,
            List<MolangExpression.Statement> onEntry, List<MolangExpression.Statement> onExit,
            Blend blend) {}

    /** 片段时间查询：判断「本状态里的动画播完没有」，实现方拿的是模型自己的片段表。 */
    public interface Lengths {
        float of(String animation);
    }

    /**
     * 由代码侧直接驱动的槽：主体态 {@code main} 与手部三层 {@code hold_*} / {@code swing} / {@code use}。
     *
     * <p>YSM 给这几个槽注册的是 {@code PlayerMainPredicate} / {@code MainhandPredicate} /
     * {@code OffhandPredicate} / {@code SwingPredicate} / {@code UsePredicate} 这类**代码控制器**，
     * 我们已经按 {@link ItemClassifier} 的优先级在 {@code ModelRuntime} 里实现了同一套判定。
     * 模型若把默认状态机整份抄进 {@code controller.json}，再求值一次等于把同一段动画叠两遍。
     *
     * <p>{@code pre_*} / {@code post_*} 八个槽不在这里：YSM 用 {@code multi(...)} + {@code EmptyPredicate}
     * 注册它们（{@code PlayerControllerCollection:39-60}），没有代码侧对应物，纯靠模型自己的状态机驱动，
     * 丢掉就等于这些槽永远不播。
     */
    private static final Set<String> CODE_SLOTS = Set.of(
            "main", "hold_mainhand", "hold_offhand", "swing", "use");

    /** 一帧内最多连跳几次（YSM 用 skipPathSet 防环，这里再加一道次数上限）。 */
    private static final int MAX_CHAIN = 8;

    private final String slot;
    private final List<State> states;
    /** 当前状态里条件成立、正在播的动画名。 */
    private final List<String> playing = new ArrayList<>();
    private String current;
    /** 进入当前状态的时刻（秒）：状态里所有动画共用一个起点。 */
    private float stateStart = Float.NaN;
    /** 最近一次进入的状态与它的来源（首帧进入时来源为 {@code null}）。 */
    private String lastFrom;
    private String lastTo;

    private ModelController(String slot, List<State> states, String current) {
        this.slot = slot;
        this.states = states;
        this.current = current;
    }

    /** 槽名（去掉 {@code player.} 前缀），例如 {@code parallel_0}；解析不出就返回空串。 */
    public String slot() {
        return slot;
    }

    /** 该槽是否由代码侧驱动，模型自带的同名控制器应整条忽略。 */
    public static boolean isCodeDriven(String slot) {
        return CODE_SLOTS.contains(slot);
    }

    public static ModelController parse(RawYsmModel.RawAnimationController source) {
        if (source == null || source.states == null || source.states.isEmpty()) {
            return null;
        }
        List<State> parsed = new ArrayList<>(source.states.size());
        for (RawYsmModel.RawControllerState state : source.states) {
            if (state == null || state.name == null) {
                continue;
            }
            List<Choice> animations = new ArrayList<>();
            if (state.animations != null) {
                for (Map.Entry<String, String> entry : state.animations) {
                    if (entry.getKey() != null && !entry.getKey().isEmpty()) {
                        animations.add(new Choice(entry.getKey(), compile(entry.getValue())));
                    }
                }
            }
            List<Transition> transitions = new ArrayList<>();
            if (state.transitions != null) {
                for (Map.Entry<String, String> entry : state.transitions) {
                    if (entry.getKey() != null && !entry.getKey().isEmpty()) {
                        transitions.add(new Transition(entry.getKey(), compile(entry.getValue())));
                    }
                }
            }
            parsed.add(new State(state.name, animations, transitions,
                    program(state.onEntry), program(state.onExit), blend(state)));
        }
        if (parsed.isEmpty()) {
            return null;
        }
        String slot = slotOf(source.animationName);
        String initial = source.initialState;
        if (initial == null || initial.isEmpty() || find(parsed, initial) == null) {
            initial = parsed.get(0).name();
        }
        return new ModelController(slot, parsed, initial);
    }

    private static String slotOf(String controllerName) {
        if (controllerName == null) {
            return "";
        }
        int dot = controllerName.lastIndexOf('.');
        return dot < 0 ? controllerName : controllerName.substring(dot + 1);
    }

    private static MolangExpression.Expr compile(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return MolangExpression.of(source);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static List<MolangExpression.Statement> program(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<MolangExpression.Statement> compiled = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            compiled.addAll(MolangExpression.statements(line));
        }
        return compiled;
    }

    /**
     * 解析一个状态的 {@code blend_transition} / {@code blend_via_shortest_path}。
     *
     * <p>数值单位是**秒**：SPM 的 {@code TicksInterpolator} 构造时乘 20 换成 tick，求值时再除回去，
     * 所以对外按秒给、由调用方自己换算成 tick 或帧。
     *
     * <p>写成对象时是「时间(秒) → 权重」的分段线性曲线；SPM 的 {@code LinearKeyframeInterpolator}
     * 存的是 {@code 1 - 权重}（0 表示旧姿态），这里换成「新姿态占比」，语义与 {@link Blend#weight} 一致。
     *
     * <p>{@code declared} 只能靠「标量非 0」判断：二进制里本来有一个「标量还是曲线」的标志位，
     * 但 {@link RawYsmModel.RawControllerState} 没保留它，显式写 0 与不写在这里无法区分。
     *
     * <p>控制器级的 {@code transitionLengthTicks} 不在这里：它是「控制器没声明过渡时用多久」的
     * 兜底，由调用方（{@code ModelRuntime}）持有，见 {@link #transitionSeconds}。
     */
    private static Blend blend(RawYsmModel.RawControllerState state) {
        boolean shortest = state.blendViaShortestPath;
        Map<Float, Float> points = state.blendTransitions;
        if (points != null && !points.isEmpty()) {
            // 键序不可信：二进制按写入顺序、JSON 按对象顺序，都不保证升序
            List<Float> keys = new ArrayList<>(points.keySet());
            keys.sort(Float::compare);
            int size = keys.size();
            float[] times = new float[size];
            float[] progress = new float[size];
            for (int i = 0; i < size; i++) {
                float key = keys.get(i);
                times[i] = key;
                progress[i] = 1.0F - points.get(key);
            }
            return new Blend(times[size - 1], shortest, times, progress, true);
        }
        float seconds = state.blendTransitionValue;
        return new Blend(seconds, shortest, Blend.EMPTY, Blend.EMPTY, seconds != 0.0F);
    }

    private static boolean holds(MolangExpression.Expr condition, float animTime, float lifeTime) {
        return condition == null || condition.eval(animTime, lifeTime) > 0.5F;
    }

    private static State find(List<State> states, String name) {
        for (State state : states) {
            if (state.name().equals(name)) {
                return state;
            }
        }
        return null;
    }

    private State stateOf(String name) {
        return find(states, name);
    }

    /** 进入当前状态后过了多久（秒）。 */
    public float stateStart() {
        return stateStart;
    }

    /** 当前状态名；还没 tick 过时是初始状态。 */
    public String currentState() {
        return current;
    }

    /**
     * 进入 {@code state} 时的过渡混合数据；状态不存在时返回 {@link Blend#NONE}。
     *
     * <p>过渡挂在**目标状态**上（SPM {@code AnimationControllerRuntime:337,367} 取的是
     * {@code nextState} 的 blendTransition / blendViaShortestPath），所以 {@code fromState}
     * 只用于「从哪来」的日志与将来的按来源分叉，不参与取值。
     */
    public Blend blendInto(String fromState, String state) {
        State target = stateOf(state);
        return target == null ? Blend.NONE : target.blend();
    }

    /**
     * 从 {@code fromState} 切到 {@code toState} 的过渡时长（秒）。
     *
     * <p>模型没声明 {@code blend_transition} 时用 {@code fallbackSeconds}，由调用方决定传什么：
     * 控制器级的 {@code transitionLengthTicks / 20}（SPM 的 {@code AnimationControllerInstance}
     * 构造参数，玩家槽位是 0.0f 或 0.1f，见 {@code PlayerAnimationController:44-67}），
     * 或者 0.0F 表示硬切。
     *
     * <p>注意 SPM 的模型状态路径其实**不**回落到控制器级时长：{@code transitionToEntry} 一律用
     * {@code nextState.getBlendTransition()} 覆盖槽位插值器，没声明就是 {@code TicksInterpolator(0)}
     * （硬切）；{@code transitionLengthTicks} 只用于槽位初始插值器和代码侧控制器。要与 SPM 逐帧
     * 一致就传 0.0F。
     */
    public float transitionSeconds(String fromState, String toState, float fallbackSeconds) {
        Blend blend = blendInto(fromState, toState);
        return blend.declared() ? blend.seconds() : fallbackSeconds;
    }

    /**
     * 同 {@link #transitionSeconds(String, String, float)}，未声明时取 0（硬切）。
     *
     * <p>模型控制器只跑在非代码驱动的槽上，其中 SPM 注册过的 {@code parallel*} / {@code pre_parallel*}
     * 的 {@code transitionLengthTicks} 都是 0.0f（{@code PlayerAnimationController:40,66}）；
     * {@code new} 这类 YSM 独有槽在 SPM 里没有对应注册，同样按 0 处理。
     */
    public float transitionSeconds(String fromState, String toState) {
        return transitionSeconds(fromState, toState, 0.0F);
    }

    /** 从 {@code fromState} 切到 {@code toState} 是否走最短欧拉路径（{@code blend_via_shortest_path}）。 */
    public boolean shortestPath(String fromState, String toState) {
        return blendInto(fromState, toState).shortestPath();
    }

    /**
     * 最近一次跳转的来源状态；首帧进入（或还没 tick 过）为 {@code null}。
     *
     * <p>{@link #tick} 内部完成跳转，调用方拿不到 from/to，所以这里把「刚发生的那次跳转」记下来：
     * 每帧 tick 之后用 {@link #lastFrom()} / {@link #lastTo()} 取 {@link #blendInto} 即可。
     */
    public String lastFrom() {
        return lastFrom;
    }

    /** 最近一次跳转的目标状态（= 当前状态）；还没 tick 过时为 {@code null}。 */
    public String lastTo() {
        return lastTo;
    }

    /** 最近一次跳转的过渡数据；首帧进入返回 {@link Blend#NONE}。 */
    public Blend lastBlend() {
        return lastFrom == null ? Blend.NONE : blendInto(lastFrom, lastTo);
    }

    /**
     * 推进一帧，返回本帧该播的动画名（可能多条，也可能为空）。
     *
     * <p>调用方按 {@link #stateStart()} 算时间：同一状态里的动画共用一个起点，各自按自己的循环模式取模。
     */
    public List<String> tick(float ageSeconds, Lengths lengths) {
        State state = stateOf(current);
        boolean entered = false;
        if (state == null) {
            state = states.get(0);
        }
        // 第一帧（或状态名在模型里不存在）也要走一次「进入」：状态时间从这里起算
        if (state == null || !Float.isFinite(stateStart)) {
            enter(state, ageSeconds);
            entered = true;
        }
        if (!entered) {
            // 「播完没有」判的是上一帧算出来的 playing；YSM 也是拿动画播放器的状态判跳转
            bindFinished(ageSeconds, lengths);
            Set<String> visited = new HashSet<>(4);
            visited.add(state.name());
            for (int step = 0; step < MAX_CHAIN; step++) {
                Transition taken = null;
                float elapsed = elapsed(ageSeconds);
                for (Transition transition : state.transitions()) {
                    if (holds(transition.condition(), elapsed, ageSeconds)) {
                        taken = transition;
                        break;
                    }
                }
                if (taken == null) {
                    break;
                }
                State target = stateOf(taken.target());
                if (target == null || !visited.add(target.name())) {
                    break;
                }
                exit(state, ageSeconds);
                enter(target, ageSeconds);
                state = target;
                // 空状态不用停一帧：YSM 的 updateState 在 activeAnimationPlayerSize==0 时接着跳
                if (!state.animations().isEmpty()) {
                    bindFinished(ageSeconds, lengths);
                    break;
                }
                bindFinished(ageSeconds, lengths);
            }
        }
        playing.clear();
        float elapsed = elapsed(ageSeconds);
        for (Choice choice : state.animations()) {
            if (holds(choice.condition(), elapsed, ageSeconds)) {
                playing.add(choice.animation());
            }
        }
        return playing;
    }

    /** 交完本帧结果就撤掉绑定：别的层（主体态、手部、物理）不该借到控制器的标志位。 */
    public void finishFrame() {
        MolangVariables.clearControllerState();
    }

    private float elapsed(float ageSeconds) {
        return Float.isFinite(stateStart) ? Math.max(0.0F, ageSeconds - stateStart) : 0.0F;
    }

    private void enter(State state, float ageSeconds) {
        // 首帧进入不算跳转：没有「从哪来」，也不该被当成一次混合
        lastFrom = Float.isFinite(stateStart) ? current : null;
        lastTo = state.name();
        current = state.name();
        stateStart = ageSeconds;
        playing.clear();
        for (MolangExpression.Statement statement : state.onEntry()) {
            statement.run(0.0F, ageSeconds);
        }
    }

    private void exit(State state, float ageSeconds) {
        float elapsed = elapsed(ageSeconds);
        for (MolangExpression.Statement statement : state.onExit()) {
            statement.run(elapsed, ageSeconds);
        }
    }

    /**
     * 把「本状态里正在播的动画是否都播完」写进 Molang 上下文。
     *
     * <p>与 YSM {@code BedrockAnimationController#updateState} 同口径：没有生效动画时两边都为真；
     * 否则 all 要求全部播完、any 只要一条播完。循环动画播过一轮就算播完（GeckoLib 的
     * {@code currentAnimFinished} 在回卷后保持为真）。
     */
    private void bindFinished(float ageSeconds, Lengths lengths) {
        if (playing.isEmpty()) {
            MolangVariables.bindControllerState(true, true);
            return;
        }
        float elapsed = elapsed(ageSeconds);
        boolean all = true;
        boolean any = false;
        for (String animation : playing) {
            float length = lengths.of(animation);
            boolean finished = length <= 0.0F || elapsed >= length;
            all &= finished;
            any |= finished;
        }
        MolangVariables.bindControllerState(all, any);
    }
}
