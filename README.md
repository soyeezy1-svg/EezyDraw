# EEZY DRAW

Native Android project for the tablet-first MEP coordination app described in [SPEC.md](SPEC.md). It includes vector branding/startup, a durable local project library, native project save/load/import/export, Settings and Help, plus a floating-origin 2D drawing canvas and orbit 3D views with wireframe, hidden-line, shaded and lit surfaces. Drawing tools, object selection, handle transforms, numeric Properties and session undo/redo are implemented; coordination engines and external CAD codecs remain future work.

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

Create a project with a name, template and display units. Office, Residential and Data center supply initial room footprints; Empty supplies a blank document. Creation, Continue, recent cards and native-file import open the drawing canvas. Its top bar offers Save, Export and Project details; details supports metadata/unit editing and Back returns to the same camera. Returning from a dirty project to the menu saves before leaving; failed saves keep the draft open. Continue and Recent Projects use the persisted catalogue and generated thumbnails.

The library is stored privately on this device using Room metadata and atomic, checked document revisions. Export makes a portable `.eezydraw` ZIP containing the document and its referenced attachments. Open from file validates and imports a separate local copy, never silently replacing an existing project. Keep exported copies before uninstalling the app, which removes the private library.

Settings persist across restarts. Units/precision/snaps/grid/service settings supply defaults for new projects; existing projects retain their own settings and changing display units never rescales geometry. Canvas units/snap changes are project edits and use normal save/autosave. Theme, stylus/touch mode, gesture sensitivity, autosave (zero means off), backup, save-folder behavior and the undo-history size work now; line weights, service placement, quality presets and drawing-export preferences await their respective engines. The selected save folder receives additional versioned archive copies; local storage remains available if the folder is disconnected or permission is revoked. External copies are not automatically pruned. Native archives remain complete; drawing-export visibility options do not remove attachments from project saves.

Autosave writes a snapshot while drawing remains editable. Successful autosaves are silent by default; Settings → Show autosave notification restores the success message when enabled. Save and folder-copy failures remain visible. Explicit Save still confirms success. A write in progress does not replace changes made after its snapshot, and serialized storage operations keep an explicit save/open/export from racing an autosave.

Backups retain the preceding valid saved revision when enabled. On a damaged primary, explicitly choose backup recovery; the app does not silently substitute an older project. Help explains current workflows, gesture/shortcut references, about/version and privacy. Feedback opens a share chooser only after explicit submission; it attaches no projects and sends nothing automatically.

## Canvas controls

| Action | Control |
| --- | --- |
| Show drawing/editing tools | Expand Tools; collapse it to recover drawing space |
| Place a sized shape | Choose circle, ellipse, rectangle, polygon, triangle or a 3D primitive; tap once to place the dimensions shown in Tools |
| Draw geometry by points | Enable Draw by points for a sized shape, or choose a freeform tool; Finish ends a polyline, polygon, spline or leader |
| Select an object | Select tool, then tap its visible outline or closed interior; polygon holes remain empty |
| Move or rotate | Drag the 52dp four-arrow grab handle to move freely, X/Y/Z arrows to constrain movement, or the separate Z-rotation ring. Move snaps are active; body dragging is optional in Settings |
| Resize one object | Base corners change width/depth; side handles change their matching width or depth; the separate top handle changes only height. Keep width/depth proportions locks the corner ratio |
| Enter exact values | Collapsible Properties side panel for one selected object: position X/Y/Z, rotation X/Y/Z in degrees, and local width/depth/height in project units |
| Duplicate or remove | Copy/Paste, Mirror X/Y, Array and Delete in Tools; Undo/Redo reverses committed drawing edits |
| Pan the plan | Drag the canvas with one finger; two-finger drag also pans |
| Zoom the plan | Pinch; the point under the gesture stays in place |
| Inspect coordinates/snaps | Hover, or enable Snap probe and drag; a stylus in Stylus mode probes without panning |
| Set a snap reference | Tap the plan; Clear reference removes it |
| Configure snaps | Snaps toolbar/status control; grid, endpoint, midpoint, intersection, perpendicular and ortho |
| Fit the drawing | Zoom extents fits geometry for the selected level |
| Fit a rectangle | Enable Zoom window, then drag the desired rectangle in 2D or 3D |
| Orbit in 3D | Switch to 3D and drag; two fingers pan/pinch, or select Pan for single-drag panning |
| Align the 3D view | Tap an oriented view-cube face or Top, Front, Side or Isometric; choose Perspective or Orthographic |
| View modes / visibility | View offers Wireframe, Hidden Line, Shaded, Shaded with Edges, X-ray and Realistic, plus layer/system checkboxes; choices persist with the project |
| Save a measurement | Measure → Measure distance: tap two points; the saved annotation shows distance, angles and coordinates |
| Dimension from reference | Pick an object, a wall corner/object/grid reference, then label position; PLAN offset and AFFL height are reported separately |
| Share the current view | View → PNG or PDF; choose a destination in the Android picker; visible dimensions are included and editor handles are omitted |
| Recover canvas space | Hide panels keeps the bottom status bar; Show panels restores toolbars and side panels |
| Change units/level | Bottom status bar; All levels includes every level, while a chosen level also includes unassigned geometry |

The plan includes an adaptive grid, vector rulers and a coordinate/snap readout in project units. The 3D status reports the camera target. Perpendicular and ortho need a reference: perpendicular finds the projection onto a finite edge, while ortho holds a horizontal/vertical construction axis. Grid spacing and snap tolerance remain physical metre values behind the chosen display units. Drawing taps use the configured snaps on the selected level's XY plane. Cameras, level/view choices and the reference survive Project details navigation and activity recreation; they are not yet saved into portable project views.

Tools include line, polyline, three-point arc, circle, ellipse, rectangle, polygon, triangle, spline, text, leader, aligned/angular/radial dimensions, box, sphere, cylinder, cone and pyramid. The ten closed/solid tools use one-tap placement by default. A box starts at 1×1×1m; circle/sphere/cylinder/cone diameter starts at 1m, so a sphere has a 0.5m radius. Other sized footprints start at 1×1m, with a 1m height for box/cylinder/cone/pyramid. Triangle is an isosceles footprint and polygon is a normalized six-sided footprint. All values appear in the current project units: 1m is 1,000mm, 100cm, approximately 39.37in or 3.28ft. Width is a diameter for radial tools, and sphere placement uses one diameter to keep it round. Numeric placement dimensions do not depend on zoom or pointer distance.

Draw by points retains the alternative construction sequences: rectangles and boxes use opposite corners; circles/spheres use a centre and radius point; an arc uses start, middle and end. Text has editable content/height. A leader starts at its arrow tip and ends at its text. Dimensions use two measurement anchors plus a label point, or vertex/two arms/label for angular dimensions. Box/cylinder/cone/pyramid height is entered in Tools before placing or drawing the base; Z and full XYZ rotations remain editable in Properties. Construction stays in an XY level plane, then primitives can be inspected and transformed in 3D. An empty plan opens with a useful 4m XY span, and first entry into 3D fits the current geometry rather than reusing a pre-placement empty extent.

Properties sizes and resize handles use the same physical extents along each object's local axes before rotation, including existing scale. A width/depth side holds the opposite side fixed; a base-corner drag holds its opposite corner fixed and changes width/depth without changing height. Keep width/depth proportions locks only that XY ratio. The top handle holds the base fixed and changes height without changing width/depth. Rotated and mirrored objects retain their local dimension directions and handedness; flat axes stay flat. Move/rotate and multiple-selection uniform scaling remain separate controls. Plain drawing-tool dimensions use fixed local anchors. Measure and Dimension from reference save optional object links, show separate 3D distance, PLAN offset, angles, coordinates and AFFL, and update after host edits. Saved measurements supports point/name/label editing, visibility and deletion. Text/annotations use the wireframe stroke alphabet, including a fixed 0.25m leader text height.

Copy/Paste uses a project-local in-app clipboard and creates new IDs. Array repeats the selection in rows/columns at project X/Y spacing and caps newly created objects at 1,000. Delete removes dependent host/shared-wall/group/issue links in the same reversible operation. Undo/redo retains changed records in memory, bounded by Settings and an auxiliary changed-record budget; it is cleared for a different editing session and is not written to project files. Save/autosave persists the resulting geometry.

Geometry, snapping and selection indexes are prepared off the UI thread. The GLES 2 surface uses separate immutable buffers for the drawing and selection, subtracts a Double camera origin before Float conversion, culls offscreen chunks, and renders on demand. Camera gestures reuse buffers. Handle drags update a full oriented linear transform uniform on the selected batch, including nonuniform dimension changes; chunk offsets and preview culling use the same transform in Double. Releasing commits one undoable edit and prepares the new scene. Nearby snap queries have bounded work, including intersection checks over the nearest 64 edges. These safeguards do not establish tablet frame rates or memory use; those remain part of device QA.

View modes use actual triangle faces with system/material/layer colours. Hidden Line uses depth occlusion; Shaded and Realistic add basic lighting; Shaded with Edges retains outlines; X-ray uses translucent faces. Project mode and visibility persist through save/reopen. PNG/PDF exports use the current camera and a bounded software colour/depth renderer; PDF embeds the same raster view on an A4 page. X-ray sorts ordinary batches and does not provide exact order-independent transparency for intersecting faces.

## Validation

All **300 tests pass**: model 11, geometry 66, editing 103, storage 12 and app 108. The debug APK builds, and app/storage debug lint reports have zero issues. See [the test report](docs/TEST_REPORT.md) for commands, results and device QA boundaries.

New regressions cover all six view modes, surface triangulation and holes, move snapping/body-drag preferences, associative measurements/AFFL, visibility persistence, nonmodal panel drafts/full-screen controls, and PNG/PDF output. The editing suite also covers all 19 drawing tools, invalid/degenerate picks, directed three-point arcs and millimetre details near billion-metre coordinates; transformed dimension values/units/angles; geometry-based picking and local Properties; reversible edits, reference cleanup, mirroring, arrays and history budgets; and Compose tools, numeric Properties, handle-only movement, multitouch cancellation and consecutive snapped shapes. Real Room/file/archive tests retain all shape variants and signed mirrored transforms through save/load, export/import and backup restoration.

Existing camera regressions include 1 mm extents, deep window zoom, large-coordinate precision, full-height first-entry 3D framing, and gesture anchoring. Project creation → canvas → Back → Continue is covered through actual app navigation with only the native surface substituted. These tests do not measure physical tablet FPS or Android EGL lifecycle behavior.

The suite covers model serialization/invariants; Room reopening and real file/archive/backup/failure paths; ViewModel/autosave/restoration; Compose startup, creation/details, Settings, Help and thumbnails; and camera, snap and scene conversion regressions. Geometry tests include large-coordinate floating origins, all model shape variants, scene-budget rejection and deterministic 100,000-segment spatial-query checks, including dense overlapping edges. Canvas Compose gesture/control tests use a substitute surface and inspect real camera/snap state rather than claiming native GPU output.

The production line/surface shaders pass the software Mesa GLES 2 smoke check for packed buffers, material colour, plan depth, hidden-line occlusion, Shaded/Realistic lighting, X-ray alpha, inverse-transpose normals and selected local resizing at distant coordinates, with no GL errors. On Linux with Python 3 and Mesa EGL/GLES 2 installed, run `python3 scripts/gles_shader_smoke.py`. Software GL checks do not establish Android surface lifecycle or physical-device frame rates. The manual QA below remains required.

## Manual QA plan

The following remain device/emulator checks, not claims of completed testing:

- Cold launch on API 26/35, disabled animations, rotation, theme/system bars, phone/tablet layouts, large text, RTL, and TalkBack.
- Create each template/unit choice, edit metadata, save, restart the process and reopen via Continue and Recent Projects; inspect regenerated previews.
- Confirm create/continue/recent/import open the canvas, Project details returns to the same camera, and rotation restores the active view/level/reference.
- On a tablet, check one-finger pan, two-finger pan/pinch, stylus hover/probe, gesture sensitivity and view-cube taps; try rapid direction changes and fingers joining/leaving a gesture.
- Open geometry with endpoints, midpoints, crossing lines and multiple levels; check all six snaps, disabled snapping, tolerance at different zooms, negative/large coordinates, perpendicular/ortho references, units and status values. Check global/unassigned geometry remains visible with a selected level.
- Check 2D/3D zoom extents/window, all view presets, perspective/orthographic framing, depth, grid/rulers and light/dark contrast. Confirm tiny window drags do not jump the camera.
- Place each sized tool in mm/cm/m/in/ft projects and confirm default one-metre footprints/heights, round spheres and consistent 2D/3D scale. Switch to Draw by points and create freeform geometry on multiple levels with snapping on/off, including clockwise/major arcs, open/closed polylines, irregular polygons, multi-line text and each primitive height. Cancel incomplete tools and reject duplicate/collinear picks without committing objects.
- Select outlines and closed interiors in plan and orbit views, including polygon holes, overlapping solids, hidden layers and locked objects. Confirm dragging the body leaves geometry unchanged, while move/rotation/resize handles show a live preview and commit only once; cancellation must restore the original. Check each width/depth side, all base corners with ratio lock on/off and the top height control; verify only their intended dimensions change, the opposite edge/base stays fixed, and Properties agrees for rotated/mirrored shapes.
- Apply numeric X/Y/Z, XYZ rotations and local dimensions in mm/cm/m/in/ft; check flat axes, mirrored objects, small values below display precision and invalid input. Check measured dimension labels after scaling and unit/angle changes.
- Copy/paste, mirror twice, create row/column arrays, delete linked objects, undo/redo repeatedly, then save/reopen. Verify geometry and references persist, clipboard/history stay session-local, and the configured history limit applies.
- On API 26/35, background/resume and rotate the native surface, force EGL context recreation where possible, reopen different projects, and check renderer errors are visible without damaging saved data.
- Profile a large imported drawing on target tablets: scene/index load time, pan/pinch frame-time percentiles, visible chunk count, GPU/CPU memory and repeated open/close/background cycles. Include dense overlaps and large coordinate offsets; report device/model/API and drawing size. No tablet FPS target has been verified in cloud tests.
- Export/open a native archive with attachments through real SAF providers; verify imported copies leave originals intact.
- Select a writable folder, save copies, revoke its grant or disconnect the provider, and verify the private save remains readable and a copy failure is reported.
- Compare autosave on/off while editing, backgrounding and returning. Verify successful autosaves produce no popup by default, the Show autosave notification preference persists and restores messages when enabled, editing remains available during a write, newer edits stay dirty, and save/folder-copy failures remain visible.
- Damage a primary in a controlled test installation and restore the previous backup; check disabled/no-backup cases and invalid archives.
- Save every Settings category, restart, and verify persistence and physical grid/service-height values across mm/cm/m/in/ft switches.
- Compare all six view modes in plan and orbit, including closed faces, holes, material/system colours, hidden edges, X-ray overlap and light/dark contrast. Toggle every layer/system and reopen the project.
- Measure two points and create a socket-to-wall reference. Check 300mm PLAN and 1200mm AFFL independently; move/delete/clone hosts, edit labels/endpoints, undo/redo, then save/reopen and export PNG/PDF from the current camera.
- Check the 52dp free-move handle, constrained axes and snapping. Verify default body drags navigate, opt-in body drags move one selection, and a second finger cancels a transform.
- Hide/show panels with an unfinished Properties draft, restore it, and check all touch targets with fingers, stylus and TalkBack.
- Review Help sections and feedback sharing, including cancellation and missing-handler behavior.

## Current limits

The supported UI language is English and the portable format is native `.eezydraw` v1. Archive limits are 32MiB compressed/expanded, 8MiB document JSON, 16MiB per attachment, and 256 entries. The wireframe viewer accepts at most 500,000 semantic/subdivided render edges and 16,384 spatial chunks, with a maximum single-edge axis span of 64,000,000m; oversized scenes display an error instead of truncating. Plan scale is bounded to `1e-6..1e7` pixels/metre. The virtual canvas has no fixed page boundary, but finite coordinate precision and scene/memory limits still apply.

Curves use up to 64 segments per turn, spline degree is capped at 32, hatches show boundaries, wireframe routes show centrelines and filled modes use their known cross-sections, and annotation previews use a limited stroke alphabet. New splines use degree up to three. Snaps operate in 2D on tessellated edges; dense-query budgets may omit a candidate. Picking uses actual outlines and supported closed interiors rather than accepting empty bounding boxes; filled orbit selection also ray-tests visible surfaces when analytic picking has no interior hit. Drawing placement is in level XY planes, Properties cannot add thickness to a flat shape, and handle rotation is about project Z. Single-object dimension handles follow local axes; a view-parallel axis or edge-on base plane hides controls that cannot be dragged reliably. Multiple-selection scaling remains uniform. Plain dimensions have fixed anchors; saved reference measurements follow their source objects and freeze endpoints when a referenced host is deleted. Revision-cloud/hatch/extrude/revolve creation, topology repair, saved-view management, underlays, cloud synchronization, coordination, a built-in component catalogue and DWG/DXF/PDF/IFC codecs remain future work. The tools panel collapses but does not yet dock or drag. Autosave runs during the app/ViewModel lifetime, not as a background sync service; unsaved drafts and undo history are not guaranteed to survive process death.
