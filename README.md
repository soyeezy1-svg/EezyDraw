# EEZY DRAW

Native Android project for the tablet-first MEP coordination app described in [SPEC.md](SPEC.md). It includes vector branding/startup, a durable local project catalogue, a built-in multidisciplinary component and fittings library, native project save/load/import/export, Settings and Help, plus a floating-origin 2D drawing canvas and orbit 3D views with wireframe, hidden-line, shaded and lit surfaces. Drawing tools and connected service routing with shared precision pointing, object selection, handle transforms, numeric Properties and session undo/redo are implemented; coordination engines and external CAD codecs remain future work.

## Project

```text
EezyDraw/
├── app/                         Android application, Compose UI and GLES 2 renderer
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/eezydraw/
│       │   ├── MainActivity.kt
│       │   └── ui/              Startup, projects, Settings/Help, theme, canvas
│       └── res/                 Branding, splash/launcher icons, strings, backup policy
├── core/data/                   Room catalogue/preferences, revision files, native archives
├── core/editing/                Drawing factories, selection, transforms and sparse undo/redo
├── core/geometry/               Scene compilation, Double cameras, spatial index and snaps
├── core/model/                  Android-independent Kotlin/JVM module
│   └── src/
│       ├── main/kotlin/com/eezydraw/core/model/
│       │   ├── Ids.kt
│       │   ├── Geometry.kt
│       │   ├── Project.kt
│       │   ├── ProjectDocument.kt
│       │   ├── Elements.kt
│       │   ├── Library.kt
│       │   ├── BuiltInLibrary.kt
│       │   ├── ExpandedLibrary.kt
│       │   ├── Views.kt
│       │   ├── Coordination.kt
│       │   └── Settings.kt
│       └── test/kotlin/com/eezydraw/core/model/
├── docs/ARCHITECTURE.md          Module boundaries, canvas, storage and model conventions
├── gradle/                      Version catalog and Gradle wrapper
├── build.gradle.kts
└── settings.gradle.kts
```

Module dependencies are `:app → :core:data → :core:model`, `:app → :core:editing → :core:geometry → :core:model`, with `:app` also using geometry/model directly. See [architecture and data model](docs/ARCHITECTURE.md) for coordinate conventions and the editing/persistence boundaries.

## Open in Android Studio

Use Android Studio Meerkat or newer with Android Gradle Plugin 8.9 support. The project pins:

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 8.9.2 |
| Gradle wrapper | 8.13 |
| Kotlin / Compose compiler plugin | 2.1.20 |
| Compose BOM | 2025.04.01 |
| Compile / target SDK | Android 35 |
| Minimum SDK | Android 26 |
| JVM bytecode target | 17 |

1. Open this repository root as a project.
2. Select a JDK 17 or 21 installation for Gradle, such as Android Studio's bundled JDK. A JRE alone is insufficient.
3. Install Android SDK Platform 35 and Android SDK Build-Tools 35.0.0 through SDK Manager; accept the SDK licences.
4. Let Android Studio create the ignored `local.properties` SDK path, then sync Gradle.
5. Select the `app` run configuration and an Android 26+ device or emulator with OpenGL ES 2 support. The animated splash transitions to the Start Menu; create or continue a project to open its canvas.

No credentials, environment files, backend services, or broad filesystem runtime permissions are required. File/folder access uses Android's system picker. Internet access to the standard Google, Maven Central, and Gradle repositories is needed for initial dependency downloads. Do not commit `local.properties` or machine-specific SDK paths.

## Command line

From the repository root, with a full JDK on `JAVA_HOME` and the Android SDK configured through `ANDROID_HOME` or `local.properties`:

```sh
./gradlew :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

On Windows, use `gradlew.bat`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected device:

```sh
./gradlew :app:installDebug
```

Model/geometry/editing tests run on the JVM. Room/file/archive tests use real temporary storage under Robolectric. Compose tests cover startup, project/settings/help workflows and canvas controls/gestures; the canvas tests replace the native surface to isolate interaction state. Thumbnail tests decode real local PNG fixtures. These checks do not establish Android GPU rendering, physical tablet performance or external URI permission behavior.

## Project workflow

Create a project with a name, template and display units. Office, Residential and Data center supply initial room footprints; Empty supplies a blank document. Creation, Continue, recent cards and native-file import open the drawing canvas. View offers Save and current-view PNG/PDF export; the top bar offers native archive Export and Project details; details supports metadata/unit editing and Back returns to the same camera. Returning from a dirty project to the menu saves before leaving; failed saves keep the draft open. Continue and Recent Projects use the persisted catalogue and generated thumbnails.

The project catalogue is stored privately on this device using Room metadata and atomic, checked document revisions. Export makes a portable `.eezydraw` ZIP containing the document and its referenced attachments. Open from file validates and imports a separate local copy, never silently replacing an existing project. Keep exported copies before uninstalling the app, which removes the private library.

Settings persist across restarts. Units/precision/snaps/grid/service settings supply defaults for new projects; existing projects retain their own settings and changing display units never rescales geometry. Canvas units/snap changes are project edits and use normal save/autosave. Theme, stylus/touch mode, gesture sensitivity, autosave (zero means off), backup, save-folder behavior and the undo-history size work now. Move selected objects by dragging body, Show detailed measurement (angle and coordinates), Keep tool active after placing, Auto-hide panels while drawing, and Lock walls by default after generating them are all off by default. Precision pointer is on by default: Settings controls its offset distance/handedness, magnifier and zoom, grid-following or custom nudge step, and snap haptics. Turning it off restores direct-tap placement. Line-weight editing, route service-height defaults, quality presets and drawing-export preferences await their respective engines; library items use their own mounting defaults. The selected save folder receives additional versioned archive copies; local storage remains available if the folder is disconnected or permission is revoked. External copies are not automatically pruned. Native archives remain complete; drawing-export visibility options do not remove attachments from project saves.

Autosave writes a snapshot while drawing remains editable. Successful autosaves are silent by default; Settings → Show autosave notification restores the success message when enabled. Save and folder-copy failures remain visible. Explicit Save still confirms success. A write in progress does not replace changes made after its snapshot, and serialized storage operations keep an explicit save/open/export from racing an autosave.

Backups retain the preceding valid saved revision when enabled. On a damaged primary, explicitly choose backup recovery; the app does not silently substitute an older project. Help explains current workflows, gesture/shortcut references, about/version and privacy. Feedback opens a share chooser only after explicit submission; it attaches no projects and sends nothing automatically.

## Canvas controls

| Action | Control |
| --- | --- |
| Arrange tools and panels | Open Draw, Edit, View, Measure or Library; use each panel’s handle to collapse/expand or drag it. More → Panels can also show, collapse or hide panels |
| Browse/place library items | Library offers icon folders, search, Favorites, Recently used and grid/list views; choose an entry, aim the ghost and tap Place. The mounting height is shown during placement; successful placement returns to Select |
| Edit a library instance | Properties includes size, mounting height, material, colour, system, manufacturer, model and name tag. Apply affects one instance; Update all of this type in the project and Reset to library default are explicit options |
| Place a sized shape | Choose the tool, touch/drag to aim the offset crosshair, lift to keep the target, then tap Place; choosing a tool or lifting creates nothing |
| Aim a point | Precision pointer keeps the crosshair about 80dp above-left by default; it flips at canvas edges. Settings can choose above-right. A stylus targets its pen tip without the offset |
| Fine-adjust a point | Use the four nudge arrows, then Place; the step follows the project grid or a custom physical length in Settings |
| Inspect the target | While dragging, a 120dp loupe shows the crosshair area at 2×–4×. Snap markers/labels and optional haptics identify the chosen snap; live distance appears after a first point |
| Draw geometry by points | Enable Draw by points for a sized shape, or choose a freeform tool; aim and confirm each point with Place. Done ends a valid polyline, polygon, spline or leader; Cancel discards unfinished picks |
| Select an object | Select tool, then tap its visible outline or closed interior; empty canvas clears selection and polygon holes remain empty |
| Open selection actions | The floating toolbar has Move/Rotate, Copy, Delete, Properties and More; Properties opens only when requested |
| Move | In Move mode, drag the centre square/four-arrow handle along ground XY, or red X, green Y or blue Z arrows along one project axis. Targets are at least 48dp; snapping works in plan, front, side and orbit views |
| Rotate | Switch to Rotate for red X, green Y and blue Z bands. The angle appears during the drag; More → Rotation snap offers 1°, 5°, 15° (default), 45° or 90° |
| Resize one object | More → Scale reveals corner, W/D side and H top handles. Corners change width/depth, W/D changes its matching dimension, and H changes only height. More → Keep width/depth proportions locks the corner ratio |
| Enter exact values | Selection toolbar → Properties opens a collapsible panel for position X/Y/Z, Rotation X/Y/Z in degrees, and local width/depth/height in project units |
| Advanced edits / history | More contains Paste, Mirror X/Y, Array, layer, colour, Lock/Unlock, Display, room-wall and panel options, Rotate 90°, Undo and Redo; completed object edits are undoable |
| Lock objects | More → Lock defaults to Lock everything. Lock options also offers Lock position only, Unlock, and Lock all/Unlock all for the current level. A selected locked object shows a padlock |
| Change one object or room wall | More → Display offers Normal, Hide, X-ray or Wireframe. For a selected room, choose its wall side before changing Display, or use Hide this wall / Show all walls of this room |
| Pan the plan | In Select mode, drag the canvas with one finger; two-finger drag also pans |
| Zoom the plan | Pinch; the point under the gesture stays in place |
| Inspect coordinates/snaps | Hover, or enable Snap probe and drag; a stylus in Stylus mode probes without panning |
| Set a snap reference | Tap the plan; Clear reference removes it |
| Configure snaps | Snaps toolbar/status control; grid, endpoint, midpoint, intersection, perpendicular and ortho |
| Fit the drawing | Zoom extents fits geometry for the selected level |
| Fit a rectangle | Enable Zoom window, then drag the desired rectangle in 2D or 3D |
| Orbit / align the view | Drag the top-left view cube to orbit, tap a face for top/bottom/front/back/left/right, or tap a corner for isometric; cube navigation also opens the orbit view from plan |
| Reset the view | Home beside the cube restores the isometric orthographic view and fits the drawing; Zoom extents remains in View. Long press the cube for Plan 2D, Perspective or Orthographic. |
| View modes / visibility | View offers the six project display modes. Its visibility icon or More → Visibility opens level, room, wall, object, layer and system eye controls, Show all, faint hidden outlines and Auto X-ray front walls |
| Save the project | Save in View writes the local library; portable archive Export remains in the top bar/project details |
| Save a measurement | Measure → Measure distance: aim and confirm two points with Place; the saved annotation shows distance only by default |
| Dimension from reference | Aim and confirm an object, a wall corner/object/grid reference, then a label position; enable Show detailed measurement in Settings for PLAN offset, angles, coordinates and height above floor (AFFL) |
| Share the current view | View → PNG or PDF; choose a destination in the Android picker; visible dimensions are included and editor handles are omitted |
| Recover canvas space | The single floating full-screen toggle hides all panels while retaining the status bar and the same always-visible restore icon; restoring keeps each panel’s saved state and position |
| Change units/level | Bottom status bar; All levels includes every level, while a chosen level also includes unassigned geometry |

Panel states and positions change through their controls, not through selection, movement, rotation, measurement or placement. Properties stays where it was opened, including when selection becomes empty; it shows an instruction until another object is selected. Open, collapsed and hidden states plus normalized positions are stored with the project. A small per-project settings cache also restores the latest layout after an app restart when ordinary autosave is off. Layout changes have no document undo step. The default-off Auto-hide panels while drawing preference temporarily conceals other floating panels during active drawing/selection and leaves their saved state intact. Library is excluded from auto-hide: its explicit controls and the full-screen toggle alone change its visibility. Choosing, placing or cancelling an item never closes, reopens or expands it.

Lock everything blocks move, rotation, size edits and delete while retaining selection, snapping, measurement, Copy and readonly Properties. Properties explains “Object is locked. Unlock to edit.” Lock position only protects insertion X/Y/Z; numeric rotation/size and deletion remain available, and the single-object rotate/uniform-scale gizmo uses the fixed insertion point. Layer locks remain an additional edit restriction; when both layer and object are locked, More offers the layer unlock first and then the object unlock. The wall-generation preference applies to newly generated wall records, without changing existing records on open/import.

Display states change presentation without rewriting bodies, transforms, dimensions or measurement anchors. Hidden geometry is excluded from normal picking and snapping. Show hidden objects as faint outlines reveals it and permits selection/snapping so it can be restored. Room wall sides use stable outer/hole edge identities; linked wall records follow the room-side override, and shared physical walls have one effective displayed result. Show all walls of this room also clears conflicting overrides on references to those shared walls. Auto X-ray front walls fades prepared room sides toward the camera in 3D as it orbits; it leaves manual Hidden/Wireframe overrides intact. Lock/display/visibility changes use document undo/redo and short notices with Undo.

The plan includes an adaptive grid, vector rulers and a coordinate/snap readout in project units. The 3D status reports the camera target. Perpendicular and ortho need a reference: perpendicular finds the projection onto a finite edge, while ortho holds a horizontal/vertical construction axis. Grid spacing and snap tolerance remain physical metre values behind the chosen display units. Precision pointing resolves a canonical project point using the selected level and current plan/orbit camera; the ghost, loupe, readout and Place action use that same point. Existing tool factories retain their own planar and degeneracy rules. Cameras, level/view choices and the reference survive Project details navigation and activity recreation; they are not yet saved into portable project views.

Tools include line, polyline, three-point arc, circle, ellipse, rectangle, polygon, triangle, spline, text, leader, aligned/angular/radial dimensions, box, sphere, cylinder, cone and pyramid. Choosing a tool highlights it, shows a placement hint and Cancel/Done controls, and prepares a translucent preview that follows the pointer. With Precision pointer enabled, touching or dragging only aims; lifting leaves the crosshair fixed, and the large Place button confirms one point. Cancel clears the unfinished operation. A stylus uses its tip without a finger offset but still requires Place. Turning Precision pointer off restores canvas taps as confirmations. A completed placement briefly shows Placed, selects the new object and returns to Select; Keep tool active after placing enables repeated drawing-tool use instead. Library placement always returns to Select. The ten closed/solid tools need one confirmed placement point by default. A box starts at 1×1×1m; circle/sphere/cylinder/cone diameter starts at 1m, so a sphere has a 0.5m radius. Other sized footprints start at 1×1m, with a 1m height for box/cylinder/cone/pyramid. Triangle is an isosceles footprint and polygon is a normalized six-sided footprint. All values appear in the current project units: 1m is 1,000mm, 100cm, approximately 39.37in or 3.28ft. Width is a diameter for radial tools, and sphere placement uses one diameter to keep it round. Numeric placement dimensions do not depend on zoom or pointer distance.

Draw by points retains the alternative construction sequences: rectangles and boxes use opposite corners; circles/spheres use a centre and radius point; an arc uses start, middle and end. Text has editable content/height. A leader starts at its arrow tip and ends at its text. Dimensions use two measurement anchors plus a label point, or vertex/two arms/label for angular dimensions. Box/cylinder/cone/pyramid height is entered in Tools before placing or drawing the base; Z and full XYZ rotations remain editable in Properties. The selected level supplies the default construction floor. Precision picks can retain actual 3D snap elevations; individual tool factories still enforce their geometry rules, and no arbitrary workplane editor is implied. An empty plan opens with a useful 4m XY span, and first entry into 3D fits the current geometry rather than reusing a pre-placement empty extent.

The built-in component library includes the following categories. Every entry has nominal dimensions, a 2D vector symbol and a physical 3D shape.

| Category | Built-in items |
| --- | --- |
| Electrical | Single/double switches, dimmer, socket, floor box, isolator, distribution board, downlight/linear/emergency lights, exit sign, junction/pull boxes, tray, ladder, trunking, conduit, earth bar/rod |
| ICT / low current | 42U/24U/12U racks, patch panel, basket, data outlet, fibre box, Wi-Fi AP, CCTV camera, access reader, intercom, PA speaker, TV outlet |
| Fire alarm and safety | Smoke/heat detectors, manual call point, sounder, fire alarm panel, sprinkler, hose reel, extinguisher |
| Mechanical / HVAC | AHU, FCU, split AC indoor/outdoor units, VAV, exhaust fan, supply/return diffusers, grille, volume/fire dampers, rectangular/round/flexible ducts, chiller, pump |
| Plumbing and drainage | DN15–DN200 pipe stock, gate/ball/check valves, pump, tank, water heater, sink, basin, water closet, urinal, shower, floor drain, cleanout, manhole |
| Architecture | Single/double/sliding doors, single/double/fixed windows, stairs, ramp, column, ceiling, floor, desk, chair, table, cabinet, counter |
| Civil and structure | I/H/rectangular beams, square/round columns, slab, footing, shear wall, opening, sleeve |
| Fittings and accessories | Trunking, cable tray, cable ladder, conduit, duct and pipe connections and supports |

Visible rows and vector thumbnails are composed lazily, with no bitmap or asset downloads. Favorites, the last 24 unique used types and grid/list preference save with the project; browsing changes remain outside geometry undo history.

Fittings include 45° bends, horizontal/vertical inside/outside 90° bends, tees, crosses, reducers, up/down risers, offsets, couplers, end caps, flexible connectors, expansion joints, saddles, supports, hangers, brackets and trapezes; pipe also has elbows, unions and flanges. Choose a fitting in Library and aim near a compatible visible route endpoint. The preview aligns its input connection with the endpoint, matches the route's section dimensions and shows a connection marker before Place commits it. Cancel discards the preview, and successful placement returns to Select. Independent placement remains available away from compatible endpoints. Section dimensions and connection metadata persist with the component for later automatic insertion at route bends; automatic route drawing/insertion remains future work.

Floor items sit at the active level's finished floor. Wall items snap to the nearest eligible visible wall finish face, including implicit room sides, face outward and use the definition's mounting default (1,200mm AFFL for a single switch). Ceiling items attach to a containing room's ceiling or the level ceiling below the next slab and hang downward. The shared ghost and committed object resolve the same constraint, and the hint shows actual mounting AFFL in project units. Wall placement requires a face large enough for the item; invalid placement keeps the tool active with a message. Move/rotate remain available afterward; continuous host-following and wall recess/chase cuts are not implemented.

Open **Library → Manage library (⋮)** to create a custom item, create one **From selected drawing**, import/export a `.eezy-library` backup, or undo/redo a library edit. An item's ⋮ menu offers **Edit item**, **Duplicate**, **Restore default** for built-ins and **Delete item** for custom definitions. The editor's **Save as new item** keeps the original and saves a custom version. Edits are device-wide defaults; existing placed objects retain their project snapshots.

Custom items start from a box, cylinder, sphere or cone, or from shapes drawn and selected with the canvas tools. Closed 2D profiles become an extrusion; open drawings retain their strokes. Set dimensions in mm, name, category, colour, system, mounting type/height, and the plan symbol; optionally choose an icon/photo. Photos are copied into the library, so provider access is unnecessary after restart. **Free** mounting preserves pointer XYZ; floor/wall/ceiling use the existing mounting rules. Save persists immediately under app-private `files/library/`, independently of project saves, and survives app updates. Export a backup before uninstalling or clearing app data. Import merges by stable ID and can be undone with **Undo library edit**. Export is read-only; library history is session-only.

Library Properties changes size in both plan and 3D and edits mounting height, material, colour, system, manufacturer, model and name tag for that instance. Material/system names reuse or create real project records, so system visibility works for placed components. Update all of this type in the project changes matching instances together while preserving their X/Y positions and orientations and resolving height on each instance's floor; a locked match rejects the whole edit. Reset to library default restores only the selected instance. Placement, parameter edits, bulk changes and resets each use normal undo/redo, including any added definitions/materials/systems, and save/load/archive import preserves them. Existing projects and definitions load with backward-compatible defaults; Room/project schema and native archive format remain version 1.

Properties sizes and resize handles use the same physical extents along each object's local axes before rotation, including existing scale. A width/depth side holds the opposite side fixed; a base-corner drag holds its opposite corner fixed and changes width/depth without changing height. Keep width/depth proportions locks only that XY ratio. The top handle holds the base fixed and changes height without changing width/depth. Rotated and mirrored objects retain their local dimension directions and handedness; flat axes stay flat. Only the active Move, Rotate or explicitly requested Scale gizmo is shown, and camera orbiting hides it. Multiple-selection scaling remains uniform. Plain drawing-tool dimensions use fixed local anchors. Measure and Dimension from reference save optional object links and update after host edits. Their default readout is distance only; the detailed-measurement preference reveals PLAN offset, angles, coordinates and AFFL without discarding stored anchors. Saved measurements supports point/name/label editing, visibility and deletion. Text/annotations use the wireframe stroke alphabet, including a fixed 0.25m leader text height.

Copy/Paste uses a project-local in-app clipboard and creates new IDs. Array repeats the selection in rows/columns at project X/Y spacing and caps newly created objects at 1,000. Delete removes dependent host/shared-wall/group/issue links in the same reversible operation. Undo/redo covers geometry, object colour/layer/lock/display, room-side display, canvas project settings, level/layer/system visibility and library placement/instance/bulk/reset changes, including newly associated definitions, materials and systems. It retains changed records in memory, bounded by Settings and an auxiliary changed-record budget; it is cleared for a different editing session and is not written to project files. While a tool is unfinished, local undo/redo restores its confirmed draft points and nudge adjustments. A completed placement clears that draft history and becomes one normal document undo step. Camera navigation, panel layout and library browsing preferences do not create document history entries; settings undo preserves the current layout. Save/autosave persists the resulting document.

Geometry, snapping and selection indexes are prepared off the UI thread. The GLES 2 surface uses separate immutable buffers for the drawing and selection, subtracts a Double camera origin before Float conversion, culls offscreen chunks, and renders on demand. Camera gestures reuse buffers. Handle drags update a full oriented linear transform uniform on the selected batch, including nonuniform dimension changes; chunk offsets and preview culling use the same transform in Double. Releasing commits one undoable edit and prepares the new scene. Precision picks and loupe queries reuse prepared plan/3D indexes with bounded candidate work, including bounded intersection checks; they do not rebuild or scan the whole drawing for each finger move. These safeguards do not establish tablet frame rates or memory use; those remain part of device QA.

View modes use actual triangle faces with object overrides or system/material/layer colours. Hidden Line uses depth occlusion; Shaded and Realistic add basic lighting; Shaded with Edges retains outlines; X-ray uses translucent faces. Project mode and visibility persist through save/reopen. PNG/PDF exports use the current camera, measurement-detail preference and a bounded software colour/depth renderer; PDF embeds the same raster view on an A4 page. X-ray sorts ordinary batches and does not provide exact order-independent transparency for intersecting faces.

## Validation

The suite contains **669 passing tests**: model 53, geometry 110, editing 232, storage 27 and app 247. Debug APK and app/storage debug lint checks pass. See [the test report](docs/TEST_REPORT.md) for commands, results and device QA boundaries.

Precision-pointer regressions cover offset distance and edge flips, loupe placement/content, confirm-only placement, retained targets, nudge steps, snapping and Settings defaults/restoration. Existing regressions cover axis-isolated snapped movement/rotation, mode-exclusive selection controls, manual-only Properties, placement previews/reset/cancellation, distance-only measurement defaults, cube face/corner/drag/Home navigation and undoable style/settings changes. Existing regressions cover all six view modes, surface triangulation and holes, move snapping/body-drag preferences, associative measurements/AFFL, visibility persistence, nonmodal panel drafts/full-screen controls, and PNG/PDF output. The editing suite also covers all 19 drawing tools, invalid/degenerate picks, directed three-point arcs and millimetre details near billion-metre coordinates; transformed dimension values/units/angles; geometry-based picking and local Properties; reversible edits, reference cleanup, mirroring, arrays and history budgets; and Compose tools, numeric Properties, handle-only movement, multitouch cancellation and consecutive snapped shapes. Real Room/file/archive tests retain all shape variants and signed mirrored transforms through save/load, export/import and backup restoration.

Existing camera regressions include 1 mm extents, deep window zoom, large-coordinate precision, full-height first-entry 3D framing, and gesture anchoring. Project creation → canvas → Back → Continue is covered through actual app navigation with only the native surface substituted. These tests do not measure physical tablet FPS or Android EGL lifecycle behavior.

The suite covers model serialization/invariants; Room reopening and real file/archive/backup/failure paths; ViewModel/autosave/restoration; Compose startup, creation/details, Settings, Help and thumbnails; and camera, snap and scene conversion regressions. Geometry tests include large-coordinate floating origins, all model shape variants, scene-budget rejection and deterministic 100,000-segment spatial-query checks, including dense overlapping edges. Canvas Compose gesture/control tests use a substitute surface and inspect real camera/snap state rather than claiming native GPU output.

The production line/surface shaders pass the software Mesa GLES 2 smoke check for packed buffers, material colour, plan depth, hidden-line occlusion, Shaded/Realistic lighting, X-ray alpha, inverse-transpose normals and selected local resizing at distant coordinates, with no GL errors. On Linux with Python 3 and Mesa EGL/GLES 2 installed, run `python3 scripts/gles_shader_smoke.py`. Software GL checks do not establish Android surface lifecycle or physical-device frame rates. The manual QA below remains required.

## Manual QA plan

The following remain device/emulator checks, not claims of completed testing:

- Cold launch on API 26/35, disabled animations, rotation, theme/system bars, phone/tablet layouts, large text, RTL, and TalkBack.
- Create each template/unit choice, edit metadata, save, restart the process and reopen via Continue and Recent Projects; inspect regenerated previews.
- Confirm create/continue/recent/import open the canvas, Project details returns to the same camera, and rotation restores the active view/level/reference.
- On a tablet, check one-finger pan, two-finger pan/pinch, stylus hover/probe, gesture sensitivity, all six cube faces, all eight corners, cube dragging and Home; try rapid direction changes and fingers joining/leaving a gesture. Check the gizmo stays hidden during orbit and reappears afterward.
- Open geometry with endpoints, midpoints, crossing lines and multiple levels; check all six snaps, disabled snapping, tolerance at different zooms, negative/large coordinates, perpendicular/ortho references, units and status values. Check global/unassigned geometry remains visible with a selected level.
- Check 2D/3D zoom extents/window, cube-aligned and isometric framing, depth, grid/rulers and light/dark contrast. Confirm the old Top/Front/Side/Isometric/projection buttons are absent and tiny window drags do not jump the camera.
- Choose each drawing/measurement/library tool and confirm no object is created, the active highlight/hint appears, and a translucent preview tracks the precision crosshair. Drag with a finger, lift without placing, adjust again, and confirm each point only through Place; check that Cancel discards the draft. Place in mm/cm/m/in/ft projects and confirm the shape tools' default one-metre footprints/heights, round spheres, library definitions' nominal sizes and consistent 2D/3D scale. Completion should show Placed, select the result and undo in one step. Drawing tools return to Select by default; repeat with Keep tool active after placing enabled and verify Library still always returns to Select. Switch to Draw by points and check clockwise/major arcs, open/closed polylines, irregular polygons, multi-line text and primitive height. Cancel incomplete tools and reject duplicate/collinear picks without committing objects.
- Select outlines and closed interiors in plan and orbit views, including polygon holes, overlapping solids, hidden layers and locked objects. Check the five-icon selection toolbar, manual-only Properties, empty-space deselection and More actions. Confirm default body dragging navigates, and Move/Rotate/Scale gizmos are exclusive. Choose Scale explicitly, then check each width/depth side, all base corners with ratio lock on/off and the top height control; verify only intended dimensions change, the opposite edge/base stays fixed, and Properties agrees for rotated/mirrored shapes.
- Apply numeric X/Y/Z, XYZ rotations and local dimensions in mm/cm/m/in/ft; check flat axes, mirrored objects, small values below display precision and invalid input. Check measured dimension labels after scaling and unit/angle changes.
- Copy/paste, mirror twice, create row/column arrays, change layer/colour/lock, Rotate 90°, change canvas settings/visibility, delete linked objects and undo/redo repeatedly, then save/reopen. Verify geometry, styles and references persist, clipboard/history stay session-local, and the configured history limit applies.
- In precision mode, test every canvas edge/corner at different densities, above-left/right handedness, a retained target after lift, 56dp Place/Cancel and all nudge arrows. Check the 120dp loupe avoids the finger/target and follows 2×–4× zoom in plan/front/side/perspective, including filled modes. Test endpoint/midpoint/intersection/perpendicular/grid/wall-edge labels, haptic transitions on/off, and first-point live distances. Confirm stylus picks use the tip without an offset, direct-tap mode remains available, and both modes respect units and selected levels.
- On API 26/35, background/resume and rotate the native surface, force EGL context recreation where possible, reopen different projects, and check renderer errors are visible without damaging saved data.
- Profile a large imported drawing on target tablets: scene/index load time, pan/pinch frame-time percentiles, visible chunk count, GPU/CPU memory and repeated open/close/background cycles. Include dense overlaps and large coordinate offsets; report device/model/API and drawing size. No tablet FPS target has been verified in cloud tests.
- Export/open a native archive with attachments through real SAF providers; verify imported copies leave originals intact.
- Select a writable folder, save copies, revoke its grant or disconnect the provider, and verify the private save remains readable and a copy failure is reported.
- Compare autosave on/off while editing, backgrounding and returning. Verify successful autosaves produce no popup by default, the Show autosave notification preference persists and restores messages when enabled, editing remains available during a write, newer edits stay dirty, and save/folder-copy failures remain visible.
- Damage a primary in a controlled test installation and restore the previous backup; check disabled/no-backup cases and invalid archives.
- Save every Settings category, restart, and verify persistence and physical grid/service-height values across mm/cm/m/in/ft switches.
- Compare all six view modes in plan and orbit, including closed faces, holes, material/system colours, hidden edges, X-ray overlap and light/dark contrast. Toggle every layer/system and reopen the project.
- Measure two points and create a socket-to-wall reference. Confirm distance-only readouts and exports by default; enable detailed measurement and check 300mm PLAN, 1200mm AFFL, angles and coordinates independently. Move/delete/clone hosts, edit labels/endpoints, undo/redo, then save/reopen and export PNG/PDF from the current camera.
- Check the centre ground-XY move handle and all three coloured axes in plan, top, front, side and isometric views, including an axis pointing into the view. Confirm movement snaps while the other coordinates remain fixed. Rotate around X/Y/Z at each snap step, inspect the angle HUD and undo/redo each drag. Verify opt-in body dragging and second-finger cancellation.
- Open, collapse, hide and drag each panel, then select, move, rotate, measure and place objects without changing the saved layout. Verify full-screen restore, app/project reopen with autosave off, and the optional auto-hide preference. Keep an unfinished Properties draft through collapse and check finger, stylus and TalkBack targets.
- Lock everything and verify move/rotate/scale/delete are blocked while selection, measurement, snaps and readonly Properties work. Unlock and repeat; test position-only mode, per-level Lock all/Unlock all, generated-wall defaults and undo.
- Hide, X-ray and wireframe shapes, library items, rooms and individual outer/hole room walls. Verify hidden targets are excluded until faint outlines are enabled, Show all restores visibility, front-wall X-ray follows orbit without changing geometry, and save/reopen/export retains the chosen display.
- Edit built-in defaults and restore them; save as new, duplicate/delete custom types and undo/redo each action. Create base shapes and selected drawings, attach a photo, restart, export/import the library on another installation and verify dimensions, symbols, photos and mounting. Existing placed instances must retain their geometry after default edits or library deletion.
- Browse all library folders in grid/list views; search Wi-Fi/wifi, detectors, DN pipe stock and multiword queries, toggle favorites, place several types and verify recent order. Check each thumbnail, name and W/D/H, then load hundreds of project definitions and inspect scrolling/memory on the target tablet. Save/reopen/import and verify favorites, recents and view preference.
- Place fittings at both ends of rectangular and round routes, including rotated/mirrored routes and different levels; verify the connection marker, orientation, section-size matching and saved endpoint reference. Check incompatible or hidden targets, independent placement, Cancel, undo/redo, instance edits, bulk update/reset and save/reopen. Confirm bends, branches, reducers and supports retain distinct 2D/3D shapes.
- Place a switch on both sides of rotated/mirrored walls and implicit room walls, with hidden/linked/too-small faces and multiple levels. Check outward orientation, default 1,200mm AFFL, floor contact, room/level ceiling attachment, ghost/commit agreement, mounting-height readout, Cancel and Place. Edit every library parameter, resize in plan/3D, test system visibility and real material association, then bulk update/reset with locked instances and undo/redo/save/reopen. Verify Library never auto-hides/reappears, including when Auto-hide panels while drawing is enabled.
- Review Help sections and feedback sharing, including cancellation and missing-handler behavior.

## Current limits

The supported UI language is English and the portable format is native `.eezydraw` v1. Archive limits are 32MiB compressed/expanded, 8MiB document JSON, 16MiB per attachment, and 256 entries. The wireframe viewer accepts at most 500,000 semantic/subdivided render edges and 16,384 spatial chunks, with a maximum single-edge axis span of 64,000,000m; oversized scenes display an error instead of truncating. Plan scale is bounded to `1e-6..1e7` pixels/metre. The virtual canvas has no fixed page boundary, but finite coordinate precision and scene/memory limits still apply.

Curves use up to 64 segments per turn, spline degree is capped at 32, hatches show boundaries, wireframe routes show centrelines and filled modes use their known cross-sections, and annotation previews use a limited stroke alphabet. New splines use degree up to three. Plan snaps use tessellated XY edges; shared precision picks also query projected 3D edges and retain their canonical elevations. Axis moves constrain candidates and support physical grid increments on Z. Dense-query budgets may omit a candidate. Picking uses actual outlines and supported closed interiors rather than accepting empty bounding boxes; filled orbit selection also ray-tests visible surfaces when analytic picking has no interior hit. The active level supplies the default construction floor; each tool retains its planar validation, and Properties cannot add thickness to a flat shape. Move axes and rotation bands use screen-space fallbacks when viewed edge-on; single-object dimension handles follow local axes and may hide ambiguous edge-on base controls. Multiple-selection scaling remains uniform. Plain dimensions have fixed anchors; saved reference measurements follow their source objects and freeze endpoints when a referenced host is deleted. Revision-cloud/hatch/extrude/revolve creation, topology repair, saved-view management, underlays, cloud synchronization, coordination, DWG/DXF/PDF/IFC codecs remain future work. Panels can collapse, hide and move within the canvas; automatic docking is not implemented. Autosave runs during the app/ViewModel lifetime, not as a background sync service; unsaved drafts and undo history are not guaranteed to survive process death.

## Service routing

Open Draw and choose a service tool. Set its size, elevation/reference, system, colour, ortho and fitting preference in the routing bar (numeric lengths are millimetres). Aim and use Place to confirm each point; Done commits the route and Cancel discards the draft. With precision pointing disabled, taps confirm points directly. Changing elevation or level mid-route inserts a riser. Equipment face/port capture can add a final vertical leg. Auto fittings inserts sized bends, junctions, reducers and caps; 45° doglegs can form offset assemblies.

Select a section to move it with the ordinary gizmo, drag its A/B endpoints, or open Route properties. Connected endpoints and fittings follow in one undoable edit. More replaces/deletes a selected fitting or adds spaced supports, hangers, brackets, couplers and expansion joints. Expand Routes to select sections and review length, size, height and system. Settings supplies defaults for every service; the app remembers each tool's last configuration. See the connected-routing section of [ARCHITECTURE.md](docs/ARCHITECTURE.md) for topology, persistence, limits and equipment connection conventions.

See [routing validation](docs/ROUTING_TEST_REPORT.md) for current checks and device QA boundaries.

## Wall services

Conduit and trunking can use Surface mounted or Concealed (chase) from the routing bar. In 3D or an orthographic elevation, aim at the wall face, confirm points with Place and finish with Done. Snap angles offers 0° (horizontal), 45° (horizontal/vertical/diagonal), 90° (horizontal/vertical) and Free in the wall plane. Bends use the drawn angle and concealed chases follow diagonal sections with sharp mitred corners. Set clearance before drawing; concealed routes produce visible grooves while the wall's nominal dimensions stay unchanged.

Wall devices offer surface/recess/opening mounting, depth/clearance and a nearby-conduit or ceiling/floor feed choice. Switches, sockets and data outlets create back-box recesses by default. The Wall services library includes flush/surface boards, wall AC/thermostats/CCTV/fire equipment, cabinets, risers and sleeves/penetrations. Moving or deleting services updates their derived cuts through normal undo/redo and save/load.

Visibility can show/hide all wall cuts and open the Wall services schedule. The schedule lists source cuts, faces, sizes and positions, flags depths over 50% of nominal thickness by default, and controls the threshold and wall-section height. Section, X-ray and other views share the derived cut geometry. See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for host conventions, bounds and engineering/device QA limitations.

See [wall-service validation](docs/WALL_SERVICES_TEST_REPORT.md) and [room/wall validation](docs/WALL_CREATION_TEST_REPORT.md) for checks and device QA boundaries.

Draw includes **Room**, **Room polygon** and a chained **Wall** tool. Rooms create walls automatically by default; the Room tool and Settings can turn this off. Select a room for the visible **Generate walls** action. See [the quick room → walls → socket test](docs/WALL_CREATION_QUICK_TEST.md).


Floor installations are available for conduit, trunking, cable tray and pipe. Draw a **Floor slab**, then choose **Installation → In screed / In slab / Under floor (raised floor void)** in the route bar. Depths are below finished floor; plan routes are dashed. Visibility includes **Show underfloor services** and the **Floor services schedule**. Library provides recessed floor boxes, floor sockets and slab sleeves; select a route for **Rise to wall / Rise to floor box**. See [the underfloor validation and manual path](docs/UNDERFLOOR_TEST_REPORT.md).

Project → **Export → Export 3D model** saves IFC4, OBJ, embedded glTF, GLB or STL through the Android file picker. Choose project/level/selection/visible scope, system filters, units, categories and baked/separate cuts. See [3D-export validation](docs/MODEL_EXPORT_TEST_REPORT.md).
