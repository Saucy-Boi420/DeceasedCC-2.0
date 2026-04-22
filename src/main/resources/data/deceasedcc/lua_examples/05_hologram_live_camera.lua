-- 05_hologram_live_camera.lua
-- Live camera feed on a projector. Remember: the camera and projector
-- must be PAIRED with the Linking Tool (camera → projector).

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local cameraId, holoId
for _, d in ipairs(ctrl.listDevices()) do
  if     d.type == "camera"             and d.loaded and not cameraId then cameraId = d.id
  elseif d.type == "hologram_projector" and d.loaded and not holoId    then holoId    = d.id
  end
end
if not cameraId then error("no camera linked") end
if not holoId   then error("no hologram_projector linked") end

ctrl.hologramSetScale(holoId, 2, 2, 2)
ctrl.hologramSetOffset(holoId, 0, 1.5, 0)
ctrl.hologramClearAlpha(holoId)
ctrl.hologramShow(holoId)

-- Sticky opts. Without opts the call acts as a heartbeat.
ctrl.hologramLoadFromCamera2D(holoId, cameraId, { width = 640, height = 360, fov = 70 })
print("live 360p feed for 20s (heartbeat each loop)...")

local t0 = os.clock()
while os.clock() - t0 < 20 do
  ctrl.hologramLoadFromCamera2D(holoId, cameraId)
  sleep(0.5)
end
ctrl.hologramHide(holoId)
