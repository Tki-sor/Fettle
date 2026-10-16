package com.tkisor.fettle.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-tick recorder used by the benchmark harness.
 *
 * <p>Recording is armed on demand ({@code /fettle bench start <tag>}) and writes a plain CSV when
 * stopped, so a measurement window contains exactly the warm game state and nothing else. Samples are
 * kept in a primitive {@code long[]} on purpose: boxing them would allocate in the very loop we are
 * trying to measure.
 *
 * <p>Only the server tick thread writes samples; the command thread only arms/disarms.
 */
public final class TickRecorder {

    private static final AtomicBoolean ARMED = new AtomicBoolean(false);

    /** Named sections measured per tick; the ids are stable so the CSV columns stay aligned. */
    public static final int SECTION_PANEL_TICK = 0;
    public static final int SECTION_CRAFTER_TICK = 1;
    /** Count-only section: how many real neighbour-signal queries the crafters performed. */
    public static final int SECTION_CRAFTER_SIGNAL = 2;
    public static final int SECTION_COUNT = 3;
    public static final String[] SECTION_NAMES = {"panels_tick_ms", "crafters_tick_ms", "crafters_signal_ms"};
    private static final String[] SECTION_CALL_COLUMNS =
            {"panels_tick_calls", "crafters_tick_calls", "crafters_signal_calls"};

    private static long[] samples = new long[4096];
    private static final long[][] sectionNanos = new long[SECTION_COUNT][4096];
    private static final int[] sectionCallsThisTick = new int[SECTION_COUNT];
    private static final int[][] sectionCalls = new int[SECTION_COUNT][4096];
    private static int count;
    private static String tag = "run";

    private TickRecorder() {
    }

    public static boolean isArmed() {
        return ARMED.get();
    }

    public static void record(long nanos) {
        if (count == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
            for (int section = 0; section < SECTION_COUNT; section++) {
                sectionNanos[section] = Arrays.copyOf(sectionNanos[section], samples.length);
                sectionCalls[section] = Arrays.copyOf(sectionCalls[section], samples.length);
            }
        }
        for (int section = 0; section < SECTION_COUNT; section++) {
            sectionNanos[section][count] = sectionAccumulator[section];
            sectionCalls[section][count] = sectionCallsThisTick[section];
            sectionAccumulator[section] = 0L;
            sectionCallsThisTick[section] = 0;
        }
        samples[count++] = nanos;
    }

    private static final long[] sectionAccumulator = new long[SECTION_COUNT];

    /** @return true when the caller should bother timing itself. */
    public static boolean isSectionArmed(int section) {
        return ARMED.get();
    }

    public static void countCall(int section) {
        sectionCallsThisTick[section]++;
    }

    public static void addSectionNanos(int section, long nanos) {
        sectionAccumulator[section] += nanos;
    }

    public static String start(String newTag) {
        tag = newTag;
        count = 0;
        ARMED.set(true);
        return "armed '" + tag + "'";
    }

    public static String stop() {
        ARMED.set(false);
        Path out = Path.of("fettle-ticks-" + tag + ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(out)) {
            writer.write("tick_ms");
            for (String name : SECTION_NAMES) {
                writer.write(",");
                writer.write(name);
            }
            for (String name : SECTION_CALL_COLUMNS) {
                writer.write(",");
                writer.write(name);
            }
            writer.write("\n");
            for (int i = 0; i < count; i++) {
                writer.write(String.format(Locale.ROOT, "%.4f", samples[i] / 1_000_000.0));
                for (int section = 0; section < SECTION_COUNT; section++) {
                    writer.write(String.format(Locale.ROOT, ",%.4f", sectionNanos[section][i] / 1_000_000.0));
                }
                for (int section = 0; section < SECTION_COUNT; section++) {
                    writer.write(",");
                    writer.write(Integer.toString(sectionCalls[section][i]));
                }
                writer.write("\n");
            }
        } catch (IOException e) {
            return "error: " + e;
        }
        int written = count;
        count = 0;
        return "wrote " + out.toAbsolutePath() + " (" + written + " ticks)";
    }

    public static String status() {
        return ARMED.get() ? "armed '" + tag + "', " + count + " ticks" : "idle";
    }
}
