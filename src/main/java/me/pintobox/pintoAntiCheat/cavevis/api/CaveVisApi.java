package me.pintobox.pintoAntiCheat.cavevis.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

public interface CaveVisApi {
    /**
     * Preloads and pins opacity for a square centered on the given chunk.
     * A radius of 1 covers 3 by 3 chunk columns.
     *
     * Call on the server thread. The future completes on the server thread
     * after the opacity data is published, or completes exceptionally if
     * the request is rejected, the build fails, or the build is cancelled.
     *
     * The opacity stays pinned until CaveVis clears the preload cache.
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
}