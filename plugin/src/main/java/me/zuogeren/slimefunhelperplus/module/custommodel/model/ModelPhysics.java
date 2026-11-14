package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import java.util.HashMap;
import java.util.Map;

/**
 * YSM 的物理内建函数 {@code ysm.second_order} / {@code ysm.first_order}：一个模型实例一张模拟器表，
 * 每帧出图前推进一次，表达式求值时按 key 取上一帧的积分结果。
 *
 * <p>key 在 YSM 里是全局字符串池 id，所以 <b>同名即同一个模拟器</b> —— 这是它区别于「每根骨骼一条弹簧」的
 * 关键：作者让多条头发骨写同一个名字，它们就共享同一条弹簧、永远同步。这里直接拿字符串当表键。
 *
 * <p>k1/k2/k3 与子步上限照 YSM 的 {@code SecondOrder}（论文 KPoeNZZ6H4s）；一帧延迟也照它：调用方先
 * {@code setArgs} 再取 {@code value()}，用完的输入给下一帧积分，返回的是上一帧的结果。
 */
final class ModelPhysics {

    /** 参数缺省时 YSM 取 1（{@code ModelBuiltins#physics}），这里对齐同一份缺省。 */
    private static final float DEFAULT_ARGUMENT = 1.0F;
    /** 固有频率的上界照 YSM 的 clamp；下限取非零值，因为 k1/k2/k3 都要除以频率。 */
    private static final float MAX_FREQUENCY = 5.0F;
    private static final float MIN_FREQUENCY = 0.05F;
    /** 单帧时长上限：卡顿或暂停后回来的一帧不该把弹簧推飞。 */
    private static final float MAX_TIME_STEP = 0.25F;
    /** 子步数上限：最硬的弹簧（5Hz）配最长的一帧也只要十几个子步，再多只是浪费。 */
    private static final int MAX_SUBSTEPS = 64;
    private static final float PI = (float) Math.PI;

    /** 正在出图的模型实例；表达式只在渲染线程求值，出图期间由调用方设置。 */
    private static volatile ModelPhysics rendering;

    private final Map<String, Simulation> simulations = new HashMap<>();
    private float lastTime = Float.NaN;

    private interface Simulation {
        void setArgs(float input, float a, float b, float c);

        void update(float timeStep);

        float value();
    }

    static void bind(ModelPhysics physics) {
        rendering = physics;
    }

    /** 没有渲染上下文时（控制器条件、图标预览）退化成直通，免得把 NaN 写进骨骼通道。 */
    static float secondOrder(String key, float input, float frequency, float coefficient, float response) {
        ModelPhysics physics = rendering;
        return physics == null ? input
                : physics.sample(key, false, input, frequency, coefficient, response);
    }

    static float firstOrder(String key, float input, float response) {
        ModelPhysics physics = rendering;
        return physics == null ? input
                : physics.sample(key, true, input, response, DEFAULT_ARGUMENT, DEFAULT_ARGUMENT);
    }

    /** 每帧出图前推进一次；{@code ageSeconds} 是调用方那根渲染时钟。 */
    void advance(float ageSeconds) {
        float previous = lastTime;
        lastTime = ageSeconds;
        if (Float.isNaN(previous) || ageSeconds <= previous) {
            // 首帧没有历史、同一帧的多次出图、时钟回退：都不积分（YSM 的 PhysicsManager 同样只跳过）
            return;
        }
        float timeStep = Math.min(ageSeconds - previous, MAX_TIME_STEP);
        for (Simulation simulation : simulations.values()) {
            simulation.update(timeStep);
        }
    }

    private float sample(String key, boolean first, float input, float a, float b, float c) {
        if (key == null || key.isEmpty()) {
            // 空 key 在 YSM 里直接返回 0，不建模拟器
            return 0.0F;
        }
        Simulation simulation = simulations.get(key);
        if (simulation == null) {
            simulation = first ? new FirstOrder(input, a) : new SecondOrder(input, a, b, c);
            simulations.put(key, simulation);
            // 首帧没有历史可用，给输入值本身，避免通道从 0 弹起
            return input;
        }
        simulation.setArgs(input, a, b, c);
        return simulation.value();
    }

    /** NaN 会永久污染弹簧状态（之后每帧都是 NaN），遇到就按中性值处理，至少让通道保持不动。 */
    private static float finite(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    /** 二阶弹簧（YSM SecondOrder 的逐行移植），三个参数是固有频率、阻尼比、速度前馈。 */
    private static final class SecondOrder implements Simulation {

        private float input;
        private float frequency = DEFAULT_ARGUMENT;
        private float coefficient = DEFAULT_ARGUMENT;
        private float response;
        private float inputFunction;
        private float simulation;
        private float simulationDot;

        SecondOrder(float input, float frequency, float coefficient, float response) {
            setArgs(input, frequency, coefficient, response);
        }

        @Override
        public void setArgs(float input, float a, float b, float c) {
            this.input = finite(input, 0.0F);
            this.frequency = Math.min(Math.max(finite(a, DEFAULT_ARGUMENT), MIN_FREQUENCY), MAX_FREQUENCY);
            this.coefficient = Math.min(Math.max(finite(b, DEFAULT_ARGUMENT), 0.0F), 1.0F);
            this.response = finite(c, DEFAULT_ARGUMENT);
        }

        @Override
        public void update(float timeStep) {
            float k1 = this.coefficient / PI / this.frequency;
            float k2 = 1.0F / (2.0F * PI * this.frequency) / (2.0F * PI * this.frequency);
            float k3 = this.response * this.coefficient / 2.0F / PI / this.frequency;

            float inputFunctionDot = (this.input - this.inputFunction) / timeStep;
            this.inputFunction = this.input;

            // 子步长超过 sqrt(4k2 + k1^2) - k1 时显式积分会发散，按它切分
            float maxTimeStep = (float) Math.sqrt(4.0F * k2 + k1 * k1) - k1;
            int substeps = maxTimeStep <= 0.0F ? MAX_SUBSTEPS : (int) Math.ceil(timeStep / maxTimeStep);
            substeps = Math.min(Math.max(substeps, 1), MAX_SUBSTEPS);
            float step = timeStep / substeps;

            float value = this.simulation;
            float velocity = this.simulationDot;
            for (int i = 0; i < substeps; i++) {
                value += step * velocity;
                velocity += step * (k3 * inputFunctionDot + this.input - value - k1 * velocity) / k2;
            }
            this.simulation = value;
            this.simulationDot = velocity;
        }

        @Override
        public float value() {
            return this.simulation;
        }
    }

    /** 一阶低通（YSM FirstOrder 的逐行移植）：response 是时间常数，单位秒。 */
    private static final class FirstOrder implements Simulation {

        private float input;
        private float response = DEFAULT_ARGUMENT;
        private float simulation;

        FirstOrder(float input, float response) {
            setArgs(input, response, DEFAULT_ARGUMENT, DEFAULT_ARGUMENT);
        }

        @Override
        public void setArgs(float input, float a, float b, float c) {
            this.input = finite(input, 0.0F);
            float constant = finite(a, DEFAULT_ARGUMENT);
            // 时间常数 0 会让 dt/response 变成 Infinity
            this.response = constant > 0.0F ? constant : DEFAULT_ARGUMENT;
        }

        @Override
        public void update(float timeStep) {
            float alpha = timeStep / this.response;
            this.simulation = (1.0F - alpha) * this.simulation + alpha * this.input;
        }

        @Override
        public float value() {
            return this.simulation;
        }
    }
}
