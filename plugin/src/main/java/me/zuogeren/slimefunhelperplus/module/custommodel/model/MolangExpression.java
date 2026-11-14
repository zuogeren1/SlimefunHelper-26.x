package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Molang 的数值子集：覆盖模型里最常见的写法（{@code math.sin/cos}、{@code query.anim_time}、
 * {@code math.clamp} 等），够让 idle 呼吸、头发摆动这类通道动起来。
 *
 * <p>不是完整实现：条件、四则、比较、三元与常用数学函数都支持；YSM 的变量表（{@code v.*}、
 * {@code t.*}）与实体查询（{@code query.*} 除时间外）一律按 0 处理 —— 这些值要靠动画控制器
 * 或背包/装备状态写入，当前没有对应来源，返回 0 至少能保持静止而不是整条通道丢失。
 *
 * <p>解析失败按 0 处理，不抛异常：一个坏表达式不应该让整帧的骨骼矩阵算不出来。
 */
public final class MolangExpression {

    public interface Expr {
        float eval(float animTime, float lifeTime);
    }

    /**
     * 未知标识符（YSM 自己的 {@code ysm.*} 查询、控制器写入的 {@code v.*}）按 NaN 处理：
     * 算术上会传染成「无效值」，比较与三元当作 false，最后在采样时落回该通道的中性值。
     * 直接用 0 会让 {@code hungry ? 姿势 : 0} 这类条件恒真，模型永远摆出饥饿姿势。
     */
    private static final float UNKNOWN = Float.NaN;
    private static final Expr ZERO = (animTime, lifeTime) -> 0.0F;
    private static final Map<String, Expr> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 2048;

    private MolangExpression() {}

    public static Expr of(String source) {
        if (source == null || source.isBlank()) {
            return ZERO;
        }
        String key = source.trim();
        Expr cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Expr parsed;
        try {
            Parser parser = new Parser(key);
            Expr expr = parser.expression();
            parser.skipSpace();
            parsed = parser.atEnd() ? expr : ZERO;
        } catch (RuntimeException malformed) {
            parsed = ZERO;
        }
        if (CACHE.size() > CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(key, parsed);
        return parsed;
    }

    private static boolean truthy(float value) {
        return !Float.isNaN(value) && value != 0.0F;
    }

    private static final class Parser {

        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return this.index >= this.text.length();
        }

        void skipSpace() {
            while (this.index < this.text.length() && Character.isWhitespace(this.text.charAt(this.index))) {
                this.index++;
            }
        }

        Expr expression() {
            return ternary();
        }

        private Expr ternary() {
            Expr condition = or();
            skipSpace();
            if (!peek('?')) {
                return condition;
            }
            this.index++;
            Expr whenTrue = expression();
            skipSpace();
            if (!peek(':')) {
                throw new IllegalArgumentException("missing ':'");
            }
            this.index++;
            Expr whenFalse = expression();
            return (animTime, lifeTime) -> truthy(condition.eval(animTime, lifeTime))
                    ? whenTrue.eval(animTime, lifeTime)
                    : whenFalse.eval(animTime, lifeTime);
        }

        private Expr or() {
            Expr left = and();
            while (true) {
                skipSpace();
                if (!peek("||")) {
                    return left;
                }
                this.index += 2;
                Expr right = and();
                Expr first = left;
                left = (animTime, lifeTime) -> truthy(first.eval(animTime, lifeTime))
                        || truthy(right.eval(animTime, lifeTime)) ? 1.0F : 0.0F;
            }
        }

        private Expr and() {
            Expr left = equality();
            while (true) {
                skipSpace();
                if (!peek("&&")) {
                    return left;
                }
                this.index += 2;
                Expr right = equality();
                Expr first = left;
                left = (animTime, lifeTime) -> truthy(first.eval(animTime, lifeTime))
                        && truthy(right.eval(animTime, lifeTime)) ? 1.0F : 0.0F;
            }
        }

        private Expr equality() {
            Expr left = relational();
            while (true) {
                skipSpace();
                if (peek("==")) {
                    this.index += 2;
                    Expr right = relational();
                    Expr first = left;
                    left = (animTime, lifeTime) -> compare(first, right, animTime, lifeTime, '=', false);
                } else if (peek("!=")) {
                    this.index += 2;
                    Expr right = relational();
                    Expr first = left;
                    left = (animTime, lifeTime) -> compare(first, right, animTime, lifeTime, '=', true);
                } else {
                    return left;
                }
            }
        }

        private Expr relational() {
            Expr left = additive();
            while (true) {
                skipSpace();
                if (peek("<=")) {
                    this.index += 2;
                    Expr right = additive();
                    Expr first = left;
                    left = (animTime, lifeTime) -> compare(first, right, animTime, lifeTime, '<', true);
                } else if (peek(">=")) {
                    this.index += 2;
                    Expr right = additive();
                    Expr first = left;
                    left = (animTime, lifeTime) -> compare(first, right, animTime, lifeTime, '>', true);
                } else if (peek('<')) {
                    this.index++;
                    Expr right = additive();
                    Expr first = left;
                    left = (animTime, lifeTime) -> compare(first, right, animTime, lifeTime, '<', false);
                } else if (peek('>')) {
                    this.index++;
                    Expr right = additive();
                    Expr first = left;
                    left = (animTime, lifeTime) -> compare(first, right, animTime, lifeTime, '>', false);
                } else {
                    return left;
                }
            }
        }

        private Expr additive() {
            Expr left = multiplicative();
            while (true) {
                skipSpace();
                if (peek('+')) {
                    this.index++;
                    Expr right = multiplicative();
                    Expr first = left;
                    left = (animTime, lifeTime) -> first.eval(animTime, lifeTime) + right.eval(animTime, lifeTime);
                } else if (peek('-')) {
                    this.index++;
                    Expr right = multiplicative();
                    Expr first = left;
                    left = (animTime, lifeTime) -> first.eval(animTime, lifeTime) - right.eval(animTime, lifeTime);
                } else {
                    return left;
                }
            }
        }

        private Expr multiplicative() {
            Expr left = unary();
            while (true) {
                skipSpace();
                if (peek('*')) {
                    this.index++;
                    Expr right = unary();
                    Expr first = left;
                    left = (animTime, lifeTime) -> first.eval(animTime, lifeTime) * right.eval(animTime, lifeTime);
                } else if (peek('/')) {
                    this.index++;
                    Expr right = unary();
                    Expr first = left;
                    left = (animTime, lifeTime) -> first.eval(animTime, lifeTime) / right.eval(animTime, lifeTime);
                } else if (peek('%')) {
                    this.index++;
                    Expr right = unary();
                    Expr first = left;
                    left = (animTime, lifeTime) -> first.eval(animTime, lifeTime) % right.eval(animTime, lifeTime);
                } else {
                    return left;
                }
            }
        }

        private Expr unary() {
            skipSpace();
            if (peek('-')) {
                this.index++;
                Expr operand = unary();
                return (animTime, lifeTime) -> -operand.eval(animTime, lifeTime);
            }
            if (peek('+')) {
                this.index++;
                return unary();
            }
            if (peek('!')) {
                this.index++;
                Expr operand = unary();
                return (animTime, lifeTime) -> truthy(operand.eval(animTime, lifeTime)) ? 0.0F : 1.0F;
            }
            return primary();
        }

        private Expr primary() {
            skipSpace();
            if (peek('(')) {
                this.index++;
                Expr inner = expression();
                skipSpace();
                if (!peek(')')) {
                    throw new IllegalArgumentException("missing ')'");
                }
                this.index++;
                return inner;
            }
            char first = peekChar();
            if (first == 0) {
                throw new IllegalArgumentException("unexpected end");
            }
            if (Character.isDigit(first) || first == '.') {
                return number();
            }
            if (Character.isLetter(first) || first == '_') {
                return identifier();
            }
            throw new IllegalArgumentException("unexpected '" + first + "'");
        }

        private Expr number() {
            int start = this.index;
            while (this.index < this.text.length()
                    && (Character.isDigit(this.text.charAt(this.index)) || this.text.charAt(this.index) == '.')) {
                this.index++;
            }
            float value = Float.parseFloat(this.text.substring(start, this.index));
            return (animTime, lifeTime) -> value;
        }

        private Expr identifier() {
            int start = this.index;
            while (this.index < this.text.length()) {
                char letter = this.text.charAt(this.index);
                if (Character.isLetterOrDigit(letter) || letter == '_' || letter == '.') {
                    this.index++;
                } else {
                    break;
                }
            }
            String name = this.text.substring(start, this.index).toLowerCase(java.util.Locale.ROOT);
            skipSpace();
            if (peek('(')) {
                this.index++;
                List<Expr> arguments = new ArrayList<>(3);
                skipSpace();
                if (!peek(')')) {
                    arguments.add(expression());
                    while (true) {
                        skipSpace();
                        if (!peek(',')) {
                            break;
                        }
                        this.index++;
                        arguments.add(expression());
                    }
                }
                skipSpace();
                if (!peek(')')) {
                    throw new IllegalArgumentException("missing ')' in call");
                }
                this.index++;
                return call(name, arguments);
            }
            return variable(name);
        }

        private Expr variable(String name) {
            switch (name) {
                case "query.anim_time", "q.anim_time" -> {
                    return (animTime, lifeTime) -> animTime;
                }
                case "query.life_time", "q.life_time" -> {
                    return (animTime, lifeTime) -> lifeTime;
                }
                case "math.pi" -> {
                    return (animTime, lifeTime) -> (float) Math.PI;
                }
                default -> {
                    // ysm.* / query.* 的实体查询量：按每帧绑定的上下文取值；未实现的仍落 NaN
                    return (animTime, lifeTime) -> MolangVariables.get(name);
                }
            }
        }

        /**
         * 任一操作数无效（NaN）时比较按 false 处理，避免未知查询把条件判成真。
         *
         * <p>{@code '<'}/{@code '>'} 的 {@code inclusive} 表示闭区间，{@code '='} 的表示「不等」。
         */
        private static float compare(Expr left, Expr right, float animTime, float lifeTime,
                char operator, boolean inclusive) {
            float a = left.eval(animTime, lifeTime);
            float b = right.eval(animTime, lifeTime);
            if (Float.isNaN(a) || Float.isNaN(b)) {
                return 0.0F;
            }
            boolean result = switch (operator) {
                case '<' -> inclusive ? a <= b : a < b;
                case '>' -> inclusive ? a >= b : a > b;
                default -> inclusive ? a != b : a == b;
            };
            return result ? 1.0F : 0.0F;
        }

        private Expr call(String name, List<Expr> arguments) {
            Expr a = argument(arguments, 0);
            Expr b = argument(arguments, 1);
            Expr c = argument(arguments, 2);
            switch (name) {
                case "math.sin" -> {
                    return (animTime, lifeTime) -> (float) Math.sin(Math.toRadians(a.eval(animTime, lifeTime)));
                }
                case "math.cos" -> {
                    return (animTime, lifeTime) -> (float) Math.cos(Math.toRadians(a.eval(animTime, lifeTime)));
                }
                case "math.tan" -> {
                    return (animTime, lifeTime) -> (float) Math.tan(Math.toRadians(a.eval(animTime, lifeTime)));
                }
                case "math.asin" -> {
                    return (animTime, lifeTime) -> (float) Math.toDegrees(Math.asin(a.eval(animTime, lifeTime)));
                }
                case "math.acos" -> {
                    return (animTime, lifeTime) -> (float) Math.toDegrees(Math.acos(a.eval(animTime, lifeTime)));
                }
                case "math.atan" -> {
                    return (animTime, lifeTime) -> (float) Math.toDegrees(Math.atan(a.eval(animTime, lifeTime)));
                }
                case "math.atan2" -> {
                    return (animTime, lifeTime) -> (float) Math.toDegrees(
                            Math.atan2(a.eval(animTime, lifeTime), b.eval(animTime, lifeTime)));
                }
                case "math.abs" -> {
                    return (animTime, lifeTime) -> Math.abs(a.eval(animTime, lifeTime));
                }
                case "math.floor" -> {
                    return (animTime, lifeTime) -> (float) Math.floor(a.eval(animTime, lifeTime));
                }
                case "math.ceil" -> {
                    return (animTime, lifeTime) -> (float) Math.ceil(a.eval(animTime, lifeTime));
                }
                case "math.round" -> {
                    return (animTime, lifeTime) -> (float) Math.round(a.eval(animTime, lifeTime));
                }
                case "math.sqrt" -> {
                    return (animTime, lifeTime) -> (float) Math.sqrt(a.eval(animTime, lifeTime));
                }
                case "math.pow" -> {
                    return (animTime, lifeTime) -> (float) Math.pow(a.eval(animTime, lifeTime), b.eval(animTime, lifeTime));
                }
                case "math.exp" -> {
                    return (animTime, lifeTime) -> (float) Math.exp(a.eval(animTime, lifeTime));
                }
                case "math.log" -> {
                    return (animTime, lifeTime) -> (float) Math.log(a.eval(animTime, lifeTime));
                }
                case "math.clamp" -> {
                    return (animTime, lifeTime) -> Math.min(Math.max(a.eval(animTime, lifeTime), b.eval(animTime, lifeTime)),
                            c.eval(animTime, lifeTime));
                }
                case "math.lerp" -> {
                    return (animTime, lifeTime) -> {
                        float from = a.eval(animTime, lifeTime);
                        float to = b.eval(animTime, lifeTime);
                        return from + (to - from) * c.eval(animTime, lifeTime);
                    };
                }
                case "math.min" -> {
                    return (animTime, lifeTime) -> Math.min(a.eval(animTime, lifeTime), b.eval(animTime, lifeTime));
                }
                case "math.max" -> {
                    return (animTime, lifeTime) -> Math.max(a.eval(animTime, lifeTime), b.eval(animTime, lifeTime));
                }
                case "math.mod" -> {
                    return (animTime, lifeTime) -> a.eval(animTime, lifeTime) % b.eval(animTime, lifeTime);
                }
                case "math.hermite_blend" -> {
                    return (animTime, lifeTime) -> {
                        float t = Math.max(0.0F, Math.min(1.0F, a.eval(animTime, lifeTime)));
                        return t * t * (3.0F - 2.0F * t);
                    };
                }
                default -> {
                    return (animTime, lifeTime) -> UNKNOWN;
                }
            }
        }

        private static Expr argument(List<Expr> arguments, int index) {
            return index < arguments.size() ? arguments.get(index) : ZERO;
        }

        private boolean peek(char expected) {
            return this.index < this.text.length() && this.text.charAt(this.index) == expected;
        }

        private boolean peek(String expected) {
            return this.text.startsWith(expected, this.index);
        }

        private char peekChar() {
            return this.index < this.text.length() ? this.text.charAt(this.index) : 0;
        }
    }
}