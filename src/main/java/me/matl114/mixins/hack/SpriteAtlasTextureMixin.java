package me.matl114.mixins.hack;

import me.matl114.hacks.modules.render.NoRender;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class SpriteAtlasTextureMixin {
    @Shadow
    public abstract Identifier location();

    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"), cancellable = true)
    private void noBlockAnimation(CallbackInfo ci) {
        if (NoRender.INSTANCE.noBlockAnimation() && TextureAtlas.LOCATION_BLOCKS.equals(this.location())) {
            ci.cancel();
        }
    }
}
