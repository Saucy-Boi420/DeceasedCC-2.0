-- 01_inspect_network.lua
-- Dump every peripheral on the network + every device linked to the
-- Advanced Network Controller. Safe to run on any setup.

for _, n in ipairs(peripheral.getNames()) do
  print(("peripheral: %-40s  type=%s"):format(n, peripheral.getType(n)))
end

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then
  print("\nno advanced_network_controller on network — link one with a modem.")
  return
end

print(("\nANC @%s   capacity %d/%d"):format(
  textutils.serialise(ctrl.getPosition()),
  ctrl.getDeviceCount(), ctrl.getMaxConnections()))

for _, d in ipairs(ctrl.listDevices()) do
  print(string.format("  id=%-2d  %-22s  @(%d,%d,%d)  loaded=%s",
    d.id, d.type, d.pos.x, d.pos.y, d.pos.z, tostring(d.loaded)))
end
