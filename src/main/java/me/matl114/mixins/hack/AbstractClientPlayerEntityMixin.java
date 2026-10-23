package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.authlib.GameProfile;
import me.matl114.hacks.modules.render.NoRender;
import me.matl114.utils.AttributeUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Environment(EnvType.CLIENT)
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerEntityMixin extends Player {
    public AbstractClientPlayerEntityMixin(Level world, GameProfile profile) {
        super(world, profile);
    }

    @ModifyExpressionValue(
            method = "getFieldOfViewModifier",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;flying:Z"))
    private boolean onNoFlyFov(boolean original) {
        if (NoRender.INSTANCE.noFlyFov()) {
            return false;
        }
        return original;
    }

    @ModifyExpressionValue(
            method = "getFieldOfViewModifier",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/player/AbstractClientPlayer;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
    private double onNoSpeedFov(double original) {
        if (NoRender.INSTANCE.noSprintFov()) {
            AttributeMap attributeContainer = AttributeUtils.copyAttributes(this);
            AttributeInstance entityAttributeInstance =
                    attributeContainer.getInstance(Attributes.MOVEMENT_SPEED);
            if (entityAttributeInstance != null) {
                entityAttributeInstance.removeModifier(Identifier.withDefaultNamespace("sprinting"));
            }
            original = attributeContainer.getValue(Attributes.MOVEMENT_SPEED);
        }
        if (NoRender.INSTANCE.noSlowDownFov()) {
            original = Math.max(original, 0.1F);
        }
        if (NoRender.INSTANCE.noSpeedFov()) {
            // 0.135 介于步行(0.1)与疾跑(0.13)之间：只压速度药水带来的额外拉伸，疾跑本体交给 noSprintFov
            original = Math.min(original, 0.135F);
        }
        return original;
    }

    @ModifyExpressionValue(
            method = "getFieldOfViewModifier",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;isUsingItem()Z"))
    private boolean onNoUseItemFov(boolean original) {
        if (NoRender.INSTANCE.noUseItemFov()) {
            return false;
        }
        return original;
    }
}
