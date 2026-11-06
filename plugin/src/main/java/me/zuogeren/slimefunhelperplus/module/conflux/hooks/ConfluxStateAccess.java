package me.zuogeren.slimefunhelperplus.module.conflux.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * conflux 全屏地图的<b>运行态读取桥</b>：位置菜单状态与视口几何一律走反射取，不写 {@code @Shadow}。
 *
 * <h2>为什么不写 {@code @Shadow}</h2>
 * conflux 0.1.16 把位置菜单的整块状态从 {@code FullscreenMapScreen} 搬进了
 * {@code MapLocationMenuController}（包级私有）：{@code locationMenuBounds} /
 * {@code locationMenuTarget} / {@code dismissLocationMenu()} 在屏幕类上已经不存在。
 * {@code @Shadow} 找不到成员就是 <b>mixin apply 失败</b>（整局游戏起不来），而本模块的硬约束是
 * 「结构不认识就整块不生效」—— 反射读不到只会返回 {@code null} / {@code false}，
 * 调用方静默降级，原因由 {@code ConfluxMapHooks} 的启动自检写进日志。
 *
 * <h2>两种布局共用一套名字</h2>
 * 0.1.16 之前状态挂在屏幕自己身上；0.1.16 起挂在屏幕的 {@code locationMenu} 字段上。
 * 两边的字段名 / 方法名完全一致，所以这里只做一件事：先找 {@code locationMenu}，
 * 找得到就把它当宿主，找不到就把屏幕自己当宿主。缓存按宿主类存，逐帧只有一次 Map 查表。
 *
 * <p>本类<b>不出现任何 conflux 类型</b>（包级私有类型也不出现），拿到的都是 {@code Object}；
 * 需要调用它们成员的地方必须在 mixin 文件里做（合进目标类之后才有同包访问权）。
 */
public final class ConfluxStateAccess {
    /** 屏幕上的控制器字段名（0.1.16+） */
    private static final String OWNER_FIELD = "locationMenu";

    private static final String BOUNDS_FIELD = "locationMenuBounds";

    private static final String TARGET_FIELD = "locationMenuTarget";

    private static final Map<Class<?>, Binding> BINDINGS = new ConcurrentHashMap<>();

    private ConfluxStateAccess() {}

    /** 控制器对象 → 它所属的全屏地图界面（字段 {@code screen}）；拿不到返回 {@code null} */
    public static Object screenOf(Object owner) {
        if (owner == null) {
            return null;
        }
        Optional<Field> field = SCREEN_FIELDS.computeIfAbsent(owner.getClass(), ConfluxStateAccess::findScreenField);
        if (field.isEmpty()) {
            return null;
        }
        try {
            return field.get().get(owner);
        } catch (Throwable e) {
            return null;
        }
    }

    private static final Map<Class<?>, Optional<Field>> SCREEN_FIELDS = new ConcurrentHashMap<>();

    private static Optional<Field> findScreenField(Class<?> owner) {
        try {
            Field field = owner.getDeclaredField("screen");
            field.setAccessible(true);
            return Optional.of(field);
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    /** 位置菜单状态的宿主：0.1.16+ 是控制器对象，更早是屏幕自己；拿不到返回 {@code null} */
    public static Object menuOwner(Object screen) {
        Binding binding = binding(screen);
        if (binding == null) {
            return null;
        }
        if (binding.owner == null) {
            return screen;
        }
        try {
            return binding.owner.get(screen);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 当前位置菜单的面板矩形（{@code FullscreenMapLocationMenu$Bounds}），菜单没开时返回 {@code null} */
    public static Object bounds(Object screen) {
        return read(binding(screen), Binding::boundsField, screen);
    }

    /** 当前位置菜单指向的目标（{@code FullscreenMapLocationMenu$Target}），菜单没开时返回 {@code null} */
    public static Object target(Object screen) {
        return read(binding(screen), Binding::targetField, screen);
    }

    /** 关掉位置菜单；拿不到方法就是无操作 */
    public static boolean dismiss(Object screen) {
        Binding binding = binding(screen);
        Object owner = menuOwner(screen);
        if (binding == null || binding.dismiss == null || owner == null) {
            return false;
        }
        try {
            binding.dismiss.invoke(owner);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 大厅会话（{@code SessionGuard$Session}），拿不到返回 {@code null} */
    public static Object viewSession(Object screen) {
        Binding binding = binding(screen);
        if (binding == null || binding.viewSession == null) {
            return null;
        }
        try {
            return binding.viewSession.invoke(screen);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 当前视口是不是「当前存档 + 当前维度」的实时会话；判断不出来一律按否处理 */
    public static boolean viewingLiveSession(Object screen) {
        Binding binding = binding(screen);
        if (binding == null || binding.viewingLiveSession == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(binding.viewingLiveSession.invoke(screen));
        } catch (Throwable e) {
            return false;
        }
    }

    public static double centerX(Object screen) {
        return readDouble(binding(screen), Binding::centerXField, screen);
    }

    public static double centerZ(Object screen) {
        return readDouble(binding(screen), Binding::centerZField, screen);
    }

    public static double scale(Object screen) {
        return readDouble(binding(screen), Binding::scaleField, screen);
    }

    /**
     * {@code MinimapRadarPainter} / {@code MinimapHudRenderer} 上的 {@code config} 是不是圆形小地图
     * （{@code ConfluxConfig#minimapShape == CIRCLE}）。
     *
     * <p>{@code minimapShape} 在 {@code ConfluxConfig} 上是 public 字段，按名字取即可；
     * 取不到按矩形处理（裁剪区域大一点，不会切掉本该显示的内容）。
     */
    public static boolean circularMinimap(Object renderer) {
        if (renderer == null) {
            return false;
        }
        try {
            Field configField = configField(renderer.getClass());
            Field shapeField = shapeField(configField.getType());
            Object config = configField.get(renderer);
            Object shape = config == null ? null : shapeField.get(config);
            return shape instanceof Enum<?> constant && "CIRCLE".equals(constant.name());
        } catch (Throwable e) {
            return false;
        }
    }

    private static final Map<Class<?>, Field> CONFIG_FIELDS = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Field> SHAPE_FIELDS = new ConcurrentHashMap<>();

    private static Field configField(Class<?> ownerClass) throws NoSuchFieldException {
        Field cached = CONFIG_FIELDS.get(ownerClass);
        if (cached != null) {
            return cached;
        }
        Field field = ownerClass.getDeclaredField("config");
        field.setAccessible(true);
        CONFIG_FIELDS.put(ownerClass, field);
        return field;
    }

    private static Field shapeField(Class<?> configClass) throws NoSuchFieldException {
        Field cached = SHAPE_FIELDS.get(configClass);
        if (cached != null) {
            return cached;
        }
        Field field = configClass.getField("minimapShape");
        SHAPE_FIELDS.put(configClass, field);
        return field;
    }

    private static Object read(Binding binding, java.util.function.Function<Binding, Field> picker, Object screen) {
        if (binding == null) {
            return null;
        }
        Object owner = menuOwner(screen);
        Field field = picker.apply(binding);
        if (owner == null || field == null) {
            return null;
        }
        try {
            return field.get(owner);
        } catch (Throwable e) {
            return null;
        }
    }

    private static double readDouble(Binding binding, java.util.function.Function<Binding, Field> picker, Object screen) {
        if (binding == null || screen == null) {
            return Double.NaN;
        }
        Field field = picker.apply(binding);
        if (field == null) {
            return Double.NaN;
        }
        try {
            return field.getDouble(screen);
        } catch (Throwable e) {
            return Double.NaN;
        }
    }

    private static Binding binding(Object screen) {
        return screen == null ? null : BINDINGS.computeIfAbsent(screen.getClass(), ConfluxStateAccess::resolve);
    }

    private static Binding resolve(Class<?> screenClass) {
        Field owner = declaredField(screenClass, OWNER_FIELD);
        Class<?> ownerClass = owner != null ? owner.getType() : screenClass;
        return new Binding(
                owner,
                declaredField(ownerClass, BOUNDS_FIELD),
                declaredField(ownerClass, TARGET_FIELD),
                declaredMethod(ownerClass, "dismissLocationMenu"),
                declaredMethod(screenClass, "viewSession"),
                declaredMethod(screenClass, "viewingLiveSession"),
                declaredField(screenClass, "centerX"),
                declaredField(screenClass, "centerZ"),
                declaredField(screenClass, "scale"));
    }

    private static Field declaredField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable e) {
            return null;
        }
    }

    private static Method declaredMethod(Class<?> owner, String name) {
        try {
            Method method = owner.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 一个目标类上解析出来的句柄集合；缺失的成员保持 {@code null}，调用方各自降级 */
    private static final class Binding {
        final Field owner;
        final Field bounds;
        final Field target;
        final Method dismiss;
        final Method viewSession;
        final Method viewingLiveSession;
        final Field centerX;
        final Field centerZ;
        final Field scale;

        Binding(
                Field owner,
                Field bounds,
                Field target,
                Method dismiss,
                Method viewSession,
                Method viewingLiveSession,
                Field centerX,
                Field centerZ,
                Field scale) {
            this.owner = owner;
            this.bounds = bounds;
            this.target = target;
            this.dismiss = dismiss;
            this.viewSession = viewSession;
            this.viewingLiveSession = viewingLiveSession;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.scale = scale;
        }

        Field boundsField() {
            return bounds;
        }

        Field targetField() {
            return target;
        }

        Field centerXField() {
            return centerX;
        }

        Field centerZField() {
            return centerZ;
        }

        Field scaleField() {
            return scale;
        }
    }
}
