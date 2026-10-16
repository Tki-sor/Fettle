package com.tkisor.fettle.mixin.accessor;

import com.google.common.collect.Multimap;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the private per-type recipe multimap so the index can be rebuilt from the real source. */
@Mixin(RecipeManager.class)
public interface RecipeManagerAccessor {

    @Accessor("byType")
    Multimap<RecipeType<?>, RecipeHolder<?>> fettle$byType();
}
