package com.tkisor.fettle.mixin.create.fluids;

import com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to the members {@code FluidManipulationBehaviour#search} needs. {@code rootPos} is
 * package-private, which a class mixin from another package cannot {@code @Shadow} - a mixin apply
 * failure of exactly that kind is why the first attempt at F1 was rolled back (docs/fettle/23).
 * Accessors and invokers are generated inside the target class, so visibility is not an issue.
 */
@Mixin(value = FluidManipulationBehaviour.class, remap = false)
public interface FluidManipulationAccessor {

    @Accessor("rootPos")
    BlockPos fettle$rootPos();

    @Invoker("maxBlocks")
    int fettle$maxBlocks();

    @Invoker("maxRange")
    int fettle$maxRange();

    @Invoker("canDrainInfinitely")
    boolean fettle$canDrainInfinitely(Fluid fluid);
}
