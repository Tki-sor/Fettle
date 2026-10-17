package com.tkisor.fettle.bench;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * World scenarios that are impractical to build with thousands of RCON commands.
 *
 * <p>Panels placed here survive only because {@code -Dfettle.debug.panels=true} disables Create's
 * "delete panels with no active slot" self-cleanup (see {@code FactoryPanelDebugMixin}); otherwise a
 * scripted gauge removes itself within seconds.
 *
 * <p>Called reflectively so this class only loads when Create is installed.
 */
public final class CreateDebugScenarios {

    private CreateDebugScenarios() {
    }

    /**
     * Drives the real FluidManipulationBehaviour#search on a real fluid body, so the F1 change can be
     * A/B tested without depending on a rig that actually drains. The frontier is seeded with the first
     * fluid block below the block entity (a hose pulley), the visited set is fresh per call, so every
     * call does exactly the same work and only the queue implementation differs.
     */
    public static String fluidSearchBench(ServerLevel level, BlockPos pos, int iterations) {
        var be = level.getBlockEntity(pos);
        if (!(be instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart)) {
            return "fluidSearchBench: no SmartBlockEntity at " + pos.toShortString();
        }
        java.util.List<com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour> found =
                new java.util.ArrayList<>();
        smart.forEachBehaviour(behaviour -> {
            if (behaviour instanceof com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour fluid) {
                found.add(fluid);
            }
        });
        if (found.isEmpty()) {
            return "fluidSearchBench: no FluidManipulationBehaviour on " + be.getClass().getSimpleName();
        }
        BlockPos seed = null;
        for (int down = 1; down <= 8; down++) {
            BlockPos candidate = pos.below(down);
            if (!level.getFluidState(candidate).isEmpty()) {
                seed = candidate;
                break;
            }
        }
        if (seed == null) {
            return "fluidSearchBench: no fluid within 8 blocks below " + pos.toShortString();
        }
        java.lang.reflect.Method search;
        try {
            search = com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour.class
                    .getDeclaredMethod("search", net.minecraft.world.level.material.Fluid.class, java.util.List.class,
                            java.util.Set.class, java.util.function.BiConsumer.class, boolean.class);
            search.setAccessible(true);
        } catch (Throwable t) {
            return "fluidSearchBench: cannot resolve search: " + t;
        }
        boolean rootWasNull = false;
        try {
            var access = (com.tkisor.fettle.mixin.create.fluids.FluidManipulationAccessor) found.get(0);
            if (access.fettle$rootPos() == null) {
                access.fettle$setRootPos(pos);
                rootWasNull = true;
            }
        } catch (Throwable t) {
            return "fluidSearchBench: cannot set rootPos: " + t;
        }
        int[] accepted = new int[1];
        java.util.function.BiConsumer<BlockPos, Integer> add = (p, distance) -> accepted[0]++;
        int runs = Math.max(1, Math.min(iterations, 500));
        long first = 0L;
        long total = 0L;
        int lastAccepted = 0;
        int leftover = 0;
        long hashOfLast = 0L;
        long hashAll = 0xCBF29CE484222325L;
        for (int i = 0; i < runs; i++) {
            java.util.List<com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour.BlockPosEntry> frontier =
                    new java.util.ArrayList<>();
            frontier.add(new com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour.BlockPosEntry(seed, 0));
            java.util.Set<BlockPos> visited = new java.util.HashSet<>();
            accepted[0] = 0;
            long start = System.nanoTime();
            try {
                search.invoke(found.get(0), null, frontier, visited, add, true);
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                return "fluidSearchBench: call failed: " + cause;
            }
            long elapsed = System.nanoTime() - start;
            // Parity check: whatever is left in the frontier must be identical in order and content,
            // because the caller uses its emptiness as "search finished" and resumes from it next tick.
            long hash = 0xCBF29CE484222325L;
            for (var entry : frontier) {
                hash ^= entry.pos().asLong();
                hash *= 0x100000001B3L;
                hash ^= entry.distance();
                hash *= 0x100000001B3L;
            }
            leftover = frontier.size();
            hashOfLast = hash;
            hashAll ^= hash;
            hashAll *= 0x100000001B3L;
            if (i == 0) {
                first = elapsed;
            } else {
                total += elapsed;
            }
            lastAccepted = accepted[0];
        }
        double averageUs = total / (double) Math.max(1, runs - 1) / 1000.0;
        return String.format(Locale.ROOT,
                "fluidSearchBench: %d calls, avg=%.1f us/call, first=%.1f us, accepted=%d, leftover=%d, hash=0x%016x, hashAll=0x%016x, seed=%s, rootWasNull=%s, dequeFlag=%s",
                runs, averageUs, first / 1000.0, lastAccepted, leftover, hashOfLast, hashAll,
                seed.toShortString(), rootWasNull, com.tkisor.fettle.config.HotFlags.FLUID_SEARCH_DEQUE);
    }

    /** Reports what is actually at a position: block, block entity, kinetic speed and tank contents. */
    public static String fluidCheck(ServerLevel level, BlockPos pos) {
        StringBuilder sb = new StringBuilder();
        var state = level.getBlockState(pos);
        sb.append(state.getBlock()).append(" at ").append(pos.toShortString());
        var be = level.getBlockEntity(pos);
        if (be == null) {
            return sb.append(" be=none").toString();
        }
        sb.append(" be=").append(be.getClass().getSimpleName());
        if (be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic) {
            sb.append(" speed=").append(String.format(Locale.ROOT, "%.1f", kinetic.getSpeed()));
        }
        try {
            var handler = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
                    pos, null);
            if (handler == null) {
                sb.append(" tank=none");
            } else {
                for (int i = 0; i < handler.getTanks(); i++) {
                    sb.append(" tank").append(i).append('=').append(handler.getFluidInTank(i).getFluid())
                            .append(':').append(handler.getFluidInTank(i).getAmount())
                            .append('/').append(handler.getTankCapacity(i));
                }
            }
        } catch (Throwable t) {
            sb.append(" tankErr=").append(t);
        }
        return sb.toString();
    }

    /** A flat grid of fluid pipes, all connected to their neighbours - a large fluid network. */
    public static String placePipeNetwork(ServerLevel level, BlockPos origin, int radius) {
        int r = Math.max(2, Math.min(radius, 24));
        var pipeState = com.simibubi.create.AllBlocks.FLUID_PIPE.get().defaultBlockState();
        int placed = 0;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                BlockPos pos = origin.offset(x, 0, z);
                level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
                level.getChunk(pos);
                if (level.setBlock(pos, pipeState, 3)) {
                    placed++;
                }
            }
        }
        return String.format(Locale.ROOT, "pipeNetwork r=%d pipes=%d at %s", r, placed, origin.toShortString());
    }

    /**
     * Times the real FluidPropagator#propagateChangedPipe over a pipe network and hashes the observable
     * network state (per connection: present / has flow / has network, plus which pipes ended up without
     * pressure). Both queue implementations must produce the same traversal, hence the same hash.
     */
    public static String fluidPropagateBench(ServerLevel level, BlockPos pos, int iterations) {
        var state = level.getBlockState(pos);
        java.lang.reflect.Method propagate;
        try {
            propagate = com.simibubi.create.content.fluids.FluidPropagator.class.getDeclaredMethod(
                    "propagateChangedPipe", net.minecraft.world.level.LevelAccessor.class, BlockPos.class,
                    net.minecraft.world.level.block.state.BlockState.class);
            propagate.setAccessible(true);
        } catch (Throwable t) {
            return "fluidPropagate: cannot resolve propagateChangedPipe: " + t;
        }
        int runs = Math.max(1, Math.min(iterations, 200));
        long total = 0L;
        long first = 0L;
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            try {
                propagate.invoke(null, level, pos, state);
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                return "fluidPropagate: call failed: " + cause;
            }
            long elapsed = System.nanoTime() - start;
            if (i == 0) {
                first = elapsed;
            } else {
                total += elapsed;
            }
        }
        long[] stats = new long[3];
        hashPipeNetwork(level, pos, stats);
        return String.format(Locale.ROOT,
                "fluidPropagateBench: %d calls, avg=%.1f us/call, first=%.1f us, pipes=%d, noPressure=%d, hash=0x%016x, dequeFlag=%s",
                runs, total / (double) Math.max(1, runs - 1) / 1000.0, first / 1000.0, stats[0], stats[1], stats[2],
                com.tkisor.fettle.config.HotFlags.FLUID_PROPAGATOR_DEQUE);
    }

    /** Walks the pipe network with public APIs and folds its observable state into one hash. */
    private static void hashPipeNetwork(ServerLevel level, BlockPos start, long[] out) {
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        queue.addLast(start);
        long hash = 0xCBF29CE484222325L;
        long pipes = 0L;
        long noPressure = 0L;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.pollFirst();
            if (!seen.add(pos)) {
                continue;
            }
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(level, pos);
            if (pipe == null) {
                continue;
            }
            pipes++;
            hash ^= pos.asLong();
            hash *= 0x100000001B3L;
            boolean pressure = pipe.hasAnyPressure();
            if (!pressure) {
                noPressure++;
            }
            hash ^= pressure ? 1L : 0L;
            hash *= 0x100000001B3L;
            for (Direction direction : net.createmod.catnip.data.Iterate.directions) {
                var connection = pipe.getConnection(direction);
                boolean present = connection != null;
                boolean flow = present && connection.hasFlow();
                boolean network = present && connection.hasNetwork();
                hash ^= (present ? 1L : 0L) | (flow ? 2L : 0L) | (network ? 4L : 0L);
                hash *= 0x100000001B3L;
                BlockPos next = pos.relative(direction);
                if (present && !seen.contains(next)
                        && com.simibubi.create.content.fluids.FluidPropagator.getPipe(level, next) != null) {
                    queue.addLast(next);
                }
            }
        }
        out[0] = pipes;
        out[1] = noPressure;
        out[2] = hash;
    }

    /** Places {@code count} shafts in a line so they merge into one kinetic network. */
    public static String placeShaftLine(ServerLevel level, BlockPos origin, int count, int y) {
        BlockState shaft = AllBlocks.SHAFT.get()
                .defaultBlockState()
                .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                        net.minecraft.core.Direction.Axis.X);
        // A rotation source is required: without one the kinetic block entities never attach and the
        // networks stay empty, so there would be nothing to measure.
        BlockPos motorPos = origin.offset(-1, 0, 0);
        level.setChunkForced(motorPos.getX() >> 4, motorPos.getZ() >> 4, true);
        level.getChunk(motorPos);
        level.setBlock(motorPos.offset(0, -1, 0), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        BlockState motor = AllBlocks.CREATIVE_MOTOR.get()
                .defaultBlockState()
                .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING,
                        net.minecraft.core.Direction.EAST);
        level.setBlock(motorPos, motor, 3);
        level.updateNeighborsAt(motorPos, motor.getBlock());

        int placed = 0;
        for (int i = 0; i < count; i++) {
            BlockPos pos = origin.offset(i, 0, 0);
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            level.getChunk(pos);
            level.setBlock(pos.offset(0, -1, 0), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            // flag 3 so the kinetic block entity is created and the propagation runs
            level.setBlock(pos, shaft, 3);
            level.updateNeighborsAt(pos, shaft.getBlock());
            level.updateNeighborsAt(pos.offset(0, -1, 0), shaft.getBlock());
            placed++;
        }
        return String.format(Locale.ROOT, "placed %d shafts at %s", placed, origin.toShortString());
    }

    /**
     * Places a grid of packagers with a factory gauge attached to each, so the panels are part of a
     * real logistics setup. A scripted gauge whose {@code activePanels()} is zero erodes itself away in
     * {@code FactoryPanelBlockEntity#lazyTick} - linking each gauge to a packager keeps it alive and
     * ticking through the normal code path, with no debug flag involved.
     */
    public static String placePanelPackagers(ServerLevel level, BlockPos origin, int side, int spacing) {
        BlockState packager = AllBlocks.PACKAGER.get().defaultBlockState();
        BlockState panel = AllBlocks.FACTORY_GAUGE.get()
                .defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.SOUTH)
                .setValue(FactoryPanelBlock.WATERLOGGED, false);

        int placed = 0;
        for (int i = 0; i < side * side; i++) {
            int x = origin.getX() + (i % side) * spacing;
            int z = origin.getZ() + (i / side) * spacing;
            BlockPos packagerPos = new BlockPos(x, origin.getY(), z);
            level.setChunkForced(packagerPos.getX() >> 4, packagerPos.getZ() >> 4, true);
            level.getChunk(packagerPos);
            level.setBlock(packagerPos, packager, 3);
            if (level.setBlock(packagerPos.south(), panel, 3)) {
                placed++;
            }
        }
        return String.format(Locale.ROOT, "placed %d packagers with factory gauges at %s",
                placed, origin.toShortString());
    }

    /**
     * Places the panel grid and then wires each panel the way the in-game UI does: the behaviour is
     * marked active and given a symmetric connection to a neighbouring panel. That satisfies Create's
     * own liveness check ({@code FactoryPanelBlockEntity#lazyTick} erases a panel whose
     * {@code activePanels()} is zero), so the panels tick through the normal code path with no debug
     * scaffolding in the tick itself - unlike {@code debugPanelKeepAlive}, which cancels that check.
     */
    public static String wirePanelsAlive(ServerLevel level, BlockPos origin, int side, int spacing) {
        placePanels(level, origin, side, spacing);
        int wired = 0;
        try {
            java.lang.reflect.Field panelsField =
                    com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity.class
                            .getDeclaredField("panels");
            panelsField.setAccessible(true);
            for (int i = 0; i < side * side; i++) {
                int gx = i % side;
                int x = origin.getX() + gx * spacing;
                int z = origin.getZ() + (i / side) * spacing;
                BlockPos panelPos = new BlockPos(x, origin.getY(), z).south();
                BlockPos neighbour = new BlockPos(origin.getX() + (gx == 0 ? 1 : gx - 1) * spacing,
                        origin.getY(), z).south();
                if (!(level.getBlockEntity(panelPos)
                        instanceof com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity be)) {
                    continue;
                }
                Object value = panelsField.get(be);
                if (!(value instanceof java.util.Map<?, ?> panels)) {
                    continue;
                }
                for (Object entry : panels.values()) {
                    if (!(entry
                            instanceof com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour behaviour)) {
                        continue;
                    }
                    behaviour.active = true;
                    var from = new com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition(
                            be.getBlockPos(), behaviour.slot);
                    var to = new com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition(
                            neighbour, behaviour.slot);
                    behaviour.targeting.add(to);
                    behaviour.targetedBy.put(to,
                            new com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection(to, 0));
                    wired++;
                }
                be.setChanged();
                be.notifyUpdate();
            }
        } catch (Throwable t) {
            return "panel wiring failed: " + t;
        }
        return String.format(Locale.ROOT, "placed and wired %d panels at %s", wired, origin.toShortString());
    }

    /**
     * Builds a working fluid scene for F1: stone floor, a water body, a hose pulley just above the
     * surface, a pipe + mechanical pump + tank above it and creative motors for the kinetic parts.
     * Everything is placed through Level#setBlock, so it does not depend on vanilla placement rules
     * (a scripted hose pulley is rejected by them).
     */
    public static String buildFluidScene(ServerLevel level, BlockPos origin, int radius) {
        int r = Math.max(2, Math.min(radius, 24));
        int baseY = origin.getY();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        int floor = 0;
        int filled = 0;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                BlockPos floorPos = origin.offset(x, 0, z);
                level.setChunkForced(floorPos.getX() >> 4, floorPos.getZ() >> 4, true);
                level.getChunk(floorPos);
                if (level.setBlock(floorPos, stone, 3)) {
                    floor++;
                }
                for (int y = 1; y <= 8; y++) {
                    if (level.setBlock(origin.offset(x, y, z), water, 3)) {
                        filled++;
                    }
                }
            }
        }
        BlockPos pulleyPos = origin.above(9);
        boolean pulley = level.setBlock(pulleyPos, com.simibubi.create.AllBlocks.HOSE_PULLEY.get()
                .defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, Direction.NORTH), 3);
        BlockPos pipePos = origin.above(10);
        boolean pipe = level.setBlock(pipePos, com.simibubi.create.AllBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        BlockPos pumpPos = origin.above(11);
        boolean pump = level.setBlock(pumpPos, com.simibubi.create.AllBlocks.MECHANICAL_PUMP.get()
                .defaultBlockState()
                .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.UP), 3);
        // the pump's shaft runs along its facing axis, so its motor has to sit above it, coaxial
        boolean motor = level.setBlock(origin.above(12),
                com.simibubi.create.AllBlocks.CREATIVE_MOTOR.get().defaultBlockState()
                        .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.DOWN), 3);
        // the pulley's shaft is horizontal; one of these four is coaxial with it
        int pulleyMotors = 0;
        for (Direction side : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (level.setBlock(pulleyPos.relative(side),
                    com.simibubi.create.AllBlocks.CREATIVE_MOTOR.get().defaultBlockState()
                            .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, side.getOpposite()), 3)) {
                pulleyMotors++;
            }
        }
        boolean tank = level.setBlock(origin.above(13), com.simibubi.create.AllBlocks.FLUID_TANK.get().defaultBlockState(), 3);
        Object pulleyBe = level.getBlockEntity(pulleyPos);
        return String.format(Locale.ROOT,
                "fluidScene r=%d floor=%d water=%d pulley=%s(be=%s) pipe=%s pump=%s motor=%s pulleyMotors=%d tank=%s at %s",
                r, floor, filled, pulley, pulleyBe == null ? "none" : pulleyBe.getClass().getSimpleName(),
                pipe, pump, motor, pulleyMotors, tank, pulleyPos.toShortString());
    }
    public static String placePanels(ServerLevel level, BlockPos origin, int side, int spacing) {
        BlockState support = Blocks.STONE.defaultBlockState();
        BlockState panel = AllBlocks.FACTORY_GAUGE.get()
                .defaultBlockState()
                .setValue(FactoryPanelBlock.FACE, AttachFace.WALL)
                .setValue(FactoryPanelBlock.FACING, Direction.SOUTH)
                .setValue(FactoryPanelBlock.WATERLOGGED, false);

        int placed = 0;
        for (int i = 0; i < side * side; i++) {
            int x = origin.getX() + (i % side) * spacing;
            int z = origin.getZ() + (i / side) * spacing;
            BlockPos supportPos = new BlockPos(x, origin.getY(), z);
            // A chunk loaded by getChunk() does not tick; it must be forced so the block entities
            // actually tick during the measurement.
            level.setChunkForced(supportPos.getX() >> 4, supportPos.getZ() >> 4, true);
            level.getChunk(supportPos);
            level.setBlock(supportPos, support, 2);
            if (level.setBlock(supportPos.south(), panel, 3)) {
                placed++;
            }
        }
        return String.format(Locale.ROOT, "placed %d factory panels at %s (keep-alive=%s)",
                placed, origin.toShortString(),
                com.tkisor.fettle.config.HotFlags.DEBUG_PANEL_KEEP_ALIVE);
    }
}
