package me.zuogeren.slimefunhelperplus.module.newclickgui.ui;

import java.util.function.Function;
import me.matl114.hacks.api.BaseModule;
import me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield.Ef;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * 一套界面风格：一个 id，加它自己的配色与屏工厂。
 *
 * <p>风格是整套切换的，不做逐项混搭——切角、强调色、宽体拉伸是配套的，拆开调只会得到两边都不像的结果。
 * 明暗仍在风格内部单独切，所以每套风格都要给两份 {@link Ef.Palette}。
 *
 * <p>屏工厂也归风格：不同风格对控件的重写程度不同，各写各的屏比在一套屏里塞分支干净。
 * 主屏与分类页都从这里取，界面代码不直接 {@code new} 具体屏。
 */
public record GuiStyle(
        Identifier id,
        Ef.Palette dark,
        Ef.Palette light,
        Function<Settings, Screen> mainScreen,
        PageFactory pages,
        ModuleConfigFactory moduleConfig) {

    /** 顶部分类 → 该风格下的一页；没做的分类返回 {@code null}，由调用方退回宿主的旧页面。 */
    @FunctionalInterface
    public interface PageFactory {
        Screen create(String key, Settings settings);
    }

    /** 模块配置页的工厂。 */
    @FunctionalInterface
    public interface ModuleConfigFactory {
        Screen create(BaseModule module, Settings settings);
    }

    /** 按当前明暗取这一套风格下的样式。 */
    public Ef.Palette palette(boolean light) {
        return light ? this.light : this.dark;
    }

    /** 打开这一套风格的主屏。 */
    public Screen openMain(Settings settings) {
        return mainScreen.apply(settings);
    }

    /** 打开这一套风格下的某个分类页。 */
    public Screen openPage(String key, Settings settings) {
        return pages == null ? null : pages.create(key, settings);
    }

    /** 打开某个模块的配置页：控件外观同样归风格。 */
    public Screen openModuleConfig(BaseModule module, Settings settings) {
        return moduleConfig == null ? null : moduleConfig.create(module, settings);
    }
}