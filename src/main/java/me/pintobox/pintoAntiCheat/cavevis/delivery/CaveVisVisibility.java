package me.pintobox.pintoAntiCheat.cavevis.delivery;

import java.util.BitSet;
import java.util.UUID;
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