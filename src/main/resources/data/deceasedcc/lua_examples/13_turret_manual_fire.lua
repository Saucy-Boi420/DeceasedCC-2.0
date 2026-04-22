-- 13_turret_manual_fire.lua
-- Manual turret control: aim at a point relative to the turret and fire
-- a single shot every couple seconds.

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local turretId
for _, d in ipairs(ctrl.listDevices()) do
  if d.type == "turret" and d.loaded then turretId = d.id; break end
end
if not turretId then error("no advanced turret linked") end
if not ctrl.turretIsComputerControlled(turretId) then
  error("turret Computer Control is OFF — open the turret GUI and enable it")
end

ctrl.turretSetManualMode(turretId, true)
ctrl.turretSetEnabled(turretId, true)

local p = ctrl.getDevicePos(turretId)
local targets = {
  { x = p.x + 10, y = p.y + 2, z = p.z      },
  { x = p.x,      y = p.y + 2, z = p.z + 10 },
  { x = p.x - 10, y = p.y + 2, z = p.z      },
  { x = p.x,      y = p.y + 2, z = p.z - 10 },
}

for _, t in ipairs(targets) do
  local aim = ctrl.turretAimAtPoint(turretId, t.x, t.y, t.z)
  print(("aim → (%d,%d,%d)  yaw=%.1f pitch=%.1f"):format(t.x, t.y, t.z, aim.yaw, aim.pitch))
  sleep(0.8)
  local ok = ctrl.turretFire(turretId)
  print("  fire: " .. tostring(ok))
  sleep(1.5)
end

ctrl.turretSetManualMode(turretId, false)
ctrl.turretSetEnabled(turretId, false)
print("done.")
