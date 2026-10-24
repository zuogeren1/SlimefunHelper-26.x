package me.zuogeren.slimefunhelperplus;

import me.matl114.plugins.api.PluginHosts;
import me.matl114.plugins.api.SlimefunHelperPlugin;
import me.matl114.utils.Debug;
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
        Debug.info("[SFH+] onModuleBootstrap");
    }

    @Override
    public void postInitialize() {
        Debug.info("[SFH+] postInitialize, host plugin API v" + PluginHosts.context().API_VERSION);
    }
}
