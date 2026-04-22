-- 08_radar_simple_scan.lua
-- Synchronous radar scan: list the first few blocks found in a small
-- radius. This API is cooldown-gated (see server config).

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local radarId
for _, d in ipairs(ctrl.listDevices()) do
  if d.type == "chunk_radar" and d.loaded then radarId = d.id; break end
end
if not radarId then error("no chunk_radar linked") end

local RADIUS = 8
local result = ctrl.radarScan(radarId, RADIUS)
print(("scan at radius %d: %d non-air blocks  (stale=%s)"):format(
  RADIUS, result.count or 0, tostring(result.stale)))

local shown = 0
for _, p in pairs(result.entries or {}) do
  print(("  %s at (%d,%d,%d)"):format(p.block, p.x, p.y, p.z))
  shown = shown + 1
  if shown >= 10 then print("  ... truncated"); break end
end
