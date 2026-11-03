You are a senior Android engineer and BIM/CAD software architect. Build a COMPLETE native Android app (not web, not PWA) called "EEZY DRAW": a tablet-first MEP coordination drawing app similar to Revit. Deliver a full Android Studio project (Kotlin, Jetpack Compose UI, OpenGL ES/Vulkan or Filament for the 2D/3D canvas, Room database, MVVM, Gradle files). Work in this order: (A) architecture + folder structure + data model, (B) full code file by file, (C) test suite, (D) build and run instructions. If output limits are hit, continue until every file is delivered; never use placeholders like "TODO" or "implement here".

1. BRANDING
- Logo like Revit's, with the "R" replaced by "E". App name: EEZY DRAW. Provide the vector logo (SVG/XML), adaptive launcher icon, and splash animation.

2. STARTUP FLOW
- Logo splash, then animated transition to Start Menu.
- Start Menu: Create New Project, Continue Project, Recent Projects with thumbnail previews. Also add: Open Project from file, Templates (office, residential, data center), Import DWG/PDF/image, Cloud/backup restore, Sample projects, Tutorials, Recent files search/sort, Rename/Duplicate/Delete/Share project.
- Top right: Settings and Help icons.
- Settings: save location, units (mm/cm/m/in/ft), angle format, decimal precision, language, theme (light/dark), autosave interval, backup, snap settings (grid, endpoint, midpoint, intersection, perpendicular, ortho), grid size, default line weights, default service heights, stylus/touch mode, gesture sensitivity, performance/quality, undo history size, export defaults, default templates, file naming, permissions.
- Help: user guide, gesture cheat sheet, shortcuts, about, version, contact/feedback.

3. DRAWING CANVAS
- Infinite canvas (floating-point-safe, large coordinate handling), grid, ruler, coordinate display, snapping, layers/levels.

4. LIBRARY
- Built-in categorized library (suggest and include a comprehensive list):
  Electrical: switches, sockets, DB/panels, lights, emergency lights, cable tray, ladder, trunking, conduit, junction boxes, isolators, earthing.
  ICT/Low current: 42U rack, patch panel, cable basket, CCTV, access control, fire alarm devices, PA, Wi-Fi AP.
  Mechanical/HVAC: ducts, VAV, FCU, AHU, diffusers, grilles, dampers, chillers.
  Plumbing/Fire: pipes, valves, pumps, sprinklers, hose reels, tanks, drains, fixtures.
  Architecture: walls, doors, windows, floors, ceilings, stairs, columns, furniture.
  Civil & Structure: beams, columns, slabs, footings, shear walls, openings.
- Each item has editable parameters (width, depth, height, material, system, colour, mounting height).
- User can edit existing items, save as new, create custom items, import/export libraries.
- Accessories/fittings for trunking, tray, conduit, duct, pipe: 45°, 90°, tee, cross, reducer, riser (up/down), flexible connector, coupler, end cap, bend, elbow, offset, saddle, support/hanger/bracket, expansion joint. Auto-insert fitting at bends and allow manual placement/editing.

5. SHAPES
- Line, polyline, arc, circle, ellipse, rectangle, polygon, triangle, spline, cloud (revision), text, leader, dimension (aligned/angular/radial), hatch, sphere, box, cylinder, cone, pyramid, extrude/revolve tools.

6. CAMERA
- Pan, zoom, pinch zoom, orbit, zoom extents, zoom window, look-around, walk-through, fly, ViewCube, saved views, top/front/side/isometric/perspective/orthographic, section box, cut plane.

7. ROOMS, WALLS, LEVELS
- Rectangular room: place and drag to resize. Irregular room (e.g. L-shape): tap points to define boundary. Live editable dimension labels beside each edge (tap to type a value).
- Auto-generate walls from the room boundary with thickness/height/material settings. Explain and implement the approach (wall centerline vs. finish face, join/cleanup).
- Adjacent rooms must share walls with NO gaps (shared edges, snapping, wall-join logic). Multi-level (Level 1, Level 2...) with floors stacked and aligned with no gap (level elevations, slab thickness, vertical alignment, level manager).

8. HIGH-LEVEL SERVICES ROUTING
- Per-service height setting (trunking, conduit, tray, duct, pipe) with elevation reference (AFFL/above ceiling). Draw route after setting height; edit route, add or change fittings (90°, 45°, risers), auto-fittings, sizing, system colours.

9. WALL-MOUNTED/EMBEDDED SERVICES
- Conduit routed on walls creates a visible chase/hack in the wall (boolean cut or visual groove). Same for switches, sockets, back boxes. Add other common wall services: data outlets, FCU/wall AC, isolators, DB, fire alarm devices, CCTV, thermostats, hose reel cabinets, pipe risers, sleeves/penetrations.

10. OVERLAY / TRACING
- Import DWG/DXF/PDF/image as underlay. Set scale (1:1, 1:10, 1:50, 1:100, custom or two-point calibration), move/rotate/lock/opacity, trace over it.

11. TRANSFORM GIZMO
- Selected item shows on-screen handles (move arrows, rotate ring/icon, scale). Drag handle to move/rotate (not by tapping the item itself). Also offer a separate Properties/Transform window with numeric X/Y/Z, rotation, dimensions.

12. VIEW MODES
- Wireframe, hidden line, shaded, realistic, transparency, section view, per-system visibility toggles, colour by system, X-ray.

13. UI
- Simple, clean, modern. Collapsible/dockable/draggable side panels, dropdown menus, hide/show toolbars, floating toolbar, bottom status bar (coords, snap, units, level), tablet and phone layouts, dark/light themes, stylus support.

14. COORDINATION FEATURES (add all)
- Clash detection (hard/soft/clearance) with clash list and jump-to-clash, markup/comments/issues, tags, schedules (quantities, cable/tray lengths), BOQ export, sheets and title blocks, print/PDF export, DWG/DXF/IFC export and import, measure tool, layers/systems manager, filters, undo/redo, copy/paste/array/mirror, groups, grid and level lines, project info, revision history, cloud sync/share, offline-first, multi-user comments.

15. TESTING (mandatory)
- Provide: unit tests (geometry, snapping, units, clash logic), UI tests (Compose/Espresso), instrumented tests, screenshot tests, performance tests for huge drawings, gesture tests, memory/leak checks, save/load/corruption tests, crash handling, and a manual QA checklist covering every feature above. Run through each feature, list found bugs, fix them, and re-test. Provide a test report.

DELIVERABLES: full source code, Gradle config, manifest, resources, test code, README with build/run steps (Android Studio, min SDK, APK/AAB build), and a known-limitations list. Ask me ONLY for missing critical decisions after delivering Part A.