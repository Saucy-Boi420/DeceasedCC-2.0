-- 12_turret_auto_sentry.lua
-- Arm every linked Advanced Turret in head-aim auto-sentry mode with a
-- slow fire rate (1 shot/sec = "single headshots"). Prints diagnostics
-- every 2s so you can see whether the turret sees targets and fires.
--
-- REMEMBER: open the turret GUI and tick "Computer Control" ON. Also
-- need a TACZ gun + ammo loaded for actual bullets.

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local turretIds = {}
for _, d in ipairs(ctrl.listDevices()) do
  if d.type == "turret" and d.loaded then turretIds[#turretIds + 1] = d.id end
end
if #turretIds == 0 then error("no advanced turrets linked — link via the Linking Tool") end

for _, id in ipairs(turretIds) do
  if not ctrl.turretIsComputerControlled(id) then
    print(("  turret %d: Computer Control OFF in GUI — skipping"):format(id))
  else
    pcall(ctrl.turretSetManualMode, id, false)
    pcall(ctrl.turretSetAimMode,    id, "head")
    pcall(ctrl.turretSetTargetFilter, id, "hostiles")
    pcall(ctrl.turretSetFriendlyFire, id, false)
    pcall(ctrl.turretSetFireRate,   id, 20)
    -- REQUIRED: opt the turret in to auto-scan. Without this, CC
    -- turrets rest until Lua forceTargets them — they don't hunt.
    pcall(ctrl.turretSetAutoHunt,   id, true)
    pcall(ctrl.turretSetEnabled,    id, true)
    print(("  turret %d armed (head / auto / hostiles / 1 shot/sec)"):format(id))
  end
end

print("\narmed — monitoring for 60s. Ctrl+T to disarm + exit.")
local deadline = os.clock() + 60
while os.clock() < deadline do
  for _, id in ipairs(turretIds) do
    local s = ctrl.turretGetStatus(id)
    print(("  t%d  en=%s  wpn=%s  tgt=%s  yaw=%.1f  pitch=%.1f"):format(
      id, tostring(s.enabled), tostring(s.weaponLoaded),
      tostring(s.currentTarget), s.yaw or 0, s.pitch or 0))
  end
  sleep(2)
end

for _, id in ipairs(turretIds) do pcall(ctrl.turretSetEnabled, id, false) end
print("disarmed.")
