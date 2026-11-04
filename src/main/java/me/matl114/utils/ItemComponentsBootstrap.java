package me.matl114.utils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import me.matl114.utils.structures.DefaultWorldgenRegistryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Util;
import net.minecraft.world.item.Items;

/**
 * 主菜单（本次启动没进过世界）也要画物品图标，就得先有一份物品数据组件。
 *
 * <p>26.2 的组件由 {@link DataComponentInitializers} 在加载数据包时统一绑定，客户端只在进世界 / 进服
 * 时才走到那一步。这里拿原版数据包的注册表先绑一次 —— {@link DefaultWorldgenRegistryManager} 已经把
 * 原版 server-data 包加载并缓存好了，绑的是内置 vanilla 数据；进世界 / 进服时
 * {@code ReloadableServerResources#updateComponentsAndStaticRegistryTags} 与
 * {@code RegistryDataCollector#updateComponents} 都会无条件重绑成真实数据，不会留下脏状态。
 *
 * <p>解析放后台线程，写回放客户端线程（{@code Holder.Reference#components} 不是 volatile）；
 * 失败只记一行日志且不再重试，图标继续走「未绑定 → 空槽」的兜底。
 */
public final class ItemComponentsBootstrap {
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private ItemComponentsBootstrap() {}

    /** 幂等；未绑定时由 {@link ItemStackUtils#itemComponentsBound()} 触发，只起一次后台线程。 */
    public static void ensure() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Debug.info("item components prebind: triggered");
        ThreadUtils.daemonThreadFactory("sfh-item-components")
                .newThread(ItemComponentsBootstrap::load)
                .start();
    }

    private static void load() {
        long start = Util.getMillis();
        List<DataComponentInitializers.PendingComponents<?>> pending;
        try {
            pending = BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(DefaultWorldgenRegistryManager.registry());
        } catch (Throwable throwable) {
            Debug.getLogger().warn("item components prebind failed after {} ms", Util.getMillis() - start, throwable);
            return;
        }
        long loaded = Util.getMillis() - start;
        Minecraft.getInstance().execute(() -> {
            if (BuiltInRegistries.ITEM.wrapAsHolder(Items.AIR).areComponentsBound()) {
                // 加载期间已经进过服 / 进过世界：真实数据已绑好，不能让内置数据把它盖回去
                return;
            }
            long applyStart = Util.getMillis();
            try {
                pending.forEach(DataComponentInitializers.PendingComponents::apply);
            } catch (Throwable throwable) {
                Debug.getLogger().warn("item components prebind apply failed", throwable);
                return;
            }
            Debug.info("item components prebind: load " + loaded + " ms, apply " + (Util.getMillis() - applyStart)
                    + " ms, total " + (Util.getMillis() - start) + " ms");
        });
    }
}
