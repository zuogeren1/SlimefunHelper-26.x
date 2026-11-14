package me.zuogeren.slimefunhelperplus.module.custommodel;

import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.StringRef;
import me.zuogeren.slimefunhelperplus.config.PluginConfig;

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

    /** 显示没有参与基础动画的骨骼（舞台道具/额外角色）；默认关，个别模型需要展示时再开。 */
    public final FlagRef showProps = flagBuilder(root.add("show-props")).defaultValue(false).build();

    /** 模型文件名（相对 {@code config/slimefunhelper-plus/models}），P4 接 ClickGui 选择器。 */
    public final StringRef model = builder(root.add("model"), String.class)
            .defaultValue("")
            .updateListener(this::onModelChanged)
            .build();

    public CustomModelModule() {
        super("CustomModel");
        bindFlag(enable);
        showProps.addUpdateListener(value -> {
            if (isActive()) {
                CustomModelManager.reloadAsync(model.get());
            }
        });
    }

    @Override
    public void onEnableModule() {
        log("自定义模型已启用");
        ModelCatalog.rescan();
        CustomModelManager.reloadAsync(model.get());
    }

    @Override
    public void onDisableModule() {
        CustomModelManager.clear();
        log("自定义模型已停用");
    }

    private void onModelChanged(String value) {
        if (isActive()) {
            CustomModelManager.reloadAsync(value);
        }
    }
}
