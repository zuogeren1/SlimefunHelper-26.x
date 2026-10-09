package me.matl114.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import lombok.Getter;
import me.matl114.events.annotations.Broadcast;
import me.matl114.events.annotations.ExtraArgs;
import me.matl114.events.channels.EventChannel;
import me.matl114.hooks.impl.confluxmap.ConfluxMenuContext;
import me.matl114.hooks.impl.confluxmap.ConfluxMenuTarget;
import me.matl114.utils.Debug;
import net.fabricmc.loader.api.FabricLoader;

/**
 * conflux-map 的接入点，形态照抄 {@link XaeroHooks}：
 * 未安装 / 结构不符时一律降级为“不启用”，绝不抛异常。
 *
 * <p>自检在构造期做一次：{@code FullscreenMapScreen} 的
 * {@code locationMenuBounds / locationMenuTarget / renderContents / mouseClicked / keyPressed / viewSession}
 * 必须都在，**而且类型要对**（字段比 {@code field.getType()}、方法比返回类型），
 * 缺一个或类型不符都会把「名字 + 期望类型 + 实际类型」逐条 WARN 出来，
 * 并让 {@link #isEnabled()} 返回 false。
 *
 * <p>只按名字查是不够的：mixin 的 {@code @Shadow} 在没有 refMap 时按「名字 + 描述符」匹配，
 * 名字在、类型变了照样在 apply 阶段炸（26.2 上钩子整体失效、26.1.2 上直接 entrypoint 失败）。
 */
public class ConfluxMapHooks implements IHooks {
    public static final ConfluxMapHooks INSTANCE = new ConfluxMapHooks();

    public static ConfluxMapHooks getInstance() {
        return INSTANCE;
    }

    /** conflux-map 的模组 id */
    public static final String MOD_ID = "confluxmap";

    /** 被我们盯上的那个界面（用字符串而不是类字面量，缺依赖时也不会连累类加载） */
    private static final String SCREEN_CLASS = "cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen";

    boolean enable;
    String unsupportedReason;

    @Getter
    @Broadcast
    @ExtraArgs({ConfluxMenuTarget.class})
    private static final EventChannel<ArrayList<ConfluxMenuContext>> locationMenuOption = new EventChannel<>();

    public ConfluxMapHooks() {
        enable = false;
        unsupportedReason = null;
        try {
            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
                unsupportedReason = "conflux-map is not installed";
                return;
            }
            // 这些清单只能当局部变量！一旦提成 static 字段，javac 会按文本顺序生成 <clinit>，
            // 构造 INSTANCE 时数组还是 null -> arraylength NPE，被 catch(Throwable) 吞掉，
            // 结果就是 isEnabled() 恒 false、一条菜单都不产出。
            // 期望类型同样用字符串写，不能写成 conflux-map 的类字面量（它们是包级私有类型）。
            String[][] requiredFields = {
                {"locationMenuBounds", "cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapLocationMenu$Bounds"},
                {"locationMenuTarget", "cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapLocationMenu$Target"},
            };
            String[][] requiredMethods = {
                {"renderContents", "void"},
                {"mouseClicked", "boolean"},
                {"keyPressed", "boolean"},
                {"viewSession", "cn.net.rms.confluxmap.core.task.SessionGuard$Session"},
            };

            ArrayList<String> problems = new ArrayList<>();
            Class<?> screen = Class.forName(SCREEN_CLASS);
            for (String[] required : requiredFields) {
                String name = required[0];
                String expected = required[1];
                try {
                    Field field = screen.getDeclaredField(name);
                    if (Modifier.isStatic(field.getModifiers())) {
                        problems.add("field " + name + ": expected " + expected + ", actual <static field>");
                    } else if (!field.getType().getName().equals(expected)) {
                        problems.add("field " + name + ": expected " + expected + ", actual "
                                + field.getType().getName());
                    }
                } catch (Throwable e) {
                    problems.add("field " + name + ": expected " + expected + ", actual <missing>");
                }
            }
            for (String[] required : requiredMethods) {
                String name = required[0];
                String expected = required[1];
                Method found = null;
                for (Method method : screen.getDeclaredMethods()) {
                    if (method.getName().equals(name)) {
                        found = method;
                        break;
                    }
                }
                if (found == null) {
                    problems.add("method " + name + "(): expected " + expected + ", actual <missing>");
                } else if (!found.getReturnType().getName().equals(expected)) {
                    problems.add("method " + name + "(): expected " + expected + ", actual "
                            + found.getReturnType().getName());
                }
            }
            if (!problems.isEmpty()) {
                unsupportedReason = "conflux-map structure changed: " + String.join("; ", problems);
                Debug.getLogger().warn("ConfluxMapHelper disabled: " + unsupportedReason);
                for (String problem : problems) {
                    Debug.getLogger().warn("  - " + problem);
                }
                return;
            }
            enable = true;
        } catch (Throwable e) {
            unsupportedReason = "conflux-map probe failed: " + e;
            Debug.getLogger().warn("ConfluxMapHelper disabled: " + unsupportedReason);
        }
    }

    @Override
    public boolean isEnabled() {
        return enable;
    }

    public String getUnsupportedReason() {
        return unsupportedReason;
    }
}
