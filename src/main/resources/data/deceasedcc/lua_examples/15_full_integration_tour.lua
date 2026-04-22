-- 15_full_integration_tour.lua
-- Runs every other example on the disk in sequence, pausing between
-- each so you can see the effect. Use this as a "does everything on my
-- setup actually work?" smoke test.

local ORDER = {
  "01_inspect_network",
  "02_hologram_2d_image",
  "03_hologram_voxel_cube",
  "04_hologram_markers",
  "05_hologram_live_camera",
  "06_camera_aim",
  "08_radar_simple_scan",
  "09_radar_async_area",
  "10_tracker_entities",
  -- 07, 11, 12, 13, 14 are event-driven / long-running, skip them here.
}

for _, name in ipairs(ORDER) do
  local path = "/disk/" .. name .. ".lua"
  if fs.exists(path) then
    print(("\n--- running %s ---"):format(name))
    local ok, err = pcall(function() shell.run(path) end)
    if not ok then print("ERROR: " .. tostring(err)) end
    print("--- done. Enter to continue ---")
    read()
  else
    print("skip (missing): " .. path)
  end
end
print("\ntour complete.")
