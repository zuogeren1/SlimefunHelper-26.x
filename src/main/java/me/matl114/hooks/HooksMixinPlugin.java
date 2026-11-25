package me.matl114.hooks;

import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

/**
 * hooks 配置的条件门。
 *
 * <p>Sodium 是可选依赖：未安装时 {@code hooks/mixin/sodium} 下的 mixin 不应被应用，
 * 因为这些类直接引用 {@code net.caffeinemc.*} 类型。其余包（baritone / meteor / xaero / viaversion）
 * 一律放行，保持原有行为。
 */
public class HooksMixinPlugin implements IMixinConfigPlugin {
    private static final String SODIUM_MIXIN_PACKAGE = "me.matl114.hooks.mixin.sodium.";

    private boolean sodiumLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        sodiumLoaded = FabricLoader.getInstance().isModLoaded("sodium");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(SODIUM_MIXIN_PACKAGE)) {
            return sodiumLoaded;
        }

        return true;
    }
}
