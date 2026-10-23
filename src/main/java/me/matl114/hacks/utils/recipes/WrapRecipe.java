package me.matl114.hacks.utils.recipes;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public interface WrapRecipe extends RecipeEntry {
    ItemStack workType();

    void place(AbstractContainerMenu screenHandler);

    default RecipeIngredient[] ingredients() {
        return ingredient();
    }
}
