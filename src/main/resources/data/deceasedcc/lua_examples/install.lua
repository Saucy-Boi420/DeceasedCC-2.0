-- DeceasedCC Example Disk browser.
-- View any script or copy it to the computer's filesystem.

local EXAMPLES = {
  { key = "01", name = "inspect_network",       desc = "list all peripherals + ANC-linked devices" },
  { key = "02", name = "hologram_2d_image",     desc = "draw a 2D image on a projector" },
  { key = "03", name = "hologram_voxel_cube",   desc = "draw a hollow voxel cube" },
  { key = "04", name = "hologram_markers",      desc = "marker shapes: cube, diamond, sphere, pyramid" },
  { key = "05", name = "hologram_live_camera",  desc = "live camera feed on a projector" },
  { key = "06", name = "camera_aim",            desc = "set camera yaw/pitch + lookAt demo" },
  { key = "07", name = "camera_motion_alarm",   desc = "camera motion trigger alert" },
  { key = "08", name = "radar_simple_scan",     desc = "sync radar scan — print nearby blocks" },
  { key = "09", name = "radar_async_area",      desc = "async AABB scan + render on projector" },
  { key = "10", name = "tracker_entities",      desc = "list entities/hostiles/players in range" },
  { key = "11", name = "tracker_area_watch",    desc = "proximity enter/leave events" },
  { key = "12", name = "turret_auto_sentry",    desc = "arm turret in head-mode auto-sentry" },
  { key = "13", name = "turret_manual_fire",    desc = "manual aim + single shots" },
  { key = "14", name = "base_blueprint_demo",   desc = "blueprint + overlay + cones combined" },
  { key = "15", name = "full_integration_tour", desc = "menu that runs every example in turn" },
}

local function listing()
  term.clear(); term.setCursorPos(1, 1)
  print("=== DeceasedCC Example Disk ===")
  print("")
  for _, e in ipairs(EXAMPLES) do
    print(string.format("  %s  %-28s  %s", e.key, e.name, e.desc))
  end
  print("")
  print("Actions:")
  print("  v <NN>   view a script")
  print("  c <NN>   copy a script to this computer's root")
  print("  a        copy ALL scripts to /examples/ on this computer")
  print("  q        quit")
  print("")
  write("> ")
end

local function pathOf(e) return ("/disk/%s_%s.lua"):format(e.key, e.name) end
local function destOf(e) return ("/%s.lua"):format(e.name) end
local function findByKey(key)
  for _, e in ipairs(EXAMPLES) do if e.key == key then return e end end
  return nil
end

local function viewOne(key)
  local e = findByKey(key)
  if not e then print("no example with key " .. key); return end
  if not fs.exists(pathOf(e)) then print("not found on disk: " .. pathOf(e)); return end
  local f = io.open(pathOf(e), "r")
  if not f then print("couldn't open " .. pathOf(e)); return end
  term.clear(); term.setCursorPos(1, 1)
  print(("--- %s  (any key to continue) ---"):format(e.name))
  for line in f:lines() do print(line) end
  f:close()
  print("--- end ---")
  os.pullEvent("key")
end

local function copyOne(key)
  local e = findByKey(key)
  if not e then print("no example with key " .. key); return end
  if fs.exists(destOf(e)) then fs.delete(destOf(e)) end
  fs.copy(pathOf(e), destOf(e))
  print(("copied %s → %s"):format(pathOf(e), destOf(e)))
end

local function copyAll()
  if not fs.exists("/examples") then fs.makeDir("/examples") end
  for _, e in ipairs(EXAMPLES) do
    local dst = "/examples/" .. e.name .. ".lua"
    if fs.exists(dst) then fs.delete(dst) end
    fs.copy(pathOf(e), dst)
    print("copied " .. dst)
  end
end

while true do
  listing()
  local input = read() or ""
  input = input:lower():gsub("^%s+", ""):gsub("%s+$", "")
  if input == "q" or input == "quit" or input == "exit" then
    return
  elseif input == "a" then
    copyAll()
    print("done — run any example with `<name>` from /examples/. Enter to continue.")
    read()
  elseif input:sub(1, 2) == "v " then
    viewOne(input:sub(3))
  elseif input:sub(1, 2) == "c " then
    copyOne(input:sub(3))
    print("Enter to continue.")
    read()
  elseif input == "" then
    -- just re-render
  else
    print("unknown command: " .. input)
    print("Enter to continue.")
    read()
  end
end
