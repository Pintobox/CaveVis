package me.pintobox.pintoAntiCheat.cavevis.cache;

import me.pintobox.pintoAntiCheat.cavevis.profiling.CaveVisPrepProfiler;
import me.pintobox.pintoAntiCheat.cavevis.core.CaveVisSettings;
import me.pintobox.pintoAntiCheat.cavevis.visibility.CaveVisRayData;
import me.pintobox.pintoAntiCheat.cavevis.visibility.CaveVisRaycast;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaveVisRayCache implements AutoCloseable, Listener {
    public static final int MIN_RAYS = 256;
    public static final int MAX_RAYS = 65536;
    public static final int MAX_SEARCH_RADIUS_CHUNKS = 64;
    private static final int MAX_BUILD_RADIUS_CHUNKS = 64;
    private static final long MAX_RAW_SNAPSHOT_BYTES = 256L * 1024 * 1024;

    private final JavaPlugin plugin;
    private final ExecutorService rays = Executors.newFixedThreadPool(CaveVisSettings.RAY_WORKERS);
    private final CaveVisStorageChanges changes = new CaveVisStorageChanges();
    private final long[] dirtyCells = new long[8];
    private final Map<Integer, long[]> liveCopies = new HashMap<>();
    private final Set<Integer> rawCopied = new HashSet<>();
    private final CaveVisPrepProfiler prepProfiler = new CaveVisPrepProfiler();

    private Cache cache;
    private Build build;
    private long serial;
    private long sequence;
    private CaveVisRaycast.Directions directions;

    public record Scan(long generation, CaveVisRayData.Grid grid, CaveVisRaycast.Run run) {}

    private record Loaded(int index, Chunk chunk, boolean ticket) {}

    public CaveVisRayCache(JavaPlugin plugin) {
        this.plugin = plugin;
        setRays(CaveVisSettings.DEFAULT_RAYS);

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public CaveVisPrepProfiler preparationProfile() {
        return prepProfiler;
    }

    public void setRays(int count) {
        if (count < MIN_RAYS
            || count > MAX_RAYS) {
            throw new IllegalArgumentException(
                "Rays must be "
                    + MIN_RAYS
                    + ".."
                    + MAX_RAYS
            );
        }

        directions = CaveVisRaycast.directions(count);
    }

    public boolean canSearch() {
        return !changes.hasQueuedChanges();
    }

    public long generation() {
        return cache == null ? -1 : cache.id;
    }

    public boolean ready(Location eye) {
        return cache != null
            && cache.world.equals(eye.getWorld())
            && eye.getY() >= cache.bottom * 16
            && cache.column(
            eye.getBlockX() >> 4,
            eye.getBlockZ() >> 4
        ) >= 0;
    }

    public CompletableFuture<Scan> search(
        Location eye,
        int radius,
        AtomicBoolean cancelled
    ) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                "Search preparation must run on the server thread"
            );
        }

        if (!ready(eye)) {
            throw new IllegalStateException(
                "Build a ray cache around your position first"
            );
        }

        if (radius < 1
            || radius > MAX_SEARCH_RADIUS_CHUNKS) {
            throw new IllegalArgumentException(
                "Invalid search radius: " + radius
            );
        }

        long preparationStarted = prepProfiler.begin();

        try {
            Cache c = cache;
            int width = Math.min(c.diameter, radius * 2 + 1);

            int x = Math.max(
                c.minX,
                Math.min(
                    (eye.getBlockX() >> 4) - radius,
                    c.minX + c.diameter - width
                )
            );

            int z = Math.max(
                c.minZ,
                Math.min(
                    (eye.getBlockZ() >> 4) - radius,
                    c.minZ + c.diameter - width
                )
            );

            long stageStarted = prepProfiler.stageStart();

            CaveVisRayData.Grid grid = new CaveVisRayData.Grid(
                x, z, width, c.bottom, c.height
            );

            long[][] columns = new long[width * width][];
            int firstColumn = c.column(x, z);

            for (int dz = 0; dz < width; dz++) {
                System.arraycopy(
                    c.columns,
                    firstColumn + dz * c.diameter,
                    columns,
                    dz * width,
                    width
                );
            }

            var snapshot = new CaveVisRaycast.Snapshot(grid, columns);
            var selected = directions;

            long seed = ++sequence * 0x9E3779B97F4A7C15L;

            double ex = eye.getX();
            double ey = eye.getY();
            double ez = eye.getZ();

            prepProfiler.stageEnd(
                CaveVisPrepProfiler.Phase.SNAPSHOT,
                stageStarted
            );

            stageStarted = prepProfiler.stageStart();

            CompletableFuture<Scan> future =
                CompletableFuture.supplyAsync(
                    () -> new Scan(
                        c.id,
                        grid,
                        CaveVisRaycast.search(
                            snapshot,
                            ex, ey, ez,
                            radius,
                            selected,
                            seed,
                            cancelled
                        )
                    ),
                    rays
                );

            prepProfiler.stageEnd(
                CaveVisPrepProfiler.Phase.SUBMIT,
                stageStarted
            );

            return future;
        } finally {
            prepProfiler.end(preparationStarted);
        }
    }

    public void start(
        CommandSender source,
        Location center,
        int radius
    ) {
        if (build != null) {
            throw new IllegalStateException(
                "A build is running; use /cavevis raycancel"
            );
        }

        if (radius < 1
            || radius > MAX_BUILD_RADIUS_CHUNKS) {
            throw new IllegalArgumentException(
                "Build radius must be 1.."
                    + MAX_BUILD_RADIUS_CHUNKS
            );
        }

        World world = Objects.requireNonNull(center.getWorld());

        int bottom = Math.floorDiv(world.getMinHeight(), 16);
        int cutoff = CaveVisSettings.OCCLUDERS_UP_TO_Y;

        int top = Math.min(
            Math.floorDiv(world.getMaxHeight(), 16),
            Math.floorDiv(cutoff + 15, 16)
        );

        int visibleTop = Math.min(
            Math.floorDiv(world.getMaxHeight(), 16),
            CaveVisSettings.alwaysVisibleSection()
        );

        if (top < visibleTop) {
            throw new IllegalArgumentException(
                "Occluder height must reach at least the always-visible height"
            );
        }

        int diameter = radius * 2 + 1;
        int height = top - bottom;

        if (height <= 0
            || 512L * diameter * diameter * height
            > MAX_RAW_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException(
                "Invalid cutoff or raw snapshots exceed MAX_RAW_SNAPSHOT_BYTES"
            );
        }

        Cache next = new Cache(
            world,
            (center.getBlockX() >> 4) - radius,
            (center.getBlockZ() >> 4) - radius,
            diameter,
            bottom,
            height,
            ++serial
        );

        build = new Build(source, next);

        tell(source,
            "Phase 1/2: loading/generating "
                + diameter * diameter
                + " columns; raw opacity snapshots follow each load.");
    }

    public void tick() {
        flushEdits();

        Build b = build;
        if (b == null) return;

        try {
            if (b.future == null) {
                load(b);
            } else {
                finish(b);
            }

            if (build == b
                && System.nanoTime() - b.report >= 2_000_000_000L) {
                b.report = System.nanoTime();
                tell(b.source, status());
            }
        } catch (RuntimeException e) {
            cancel();
            tell(b.source, "Build failed: " + e.getMessage());

            plugin.getLogger().log(
                java.util.logging.Level.SEVERE,
                "Ray build failed",
                e
            );
        }
    }

    private void load(Build b) {
        long begin = System.nanoTime();
        long budget = CaveVisSettings.BUILD_BUDGET_NANOS;
        int maximum = CaveVisSettings.BUILD_IN_FLIGHT;

        do {
            while (b.loading + b.ready.size() < maximum
                && b.next < b.raw.length) {
                int index = b.next++;
                int x = b.data.minX + index % b.data.diameter;
                int z = b.data.minZ + index / b.data.diameter;

                b.loading++;

                b.data.world.getChunkAtAsync(x, z, true)
                    .whenComplete((chunk, error) -> {
                        Runnable completed = () -> {
                            if (build != b) return;

                            b.loading--;

                            if (error != null || chunk == null) {
                                b.error = error == null
                                    ? new IllegalStateException(
                                    "Missing chunk"
                                )
                                    : error;

                                return;
                            }

                            try {
                                boolean ticket =
                                    chunk.addPluginChunkTicket(plugin);

                                b.ready.addLast(
                                    new Loaded(index, chunk, ticket)
                                );
                            } catch (RuntimeException failure) {
                                b.error = failure;
                            }
                        };

                        if (!plugin.isEnabled()) return;

                        if (Bukkit.isPrimaryThread()) {
                            completed.run();
                        } else {
                            Bukkit.getScheduler().runTask(
                                plugin, completed
                            );
                        }
                    });
            }

            if (b.error != null) {
                throw new IllegalStateException(
                    "Chunk loading failed", b.error
                );
            }

            Loaded loaded = b.ready.pollFirst();
            if (loaded == null) break;

            try {
                long started = System.nanoTime();

                b.raw[loaded.index] = capture(loaded.chunk, b.data);
                b.captureNanos += System.nanoTime() - started;
                b.loaded++;
            } finally {
                if (loaded.ticket) {
                    loaded.chunk.removePluginChunkTicket(plugin);
                }
            }
        } while (
            b.loaded < b.raw.length
                && System.nanoTime() - begin < budget
        );

        if (b.loaded != b.raw.length) return;

        b.phase1 = System.nanoTime() - b.started;

        tell(b.source, String.format(
            Locale.ROOT,
            "Phase 1 complete: %.3f s; snapshot work %.3f ms. "
                + "Phase 2/2: packing.",
            b.phase1 / 1e9,
            b.captureNanos / 1e6
        ));

        long[][] input = b.raw.clone();
        b.dirty.clear();
        b.phase2Start = System.nanoTime();

        b.worker = Executors.newSingleThreadExecutor();

        b.future = CompletableFuture.supplyAsync(
            () -> CaveVisRayPacking.pack(
                input,
                b.data.height,
                n -> b.packed = n
            ),
            b.worker
        );
    }

    public record Bounds(
        UUID world,
        int minX,
        int minZ,
        int diameter,
        int bottom,
        int height
    ) {
        public boolean contains(int x, int z) {
            return x >= minX
                && z >= minZ
                && x - minX < diameter
                && z - minZ < diameter;
        }

        public int top() {
            return bottom + height;
        }
    }

    public Bounds bounds() {
        Cache c = cache;

        if (c == null) {
            throw new IllegalStateException(
                "Build a ray cache first"
            );
        }

        return new Bounds(
            c.world.getUID(),
            c.minX,
            c.minZ,
            c.diameter,
            c.bottom,
            c.height
        );
    }

    private long[] capture(Chunk chunk, Cache c) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                "Terrain capture must run on the server thread"
            );
        }

        ChunkAccess handle =
            ((CraftChunk) chunk).getHandle(ChunkStatus.FULL);

        LevelChunkSection[] sections = handle.getSections();

        int worldBottomSection =
            Math.floorDiv(c.world.getMinHeight(), 16);

        long[] bits = new long[c.height * 64];

        for (int s = 0; s < c.height; s++) {
            int sectionIndex = c.bottom + s - worldBottomSection;

            LevelChunkSection section = sections[sectionIndex];
            if (section != null) {
                changes.bind(c.world, chunk.getX(), c.bottom + s, chunk.getZ(), section);
            }

            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            int sectionWord = s * 64;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!section.getBlockState(x, y, z)
                            .isSolidRender()) {
                            continue;
                        }

                        int cell = x | (z << 4) | (y << 8);

                        bits[sectionWord + (cell >>> 6)]
                            |= 1L << (cell & 63);
                    }
                }
            }
        }

        return bits;
    }

    private void finish(Build b) {
        if (!b.future.isDone()) return;

        if (b.result == null) {
            b.result = b.future.join();
            b.worker.shutdown();

            System.arraycopy(
                b.result.columns(),
                0,
                b.data.columns,
                0,
                b.data.columns.length
            );
        }

        long begin = System.nanoTime();
        try {
            while (b.validated < b.raw.length) {
                int index = b.validated++;
                int x = b.data.minX + index % b.data.diameter;
                int z = b.data.minZ + index / b.data.diameter;
                LevelChunk chunk = loaded(b.data.world, x, z);
                if (chunk != null) refreshColumn(b.data, chunk);
                if (System.nanoTime() - begin >= CaveVisSettings.BUILD_BUDGET_NANOS) break;
            }
        } finally {
            publishChanges();
        }
        if (b.validated < b.raw.length) return;

        for (int index = b.dirty.nextSetBit(0);
             index >= 0;
             index = b.dirty.nextSetBit(index + 1)) {
            b.data.columns[index] = CaveVisRayPacking.column(
                b.raw[index], b.data.height
            );

            b.dirty.clear(index);

            if (System.nanoTime() - begin
                > CaveVisSettings.BUILD_BUDGET_NANOS) {
                break;
            }
        }

        if (!b.dirty.isEmpty()) return;

        cache = b.data;
        build = null;
        retainWatches();

        tell(b.source, String.format(
            Locale.ROOT,
            "READY: %,d columns, %.2f MiB bits, Y %d..%d. "
                + "Phase 1 %.3f s; phase 2 %.3f s; total %.3f s. "
                + "Worker packing %.3f ms.",
            b.raw.length,
            b.raw.length * b.data.height * 64.0 / 1048576,
            b.data.bottom * 16,
            (b.data.bottom + b.data.height) * 16 - 1,
            b.phase1 / 1e9,
            (System.nanoTime() - b.phase2Start) / 1e9,
            (System.nanoTime() - b.started) / 1e9,
            b.result.nanos() / 1e6
        ));
    }

    public void changed(Block block) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Explicit cache invalidation must run on the server thread");
        }
        LevelChunk chunk = loaded(block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
        if (chunk == null) return;
        int y = block.getY();
        if (!relevant(cache, block.getWorld(), block.getX(), y, block.getZ())
            && (build == null || !relevant(build.data, block.getWorld(), block.getX(), y, block.getZ()))) return;
        var section = chunk.getSection(chunk.getSectionIndex(y));
        if (section == null) return;
        var watch = changes.bind(block.getWorld(), block.getX() >> 4, y >> 4, block.getZ() >> 4, section);
        watch.accept((block.getX() & 15) | ((block.getZ() & 15) << 4) | ((y & 15) << 8));
    }

    private static boolean relevant(Cache c, World world, int x, int y, int z) {
        return c != null && c.world.equals(world)
            && y >= c.bottom * 16 && y < (c.bottom + c.height) * 16
            && c.column(x >> 4, z >> 4) >= 0;
    }

    private static LevelChunk loaded(World world, int x, int z) {
        return ((CraftWorld) world).getHandle().getChunkSource().getChunkAtIfLoadedImmediately(x, z);
    }

    private void refreshColumn(Cache c, LevelChunk chunk) {
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;

        int column = c.column(x, z);

        if (column < 0) {
            return;
        }

        CaveVisStorageChanges.Watch[] observed =
            c.observed[column];

        if (observed == null) {
            observed = new CaveVisStorageChanges.Watch[c.height];
            c.observed[column] = observed;
        }

        LevelChunkSection[] sections = chunk.getSections();
        int first = chunk.getSectionIndex(c.bottom << 4);

        for (int s = 0; s < c.height; s++) {
            LevelChunkSection section = sections[first + s];

            if (section == null) {
                observed[s] = null;
                continue;
            }

            CaveVisStorageChanges.Watch watch = observed[s];

            // Existing section and palette: reuse the binding directly.
            if (watch == null || !watch.matches(section)) {
                watch = changes.bind(
                    c.world,
                    x,
                    c.bottom + s,
                    z,
                    section
                );

                observed[s] = watch;
            }

            applyWatch(watch, section);
        }
    }

    private void flushEdits() {
        long started = System.nanoTime();
        boolean processed = false;

        try {
            CaveVisStorageChanges.Watch watch;

            while ((!processed
                || System.nanoTime() - started
                < CaveVisSettings.EDIT_BUDGET_NANOS)
                && (watch = changes.poll()) != null) {

                processed = true;

                boolean inLive = relevant(
                    cache,
                    watch.world,
                    watch.x << 4,
                    watch.y << 4,
                    watch.z << 4
                );

                boolean inBuild = build != null && relevant(
                    build.data,
                    watch.world,
                    watch.x << 4,
                    watch.y << 4,
                    watch.z << 4
                );

                if (!inLive && !inBuild) continue;

                LevelChunk chunk = loaded(
                    watch.world,
                    watch.x,
                    watch.z
                );

                if (chunk == null) continue;

                LevelChunkSection section = chunk.getSection(
                    chunk.getSectionIndex(watch.y << 4)
                );

                if (section == null) continue;

                CaveVisStorageChanges.Watch current = changes.bind(
                    watch.world,
                    watch.x,
                    watch.y,
                    watch.z,
                    section
                );

                applyWatch(current, section);
            }
        } finally {
            publishChanges();
        }
    }

    private void applyWatch(
        CaveVisStorageChanges.Watch watch,
        LevelChunkSection section
    ) {
        if (watch.hasPending()) {
            applyDirtyWatch(watch, section);
        }
    }

    private void applyDirtyWatch(
        CaveVisStorageChanges.Watch watch,
        LevelChunkSection section
    ) {
        if (!watch.drain(dirtyCells)) return;
        Cache live = cache;
        Build b = build;
        boolean inLive = relevant(live, watch.world, watch.x << 4, watch.y << 4, watch.z << 4);
        boolean inBuild = b != null && relevant(b.data, watch.world,
            watch.x << 4, watch.y << 4, watch.z << 4);
        if (!inLive && !inBuild) return;

        int liveColumn = inLive ? live.column(watch.x, watch.z) : -1;
        int liveSection = inLive ? watch.y - live.bottom : -1;
        long[] packed = inLive ? liveCopies.getOrDefault(liveColumn, live.columns[liveColumn]) : null;
        int rawColumn = inBuild ? b.data.column(watch.x, watch.z) : -1;
        int rawSection = inBuild ? watch.y - b.data.bottom : -1;
        long[] raw = inBuild ? b.raw[rawColumn] : null;
        boolean changedPacked = false;

        for (int row = 0; row < 8; row++) {
            long bits = dirtyCells[row];
            while (bits != 0) {
                int offset = Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                int cell = (row << 6) | offset;
                int bx = (cell & 7) << 1;
                int bz = ((cell >>> 3) & 7) << 1;
                int by = (cell >>> 6) << 1;
                boolean solid = true;

                cellRead:
                for (int dy = 0; dy < 2; dy++) {
                    for (int dz = 0; dz < 2; dz++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int nx = bx + dx, ny = by + dy, nz = bz + dz;
                            boolean opaque = section.getBlockState(nx, ny, nz).isSolidRender();
                            if (!opaque) solid = false;
                            if (raw != null) {
                                int block = nx | (nz << 4) | (ny << 8);
                                int word = rawSection * 64 + (block >>> 6);
                                long bit = 1L << (block & 63);
                                if (((raw[word] & bit) != 0) != opaque) {
                                    if (b.future != null && rawCopied.add(rawColumn)) {
                                        raw = raw.clone();
                                        b.raw[rawColumn] = raw;
                                    }
                                    if (opaque) raw[word] |= bit;
                                    else raw[word] &= ~bit;
                                    if (b.future != null) b.dirty.set(rawColumn);
                                }
                            } else if (!opaque) {
                                break cellRead;
                            }
                        }
                    }
                }
                if (packed == null) continue;
                int word = liveSection * 8 + row;
                long bit = 1L << offset;
                if (((packed[word] & bit) != 0) == solid) continue;
                if (!liveCopies.containsKey(liveColumn)) {
                    packed = packed.clone();
                    liveCopies.put(liveColumn, packed);
                }
                if (solid) packed[word] |= bit;
                else packed[word] &= ~bit;
                changedPacked = true;
            }
        }
        if (changedPacked) {
            CaveVisRayPacking.updateEmptyFlag(packed, live.height, liveSection);
        }
    }

    private void publishChanges() {
        if (cache != null) {
            for (var entry : liveCopies.entrySet()) cache.columns[entry.getKey()] = entry.getValue();
        }
        liveCopies.clear();
        rawCopied.clear();
    }

    private void retainWatches() {
        changes.retain(watch -> relevant(cache, watch.world, watch.x << 4, watch.y << 4, watch.z << 4)
            || (build != null && relevant(build.data, watch.world,
            watch.x << 4, watch.y << 4, watch.z << 4)));
    }

    public String status() {
        Build b = build;

        if (b != null) {
            return b.future == null
                ? "Phase 1/2: " + b.loaded + "/" + b.raw.length
                + " captured; " + b.loading + " loading."
                : "Phase 2/2: " + b.packed + "/" + b.raw.length
                + " packed.";
        }

        return cache == null
            ? "No cache."
            : "Cache ready: " + cache.columns.length
            + " columns in " + cache.world.getName();
    }

    public void cancel() {
        Build b = build;
        build = null;

        if (b == null) return;

        for (Loaded loaded : b.ready) {
            if (loaded.ticket) {
                loaded.chunk.removePluginChunkTicket(plugin);
            }
        }

        b.ready.clear();

        if (b.future != null) b.future.cancel(true);
        if (b.worker != null) b.worker.shutdownNow();
        retainWatches();
    }

    public void clear() {
        cancel();
        cache = null;
        changes.close();
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);

        clear();
        rays.shutdownNow();
        liveCopies.clear();
        rawCopied.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        int x = event.getChunk().getX();
        int z = event.getChunk().getZ();

        Cache live = cache;
        Build activeBuild = build;

        boolean inLive = live != null
            && live.world.equals(world)
            && live.column(x, z) >= 0;

        boolean inBuild = activeBuild != null
            && activeBuild.data.world.equals(world)
            && activeBuild.data.column(x, z) >= 0;

        if (!inLive && !inBuild) return;

        LevelChunk chunk = (LevelChunk) ((CraftChunk) event.getChunk())
            .getHandle(ChunkStatus.FULL);

        try {
            if (inLive) refreshColumn(live, chunk);
            if (inBuild) refreshColumn(activeBuild.data, chunk);
        } finally {
            publishChanges();
        }
    }

    private static void tell(CommandSender sender, String text) {
        sender.sendMessage("[CaveVis] " + text);
    }

    private static final class Cache {
        final World world;
        final int minX, minZ, diameter, bottom, height;
        final long id;
        final long[][] columns;
        final CaveVisStorageChanges.Watch[][] observed;

        Cache(
            World world,
            int minX,
            int minZ,
            int diameter,
            int bottom,
            int height,
            long id
        ) {
            this.world = world;
            this.minX = minX;
            this.minZ = minZ;
            this.diameter = diameter;
            this.bottom = bottom;
            this.height = height;
            this.id = id;

            columns = new long[diameter * diameter][];
            observed = new CaveVisStorageChanges.Watch[columns.length][];
        }

        int column(int x, int z) {
            x -= minX;
            z -= minZ;

            return x < 0 || z < 0
                || x >= diameter || z >= diameter
                ? -1
                : z * diameter + x;
        }
    }

    private static final class Build {
        final CommandSender source;
        final Cache data;
        final long[][] raw;
        final ArrayDeque<Loaded> ready = new ArrayDeque<>();
        final BitSet dirty = new BitSet();

        final long started = System.nanoTime();
        long report = started;
        long phase1, captureNanos, phase2Start;

        int next, loaded, loading, validated;
        volatile int packed;
        Throwable error;

        ExecutorService worker;
        CompletableFuture<CaveVisRayPacking.Packed> future;
        CaveVisRayPacking.Packed result;

        Build(CommandSender source, Cache data) {
            this.source = source;
            this.data = data;
            raw = new long[data.columns.length][];
        }
    }
}