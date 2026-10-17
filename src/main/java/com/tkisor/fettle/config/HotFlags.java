package com.tkisor.fettle.config;

/**
 * Hot-path copies of the config switches.
 *
 * <p>Reading a {@code ModConfigSpec} value (and, worse, a system property) inside a per-tick or
 * per-call mixin handler costs more than the micro-optimisation is worth - the first benchmark run
 * showed a guard costing roughly as much as the single neighbour-signal query it avoided. These
 * flags are plain volatile reads; they are refreshed whenever the config is loaded or a runtime
 * override changes.
 */
public final class HotFlags {

    public static volatile boolean ENABLED = true;
    public static volatile boolean RECIPE_INDEX = true;
    public static volatile boolean RECIPE_BITSET = true;
    public static volatile boolean RECIPE_STRICT = false;
    public static volatile boolean CACHE_ALL_RECIPES = true;
    public static volatile boolean CREATE = true;
    public static volatile boolean TRACK_GRAPH = true;
    public static volatile boolean BELT_SHAFT = true;
    public static volatile boolean OBSERVE = false;
    public static volatile int OBSERVE_REPORT_MINUTES = 5;
    public static volatile boolean BE_SLEEPING_ENABLED = false;
    public static volatile String[] BE_SLEEPING_CLASSES = new String[0];
    public static volatile int BE_SLEEPING_OBSERVE_TICKS = 1200;
    public static volatile int BE_SLEEPING_MAX_SKIP_TICKS = 20;
    public static volatile int BE_SLEEPING_BLACKLIST_WAKES = 20;
    public static volatile boolean BENCH_PROBES = false;
    public static volatile boolean DEBUG_PANEL_KEEP_ALIVE = false;
    public static volatile boolean KINETIC_INCREMENTAL_STRESS = true;
    public static volatile boolean KINETIC_INCREMENTAL_CAPACITY = true;
    public static volatile boolean MOUNTED_STORAGE_FAST_PATH = true;
    public static volatile boolean FLUID_SEARCH_DEQUE = true;
    public static volatile boolean FLUID_PROPAGATOR_DEQUE = true;
    public static volatile boolean KINETIC_VALIDITY_CACHE = true;
    public static volatile boolean TRAIN_COLLISION_INDEX = true;
    public static volatile boolean TRAIN_COLLISION_VERIFY_MOVED = true;
    public static volatile boolean TRAVEL_POSITION_CACHE = true;
    public static volatile boolean PACKAGE_ADDRESS = true;
    public static volatile int PANEL_INTERVAL = 5;

    private HotFlags() {
    }

    public static void refresh() {
        ENABLED = FettleConfig.enabled();
        RECIPE_INDEX = FettleConfig.recipeIndexEnabled();
        RECIPE_BITSET = FettleConfig.bitsetIndexEnabled();
        RECIPE_STRICT = FettleConfig.strictVanillaSerializersOnly();
        CACHE_ALL_RECIPES = FettleConfig.cacheGetAllRecipesFor();
        CREATE = FettleConfig.createEnabled();
        TRACK_GRAPH = FettleConfig.createTrackGraphConnections();
        BELT_SHAFT = FettleConfig.createBeltShaftLookup();
        OBSERVE = FettleConfig.observe();
        OBSERVE_REPORT_MINUTES = FettleConfig.observeReportMinutes();
        BE_SLEEPING_ENABLED = FettleConfig.beSleepingEnabled();
        BE_SLEEPING_CLASSES = FettleConfig.beSleepingClasses();
        BE_SLEEPING_OBSERVE_TICKS = FettleConfig.beSleepingObserveTicks();
        BE_SLEEPING_MAX_SKIP_TICKS = FettleConfig.beSleepingMaxSkipTicks();
        BE_SLEEPING_BLACKLIST_WAKES = FettleConfig.beSleepingBlacklistWakes();
        BENCH_PROBES = FettleConfig.benchProbes();
        DEBUG_PANEL_KEEP_ALIVE = FettleConfig.debugPanelKeepAlive();
        KINETIC_INCREMENTAL_STRESS = FettleConfig.createKineticIncrementalStress();
        KINETIC_INCREMENTAL_CAPACITY = FettleConfig.createKineticIncrementalCapacity();
        MOUNTED_STORAGE_FAST_PATH = FettleConfig.createMountedStorageFastPath();
        FLUID_SEARCH_DEQUE = FettleConfig.createFluidSearchDeque();
        FLUID_PROPAGATOR_DEQUE = FettleConfig.createFluidPropagatorDeque();
        KINETIC_VALIDITY_CACHE = FettleConfig.createKineticValidityCache();
        TRAIN_COLLISION_INDEX = FettleConfig.createTrainCollisionIndex();
        TRAIN_COLLISION_VERIFY_MOVED = FettleConfig.createTrainCollisionVerifyMoved();
        TRAVEL_POSITION_CACHE = FettleConfig.createTravelPositionCache();
        PACKAGE_ADDRESS = FettleConfig.createPackageAddressCache();
        PANEL_INTERVAL = FettleConfig.createFactoryPanelInterval();
    }

    static {
        refresh();
    }
}
