package me.pintobox.pintoAntiCheat.cavevis.core;

import me.pintobox.pintoAntiCheat.cavevis.api.CaveVisApi;
import me.pintobox.pintoAntiCheat.cavevis.cache.CaveVisRayCache;
import me.pintobox.pintoAntiCheat.cavevis.delivery.CaveVisEntityVisibility;
import me.pintobox.pintoAntiCheat.cavevis.delivery.CaveVisTerrainDelivery;
import me.pintobox.pintoAntiCheat.cavevis.profiling.CaveVisMainProfiler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaveVis implements CaveVisApi, Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> pendingScans = new LinkedHashSet<>();
    private final CaveVisMainProfiler mainProfiler = new CaveVisMainProfiler();

    private CaveVisConfiguration config;
    private CaveVisRayCache cache;
    private CaveVisTerrainDelivery terrain;
    private CaveVisEntityVisibility entities;
    private BukkitTask tickTask;
    private long ticks;

    private int profilingPlayers;

    public CaveVis(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        CaveVisSettings.validate();
        config = CaveVisConfiguration.load(plugin);

        PluginCommand command = Objects.requireNonNull(
            plugin.getCommand("cavevis"),
            "Missing cavevis command in plugin.yml"
        );

        cache = new CaveVisRayCache(plugin, config);
        terrain = new CaveVisTerrainDelivery(plugin, config);
        entities = new CaveVisEntityVisibility(plugin, terrain, config);

        CaveVisCommands commands = new CaveVisCommands(this);
        command.setExecutor(commands);
        command.setTabCompleter(commands);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        tickTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::tick, 1L, 1L
        );

        Bukkit.getServicesManager().register(
            CaveVisApi.class, this, plugin, ServicePriority.Normal
        );

        plugin.getLogger().info("Cave visibility initialized.");
    }

    @Override
    public void close() {
        Bukkit.getServicesManager().unregister(CaveVisApi.class, this);

        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        HandlerList.unregisterAll(this);

        for (Session session : sessions.values()) {
            cancel(session);
        }

        sessions.clear();
        pendingScans.clear();

        if (entities != null) entities.close();
        if (terrain != null) terrain.close();

        CaveVisRayCache closingCache = cache;
        cache = null;

        if (closingCache != null) {
            closingCache.close();
        }
    }

    @Override
    public void enableTemporaryWorld(World world, WorldRules rules) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                "Call CaveVis.enableTemporaryWorld on the server thread"
            );
        }

        if (cache == null) {
            throw new IllegalStateException("CaveVis is not running");
        }

        if (world == null || Bukkit.getWorld(world.getUID()) != world) {
            throw new IllegalArgumentException("The temporary world must be loaded");
        }

        if (!world.getPlayers().isEmpty()) {
            throw new IllegalStateException(
                "Enable CaveVis before players enter " + world.getName()
            );
        }

        config.enableTemporaryWorld(world, rules);
        terrain.refreshWorldPolicy(world);
        cache.queueLoadedChunks(world);
    }

    @Override
    public CompletableFuture<Void> preload(
        World world,
        int centerChunkX,
        int centerChunkZ,
        int radiusChunks
    ) {
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Call CaveVis.preload on the server thread")
            );
        }

        if (cache == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("CaveVis is not running")
            );
        }

        if (world == null || Bukkit.getWorld(world.getUID()) != world) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("The preload world must be loaded")
            );
        }

        try {
            return cache.start(null, world, centerChunkX, centerChunkZ, radiusChunks);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void releasePreloaded(UUID worldId) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                "Call CaveVis.releasePreloaded on the server thread"
            );
        }

        if (cache == null) {
            throw new IllegalStateException("CaveVis is not running");
        }

        cache.releasePreloaded(Objects.requireNonNull(worldId, "worldId"));
    }

    @Override
    public boolean hasReceivedSection(
        Player viewer, int sectionX, int sectionY, int sectionZ
    ) {
        requireVisibilityQueryThread();

        return cache != null
            && terrain.hasReceivedSection(viewer, sectionX, sectionY, sectionZ);
    }

    @Override
    public ReceivedSections getReceivedSections(Player viewer) {
        requireVisibilityQueryThread();

        if (cache == null) {
            return new ReceivedSections(viewer.getWorld().getUID(), List.of());
        }

        return terrain.getReceivedSections(viewer);
    }

    private static void requireVisibilityQueryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                "CaveVis visibility queries must run on the server thread"
            );
        }
    }

    void build(CommandSender sender, Player player, int radius) {
        cache.start(sender, player.getLocation(), radius);
    }

    void cancelBuild() {
        cache.cancel();
    }

    void clear() {
        cache.clear();
        pendingScans.clear();
    }

    String profile(Player player, String action) {
        Session session = sessions.computeIfAbsent(
            player.getUniqueId(), id -> new Session(player)
        );

        switch (action) {
            case "on" -> setProfiling(session, true);
            case "off" -> {
                setProfiling(session, false);
                player.sendActionBar(Component.empty());
            }
            case "reset" -> {
                session.stats = new Stats();
                session.profileScan = false;
                session.preparationNanos = 0L;
                mainProfiler.reset();
                cache.preparationProfile().reset();
            }
            case "detail" -> {
                if (!session.display) {
                    return "Use /cavevis profile on before enabling detailed timings.";
                }
                boolean enabled = cache.preparationProfile().toggle();
                return "Detailed preparation timings: " + (enabled ? "ON" : "OFF");
            }
            default -> throw new IllegalArgumentException(
                "Usage: /cavevis profile <on|off|reset|detail>"
            );
        }

        return "Profile " + action + "; action bar "
            + (session.display ? "ON" : "OFF") + ".";
    }

    List<String> stats(CommandSender sender, boolean tickStats) {
        if (tickStats) {
            if (profilingPlayers == 0) {
                return List.of("Tick profiling is off. Use /cavevis profile on.");
            }
            return List.of(
                mainProfiler.summary(),
                mainProfiler.breakdown(),
                mainProfiler.maxima()
            );
        }

        List<String> lines = new ArrayList<>();
        lines.add(cache.status());

        if (sender instanceof Player player) {
            Session session = sessions.get(player.getUniqueId());
            if (session != null && session.stats.count > 0) {
                lines.add(session.stats.text());
                lines.add(session.stats.prepText());
                lines.add(session.stats.sections + " approved 16^3 sections");
            } else {
                lines.add("No completed scans yet.");
            }
        }

        for (String line : cache.preparationProfile().report()) {
            lines.add("Shared " + line);
        }

        return lines;
    }

    private void setProfiling(Session session, boolean enabled) {
        if (session.display == enabled) return;

        session.display = enabled;
        session.stats = new Stats();
        session.profileScan = false;
        session.preparationNanos = 0;

        profilingPlayers += enabled ? 1 : -1;
        if (profilingPlayers == 0) {
            mainProfiler.reset();
            cache.preparationProfile().setEnabled(false);
        }
    }

    private long startPhaseTiming() {
        return profilingPlayers > 0 ? System.nanoTime() : 0L;
    }

    private void finishPhaseTiming(CaveVisMainProfiler.Phase phase, long started) {
        if (started != 0L && profilingPlayers > 0) {
            mainProfiler.add(phase, System.nanoTime() - started);
        }
    }

    private void tick() {
        long tickStarted = startPhaseTiming();
        if (tickStarted != 0L) {
            mainProfiler.beginTick();
        }

        try {
            ticks++;

            long phaseStarted = startPhaseTiming();
            cache.tick();
            finishPhaseTiming(CaveVisMainProfiler.Phase.CACHE, phaseStarted);

            int interval = Math.max(1, CaveVisSettings.SEARCH_INTERVAL_TICKS);

            for (var entry : sessions.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player == null) continue;

                Session s = entry.getValue();

                if (!player.getWorld().getUID().equals(s.world)) {
                    cancel(s);
                    s.world = player.getWorld().getUID();
                    s.stats = new Stats();
                    s.force = s.automatic;
                    s.failures = 0;
                    s.retryAtTick = 0;
                }

                long generation = cache.generation();

                if (s.cacheGeneration != generation) {
                    cancel(s);

                    s.cacheGeneration = generation;
                    s.stats = new Stats();
                    s.failures = 0;
                    s.retryAtTick = 0;

                    if (terrain.invalidateScan(player)) {
                        s.automatic = true;
                        s.force = true;
                    }
                }

                if (terrain.resumeAfterReset(player)) {
                    cancel(s);

                    s.stats = new Stats();
                    s.automatic = true;
                    s.force = true;
                    s.failures = 0;
                    s.retryAtTick = 0;
                }

                if (s.future != null && s.future.isDone()) {
                    try {
                        CaveVisRayCache.Scan scan = s.future.join();

                        if (!cache.isCurrent(scan)) {
                            s.force = true;
                        } else if (cache.ready(player.getEyeLocation())) {
                            s.failures = 0;
                            s.retryAtTick = 0;

                            var run = scan.run();

                            if (s.profileScan) {
                                s.stats.add(
                                    run.result().nanos(),
                                    s.preparationNanos,
                                    run.result().sections().cardinality()
                                );
                            }

                            if (s.scanChunkX == (player.getLocation().getBlockX() >> 4)
                                && s.scanChunkZ == (player.getLocation().getBlockZ() >> 4)
                                && s.scanSectionY == (player.getLocation().getBlockY() >> 4)
                                && player.getWorld().getUID().equals(s.scanWorld)) {

                                long publishStarted = startPhaseTiming();
                                try {
                                    terrain.publish(player, scan, s.terrainVersion);
                                } finally {
                                    finishPhaseTiming(
                                        CaveVisMainProfiler.Phase.PUBLISH,
                                        publishStarted
                                    );
                                }
                            } else {
                                s.force = true;
                            }
                        }
                    } catch (RuntimeException e) {
                        scanFailed(player, s, e);
                    }

                    s.future = null;
                }

                if ((s.automatic || s.force)
                    && s.future == null
                    && ticks >= s.retryAtTick
                    && cache.canSearch()
                    && cache.ready(player.getEyeLocation())
                    && (s.force
                    || terrain.needsScan(player)
                    || ticks - s.lastScan >= interval)) {
                    pendingScans.add(entry.getKey());
                }

                if (ticks % 10 == 0 && s.display && s.stats.count > 0) {
                    player.sendActionBar(Component.text(
                        String.format(
                            Locale.ROOT,
                            "Rays %.3f ms/scan | Prep %.3f ms/scan",
                            s.stats.last / 1e6,
                            s.stats.lastPrep / 1e6
                        )
                    ));
                }
            }

            startQueuedScans();

            phaseStarted = startPhaseTiming();
            terrain.tick();
            finishPhaseTiming(CaveVisMainProfiler.Phase.DELIVERY, phaseStarted);

            phaseStarted = startPhaseTiming();
            entities.tick();
            finishPhaseTiming(CaveVisMainProfiler.Phase.ENTITIES, phaseStarted);
        } finally {
            if (tickStarted != 0L && profilingPlayers > 0) {
                mainProfiler.endTick(System.nanoTime() - tickStarted);
            }
        }
    }

    private void startScan(Player player, Session session) {
        if (!cache.canSearch()) {
            session.force = true;
            return;
        }

        session.profileScan = session.display;
        session.preparationNanos = 0L;
        long started = startPhaseTiming();

        try {
            session.cancelled = new AtomicBoolean();
            session.world = player.getWorld().getUID();
            session.terrainVersion = terrain.beginSearch(player);
            session.scanWorld = session.world;

            Location position = player.getLocation();
            session.scanChunkX = position.getBlockX() >> 4;
            session.scanChunkZ = position.getBlockZ() >> 4;
            session.scanSectionY = position.getBlockY() >> 4;

            session.future = cache.search(
                player.getEyeLocation(),
                config.rulesFor(player.getWorld()).searchRadiusChunks(),
                session.profileScan,
                session.cancelled
            );

            session.lastScan = ticks;
            session.cacheGeneration = cache.generation();
            session.force = false;
        } catch (RuntimeException failure) {
            scanFailed(player, session, failure);
        } finally {
            if (started != 0L && profilingPlayers > 0) {
                long elapsed = System.nanoTime() - started;
                if (session.profileScan) {
                    session.preparationNanos = elapsed;
                }
                mainProfiler.add(CaveVisMainProfiler.Phase.PREPARE, elapsed);
            }
        }
    }

    private void startQueuedScans() {
        if (!cache.canSearch()) return;

        int starts = 0;
        var requests = pendingScans.iterator();

        while (starts < config.scanStartsPerTick && requests.hasNext()) {
            UUID playerId = requests.next();
            Session session = sessions.get(playerId);
            Player player = Bukkit.getPlayer(playerId);

            if (session == null || player == null
                || (!session.automatic && !session.force)) {
                requests.remove();
                continue;
            }

            if (session.future != null
                || ticks < session.retryAtTick
                || !cache.ready(player.getEyeLocation())) {
                continue;
            }

            requests.remove();
            starts++;
            startScan(player, session);
        }
    }

    private void scanFailed(Player player, Session session, RuntimeException failure) {
        cancel(session);

        session.failures = Math.min(1_000_000, session.failures + 1);
        long delay = (long) CaveVisSettings.SEARCH_RETRY_DELAY_TICKS
            << Math.min(3, session.failures - 1);

        session.retryAtTick = ticks + delay;
        session.force = true;

        if (session.failures == 1) {
            message(player, "Visibility scan failed; retrying automatically.");
        }

        if (session.failures == 1 || session.failures == 16) {
            plugin.getLogger().log(
                java.util.logging.Level.WARNING,
                "Visibility scan failed for " + player.getName()
                    + "; retry scheduled in " + delay + " ticks",
                failure
            );
        }
    }

    private static void cancel(Session session) {
        if (session.cancelled != null) {
            session.cancelled.set(true);
        }
        if (session.future != null) {
            session.future.cancel(false);
        }
        session.future = null;
    }

    private static void message(CommandSender sender, String text) {
        sender.sendMessage("[CaveVis] " + text);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Session session = sessions.computeIfAbsent(
            player.getUniqueId(), unused -> new Session(player)
        );
        session.automatic = true;
        session.force = true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        force(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        force(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Session session = sessions.remove(playerId);

        if (session != null) {
            setProfiling(session, false);
            cancel(session);
        }
        pendingScans.remove(playerId);
    }

    private void force(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.automatic) {
            session.force = true;
        }
    }

    int defaultPreloadRadius() {
        return config.defaultPreloadRadius;
    }

    private static final class Session {
        UUID world;
        boolean automatic;
        boolean display;
        boolean profileScan;
        boolean force;
        long lastScan;
        long terrainVersion;
        long preparationNanos;
        long cacheGeneration = -1;
        long retryAtTick;
        int failures;

        UUID scanWorld;
        int scanChunkX;
        int scanChunkZ;
        int scanSectionY;

        AtomicBoolean cancelled;
        CompletableFuture<CaveVisRayCache.Scan> future;
        Stats stats = new Stats();

        Session(Player player) {
            world = player.getWorld().getUID();
        }
    }

    private static final class Stats {
        private final long[] raySamples = new long[32];
        private final long[] prepSamples = new long[32];

        int cursor;
        int count;
        int sections;
        long raySum;
        long prepSum;
        long last;
        long lastPrep;

        void add(long rayNanos, long prepNanos, int sections) {
            if (count == raySamples.length) {
                raySum -= raySamples[cursor];
                prepSum -= prepSamples[cursor];
            } else {
                count++;
            }

            raySamples[cursor] = rayNanos;
            prepSamples[cursor] = prepNanos;
            raySum += rayNanos;
            prepSum += prepNanos;
            cursor = (cursor + 1) % raySamples.length;

            last = rayNanos;
            lastPrep = prepNanos;
            this.sections = sections;
        }

        String text() {
            return String.format(
                Locale.ROOT,
                "Rays %.3f ms/scan | avg %.3f | max %.3f (%d scans)",
                last / 1e6, mean(raySum), maximum(raySamples), count
            );
        }

        String prepText() {
            return String.format(
                Locale.ROOT,
                "Prep %.3f ms/scan | avg %.3f | max %.3f (%d scans)",
                lastPrep / 1e6, mean(prepSum), maximum(prepSamples), count
            );
        }

        private double mean(long sum) {
            return count == 0 ? 0.0 : sum / (double) count / 1e6;
        }

        private double maximum(long[] samples) {
            long max = 0;
            for (int i = 0; i < count; i++) {
                max = Math.max(max, samples[i]);
            }
            return max / 1e6;
        }
    }
}