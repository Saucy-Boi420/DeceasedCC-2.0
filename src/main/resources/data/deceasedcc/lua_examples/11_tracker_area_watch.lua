-- 11_tracker_area_watch.lua
-- Proximity watch: register a named AABB around the tracker and print
-- enter/leave events when anything walks through for 60 seconds.

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local trId
for _, d in ipairs(ctrl.listDevices()) do
  if d.type == "entity_tracker" and d.loaded then trId = d.id; break end
end
if not trId then error("no entity_tracker linked") end

local p = ctrl.trackerGetPosition(trId)
local R = 6
ctrl.trackerWatchArea(trId,
  p.x - R, p.y - R, p.z - R,
  p.x + R, p.y + R, p.z + R, "example_watch")

print(("watching a %dx%dx%d box around the tracker for 60s"):format(R*2, R*2, R*2))
local deadline = os.clock() + 60
while os.clock() < deadline do
  local ev = { os.pullEvent() }
  if ev[1] == "entity_proximity_enter" then
    print(("ENTER  %s  %s"):format(ev[3] or "?", ev[4] or ev[2]))
  elseif ev[1] == "entity_proximity_leave" then
    print(("LEAVE  %s"):format(ev[3] or "?"))
  end
end

ctrl.trackerRemoveAreaWatch(trId, "example_watch")
print("cleaned up.")
