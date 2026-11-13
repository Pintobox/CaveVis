package me.pintobox.cavevis.profiling;

import java.util.Arrays;
import java.util.Locale;

public final class CaveVisMainProfiler {
    public enum Phase { CACHE, PREPARE, PUBLISH, DELIVERY, ENTITIES }

    private static final int WINDOW = 100;
    private static final int TOTAL = Phase.values().length;
    private static final int OTHER = TOTAL + 1;
    private final long[][] samples = new long[OTHER + 1][WINDOW];
    private final long[] sums = new long[OTHER + 1];
    private final long[] pending = new long[TOTAL];
    private final long[] sorted = new long[WINDOW];
    private int cursor, count, reportTicks;
    private boolean inTick;
    private long outsideTick;
    private String summary;
    private String breakdown;

    public CaveVisMainProfiler() { refreshText(); }

    public void beginTick() { inTick = true; }

    public void add(Phase phase, long nanos) {
        nanos = Math.max(0L, nanos);
        pending[phase.ordinal()] += nanos;
        // Include command-triggered preparation between scheduled plugin ticks.
        if (!inTick) outsideTick += nanos;
    }

    public void endTick(long tickNanos) {
        inTick = false;
        long total = Math.max(0L, tickNanos) + outsideTick;
        long phases = 0;
        boolean full = count == WINDOW;
        for (int i = 0; i < TOTAL; i++) {
            phases += pending[i];
            record(i, pending[i], full);
        }
        record(TOTAL, total, full);
        record(OTHER, Math.max(0L, total - phases), full);
        Arrays.fill(pending, 0L);
        outsideTick = 0;
        cursor = (cursor + 1) % WINDOW;
        if (!full) count++;
        if (++reportTicks >= 10 || count == 1) {
            refreshText();
            reportTicks = 0;
        }
    }

    private void record(int series, long nanos, boolean full) {
        if (full) sums[series] -= samples[series][cursor];
        samples[series][cursor] = nanos;
        sums[series] += nanos;
    }

    public void reset() {
        for (long[] series : samples) Arrays.fill(series, 0L);
        Arrays.fill(sums, 0L);
        Arrays.fill(pending, 0L);
        cursor = count = reportTicks = 0;
        outsideTick = 0;
        refreshText();
    }

    public String summary() { refreshText(); return summary; }
    public String breakdown() { return breakdown; }

    private double mean(int series) {
        return count == 0 ? 0.0 : sums[series] / (double) count / 1e6;
    }

    private void refreshText() {
        double median = 0, p95 = 0, max = 0;
        if (count > 0) {
            System.arraycopy(samples[TOTAL], 0, sorted, 0, count);
            Arrays.sort(sorted, 0, count);
            median = (sorted[(count - 1) / 2] / 2.0 + sorted[count / 2] / 2.0) / 1e6;
            p95 = sorted[(int) Math.ceil(count * 0.95) - 1] / 1e6;
            max = sorted[count - 1] / 1e6;
        }
        summary = String.format(Locale.ROOT,
            "Scheduled plugin loop, shared across players (%d ticks): avg %.3f | p50 %.3f | p95 %.3f | max %.3f ms/tick",
            count, mean(TOTAL), median, p95, max);
        breakdown = String.format(Locale.ROOT,
            "Average ms/tick: cache %.3f | prep %.3f | publish %.3f "
                + "| delivery %.3f | entities %.3f | other %.3f",
            mean(Phase.CACHE.ordinal()),
            mean(Phase.PREPARE.ordinal()),
            mean(Phase.PUBLISH.ordinal()),
            mean(Phase.DELIVERY.ordinal()),
            mean(Phase.ENTITIES.ordinal()),
            mean(OTHER)
        );
    }

    public String maxima() {
        return String.format(Locale.ROOT,
            "Maximum ms/tick: cache %.3f | prep %.3f | publish %.3f "
                + "| delivery %.3f | entities %.3f | other %.3f",
            maximum(Phase.CACHE.ordinal()),
            maximum(Phase.PREPARE.ordinal()),
            maximum(Phase.PUBLISH.ordinal()),
            maximum(Phase.DELIVERY.ordinal()),
            maximum(Phase.ENTITIES.ordinal()),
            maximum(OTHER)
        );
    }

    private double maximum(int series) {
        long largest = 0;

        for (int i = 0; i < count; i++) {
            largest = Math.max(largest, samples[series][i]);
        }

        return largest / 1e6;
    }
}