package me.matl114.mixins.events;

import me.matl114.utils.ClientUtils;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.platform.Window;
import java.io.File;
import java.util.Objects;
import me.matl114.events.Event;
import me.matl114.events.GlobalEventVars;
import me.matl114.events.Listener;
import me.matl114.utils.collections.Point;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.CrashReport;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(Minecraft.class)
@Environment(EnvType.CLIENT)
public abstract class MinecraftClientEvents {
    @Shadow
    private int rightClickDelay;

    @Shadow
    public HitResult hitResult;

    @Shadow
    public abstract Window getWindow();

    @Shadow
    public abstract void tick();

    //#if MC >= 260300
    //$$ @Shadow
    //$$ public abstract DeltaTracker getDeltaTracker();
    //#endif

    @Shadow
    @Nullable
    public LocalPlayer player;

    @Shadow
    protected abstract void continueAttack(boolean breaking);

    @Shadow
    public int missTime;

    @Shadow
    protected abstract void handleKeybinds();

    @Inject(
            method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;close()V"))
    public void onServerDisconnect(
            Screen disconnectionScreen, boolean transferring, boolean stopSounds, CallbackInfo ci) {
        // origin exit
        // ensure that player is exiting from PLAY stage , we inject before the unloadWorld
        Listener.getServerLeavePoint().handleValue(new Event<>(null, false, false, true));
        Listener.getServerDisconnectPoint().handleValue(new Event<>(null, false, false, transferring));
    }

    @Inject(method = "clearClientLevel", at = @At("HEAD"))
    public void onServerReconfiguration(Screen reconfigurationScreen, CallbackInfo ci) {
        Listener.getServerLeavePoint().handleValue(new Event<>(null, false, false, false));
    }

    // 26.2: GameRenderer.render(DeltaTracker, boolean) 的调用点从 runTick 搬到了 renderFrame
    //#if MC >= 260300
    //$$ // 26.3 GameRenderer.render() 无参：DeltaTracker 改从 Minecraft#getDeltaTracker() 取，调用点
    //$$ // 已没有 advanceGameTime，Event 的 extraArgs 只保留 DeltaTracker（消费者只读 getArgs(0)）
    //$$ @WrapWithCondition(
    //$$         method = "renderFrame",
    //$$         at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
    //$$ private boolean onGameRenderer(GameRenderer instance) {
    //$$     Event<GameRenderer> rendererEvent = new Event<>(instance, true, false, getDeltaTracker());
    //$$     Listener.getGameRender().handleValue(rendererEvent);
    //$$     return !rendererEvent.isCancelled();
    //$$ }
    //#else
    @WrapWithCondition(
            method = "renderFrame",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private boolean onGameRenderer(GameRenderer instance, DeltaTracker tickCounter, boolean tick) {
        Event<GameRenderer> rendererEvent = new Event<>(instance, true, false, tickCounter, tick);
        Listener.getGameRender().handleValue(rendererEvent);
        return !rendererEvent.isCancelled();
    }
    //#endif

    @WrapOperation(
            method = "emergencySaveAndCrash(Lnet/minecraft/CrashReport;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;emergencySave()V"))
    private void onSystemPreExitClearGameContent(
            Minecraft client, Operation<Void> original, @Local(argsOnly = true) CrashReport report) {
        if (client == null) {
            return;
        }
        GlobalEventVars.crashReport = report;

        GlobalEventVars.crashReportEvent = new Event<>(client, client.isRunning(), false, report);
        // call the event previously
        if (!Listener.getClientMainExit().isEmpty()) {
            Event<Minecraft> exitEvent = GlobalEventVars.crashReportEvent;
            Listener.getClientMainExit().handleValue(exitEvent);
        }
        if (!GlobalEventVars.crashReportEvent.isCancelled()) {
            original.call(client);
        }
    }

    //#if MC >= 26.2
    @Inject(
            method = "crash(Lnet/minecraft/client/Minecraft;Ljava/io/File;Lnet/minecraft/CrashReport;I)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/Minecraft;saveReportAndShutdownSoundManager(Lnet/minecraft/client/Minecraft;Ljava/io/File;Lnet/minecraft/CrashReport;I)I",
                            shift = At.Shift.AFTER),
            cancellable = true)
    private static void onSystemExit(
            Minecraft client, File runDirectory, CrashReport crashReport, int exitCode, CallbackInfo ci) {
    //#else
    //$$ @Inject(
    //$$         method = "crash(Lnet/minecraft/client/Minecraft;Ljava/io/File;Lnet/minecraft/CrashReport;)V",
    //$$         at =
    //$$                 @At(
    //$$                         value = "INVOKE",
    //$$                         target =
    //$$                                 "Lnet/minecraft/client/Minecraft;saveReportAndShutdownSoundManager(Lnet/minecraft/client/Minecraft;Ljava/io/File;Lnet/minecraft/CrashReport;)I",
    //$$                         shift = At.Shift.AFTER),
    //$$         cancellable = true)
    //$$ private static void onSystemExit(
    //$$         Minecraft client, File runDirectory, CrashReport crashReport, CallbackInfo ci) {
    //#endif
        if (client == null) {
            return;
        }
        GlobalEventVars.crashReport = crashReport;
        if (GlobalEventVars.crashReportEvent == null) {
            GlobalEventVars.crashReportEvent = new Event<>(client, client.isRunning(), false, crashReport);
            if (!Listener.getClientMainExit().isEmpty()) {
                Event<Minecraft> exitEvent = GlobalEventVars.crashReportEvent;
                Listener.getClientMainExit().handleValue(exitEvent);
            }
        }
        if (GlobalEventVars.crashReportEvent.isCancelled()) {
            ci.cancel();
        } else {
            GlobalEventVars.crashReportEvent = null;
            GlobalEventVars.crashReport = null;
            // exit
        }
    }

    //     //deprecated ItemUseEvent
    //        @Inject(method = "doItemUse", at = @At(value = "INVOKE", target =
    //
    // "Lnet/minecraft/client/network/ClientPlayerEntity;getStackInHand(Lnet/minecraft/util/Hand;)Lnet/minecraft/item/ItemStack;", shift = At.Shift.BEFORE), cancellable = true)
    //        private void doItemUseEvent(CallbackInfo ci, @Local Hand hand){
    //            if(!Listener.getTriggerRightClick().isEmpty()){
    //                Event<Hand> useWithHandEvent = new Event<>(hand, true, false);
    //                Listener.getTriggerRightClick().handleValue(useWithHandEvent);
    //                if(useWithHandEvent.isCancelled()){
    //                    ci.cancel();
    //                }
    //            }
    //        }
    @Inject(method = "startUseItem", at = @At("HEAD"))
    private void onItemUseTargetStore(CallbackInfo ci) {

        cacheHitResult = hitResult;
    }

    @Inject(
            method = "startUseItem",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/player/LocalPlayer;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"),
            cancellable = true)
    private void onItemUseEvent(CallbackInfo ci, @Local InteractionHand currentHand) {
        hitResult = cacheHitResult;
        Event<HitResult> hitResultEvent = new Event<>(hitResult, true, true, currentHand);
        Listener.getItemUseAction().handleValue(hitResultEvent);
        if (hitResultEvent.isCancelled() || hitResultEvent.context == null) {
            hitResult = cacheHitResult;
            ci.cancel();
            return;
        }
        hitResult = hitResultEvent.context;
    }

    @Inject(method = "startUseItem", at = @At("RETURN"))
    private void onItemUseTargetRestore(CallbackInfo ci) {
        hitResult = cacheHitResult;
    }

    @Inject(
            method = "startUseItem",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/LocalPlayer;isHandsBusy()Z",
                            shift = At.Shift.BEFORE))
    private void onItemCooldown(CallbackInfo ci) {
        // inject the cooldown, before the riding call
        Event<Integer> event = new Event<>(null, true, true);
        Listener.getUseItemCooldownReset().handleValue(event);
        if (event.isCancelled()) {
            this.rightClickDelay = 0;
        } else if (event.context() != null) {
            this.rightClickDelay = event.context();
        }
    }

    @Unique
    private HitResult cacheHitResult = null;

    @Inject(
            method = "continueAttack",
            at =
                    @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/Minecraft;hitResult:Lnet/minecraft/world/phys/HitResult;",
                            ordinal = 0,
                            shift = At.Shift.BEFORE))
    private void onMineBlock(boolean breaking, CallbackInfo ci) {
        if (breaking) {
            Event<HitResult> hitResultEvent = new Event<>(this.hitResult, true, true, false);
            Listener.getMineBlockAction().handleValue(hitResultEvent);
            if (hitResultEvent.isCancelled() || hitResultEvent.context != hitResult) {
                cacheHitResult = hitResult;
                hitResult = hitResultEvent.isCancelled() ? null : hitResultEvent.context;
            }
        }
    }

    @Inject(method = "continueAttack", at = @At("RETURN"))
    private void onRestoreHitResultAfterBreak(CallbackInfo ci) {
        if (cacheHitResult != null) {
            this.hitResult = cacheHitResult;
        }
        cacheHitResult = null;
    }

    @Inject(
            method = "startAttack",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/phys/BlockHitResult;getBlockPos()Lnet/minecraft/core/BlockPos;",
                            shift = At.Shift.BEFORE),
            cancellable = true)
    private void onAttackBlock(CallbackInfoReturnable<Boolean> cir, @Local LocalRef<BlockHitResult> hitResultLocalRef) {
        var re = hitResultLocalRef.get();
        Event<HitResult> hitResultEvent = new Event<>(re, true, true, true);
        Listener.getMineBlockAction().handleValue(hitResultEvent);
        if (hitResultEvent.isCancelled() || !(hitResultEvent.context instanceof BlockHitResult)) {
            cir.setReturnValue(false);
        }
        if (hitResultEvent.context != re) {
            hitResultLocalRef.set((BlockHitResult) hitResultEvent.context);
        }
    }

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;handleKeybinds()V"))
    private void onInputEvent(Minecraft instance, Operation<Void> original) {
        Event<Void> re = new Event<>(null, true, false);
        Listener.getPreHandleInputEvents().handleValue(re);
        if (!re.isCancelled()) {
            original.call(instance);
        }
        Listener.getPostHandleInputEvents().handleValue(new Event<>(null, false, false));
    }

    @WrapOperation(
            method = "handleKeybinds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V"))
    private void onSetSelectedSlotNumberButton(Inventory instance, int slot, Operation<Void> original) {
        Event<Integer> hotBar = new Event<>(slot, true, true);
        Listener.getPlayerScrollHotBar().handleValue(hotBar);
        if (hotBar.isCancelled()) return;
        original.call(instance, (int) hotBar.context());
    }

    @Inject(
            method = "tick",
            at =
                    //#if MC >= 26.2
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;",
                            shift = At.Shift.BEFORE))
    //#else
    //$$                 @At(
    //$$                         value = "FIELD",
    //$$                         target =
    //$$                                 "Lnet/minecraft/client/Minecraft;overlay:Lnet/minecraft/client/gui/screens/Overlay;",
    //$$                         shift = At.Shift.BEFORE,
    //$$                         ordinal = 2))
    //#endif
    public void onInputEventIfScreenOpen(CallbackInfo ci, @Local ProfilerFiller profiler) {
        if (ClientUtils.getScreen() != null
                || ClientUtils.getOverlay() != null) {
            profiler.popPush("Keybindings");
            if (Minecraft.getInstance().player != null) {
                Event<Void> re = new Event<>(null, true, false);
                re.cancel();
                Listener.getPreHandleInputEvents().handleValue(re);
                if (!re.isCancelled()) {
                    handleKeybinds();
                } else {
                    this.continueAttack(false);
                    if (this.missTime > 0) {
                        --this.missTime;
                    }
                }
                Listener.getPostHandleInputEvents().handleValue(new Event<>(null, false, false));
            }
        }
    }

    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/tutorial/Tutorial;onLookAt(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/world/phys/HitResult;)V",
                            shift = At.Shift.AFTER))
    public void onPreTick(CallbackInfo ci) {
        Listener.getPreTick().broadcast(null);
        if (player != null) {
            Listener.getPreGameTick().broadcast(player);
        }
    }

    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/Minecraft;getConnection()Lnet/minecraft/client/multiplayer/ClientPacketListener;",
                            shift = At.Shift.BEFORE))
    private void onPostGameTick(CallbackInfo ci, @Local ProfilerFiller profiler) {
        if (player != null) {
            profiler.popPush("post-game-tick");
            Listener.getPostGameTick().broadcast(player);
        }
    }

    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V",
                            shift = At.Shift.BEFORE,
                            ordinal = 1),
            locals = LocalCapture.CAPTURE_FAILSOFT)
    public void onPostTick(CallbackInfo ci, @Local ProfilerFiller profiler) {
        profiler.popPush("post-tick");
        Listener.getPostTick().broadcast(null);
    }

    // move before the block interaction, so that it will not reset cooldown when interact block or swing hand
    @Inject(
            method = "startAttack",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/phys/HitResult;getType()Lnet/minecraft/world/phys/HitResult$Type;",
                            shift = At.Shift.BEFORE),
            locals = LocalCapture.CAPTURE_FAILHARD,
            cancellable = true)
    public void onAttackAction(CallbackInfoReturnable<Boolean> cir) {
        if (hitResult != null) {
            Event<HitResult> resultEvent = new Event<>(hitResult, true, true);
            Listener.getAttackAction().handleValue(resultEvent);
            if (resultEvent.isCancelled()) {
                cir.setReturnValue(false);
            } else {
                if (!Objects.equals(resultEvent.context(), hitResult)) {
                    cacheHitResult = hitResult;
                    hitResult = resultEvent.context();
                } else {
                    cacheHitResult = null;
                }
            }
        }
    }

    @Inject(
            method = "startAttack",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    //#if MC >= 260300
                                    //$$ "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z",
                                    //#else
                                    "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;)V",
                                    //#endif
                            shift = At.Shift.BEFORE))
    private void restoreAttackTarget(CallbackInfoReturnable<Boolean> cir) {
        if (cacheHitResult != null) {
            hitResult = cacheHitResult;
            cacheHitResult = null;
        }
    }

    // 1.21.11 是在 resize 之前（getFramebuffer() 调用前）触发；26.2 无该锚点，
    // 改用 HEAD —— resizeGui 由窗口事件回调调用，此时 window 已经是新尺寸，
    // 载荷与旧版一致，且时序更接近"resize 前"。
    @Inject(method = "resizeGui", at = @At("HEAD"))
    public void onResolutionChanged(CallbackInfo ci) {
        Listener.getResolutionChange()
                .handleValue(new Event<>(
                        new Point(
                                Minecraft.getInstance().getWindow().getGuiScaledWidth(),
                                Minecraft.getInstance().getWindow().getGuiScaledHeight()),
                        false,
                        false));
    }
}
