package me.matl114.utils;

import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ConditionalEffect;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.predicates.AllOfCondition;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.apache.commons.lang3.mutable.MutableFloat;

public class EnchantmentUtils {
    public static float calculate(ItemStack stack, Calculator<Float> consumer, float baseValue) {
        MutableFloat mutableFloat = new MutableFloat(baseValue);
        EnchantmentHelper.runIterationOnItem(stack, (enchantment, level) -> {
            mutableFloat.setValue(consumer.calculate(mutableFloat.getValue(), enchantment, level));
        });
        return mutableFloat.getValue();
    }

    public static float calculate(LivingEntity stack, ContextAwareCalculator<Float> consumer, float baseValue) {
        MutableFloat mutableFloat = new MutableFloat(baseValue);
        EnchantmentHelper.runIterationOnEquipment(stack, (enchantment, level, context) -> {
            mutableFloat.setValue(consumer.calculate(
                    mutableFloat.getValue(), enchantment, level, context.itemStack(), context.inSlot()));
        });
        return mutableFloat.getValue();
    }

    public static float calculate(
            Map<EquipmentSlot, ItemStack> slotItemStackMap, ContextAwareCalculator<Float> consumer, float baseValue) {
        MutableFloat mutableFloat = new MutableFloat(baseValue);
        for (Map.Entry<EquipmentSlot, ItemStack> entry : slotItemStackMap.entrySet()) {
            forEachEnchantments(entry.getValue(), entry.getKey(), (enchantment, level) -> {
                mutableFloat.setValue(consumer.calculate(
                        mutableFloat.getValue(), enchantment, level, entry.getValue(), entry.getKey()));
            });
        }
        ;
        return mutableFloat.getValue();
    }

    public static boolean matchPartialCondition(
            ConditionalEffect<?> effectEntry, Predicate<LootItemCondition> testCondition) {
        if (effectEntry.requirements().isEmpty()) {
            return true;
        } else {
            //#if MC >= 260300
            //$$ LootItemCondition condition = effectEntry.requirements().get().value();
            //#else
            LootItemCondition condition = effectEntry.requirements().get();
            //#endif
            return matchPartialCondition(condition, testCondition);
        }
    }

    public static boolean matchPartialCondition(
            LootItemCondition condition, Predicate<LootItemCondition> testCondition) {
        if (condition instanceof AllOfCondition allOf) {
            //#if MC >= 260300
            //$$ return allOf.terms.stream().allMatch(s -> matchPartialCondition(s.value(), testCondition));
            //#else
            return allOf.terms.stream().allMatch(s -> matchPartialCondition(s, testCondition));
            //#endif
        } else if (condition instanceof AnyOfCondition anyOf) {
            //#if MC >= 260300
            //$$ return anyOf.terms.stream().anyMatch(s -> matchPartialCondition(s.value(), testCondition));
            //#else
            return anyOf.terms.stream().anyMatch(s -> matchPartialCondition(s, testCondition));
            //#endif
        } else if (condition instanceof InvertedLootItemCondition invert) {
            //#if MC >= 260300
            //$$ return !matchPartialCondition(invert.term().value(), testCondition);
            //#else
            return !matchPartialCondition(invert.term(), testCondition);
            //#endif
        } else {
            return testCondition.test(condition);
        }
    }

    private static void forEachEnchantments(
            ItemStack stack, EquipmentSlot slot, EnchantmentHelper.EnchantmentVisitor consumer) {
        if (!stack.isEmpty()) {
            ItemEnchantments itemEnchantmentsComponent = (ItemEnchantments) stack.get(DataComponents.ENCHANTMENTS);
            if (itemEnchantmentsComponent != null && !itemEnchantmentsComponent.isEmpty()) {

                for (var entry : itemEnchantmentsComponent.entrySet()) {
                    Holder<Enchantment> registryEntry = entry.getKey();
                    if ((registryEntry.value()).matchingSlot(slot)) {
                        consumer.accept(registryEntry, entry.getIntValue());
                    }
                }
            }
        }
    }

    public interface Calculator<T> {
        public T calculate(T current, Holder<Enchantment> enchantment, int level);
    }

    public interface ContextAwareCalculator<T> {
        public T calculate(
                T current, Holder<Enchantment> enchantment, int level, ItemStack stack, @Nullable EquipmentSlot slot);
    }
}
