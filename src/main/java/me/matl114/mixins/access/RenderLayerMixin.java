package me.matl114.mixins.access;

import java.util.Optional;
import me.matl114.accessors.access.RenderLayerAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Environment(EnvType.CLIENT)
@Mixin(RenderType.class)
public abstract class RenderLayerMixin implements RenderLayerAccess {
    @Shadow
    @Final
    private RenderSetup state;

    @Unique private Optional<Identifier> resolvedRenderTexture;

    @Unique private boolean resolvedRenderTextureDone;

    /**
     * 取该 RenderType 绑定的第一张纹理。
     *
     * <p>刻意不在构造器里注入：26.2 的 {@code RenderType} 构造器是私有的两参构造，
     * 注入 {@code <init>} 属于「编译期查不出、运行时才炸」的一类风险（本项目 shadow-check 会直接报
     * 构造器 arity 不匹配）。这里改成首次调用时惰性读取 {@code state}，行为等价且没有注入点。
     */
    @Override
    public Optional<Identifier> getRenderTexture() {
        if (!resolvedRenderTextureDone) {
            resolvedRenderTextureDone = true;
            resolvedRenderTexture = this.state == null
                    ? Optional.empty()
                    : this.state.textures.values().stream()
                            .findFirst()
                            .map(RenderSetup.TextureBinding::location);
        }
        return resolvedRenderTexture;
    }
}
