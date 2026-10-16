package com.tkisor.fettle.util;

/** Server tick counter used to scope per-tick memoisation. */
public final class ServerTickClock {

    private static volatile long tick;

    private ServerTickClock() {
    }

    public static long tick() {
        return tick;
    }

    public static void advance() {
        tick++;
    }
}
