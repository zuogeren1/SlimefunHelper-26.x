package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.Iterator;
import java.util.List;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.SubtitleOverlay;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.*;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(SoundEngine.class)
public abstract class SoundSystemEvents {
    @Shadow
    @Final
    private List<SoundEventListener> listeners;

    @Shadow
    @Final
    private SoundManager soundManager;

    @WrapOperation(
            //#if MC >= 260300
            //$$ method = "notifyListeners(Lnet/minecraft/client/resources/sounds/SoundInstance;Lnet/minecraft/client/sounds/WeighedSoundEvents;F)V",
            //#else
            method =
                    "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
            //#endif
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/sounds/SoundEventListener;onPlaySound(Lnet/minecraft/client/resources/sounds/SoundInstance;Lnet/minecraft/client/sounds/WeighedSoundEvents;F)V"))
    private void play(
            SoundEventListener instance,
            SoundInstance soundInstance,
            WeighedSoundEvents weightedSoundSet,
            float v,
            Operation<Void> original) {
        if (instance instanceof SubtitleOverlay hud) {
            Event<SoundInstance> event = new Event<>(soundInstance, true, false);
            Listener.getSoundAddToHudEvent().handleValue(event);
            if (event.isCancelled()) {
                return;
            } else {
                original.call(instance, event.context, weightedSoundSet, v);
            }
        } else {
            original.call(instance, soundInstance, weightedSoundSet, v);
        }
    }

    @Inject(
            method =
                    "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
            at = @At("HEAD"),
            cancellable = true)
    private void onInterceptPlay(
            SoundInstance sound,
            CallbackInfoReturnable<SoundEngine.PlayResult> cir,
            @Local(argsOnly = true) LocalRef<SoundInstance> args) {
        Event<SoundInstance> event = new Event<>(sound, true, true);
        Listener.getSoundPlayEvent().handleValue(event);
        if (event.isCancelled()) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
            onSoundPlayed(event.context);
            return;
        }
        if (sound != event.context) {
            args.set(event.context);
        }
    }

    @Unique
    private void onSoundPlayed(SoundInstance sound) {
        // need getSoundSet to initialize getSound , wtf mojang pieces of shit
        //#if MC >= 260300
        //$$ WeighedSoundEvents weightedSoundSet = sound.getOrResolve(this.soundManager);
        //#else
        WeighedSoundEvents weightedSoundSet = sound.resolve(this.soundManager);
        //#endif
        if (weightedSoundSet == null) {
            return;
        }
        Sound sound2 = sound.getSound();
        if (sound2 == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
            return;
        } else if (sound2 == SoundManager.EMPTY_SOUND) {
            return;
        }
        if (!this.listeners.isEmpty()) {

            boolean bl = sound.isRelative();
            SoundInstance.Attenuation attenuationType = sound.getAttenuation();
            float f = sound.getVolume();
            float g = Math.max(f, 1.0F) * (float) sound2.getAttenuationDistance();
            float j = !bl && attenuationType != SoundInstance.Attenuation.NONE ? g : Float.POSITIVE_INFINITY;
            Iterator var13 = this.listeners.iterator();

            while (var13.hasNext()) {
                SoundEventListener soundInstanceListener = (SoundEventListener) var13.next();
                if (soundInstanceListener instanceof SubtitleOverlay) {
                    Event<SoundInstance> event = new Event<>(sound, true, true);
                    Listener.getSoundAddToHudEvent().handleValue(event);
                    if (event.isCancelled()) {
                        continue;
                    }
                }
                soundInstanceListener.onPlaySound(sound, weightedSoundSet, j);
            }
        }
    }
}
