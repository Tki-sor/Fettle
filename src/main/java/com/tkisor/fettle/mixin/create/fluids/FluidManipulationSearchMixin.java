package com.tkisor.fettle.mixin.create.fluids;

import com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour;
import com.simibubi.create.foundation.fluid.FluidHelper;
import com.tkisor.fettle.config.HotFlags;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fluid finding F1 - FluidManipulationBehaviour#search.
 *
 * <p>Vanilla dequeues with frontier.remove(0), an arraycopy of the whole remaining list: O(n) per pop
 * and O(n^2) per search, called every tick by the hose pulley with a cap of 1024 pops. An ArrayDeque
 * dequeue is O(1); measured on its own that is 519x faster for a 50000-entry frontier
 * (docs/fettle/23-fluid-search-deque.md).
 *
 * <p>Both structures are FIFO, so traversal order, the result and the order of the leftover entries are
 * unchanged. The frontier is a parameter the caller keeps and whose emptiness it uses as its
 * "search finished" flag, so leftovers are written back into that same list, on the abort path too.
 */
@Mixin(value = FluidManipulationBehaviour.class, remap = false, priority = 1100)
public abstract class FluidManipulationSearchMixin {

    /** searchedPerTick is a compile-time constant, so it cannot be shadowed. */
    @Unique
    private static final int FETTLE_SEARCHED_PER_TICK = 1024;

    @Unique
    private final ArrayDeque<FluidManipulationBehaviour.BlockPosEntry> fettle$queue = new ArrayDeque<>();

    @Inject(method = "search(Lnet/minecraft/world/level/material/Fluid;Ljava/util/List;Ljava/util/Set;Ljava/util/function/BiConsumer;Z)Lnet/minecraft/world/level/material/Fluid;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void fettle$dequeSearch(Fluid fluid, List<FluidManipulationBehaviour.BlockPosEntry> frontier,
                                    Set<BlockPos> visited, BiConsumer<BlockPos, Integer> add,
                                    boolean searchDownward, CallbackInfoReturnable<Fluid> cir)
            throws FluidManipulationBehaviour.ChunkNotLoadedException {
        if (!HotFlags.FLUID_SEARCH_DEQUE) {
            return;
        }
        FluidManipulationBehaviour self = (FluidManipulationBehaviour) (Object) this;
        FluidManipulationAccessor access = (FluidManipulationAccessor) (Object) this;
        Level world = self.getWorld();
        BlockPos rootPos = access.fettle$rootPos();
        int maxBlocks = access.fettle$maxBlocks();
        int maxRange = access.fettle$maxRange();
        int maxRangeSq = maxRange * maxRange;

        ArrayDeque<FluidManipulationBehaviour.BlockPosEntry> queue = fettle$queue;
        queue.clear();
        if (!frontier.isEmpty()) {
            queue.addAll(frontier);
            frontier.clear();
        }

        try {
            for (int i = 0; i < FETTLE_SEARCHED_PER_TICK && !queue.isEmpty()
                    && (visited.size() <= maxBlocks || !access.fettle$canDrainInfinitely(fluid)); i++) {

                FluidManipulationBehaviour.BlockPosEntry entry = queue.pollFirst();
                BlockPos currentPos = entry.pos();
                if (visited.contains(currentPos)) {
                    continue;
                }
                visited.add(currentPos);

                if (!world.isLoaded(currentPos)) {
                    throw new FluidManipulationBehaviour.ChunkNotLoadedException();
                }

                FluidState fluidState = world.getFluidState(currentPos);
                if (fluidState.isEmpty()) {
                    continue;
                }

                Fluid currentFluid = FluidHelper.convertToStill(fluidState.getType());
                if (fluid == null) {
                    fluid = currentFluid;
                }
                if (!currentFluid.isSame(fluid)) {
                    continue;
                }

                add.accept(currentPos, entry.distance());

                for (Direction side : Iterate.directions) {
                    if (!searchDownward && side == Direction.DOWN) {
                        continue;
                    }
                    BlockPos offsetPos = currentPos.relative(side);
                    if (!world.isLoaded(offsetPos)) {
                        throw new FluidManipulationBehaviour.ChunkNotLoadedException();
                    }
                    if (visited.contains(offsetPos)) {
                        continue;
                    }
                    if (offsetPos.distSqr(rootPos) > maxRangeSq) {
                        continue;
                    }
                    FluidState nextFluidState = world.getFluidState(offsetPos);
                    if (nextFluidState.isEmpty()) {
                        continue;
                    }
                    Fluid nextFluid = nextFluidState.getType();
                    if (nextFluid == FluidHelper.convertToFlowing(nextFluid) && side == Direction.UP
                            && !VecHelper.onSameAxis(rootPos, offsetPos, Axis.Y)) {
                        continue;
                    }
                    queue.addLast(new FluidManipulationBehaviour.BlockPosEntry(offsetPos, entry.distance() + 1));
                }
            }
        } finally {
            if (!queue.isEmpty()) {
                frontier.addAll(queue);
                queue.clear();
            }
        }

        cir.setReturnValue(fluid);
    }
}
