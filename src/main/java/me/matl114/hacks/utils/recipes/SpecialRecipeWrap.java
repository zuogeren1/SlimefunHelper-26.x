package me.matl114.hacks.utils.recipes;

import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public record SpecialRecipeWrap(
        String rid,
        String id,
        ItemStack workType,
        ItemStack output,
        RecipeIngredient[] ingredient,
        Consumer<AbstractContainerMenu> placer)
        implements WrapRecipe {
    public SpecialRecipeWrap {
        Objects.requireNonNull(rid);
        Objects.requireNonNull(id);
        Objects.requireNonNull(workType);
        Objects.requireNonNull(output);
        Objects.requireNonNull(ingredient);
        Objects.requireNonNull(placer);
    }

    @Override
    public void place(AbstractContainerMenu screenHandler) {
        placer.accept(screenHandler);
    }
}
