package me.pintobox.pintoAntiCheat.cavevis.delivery;

import java.util.BitSet;
import java.util.UUID;

import me.pintobox.pintoAntiCheat.cavevis.core.CaveVisSettings;
import me.pintobox.pintoAntiCheat.cavevis.visibility.CaveVisRayData;

public final class CaveVisVisibility {
    public final UUID world;

    private final CaveVisRayData.Grid grid;
    private final BitSet sections;

    public CaveVisVisibility(
        UUID world,
        CaveVisRayData.Grid grid,
        BitSet sections
    ) {
        this.world = world;
        this.grid = grid;
        this.sections = (BitSet) sections.clone();

        if (!CaveVisSettings.VISIBILITY_MARGIN_ENABLED) {
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

    public BitSet column(
        int x,
        int z,
        int bottom,
        int height,
        int cutoff
    ) {
        BitSet result = new BitSet(height);

        for (int i = 0; i < height; i++) {
            int y = bottom + i;
            int index = grid.index(x, y, z);

            if (y >= cutoff
                || (index >= 0 && sections.get(index))) {
                result.set(i);
            }
        }

        return result;
    }
}