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
        boolean motor = level.setBlock(origin.above(11).east(),
                com.simibubi.create.AllBlocks.CREATIVE_MOTOR.get().defaultBlockState(), 3);
        boolean tank = level.setBlock(origin.above(12), com.simibubi.create.AllBlocks.FLUID_TANK.get().defaultBlockState(), 3);
        Object pulleyBe = level.getBlockEntity(pulleyPos);
        return String.format(Locale.ROOT,
                "fluidScene r=%d floor=%d water=%d pulley=%s(be=%s) pipe=%s pump=%s motor=%s tank=%s at %s",
                r, floor, filled, pulley, pulleyBe == null ? "none" : pulleyBe.getClass().getSimpleName(),
                pipe, pump, motor, tank, pulleyPos.toShortString());
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
