package me.zuogeren.slimefunhelperplus.module.custommodel.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Molang 的数值子集：覆盖模型里最常见的写法（{@code math.sin/cos}、{@code query.anim_time}、
 * {@code math.clamp} 等），够让 idle 呼吸、头发摆动这类通道动起来。
 *
 * <p>不是完整实现：条件、四则、比较、三元、常用数学函数，以及 YSM 的物理内建函数
 * （{@code ysm.second_order} / {@code ysm.first_order}，弹簧状态在 {@link ModelPhysics}）都支持。
 * {@code v.*} 是模型自定义变量，读写都落在 {@link MolangVariables} 的变量表上；未赋值的按 0 读
 * （NaN 会把整条通道算废）。写入侧是 {@link #statements}：模型的弹簧物理全写在各片段的 timeline 语句里，
 * 功能骨骼（0 面片）则把同样的语句串直接写在通道上，由 {@link #channel} 分流。
 *
 * <p>解析失败按 0 处理，不抛异常：一个坏表达式不应该让整帧的骨骼矩阵算不出来。
 */
public final class MolangExpression {

    public interface Expr {
        float eval(float animTime, float lifeTime);

        /**
         * 本帧这条表达式是不是「没有值」（{@code v.*} 还没被写过）。只有 {@code ??} 用到它：
         * 其余场合未赋值的局部变量一律按 0 参与运算，与作者 {@code expr + v.hv} 的写法对得上。
         */
        default boolean isNull(float animTime, float lifeTime) {
            return Float.isNaN(eval(animTime, lifeTime));
        }
    }

    /** 时间轴上的语句：求值只为副作用（写 {@code v.*}），返回值丢弃。 */
    public interface Statement {
        void run(float animTime, float lifeTime);
    }

    /**
     * 未知标识符（YSM 自己的 {@code ysm.*} 查询、控制器写入的 {@code v.*}）按 NaN 处理：
     * 算术上会传染成「无效值」，比较与三元当作 false，最后在采样时落回该通道的中性值。
     * 直接用 0 会让 {@code hungry ? 姿势 : 0} 这类条件恒真，模型永远摆出饥饿姿势。
     */
    private static final float UNKNOWN = Float.NaN;
    private static final Expr ZERO = (animTime, lifeTime) -> 0.0F;
    private static final Expr ONE = (animTime, lifeTime) -> 1.0F;
    private static final Map<String, Expr> CACHE = new ConcurrentHashMap<>();
    /** 语句串按原文缓存：同一个模型每帧都会重复求值同一批语句。 */
    private static final Map<String, List<Statement>> PROGRAM_CACHE = new ConcurrentHashMap<>();
    /** 通道串「是不是语句」的判定结果，省得每帧重新探一次。 */
    private static final Map<String, Boolean> STATEMENT_FLAGS = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 2048;

    private MolangExpression() {}

    private static String stripTrailingSemicolons(String source) {
        String text = source.trim();
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (c != ';' && !Character.isWhitespace(c)) {
                break;
            }
            end--;
        }
        return text.substring(0, end);
    }

    public static Expr of(String source) {
        if (source == null || source.isBlank()) {
            return ZERO;
        }
        // 部分模型的关键帧值以分号结尾（Blockbench 导出习惯，例如 `(v.qh==1?(-167)) + (v.jump?(...));`）。
        // 不剥掉的话解析器停在分号前、atEnd() 为假，整条表达式会被判成 ZERO，骨骼就停在静止姿态。
        String key = stripTrailingSemicolons(source);
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

    /**
     * 把一段时间轴语句（模型里的 {@code timelineEvents}，形如 {@code "v.a=1;v.b=v.a+1"}）编译成语句表。
     *
     * <p>单条语句解不出来只跳过这一条：模型的物理是一长串弹簧积分语句，一条写错的语句不该让整段停摆。
     */
    public static List<Statement> statements(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String key = source.trim();
        List<Statement> cached = PROGRAM_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        List<Statement> parsed;
        try {
            parsed = new Parser(key).program();
        } catch (RuntimeException malformed) {
            parsed = List.of();
        }
        if (PROGRAM_CACHE.size() > CACHE_LIMIT) {
            PROGRAM_CACHE.clear();
        }
        PROGRAM_CACHE.put(key, parsed);
        return parsed;
    }

    /**
     * 单个通道的字符串：通常是纯表达式；功能骨骼（模型里 0 面片的 {@code molang} 系列）把语句串写进通道，
     * 这种按副作用执行、通道值给 0 —— 语句本身只为写变量，作者拿它承载纯表达式逻辑。
     */
    public static float channel(String source, float animTime, float lifeTime) {
        if (source == null || source.isBlank()) {
            return 0.0F;
        }
        if (!isStatement(source)) {
            return of(source).eval(animTime, lifeTime);
        }
        for (Statement statement : statements(source)) {
            statement.run(animTime, lifeTime);
        }
        return 0.0F;
    }

    /** 串的第一条语句是不是赋值（{@code v.x = ...}）；纯表达式即使含 {@code ==} 也不算。 */
    public static boolean isStatement(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        Boolean cached = STATEMENT_FLAGS.get(source);
        if (cached != null) {
            return cached;
        }
        boolean parsed;
        try {
            parsed = new Parser(source.trim()).assignmentStart();
        } catch (RuntimeException malformed) {
            parsed = false;
        }
        if (STATEMENT_FLAGS.size() > CACHE_LIMIT) {
            STATEMENT_FLAGS.clear();
        }
        STATEMENT_FLAGS.put(source, parsed);
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

        /** 逐条解析语句表；解不出来的一条跳到下一个分号，保证后面的语句还能跑。 */
        List<Statement> program() {
            List<Statement> statements = new ArrayList<>();
            while (true) {
                skipSpace();
                while (peek(';')) {
                    this.index++;
                    skipSpace();
                }
                if (atEnd()) {
                    return statements;
                }
                int start = this.index;
                try {
                    statements.add(statement());
                    skipSpace();
                    if (!atEnd() && !peek(';')) {
                        throw new IllegalArgumentException("trailing text");
                    }
                } catch (RuntimeException broken) {
                    this.index = start;
                    while (this.index < this.text.length() && this.text.charAt(this.index) != ';') {
                        this.index++;
                    }
                }
            }
        }

        /** 只探「下一条语句是不是赋值」，给 {@link #isStatement} 分流通道串用。 */
        boolean assignmentStart() {
            // 作者常把一句说明串挂在语句前面（{@code '头发垂直';v.x=1}），跳过它再判
            while (true) {
                skipSpace();
                char quote = peekChar();
                if (quote != '\'' && quote != '"') {
                    break;
                }
                text();
                skipSpace();
                if (peek(';')) {
                    this.index++;
                }
            }
            String name = identifierName();
            if (name == null) {
                return false;
            }
            skipSpace();
            return peek('=') && !peek("==");
        }

        /** {@code v.x = expr} 是赋值，其余按纯表达式求值后丢弃。 */
        private Statement statement() {
            int start = this.index;
            String target = identifierName();
            if (target != null) {
                skipSpace();
                if (peek('=') && !peek("==")) {
                    this.index++;
                    Expr value = expression();
                    return (animTime, lifeTime) -> MolangVariables.set(target, value.eval(animTime, lifeTime));
                }
            }
            this.index = start;
            Expr discarded = expression();
            return (animTime, lifeTime) -> discarded.eval(animTime, lifeTime);
        }

        /** 只吃掉标识符本体（含命名空间的点），函数调用块留给后面的解析；不是标识符返回 null。 */
        private String identifierName() {
            skipSpace();
            char first = peekChar();
            if (!Character.isLetter(first) && first != '_') {
                return null;
            }
            int start = this.index;
            while (this.index < this.text.length()) {
                char letter = this.text.charAt(this.index);
                if (Character.isLetterOrDigit(letter) || letter == '_' || letter == '.') {
                    this.index++;
                } else {
                    break;
                }
            }
            return this.text.substring(start, this.index).toLowerCase(java.util.Locale.ROOT);
        }

        /**
         * Molang 的空值合并 {@code a ?? b}：左边没有值（{@code v.*} 还没被写过）时取右边。
         *
         * <p>优先级夹在三元与 {@code ||} 之间，正好避开 {@code ? :} 的首问号。
         * 未赋值的 {@code v.*} 在算术里仍当 0 用（作者写成 {@code expr + v.hv}），只有 {@code ??}
         * 这一处需要区分「没值」与「值是 0」，判定交给 {@link Variable#isNull}：表达式按原文缓存，
         * 变量表每帧都在变，只能运行期问。
         */
        private Expr coalesce() {
            Expr left = or();
            while (true) {
                skipSpace();
                if (!peek("??")) {
                    return left;
                }
                this.index += 2;
                Expr right = or();
                Expr first = left;
                left = (animTime, lifeTime) -> first.isNull(animTime, lifeTime)
                        ? right.eval(animTime, lifeTime)
                        : first.eval(animTime, lifeTime);
            }
        }

        private Expr ternary() {
            Expr condition = coalesce();
            skipSpace();
            if (!peek('?')) {
                return condition;
            }
            this.index++;
            Expr whenTrue = expression();
            skipSpace();
            // Molang 允许省略 else：`cond ? a` 在条件为假时取 0。模型里大量这么写
            // （`(v.qh==1?(-167)) + (v.jump?(-20))`），缺了这条整条表达式会被判成非法、返回 0。
            Expr whenFalse;
            if (peek(':')) {
                this.index++;
                whenFalse = expression();
            } else {
                whenFalse = (animTime, lifeTime) -> 0.0F;
            }
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
            if (first == '\'' || first == '"') {
                return text();
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

        /** 字符串字面量只当内建函数的实参（物理 key、骨骼名）用，本身求值为 0，名字由 {@link #literal} 取。 */
        private Expr text() {
            char quote = this.text.charAt(this.index);
            this.index++;
            int start = this.index;
            while (this.index < this.text.length() && this.text.charAt(this.index) != quote) {
                this.index++;
            }
            if (this.index >= this.text.length()) {
                throw new IllegalArgumentException("unterminated string");
            }
            String value = this.text.substring(start, this.index);
            this.index++;
            return new Literal(value);
        }

        /** 变量引用：{@code v.*} 未赋值时 {@link #eval} 给 0，{@link #isNull} 给真。 */
        private record Variable(String name) implements Expr {
            @Override
            public float eval(float animTime, float lifeTime) {
                return MolangVariables.get(name);
            }

            @Override
            public boolean isNull(float animTime, float lifeTime) {
                // 未赋过值的 v.* 是「空」；查询量没有值（NaN）同样算空，?? 才会退到默认值
                return !MolangVariables.isDefined(name) || Float.isNaN(eval(animTime, lifeTime));
            }
        }

        private record Literal(String text) implements Expr {
            @Override
            public float eval(float animTime, float lifeTime) {
                return 0.0F;
            }
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
                    // ysm.* / query.* 的实体查询量按每帧绑定的上下文取值；未实现的仍落 NaN。
                    // v.* 用 Variable 包一层：算术上当 0，但 ?? 能问出「有没有被赋过值」。
                    return new Variable(name);
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
                // 反三角函数返回**弧度**：模型普遍写成 80*math.acos(x)/math.pi 这种「弧度除以 π 再缩放」的形式，
                // 换成角度会让结果放大 57.3 倍，喂进弹簧直接发散。
                case "math.asin" -> {
                    return (animTime, lifeTime) -> (float) Math.asin(a.eval(animTime, lifeTime));
                }
                case "math.acos" -> {
                    return (animTime, lifeTime) -> (float) Math.acos(a.eval(animTime, lifeTime));
                }
                case "math.atan" -> {
                    return (animTime, lifeTime) -> (float) Math.atan(a.eval(animTime, lifeTime));
                }
                case "math.atan2" -> {
                    return (animTime, lifeTime) -> (float) Math.atan2(
                            a.eval(animTime, lifeTime), b.eval(animTime, lifeTime));
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
                // 自然对数：SPM 的 math.ln 与 MoLang 的 math.log 同义
                case "math.ln" -> {
                    return (animTime, lifeTime) -> (float) Math.log(a.eval(animTime, lifeTime));
                }
                // YSM/SPM 的 math.random(min, max) 是闭区间随机；省参时取 [0,1)
                case "math.random" -> {
                    return (animTime, lifeTime) -> {
                        if (arguments.size() < 2) {
                            return java.util.concurrent.ThreadLocalRandom.current().nextFloat();
                        }
                        float low = a.eval(animTime, lifeTime);
                        float high = b.eval(animTime, lifeTime);
                        float min = Math.min(low, high);
                        return min + java.util.concurrent.ThreadLocalRandom.current().nextFloat()
                                * (Math.max(low, high) - min);
                    };
                }
                // 控制器条件里的手持物判定：筛选串按 YSM 的 empty / $id / #tag / :分类 前缀解析
                case "ctrl.hold" -> {
                    return itemCheckCall(arguments, MolangVariables.CHECK_HOLD);
                }
                case "ctrl.swing" -> {
                    return itemCheckCall(arguments, MolangVariables.CHECK_SWING);
                }
                case "ctrl.use" -> {
                    return itemCheckCall(arguments, MolangVariables.CHECK_USE);
                }
                case "query.equipped_item_any_tag" -> {
                    return equippedTagCall(arguments);
                }
                // YSM 的 math.random_integer 是 [min, max) 的整数；YSM 的 nextInt 在区间为空时会抛，
                // 这里退化成返回下界，作者的随机抖动不该让整帧算不出来。
                case "math.random_integer" -> {
                    return (animTime, lifeTime) -> {
                        int low = (int) a.eval(animTime, lifeTime);
                        int high = (int) b.eval(animTime, lifeTime);
                        if (low > high) {
                            int swap = low;
                            low = high;
                            high = swap;
                        }
                        if (high <= low) {
                            return (float) low;
                        }
                        return low + java.util.concurrent.ThreadLocalRandom.current().nextInt(high - low);
                    };
                }
                case "query.position_delta" -> {
                    return (animTime, lifeTime) -> MolangVariables.positionDelta(a.eval(animTime, lifeTime));
                }
                // YSM 的物理内建函数：首参是字符串 key（同名即同一个模拟器），其余是输入与弹簧参数
                case "ysm.first_order" -> {
                    String key = literal(arguments, 0);
                    Expr input = argument(arguments, 1);
                    Expr response = orOne(arguments, 2);
                    return (animTime, lifeTime) -> ModelPhysics.firstOrder(
                            key, input.eval(animTime, lifeTime), response.eval(animTime, lifeTime));
                }
                case "ysm.second_order" -> {
                    String key = literal(arguments, 0);
                    Expr input = argument(arguments, 1);
                    Expr frequency = orOne(arguments, 2);
                    Expr coefficient = orOne(arguments, 3);
                    Expr response = orOne(arguments, 4);
                    return (animTime, lifeTime) -> ModelPhysics.secondOrder(key, input.eval(animTime, lifeTime),
                            frequency.eval(animTime, lifeTime), coefficient.eval(animTime, lifeTime),
                            response.eval(animTime, lifeTime));
                }
                default -> {
                    return (animTime, lifeTime) -> UNKNOWN;
                }
            }
        }

        /** {@code ctrl.hold(slot, filter)}：两个字符串字面量，缺一个就恒假。 */
        private static Expr itemCheckCall(List<Expr> arguments, int operation) {
            String slot = literal(arguments, 0);
            String filter = literal(arguments, 1);
            if (slot.isEmpty() || filter.isEmpty()) {
                return (animTime, lifeTime) -> 0.0F;
            }
            return (animTime, lifeTime) -> MolangVariables.itemCheck(slot, filter, operation);
        }

        /** {@code query.equipped_item_any_tag(slot, tag...)}：第一个实参是槽名，其余是物品标签。 */
        private static Expr equippedTagCall(List<Expr> arguments) {
            String slot = literal(arguments, 0);
            List<String> tags = new ArrayList<>(Math.max(0, arguments.size() - 1));
            for (int i = 1; i < arguments.size(); i++) {
                String tag = literal(arguments, i);
                if (!tag.isEmpty()) {
                    tags.add(tag);
                }
            }
            if (slot.isEmpty() || tags.isEmpty()) {
                return (animTime, lifeTime) -> 0.0F;
            }
            return (animTime, lifeTime) -> MolangVariables.equippedItemAnyTag(slot, tags);
        }

        private static Expr argument(List<Expr> arguments, int index) {
            return index < arguments.size() ? arguments.get(index) : ZERO;
        }

        private static String literal(List<Expr> arguments, int index) {
            return index < arguments.size() && arguments.get(index) instanceof Literal text
                    ? text.text()
                    : "";
        }

        /** 弹簧参数省掉时 YSM 取 1（{@code ModelBuiltins#physics}），不能跟着 {@link #argument} 取 0。 */
        private static Expr orOne(List<Expr> arguments, int index) {
            return index < arguments.size() ? arguments.get(index) : ONE;
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