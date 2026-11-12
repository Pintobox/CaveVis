# PintoAntiCheat

An experimental Minecraft 1.21.11 server plugin. Its main feature, CaveVis,
limits the underground terrain sent to each player to reduce what freecam
clients can see.

## Build and run

Install Git and JDK 21, and make sure your terminal uses Java 21.
You’ll need access to both GitHub repositories. Clone them side by side
because the plugin’s `runServer` task looks for `../PintoPaper`.

From the folder where you want both projects, run these PowerShell commands:

```powershell
git clone https://github.com/Pintobox/PintoAntiCheat.git
git clone https://github.com/Pintobox/PintoPaper.git
cd PintoPaper
git checkout --detach e4cff2319588b9d14f3b2f09ae7eb9bafb756d61
.\gradlew.bat applyPatches
.\gradlew.bat createMojmapPaperclipJar -PpublishDevBundle publishToMavenLocal
cd ..\PintoAntiCheat
.\gradlew.bat runServer --refresh-dependencies
```

The Paper build creates the server JAR and publishes the matching
development bundle to your local Maven repository. PintoAntiCheat
compiles against that bundle and runs against the custom server JAR.

## Demo

1. Join a world where CaveVis is enabled. CaveVis filters terrain automatically from the first chunk packet.
2. Walk into a cave. Nearby terrain and sections approved by visibility scans appear as you move.
3. Optionally run `/cavevis build 16` to preload opacity data around your position.
   Preloaded data survives chunk unloads until `/cavevis clear` releases it.

## Using CaveVis from another plugin

Add PintoAntiCheat as a `compileOnly` dependency and declare
`depend: [PintoAntiCheat]` in your plugin's `plugin.yml`.

Import `me.pintobox.pintoAntiCheat.cavevis.api.CaveVisApi` and call the API
on the server thread.

```java
CaveVisApi caveVis = Bukkit.getServicesManager().load(CaveVisApi.class);
if (caveVis == null) {
    throw new IllegalStateException("CaveVis API is unavailable");
}
```

### Enable a temporary world

After creating or loading an arena world, call `enableTemporaryWorld()` before
teleporting any players into it. The world must be outside
`cavevis.enabled-worlds` and can be enabled once per load.

```java
var rules = new CaveVisApi.WorldRules(
    48,    // Always-visible block Y, inclusive
    64,    // Occluder block Y, exclusive
    10,    // Scan radius in chunks
    8,     // Near-reveal radius in blocks
    true,  // Visibility margin
    true,  // Entity culling
    true   // Particle culling
);
caveVis.enableTemporaryWorld(arenaWorld, rules);
```

The rules stay in memory until the world unloads. Unloading also drops that
world's preloaded opacity. Call `enableTemporaryWorld()` again if you reload it.

### Optionally preload opacity

Preload in a world enabled through YAML or immediately after calling
`enableTemporaryWorld()`. The center uses chunk coordinates. A radius of 16
covers a 33 × 33 square of chunk columns.

```java
caveVis.preload(arenaWorld, centerChunkX, centerChunkZ, 16)
    .whenComplete((ignored, error) -> {
        if (error != null) {
            getLogger().warning("CaveVis preload failed: " + error);
            return;
        }

        startMatch();
    });
```

The future completes successfully after CaveVis publishes and pins the opacity
data. Only one preload runs at a time.

To release a world's pins while keeping it loaded, call
`caveVis.releasePreloaded(arenaWorld.getUID())` on the server thread. This also
cancels any active preload for that world. Loaded chunks stay cached until
they unload. For a YAML-enabled world, you can also release its pins after
it unloads by passing its saved UUID.

`/cavevis clear` releases preload pins across all worlds.

### Keep an entity visible

CaveVis exempts glowing entities, see-through text displays, and entities
with always-visible nameplates from terrain-based entity culling.

To exempt another entity, call this on the server thread after it spawns:

```java
caveVis.setEntityAlwaysVisible(this, entity, true);

// Clear this plugin's exemption.
caveVis.setEntityAlwaysVisible(this, entity, false);
```

The exemption ends when cleared, when the entity leaves its world (including
chunk unload), or when the owning plugin is disabled. Normal tracking range
and other plugins' visibility rules still apply.

## How CaveVis works

CaveVis groups blocks into 2×2×2 cells and stores one opacity bit per cell.
A cell stops a ray only when all eight blocks are opaque. This keeps narrow
openings from being treated as solid.

CaveVis builds opacity data as chunks load and stores it per world and chunk
column. Ordinary entries are removed when their chunks unload. Each scan takes
an array snapshot around the player, and rays stop at columns whose opacity
data is unavailable.

Worker threads cast rays from the player's eye position through this grid.
In a 16×16×16 section with no opaque cells, rays continue stepping through
the grid and skip the opacity checks.

The scan identifies sections to reveal. The server uses that result to
filter the chunk data it sends to each player.

## Code overview

- `CaveVis.java` coordinates scanning and delivery.
- `CaveVisRayCache.java` prepares terrain data for visibility scans.
- `CaveVisTerrainDelivery.java` tracks what each player has received.
- `CaveVisColumnPackets.java` builds the filtered chunk packets.

## Configuration

Edit `plugins/PintoAntiCheat/config.yml` and restart the server.
CaveVis automatically enables the worlds listed under `cavevis.enabled-worlds`.
The default list contains `world`. Another plugin can also enable a temporary
world through the API.

Server-wide settings are under `cavevis.server`. Visibility rules are under
`cavevis.world-defaults`. To customize an enabled world, add its name under
`cavevis.worlds` and specify the settings you want to override. Omitted
settings inherit the world defaults.

The bundled `src/main/resources/config.yml` describes each setting and
includes commented examples of world overrides.