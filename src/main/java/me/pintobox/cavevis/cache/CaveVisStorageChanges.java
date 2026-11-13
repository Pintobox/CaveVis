package me.pintobox.cavevis.cache;

import ca.spottedleaf.moonrise.patches.fast_palette.FastPaletteData;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.core.IdMap;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.bukkit.World;

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
    private final Queue<Watch> pending =
        new ConcurrentLinkedQueue<>();

    private final Map<UUID, Long2ObjectOpenHashMap<Watch>> watches =
        new HashMap<>();

    Watch bind(World world, int x, int y, int z, LevelChunkSection section) {
        var worldWatches = watches.computeIfAbsent(
            world.getUID(), unused -> new Long2ObjectOpenHashMap<>()
        );

        long key = SectionPos.asLong(x, y, z);
        Watch previous = worldWatches.get(key);
        var states = section.getStates();

        if (previous != null && !previous.closed && previous.section.refersTo(section)) {
            if (!previous.matches(section)) {
                previous.attach(states);
            }
            return previous;
        }

        if (previous != null) {
            detach(previous);
        }

        Watch watch = new Watch(this, world, x, y, z, section);
        watch.attach(states);
        worldWatches.put(key, watch);
        return watch;
    }

    private void detach(Watch watch) {
        synchronized (watch) {
            watch.closed = true;
        }

        LevelChunkSection section = watch.section.get();
        if (section != null) {
            watch.unwrap(section.getStates());
        }
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

    @Override
    public void close() {
        retain(watch -> false);
        pending.clear();
    }

    static final class Watch implements IntConsumer {
        final CaveVisStorageChanges owner;
        final World world;

        // Global section coordinates.
        final int x, y, z;

        final WeakReference<LevelChunkSection> section;

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
                && owns(current.getStates().data);
        }

        private boolean owns(PalettedContainer.Data<BlockState> data) {
            return data.storage() instanceof WatchingStorage storage
                && storage.onWrite == this
                && data.palette() instanceof WatchingPalette<?>;
        }

        private void attach(PalettedContainer<BlockState> states) {
            synchronized (states) {
                var current = states.data;
                if (owns(current)) {
                    return;
                }

                if (current.storage() instanceof WatchingStorage
                    || current.palette() instanceof WatchingPalette<?>) {
                    throw new IllegalStateException(
                        "Block storage already has a different CaveVis wrapper"
                    );
                }

                install(states, current);
                accept(-1);
            }
        }

        private void install(
            PalettedContainer<BlockState> states,
            PalettedContainer.Data<BlockState> original
        ) {
            var replacement = new PalettedContainer.Data<>(
                original.configuration(),
                new WatchingStorage(original.storage(), this),
                new WatchingPalette<>(
                    original.palette(),
                    states,
                    (bits, state) -> resize(states, bits, state)
                )
            );

            bindFastPalette(replacement);
            states.data = replacement;
        }

        private int resize(
            PalettedContainer<BlockState> states, int bits, BlockState state
        ) {
            synchronized (states) {
                var before = states.data;
                boolean wasWatching = !closed && owns(before);
                int id = states.onResize(bits, state);

                var after = states.data;
                if (wasWatching && !closed && after != before) {
                    install(states, after);
                    accept(-1);
                }

                return id;
            }
        }

        private void unwrap(PalettedContainer<BlockState> states) {
            synchronized (states) {
                var current = states.data;
                if (!owns(current)) {
                    return;
                }

                var storage = (WatchingStorage) current.storage();
                var palette = (WatchingPalette<BlockState>) current.palette();
                var replacement = new PalettedContainer.Data<>(
                    current.configuration(), storage.original, palette.original
                );

                bindFastPalette(replacement);
                states.data = replacement;
            }
        }

        private static <T> void bindFastPalette(PalettedContainer.Data<T> data) {
            data.moonrise$setPalette(data.palette().moonrise$getRawPalette(data));
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

    private static final class WatchingPalette<T> implements Palette<T> {
        private final Palette<T> original;
        private final PaletteResize<T> container;
        private final PaletteResize<T> resizeCallback;

        WatchingPalette(
            Palette<T> original,
            PaletteResize<T> container,
            PaletteResize<T> resizeCallback
        ) {
            this.original = original;
            this.container = container;
            this.resizeCallback = resizeCallback;
        }

        @Override
        public int idFor(T state, PaletteResize<T> resizeHandler) {
            return original.idFor(
                state,
                resizeHandler == container ? resizeCallback : resizeHandler
            );
        }

        @Override
        public T valueFor(int id) {
            return original.valueFor(id);
        }

        @Override
        public boolean maybeHas(Predicate<T> filter) {
            return original.maybeHas(filter);
        }

        @Override
        public void read(FriendlyByteBuf buffer, IdMap<T> map) {
            original.read(buffer, map);
        }

        @Override
        public void write(FriendlyByteBuf buffer, IdMap<T> map) {
            original.write(buffer, map);
        }

        @Override
        public int getSerializedSize(IdMap<T> map) {
            return original.getSerializedSize(map);
        }

        @Override
        public int getSize() {
            return original.getSize();
        }

        @Override
        public Palette<T> copy() {
            return original.copy();
        }

        @Override
        public T[] moonrise$getRawPalette(FastPaletteData<T> data) {
            return original.moonrise$getRawPalette(data);
        }
    }

    private static final class WatchingStorage implements BitStorage {
        private final BitStorage original;
        private final IntConsumer onWrite;

        WatchingStorage(BitStorage original, IntConsumer onWrite) {
            this.original = original;
            this.onWrite = onWrite;
        }

        @Override
        public void set(int index, int value) {
            original.set(index, value);
            onWrite.accept(index);
        }

        @Override
        public int getAndSet(int index, int value) {
            int previous = original.getAndSet(index, value);
            if (previous != value) {
                onWrite.accept(index);
            }
            return previous;
        }

        @Override
        public int get(int index) {
            return original.get(index);
        }

        @Override
        public long[] getRaw() {
            return original.getRaw();
        }

        @Override
        public int getSize() {
            return original.getSize();
        }

        @Override
        public int getBits() {
            return original.getBits();
        }

        @Override
        public void getAll(IntConsumer consumer) {
            original.getAll(consumer);
        }

        @Override
        public void unpack(int[] values) {
            original.unpack(values);
        }

        @Override
        public BitStorage copy() {
            return original.copy();
        }

        @Override
        public Int2ObjectOpenHashMap<ShortArrayList> moonrise$countEntries() {
            return original.moonrise$countEntries();
        }
    }
}