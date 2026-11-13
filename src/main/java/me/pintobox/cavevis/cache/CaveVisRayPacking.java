package me.pintobox.cavevis.cache;

import java.util.concurrent.CancellationException;
import java.util.function.IntConsumer;

public final class CaveVisRayPacking {
    private static final long[] MASKS = new long[64];

    static {
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 8; x++) {
                int bit = ((z & 1) << 5) + (x << 1);

                MASKS[z * 8 + x] =
                    (3L << bit) | (3L << (bit + 16));
            }
        }
    }

    private CaveVisRayPacking() {}

    public record Packed(long[][] columns, long nanos) {}

    public static Packed pack(
        long[][] snapshots,
        int height,
        IntConsumer progress
    ) {
        long begin = System.nanoTime();
        long[][] result = new long[snapshots.length][];

        for (int column = 0; column < snapshots.length; column++) {
            check();
            validate(snapshots[column], height);

            long[] output = new long[
                height * 8 + ((height + 63) >>> 6)
                ];

            for (int section = 0; section < height; section++) {
                check();
                packSection(snapshots[column], output, section);
            }

            updateEmptyFlags(output, height);
            result[column] = output;

            progress.accept(column + 1);
        }

        return new Packed(
            result,
            System.nanoTime() - begin
        );
    }

    public static long[] column(long[] raw, int height) {
        validate(raw, height);

        long[] output = new long[
            height * 8 + ((height + 63) >>> 6)
            ];

        for (int section = 0; section < height; section++) {
            packSection(raw, output, section);
        }

        updateEmptyFlags(output, height);

        return output;
    }

    private static void updateEmptyFlags(
        long[] column,
        int height
    ) {
        for (int section = 0; section < height; section++) {
            updateEmptyFlag(column, height, section);
        }
    }

    public static void updateEmptyFlag(
        long[] column,
        int height,
        int section
    ) {
        int base = section * 8;

        long solid =
            column[base]
                | column[base + 1]
                | column[base + 2]
                | column[base + 3]
                | column[base + 4]
                | column[base + 5]
                | column[base + 6]
                | column[base + 7];

        int word = height * 8 + (section >>> 6);
        long bit = 1L << (section & 63);

        if (solid == 0) {
            column[word] |= bit;
        } else {
            column[word] &= ~bit;
        }
    }

    private static void packSection(
        long[] raw,
        long[] output,
        int section
    ) {
        int base = section * 64;
        int out = section * 8;

        for (int y = 0; y < 8; y++) {
            long bits = 0;

            for (int z = 0; z < 8; z++) {
                int word = base + y * 8 + (z >> 1);
                int offset = z * 8;

                long a = raw[word];
                long b = raw[word + 4];

                for (int x = 0; x < 8; x++) {
                    long mask = MASKS[offset + x];

                    if ((a & mask) == mask && (b & mask) == mask) {
                        bits |= 1L << (offset + x);
                    }
                }
            }

            output[out + y] = bits;
        }
    }

    private static void validate(long[] raw, int height) {
        if (height <= 0 || raw == null || raw.length != height * 64) {
            throw new IllegalArgumentException(
                "Invalid raw opacity snapshot"
            );
        }
    }

    private static void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}