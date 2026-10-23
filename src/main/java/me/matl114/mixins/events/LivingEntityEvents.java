package me.matl114.mixins.events;

import com.google.common.collect.Maps;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Iterator;
import java.util.Map;
import me.matl114.accessors.access.LivingEntityAccess;
import me.matl114.accessors.events.EntityAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.utils.AttributeUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(LivingEntity.class)
public abstract class LivingEntityEvents extends Entity
        implements EntityAccess<LivingEntity>, LivingEntityAccess<LivingEntity> {

    public LivingEntityEvents(EntityType<?> type, Level world) {
        super(type, world);
    }

    @Unique
    private Map<EquipmentSlot, ItemStack> clientLastEquipmentSnapshot;

    @Unique
    public Map<EquipmentSlot, ItemStack> getClientLastEquipmentSnapshot() {
        if (clientLastEquipmentSnapshot == null) {
            clientLastEquipmentSnapshot = Util.makeEnumMap(EquipmentSlot.class, (slot) -> {
                return ItemStack.EMPTY;
            });
        }
        return clientLastEquipmentSnapshot;
    }

    @Unique
    public void tickEquipment() {
        var map = getClientLastEquipmentSnapshot();
        for (var re : EquipmentSlot.values()) {
            map.put(re, getItemBySlot(re));
        }
    }

    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/LivingEntity;isRemoved()Z",
                            shift = At.Shift.BEFORE))
    public void onTickEquipment(CallbackInfo ci) {
        if ((Entity) (Object) this instanceof Player) {
            tickEquipment();
        }
    }

    @Unique
    Integer nextJumpCooldown;

    @Shadow
    private int noJumpDelay;

    @Shadow
    protected int fallFlyTicks;

    @Shadow
    public abstract ItemStack getItemBySlot(EquipmentSlot slot);

    @Shadow
    public abstract boolean equipmentHasChanged(ItemStack stack, ItemStack stack2);

    @Shadow
    public abstract AttributeMap getAttributes();

    @Shadow
    protected abstract void stopLocationBasedEffects(
            ItemStack removedEquipment, EquipmentSlot slot, AttributeMap container);

    @WrapOperation(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;jumpFromGround()V"))
    private void onJump(LivingEntity instance, Operation<Void> original) {
        if ((Entity) this == ((Entity) Minecraft.getInstance().player)) {
            // 10 sec
            Event<Integer> jumpEvent = new Event<>(10, true, true);
            Listener.getPlayerNotFlyJumpPoint().handleValue(jumpEvent);
            nextJumpCooldown = jumpEvent.context();
            if (!jumpEvent.isCancelled()) {
                original.call(instance);
            }
        } else {
            original.call(instance);
        }
    }

    @Inject(
            method = "aiStep",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/LivingEntity;isFallFlying()Z",
                            shift = At.Shift.BEFORE))
    private void overrideJumpCooldown(CallbackInfo ci) {
        if (nextJumpCooldown != null) {
            noJumpDelay = nextJumpCooldown;
            nextJumpCooldown = null;
        }
    }

    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/LivingEntity;isFallFlying()Z",
                            shift = At.Shift.BEFORE))
    private void onWriteFlyingTicks(CallbackInfo ci) {
        if (checkClientPlayer()) {
            Event<Integer> fallFlyingEvent = new Event<>(this.fallFlyTicks + 1, true, true);
            Listener.getPlayerFallFlyingTick().handleValue(fallFlyingEvent);
            if (fallFlyingEvent.isCancelled()) {
                this.fallFlyTicks -= 1;
            } else {
                this.fallFlyTicks = fallFlyingEvent.context() - 1;
            }
        }
    }

    @Unique
    public final void updateEquipmentAttributeChange() {
        getClientLastEquipmentSnapshot();
        Map<EquipmentSlot, ItemStack> map = null;
        Iterator var2 = EquipmentSlot.VALUES.iterator();
        ItemStack itemStack2;
        while (var2.hasNext()) {
            EquipmentSlot equipmentSlot = (EquipmentSlot) var2.next();
            ItemStack itemStack = (ItemStack) clientLastEquipmentSnapshot.get(equipmentSlot);
            itemStack2 = this.getItemBySlot(equipmentSlot);
            if (this.equipmentHasChanged(itemStack, itemStack2)) {
                if (map == null) {
                    map = Maps.newEnumMap(EquipmentSlot.class);
                }

                map.put(equipmentSlot, itemStack2);
                AttributeMap attributeContainer = this.getAttributes();
                if (!itemStack.isEmpty()) {
                    this.stopLocationBasedEffects(itemStack, equipmentSlot, attributeContainer);
                }
            }
        }

        if (map != null) {
            var2 = map.entrySet().iterator();

            while (var2.hasNext()) {
                Map.Entry<EquipmentSlot, ItemStack> entry = (Map.Entry) var2.next();
                EquipmentSlot equipmentSlot2 = (EquipmentSlot) entry.getKey();
                itemStack2 = (ItemStack) entry.getValue();
                if (!itemStack2.isEmpty() && !itemStack2.isBroken()) {
                    itemStack2.forEachModifier(equipmentSlot2, (attribute, modifier) -> {
                        AttributeInstance entityAttributeInstance =
                                this.getAttributes().getInstance(attribute);
                        if (entityAttributeInstance != null) {
                            entityAttributeInstance.removeModifier(modifier.id());
                            entityAttributeInstance.addTransientModifier(modifier);
                        }
                    });
                }
            }
            tickEquipment();

            if (checkClientPlayer()
                    && ViaFabricPlusHooks.getInstance().getCurrentVersion().isLowerOrEqualTo(20, 8)) {
                AttributeUtils.overrideViaAttributes(getClientLastEquipmentSnapshot(), this.getAttributes());
            }
        }
    }
}
