package me.matl114.hacks.utils.survival;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;

public enum ToolCategory {
    SWORD,
    AXE,
    MACE,
    HELMET,
    CHEST_PLATE,
    LEGGINGS,
    BOOTS,
    TOOL;

    public static ToolCategory of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (item instanceof MaceItem) {
            return MACE;
        }
        if (stack.is(ItemTags.SWORDS)) {
            return SWORD;
        }
        if (stack.is(ItemTags.AXES)) {
            return AXE;
        }
        if (stack.is(ItemTags.HEAD_ARMOR)) {
            return HELMET;
        }
        if (stack.is(ItemTags.CHEST_ARMOR)) {
            return CHEST_PLATE;
        }
        if (stack.is(ItemTags.LEG_ARMOR)) {
            return LEGGINGS;
        }
        if (stack.is(ItemTags.FOOT_ARMOR)) {
            return BOOTS;
        }
        if (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)) {
            return TOOL;
        }
        return null;
    }
}
