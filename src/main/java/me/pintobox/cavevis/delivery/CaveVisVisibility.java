package me.pintobox.cavevis.delivery;

import me.pintobox.cavevis.visibility.CaveVisRayData;

import java.util.BitSet;
import java.util.UUID;

public final class CaveVisVisibility {
    public final UUID world;

    private final CaveVisRayData.Grid grid;
    private final BitSet sections;

    public CaveVisVisibility(
        UUID world,
        CaveVisRayData.Grid grid,
        BitSet sections,
        boolean visibilityMargin
    ) {
        this.world = world;
        this.grid = grid;
        this.sections = (BitSet) sections.clone();

        if (!visibilityMargin) {
            return;
        }

        int height = grid.height;
        int diameter = grid.diameter;
        int rowStride = diameter * height;
        int sectionCount = diameter * rowStride;

        for (int index = sections.nextSetBit(0);
             index >= 0 && index < sectionCount;
             index = sections.nextSetBit(index + 1)) {

            int column = index / height;
            int y = index % height;
            int x = column % diameter;
            int z = column / diameter;

            if (x > 0) {
                this.sections.set(index - height);
            }

            if (x + 1 < diameter) {
                this.sections.set(index + height);
            }

            if (y > 0) {
                this.sections.set(index - 1);
            }

            if (y + 1 < height) {
                this.sections.set(index + 1);
            }

            if (z > 0) {
                this.sections.set(index - rowStride);
            }

            if (z + 1 < diameter) {
                this.sections.set(index + rowStride);
            }
        }
    }

    public void writeColumn(
        BitSet result,
        int x,
        int z,
        int bottom,
        int height,
        int cutoff
    ) {
        result.clear();

        int surfaceStart = Math.clamp(cutoff - bottom, 0, height);
        result.set(surfaceStart, height);

        int firstY = Math.max(bottom, grid.bottom);
        int endY = Math.min(cutoff, Math.min(bottom + height, grid.bottom + grid.height));
        if (firstY >= endY) {
            return;
        }

        int firstIndex = grid.index(x, firstY, z);
        if (firstIndex < 0) {
            return;
        }

        int endIndex = firstIndex + endY - firstY;
        for (int index = sections.nextSetBit(firstIndex);
             index >= 0 && index < endIndex;
             index = sections.nextSetBit(index + 1)) {
            result.set(firstY - bottom + index - firstIndex);
        }
    }
}