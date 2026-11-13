package me.pintobox.pintoAntiCheat.cavevis.core;

import me.pintobox.pintoAntiCheat.cavevis.api.CaveVisApi.WorldRules;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class CaveVisConfiguration {
    private static final int MAX_NEAR_REVEAL_RADIUS_BLOCKS = 32;

    private final Set<String> enabledWorlds;
    private final WorldRules defaults;
    private final Map<String, WorldRules> overrides;
    private final Map<UUID, WorldRules> temporaryRules = new HashMap<>();

    public final boolean playerCulling;
    public final int raysPerScan;
    public final int rayWorkers;
    public final int scanStartsPerTick;
    public final int defaultPreloadRadius;

    private CaveVisConfiguration(ConfigurationSection root) {
        enabledWorlds = Set.copyOf(root.getStringList("enabled-worlds"));

        playerCulling = flag(root, "server.player-culling", true);
        raysPerScan = integer(
            root, "server.rays-per-scan", CaveVisSettings.DEFAULT_RAYS
        );
        rayWorkers = integer(
            root, "server.ray-workers", CaveVisSettings.RAY_WORKERS
        );
        scanStartsPerTick = integer(
            root, "server.scan-starts-per-tick", CaveVisSettings.SCAN_STARTS_PER_TICK
        );
        defaultPreloadRadius = integer(
            root, "server.default-preload-radius-chunks",
            CaveVisSettings.DEFAULT_BUILD_RADIUS_CHUNKS
        );

        require(raysPerScan >= 256 && raysPerScan <= 65536,
            "rays-per-scan must be within 256..65536");
        require(rayWorkers > 0, "ray-workers must be positive");
        require(scanStartsPerTick > 0, "scan-starts-per-tick must be positive");
        require(defaultPreloadRadius >= 1 && defaultPreloadRadius <= 64,
            "default-preload-radius-chunks must be within 1..64");

        WorldRules builtIn = new WorldRules(
            CaveVisSettings.ALWAYS_VISIBLE_FROM_Y,
            CaveVisSettings.OCCLUDERS_UP_TO_Y,
            CaveVisSettings.SEARCH_RADIUS_CHUNKS,
            CaveVisSettings.NEAR_REVEAL_RADIUS_BLOCKS,
            CaveVisSettings.VISIBILITY_MARGIN_ENABLED,
            CaveVisSettings.ENTITY_CULLING_ENABLED,
            CaveVisSettings.PARTICLE_CULLING_ENABLED
        );

        defaults = readRules(root.getConfigurationSection("world-defaults"), builtIn);

        Map<String, WorldRules> named = new HashMap<>();
        ConfigurationSection worlds = root.getConfigurationSection("worlds");
        if (worlds != null) {
            for (var entry : worlds.getValues(false).entrySet()) {
                if (!(entry.getValue() instanceof ConfigurationSection section)) {
                    throw new IllegalArgumentException(
                        "Expected a settings section for CaveVis world " + entry.getKey()
                    );
                }

                named.put(entry.getKey(), readRules(section, defaults));
            }
        }

        overrides = Map.copyOf(named);
    }

    public static CaveVisConfiguration load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("cavevis");
        if (root == null) {
            throw new IllegalArgumentException("Missing cavevis section in config.yml");
        }

        return new CaveVisConfiguration(root);
    }

    public boolean enabled(World world) {
        return temporaryRules.containsKey(world.getUID())
            || enabledWorlds.contains(world.getName());
    }

    public WorldRules rulesFor(World world) {
        WorldRules temporary = temporaryRules.get(world.getUID());
        return temporary != null
            ? temporary
            : overrides.getOrDefault(world.getName(), defaults);
    }

    void enableTemporaryWorld(World world, WorldRules rules) {
        if (enabled(world)) {
            throw new IllegalStateException(
                "CaveVis is already enabled in " + world.getName()
            );
        }

        Objects.requireNonNull(rules, "rules");
        validateRules(rules, "Temporary CaveVis settings for " + world.getName());

        if (rules.occludersUpToY() <= world.getMinHeight()) {
            throw new IllegalArgumentException(
                "Occluder height must be above the minimum height of " + world.getName()
            );
        }

        temporaryRules.put(world.getUID(), rules);
    }

    public boolean removeTemporaryWorld(UUID worldId) {
        return temporaryRules.remove(worldId) != null;
    }

    private static WorldRules readRules(
        ConfigurationSection section,
        WorldRules fallback
    ) {
        if (section == null) {
            return fallback;
        }

        WorldRules rules = new WorldRules(
            integer(section, "always-visible-from-y", fallback.alwaysVisibleFromY()),
            integer(section, "occluders-up-to-y", fallback.occludersUpToY()),
            integer(section, "search-radius-chunks", fallback.searchRadiusChunks()),
            integer(section, "near-reveal-radius-blocks", fallback.nearRevealRadiusBlocks()),
            flag(section, "visibility-margin", fallback.visibilityMargin()),
            flag(section, "entity-culling", fallback.entityCulling()),
            flag(section, "particle-culling", fallback.particleCulling())
        );

        validateRules(rules, section.getCurrentPath());
        return rules;
    }

    private static void validateRules(WorldRules rules, String path) {
        require((rules.alwaysVisibleFromY() & 15) == 0
                && (rules.occludersUpToY() & 15) == 0,
            path + " heights must be multiples of 16");

        require(rules.occludersUpToY() >= rules.alwaysVisibleFromY(),
            path + ".occluders-up-to-y must reach always-visible-from-y");

        require(rules.searchRadiusChunks() >= 1 && rules.searchRadiusChunks() <= 64,
            path + ".search-radius-chunks must be within 1..64");

        require(rules.nearRevealRadiusBlocks() >= 0
                && rules.nearRevealRadiusBlocks() <= MAX_NEAR_REVEAL_RADIUS_BLOCKS,
            path + ".near-reveal-radius-blocks must be within 0.."
                + MAX_NEAR_REVEAL_RADIUS_BLOCKS);
    }

    private static int integer(ConfigurationSection section, String key, int fallback) {
        Object value = section.get(key);
        if (value == null) return fallback;
        if (value instanceof Integer number) return number;

        throw new IllegalArgumentException(section.getCurrentPath() + "." + key
            + " must be an integer");
    }

    private static boolean flag(ConfigurationSection section, String key, boolean fallback) {
        Object value = section.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean enabled) return enabled;

        throw new IllegalArgumentException(section.getCurrentPath() + "." + key
            + " must be true or false");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}