-- 02_hologram_2d_image.lua
-- Draw a 32x32 checkerboard on the first linked hologram projector.

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local holoId
for _, d in ipairs(ctrl.listDevices()) do
  if d.type == "hologram_projector" and d.loaded then holoId = d.id; break end
end
if not holoId then error("no hologram_projector linked") end

local W, H = 32, 32
local pixels = {}
for y = 0, H - 1 do for x = 0, W - 1 do
  local on = ((math.floor(x / 4) + math.floor(y / 4)) % 2 == 0)
  pixels[1 + x + y * W] = on and 0xFFFFFFFF or 0xFF000000
end end

ctrl.hologramSetMode(holoId, "2d")
ctrl.hologramSetImage(holoId, { width = W, height = H, pixels = pixels })
ctrl.hologramSetScale(holoId, 2, 2, 2)
ctrl.hologramSetOffset(holoId, 0, 1.5, 0)
ctrl.hologramShow(holoId)

print("checkerboard up for 10s...")
sleep(10)
ctrl.hologramHide(holoId)
