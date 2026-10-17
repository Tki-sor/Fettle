package com.tkisor.fettle.mixin.recipe;

import com.google.common.collect.Multimap;
import com.tkisor.fettle.config.HotFlags;
import com.tkisor.fettle.mixin.accessor.RecipeManagerAccessor;
import com.tkisor.fettle.recipe.RecipeIndex;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Recipe lookup fast path.
 *
 * <p>Vanilla iterates every recipe of a type and runs {@code matches} on each one. Fettle keeps that
 * iteration order and that final {@code matches} call, but walks a pre-compiled candidate bitset and
 * skips the recipes that provably cannot accept the given input.
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {

    @Unique
    private Multimap<RecipeType<?>, RecipeHolder<?>> fettle$indexedSource;

    @Unique
    private RecipeIndex fettle$index;

    @Unique
    private final ThreadLocal<long[]> fettle$scratch = ThreadLocal.withInitial(() -> new long[8]);

    @Inject(
            method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void fettle$fastGetRecipeFor(RecipeType<?> type, RecipeInput input, Level level,
                                         CallbackInfoReturnable<Optional<RecipeHolder<?>>> cir) {
        if (input.isEmpty()) {
            // Vanilla refuses an empty input before it looks at a single recipe, so the fast path must
            // not guess either: fall through and let vanilla return nothing. (Found by a review of the
            // shipped index, docs/fettle/26.)
            return;
        }
        RecipeIndex.TypeIndex index = fettle$typeIndex(type);
        if (index == null) {
            return; // not indexed -> let vanilla do its thing
        }

        long[] scratch = fettle$scratch.get();
        if (scratch.length < index.words()) {
            scratch = new long[index.words()];
            fettle$scratch.set(scratch);
        }
        long[] candidates = index.candidates(input, scratch);
        List<RecipeHolder<?>> ordered = index.ordered;

        for (int i = 0; i < ordered.size(); i++) {
            if ((candidates[i >>> 6] & (1L << (i & 63))) == 0) {
                continue;
            }
            RecipeHolder<?> holder = ordered.get(i);
            if (fettle$matches(holder, input, level)) {
                cir.setReturnValue(Optional.of(holder));
                return;
            }
        }
        cir.setReturnValue(Optional.empty());
    }

    @Inject(
            method = "getAllRecipesFor(Lnet/minecraft/world/item/crafting/RecipeType;)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void fettle$cachedGetAllRecipesFor(RecipeType<?> type, CallbackInfoReturnable<List<RecipeHolder<?>>> cir) {
        if (!HotFlags.CACHE_ALL_RECIPES) {
            return;
        }
        RecipeIndex.TypeIndex index = fettle$typeIndex(type);
        if (index == null) {
            return;
        }
        // Same elements, same order, immutable - so it is equal to vanilla's List.copyOf(byType(type)).
        cir.setReturnValue((List) index.ordered);
    }

    @Unique
    private RecipeIndex.TypeIndex fettle$typeIndex(RecipeType<?> type) {
        RecipeIndex current = fettle$currentIndex();
        return current == null ? null : current.type(type);
    }

    @Unique
    private RecipeIndex fettle$currentIndex() {
        if (!HotFlags.RECIPE_INDEX) {
            return null;
        }
        Multimap<RecipeType<?>, RecipeHolder<?>> source = ((RecipeManagerAccessor) this).fettle$byType();
        if (source == null) {
            return null;
        }
        if (source != fettle$indexedSource) {
            // Identity check doubles as invalidation: /reload replaces the multimap (and on the
            // client replaceRecipes swaps it in place), so a new instance always means "rebuild".
            fettle$indexedSource = source;
            fettle$index = RecipeIndex.build(source);
        }
        return fettle$index;
    }

    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean fettle$matches(RecipeHolder<?> holder, RecipeInput input, Level level) {
        // The final decision stays with vanilla: we only decided *which* recipes to ask.
        return ((Recipe) holder.value()).matches(input, level);
    }
}
