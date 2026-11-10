package me.pintobox.pintoAntiCheat.cavevis.delivery;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.*;
import me.pintobox.pintoAntiCheat.cavevis.core.CaveVisSettings;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class CaveVisEntityVisibility
    implements Listener, AutoCloseable {

    private final JavaPlugin plugin;
    private final CaveVisTerrainDelivery terrain;
    private final Map<UUID, Watch> watches = new HashMap<>();

    private int ticks;

    public CaveVisEntityVisibility(
        JavaPlugin plugin,
        CaveVisTerrainDelivery terrain
    ) {
        this.plugin = plugin;
        this.terrain = terrain;

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void track(PlayerTrackEntityEvent event) {
        Entity entity = event.getEntity();
        Player viewer = event.getPlayer();

        if (!CaveVisSettings.ENTITY_CULLING_ENABLED
            || entity instanceof Player
            || !terrain.active(viewer)) {
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
            return;
        }

        event.setCancelled(true);
        entry.blocked = true;

        if (entity.isVisibleByDefault() && !entry.apiHidden) {
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

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        watches.remove(event.getPlayer().getUniqueId());
    }

    public void tick() {
        if (++ticks < Math.max(
            1,
            CaveVisSettings.ENTITY_VISIBILITY_INTERVAL_TICKS
        )) {
            return;
        }

        ticks = 0;

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!CaveVisSettings.ENTITY_CULLING_ENABLED
                || !terrain.active(viewer)) {
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

                        if (!(bukkit instanceof Player)) {
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

    private void release(Player viewer, Entry entry) {
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