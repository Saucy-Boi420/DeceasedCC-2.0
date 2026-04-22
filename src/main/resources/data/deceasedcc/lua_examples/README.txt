DeceasedCC Example Disk
=======================

You're reading this from a CC Disk Drive — the disk is mounted at /disk/.

QUICK START
  1. `cd disk`           change into the disk
  2. `install`           open the menu browser: view any script, copy to PC
  3. `ls`                list every file
  4. `edit <name>`       open a file in the CC editor (read-only from disk)
  5. `cp <name> /<name>` copy to the computer's own filesystem

CONTENTS (by theme)
  01_inspect_network         list every peripheral + linked device
  02_hologram_2d_image       draw a 2D image on a projector
  03_hologram_voxel_cube     draw a hollow voxel cube
  04_hologram_markers        marker shapes (cube / diamond / sphere / pyramid)
  05_hologram_live_camera    live camera feed on a projector
  06_camera_aim              camera direction + lookAt demo
  07_camera_motion_alarm     camera motion trigger → chat alert
  08_radar_simple_scan       sync scan, print nearby blocks
  09_radar_async_area        async AABB scan + render on projector
  10_tracker_entities        list nearby entities / hostiles / players
  11_tracker_area_watch      proximity enter/leave events
  12_turret_auto_sentry      arm turret in head-mode auto-sentry
  13_turret_manual_fire      manual aim + single shots
  14_base_blueprint_demo     combined blueprint + overlay + cones
  15_full_integration_tour   menu that runs every example in sequence

WIRING REMINDER
  Everything in these scripts goes through an Advanced Network Controller.
  Place the ANC next to a computer + wired modem, link each device with the
  Linking Tool (right-click device, then right-click ANC).

  For turret examples: toggle Computer Control ON in the turret's GUI
  first (right-click the turret with an empty hand).
