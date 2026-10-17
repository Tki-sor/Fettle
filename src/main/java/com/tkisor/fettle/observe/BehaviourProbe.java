package com.tkisor.fettle.observe;

import com.tkisor.fettle.config.HotFlags;
import com.tkisor.fettle.util.ServerTickClock;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zero-behaviour-change observation layer for the BE-sleeping pilot (see docs 09).
 *
 * <p>Records, per block entity class: how often its tick ran, how often it called {@code setChanged}
 * (persistent state changed), and how often it sent data to clients. Classes whose tick never changes
 * anything over a long window are the candidates for sleeping; nothing is skipped here.
 *
 * <p>For long-running packs the layer can append snapshots to {@code fettle-observe.csv} every
 * {@code observe.reportIntervalMinutes}, so a pack author can collect hours of data and then decide the
 * whitelist instead of guessing it.
 */
public final class BehaviourProbe {

    private static final String CSV = "fettle-observe.csv";

    /** [0] ticks, [1] setChanged calls, [2] data sends. */
    private static final Map<Class<?>, long[]> STATS = new ConcurrentHashMap<>();
    private static volatile long lastDumpTick = -1;

    private BehaviourProbe() {
    }

    private static long[] stats(Class<?> type) {
        return STATS.computeIfAbsent(type, $ -> new long[3]);
    }

    public static void tick(Class<?> type) {
        stats(type)[0]++;
    }

    public static void changed(Class<?> type) {
        stats(type)[1]++;
    }

    public static void sentData(Class<?> type) {
        stats(type)[2]++;
    }

    public static void reset() {
        STATS.clear();
        lastDumpTick = -1;
    }

    public static int size() {
        return STATS.size();
    }

    /** Called once per server tick: writes a snapshot on the configured interval. */
    public static void maybeDump() {
        if (!HotFlags.OBSERVE) {
            return;
        }
        int minutes = HotFlags.OBSERVE_REPORT_MINUTES;
        if (minutes <= 0) {
            return;
        }
        long tick = ServerTickClock.tick();
        long interval = minutes * 1200L;
        long last = lastDumpTick;
        if (last >= 0 && tick - last < interval) {
            return;
        }
        lastDumpTick = tick;
        dump();
    }

    /** Appends one snapshot per class to {@code fettle-observe.csv}. @return a short summary. */
    public static synchronized String dump() {
        List<Map.Entry<Class<?>, long[]>> entries = new ArrayList<>(STATS.entrySet());
        if (entries.isEmpty()) {
            return "observation dump: nothing recorded yet";
        }
        entries.sort(Comparator.comparingDouble(
                (Map.Entry<Class<?>, long[]> e) -> e.getValue()[0] == 0 ? 0
                        : (double) e.getValue()[1] / e.getValue()[0]).reversed());
        File file = new File(CSV);
        boolean header = !file.exists();
        int rows = 0;
        try (FileWriter writer = new FileWriter(file, true)) {
            if (header) {
                writer.write("epoch_seconds,class,ticks,changed,sendData,changedPerTick\n");
            }
            long now = System.currentTimeMillis() / 1000L;
            for (Map.Entry<Class<?>, long[]> entry : entries) {
                long[] value = entry.getValue();
                double perTick = value[0] == 0 ? 0 : (double) value[1] / value[0];
                writer.write(String.format(Locale.ROOT, "%d,%s,%d,%d,%d,%.6f\n",
                        now, entry.getKey().getName(), value[0], value[1], value[2], perTick));
                rows++;
            }
        } catch (IOException e) {
            return "observation dump failed: " + e;
        }
        return "observation dump: " + rows + " rows appended to " + file.getAbsolutePath();
    }

    /** @return a table of the classes with the highest tick counts. */
    public static List<String> report(int limit) {
        List<Map.Entry<Class<?>, long[]>> entries = new ArrayList<>(STATS.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<Class<?>, long[]> e) -> e.getValue()[0]).reversed());
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Class<?>, long[]> entry : entries) {
            if (lines.size() >= limit) {
                break;
            }
            long[] value = entry.getValue();
            double changedPerTick = value[0] == 0 ? 0 : (double) value[1] / value[0];
            double sentPerTick = value[0] == 0 ? 0 : (double) value[2] / value[0];
            lines.add(String.format(Locale.ROOT, "%-52s ticks=%-9d changed/tick=%.4f sendData/tick=%.4f",
                    entry.getKey().getSimpleName(), value[0], changedPerTick, sentPerTick));
        }
        return lines;
    }

    /**
     * @return true when this class has ticked at least {@code minTicks} times without a single
     *         {@code setChanged} or {@code sendData} call - the data-driven sleep-candidate test (C7).
     */
    public static boolean isIdleClass(Class<?> type, long minTicks) {
        long[] value = STATS.get(type);
        return value != null && value[0] >= minTicks && value[1] == 0 && value[2] == 0;
    }

    /** @return the paste-ready whitelist entries for the sleep candidates, most-ticked first. */
    public static List<String> candidates(int minTicks) {
        List<Map.Entry<Class<?>, long[]>> entries = new ArrayList<>(STATS.entrySet());
        entries.removeIf(entry -> entry.getValue()[0] < minTicks || entry.getValue()[1] != 0
                || entry.getValue()[2] != 0);
        entries.sort(Comparator.comparingLong((Map.Entry<Class<?>, long[]> e) -> e.getValue()[0]).reversed());
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Class<?>, long[]> entry : entries) {
            lines.add(String.format(Locale.ROOT, "%-60s ticks=%-10d changed=0 sendData=0",
                    entry.getKey().getName(), entry.getValue()[0]));
        }
        return lines;
    }

    /** @return the number of classes that never changed anything (sleep candidates). */
    public static int idleClasses() {
        int idle = 0;
        for (long[] value : STATS.values()) {
            if (value[0] > 0 && value[1] == 0 && value[2] == 0) {
                idle++;
            }
        }
        return idle;
    }
}
