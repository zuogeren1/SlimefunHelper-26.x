package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.ArrayDeque;
import java.util.Optional;
import me.matl114.accessors.access.PlayerInteractBlockC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.SlotClickAction;
import me.matl114.events.impl.UseItem;
import me.matl114.events.impl.UseItemOnBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(MultiPlayerGameMode.class)
public abstract class ClientPlayerInteractionManagerEvents {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "handlePlaceRecipe", at = @At("HEAD"))
    public void onClickRecipe(int syncId, RecipeDisplayId recipeId, boolean craftAll, CallbackInfo ci) {
        Listener.getClickCraftingRecipe().broadcast(recipeId);
    }

    @Inject(
            method = "useItem",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;ensureHasSentCarriedItem()V",
                            shift = At.Shift.BEFORE),
            cancellable = true)
    private void onCancelSend(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        Event<UseItem> handEvent = new Event<>(new UseItem(InteractionResult.PASS, hand, player.getItemInHand(hand)), true, true);
        Listener.getPrePlayerUseItem().handleValue(handEvent);
        if (handEvent.isCancelled()) {
            cir.setReturnValue(handEvent.context.actionResult());
        }
    }

    @WrapOperation(
            method = "useItem",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V"))
    private void onInteractItemCapture(
            MultiPlayerGameMode instance,
            ClientLevel world,
            PredictiveAction packetCreator,
            Operation<Void> original,
            @Local(argsOnly = true) InteractionHand hand,
            @Local MutableObject<InteractionResult> mutableObject) {
        ItemStack stackCopy = minecraft.player.getItemInHand(hand).copy();
        original.call(instance, world, (PredictiveAction) (seq) -> {
            var packet = packetCreator.predict(seq);
            InteractionResult acc = mutableObject.getValue();
            Event<UseItem> eventResult = new Event<>(new UseItem(acc, hand, stackCopy), false, true);
            Listener.getPostPlayerUseItem().handleValue(eventResult);
            mutableObject.setValue(eventResult.context.actionResult());
            return packet;
        });
    }

    @Inject(method = "useItemOn", at = @At(value = "HEAD"), cancellable = true)
    public void onPreInteractBlock(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir,
            @Local(argsOnly = true) LocalRef<BlockHitResult> hand2) {
        Event<UseItemOnBlock> blockHitResultEvent =
                new Event<>(new UseItemOnBlock(hitResult, InteractionResult.SUCCESS, Optional.empty(), hand, player.getItemInHand(hand)), true, true);
        Listener.getPrePlayerUseItemAtBlock().handleValue(blockHitResultEvent);
        if (blockHitResultEvent.isCancelled()) {
            cir.setReturnValue(blockHitResultEvent.context.actionResult());
        } else {
            BlockHitResult hitResult2 = blockHitResultEvent.context.hitResult();
            if (hitResult2 != hitResult) {
                hand2.set(hitResult2);
            }
        }
    }

    @Final
    @Unique
    private final ArrayDeque<MutableBoolean> lastInteractCaptureBlockPlace = new ArrayDeque<>(4);

    @WrapOperation(
            method = "useItemOn",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V"))
    private void onInteractBlockAction(
            MultiPlayerGameMode instance,
            ClientLevel world,
            PredictiveAction packetCreator,
            Operation<Void> original,
            @Local(argsOnly = true) InteractionHand hand,
            @Local(argsOnly = true) BlockHitResult hitResult,
            @Local MutableObject<InteractionResult> actionResult) {
        ItemStack stackCopy = minecraft.player.getItemInHand(hand).copy();
        BlockState state = minecraft.level.getBlockState(hitResult.getBlockPos());
        MutableBoolean placeBlock = new MutableBoolean(false);
        BlockPos predictingPlace = new BlockPlaceContext(minecraft.player, hand, stackCopy, hitResult).getClickedPos();
        original.call(instance, world, (PredictiveAction) (seq) -> {
            lastInteractCaptureBlockPlace.addLast(placeBlock);
            try {
                var packet = packetCreator.predict(seq);
                if (packet instanceof PlayerInteractBlockC2SPacketAccess access) {
                    access.setUseContext(new PlayerInteractBlockC2SPacketAccess.UseContext(
                            stackCopy,
                            state,
                            actionResult.getValue(),
                            placeBlock.booleanValue() ? Optional.of(predictingPlace) : Optional.empty()));
                }
                return packet;
            } finally {
                lastInteractCaptureBlockPlace.removeLast();
            }
        });
        InteractionResult acc = actionResult.getValue();
        Event<UseItemOnBlock> eventResult =
                new Event<>(
                        new UseItemOnBlock(
                                hitResult,
                                acc,
                                placeBlock.getValue() ? Optional.of(predictingPlace) : Optional.empty(),
                                hand,
                                stackCopy),
                        false,
                        true);
        Listener.getPostPlayerUseItemOnBlock().handleValue(eventResult);
        actionResult.setValue(eventResult.context.actionResult());
    }

    @Inject(
            method = "performUseItemOn",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private void onInteractBlockInternalCaptureBlockPlace(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir) {
        var re = lastInteractCaptureBlockPlace.peekLast();
        if (re != null) {
            re.setValue(true);
        }
    }

    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    public void onClickSlot(
            int syncId, int slotId, int button, ContainerInput actionType, Player player, CallbackInfo ci) {
        Event<SlotClickAction> eventClickSlot =
                new Event<>(new SlotClickAction(actionType, syncId, slotId, button), true, false);
        Listener.getPreClickSlot().handleValue(eventClickSlot);
        if (eventClickSlot.isCancelled()) {
            ci.cancel();
            return;
        }
    }

    @Inject(method = "handleContainerInput", at = @At("RETURN"))
    public void onClickSlotPost(
            int syncId, int slotId, int button, ContainerInput actionType, Player player, CallbackInfo ci) {
        Listener.getPostClickSlot().broadcast(new SlotClickAction(actionType, syncId, slotId, button));
    }

    @Inject(
            method =
                    "createPlayer(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/stats/StatsCounter;Lnet/minecraft/client/ClientRecipeBook;Lnet/minecraft/world/entity/player/Input;Z)Lnet/minecraft/client/player/LocalPlayer;",
            at = @At("RETURN"))
    public void onCreatePlayer(
            ClientLevel world,
            StatsCounter statHandler,
            ClientRecipeBook recipeBook,
            Input lastPlayerInput,
            boolean lastSprinting,
            CallbackInfoReturnable<LocalPlayer> cir) {
        LocalPlayer player = cir.getReturnValue();
        Listener.getPlayerInitConfiguration().broadcast(player);
    }
}
