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
