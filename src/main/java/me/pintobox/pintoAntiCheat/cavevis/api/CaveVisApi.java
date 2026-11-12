package me.pintobox.pintoAntiCheat.cavevis.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public interface CaveVisApi {
    /**
     * Enables CaveVis for a loaded world until it unloads.
     * Call on the server thread before players enter the world.
     * The world must not already be enabled through config or this API.
     */
    void enableTemporaryWorld(World world, WorldRules rules);

    /**
     * Preloads and pins opacity for a square centered on the given chunk.
     * A radius of 1 covers 3 by 3 chunk columns.
     *
     * Call on the server thread. The future completes after the opacity
     * data is published, or completes exceptionally if the request is
     * rejected, fails, or is canceled.
     *
     * Pins remain until releasePreloaded is called or the preload cache
     * is cleared.
     */
    CompletableFuture<Void> preload(
        World world,
        int centerChunkX,
        int centerChunkZ,
        int radiusChunks
    );

    /**
     * Releases preload pins for one world and cancels its active preload.
     * Loaded chunks stay cached until they unload.
     *
     * Call on the server thread. The world can already be unloaded.
     */
    void releasePreloaded(UUID worldId);

    /**
     * Checks whether CaveVis has sent this section in the viewer's current
     * world and the section is still tracked for that viewer.
     *
     * Call on the server thread. Returns false when there is no active
     * delivery state for the viewer.
     */
    boolean hasReceivedSection(
        Player viewer, int sectionX, int sectionY, int sectionZ
    );

    /**
     * Returns an immutable snapshot for the viewer's current world.
     * The same snapshot can be reused until delivery changes.
     *
     * Call on the server thread. Returns an empty snapshot when there is
     * no active delivery state for the viewer.
     */
    ReceivedSections getReceivedSections(Player viewer);

    /**
     * Sets whether CaveVis should exempt this entity from terrain-based culling
     * on behalf of the calling plugin. Normal entity tracking still applies.
     *
     * Call on the server thread after the entity has spawned. A mark lasts
     * until cleared, the entity leaves its world, or the owner is disabled.
     * Players already bypass CaveVis entity culling.
     */
    void setEntityAlwaysVisible(Plugin owner, Entity entity, boolean visible);

    /**
     * Immutable CaveVis settings for one world. Both height cutoffs use
     * block Y coordinates and must be multiples of 16.
     *
     * @param alwaysVisibleFromY lowest block Y that is always visible, inclusive
     * @param occludersUpToY exclusive upper block Y for terrain that can stop rays;
     *                       must be at least {@code alwaysVisibleFromY}
     * @param searchRadiusChunks horizontal radius of visibility scans in chunks (1–64)
     * @param nearRevealRadiusBlocks radius around the player's eyes that reveals
     *                               nearby whole sections (0–32 blocks)
     * @param visibilityMargin whether to reveal face-adjacent sections to reduce pop-in
     * @param entityCulling whether to hide non-player entities in hidden terrain
     * @param particleCulling whether to filter particle effects from hidden terrain
     */
    record WorldRules(
        int alwaysVisibleFromY,
        int occludersUpToY,
        int searchRadiusChunks,
        int nearRevealRadiusBlocks,
        boolean visibilityMargin,
        boolean entityCulling,
        boolean particleCulling
    ) {
        public int alwaysVisibleSection() {
            return Math.floorDiv(alwaysVisibleFromY, 16);
        }

        public int occluderTopSection() {
            return Math.floorDiv(occludersUpToY, 16);
        }
    }

    /** A 16×16×16 section, expressed in section coordinates. */
    record Section(int x, int y, int z) {}

    /** An immutable snapshot of the sections sent in one world. */
    record ReceivedSections(UUID worldId, List<Section> sections) {
        public ReceivedSections {
            sections = List.copyOf(sections);
        }
    }
}