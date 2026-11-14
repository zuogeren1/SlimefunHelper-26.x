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

    public record State(String name, List<Choice> animations, List<Transition> transitions,
            List<MolangExpression.Statement> onEntry, List<MolangExpression.Statement> onExit) {}

    /** 片段时间查询：判断「本状态里的动画播完没有」，实现方拿的是模型自己的片段表。 */
    public interface Lengths {
        float of(String animation);
    }

    /**
     * 由代码侧直接驱动的槽：主体态（{@code main} 与它的 pre/post）和手部三层
     * （{@code hold_*} / {@code swing} / {@code use}）。
     *
     * <p>YSM 里这些槽挂的是 {@code PlayerMainPredicate} / {@code MainhandPredicate} /
     * {@code OffhandPredicate} / {@code SwingPredicate} / {@code UsePredicate} 这类**代码控制器**，
     * 我们已经按 {@link ItemClassifier} 的优先级在 {@code ModelRuntime} 里实现了同一套判定；
     * 模型若把默认状态机整份抄进 {@code controller.json}（例如把 {@code post_hold} 写成
     * {@code hold_mainhand:axe …} 那串），再求值一次等于把同一段动画叠两遍。
     */
    private static final Set<String> CODE_SLOTS = Set.of(
            "main", "pre_main", "post_main",
            "hold_mainhand", "hold_offhand", "pre_hold", "post_hold",
            "swing", "pre_swing", "post_swing",
            "use", "pre_use", "post_use");

    /** 一帧内最多连跳几次（YSM 用 skipPathSet 防环，这里再加一道次数上限）。 */
    private static final int MAX_CHAIN = 8;

    private final String slot;
    private final List<State> states;
    /** 当前状态里条件成立、正在播的动画名。 */
    private final List<String> playing = new ArrayList<>();
    private String current;
    /** 进入当前状态的时刻（秒）：状态里所有动画共用一个起点。 */
    private float stateStart = Float.NaN;

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
                    program(state.onEntry), program(state.onExit)));
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
