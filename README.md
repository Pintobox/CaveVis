# CaveVis

An experimental Minecraft 1.21.11 server plugin. Its main feature, CaveVis,
limits the underground terrain sent to each player to reduce what freecam
clients can see.

## Build and run

Requires JDK 21 and stock Paper 1.21.11. Tested on Paper build 132.

```powershell
git clone https://github.com/Pintobox/CaveVis.git
cd CaveVis
.\gradlew.bat runServer
```

`runServer` launches a stock Paper test server with CaveVis loaded.

To build the plugin for an existing server, run `.\gradlew.bat :jar`
and copy the CaveVis JAR from the root `build/libs/` folder
into the server's `plugins/` folder.

## Demo

1. Join a world where CaveVis is enabled. CaveVis filters terrain automatically from the first chunk packet.
2. Walk into a cave. Nearby terrain and sections approved by visibility scans appear as you move.
3. Optionally run `/cavevis build 16` to preload opacity data around your position.
   Preloaded data survives chunk unloads until `/cavevis clear` releases it.

## Admin commands

All `/cavevis` commands require the `cavevis.admin` permission.
Server operators have it by default. Run `/cavevis` to see the command list in-game.

| Command | Effect |
| --- | --- |
| `/cavevis build [radius]` | Preload and pin opacity data around your current chunk. The radius is measured in chunks and defaults to `default-preload-radius-chunks` in the config. Run this in-game. |
| `/cavevis cancel` | Cancel the active preload and keep previously completed preloads. |
| `/cavevis clear` | Clear the opacity cache and release preload pins across all worlds. Terrain filtering stays active. |
| `/cavevis profile on` | Start collecting profiling data and show scan timings in your action bar. |
| `/cavevis profile off` | Stop profiling and clear the action bar. |
| `/cavevis profile reset` | Reset profiling measurements. |
| `/cavevis profile detail` | Toggle detailed scan preparation timings. Enable profiling first. |
| `/cavevis raystats [tick]` | Show visibility scan statistics. Add `tick` to see server tick timings. |
| `/cavevis bypass on [player]` | Disable CaveVis filtering for yourself or the named online player. |
| `/cavevis bypass off [player]` | Restore CaveVis filtering for yourself or the named online player. |

When running `bypass` from the console, specify an online player. The bypass
follows the player across world changes and clears when they disconnect.
CaveVis refreshes chunks already on the client through its delivery queue,
so the visual change is gradual.

## Using CaveVis from another plugin

Server admins install only the `CaveVis` JAR in `plugins/`. It
already includes `CaveVisApi`. The separate `cavevis-api` JAR is a
compile-time dependency for developers integrating with CaveVis.

To publish the API to your local Maven repository, run this from the
CaveVis project root:

```powershell
.\gradlew.bat :cavevis-api:publishToMavenLocal
```

Then add the API to your plugin's `build.gradle`:

```groovy
repositories {
    mavenLocal()
}

dependencies {
    compileOnly 'me.pintobox:cavevis-api:1.0.0'
}
```

Declare CaveVis as a required dependency in your plugin's `plugin.yml`:

```yaml
depend: [CaveVis]
```

Import `me.pintobox.cavevis.api.CaveVisApi` and call the API
on the server thread.

```java
CaveVisApi caveVis = Bukkit.getServicesManager().load(CaveVisApi.class);
if (caveVis == null) {
    throw new IllegalStateException("CaveVis API is unavailable");
}
```

### Bypass filtering for a player

Call these methods on the server thread with an online player. They use the
same session bypass as the admin command.

```java
caveVis.setVisibilityBypass(player, true);  // Disable filtering
boolean bypassed = caveVis.hasVisibilityBypass(player);
caveVis.setVisibilityBypass(player, false); // Restore filtering
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

Edit `plugins/CaveVis/config.yml` and restart the server.
CaveVis automatically enables the worlds listed under `cavevis.enabled-worlds`.
The default list contains `world`. Another plugin can also enable a temporary
world through the API.

Server-wide settings are under `cavevis.server`. Visibility rules are under
`cavevis.world-defaults`. To customize an enabled world, add its name under
`cavevis.worlds` and specify the settings you want to override. Omitted
settings inherit the world defaults.

The bundled `src/main/resources/config.yml` describes each setting and
includes commented examples of world overrides.