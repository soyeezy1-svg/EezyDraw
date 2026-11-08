# EEZY Test Building

Open **Start Menu → Sample projects → EEZY Test Building**. Each launch creates and saves a separate editable project. The fixture uses stock project-carried library definitions and the existing room-wall, routing, wall/floor-service, issue and schedule APIs. It works offline and does not change the reusable device library. Its equipment metadata and USD unit rates are illustrative QA data. Fixture dimensions, systems and units are fixed for repeatable QA; device theme, input and bar preferences still apply.

## Building and reference locations

Coordinates below are project metres in the app's right-handed Z-up frame. Display units start in millimetres. The footprint is 24 × 18 m; finished floors are **Ground 0.0 m, Level 1 4.2 m, Level 2 8.4 m**. Slabs are 250 mm, generated shared walls 180 mm thick and 3.95 m high, and room ceilings 3.6 m above the floor.

Each level has nine rooms: Lobby, Office A, Office B, Corridor, Server room, Plant room, Toilets, Stair core and an L-shaped office. The corridor is at Y=6–8; the server/plant/toilet/stair rooms are north of it. The L-shaped office is along Y=14–18. Adjacent room edges reference the same physical walls, including split shared edges. The stair core has a slab opening and a stock stair flight.

| Area | Included examples |
| --- | --- |
| Architecture/structure | Shared generated walls, doors/windows with real host openings, columns, beams, slabs with stair openings, room text labels and saved 24 m building-width dimensions |
| Electrical | Wall sockets/switches, lights, distribution board, tray with tee/reducer, trunking with supports, ladder, concealed drops, back boxes and a diagonal chase |
| ICT | Two racks per floor, patch panel, wall data outlets, CCTV, Wi-Fi and basket routing; the basket has a rack connection |
| Fire | Smoke detectors, manual call point, sprinklers and sprinkler pipe |
| HVAC | AHU with a vertical duct connection, FCUs, supply diffusers, volume damper and duct branches |
| Plumbing | Water pipe, valves, WCs, basin and floor drain; inter-level water risers |
| Floor services | Conduit in screed, trunking/pipe in slab, tray in a raised-floor void, a floor box with a rise connection, and a hollow sleeve through each slab at approximately X=14.5, Y=12 |
| Coordination | Two deliberate hard clashes per floor; manual issues, capture-linked cloud/arrow/text markups, provided schedules, BOQ allowance/rates, saved views and hidden/locked examples |

The marked clashes are **duct through beam near (14.2, 7.5, floor+3.1)** and **pipe through tray near (7, 7, floor+2.7)**. Only these marked solids are used to seed results through the real clash engine. Ground results demonstrate Active and Approved status; other levels start New. A full-project re-check can report other contacts or clearances as well. Check settings, scope and connected interfaces before judging those additional results.

The **Deep diagonal wall chase**, on the Ground lobby south wall near X=6–7.5, has a deliberately excessive **110 mm depth** in a 180 mm wall, exceeding the default 50% warning threshold. Its source route and the recess/opening geometry remain ordinary editable objects. A separate issue points to this warning.

Columns are locked. **Hidden spare chair** is hidden on each floor, and a separate **QA reference** layer starts hidden. The Underfloor services, Issue pins and Issue markups layers use their existing visibility behavior. Saved views are **Ground plan**, **Building isometric**, **Ground underfloor X-ray** and **Plant section box**. The beam/duct issue stores a Ground plan capture and markups.

## Stress test and measurements

Open **Settings → Developer → Generate stress test**, choose **5, 10 or 20 levels**, then Generate. Save a changed Settings draft first. An edited active project is saved before generation, and the result is a new independent project. The generator repeats the complete floor program, rebuilds shared routing nodes/fittings and extends the inter-level risers. Model generation, meshing, clash seeding and quantities run off the UI thread; the ordinary working indicator shows generation progress.

The canvas switches to an all-level 3D wireframe view and runs a five-second, 90° orbit. Keep the application foregrounded. Stop benchmark cancels measurement without deleting the generated model. Results remain until explicitly closed and are session diagnostics, rather than fabricated project properties.

- **Generation and save:** elapsed monotonic time for project creation, model generation, save and any configured external-folder copy.
- **Storage load:** an actual repository reload, including deserialization/validation and catalogue updates.
- **First frame after load:** time from that reload's start to the first nonempty model frame submitted by the native renderer, including scene preparation and initial upload.
- **Rendered FPS:** successful GL draw callbacks during the timed orbit divided by the full measured duration. Slow frames count against the result; stationary redraw-on-demand time is not represented as a refresh rate. This is draw submission throughput, not a GPU completion/VRAM measurement.
- **Memory:** Java heap used, native heap allocation and OS process PSS after the run, in MiB. These are process-wide readings, not isolated model allocations or peak/GPU memory. Unsupported OS readings are shown as unavailable.

No native frame means FPS is unavailable, with an explanation; a headless test cannot invent a frame rate. Native startup/preparation has a bounded timeout. Compare runs on the same device, foreground state and thermal/battery conditions. Twenty-level all-system clash checks can exceed the existing triangle budget: use current-level/system scopes when requested by the engine.

## Manual QA checklist

Use a fresh sample copy for destructive checks, or undo each change. Record device/OS, orientation, build, count and results. Automated checks are described below; the device checklist is deliberately left unchecked until run on hardware.

### Open, navigate and present

- [ ] Open Sample projects, create the building, close and reopen it from Recent/Continue. Verify three levels, room names and a separate catalogue entry on a second launch. Change units to m/ft and confirm physical dimensions remain 24 × 18 m.
- [ ] Scroll every group and tool strip. Toggle groups, close/reopen and resize sheets, drag a sheet down to close, and tap empty canvas. Check no closed panel opens from selection or navigation. Test full screen/restore and hidden bar, then save/reopen its remembered state.
- [ ] Check Start Menu, Settings and Help in light/dark, phone/tablet and portrait/landscape. Try labels Show/Hide, left/bottom bar, long-press tooltips, XYZ visibility and safe areas with the keyboard open.
- [ ] Pinch, pan and twist the plan; use Pan/Orbit/Select, zoom window/in/out/extents/selection, numeric plan rotation and Reset north. Test sensitivity, invert orbit and finger/center zoom.
- [ ] Drag/tap the cube, its corners and compass; test six faces, Isometric/Home and perspective/orthographic. Use Look around and Walk; movement has no collision or gravity simulation.
- [ ] Restore all four saved views, create/name/restore/delete a new view, and adjust/disable all six section-box faces numerically and by dragging. Source dimensions must stay unchanged.
- [ ] Inspect all display modes, front-wall X-ray behavior, colours by system, and layer/system/level/object visibility. Reveal the hidden chairs and QA reference layer, then restore visibility. Try all-level and individual-level views.

### Draw, select and edit

- [ ] Place a rectangular and irregular room, including an L shape, with automatic walls on/off. Use Generate walls on a selected room. Change thickness/material/alignment/height; edit a shared room corner or dimension and verify shared walls follow without gaps.
- [ ] Chain standalone walls with precision Place/Cancel, then place a wall socket. On an empty level, verify the missing-wall message and Open Wall tool action.
- [ ] Exercise Line, Polyline, Arc, Circle, Ellipse, Rectangle, Polygon, Triangle, Spline, Text, Leader, Aligned/Angular/Radial dimensions, Box, Sphere, Cylinder, Cone and Pyramid in a spare area. Cancel unfinished work; check ghost previews, default dimensions and Draw by points.
- [ ] Use the precision crosshair, loupe, four nudges, handedness, snapping (grid/end/mid/intersection/perpendicular/ortho), reference probe and Clear reference. Turn precision off and repeat direct tapping. Check haptics/stylus on hardware.
- [ ] Select devices/routes/rooms and use the nearby toolbar and XYZ Move/Rotate/Scale handles. Test local numeric Properties, proportions, copy/paste, mirror X/Y, array, layer/colour and rotation snaps. Undo/redo and verify a single gesture is one edit.
- [ ] Try editing/deleting a locked column, position-only lock, full lock, layer/level locks and unlocking. Measure locked objects. Hide/X-ray/wireframe one room side and restore whole-room walls; visibility changes must preserve measurements.
- [ ] Edit/delete a saved building-width measurement; make a device-to-wall reference dimension and inspect mounting height. Hide/show dimensions, change precision/angle units and save/load.

### Library and services

- [ ] Search all library categories; use grid/list, folders, Favorites/Recently used. Insert, edit parameters, duplicate, restore/delete, create a custom item/from drawing, and import/export a library. Check manufacturer/model and illustrative rates in project definitions.
- [ ] Select tray, trunking, basket, ladder, conduit, duct and pipe sections in Route list. Change size, elevation/reference/system/colour and verify lengths/fitting counts. Extend routes with ortho/free and 45°/90° choices, add tees/crosses/reducers/end caps, and change height to make a riser.
- [ ] Drag a connected endpoint and resize a section; adjacent sections and fittings must update. Check the AHU/rack anchors, inter-level water risers and sections on adjacent levels. Replace/delete a fitting and add couplers/hangers/brackets/expansion accessories.
- [ ] Inspect trunking supports at 1.5 m spacing. Change spacing and count supports, then undo.
- [ ] Inspect surface and concealed wall routes in plan/elevation/3D. Use the diagonal chase to check 45°/90° fittings, mitred corners, tees and overlapping cuts. Move/edit/delete/undo a route or device and verify recess/feed/chase updates.
- [ ] Check the 110 mm chase warning, change the threshold/depth and compare the wall schedule. Toggle chases in Visibility, X-ray and section, then verify the wall stays 180 mm thick. Inspect door/window openings; place a duct/pipe wall penetration or sleeve from the library.
- [ ] Show underfloor services in plan (dashed), hide/show their layer, and inspect Ground underfloor X-ray with floor channels. Compare screed/slab/raised-floor depths, change a depth to trigger a warning and draw two crossing services at the same depth.
- [ ] Rise an underfloor conduit to a wall device and a floor box. Inspect each slab sleeve and water riser through the next slab. Move/delete a sleeve/route and undo; nominal slab thickness remains 250 mm. Inspect the floor schedule's sizes/depths/positions.

### Coordinate, quantify and exchange

- [ ] Open Clashes, inspect the two Ground targets with contrasting highlights/faded context and X-ray, clear focus, and filter/sort by type/system/level/status. Change status/note, undo/redo and save/load.
- [ ] Re-run All vs All, Electrical vs HVAC, Plumbing vs Structure, custom systems/categories, current/all levels and visible/all objects. Vary tolerance and per-system clearance. Duplicate an object to create a duplicate result; move a deliberate clash clear and verify Resolved/status preservation on rerun. Test background progress/cancel on a larger scoped run.
- [ ] Open the beam/duct and chase issues, pins and stored views. Create an issue from a point, selection and clash; edit title/priority/status/assignee/due date/position, search/filter/sort, lock/hide, undo/redo and save/load.
- [ ] Restore the captured view, draw all seven markup types with colour/thickness, use draft undo/redo, save a composited screenshot, and toggle the markup layer. Add actual camera/gallery photos on the device, remove/undo, then save/reopen/archive-import to verify attachments.
- [ ] Export the issue list as PDF/CSV and a single issue as PNG/PDF through the share sheet; inspect saved views, annotations and photos in the recipients.
- [ ] Build equipment, seven route-service, fittings, room and wall-cut schedules. Tap rows to highlight sources, filter levels/visibility/systems, configure grouping/columns/sort/totals and create a custom schedule. Compare lengths with 0% and 10% allowance and count fittings/supports once.
- [ ] Open BOQ, change a unit rate/total/currency, save/reopen and verify totals/unpriced counts. Try Also save to reusable library intentionally. Export CSV/XLSX/PDF with metadata, filters and totals; read them independently.
- [ ] Export current-view PNG/PDF, current-level and scaled A3/A1 PDF sheets with title/north/scale/dimensions/labels, and DXF with correct units/system layers/dashes. Underlay inclusion is relevant only after a supported source has been added through an implemented workflow.
- [ ] Export IFC4, OBJ, glTF, GLB and STL for project/current level/selection/visible scope, system/category options, units, up axis and origin placement. Compare a known 24 m width, levels/storeys, mounting properties and openings in an independent viewer; hidden objects must obey export scope.
- [ ] Save, close/reopen, export/import a native archive and restore a backup after an edit. Repeat with attached photos; verify links, visibility, locks, issues, views, rates and geometry. Exercise unsaved exit Save/Don't save/Cancel and autosave/error recovery on copies.
- [ ] Generate 5/10/20-level stress projects. Record generation/storage-load/first-frame/FPS/heap/PSS readings, Stop and rerun, verify independent project IDs and saved prior edits, and test backgrounding/foregrounding and low-memory behavior on hardware.

## Skipped or limited features

The fixture uses implemented features rather than introducing codecs or fabricated behavior:

- No live underlay import/render/tracing UI is implemented in this checkout; metadata/asset/export support does not constitute that workflow. No external drawing/image files are bundled. DWG/DXF/IFC model import and DWG export are not implemented; native project/library import is available.
- Standalone revision-cloud/hatch/extrude/revolve creation, arbitrary cut planes, general solid booleans, photoreal textures, automatic panel docking and door animation are not implemented. Issue revision clouds, static door/window library geometry, derived wall/floor cuts, material colours and axis-aligned section boxes are used instead.
- Cloud/multi-user sync, identity-backed assignment, revision-history browsing and persistent sheet editing/publishing are not implemented. Issues use typed names, session undo/redo, native backups and the existing scaled PDF/title-block export.
- Managed sized fitting definitions travel in native project archives. Standalone library backups follow the reusable catalogue identity rules; the reusable stock overrides are tested separately.
- No stock photos or fake camera assets are supplied. Camera/gallery/screenshots/attachment storage and sharing are implemented and belong in the device checklist above.
- Walk has no collision/gravity. Section-box cut faces remain open. Model/library quantities are the current engine's measured centrelines and source cuts, rather than fabrication estimates.

## Automated validation

Generator tests cover 3/5/10/20 levels, shared walls, unique references, cross-level route nodes, managed fittings/cuts, seeded clashes, views/issues/markups, rates, cancellation and scene limits. Real repository tests save/load, archive export/import and restore the sample backup. App tests launch the sample, choose stress levels, preserve an edited project, display metrics, and validate the GL frame counter without inventing native FPS. Export tests exercise every 3D/2D format and schedule/issue report format with independent readers where available.

**765 tests passed**, with zero failures, errors or skipped tests: model 54, geometry 140, editing 244, storage 32 and app 295. The debug APK built at `app/build/outputs/apk/debug/app-debug.apk`; app and storage debug lint both report zero issues.

Validation command (JDK 21 / Android SDK 35):

```sh
./gradlew --max-workers=4 --continue \
  :core:model:test :core:geometry:test :core:editing:test \
  :core:data:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug :core:data:lintDebug \
  -Pkotlin.incremental=false
```

`TestBuildingExportTest` writes fixtures to `/tmp/eezy-test-building-exports`. Independent validation passed using `scripts/validate_sample_exports.py`: IfcOpenShell accepted IFC4 schema/EXPRESS rules and represented-product geometry; trimesh/pygltflib read OBJ, STL, GLB and glTF; PyMuPDF read/rendered the plan (1 page), BOQ (25 pages) and issue report (2 pages); ezdxf audited the millimetre plan without errors; Pillow decoded both PNGs; openpyxl opened the workbook; CSV readers accepted the BOQ and saved-view issue rows. Reusable library stock overrides also round-trip through LibraryArchive; native project archives retain the complete managed fitting definitions.

The export fixture found and now guards a saved-view issue-report bug: immutable rendered bitmaps are copied before markup compositing. The generator and UI tests exercise actual background generation and a real repository/archive round trip; frame-counter tests use explicit synthetic clock events, not fabricated device measurements.

Physical-device frame rate and the unchecked device QA checklist are not claimed as completed by headless tests. Keep benchmarks foregrounded; background time invalidates comparisons and the run should be stopped and repeated.
