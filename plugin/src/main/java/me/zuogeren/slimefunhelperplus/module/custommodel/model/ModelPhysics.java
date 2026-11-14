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
 * <p>k1/k2/k3 与子步切分照 YSM 的 {@code SecondOrder}（论文 KPoeNZZ6H4s），步长上限与子步数上限照 SPM 的同名实现
 * （YSM 不钳步长，卡顿时会切出上千个子步）。一帧延迟也照 YSM：调用方先 {@code setArgs} 再取 {@code value()}，
 * 用完的输入给下一帧积分，返回的是上一帧的结果。
 */
final class ModelPhysics {

    /** 参数缺省时 YSM 取 1（{@code ModelBuiltins#physics}），这里对齐同一份缺省。 */
    private static final float DEFAULT_ARGUMENT = 1.0F;
    /** 固有频率的上界照 YSM 的 clamp；下限取非零值，因为 k1/k2/k3 都要除以频率。 */
    private static final float MAX_FREQUENCY = 5.0F;
    private static final float MIN_FREQUENCY = 0.001F;
    /** 单帧时长上限：卡顿或暂停后回来的一帧不该把弹簧推飞。 */
    private static final float MAX_TIME_STEP = 0.1F;
    /** 子步数上限（SPM {@code SecondOrder:14} 的 {@code MAX_CYCLES}）：最硬的弹簧配 0.1 秒的一帧只要 4 个子步。 */
    private static final int MAX_SUBSTEPS = 16;
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

    /**
     * 每帧出图前推进一次；{@code ageSeconds} 是调用方那根渲染时钟。
     *
     * <p>只在时钟**前进**时积分（YSM {@code PhysicsManager:11-21} 的 {@code renderTicks > lastRenderTicks}）：
     * 首帧没有历史、同一帧的多次出图、时钟回退都不推进。大步长不在这里处理 —— 两个积分器各自把
     * 步长钳到 {@code MAX_TIME_STEP} 并按稳定性条件切子步，钳在调用方会让子步数算错。
     */
    void advance(float ageSeconds) {
        if (!Float.isFinite(ageSeconds)) {
            return;
        }
        float previous = lastTime;
        lastTime = ageSeconds;
        if (!Float.isFinite(previous) || ageSeconds <= previous) {
            return;
        }
        float timeStep = ageSeconds - previous;
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
            // 首帧没有历史可用，给输入值本身，避免通道从 0 弹起
            simulation = first ? new FirstOrder(input, a) : new SecondOrder(input, a, b, c);
            simulations.put(key, simulation);
            return simulation.value();
        }
        simulation.setArgs(input, a, b, c);
        return simulation.value();
    }

    /** NaN 会永久污染弹簧状态（之后每帧都是 NaN），遇到就按中性值处理，让积分继续跑下去。 */
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
            // 新建的模拟器没有历史，直接落在输入上，避免通道从 0 弹起
            snapToInput(finite(input, 0.0F));
        }

        @Override
        public void setArgs(float input, float a, float b, float c) {
            this.input = input;
            this.frequency = a;
            this.coefficient = b;
            this.response = c;
        }

        @Override
        public void update(float timeStep) {
            // 消毒放在积分时而不是 setArgs：setArgs 每帧都会被调用，这里一帧只做一次。
            // 非有限输入按 0 处理而不是跳过：跳过会让弹簧停在旧值上，通道再也回不到输入。
            float input = finite(this.input, 0.0F);
            if (!Float.isFinite(timeStep) || timeStep <= 0.0F) {
                this.inputFunction = input;
                sanitizeSimulation(input);
                return;
            }
            timeStep = Math.min(timeStep, MAX_TIME_STEP);
            float frequency = Math.min(Math.max(finite(this.frequency, 0.0F), MIN_FREQUENCY), MAX_FREQUENCY);
            float coefficient = Math.min(Math.max(finite(this.coefficient, 0.0F), 0.0F), 1.0F);
            float response = finite(this.response, 0.0F);

            float k1 = coefficient / PI / frequency;
            float k2 = 1.0F / (2.0F * PI * frequency) / (2.0F * PI * frequency);
            float k3 = response * coefficient / 2.0F / PI / frequency;
            if (!Float.isFinite(k1) || !Float.isFinite(k2) || k2 <= 0.0F || !Float.isFinite(k3)) {
                snapToInput(input);
                return;
            }

            float inputFunctionDot = (input - this.inputFunction) / timeStep;
            if (!Float.isFinite(inputFunctionDot)) {
                inputFunctionDot = 0.0F;
            }
            this.inputFunction = input;

            // 子步长超过 sqrt(4k2 + k1^2) - k1 时显式积分会发散，按它切分
            float maxTimeStep = (float) Math.sqrt(4.0F * k2 + k1 * k1) - k1;
            if (!Float.isFinite(maxTimeStep) || maxTimeStep <= 0.0F) {
                maxTimeStep = timeStep;
            }
            int substeps = Math.min(Math.max((int) Math.ceil(timeStep / maxTimeStep), 1), MAX_SUBSTEPS);
            float step = timeStep / substeps;

            float value = Float.isFinite(this.simulation) ? this.simulation : input;
            float velocity = Float.isFinite(this.simulationDot) ? this.simulationDot : 0.0F;
            for (int i = 0; i < substeps; i++) {
                value += step * velocity;
                velocity += step * (k3 * inputFunctionDot + input - value - k1 * velocity) / k2;
                if (!Float.isFinite(value) || !Float.isFinite(velocity)) {
                    snapToInput(input);
                    return;
                }
            }
            this.simulation = value;
            this.simulationDot = velocity;
        }

        /** 状态里残留的 NaN 会永久污染后续积分，遇到就按输入重建。 */
        private void sanitizeSimulation(float input) {
            if (!Float.isFinite(this.simulation) || !Float.isFinite(this.simulationDot)) {
                snapToInput(input);
            }
        }

        /** 状态已经不可用（参数退化或积分发散）：直接落到输入上，速度清零，下一帧从静止重新起算。 */
        private void snapToInput(float input) {
            this.inputFunction = input;
            this.simulation = input;
            this.simulationDot = 0.0F;
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
            // 新建的模拟器没有历史，直接落在输入上，避免通道从 0 弹起
            this.simulation = finite(input, 0.0F);
        }

        @Override
        public void setArgs(float input, float a, float b, float c) {
            this.input = input;
            this.response = a;
        }

        @Override
        public void update(float timeStep) {
            if (!Float.isFinite(this.input) || !Float.isFinite(this.response)
                    || !Float.isFinite(timeStep) || timeStep <= 0.0F) {
                return;
            }
            float input = this.input;
            float response = this.response;
            if (response <= 0.0F) {
                // 时间常数 0 会让 dt/response 变成 Infinity，直接跟随输入
                this.simulation = input;
                return;
            }
            // 步长钳到时间常数：离屏或跳帧后的大步长会让 (1 - step/response) 变负，低通变成过冲震荡
            float step = Math.min(timeStep, response);
            float alpha = step / response;
            this.simulation = (1.0F - alpha) * this.simulation + alpha * input;
        }

        @Override
        public float value() {
            return this.simulation;
        }
    }
}
