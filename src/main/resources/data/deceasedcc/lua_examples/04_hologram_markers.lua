-- 04_hologram_markers.lua
-- Show all four marker shapes in a row: cube, diamond, sphere, pyramid.
-- The pyramid uses non-uniform scale + orientation to act as a stretched
-- cone — point an apex along a yaw/pitch direction.

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local holoId
for _, d in ipairs(ctrl.listDevices()) do
  if d.type == "hologram_projector" and d.loaded then holoId = d.id; break end
end
if not holoId then error("no hologram_projector linked") end

ctrl.hologramSetMode(holoId, "markers")
ctrl.hologramSetScale(holoId, 2, 2, 2)
ctrl.hologramSetOffset(holoId, 0, 1.5, 0)
ctrl.hologramClearAlpha(holoId)
ctrl.hologramShow(holoId)

ctrl.hologramSetEntityMarkers(holoId, {
  sizeX = 32, sizeY = 16, sizeZ = 16,
  markers = {
    { x =  4, y = 8, z = 8, shape = "cube",    color = "#FF3030", scale = 1.5 },
    { x = 12, y = 8, z = 8, shape = "diamond", color = "#FFDD00", scale = 1.5 },
    { x = 20, y = 8, z = 8, shape = "sphere",  color = "#00E5FF", scale = 1.5 },
    { x = 28, y = 8, z = 8, shape = "pyramid", color = "#4000FF80",
      scale = { x = 3, y = 2, z = 6 }, yaw = 0, pitch = 0 },
  },
})
print("four marker shapes for 15s. Note the pyramid is non-uniform!")
sleep(15)
ctrl.hologramHide(holoId)
