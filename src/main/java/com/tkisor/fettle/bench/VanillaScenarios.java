package com.tkisor.fettle.bench;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Scenes built from vanilla blocks, used to exercise the sleeping pilot on non-Create block entities. */
public final class VanillaScenarios {

    private VanillaScenarios() {
    }

    /** Places a grid of empty hoppers: they tick forever, but change nothing while nothing moves. */
    public static String placeHoppers(ServerLevel level, BlockPos origin, int side, int spacing) {
        int placed = 0;
        for (int i = 0; i < side * side; i++) {
            BlockPos pos = origin.offset((i % side) * spacing, 0, (i / side) * spacing);
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            level.getChunk(pos);
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            if (level.setBlock(pos, Blocks.HOPPER.defaultBlockState(), 3)) {
                placed++;
            }
        }
        return String.format(Locale.ROOT, "placed %d hoppers at %s", placed, origin.toShortString());
    }
}
