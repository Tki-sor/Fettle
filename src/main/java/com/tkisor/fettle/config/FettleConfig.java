package com.tkisor.fettle.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Fettle configuration.
 *
 * <p>Reads are defensive: mixin plugins run earlier than config loading, so every accessor falls
 * back to a default instead of throwing.
 */
public final class FettleConfig {

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.BooleanValue RECIPE_INDEX;
    private static final ModConfigSpec.BooleanValue RECIPE_BITSET;
    private static final ModConfigSpec.BooleanValue RECIPE_INDEX_STRICT;
    private static final ModConfigSpec.BooleanValue CACHE_ALL_RECIPES;
    private static final ModConfigSpec.BooleanValue SELF_TEST;
    private static final ModConfigSpec.BooleanValue CREATE_ENABLED;
    private static final ModConfigSpec.BooleanValue CREATE_TRACK_GRAPH;
    private static final ModConfigSpec.BooleanValue CREATE_BELT_SHAFT;
    private static final ModConfigSpec.IntValue CREATE_PANEL_INTERVAL;
    private static final ModConfigSpec.BooleanValue OBSERVE;
    private static final ModConfigSpec.IntValue OBSERVE_REPORT_MINUTES;
    private static final ModConfigSpec.BooleanValue BE_SLEEPING_ENABLED;
    private static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> BE_SLEEPING_CLASSES;
    private static final ModConfigSpec.IntValue BE_SLEEPING_OBSERVE_TICKS;
    private static final ModConfigSpec.IntValue BE_SLEEPING_MAX_SKIP_TICKS;
    private static final ModConfigSpec.IntValue BE_SLEEPING_BLACKLIST_WAKES;
    private static final ModConfigSpec.BooleanValue CREATE_FLUID_SEARCH_DEQUE;
    private static final ModConfigSpec.BooleanValue CREATE_FLUID_PROPAGATOR_DEQUE;
    private static final ModConfigSpec.BooleanValue CREATE_KINETIC_INCREMENTAL_CAPACITY;
    private static final ModConfigSpec.BooleanValue CREATE_MOUNTED_STORAGE_FAST_PATH;
    private static final ModConfigSpec.BooleanValue BENCH_PROBES;
    private static final ModConfigSpec.BooleanValue DEBUG_PANEL_KEEP_ALIVE;
    private static final ModConfigSpec.BooleanValue MIXIN_LOG;
    private static final ModConfigSpec.BooleanValue CREATE_KINETIC_INCREMENTAL;
    private static final ModConfigSpec.BooleanValue CREATE_KINETIC_LOOKUP;
    private static final ModConfigSpec.BooleanValue CREATE_TRAIN_COLLISION;
    private static final ModConfigSpec.BooleanValue CREATE_TRAIN_COLLISION_VERIFY;
    private static final ModConfigSpec.BooleanValue CREATE_TRAVEL_POSITION;
    private static final ModConfigSpec.BooleanValue CREATE_PACKAGE_ADDRESS;
    private static final ModConfigSpec.BooleanValue YIELD_TO_FASTSUITE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        ENABLED = b
                .comment("Master switch. Turn this off to get 100% vanilla behaviour, e.g. for the A/B part of a benchmark.")
                .define("enabled", true);

        b.push("diagnostics");
        BENCH_PROBES = b
                .comment("Measurement probes (per-section tick timing counters). Off by default; can be",
                        "switched at runtime with /fettle feature benchProbes true.")
                .define("benchProbes", false);
        DEBUG_PANEL_KEEP_ALIVE = b
                .comment("Measurement scaffold only: stops factory panels from deleting themselves when they have",
                        "no active slot, so a benchmark scene can hold ticking panels. Not a gameplay feature.")
                .define("debugPanelKeepAlive", false);
        MIXIN_LOG = b
                .comment("Log every mixin apply decision at startup.")
                .define("mixinLog", false);
        b.pop();

        b.push("observe");
        OBSERVE = b
                .comment("Observation layer for the block-entity sleeping pilot: counts ticks, setChanged()",
                        "and client syncs per BE class. Observation only, nothing is ever skipped.",
                        "Can run for hours: snapshots are appended to fettle-observe.csv every",
                        "reportIntervalMinutes.")
                .define("enabled", false);
        OBSERVE_REPORT_MINUTES = b
                .comment("Append an observation snapshot to fettle-observe.csv every N minutes (0 = never).")
                .defineInRange("reportIntervalMinutes", 5, 0, 720);
        b.pop();

        b.push("beSleeping");
        BE_SLEEPING_ENABLED = b
                .comment("Skip ticks of block entities whose class is listed in 'classes' and which have been",
                        "provably idle (see observe). Default off; empty class list means nothing sleeps.")
                .define("enabled", false);
        BE_SLEEPING_CLASSES = b
                .comment("Whitelist of block entity classes. Simple names (HopperBlockEntity) or fully qualified",
                        "names are both accepted. A class is only slept after it ticked observeTicks times",
                        "without calling setChanged, and it still gets one heartbeat tick every maxSkipTicks.")
                .defineList("classes", java.util.List.of(), () -> "", value -> value instanceof String);
        BE_SLEEPING_OBSERVE_TICKS = b
                .comment("How many consecutive unchanged ticks a block entity must show before it may sleep.")
                .defineInRange("observeTicks", 1200, 1, 1000000);
        BE_SLEEPING_MAX_SKIP_TICKS = b
                .comment("A sleeping block entity is still ticked once every N skipped ticks (heartbeat).")
                .defineInRange("maxSkipTicks", 20, 1, 10000);
        BE_SLEEPING_BLACKLIST_WAKES = b
                .comment("Auto-blacklist: if a whitelisted class is woken this many times, it is dropped from the",
                        "whitelist for the rest of the run (it is evidently not idle).")
                .defineInRange("blacklistAfterWakes", 20, 1, 1000000);
        b.pop();

        b.push("recipe");
        RECIPE_INDEX = b
                .comment("Pre-filter recipe lookups with a per-input index.",
                        "Semantics are preserved: the final accept/reject decision is still vanilla Recipe#matches.")
                .define("indexEnabled", true);
        RECIPE_BITSET = b
                .comment("Use the pre-compiled bitset candidate index instead of per-item hash sets.",
                        "Pure data-plane change: same candidates, same final Recipe#matches decision.")
                .define("bitsetIndex", true);
        RECIPE_INDEX_STRICT = b
                .comment("Extra safety: only index recipes whose serializer lives in the minecraft namespace.",
                        "Lower coverage, but it guarantees that only vanilla recipe semantics are pre-filtered.")
                .define("strictVanillaSerializersOnly", false);
        CACHE_ALL_RECIPES = b
                .comment("Cache the immutable list returned by RecipeManager#getAllRecipesFor per (manager, recipe type).",
                        "Vanilla rebuilds that list with List.copyOf on every call; the cached instance is immutable as well.")
                .define("cacheGetAllRecipesFor", true);
        SELF_TEST = b
                .comment("On server start, compare the indexed lookup against a brute-force vanilla scan for every",
                        "crafting recipe and log any mismatch. Correctness gate, not a benchmark switch.")
                .define("selfTestOnStartup", false);
        b.pop();

        b.push("create");
        CREATE_ENABLED = b
                .comment("Enable the mixins that target Create's own classes. Requires Create to be installed.")
                .define("enabled", true);
        CREATE_TRACK_GRAPH = b
                .comment("TrackGraph#getConnectionsFrom: return the stored edge map instead of allocating a fresh HashMap")
                .comment("on every lookup miss. Callers only read the result (verified against all 12 call sites).")
                .define("trackGraphConnections", true);
        CREATE_BELT_SHAFT = b
                .comment("BeltBlock#hasShaftTowards: replace the Optional-based block entity lookup with an instanceof check.")
                .define("beltShaftLookup", true);
        CREATE_PANEL_INTERVAL = b
                .comment("Factory panels: run the per-tick storage/request aggregation every N ticks instead of every tick.",
                        "DEFAULT 1 = vanilla behaviour, and that is deliberate: this optimisation is NOT proven yet.",
                        "A server-side scaffolding scene (panels kept alive by a diagnostic that cancels Create's own",
                        "self-removal) measured the panel section at 1.16-1.30 -> 0.35-0.37 ms, but that scene made the",
                        "panels do ~3.6x more per-tick work than naturally activated ones do. On a naturally",
                        "active client scene (4096 wired panels, 693-tick windows) the panel section was 0.5210 ms",
                        "at 5 versus 0.5302 ms at 1 - no difference. See docs/fettle/19-client-spark-test.md.",
                        "Set 5 only if you are experimenting; throughput is unchanged either way.")
                .defineInRange("factoryPanelCheckInterval", 1, 1, 100);
        CREATE_FLUID_SEARCH_DEQUE = b
                .comment("FluidManipulationBehaviour#search: dequeue the fluid BFS with an ArrayDeque instead of",
                        "ArrayList.remove(0). Both are FIFO, so order, result and leftover frontier are identical;",
                        "only the O(n) arraycopy per pop disappears (measured 519x on the dequeue itself).",
                        "Verified on a real 5000-block water body by /fettle debug fluidsearch: accepted, leftover",
                        "size and an order-sensitive hash of the leftover frontier are identical to vanilla over",
                        "60 calls, while the call is ~1.5x faster (360 vs 540-572 us). Whole-server MSPT on a",
                        "genuinely draining rig is still unmeasured. See docs/fettle/23-fluid-search-deque.md.")
                .define("fluidSearchDeque", true);
        CREATE_FLUID_PROPAGATOR_DEQUE = b
                .comment("FluidPropagator#propagateChangedPipe / #resetAffectedFluidNetworks: dequeue the BFS with",
                        "an ArrayDeque instead of List.remove(0). FIFO in both cases, so visit order and result are",
                        "identical. These run on pipe edits / pressure recalculation, so this removes spikes in",
                        "large pipe networks rather than steady-state tick cost. See docs/fettle/25-fluid-propagator-deque.md.")
                .define("fluidPropagatorDeque", true);
        CREATE_KINETIC_INCREMENTAL_CAPACITY = b
                .comment("KineticNetwork#updateCapacityFor: keep a running capacity sum instead of recomputing",
                        "calculateCapacity(), with an exact reconciliation scan once per tick (same scheme as",
                        "kineticIncrementalStress). DEFAULT false on purpose: calculateCapacity() only walks the",
                        "capacity SOURCES (motors/generators, usually 1-3 per network), not every member, so the",
                        "measured win is ~2x on a microsecond-scale call, and the value-level parity of the running",
                        "sum against calculateCapacity() has not been measured yet. See docs/fettle/28.")
                .define("kineticIncrementalCapacity", false);
        CREATE_MOUNTED_STORAGE_FAST_PATH = b
                .comment("MountedStorageManager#tick: skip the two per-sync HashMap allocations when a",
                        "contraption has no synced item/fluid storage. The vanilla path is otherwise a no-op,",
                        "so this early return is behaviour-identical. Measurable with /fettle micro mountedStorageTick.")
                .define("mountedStorageFastPath", true);
        CREATE_KINETIC_INCREMENTAL = b
                .comment("KineticNetwork#updateStressFor: update the running stress sum in O(1) instead of",
                        "re-summing (and re-validating) every member. The exact scan runs once per server tick.")
                .define("kineticIncrementalStress", true);
        CREATE_KINETIC_LOOKUP = b
                .comment("KineticNetwork stress/capacity sums validate every member with Level#getBlockEntity.",
                        "Memoise that lookup per server tick; the comparison sees the same value.")
                .define("kineticValidityCache", true);
        CREATE_TRAIN_COLLISION = b
                .comment("Train#findCollidingTrain: pre-filter the trains it iterates with a per-tick carriage bounding",
                        "box snapshot, instead of testing every carriage of every train.",
                        "Snapshot policy: rebuilt on the next server tick (option B).")
                .define("trainCollisionIndex", true);
        CREATE_TRAIN_COLLISION_VERIFY = b
                .comment("Re-check a train that the snapshot wants to drop: if it moved since the snapshot was taken,",
                        "keep it as a candidate. Keeps the filter exactly equivalent to vanilla.",
                        "Set false for the raw snapshot policy (max speed, collision detection can lag one tick).")
                .define("trainCollisionIndexVerifyMoved", true);
        CREATE_TRAVEL_POSITION = b
                .comment("TravellingPoint#getPositionWithOffset: memoise the world position for the current server tick.",
                        "The cache key covers graph, edge, position, offset, flip and upsideDown, so a hit is an exact value.")
                .define("travelPositionCache", true);
        CREATE_PACKAGE_ADDRESS = b
                .comment("PackageItem#matchAddress: cache the compiled glob Pattern instead of recompiling")
                .comment("the same regex for every package movement.")
                .define("packageAddressCache", true);
        b.pop();

        b.push("compat");
        YIELD_TO_FASTSUITE = b
                .comment("When FastSuite is installed, skip Fettle's recipe index by default.",
                        "Kept as a config so you can benchmark 'Fettle alone' vs 'FastSuite alone' vs 'both' and then decide.")
                .define("yieldToFastSuite", false);
        b.pop();

        SPEC = b.build();
    }

    private FettleConfig() {
    }

    // ------------------------------------------------------------------ runtime feature switches
    /** Runtime overrides, used by the benchmark harness to A/B a single feature inside one JVM. */
    private static final java.util.Map<String, Boolean> BOOL_OVERRIDES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Integer> INT_OVERRIDES = new java.util.concurrent.ConcurrentHashMap<>();

    public static String setBoolOverride(String name, boolean value) {
        BOOL_OVERRIDES.put(name, value);
        HotFlags.refresh();
        return name + "=" + value;
    }

    public static String setIntOverride(String name, int value) {
        INT_OVERRIDES.put(name, value);
        HotFlags.refresh();
        return name + "=" + value;
    }

    public static String overrideState() {
        return BOOL_OVERRIDES + " " + INT_OVERRIDES;
    }

    private static boolean boolOverride(String name, boolean fallback) {
        Boolean value = BOOL_OVERRIDES.get(name);
        return value != null ? value : fallback;
    }

    private static int intOverride(String name, int fallback) {
        Integer value = INT_OVERRIDES.get(name);
        return value != null ? value : fallback;
    }

    public static boolean enabled() {
        return get(ENABLED, true);
    }

    public static boolean recipeIndexEnabled() {
        return enabled() && boolOverride("recipeIndex", get(RECIPE_INDEX, true));
    }

    public static boolean bitsetIndexEnabled() {
        return enabled() && boolOverride("bitsetIndex", get(RECIPE_BITSET, true));
    }

    public static boolean strictVanillaSerializersOnly() {
        return get(RECIPE_INDEX_STRICT, false);
    }

    public static boolean cacheGetAllRecipesFor() {
        return enabled() && get(CACHE_ALL_RECIPES, true);
    }

    public static boolean createEnabled() {
        return enabled() && get(CREATE_ENABLED, true);
    }

    public static boolean createTrackGraphConnections() {
        return createEnabled() && boolOverride("trackGraph", get(CREATE_TRACK_GRAPH, true));
    }

    public static boolean createBeltShaftLookup() {
        return createEnabled() && get(CREATE_BELT_SHAFT, true);
    }

    public static boolean createKineticIncrementalCapacity() {
        return createEnabled() && boolOverride("kineticIncrementalCapacity",
                get(CREATE_KINETIC_INCREMENTAL_CAPACITY, true));
    }

    public static boolean createFluidPropagatorDeque() {
        return createEnabled() && boolOverride("fluidPropagatorDeque", get(CREATE_FLUID_PROPAGATOR_DEQUE, true));
    }

    public static boolean createFluidSearchDeque() {
        return createEnabled() && boolOverride("fluidSearchDeque", get(CREATE_FLUID_SEARCH_DEQUE, true));
    }

    public static boolean createMountedStorageFastPath() {
        return createEnabled() && boolOverride("mountedStorageFastPath",
                get(CREATE_MOUNTED_STORAGE_FAST_PATH, true));
    }

    public static boolean observe() {
        return enabled() && boolOverride("observe", get(OBSERVE, false));
    }

    /** Minutes between observation snapshots written to fettle-observe.csv (0 = never). */
    public static int observeReportMinutes() {
        return get(OBSERVE_REPORT_MINUTES, 5);
    }

    /** Whitelist of block entity class names that may be slept (simple or fully qualified). */
    public static String[] beSleepingClasses() {
        java.util.List<? extends String> configured = get(BE_SLEEPING_CLASSES, java.util.List.of());
        if (configured == null || configured.isEmpty()) {
            return new String[0];
        }
        return configured.stream().filter(java.util.Objects::nonNull).map(String::trim)
                .filter(name -> !name.isEmpty()).toArray(String[]::new);
    }

    public static boolean beSleepingEnabled() {
        return enabled() && boolOverride("beSleeping", get(BE_SLEEPING_ENABLED, false));
    }

    public static int beSleepingObserveTicks() {
        return get(BE_SLEEPING_OBSERVE_TICKS, 1200);
    }

    public static int beSleepingMaxSkipTicks() {
        return get(BE_SLEEPING_MAX_SKIP_TICKS, 20);
    }

    public static int beSleepingBlacklistWakes() {
        return get(BE_SLEEPING_BLACKLIST_WAKES, 20);
    }

    /** Measurement probes for the tick recorder; off by default, runtime-toggleable. */
    public static boolean benchProbes() {
        return enabled() && boolOverride("benchProbes", get(BENCH_PROBES, false));
    }

    /** Measurement scaffold: keeps inactive factory panels alive so B8 can be benchmarked. */
    public static boolean debugPanelKeepAlive() {
        return enabled() && boolOverride("debugPanelKeepAlive", get(DEBUG_PANEL_KEEP_ALIVE, false));
    }

    /** Logs every mixin apply decision. */
    public static boolean mixinLog() {
        return get(MIXIN_LOG, false);
    }

    public static boolean createKineticIncrementalStress() {
        return createEnabled() && boolOverride("kineticIncrementalStress",
                get(CREATE_KINETIC_INCREMENTAL, true));
    }

    public static boolean createKineticValidityCache() {
        return createEnabled() && boolOverride("kineticValidityCache", get(CREATE_KINETIC_LOOKUP, true));
    }

    public static boolean createTrainCollisionIndex() {
        return createEnabled() && boolOverride("trainCollisionIndex", get(CREATE_TRAIN_COLLISION, true));
    }

    public static boolean createTrainCollisionVerifyMoved() {
        return createEnabled() && boolOverride("trainCollisionIndexVerifyMoved",
                get(CREATE_TRAIN_COLLISION_VERIFY, true));
    }

    public static boolean createTravelPositionCache() {
        return createEnabled() && boolOverride("travelPositionCache", get(CREATE_TRAVEL_POSITION, true));
    }

    public static boolean createPackageAddressCache() {
        return createEnabled() && boolOverride("packageAddressCache", get(CREATE_PACKAGE_ADDRESS, true));
    }

    /** @return the factory panel aggregation interval in ticks (1 = vanilla). */
    public static int createFactoryPanelInterval() {
        if (!createEnabled()) {
            return 1;
        }
        try {
            return intOverride("panelInterval", CREATE_PANEL_INTERVAL.get());
        } catch (Throwable ignored) {
            return intOverride("panelInterval", 5);
        }
    }

    public static boolean selfTestOnStartup() {
        return enabled() && get(SELF_TEST, false);
    }

    public static boolean yieldToFastSuite() {
        return get(YIELD_TO_FASTSUITE, false);
    }

    private static int get(ModConfigSpec.IntValue value, int fallback) {
        try {
            Integer configured = value.get();
            return configured == null ? fallback : configured;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static <T> T get(ModConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            T configured = value.get();
            return configured == null ? fallback : configured;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean get(ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (Throwable ignored) {
            // Config not loaded yet (mixin plugin / early class loading): fall back to the default.
            return fallback;
        }
    }
}
