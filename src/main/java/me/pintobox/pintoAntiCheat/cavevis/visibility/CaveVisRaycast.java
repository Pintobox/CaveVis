package me.pintobox.pintoAntiCheat.cavevis.visibility;

import me.pintobox.pintoAntiCheat.cavevis.core.CaveVisSettings;
import me.pintobox.pintoAntiCheat.cavevis.cache.CaveVisRayCache;

import java.util.BitSet;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaveVisRaycast {
    private CaveVisRaycast() {}

    public record Snapshot(
        CaveVisRayData.Grid grid,
        long[][] columns
    ) {}

    public record Directions(int count, double[] xyz) {}

    public record Run(
        CaveVisRayData.Result result,
        long voxels,
        int hits,
        int unknown
    ) {}

    public static Directions directions(int count) {
        double[] xyz = new double[count * 3];
        double golden = Math.PI * (3 - Math.sqrt(5));

        for (int i = 0; i < count; i++) {
            double y = 1 - 2 * (i + 0.5) / count;
            double radius = Math.sqrt(1 - y * y);
            double angle = i * golden;

            xyz[i * 3] = Math.cos(angle) * radius;
            xyz[i * 3 + 1] = y;
            xyz[i * 3 + 2] = Math.sin(angle) * radius;
        }

        return new Directions(count, xyz);
    }

    public static Run search(
        Snapshot snapshot,
        double ex,
        double ey,
        double ez,
        int radius,
        Directions directions,
        long seed,
        AtomicBoolean cancelled
    ) {
        long begin = System.nanoTime();
        check(cancelled);

        CaveVisRayData.Grid g = snapshot.grid();

        BitSet approved = new BitSet(
            g.diameter * g.diameter * g.height
        );

        if (radius < 0 || radius > CaveVisRayCache.MAX_SEARCH_RADIUS_CHUNKS) {
            throw new IllegalArgumentException(
                "Invalid search radius: " + radius
            );
        }

        int cx = (int) Math.floor(ex / 16);
        int cz = (int) Math.floor(ez / 16);

        double x0 = Math.max(g.minX, cx - radius) * 16.0;
        double x1 = Math.min(
            g.minX + g.diameter, cx + radius + 1
        ) * 16.0;

        double z0 = Math.max(g.minZ, cz - radius) * 16.0;
        double z1 = Math.min(
            g.minZ + g.diameter, cz + radius + 1
        ) * 16.0;

        double y0 = g.bottom * 16.0;
        double y1 = (g.bottom + g.height) * 16.0;

        double safety = Math.max(
            0,
            CaveVisSettings.NEAR_REVEAL_RADIUS_BLOCKS
        );

        for (int z = (int) Math.floor((ez - safety) / 16);
             z <= (int) Math.floor((ez + safety) / 16); z++) {
            for (int x = (int) Math.floor((ex - safety) / 16);
                 x <= (int) Math.floor((ex + safety) / 16); x++) {
                for (int y = (int) Math.floor((ey - safety) / 16);
                     y <= (int) Math.floor((ey + safety) / 16); y++) {
                    int owner = g.index(x, y, z);

                    if (owner >= 0) {
                        approved.set(owner);
                    }
                }
            }
        }

        SplittableRandom random = new SplittableRandom(seed);

        double u = random.nextDouble();
        double a = random.nextDouble() * Math.PI * 2;
        double b = random.nextDouble() * Math.PI * 2;

        double qx = Math.sqrt(1 - u) * Math.sin(a);
        double qy = Math.sqrt(1 - u) * Math.cos(a);
        double qz = Math.sqrt(u) * Math.sin(b);
        double qw = Math.sqrt(u) * Math.cos(b);

        double m00 = 1 - 2 * (qy * qy + qz * qz);
        double m01 = 2 * (qx * qy - qz * qw);
        double m02 = 2 * (qx * qz + qy * qw);

        double m10 = 2 * (qx * qy + qz * qw);
        double m11 = 1 - 2 * (qx * qx + qz * qz);
        double m12 = 2 * (qy * qz - qx * qw);

        double m20 = 2 * (qx * qz - qy * qw);
        double m21 = 2 * (qy * qz + qx * qw);
        double m22 = 1 - 2 * (qx * qx + qy * qy);

        long voxels = 0;
        int hits = 0;
        int unknown = 0;

        boolean inside = ex >= x0 && ex < x1
            && ey >= y0 && ey < y1
            && ez >= z0 && ez < z1;

        int startXp = startVoxel(ex, true);
        int startXm = startVoxel(ex, false);
        int startYp = startVoxel(ey, true);
        int startYm = startVoxel(ey, false);
        int startZp = startVoxel(ez, true);
        int startZm = startVoxel(ez, false);

        double lowX = x0 - ex;
        double highX = x1 - ex;

        double lowY = y0 - ey;
        double highY = y1 - ey;

        double lowZ = z0 - ez;
        double highZ = z1 - ez;

        double[] xyz = directions.xyz();

        for (int ray = 0; ray < directions.count(); ray++) {
            if ((ray & 63) == 0) {
                check(cancelled);
            }

            int p = ray * 3;

            double dx =
                m00 * xyz[p] + m01 * xyz[p + 1] + m02 * xyz[p + 2];
            double dy =
                m10 * xyz[p] + m11 * xyz[p + 1] + m12 * xyz[p + 2];
            double dz =
                m20 * xyz[p] + m21 * xyz[p + 1] + m22 * xyz[p + 2];

            double invX = Math.abs(dx) < 1e-15
                ? Double.POSITIVE_INFINITY : 1 / dx;
            double invY = Math.abs(dy) < 1e-15
                ? Double.POSITIVE_INFINITY : 1 / dy;
            double invZ = Math.abs(dz) < 1e-15
                ? Double.POSITIVE_INFINITY : 1 / dz;

            double enter = 0;
            double exit = Double.POSITIVE_INFINITY;

            if (inside) {
                // Every ray starts at the eye. Only its exit needs calculating.
                double exitX = Math.abs(dx) < 1e-15
                    ? Double.POSITIVE_INFINITY
                    : (dx >= 0 ? highX : lowX) * invX;

                double exitY = Math.abs(dy) < 1e-15
                    ? Double.POSITIVE_INFINITY
                    : (dy >= 0 ? highY : lowY) * invY;

                double exitZ = Math.abs(dz) < 1e-15
                    ? Double.POSITIVE_INFINITY
                    : (dz >= 0 ? highZ : lowZ) * invZ;

                exit = Math.min(exitX, Math.min(exitY, exitZ));
            } else {
                // Preserve the original intersection for eyes outside the region,
                // including positions above the height cutoff.
                for (int axis = 0; axis < 3; axis++) {
                    double origin = axis == 0 ? ex : axis == 1 ? ey : ez;
                    double dir = axis == 0 ? dx : axis == 1 ? dy : dz;
                    double low = axis == 0 ? x0 : axis == 1 ? y0 : z0;
                    double high = axis == 0 ? x1 : axis == 1 ? y1 : z1;

                    if (Math.abs(dir) < 1e-15) {
                        if (origin < low || origin >= high) {
                            exit = -1;
                            break;
                        }
                    } else {
                        double inv = axis == 0
                            ? invX : axis == 1 ? invY : invZ;

                        double t0 = (low - origin) * inv;
                        double t1 = (high - origin) * inv;

                        enter = Math.max(enter, Math.min(t0, t1));
                        exit = Math.min(exit, Math.max(t0, t1));
                    }
                }
            }

            if (enter >= exit) continue;

            int sx = dx >= 0 ? 1 : -1;
            int sy = dy >= 0 ? 1 : -1;
            int sz = dz >= 0 ? 1 : -1;

            int x;
            int y;
            int z;

            if (inside) {
                x = sx > 0 ? startXp : startXm;
                y = sy > 0 ? startYp : startYm;
                z = sz > 0 ? startZp : startZm;
            } else {
                x = startVoxel(ex + enter * dx, sx > 0);
                y = startVoxel(ey + enter * dy, sy > 0);
                z = startVoxel(ez + enter * dz, sz > 0);
            }

            double tx = Math.abs(dx) < 1e-15
                ? Double.POSITIVE_INFINITY
                : ((x + (sx > 0 ? 1 : 0)) * 2.0 - ex) * invX;

            double ty = Math.abs(dy) < 1e-15
                ? Double.POSITIVE_INFINITY
                : ((y + (sy > 0 ? 1 : 0)) * 2.0 - ey) * invY;

            double tz = Math.abs(dz) < 1e-15
                ? Double.POSITIVE_INFINITY
                : ((z + (sz > 0 ? 1 : 0)) * 2.0 - ez) * invZ;

            double ddx = Math.abs(dx) < 1e-15
                ? Double.POSITIVE_INFINITY : 2 * Math.abs(invX);
            double ddy = Math.abs(dy) < 1e-15
                ? Double.POSITIVE_INFINITY : 2 * Math.abs(invY);
            double ddz = Math.abs(dz) < 1e-15
                ? Double.POSITIVE_INFINITY : 2 * Math.abs(invZ);

            rayWalk:
            while (true) {
                if ((voxels & 1023) == 0) {
                    check(cancelled);
                }

                // These lookups now happen once per section.
                int lx = (x >> 3) - g.minX;
                int lz = (z >> 3) - g.minZ;
                int word = y - (g.bottom << 3);

                if (lx < 0 || lz < 0 || word < 0
                    || lx >= g.diameter
                    || lz >= g.diameter
                    || word >= g.height * 8) {
                    break;
                }

                int col = lz * g.diameter + lx;
                long[] column = snapshot.columns()[col];

                if (column == null) {
                    unknown++;
                    break;
                }

                int section = word >> 3;
                approved.set(col * g.height + section);

                // First voxel coordinate outside this section.
                int endX = sx > 0 ? (x | 7) + 1 : (x & ~7) - 1;
                int endY = sy > 0 ? (y | 7) + 1 : (y & ~7) - 1;
                int endZ = sz > 0 ? (z | 7) + 1 : (z & ~7) - 1;

                long emptyMask =
                    column[g.height * 8 + (section >>> 6)];

                if ((emptyMask & (1L << (section & 63))) != 0) {
                    // Empty section: stepping only.
                    while (true) {
                        voxels++;

                        if (tx <= ty && tx <= tz) {
                            if (tx >= exit) break rayWalk;

                            x += sx;
                            tx += ddx;

                            if (x == endX) break;
                        } else if (ty <= tz) {
                            if (ty >= exit) break rayWalk;

                            y += sy;
                            ty += ddy;

                            if (y == endY) break;
                        } else {
                            if (tz >= exit) break rayWalk;

                            z += sz;
                            tz += ddz;

                            if (z == endZ) break;
                        }
                    }
                } else {
                    // Mixed section: cache the current horizontal opacity row.
                    long row = column[word];

                    while (true) {
                        voxels++;

                        int bit = ((z & 7) << 3) | (x & 7);

                        if ((row & (1L << bit)) != 0) {
                            hits++;
                            break rayWalk;
                        }

                        if (tx <= ty && tx <= tz) {
                            if (tx >= exit) break rayWalk;

                            x += sx;
                            tx += ddx;

                            if (x == endX) break;
                        } else if (ty <= tz) {
                            if (ty >= exit) break rayWalk;

                            y += sy;
                            ty += ddy;

                            if (y == endY) break;

                            // Only a vertical step changes the opacity row.
                            word += sy;
                            row = column[word];
                        } else {
                            if (tz >= exit) break rayWalk;

                            z += sz;
                            tz += ddz;

                            if (z == endZ) break;
                        }
                    }
                }
            }
        }

        return new Run(
            new CaveVisRayData.Result(
                approved,
                System.nanoTime() - begin
            ),
            voxels,
            hits,
            unknown
        );
    }

    private static int startVoxel(
        double coordinate,
        boolean positive
    ) {
        return (int) Math.floor(Math.nextAfter(
            coordinate,
            positive
                ? Double.POSITIVE_INFINITY
                : Double.NEGATIVE_INFINITY
        ) / 2);
    }

    private static void check(AtomicBoolean cancelled) {
        if (cancelled.get()
            || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}