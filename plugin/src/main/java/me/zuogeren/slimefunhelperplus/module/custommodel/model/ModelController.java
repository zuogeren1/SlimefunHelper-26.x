package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm.RawYsmModel;

/**
 * YSM 的动画控制器状态机：一个控制器管一个槽（{@code player.parallel_0}、{@code player.pre_parallel_1} …）。
 *
 * <p>语义对齐 YSM 的 {@code ParallelControllerDiscovery} + {@code HybridAnimationController}：
 * 从 {@code initialState} 开始，每帧先按当前状态的 {@code transitions} 顺序找第一条成立的跳转，
 * 再按新状态的 {@code animations} 顺序找第一条成立的（动画名 → Molang 条件），把动画名交给调用方当覆盖层播。
 *
 * <p>条件求值复用 {@link MolangExpression}，未知量按 NaN → 比较为假，不会误判成真。
 */
public final class ModelController {

    /** 一条「动画名 → 条件」；条件为空表示无条件成立。 */
    public record Choice(String animation, MolangExpression.Expr condition) {}

    /** 一条「目标状态 → 条件」。 */
    public record Transition(String target, MolangExpression.Expr condition) {}

    public record State(String name, List<Choice> animations, List<Transition> transitions) {}

    private final String slot;
    private final List<State> states;
    private String current;

    private ModelController(String slot, List<State> states, String current) {
        this.slot = slot;
        this.states = states;
        this.current = current;
    }

    /** 槽名（去掉 {@code player.} 前缀），例如 {@code parallel_0}；解析不出就返回空串。 */
    public String slot() {
        return slot;
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
                    if (entry.getKey() != null) {
                        animations.add(new Choice(entry.getKey(), compile(entry.getValue())));
                    }
                }
            }
            List<Transition> transitions = new ArrayList<>();
            if (state.transitions != null) {
                for (Map.Entry<String, String> entry : state.transitions) {
                    if (entry.getKey() != null) {
                        transitions.add(new Transition(entry.getKey(), compile(entry.getValue())));
                    }
                }
            }
            parsed.add(new State(state.name, animations, transitions));
        }
        if (parsed.isEmpty()) {
            return null;
        }
        String slot = slotOf(source.animationName);
        String initial = source.initialState;
        if (initial == null || initial.isEmpty()) {
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

    private static boolean holds(MolangExpression.Expr condition, float animTime, float lifeTime) {
        return condition == null || condition.eval(animTime, lifeTime) > 0.5F;
    }

    private State stateOf(String name) {
        for (State state : states) {
            if (state.name().equals(name)) {
                return state;
            }
        }
        return null;
    }

    /**
     * 推进一帧，返回本帧该播的动画名；没有则 null。
     *
     * <p>跳转只走一步（与其他 YSM 移植实现一致），避免同一帧内连环跳转把状态甩飞。
     */
    public String tick(float animTime, float lifeTime) {
        State state = stateOf(current);
        if (state == null) {
            state = states.get(0);
            current = state.name();
        }
        for (Transition transition : state.transitions()) {
            if (holds(transition.condition(), animTime, lifeTime)) {
                State target = stateOf(transition.target());
                if (target != null) {
                    current = target.name();
                    state = target;
                    break;
                }
            }
        }
        for (Choice choice : state.animations()) {
            if (holds(choice.condition(), animTime, lifeTime)) {
                return choice.animation();
            }
        }
        return null;
    }
}
