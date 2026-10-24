package me.zuogeren.slimefunhelperplus;

import me.matl114.utils.Debug;
import net.fabricmc.api.ClientModInitializer;

/**
 * SlimeFunHelper+ 的 Fabric main 入口。
 *
 * <p>只保证 {@code main} entrypoint 存在；真正的插件逻辑在 {@link PlusPlugin}，
 * 由宿主的 {@code slimefunhelper} entrypoint 驱动。
 */
public class PlusMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Debug.info("[SFH+] SlimeFunHelper+ client bootstrap");
    }
}
