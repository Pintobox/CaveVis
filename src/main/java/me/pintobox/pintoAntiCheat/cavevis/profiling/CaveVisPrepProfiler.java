package me.pintobox.pintoAntiCheat.cavevis.profiling;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class CaveVisPrepProfiler {
    public enum Phase {
        SNAPSHOT,
        SUBMIT,
        OTHER
    }

    private static final int WINDOW = 100;
    private static final int TOTAL = Phase.values().length;

    private final long[][] samples = new long[TOTAL + 1][WINDOW];
    private final long[] sums = new long[TOTAL + 1];
    private final long[] pending = new long[TOTAL];

    private int cursor;
    private int count;

    private boolean enabled = true;
    private boolean recording;

    public boolean toggle() {
        enabled = !enabled;
        reset();
        return enabled;
    }

    public long begin() {
        recording = enabled;

        if (!recording) {
            return 0L;
        }

        Arrays.fill(pending, 0L);
        return System.nanoTime();
    }

    public long stageStart() {
        return recording ? System.nanoTime() : 0L;
    }

    public void add(Phase phase, long nanos) {
        if (recording) {
            pending[phase.ordinal()] += Math.max(0L, nanos);
        }
    }

    public void stageEnd(Phase phase, long started) {
        if (recording) {
            add(phase, System.nanoTime() - started);
        }
    }

    public void end(long started) {
        if (!recording) {
            return;
        }

        long total = System.nanoTime() - started;
        long measured = 0;

        for (long nanos : pending) {
            measured += nanos;
        }

        pending[Phase.OTHER.ordinal()] =
            Math.max(0L, total - measured);

        boolean full = count == WINDOW;

        for (int i = 0; i < TOTAL; i++) {
            store(i, pending[i], full);
        }

        store(TOTAL, total, full);

        cursor = (cursor + 1) % WINDOW;

        if (!full) {
            count++;
        }

        recording = false;
    }

    private void store(int series, long nanos, boolean full) {
        if (full) {
            sums[series] -= samples[series][cursor];
        }

        samples[series][cursor] = nanos;
        sums[series] += nanos;
    }

    public void reset() {
        for (long[] series : samples) {
            Arrays.fill(series, 0L);
        }

        Arrays.fill(sums, 0L);
        Arrays.fill(pending, 0L);

        cursor = 0;
        count = 0;
        recording = false;
    }

    private double mean(int series) {
        return count == 0
            ? 0.0
            : sums[series] / (double) count / 1e6;
    }

    private double max(int series) {
        long largest = 0;

        for (int i = 0; i < count; i++) {
            largest = Math.max(largest, samples[series][i]);
        }

        return largest / 1e6;
    }

    private String stage(Phase phase) {
        return String.format(
            Locale.ROOT,
            "%.3f/%.3f",
            mean(phase.ordinal()),
            max(phase.ordinal())
        );
    }

    public List<String> report() {
        return List.of(
            String.format(
                Locale.ROOT,
                "Detailed prep %s (%d searches): "
                    + "total avg %.3f | max %.3f ms/search",
                enabled ? "ON" : "OFF",
                count,
                mean(TOTAL),
                max(TOTAL)
            ),
            "Prep avg/max ms/search: snapshot "
                + stage(Phase.SNAPSHOT)
                + " | submit " + stage(Phase.SUBMIT)
                + " | other " + stage(Phase.OTHER)
        );
    }
}