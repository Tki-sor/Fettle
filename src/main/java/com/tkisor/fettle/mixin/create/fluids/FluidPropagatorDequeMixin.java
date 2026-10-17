package com.tkisor.fettle.mixin.create.fluids;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.tkisor.fettle.config.HotFlags;
import com.tkisor.fettle.util.PosDist;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two more instances of fluid finding F1: FluidPropagator's BFS loops both dequeue with
 * List.remove(0), which is an arraycopy of the remaining list per pop (O(n^2) over the search).
 * ArrayDeque.pollFirst() is FIFO as well, so the visit order, the discovered pumps and the result are
 * unchanged; only the shifting disappears.
 *
 * <p>These run on pipe edits and pressure recalculations rather than every tick, so this is about
 * removing a spike in large pipe networks, not about steady-state MSPT.
 */
@Mixin(value = FluidPropagator.class, remap = false, priority = 1100)
public abstract class FluidPropagatorDequeMixin {

    @Inject(method = "propagateChangedPipe", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fettle$dequePropagate(LevelAccessor world, BlockPos pipePos, BlockState pipeState,
                                              CallbackInfo ci) {
        if (!HotFlags.FLUID_PROPAGATOR_DEQUE) {
            return;
        }
        ArrayDeque<PosDist> frontier = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        Set<Pair<PumpBlockEntity, Direction>> discoveredPumps = new HashSet<>();
        frontier.addLast(new PosDist(pipePos, 0));

        while (!frontier.isEmpty()) {
            PosDist entry = frontier.pollFirst();
            BlockPos currentPos = entry.pos();
            if (visited.contains(currentPos)) {
                continue;
            }
            visited.add(currentPos);
            BlockState currentState = currentPos.equals(pipePos) ? pipeState : world.getBlockState(currentPos);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(world, currentPos);
            if (pipe == null) {
                continue;
            }
            pipe.wipePressure();

            for (Direction direction : FluidPropagator.getPipeConnections(currentState, pipe)) {
                BlockPos target = currentPos.relative(direction);
                if (world instanceof Level level && !level.isLoaded(target)) {
                    continue;
                }
                BlockEntity blockEntity = world.getBlockEntity(target);
                BlockState targetState = world.getBlockState(target);
                if (blockEntity instanceof PumpBlockEntity pump) {
                    if (!(targetState.getBlock() instanceof PumpBlock)
                            || targetState.getValue(PumpBlock.FACING).getAxis() != direction.getAxis()) {
                        continue;
                    }
                    discoveredPumps.add(Pair.of(pump, direction.getOpposite()));
                    continue;
                }
                if (visited.contains(target)) {
                    continue;
                }
                FluidTransportBehaviour targetPipe = FluidPropagator.getPipe(world, target);
                if (targetPipe == null) {
                    continue;
                }
                int distance = entry.distance();
                if (distance >= FluidPropagator.getPumpRange() && !targetPipe.hasAnyPressure()) {
                    continue;
                }
                if (targetPipe.canHaveFlowToward(targetState, direction.getOpposite())) {
                    frontier.addLast(new PosDist(target, distance + 1));
                }
            }
        }

        discoveredPumps.forEach(pair -> pair.getFirst().updatePipesOnSide(pair.getSecond()));
        ci.cancel();
    }

    @Inject(method = "resetAffectedFluidNetworks", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fettle$dequeResetNetworks(Level world, BlockPos start, Direction side, CallbackInfo ci) {
        if (!HotFlags.FLUID_PROPAGATOR_DEQUE) {
            return;
        }
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        frontier.addLast(start);

        while (!frontier.isEmpty()) {
            BlockPos pos = frontier.pollFirst();
            if (visited.contains(pos)) {
                continue;
            }
            visited.add(pos);
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(world, pos);
            if (pipe == null) {
                continue;
            }
            for (Direction d : Iterate.directions) {
                if (pos.equals(start) && d != side) {
                    continue;
                }
                BlockPos target = pos.relative(d);
                if (visited.contains(target)) {
                    continue;
                }
                PipeConnection connection = pipe.getConnection(d);
                if (connection == null || !connection.hasFlow()) {
                    continue;
                }
                if (!((PipeConnectionAccessor) connection).fettle$flow().get().inbound) {
                    continue;
                }
                connection.resetNetwork();
                frontier.addLast(target);
            }
        }
        ci.cancel();
    }
}
