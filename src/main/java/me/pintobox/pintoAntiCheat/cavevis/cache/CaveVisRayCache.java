package me.pintobox.pintoAntiCheat.cavevis.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.pintobox.pintoAntiCheat.cavevis.core.CaveVisConfiguration;
import me.pintobox.pintoAntiCheat.cavevis.core.CaveVisSettings;
import me.pintobox.pintoAntiCheat.cavevis.delivery.CaveVisVisibility;
import me.pintobox.pintoAntiCheat.cavevis.profiling.CaveVisPrepProfiler;
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
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
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
    private static final long AUTOMATIC_CAPTURE_BUDGET_NANOS = 1_000_000L;

    private final JavaPlugin plugin;
    private final CaveVisConfiguration config;
    private final ExecutorService rays;
    private final CaveVisStorageChanges changes = new CaveVisStorageChanges();
    private final long[] dirtyCells = new long[8];
    private final Map<UUID, WorldCache> worlds = new HashMap<>();
    private final Map<LiveColumn, long[]> liveCopies = new IdentityHashMap<>();
    private final Set<Integer> rawCopied = new HashSet<>();
    private final CaveVisPrepProfiler prepProfiler = new CaveVisPrepProfiler();
    private final CaveVisCaptureQueue captures = new CaveVisCaptureQueue();

    private Build build;
    private long generation = -1;
    private long sequence;
    private CaveVisRaycast.Directions directions;

    public record Scan(
        long generation,
        CaveVisRayData.Grid grid,
        CaveVisRaycast.Run run,
        CaveVisVisibility visibility,
        CaveVisRaycast.Snapshot snapshot
    ) {}

    private record Loaded(int index, Chunk chunk, boolean ticket) {}

    public CaveVisRayCache(JavaPlugin plugin, CaveVisConfiguration config) {
        this.plugin = plugin;
        this.config = config;
        rays = Executors.newFixedThreadPool(config.rayWorkers);
        setRays(config.raysPerScan);

        Bukkit.getPluginManager().registerEvents(this, plugin);

        for (World world : Bukkit.getWorlds()) {
            if (!config.enabled(world)) continue;
            queueLoadedChunks(world);
        }
    }

    public void queueLoadedChunks(World world) {
        for (Chunk chunk : world.getLoadedChunks()) {
            captures.add(world.getUID(), chunk.getX(), chunk.getZ());
        }
    }

    public CaveVisPrepProfiler preparationProfile() {
        return prepProfiler;
    }

    private void setRays(int count) {
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
        return generation;
    }

    public boolean ready(Location eye) {
        World world = eye.getWorld();
        if (world == null || !config.enabled(world)) {
            return false;
        }

        LiveColumn column = liveColumn(
            world,
            eye.getBlockX() >> 4,
            eye.getBlockY() >> 4,
            eye.getBlockZ() >> 4
        );

        if (column != null) {
            return column.ready;
        }

        // An eye above the occluder cutoff can still scan downward.
        WorldCache cached = worlds.get(world.getUID());
        if (cached == null || eye.getY() < cached.bottom * 16) {
            return false;
        }

        column = cached.columns.get(columnKey(
            eye.getBlockX() >> 4, eye.getBlockZ() >> 4
        ));
        return column != null && column.ready;
    }

    public CompletableFuture<Scan> search(
        Location eye,
        int radius,
        boolean profileScan,
        AtomicBoolean cancelled
    ) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                "Search preparation must run on the server thread"
            );
        }

        if (!ready(eye)) {
            throw new IllegalStateException(
                "The opacity cache is not ready at this location"
            );
        }

        if (radius < 1 || radius > MAX_SEARCH_RADIUS_CHUNKS) {
            throw new IllegalArgumentException("Invalid search radius: " + radius);
        }

        long preparationStarted = prepProfiler.begin();

        try {
            World world = eye.getWorld();
            UUID worldId = world.getUID();
            var rules = config.rulesFor(world);
            int nearRevealRadius = rules.nearRevealRadiusBlocks();
            boolean visibilityMargin = rules.visibilityMargin();

            WorldCache cached = worlds.get(worldId);
            long scanGeneration = generation;
            int width = radius * 2 + 1;
            int x = (eye.getBlockX() >> 4) - radius;
            int z = (eye.getBlockZ() >> 4) - radius;

            long stageStarted = prepProfiler.stageStart();
            CaveVisRayData.Grid grid = new CaveVisRayData.Grid(
                x, z, width, cached.bottom, cached.height
            );

            long[][] columns = new long[width * width][];
            for (int dz = 0; dz < width; dz++) {
                for (int dx = 0; dx < width; dx++) {
                    LiveColumn column = cached.columns.get(columnKey(x + dx, z + dz));
                    if (column != null && column.ready) {
                        columns[dz * width + dx] = column.mask;
                    }
                }
            }

            var snapshot = new CaveVisRaycast.Snapshot(grid, columns);
            var selected = directions;
            long seed = ++sequence * 0x9E3779B97F4A7C15L;

            double ex = eye.getX();
            double ey = eye.getY();
            double ez = eye.getZ();

            prepProfiler.stageEnd(CaveVisPrepProfiler.Phase.SNAPSHOT, stageStarted);
            stageStarted = prepProfiler.stageStart();

            CompletableFuture<Scan> future = CompletableFuture.supplyAsync(
                () -> {
                    CaveVisRaycast.Run run = CaveVisRaycast.search(
                        snapshot,
                        ex, ey, ez,
                        radius,
                        selected,
                        seed,
                        nearRevealRadius,
                        profileScan,
                        cancelled
                    );

                    CaveVisVisibility visibility = new CaveVisVisibility(
                        worldId,
                        grid,
                        run.result().sections(),
                        visibilityMargin
                    );

                    return new Scan(scanGeneration, grid, run, visibility, snapshot);
                },
                rays
            );

            prepProfiler.stageEnd(CaveVisPrepProfiler.Phase.SUBMIT, stageStarted);
            return future;
        } finally {
            prepProfiler.end(preparationStarted);
        }
    }

    public void start(CommandSender source, Location center, int radius) {
        start(
            source,
            Objects.requireNonNull(center.getWorld()),
            center.getBlockX() >> 4,
            center.getBlockZ() >> 4,
            radius
        );
    }

    public CompletableFuture<Void> start(
        CommandSender source,
        World world,
        int centerChunkX,
        int centerChunkZ,
        int radius
    ) {
        String worldName = world.getName();
        if (!config.enabled(world)) {
            throw new IllegalArgumentException(
                "CaveVis is disabled in world " + worldName
            );
        }

        if (build != null) {
            throw new IllegalStateException(
                "A build is running; use /cavevis cancel"
            );
        }

        if (radius < 1 || radius > MAX_BUILD_RADIUS_CHUNKS) {
            throw new IllegalArgumentException(
                "Build radius must be 1.." + MAX_BUILD_RADIUS_CHUNKS
            );
        }

        var rules = config.rulesFor(world);
        int bottom = Math.floorDiv(world.getMinHeight(), 16);
        int worldTop = Math.floorDiv(world.getMaxHeight(), 16);
        int top = Math.min(worldTop, rules.occluderTopSection());

        int diameter = radius * 2 + 1;
        int height = top - bottom;

        if (height <= 0) {
            throw new IllegalArgumentException(
                "CaveVis occluder height must be above the minimum height of " + worldName
            );
        }

        if (512L * diameter * diameter * height > MAX_RAW_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException(
                "Raw opacity snapshots exceed the build memory limit"
            );
        }

        BuildArea next = new BuildArea(
            world,
            centerChunkX - radius,
            centerChunkZ - radius,
            diameter,
            bottom,
            height
        );

        Build requested = new Build(source, next);
        build = requested;

        tell(source,
            "Phase 1/2: loading/generating "
                + diameter * diameter
                + " columns; raw opacity snapshots follow each load.");

        return requested.completion.copy();
    }

    public void tick() {
        captures.drain(AUTOMATIC_CAPTURE_BUDGET_NANOS, this::captureLoadedColumn);

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
            stopBuild();

            tell(b.source, "Build failed: " + e.getMessage());

            plugin.getLogger().log(
                java.util.logging.Level.SEVERE,
                "Ray build failed",
                e
            );

            b.completion.completeExceptionally(e);
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

    private long[] capture(Chunk chunk, BuildArea area) {
        ChunkAccess handle =
            ((CraftChunk) chunk).getHandle(ChunkStatus.FULL);

        return capture(
            handle,
            area.world,
            chunk.getX(),
            chunk.getZ(),
            area.bottom,
            area.height,
            false
        );
    }

    private long[] capture(
        ChunkAccess handle,
        World world,
        int chunkX,
        int chunkZ,
        int bottom,
        int height,
        boolean drainBeforeCapture
    ) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                "Terrain capture must run on the server thread"
            );
        }

        LevelChunkSection[] sections = handle.getSections();
        int first = bottom - Math.floorDiv(world.getMinHeight(), 16);
        long[] bits = new long[height * 64];

        for (int s = 0; s < height; s++) {
            LevelChunkSection section = sections[first + s];
            if (section == null) {
                continue;
            }

            var watch = changes.bind(world, chunkX, bottom + s, chunkZ, section);
            if (drainBeforeCapture) {
                // This capture reads the whole section immediately.
                watch.drain(dirtyCells);
            }

            if (section.hasOnlyAir()) {
                continue;
            }

            int sectionWord = s * 64;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).isSolidRender()) {
                            int cell = x | (z << 4) | (y << 8);
                            bits[sectionWord + (cell >>> 6)] |= 1L << (cell & 63);
                        }
                    }
                }
            }
        }

        return bits;
    }

    private void captureLoadedColumn(CaveVisCaptureQueue.Column request) {
        World world = Bukkit.getWorld(request.worldId());
        if (world == null || !config.enabled(world)) {
            return;
        }

        int x = request.x();
        int z = request.z();
        LevelChunk chunk = loaded(world, x, z);
        if (chunk == null) {
            return;
        }

        var rules = config.rulesFor(world);
        int bottom = Math.floorDiv(world.getMinHeight(), 16);
        int worldTop = Math.floorDiv(world.getMaxHeight(), 16);
        int top = Math.min(worldTop, rules.occluderTopSection());
        int height = top - bottom;
        if (height <= 0) {
            return;
        }

        WorldCache cached = worlds.computeIfAbsent(
            request.worldId(), unused -> new WorldCache(bottom, height)
        );

        long key = columnKey(x, z);
        LiveColumn previous = cached.columns.get(key);
        if (previous != null && previous.ready) {
            return;
        }

        Build activeBuild = build;
        int buildIndex = activeBuild != null && activeBuild.data.world.equals(world)
            ? activeBuild.data.column(x, z)
            : -1;

        // A preload that already captured this column still needs its edit notifications.
        boolean staged = buildIndex >= 0 && activeBuild.raw[buildIndex] != null;
        long[] raw = capture(chunk, world, x, z, bottom, height, !staged);

        LiveColumn current = new LiveColumn(CaveVisRayPacking.column(raw, height));
        current.retained = previous != null && previous.retained;
        cached.columns.put(key, current);
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

        BuildArea area = b.data;
        WorldCache cached = worlds.computeIfAbsent(
            area.world.getUID(),
            unused -> new WorldCache(area.bottom, area.height)
        );

        for (int index = 0; index < area.columns.length; index++) {
            int x = area.minX + index % area.diameter;
            int z = area.minZ + index / area.diameter;
            LiveColumn column = new LiveColumn(area.columns[index]);
            column.retained = true;
            cached.columns.put(columnKey(x, z), column);
            captures.remove(area.world.getUID(), x, z);
        }

        generation++;
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

        b.completion.complete(null);
    }

    public void changed(Block block) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Explicit cache invalidation must run on the server thread");
        }
        LevelChunk chunk = loaded(block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
        if (chunk == null) return;
        int y = block.getY();
        if (liveColumn(block.getWorld(), block.getX() >> 4, y >> 4, block.getZ() >> 4) == null
            && (build == null || !relevant(build.data, block.getWorld(), block.getX(), y, block.getZ()))) return;
        var section = chunk.getSection(chunk.getSectionIndex(y));
        if (section == null) return;
        var watch = changes.bind(block.getWorld(), block.getX() >> 4, y >> 4, block.getZ() >> 4, section);
        watch.accept((block.getX() & 15) | ((block.getZ() & 15) << 4) | ((y & 15) << 8));
    }

    public boolean isCurrent(Scan scan) {
        if (scan.generation() != generation || !canSearch()) {
            return false;
        }

        WorldCache cached = worlds.get(scan.visibility().world);
        if (cached == null) {
            return false;
        }

        CaveVisRayData.Grid grid = scan.grid();
        long[][] captured = scan.snapshot().columns();

        for (int dz = 0; dz < grid.diameter; dz++) {
            for (int dx = 0; dx < grid.diameter; dx++) {
                long[] mask = captured[dz * grid.diameter + dx];
                if (mask == null) {
                    continue;
                }

                LiveColumn current = cached.columns.get(
                    columnKey(grid.minX + dx, grid.minZ + dz)
                );

                if (current == null || !current.ready || current.mask != mask) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean relevant(BuildArea c, World world, int x, int y, int z) {
        return c != null && c.world.equals(world)
            && y >= c.bottom * 16 && y < (c.bottom + c.height) * 16
            && c.column(x >> 4, z >> 4) >= 0;
    }

    private LiveColumn liveColumn(World world, int x, int y, int z) {
        WorldCache cached = worlds.get(world.getUID());
        if (cached == null || y < cached.bottom || y >= cached.bottom + cached.height) {
            return null;
        }

        return cached.columns.get(columnKey(x, z));
    }

    private static long columnKey(int x, int z) {
        return (x & 0xffffffffL) | ((long) z << 32);
    }

    private static LevelChunk loaded(World world, int x, int z) {
        return ((CraftWorld) world).getHandle().getChunkSource().getChunkAtIfLoadedImmediately(x, z);
    }

    private void refreshColumn(BuildArea c, LevelChunk chunk) {
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
                if (!watch.hasPending()) continue;

                boolean inLive = liveColumn(watch.world, watch.x, watch.y, watch.z) != null;

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
        WorldCache live = worlds.get(watch.world.getUID());
        LiveColumn column = liveColumn(watch.world, watch.x, watch.y, watch.z);
        Build b = build;
        boolean inBuild = b != null && relevant(b.data, watch.world,
            watch.x << 4, watch.y << 4, watch.z << 4);
        if (column == null && !inBuild) return;

        int liveSection = column == null ? -1 : watch.y - live.bottom;
        long[] packed = column == null ? null : liveCopies.getOrDefault(column, column.mask);
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
                if (!liveCopies.containsKey(column)) {
                    packed = packed.clone();
                    liveCopies.put(column, packed);
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
        for (var entry : liveCopies.entrySet()) {
            entry.getKey().mask = entry.getValue();
        }
        liveCopies.clear();
        rawCopied.clear();
    }

    private void retainWatches() {
        changes.retain(watch -> liveColumn(watch.world, watch.x, watch.y, watch.z) != null
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

        int ordinary = 0;
        int retained = 0;

        for (WorldCache cached : worlds.values()) {
            for (LiveColumn column : cached.columns.values()) {
                if (column.retained) {
                    retained++;
                } else {
                    ordinary++;
                }
            }
        }

        return "Opacity cache: " + ordinary + " automatic, "
            + retained + " preloaded columns.";
    }

    public void cancel() {
        Build stopped = stopBuild();
        if (stopped != null) {
            stopped.completion.cancel(false);
        }
    }

    private Build stopBuild() {
        Build stopped = build;
        build = null;

        if (stopped == null) {
            return null;
        }

        for (Loaded loaded : stopped.ready) {
            if (loaded.ticket) {
                loaded.chunk.removePluginChunkTicket(plugin);
            }
        }
        stopped.ready.clear();

        if (stopped.future != null) {
            stopped.future.cancel(true);
        }
        if (stopped.worker != null) {
            stopped.worker.shutdownNow();
        }

        retainWatches();
        return stopped;
    }

    public void releasePreloaded(UUID worldId) {
        Build stopped = build != null && build.data.world.getUID().equals(worldId)
            ? stopBuild()
            : null;

        WorldCache cached = worlds.get(worldId);
        if (cached != null) {
            releasePins(worldId, cached);

            if (cached.columns.isEmpty()) {
                worlds.remove(worldId);
            }

            retainWatches();
        }

        if (stopped != null) {
            stopped.completion.cancel(false);
        }
    }

    public void clear() {
        Build stopped = stopBuild();

        var iterator = worlds.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            WorldCache cached = entry.getValue();

            releasePins(entry.getKey(), cached);

            if (cached.columns.isEmpty()) {
                iterator.remove();
            }
        }

        generation++;
        retainWatches();

        if (stopped != null) {
            stopped.completion.cancel(false);
        }
    }

    private void releasePins(UUID worldId, WorldCache cached) {
        World world = Bukkit.getWorld(worldId);
        var columns = cached.columns.long2ObjectEntrySet().iterator();

        while (columns.hasNext()) {
            var entry = columns.next();
            LiveColumn column = entry.getValue();

            if (!column.retained) {
                continue;
            }

            column.retained = false;

            long key = entry.getLongKey();
            int x = (int) key;
            int z = (int) (key >>> 32);

            if (world == null || loaded(world, x, z) == null) {
                columns.remove();
            }
        }
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);

        Build stopped = stopBuild();

        captures.clear();
        worlds.clear();
        changes.close();
        rays.shutdownNow();
        liveCopies.clear();
        rawCopied.clear();

        if (stopped != null) {
            stopped.completion.cancel(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (!config.enabled(world)) return;

        int x = event.getChunk().getX();
        int z = event.getChunk().getZ();
        UUID worldId = world.getUID();

        WorldCache cached = worlds.get(worldId);
        if (cached != null) {
            LiveColumn column = cached.columns.get(columnKey(x, z));
            if (column != null) {
                column.ready = false;
            }
        }

        captures.add(worldId, x, z);

        Build activeBuild = build;
        if (activeBuild == null || !activeBuild.data.world.equals(world)) {
            return;
        }

        int index = activeBuild.data.column(x, z);
        if (index < 0 || activeBuild.raw[index] == null) {
            return;
        }

        LevelChunk chunk = (LevelChunk) ((CraftChunk) event.getChunk())
            .getHandle(ChunkStatus.FULL);

        try {
            refreshColumn(activeBuild.data, chunk);
        } finally {
            publishChanges();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        World world = event.getWorld();
        int x = event.getChunk().getX();
        int z = event.getChunk().getZ();

        captures.remove(world.getUID(), x, z);

        WorldCache cached = worlds.get(world.getUID());
        Build activeBuild = build;
        int buildIndex = activeBuild != null && activeBuild.data.world.equals(world)
            ? activeBuild.data.column(x, z)
            : -1;

        if (cached != null && cached.columns.containsKey(columnKey(x, z))) {
            LiveColumn column = cached.columns.get(columnKey(x, z));
            boolean staged = buildIndex >= 0 && activeBuild.raw[buildIndex] != null;

            try {
                if (column.retained || staged) {
                    LevelChunk chunk = loaded(world, x, z);
                    if (chunk != null) {
                        refreshRetainedColumn(world, x, z, cached, chunk);
                    }
                    publishChanges();
                }
            } finally {
                publishChanges();
                changes.unbindColumn(world, x, z, cached.bottom, cached.height);
            }
        } else if (buildIndex >= 0) {
            BuildArea area = activeBuild.data;
            try {
                if (activeBuild.raw[buildIndex] != null) {
                    LevelChunk chunk = loaded(world, x, z);
                    if (chunk != null) {
                        refreshColumn(area, chunk);
                    }
                }
            } finally {
                publishChanges();
                changes.unbindColumn(world, x, z, area.bottom, area.height);
            }
        }

        if (buildIndex >= 0) {
            activeBuild.data.observed[buildIndex] = null;
        }

        if (cached != null) {
            long key = columnKey(x, z);
            LiveColumn column = cached.columns.get(key);
            if (column != null && !column.retained) {
                cached.columns.remove(key);
            }

            if (cached.columns.isEmpty()) {
                worlds.remove(world.getUID());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        UUID worldId = world.getUID();

        captures.removeWorld(worldId);
        Build stopped = build != null && build.data.world.equals(world)
            ? stopBuild()
            : null;

        if (config.removeTemporaryWorld(worldId)) {
            worlds.remove(worldId);
        } else {
            WorldCache cached = worlds.get(worldId);
            if (cached != null) {
                cached.columns.values().removeIf(column -> !column.retained);
                if (cached.columns.isEmpty()) {
                    worlds.remove(worldId);
                }
            }
        }

        changes.retain(watch -> !watch.world.getUID().equals(worldId));

        if (stopped != null) {
            stopped.completion.cancel(false);
        }
    }

    private void refreshRetainedColumn(World world, int x, int z, WorldCache cached, LevelChunk chunk) {
        int first = chunk.getSectionIndex(cached.bottom << 4);
        var sections = chunk.getSections();
        for (int i = 0; i < cached.height; i++) {
            LevelChunkSection section = sections[first + i];
            if (section != null) {
                applyWatch(changes.bind(world, x, cached.bottom + i, z, section), section);
            }
        }
    }

    private static void tell(CommandSender sender, String text) {
        if (sender != null) {
            sender.sendMessage("[CaveVis] " + text);
        }
    }

    private static final class WorldCache {
        final int bottom;
        final int height;
        final Long2ObjectOpenHashMap<LiveColumn> columns = new Long2ObjectOpenHashMap<>();

        WorldCache(int bottom, int height) {
            this.bottom = bottom;
            this.height = height;
        }
    }

    private static final class LiveColumn {
        long[] mask;
        boolean ready = true;
        boolean retained;

        LiveColumn(long[] mask) {
            this.mask = mask;
        }
    }

    private static final class BuildArea {
        final World world;
        final int minX, minZ, diameter, bottom, height;
        final long[][] columns;
        final CaveVisStorageChanges.Watch[][] observed;

        BuildArea(
            World world,
            int minX,
            int minZ,
            int diameter,
            int bottom,
            int height
        ) {
            this.world = world;
            this.minX = minX;
            this.minZ = minZ;
            this.diameter = diameter;
            this.bottom = bottom;
            this.height = height;

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
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        final CommandSender source;
        final BuildArea data;
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

        Build(CommandSender source, BuildArea data) {
            this.source = source;
            this.data = data;
            raw = new long[data.columns.length][];
        }
    }
}