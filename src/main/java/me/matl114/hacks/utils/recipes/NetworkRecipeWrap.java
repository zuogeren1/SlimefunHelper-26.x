package me.matl114.hacks.utils.recipes;

import me.matl114.hacks.RecipeTasks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

public record NetworkRecipeWrap(
        RecipeDisplayEntry entry, ItemStack workType, ItemStack output, RecipeIngredient[] ingredient)
        implements WrapRecipe {
    public static NetworkRecipeWrap of(RecipeDisplayEntry entry, ContextMap context) {
        return new NetworkRecipeWrap(
                entry,
                entry.display().craftingStation().resolveForFirstStack(context),
                entry.display().result().resolveForFirstStack(context),
                RecipeTasks.transfer3x3RecipeDisplay(entry.display(), context));
    }

    @Override
    public String rid() {
        return BuiltInRegistries.ITEM.getKey(workType.getItem()).toString();
    }

    @Override
    public String id() {
        return Identifier.withDefaultNamespace(String.valueOf(entry.id().index())).toString();
    }

    @Override
    public void place(AbstractContainerMenu screenHandler) {
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode != null) {
            client.gameMode.handlePlaceRecipe(screenHandler.containerId, entry.id(), true);
        }
    }
}
