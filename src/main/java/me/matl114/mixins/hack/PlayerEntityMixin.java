package me.matl114.mixins.hack;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.matl114.accessors.access.LivingEntityAccess;
import me.matl114.accessors.hacks.EntityInternalAccess;
import me.matl114.accessors.hacks.PlayerInternalAccess;
import me.matl114.hacks.modules.combat.CombatExtra;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.utils.entity.Predictor;
import me.matl114.hacks.utils.entity.PredictorImpl;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(Player.class)
public abstract class PlayerEntityMixin extends LivingEntity
        implements LivingEntityAccess<Player>, EntityInternalAccess<Player>, PlayerInternalAccess {
    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, Level world) {
        super(entityType, world);
    }

    @ModifyExpressionValue(
            method = "getDestroySpeed",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/player/Player;getAttributeValue(Lnet/minecraft/core/Holder;)D",
                            ordinal = 1))
    private double onBlockBreakingSpeedAttrWrongValueFix(double original) {
        return original < 1E-5 ? 1.0F : original;
    }

    @Unique
    PredictorImpl predictorImpl;

    // 26.2 起 Entity#getId() 在 id 未分配（0）时会抛 IllegalStateException，而玩家实体的 id 是
    // 构造之后才由 ClientPacketListener#handleLogin 调 setId(packet.playerId()) 分配的，
    // 所以不能在构造器里 new PredictorImpl(this)（会直接打断 LocalPlayer 的构造，进而整个进世界流程）；
    // 统一走下面的惰性创建，那时 id 一定已经分配好了。
    @Override
    public Predictor getPositionPredictor() {
        if (predictorImpl == null) {
            predictorImpl = new PredictorImpl(this);
        }
        return predictorImpl;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void positionRecordTick(CallbackInfo ci) {
        if (predictorImpl == null) {
            predictorImpl = new PredictorImpl(this);
        }
        predictorImpl.tick();
    }

    @Inject(method = "blockInteractionRange", at = @At("RETURN"), cancellable = true)
    private void getBlockInteractionRange(CallbackInfoReturnable<Double> cir) {
        double reach = InteractExtra.INSTANCE.reachDistance.get();
        if (reach > 1E-6) {
            cir.setReturnValue(cir.getReturnValueD() + reach);
        }
    }

    @Inject(method = "entityInteractionRange", at = @At("RETURN"), cancellable = true)
    private void getEntityInteractionRange(CallbackInfoReturnable<Double> cir) {
        if (CombatExtra.INSTANCE.range.get() > 0.1) {
            cir.setReturnValue(CombatExtra.INSTANCE.getAttackRange());
        }
    }
}
