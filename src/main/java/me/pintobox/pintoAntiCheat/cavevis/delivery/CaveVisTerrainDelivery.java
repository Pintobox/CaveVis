package me.pintobox.pintoAntiCheat.cavevis.delivery;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.cavevis.CaveVisJoinHook;
import me.pintobox.pintoAntiCheat.cavevis.cache.CaveVisRayCache;
import me.pintobox.pintoAntiCheat.cavevis.core.CaveVisSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class CaveVisTerrainDelivery
    implements Listener, AutoCloseable {

    private static final String HANDLER = "pinto_cavevis_columns";

    private static final VarHandle DELTA_SECTION = deltaSectionHandle();

    private static final int PER_PLAYER = Math.max(
        1, CaveVisSettings.COLUMNS_PER_PLAYER_PER_TICK
    );

    private static final int TOTAL_PER_TICK = Math.max(
        1, CaveVisSettings.COLUMNS_TOTAL_PER_TICK
    );

    private static final long SEND_BUDGET = Math.max(
        1L,
        (long) (
            CaveVisSettings.COLUMN_SEND_BUDGET_MS * 1_000_000.0
        )
    );

    private final JavaPlugin plugin;

    private final Consumer<ServerPlayer> joinHook =
        player -> attach(player.getBukkitEntity());

    private final Map<UUID, State> states =
        new ConcurrentHashMap<>();

    private final CaveVisColumnPackets packets =
        new CaveVisColumnPackets();

    private int cursor;

    public CaveVisTerrainDelivery(JavaPlugin plugin) {
        this.plugin = plugin;

        CaveVisJoinHook.register(joinHook);

        Bukkit.getPluginManager().registerEvents(this, plugin);

        for (Player player : Bukkit.getOnlinePlayers()) {
            attach(player);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        detach(event.getPlayer());
    }

    @EventHandler
    public void moved(PlayerMoveEvent event) {
        var from = event.getFrom();
        var to = event.getTo();

        if (to == null) return;

        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4)
            && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)
            && (from.getBlockY() >> 4) == (to.getBlockY() >> 4)) {
            return;
        }

        State s = states.get(event.getPlayer().getUniqueId());

        if (s != null) {
            synchronized (s) {
                if (s.active) s.requested++;
            }
        }
    }

    private void attach(Player player) {
        Channel channel = ((CraftPlayer) player)
            .getHandle().connection.connection.channel;

        State s = new State(
            player.getWorld().getUID(), channel
        );

        s.enabled = true;
        s.active = true;
        s.worldTop = Math.floorDiv(player.getWorld().getMaxHeight(), 16);
        s.worldBottom = Math.floorDiv(player.getWorld().getMinHeight(), 16);

        UUID playerId = player.getUniqueId();

        State registered = states.compute(playerId, (id, previous) ->
            previous != null && previous.channel == channel ? previous : s
        );

        if (registered != s) {
            return;
        }

        channel.closeFuture().addListener(ignored -> states.remove(playerId, s));

        channel.eventLoop().execute(() -> {
            if (!channel.isActive() || states.get(playerId) != s) {
                return;
            }
            if (channel.pipeline().get(HANDLER) != null) return;

            channel.pipeline().addBefore(
                "packet_handler",
                HANDLER,
                new ChannelDuplexHandler() {
                    @Override
                    public void write(
                        ChannelHandlerContext ctx,
                        Object msg,
                        ChannelPromise promise
                    ) throws Exception {
                        Permit permit;

                        synchronized (s) {
                            permit = s.manual.remove(msg);
                        }

                        if (permit != null) {
                            promise = promise.unvoid();

                            synchronized (s) {
                                if (s.epoch != permit.epoch
                                    || s.columns.get(permit.key)
                                    != permit.column
                                    || permit.column.required
                                    != permit.required) {

                                    if (s.epoch == permit.epoch
                                        && s.columns.get(permit.key)
                                        == permit.column) {
                                        permit.column.inFlight = false;
                                        enqueue(
                                            s, permit.key, permit.column
                                        );
                                    }

                                    promise.trySuccess();
                                    return;
                                }
                            }

                            promise.addListener(done -> {
                                synchronized (s) {
                                    if (s.epoch != permit.epoch
                                        || s.columns.get(permit.key)
                                        != permit.column) {
                                        return;
                                    }

                                    if (permit.column.writingMask == permit.mask) {
                                        permit.column.writingMask = null;
                                    }

                                    permit.column.inFlight = false;

                                    if (done.isSuccess()
                                        && permit.column.required
                                        == permit.required) {
                                        if (!permit.column.sent
                                            || !permit.column.delivered.equals(permit.mask)) {
                                            s.deliveryRevision++;
                                        }

                                        permit.column.sent = true;
                                        permit.column.delivered = (BitSet) permit.mask.clone();

                                        permit.column.fresh = false;

                                        if (permit.restore) {
                                            s.columns.remove(permit.key);
                                        }
                                    }

                                    if (s.columns.get(permit.key)
                                        == permit.column
                                        && (permit.column.fresh
                                        || (permit.column.wanted != null
                                        && !permit.column.wanted.equals(
                                        permit.column.delivered
                                    )))) {
                                        enqueue(
                                            s, permit.key, permit.column
                                        );
                                    }
                                }
                            });

                            synchronized (s) {
                                permit.column.writingMask = permit.mask;
                            }

                            ctx.write(msg, promise);
                            return;
                        }

                        if (msg instanceof ClientboundBundlePacket bundle) {
                            var kept = new ArrayList<
                                Packet<? super ClientGamePacketListener>
                                >();

                            boolean changed = false;

                            for (var packet : bundle.subPackets()) {
                                if (intercept(s, packet)) {
                                    changed = true;
                                } else {
                                    kept.add(packet);
                                }
                            }

                            if (!changed) {
                                ctx.write(msg, promise);
                            } else if (kept.isEmpty()) {
                                promise.trySuccess();
                            } else {
                                ctx.write(
                                    new ClientboundBundlePacket(kept),
                                    promise
                                );
                            }
                        } else if (intercept(s, msg)) {
                            promise.trySuccess();
                        } else {
                            ctx.write(msg, promise);
                        }
                    }
                }
            );
        });
    }

    private static boolean intercept(State s, Object packet) {
        synchronized (s) {
            if (packet instanceof ClientboundRespawnPacket) {
                s.epoch++;
                s.deliveryRevision++;

                s.active = false;
                s.resetPending = s.enabled;
                s.accepted = -1;
                s.visible = null;

                s.columns.clear();
                s.queue.clear();
                s.urgent.clear();

                s.nearBox = null;
                s.nearDirty = true;

                return false;
            }

            if (packet instanceof ClientboundForgetLevelChunkPacket unload) {
                if (s.columns.remove(unload.pos().toLong()) != null) {
                    s.deliveryRevision++;
                }

                return false;
            }

            if (packet instanceof ClientboundBlockUpdatePacket update) {
                return !blockDelivered(s, update.getPos());
            }

            if (packet instanceof ClientboundSectionBlocksUpdatePacket updates) {
                SectionPos section = (SectionPos) DELTA_SECTION.get(updates);

                return !blockSectionDelivered(
                    s,
                    section.x(),
                    section.y(),
                    section.z()
                );
            }

            if (packet instanceof ClientboundBlockEntityDataPacket update) {
                return !blockDelivered(s, update.getPos());
            }

            if (packet instanceof ClientboundBlockEventPacket event) {
                return !blockDelivered(s, event.getPos());
            }

            if (packet instanceof ClientboundBlockDestructionPacket destruction) {
                return !blockDelivered(s, destruction.getPos());
            }

            if (CaveVisSettings.PARTICLE_CULLING_ENABLED) {
                if (packet instanceof ClientboundLevelParticlesPacket particles) {
                    return !positionDelivered(
                        s,
                        particles.getX(),
                        particles.getY(),
                        particles.getZ()
                    );
                }

                // Preserve explosion packets that apply knockback to this player.
                if (packet instanceof ClientboundExplodePacket explosion
                    && explosion.playerKnockback().isEmpty()) {
                    var pos = explosion.center();

                    return !positionDelivered(
                        s,
                        pos.x(),
                        pos.y(),
                        pos.z()
                    );
                }

                // Particle-producing world events, including block-break effects.
                if (packet instanceof ClientboundLevelEventPacket effect
                    && ((effect.getType() >= 1500 && effect.getType() <= 1505)
                    || effect.getType() >= 2000)) {
                    var pos = effect.getPos();

                    return !positionDelivered(
                        s,
                        pos.getX(),
                        pos.getY(),
                        pos.getZ()
                    );
                }
            }

            if (!(packet instanceof ClientboundLevelChunkWithLightPacket chunk)) {
                return false;
            }

            if (!s.active && !s.resetPending) {
                return false;
            }

            long key = key(chunk.getX(), chunk.getZ());

            Column column = s.columns.computeIfAbsent(
                key, unused -> new Column()
            );

            column.required = ++s.requested;
            column.fresh = true;
            s.nearDirty = true;

            if (!column.inFlight) {
                enqueue(s, key, column);
            }

            return true;
        }
    }

    public boolean resumeAfterReset(Player player) {
        State s = states.get(player.getUniqueId());
        if (s == null) return false;

        synchronized (s) {
            if (!s.resetPending) return false;

            var world = player.getWorld();
            s.world = world.getUID();
            s.worldBottom = Math.floorDiv(world.getMinHeight(), 16);
            s.worldTop = Math.floorDiv(world.getMaxHeight(), 16);

            s.active = true;
            s.resetPending = false;
            return true;
        }
    }

    public boolean active(Player player) {
        State s = states.get(player.getUniqueId());

        if (s == null) return false;

        synchronized (s) {
            return s.active || s.resetPending;
        }
    }

    public boolean needsScan(Player player) {
        State s = states.get(player.getUniqueId());

        if (s == null) return false;

        synchronized (s) {
            return s.active && s.requested > s.issued;
        }
    }

    public long beginSearch(Player player) {
        State s = states.get(player.getUniqueId());

        if (s == null) return -1;

        synchronized (s) {
            s.issued = s.requested;
            return s.issued;
        }
    }

    public void publish(
        Player player,
        CaveVisRayCache.Scan scan,
        long version
    ) {
        State s = states.get(player.getUniqueId());

        if (s == null) return;

        synchronized (s) {
            if (!s.active
                || !s.world.equals(player.getWorld().getUID())) {
                return;
            }

            s.visible = scan.visibility();
            s.accepted = version;

            int bottom = Math.floorDiv(
                player.getWorld().getMinHeight(), 16
            );

            int height = (
                player.getWorld().getMaxHeight()
                    - player.getWorld().getMinHeight()
            ) / 16;

            for (var entry : s.columns.entrySet()) {
                long key = entry.getKey();
                Column c = entry.getValue();

                if (c.restoreOnly) continue;

                if (c.wanted == null) {
                    c.wanted = new BitSet(height);
                }

                s.visible.writeColumn(
                    c.wanted,
                    (int) key,
                    (int) (key >> 32),
                    bottom,
                    height,
                    CaveVisSettings.alwaysVisibleSection()
                );

                addSafety(s, key, c.wanted, bottom);
                c.wanted.or(c.delivered);

                if (!c.inFlight
                    && (c.fresh
                    || !c.wanted.equals(c.delivered))) {
                    enqueue(s, key, c);
                }
            }
        }
    }

    public void disable(Player player) {
        State s = states.get(player.getUniqueId());
        if (s == null) return;

        synchronized (s) {
            UUID world = player.getWorld().getUID();

            if (!s.world.equals(world)) {
                if (!s.resetPending) {
                    s.columns.clear();
                }

                s.world = world;
                s.worldTop = Math.floorDiv(player.getWorld().getMaxHeight(), 16);
                s.worldBottom = Math.floorDiv(player.getWorld().getMinHeight(), 16);
            }

            s.epoch++;
            s.deliveryRevision++;

            s.enabled = false;
            s.resetPending = false;
            s.active = false;
            s.visible = null;

            s.queue.clear();
            s.urgent.clear();

            s.nearBox = null;
            s.nearDirty = true;

            for (var entry : s.columns.entrySet()) {
                Column c = entry.getValue();

                c.inFlight = false;
                c.retryScheduled = false;
                c.writingMask = null;
                c.fresh = true;
                c.queued = false;
                c.urgentQueued = false;
                c.near = false;

                enqueue(s, entry.getKey(), c);
            }
        }
    }

    public void disableAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            disable(p);
        }
    }

    public void tick() {
        var players = new ArrayList<>(Bukkit.getOnlinePlayers());

        if (players.isEmpty()) return;

        int start = Math.floorMod(cursor++, players.size());

        // Only changes masks when the safety cube's section bounds
        // change or a new native column request arrives.
        for (Player p : players) {
            State s = states.get(p.getUniqueId());

            if (s != null) {
                refreshSafety(p, s);
            }
        }

        if (CaveVisSettings.NEAR_SEND_IMMEDIATELY) {
            for (int n = 0; n < players.size(); n++) {
                Player p = players.get(
                    (start + n) % players.size()
                );

                State s = states.get(p.getUniqueId());

                if (s == null) continue;

                int remaining;

                synchronized (s) {
                    remaining = s.urgent.size();
                }

                while (remaining-- > 0) {
                    Plan plan = plan(p, s, true);

                    if (plan == null) break;

                    send(p, s, plan);
                }
            }
        }

        long started = System.nanoTime();
        int sent = 0;

        int[] perPlayer = new int[players.size()];

        // First pass: nearby work for every player.
        // Second pass: remaining work.
        for (int pass = 0; pass < 2; pass++) {
            for (int n = 0; n < players.size(); n++) {
                Player p = players.get(
                    (start + n) % players.size()
                );

                State s = states.get(p.getUniqueId());

                if (s == null) continue;

                while (perPlayer[n] < PER_PLAYER) {
                    Plan plan = plan(p, s, pass == 0);

                    if (plan == null) break;

                    send(p, s, plan);
                    perPlayer[n]++;

                    if (++sent >= TOTAL_PER_TICK
                        || System.nanoTime() - started >= SEND_BUDGET) {
                        return;
                    }
                }
            }
        }
    }

    public long deliveryRevision(UUID playerId) {
        State s = states.get(playerId);

        if (s == null) {
            return -1;
        }

        synchronized (s) {
            return s.deliveryRevision;
        }
    }

    /**
     * Coordinates are inclusive section coordinates, not block coordinates.
     * Returns true if any overlapping section has been delivered.
     */
    public boolean areaDelivered(
        UUID playerId,
        UUID world,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
    ) {
        State s = states.get(playerId);

        if (s == null) {
            return true;
        }

        synchronized (s) {
            if (s.resetPending) return false;
            if (!s.active) {
                return true;
            }

            if (!s.world.equals(world)) {
                return false;
            }

            return areaDelivered(
                s,
                minX, minY, minZ,
                maxX, maxY, maxZ
            );
        }
    }

    private static boolean positionDelivered(
        State s,
        double x,
        double y,
        double z
    ) {
        int sx = (int) Math.floor(x / 16);
        int sy = (int) Math.floor(y / 16);
        int sz = (int) Math.floor(z / 16);

        return areaDelivered(s, sx, sy, sz, sx, sy, sz);
    }

    private static boolean areaDelivered(
        State s,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
    ) {
        if (s.resetPending) return false;
        if (!s.active) return true;

        int bottom = s.worldBottom;

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                Column column = s.columns.get(key(x, z));

                if (column == null || !column.sent) {
                    continue;
                }

                // Entities can exist outside the world's block-height limits.
                if (minY < bottom || maxY >= s.worldTop) {
                    return true;
                }

                int first = column.delivered.nextSetBit(
                    Math.max(0, minY - bottom)
                );

                if (first >= 0 && first <= maxY - bottom) {
                    return true;
                }
            }
        }

        return false;
    }

    private void refreshSafety(Player p, State s) {
        var eye = p.getEyeLocation();

        double radius = Math.max(
            0, CaveVisSettings.NEAR_REVEAL_RADIUS_BLOCKS
        );

        int bottom = Math.floorDiv(
            p.getWorld().getMinHeight(), 16
        );

        int height = (
            p.getWorld().getMaxHeight()
                - p.getWorld().getMinHeight()
        ) / 16;

        NearBox box = new NearBox(
            (int) Math.floor((eye.getX() - radius) / 16),
            (int) Math.floor((eye.getX() + radius) / 16),
            Math.max(
                bottom,
                (int) Math.floor((eye.getY() - radius) / 16)
            ),
            Math.min(
                bottom + height - 1,
                (int) Math.floor((eye.getY() + radius) / 16)
            ),
            (int) Math.floor((eye.getZ() - radius) / 16),
            (int) Math.floor((eye.getZ() + radius) / 16)
        );

        synchronized (s) {
            if (!s.active
                || !s.world.equals(p.getWorld().getUID())) {
                return;
            }

            if (!s.nearDirty && box.equals(s.nearBox)) {
                return;
            }

            if (s.nearBox != null) {
                for (int z = s.nearBox.minZ;
                     z <= s.nearBox.maxZ; z++) {
                    for (int x = s.nearBox.minX;
                         x <= s.nearBox.maxX; x++) {
                        Column c = s.columns.get(key(x, z));

                        if (c != null) c.near = false;
                    }
                }
            }

            s.nearBox = box;
            s.nearDirty = false;

            if (box.minY > box.maxY) return;

            for (int z = box.minZ; z <= box.maxZ; z++) {
                for (int x = box.minX; x <= box.maxX; x++) {
                    long key = key(x, z);
                    Column c = s.columns.get(key);

                    // Never load or send untracked columns.
                    if (c == null) continue;

                    c.near = true;

                    if (c.wanted == null) {
                        c.wanted = new BitSet(height);

                        int surface = Math.max(
                            0,
                            Math.min(
                                height,
                                CaveVisSettings.alwaysVisibleSection() - bottom
                            )
                        );

                        c.wanted.set(surface, height);
                    }

                    c.wanted.set(
                        box.minY - bottom,
                        box.maxY - bottom + 1
                    );

                    c.wanted.or(c.delivered);

                    if (!c.inFlight
                        && (c.fresh
                        || !c.wanted.equals(c.delivered))) {
                        enqueue(s, key, c);
                    }
                }
            }
        }
    }

    private static void addSafety(
        State s,
        long key,
        BitSet mask,
        int bottom
    ) {
        NearBox b = s.nearBox;

        int x = (int) key;
        int z = (int) (key >> 32);

        if (b != null
            && b.minY <= b.maxY
            && x >= b.minX && x <= b.maxX
            && z >= b.minZ && z <= b.maxZ) {
            mask.set(
                b.minY - bottom,
                b.maxY - bottom + 1
            );
        }
    }

    private Plan plan(Player player, State s) {
        return plan(player, s, false);
    }

    private Plan plan(
        Player player,
        State s,
        boolean urgentOnly
    ) {
        int height = (
            player.getWorld().getMaxHeight()
                - player.getWorld().getMinHeight()
        ) / 16;

        int bottom = Math.floorDiv(player.getWorld().getMinHeight(), 16);

        synchronized (s) {
            if (s.resetPending) return null;

            if (!s.world.equals(player.getWorld().getUID())) {
                return null;
            }

            while (true) {
                Work work = s.urgent.pollFirst();
                boolean urgent = work != null;

                if (work == null && !urgentOnly) {
                    work = s.queue.pollFirst();
                }

                if (work == null) return null;

                long key = work.key;
                Column c = work.column;

                if (urgent) {
                    c.urgentQueued = false;
                } else {
                    c.queued = false;
                }

                if (s.columns.get(key) != c || c.inFlight || c.retryScheduled) {
                    continue;
                }

                if (urgent && !c.near) {
                    enqueue(s, key, c);
                    continue;
                }

                boolean restore = !s.active || c.restoreOnly;
                BitSet wanted;

                if (restore) {
                    wanted = new BitSet(height);
                    wanted.set(0, height);
                } else {
                    boolean scanReady = s.visible != null
                        && c.required <= s.accepted
                        && c.wanted != null;

                    if (scanReady) {
                        wanted = (BitSet) c.wanted.clone();
                    } else {
                        wanted = new BitSet(height);
                        int surface = Math.clamp(
                            CaveVisSettings.alwaysVisibleSection() - bottom, 0, height
                        );
                        wanted.set(surface, height);
                        addSafety(s, key, wanted, bottom);
                    }

                    wanted.or(c.delivered);

                    if (!c.fresh && wanted.equals(c.delivered)) {
                        continue;
                    }
                }

                c.inFlight = true;

                return new Plan(
                    key,
                    s.epoch,
                    c.required,
                    c,
                    wanted,
                    restore
                );
            }
        }
    }

    private void send(Player player, State s, Plan plan) {
        try {
            int x = (int) plan.key;
            int z = (int) (plan.key >> 32);

            var packet = packets.create(
                player.getWorld(), x, z, plan.mask
            );

            if (packet == null) {
                retryMissingColumn(player, s, plan);
                return;
            }

            synchronized (s) {
                if (s.epoch != plan.epoch
                    || s.columns.get(plan.key) != plan.column
                    || plan.column.required != plan.required) {
                    if (s.epoch == plan.epoch
                        && s.columns.get(plan.key) == plan.column) {
                        plan.column.inFlight = false;
                        enqueue(s, plan.key, plan.column);
                    }

                    return;
                }

                s.manual.put(
                    packet,
                    new Permit(
                        plan.key,
                        plan.epoch,
                        plan.required,
                        plan.column,
                        plan.mask,
                        plan.restore
                    )
                );
            }

            ((CraftPlayer) player)
                .getHandle().connection.send(packet);
        } catch (RuntimeException failure) {
            synchronized (s) {
                plan.column.inFlight = false;

                if (s.columns.get(plan.key) == plan.column) {
                    enqueue(s, plan.key, plan.column);
                }
            }

            plugin.getLogger().log(
                Level.SEVERE,
                "CaveVis column delivery failed; filtering remains enabled",
                failure
            );
        }
    }

    private void retryMissingColumn(Player player, State s, Plan plan) {
        synchronized (s) {
            if (s.epoch != plan.epoch || s.columns.get(plan.key) != plan.column) {
                return;
            }

            plan.column.inFlight = false;

            if (plan.restore) {
                s.columns.remove(plan.key);
                return;
            }

            if (plan.column.retryScheduled) {
                return;
            }
            plan.column.retryScheduled = true;
        }

        UUID playerId = player.getUniqueId();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            synchronized (s) {
                if (states.get(playerId) != s
                    || s.epoch != plan.epoch
                    || s.columns.get(plan.key) != plan.column) {
                    return;
                }

                plan.column.retryScheduled = false;
                enqueue(s, plan.key, plan.column);
            }
        }, 2L);
    }

    private void detach(Player player) {
        UUID playerId = player.getUniqueId();
        State s = states.get(playerId);
        if (s == null) {
            return;
        }

        Channel channel = ((CraftPlayer) player)
            .getHandle().connection.connection.channel;

        if (s.channel != channel || !states.remove(playerId, s)) {
            return;
        }

        synchronized (s) {
            s.epoch++;
            s.deliveryRevision++;
            s.active = false;
            s.columns.clear();
        }

        s.channel.eventLoop().execute(() -> {
            if (s.channel.pipeline().get(HANDLER) != null) {
                s.channel.pipeline().remove(HANDLER);
            }
        });
    }

    @Override
    public void close() {
        CaveVisJoinHook.unregister(joinHook);
        HandlerList.unregisterAll(this);
        disableAll();

        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());

            if (s == null) continue;

            int remaining;

            synchronized (s) {
                remaining = s.columns.size();
            }

            while (remaining-- > 0) {
                Plan plan = plan(p, s);

                if (plan == null) break;

                send(p, s, plan);
            }

            states.remove(p.getUniqueId(), s);

            s.channel.eventLoop().execute(() -> {
                if (s.channel.pipeline().get(HANDLER) != null) {
                    s.channel.pipeline().remove(HANDLER);
                }
            });
        }

        packets.clear();
    }

    public boolean invalidateScan(Player player) {
        State s = states.get(player.getUniqueId());
        if (s == null) return false;

        synchronized (s) {
            if (!s.active || s.resetPending) return false;

            s.visible = null;
            s.accepted = -1;
            s.nearDirty = true;

            for (Column column : s.columns.values()) {
                column.wanted = null;
            }

            return true;
        }
    }

    private static void enqueue(
        State s,
        long key,
        Column c
    ) {
        if (c.near) {
            if (c.urgentQueued) return;

            c.urgentQueued = true;
            s.urgent.addLast(new Work(key, c));
        } else {
            if (c.queued) return;

            c.queued = true;
            s.queue.addLast(new Work(key, c));
        }
    }

    private static long key(int x, int z) {
        return (x & 0xffffffffL) | ((long) z << 32);
    }

    private static VarHandle deltaSectionHandle() {
        try {
            return MethodHandles.privateLookupIn(
                ClientboundSectionBlocksUpdatePacket.class,
                MethodHandles.lookup()
            ).findVarHandle(
                ClientboundSectionBlocksUpdatePacket.class,
                "sectionPos",
                SectionPos.class
            );
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static boolean blockSectionDelivered(
        State s,
        int sectionX,
        int sectionY,
        int sectionZ
    ) {
        if (s.resetPending) return false;
        if (!s.active) return true;

        Column column = s.columns.get(key(sectionX, sectionZ));

        if (column == null) {
            return false;
        }

        BitSet writing = column.writingMask;

        int bottom = s.worldBottom;

        if (sectionY < bottom || sectionY >= s.worldTop) {
            return column.sent || writing != null;
        }

        int index = sectionY - bottom;

        return (column.sent && column.delivered.get(index))
            || (writing != null && writing.get(index));
    }

    private static boolean blockDelivered(State s, BlockPos position) {
        return blockSectionDelivered(
            s,
            position.getX() >> 4,
            position.getY() >> 4,
            position.getZ() >> 4
        );
    }

    private static final class Column {
        long required;

        boolean fresh = true;
        boolean inFlight;
        boolean queued;
        boolean urgentQueued;
        boolean near;
        boolean sent;
        boolean restoreOnly;
        boolean retryScheduled;

        BitSet wanted;
        BitSet delivered = new BitSet();
        BitSet writingMask;
    }

    private static final class State {
        UUID world;
        final Channel channel;

        boolean active;
        boolean enabled;
        boolean resetPending;

        long epoch;
        long requested;
        long issued;
        long accepted = -1;
        long deliveryRevision;
        int worldTop;
        int worldBottom;

        CaveVisVisibility visible;

        NearBox nearBox;
        boolean nearDirty = true;

        final Map<Long, Column> columns =
            new LinkedHashMap<>();

        final ArrayDeque<Work> queue =
            new ArrayDeque<>();

        final ArrayDeque<Work> urgent =
            new ArrayDeque<>();

        final Map<Object, Permit> manual =
            new IdentityHashMap<>();

        State(UUID world, Channel channel) {
            this.world = world;
            this.channel = channel;
        }
    }

    private record NearBox(
        int minX,
        int maxX,
        int minY,
        int maxY,
        int minZ,
        int maxZ
    ) {}

    private record Work(
        long key,
        Column column
    ) {}

    private record Plan(
        long key,
        long epoch,
        long required,
        Column column,
        BitSet mask,
        boolean restore
    ) {}

    private record Permit(
        long key,
        long epoch,
        long required,
        Column column,
        BitSet mask,
        boolean restore
    ) {}
}