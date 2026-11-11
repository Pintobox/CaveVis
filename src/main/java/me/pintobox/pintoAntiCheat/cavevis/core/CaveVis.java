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

    private CaveVisRayCache cache;
    private CaveVisTerrainDelivery terrain;
    private CaveVisEntityVisibility entities;
    private BukkitTask tickTask;
    private long ticks;

    public CaveVis(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        CaveVisSettings.validate();

        PluginCommand command = Objects.requireNonNull(
            plugin.getCommand("cavevis"),
            "Missing cavevis command in plugin.yml"
        );

        cache = new CaveVisRayCache(plugin);
        terrain = new CaveVisTerrainDelivery(plugin);
        entities = new CaveVisEntityVisibility(plugin, terrain);

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

    void setRays(int count) {
        cache.setRays(count);
    }

    void requestScan(Player player) {
        if (!cache.ready(player.getEyeLocation())) {
            throw new IllegalStateException(
                "Finish /cavevis raybuild around your position first"
            );
        }

        UUID playerId = player.getUniqueId();
        Session session = sessions.computeIfAbsent(
            playerId, id -> new Session(player)
        );
        cancel(session);

        session.automatic = true;
        session.notice = true;
        session.failures = 0;
        session.retryAtTick = 0;
        session.force = true;

        pendingScans.add(playerId);
    }

    String profile(Player player, String action) {
        Session session = sessions.computeIfAbsent(
            player.getUniqueId(), id -> new Session(player)
        );

        switch (action) {
            case "on" -> session.display = true;
            case "off" -> {
                session.display = false;
                player.sendActionBar(Component.empty());
            }
            case "reset" -> {
                session.stats = new Stats();
                mainProfiler.reset();
                cache.preparationProfile().reset();
            }
            case "detail" -> {
                boolean enabled = cache.preparationProfile().toggle();
                return "Detailed preparation timings: " + (enabled ? "ON" : "OFF");
            }
            case "toggle" -> {
                session.display = !session.display;
                if (!session.display) {
                    player.sendActionBar(Component.empty());
                }
            }
            default -> throw new IllegalArgumentException(
                "Usage: /cavevis profile [on|off|reset|detail|toggle]"
            );
        }

        return "Profile " + action + "; action bar "
            + (session.display ? "ON" : "OFF") + ".";
    }

    List<String> stats(CommandSender sender, boolean tickStats) {
        if (tickStats) {
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

    private void tick() {
        long tickStarted = System.nanoTime();
        mainProfiler.beginTick();

        try {
            ticks++;

            long phaseStarted = System.nanoTime();
            cache.tick();
            mainProfiler.add(
                CaveVisMainProfiler.Phase.CACHE, System.nanoTime() - phaseStarted
            );

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
                    s.notice = false;
                    s.failures = 0;
                    s.retryAtTick = 0;
                }

                long generation = cache.generation();

                if (s.cacheGeneration != generation) {
                    cancel(s);

                    s.cacheGeneration = generation;
                    s.stats = new Stats();
                    s.notice = s.notice && pendingScans.contains(entry.getKey());
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
                    s.notice = false;
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

                            s.stats.add(
                                run.result().nanos(),
                                s.preparationNanos,
                                run.result().sections().cardinality()
                            );

                            if (s.scanChunkX == (player.getLocation().getBlockX() >> 4)
                                && s.scanChunkZ == (player.getLocation().getBlockZ() >> 4)
                                && s.scanSectionY == (player.getLocation().getBlockY() >> 4)
                                && player.getWorld().getUID().equals(s.scanWorld)) {

                                long publishStarted = System.nanoTime();
                                try {
                                    terrain.publish(player, scan, s.terrainVersion);
                                } finally {
                                    mainProfiler.add(
                                        CaveVisMainProfiler.Phase.PUBLISH,
                                        System.nanoTime() - publishStarted
                                    );
                                }
                            } else {
                                s.force = true;
                            }

                            if (s.notice) {
                                message(player,
                                    s.stats.text()
                                        + " | " + run.voxels()
                                        + " voxel visits | "
                                        + s.stats.sections
                                        + " approved 16^3 sections"
                                        + String.format(
                                        Locale.ROOT,
                                        " | prep %.3f ms/scan",
                                        s.preparationNanos / 1e6
                                    ));
                            }
                        }
                    } catch (RuntimeException e) {
                        scanFailed(player, s, e);
                    }

                    s.future = null;
                    s.notice = false;
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

            phaseStarted = System.nanoTime();
            terrain.tick();
            mainProfiler.add(
                CaveVisMainProfiler.Phase.DELIVERY, System.nanoTime() - phaseStarted
            );

            phaseStarted = System.nanoTime();
            entities.tick();
            mainProfiler.add(
                CaveVisMainProfiler.Phase.ENTITIES, System.nanoTime() - phaseStarted
            );
        } finally {
            mainProfiler.endTick(System.nanoTime() - tickStarted);
        }
    }

    private void startScan(Player player, Session session) {
        if (!cache.canSearch()) {
            session.force = true;
            return;
        }

        long started = System.nanoTime();

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
                CaveVisSettings.SEARCH_RADIUS_CHUNKS,
                session.cancelled
            );

            session.lastScan = ticks;
            session.cacheGeneration = cache.generation();
            session.force = false;
        } catch (RuntimeException failure) {
            scanFailed(player, session, failure);
        } finally {
            session.preparationNanos = System.nanoTime() - started;
            mainProfiler.add(
                CaveVisMainProfiler.Phase.PREPARE, session.preparationNanos
            );
        }
    }

    private void startQueuedScans() {
        if (!cache.canSearch()) return;

        int starts = 0;
        var requests = pendingScans.iterator();

        while (starts < CaveVisSettings.SCAN_STARTS_PER_TICK && requests.hasNext()) {
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

        if (session != null) cancel(session);
        pendingScans.remove(playerId);
    }

    private void force(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.automatic) {
            session.force = true;
        }
    }

    private static final class Session {
        UUID world;
        boolean automatic;
        boolean display = true;
        boolean force;
        boolean notice;
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