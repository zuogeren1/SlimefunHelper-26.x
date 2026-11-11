/*
 * Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
 * Copyright (c) 2026 FirstSight. Licensed under the MIT License.
 * Modified for SlimeFunHelper+: package, namespace and Minecraft 26.x API wiring.
 */
package me.zuogeren.slimefunhelperplus.module.carbon;

import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.zuogeren.slimefunhelperplus.config.PluginConfig;

/**
 * 物品模型 GPU 实例化（移植自 Carbon）。
 *
 * <p>把重复物品的模型几何缓存起来、用实例化合并绘制，默认只接管世界里的掉落物；
 * 标准物品提交（手持 / 界面 / 展示）走同一套批表，是否启用由配置控制。
 *
 * <p>默认关闭：它会接管原版渲染通道，关掉时行为必须与原版逐像素一致，
 * 所以在实机确认之前不随插件默认开启。
 */
public class CarbonModule extends BaseModule {

    private static final Config CONFIG = PluginConfig.get();

    public final ModulePath root = makePath(CONFIG, "carbon-render");

    public final FlagRef enable = flagBuilder(root.addEnable()).defaultValue(false).build();

    /** 掉落物专用管线：关掉后掉落物仍可能走通用路径，但不再做实体级批处理。 */
    public final FlagRef optimizeDroppedItems =
            flagBuilder(root.add("optimize-dropped-items")).defaultValue(true).build();

    /** 物理旋转观感（需要掉落物专用管线）。 */
    public final FlagRef physicalDroppedItems =
            flagBuilder(root.add("physical-dropped-items")).defaultValue(false).build();

    /** 跳过视锥与遮挡检查，只保留距离限制；关掉更保守。 */
    public final FlagRef skipVisibilityChecks =
            flagBuilder(root.add("skip-visibility-checks")).defaultValue(false).build();

    /** 每帧允许花在缓存更新上的毫秒数；0 表示当帧做完。 */
    public final DoubleRef cacheUpdateBudgetMs = doubleBuilder(root.add("cache-update-budget-ms"))
            .defaultValue(2.0D)
            .validator(Configs.doubleRange(0.0D, 20.0D))
            .build();

    public CarbonModule() {
        super("CarbonRender");
        bindFlag(enable);
    }

    @Override
    public void onEnableModule() {
        CarbonRenderer.setEnabled(true);
        log("Carbon 物品实例化已启用");
    }

    @Override
    public void onDisableModule() {
        CarbonRenderer.setEnabled(false);
        log("Carbon 物品实例化已停用");
    }
}
