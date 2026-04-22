# DeceasedCC — Peripheral API reference

All Lua methods below are surfaced through `peripheral.wrap(...)` or `peripheral.call(...)`. Every method returns plain Lua values. Tables are always integer-indexed unless noted.

> **Implementation status legend**
> ✅ fully wired end-to-end
> 🟡 API complete, integration delegated to reflection bridge — works when the target mod is loaded and its internals match
> 🔒 stub: the method signature is locked and safe-to-call; the heavy lifting (pathfinding, craft dispatch, schematic loading) has a TODO in the source and will no-op until wired

Peripheral names below are the values returned by `peripheral.getType(...)`.

---

## `chunk_radar` — Phase 3 ✅

| Method | Signature | Returns |
| --- | --- | --- |
| `scan(radius)` | `radius:int (1..maxRadius)` | scan table |
| `scanOres(radius)` | same | scan table, filtered to `forge:ores` |
| `scanMod(radius, modid)` | `modid:string` | scan table, filtered to that mod |
| `getLastScan()` | — | last cached scan (flagged `stale=true`) |
| `getScanRadius()` | — | int — configured max |

### Scan table shape

```lua
{
    stale = false,            -- true when returned from cache
    timestamp = 1713302400000, -- ms since epoch
    radius = 8,
    count = 42,
    entries = {
        [1] = { x = 3, y = -1, z = 2, block = "minecraft:diamond_ore", mod = "minecraft", nbt = { ... } },
        [2] = { ... }
    }
}
```

Entries are relative to the radar block. `nbt` is only present for tile entities. A scan is allowed at most once every `chunkRadar.cooldownSeconds` seconds per peripheral instance; calls inside that window return the cached result with `stale = true`.

### Example

```lua
local r = peripheral.wrap("right")
local hits = r.scanOres(8)
for _, e in pairs(hits.entries) do print(e.block, e.x, e.y, e.z) end
```

---

## `entity_tracker` — Phase 4 ✅

| Method | Signature | Returns |
| --- | --- | --- |
| `getEntities(radius)` | `radius:int` | table of entity rows |
| `getHostile(radius)` | same | subset — hostile only |
| `getPlayers(radius)` | same | subset — players only |
| `getNearest(radius)` | same | single row (or nil) |
| `watchEntity(uuid)` | `uuid:string` | — |
| `clearWatch()` | — | — |

### Entity row shape

```lua
{
    type = "minecraft:zombie",
    registryName = "minecraft:zombie",
    uuid = "aa11...",             -- masked hash if player & exposePlayerUUIDs=false
    uuidMasked = false,
    x = 12.5, y = 64, z = -7.2,
    velocity = { x = 0, y = -0.08, z = 0 },
    hostile = true,
    isPlayer = false,
    displayName = "Zombie",
    health = 20, maxHealth = 20
}
```

### Events

Watched entities fire CC event `entity_tracker_update` with two args: `uuid`, and a `reason` string which is one of `"moved"`, `"combat"`, `"died"`, `"out_of_range"`, `"gone"`. Moves fire only when the entity travels more than 2 blocks from its last observed position.

---

## `turret_mount` — Phase 5 🟡 (requires TACZ for firing)

| Method | Signature | Returns |
| --- | --- | --- |
| `setEnabled(bool)` | — | — |
| `setTargetPriority(type)` | `"nearest" \| "mostDangerous" \| "lowestHealth" \| "firstInRange"` | — |
| `setFriendlyFire(bool)` | — | — |
| `setSector(min, max)` | degrees in `[0,360]` | — |
| `getStatus()` | — | state table |
| `getKillLog()` | — | last-20 kills |
| `forceTarget(uuid)` | `uuid:string` | — |
| `clearTarget()` | — | — |

`getStatus()` returns: `enabled`, `weaponLoaded`, `weapon`, `ammo` (int), `currentTarget` (uuid or nil), `yaw`, `pitch`.

`getKillLog()` returns `{ [1] = { entity = "...", timestamp = <ms> } }`.

---

## `turret_network` — Phase 5 🟡

| Method | Signature | Returns |
| --- | --- | --- |
| `getAllStatus()` | — | `{ ["x,y,z"] = status_table }` |
| `setNetworkEnabled(bool)` | — | — |
| `setNetworkPriority(type)` | same options as per-turret | — |
| `assignSectors(table)` | `{ ["x,y,z"] = { min=, max= } }` | — |
| `getNetworkKillLog()` | — | aggregated log |
| `coordinateTargeting(bool)` | — | when true, no two turrets pick the same target on one tick |
| `getNetworkAmmo()` | — | `{ ["x,y,z"] = ammo_count }` |

---

## `sequencer_brain` — Phase 6 🟡

| Method | Signature |
| --- | --- |
| `defineSequence(id, steps)` | `steps` is `{ { action="SET_SPEED", target="main_shaft", params={speed=256} }, ... }` |
| `runSequence(id)` | — |
| `stopSequence()` | — |
| `pauseSequence()` / `resumeSequence()` | — |
| `getSequenceStatus()` | `{ running, paused, sequenceId, stepIndex }` |
| `linkComponent(localName, pos)` | `pos = {x=, y=, z=}` |
| `onStepComplete(callback)` | fires CC event `sequencer_step` (sequenceId, index, actionType) |

### Supported action types

`SET_SPEED`, `SET_ROTATION`, `WAIT_TICKS`, `WAIT_CONDITION`, `ACTIVATE_ARM`, `SET_GEARSHIFT`, `REVERSE`, `STOP`.

### Supported conditions

`inventoryContains`, `fluidAbove`, `entityNearby`, `peripheralReturns`. A condition table looks like `{ type = "inventoryContains", item = "minecraft:cobblestone", count = 64, target = "storage_side" }`. The condition evaluator resolves each condition type reflectively against Create's capability system; with Create absent, conditions fail-closed (block the sequence).

---

## `storage_brain` — Phase 7 🟡 (requires Refined Storage)

| Method | Signature |
| --- | --- |
| `listItems()` | — |
| `getItem(name)` | `name:string` |
| `exportItem(name, count, direction)` | direction is a facing name |
| `importItems(direction)` | — |
| `watchItem(name, threshold, condition)` | `condition = "above" \| "below"` |
| `clearWatches()` | — |
| `getNetworkEnergy()` | `{ current, max }` |
| `getDiskStatus()` | — |

Watched items fire CC event `storage_alert` with `{ name, count, threshold, condition }`.

---

## `crafting_scheduler` — Phase 7 🟡

| Method | Signature |
| --- | --- |
| `queueCraft(name, count, priority)` | priority `1..10` |
| `getQueue()` | list of queued jobs |
| `cancelJob(id)` | returns bool |
| `getActiveCrafts()` | — |
| `setIdleThreshold(ticks)` | — |
| `onCraftComplete(name)` | fires CC event `craft_complete` (name, count) |

---

## `network_mapper` — Phase 7 🟡

| Method | Signature |
| --- | --- |
| `getTopology()` | graph of RS nodes |
| `getDeviceStatus(pos)` | `pos = { x=, y=, z= }` |
| `findDisconnected()` | list of dangling nodes |

---

## `neural_link_emitter` — Phase 8 ✅

| Method | Signature | Returns |
| --- | --- | --- |
| `alert(playerName, message, priority)` | `priority = "low"\|"medium"\|"high"` | bool (true if sent) |
| `broadcast(message, priority)` | same | int (number of recipients) |
| `getRegisteredPlayers()` | — | list of player names |

Alerts appear as toasts in the top-right of the target client. `"high"` additionally plays a beacon activation chime. Toasts persist for 5 seconds.

The chip item itself (`deceasedcc:neural_link_chip`) is registered in a Curios slot identified as `neural_link`. Right-click any CC computer while holding the chip to register that computer's address; up to `neuralLink.maxComputers` computers per chip. The registered list is stored as NBT on the chip and survives drops.

### Client-side dashboard

Press **N** (rebindable under Controls → DeceasedCC) to open the dashboard. The dashboard lists registered computers and can display a read-only snapshot of any selected computer's terminal output. Snapshots are pushed from the server via `NeuralTerminalSnapshotPacket` — a Lua script calls `neurallink.pushSnapshot(text)` on its emitter to supply them.

---

## `hologram_projector` — Phase 10 🟡 (rendering works; voxel file I/O requires inlining JSON)

| Method | Signature |
| --- | --- |
| `loadVoxel(filename)` | loads a `.dcvox` file from the computer's filesystem *(see caveat below)* |
| `setColor(r, g, b)` | each `0..255` |
| `setScale(scale)` | `0.1..hologram.maxScale` |
| `setRotation(x, y, z)` | fixed angles in degrees |
| `setAutoRotate(axis, speed)` | `axis = "x"\|"y"\|"z"` |
| `setMusicReactive(bool)` | — |
| `setAnimationMode(mode)` | `"PULSE"\|"SPIN"\|"GLOW"\|"WAVE"\|"EXPLODE"` |
| `setSensitivity(value)` | `0.0..1.0` |
| `clear()` | — |
| `getStatus()` | state dump |

### `.dcvox` format

See `DCVOX_FORMAT.md` in the resources folder. A minimal example:

```json
{
    "dimensions": { "x": 4, "y": 4, "z": 4 },
    "color": "#33CCCC",
    "voxels": [
        { "x": 0, "y": 0, "z": 0, "filled": true },
        { "x": 1, "y": 0, "z": 0, "filled": true }
    ]
}
```

**Caveat on `loadVoxel(filename)`:** the CC:T `IComputerAccess` available to block-entity peripherals in 1.20.1 does not expose a clean filesystem mount lookup for read-only access (the mount is attached to a specific computer side and resolved at computer boot). As a fallback, the spec intent is preserved by accepting the same JSON payload through a dedicated helper method `loadVoxelJson(raw)` that takes the raw `.dcvox` string directly — typical usage:

```lua
local file = fs.open("models/drone.dcvox", "r")
peripheral.call("top", "loadVoxelJson", file.readAll())
file.close()
```

Both `loadVoxel` and `loadVoxelJson` end up at the same parser.

---

## Turtle upgrades — Phase 9

Register each with a turtle through the standard CC:T upgrade crafting system using the item declared in the upgrade's datafile under `data/deceasedcc/computercraft/turtle_upgrades/`.

### `surveyor` ✅

| Method | Signature |
| --- | --- |
| `turtle.survey(radius)` | identical shape to Chunk Radar |
| `turtle.markTarget(x, y, z)` | — |
| `turtle.getTargets()` | list of marked targets |

Marked targets persist on the upgrade's NBT.

### `geological` 🟡

| Method | Signature |
| --- | --- |
| `turtle.analyzeVein(x, y, z)` | flood-fills ore-tagged blocks connected to the start; capped at 256 nodes |
| `turtle.getBiomeReport()` | per-block-type totals accumulated across vein analyses |

### `combat` 🔒 (TACZ required)

| Method | Signature |
| --- | --- |
| `turtle.equip(slot)` | stores an item as the turtle's active weapon |
| `turtle.fireAt(uuid)` | — |
| `turtle.patrol(waypointTable)` | — |
| `turtle.setCombatMode(mode)` | `"aggressive" \| "defensive" \| "guard"` |

### `medic` 🟡

| Method | Signature |
| --- | --- |
| `turtle.bindPlayer(playerName)` | — |
| `turtle.getPatientStatus()` | `{ health, maxHealth, hunger, effects }` |
| `turtle.treatPatient()` | — |
| `turtle.setThresholds(health, hunger)` | — |

### `logistics` 🔒

| Method | Signature |
| --- | --- |
| `turtle.bridgeNetworks(sourcePos, destPos)` | both positions are `{x=, y=, z=}` tables |
| `turtle.setFilter(itemList)` | — |
| `turtle.getTransferLog()` | — |

### `architect` 🔒

| Method | Signature |
| --- | --- |
| `turtle.loadSchematic(filename)` | — |
| `turtle.buildSchematic(originX, originY, originZ)` | — |
| `turtle.getBuildStatus()` | `{ total, placed, missing, etaPct }` |

---

## Configuration keys

See `config/deceasedcc-common.toml`. Defaults:

| Key | Default | Range |
| --- | --- | --- |
| `chunkRadar.maxRadius` | 16 | 1..32 |
| `chunkRadar.cooldownSeconds` | 5 | 1..300 |
| `entityTracker.maxRadius` | 64 | 1..128 |
| `entityTracker.exposePlayerUUIDs` | false | bool |
| `turret.tickRate` | 4 | 1..40 |
| `turret.networkRange` | 32 | 4..128 |
| `neuralLink.maxComputers` | 16 | 1..64 |
| `neuralLink.alertQueueSize` | 64 | 1..1024 |
| `hologram.maxScale` | 10.0 | 0.1..64.0 |
| `hologram.maxVoxelCount` | 8192 | 1..262144 |

---

## CC events

| Event | Args | Fired by |
| --- | --- | --- |
| `entity_tracker_update` | `uuid, reason` | entity_tracker |
| `sequencer_step` | `sequenceId, index, actionType` | sequencer_brain |
| `storage_alert` | `{ name, count, threshold, condition }` | storage_brain |
| `craft_complete` | `name, count` | crafting_scheduler |
