package me.matl114.hooks.mixin.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.command.defaults.ElytraCommand;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.matl114.hacks.WorldTasks;
import me.matl114.hacks.modules.survival.BaritoneFix;
import me.matl114.hacks.modules.survival.SeedOre;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.Debug;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Environment(EnvType.CLIENT)
@Mixin(ElytraCommand.class)
public abstract class ElytraCommandMixin extends Command {
    public ElytraCommandMixin(IBaritone iBaritone, String... strings) {
        super(iBaritone, strings);
    }

    @ModifyExpressionValue(
            method = "execute",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;dimension()Lnet/minecraft/resources/ResourceKey;"),
            require = 0)
    private ResourceKey<Level> onExecuteNetherSupport(ResourceKey<Level> original) {
        if (BaritoneFix.INSTANCE.enableDimensionFix.get() && original != Level.NETHER) {
            BaritoneFix.INSTANCE.logI18N("message.module.baritone-fix.ignore-dimension-limit");
            return Level.NETHER;
        }
        return original;
    }

    @Inject(method = "execute", at = @At("HEAD"), require = 0, remap = false)
    private void onAutoImportSeedValue(String par1, IArgConsumer par2, CallbackInfo ci) {
        if (BaritoneFix.INSTANCE.enableSeedAutoImport.get()) {
            if (WorldTasks.hasSeed()) {
                long seed = WorldTasks.getSeed();
                if (seed != BaritoneAPI.getSettings().elytraNetherSeed.value && SeedOre.isSeedValid(seed)) {
                    Debug.chat(
                            Component.literal("[BaritoneFix]").withStyle(ChatFormatting.RED),
                            "Auto import the cached world seed",
                            ChatUtils.getDisplayedLong(seed));
                    BaritoneAPI.getSettings().elytraNetherSeed.value = (Long) seed;
                }
            }
            Debug.chat("[BaritoneFix] Using seed", BaritoneAPI.getSettings().elytraNetherSeed.value);
        }
    }
}
