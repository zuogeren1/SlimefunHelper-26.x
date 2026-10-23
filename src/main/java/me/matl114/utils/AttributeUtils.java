package me.matl114.utils;

import static net.minecraft.world.entity.ai.attributes.Attributes.*;

import java.util.*;
import me.matl114.accessors.access.LivingEntityAccess;
import me.matl114.hooks.ViaFabricPlusHooks;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

public class AttributeUtils {
    public static double getPlayerBlockInteractionRange(Player player) {
        return player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
    }

    public static AttributeMap copyAttributes(LivingEntity living) {
        AttributeMap attributeContainer = new AttributeMap(
                DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) living.getType()));
        attributeContainer.assignAllValues(living.getAttributes());
        return attributeContainer;
    }

    public static AttributeMap getAttributeWith(
            LivingEntity living, Map<EquipmentSlot, ItemStack> equipmentOverrides) {
        Map<EquipmentSlot, ItemStack> filterMap = new LinkedHashMap<>(equipmentOverrides);
        AttributeMap attributeContainer = copyAttributes(living);
        for (var slot : EquipmentSlot.values()) {
            var itemStack2 = equipmentOverrides.getOrDefault(slot, living.getItemBySlot(slot));
            ItemStack toBeRemoved = living.getItemBySlot(slot);
            if (!toBeRemoved.isEmpty()) {
                toBeRemoved.forEachModifier(slot, (attribute, modifier) -> {
                    AttributeInstance entityAttributeInstance = attributeContainer.getInstance(attribute);
                    if (entityAttributeInstance != null) {
                        entityAttributeInstance.removeModifier(modifier);
                    }
                });
            }
            if (!itemStack2.isEmpty() && !itemStack2.isBroken()) {
                itemStack2.forEachModifier(slot, (attribute, modifier) -> {
                    AttributeInstance entityAttributeInstance = attributeContainer.getInstance(attribute);
                    if (entityAttributeInstance != null) {
                        entityAttributeInstance.removeModifier(modifier.id());
                        entityAttributeInstance.addTransientModifier(modifier);
                    }
                });
            }
        }
        if (living instanceof Player pl
                && ViaFabricPlusHooks.getInstance().getCurrentVersion().isLowerOrEqualTo(20, 8)) {
            Map<EquipmentSlot, ItemStack> newMap = new HashMap<>(filterMap);
            for (var re : EquipmentSlot.values()) {
                if (!newMap.containsKey(re)) {
                    newMap.put(re, living.getItemBySlot(re));
                }
            }
            overrideViaAttributes(newMap, attributeContainer);
        }
        return attributeContainer;
    }

    public static void updateAttribute(LivingEntity living) {
        LivingEntityAccess.of(living).updateEquipmentAttributeChange();
    }

    public static List<Holder<Attribute>> ATTRIBUTES_1_21_1 = List.of(
            MOVEMENT_EFFICIENCY,
            WATER_MOVEMENT_EFFICIENCY,
            MINING_EFFICIENCY,
            SNEAKING_SPEED,
            SUBMERGED_MINING_SPEED,
            ATTACK_KNOCKBACK);

    private static void setAttributeVia(
            AttributeMap attributeContainer, Holder<Attribute> attribute, double level) {
        var attributeInstance = attributeContainer.getInstance(attribute);
        if (attributeInstance != null) {
            attributeInstance
                    .removeModifiers(); // Minecraft is applying attribute modifiers in some situations, remove them
            // before
            // we set the base value
            attributeInstance.setBaseValue(level);
        }
    }

    private static int getEquipmentLevel(
            ResourceKey<Enchantment> enchantment, Map<EquipmentSlot, ItemStack> equipmentOverrides) {
        var entry = ItemStackUtils.registry().getOrThrow(enchantment);
        int i = 0;
        var var4 = equipmentOverrides.entrySet().iterator();

        while (var4.hasNext()) {
            var en = var4.next();
            if (entry.value().matchingSlot(en.getKey())) {
                ItemStack itemStack = en.getValue();
                int j = EnchantmentHelper.getItemEnchantmentLevel(entry, itemStack);
                if (j > i) {
                    i = j;
                }
            }
        }

        return i;
    }

    public static void overrideViaAttributes(Map<EquipmentSlot, ItemStack> equipmentMap, AttributeMap container) {
        // Update generic attributes for all entities
        setAttributeVia(
                container,
                Attributes.WATER_MOVEMENT_EFFICIENCY,
                getEquipmentLevel(Enchantments.DEPTH_STRIDER, equipmentMap) / 3D);
        final int efficiencyLevel = getEquipmentLevel(Enchantments.EFFICIENCY, equipmentMap);
        setAttributeVia(
                container,
                Attributes.MINING_EFFICIENCY,
                efficiencyLevel > 0 ? efficiencyLevel * efficiencyLevel + 1D : 0D);
        setAttributeVia(
                container,
                Attributes.SNEAKING_SPEED,
                0.3D + getEquipmentLevel(Enchantments.SWIFT_SNEAK, equipmentMap) * 0.15D);
        setAttributeVia(
                container,
                Attributes.SUBMERGED_MINING_SPEED,
                getEquipmentLevel(Enchantments.AQUA_AFFINITY, equipmentMap) <= 0 ? 0.2D : 1D);
        setAttributeVia(
                container, Attributes.ATTACK_KNOCKBACK, getEquipmentLevel(Enchantments.KNOCKBACK, equipmentMap));
    }
}
