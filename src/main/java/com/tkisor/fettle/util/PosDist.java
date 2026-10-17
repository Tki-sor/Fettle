package com.tkisor.fettle.util;

import net.minecraft.core.BlockPos;

/** A position with its BFS distance, without the Integer boxing of Pair&lt;Integer, BlockPos&gt;. */
public record PosDist(BlockPos pos, int distance) {
}
