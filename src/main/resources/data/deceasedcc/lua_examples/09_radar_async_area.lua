-- 09_radar_async_area.lua
-- Async AABB scan: radar paces across ticks, fires radar_scan_complete
-- when done, then we render the captured scan as a voxel hologram.

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local radarId, holoId
for _, d in ipairs(ctrl.listDevices()) do
  if     d.type == "chunk_radar"        and d.loaded and not radarId then radarId = d.id
  elseif d.type == "hologram_projector" and d.loaded and not holoId  then holoId  = d.id
  end
end
if not radarId then error("no chunk_radar linked") end
if not holoId  then error("no hologram_projector linked") end

local p = ctrl.radarGetPosition(radarId)
local R = 6
local NAME = "example_area_scan"
local job = ctrl.radarScanArea(radarId, p.x - R, p.y - R, p.z - R,
                                         p.x + R, p.y + R, p.z + R, NAME)
print(("scanning %dx%dx%d  (jobId=%s estTicks=%d)..."):format(
  R*2+1, R*2+1, R*2+1, job.jobId, job.estimatedTicks))

while true do
  local _, _, jid, _, count = os.pullEvent("radar_scan_complete")
  if jid == job.jobId then
    print(("done: %d blocks"):format(count)); break
  end
end

ctrl.hologramSetMode(holoId, "3d_culled")
ctrl.hologramSetScale(holoId, 0.8, 0.8, 0.8)
ctrl.hologramSetAlpha(holoId, 0.7)
ctrl.hologramSetOffset(holoId, 0, 0.5, 0)
ctrl.hologramShow(holoId)

local out = ctrl.hologramSetFromScan(holoId, NAME, {
  excludeTypes = { "minecraft:air", "minecraft:cave_air", "minecraft:void_air" },
})
print(("rendered: %dx%dx%d, %d blocks"):format(out.sizeX, out.sizeY, out.sizeZ, out.pointsRendered))

sleep(12)
ctrl.hologramHide(holoId)
ctrl.radarDeleteFile(radarId, NAME)
