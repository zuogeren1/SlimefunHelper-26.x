package me.zuogeren.slimefunhelperplus.module.custommodel;

import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.StringRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.config.PluginConfig;
import me.zuogeren.slimefunhelperplus.module.custommodel.ui.ModelPickerScreen;
import me.zuogeren.slimefunhelperplus.module.custommodel.ui.ModelRouletteScreen;

/**
 * 本地玩家的自定义模型（YSM 类功能）。
 *
 * <p>只替换本地玩家自己的外观，不改服务端、不同步他人；模型文件放在
 * {@code config/slimefunhelper-plus/models}，选择与重载全部在本模块的配置页内完成。
 *
 * <p>默认关闭：开关只建立配置项与模块入口，模型加载与渲染替换在后续阶段接入。
 */
public class CustomModelModule extends BaseModule {

    public static CustomModelModule INSTANCE;

    private static final Config CONFIG = PluginConfig.get();

    public final ModulePath root = makePath(CONFIG, "custom-model");

    public final FlagRef enable = flagBuilder(root.addEnable()).defaultValue(false).build();

    /**
     * 开关热键，同时把模块登记进宿主的模块条目表。
     *
     * <p>{@code /sfh toggle <名字>} 与它的补全都只认 {@code getModuleEntries()}，
     * 而那张表只由 {@code moduleEntry(...)} 填；只 {@code bindFlag} 的模块在命令里不存在。
     */
    public final KeyBindRef hotkey = moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable())
            .build();

    /**
     * 模型文件名（相对 {@code config/slimefun-plus/models}）。
     *
     * <p>宿主 ClickGui 对 {@code StringRef} 一律画文本框，只有 {@code EnumAttrKeyValue} 走下拉；
     * 而配置项必须经 {@code builder(...)} 才会登记进界面，那条路固定产出 {@code StringRef}。
     * 所以宿主侧保持文本框，下拉只在 newclickgui 里提供（见 {@code registerStringChoices}）。
     */
    public final StringRef model = builder(root.add("model"), String.class)
            .defaultValue("")
            .updateListener(this::onModelChanged)
            .build();

    /**
     * 第一人称用模型自带的手臂替换原版皮肤手臂。
     *
     * <p>默认开：模型没带 armModel 时 mixin 会原样放行，「看不到手」不会发生，
     * 所以开着比关着更符合「选了自定义模型就该看到它」的预期。
     */
    public final FlagRef hideFirstPersonArm = flagBuilder(root.add("hide-first-person-arm"))
            .defaultValue(true)
            .build();

    /**
     * 诊断日志开关。
     *
     * <p>默认关：模型加载、贴图注册、动画采样这些路径每次都会打一行，正常游玩时只是噪音。
     * 排查「模型没出来 / 贴图不对 / 动作不播」时打开。
     */
    public final FlagRef debug = flagBuilder(root.add("debug")).defaultValue(false).build();

    /**
     * 模块内的诊断输出统一走这里。
     *
     * <p>开关关着时直接丢弃；调用点都是加载/注册这类冷路径，字符串拼接的开销可以忽略。
     */
    public static void logDebug(String message) {
        CustomModelModule module = INSTANCE;
        // 构造期 INSTANCE 已由 BaseModule 反射写入，但字段初始化还没跑完，debug 可能仍是 null
        if (module != null && module.debug != null && module.debug.get()) {
            Debug.info(message);
        }
    }

    /** 打开动画轮盘的热键；默认不绑，在模块配置页里设。 */
    public final KeyBindRef rouletteKey = hotkey(root.add("roulette-key"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler((Runnable) this::openRoulette))
            .build();

    /** 打开模型选择页的热键；默认不绑，在模块配置页里设。 */
    public final KeyBindRef modelPickerKey = hotkey(root.add("model-picker-key"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler((Runnable) this::openModelPicker))
            .build();

    /** 模型文件的候选值：空串（不选）+ 目录里扫到的所有模型。 */
    private static java.util.List<String> modelChoices() {
        java.util.List<String> names = ModelCatalog.names();
        java.util.List<String> choices = new java.util.ArrayList<>(names.size() + 1);
        choices.add("");
        choices.addAll(names);
        return choices;
    }

    public CustomModelModule() {
        super("CustomModel");
        bindFlag(enable);
        // newclickgui 的下拉读这张表；宿主 ClickGui 走 StringChoiceRef 的 keyValue，不需要注册
        me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield.EndfieldModuleConfigScreen
                .registerStringChoices(root.add("model").asString(), CustomModelModule::modelChoices);
    }

    @Override
    public void onEnableModule() {
        ModelCatalog.rescan();
        CustomModelManager.reloadAsync(model.get());
    }

    @Override
    public void onDisableModule() {
        CustomModelManager.clear();
    }

    private void onModelChanged(String value) {
        if (isActive()) {
            CustomModelManager.reloadAsync(value);
        }
    }

    /**
     * 模型选择页：不要求模型已加载 —— 这一页本来就是用来挑模型的，
     * 一个都没加载时正好从列表里选第一个。
     */
    public void openModelPicker() {
        if (!isActive()) {
            return;
        }
        ModelPickerScreen.open();
    }

    /** 热键与模块页动作行共用的入口：没启用或模型还没加载时什么都不做。 */
    public void openRoulette() {
        if (!isActive() || CustomModelManager.runtime() == null) {
            return;
        }
        ModelRouletteScreen.open();
    }
}
