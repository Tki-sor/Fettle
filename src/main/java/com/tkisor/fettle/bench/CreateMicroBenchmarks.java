package com.tkisor.fettle.bench;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.tkisor.fettle.config.HotFlags;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.phys.Vec3;

/**
 * Micro benchmarks for Create / vanilla paths that a scripted world cannot reach.
 *
 * <p>Called reflectively from {@code /fettle micro ...} so this class - which links against Create
 * and against game classes - only loads when it is actually used.
 *
 * <p>Each benchmark runs the same loop with the feature switched on and off via
 * {@code /fettle feature ...}, inside one JVM, which removes JIT and machine drift.
 */
public final class CreateMicroBenchmarks {

    private CreateMicroBenchmarks() {
    }

    public static String run(String name, ServerLevel level, int iterations) {
        if ("matchAddress".equals(name)) {
            return matchAddress(iterations);
        }
        if ("trackGraphMiss".equals(name)) {
            return trackGraphMiss(iterations);
        }
        if ("getRecipeFor".equals(name)) {
            return getRecipeFor(level, iterations);
        }
        if ("travelPointPosition".equals(name)) {
            return travelPointPosition(iterations);
        }
        if ("collisionFilter".equals(name)) {
            return collisionFilter();
        }
        if ("kineticCapacity".equals(name)) {
            return kineticCapacity(level, iterations);
        }
        if ("kineticStress".equals(name)) {
            return kineticStress(level, iterations);
        }
        if ("beltShaft".equals(name)) {
            return beltShaft(level, iterations);
        }
        if ("fluidSearchQueue".equals(name)) {
            return fluidSearchQueue(iterations);
        }
        if ("mountedStorageTick".equals(name)) {
            return mountedStorageTick(iterations);
        }
        if ("smartBETick".equals(name)) {
            return smartBETick(level, iterations);
        }
        return "unknown micro benchmark: " + name;
    }

    /**
     * F1 data-structure comparison: drain a frontier of {@code iterations} entries, once with
     * {@code ArrayList.remove(0)} (vanilla) and once with {@code ArrayDeque.pollFirst()}. Both are FIFO,
     * so this isolates exactly the cost the mixin removes.
     */
    private static String fluidSearchQueue(int iterations) {
        int n = Math.min(iterations, 400_000);
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(i);
        }
        long start = System.nanoTime();
        while (!list.isEmpty()) {
            list.remove(0);
        }
        long listNanos = System.nanoTime() - start;

        java.util.ArrayDeque<Integer> deque = new java.util.ArrayDeque<>(n);
        for (int i = 0; i < n; i++) {
            deque.addLast(i);
        }
        start = System.nanoTime();
        while (!deque.isEmpty()) {
            deque.pollFirst();
        }
        long dequeNanos = System.nanoTime() - start;

        return String.format(Locale.ROOT,
                "fluidSearchQueue: n=%d ArrayList.remove(0)=%.2f ms  ArrayDeque.poll=%.3f ms  speedup=%.0fx",
                n, listNanos / 1e6, dequeNanos / 1e6, (double) listNanos / Math.max(1L, dequeNanos));
    }

    /** Allocated bytes of the calling thread, or -1 when the JVM does not expose it. */
    private static long allocatedBytes() {
        try {
            com.sun.management.ThreadMXBean bean =
                    (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * ① {@code MountedStorageManager#tick}: a contraption without synced item/fluid storage makes the
     * vanilla path allocate two HashMaps and do nothing else, so the fast path returns first. The
     * entity argument is never dereferenced on that path, hence {@code null}.
     */
    private static String mountedStorageTick(int iterations) {
        com.simibubi.create.content.contraptions.MountedStorageManager manager =
                new com.simibubi.create.content.contraptions.MountedStorageManager();
        try {
            // The real thing is filled in by AbstractContraptionEntity#initialize; on a bare manager the
            // two immutable maps are null, so stand them in as empty - that is the case under study.
            for (String fieldName : new String[] {"syncedItems", "syncedFluids"}) {
                java.lang.reflect.Field field =
                        com.simibubi.create.content.contraptions.MountedStorageManager.class
                                .getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(manager, com.google.common.collect.ImmutableMap.of());
            }
        } catch (Throwable t) {
            return "mountedStorageTick: could not prepare the manager: " + t;
        }
        for (int i = 0; i < 20_000; i++) {
            manager.tick(null);
        }
        long bytesBefore = allocatedBytes();
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            manager.tick(null);
        }
        long elapsed = System.nanoTime() - start;
        long bytes = allocatedBytes();
        if (bytes < 0 || bytesBefore < 0) {
            return String.format(Locale.ROOT, "mountedStorageTick: %.2f ns/op (%d iterations)",
                    (double) elapsed / iterations, iterations);
        }
        return String.format(Locale.ROOT, "mountedStorageTick: %.2f ns/op, %.1f B/op (%d iterations)",
                (double) elapsed / iterations, (double) (bytes - bytesBefore) / iterations, iterations);
    }

    /**
     * ② {@code SmartBlockEntity#tick} on a real block entity in a forced chunk: reports the per-tick
     * allocation so we can tell whether the lambda/iterator it creates is actually materialised.
     */
    private static String smartBETick(ServerLevel level, int iterations) {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(12, 100, 12);
        level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        level.getChunk(pos);
        level.setBlock(pos, com.simibubi.create.AllBlocks.SHAFT.get().defaultBlockState(), 3);
        if (!(level.getBlockEntity(pos)
                instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart)) {
            return "smartBETick: no SmartBlockEntity at " + pos.toShortString();
        }
        for (int i = 0; i < 20_000; i++) {
            smart.tick();
        }
        long bytesBefore = allocatedBytes();
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            smart.tick();
        }
        long elapsed = System.nanoTime() - start;
        long bytes = allocatedBytes();
        if (bytes < 0 || bytesBefore < 0) {
            return String.format(Locale.ROOT, "smartBETick: %.2f ns/op (%d iterations)",
                    (double) elapsed / iterations, iterations);
        }
        return String.format(Locale.ROOT, "smartBETick: %.2f ns/op, %.1f B/op (%d iterations)",
                (double) elapsed / iterations, (double) (bytes - bytesBefore) / iterations, iterations);
    }

    /**
     * A8: {@code BeltBlock#hasShaftTowards} on a real belt block placed by this benchmark. Vanilla
     * builds an {@code Optional} for the block-entity lookup, the Fettle path is an {@code instanceof}.
     *
     * <p>That mixin is an {@code @Overwrite} selected at load time, so this one is A/B'd by flipping
     * {@code create.beltShaftLookup} in the config and restarting, not with {@code /fettle feature}.
     */
    private static String beltShaft(ServerLevel level, int iterations) {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(8, 100, 8);
        level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        level.getChunk(pos);
        net.minecraft.world.level.block.state.BlockState belt =
                com.simibubi.create.AllBlocks.BELT.get().defaultBlockState()
                        .setValue(com.simibubi.create.content.kinetics.belt.BeltBlock.SLOPE,
                                com.simibubi.create.content.kinetics.belt.BeltSlope.HORIZONTAL)
                        .setValue(com.simibubi.create.content.kinetics.belt.BeltBlock.PART,
                                com.simibubi.create.content.kinetics.belt.BeltPart.START)
                        .setValue(com.simibubi.create.content.kinetics.belt.BeltBlock.CASING, false)
                        .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                                net.minecraft.core.Direction.EAST);
        level.setBlock(pos, belt, 3);
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof com.simibubi.create.content.kinetics.belt.BeltBlock beltBlock)) {
            return "beltShaft: belt block was not placed at " + pos.toShortString();
        }
        net.minecraft.core.Direction face = net.minecraft.core.Direction.EAST;
        for (int i = 0; i < 2_000; i++) {
            beltBlock.hasShaftTowards(level, pos, state, face);
        }
        long start = System.nanoTime();
        boolean result = false;
        for (int i = 0; i < iterations; i++) {
            result = beltBlock.hasShaftTowards(level, pos, state, face);
        }
        long elapsed = System.nanoTime() - start;
        return String.format(Locale.ROOT, "beltShaft: %.2f ns/op (%d iterations) blockEntity=%s result=%s",
                (double) elapsed / iterations, iterations, level.getBlockEntity(pos) != null, result);
    }

    /**
     * Models the train collision workload: the same travelling point is asked for its world position
     * many times within one tick (O(T^2 * C) in {@code Train#findCollidingTrain}).
     */
    private static String travelPointPosition(int iterations) {
        TrackNodeLocation locationA = new TrackNodeLocation(0, 0, 0).in(net.minecraft.world.level.Level.OVERWORLD);
        TrackNodeLocation locationB = new TrackNodeLocation(12, 0, 0).in(net.minecraft.world.level.Level.OVERWORLD);
        TrackNode nodeA = new TrackNode(locationA, 1, Vec3.ZERO);
        TrackNode nodeB = new TrackNode(locationB, 2, Vec3.ZERO);
        TrackEdge edge = new TrackEdge(nodeA, nodeB, null, null);
        com.simibubi.create.content.trains.entity.TravellingPoint point =
                new com.simibubi.create.content.trains.entity.TravellingPoint(nodeA, nodeB, edge, 3.0, false);
        TrackGraph graph = new TrackGraph();

        for (int i = 0; i < 20_000; i++) {
            point.getPosition(graph);
        }

        long start = System.nanoTime();
        Vec3 last = Vec3.ZERO;
        for (int i = 0; i < iterations; i++) {
            last = point.getPosition(graph);
        }
        long elapsed = System.nanoTime() - start;
        return String.format(Locale.ROOT,
                "travelPointPosition: iters=%d ns/op=%.1f x=%.3f cache=%s",
                iterations, (double) elapsed / iterations, last.x, HotFlags.TRAVEL_POSITION_CACHE);
    }

    /**
     * Algorithm prototype for the train collision check: the vanilla loop compares every carriage of
     * every train against the query point (O(T*C) per query, O(T^2*C) per tick). A conservative grid
     * over the carriage positions visits only the neighbouring cells.
     *
     * <p>Both paths count the points inside the radius so the two candidate sets can be compared.
     */
    private static String collisionFilter() {
        int trains = 200;
        int perTrain = 4;
        double spacing = 40.0;
        double maxDistance = 32.0;
        int total = trains * perTrain;

        double[] xs = new double[total];
        double[] ys = new double[total];
        double[] zs = new double[total];
        int index = 0;
        for (int train = 0; train < trains; train++) {
            for (int carriage = 0; carriage < perTrain; carriage++) {
                xs[index] = train * spacing + carriage * 3.0;
                ys[index] = 64.0;
                zs[index] = 0.0;
                index++;
            }
        }

        double radiusSqr = maxDistance * maxDistance;
        long naivePairs = 0;
        long naiveBest = Long.MAX_VALUE;
        long naiveHits = 0;
        com.tkisor.fettle.alg.SpatialBuckets grid = new com.tkisor.fettle.alg.SpatialBuckets(maxDistance);
        for (int p = 0; p < total; p++) {
            grid.add(xs[p], ys[p], zs[p]);
        }
        long indexedBest = Long.MAX_VALUE;
        long indexedHits = 0;
        long indexedVisited = 0;

        for (int round = 0; round < 60; round++) {
            long naiveHitsRound = 0;
            long start = System.nanoTime();
            for (int train = 0; train < trains; train++) {
                double qx = train * spacing;
                double qy = 64.0;
                for (int p = 0; p < total; p++) {
                    if (round == 0) {
                        naivePairs++;
                    }
                    double dx = xs[p] - qx;
                    double dy = ys[p] - qy;
                    double dz = zs[p];
                    if (dx * dx + dy * dy + dz * dz <= radiusSqr) {
                        naiveHitsRound++;
                    }
                }
            }
            long naiveNanos = System.nanoTime() - start;
            if (round >= 10) {
                naiveBest = Math.min(naiveBest, naiveNanos);
                naiveHits = naiveHitsRound;
            }

            long indexedHitsRound = 0;
            long start2 = System.nanoTime();
            for (int train = 0; train < trains; train++) {
                indexedHitsRound += grid.countWithin(train * spacing, 64.0, 0.0, maxDistance);
            }
            long indexedNanos = System.nanoTime() - start2;
            if (round == 0) {
                indexedVisited = grid.visited();
            }
            if (round >= 10) {
                indexedBest = Math.min(indexedBest, indexedNanos);
                indexedHits = indexedHitsRound;
            }
        }

        return String.format(Locale.ROOT,
                "collisionFilter: points=%d queries=%d radius=%.0f naive_pairs=%d indexed_visited=%d "
                        + "hits(naive/indexed)=%d/%d naive_us=%.1f indexed_us=%.1f speedup=%.2fx",
                total, trains, maxDistance, naivePairs, indexedVisited, naiveHits, indexedHits,
                naiveBest / 1000.0, indexedBest / 1000.0, (double) naiveBest / Math.max(1, indexedBest));
    }

    /**
     * Measures the cost of {@code KineticNetwork#updateStressFor} on a real kinetic network: the exact
     * operation that sums over every member and re-validates each one with a block-entity lookup.
     */
    /**
     * C2: calls {@code KineticNetwork#updateCapacityFor} on the biggest network in the level with the
     * value it already has, so only the bookkeeping strategy differs between the two runs.
     */
    private static String kineticCapacity(ServerLevel level, int iterations) {
        com.simibubi.create.content.kinetics.KineticNetwork network = biggestNetworkByScan();
        if (network == null) {
            network = KineticBenchHelper.biggestNetwork(level);
        }
        if (network == null || network.sources == null || network.sources.isEmpty()) {
            return "kineticCapacity: no populated kinetic network";
        }
        com.simibubi.create.content.kinetics.base.KineticBlockEntity member =
                network.sources.keySet().iterator().next();
        float value = network.sources.get(member);
        int members = network.sources.size();
        for (int i = 0; i < 2000; i++) {
            network.updateCapacityFor(member, value);
        }
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            network.updateCapacityFor(member, value);
        }
        long elapsed = System.nanoTime() - start;
        return String.format(Locale.ROOT,
                "kineticCapacity: %.3f us/op (members=%d, %d iterations, incrementalCapacity=%s)",
                (double) elapsed / iterations / 1000.0, members, iterations,
                com.tkisor.fettle.config.HotFlags.KINETIC_INCREMENTAL_CAPACITY);
    }

    /** The biggest populated network across every level, found the same way the stress micro does it. */
    private static com.simibubi.create.content.kinetics.KineticNetwork biggestNetworkByScan() {
        try {
            Class<?> propagator = Class.forName("com.simibubi.create.content.kinetics.TorquePropagator");
            java.lang.reflect.Field field = propagator.getDeclaredField("networks");
            field.setAccessible(true);
            Map<?, ?> byLevel = (Map<?, ?>) field.get(null);
            com.simibubi.create.content.kinetics.KineticNetwork best = null;
            int bestSize = -1;
            for (Object perLevel : byLevel.values()) {
                if (!(perLevel instanceof Map<?, ?> map)) {
                    continue;
                }
                for (Object candidate : map.values()) {
                    if (!(candidate instanceof com.simibubi.create.content.kinetics.KineticNetwork network)) {
                        continue;
                    }
                    if (network.sources == null || network.sources.isEmpty()) {
                        continue;
                    }
                    if (network.sources.size() > bestSize) {
                        bestSize = network.sources.size();
                        best = network;
                    }
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String kineticStress(ServerLevel level, int iterations) {
        try {
            com.simibubi.create.content.kinetics.KineticNetwork direct = KineticBenchHelper.biggestNetwork(level);
            if (direct != null) {
                return measureKineticStress(direct, iterations);
            }
            Class<?> propagator = Class.forName("com.simibubi.create.content.kinetics.TorquePropagator");
            java.lang.reflect.Field field = propagator.getDeclaredField("networks");
            field.setAccessible(true);
            Map<?, ?> byLevel = (Map<?, ?>) field.get(null);
            Object networks = null;
            for (Map.Entry<?, ?> entry : byLevel.entrySet()) {
                if (entry.getKey() == level) {
                    networks = entry.getValue();
                    break;
                }
            }
            com.simibubi.create.content.kinetics.KineticNetwork best = null;
            if (networks != null) {
                for (Object candidate : ((Map<?, ?>) networks).values()) {
                    com.simibubi.create.content.kinetics.KineticNetwork network =
                            (com.simibubi.create.content.kinetics.KineticNetwork) candidate;
                    if (network.members == null || network.members.isEmpty()) {
                        continue;
                    }
                    if (best == null || network.members.size() > best.members.size()) {
                        best = network;
                    }
                }
            }
            if (best == null) {
                // fall back to any level's biggest network, so a key mismatch cannot hide the data
                int levels = 0;
                for (Object perLevel : byLevel.values()) {
                    levels++;
                    for (Object candidate : ((Map<?, ?>) perLevel).values()) {
                        com.simibubi.create.content.kinetics.KineticNetwork network =
                                (com.simibubi.create.content.kinetics.KineticNetwork) candidate;
                        if (network.members == null || network.members.isEmpty()) {
                            continue;
                        }
                        if (best == null || network.members.size() > best.members.size()) {
                            best = network;
                        }
                    }
                }
                if (best == null) {
                    return "kineticStress: no populated kinetic network (levels=" + levels + ")";
                }
            }
            if (best == null) {
                return "kineticStress: no populated kinetic network in this level";
            }
            return measureKineticStress(best, iterations);
        } catch (Throwable t) {
            return "kineticStress failed: " + t;
        }
    }

    private static String measureKineticStress(com.simibubi.create.content.kinetics.KineticNetwork network,
                                               int iterations) {
        com.simibubi.create.content.kinetics.base.KineticBlockEntity member =
                network.members.keySet().iterator().next();
        float stress = network.members.get(member);

        com.tkisor.fettle.alg.LevelBlockEntityCache.resetCounters();
        com.tkisor.fettle.alg.KineticCounters.reset();
        for (int i = 0; i < 200; i++) {
            network.updateStressFor(member, stress);
        }
        com.tkisor.fettle.alg.LevelBlockEntityCache.resetCounters();
        com.tkisor.fettle.alg.KineticCounters.reset();
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            network.updateStressFor(member, stress);
        }
        long elapsed = System.nanoTime() - start;
        return String.format(Locale.ROOT,
                "kineticStress: members=%d iters=%d us/op=%.3f incremental=%s lookups=%s kinetic=%s",
                network.members.size(), iterations, elapsed / 1000.0 / iterations,
                HotFlags.KINETIC_INCREMENTAL_STRESS, com.tkisor.fettle.alg.LevelBlockEntityCache.counters(),
                com.tkisor.fettle.alg.KineticCounters.report());
    }

    private static String matchAddress(int iterations) {
        String[] globs = {"factory-*", "item-?-box", "*", "warehouse-2026-*"};
        String[] addresses = {"factory-1", "factory-27", "item-a-box", "warehouse-2026-05", "nothing"};

        int sink = 0;
        for (int i = 0; i < 20_000; i++) {
            sink += PackageItem.matchAddress(globs[i & 3], addresses[i % 5]) ? 1 : 0;
        }

        long start = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < iterations; i++) {
            if (PackageItem.matchAddress(globs[i & 3], addresses[i % 5])) {
                hits++;
            }
        }
        long elapsed = System.nanoTime() - start;
        return String.format(Locale.ROOT, "matchAddress: iters=%d ns/op=%.1f hits=%d (warmup sink=%d)",
                iterations, (double) elapsed / iterations, hits, sink);
    }

    /** Exercises the "node has no connections" path, which allocates a HashMap in vanilla. */
    private static String trackGraphMiss(int iterations) {
        TrackGraph graph = new TrackGraph();
        TrackNode node = new TrackNode(new TrackNodeLocation(Vec3.ZERO), 1, Vec3.ZERO);

        int sink = 0;
        for (int i = 0; i < 20_000; i++) {
            sink += graph.getConnectionsFrom(node).size();
        }

        long start = System.nanoTime();
        long total = 0;
        for (int i = 0; i < iterations; i++) {
            total += graph.getConnectionsFrom(node).size();
        }
        long elapsed = System.nanoTime() - start;
        return String.format(Locale.ROOT, "trackGraphMiss: iters=%d ns/op=%.1f total=%d (warmup sink=%d) index=%s",
                iterations, (double) elapsed / iterations, total, sink, HotFlags.TRACK_GRAPH);
    }

    /** Timed recipe lookups over synthetic inputs built from the real recipe set. */
    private static String getRecipeFor(ServerLevel level, int iterations) {
        RecipeManager manager = level.getRecipeManager();
        List<RecipeHolder<CraftingRecipe>> recipes = manager.getAllRecipesFor(RecipeType.CRAFTING);

        List<CraftingInput> inputs = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : recipes) {
            CraftingInput input = inputFor(holder.value());
            if (input != null) {
                inputs.add(input);
            }
            if (inputs.size() >= 256) {
                break;
            }
        }
        if (inputs.isEmpty()) {
            return "getRecipeFor: no usable crafting recipes in this world";
        }

        int sink = 0;
        for (int i = 0; i < 2_000; i++) {
            sink += manager.getRecipeFor(RecipeType.CRAFTING, inputs.get(i % inputs.size()), level).isPresent() ? 1 : 0;
        }

        long start = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < iterations; i++) {
            if (manager.getRecipeFor(RecipeType.CRAFTING, inputs.get(i % inputs.size()), level).isPresent()) {
                hits++;
            }
        }
        long elapsed = System.nanoTime() - start;
        return String.format(Locale.ROOT,
                "getRecipeFor: inputs=%d iters=%d ns/op=%.1f hits=%d (warmup sink=%d) index=%s",
                inputs.size(), iterations, (double) elapsed / iterations, hits, sink, HotFlags.RECIPE_INDEX);
    }

    private static CraftingInput inputFor(CraftingRecipe recipe) {
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || ingredients.size() > 9) {
            return null;
        }
        List<ItemStack> stacks = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            if (i >= ingredients.size()) {
                stacks.add(ItemStack.EMPTY);
                continue;
            }
            Ingredient ingredient = ingredients.get(i);
            if (ingredient == null || ingredient.isEmpty()) {
                return null;
            }
            ItemStack[] items = ingredient.getItems();
            if (items.length == 0) {
                return null;
            }
            stacks.add(new ItemStack(items[0].getItem()));
        }
        return CraftingInput.of(3, 3, stacks);
    }
}
