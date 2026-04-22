-- 03_hologram_voxel_cube.lua
-- Hollow cyan cube, slowly rotating.

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local holoId
for _, d in ipairs(ctrl.listDevices()) do
  if d.type == "hologram_projector" and d.loaded then holoId = d.id; break end
end
if not holoId then error("no hologram_projector linked") end

local S = 12
local palette = { "#00FFFF" }
local indexes = {}
for i = 1, S * S * S do indexes[i] = 0 end
for z = 0, S - 1 do for y = 0, S - 1 do for x = 0, S - 1 do
  if x == 0 or x == S - 1 or y == 0 or y == S - 1 or z == 0 or z == S - 1 then
    indexes[1 + x + y * S + z * S * S] = 1
  end
end end end

ctrl.hologramSetMode(holoId, "3d_culled")
ctrl.hologramSetVoxelGrid(holoId, { sizeX = S, sizeY = S, sizeZ = S,
                                    palette = palette, indexes = indexes })
ctrl.hologramSetScale(holoId, 2, 2, 2)
ctrl.hologramSetOffset(holoId, 0, 1, 0)
ctrl.hologramSetAlpha(holoId, 0.85)
ctrl.hologramShow(holoId)

print("hollow cube spinning for 15s (Ctrl+T to stop)...")
local t0 = os.clock()
while os.clock() - t0 < 15 do
  ctrl.hologramSetRotation(holoId, (os.clock() * 60) % 360, 0, 0)
  sleep(0.05)
end
ctrl.hologramHide(holoId)
