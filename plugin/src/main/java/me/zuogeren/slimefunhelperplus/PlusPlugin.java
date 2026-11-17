package me.zuogeren.slimefunhelperplus;

import java.util.function.Supplier;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.plugins.api.PluginContext;
import me.matl114.plugins.api.PluginHosts;
import me.matl114.plugins.api.SlimefunHelperPlugin;
import me.matl114.utils.Debug;
import me.zuogeren.slimefunhelperplus.module.carbon.CarbonModule;
import me.zuogeren.slimefunhelperplus.module.conflux.ConfluxMapHelper;
import me.zuogeren.slimefunhelperplus.module.custommodel.CustomModelModule;
import me.zuogeren.slimefunhelperplus.module.friendwine.FriendWine;
import me.zuogeren.slimefunhelperplus.module.locator.LocatorTrackModule;
import me.zuogeren.slimefunhelperplus.module.newclickgui.NewClickGui;
import net.fabricmc.loader.api.ModContainer;

/**
 * SlimeFunHelper+ 的宿主插件入口。
 *
 * <p>由宿主插件管理器通过 Fabric entrypoint {@code slimefunhelper} 发现并实例化
 * （必须有无参构造器），随后按 config -> listener -> module -> post 四个阶段回调。
 * 模块登记走工厂：宿主重载模块组时会重跑工厂，直接 register 的实例不会回来。
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
        // 碳 · 物品实例化：纯渲染层，挂 Render 组
        registerInto(context, "Render", CarbonModule::new);
        // 自定义玩家模型：只改本地玩家外观，默认关
        registerInto(context, "Render", CustomModelModule::new);
        // 真身注册进宿主的 Survival 组，翻译键/开关沿用宿主那一套
        registerInto(context, "Survival", ConfluxMapHelper::new);
        // 定位栏坐标反推挂到 Render 组（宿主的名单 NameList 也在这组，行由宿主排版）
        registerInto(context, "Render", LocatorTrackModule::new);
        // 朋友的酒：纯渲染层效果，同样挂 Render 组
        registerInto(context, "Render", FriendWine::new);
        // 新界面：壳子和配置都是插件自己的，但注册成模块才能在列表里看到 / 开关
        registerInto(context, "Tasks", NewClickGui::new);
        // 聚合视图：重载后旧实例已被移除，过滤掉它们，否则这一列会显示一堆点不动的死模块
        context.modules()
                .registerViewGroup(
                        PLUGIN_ID,
                        "SlimeFunHelper+",
                        () -> context.modules().modulesOf(PLUGIN_ID).stream()
                                .filter(module -> !module.isRemoved())
                                .toList());
        Debug.info("[SFH+] onModuleBootstrap: 模块已登记");
    }

    /**
     * 把一个模块登记进宿主已有的组，并留下工厂供宿主重载时重建。
     *
     * <p>宿主 {@code /sfh reload module} 会先清空组内模块、再重跑 {@code registeringFunctions}；
     * 只调 {@code register} 的实例被 {@code onRemove} 之后不会回来，模块会从列表里永久消失。
     * 工厂里重新 new 一个，顺带让 {@code modulesOf} 的归属表跟着更新。
     */
    private static void registerInto(PluginContext context, String groupName, Supplier<BaseModule> factory) {
        ModuleGroup group = context.modules().group(groupName);
        if (group == null) {
            Debug.info("[SFH+] 未找到模块组 " + groupName + "，跳过注册");
            return;
        }
        group.registerFactories(manager -> context.modules().register(PLUGIN_ID, groupName, factory.get()));
    }

    @Override
    public void postInitialize() {
        Debug.info("[SFH+] postInitialize, host plugin API v" + PluginHosts.context().API_VERSION);
    }
}
