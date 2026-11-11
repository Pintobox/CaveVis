package me.pintobox.pintoAntiCheat.cavevis.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.bukkit.World;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * Native callbacks only enqueue masks.
 * World and cache access stay on the server thread.
 */
final class CaveVisStorageChanges implements AutoCloseable {
    private static final MethodHandle GET;
    private static final MethodHandle SET;

    static {
        try {
            var lookup = MethodHandles.publicLookup();

            GET = lookup.findVirtual(
                PalettedContainer.class,
                "pinto$getChangeListener",
                MethodType.methodType(IntConsumer.class)
            );

            SET = lookup.findVirtual(
                PalettedContainer.class,
                "pinto$setChangeListener",
                MethodType.methodType(void.class, IntConsumer.class)
            );
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                "This plugin requires the PintoPaper storage patch",
                error
            );
        }
    }

    private final Queue<Watch> pending =
        new ConcurrentLinkedQueue<>();

    private final Map<UUID, Long2ObjectOpenHashMap<Watch>> watches =
        new HashMap<>();

    Watch bind(
        World world,
        int x,
        int y,
        int z,
        LevelChunkSection section
    ) {
        var worldWatches = watches.computeIfAbsent(
            world.getUID(),
            unused -> new Long2ObjectOpenHashMap<>()
        );

        long key = SectionPos.asLong(x, y, z);
        Watch previous = worldWatches.get(key);
        var states = section.getStates();

        if (previous != null && !previous.closed
            && previous.section.refersTo(section)) {
            if (!previous.data.refersTo(states.data)) {
                previous.data = new WeakReference<>(states.data);
                previous.accept(-1);
            }

            return previous;
        }

        IntConsumer listener = get(states);

        if (listener instanceof Watch watch
            && watch.owner == this
            && watch.world.equals(world)
            && watch.x == x
            && watch.y == y
            && watch.z == z
            && watch.section.refersTo(section)) {

            if (!watch.data.refersTo(states.data)) {
                watch.data = new WeakReference<>(states.data);
                watch.accept(-1);
            }

            return watch;
        }

        if (listener != null) {
            throw new IllegalStateException(
                "A block container already has a different storage listener"
            );
        }

        if (previous != null) {
            detach(previous);
        }

        Watch watch = new Watch(
            this, world, x, y, z, section
        );

        set(states, watch);
        worldWatches.put(key, watch);
        watch.accept(-1);

        return watch;
    }

    Watch poll() {
        return pending.poll();
    }

    boolean hasQueuedChanges() {
        return !pending.isEmpty();
    }

    void unbindColumn(World world, int x, int z, int bottom, int height) {
        UUID worldId = world.getUID();
        var worldWatches = watches.get(worldId);
        if (worldWatches == null) {
            return;
        }

        for (int y = bottom; y < bottom + height; y++) {
            Watch watch = worldWatches.remove(SectionPos.asLong(x, y, z));
            if (watch != null) {
                detach(watch);
            }
        }

        if (worldWatches.isEmpty()) {
            watches.remove(worldId);
        }
    }

    void retain(Predicate<Watch> keep) {
        var worlds = watches.values().iterator();

        while (worlds.hasNext()) {
            var map = worlds.next();
            var iterator = map.values().iterator();

            while (iterator.hasNext()) {
                Watch watch = iterator.next();

                if (!keep.test(watch)) {
                    detach(watch);
                    iterator.remove();
                }
            }

            if (map.isEmpty()) {
                worlds.remove();
            }
        }
    }

    private void detach(Watch watch) {
        synchronized (watch) {
            watch.closed = true;
        }

        LevelChunkSection section = watch.section.get();

        if (section != null
            && get(section.getStates()) == watch) {
            set(section.getStates(), null);
        }
    }

    @Override
    public void close() {
        retain(watch -> false);
        pending.clear();
    }

    private static IntConsumer get(PalettedContainer<?> states) {
        try {
            return (IntConsumer) GET.invokeExact(
                (PalettedContainer) states
            );
        } catch (Throwable error) {
            throw failure(error);
        }
    }

    private static void set(
        PalettedContainer<?> states,
        IntConsumer listener
    ) {
        try {
            SET.invokeExact(
                (PalettedContainer) states,
                listener
            );
        } catch (Throwable error) {
            throw failure(error);
        }
    }

    private static RuntimeException failure(Throwable error) {
        if (error instanceof Error fatal) {
            throw fatal;
        }

        return new IllegalStateException(
            "PintoPaper storage bridge failed",
            error
        );
    }

    static final class Watch implements IntConsumer {
        final CaveVisStorageChanges owner;
        final World world;

        // Global section coordinates.
        final int x, y, z;

        final WeakReference<LevelChunkSection> section;
        WeakReference<Object> data;

        private final long[] cells = new long[8];

        private volatile boolean queued;
        private volatile boolean closed;

        Watch(
            CaveVisStorageChanges owner,
            World world,
            int x,
            int y,
            int z,
            LevelChunkSection section
        ) {
            this.owner = owner;
            this.world = world;

            this.x = x;
            this.y = y;
            this.z = z;

            this.section = new WeakReference<>(section);
            this.data = new WeakReference<>(
                section.getStates().data
            );
        }

        @Override
        public synchronized void accept(int blockIndex) {
            if (closed) {
                return;
            }

            if (blockIndex < 0) {
                Arrays.fill(cells, -1L);
            } else {
                int cell = ((blockIndex >>> 1) & 7)
                    | ((blockIndex >>> 2) & 56)
                    | ((blockIndex >>> 3) & 448);

                cells[cell >>> 6] |= 1L << (cell & 63);
            }

            if (!queued) {
                queued = true;
                owner.pending.add(this);
            }
        }

        boolean matches(LevelChunkSection current) {
            return !closed
                && section.refersTo(current)
                && data.refersTo(current.getStates().data);
        }

        boolean drain(long[] output) {
            // Clean sections return without acquiring a lock.
            if (!queued || closed) {
                return false;
            }

            synchronized (this) {
                if (!queued || closed) {
                    return false;
                }

                System.arraycopy(cells, 0, output, 0, 8);
                Arrays.fill(cells, 0L);

                queued = false;
                return true;
            }
        }

        boolean hasPending() {
            return queued && !closed;
        }
    }
}