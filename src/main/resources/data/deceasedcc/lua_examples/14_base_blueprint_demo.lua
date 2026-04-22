-- 14_base_blueprint_demo.lua
-- A trimmed-down "base blueprint" that stitches radar scans, overlays
-- tracked mobs, and shows camera FOV + turret aim on the hologram. Good
-- reference for integration patterns. See test/base_blueprint.lua for
-- the fuller version with diagnostic logging.

local SCAN_R = 6
local POLL   = 0.5

local ctrl = peripheral.find("advanced_network_controller")
if not ctrl then error("no ANC on the network") end

local radars, turrets, cameras = {}, {}, {}
local holoId, trackerId
for _, d in ipairs(ctrl.listDevices()) do
  if d.type == "chunk_radar"        and d.loaded then radars[#radars+1] = { id=d.id, pos=d.pos }
  elseif d.type == "hologram_projector" and d.loaded and not holoId    then holoId = d.id
  elseif d.type == "entity_tracker" and d.loaded and not trackerId then trackerId = d.id
  elseif d.type == "turret"         and d.loaded then turrets[#turrets+1] = { id=d.id, pos=d.pos }
  elseif d.type == "camera"         and d.loaded then cameras[#cameras+1] = { id=d.id, pos=d.pos }
  end
end
if #radars == 0 then error("no chunk_radar linked") end
if not holoId    then error("no hologram_projector linked") end
if not trackerId then error("no entity_tracker linked") end

local function fwd(yaw, pitch)
  local y, p = math.rad(yaw), math.rad(pitch)
  local cp = math.cos(p)
  return -math.sin(y) * cp, -math.sin(p), math.cos(y) * cp
end

-- Compute union AABB across radars.
local nx, ny, nz =  math.huge,  math.huge,  math.huge
local xx, xy, xz = -math.huge, -math.huge, -math.huge
for _, r in ipairs(radars) do
  if r.pos.x - SCAN_R < nx then nx = r.pos.x - SCAN_R end
  if r.pos.y - SCAN_R < ny then ny = r.pos.y - SCAN_R end
  if r.pos.z - SCAN_R < nz then nz = r.pos.z - SCAN_R end
  if r.pos.x + SCAN_R > xx then xx = r.pos.x + SCAN_R end
  if r.pos.y + SCAN_R > xy then xy = r.pos.y + SCAN_R end
  if r.pos.z + SCAN_R > xz then xz = r.pos.z + SCAN_R end
end
nx, ny, nz = nx - 16, ny - 16, nz - 16
xx, xy, xz = xx + 16, xy + 16, xz + 16

-- Scan each radar.
local names = {}
for _, r in ipairs(radars) do
  local name = ("bp_demo_%d_%d_%d"):format(r.pos.x, r.pos.y, r.pos.z)
  pcall(ctrl.radarDeleteFile, r.id, name)
  local job = ctrl.radarScanArea(r.id,
    r.pos.x - SCAN_R, r.pos.y - SCAN_R, r.pos.z - SCAN_R,
    r.pos.x + SCAN_R, r.pos.y + SCAN_R, r.pos.z + SCAN_R, name)
  names[#names+1] = name
  print(("scanning %s ..."):format(name))
  while true do
    local _, _, jid = os.pullEvent("radar_scan_complete")
    if jid == job.jobId then break end
  end
end

ctrl.hologramSetMode(holoId, "3d_full")
ctrl.hologramSetScale(holoId, 1, 1, 1)
ctrl.hologramSetOffset(holoId, 0, 0.5, 0)
ctrl.hologramSetAlpha(holoId, 0.5)
ctrl.hologramShow(holoId)
ctrl.hologramStitchScans(holoId, names, {
  excludeTypes = { "minecraft:air", "minecraft:cave_air", "minecraft:void_air" },
})

-- Live overlay loop.
local sx, sy, sz = xx-nx+1, xy-ny+1, xz-nz+1
print("overlay running — Ctrl+T to exit")
while true do
  local markers = {}
  local function add(wx, wy, wz, m)
    local lx, ly, lz = wx-nx, wy-ny, wz-nz
    if lx>=0 and ly>=0 and lz>=0 and lx<sx and ly<sy and lz<sz then
      m.x, m.y, m.z = lx, ly, lz
      markers[#markers+1] = m
    end
  end

  local ents = ctrl.trackerGetEntities(trackerId, 32)
  for _, e in pairs(ents) do
    local c = e.isPlayer and "#33CC33" or (e.hostile and "#FF3030" or "#3366FF")
    add(e.x, e.y + 0.5, e.z, { shape="cube", color=c, scale=0.5 })
  end
  for _, t in ipairs(turrets) do
    add(t.pos.x, t.pos.y + 1, t.pos.z, { shape="diamond", color="#FFDD00", scale=0.7 })
    local okS, s = pcall(ctrl.turretGetStatus, t.id)
    if okS and s and s.yaw then
      local dx, dy, dz = fwd(s.yaw, s.pitch)
      add(t.pos.x + dx*8, t.pos.y + 1 + dy*8, t.pos.z + dz*8,
        { shape="cube", color="#FFAA00",
          scale = { x = 0.25, y = 0.25, z = 16 },
          yaw = s.yaw, pitch = s.pitch })
    end
  end
  for _, c in ipairs(cameras) do
    add(c.pos.x, c.pos.y + 0.5, c.pos.z, { shape="diamond", color="#00E5FF", scale=0.7 })
    local okD, d = pcall(ctrl.cameraGetDirection, c.id)
    if okD and d and d.yaw then
      local dx, dy, dz = fwd(d.yaw, d.pitch)
      local R = 16
      add(c.pos.x + dx*R*0.5, c.pos.y + 0.5 + dy*R*0.5, c.pos.z + dz*R*0.5,
        { shape="pyramid", color="#4000AAFF",
          scale = { x = 2*R*math.tan(math.rad(35)), y = 2*R*math.tan(math.rad(35))*0.56, z = R },
          yaw = d.yaw, pitch = d.pitch })
    end
  end
  pcall(ctrl.hologramUpdateMarkers, holoId, { sizeX = sx, sizeY = sy, sizeZ = sz, markers = markers })
  sleep(POLL)
end
