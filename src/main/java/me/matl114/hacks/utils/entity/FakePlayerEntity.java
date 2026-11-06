package me.matl114.hacks.utils.entity;

import com.mojang.authlib.GameProfile;
import java.util.function.Consumer;
import me.matl114.accessors.events.ClientConnectionAccess;
import me.matl114.utils.DamageUtils;
import me.matl114.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class FakePlayerEntity extends RemotePlayer {
    boolean hasPhysics = true;
    Consumer<FakePlayerEntity> tickTask;

    public FakePlayerEntity(ClientLevel clientWorld, GameProfile gameProfile) {
        super(clientWorld, gameProfile);
        // 26.2 起构造期 id 还没分配，getId() 会抛异常；改用自增负数（语义见 ClientEntityIds）
        setId(ClientEntityIds.next());
    }

    public void copyDataFrom(Player player) {
        setPos(player.position());
        setLevel(player.level());
        setXRot(player.getXRot());
        setYRot(player.getYRot());
        setOldPosAndRot();
    }

    public void copyEquipmentFrom(Player player) {
        InventoryUtils.copyInventory(player.getInventory(), getInventory());
    }

    public void setFreeze(boolean freeze) {
        hasPhysics = !freeze;
    }

    public void setTickTask(Consumer<FakePlayerEntity> tickTask) {
        this.tickTask = tickTask;
    }

    public float damage(float rawDamage, DamageSource damageSource) {
        if (this.isInvulnerableToBase(damageSource)) {
            return 0.0F;
        }
        DamageUtils.DamageContext context = DamageUtils.fromPlayer(this).build();
        rawDamage = DamageUtils.getDamageAfterDifficulty(rawDamage, damageSource, context);
        if (rawDamage <= 0.0F) {
            return 0.0F;
        }
        rawDamage = applyHurtTimeDamage(rawDamage, damageSource);
        if (rawDamage <= 0.0F) {
            return 0.0F;
        }
        rawDamage = DamageUtils.getDamageAfterArmorReduce(rawDamage, null, damageSource, context);
        if (rawDamage <= 0.0F) {
            return 0.0F;
        }
        rawDamage = DamageUtils.getDamageAfterEffectAndProtection(rawDamage, damageSource, context);
        if (rawDamage <= 0.0F) {
            return 0.0F;
        }
        DamageUtils.damageOrAbsorption(this, damageSource, rawDamage);
        if (this.isDeadOrDying()) {
            if (!this.checkTotemDeathProtection(damageSource)) {
                this.die(damageSource);
            } else {
                ClientConnectionAccess.of(
                                Minecraft.getInstance().getConnection().getConnection())
                        .handlePacket(new ClientboundEntityEventPacket(this, EntityEvent.PROTECTED_FROM_DEATH));
            }
        }
        return rawDamage;
    }

    public float applyHurtTimeDamage(float currentVal, DamageSource source) {
        //#if MC >= 260300
        //$$ if ((float) this.getInvulnerableTime() > 10.0F && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
        //#else
        if ((float) this.invulnerableTime > 10.0F && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
        //#endif
            if (currentVal <= this.lastHurt) {
                return 0.0F;
            }
            float newAmount = currentVal - this.lastHurt;
            this.lastHurt = currentVal;
            return newAmount;
        } else {
            this.lastHurt = currentVal;
            //#if MC >= 260300
            //$$ this.setInvulnerableTime(20);
            //#else
            this.invulnerableTime = 20;
            //#endif
            this.hurtDuration = 10;
            this.hurtTime = this.hurtDuration;
            return currentVal;
        }
    }

    @Override
    public boolean onGround() {
        if (hasPhysics) {
            return super.onGround();
        } else {
            return true;
        }
    }

    @Override
    public Vec3 getDeltaMovement() {
        if (hasPhysics) {
            return super.getDeltaMovement();
        } else {
            return Vec3.ZERO;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (tickTask != null) {
            tickTask.accept(this);
        }
    }
}
