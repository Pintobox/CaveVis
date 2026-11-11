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
git checkout --detach 57b245da22fcea6cf946f865083665e47dc42a51
.\gradlew.bat applyPatches
.\gradlew.bat createMojmapPaperclipJar -PpublishDevBundle publishToMavenLocal
cd ..\PintoAntiCheat
.\gradlew.bat runServer --refresh-dependencies
```

The Paper build creates the server JAR and publishes the matching
development bundle to your local Maven repository. PintoAntiCheat
compiles against that bundle and runs against the custom server JAR.

## Demo

1. Join the server. Terrain loads normally at first.
2. Run `/cavevis raybuild 16` and wait for the visibility cache to finish building.
3. Run `/cavevis reveal` to turn on terrain filtering for your player.

## How CaveVis works

CaveVis groups blocks into 2×2×2 cells and stores one opacity bit per cell.
A cell stops a ray only when all eight blocks are opaque. This keeps narrow
openings from being treated as solid.

Worker threads cast rays from the player's eye position through this grid.
When a 16×16×16 section contains no opaque cells, a ray can skip the
individual cell checks and jump to the section boundary.

The scan identifies sections to reveal. The server uses that result to
filter the chunk data it sends to each player.

## Code overview

- `CaveVis.java` coordinates scanning and delivery.
- `CaveVisRayCache.java` prepares terrain data for visibility scans.
- `CaveVisTerrainDelivery.java` tracks what each player has received.
- `CaveVisColumnPackets.java` builds the filtered chunk packets.

## Configuration

Edit `CaveVisSettings.java`, then rebuild the plugin and restart the server.
Terrain loads normally until you build a cache and run `/cavevis reveal`.

Both height settings must be multiples of 16. At the defaults, terrain at
Y=48 and above stays visible while filtering is active. Blocks from Y=48
through Y=63 can still stop rays, so a visible section can block the view
of a hidden cave.

| Setting | Default | What it controls |
| --- | ---: | --- |
| `ALWAYS_VISIBLE_FROM_Y` | 48 | Lowest block Y that always stays visible while filtering is active. |
| `OCCLUDERS_UP_TO_Y` | 64 | Exclusive upper Y limit for blocks that can stop visibility rays. |
| `ENTITY_CULLING_ENABLED` | `true` | Enables filtering of entities in terrain sections the player hasn't received. |
| `PARTICLE_CULLING_ENABLED` | `true` | Enables filtering of particle effects originating in hidden sections. |
| `ENTITY_VISIBILITY_INTERVAL_TICKS` | 5 | How often entity visibility is refreshed, about four times per second at 20 TPS. |
| `DEFAULT_RAYS` | 2048 | Rays per scan. More rays sample more directions and take more CPU time. |
| `RAY_WORKERS` | 2 | Number of worker threads available to run ray scans. |
| `SCAN_STARTS_PER_TICK` | 6 | Maximum number of queued scans the server can start in one tick, shared across players. |
| `SEARCH_RADIUS_CHUNKS` | 10 | Horizontal search radius in chunks. Scans use the available data in the built cache. |
| `SEARCH_INTERVAL_TICKS` | 20 | Normal interval between automatic scans. Movement or terrain updates can request one sooner. |
| `SEARCH_RETRY_DELAY_TICKS` | 20 | Initial wait before retrying a failed scan. Repeated failures increase the wait. |
| `DEFAULT_BUILD_RADIUS_CHUNKS` | 16 | Radius used when `/cavevis raybuild` is called without a number. Radius 16 covers 33 × 33 chunk columns. |
| `BUILD_IN_FLIGHT` | 64 | Maximum number of chunk loads pending or waiting to be captured during a build. |
| `BUILD_BUDGET_MS` | 16.0 | Target server-thread time per tick for loading and capturing chunks during a build. |
| `EDIT_BUDGET_MS` | 1.0 | Target server-thread time per tick for processing queued block changes. |
| `COLUMNS_PER_PLAYER_PER_TICK` | 8 | Maximum chunk columns sent to one player per tick through the normal delivery queue. |
| `COLUMNS_TOTAL_PER_TICK` | 64 | Maximum chunk columns sent per tick across all players through the normal delivery queue. |
| `COLUMN_SEND_BUDGET_MS` | 3.0 | Target server-thread time per tick for normal column delivery. |
| `NEAR_REVEAL_RADIUS_BLOCKS` | 8 | Distance around the player's eyes used to prioritize nearby sections. Whole sections are revealed. |
| `NEAR_SEND_IMMEDIATELY` | `false` | Lets nearby deliveries bypass the normal column limits and time budget. |
| `VISIBILITY_MARGIN_ENABLED` | `true` | Adds a one-section margin in the six face directions to reduce terrain pop-in around corners. Set to `false` to use the original raycast mask. |

Time budgets are targets checked between work items. `BUILD_BUDGET_NANOS`
and `EDIT_BUDGET_NANOS` are calculated from the millisecond settings.