package me.pintobox.cavevis.cache;

import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.function.Consumer;

final class CaveVisCaptureQueue {
    record Column(UUID worldId, int x, int z) {}

    private final LinkedHashSet<Column> pending = new LinkedHashSet<>();

    void add(UUID worldId, int x, int z) {
        pending.add(new Column(worldId, x, z));
    }

    void remove(UUID worldId, int x, int z) {
        pending.remove(new Column(worldId, x, z));
    }

    void removeWorld(UUID worldId) {
        pending.removeIf(column -> column.worldId().equals(worldId));
    }

    void drain(long budgetNanos, Consumer<Column> capture) {
        long started = System.nanoTime();

        while (!pending.isEmpty()) {
            Column column = pending.removeFirst();
            capture.accept(column);

            if (System.nanoTime() - started >= budgetNanos) {
                break;
            }
        }
    }

    void clear() {
        pending.clear();
    }
}