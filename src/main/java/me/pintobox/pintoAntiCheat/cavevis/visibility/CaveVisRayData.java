package me.pintobox.pintoAntiCheat.cavevis.visibility;

import java.util.BitSet;

public final class CaveVisRayData {
    private CaveVisRayData() {}

    public static final class Grid {
        public final int minX;
        public final int minZ;
        public final int diameter;
        public final int bottom;
        public final int height;

        public Grid(
            int minX,
            int minZ,
            int diameter,
            int bottom,
            int height
        ) {
            this.minX = minX;
            this.minZ = minZ;
            this.diameter = diameter;
            this.bottom = bottom;
            this.height = height;
        }

        public int index(int x, int y, int z) {
            x -= minX;
            y -= bottom;
            z -= minZ;

            if (x < 0 || z < 0 || y < 0
                || x >= diameter
                || z >= diameter
                || y >= height) {
                return -1;
            }

            return (z * diameter + x) * height + y;
        }
    }

    public record Result(BitSet sections, long nanos) {}
}