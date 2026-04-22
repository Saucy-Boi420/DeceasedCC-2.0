-- 07_camera_motion_alarm.lua
-- Enable the camera's motion trigger and print an alert when something
-- walks into its FOV. 60-second demo.

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local cameraId
for _, d in ipairs(ctrl.listDevices()) do
  if d.type == "camera" and d.loaded then cameraId = d.id; break end
end
if not cameraId then error("no camera linked") end

ctrl.cameraSetMotionFilter(cameraId, {
  categories   = { "player", "hostile" },
  minDistance  = 2,
  maxDistance  = 24,
  triggers     = { enter = true, leave = true, present = false },
})
ctrl.cameraSetMotionTriggerEnabled(cameraId, true)
print("armed — walk into the camera's cone. 60s demo.")

local deadline = os.clock() + 60
while os.clock() < deadline do
  local ev = { os.pullEvent("deceasedcc_motion") }
  if ev[1] == "deceasedcc_motion" then
    print(("MOTION  %s"):format(textutils.serialise(ev, { compact = true })))
  end
end

ctrl.cameraSetMotionTriggerEnabled(cameraId, false)
print("disarmed.")
