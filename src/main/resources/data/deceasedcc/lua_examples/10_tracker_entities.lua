-- 10_tracker_entities.lua
-- Enumerate players, hostiles, and other entities around an entity
-- tracker at three different radii.

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local trId
for _, d in ipairs(ctrl.listDevices()) do
  if d.type == "entity_tracker" and d.loaded then trId = d.id; break end
end
if not trId then error("no entity_tracker linked") end

local function dump(label, tbl)
  local n = 0
  for _ in pairs(tbl) do n = n + 1 end
  print(("%s: %d"):format(label, n))
  local shown = 0
  for _, r in pairs(tbl) do
    print(("  %s  hp=%.1f/%.1f  @(%.1f,%.1f,%.1f)"):format(
      r.displayName, r.health or 0, r.maxHealth or 0, r.x, r.y, r.z))
    shown = shown + 1
    if shown >= 5 then print("  ..."); break end
  end
end

dump("players within 64",  ctrl.trackerGetPlayers(trId, 64))
dump("hostiles within 32", ctrl.trackerGetHostile(trId, 32))
dump("all within 16",      ctrl.trackerGetEntities(trId, 16))

local nearest = ctrl.trackerGetNearest(trId, 64)
if nearest then
  print(("nearest = %s @(%.1f,%.1f,%.1f)"):format(
    nearest.displayName, nearest.x, nearest.y, nearest.z))
else
  print("nearest = (nothing within 64)")
end
