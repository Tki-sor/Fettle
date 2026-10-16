package com.tkisor.fettle.alg;

/** Deterministic counters for the kinetic network bookkeeping. */
public final class KineticCounters {

    private static long incrementalUpdates;
    private static long reconciles;
    private static long syncs;
    private static long speedAdjusts;

    private KineticCounters() {
    }

    public static void reset() {
        incrementalUpdates = 0;
        reconciles = 0;
        syncs = 0;
        speedAdjusts = 0;
    }

    public static void speedAdjust() {
        speedAdjusts++;
    }

    public static void incrementalUpdate() {
        incrementalUpdates++;
    }

    public static void reconcile() {
        reconciles++;
    }

    public static void sync() {
        syncs++;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT,
                "incremental=%d reconciles=%d syncs=%d speedAdjusts=%d",
                incrementalUpdates, reconciles, syncs, speedAdjusts);
    }
}
