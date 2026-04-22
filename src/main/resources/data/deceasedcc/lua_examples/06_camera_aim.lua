-- 06_camera_aim.lua
-- Swing the first linked camera between four compass directions, then
-- lookAt a fixed world coordinate.

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local cameraId
for _, d in ipairs(ctrl.listDevices()) do
  if d.type == "camera" and d.loaded then cameraId = d.id; break end
end
if not cameraId then error("no camera linked") end

local YAWS = { 0, 90, 180, 270 }
for _, yaw in ipairs(YAWS) do
  ctrl.cameraSetDirection(cameraId, yaw, 0, 0)
  local d = ctrl.cameraGetDirection(cameraId)
  print(("  set yaw=%d → got yaw=%.1f pitch=%.1f roll=%.1f"):format(
    yaw, d.yaw, d.pitch, d.roll))
  sleep(1.2)
end

local camPos = ctrl.getDevicePos(cameraId)
ctrl.cameraLookAt(cameraId, camPos.x + 10, camPos.y + 3, camPos.z + 10)
print("lookAt'd 10 blocks NE and up — hold 3s then reset")
sleep(3)
ctrl.cameraResetDirection(cameraId)
print("reset.")
