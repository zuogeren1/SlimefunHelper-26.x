package me.matl114.hacks;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.utils.recipes.NetworkRecipeWrap;
import me.matl114.hacks.utils.recipes.RecipeIngredient;
import me.matl114.hacks.utils.recipes.WrapRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.*;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

public class RecipeTasks {
    private static Minecraft mc = Minecraft.getInstance();
    private static Map<Identifier, WrapRecipe> CACHE;
    public static final Map EMPTY = Map.of();
    public static final Map<String, RecipeType> TYPE_MAP = new LinkedHashMap<>();

    public static boolean isVanillaRecipeType(String rid) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(rid)).isPresent();
    }

    private static final Map<String, ItemStack> SUPPORT_VANILLA_RTYPE = Map.of(
            "minecraft:crafting", new ItemStack(Items.CRAFTING_TABLE),
            "minecraft:smelting", new ItemStack(Items.FURNACE),
            "minecraft:blasting", new ItemStack(Items.BLAST_FURNACE),
            "minecraft:smoking", new ItemStack(Items.SMOKER),
            "minecraft:campfire_cooking", new ItemStack(Items.CAMPFIRE),
            "minecraft:stonecutting", new ItemStack(Items.STONECUTTER),
            "minecraft:smithing", new ItemStack(Items.SMITHING_TABLE));

    public static ItemStack getVanillaRecipeTypeIcon(String rid) {
        return BuiltInRegistries.ITEM
                .getOptional(Identifier.tryParse(rid))
                .map(ItemStack::new)
                .orElse(null);
    }

    public static Map<Identifier, WrapRecipe> getAllRecipe() {
        init();
        return CACHE;
    }

    private static void init() {
        if (CACHE == null || CACHE.isEmpty()) {
            Map<Identifier, WrapRecipe> map;
            synchronized (RecipeTasks.class) {
                resetCache();
                CACHE = new LinkedHashMap<>();
                map = CACHE;
            }
            // init empty map and do not go in if you are not in a world
            if (mc.level == null) {
                return;
            }

            for (var entry : mc.player.getRecipeBook().known.entrySet()) {
                var key = entry.getKey();
                var value = entry.getValue();
                map.put(Identifier.withDefaultNamespace(String.valueOf(key.index())), wrap(value));
            }
        }
    }

    private static void resetCache() {
        synchronized (RecipeTasks.class) {
            if (CACHE != null) {
                CACHE.clear();
            }
            CACHE = new LinkedHashMap<>();
        }
    }

    private static WrapRecipe wrap(RecipeDisplayEntry entry) {
        ContextMap context = SlotDisplayContext.fromLevel(mc.level);
        return NetworkRecipeWrap.of(entry, context);
    }

    public static RecipeIngredient[] transfer3x3RecipeDisplay(RecipeDisplay instance, ContextMap map) {

        RecipeIngredient[] ingredients = new RecipeIngredient[9];

        if (instance instanceof ShapedCraftingRecipeDisplay shaped) {
            List<SlotDisplay> raw = shaped.ingredients();
            int width = shaped.width();
            int height = shaped.height();
            for (int i = 0; i < 3; ++i) {
                for (int j = 0; j < 3; ++j) {
                    if (i < height && j < width) {
                        ingredients[3 * i + j] = new RecipeIngredient(
                                (raw.get(width * i + j)).resolveForStacks(map).toArray(ItemStack[]::new));
                    } else {
                        ingredients[3 * i + j] = RecipeIngredient.EMPTY;
                    }
                }
            }
        } else if (instance instanceof ShapelessCraftingRecipeDisplay shapeless) {
            int var = 0;
            for (var i : shapeless.ingredients()) {
                ingredients[var++] =
                        new RecipeIngredient(i.resolveForStacks(map).toArray(ItemStack[]::new));
            }
            for (; var < 9; ++var) {
                ingredients[var] = RecipeIngredient.EMPTY;
            }
        } else if (instance instanceof FurnaceRecipeDisplay shaped) {
            ingredients[0] = new RecipeIngredient(
                    shaped.ingredient().resolveForStacks(map).toArray(ItemStack[]::new));
            for (var i = 1; i < 9; ++i) {
                ingredients[i] = RecipeIngredient.EMPTY;
            }
        } else if (instance instanceof StonecutterRecipeDisplay shaped) {
            ingredients[0] =
                    new RecipeIngredient(shaped.input().resolveForStacks(map).toArray(ItemStack[]::new));
            for (var i = 1; i < 9; ++i) {
                ingredients[i] = RecipeIngredient.EMPTY;
            }
        } else if (instance instanceof SmithingRecipeDisplay shaped) {
            ingredients[1] =
                    new RecipeIngredient(shaped.base().resolveForStacks(map).toArray(ItemStack[]::new));
            ingredients[2] =
                    new RecipeIngredient(shaped.addition().resolveForStacks(map).toArray(ItemStack[]::new));
            ingredients[0] =
                    new RecipeIngredient(shaped.template().resolveForStacks(map).toArray(ItemStack[]::new));
            for (var i = 3; i < 9; ++i) {
                ingredients[i] = RecipeIngredient.EMPTY;
            }
        } else {
            // throw new UnsupportedOperationException("Unsupported type");
            for (var i = 0; i < 9; ++i) {
                ingredients[i] = RecipeIngredient.EMPTY;
            }
        }
        return ingredients;
    }

    public static Stream<ItemStack> streamIngredientOptions(Ingredient ingredient) {
        // 26.2 移除了 CustomIngredient，Ingredient.items() 直接返回全部匹配项
        return ingredient.items().map(Holder::value).map(ItemStack::new);
    }

    public static List<Ingredient> getIngredients(RecipeDisplayId recipeEntry) {
        RecipeDisplayEntry entry = mc.player.getRecipeBook().known.get(recipeEntry);
        if (entry == null) {
            return List.of();
        }
        return entry.craftingRequirements().orElse(List.of());
    }

    public static ItemStack getRecipeResult(RecipeDisplayId recipeEntry) {
        RecipeDisplayEntry entry = mc.player.getRecipeBook().known.get(recipeEntry);
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        ContextMap contextParameterMap = SlotDisplayContext.fromLevel(mc.level);
        List<ItemStack> stacks = entry.resultItems(contextParameterMap);
        return stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0);
    }

    public static void addRecipe(Event<ClientboundRecipeBookAddPacket> event) {
        if (mc.level == null) return;
        var map = getAllRecipe();
        for (ClientboundRecipeBookAddPacket.Entry entry : event.context().entries()) {
            RecipeDisplayEntry entry0 = entry.contents();
            map.put(Identifier.withDefaultNamespace(String.valueOf(entry0.id().index())), wrap(entry0));
        }
    }

    public static void removeRecipe(Event<ClientboundRecipeBookRemovePacket> event) {
        Map<Identifier, WrapRecipe> map;
        if ((map = CACHE) != null) {
            event.context().recipes().stream()
                    .map(RecipeDisplayId::index)
                    .map(String::valueOf)
                    .map(Identifier::withDefaultNamespace)
                    .forEach(map::remove);
        }
    }

    static {
        Listener.getServerLeavePoint().registerHandler((v) -> {
            resetCache();
        });
        Listener.getPacketPostHandlePoint()
                .getChannel(ClientboundRecipeBookAddPacket.class)
                .registerHandler(RecipeTasks::addRecipe);
        Listener.getPacketPoint()
                .getChannel(ClientboundRecipeBookRemovePacket.class)
                .registerHandler(RecipeTasks::removeRecipe);
        Listener.registerSinglePacketListener(
                ClientboundUpdateRecipesPacket.class, (Consumer<ClientboundUpdateRecipesPacket>) (p) -> resetCache());
    }
}
