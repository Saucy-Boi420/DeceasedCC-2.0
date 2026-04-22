# DeceasedCC v2.0 — User Guide

A beginner-friendly manual for every block, item, and ComputerCraft API this
mod adds. Read the **Quick start** first, then jump to whatever section you
need. Everything marked "(Lua)" has a working API on one of the peripherals.

- [What this mod adds](#what-this-mod-adds)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Blocks](#blocks)
- [Items](#items)
- [The Linking Tool](#the-linking-tool)
- [The Advanced Network Controller](#the-advanced-network-controller)
- [Lua API — overview](#lua-api--overview)
- [Lua API — hologram](#lua-api--hologram)
- [Lua API — camera](#lua-api--camera)
- [Lua API — chunk radar](#lua-api--chunk-radar)
- [Lua API — entity tracker](#lua-api--entity-tracker)
- [Lua API — turret](#lua-api--turret)
- [Events](#events)
- [Config](#config)
- [Troubleshooting](#troubleshooting)

---

## What this mod adds

DeceasedCC extends the DeceasedCraft / Urban Zombie Apocalypse pack with a
set of **ComputerCraft-controllable** security and automation blocks:

- **Surveillance cameras** that capture live video and can stream their feed
  to a 3D hologram projector.
- **Hologram projectors** that render 2D images, 3D voxel grids, entity
  markers, or live camera feeds.
- **Chunk radars** that scan terrain into saved "scan files" you can replay
  as holograms (base blueprints, mining maps, etc.).
- **Entity trackers** that list mobs/players in range, watch named areas,
  and fire CC events on movement / combat / death.
- **Advanced turrets** (aim-controllable, CC-scriptable, TACZ-powered) and
  **basic turrets** (autonomous, no CC).
- An **Advanced Network Controller** that acts as a single wireless hub so
  one computer can drive everything without running wires to each device.

Everything except the Basic Turret and the Turret Remote can be driven by
Lua. The Advanced Network Controller is the recommended way to reach all
devices from one computer — see [its dedicated section](#the-advanced-network-controller).

---

## Requirements

- Minecraft 1.20.1 (Forge).
- **ComputerCraft: Tweaked** — required. Peripherals won't attach without it.
- **TACZ (Timeless and Classics Zero)** — *strongly recommended*. Turrets use
  TACZ guns for firing. Without TACZ, turrets still track/aim but don't
  actually shoot.
- **Create** — *optional*. Main recipes use Create `sequenced_assembly`;
  fallback smithing recipes are provided so the mod works without Create
  installed.

---

## Quick start

A 5-minute setup to see the whole system working:

1. Place an **Advanced Computer** from CC.
2. Place an **Advanced Network Controller** next to it.
3. Attach a **Wired Modem** to the computer, another to the controller, and
   wire them together. Right-click both modems until the ring turns red.
4. Place a **Hologram Projector**, a **Camera**, and an **Entity Tracker**
   nearby. No wiring for these — they talk wirelessly via the controller.
5. Grab a **Linking Tool**. Right-click each of the three devices, then
   right-click the controller. Chat should confirm
   `Linked <type> as id N (N/8)` each time.
6. On the computer, run `ls` — you should see the peripheral appear as
   `advanced_network_controller_0`.
7. Copy-paste a script from [Lua API — hologram](#lua-api--hologram) to make
   something render. Try the voxel-cube example first.

If any step breaks, jump to [Troubleshooting](#troubleshooting).

---

## Blocks

### Advanced Network Controller

The central hub. One controller can link up to **8 devices** (configurable
up to 64) of any mix — turrets, cameras, radars, trackers, projectors. Only
the controller itself needs a wired modem; linked devices talk wirelessly.

- **Place:** anywhere solid.
- **Attach modem:** next to it, and wire it back to your computer.
- **Link devices:** right-click a device with the Linking Tool, then
  right-click the controller.
- **Remove a device:** `controller.removeDevice(id)` in Lua, or break + re-link.
- **GUI:** none — it's a headless hub.
- **Peripheral name:** `advanced_network_controller` (Lua).

### Hologram Projector

Renders holograms 1–16 blocks above itself. Supports four content modes:

| Mode | What it shows |
|------|---------------|
| `2d`        | A flat ARGB image (up to 128×128) |
| `3d_culled` | 3D voxel grid, opaque sides only (fast) |
| `3d_full`   | 3D voxel grid, all faces (for translucent) |
| `markers`   | Entity-marker overlay (cubes/diamonds/spheres/pyramids) |
| (composite) | Voxel grid + markers together |

- **Place:** anywhere flat; needs clear space above it.
- **Pair with a camera:** use the Linking Tool in LINK mode (right-click
  camera, right-click projector) to set up a peer pairing. The projector
  can then `loadFromCamera2D()` for a live video feed.
- **Break it:** clears the client-side cache immediately (no ghost
  holograms left hanging).
- **Peripheral name (direct modem):** `hologram_projector`.
- **ANC proxy prefix:** `hologram*`.

### Camera

A surveillance camera that attaches to floor / wall / ceiling. Captures a
frustum (cone-shaped view) of the world around it.

- **Place:** click a block face. Floor / wall / ceiling auto-detected.
- **Initial aim:** on placement, lens auto-aims at 180° opposite the
  placer (on floor/ceiling) or straight out (wall mount). You can override
  via Lua or the in-world GUI.
- **GUI:** right-click with empty hand — sliders for yaw / pitch / roll
  with numeric input boxes. Click Apply to commit.
- **Pair with a projector:** use the Linking Tool LINK mode as above.
- **Peripheral name (direct modem):** `camera`.
- **ANC proxy prefix:** `camera*`.

### Chunk Radar

Scans blocks in a cubic area and stores the result as a named scan file.
Scan files survive restart and can be rendered by a hologram projector via
`hologramSetFromScan` / `hologramStitchScans`.

- **Place:** anywhere. It scans around itself.
- **Max radius:** 48 blocks (= 3 chunks) by default; configurable up to 64.
- **Scan cost:** paced across ticks (default 4096 blocks/tick), so a full
  96³ scan takes a few seconds but doesn't lag.
- **Peripheral name (direct modem):** `chunk_radar`.
- **ANC proxy prefix:** `radar*`.
- **Events:** fires `radar_scan_complete(jobId, name, count)` when async
  scans finish.

### Entity Tracker

Enumerates entities in range and maintains named **area watches** that
fire proximity events when mobs/players enter or leave.

- **Place:** anywhere. Default scan range is 64 blocks.
- **Player UUID privacy:** by default returns a hash instead of a real
  UUID so scripts can't pin down specific players. Flip `exposePlayerUUIDs`
  in the server config if you really want raw UUIDs.
- **Peripheral name (direct modem):** `entity_tracker`.
- **ANC proxy prefix:** `tracker*`.
- **Events:** `entity_tracker_update`, `entity_proximity_enter`,
  `entity_proximity_leave`.

### Advanced Turret (turret_mount)

A TACZ-powered computer-controllable gun. Supports manual aim, forced
targets, auto-targeting with priority + filter, friendly whitelist, and
head / body aim-point selection.

- **Place:** anywhere. The gun-mount model is a slab; the gun itself
  floats above.
- **Load a gun:** open the GUI (right-click empty-handed). Drop a TACZ
  gun into the weapon slot and ammo into the ammo slots.
- **Enable Computer Control:** the GUI has a toggle. **When OFF, all
  setters from Lua throw `LuaException`.** Read-only methods still work.
  Scripts should always call `isComputerControlled()` first.
- **Upgrades:** four slot types (fire rate, turn speed, range, power),
  Basic (+25%) and Advanced (+50%). Stacks multiplicatively.
- **Peripheral name (direct modem):** `turret_mount`.
- **ANC proxy prefix:** `turret*`.

### Basic Turret

Autonomous, self-contained turret. **No ComputerCraft surface** — does not
attach to modems, does not appear to linking tools, will not link to the
ANC. Use it as a "fire-and-forget" sentry.

- **Place:** anywhere. Works immediately if enabled in config.
- **Config gate:** `basicTurret.enabled` (default true).
- **Sentry swivel:** when a player is within `basicTurret.sentryRadius`
  (default 16), the barrel idles in a slow arc so the turret looks alive
  even without a target.

### Turret Network Controller (legacy)

A v1.9-era hub that only links turrets. Kept for recipe compatibility but
superseded by the Advanced Network Controller in v2.0. You can use either;
the ANC is recommended for new builds.

- **Peripheral name:** `turret_network`.

---

## Items

### Tools

- **Linking Tool** — the universal linker. See [its own section](#the-linking-tool).
- **Turret Linker** (legacy) — links turrets to the Turret Network
  Controller. Kept for v1.9 compatibility; use the Linking Tool for new
  builds.
- **Turret Remote** — wireless control pad (Rarity: RARE). Handheld.
  Drains FE while in camera/control mode. Range scales with Power
  upgrades on it. Kicks if you change dimensions.
- **Example Disk** — a ComputerCraft-compatible floppy packed with ~15
  themed Lua examples (one per peripheral + a menu browser). Insert into
  a CC Disk Drive next to a computer; mounts at `/disk/`. Run
  `/disk/install` for a browse-and-copy menu. Recipe: 4× redstone + 4×
  iron ingots around 1 book in a crafting table. Read-only — library
  ships with the mod jar, always current.

### Turret Upgrades

Each upgrade is single-stack. Place into a turret's upgrade slot via the
GUI. Effects stack **additively**:

| Upgrade | Basic | Advanced |
|---------|-------|----------|
| Fire rate | +25% | +50% |
| Turn speed | +25% | +50% |
| Range | +25% | +50% |
| Power | +25% | +50% |

So 2× Basic = +50%, 3× Advanced = +150%, and so on. Power is currently a
placeholder (not yet wired to TACZ bullet damage).

### WIP items (hidden)

`wip_*` items are intermediate stages used by Create `sequenced_assembly`
recipes. They're not in the creative tab and you'll never see one unless
you're mid-recipe.

---

## The Linking Tool

One item, two modes. Shift+right-click air to toggle:

- **LINK mode** (aqua, default): click a device, then click its
  counterpart.
  - device → Advanced Network Controller  (most common)
  - camera → hologram projector  (peer pairing, no controller needed)
  - hologram projector → camera  (same pair, either direction)
- **CHAIN mode** (gold): click a device that's already linked to
  something, then click a new device. The new device joins the original's
  controller. Clicking a device that's already in the chain REMOVES it.

Chat will confirm every action:
- `Stored <type> at (x, y, z) — now right-click the controller` (first click
  of a link)
- `Linked <type> to controller as id N (N/8)` (completed link)
- `Paired camera ↔ projector at (…) / (…)` (peer pairing)
- `Mode: CHAIN` / `Mode: LINK` (mode toggle)

**Gotcha:** the Linking Tool can't link a **Basic Turret** — basic turrets
have no CC surface. Only the Advanced Turret (`turret_mount`) links.

---

## The Advanced Network Controller

Most of your Lua work happens through this peripheral. The workflow:

```lua
local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

-- list everything linked
for _, d in ipairs(ctrl.listDevices()) do
  print(d.id, d.type, d.name, d.loaded)
end

-- drive a device by id
local holoId = 1  -- from listDevices
ctrl.hologramShow(holoId)
ctrl.hologramSetScale(holoId, 2, 2, 2)
```

Every linked device gets a 1-based `id`. Use `listDevices()` or
`getDeviceIdByName("hologram_projector_0")` to discover it. If a device's
chunk is unloaded, `isDeviceLoaded(id)` returns false and calls that need
the BE will throw a clean error.

### Method families

| Prefix | Drives | Common use |
|--------|--------|-----------|
| `hologram*` | linked hologram projectors | render / transform / live feeds |
| `camera*` | linked cameras | direction, lock-on, snapshots |
| `radar*` | linked chunk radars | scans, inspect, line-of-sight |
| `tracker*` | linked entity trackers | entity queries, area watches |
| `turret*` | linked advanced turrets | enable, aim, fire |

The full method list per family is in the individual API sections below.

---

## Lua API — overview

Two ways to reach a peripheral:

1. **Direct**: each block with a peripheral can have its own wired modem.
   `peripheral.find("hologram_projector")` returns the direct handle. Good
   for single-device setups; no ANC needed.
2. **Via the Advanced Network Controller**: link multiple devices to one
   ANC and drive them all through the controller's `<prefix>*(id, ...)`
   methods. Scales to 8+ devices per controller.

**Rule of thumb:** use the ANC for anything that talks to more than one
device type, or when you need the controller's event fan-out (every event
fired by a linked scanner is automatically re-queued on the controller's
attachments, so your computer gets them without subscribing to each
scanner separately).

All mutating methods are `mainThread = true` — they run on the server tick,
so Lua yields while they execute (fine for normal scripts; heavy loops
should `sleep(0)` between calls).

---

## Lua API — hologram

Reached via `ctrl.hologram*(id, ...)` or direct `hp = peripheral.find("hologram_projector"); hp.xxx(...)`.

### Rotation default

Every content upload (`setImage` / `setVoxelGrid` / `setMarkers` / `setComposite`)
resets rotation to **world-grid-aligned (0, 0, 0)** unless the script has
explicitly called `setRotation(…)` since the last clear. In short: holograms
render world-aligned by default, and you have to opt into rotation each time
you push new content. Call `setRotation(yaw, pitch, roll)` *after* the upload
for a non-default pose.

### Content upload

```lua
-- 2D image. pixels is a flat array of ARGB ints, row-major.
hp.setImage{ width = 32, height = 32, pixels = { 0xFFFFFFFF, 0xFF000000, ... } }

-- 3D voxel grid. palette is 1-based; indexes 0 = empty, 1..N = palette slot.
hp.setVoxelGrid{
  sizeX = 16, sizeY = 16, sizeZ = 16,
  palette = { "#FF0000", "#00FF00", "#0000FF" },   -- or blockIds like "minecraft:stone"
  indexes = { 1, 2, 3, 0, 0, 1, ... },              -- length = sizeX*sizeY*sizeZ
}

-- Entity markers (4 shapes supported: cube, diamond, sphere, pyramid).
-- Scale can be a number (uniform) or {x=, y=, z=} for non-uniform.
-- yaw/pitch are optional (degrees, MC convention) and orient the mesh.
hp.setEntityMarkers{
  sizeX = 32, sizeY = 32, sizeZ = 32,
  markers = {
    { x = 16, y = 16, z = 16, shape = "pyramid",
      color = "#4000AAFF", scale = { x = 8, y = 4.5, z = 20 },
      yaw = 90, pitch = 0 },
    { x = 10, y = 12, z = 14, shape = "cube", color = "#FF0000", scale = 0.5 },
  },
}

-- Composite = voxel grid + markers in one packet (markers inherit the
-- voxel grid's coordinate space).
hp.setComposite{
  voxelGrid = { sizeX = 16, sizeY = 16, sizeZ = 16, palette = {...}, indexes = {...} },
  markers   = { { x = 8, y = 8, z = 8, shape = "diamond", color = "#FFAA00" } },
}

-- Update markers without evicting the voxel grid (for live entity overlays).
hp.updateMarkers{ sizeX = 32, sizeY = 32, sizeZ = 32, markers = { ... } }
```

### Scan-file rendering (via ANC only)

```lua
-- Render a named scan file (produced by radar.scanArea or camera snapshots).
ctrl.hologramSetFromScan(holoId, "my_scan", {
  excludeTypes    = { "minecraft:air", "minecraft:cave_air" },
  palette         = { ["minecraft:glass"] = "#80B8E1F2" },  -- override per-block
  highlightBlocks = { { x=10, y=64, z=20, color = "#FF00FF" } },
  useBlockAtlas   = true,         -- native block colors (see Atlas section)
  cutoutMode      = "transparent", -- or "solid"; only relevant with atlas
})

-- Stitch several radar scans into one big hologram.
ctrl.hologramStitchScans(holoId, { "scan_a", "scan_b", "scan_c" }, { ... })
```

### Native block-color atlas

On first client launch the mod scans every registered block's texture,
averages the pixels, and caches the result to
`config/deceasedcc/block_atlas.json`. The cache is re-used on subsequent
launches unless the loaded mod list changes (hashed for invalidation). The
client ships the atlas to the server on login, and any
`hologramSetFromScan` / `hologramStitchScans` call can opt into
atlas-colored voxels:

```lua
ctrl.hologramSetFromScan(holoId, "my_scan", { useBlockAtlas = true })
```

Options layered over the atlas:
- `palette = { [blockId] = "#RRGGBB" }` — per-block overrides win over atlas.
- `cutoutMode = "solid"` — render cutout blocks (torches, fences, flowers)
  as opaque cubes with their averaged color. Default `"transparent"` keeps
  the atlas's alpha penalty (a torch = faint, a fence = sparse).
- `defaultPalette = true` — if a block isn't in the atlas, fall through to
  the built-in pattern matching instead of the scanner's raw color.

Alpha formula: `alpha = mean_pixel_alpha × opaque_pixel_ratio`. So dirt =
255, glass ≈ 127, torch ≈ 25. Biome-tinted blocks (grass, leaves) use the
un-tinted texture per your setup preference.

### Transform + visibility

```lua
hp.show()                      hp.hide()
hp.setMode("3d_culled")        -- "2d" | "3d_culled" | "3d_full" | "markers"
hp.setScale(2, 2, 2)
hp.setOffset(0, 1.5, 0)        -- YE offset, Y clamped >= 0.5
hp.setRotation(yaw, pitch, roll)
hp.setColor(0xFFFFFFFF)        -- tint (ARGB)
hp.setAlpha(0.6)               -- global alpha multiplier
hp.clear()                     -- erase content
hp.clearAlpha()                -- restore default cap
hp.isVisible() → bool
hp.getAlpha()   → number
hp.getState()   → table        -- everything at once
```

### Live camera feed

Requires the projector to be paired with a camera (Linking Tool in LINK
mode, camera ↔ projector).

```lua
-- Start the live feed. opts are all optional and sticky.
hp.loadFromCamera2D{ width = 640, height = 360, fov = 70 }

-- Subsequent calls without opts act as heartbeats. Without a heartbeat
-- for 3 seconds, the server auto-clears the feed — don't let the script
-- Ctrl+T away and leave the projector live.
hp.loadFromCamera2D()

-- End the feed.
hp.clearLiveCamera()

-- Or use the voxel-splat fallback (rendered from the server, not the
-- player's POV). Slower / lower fidelity but works when the client can't
-- render the camera's POV (e.g., off-screen cameras).
hp.loadFromCamera()
```

**Rate limit:** `setImage` / `setVoxelGrid` / `setMarkers` / `setComposite`
are silently coalesced at `hologram.maxUpdatesPerSecond` (default 2).
Lua keeps succeeding but the projector only updates at that rate. Raise
the config if you need smoother animations.

---

## Lua API — camera

Reached via `ctrl.camera*(id, ...)`.

```lua
ctrl.cameraGetDirection(id)            -- → { yaw, pitch, roll }
ctrl.cameraSetDirection(id, yaw, pitch, roll)
ctrl.cameraLookAt(id, x, y, z)         -- aim at absolute coords
ctrl.cameraLookAtBlock(id, bx, by, bz)
ctrl.cameraResetDirection(id)          -- restore placement yaw/pitch

-- Hard lock-on (camera tracks the target every tick).
ctrl.cameraLockOnto(id, entityUuid)
ctrl.cameraLockOntoBlock(id, bx, by, bz)
ctrl.cameraLockOntoPos(id, x, y, z)
ctrl.cameraClearLockTarget(id)
ctrl.cameraIsLocked(id) → bool

-- Ring buffer of frames (entity lists captured at config cadence).
ctrl.cameraGetFrame(id)               -- latest
ctrl.cameraGetFrameAt(id, ticksAgo)
ctrl.cameraGetBufferSize(id)
ctrl.cameraGetBufferDurationTicks(id)
ctrl.cameraClearBuffer(id)

-- Save a snapshot as a named scan file (replayable on a projector).
ctrl.cameraSaveSnapshot(id, ticksAgo)
ctrl.cameraSaveSnapshot2D(id, { width = 960, height = 540, fov = 70 })

-- Motion trigger — camera fires a CC event when an entity enters its cone.
ctrl.cameraSetMotionTriggerEnabled(id, true)
ctrl.cameraSetMotionFilter(id, {
  categories   = { "player", "hostile" },
  includeTypes = { "minecraft:zombie" },
  excludeTypes = { "minecraft:zombie_villager" },
  minDistance  = 2,
  maxDistance  = 24,
  triggers     = { enter = true, leave = true, present = false },
})
```

---

## Lua API — chunk radar

Reached via `ctrl.radar*(id, ...)`.

```lua
ctrl.radarGetPosition(id)         -- → { x, y, z }
ctrl.radarGetScanRadius(id)       -- config max

-- Synchronous scans (cooldown-gated; returned table has stale flag if cached).
ctrl.radarScan(id, 32)            -- everything in radius
ctrl.radarScanOres(id, 32)        -- Forge ore tag
ctrl.radarScanMod(id, 32, "create")
ctrl.radarScanForBlock(id, 32, "minecraft:diamond_ore")
ctrl.radarScanEmpty(id, 32)       -- only air/replaceable (for LOS)

-- Asynchronous AABB scan. Paces across ticks. Fires radar_scan_complete.
local job = ctrl.radarScanArea(id, x1,y1,z1, x2,y2,z2, "my_scan")
repeat
  local _, _, jid = os.pullEvent("radar_scan_complete")
until jid == job.jobId

-- Saved scan files.
ctrl.radarListFiles(id)           -- → { 1 → name, ... }
ctrl.radarReadFile(id, name)      -- full scan payload
ctrl.radarDeleteFile(id, name)
ctrl.radarRescan(id, name)        -- re-run scan with original bounds

-- Single-block inspect (returns block id + NBT for chests, signs, etc.).
ctrl.radarInspect(id, x, y, z)

-- Cheap LOS check from radar's center to target.
ctrl.radarHasLineOfSight(id, tx, ty, tz) → bool
```

---

## Lua API — entity tracker

Reached via `ctrl.tracker*(id, ...)`.

### Queries

```lua
ctrl.trackerGetPosition(id)
ctrl.trackerGetEntities(id, radius)   -- everything alive
ctrl.trackerGetHostile(id, radius)    -- Enemy instances + mobs with a target
ctrl.trackerGetPlayers(id, radius)
ctrl.trackerGetNearest(id, radius)    -- single closest entity
```

Every entity row looks like:

```lua
{
  type = "minecraft:zombie",
  x = 100.3, y = 64, z = 22.5,
  velocity = { x = 0, y = -0.08, z = 0.12 },
  hostile = true,
  isPlayer = false,
  displayName = "Zombie",
  health = 20, maxHealth = 20,
  uuid = "abc…",
  uuidMasked = false,          -- true if server-config masks UUIDs
}
```

### Per-entity watches

```lua
ctrl.trackerWatchEntity(id, uuid)     -- start watching
ctrl.trackerClearWatch(id)            -- stop all watches
```

Events fired:

- `entity_tracker_update(uuid, reason)` — `reason` is `moved`, `gone`,
  `out_of_range`, `died`, or `combat`.

### Area / proximity watches

```lua
ctrl.trackerScanArea(id, x1,y1,z1, x2,y2,z2, "scan_name")  -- one-shot, saves file
ctrl.trackerWatchArea(id, x1,y1,z1, x2,y2,z2, "watch_name") -- persistent
ctrl.trackerWatchPoint(id, x, y, z, radius, "watch_name")   -- sphere (AABB-approx)
ctrl.trackerWatchRelative(id, dx1,dy1,dz1, dx2,dy2,dz2, "watch_name")
ctrl.trackerListAreaWatches(id)
ctrl.trackerRemoveAreaWatch(id, "watch_name")
ctrl.trackerClearAreaWatches(id)
```

Events fired:

- `entity_proximity_enter(watchName, uuid, type, x, y, z)`
- `entity_proximity_leave(watchName, uuid, type)`

Watches are persistent (saved to NBT, survive world restart).

---

## Lua API — turret

Reached via `ctrl.turret*(id, ...)`. **All setters require the in-world
Computer Control toggle to be ON** (GUI → checkbox). Read-only methods
(`turretGetStatus`, `turretIsComputerControlled`, `turretGetKillLog`) work
regardless.

```lua
ctrl.turretIsComputerControlled(id) → bool  -- check this first
ctrl.turretGetStatus(id)            -- → { enabled, weapon, currentTarget, yaw, pitch, ... }
ctrl.turretGetEffectiveStats(id)    -- after upgrades / config multipliers
ctrl.turretGetKillLog(id)           -- ring buffer of recent kills
ctrl.turretGetDurability(id)        -- gun durability info
ctrl.turretGetAmmoDetails(id)       -- per-slot ammo count
ctrl.turretGetTargetFilter(id) → string
ctrl.turretGetTargetTypes(id) → {...}
ctrl.turretIsAutoHunting(id) → bool

-- Power / mode
ctrl.turretSetEnabled(id, true)
ctrl.turretSetManualMode(id, false)         -- false = auto (needs autoHunt); true = manual only
ctrl.turretSetAutoHunt(id, true)            -- opt into auto-scan (see below)
ctrl.turretSetAimMode(id, "head")           -- "head" | "body"
ctrl.turretSetFireRate(id, 20)              -- ticks between shots (20 = 1/s)
ctrl.turretSetTargetFilter(id, "hostiles")  -- "hostiles" | "allLiving" | "whitelist"
ctrl.turretSetFriendlyFire(id, false)

-- Whitelist entity types (for "whitelist" filter mode).
ctrl.turretAddTargetType(id, "minecraft:zombie")

-- Aim + fire
ctrl.turretSetAim(id, yaw, pitch)
ctrl.turretAimAtPoint(id, x, y, z)          -- → { yaw, pitch }
ctrl.turretForceTarget(id, uuid)            -- override auto-target
ctrl.turretClearTarget(id)
ctrl.turretFire(id)                         -- single shot (CD applies)
```

### Mode / arming rules

A CC-controlled turret is **idle by default**. Three switches govern behavior:

| manualMode | autoHunt | Behavior |
|:---:|:---:|---|
| `true`  | any   | Turret holds whatever yaw/pitch you set. Lua drives everything. Fire only when you call `turretFire`. |
| `false` | `false` (default) | Turret waits for a `forceTarget(uuid)` call and tracks that entity. Otherwise rests. |
| `false` | `true`  | Full auto-sentry. Scans in range (respecting filter / priority / friendlyFire / LOS), picks nearest shootable, aims + fires. |

Opt-in auto-hunt exists so CC turrets don't spontaneously engage until the
script says so. Lua scripts that want auto-sentry behavior **must call
`setAutoHunt(true)`** — forgetting this is the #1 reason turrets "won't fire".

### Minimum engagement distance

The turret refuses to target any entity closer than a gun-type-specific
minimum so point-blank shots don't chew through its own block:

| Gun family | Minimum (blocks) |
|---|:---:|
| Pistol / revolver / SMG | 0.5 |
| Rifle / shotgun / AR / AK / M4 | 1.0 |
| LMG / machinegun / minigun | 1.5 |
| Sniper / AWP / bolt / DMR | 2.0 |

Detected by substring match on the loaded weapon's item registry id
(`tacz:m1911` → pistol, `tacz:m24` → sniper, etc.). Fallback is 1.0.

### Mobs don't aggro on the turret

Non-player mobs cannot target the turret's invisible shooter entity, and
the shooter emits no `GameEvent` vibrations. Zombies (and any mod's
sound-aware AI that goes through GameEvents) ignore turret fire —
they keep pathing toward player noise instead.

A typical "auto-sentry" setup:

```lua
ctrl.turretSetEnabled(id, true)
ctrl.turretSetAimMode(id, "head")
ctrl.turretSetManualMode(id, false)
ctrl.turretSetAutoHunt(id, true)       -- <-- required for auto-scan
ctrl.turretSetTargetFilter(id, "hostiles")
ctrl.turretSetFriendlyFire(id, false)
ctrl.turretSetFireRate(id, 20)   -- 1 shot/sec = "single headshots"
```

---

## Events

All events are queued as normal CC events. If the peripheral is linked to
an Advanced Network Controller, the controller re-queues the event on
its own computer attachments as well, so scripts don't need to subscribe
to each scanner separately.

| Event | Args | Source | When |
|-------|------|--------|------|
| `radar_scan_complete` | `jobId, name, count` | chunk_radar | Async area scan finishes |
| `entity_tracker_update` | `uuid, reason` | entity_tracker | Watched entity changed (reason: `moved`\|`gone`\|`out_of_range`\|`died`\|`combat`) |
| `entity_proximity_enter` | `watchName, uuid, type, x, y, z` | entity_tracker | Entity entered area watch |
| `entity_proximity_leave` | `watchName, uuid, type` | entity_tracker | Entity left area watch |
| `deceasedcc_motion` | filter-specific fields | camera | Motion trigger fired (see `setMotionFilter`) |

Example wait loop:

```lua
while true do
  local ev = { os.pullEvent() }
  if ev[1] == "entity_proximity_enter" then
    print("intruder!", ev[3], "at", ev[5], ev[6], ev[7])
  elseif ev[1] == "radar_scan_complete" then
    print("scan", ev[3], "done:", ev[5], "blocks")
  end
end
```

---

## Config

Two TOML files auto-generated on first world load:

- `world/serverconfig/deceasedcc-server.toml` — gameplay knobs (per world).
- `config/deceasedcc-client.toml` — rendering knobs (per client).

### Server — highlights (full list in the TOML comments)

| Section | Key | Default | Purpose |
|---------|-----|---------|---------|
| `advancedTurret` | `range` | 64 | Target-search radius |
| `advancedTurret` | `fireCooldownMult` | 2.0 | Auto-fire cooldown multiplier |
| `basicTurret` | `enabled` | true | Master toggle |
| `turrets` | `tickRate` | 4 | ENGAGING scan cadence |
| `turrets` | `maxTurnSpeedDegPerTick` | 48 | Turn-speed hard cap |
| `chunkRadar` | `maxRadius` | 48 | 3-chunk cube max |
| `chunkRadar` | `cooldownSeconds` | 5 | Between scans |
| `chunkRadar` | `maxBlocksPerTick` | 4096 | Async scan pacing |
| `entityTracker` | `maxRadius` | 64 | Query range cap |
| `entityTracker` | `exposePlayerUUIDs` | false | Privacy toggle |
| `entityTracker` | `cooldownTicks` | 4 | Watch scan cadence |
| `advancedNetworkController` | `maxConnections` | 8 | Devices per controller |
| `camera` | `captureIntervalTicks` | 10 | 2 Hz frustum scan |
| `camera` | `bufferDurationSeconds` | 60 | Ring-buffer window |
| `camera` | `coneAngleDegrees` | 60 | FOV cone angle |
| `camera` | `coneRange` | 32 | Max sight distance |
| `camera` | `viewMaxWidth` / `viewMaxHeight` | 1920 / 1080 | 2D feed resolution cap |
| `hologram` | `maxUpdatesPerSecond` | 2 | Rate limit on setImage/setVoxelGrid/etc. |
| `hologram` | `scanMaxVoxels` | 65536 | Max voxels for hologramSetFromScan |

### Client

| Key | Default | Purpose |
|-----|---------|---------|
| `hologramRenderDistance` | 128 | Blocks; beyond this, cull holograms |
| `flipGunRender` | false | Flip gun model if it renders backwards |
| `showRangeOverlay` / `showActivityLabel` | false | Reserved for future overlays |

### Auto-migration

Phase 8.1 → 8.3: old camera caps (1280×720) auto-bump to 1920×1080 on
config load. User-customized values are NOT overridden.

---

## Troubleshooting

**Nothing links to the controller / linking tool just opens a GUI.**
- Make sure the block actually exposes CC. Basic turrets don't.
- Hold the Linking Tool — not the Turret Linker (legacy item). Check the
  tooltip.
- Advanced Turret previously intercepted right-click. Fixed in v2.0 —
  make sure you're on the latest build.

**"Computer control is disabled on this turret" (LuaException).**
- Open the turret GUI, tick **Computer Control** ON.
- Scripts can check `ctrl.turretIsComputerControlled(id)` first.

**Hologram never appears.**
- Is it `show()`n? Is `setMode()` correct for your content kind (2D vs voxel)?
- Check `hologramRenderDistance` client config — default 128. If you're far
  from the projector, that's why.
- A nearby shader mod (Iris/Oculus) is active — live-feed captures are
  skipped to avoid crashes. Static holograms still render fine.

**Live feed works for 3 seconds then goes blank.**
- You stopped calling `loadFromCamera2D()`. It's a heartbeat — call it at
  least once per second while you want the feed live.

**Turret won't engage despite "auto" mode.**
- By design. CC turrets rest until told. Call
  `ctrl.turretSetAutoHunt(id, true)` to opt into auto-scanning, or call
  `ctrl.turretForceTarget(id, uuid)` to point it at a specific entity.

**"width 1920 out of range" or similar camera error after updating.**
- Old config file with pre-Phase-8.3 caps. Delete
  `world/serverconfig/deceasedcc-server.toml` and restart; the new defaults
  will auto-generate.

**Radar scan never completes.**
- You're waiting on `radar_scan_complete` but the chunk is unloaded.
- For overlapping scans, the event can drop — poll `radarReadFile(name)`
  instead (returns non-nil once the file appears).

**MC restart required after updating the mod jar.**
- Always. `gradlew build` copies the jar into `mods/`; MC still uses the
  pre-restart classes until a full relaunch.

**Where are the example scripts?**
- `test/` in the repo. Many are dual-copied into the test save at
  `saves/New World (1)/computercraft/computer/0/`. Key ones to start with:
  - `peripheral_inspect.lua` — dump every peripheral on the network.
  - `hologram_hello_image.lua` — 2D image demo.
  - `hologram_wireframe_box.lua` — voxel grid demo.
  - `cctv_live.lua` — live camera feed.
  - `base_blueprint.lua` — full base blueprint with turrets + cameras.
  - `v2_integration_test.lua` — menu-driven walkthrough of every
    peripheral.

---

*DeceasedCC v2.0 — ship built 2026-04-21. See `v2.0-stages.txt` for the
full development log, `MEMORY_AUDIT.md` for retention-critical system
details, and `ideas.txt` for the post-v2.0 roadmap.*
