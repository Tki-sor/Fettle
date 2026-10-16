package com.tkisor.fettle.recipe;

import com.tkisor.fettle.Fettle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Correctness gate for the recipe index layer.
 *
 * <p>For every crafting recipe it builds a synthetic input out of the recipe's own ingredients and
 * compares the indexed lookup with a brute-force vanilla scan (first holder whose {@code matches}
 * returns true). Any difference means the pre-filter dropped a candidate it should have kept.
 */
public final class RecipeParityCheck {

    private RecipeParityCheck() {
    }

    public static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        RecipeManager manager = level.getRecipeManager();
        List<RecipeHolder<CraftingRecipe>> all = manager.getAllRecipesFor(RecipeType.CRAFTING);

        int checked = 0;
        int skipped = 0;
        int mismatches = 0;

        for (int i = 0; i < all.size(); i++) {
            RecipeHolder<CraftingRecipe> holder = all.get(i);
            CraftingInput input = inputFor(holder.value());
            if (input == null) {
                skipped++;
                continue;
            }
            Optional<RecipeHolder<CraftingRecipe>> indexed =
                    manager.getRecipeFor(RecipeType.CRAFTING, input, level);
            Optional<RecipeHolder<CraftingRecipe>> vanilla = bruteForce(all, input, level);
            checked++;
            if (!sameId(indexed, vanilla)) {
                mismatches++;
                Fettle.LOGGER.error("[Fettle] recipe parity mismatch: indexed={} vanilla={} source={} input={}",
                        idOf(indexed), idOf(vanilla), holder.id(), describe(input));
            }
        }

        Fettle.LOGGER.info("[Fettle] recipe parity self-test: checked={} skipped={} mismatches={}",
                checked, skipped, mismatches);
    }

    private static Optional<RecipeHolder<CraftingRecipe>> bruteForce(List<RecipeHolder<CraftingRecipe>> all,
                                                                    CraftingInput input, ServerLevel level) {
        for (int i = 0; i < all.size(); i++) {
            RecipeHolder<CraftingRecipe> holder = all.get(i);
            if (holder.value().matches(input, level)) {
                return Optional.of(holder);
            }
        }
        return Optional.empty();
    }

    /** Builds a 3x3 grid where slot i holds the first item of ingredient i, or null if not possible. */
    private static CraftingInput inputFor(CraftingRecipe recipe) {
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || ingredients.size() > 9) {
            return null;
        }
        List<ItemStack> stacks = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            if (i >= ingredients.size()) {
                stacks.add(ItemStack.EMPTY);
                continue;
            }
            Ingredient ingredient = ingredients.get(i);
            if (ingredient == null || ingredient.isEmpty()) {
                return null;
            }
            ItemStack[] items = ingredient.getItems();
            if (items.length == 0) {
                return null;
            }
            stacks.add(new ItemStack(items[0].getItem()));
        }
        return CraftingInput.of(3, 3, stacks);
    }

    private static boolean sameId(Optional<RecipeHolder<CraftingRecipe>> a,
                                  Optional<RecipeHolder<CraftingRecipe>> b) {
        return idOf(a).equals(idOf(b));
    }

    private static String idOf(Optional<RecipeHolder<CraftingRecipe>> holder) {
        return holder.map(h -> h.id().toString()).orElse("<none>");
    }

    private static String describe(CraftingInput input) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < input.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ItemStack stack = input.getItem(i);
            sb.append(stack.isEmpty() ? "-" : stack.getItem().toString());
        }
        return sb.append(']').toString();
    }
}
