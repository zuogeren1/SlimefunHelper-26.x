package me.matl114;

import com.google.common.base.Preconditions;
import java.util.Set;
import lombok.Getter;
import me.matl114.plugins.internal.MainPlugin;
import me.matl114.plugins.internal.PluginManager;
import me.matl114.plugins.internal.PluginManager.BootstrapPhase;
import me.matl114.utils.Debug;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.FabricLoaderImpl;

public class SlimefunHelper implements ModInitializer {
    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final String MOD_ID = "slimefunhelper";
    // public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    @Getter
    public static SlimefunHelper instance;

    @Getter
    public static ModContainer modContainer;

    public static boolean DEV_ENV = false;

    public static Set<String> DEV_NAME =
            Set.of("matl114", "matl_test", "matl_test2", "mtl", "||matl_test", "||matl_test2", "||mtl");

    public static void authentication() {
        try {
            Class.forName("net.minecraft.client.Minecraft");
            DEV_ENV = true;
            Debug.info("Dev Environment Detected !");
        } catch (Throwable e) {
        }
    }

    public static void initializeEnvironment() {
        for (var re : FabricLoaderImpl.INSTANCE.getEntrypointContainers("main", ModInitializer.class)) {
            if (re.getEntrypoint() == instance) {
                modContainer = re.getProvider();
                break;
            }
        }
        Preconditions.checkNotNull(modContainer);
    }

    @Override
    public void onInitialize() {
        instance = this;
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.
        authentication();
        initializeEnvironment();
        Debug.info("SlimefunHelper, start!");
        Debug.info("SlimefunHelper start loading!");
        PluginManager pluginManager = PluginManager.getInstance();
        pluginManager.begin(new MainPlugin(getModContainer()));
        pluginManager.discover();
        pluginManager.advance(BootstrapPhase.CONFIG_READ);
        pluginManager.advance(BootstrapPhase.LISTENERS);
        pluginManager.advance(BootstrapPhase.MODULES);
        pluginManager.advance(BootstrapPhase.OTHER);
        pluginManager.complete();
        Debug.info("SlimefunHelper loading finish");
    }

    // 大饼: 实现指令系统，接入聊天框 !!开头
    // 大饼: 客户端实现/give指令劫持
    // 大饼: 发射器界面实现一键放入+合成(?)+交互合成按钮  有了
    // 大饼: 通过客户端指令listRegisty
    // 大饼 Stats modify
    // todo 大饼 下单系统; 需要实现vanilla walk 模块，拉取baritone api
    // 大病: 新配置体系 有了
    // villager trade utils 有了
    // todo: generalize sf id to some nbt path -> id
    // todo: add JsonMapRef , store data as json string

    // todo: 重构旧的配置路径

}
