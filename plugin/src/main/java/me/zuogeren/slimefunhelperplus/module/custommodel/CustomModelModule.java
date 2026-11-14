package me.zuogeren.slimefunhelperplus.module.custommodel;

import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.FlagRef;
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

    public CustomModelModule() {
        super("CustomModel");
        bindFlag(enable);
    }

    @Override
    public void onEnableModule() {
        log("自定义模型已启用（P2 格式层探针）");
        ModelProbe.scanAsync();
    }

    @Override
    public void onDisableModule() {
        log("自定义模型已停用");
    }
}
