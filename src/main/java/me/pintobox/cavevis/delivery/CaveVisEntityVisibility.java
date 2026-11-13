package me.pintobox.cavevis.delivery;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import me.pintobox.cavevis.core.CaveVisConfiguration;
import me.pintobox.cavevis.core.CaveVisSettings;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public final class CaveVisEntityVisibility
    implements Listener, AutoCloseable {

    private final JavaPlugin plugin;
    private final CaveVisTerrainDelivery terrain;
    private final CaveVisConfiguration config;
    private final Map<UUID, Watch> watches = new HashMap<>();
    private final Map<UUID, Set<Plugin>> alwaysVisibleOwners = new HashMap<>();

    private final List<List<Player>> viewerBuckets = new ArrayList<>();
    private int nextViewerBucket;

    public CaveVisEntityVisibility(
        JavaPlugin plugin,
        CaveVisTerrainDelivery terrain,
        CaveVisConfiguration config
    ) {
        this.plugin = plugin;
        this.terrain = terrain;
        this.config = config;

        int interval = Math.max(1, CaveVisSettings.ENTITY_VISIBILITY_INTERVAL_TICKS);
        for (int i = 0; i < interval; i++) {
            viewerBuckets.add(new ArrayList<>());
        }

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            addViewer(viewer);
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void track(PlayerTrackEntityEvent event) {
        Entity entity = event.getEntity();
        Player viewer = event.getPlayer();

        if (!terrain.active(viewer)
            || !(entity instanceof Player
            ? config.playerCulling
            : config.rulesFor(viewer.getWorld()).entityCulling())) {
            return;
        }

        Watch watch = watch(viewer);

        Entry entry = watch.entries.computeIfAbsent(
            entity.getUniqueId(),
            unused -> new Entry(entity)
        );

        long revision = terrain.deliveryRevision(
            viewer.getUniqueId()
        );

        if (allowed(viewer, entry, revision)) {
            if (entity instanceof Player && entry.blocked) {
                clearNameTag(viewer, entry);
                entry.blocked = false;
            }
            return;
        }

        event.setCancelled(true);
        entry.blocked = true;

        if (entity instanceof Player) {
            entry.playerTrackAttempted = true;
        } else if (entity.isVisibleByDefault() && !entry.apiHidden) {
            entry.apiHidden = true;
            viewer.hideEntity(plugin, entity);
        }

        /*
         * Paper adds the viewer to seenBy before firing this event.
         * Cancelling the spawn must not leave it receiving updates.
         */
        ChunkMap.TrackedEntity tracker = tracker(viewer, entity);

        if (tracker != null) {
            tracker.seenBy.remove(
                ((CraftPlayer) viewer).getHandle().connection
            );
        }
    }

    private void addViewer(Player viewer) {
        List<Player> smallest = viewerBuckets.getFirst();

        for (List<Player> bucket : viewerBuckets) {
            if (bucket.size() < smallest.size()) {
                smallest = bucket;
            }
        }

        smallest.add(viewer);
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        addViewer(event.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        Player viewer = event.getPlayer();

        for (List<Player> bucket : viewerBuckets) {
            if (bucket.remove(viewer)) {
                break;
            }
        }

        watches.remove(viewer.getUniqueId());
    }

    public void tick() {
        List<Player> viewers = viewerBuckets.get(nextViewerBucket);
        nextViewerBucket = (nextViewerBucket + 1) % viewerBuckets.size();

        for (Player viewer : List.copyOf(viewers)) {
            if (!viewer.isOnline()) {
                continue;
            }
            boolean cullMobs = config.rulesFor(viewer.getWorld()).entityCulling();

            if (!terrain.active(viewer) || (!cullMobs && !config.playerCulling)) {
                Watch old = watches.remove(viewer.getUniqueId());

                if (old != null) {
                    restore(viewer, old);
                }

                continue;
            }

            Watch watch = watch(viewer);

            /*
             * Seed existing entities once when filtering starts.
             * Later arrivals are registered by the tracking event.
             */
            if (!watch.seeded) {
                watch.seeded = true;

                var handle = ((CraftPlayer) viewer).getHandle();

                handle.level().getChunkSource().chunkMap
                    .forEachEntityTrackedBy(handle, entity -> {
                        Entity bukkit = entity.getBukkitEntity();

                        if (bukkit instanceof Player ? config.playerCulling : cullMobs) {
                            watch.entries.computeIfAbsent(
                                bukkit.getUniqueId(),
                                unused -> new Entry(bukkit)
                            );
                        }
                    });
            }

            long revision = terrain.deliveryRevision(
                viewer.getUniqueId()
            );

            /*
             * Showing/hiding can fire tracking events, so iterate
             * a reusable snapshot rather than the live map.
             */
            watch.scratch.clear();

            for (Entry entry : watch.entries.values()) {
                watch.scratch.add(entry);
            }

            for (Entry entry : watch.scratch) {
                Entity entity = entry.entity;

                if (!entity.isValid()
                    || !entity.getWorld().getUID().equals(watch.world)
                    || !viewer.isChunkSent(
                    ((CraftEntity) entity).getHandle()
                        .chunkPosition().toLong()
                )) {
                    release(viewer, entry);

                    watch.entries.remove(
                        entity.getUniqueId(),
                        entry
                    );

                    continue;
                }

                if (entity instanceof Player target) {
                    if (!updatePlayerEntry(viewer, target, entry, revision)) {
                        watch.entries.remove(target.getUniqueId(), entry);
                    }
                    continue;
                }

                boolean show = allowed(viewer, entry, revision);

                if (show) {
                    if (entry.blocked) {
                        release(viewer, entry);
                    }
                } else if (!entry.blocked) {
                    entry.blocked = true;

                    if (entity.isVisibleByDefault()) {
                        entry.apiHidden = true;
                        viewer.hideEntity(plugin, entity);
                    } else {
                        /*
                         * Preserve compatibility with entities another
                         * plugin explicitly showed despite their
                         * visible-by-default flag being false.
                         */
                        ChunkMap.TrackedEntity tracker =
                            tracker(viewer, entity);

                        if (tracker != null) {
                            tracker.removePlayer(
                                ((CraftPlayer) viewer).getHandle()
                            );
                        }
                    }
                }
            }

            watch.scratch.clear();
        }
    }

    public void setAlwaysVisible(Plugin owner, Entity entity, boolean visible) {
        if (entity instanceof Player) {
            return;
        }

        UUID id = entity.getUniqueId();

        if (visible) {
            if (!owner.isEnabled() || !entity.isValid()) {
                throw new IllegalArgumentException(
                    "An enabled plugin and a spawned entity are required"
                );
            }

            alwaysVisibleOwners.computeIfAbsent(id, ignored -> new HashSet<>())
                .add(owner);
            return;
        }

        Set<Plugin> owners = alwaysVisibleOwners.get(id);
        if (owners != null) {
            owners.remove(owner);
            if (owners.isEmpty()) {
                alwaysVisibleOwners.remove(id);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveFromWorldEvent event) {
        Entity entity = event.getEntity();
        UUID id = entity.getUniqueId();
        alwaysVisibleOwners.remove(id);

        if (!(entity instanceof Player)) {
            return;
        }

        for (var watched : watches.entrySet()) {
            Watch watch = watched.getValue();
            Entry entry = watch.entries.get(id);

            if (entry == null || entry.entity.getEntityId() != entity.getEntityId()) {
                continue;
            }

            watch.entries.remove(id);

            Player viewer = Bukkit.getPlayer(watched.getKey());
            if (viewer != null) {
                clearNameTag(viewer, entry);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisabled(PluginDisableEvent event) {
        Plugin owner = event.getPlugin();

        alwaysVisibleOwners.values().removeIf(owners -> {
            owners.remove(owner);
            return owners.isEmpty();
        });
    }

    private Watch watch(Player viewer) {
        UUID id = viewer.getUniqueId();
        UUID world = viewer.getWorld().getUID();

        Watch old = watches.get(id);

        if (old != null && !old.world.equals(world)) {
            watches.remove(id);
            restore(viewer, old);
            old = null;
        }

        if (old == null) {
            old = new Watch(world);
            watches.put(id, old);
        }

        return old;
    }

    private boolean allowed(
        Player viewer,
        Entry entry,
        long revision
    ) {
        Entity entity = entry.entity;

        if (entity.isGlowing()
            || (entity instanceof LivingEntity living
            && living.hasPotionEffect(PotionEffectType.GLOWING))) {
            return true;
        }

        if (entity instanceof Player target) {
            if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || target.equals(viewer.getSpectatorTarget())) {
                return true;
            }
        } else if ((entity instanceof TextDisplay text && text.isSeeThrough())
            || entity.isCustomNameVisible()
            || alwaysVisibleOwners.containsKey(entity.getUniqueId())) {
            return true;
        }

        AABB box = ((CraftEntity) entry.entity)
            .getHandle().getBoundingBox();

        int minX = section(box.minX);
        int minY = section(box.minY);
        int minZ = section(box.minZ);

        int maxX = section(
            box.maxX > box.minX
                ? Math.nextDown(box.maxX)
                : box.maxX
        );

        int maxY = section(
            box.maxY > box.minY
                ? Math.nextDown(box.maxY)
                : box.maxY
        );

        int maxZ = section(
            box.maxZ > box.minZ
                ? Math.nextDown(box.maxZ)
                : box.maxZ
        );

        if (entry.checked
            && entry.revision == revision
            && entry.minX == minX
            && entry.minY == minY
            && entry.minZ == minZ
            && entry.maxX == maxX
            && entry.maxY == maxY
            && entry.maxZ == maxZ) {
            return entry.allowed;
        }

        entry.checked = true;
        entry.revision = revision;

        entry.minX = minX;
        entry.minY = minY;
        entry.minZ = minZ;

        entry.maxX = maxX;
        entry.maxY = maxY;
        entry.maxZ = maxZ;

        entry.allowed = terrain.areaDelivered(
            viewer.getUniqueId(),
            entry.entity.getWorld().getUID(),
            minX, minY, minZ,
            maxX, maxY, maxZ
        );

        return entry.allowed;
    }

    private boolean updatePlayerEntry(
        Player viewer, Player target, Entry entry, long revision
    ) {
        if (allowed(viewer, entry, revision)) {
            if (entry.blocked) {
                release(viewer, entry);
            }
            return true;
        }

        ChunkMap.TrackedEntity tracker = tracker(viewer, target);
        if (tracker == null || !viewer.canSee(target)) {
            clearNameTag(viewer, entry);
            return false;
        }

        var observer = ((CraftPlayer) viewer).getHandle();
        if (!entry.blocked) {
            entry.blocked = true;
            tracker.removePlayer(observer);
        }

        // If Paper would track this player, track() marks the attempt
        // and cancels the real player's spawn.
        entry.playerTrackAttempted = false;
        tracker.updatePlayer(observer);

        if (!entry.playerTrackAttempted) {
            clearNameTag(viewer, entry);
            return false;
        }

        entry.nameTag = CaveVisPlayerNameTag.update(
            viewer, target, entry.nameTag,
            CaveVisSettings.ENTITY_VISIBILITY_INTERVAL_TICKS
        );
        return true;
    }

    private static void clearNameTag(Player viewer, Entry entry) {
        CaveVisPlayerNameTag.remove(viewer, entry.nameTag);
        entry.nameTag = null;
    }

    private void release(Player viewer, Entry entry) {
        clearNameTag(viewer, entry);
        boolean apiHidden = entry.apiHidden;

        entry.apiHidden = false;
        entry.blocked = false;
        entry.checked = false;

        if (apiHidden) {
            viewer.showEntity(plugin, entry.entity);
        } else if (entry.entity.isValid()
            && entry.entity.getWorld().equals(viewer.getWorld())) {
            ChunkMap.TrackedEntity tracker =
                tracker(viewer, entry.entity);

            if (tracker != null) {
                tracker.updatePlayer(
                    ((CraftPlayer) viewer).getHandle()
                );
            }
        }
    }

    private static ChunkMap.TrackedEntity tracker(
        Player viewer,
        Entity entity
    ) {
        var handle = ((CraftPlayer) viewer).getHandle();

        return handle.level().getChunkSource()
            .chunkMap.entityMap.get(entity.getEntityId());
    }

    private void restore(Player viewer, Watch watch) {
        watch.scratch.clear();

        for (Entry entry : watch.entries.values()) {
            watch.scratch.add(entry);
        }

        for (Entry entry : watch.scratch) {
            if (entry.blocked) {
                release(viewer, entry);
            }
        }

        watch.scratch.clear();
        watch.entries.clear();
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Watch watch = watches.remove(viewer.getUniqueId());

            if (watch != null) {
                restore(viewer, watch);
            }
        }

        watches.clear();
        alwaysVisibleOwners.clear();
        viewerBuckets.clear();
    }

    private static int section(double coordinate) {
        return (int) Math.floor(coordinate / 16);
    }

    private static final class Watch {
        final UUID world;

        boolean seeded;

        final Map<UUID, Entry> entries = new HashMap<>();
        final ArrayList<Entry> scratch = new ArrayList<>();

        Watch(UUID world) {
            this.world = world;
        }
    }

    private static final class Entry {
        final Entity entity;

        boolean blocked;
        boolean apiHidden;
        boolean checked;
        boolean allowed;

        boolean playerTrackAttempted;
        CaveVisPlayerNameTag nameTag;

        long revision;

        int minX;
        int minY;
        int minZ;

        int maxX;
        int maxY;
        int maxZ;

        Entry(Entity entity) {
            this.entity = entity;
        }
    }
}