package me.zuogeren.slimefunhelperplus.module.newclickgui;

import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.managers.Configs;
import me.zuogeren.slimefunhelperplus.config.PluginConfig;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldClickGuiScreen;
import me.zuogeren.slimefunhelperplus.module.newclickgui.ui.EndfieldSettings;
import net.minecraft.client.Minecraft;

/**
 * 自绘 ClickGui 的入口模块。界面样式与本站的配色无关：具体画法在 {@code ui} 包里，
 * 换一套外观只需替换那层的调色板与几何常量，这里不跟着动。
 *
 * <p>注册进宿主的模块表，所以在 ClickGui 列表里能看到、能被开关；
 * 但配置**全部存在插件自己的文件里**（{@code config/slimefunhelper-plus/config.yml} 的
 * {@code new-click-gui.*}），不占宿主配置树里的位置。
 *
 * <p>它没有开关：这个模块是触发型的，按键就开界面，不存在"关掉"这个状态。
 * 宿主各处对 {@code getBindFlag()} 返回值都有判空，所以不绑开关是安全的。
 *
 * <p>打开方式是模块自己的一条按键：配置页里设，默认不绑，不往宿主的
 * {@code click-gui-style} 里注册样式。
 */
public class NewClickGui extends BaseModule {
    private static NewClickGui instance;
    private static EndfieldSettings settings;

    private final ModulePath base;

    /** 打开界面的按键：存在插件配置里，配置页由宿主的键位控件渲染。 */
    public final KeyBindRef openKey;

    /** 界面背景的不透明度：1 = 不透明，越低越能看见背后的世界。 */
    public final DoubleRef opacity;

    /** 多人游戏界面右上角的 SlimefunHelper 按钮是否改成本界面；关掉时仍开宿主自己的 ClickGui。 */
    public final FlagRef takeoverMultiplayer;

    public NewClickGui() {
        super("NewClickGui");
        instance = this;

        this.base = makePath(PluginConfig.get(), "new-click-gui");
        this.opacity = doubleBuilder(base.add("opacity"))
                .defaultValue(0.92D)
                .validator(Configs.doubleRange(0.25D, 1.0D))
                .build();

        this.takeoverMultiplayer = flagBuilder(base.add("takeover-multiplayer"))
                .defaultValue(false)
                .build();

        this.openKey = hotkey(base.add("open-key"))
                .defaultValue(new MultiKeyBind())
                .registerHotkey(HotKeyUtils.wrapAsHandler((Runnable) this::openGui))
                .build();
    }

    /** 宿主把 ElementFactory 放在 protected static 里，这里替配置页开个口子。 */
    public static me.matl114.gui.interfaces.ElementFactory elementFactory() {
        return ELEMENT_FACTORY;
    }

    public static EndfieldSettings settings() {
        if (settings == null) {
            settings = new EndfieldSettings(PluginConfig.get());
        }
        return settings;
    }

    /** 背景不透明度，夹在合理区间里，配置里写坏了也不至于把界面画没。 */
    public static double opacity() {
        if (instance == null || instance.opacity == null) {
            return 0.92D;
        }
        return Math.max(0.25D, Math.min(1.0D, instance.opacity.get()));
    }

    /** 是否接管多人游戏界面右上角那个按钮。 */
    public static boolean takeoverMultiplayer() {
        return instance != null && instance.takeoverMultiplayer != null && instance.takeoverMultiplayer.get();
    }

    /**
     * 从任意界面打开本界面。
     *
     * <p>多人游戏界面还没有玩家实体，所以不能像热键路径那样按 {@code player} 判空；
     * 走来处记录，关闭时回到打开它的那个界面。
     */
    public static void openScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        ScreenAccess.of(new EndfieldClickGuiScreen(settings())).openFromCurrent();
    }

    private void openGui() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        openScreen();
    }

}
