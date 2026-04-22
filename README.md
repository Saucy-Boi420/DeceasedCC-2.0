# DeceasedCC

A ComputerCraft: Tweaked expansion built for the **DeceasedCraft — Urban Zombie Apocalypse** modpack. Adds surveillance cameras, 3D hologram projectors, chunk-scanning radars, entity trackers, advanced TACZ-powered turrets, and a wireless network controller — all Lua-scriptable from a single computer.

![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-green) ![Forge 47+](https://img.shields.io/badge/Forge-47%2B-orange) ![License MIT](https://img.shields.io/badge/License-MIT-blue)

---

## What it adds

| Block / Item | What it does |
|---|---|
| **Camera** | Captures a frustum of the world, streams a live FBO-rendered feed to a paired Hologram Projector, fires `deceasedcc_motion` CC events on entity entry/leave |
| **Hologram Projector** | Renders 2D images (up to 128×128), 3D voxel grids (up to 64³), entity markers (cube/diamond/sphere/pyramid), or live camera feeds. Greedy-meshed, transform-controllable from Lua |
| **Chunk Radar** | Scans terrain into named scan files, replayable as 3D blueprints on a projector. Async, paced (4096 blocks/tick), survives world restart |
| **Entity Tracker** | Lists / filters entities in range, persistent area-watches with proximity events |
| **Advanced Turret** | TACZ-powered, fully Lua-scriptable: aim, fire, target filters, friendly whitelist, head/body aim, four upgrade slots |
| **Basic Turret** | Autonomous fire-and-forget sentry. No CC surface |
| **Advanced Network Controller** | Wireless hub. One computer drives 8+ devices of any mix via a single peripheral |
| **Linking Tool** | Universal LINK / CHAIN tool that pairs devices to the controller (or cameras ↔ projectors directly) |
| **Example Disk** | CC-mountable floppy with ~15 example Lua scripts + a `/disk/install` browser |

Full per-block / per-API reference: [`USER_GUIDE.md`](USER_GUIDE.md).

---

## Requirements

- Minecraft 1.20.1
- Forge 47+
- ComputerCraft: Tweaked 1.100+
- TACZ (Timeless and Classics Zero)
- GeckoLib 4.0+
- Create + Create Addition
- Immersive Engineering
- c4p (ComputerCraft Create Compatibility Patch)

Every primary recipe ships a vanilla shaped fallback so the mod still degrades gracefully if Create is missing — but the full experience assumes everything above is present.

---

## Quick start (5 minutes)

1. Place an **Advanced Computer** + **Advanced Network Controller** next to each other.
2. Wired-modem the computer to the controller.
3. Place a **Hologram Projector**, a **Camera**, and an **Entity Tracker** anywhere nearby — no wiring needed.
4. Right-click each with a **Linking Tool**, then right-click the controller. Chat will confirm `Linked <type> as id N`.
5. On the computer, `peripheral.find("advanced_network_controller")` and start scripting.

```lua
local ctrl = peripheral.find("advanced_network_controller")
for _, d in ipairs(ctrl.listDevices()) do print(d.id, d.type, d.name) end
ctrl.hologramShow(1)
ctrl.hologramSetVoxelGrid(1, { sizeX = 8, sizeY = 8, sizeZ = 8, palette = {"#FF0000"}, indexes = {...} })
```

Or drop in an **Example Disk** + CC Disk Drive for ~15 ready-to-run scripts and a menu browser.

---

## Documentation

- **[USER_GUIDE.md](USER_GUIDE.md)** — block-by-block reference, full Lua API per peripheral, events, config knobs, troubleshooting

---

## Building from source

```bash
cd deceasedcc
./gradlew build
```

The built jar lands in `build/libs/`. The `copyToMods` task auto-deploys it to a local DeceasedCraft modpack instance if one is detected at the standard CurseForge path; otherwise it prints a warning and continues. JDK 17 required.

---

## Inclusion in modpacks

This mod is MIT-licensed — pack authors are free to include it without asking. A heads-up via GitHub Issues is appreciated but not required. If you ship it, please point users back to this repo for issue reports.

---

## Credits

- **SaucyBoi420** — design, code, art, Lua API
- Live camera-feed FBO pipeline adapted from **SecurityCraft**'s `FrameFeedHandler` pattern (MIT)

---

## License

MIT — see [LICENSE](LICENSE).
