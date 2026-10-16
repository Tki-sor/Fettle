package com.tkisor.fettle.alg;

import com.tkisor.fettle.config.HotFlags;
import com.tkisor.fettle.util.ServerTickClock;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Per-tick memo of {@code Level#getBlockEntity(BlockPos)}.
 *
 * <p>Kinetic network bookkeeping validates every member with
 * {@code level.getBlockEntity(pos) != be} while summing stress/capacity, which turns an O(members)
 * arithmetic loop into O(members) chunk+section lookups. The same positions are re-validated for
 * every update in a tick, so caching the answer per tick removes nearly all of those lookups while
 * keeping the exact same comparison result.
 */
public final class LevelBlockEntityCache {

    private static final Long2ObjectOpenHashMap<BlockEntity> CACHE = new Long2ObjectOpenHashMap<>();
    private static long cachedTick = Long.MIN_VALUE;
    private static Level cachedLevel;

    private static long lookups;
    private static long hits;

    private LevelBlockEntityCache() {
    }

    public static void resetCounters() {
        lookups = 0;
        hits = 0;
    }

    public static String counters() {
        return String.format(java.util.Locale.ROOT, "realLookups=%d cacheHits=%d size=%d",
                lookups, hits, CACHE.size());
    }

    public static BlockEntity get(Level level, BlockPos pos) {
        if (!HotFlags.KINETIC_VALIDITY_CACHE) {
            lookups++;
            return level.getBlockEntity(pos);
        }
        long tick = ServerTickClock.tick();
        if (tick != cachedTick || level != cachedLevel) {
            CACHE.clear();
            cachedTick = tick;
            cachedLevel = level;
        }
        long key = pos.asLong();
        BlockEntity cached = CACHE.get(key);
        if (cached != null) {
            hits++;
            return cached;
        }
        lookups++;
        BlockEntity found = level.getBlockEntity(pos);
        CACHE.put(key, found == null ? null : found);
        return found;
    }
}
