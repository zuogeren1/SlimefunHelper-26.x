package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import java.util.Objects;
import me.matl114.accessors.access.PlayerMoveC2SPacketAccess;
import me.matl114.accessors.events.ClientPlayerEntityAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Tasks;
import me.matl114.utils.entity.PlayerInputUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.chat.ChatAbilities;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
//#if MC >= 260300
//$$ import net.minecraft.client.player.ItemActivation;
//#endif
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityEvents extends AbstractClientPlayer implements ClientPlayerEntityAccess {
    @Shadow
    private Input lastSentInput;

    @Unique
    private boolean resyncLastInput = false;

    @Shadow
    private double xLast;

    @Shadow
    private double zLast;

    @Shadow
    private double yLast;

    @Shadow
    private float xRotLast;

    @Shadow
    private float yRotLast;

    @Shadow
    public ClientInput input;

    @Shadow
    @Final
    public ClientPacketListener connection;

    @Shadow
    private boolean wasSprinting;

    @Shadow
    private boolean lastOnGround;

    @Shadow
    public abstract void resetPos();

    @Shadow
    private int positionReminder;

    public ClientPlayerEntityEvents(ClientLevel world, GameProfile profile) {
        super(world, profile);
    }

    @Unique
    public LegalMovementManager movementManager;

    @Unique
    public LegalMovementManager getLegalMovementManager() {
        return this.movementManager;
    }

    @Override
    @Unique
    public void setLastSprintFlag(boolean lastSprint) {
        this.wasSprinting = lastSprint;
    }

    public void setLastSneakFlag(boolean lastSprint) {
        this.lastSentInput = new Input(
                this.lastSentInput.forward(),
                this.lastSentInput.backward(),
                this.lastSentInput.left(),
                this.lastSentInput.right(),
                this.lastSentInput.jump(),
                lastSprint,
                this.lastSentInput.sprint());
    }

    @Unique
    @Override
    public void setLastOnGroundFlag(boolean lastOnGround) {
        this.lastOnGround = lastOnGround;
    }

    public void setLastPos(Vec3 vec3d) {
        this.xLast = vec3d.x;
        this.zLast = vec3d.z;
        this.yLast = vec3d.y;
    }

    public void setLastRot(float pitch, float yaw) {
        this.xRotLast = pitch;
        this.yRotLast = yaw;
    }

    public void resyncInput() {
        this.resyncLastInput = true;
    }

    @Override
    @Unique
    public void setResyncMovementPacketTicks(int ticks) {
        this.positionReminder = ticks;
    }

    //#if MC >= 260300
    //$$ @Inject(method = "<init>", at = @At("RETURN"))
    //$$ private void onClientPlayerInitConfiguration(
    //$$         Minecraft client,
    //$$         ClientLevel world,
    //$$         ClientPacketListener networkHandler,
    //$$         StatsCounter stats,
    //$$         ClientRecipeBook recipeBook,
    //$$         Input lastPlayerInput,
    //$$         boolean lastSprinting,
    //$$         ChatAbilities abilities,
    //$$         ItemActivation itemActivation,
    //$$         CallbackInfo ci) {
    //#else
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onClientPlayerInitConfiguration(
            Minecraft client,
            ClientLevel world,
            ClientPacketListener networkHandler,
            StatsCounter stats,
            ClientRecipeBook recipeBook,
            Input lastPlayerInput,
            boolean lastSprinting,
            ChatAbilities abilities,
            CallbackInfo ci) {
    //#endif
        this.movementManager = new LegalMovementManager();
    }

    @Unique
    private static Vec2 compatMovementVectorWithViaFabric(Vec2 vec2f) {
        // shit,
        return ViaFabricPlusHooks.getInstance().getCurrentVersion().isLowerOrEqualTo(21, 4)
                ? vec2f
                : vec2f.normalized();
    }

    @Inject(
            method = "aiStep",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/ClientInput;tick()V",
                            shift = At.Shift.AFTER))
    public void onPostInputTick(CallbackInfo ci) {
        if (!checkClientPlayer()) return;
        Input currentInput = this.input.keyPresses;
        if (!Listener.getPlayerKeyboardInputTick().isEmpty()) {
            Listener.getPlayerKeyboardInputTick().handleValue(new Event<>(this.input, false, false));
        }
        getLegalMovementManager().postInputTick((LocalPlayer) (AbstractClientPlayer) this);
        // changed, update movementVector
        if (!Objects.equals(currentInput, this.input.keyPresses)) {
            PlayerInputUtils.Input i0 = new PlayerInputUtils.Input(this.input.keyPresses);
            this.input.moveVector = compatMovementVectorWithViaFabric(new Vec2(i0.sidewaysSpeed(), i0.forwardSpeed()));
        }
    }

    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V",
                            shift = At.Shift.BEFORE))
    public void prePlayerTick(CallbackInfo ci) {
        if (!checkClientPlayer()) return;
        this.movementManager.preProgress((LocalPlayer) (AbstractClientPlayer) this);
    }

    @Unique
    int lastCancelTick = 0;

    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V",
                            shift = At.Shift.AFTER),
            order = 100)
    public void onAfterTick(CallbackInfo ci) {
        if (!checkClientPlayer()) return;
        Event<LocalPlayer> event = new Event<>((LocalPlayer) (AbstractClientPlayer) this, true);
        Listener.getClientPlayerSendMovementPoint().handleValue(event);
        if (isPassenger()) {
            if (!this.movementManager.preInputProgress((LocalPlayer) (AbstractClientPlayer) this)
                    || event.isCancelled()) {
                lastCancelTick = Tasks.getTick();
            }
        } else {
            if (!this.movementManager.preMovementProgress((LocalPlayer) (AbstractClientPlayer) this)
                    || event.isCancelled()) {
                lastCancelTick = Tasks.getTick();
            }
        }
    }

    @ModifyExpressionValue(
            //#if MC >= 260300
            //$$ method = "sendChanges",
            //#else
            method = "tick",
            //#endif
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isPassenger()Z"))
    private boolean onTick(boolean original) {
        if (Tasks.getTick() == lastCancelTick) {
            // redirect to sendMovementPackets to eat shit
            return false;
        }
        return original;
    }

    @ModifyExpressionValue(
            method = "sendPosition",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z"))
    private boolean onCancelSendMovementBehaviour(boolean original) {
        if (Tasks.getTick() == lastCancelTick) {
            lastCancelTick = 0;
            return false;
        }
        return original;
    }

    @WrapOperation(
            //#if MC >= 260300
            //$$ method = "sendChanges",
            //#else
            method = "tick",
            //#endif
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Input;equals(Ljava/lang/Object;)Z"))
    private boolean onPlayerInputPackets(Input instance, Object object, Operation<Boolean> original) {
        if (resyncLastInput) {
            resyncLastInput = false;
            return false;
        }
        return original.call(instance, object);
    }

    @Unique
    public void onPlayerInputPackets() {
        if (!this.lastSentInput.equals(this.input.keyPresses) || resyncLastInput) {
            resyncLastInput = false;
            this.connection.send(new ServerboundPlayerInputPacket(this.input.keyPresses));
            this.lastSentInput = this.input.keyPresses;
        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    public void postwrapperPlayerMovementSentTick(CallbackInfo ci) {
        if (!checkClientPlayer()) return;
        onPostPlayerMovementTick((LocalPlayer) (Object) this);
    }

    @Unique
    private void onPostPlayerMovementTick(LocalPlayer player) {
        Listener.getClientPlayerPostSendMovementPoint().broadcast(player);
        movementManager.postProgress(player);
    }

    @Override
    public boolean tryToStartFallFlying() {
        if (!checkClientPlayer()) return super.tryToStartFallFlying();
        boolean fallflying = this.isFallFlying();
        boolean shouldSwitch = false;
        if (!fallflying) {
            shouldSwitch = this.canGlide() && !this.isInWater();
        }
        Event<Boolean> switchGliding = new Event<>(shouldSwitch, true, true, fallflying);
        Listener.getPlayerSwitchFallFlying().handleValue(switchGliding);
        boolean switchFlag;
        if (switchGliding.isCancelled()) {
            switchFlag = false;
        } else {
            switchFlag = switchGliding.context();
        }
        if (!fallflying) {
            if (switchFlag) {
                startFallFlying();
                return true;
            } else {
                return false;
            }
        } else {
            if (switchFlag) {
                stopFallFlying();
                return true;
            } else {
                return false;
            }
        }
    }

    @ModifyArg(
            method = "sendPosition",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private Packet onSendMovementPackets(Packet par1) {
        if (par1 instanceof PlayerMoveC2SPacketAccess acc) {
            acc.setCause(PlayerMoveC2SPacketAccess.Cause.PLAYER_MOVEMENT);
        }
        return par1;
    }

    //#if MC >= 260300
    //$$ // 26.3 的丢弃入口在 MultiPlayerGameMode#dropItem（见 MultiPlayerGameModeEvents）
    //#else
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void onDropSelected(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        if (checkClientPlayer()) {
            if (!Listener.getPlayerDropSelectedItem().fireEvent(entireStack)) {
                cir.setReturnValue(false);
            }
        }
    }
    //#endif

    @Inject(method = "closeContainer", at = @At("HEAD"), cancellable = true)
    private void onCloseHandledScreen(CallbackInfo ci) {
        if (checkClientPlayer()) {
            if (!Listener.getPlayerCloseHandledScreen().fireEvent(null)) {
                ci.cancel();
            }
        }
    }
}
