package me.zuogeren.slimefunhelperplus;

import me.matl114.hacks.api.ModuleGroup;
import me.matl114.plugins.api.PluginContext;
import me.matl114.plugins.api.PluginHosts;
import me.matl114.plugins.api.SlimefunHelperPlugin;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.conflux.ConfluxMapHelper;
import me.zuogeren.slimefunhelperplus.friendwine.FriendWine;
import me.zuogeren.slimefunhelperplus.locator.LocatorTrackModule;
import net.fabricmc.loader.api.ModContainer;

/**
 * SlimeFunHelper+ 的宿主插件入口。
 *
 * <p>由宿主插件管理器通过 Fabric entrypoint {@code slimefunhelper} 发现并实例化
 * （必须有无参构造器），随后按 config -> listener -> module -> post 四个阶段回调。
 * 本阶段只做骨架：记录生命周期日志，不注册任何模块 / 配置。
 */
public class PlusPlugin implements SlimefunHelperPlugin {
    public static final String PLUGIN_ID = "slimefunhelper-plus";

    private ModContainer container;

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public ModContainer getContainer() {
        return container;
    }

    @Override
    public void onDiscovered(ModContainer container) {
        this.container = container;
        String version = container == null ? "unknown" : container.getMetadata().getVersion().getFriendlyString();
        Debug.info("[SFH+] discovered plugin " + PLUGIN_ID + " (mod version " + version + ")");
    }

    @Override
    public void onConfigBootstrap() {
        Debug.info("[SFH+] onConfigBootstrap");
    }

    @Override
    public void onListenerBootstrap() {
        Debug.info("[SFH+] onListenerBootstrap");
    }

    @Override
    public void onModuleBootstrap() {
        PluginContext context = PluginHosts.context();
        ModuleGroup survival = context.modules().group("Survival");
        if (survival == null) {
            Debug.info("[SFH+] 未找到 Survival 模块组，跳过 Conflux 注册");
            return;
        }
        // 真身注册进宿主的 Survival 组，翻译键/开关沿用宿主那一套
        context.modules().register(PLUGIN_ID, "Survival", new ConfluxMapHelper());
        // 定位栏坐标反推挂到 Render 组（宿主的名单 NameList 也在这组，行由宿主排版）
        context.modules().register(PLUGIN_ID, "Render", new LocatorTrackModule());
        // 朋友的酒：纯渲染层效果，同样挂 Render 组
        context.modules().register(PLUGIN_ID, "Render", new FriendWine());
        // 再把本插件的模块聚合到一个只读视图组，方便集中查找
        context.modules().registerViewGroup(PLUGIN_ID, "SlimeFunHelper+", () -> context.modules().modulesOf(PLUGIN_ID));
        Debug.info("[SFH+] onModuleBootstrap: ConfluxMapHelper 已注册");
    }

    @Override
    public void postInitialize() {
        Debug.info("[SFH+] postInitialize, host plugin API v" + PluginHosts.context().API_VERSION);
    }
}
