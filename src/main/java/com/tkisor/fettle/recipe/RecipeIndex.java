package com.tkisor.fettle.recipe;

import com.google.common.collect.Multimap;
import com.tkisor.fettle.config.FettleConfig;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Pre-compiled, bitset based pre-filter for recipe lookups.
 *
 * <p>Contract: for any input, the candidate bitset is a <em>superset</em> of the recipes vanilla
 * {@code Recipe#matches} would accept. The final decision therefore stays with vanilla; the index
 * only decides which recipes are worth asking.
 *
 * <p>Design (this is the "pre-compile the data plane" step, deliberately not a HashMap per item):
 * <ul>
 *   <li>every item used by a recipe gets a dense id at index build time (identity map, no hashing at
 *       lookup time beyond a single {@code IdentityHashMap} probe);</li>
 *   <li>each item owns a fixed-width slice of one flat {@code long[]} arena holding a bitset over the
 *       recipe-type's recipe indices, so the whole structure is a primitive array with no per-lookup
 *       allocation;</li>
 *   <li>a lookup ORs the slices of the input's items plus the "unindexed" slice into a per-thread
 *       scratch bitset and then walks the set bits in ascending index order - which is exactly
 *       vanilla's iteration order, so the first matching recipe is the same one vanilla returns.</li>
 * </ul>
 *
 * <p>Recipes that cannot be indexed safely (special recipes, incomplete recipes, ingredients whose
 * item set cannot be enumerated) land in the "unindexed" slice and are always candidates.
 */
public final class RecipeIndex {

    /** Index for one recipe type. */
    public static final class TypeIndex {
        /** All holders of that type, in the same order as {@code RecipeManager#byType}. */
        public final List<RecipeHolder<?>> ordered;

        private final Map<Item, Integer> itemSlice = new IdentityHashMap<>();
        private long[] arena = new long[0];
        private long[] unindexed = new long[0];
        private int itemCount;
        private int words = 1;

        private TypeIndex(List<RecipeHolder<?>> ordered) {
            this.ordered = ordered;
        }

        private void finish() {
            words = Math.max(1, (ordered.size() + 63) >>> 6);
            arena = new long[Math.max(1, itemCount) * words];
            unindexed = new long[words];
        }

        private void mark(int recipeIndex, int slice) {
            arena[slice * words + (recipeIndex >>> 6)] |= 1L << (recipeIndex & 63);
        }

        /**
         * Fills {@code scratch} with the candidate bitset for {@code input} and returns it.
         * The returned array is reused, so the caller must consume it before the next lookup.
         */
        public long[] candidates(RecipeInput input, long[] scratch) {
            if (!FettleConfig.bitsetIndexEnabled()) {
                Arrays.fill(scratch, -1L); // "everything is a candidate" = vanilla behaviour
                if (scratch.length > words) {
                    Arrays.fill(scratch, words, scratch.length, 0L);
                }
                return scratch;
            }
            System.arraycopy(unindexed, 0, scratch, 0, words);
            int size = input.size();
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = input.getItem(slot);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                Integer slice = itemSlice.get(stack.getItem());
                if (slice == null) {
                    continue;
                }
                int base = slice * words;
                for (int word = 0; word < words; word++) {
                    scratch[word] |= arena[base + word];
                }
            }
            return scratch;
        }

        public int words() {
            return words;
        }
    }

    private final Map<RecipeType<?>, TypeIndex> types;

    private RecipeIndex(Map<RecipeType<?>, TypeIndex> types) {
        this.types = types;
    }

    /** @return the index for that type, or {@code null} if the type is not present at all. */
    public TypeIndex type(RecipeType<?> type) {
        return types.get(type);
    }

    public static RecipeIndex build(Multimap<RecipeType<?>, RecipeHolder<?>> source) {
        Map<RecipeType<?>, TypeIndex> map = new IdentityHashMap<>();
        for (Map.Entry<RecipeType<?>, Collection<RecipeHolder<?>>> entry : source.asMap().entrySet()) {
            List<RecipeHolder<?>> ordered = List.copyOf(entry.getValue());
            TypeIndex index = new TypeIndex(ordered);

            // pass 1: collect the items this type can ever match, assigning dense slices
            int[] recipeSlices = new int[ordered.size()];
            Arrays.fill(recipeSlices, -1);
            for (int i = 0; i < ordered.size(); i++) {
                Recipe<?> recipe = ordered.get(i).value();
                if (!indexable(recipe)) {
                    continue;
                }
                NonNullList<Ingredient> ingredients = recipe.getIngredients();
                int slice = -1;
                for (int ingredient = 0; ingredient < ingredients.size(); ingredient++) {
                    ItemStack[] items = ingredients.get(ingredient).getItems();
                    for (int item = 0; item < items.length; item++) {
                        ItemStack stack = items[item];
                        if (stack == null || stack.isEmpty()) {
                            continue;
                        }
                        Integer existing = index.itemSlice.get(stack.getItem());
                        if (existing == null) {
                            index.itemSlice.put(stack.getItem(), index.itemCount);
                            slice = index.itemCount;
                            index.itemCount++;
                        } else {
                            slice = existing;
                        }
                    }
                }
                recipeSlices[i] = slice;
            }

            // pass 2: layout the arena and set the bits
            index.finish();
            for (int i = 0; i < ordered.size(); i++) {
                Recipe<?> recipe = ordered.get(i).value();
                if (!indexable(recipe)) {
                    index.unindexed[i >>> 6] |= 1L << (i & 63);
                    continue;
                }
                NonNullList<Ingredient> ingredients = recipe.getIngredients();
                for (int ingredient = 0; ingredient < ingredients.size(); ingredient++) {
                    ItemStack[] items = ingredients.get(ingredient).getItems();
                    for (int item = 0; item < items.length; item++) {
                        ItemStack stack = items[item];
                        if (stack == null || stack.isEmpty()) {
                            continue;
                        }
                        Integer slice = index.itemSlice.get(stack.getItem());
                        if (slice != null) {
                            index.mark(i, slice);
                        }
                    }
                }
            }
            map.put(entry.getKey(), index);
        }
        return new RecipeIndex(map);
    }

    private static boolean indexable(Recipe<?> recipe) {
        if (recipe == null || recipe.isSpecial() || recipe.isIncomplete()) {
            return false;
        }
        if (FettleConfig.strictVanillaSerializersOnly()) {
            ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
            if (serializerId == null || !"minecraft".equals(serializerId.getNamespace())) {
                return false;
            }
        }
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients == null || ingredients.isEmpty()) {
            return false;
        }
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient == null || ingredient.isEmpty() || ingredient.getItems().length == 0) {
                return false;
            }
        }
        return true;
    }
}
